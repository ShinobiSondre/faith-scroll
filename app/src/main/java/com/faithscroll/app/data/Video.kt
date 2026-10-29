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

data class Video(val id: String, val title: String, val channel: String, val kind: String = KIND_STORY) {
    companion object {
        const val KIND_STORY = "story"
        const val KIND_SERMON = "sermon"
    }
    val thumbnail: String get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    val url: String get() = "https://www.youtube.com/watch?v=$id"
    val mobileUrl: String get() = "https://m.youtube.com/watch?v=$id"
}

/** Testimonies for the swipe feed, sermons for the Watch tab. */
data class VideoLibrary(val stories: List<Video>, val sermons: List<Video>) {
    val all: List<Video> get() = (sermons + stories).distinctBy { it.id }
}

/**
 * Pulls the latest uploads from Christian testimony / Bible channels using YouTube's public
 * RSS feeds (no API key). The channel list can be edited in content/channels.json on GitHub.
 */
object VideoRepository {
    private val defaultStories = listOf(
        "UC9dK4qKU8-5xiqFoI9BGVmQ" to "I Am Second",
        "UCdMgPQvQhsdd-qD-fugvlSA" to "700 Club Testimonies",
        "UCVfwlh9XpX2Y_tQfjeln9QA" to "BibleProject"
    )
    private val defaultSermons = listOf(
        "UCnrFlpro0xfYjz6s5Xa8WWw" to "Desiring God",
        "UCIQqvZbHSwX0yKNVK1MyYjQ" to "Elevation Church",
        "UCXL6kvDyoQpvtaeafC4pLyg" to "In Touch Ministries",
        "UCQmUmqrMGfnesNpdL7T282Q" to "Gospel in Life"
    )
    private val fallback = listOf(Video("dDYbiukIFBc", "Best CBN Testimonies", "CBN"))

    suspend fun load(ctx: Context): VideoLibrary = withContext(Dispatchers.IO) {
        val cacheFile = File(ctx.filesDir, "videos_cache_v2.json")
        var stories = defaultStories
        var sermons = defaultSermons
        var pinned = emptyList<Video>()

        val repo = BuildConfig.CONTENT_REPO
        if (repo.isNotBlank()) {
            Http.get("https://raw.githubusercontent.com/$repo/main/content/channels.json")?.let { body ->
                runCatching {
                    val o = JSONObject(body)
                    channelList(o.optJSONArray("channels"))?.let { stories = it }
                    channelList(o.optJSONArray("sermons"))?.let { sermons = it }
                    o.optJSONArray("videos")?.let { a -> pinned = parseVideos(a) }
                }
            }
        }

        val (storyLists, sermonLists) = coroutineScope {
            val a = stories.map { (id, name) -> async { fetchChannel(id, name, Video.KIND_STORY) } }
            val b = sermons.map { (id, name) -> async { fetchChannel(id, name, Video.KIND_SERMON) } }
            a.awaitAll() to b.awaitAll()
        }
        val lib = VideoLibrary(
            stories = (pinned + interleave(storyLists)).distinctBy { it.id },
            sermons = interleave(sermonLists).distinctBy { it.id }
        )

        if (lib.stories.size + lib.sermons.size > pinned.size) {
            val a = JSONArray()
            lib.all.forEach { a.put(it.toJson()) }
            cacheFile.writeText(a.toString())
            return@withContext lib
        }
        val cached = if (cacheFile.exists()) runCatching { parseVideos(JSONArray(cacheFile.readText())) }.getOrDefault(emptyList()) else emptyList()
        if (cached.isEmpty()) VideoLibrary(pinned.ifEmpty { fallback }, emptyList())
        else VideoLibrary(cached.filter { it.kind == Video.KIND_STORY }, cached.filter { it.kind == Video.KIND_SERMON })
    }

    private fun channelList(a: JSONArray?): List<Pair<String, String>>? {
        if (a == null) return null
        val list = (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { c -> c.optString("id").takeIf { it.isNotBlank() }?.let { it to c.optString("name") } }
        }
        return list.ifEmpty { null }
    }

    /** Mixes channels so the list feels varied (newest of each first). */
    private fun interleave(lists: List<List<Video>>): List<Video> {
        val out = mutableListOf<Video>()
        val maxLen = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
        return out
    }

    fun Video.toJson(): JSONObject =
        JSONObject().put("id", id).put("title", title).put("channel", channel).put("kind", kind)

    fun parseVideos(a: JSONArray): List<Video> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id")
        if (id.isBlank()) null else Video(id, o.optString("title"), o.optString("channel"), o.optString("kind", Video.KIND_STORY))
    }

    private fun fetchChannel(channelId: String, name: String, kind: String): List<Video> {
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
                            id?.let { out += Video(it, title.orEmpty(), name.ifBlank { author.orEmpty() }, kind) }
                        }
                    }
                }
                event = parser.next()
            }
            out
        }.getOrDefault(emptyList())
    }
}
