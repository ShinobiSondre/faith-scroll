package com.faithscroll.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.faithscroll.app.data.Http
import com.faithscroll.app.data.StreamResolver
import com.faithscroll.app.data.Transcripts
import com.faithscroll.app.data.Video
import com.faithscroll.app.data.VideoCheck
import com.faithscroll.app.data.WatchPage
import com.faithscroll.app.data.YouTubeAccount
import com.faithscroll.app.util.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Does the feed get videos, with working captions, in the right language, quickly?
 * Every line goes to logcat under FSTEST; CI shows them as a note on the build.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FeedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun log(s: String) = Log.i("FSTEST", s.replace("\n", " ").take(500))
    private fun secs(t0: Long) = "%.1f s".format((System.currentTimeMillis() - t0) / 1000.0)

    private val channels = listOf(
        "UCVfwlh9XpX2Y_tQfjeln9QA" to "BibleProject",
        "UC9dK4qKU8-5xiqFoI9BGVmQ" to "I Am Second",
        "UCdMgPQvQhsdd-qD-fugvlSA" to "700 Club Testimonies",
        "UCnrFlpro0xfYjz6s5Xa8WWw" to "Desiring God",
    )

    private fun videos(): List<Video> = channels.flatMap { (id, name) ->
        val xml = Http.get("https://www.youtube.com/feeds/videos.xml?channel_id=$id").orEmpty()
        Regex("<yt:videoId>([A-Za-z0-9_-]{11})</yt:videoId>\\s*<yt:channelId>[^<]*</yt:channelId>\\s*<title>([^<]*)</title>")
            .findAll(xml).take(3).map { Video(it.groupValues[1], it.groupValues[2], name) }.toList()
    }

    @Before fun setup() {
        Lang.init(ctx)
        StreamResolver.setup(ctx)
        com.faithscroll.app.potoken.AppContext.set(ctx)
    }

    @Test fun a_captionsPerVideo() = runBlocking<Unit> {
        val vids = videos()
        log("== Captions: ${vids.size} videos from ${channels.size} channels (3 at a time)")
        val t0 = System.currentTimeMillis()
        val gate = Semaphore(3)
        val res = vids.map { v ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val t = System.currentTimeMillis()
                    val info = WatchPage.info(v.id)
                    val page = secs(t)
                    if (info == null) { log("FAIL ${v.id} page unreadable ($page) – ${v.title.take(40)}"); return@withPermit false }
                    val tracks = info.tracks.joinToString { it.lang + if (it.kind == "asr") "(auto)" else "" }
                    val orig = Transcripts.original(v.id)
                    val tr = info.tracks.firstOrNull { it.kind == "asr" } ?: info.tracks.firstOrNull()
                    val no = tr?.let { WatchPage.fetch(v.id, it, "no") }
                    val ok = orig != null && orig.cues.isNotEmpty()
                    log("${if (ok) "PASS" else "FAIL"} ${v.id} page=$page total=${secs(t)} tracks=[$tracks] spoken=${WatchPage.spoken(info).ifBlank { "?" }} " +
                        "lines=${orig?.cues?.size ?: 0} norsk=${no?.size ?: 0} '${orig?.cues?.getOrNull(3)?.text?.take(50).orEmpty()}' / '${no?.getOrNull(3)?.text?.take(50).orEmpty()}' – ${v.title.take(40)}")
                    ok
                }
            }
        }.awaitAll()
        log("== Captions: ${res.count { it }}/${res.size} working, all done in ${secs(t0)}")
    }

    @Test fun b_feedVerification() = runBlocking<Unit> {
        for (mode in listOf(Lang.Mode.EN, Lang.Mode.NO)) {
            Lang.setMode(ctx, mode)
            File(ctx.filesDir, "video_checks_v2.json").delete()
            val vids = videos()
            val t0 = System.currentTimeMillis()
            VideoCheck.queue(ctx, vids)
            delay(500)
            var firstAt: String? = null
            withTimeoutOrNull(180_000) {
                while (VideoCheck.checking.value) {
                    if (firstAt == null && VideoCheck.verified.value.isNotEmpty()) firstAt = secs(t0)
                    delay(200)
                }
            }
            val ok = vids.count { it.id in VideoCheck.verified.value }
            log("== Feed ($mode): $ok/${vids.size} videos let through; first after ${firstAt ?: secs(t0)}, all checked in ${secs(t0)}")
        }
        Lang.setMode(ctx, Lang.Mode.AUTO)
    }

    @Test fun c_audioLanguage() = runBlocking<Unit> {
        // Videos with many dubbed audio tracks: we must pick English (or Norwegian), never e.g. Spanish.
        for (id in listOf("La6T0NEXP8g", "KUJ3DxCZXlY")) {
            val r = runCatching { StreamResolver.resolve(id) }.getOrNull()
            if (r == null) { log("SKIP audio $id: couldn't load streams"); continue }
            val picked = StreamResolver.preferredAudio(r).firstOrNull()
            log("${if (picked?.lang == "en" || picked?.lang == "no") "PASS" else "FAIL"} audio $id: ${r.audios.size} tracks, picked '${picked?.lang}'")
        }
    }

    @Test fun d_christianChannels() = runBlocking<Unit> {
        val list = listOf(
            "UCVfwlh9XpX2Y_tQfjeln9QA" to "BibleProject", "UC9dK4qKU8-5xiqFoI9BGVmQ" to "I Am Second",
            "UCIQqvZbHSwX0yKNVK1MyYjQ" to "Elevation Church", "UCBJycsmduvYEL83R_U4JriQ" to "Marques Brownlee",
            "UCHnyfMqiRRG1u-2MsSQLbXA" to "Veritasium",
        )
        val t0 = System.currentTimeMillis()
        val x = YouTubeAccount.christianChannels(list)
        val names = list.filter { it.first in x }.map { it.second }
        val ok = "Marques Brownlee" !in names && "Veritasium" !in names && "Elevation Church" in names
        log("${if (ok) "PASS" else "FAIL"} Christian channels: $names (${secs(t0)})")
    }

    @Test fun e_languageFromText() = runBlocking<Unit> {
        val cases = listOf(
            "JESUS FORANDRET MITT LIV" to "no",
            "Vidar Brautaset: Kraften i Guds Ord – preken fra søndagens gudstjeneste" to "no",
            "Dagens andakt: Herren er min hyrde, jeg mangler ikke noe" to "no",
            "The Secret to Being a Good Friend – Live sermon on friendship" to "en",
            "Testimonio poderoso: Dios cambió mi vida para siempre" to "es",
        )
        for ((text, want) in cases) {
            val got = com.faithscroll.app.data.LangFilter.spokenFromText(text)
            log("${if (got == want) "PASS" else "FAIL"} language '$text' → $got (want $want)")
        }
    }
}
