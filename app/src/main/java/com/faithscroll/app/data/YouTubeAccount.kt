package com.faithscroll.app.data

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Your YouTube account, signed in on YouTube's own sign-in page inside the app (like Grayjay does).
 * The login stays in the app's browser storage on this phone; we only use it to read the list of
 * channels you subscribe to, from YouTube's "Subscriptions → Channels" page.
 */
object YouTubeAccount {
    const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Den%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252Ffeed%252Fchannels"
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val ORIGIN = "https://www.youtube.com"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private fun cookies(): String = runCatching { CookieManager.getInstance().getCookie(ORIGIN) }.getOrNull().orEmpty()
    private fun cookie(all: String, name: String) = all.split(";").map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    /** True once YouTube's login cookies are present. */
    fun signedIn(): Boolean = cookies().let { cookie(it, "SAPISID") != null || cookie(it, "__Secure-3PAPISID") != null }

    fun signOut() {
        runCatching { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The same "Authorization" header youtube.com sends for signed-in requests. */
    private fun auth(all: String): String? {
        val ts = System.currentTimeMillis() / 1000
        val parts = listOfNotNull(
            (cookie(all, "SAPISID") ?: cookie(all, "__Secure-3PAPISID"))?.let { "SAPISIDHASH ${ts}_${sha1("$ts $it $ORIGIN")}" },
            cookie(all, "__Secure-1PAPISID")?.let { "SAPISID1PHASH ${ts}_${sha1("$ts $it $ORIGIN")}" },
            cookie(all, "__Secure-3PAPISID")?.let { "SAPISID3PHASH ${ts}_${sha1("$ts $it $ORIGIN")}" },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** Reads every channel you subscribe to: (channel id, name). */
    suspend fun subscriptions(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val all = cookies()
        if (!signedIn()) error("Not signed in to YouTube")
        val page = http.newCall(
            Request.Builder().url("$ORIGIN/feed/channels?hl=en").header("User-Agent", UA).header("Cookie", all)
                .header("Accept-Language", "en-US,en;q=0.9").build()
        ).execute().use { it.body?.string().orEmpty() }
        if (!page.contains("\"LOGGED_IN\":true")) error("YouTube says you're not signed in – try signing in again")
        val version = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(page)?.groupValues?.get(1) ?: "2.20241010.00.00"
        val key = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(page)?.groupValues?.get(1)
        val authUser = Regex("\"SESSION_INDEX\":\"?(\\d+)").find(page)?.groupValues?.get(1) ?: "0"
        val data = extract(page, "ytInitialData") ?: error("Couldn't read YouTube's subscriptions page")

        val found = LinkedHashMap<String, String>()
        var token = walk(JSONObject(data), found)
        var pages = 0
        while (token != null && pages++ < 60) {
            val body = JSONObject()
                .put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB").put("clientVersion", version).put("hl", "en").put("gl", "US")))
                .put("continuation", token)
            val req = Request.Builder()
                .url("$ORIGIN/youtubei/v1/browse?prettyPrint=false" + (key?.let { "&key=$it" } ?: ""))
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", UA).header("Cookie", all)
                .header("Origin", ORIGIN).header("X-Origin", ORIGIN).header("Referer", "$ORIGIN/feed/channels")
                .header("X-Goog-AuthUser", authUser)
                .header("X-Youtube-Client-Name", "1").header("X-Youtube-Client-Version", version)
                .apply { auth(all)?.let { header("Authorization", it) } }
                .build()
            val text = http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: break
            token = walk(JSONObject(text), found)
        }
        Diag.log("youtube", "read ${found.size} subscriptions ($pages more pages)")
        found.map { it.key to it.value }
    }

    /** Collects channel entries and returns the "load more" token, if any. */
    private fun walk(node: Any?, out: MutableMap<String, String>): String? {
        var next: String? = null
        when (node) {
            is JSONObject -> {
                node.optJSONObject("channelRenderer")?.let { c ->
                    val id = c.optString("channelId")
                    val title = c.optJSONObject("title")?.let { t -> t.optString("simpleText").ifBlank { t.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty() } }.orEmpty()
                    if (id.startsWith("UC")) out[id] = title.ifBlank { id }
                }
                node.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotBlank() }?.let { next = it }
                for (k in node.keys()) if (k != "channelRenderer") walk(node.opt(k), out)?.let { next = it }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), out)?.let { next = it }
        }
        return next
    }

    private fun extract(html: String, name: String): String? {
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

    // ---- Which of your channels are Christian? ----

    private val christianWords = Regex(
        "\\b(jesus|christ|christian|church|bible|biblical|gospel|god|lord|faith|pray|prayer|worship|sermon|pastor|ministr(y|ies)|" +
            "scripture|holy spirit|psalm|devotion(al)?|apologetics|theolog|catholic|orthodox|baptist|evangel|preach|testimon|salvation|" +
            "kirke|kirken|bibel|bibelen|gud|herren|bønn|tro|frelse|menighet|forkynnelse|lovsang)",
        RegexOption.IGNORE_CASE
    )

    /** Does a channel look Christian: its name, or most of its recent video titles. */
    suspend fun christianChannels(channels: List<Pair<String, String>>, onProgress: (Int) -> Unit = {}): Set<String> = coroutineScope {
        val gate = Semaphore(8)
        var done = 0
        channels.map { (id, name) ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val yes = christianWords.containsMatchIn(name) || runCatching {
                        val xml = http.newCall(Request.Builder().url("https://www.youtube.com/feeds/videos.xml?channel_id=$id").build())
                            .execute().use { it.body?.string().orEmpty() }
                        val titles = Regex("<media:title>([^<]*)</media:title>").findAll(xml).map { it.groupValues[1] }.toList()
                        titles.isNotEmpty() && titles.count { christianWords.containsMatchIn(it) } * 3 >= titles.size // ≥ a third
                    }.getOrDefault(false)
                    synchronized(this@YouTubeAccount) { onProgress(++done) }
                    if (yes) id else null
                }
            }
        }.awaitAll().filterNotNull().toSet()
    }
}
