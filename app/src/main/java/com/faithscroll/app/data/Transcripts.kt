package com.faithscroll.app.data

import com.faithscroll.app.util.Lang
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One line of captions. */
data class Cue(val start: Long, val end: Long, val text: String)

/** Captions for a video, in the original language and (if needed) translated on the phone. */
data class Transcript(val lang: String, val cues: List<Cue>, val translated: Boolean, val spoken: String = "")

object Transcripts {
    private val cache = ConcurrentHashMap<String, Transcript>()
    private val missing = ConcurrentHashMap<String, Long>()

    /** The app language as a 2-letter code. */
    fun target(): String = if (Lang.no) "no" else "en"

    /** Original captions (best track: manual before automatic, app language before others). */
    suspend fun original(videoId: String, urgent: Boolean = false): Transcript? = withContext(Dispatchers.IO) {
        cache["o|$videoId"]?.let { return@withContext it }
        missing[videoId]?.let { if (System.currentTimeMillis() - it < 10 * 60_000) return@withContext null }
        val t0 = System.currentTimeMillis()
        val info = WatchPage.info(videoId)
        if (info == null || info.tracks.isEmpty()) {
            missing[videoId] = System.currentTimeMillis()
            Diag.log("subs", "$videoId: ${if (info == null) "page unreadable" else "no captions on YouTube"}")
            return@withContext null
        }
        val want = target()
        val spoken = WatchPage.spoken(info)
        // Prefer hand-made captions in the app language, then YouTube's automatic ones (= spoken language).
        val chosen = info.tracks.firstOrNull { it.lang == want && it.kind != "asr" }
            ?: info.tracks.firstOrNull { it.kind == "asr" }
            ?: info.tracks.first()
        val cues = WatchPage.fetch(videoId, chosen)
        if (cues.isNullOrEmpty()) { missing[videoId] = System.currentTimeMillis(); return@withContext null }
        Transcript(chosen.lang, cues, false, spoken).also {
            cache["o|$videoId"] = it
            Diag.log("subs", "$videoId: ${cues.size} caption lines (${chosen.lang}${if (chosen.kind == "asr") ", auto" else ""}), spoken=${spoken.ifBlank { "?" }} in ${(System.currentTimeMillis() - t0) / 1000.0} s")
        }
    }

