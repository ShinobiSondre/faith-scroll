package com.faithscroll.app.data

import com.faithscroll.app.util.Lang
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One line of captions. */
data class Cue(val start: Long, val end: Long, val text: String)

/** Captions for a video, in the original language and (if needed) translated on the phone. */
data class Transcript(val lang: String, val cues: List<Cue>, val translated: Boolean)

object Transcripts {
    private val cache = ConcurrentHashMap<String, Transcript>()

    /** The app language as a 2-letter code. */
    fun target(): String = if (Lang.no) "no" else "en"

    /** Original captions (best track: manual before automatic, app language before others). */
    suspend fun original(videoId: String): Transcript? = withContext(Dispatchers.IO) {
        cache["o|$videoId"]?.let { return@withContext it }
        val r = StreamResolver.resolve(videoId) ?: return@withContext null
        if (r.subtitles.isEmpty()) { Diag.log("subs", "$videoId: YouTube has no caption tracks → phone transcript"); return@withContext null }
        val want = target()
        fun base(l: String) = l.substringBefore('-').lowercase().let { if (it == "nb" || it == "nn") "no" else it }
        val track = r.subtitles.sortedWith(
            compareByDescending<SubTrack> { base(it.lang) == want }
                .thenByDescending { !it.auto }
                .thenByDescending { base(it.lang) == "en" }
        ).first()
        // YouTube sometimes refuses caption downloads outside its own player (empty reply).
        // Then the phone makes its own transcript instead (see Asr).
        val raw = Http.get(track.url, 15_000)
        val body = raw?.takeIf { it.contains("-->") } ?: run {
            Diag.log("subs", "$videoId: YouTube captions blocked/empty (${raw?.length ?: -1} chars) → phone transcript")
            return@withContext null
        }
        Diag.log("subs", "$videoId: YouTube captions OK (${track.lang}${if (track.auto) ", auto" else ""})")
        val cues = parseVtt(body, track.auto)
        if (cues.isEmpty()) return@withContext null
        Transcript(base(track.lang), cues, false).also { cache["o|$videoId"] = it }
    }

    /** Captions in the app language: translated on the phone when the video is in another language. */
    suspend fun inAppLanguage(videoId: String, onProgress: (Transcript) -> Unit = {}): Transcript? = withContext(Dispatchers.IO) {
        val want = target()
        cache["t|$want|$videoId"]?.let { return@withContext it }
        val orig = original(videoId) ?: return@withContext null
        if (orig.lang == want) return@withContext orig
        val src = TranslateLanguage.fromLanguageTag(orig.lang) ?: return@withContext orig
        val dst = TranslateLanguage.fromLanguageTag(want) ?: return@withContext orig
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
        try {
            // The language pack (~30 MB) downloads once, then works offline.
            Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()), 3, TimeUnit.MINUTES)
            val out = orig.cues.toMutableList()
            for (i in out.indices) {
                val t = runCatching { Tasks.await(translator.translate(out[i].text), 10, TimeUnit.SECONDS) }.getOrNull() ?: continue
                out[i] = out[i].copy(text = t)
                if (i % 15 == 14) onProgress(Transcript(want, out.toList(), true))
            }
            Transcript(want, out, true).also { cache["t|$want|$videoId"] = it }
        } catch (_: Exception) {
            orig
        } finally {
            translator.close()
        }
    }

    // ---- Line-by-line translation (for live transcripts) ----
    private val translators = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()
    private val lineCache = ConcurrentHashMap<String, String>()

    /** Translates one subtitle line into the app language (on the phone; the language pack downloads once). */
    suspend fun translateLine(text: String, fromLang: String = "en"): String = withContext(Dispatchers.IO) {
        val want = target()
        if (fromLang == want) return@withContext text
        lineCache["$want|$text"]?.let { return@withContext it }
        val src = TranslateLanguage.fromLanguageTag(fromLang) ?: return@withContext text
        val dst = TranslateLanguage.fromLanguageTag(want) ?: return@withContext text
        val tr = translators.getOrPut("$src>$dst") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
        }
        runCatching {
            Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()), 3, TimeUnit.MINUTES)
            Tasks.await(tr.translate(text), 10, TimeUnit.SECONDS)
        }.getOrNull()?.also { lineCache["$want|$text"] = it } ?: text
    }

    /** Transcript text around a moment, from YouTube captions or the phone's own transcript. */
    suspend fun textAround(videoId: String, atMs: Long): String {
        original(videoId)?.let { return window(it, atMs) }
        val cues = Asr.cues(videoId).value
        return cues.filter { it.end >= atMs - 90_000 && it.start <= atMs + 10_000 }.joinToString(" ") { it.text }
    }

    /** The words around a moment (for "What do they mean here?"). */
    fun window(t: Transcript, atMs: Long, beforeMs: Long = 90_000, afterMs: Long = 10_000): String =
        t.cues.filter { it.end >= atMs - beforeMs && it.start <= atMs + afterMs }.joinToString(" ") { it.text }

    fun cueAt(t: Transcript, ms: Long): String? = t.cues.lastOrNull { it.start <= ms && ms <= it.end + 400 }?.text

    private val time = Regex("(\\d+):(\\d{2}):(\\d{2})\\.(\\d{3})|(\\d{2}):(\\d{2})\\.(\\d{3})")

    private fun ms(s: String): Long {
        val m = time.find(s) ?: return 0
        return if (m.groupValues[1].isNotEmpty())
            m.groupValues[1].toLong() * 3_600_000 + m.groupValues[2].toLong() * 60_000 + m.groupValues[3].toLong() * 1000 + m.groupValues[4].toLong()
        else m.groupValues[5].toLong() * 60_000 + m.groupValues[6].toLong() * 1000 + m.groupValues[7].toLong()
    }

    /** Reads WebVTT. YouTube's automatic captions repeat each line as it rolls up, so those are de-duplicated. */
    fun parseVtt(vtt: String, auto: Boolean): List<Cue> {
        val out = mutableListOf<Cue>()
        val blocks = vtt.replace("\r", "").split(Regex("\n\n+"))
        for (b in blocks) {
            val lines = b.lines().filter { it.isNotBlank() }
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val (a, z) = lines[ti].split("-->").let { it[0] to it[1] }
            val start = ms(a); val end = ms(z)
            if (end - start < 50) continue // the tiny "hold" cues in auto captions
            val textLines = lines.drop(ti + 1).map { it.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").trim() }
                .filter { it.isNotBlank() }
            if (textLines.isEmpty()) continue
            val text = if (auto) textLines.last() else textLines.joinToString(" ")
            if (out.isNotEmpty() && out.last().text == text) {
                out[out.size - 1] = out.last().copy(end = end)
            } else out += Cue(start, end, text)
        }
        return out
    }
}
