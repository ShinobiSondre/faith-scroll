package com.faithscroll.app

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.faithscroll.app.data.CaptionGrabber
import com.faithscroll.app.data.Diag
import com.faithscroll.app.data.Http
import com.faithscroll.app.data.Transcripts
import com.faithscroll.app.data.Video
import com.faithscroll.app.data.VideoCheck
import com.faithscroll.app.util.Lang
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real app and its hidden YouTube caption player on real videos; reports each step under FSTEST. */
@RunWith(AndroidJUnit4::class)
class GrabberTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun report(msg: String) = Log.i("FSTEST", msg.replace("\n", " ").take(450))

    private fun latest(channel: String, n: Int): List<Pair<String, String>> {
        val xml = Http.get("https://www.youtube.com/feeds/videos.xml?channel_id=$channel") ?: return emptyList()
        val ids = Regex("<yt:videoId>([A-Za-z0-9_-]{11})</yt:videoId>").findAll(xml).map { it.groupValues[1] }.toList()
        val titles = Regex("<media:title>([^<]*)</media:title>").findAll(xml).map { it.groupValues[1] }.toList()
        return ids.zip(titles).take(n)
    }

    @Test fun captionsAndChecks() {
        Lang.init(ctx)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(4000)
        val vids = latest("UCVfwlh9XpX2Y_tQfjeln9QA", 3) + latest("UCQmUmqrMGfnesNpdL7T282Q", 2) + latest("UCnrFlpro0xfYjz6s5Xa8WWw", 2)
        report("videos from RSS: ${vids.size}")
        runBlocking {
            for ((id, title) in vids) {
                val t0 = System.currentTimeMillis()
                val link = withTimeoutOrNull(25_000) { CaptionGrabber.grab(id, urgent = true) }
                val t1 = System.currentTimeMillis()
                val tracks = CaptionGrabber.tracks(id)
                val tr = withTimeoutOrNull(25_000) { Transcripts.original(id) }
                val t2 = System.currentTimeMillis()
                report("$id link=${link != null} in ${(t1 - t0) / 1000.0}s tracks=$tracks transcript=${tr?.cues?.size ?: "none"} lang=${tr?.lang} spoken=${tr?.spoken} in ${(t2 - t1) / 1000.0}s first='${tr?.cues?.firstOrNull()?.text?.take(60)}' :: ${title.take(40)}")
            }
            // The full check, as the feed runs it
            VideoCheck.queue(ctx, vids.map { Video(it.first, it.second, "test") })
            withTimeoutOrNull(120_000) { while (VideoCheck.checking.value || VideoCheck.verified.value.isEmpty()) delay(1000) }
            report("verified ${VideoCheck.verified.value.size}/${vids.size}")
        }
        Diag.lines.value.filter { "[subs]" in it || "[check]" in it }.takeLast(14).forEach { report("diag $it") }
        scenario.close()
    }
}
