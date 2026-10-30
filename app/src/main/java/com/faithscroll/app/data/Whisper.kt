package com.faithscroll.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Norwegian speech recognition on the phone: NB-Whisper (base) from the National Library of
 * Norway, run with whisper.cpp. One-time ~55 MB download, then fully offline.
 */
object Whisper {
    private const val FILE = "nb-whisper-base-q5_0.bin"
    private val URLS = listOf(
        "https://github.com/ShinobiSondre/faith-scroll/releases/download/models/$FILE",
        "https://huggingface.co/NbAiLab/nb-whisper-base/resolve/main/ggml-model-q5_0.bin"
    )
    private val lock = Mutex()
    @Volatile private var ctx: Long = 0
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private val loaded by lazy { runCatching { System.loadLibrary("fswhisper"); true }.getOrElse { Diag.err("whisper", "native library", it); false } }

    private external fun nativeInit(path: String): Long
    private external fun nativeFree(ctx: Long)
    private external fun nativeTranscribe(ctx: Long, samples: FloatArray, lang: String, threads: Int, audioCtx: Int): ByteArray
    private external fun nativeNewState(ctx: Long): Long
    private external fun nativeTranscribeState(ctx: Long, state: Long, samples: FloatArray, lang: String, threads: Int, audioCtx: Int): ByteArray

    /** Independent workspaces on the one loaded model, so pieces can be transcribed in parallel. */
    const val WORKERS = 2
    private val states = kotlinx.coroutines.channels.Channel<Long>(WORKERS)

    fun file(c: Context) = File(c.filesDir, "whisper/$FILE")
    fun ready(c: Context) = file(c).let { it.exists() && it.length() > 40_000_000 }

    /** Loads (downloading once if needed) the model. */
    suspend fun load(c: Context): Boolean = lock.withLock {
        if (ctx != 0L) return@withLock true
        if (!loaded) return@withLock false
        withContext(Dispatchers.IO) {
            val f = file(c)
            if (!ready(c)) download(f)
            ctx = nativeInit(f.absolutePath)
            if (ctx == 0L) Diag.log("whisper", "model failed to load")
            else {
                repeat(WORKERS) { nativeNewState(ctx).takeIf { it != 0L }?.let { st -> states.trySend(st) } }
                Diag.log("whisper", "Norwegian model ready ($WORKERS parallel workers)")
            }
        }
        ctx != 0L
    }

    private fun download(f: File) {
        f.parentFile?.mkdirs()
        val part = File(f.parentFile, "$FILE.part")
        var last: Throwable? = null
        for (url in URLS) {
            val ok = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val total = r.body!!.contentLength().coerceAtLeast(1)
                    var got = 0L
                    r.body!!.byteStream().use { input -> part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); got += n
                            val p = got.toFloat() / total
                            if ((p * 4).toInt() > (Asr.modelProgress.value * 4).toInt()) Diag.log("whisper", "Norwegian model ${(p * 100).toInt()} %")
                            Asr.modelProgress.value = p
                        }
                    } }
                }
                part.renameTo(f)
            }.onFailure { last = it; Diag.err("whisper", "model from ${android.net.Uri.parse(url).host}", it) }.isSuccess
            if (ok) { Diag.log("whisper", "Norwegian model from ${android.net.Uri.parse(url).host}"); return }
        }
        throw last ?: IllegalStateException("Model download failed")
    }

    /** Parallel-safe: borrows one of the worker states for this piece of audio. */
    suspend fun transcribePiece(pcm: ShortArray, n: Int, lang: String = "no", threads: Int): List<Triple<Long, Long, String>> {
        if (ctx == 0L || n < 1600) return emptyList()
        val st = states.receive()
        try {
            val f = FloatArray(n) { pcm[it] / 32768f }
            val audioCtx = (n / 16000.0 * 50 + 64).toInt().coerceIn(256, 1500)
            val t0 = System.currentTimeMillis()
            val text = withContext(Dispatchers.Default) {
                // Lower priority than the video player, so playback and loading stay smooth.
                val tid = android.os.Process.myTid()
                val old = android.os.Process.getThreadPriority(tid)
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                try { nativeTranscribeState(ctx, st, f, lang, threads, audioCtx) }
                finally { android.os.Process.setThreadPriority(old) }
            }.toString(Charsets.UTF_8)
            Diag.log("whisper", "${n / 16000} s piece in ${(System.currentTimeMillis() - t0) / 1000.0} s")
            return parse(text)
        } finally { states.send(st) }
    }

    private fun parse(text: String) = text.lineSequence().mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        if (parts.size < 3) return@mapNotNull null
        val t = parts[2].trim()
        if (t.isBlank() || t.startsWith("[") || t.startsWith("(")) null
        else Triple(parts[0].toLongOrNull() ?: 0L, parts[1].toLongOrNull() ?: 0L, t)
    }.toList()

    /** Transcribes 16 kHz mono audio; returns (startMs, endMs, text) relative to the start of [pcm]. */
    fun transcribe(pcm: ShortArray, n: Int, lang: String = "no"): List<Triple<Long, Long, String>> = synchronized(this) {
        if (ctx == 0L || n < 1600) return emptyList()
        val f = FloatArray(n) { pcm[it] / 32768f }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4) // the fast cores
        // Whisper's window is 1500 steps for 30 s; use just enough for this chunk (+ a little margin).
        val audioCtx = (n / 16000.0 * 50 + 64).toInt().coerceIn(256, 1500)
        val t0 = System.currentTimeMillis()
        val text = nativeTranscribe(ctx, f, lang, threads, audioCtx).toString(Charsets.UTF_8)
        Diag.log("whisper", "${n / 16000} s of audio in ${(System.currentTimeMillis() - t0) / 1000.0} s")
        text.lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val t = parts[2].trim()
            if (t.isBlank() || t.startsWith("[") || t.startsWith("(")) null // skip "[musikk]" etc.
            else Triple(parts[0].toLongOrNull() ?: 0L, parts[1].toLongOrNull() ?: 0L, t)
        }.toList()
    }
}
