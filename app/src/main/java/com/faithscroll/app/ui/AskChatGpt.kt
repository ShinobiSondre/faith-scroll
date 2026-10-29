package com.faithscroll.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.faithscroll.app.data.Video
import com.faithscroll.app.util.Lang

/**
 * "What do they mean here?": opens ChatGPT in a browser tab (no login needed) with the video link
 * and the exact moment already asked, just like typing it on chatgpt.com yourself.
 */
object AskChatGpt {
    fun open(ctx: Context, video: Video, atMs: Long) {
        val sec = (atMs / 1000).toInt()
        val time = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
        val link = "https://www.youtube.com/watch?v=${video.id}&t=${sec}s"
        val q = if (Lang.no)
            "Se på denne YouTube-videoen: $link («${video.title}» av ${video.channel}). Hva mener de ved $time? Forklar kort på norsk, med sammenhengen rundt."
        else
            "Look at this YouTube video: $link (\"${video.title}\" by ${video.channel}). What do they mean at $time? Explain briefly with the context around it."
        val url = Uri.parse("https://chatgpt.com/").buildUpon()
            .appendQueryParameter("hints", "search") // lets ChatGPT look the video up
            .appendQueryParameter("q", q)
            .build()
        runCatching {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, url)
        }.onFailure {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
