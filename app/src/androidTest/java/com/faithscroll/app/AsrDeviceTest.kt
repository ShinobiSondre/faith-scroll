package com.faithscroll.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.faithscroll.app.data.Asr
import com.faithscroll.app.data.Diag
import com.faithscroll.app.data.Http
import com.faithscroll.app.data.StreamResolver
import com.faithscroll.app.data.Transcripts
import com.faithscroll.app.util.Lang
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the phone's own subtitle pipeline (download model, audio, decode, recognise, translate) on an emulator. */
@RunWith(AndroidJUnit4::class)
class AsrDeviceTest {
    private fun report(s: String) = Log.i("FaithScrollTest", s)

    @Test
    fun phoneMadeSubtitles() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        StreamResolver.setup(ctx)
        val xml = Http.get("https://www.youtube.com/feeds/videos.xml?channel_id=UC9dK4qKU8-5xiqFoI9BGVmQ")
        val id = xml?.let { Regex("<yt:videoId>([^<]+)</yt:videoId>").find(it)?.groupValues?.get(1) }
        report("video: $id")
        if (id == null) return@runBlocking
        val rs = StreamResolver.resolve(id)
        report("resolve: ${rs?.audios?.size} audio tracks, subs=${rs?.subtitles?.map { it.lang }}")
        report("youtube captions: ${Transcripts.original(id)?.cues?.size ?: "none"}")

        Asr.ensure(ctx, id, 0, maxMs = 60_000)
        val live = Asr.live(id)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 8 * 60_000) {
            val st = live.state.value
            if (live.cues.value.size >= 3 || st == Asr.State.FAILED || st == Asr.State.DONE) break
            delay(3000)
        }
        report("asr: state=${live.state.value} error=${live.error} cues=${live.cues.value.size} after ${(System.currentTimeMillis() - start) / 1000}s")
        live.cues.value.take(5).forEach { report("   ${it.start}ms: ${it.text}") }

        Lang.setMode(ctx, Lang.Mode.NO)
        val line = live.cues.value.firstOrNull()?.text ?: "God loves you and he is with you"
        val t = runCatching { Transcripts.translateLine(line) }
        report("translate → no: ${t.getOrNull() ?: t.exceptionOrNull()}")
        Diag.lines.value.forEach { report("diag: $it") }
    }
}