    /** Captions in the app language: translated on the phone when the video is in another language. */
    suspend fun inAppLanguage(videoId: String, urgent: Boolean = false, onProgress: (Transcript) -> Unit = {}): Transcript? = withContext(Dispatchers.IO) {
        val want = target()
        cache["t|$want|$videoId"]?.let { return@withContext it }
        val orig = original(videoId, urgent) ?: return@withContext null
        if (orig.lang == want) return@withContext orig
        // Like YouTube's "Auto-translate": ask YouTube for the captions already translated.
        WatchPage.info(videoId)?.let { info ->
            val tr = info.tracks.firstOrNull { it.lang == orig.lang && it.kind != "asr" } ?: info.tracks.firstOrNull { it.kind == "asr" } ?: info.tracks.firstOrNull()
            tr?.let { WatchPage.fetch(videoId, it, if (want == "no") "no" else want) }?.takeIf { it.isNotEmpty() }?.let { cues ->
                Diag.log("subs", "$videoId: YouTube auto-translated captions → $want (${cues.size} lines)")
                return@withContext Transcript(want, cues, true, orig.spoken).also { cache["t|$want|$videoId"] = it }
            }
        }
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
    /**
     * Transcribes only the 90 seconds before [atMs] on the phone (for videos without captions):
     * just that slice of audio is downloaded and recognised, so an answer comes quickly.
     */
    suspend fun transcribeWindow(ctx: android.content.Context, videoId: String, atMs: Long): String {
        val from = (atMs - 90_000).coerceAtLeast(0)
        Asr.ensure(ctx, videoId, from, atMs + 5_000)
        kotlinx.coroutines.withTimeoutOrNull(60_000) {
            Asr.cues(videoId).first { cues -> cues.any { it.end >= atMs - 4_000 } }
        }
        return Asr.cues(videoId).value.filter { it.end >= from && it.start <= atMs + 10_000 }.joinToString(" ") { it.text }
    }

    suspend fun textAround(videoId: String, atMs: Long): String {
        original(videoId, urgent = true)?.let { return window(it, atMs) }
        val cues = Asr.cues(videoId).value
        return cues.filter { it.end >= atMs - 90_000 && it.start <= atMs + 10_000 }.joinToString(" ") { it.text }
    }

    /**
     * YouTube's own player fetches captions with a valid token. When the fallback player does that,
     * we read the same file (as JSON) so "What do they mean here?" and subtitles have a transcript.
     */
    suspend fun captureTimedText(videoId: String, url: String) = withContext(Dispatchers.IO) {
        CaptionGrabber.remember(videoId, url)
        if (cache["o|$videoId"] != null) return@withContext
        val u = android.net.Uri.parse(url)
        val b = u.buildUpon().clearQuery()
        for (k in u.queryParameterNames) if (k != "fmt" && k != "tlang") u.getQueryParameters(k).forEach { b.appendQueryParameter(k, it) }
        b.appendQueryParameter("fmt", "json3")
        val body = Http.get(b.build().toString(), 15_000) ?: run { Diag.log("subs", "$videoId: captured captions download failed"); return@withContext }
        val cues = runCatching {
            val events = org.json.JSONObject(body).optJSONArray("events") ?: org.json.JSONArray()
            (0 until events.length()).mapNotNull { i ->
                val e = events.getJSONObject(i)
                val segs = e.optJSONArray("segs") ?: return@mapNotNull null
                val text = (0 until segs.length()).joinToString("") { segs.getJSONObject(it).optString("utf8") }.replace("\n", " ").trim()
                if (text.isBlank()) null else Cue(e.optLong("tStartMs"), e.optLong("tStartMs") + e.optLong("dDurationMs", 2000), text)
            }
        }.getOrDefault(emptyList())
        if (cues.isEmpty()) { Diag.log("subs", "$videoId: captured captions empty"); return@withContext }
        val lang = (u.getQueryParameter("lang") ?: "en").substringBefore('-').let { if (it == "nb" || it == "nn") "no" else it }
        cache["o|$videoId"] = Transcript(lang, cues, false)
        Diag.log("subs", "$videoId: transcript captured from YouTube's player (${cues.size} lines, $lang)")
    }

    fun hasTranscript(videoId: String) = cache["o|$videoId"] != null

    /** YouTube's own machine translation of a caption track (tlang), as JSON. */
    private fun youtubeTranslated(link: String, lang: String): Transcript? {
        val u = android.net.Uri.parse(link)
        val b = u.buildUpon().clearQuery()
        for (k in u.queryParameterNames) if (k != "fmt" && k != "tlang") u.getQueryParameters(k).forEach { b.appendQueryParameter(k, it) }
        b.appendQueryParameter("fmt", "json3").appendQueryParameter("tlang", if (lang == "no") "no" else lang)
        val body = Http.get(b.build().toString(), 15_000) ?: return null
        val cues = parseJson3(body)
        return if (cues.isEmpty()) null else Transcript(lang, cues, true)
    }

    private fun parseJson3(body: String): List<Cue> = runCatching {
        val events = org.json.JSONObject(body).optJSONArray("events") ?: org.json.JSONArray()
        (0 until events.length()).mapNotNull { i ->
            val e = events.getJSONObject(i)
            val segs = e.optJSONArray("segs") ?: return@mapNotNull null
            val text = (0 until segs.length()).joinToString("") { segs.getJSONObject(it).optString("utf8") }.replace("\n", " ").trim()
            if (text.isBlank()) null else Cue(e.optLong("tStartMs"), e.optLong("tStartMs") + e.optLong("dDurationMs", 2000), text)
        }
    }.getOrDefault(emptyList())

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
