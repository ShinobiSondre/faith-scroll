package com.faithscroll.app.data

import android.content.Context
import com.faithscroll.app.util.Lang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Only verified videos reach the feed and Watch. A video passes when:
 *  1. YouTube's captions for it actually load (so subtitles and "What do they mean here?" work),
 *  2. its spoken language is known (YouTube's automatic captions are made from the audio), and
 *  3. that language is the app's: English, or in Norwegian mode Norwegian, or English with YouTube's
 *     Norwegian translation working.
 * If any of that can't be confirmed, the video is left out. Results are kept for a day.
 */
object VideoCheck {
    val verified = MutableStateFlow<Set<String>>(emptySet())
    val checking = MutableStateFlow(false)

    private data class Result(val ok: Boolean, val mode: String, val t: Long)
    private val results = ConcurrentHashMap<String, Result>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var store: File? = null
    private const val KEEP = 24 * 60 * 60_000L

    private fun mode() = if (Lang.no) "no" else "en"

    fun isOk(id: String) = results[id]?.let { it.ok && it.mode == mode() && fresh(it) } == true
    private fun known(id: String) = results[id]?.let { it.mode == mode() && fresh(it) } == true
    private fun fresh(r: Result) = System.currentTimeMillis() - r.t < KEEP

    private fun publish() { verified.value = results.filterValues { it.ok && it.mode == mode() && fresh(it) }.keys.toSet() }

    /** Checks these videos in order, in the background (replaces any earlier list). */
    @Synchronized fun queue(ctx: Context, videos: List<Video>) {
        if (store == null) { store = File(ctx.applicationContext.filesDir, "video_checks_v2.json"); runCatching { load() }; publish() }
        val todo = videos.filter { it.direct.isBlank() && !known(it.id) }.distinctBy { it.id }
        if (todo.isEmpty()) return
        job?.cancel()
        job = scope.launch {
            checking.value = true
            for (v in todo) {
                if (!isActive) break
                if (known(v.id)) continue
                val ok = runCatching { check(v) }.getOrElse { Diag.err("check", "${v.id} failed", it); false }
                // Don't remember "no captions" verdicts until captions have worked at least once on this phone
                // (otherwise a temporary problem would hide videos for a whole day).
                if (ok || CaptionGrabber.successes > 0 || !softReject) results[v.id] = Result(ok, mode(), System.currentTimeMillis())
                softReject = false
                publish(); save()
            }
            checking.value = false
        }
    }

    private suspend fun check(v: Video): Boolean {
        val t = Transcripts.original(v.id)
        if (t == null) {
            if (CaptionGrabber.working) return reject(v, "no working captions")
            // Captions aren't working on this phone at all: decide by the language of the original
            // audio track if YouTube says it, otherwise by the title + description text.
            // Unknown still means out.
            val r = StreamResolver.resolve(v.id)
            val lang = r?.spoken?.takeIf { it.isNotBlank() }
                ?: LangFilter.detect("${v.title}. ${r?.description.orEmpty().take(800)}")
                ?: ""
            val ok = lang == "en" || (Lang.no && lang == "no")
            return if (ok) { Diag.log("check", "${v.id}: OK by language of title/description/audio: $lang (captions unavailable)"); true }
            else reject(v, "captions unavailable, language '${lang.ifBlank { "?" }}'")
        }
        val sample = t.cues.take(60).joinToString(" ") { it.text }
        // Spoken language: YouTube's automatic captions; double-checked against what the captions actually say.
        var spoken = t.spoken.ifBlank { StreamResolver.cached(v.id)?.spoken.orEmpty() }
        if (t.lang == t.spoken || t.spoken.isBlank()) {
            LangFilter.detect(sample)?.let { detected -> if (spoken.isBlank() || detected != spoken) spoken = detected }
        }
        if (spoken.isBlank()) return reject(v, "spoken language unknown")
        val ok = when {
            !Lang.no -> spoken == "en"
            spoken == "no" -> true
            spoken == "en" -> Transcripts.inAppLanguage(v.id)?.let { it.translated || it.lang == "no" } == true
            else -> false
        }
        if (!ok) return reject(v, "spoken '$spoken' isn't the app language")
        Diag.log("check", "${v.id}: OK (spoken $spoken, ${t.cues.size} caption lines)")
        return true
    }

    @Volatile private var softReject = false

    private fun reject(v: Video, why: String): Boolean {
        softReject = why.startsWith("no working captions") || why.startsWith("captions unavailable")
        Diag.log("check", "${v.id}: left out – $why (${v.title.take(40)})")
        return false
    }

    private fun save() {
        val f = store ?: return
        runCatching {
            val o = JSONObject()
            results.forEach { (k, r) -> if (fresh(r)) o.put(k, JSONObject().put("ok", r.ok).put("m", r.mode).put("t", r.t)) }
            f.writeText(o.toString())
        }
    }

    private fun load() {
        val f = store ?: return
        if (!f.exists()) return
        val o = JSONObject(f.readText())
        o.keys().forEach { k -> o.getJSONObject(k).let { results[k] = Result(it.getBoolean("ok"), it.getString("m"), it.getLong("t")) } }
    }
}
