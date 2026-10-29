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

    private fun mode() = if (!Lang.no) "en" else if (Lang.alsoEnglish) "no+en" else "no"
    private var appCtx: Context? = null

    fun isOk(id: String) = results[id]?.let { it.ok && it.mode == mode() && fresh(it) } == true
    private fun known(id: String) = results[id]?.let { it.mode == mode() && fresh(it) } == true
    private fun fresh(r: Result) = System.currentTimeMillis() - r.t < KEEP

    private fun publish() { verified.value = results.filterValues { it.ok && it.mode == mode() && fresh(it) }.keys.toSet() }

    /** Checks these videos in order, in the background (replaces any earlier list). */
    @Synchronized fun queue(ctx: Context, videos: List<Video>) {
        appCtx = ctx.applicationContext
        if (store == null) { store = File(ctx.applicationContext.filesDir, "video_checks_v4.json"); runCatching { load() }; publish() }
        val todo = videos.filter { it.direct.isBlank() && !known(it.id) }.distinctBy { it.id }
        if (todo.isEmpty()) return
        job?.cancel()
        job = scope.launch {
            checking.value = true
            // Three at a time: each check is one page load + one caption download (about a second).
            val gate = kotlinx.coroutines.sync.Semaphore(3)
            kotlinx.coroutines.coroutineScope {
                for (v in todo) {
                    if (!isActive) break
                    if (known(v.id)) continue
                    gate.acquire()
                    launch {
                        try {
                            // null = couldn't tell (offline / page unreadable): no verdict saved, checked again next time.
                            val ok = runCatching { check(v) }.getOrElse { Diag.err("check", "${v.id} failed", it); null }
                            if (ok != null) results[v.id] = Result(ok, mode(), System.currentTimeMillis())
                            publish(); save()
                        } finally { gate.release() }
                    }
                }
            }
            checking.value = false
        }
    }

    private suspend fun check(v: Video): Boolean? {
        // Can't read YouTube's page (e.g. offline): no verdict yet, it's checked again later.
        val info = WatchPage.info(v.id) ?: run { reject(v, "page unreadable (will retry)"); return null }
        val want = if (Lang.no) "no" else "en"
        // Captions must load right away, otherwise the video is left out (subtitles and Ask need them).
        val t = Transcripts.original(v.id) ?: return reject(v, "no captions")
        // Spoken language: YouTube's automatic captions are made from the audio, so their language is
        // the spoken one. Without them (common for Norwegian), judge the title and description.
        var spoken = WatchPage.spoken(info)
        var how = "auto captions"
        if (spoken.isNotBlank() && t.lang == spoken) {
            LangFilter.detect(t.cues.take(60).joinToString(" ") { it.text })?.let { if (it != spoken) { spoken = it; how = "caption text" } }
        }
        if (spoken.isBlank()) {
            spoken = LangFilter.spokenFromText(listOf(v.title, info.title, info.description).joinToString("\n")).orEmpty()
            how = "title/description"
        }
        if (spoken.isBlank()) return reject(v, "language unknown")
        val ok = when {
            spoken == want -> true // already in your language: no subtitles needed
            Lang.no && Lang.alsoEnglish && spoken == "en" ->
                Transcripts.inAppLanguage(v.id)?.let { it.translated || it.lang == "no" } == true
            else -> false
        }
        if (!ok) return reject(v, "spoken '$spoken' ($how) isn't the app language")
        if (spoken == "no") appCtx?.let { Discovery.rewardChannel(it, v.id) } // a Norwegian channel: keep it as a source
        Diag.log("check", "${v.id}: OK (spoken $spoken by $how, ${t.cues.size} caption lines)")
        return true
    }

    private fun reject(v: Video, why: String): Boolean {
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
