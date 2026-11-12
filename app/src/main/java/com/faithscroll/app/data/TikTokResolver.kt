package com.faithscroll.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A TikTok's own video file, so it plays in the app's player like a YouTube video (no TikTok
 * player or controls). The video's web page lists the file; the file only opens with the cookies
 * that page set, so those are sent along when playing.
 */
object TikTokResolver {
    data class Stream(val url: String, val headers: Map<String, String>, val durationMs: Long, val caption: String)

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    private val cache = ConcurrentHashMap<String, Pair<Long, Stream>>()
    /** Who posted each TikTok (its page address needs it). */
    val authors = ConcurrentHashMap<String, String>()
    private const val TTL = 30 * 60 * 1000L

    /** TikTok ids are long numbers; YouTube ids are 11 letters and digits. */
    fun isTikTokId(id: String) = id.length >= 15 && id.all { it.isDigit() }

    fun cached(id: String): Stream? = cache[id]?.takeIf { System.currentTimeMillis() - it.first < TTL }?.second

    suspend fun resolve(id: String, author: String = authors[id].orEmpty()): Stream? = cached(id) ?: withContext(Dispatchers.IO) {
        val jar = object : CookieJar {
            val store = mutableListOf<Cookie>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { synchronized(store) { cookies.forEach { c -> store.removeAll { it.name == c.name }; store += c } } }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) { store.filter { it.matches(url) } }
        }
        val http = OkHttpClient.Builder().cookieJar(jar).connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        val page = "https://www.tiktok.com/" + (if (author.isNotBlank()) "@$author" else "@_") + "/video/$id"
        runCatching {
            val html = http.newCall(
                Request.Builder().url(page).header("User-Agent", UA).header("Accept-Language", "en-US,en;q=0.9").header("Referer", "https://www.tiktok.com/").build()
            ).execute().use { it.body?.string().orEmpty() }
            val json = Regex("<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
                ?: error("no video data on the page (${html.length} chars)")
            val item = JSONObject(json).optJSONObject("__DEFAULT_SCOPE__")?.optJSONObject("webapp.video-detail")
                ?.optJSONObject("itemInfo")?.optJSONObject("itemStruct") ?: error("no item")
            val v = item.optJSONObject("video") ?: error("no video")
            val url = v.optString("playAddr").ifBlank { v.optString("downloadAddr") }.ifBlank { error("no file") }
            val cookies = synchronized(jar.store) { jar.store.joinToString("; ") { "${it.name}=${it.value}" } }
            Stream(url, mapOf("Cookie" to cookies, "Referer" to "https://www.tiktok.com/"), v.optLong("duration") * 1000, item.optString("desc"))
        }.onFailure { Diag.log("tiktok", "$id: couldn't get the video file – ${it.message}") }
            .getOrNull()?.also { cache[id] = System.currentTimeMillis() to it }
    }
}
