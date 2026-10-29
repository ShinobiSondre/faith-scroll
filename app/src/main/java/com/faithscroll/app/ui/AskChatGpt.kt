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

    fun open(ctx: Context, video: Video, atMs: Long) {
        scope.launch {
            Toast.makeText(ctx, tr("Opening ChatGPT…", "Åpner ChatGPT …"), Toast.LENGTH_SHORT).show()
            val t0 = System.currentTimeMillis()
            val cues = withTimeoutOrNull(5_000) { Transcripts.original(video.id, urgent = true) }?.cues
            val q = AskPrompt.build(video.id, video.title, video.channel, atMs, cues, Lang.no)
            Diag.log("ask", "${video.id} at ${AskPrompt.time(atMs)}: ${cues?.size ?: 0} caption lines, question ${q.length} chars in ${(System.currentTimeMillis() - t0) / 1000.0} s")
            val url = Uri.parse("https://chatgpt.com/").buildUpon()
                .apply { if (cues.isNullOrEmpty()) appendQueryParameter("hints", "search") } // no captions: let it look the video up
                .appendQueryParameter("q", q)
                .build()
            runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, url) }
                .onFailure { ctx.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Self-test on this phone: do real videos give a transcript excerpt for the question? */
    suspend fun selfTest(ids: List<Pair<String, String>>): List<String> = ids.map { (id, title) ->
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
