package com.faithscroll.app.data

import android.content.Context
import android.net.ConnectivityManager
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** One downloadable track. */
data class StreamOption(
    val url: String, val height: Int, val bitrate: Int, val avc: Boolean = true,
    val lang: String = "", // audio language ("" = unknown)
    val original: Boolean = true // false for YouTube's auto-dubbed / translated audio tracks
)

/** Every usable track for a video, so quality can be chosen at play time. */
data class ResolvedStream(
    val videos: List<StreamOption>, // video-only tracks
    val audios: List<StreamOption>, // audio-only tracks
    val muxed: List<StreamOption>, // single files with both
    val hls: String?,
    val description: String = "",
    val subtitles: List<SubTrack> = emptyList(),
    val spoken: String = "" // language actually spoken (from YouTube's automatic captions), if known
)

/** A caption track: language, whether YouTube generated it automatically, and where to get it. */
data class SubTrack(val lang: String, val auto: Boolean, val url: String)

/** Video quality: Auto (720p Wi-Fi, 480p mobile data, 360p with Data Saver), Data saver, or High. */
object VideoQuality {
    enum class Mode { AUTO, SAVER, HIGH }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("quality", Context.MODE_PRIVATE)
    fun mode(ctx: Context): Mode = runCatching { Mode.valueOf(prefs(ctx).getString("mode", "AUTO")!!) }.getOrDefault(Mode.AUTO)
    fun setMode(ctx: Context, m: Mode) = prefs(ctx).edit().putString("mode", m.name).apply()

    private fun cm(ctx: Context) = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    fun onMobileData(ctx: Context): Boolean = runCatching { cm(ctx).isActiveNetworkMetered }.getOrDefault(true)
    private fun systemDataSaver(ctx: Context): Boolean = runCatching {
        cm(ctx).restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }.getOrDefault(false)

    /** Highest video height to load right now. */
    fun targetHeight(ctx: Context): Int = when (mode(ctx)) {
        Mode.SAVER -> 360
        Mode.HIGH -> if (onMobileData(ctx)) 720 else 1080
        Mode.AUTO -> when {
            systemDataSaver(ctx) -> 360
            onMobileData(ctx) -> 480
            else -> 720
        }
    }

    /** How many videos ahead to get ready. */
    fun preloadDepth(ctx: Context): Int = when {
        targetHeight(ctx) <= 360 -> 1
        onMobileData(ctx) -> 2
        else -> 3
    }

    /** Bytes of each upcoming video to download in advance (a few seconds of playback). */
    fun preloadBytes(ctx: Context): Long = when (targetHeight(ctx)) {
        in 0..360 -> 500_000L
        in 361..480 -> 1_000_000L
        in 481..720 -> 1_800_000L
        else -> 3_000_000L
    }

    fun maxBitrate(height: Int): Int = when {
        height <= 360 -> 900_000
        height <= 480 -> 1_600_000
        height <= 720 -> 3_200_000
        else -> 6_500_000
    }
}

/**
 * Finds the direct stream URLs behind a video so it can play in our own native player,
 * full screen, without the YouTube player or app. Results are kept for 4 hours, also
 * across app restarts (stream links expire after ~6), and downloaded video is cached
 * on the phone so preloaded seconds and rewatches aren't fetched twice.
 */
