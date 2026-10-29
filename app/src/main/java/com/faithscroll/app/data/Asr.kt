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
 * Makes subtitles for any video by listening to it on the phone (Vosk speech recognition,
 * fully offline once the ~40 MB English model is downloaded). Transcribes from where you are
 * watching, faster than real time, and pre-transcribes the start of upcoming videos.
 */
object Asr {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MODEL_DIR = "vosk-model-small-en-us-0.15"
    private const val RATE = 16000

    enum class State { IDLE, DOWNLOADING_MODEL, LOADING_AUDIO, LISTENING, DONE, FAILED }

    /** Live captions for one video, growing as transcription runs. */
    class Live {
        val cues = MutableStateFlow<List<Cue>>(emptyList())
        val state = MutableStateFlow(State.IDLE)
        @Volatile var coveredFrom = Long.MAX_VALUE
        @Volatile var coveredTo = 0L
        @Volatile var runMax = 0L // how far the current/last run was allowed to go
        @Volatile var finished = false // reached the end of the video
        var job: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lives = ConcurrentHashMap<String, Live>()
    private val modelLock = Mutex()
    private val cpu = kotlinx.coroutines.sync.Semaphore(2) // at most two videos at once
    @Volatile private var model: Model? = null
    val modelProgress = MutableStateFlow(0f)
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    fun live(videoId: String): Live = lives.getOrPut(videoId) { Live() }
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
        http.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { r ->
            if (!r.isSuccessful) error("Model download failed: HTTP ${r.code}")
            val total = r.body!!.contentLength().coerceAtLeast(1)
            var got = 0L
            r.body!!.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        modelProgress.value = got.toFloat() / total
                    }
                }
            }
        }
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
     * Makes sure captions exist around [fromMs] (and ahead of it). Starts or restarts
     * transcription from there if needed. [maxMs] limits how far to go (e.g. preloading).
     */
    fun ensure(ctx: Context, videoId: String, fromMs: Long, maxMs: Long = Long.MAX_VALUE) {
        val live = live(videoId)
        val running = live.job?.isActive == true
        val inRange = fromMs >= live.coveredFrom - 1000 && fromMs <= live.coveredTo + 5000
        when {
            running && inRange && live.runMax >= minOf(maxMs, fromMs + 60_000) -> return // already heading there
            !running && inRange && live.finished -> return // whole rest is done
            !running && inRange && live.coveredTo - fromMs > 30_000 && maxMs <= live.coveredTo -> return
        }
        live.job?.cancel()
        val start = if (inRange) live.coveredTo else (fromMs - 3000).coerceAtLeast(0)
        live.runMax = maxMs
        val app = ctx.applicationContext
        live.job = scope.launch {
            cpu.acquire()
            try { transcribe(app, videoId, live, start, maxMs) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { live.state.value = State.FAILED }
            finally { cpu.release() }
        }
    }

    fun stop(videoId: String) { lives[videoId]?.job?.cancel() }

    private suspend fun audioFile(ctx: Context, videoId: String): File? = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "asr").apply { mkdirs() }
        val f = File(dir, "$videoId.m4a")
        if (f.exists() && f.length() > 10_000) return@withContext f
        val r = StreamResolver.resolve(videoId) ?: return@withContext null
        // Smallest audio track: speech recognition only needs 16 kHz mono.
        val url = r.audios.minByOrNull { it.bitrate }?.url ?: r.muxed.minByOrNull { it.height }?.url ?: return@withContext null
        val part = File(dir, "$videoId.part")
        part.outputStream().use { out ->
            var pos = 0L
            val chunk = 2L * 1024 * 1024
            val total = android.net.Uri.parse(url).getQueryParameter("clen")?.toLongOrNull()
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
        f
    }

    private suspend fun transcribe(ctx: Context, videoId: String, live: Live, fromMs: Long, maxMs: Long) {
        if (!modelReady(ctx)) live.state.value = State.DOWNLOADING_MODEL
        val m = model(ctx)
        live.state.value = State.LOADING_AUDIO
        val file = audioFile(ctx, videoId) ?: run { live.state.value = State.FAILED; return }
        live.state.value = State.LISTENING

        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: run { ex.release(); live.state.value = State.FAILED; return }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val rec = Recognizer(m, RATE.toFloat()).apply { setWords(true) }

        var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val startUs = maxOf(0L, ex.sampleTime)
        val offsetMs = startUs / 1000
        if (live.coveredTo < offsetMs - 5000 || live.coveredFrom > offsetMs + 5000) live.coveredFrom = offsetMs
        live.coveredFrom = minOf(live.coveredFrom, offsetMs)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var resamplePos = 0.0
        val out16 = ShortArray(RATE) // up to 1 s per push
        var outN = 0
        var fedMs = 0L // ms of 16 kHz audio fed so far

        fun handleResult(json: String) {
            val words = JSONObject(json).optJSONArray("result") ?: return
            if (words.length() == 0) return
            val list = (0 until words.length()).map { words.getJSONObject(it) }
            // Group into readable lines: up to 7 words, broken at pauses.
            val newCues = mutableListOf<Cue>()
            var cur = mutableListOf<JSONObject>()
            for (w in list) {
                val gap = cur.lastOrNull()?.let { w.getDouble("start") - it.getDouble("end") } ?: 0.0
                if (cur.size >= 7 || gap > 0.7) {
                    newCues += toCue(cur, offsetMs); cur = mutableListOf()
                }
                cur += w
            }
            if (cur.isNotEmpty()) newCues += toCue(cur, offsetMs)
            val merged = (live.cues.value.filter { c -> newCues.none { it.start <= c.end && c.start <= it.end } } + newCues).sortedBy { it.start }
            live.cues.value = merged
        }

        try {
            while (coroutineContext.isActive) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0 || (ex.sampleTime / 1000) > maxMs) {
                            if (n < 0) live.finished = true
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    codec.outputFormat.let {
                        srcRate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = buf.remaining() / channels
                    val step = srcRate.toDouble() / RATE
                    // Mix to mono and resample to 16 kHz.
                    while (resamplePos < frames) {
                        val idx = resamplePos.toInt() * channels
                        var s = 0
                        for (c in 0 until channels) s += buf.get(idx + c)
                        out16[outN++] = (s / channels).toShort()
                        resamplePos += step
                        if (outN == out16.size) {
                            if (rec.acceptWaveForm(out16, outN)) handleResult(rec.result)
                            fedMs += outN * 1000L / RATE; outN = 0
                            live.coveredTo = offsetMs + fedMs
                        }
                    }
                    resamplePos -= frames
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (outN > 0) rec.acceptWaveForm(out16, outN)
            handleResult(rec.finalResult)
            live.coveredTo = offsetMs + fedMs
            if (coroutineContext.isActive) live.state.value = State.DONE
        } finally {
            runCatching { codec.stop() }; codec.release(); ex.release(); rec.close()
        }
    }

    private fun toCue(words: List<JSONObject>, offsetMs: Long): Cue = Cue(
        start = offsetMs + (words.first().getDouble("start") * 1000).toLong(),
        end = offsetMs + (words.last().getDouble("end") * 1000).toLong(),
        text = words.joinToString(" ") { it.getString("word") }.replaceFirstChar { it.uppercase() }
    )
}
