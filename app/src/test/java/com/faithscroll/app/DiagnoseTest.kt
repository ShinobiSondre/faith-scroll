package com.faithscroll.app

import com.faithscroll.app.data.Ai
import com.faithscroll.app.data.Asr
import com.faithscroll.app.data.Http
import com.faithscroll.app.data.StreamResolver
import com.faithscroll.app.data.Transcripts
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Runs the real pipelines (YouTube streams, captions, audio download, free AI) on the build
 * machine and prints a report. These never fail the build; they're for finding what breaks.
 */
class DiagnoseTest {
    private fun report(s: String) = println("REPORT: $s")

    private fun videoIds(): List<String> {
        val ids = mutableListOf<String>()
        for (ch in listOf("UC9dK4qKU8-5xiqFoI9BGVmQ", "UCnrFlpro0xfYjz6s5Xa8WWw", "UCVfwlh9XpX2Y_tQfjeln9QA")) {
            val xml = Http.get("https://www.youtube.com/feeds/videos.xml?channel_id=$ch")
            if (xml == null) { report("RSS $ch: FAILED"); continue }
            ids += Regex("<yt:videoId>([^<]+)</yt:videoId>").findAll(xml).map { it.groupValues[1] }.take(2)
        }
        report("video ids: $ids")
        return ids
    }

    @Test
    fun captionsAndAudio() = runBlocking {
        for (id in videoIds().take(4)) {
            val r = runCatching { StreamResolver.resolve(id) }
            val rs = r.getOrNull()
            if (rs == null) { report("$id resolve FAILED: ${r.exceptionOrNull()}"); continue }
            report("$id resolve OK: ${rs.videos.size} video, ${rs.audios.size} audio (${rs.audios.joinToString { "${it.bitrate}k" }}), spoken='${rs.spoken}', subs=${rs.subtitles.joinToString { (if (it.auto) "auto:" else "") + it.lang }}")
            rs.subtitles.firstOrNull()?.let { t ->
                val body = Http.get(t.url, 15_000)
                report("$id caption fetch: ${body?.length ?: -1} chars, hasCues=${body?.contains("-->")}, head=${body?.take(80)?.replace("\n", " ")}")
                report("$id caption url: ${t.url.take(160)}")
            }
            val yt = runCatching { Transcripts.original(id) }
            report("$id Transcripts.original: ${yt.getOrNull()?.cues?.size ?: "null"} cues ${yt.exceptionOrNull() ?: ""}")
            yt.getOrNull()?.cues?.take(3)?.forEach { report("   ${it.start}ms: ${it.text}") }

            val dir = File("build/diag").apply { mkdirs() }
            val a = runCatching { Asr.downloadAudio(id, dir) }
            val f = a.getOrNull()
            report("$id audio download: ${f?.length() ?: -1} bytes ${a.exceptionOrNull() ?: ""}")
            if (f != null && f.length() > 0) {
                val probe = runCatching {
                    ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,sample_rate,channels", "-of", "csv=p=0", f.path)
                        .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
                }.getOrElse { "ffprobe unavailable" }
                report("$id audio decodes as: $probe")
            }
            break // one fully working video is enough detail
        }
    }

    @Test
    fun freeAi() = runBlocking {
        val r = runCatching {
            Ai.ask("Video: \"Test\" by Test\nTranscript around this moment:\n\"For God so loved the world that he gave his only Son\"", listOf(Ai.Turn(true, "What do they mean here?")))
        }
        val e = r.exceptionOrNull()
        report("AI: " + (r.getOrNull()?.take(200)?.replace("\n", " ") ?: "FAILED ${e?.javaClass?.simpleName} ${e?.message} ${(e as? Ai.RateLimited)?.seconds ?: ""}"))
    }
}
