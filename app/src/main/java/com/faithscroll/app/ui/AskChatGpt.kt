package com.faithscroll.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import com.faithscroll.app.data.AskPrompt
import com.faithscroll.app.data.Diag
import com.faithscroll.app.data.Transcripts
import com.faithscroll.app.data.Video
import com.faithscroll.app.util.Lang
import com.faithscroll.app.util.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "What do they mean here?": opens ChatGPT in a browser tab (no login needed) with the question
 * already asked – the video link, the moment, and the captions from ~5 minutes around it.
 */
object AskChatGpt {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** [onBusy] shows/hides the loading screen (null = done); [openPage] shows ChatGPT inside the app. */
    fun open(ctx: Context, video: Video, atMs: Long, onBusy: (String?) -> Unit, onReady: (String) -> Unit) {
        scope.launch {
            onBusy(tr("Getting what was said…", "Henter det som ble sagt …"))
            val t0 = System.currentTimeMillis()
            var cues = withTimeoutOrNull(4_000) { Transcripts.original(video.id, urgent = true) }?.cues
            if (cues.isNullOrEmpty()) {
                // No YouTube captions: use what the phone already transcribed, or transcribe the last 90 s now.
                val have = com.faithscroll.app.data.Asr.cues(video.id).value
                // Already gathered in the background while you watched? Then it's instant.
                cues = if (have.any { it.end >= atMs - 8_000 && it.start <= atMs + 2_000 }) have else {
                    onBusy(tr("Transcribing the last minute…", "Transkriberer det siste minuttet …"))
                    withTimeoutOrNull(65_000) { Transcripts.transcribeWindow(ctx, video.id, atMs) }
                    com.faithscroll.app.data.Asr.cues(video.id).value
                }
            }
            val q = AskPrompt.build(video.id, video.title, video.channel, atMs, cues, Lang.no)
            Diag.log("ask", "${video.id} at ${AskPrompt.time(atMs)}: ${cues?.size ?: 0} caption lines, question ${q.length} chars in ${(System.currentTimeMillis() - t0) / 1000.0} s")
            val url = Uri.parse("https://chatgpt.com/").buildUpon()
                .apply { if (cues.isNullOrEmpty()) appendQueryParameter("hints", "search") } // no captions: let it look the video up
                .appendQueryParameter("q", q)
                .build()
            onBusy(null)
            onReady(url.toString()) // asked in the background; the answer appears in the app's own panel
        }
    }

    /** Phone transcription test: 60–90 s into a video, with the engine for its language. */
    private suspend fun speechTest(ctx: Context, id: String?): List<String> {
        id ?: return emptyList()
        val t0 = System.currentTimeMillis()
        withTimeoutOrNull(90_000) { Transcripts.transcribeWindow(ctx, id, 90_000) }
        val cues = com.faithscroll.app.data.Asr.cues(id).value.filter { it.start <= 95_000 }
        val lang = com.faithscroll.app.data.Asr.engineLang[id] ?: "?"
        val secs = "%.1f s".format((System.currentTimeMillis() - t0) / 1000.0)
        return listOf((if (cues.isNotEmpty()) "PASS" else "FAIL") + " phone transcription $id ($lang, $secs): ${cues.size} lines: '${cues.lastOrNull()?.text?.take(80).orEmpty()}'")
            .onEach { Diag.log("test", it) }
    }

    /** Self-test on this phone: do real videos give a transcript excerpt for the question? */
    suspend fun selfTest(ctx: Context, ids: List<Pair<String, String>>): List<String> = speechTest(ctx, ids.firstOrNull()?.first) + ids.map { (id, title) ->
        val t0 = System.currentTimeMillis()
        val t = withTimeoutOrNull(10_000) { Transcripts.original(id, urgent = true) }
        val secs = "%.1f s".format((System.currentTimeMillis() - t0) / 1000.0)
        if (t == null || t.cues.isEmpty()) "FAIL $id ($secs): no transcript – ${title.take(40)}"
        else {
            val at = (t.cues[t.cues.size / 2].start)
            val ex = AskPrompt.excerpt(t.cues, at)
            "PASS $id ($secs): ${t.cues.size} lines (${t.lang}), excerpt at ${AskPrompt.time(at)} = ${ex.lines().size} lines: '${ex.lines().lastOrNull { it.isNotBlank() }?.take(70)}' – ${title.take(30)}"
        }.also { Diag.log("test", it) }
    }
}
