package com.faithscroll.app.data

import android.content.Context
import android.util.Xml
import com.faithscroll.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** An image pin (Christian quote / illustration) from a public Pinterest board. */
data class Pin(val id: String, val image: String, val title: String, val link: String, val board: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("image", image).put("title", title).put("link", link).put("board", board)
    companion object {
        fun from(o: JSONObject) = Pin(o.getString("id"), o.getString("image"), o.optString("title"), o.optString("link"), o.optString("board"))
    }
}

/** A TikTok video, played with TikTok's official embedded player. */
data class TikTok(val id: String, val author: String) {
    val url: String get() = if (author.isNotBlank()) "https://www.tiktok.com/@$author/video/$id" else "https://www.tiktok.com/video/$id"
}

data class SocialLibrary(val pins: List<Pin>, val tiktoks: List<TikTok>, val clips: List<Video> = emptyList())

/**
 * Pulls illustrated Christian quotes from public Pinterest boards (via their RSS feeds) and
 * TikToks listed in content/channels.json or added by you in the app.
 */
object SocialRepository {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    val defaultBoards = listOf(
        "livefromtheword/bible-verse-wallpaper",
        "edendiaries/christian-quotes",
        "printablethetruth/bible-verse-wallpaper",
        "srhhrndz/bible-verse-wallpaper",
        "jdutoit987/christian-bible-quotes"
    )

    val defaultSubreddits = listOf(
        "Christianity", "TrueChristian", "Catholicism", "OrthodoxChristianity", "ChristianArt", "dankchristianmemes"
    )

    // ---- Your own sources (added in the Me tab) ----
    fun userSubs(ctx: Context): List<String> = prefs(ctx).getString("subs", "")!!.split("\n").filter { it.isNotBlank() }
    private fun saveSubs(ctx: Context, l: List<String>) = prefs(ctx).edit().putString("subs", l.distinctBy { it.lowercase() }.joinToString("\n")).apply()
    fun removeSub(ctx: Context, s: String) = saveSubs(ctx, userSubs(ctx) - s)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("sources", Context.MODE_PRIVATE)
    fun userBoards(ctx: Context): List<String> = prefs(ctx).getString("boards", "")!!.split("\n").filter { it.isNotBlank() }
    fun userTikToks(ctx: Context): List<TikTok> = prefs(ctx).getString("tiktoks", "")!!.split("\n").filter { it.contains("|") }
        .map { it.split("|").let { p -> TikTok(p[0], p.getOrElse(1) { "" }) } }

    private fun saveBoards(ctx: Context, l: List<String>) = prefs(ctx).edit().putString("boards", l.distinct().joinToString("\n")).apply()
    private fun saveTikToks(ctx: Context, l: List<TikTok>) =
        prefs(ctx).edit().putString("tiktoks", l.distinctBy { it.id }.joinToString("\n") { "${it.id}|${it.author}" }).apply()

    fun removeBoard(ctx: Context, b: String) = saveBoards(ctx, userBoards(ctx) - b)
    fun removeTikTok(ctx: Context, id: String) = saveTikToks(ctx, userTikToks(ctx).filterNot { it.id == id })

    /** Accepts a Pinterest board link or a TikTok video link. Returns a short description, or null if not understood. */
    suspend fun addFromLink(ctx: Context, raw: String): String? = withContext(Dispatchers.IO) {
        var link = raw.trim()
        if (link.isBlank()) return@withContext null
        if (!link.startsWith("http")) link = "https://$link"
        // Short share links (pin.it, vm.tiktok.com) redirect to the real address.
        if (Regex("//(pin\\.it|vm\\.tiktok\\.com|vt\\.tiktok\\.com)/").containsMatchIn(link)) link = resolveRedirect(link) ?: link

        Regex("tiktok\\.com/@([^/?#]+)/(?:video|photo)/(\\d+)").find(link)?.let { m ->
            saveTikToks(ctx, userTikToks(ctx) + TikTok(m.groupValues[2], m.groupValues[1]))
            return@withContext "TikTok from @${m.groupValues[1]}"
        }
        Regex("tiktok\\.com/.*?(\\d{15,})").find(link)?.let { m ->
            saveTikToks(ctx, userTikToks(ctx) + TikTok(m.groupValues[1], ""))
            return@withContext "TikTok video"
        }
        Regex("(?:reddit\\.com/|^https://)r/([A-Za-z0-9_]{2,30})").find(link)?.let { m ->
            saveSubs(ctx, userSubs(ctx) + m.groupValues[1])
            return@withContext "r/${m.groupValues[1]}"
        }
        Regex("pinterest\\.[a-z.]+/([^/?#]+)/([^/?#]+)").find(link)?.let { m ->
            val (user, board) = m.destructured
            if (user != "pin" && user != "search" && user != "ideas") {
                saveBoards(ctx, userBoards(ctx) + "$user/$board")
                return@withContext "Pinterest board $user/$board"
            }
        }
        null
    }

