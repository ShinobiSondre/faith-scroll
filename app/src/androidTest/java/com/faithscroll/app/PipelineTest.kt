package com.faithscroll.app

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.faithscroll.app.data.Ai
import com.faithscroll.app.data.Asr
import com.faithscroll.app.data.Diag
import com.faithscroll.app.data.Http
import com.faithscroll.app.data.StreamResolver
import com.faithscroll.app.data.Transcripts
import com.faithscroll.app.util.Lang
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * End-to-end checks of the subtitle and "What do they mean here?" pipeline on a real device,
 * against real videos. Each step logs one line under the FSTEST tag; CI turns those into notes.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PipelineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun report(step: String, ok: Boolean, detail: String) {
        Log.i("FSTEST", "${if (ok) "PASS" else "FAIL"} $step: ${detail.replace("\n", " ").take(400)}")
    }

    private fun diagTail(n: Int = 6) = Diag.lines.value.takeLast(n).joinToString(" | ")

    /** A current, English, talking video: the newest upload from BibleProject's channel. */
    private fun testVideo(): String {
        val xml = Http.get("https://www.youtube.com/feeds/videos.xml?channel_id=UCVfwlh9XpX2Y_tQfjeln9QA") ?: return "jNQXAC9IVRw"
        return Regex("<yt:videoId>([A-Za-z0-9_-]{11})</yt:videoId>").findAll(xml).map { it.groupValues[1] }.drop(1).firstOrNull() ?: "jNQXAC9IVRw"
    }

    @Before fun setup() {
        Lang.init(ctx)
        StreamResolver.setup(ctx)
    }

    @Test fun a_resolve() = runBlocking {
        val id = testVideo()
        val r = runCatching { StreamResolver.resolve(id) }
        val s = r.getOrNull()
        report("1 resolve $id", s != null,
            s?.let { "videos=${it.videos.size} audios=${it.audios.size} muxed=${it.muxed.size} hls=${it.hls != null} subs=${it.subtitles.map { t -> (if (t.auto) "auto:" else "") + t.lang }} spoken=${it.spoken}" }
                ?: (r.exceptionOrNull()?.toString() ?: "null") + " :: " + diagTail())
    }

    @Test fun b_youtubeCaptions() = runBlocking {
        val id = testVideo()
        val t = runCatching { Transcripts.original(id) }.getOrNull()
        report("2 youtube captions", t != null, t?.let { "lang=${it.lang} cues=${it.cues.size} first='${it.cues.firstOrNull()?.text}'" } ?: diagTail())
    }

    @Test fun c_playbackPast60s() {
        val id = testVideo()
        val stream = runBlocking { StreamResolver.resolve(id) }
        if (stream == null) { report("3 playback", false, "no stream"); return }
        val inst = InstrumentationRegistry.getInstrumentation()
        var player: ExoPlayer? = null
        var error: String? = null
        inst.runOnMainSync {
            player = ExoPlayer.Builder(ctx).build().apply {
                volume = 0f
                addListener(object : Player.Listener {
                    override fun onPlayerError(e: PlaybackException) { error = "${e.errorCodeName}: ${e.cause?.message}" }
                })
                setMediaSource(StreamResolver.mediaSource(id, stream, 480))
                prepare(); play()
            }
        }
        var pos = 0L
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 150_000 && error == null) {
            Thread.sleep(2000)
            inst.runOnMainSync { pos = player!!.currentPosition }
            if (pos > 75_000) break
        }
        inst.runOnMainSync { player!!.release() }
        report("3 native playback", error == null && pos > 75_000, "reached ${pos / 1000}s, error=$error")
    }

    @Test fun d_audioDownload() = runBlocking {
        val id = testVideo()
        val f = runCatching { Asr.audioFile(ctx, id) }
        report("4 audio download", f.getOrNull() != null && f.getOrNull()!!.length() > 50_000,
            f.getOrNull()?.let { "${it.length() / 1024} KB" } ?: (f.exceptionOrNull()?.toString() ?: "null") + " :: " + diagTail())
    }

    @Test fun e_speechRecognition() = runBlocking {
        val id = testVideo()
        Asr.ensure(ctx, id, 0, maxMs = 60_000)
        val live = Asr.live(id)
        withTimeoutOrNull(8 * 60_000L) {
            while (live.cues.value.isEmpty() && live.state.value != Asr.State.FAILED && live.state.value != Asr.State.DONE) delay(2000)
        }
        val cues = live.cues.value
        report("5 speech recognition", cues.isNotEmpty(),
            "state=${live.state.value} error=${live.error} cues=${cues.size} sample='${cues.take(3).joinToString(" / ") { it.text }}' :: ${diagTail()}")
    }

    /** The speech engine itself, on a known English recording (independent of YouTube). */
    @Test fun e2_speechEngineOnSample() = runBlocking {
        val f = java.io.File(ctx.cacheDir, "sample.wav")
        val ok = runCatching {
            okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder()
                .url("https://raw.githubusercontent.com/alphacep/vosk-api/master/python/example/test.wav").build())
                .execute().use { r -> f.writeBytes(r.body!!.bytes()) }
        }
        if (ok.isFailure) { report("5b speech engine (sample)", false, "download: ${ok.exceptionOrNull()}"); return@runBlocking }
        val live = runCatching { Asr.transcribeFile(ctx, "sample", f) }
        val cues = live.getOrNull()?.cues?.value.orEmpty()
        report("5b speech engine (sample)", cues.isNotEmpty(),
            "state=${live.getOrNull()?.state?.value} error=${live.getOrNull()?.error ?: live.exceptionOrNull()} lines=${cues.size} text='${cues.joinToString(" / ") { it.text }.take(200)}' :: ${diagTail(4)}")
        // And the same lines in Norwegian, as the subtitles would show them
        Lang.setMode(ctx, Lang.Mode.NO)
        val no = cues.take(2).map { Transcripts.translateLine(it.text) }
        Lang.setMode(ctx, Lang.Mode.EN)
        report("5c subtitles in Norwegian", no.isNotEmpty() && no.zip(cues).any { (a, b) -> a != b.text }, no.joinToString(" / "))
    }

    @Test fun f_translate() = runBlocking {
        Lang.setMode(ctx, Lang.Mode.NO)
        val out = runCatching { Transcripts.translateLine("God loves you and he is always with you.") }
        Lang.setMode(ctx, Lang.Mode.EN)
        report("6 translate en→no", out.getOrNull()?.let { it != "God loves you and he is always with you." } == true, out.getOrNull() ?: out.exceptionOrNull().toString())
    }

    @Test fun g_ai() = runBlocking {
        val r = runCatching {
            Ai.ask("Video: \"Grace\" by BibleProject\nTranscript around this moment:\n\"Grace is a gift you don't earn.\"",
                listOf(Ai.Turn(true, "What do they mean here?")))
        }
        report("7 free AI", r.isSuccess && r.getOrNull()!!.length > 20,
            r.getOrNull()?.take(200) ?: (r.exceptionOrNull()?.let { if (it is Ai.RateLimited) "rate limited ${it.seconds}s" else it.toString() } ?: "null"))
    }
}
