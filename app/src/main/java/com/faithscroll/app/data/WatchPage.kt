package com.faithscroll.app.data

import com.faithscroll.app.potoken.PoTokenProviderImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Captions the way yt-dlp and NewPipe get them: read the video's YouTube page, which lists all
 * caption tracks (including the automatic one, whose language is the spoken language), then
 * download the captions with a proof-of-origin token. One page load + one download per video.
 */
object WatchPage {
    data class Track(val baseUrl: String, val lang: String, val kind: String, val name: String)
    data class Info(val tracks: List<Track>, val title: String, val description: String)

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, Info>()

    private fun norm(l: String) = l.substringBefore('-').lowercase().let { if (it == "nb" || it == "nn") "no" else it }

    /** The caption tracks listed on the video's page, or null if the page couldn't be read. */
    suspend fun info(videoId: String): Info? = withContext(Dispatchers.IO) {
        cache[videoId]?.let { return@withContext it }
        val html = runCatching {
            http.newCall(
                Request.Builder().url("https://www.youtube.com/watch?v=$videoId&hl=en&bpctr=9999999999&has_verified=1")
                    .header("User-Agent", UA)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", "SOCS=CAI; CONSENT=YES+1") // skip the cookie-consent page
                    .build()
            ).execute().use { it.body?.string() }
        }.onFailure { Diag.err("page", "$videoId page", it) }.getOrNull() ?: return@withContext null
        val json = extractJson(html, "ytInitialPlayerResponse") ?: run {
            Diag.log("page", "$videoId: no player data on page (${html.length} chars)"); return@withContext null
        }
        val pr = runCatching { JSONObject(json) }.getOrNull() ?: return@withContext null
        val status = pr.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        if (status.isNotBlank() && status != "OK") Diag.log("page", "$videoId: playability $status")
        val arr = pr.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks")
        val tracks = (0 until (arr?.length() ?: 0)).map { i ->
            val t = arr!!.getJSONObject(i)
            Track(
                t.optString("baseUrl"), norm(t.optString("languageCode")), t.optString("kind"),
                t.optJSONObject("name")?.let { n -> n.optString("simpleText").ifBlank { n.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty() } }.orEmpty()
            )
        }.filter { it.baseUrl.isNotBlank() }
        val vd = pr.optJSONObject("videoDetails")
        Info(tracks, vd?.optString("title").orEmpty(), vd?.optString("shortDescription").orEmpty()).also { cache[videoId] = it }
    }

    /** The spoken language: YouTube's automatic captions are made from the audio. */
    fun spoken(info: Info): String = info.tracks.firstOrNull { it.kind == "asr" }?.lang.orEmpty()

    /** Downloads one track as cues, optionally translated by YouTube (tlang). Tries with a proof-of-origin token first. */
    suspend fun fetch(videoId: String, track: Track, tlang: String? = null): List<Cue>? = withContext(Dispatchers.IO) {
        val base = track.baseUrl.replace(Regex("&fmt=[^&]*"), "") + "&fmt=json3" + (tlang?.let { "&tlang=$it" } ?: "")
        val tok = runCatching { PoTokenProviderImpl.getWebClientPoToken(videoId) }
            .onFailure { Diag.err("page", "token", it) }.getOrNull()
        val pot = tok?.playerRequestPoToken
        val urls = listOfNotNull(
            pot?.let { "$base&pot=$it&potc=1&c=WEB" },
            tok?.streamingDataPoToken?.let { "$base&pot=$it&potc=1&c=WEB" },
            base
        ).distinct()
        for (u in urls) {
            val body = runCatching {
                http.newCall(Request.Builder().url(u).header("User-Agent", UA).header("Cookie", "SOCS=CAI").build())
                    .execute().use { it.body?.string() }
            }.getOrNull()
            if (body != null && body.contains("\"events\"")) return@withContext parseJson3(body)
        }
        Diag.log("page", "$videoId: caption download refused (${track.lang}/${track.kind.ifBlank { "manual" }}${tlang?.let { " → $it" } ?: ""}, token=${pot != null})")
        null
    }

    fun parseJson3(body: String): List<Cue> = runCatching {
        val events = JSONObject(body).optJSONArray("events") ?: org.json.JSONArray()
        (0 until events.length()).mapNotNull { i ->
            val e = events.getJSONObject(i)
            val segs = e.optJSONArray("segs") ?: return@mapNotNull null
            val text = (0 until segs.length()).joinToString("") { segs.getJSONObject(it).optString("utf8") }.replace("\n", " ").trim()
            if (text.isBlank()) null else Cue(e.optLong("tStartMs"), e.optLong("tStartMs") + e.optLong("dDurationMs", 2000), text)
        }
    }.getOrDefault(emptyList())

    /** Pulls `var NAME = {...};` out of the page by matching braces (the JSON is large). */
    private fun extractJson(html: String, name: String): String? {
        val i = html.indexOf(name).takeIf { it >= 0 } ?: return null
        val start = html.indexOf('{', i).takeIf { it >= 0 } ?: return null
        var depth = 0; var inStr = false; var esc = false
        for (j in start until html.length) {
            val c = html[j]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return html.substring(start, j + 1) } }
        }
        return null
    }
}
