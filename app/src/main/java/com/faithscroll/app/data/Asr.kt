package com.faithscroll.app.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Makes subtitles for any video by listening to it on the phone: Vosk for English, NB-Whisper for
 * Norwegian (both offline after a one-time download).
 *
 * It follows the playhead instead of working through the whole video: the timeline is cut into
 * 6-second pieces, and only the pieces around where you are (one behind, ~30 s ahead) are
 * transcribed – nearest first, two at a time in parallel. Audio is read straight from YouTube at
 * that spot, so it can start anywhere instantly; jumping elsewhere moves the work with you.
 */
object Asr {
    // GitHub copy first (gets past ad-blocking DNS), the original site as backup.
    private val MODEL_URLS = listOf(
        "https://github.com/ShinobiSondre/faith-scroll/releases/download/models/vosk-model-small-en-us-0.15.zip",
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    )
    private const val MODEL_DIR = "vosk-model-small-en-us-0.15"
    private const val RATE = 16000

    enum class State { IDLE, DOWNLOADING_MODEL, LOADING_AUDIO, LISTENING, DONE, FAILED }

    private const val PIECE = 4_000L // short pieces: the first subtitle is ready sooner

    /** Live captions for one video, growing as pieces are transcribed. */
    class Live {
        val cues = MutableStateFlow<List<Cue>>(emptyList())
        val state = MutableStateFlow(State.IDLE)
        @Volatile var error: String? = null
        @Volatile var focus = 0L          // where you are
        @Volatile var until = 0L          // how far ahead is wanted
        @Volatile var durationMs = Long.MAX_VALUE
        val done = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
        val busy = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
        val workers = mutableListOf<Job>()
        @Volatile var source: String? = null // audio URL (or local file path)
        @Volatile var allowDownload = false
        val readers = java.util.concurrent.ConcurrentLinkedQueue<AudioReader>() // open audio connections, reused // fall back to downloading the whole audio (only when you ask)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lives = ConcurrentHashMap<String, Live>()
    private val modelLock = Mutex()
    @Volatile private var model: Model? = null
    val modelProgress = MutableStateFlow(0f)
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    fun live(videoId: String): Live = lives.getOrPut(videoId) { Live() }

    /** Downloads/loads the speech model ahead of time. */
    /** Loads the speech model for the app's language in the background at start-up, so the first subtitle doesn't wait for it. */
    fun prepare(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            val t0 = System.currentTimeMillis()
            if (com.faithscroll.app.util.Lang.no) runCatching { Whisper.load(app) }.onFailure { Diag.err("asr", "Norwegian model", it) }
            else runCatching { model(app) }.onFailure { Diag.err("asr", "speech model", it) }
            Diag.log("asr", "speech model loaded at start-up in ${(System.currentTimeMillis() - t0) / 1000.0} s")
        }
    }
    fun cues(videoId: String): StateFlow<List<Cue>> = live(videoId).cues

    fun modelReady(ctx: Context) = File(ctx.filesDir, "vosk/$MODEL_DIR").isDirectory

    private suspend fun model(ctx: Context): Model = modelLock.withLock {
        model ?: withContext(Dispatchers.IO) {
            val dir = File(ctx.filesDir, "vosk/$MODEL_DIR")
            if (!dir.isDirectory) downloadModel(ctx)
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Model(dir.absolutePath)
        }.also { model = it }
    }

    private fun downloadModel(ctx: Context) {
        val root = File(ctx.filesDir, "vosk").apply { mkdirs() }
        val tmp = File(root, "model.zip")
        var lastError: Throwable? = null
        val ok = MODEL_URLS.any { url ->
            runCatching { downloadFrom(url, tmp); Diag.log("asr", "speech model from ${android.net.Uri.parse(url).host}"); true }
                .getOrElse { lastError = it; Diag.err("asr", "speech model from ${android.net.Uri.parse(url).host}", it); false }
        }
        if (!ok) throw lastError ?: IllegalStateException("Model download failed")
        unzipModel(tmp, root)
    }

