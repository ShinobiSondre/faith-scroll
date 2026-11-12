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

    private val threeLetter = mapOf("eng" to "en", "nob" to "no", "nno" to "no", "nor" to "no", "nb" to "no", "nn" to "no", "deu" to "de", "ger" to "de",
        "swe" to "sv", "dan" to "da", "spa" to "es", "por" to "pt", "fra" to "fr", "fre" to "fr", "ita" to "it", "nld" to "nl", "pol" to "pl",
        "rus" to "ru", "ukr" to "uk", "ara" to "ar", "ind" to "id", "fil" to "tl", "tgl" to "tl", "kor" to "ko", "jpn" to "ja", "zho" to "zh", "cmn" to "zh")
    private fun norm(code: String): String = code.lowercase().substringBefore('-').substringBefore('_').let { threeLetter[it] ?: it }

    /** Same rule as YouTube videos: Norwegian (plus English if you've chosen that), or English in the English app. */
    fun languageOk(lang: String): Boolean {
        val no = com.faithscroll.app.util.Lang.no
        return if (no) lang == "no" || (com.faithscroll.app.util.Lang.alsoEnglish && lang == "en") else lang == "en"
    }

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
            // The language: TikTok's own speech captions say what's spoken; else the language of the text.
            val subs = v.optJSONArray("subtitleInfos")
            val spoken = (0 until (subs?.length() ?: 0)).map { subs!!.getJSONObject(it) }
                .firstOrNull { it.optString("Source").equals("ASR", true) }?.optString("LanguageCodeName").orEmpty()
            val lang = norm(spoken).ifBlank { norm(item.optString("textLanguage")) }.ifBlank { LangFilter.detect(item.optString("desc")).orEmpty() }
            if (lang.isNotBlank() && lang != "un" && !languageOk(lang)) {
                Diag.log("tiktok", "$id: left out – '$lang' (${if (spoken.isNotBlank()) "speech" else "text"}) isn't the app language")
                LangFilter.block(id, lang)
                TikTokFinder.drop(id)
            }
            val cookies = synchronized(jar.store) { jar.store.joinToString("; ") { "${it.name}=${it.value}" } }
            Stream(url, mapOf("Cookie" to cookies, "Referer" to "https://www.tiktok.com/"), v.optLong("duration") * 1000, item.optString("desc"))
        }.onFailure { Diag.log("tiktok", "$id: couldn't get the video file – ${it.message}") }
            .getOrNull()?.also { cache[id] = System.currentTimeMillis() to it }
    }
}
