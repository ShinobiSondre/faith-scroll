package com.faithscroll.app.data

import com.faithscroll.app.potoken.PoTokenProviderImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
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
    /** [plain] = from YouTube's Android-app answer: its caption links download without any token. */
    data class Track(val baseUrl: String, val lang: String, val kind: String, val name: String, val plain: Boolean = false)
    data class Info(val tracks: List<Track>, val title: String, val description: String)

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    // Keeps YouTube's visitor cookies between the page, player and caption requests (like a browser session).
    private val jar = object : okhttp3.CookieJar {
        private val store = ConcurrentHashMap<String, okhttp3.Cookie>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) { cookies.forEach { store[it.name] = it } }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
            store.values.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
    }
    private val http = OkHttpClient.Builder().cookieJar(jar).connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    @Volatile private var apiKey: String? = null
    private const val ANDROID_VERSION = "20.10.38"

    private fun tracksOf(pr: JSONObject, plain: Boolean): List<Track> {
        val arr = pr.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks")
        return (0 until (arr?.length() ?: 0)).map { i ->
            val t = arr!!.getJSONObject(i)
            Track(
                t.optString("baseUrl"), norm(t.optString("languageCode")), t.optString("kind"),
                t.optJSONObject("name")?.let { n -> n.optString("simpleText").ifBlank { n.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty() } }.orEmpty(),
                plain
            )
        }.filter { it.baseUrl.isNotBlank() }
    }

    /** Same as youtube-transcript-api: ask YouTube's player API as the Android app. */
    private fun androidPlayer(videoId: String, key: String): JSONObject? = runCatching {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject().put("clientName", "ANDROID").put("clientVersion", ANDROID_VERSION)
                .put("hl", "en")))
            .put("videoId", videoId)
        http.newCall(
            Request.Builder().url("https://www.youtube.com/youtubei/v1/player?key=$key")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Accept-Language", "en-US")
                .build()
        ).execute().use { r -> r.body?.string()?.let { JSONObject(it) } }
    }.onFailure { Diag.err("page", "$videoId android player", it) }.getOrNull()
    private val cache = ConcurrentHashMap<String, Info>()

    private fun norm(l: String) = l.substringBefore('-').lowercase().let { if (it == "nb" || it == "nn") "no" else it }

    /** The caption tracks listed on the video's page, or null if the page couldn't be read. */
    suspend fun info(videoId: String): Info? = withContext(Dispatchers.IO) {
        cache[videoId]?.let { return@withContext it }
        val html = runCatching {
            http.newCall(
                Request.Builder().url("https://www.youtube.com/watch?v=$videoId")
                    .header("Accept-Language", "en-US")
                    .header("Cookie", "SOCS=CAI") // skip the cookie-consent page
                    .build()
            ).execute().use { it.body?.string() }
        }.onFailure { Diag.err("page", "$videoId page", it) }.getOrNull() ?: return@withContext null
        Regex("\"INNERTUBE_API_KEY\":\\s*\"([a-zA-Z0-9_-]+)\"").find(html)?.groupValues?.get(1)?.let { apiKey = it }
        val key = apiKey
        val page = extractJson(html, "ytInitialPlayerResponse")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val android = key?.let { androidPlayer(videoId, it) }
        if (android == null && page == null) { Diag.log("page", "$videoId: nothing readable (${html.length} chars, key=${key != null})"); return@withContext null }
        val ps = android?.optJSONObject("playabilityStatus")
        val status = ps?.optString("status").orEmpty()
        val tracks = android?.let { tracksOf(it, true) }.orEmpty().ifEmpty { page?.let { tracksOf(it, false) }.orEmpty() }
        Diag.log("page", "$videoId: android=${status.ifBlank { "-" }}${ps?.optString("reason")?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}, " +
            "tracks=${tracks.joinToString { it.lang + (if (it.kind == "asr") "*" else "") + (if (it.plain) "" else "(web)") }.ifBlank { "none" }}")
        val vd = android?.optJSONObject("videoDetails") ?: page?.optJSONObject("videoDetails")
        Info(tracks, vd?.optString("title").orEmpty(), vd?.optString("shortDescription").orEmpty()).also { cache[videoId] = it }
    }

    /** The spoken language: YouTube's automatic captions are made from the audio. */
    fun spoken(info: Info): String = info.tracks.firstOrNull { it.kind == "asr" }?.lang.orEmpty()

    /** Downloads one track as cues, optionally translated by YouTube (tlang). Tries with a proof-of-origin token first. */
    suspend fun fetch(videoId: String, track: Track, tlang: String? = null): List<Cue>? = withContext(Dispatchers.IO) {
        if (track.plain) {
            val url = track.baseUrl.replace("&fmt=srv3", "") + (tlang?.let { "&tlang=$it" } ?: "")
            val body = runCatching {
                http.newCall(Request.Builder().url(url).header("Accept-Language", "en-US").build()).execute().use { it.body?.string() }
            }.getOrNull().orEmpty()
            val cues = if (body.trimStart().startsWith("{")) parseJson3(body) else parseXml(body)
            if (cues.isNotEmpty()) return@withContext cues
            Diag.log("page", "$videoId: android caption link gave ${body.length} chars (${track.lang}${tlang?.let { " → $it" } ?: ""})")
        }
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

    /** YouTube's plain caption format: <text start="1.2" dur="3.4">words</text>. */
    fun parseXml(body: String): List<Cue> =
        Regex("<text start=\"([\\d.]+)\"(?: dur=\"([\\d.]+)\")?[^>]*>(.*?)</text>", RegexOption.DOT_MATCHES_ALL).findAll(body).mapNotNull { m ->
            val start = (m.groupValues[1].toDouble() * 1000).toLong()
            val dur = (m.groupValues[2].toDoubleOrNull() ?: 2.0) * 1000
            val text = android.text.Html.fromHtml(android.text.Html.fromHtml(m.groupValues[3], 0).toString(), 0).toString()
                .replace("\n", " ").trim()
            if (text.isBlank()) null else Cue(start, start + dur.toLong(), text)
        }.toList()

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