    private fun downloadFrom(url: String, tmp: File) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("Model download failed: HTTP ${r.code}")
            val total = r.body!!.contentLength().coerceAtLeast(1)
            var got = 0L
            r.body!!.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        val p = got.toFloat() / total
                        if ((p * 4).toInt() > (modelProgress.value * 4).toInt()) Diag.log("asr", "speech model ${(p * 100).toInt()} %")
                        modelProgress.value = p
                    }
                }
            }
        }
    }

    private fun unzipModel(tmp: File, root: File) {
        ZipInputStream(tmp.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val f = File(root, e.name)
                if (!f.canonicalPath.startsWith(root.canonicalPath)) continue // safety: no paths outside the folder
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
            }
        }
        tmp.delete()
    }

    /**
     * Wants subtitles from [fromMs] onwards (up to [maxMs], at most ~30 s ahead) plus a little
     * before it. Starts the parallel workers if they aren't running.
     */
    fun ensure(ctx: Context, videoId: String, fromMs: Long, maxMs: Long = Long.MAX_VALUE, allowDownload: Boolean = false) {
        val live = live(videoId)
        if (allowDownload) live.allowDownload = true
        live.focus = fromMs
        live.until = minOf(maxMs, fromMs + 30_000)
        // Only one video at a time: stop the others and free their connections.
        lives.forEach { (id, other) -> if (id != videoId && (other.workers.any { it.isActive } || other.readers.isNotEmpty())) release(other) }
        synchronized(live) {
            live.workers.removeAll { !it.isActive }
            val app = ctx.applicationContext
            while (live.workers.size < Whisper.WORKERS && nextPiece(live, peek = true) != null) {
                live.workers += scope.launch { worker(app, videoId, live) }
            }
        }
    }

    fun stop(videoId: String) { lives[videoId]?.let(::release) }

    private fun release(live: Live) {
        live.workers.forEach { it.cancel() }
        scope.launch(Dispatchers.IO) { while (true) { val r = live.readers.poll() ?: break; runCatching { r.close() } } }
    }

    /** The most useful piece not done yet: where you are, then ahead, then just behind. */
    private fun nextPiece(live: Live, peek: Boolean = false): Int? = synchronized(live) {
        val at = (live.focus / PIECE).toInt()
        val last = (minOf(live.until, live.durationMs - 1) / PIECE).toInt()
        val order = (at..maxOf(at, last)).toList() + listOf(at - 1, at - 2).filter { it >= 0 }
        val i = order.firstOrNull { it !in live.done && it !in live.busy } ?: return null
        if (!peek) live.busy += i
        i
    }

    private suspend fun worker(ctx: Context, videoId: String, live: Live) {
        try {
            val lang = engineLang.getOrPut(videoId) { engineFor(videoId) }
            val norwegian = lang == "no"
            val vosk: Model? = if (norwegian) {
                if (!Whisper.ready(ctx)) { live.state.value = State.DOWNLOADING_MODEL; Diag.log("asr", "downloading Norwegian speech model") }
                if (!Whisper.load(ctx)) { live.error = "Norwegian model unavailable"; live.state.value = State.FAILED; return }
                null
            } else {
                if (!modelReady(ctx)) { live.state.value = State.DOWNLOADING_MODEL; Diag.log("asr", "downloading speech model") }
                model(ctx)
            }
            if (live.state.value != State.LISTENING) live.state.value = State.LOADING_AUDIO
            val tOpen = System.currentTimeMillis()
            val reused = live.readers.poll()
            val reader = reused ?: openReader(ctx, videoId, live) ?: run { live.error = "no audio"; live.state.value = State.FAILED; return }
            if (reused == null) Diag.log("asr", "$videoId: audio opened in ${(System.currentTimeMillis() - tOpen) / 1000.0} s")
            var keep = true
            try {
                live.durationMs = reader.durationMs
                live.state.value = State.LISTENING
                val pcm = ShortArray((RATE * (PIECE + 1000) / 1000).toInt())
                while (coroutineContext.isActive) {
                    val i = nextPiece(live) ?: break
                    try {
                        val start = i * PIECE
                        val t0 = System.currentTimeMillis()
                        val n = reader.read(start, start + PIECE, pcm)
                        val tRead = System.currentTimeMillis() - t0
                        val cues = if (n == 0) emptyList() else if (norwegian) {
                            val threads = 2 // per worker; leaves cores free for the video itself
                            Whisper.transcribePiece(pcm, n, "no", threads).flatMap { (a, b, t) -> splitLine(start + a, start + b, t) }
                        } else voskPiece(vosk!!, pcm, n, start)
                        live.done += i
                        if (cues.isNotEmpty()) {
                            val first = live.cues.value.isEmpty()
                            live.cues.value = (live.cues.value.filter { c -> c.start < start || c.start >= start + PIECE } + cues).sortedBy { it.start }
                            if (first) Diag.log("asr", "$videoId: first subtitles: audio ${tRead / 1000.0} s + recognition ${(System.currentTimeMillis() - t0 - tRead) / 1000.0} s (${if (norwegian) "Norwegian" else "English"})")
                        }
                    } finally { live.busy -= i }
                }
            } catch (e: Throwable) { keep = false; throw e
            } finally { if (keep && coroutineContext.isActive) live.readers.offer(reader) else runCatching { reader.close() } }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Throwable) {
            live.error = "${e.javaClass.simpleName}: ${e.message?.take(120)}"; Diag.err("asr", "$videoId failed", e); live.state.value = State.FAILED
        }
    }

    private fun voskPiece(m: Model, pcm: ShortArray, n: Int, startMs: Long): List<Cue> {
        val rec = Recognizer(m, RATE.toFloat()).apply { setWords(true) }
        try {
            rec.acceptWaveForm(pcm, n)
            val words = JSONObject(rec.finalResult).optJSONArray("result") ?: return emptyList()
            val list = (0 until words.length()).map { words.getJSONObject(it) }
            val out = mutableListOf<Cue>()
            var cur = mutableListOf<JSONObject>()
            for (w in list) {
                val gap = cur.lastOrNull()?.let { w.getDouble("start") - it.getDouble("end") } ?: 0.0
                if (cur.size >= 7 || gap > 0.7) { out += toCue(cur, startMs); cur = mutableListOf() }
                cur += w
            }
            if (cur.isNotEmpty()) out += toCue(cur, startMs)
            return out
        } finally { rec.close() }
    }

    // ---- Reading audio at any spot ----

    /** Decodes a stretch of the audio track to 16 kHz mono. Opens the stream straight from YouTube. */
    class AudioReader(private val ex: MediaExtractor, private val codec: MediaCodec, val durationMs: Long, var srcRate: Int, var channels: Int) {
        fun read(fromMs: Long, toMs: Long, out: ShortArray): Int {
            ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.flush()
            val info = MediaCodec.BufferInfo()
            var n = 0
            var inputDone = false
            val endUs = toMs * 1000
            while (n < out.size) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val size = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0 || ex.sampleTime > endUs + 200_000) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else { codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    codec.outputFormat.let { srcRate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = buf.remaining() / channels
                    // Pick the samples of this buffer that fall inside [from, to), mixed to mono at 16 kHz.
                    var k = 0
                    while (true) {
                        val tUs = info.presentationTimeUs + k * 1_000_000L / RATE
                        val src = ((tUs - info.presentationTimeUs) * srcRate / 1_000_000L).toInt()
                        if (src >= frames || n >= out.size) break
                        if (tUs >= fromMs * 1000 && tUs < endUs) {
                            var s = 0
                            for (c in 0 until channels) s += buf.get(src * channels + c)
                            out[n++] = (s / channels).toShort()
                        }
                        k++
                    }
                    val bufferEnd = info.presentationTimeUs + frames * 1_000_000L / srcRate
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || bufferEnd >= endUs) break
                }
            }
            return n
        }
        fun close() { runCatching { codec.stop() }; codec.release(); ex.release() }
    }

    private suspend fun openReader(ctx: Context, videoId: String, live: Live): AudioReader? = withContext(Dispatchers.IO) {
        fun open(setSource: (MediaExtractor) -> Unit): AudioReader {
            val ex = MediaExtractor()
            setSource(ex)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else Long.MAX_VALUE
            return AudioReader(ex, codec, dur, fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        }
        // Already downloaded? Use the file.
        File(File(ctx.cacheDir, "asr"), "$videoId.m4a").takeIf { it.exists() && it.length() > 10_000 }?.let { f ->
            return@withContext runCatching { open { it.setDataSource(f.absolutePath) } }.getOrNull()
        }
        // Otherwise straight from YouTube: only the parts that are needed are fetched.
        val url = live.source ?: audioUrl(videoId)?.also { live.source = it }
        if (url != null) {
            runCatching { open { it.setDataSource(url, mapOf("User-Agent" to "Mozilla/5.0")) } }
                .onSuccess { return@withContext it }
                .onFailure { Diag.err("asr", "$videoId: streaming audio", it) }
        }
        // Fallback: download the audio file first – only when you asked, never for background subtitles.
        if (!live.allowDownload) { Diag.log("asr", "$videoId: couldn't stream the audio; subtitles skipped"); return@withContext null }
        val f = audioFile(ctx, videoId) ?: return@withContext null
        runCatching { open { it.setDataSource(f.absolutePath) } }.getOrNull()
    }

    /** A small, widely decodable audio track for listening. */
    private suspend fun audioUrl(videoId: String): String? {
        val r = StreamResolver.resolve(videoId) ?: return null
        val tracks = StreamResolver.preferredAudio(r)
        return (tracks.filter { it.bitrate >= 48 }.minByOrNull { it.bitrate } ?: tracks.minByOrNull { it.bitrate })?.url
    }

    internal suspend fun audioFile(ctx: Context, videoId: String): File? =
        downloadAudio(videoId, File(ctx.cacheDir, "asr").apply { mkdirs() })

    /** Downloads a video's audio track into [dir] (also used by the diagnostic tests). */
    suspend fun downloadAudio(videoId: String, dir: File): File? = withContext(Dispatchers.IO) {
        val f = File(dir, "$videoId.m4a")
        if (f.exists() && f.length() > 10_000) return@withContext f
        val r = StreamResolver.resolve(videoId) ?: return@withContext null
        // A small, widely decodable audio track (AAC-LC ~128k if the tiny HE-AAC one is all there is otherwise).
        val tracks = StreamResolver.preferredAudio(r).filter { it.original || it.lang == "en" }.ifEmpty { StreamResolver.preferredAudio(r) }
        val url = (tracks.filter { it.bitrate >= 64 }.minByOrNull { it.bitrate } ?: tracks.minByOrNull { it.bitrate })?.url
            ?: r.muxed.minByOrNull { it.height }?.url
            ?: run { Diag.log("asr", "$videoId: no audio track"); return@withContext null }
        Diag.log("asr", "$videoId: downloading audio")
        val part = File(dir, "$videoId.part")
        val clen = android.net.Uri.parse(url).getQueryParameter("clen")?.toLongOrNull()
        if (clen != null && clen in 1..(60L * 1024 * 1024)) {
            // Size known: fetch 1 MB pieces, 4 at a time (much faster than one after another).
            val piece = 1L * 1024 * 1024
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            java.io.RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(clen)
                kotlinx.coroutines.coroutineScope {
                    (0 until clen step piece).map { start ->
                        launch(Dispatchers.IO) {
                            gate.acquire()
                            try {
                                val end = minOf(start + piece, clen) - 1
                                val bytes = http.newCall(Request.Builder().url("$url&range=$start-$end").header("User-Agent", "Mozilla/5.0").build())
                                    .execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}") else r.body!!.bytes() }
                                synchronized(raf) { raf.seek(start); raf.write(bytes) }
                            } finally { gate.release() }
                        }
                    }.forEach { it.join() }
                }
            }
            part.renameTo(f)
            Diag.log("asr", "$videoId: audio ${f.length() / 1024} KB (parallel)")
            return@withContext f
        }
        part.outputStream().use { out ->
            var pos = 0L
            val chunk = 2L * 1024 * 1024
            val total = clen
            val cap = 40L * 1024 * 1024 // ~2 hours of low-bitrate audio
            while (pos < (total ?: cap) && pos < cap) {
                coroutineContext.ensureActive()
                val end = minOf(pos + chunk, total ?: Long.MAX_VALUE) - 1
                val req = Request.Builder().url("$url&range=$pos-$end").header("User-Agent", "Mozilla/5.0").build()
                val bytes = http.newCall(req).execute().use { resp ->
                    if (resp.code == 416) null else if (!resp.isSuccessful) error("HTTP ${resp.code}") else resp.body?.bytes()
                } ?: break
                if (bytes.isEmpty()) break
                out.write(bytes); pos += bytes.size
                if (bytes.size < chunk) break
            }
        }
        part.renameTo(f)
        Diag.log("asr", "$videoId: audio ${f.length() / 1024} KB")
        f
    }

    /** Spoken language of a video: YouTube's automatic-caption label, else its title/description. */
    /**
     * Which speech model to use. In Norwegian-only mode every video in the feed is Norwegian, so it's
     * always NB-Whisper; otherwise YouTube's language label or the title/description decide, and if
     * that's unclear the app's language wins.
     */
    private suspend fun engineFor(videoId: String): String {
        if (com.faithscroll.app.util.Lang.no && !com.faithscroll.app.util.Lang.alsoEnglish) return "no"
        val info = WatchPage.info(videoId)
        val s = info?.let { WatchPage.spoken(it).ifBlank { LangFilter.spokenFromText(it.title + "\n" + it.description).orEmpty() } }.orEmpty()
        return when (s) { "no" -> "no"; "en" -> "en"; else -> if (com.faithscroll.app.util.Lang.no) "no" else "en" }
    }

    private suspend fun spokenLanguage(videoId: String): String {
        val info = WatchPage.info(videoId) ?: return "en"
        return WatchPage.spoken(info).ifBlank { LangFilter.spokenFromText(info.title + "\n" + info.description).orEmpty() }.ifBlank { "en" }
    }

    /** Language of the subtitles made for this video (Norwegian = NB-Whisper, otherwise English). */
    val engineLang = ConcurrentHashMap<String, String>()

    /** Long Whisper segments become readable lines of up to ~8 words, time split evenly. */
    private fun splitLine(start: Long, end: Long, text: String): List<Cue> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 9) return listOf(Cue(start, maxOf(end, start + 800), text))
        val parts = words.chunked(8)
        val per = (end - start).coerceAtLeast(1000) / parts.size
        return parts.mapIndexed { i, p -> Cue(start + i * per, start + (i + 1) * per, p.joinToString(" ")) }
    }

    private fun toCue(words: List<JSONObject>, offsetMs: Long): Cue = Cue(
        start = offsetMs + (words.first().getDouble("start") * 1000).toLong(),
        end = offsetMs + (words.last().getDouble("end") * 1000).toLong(),
        text = words.joinToString(" ") { it.getString("word") }.replaceFirstChar { it.uppercase() }
    )
}
