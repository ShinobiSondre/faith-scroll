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

data class Video(val id: String, val title: String, val channel: String) {
    val thumbnail: String get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    val url: String get() = "https://www.youtube.com/watch?v=$id"
}

/**
 * Pulls the latest uploads from Christian testimony / Bible channels using YouTube's public
 * RSS feeds (no API key). The channel list can be edited in content/channels.json on GitHub.
 */
object VideoRepository {
    private val defaultChannels = listOf(
        "UC9dK4qKU8-5xiqFoI9BGVmQ" to "I Am Second",
        "UCdMgPQvQhsdd-qD-fugvlSA" to "700 Club Testimonies",
        "UCVfwlh9XpX2Y_tQfjeln9QA" to "BibleProject"
    )
    private val fallback = listOf(Video("dDYbiukIFBc", "Best CBN Testimonies", "CBN"))

    suspend fun load(ctx: Context): List<Video> = withContext(Dispatchers.IO) {
        val cacheFile = File(ctx.filesDir, "videos_cache.json")
        var channels = defaultChannels
        var pinned = emptyList<Video>()

        val repo = BuildConfig.CONTENT_REPO
        if (repo.isNotBlank()) {
            Http.get("https://raw.githubusercontent.com/$repo/main/content/channels.json")?.let { body ->
                runCatching {
                    val o = JSONObject(body)
                    o.optJSONArray("channels")?.let { a ->
                        val list = (0 until a.length()).map { a.getJSONObject(it).let { c -> c.getString("id") to c.optString("name") } }
                        if (list.isNotEmpty()) channels = list
                    }
                    o.optJSONArray("videos")?.let { a -> pinned = parseVideos(a) }
                }
            }
        }

        val perChannel = coroutineScope {
            channels.map { (id, name) -> async { fetchChannel(id, name) } }.awaitAll()
        }
        // Interleave channels so the feed feels varied.
        val mixed = mutableListOf<Video>()
        val maxLen = perChannel.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) perChannel.forEach { list -> list.getOrNull(i)?.let(mixed::add) }
        val all = (pinned + mixed).distinctBy { it.id }

        if (all.isNotEmpty()) {
            val a = JSONArray()
            all.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel)) }
            cacheFile.writeText(a.toString())
            return@withContext all
        }
        val cached = if (cacheFile.exists()) runCatching { parseVideos(JSONArray(cacheFile.readText())) }.getOrDefault(emptyList()) else emptyList()
        cached.ifEmpty { fallback }
    }

    private fun parseVideos(a: JSONArray): List<Video> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id")
        if (id.isBlank()) null else Video(id, o.optString("title"), o.optString("channel"))
    }

    private fun fetchChannel(channelId: String, name: String): List<Video> {
        val xml = Http.get("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId") ?: return emptyList()
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(xml.reader())
            val out = mutableListOf<Video>()
            var inEntry = false
            var id: String? = null
            var title: String? = null
            var author: String? = null
            var inAuthor = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "entry" -> { inEntry = true; id = null; title = null; author = null }
                        "videoId" -> if (inEntry) id = parser.nextText()
                        "title" -> if (inEntry && title == null) title = parser.nextText()
                        "author" -> inAuthor = true
                        "name" -> if (inEntry && inAuthor) author = parser.nextText()
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "author" -> inAuthor = false
                        "entry" -> {
                            inEntry = false
                            id?.let { out += Video(it, title.orEmpty(), author ?: name) }
                        }
                    }
                }
                event = parser.next()
            }
            out
        }.getOrDefault(emptyList())
    }
}