object StreamResolver {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).build()
    @Volatile private var initialised = false
    private val cache = ConcurrentHashMap<String, Pair<Long, ResolvedStream>>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private const val TTL = 4 * 60 * 60 * 1000L
    private var diskFile: File? = null
    @Volatile private var media: SimpleCache? = null

    private class NpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val b = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body)
                .header("User-Agent", UA)
            request.headers().forEach { (name, values) ->
                b.removeHeader(name)
                values.forEach { b.addHeader(name, it) }
            }
            http.newCall(b.build()).execute().use { r ->
                if (r.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }

    /** Called once at app start. */
    @OptIn(UnstableApi::class)
    fun setup(ctx: Context) {
        val app = ctx.applicationContext
        if (media == null) synchronized(this) {
            if (media == null) media = SimpleCache(
                File(app.cacheDir, "media"),
                LeastRecentlyUsedCacheEvictor(300L * 1024 * 1024),
                StandaloneDatabaseProvider(app)
            )
        }
        if (diskFile == null) {
            diskFile = File(app.filesDir, "streams_v4.json")
            runCatching { loadDisk() }
        }
    }

    @OptIn(UnstableApi::class)
    fun cacheSizeMb(): Long = (media?.cacheSpace ?: 0L) / (1024 * 1024)

    fun ensureInit() = init()

    private fun init() {
        if (initialised) return
        synchronized(this) {
            if (!initialised) { NewPipe.init(NpDownloader()); initialised = true }
        }
    }

    fun cached(id: String): ResolvedStream? =
        cache[id]?.takeIf { System.currentTimeMillis() - it.first < TTL }?.second

    /** Videos that already have a direct stream (Reddit) skip the lookup. */
    private val direct = ConcurrentHashMap<String, String>()
    fun registerDirect(id: String, url: String) { if (url.isNotBlank()) direct[id] = url }

    suspend fun resolve(id: String): ResolvedStream? {
        direct[id]?.let { u ->
            return if (u.contains(".m3u8")) ResolvedStream(emptyList(), emptyList(), emptyList(), u)
            else ResolvedStream(emptyList(), emptyList(), listOf(StreamOption(u, 720, 0)), null)
        }
        cached(id)?.let { return it }
        val lock = locks.getOrPut(id) { Mutex() }
        return lock.withLock {
            cached(id) ?: withContext(Dispatchers.IO) {
                runCatching { extract(id) }.onFailure { Diag.err("stream", "resolve $id failed", it) }.getOrNull()?.also {
                    cache[id] = System.currentTimeMillis() to it
                    runCatching { saveDisk() }
                }
            }
        }
    }

    private fun extract(id: String): ResolvedStream? {
        init()
        val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$id")
        val videos = info.videoOnlyStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.MPEG_4 && it.height > 0 }
            .map { StreamOption(it.content, it.height, it.bitrate, (it.codec ?: "avc").startsWith("avc")) }
        val audios = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.M4A }
            .map { a ->
                // YouTube now adds auto-dubbed tracks in many languages; remember which one is the original.
                val type = runCatching { a.audioTrackType }.getOrNull()
                val lang = runCatching { a.audioLocale?.language }.getOrNull().orEmpty().lowercase().let { if (it == "nb" || it == "nn") "no" else it }
                StreamOption(a.content, 0, a.averageBitrate, lang = lang,
                    original = type == null || type == org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL)
            }
        val muxed = info.videoStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.isVideoOnly }
            .map { StreamOption(it.content, it.height, it.bitrate) }
        val hls = info.hlsUrl?.takeIf { it.isNotBlank() }
        if (videos.isEmpty() && muxed.isEmpty() && hls == null) return null
        val desc = runCatching { info.description?.content.orEmpty() }.getOrDefault("")
            .replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "").take(3000)
        val subs = runCatching {
            info.subtitles.filter { it.isUrl && it.format == MediaFormat.VTT }
                .map { SubTrack(it.languageTag ?: "", it.isAutoGenerated, it.content) }
        }.getOrDefault(emptyList())
        // YouTube's automatic captions are made from the audio, so their language is the spoken one.
        val spoken = audios.firstOrNull { it.original && it.lang.isNotBlank() }?.lang
            ?: subs.firstOrNull { it.auto }?.lang?.substringBefore('-')?.lowercase()
            ?: runCatching { info.languageInfo?.language }.getOrNull().orEmpty()
        Diag.log("stream", "$id: ${videos.size}v ${audios.size}a ${muxed.size}m, subs=${subs.joinToString { (if (it.auto) "auto:" else "") + it.lang }}, spoken=$spoken")
        return ResolvedStream(videos, audios, muxed, hls, desc, subs, spoken)
    }

    // ---- Quality choice ----

    /** The tracks to play: (video, audio) or (single file, null) or (null, null) for HLS. */
    data class Choice(val video: StreamOption?, val audio: StreamOption?, val single: StreamOption?, val hls: String?)

    /**
     * The audio to play: a dub in the app language if YouTube has one (e.g. Norwegian), otherwise
     * the original track. Never a random auto-dub in another language.
     */
    fun preferredAudio(r: ResolvedStream): List<StreamOption> {
        val want = if (com.faithscroll.app.util.Lang.no) "no" else "en"
        return r.audios.filter { it.lang == want }.ifEmpty { r.audios.filter { it.original } }
            .ifEmpty { r.audios.filter { it.lang == "en" } }.ifEmpty { r.audios }
    }

    fun choose(r: ResolvedStream, target: Int): Choice {
        if (r.hls != null && r.videos.isEmpty() && r.muxed.isEmpty()) return Choice(null, null, null, r.hls)
        // At low quality one combined file starts fastest (one connection instead of two).
        if (target <= 360) r.muxed.filter { it.height <= target }.maxByOrNull { it.height }?.let { return Choice(null, null, it, null) }
        val fit = r.videos.filter { it.height <= target }
        val video = (fit.ifEmpty { listOfNotNull(r.videos.minByOrNull { it.height }) })
            .sortedWith(compareByDescending<StreamOption> { it.height }.thenByDescending { if (it.avc) 1 else 0 })
            .firstOrNull()
        val tracks = preferredAudio(r)
        val audio = if (target <= 480) tracks.minByOrNull { it.bitrate } else
            tracks.filter { it.bitrate <= 160 }.maxByOrNull { it.bitrate } ?: tracks.maxByOrNull { it.bitrate }
        if (video != null && audio != null) return Choice(video, audio, null, null)
        r.muxed.maxByOrNull { it.height }?.let { return Choice(null, null, it, null) }
        return Choice(null, null, null, r.hls)
    }

    @OptIn(UnstableApi::class)
    private fun httpFactory() = DefaultHttpDataSource.Factory()
        .setUserAgent(UA)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)

    /** Reads through the on-phone cache first; fetches the rest in growing pieces. */
    @OptIn(UnstableApi::class)
    private fun cachedFactory(): DataSource.Factory {
        val chunked = DataSource.Factory { RangeParamDataSource(httpFactory()) }
        val c = media ?: return chunked
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(chunked)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun item(url: String, key: String) = MediaItem.Builder().setUri(url).setCustomCacheKey(key).build()

    @OptIn(UnstableApi::class)
    fun mediaSource(id: String, r: ResolvedStream, target: Int): MediaSource {
        val c = choose(r, target)
        val progressive = ProgressiveMediaSource.Factory(cachedFactory())
        return when {
            c.video != null && c.audio != null -> MergingMediaSource(
                true,
                progressive.createMediaSource(item(c.video.url, "$id-v${c.video.height}")),
                progressive.createMediaSource(item(c.audio.url, "$id-a${c.audio.bitrate}"))
            )
            c.single != null -> progressive.createMediaSource(item(c.single.url, "$id-m${c.single.height}"))
            else -> HlsMediaSource.Factory(httpFactory()).createMediaSource(MediaItem.fromUri(c.hls ?: r.hls!!))
        }
    }

    /** Downloads the first seconds of a video into the cache so it starts instantly later. */
    @OptIn(UnstableApi::class)
    suspend fun preload(ctx: Context, id: String) = withContext(Dispatchers.IO) {
        val r = resolve(id) ?: return@withContext
        val c = choose(r, VideoQuality.targetHeight(ctx))
        val bytes = VideoQuality.preloadBytes(ctx)
        val jobs = listOfNotNull(
            c.video?.let { Triple(it.url, "$id-v${it.height}", bytes) },
            c.audio?.let { Triple(it.url, "$id-a${it.bitrate}", bytes / 6) },
            c.single?.let { Triple(it.url, "$id-m${it.height}", bytes) }
        )
        for ((url, key, len) in jobs) {
            if (!isActive) return@withContext
            val ds = cachedFactory().createDataSource() as? CacheDataSource ?: return@withContext
            val spec = DataSpec.Builder().setUri(url).setPosition(0).setLength(len).setKey(key).build()
            val writer = CacheWriter(ds, spec, null, null)
            val job = coroutineContext[Job]
            val handle = job?.invokeOnCompletion { writer.cancel() }
            try { writer.cache() } catch (_: Exception) { } finally { handle?.dispose() }
        }
    }

    // ---- Remember stream links across restarts ----

    private fun optJson(o: StreamOption) = JSONObject().put("u", o.url).put("h", o.height).put("b", o.bitrate).put("a", o.avc)
        .put("l", o.lang).put("o", o.original)
    private fun optFrom(o: JSONObject) = StreamOption(o.getString("u"), o.optInt("h"), o.optInt("b"), o.optBoolean("a", true),
        o.optString("l"), o.optBoolean("o", true))

    @Synchronized private fun saveDisk() {
        val f = diskFile ?: return
        val now = System.currentTimeMillis()
        val root = JSONObject()
        cache.entries.filter { now - it.value.first < TTL }.forEach { (id, v) ->
            val r = v.second
            root.put(id, JSONObject().put("t", v.first)
                .put("v", JSONArray().apply { r.videos.forEach { put(optJson(it)) } })
                .put("a", JSONArray().apply { r.audios.forEach { put(optJson(it)) } })
                .put("m", JSONArray().apply { r.muxed.forEach { put(optJson(it)) } })
                .put("h", r.hls ?: "")
                .put("d", r.description)
                .put("sp", r.spoken)
                .put("s", JSONArray().apply { r.subtitles.forEach { put(JSONObject().put("l", it.lang).put("a", it.auto).put("u", it.url)) } }))
        }
        f.writeText(root.toString())
    }

    private fun loadDisk() {
        val f = diskFile ?: return
        if (!f.exists()) return
        val root = JSONObject(f.readText())
        val now = System.currentTimeMillis()
        root.keys().forEach { id ->
            val o = root.getJSONObject(id)
            val t = o.getLong("t")
            if (now - t < TTL) {
                fun list(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { optFrom(a.getJSONObject(it)) } }
                val subs = o.optJSONArray("s")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { j -> SubTrack(j.optString("l"), j.optBoolean("a"), j.optString("u")) } } }.orEmpty()
                cache[id] = t to ResolvedStream(list("v"), list("a"), list("m"), o.optString("h").ifBlank { null }, o.optString("d"), subs, o.optString("sp"))
            }
        }
    }
}