    private fun resolveRedirect(url: String): String? = runCatching {
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.connectTimeout = 8000; c.readTimeout = 8000
            val code = c.responseCode
            val loc = c.getHeaderField("Location")
            c.disconnect()
            if (code in 300..399 && loc != null) u = URL(u, loc) else return@runCatching u.toString()
        }
        u.toString()
    }.getOrNull()

    // ---- Loading ----

    suspend fun load(ctx: Context): SocialLibrary = withContext(Dispatchers.IO) {
        var boards = defaultBoards
        var tiktoks = emptyList<TikTok>()
        var subs = defaultSubreddits
        val repo = BuildConfig.CONTENT_REPO
        if (repo.isNotBlank()) {
            Http.get("https://raw.githubusercontent.com/$repo/main/content/channels.json")?.let { body ->
                runCatching {
                    val o = JSONObject(body)
                    o.optJSONArray("pinterest")?.let { a -> (0 until a.length()).map { a.getString(it) }.takeIf { it.isNotEmpty() }?.let { boards = it } }
                    o.optJSONArray("reddit")?.let { a -> (0 until a.length()).map { a.getString(it) }.takeIf { it.isNotEmpty() }?.let { subs = it } }
                    o.optJSONArray("tiktok")?.let { a ->
                        tiktoks = (0 until a.length()).mapNotNull { i ->
                            val t = a.optJSONObject(i) ?: return@mapNotNull null
                            t.optString("id").takeIf { it.isNotBlank() }?.let { TikTok(it, t.optString("author")) }
                        }
                    }
                }
            }
        }
        val allBoards = (userBoards(ctx) + boards).distinct()
        val allSubs = (userSubs(ctx) + subs).distinctBy { it.lowercase() }
        val (perBoard, perSub) = coroutineScope {
            val a = allBoards.map { b -> async { fetchBoard(b) } }
            val b = allSubs.map { r -> async { fetchSubreddit(r) } }
            a.awaitAll() to (b.awaitAll() + listOf(fetchSubredditCached(ctx, allSubs)))
        }
        val pins = mutableListOf<Pin>()
        val maxLen = perBoard.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) perBoard.forEach { l -> l.getOrNull(i)?.let(pins::add) }
        // Reddit images join the illustrations; Reddit videos and linked YouTube videos join the clips.
        val redditPins = mutableListOf<Pin>()
        val clips = mutableListOf<Video>()
        val maxR = perSub.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxR) perSub.forEach { l ->
            when (val x = l.getOrNull(i)) { is Pin -> redditPins += x; is Video -> clips += x; else -> Unit }
        }
        pins.addAll(interleaveTwo(pins.toList(), redditPins).also { pins.clear() })

        val cache = File(ctx.filesDir, "pins_cache.json")
        val finalPins = if (pins.isNotEmpty()) {
            cache.writeText(JSONArray().apply { pins.forEach { put(it.toJson()) } }.toString()); pins
        } else if (cache.exists()) {
            runCatching { JSONArray(cache.readText()).let { a -> (0 until a.length()).map { Pin.from(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        } else emptyList()

        clips.forEach { if (it.direct.isNotBlank()) StreamResolver.registerDirect(it.id, it.direct) }
        SocialLibrary(finalPins.distinctBy { it.image }, (userTikToks(ctx) + tiktoks).distinctBy { it.id }, clips.distinctBy { it.id })
    }

    private fun <T> interleaveTwo(a: List<T>, b: List<T>): List<T> {
        val out = mutableListOf<T>()
        for (i in 0 until maxOf(a.size, b.size)) { a.getOrNull(i)?.let(out::add); b.getOrNull(i)?.let(out::add) }
        return out
    }

    private fun fetchSubredditCached(ctx: Context, subs: List<String>): List<Any> = emptyList()

    /**
     * Top image and video posts of the week from a subreddit, via Reddit's public JSON.
     * Skips NSFW, text-only and low-score posts.
     */
    private fun fetchSubreddit(sub: String): List<Any> {
        val body = getWithUa("https://www.reddit.com/r/$sub/top.json?t=week&limit=60&raw_json=1", reddit = true) ?: return emptyList()
        return runCatching {
            val children = JSONObject(body).getJSONObject("data").getJSONArray("children")
            val out = mutableListOf<Any>()
            for (i in 0 until children.length()) {
                val d = children.getJSONObject(i).optJSONObject("data") ?: continue
                if (d.optBoolean("over_18") || d.optBoolean("spoiler") || d.optBoolean("stickied")) continue
                if (d.optInt("score") < 25) continue
                val id = d.optString("id")
                val title = d.optString("title")
                val link = "https://www.reddit.com" + d.optString("permalink")
                val url = d.optString("url_overridden_by_dest", d.optString("url"))
                val thumb = d.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("source")?.optString("url").orEmpty()

                val hls = d.optJSONObject("secure_media")?.optJSONObject("reddit_video")?.optString("hls_url").orEmpty()
                val yt = Regex("(?:youtube\\.com/(?:watch\\?v=|shorts/)|youtu\\.be/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
                when {
                    d.optBoolean("is_video") && hls.isNotBlank() ->
                        out += Video("rd_$id", title, "r/$sub", Video.KIND_SHORT, direct = hls, thumb = thumb, link = link)
                    yt != null -> out += Video(yt, title, "r/$sub", Video.KIND_SHORT)
                    Regex("\\.(jpe?g|png|webp)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url) ->
                        out += Pin("r_$id", url, title, link, "r/$sub")
                    d.optBoolean("is_gallery") -> {
                        // First image of a gallery post
                        val meta = d.optJSONObject("media_metadata")
                        val first = d.optJSONObject("gallery_data")?.optJSONArray("items")?.optJSONObject(0)?.optString("media_id")
                        val img = first?.let { meta?.optJSONObject(it)?.optJSONObject("s")?.optString("u") }
                        if (!img.isNullOrBlank()) out += Pin("r_$id", img, title, link, "r/$sub")
                    }
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun fetchBoard(board: String): List<Pin> {
        val xml = getWithUa("https://www.pinterest.com/$board.rss") ?: return emptyList()
        return runCatching {
            val p = Xml.newPullParser()
            p.setInput(xml.reader())
            val out = mutableListOf<Pin>()
            var inItem = false
            var title = ""; var link = ""; var desc = ""; var guid = ""
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (p.name) {
                    "item" -> { inItem = true; title = ""; link = ""; desc = ""; guid = "" }
                    "title" -> if (inItem) title = p.nextText()
                    "link" -> if (inItem) link = p.nextText()
                    "guid" -> if (inItem) guid = p.nextText()
                    "description" -> if (inItem) desc = p.nextText()
                } else if (ev == XmlPullParser.END_TAG && p.name == "item") {
                    inItem = false
                    Regex("src=\"(https://i\\.pinimg\\.com/[^\"]+)\"").find(desc)?.groupValues?.get(1)?.let { img ->
                        // Ask for a sharper version than the RSS thumbnail.
                        val big = img.replace(Regex("/\\d+x(\\d+)?/"), "/736x/")
                        out += Pin(guid.ifBlank { link }, big, title.trim(), link, board)
                    }
                }
                ev = p.next()
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun getWithUa(url: String, reddit: Boolean = false): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        // Reddit asks apps to identify themselves with a descriptive User-Agent.
        c.setRequestProperty("User-Agent", if (reddit) "android:com.faithscroll.app:v1.0 (personal Christian feed reader)" else UA)
        if (!reddit) c.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
        try { if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
    } catch (_: Exception) { null }
}
