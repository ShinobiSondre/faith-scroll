package com.faithscroll.app.data

import android.content.Context
import com.faithscroll.app.util.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import kotlin.random.Random

/**
 * Keeps the feed fresh by always looking for new Christian content:
 * - runs a few new YouTube searches every time the app opens, picked from your favourite
 *   themes (and in Norwegian when the app is in Norwegian),
 * - turns channels you clearly enjoy into permanent sources,
 * - now and then finds new Christian subreddits.
 */
object Discovery {
    private val general = listOf(
        "christian testimony", "jesus changed my life", "powerful testimony", "short sermon clip",
        "worship moment", "bible study short", "daily devotional", "gospel message", "prayer for today",
        "christian encouragement", "god's love story", "faith over fear sermon", "salvation testimony",
        "former atheist testimony", "jesus shorts", "bible verse explained", "christian motivation"
    )
    private val byTheme = mapOf(
        "peace" to listOf("sermon on anxiety", "god's peace devotional", "overcoming worry faith"),
        "comfort" to listOf("grief and faith testimony", "god heals broken hearts", "comfort in suffering sermon"),
        "hope" to listOf("hope in jesus sermon", "god's promises encouragement"),
        "strength" to listOf("addiction freedom testimony", "strength in god sermon", "overcoming through christ"),
        "love" to listOf("god's love sermon", "christian marriage advice", "love your neighbour message"),
        "faith" to listOf("growing in faith sermon", "jesus is real testimony", "doubt and faith"),
        "guidance" to listOf("finding god's will", "purpose in christ sermon", "christian wisdom"),
        "joy" to listOf("joy of the lord sermon", "christian worship joy", "gratitude devotional"),
        "grace" to listOf("forgiveness testimony", "grace of god sermon", "prison to pulpit testimony"),
        "courage" to listOf("courage faith sermon", "persecuted christians story"),
        "prayer" to listOf("how to pray sermon", "powerful prayer", "holy spirit encounter testimony")
    )
    private val norwegian = listOf(
        "kristent vitnesbyrd", "preken", "andakt", "lovsang", "jesus forandret livet mitt", "bibelundervisning", "bønn for i dag"
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("discovery", Context.MODE_PRIVATE)

    // ---- Channels that earned a permanent spot ----
    fun channels(ctx: Context): List<Pair<String, String>> = runCatching {
        val o = JSONObject(prefs(ctx).getString("channels", "{}")!!)
        o.keys().asSequence().map { it to o.getJSONObject(it).optString("name") }.toList()
    }.getOrDefault(emptyList())

    private val channelOfVideo = HashMap<String, Pair<String, String>>() // videoId -> (channelId, name)

    /** Called when you watch, save or share a discovered video: its channel gets closer to becoming a source. */
    fun reward(ctx: Context, videoId: String, amount: Float) {
        val (cid, name) = channelOfVideo[videoId] ?: return
        val o = runCatching { JSONObject(prefs(ctx).getString("candidates", "{}")!!) }.getOrDefault(JSONObject())
        val score = o.optDouble(cid, 0.0) + amount
        o.put(cid, score)
        val p = prefs(ctx).edit().putString("candidates", o.toString())
        if (score >= 2.0) {
            val ch = runCatching { JSONObject(prefs(ctx).getString("channels", "{}")!!) }.getOrDefault(JSONObject())
            if (!ch.has(cid) && ch.length() < 30) ch.put(cid, JSONObject().put("name", name).put("added", System.currentTimeMillis()))
            p.putString("channels", ch.toString())
        }
        p.apply()
    }

    fun removeChannel(ctx: Context, id: String) {
        val ch = runCatching { JSONObject(prefs(ctx).getString("channels", "{}")!!) }.getOrDefault(JSONObject())
        ch.remove(id)
        prefs(ctx).edit().putString("channels", ch.toString()).apply()
    }

    // ---- Fresh searches ----
    suspend fun search(ctx: Context): List<Video> = withContext(Dispatchers.IO) {
        val rnd = Random(System.currentTimeMillis())
        val queries = mutableListOf<String>()
        Taste.topThemes(ctx, 3).forEach { t -> byTheme[t]?.random(rnd)?.let(queries::add) }
        queries += general.shuffled(rnd).take(if (queries.isEmpty()) 4 else 2)
        if (Lang.no) queries += norwegian.shuffled(rnd).take(2)

        val results = coroutineScope { queries.distinct().map { q -> async { runCatching { searchOnce(q) }.getOrDefault(emptyList()) } }.awaitAll() }
        val out = mutableListOf<Video>()
        for (i in 0 until (results.maxOfOrNull { it.size } ?: 0)) results.forEach { l -> l.getOrNull(i)?.let(out::add) }
        out.distinctBy { it.id }.filter { !Seen.has(ctx, it.id) }
    }

    private fun searchOnce(q: String): List<Video> {
        StreamResolver.ensureInit()
        val yt = ServiceList.YouTube
        val handler = yt.searchQHFactory.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        val info = SearchInfo.getInfo(yt, handler)
        return info.relatedItems.filterIsInstance<StreamInfoItem>()
            .filter { it.streamType != StreamType.LIVE_STREAM && it.duration in 10..1500 } // up to 25 min
            .mapNotNull { item ->
                val id = Regex("(?:v=|shorts/|youtu\\.be/)([A-Za-z0-9_-]{11})").find(item.url)?.groupValues?.get(1) ?: return@mapNotNull null
                val channel = item.uploaderName ?: ""
                Regex("channel/(UC[A-Za-z0-9_-]{22})").find(item.uploaderUrl ?: "")?.groupValues?.get(1)?.let {
                    channelOfVideo[id] = it to channel
                }
                val kind = when {
                    item.duration <= 90 -> Video.KIND_SHORT
                    q.contains("sermon") || q.contains("preken") -> Video.KIND_SERMON
                    else -> Video.KIND_STORY
                }
                Video(id, item.name ?: "", channel, kind)
            }
            .take(12)
    }

    // ---- New subreddits now and then ----
    suspend fun maybeFindSubreddits(ctx: Context) = withContext(Dispatchers.IO) {
        val last = prefs(ctx).getLong("subs_checked", 0)
        if (System.currentTimeMillis() - last < 3 * 24 * 3600 * 1000L) return@withContext
        prefs(ctx).edit().putLong("subs_checked", System.currentTimeMillis()).apply()
        val q = listOf("christian", "jesus", "bible", "worship", "christian art").random()
        val body = Http.get("https://www.reddit.com/subreddits/search.json?q=${q.replace(" ", "+")}&limit=25&include_over_18=off") ?: return@withContext
        runCatching {
            val kids = JSONObject(body).getJSONObject("data").getJSONArray("children")
            val known = SocialRepository.userSubs(ctx).map { it.lowercase() }.toSet() + SocialRepository.defaultSubreddits.map { it.lowercase() }
            val found = (0 until kids.length()).mapNotNull { i -> kids.getJSONObject(i).optJSONObject("data") }
                .filter { !it.optBoolean("over18") && it.optInt("subscribers") > 20_000 && it.optString("subreddit_type") == "public" }
                .map { it.optString("display_name") }
                .filter { it.isNotBlank() && it.lowercase() !in known }
            val existing = JSONArray(prefs(ctx).getString("subs", "[]"))
            found.take(2).forEach { existing.put(it) }
            prefs(ctx).edit().putString("subs", existing.toString()).apply()
        }
    }

    fun discoveredSubs(ctx: Context): List<String> = runCatching {
        val a = JSONArray(prefs(ctx).getString("subs", "[]"))
        (0 until a.length()).map { a.getString(it) }.takeLast(10)
    }.getOrDefault(emptyList())
}
