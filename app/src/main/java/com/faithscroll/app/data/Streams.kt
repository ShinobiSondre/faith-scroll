package com.faithscroll.app.data

import android.content.Context
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

/** Direct media URLs for one video. */
data class ResolvedStream(
    val video: String?, // video-only track
    val audio: String?, // audio-only track
    val muxed: String?, // single file with both
    val hls: String?
)

/**
 * Finds the direct stream URLs behind a video so it can play in our own native player,
 * full screen, without the YouTube player or app. Results are cached for 4 hours
 * (stream links expire after ~6).
 */
object StreamResolver {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).build()
    @Volatile private var initialised = false
    private val cache = ConcurrentHashMap<String, Pair<Long, ResolvedStream>>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private const val TTL = 4 * 60 * 60 * 1000L

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

    private fun init() {
        if (initialised) return
        synchronized(this) {
            if (!initialised) { NewPipe.init(NpDownloader()); initialised = true }
        }
    }

    fun cached(id: String): ResolvedStream? =
        cache[id]?.takeIf { System.currentTimeMillis() - it.first < TTL }?.second

    suspend fun resolve(id: String): ResolvedStream? {
        cached(id)?.let { return it }
        val lock = locks.getOrPut(id) { Mutex() }
        return lock.withLock {
            cached(id) ?: withContext(Dispatchers.IO) {
                runCatching { extract(id) }.getOrNull()?.also { cache[id] = System.currentTimeMillis() to it }
            }
        }
    }

    private fun extract(id: String): ResolvedStream? {
        init()
        val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$id")

        val video = info.videoOnlyStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.MPEG_4 && it.height in 1..1080 }
            .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> {
                // H.264 plays on every phone; prefer it over AV1/VP9.
                if ((it.codec ?: "").startsWith("avc")) 1 else 0
            }.thenByDescending { it.height })
            .firstOrNull()?.content

        val audio = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.M4A }
            .maxByOrNull { it.averageBitrate }?.content

        val muxed = info.videoStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.isVideoOnly }
            .maxByOrNull { it.height }?.content

        val hls = info.hlsUrl?.takeIf { it.isNotBlank() }
        if (video == null && muxed == null && hls == null) return null
        return ResolvedStream(video, audio, muxed, hls)
    }

    @OptIn(UnstableApi::class)
    fun mediaSource(r: ResolvedStream): MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val chunked = DataSource.Factory { RangeParamDataSource(http) }
        val progressive = ProgressiveMediaSource.Factory(chunked)
        return when {
            r.video != null && r.audio != null -> MergingMediaSource(
                true, progressive.createMediaSource(MediaItem.fromUri(r.video)),
                progressive.createMediaSource(MediaItem.fromUri(r.audio))
            )
            r.muxed != null -> progressive.createMediaSource(MediaItem.fromUri(r.muxed))
            r.hls != null -> HlsMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(r.hls))
            else -> progressive.createMediaSource(MediaItem.fromUri(r.video!!))
        }
    }
}

/**
 * Video servers throttle long open-ended downloads, so this fetches the file in
 * 4 MB pieces using the "range" URL parameter, the way the official players do.
 */
@OptIn(UnstableApi::class)
private class RangeParamDataSource(private val upstream: DataSource.Factory) : BaseDataSource(true) {
    private val chunk = 4L * 1024 * 1024
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