/**
 * Video servers throttle long open-ended downloads, so this fetches the file in
 * 4 MB pieces using the "range" URL parameter, the way the official players do.
 */
@OptIn(UnstableApi::class)
private class RangeParamDataSource(private val upstream: DataSource.Factory) : BaseDataSource(true) {
    // Start small so the first frame arrives fast, then grow to fewer, bigger requests.
    private var chunk = 512L * 1024
    private val maxChunk = 4L * 1024 * 1024
    private var spec: DataSpec? = null
    private var current: DataSource? = null
    private var pos = 0L
    private var end = Long.MAX_VALUE // exclusive end of what was asked for
    private var chunkLeft = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        spec = dataSpec
        pos = dataSpec.position
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        end = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> pos + dataSpec.length
            total != null -> total
            else -> Long.MAX_VALUE
        }
        opened = true
        transferStarted(dataSpec)
        if (pos >= end) return 0
        openChunk()
        return if (end == Long.MAX_VALUE) C.LENGTH_UNSET.toLong() else end - pos
    }

    private fun openChunk() {
        val s = spec!!
        val last = min(end, pos + chunk) - 1
        val uri = s.uri.buildUpon().appendQueryParameter("range", "$pos-$last").build()
        val ds = upstream.createDataSource()
        try {
            ds.open(DataSpec.Builder().setUri(uri).setHttpRequestHeaders(s.httpRequestHeaders).build())
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            ds.close()
            if (e.responseCode == 416) { end = pos; current = null; return } // past the end
            throw e
        }
        current = ds
        chunkLeft = last - pos + 1
        chunk = minOf(maxChunk, chunk * 2)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (pos >= end) return C.RESULT_END_OF_INPUT
            val ds = current ?: return C.RESULT_END_OF_INPUT
            val n = ds.read(buffer, offset, min(length.toLong(), chunkLeft).toInt())
            if (n == C.RESULT_END_OF_INPUT) {
                ds.close(); current = null
                if (chunkLeft > 0) { end = pos; return C.RESULT_END_OF_INPUT } // file ended early
                openChunk()
                continue
            }
            pos += n
            chunkLeft -= n
            bytesTransferred(n)
            if (chunkLeft == 0L) { ds.close(); current = null; if (pos < end) openChunk() }
            return n
        }
    }

    override fun getUri(): Uri? = spec?.uri

    override fun close() {
        try { current?.close() } finally {
            current = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}
