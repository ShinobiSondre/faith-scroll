package com.faithscroll.app.data

import com.faithscroll.app.util.tr

import android.content.Context
import org.json.JSONObject
import kotlin.math.exp
import kotlin.random.Random

/**
 * FaithScroll's "For You" brain. It starts from the onboarding answers and keeps learning
 * from what you do: lingering, skipping, liking, saving, sharing, reading chapters and
 * finishing videos. Scores slowly fade so it follows where you are now.
 */
object Taste {
    val themes = listOf("hope", "peace", "strength", "love", "faith", "comfort", "guidance", "joy", "grace", "courage", "prayer")

    val themeLabels: Map<String, String> get() = mapOf(
        "hope" to tr("Hope", "Håp"), "peace" to tr("Peace", "Fred"), "strength" to tr("Strength", "Styrke"), "love" to tr("Love", "Kjærlighet"),
        "faith" to tr("Faith", "Tro"), "comfort" to tr("Comfort", "Trøst"), "guidance" to tr("Guidance", "Veiledning"), "joy" to tr("Joy", "Glede"),
        "grace" to tr("Forgiveness", "Tilgivelse"), "courage" to tr("Courage", "Mot"), "prayer" to tr("Prayer", "Bønn")
    )

    /** Words in a video title that hint at its topic. */
    private val keywords = mapOf(
        "peace" to listOf("anxiety", "anxious", "worry", "peace", "stress", "rest", "calm", "fear of"),
        "comfort" to listOf("grief", "loss", "pain", "suffering", "heal", "broken", "depress", "lonely", "cancer", "death", "died"),
        "hope" to listOf("hope", "future", "promise", "heaven", "new life", "restored", "second chance"),
        "strength" to listOf("strength", "strong", "battle", "addict", "overcome", "endure", "struggle", "fight"),
        "love" to listOf("love", "marriage", "relationship", "family", "father", "mother", "husband", "wife", "friend"),
        "faith" to listOf("faith", "believe", "doubt", "jesus", "gospel", "christ", "atheist", "salvation"),
        "guidance" to listOf("purpose", "calling", "decision", "wisdom", "direction", "will of god", "plan"),
        "joy" to listOf("joy", "grateful", "gratitude", "thank", "praise", "worship", "celebrate"),
        "grace" to listOf("grace", "forgive", "shame", "guilt", "mercy", "prison", "redeem", "sin"),
        "courage" to listOf("courage", "brave", "afraid", "fear", "bold", "persecut"),
        "prayer" to listOf("pray", "prayer", "fasting", "holy spirit", "presence")
    )

    private const val MAX = 6f
    private const val MIN = -3f

    private var loaded = false
    private val themeScore = mutableMapOf<String, Float>()
    private val channelScore = mutableMapOf<String, Float>()
    private val kindScore = mutableMapOf<String, Float>()
    var videoBias = 0f; private set // >0 wants more videos in the feed
    private var onboarded = false
    private val muted = mutableSetOf<String>() // channels swiped away

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("taste", Context.MODE_PRIVATE)

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(ctx)
        onboarded = p.getBoolean("onboarded", false)
        videoBias = p.getFloat("video_bias", 0f)
        fun readMap(key: String, into: MutableMap<String, Float>) {
            runCatching {
                val o = JSONObject(p.getString(key, "{}")!!)
                o.keys().forEach { into[it] = o.getDouble(it).toFloat() }
            }
        }
        readMap("themes", themeScore); readMap("channels", channelScore); readMap("kinds", kindScore)
        muted += p.getStringSet("muted", emptySet()).orEmpty()
    }

    private fun save(ctx: Context) {
        fun toJson(m: Map<String, Float>) = JSONObject().apply { m.forEach { (k, v) -> put(k, v.toDouble()) } }.toString()
        prefs(ctx).edit()
            .putBoolean("onboarded", onboarded)
            .putFloat("video_bias", videoBias)
            .putString("themes", toJson(themeScore))
            .putString("channels", toJson(channelScore))
            .putString("kinds", toJson(kindScore))
            .putStringSet("muted", muted.toSet())
            .apply()
    }

    fun isOnboarded(ctx: Context): Boolean { load(ctx); return onboarded }

    data class Answers(
        val themes: Set<String>,
        val journey: String, // exploring | new | growing | mature
        val watch: Set<String>, // stories | sermons | teaching | verses
        val channels: Set<String>
    )

    fun applyOnboarding(ctx: Context, a: Answers) {
        load(ctx)
        themeScore.clear(); channelScore.clear(); kindScore.clear(); videoBias = 0f
        a.themes.forEach { themeScore[it] = 2.5f }
        when (a.journey) {
            "exploring" -> { bump(themeScore, "faith", 1f); bump(themeScore, "love", 1f); bump(themeScore, "hope", 0.8f); bump(kindScore, Video.KIND_STORY, 1f) }
            "new" -> { bump(themeScore, "grace", 1f); bump(themeScore, "faith", 0.8f); bump(themeScore, "prayer", 0.6f) }
            "growing" -> { bump(themeScore, "guidance", 0.8f); bump(themeScore, "prayer", 0.6f); bump(kindScore, Video.KIND_SERMON, 0.5f) }
            "mature" -> { bump(kindScore, Video.KIND_SERMON, 0.8f) }
        }
        if ("stories" in a.watch) { bump(kindScore, Video.KIND_STORY, 1.5f); bump(channelScore, "I Am Second", 0.8f); bump(channelScore, "700 Club Testimonies", 0.8f) }
        if ("sermons" in a.watch) bump(kindScore, Video.KIND_SERMON, 1.5f)
        if ("teaching" in a.watch) bump(channelScore, "BibleProject", 2f)
        videoBias = when {
            a.watch == setOf("messages") -> -2f
            "messages" in a.watch -> -0.5f
            a.watch.size >= 2 -> 1f
            else -> 0.5f
        }
        a.channels.forEach { bump(channelScore, it, 2.5f) }
        onboarded = true
        save(ctx)
    }

    fun reset(ctx: Context) {
        muted.clear()
        load(ctx)
        themeScore.clear(); channelScore.clear(); kindScore.clear(); videoBias = 0f; onboarded = false
        save(ctx)
    }

    private fun bump(m: MutableMap<String, Float>, key: String, by: Float) {
        m[key] = ((m[key] ?: 0f) + by).coerceIn(MIN, MAX)
    }

    /** Gentle fade so recent behaviour matters most. */
    private fun decay() {
        for (m in listOf(themeScore, channelScore, kindScore)) m.replaceAll { _, v -> v * 0.995f }
        videoBias *= 0.995f
    }

    fun topicsOf(title: String): List<String> {
        val t = title.lowercase()
        return keywords.filter { (_, words) -> words.any { it in t } }.keys.toList()
    }

    // ---------- Signals ----------

    enum class Action { LIKE, SAVE, SHARE, READ, EXPAND }

    fun verseSeen(ctx: Context, v: Verse, dwellMs: Long) {
        load(ctx); decay()
        val d = when {
            dwellMs < 1500 -> -0.15f // swiped straight past
            dwellMs < 6000 -> 0.05f
            dwellMs < 20000 -> 0.2f
            else -> 0.3f
        }
        bump(themeScore, v.theme, d)
        if (dwellMs > 4000) videoBias = (videoBias - 0.03f).coerceIn(-3f, 3f)
        save(ctx)
    }

    fun verseAction(ctx: Context, v: Verse, a: Action) {
        load(ctx)
        bump(themeScore, v.theme, when (a) { Action.LIKE -> 0.6f; Action.SAVE -> 0.8f; Action.SHARE -> 1f; Action.READ -> 0.7f; Action.EXPAND -> 0.3f })
        save(ctx)
    }

    fun messageSeen(ctx: Context, m: Message, dwellMs: Long) {
        load(ctx); decay()
        val d = when { dwellMs < 1200 -> -0.15f; dwellMs < 4000 -> 0.05f; else -> 0.25f }
        bump(themeScore, m.theme, d)
        if (dwellMs > 4000) videoBias = (videoBias - 0.05f).coerceIn(-3f, 3f)
        save(ctx)
    }

    fun messageAction(ctx: Context, m: Message, a: Action) {
        load(ctx)
        bump(themeScore, m.theme, when (a) { Action.LIKE -> 0.6f; Action.SAVE -> 0.8f; Action.SHARE -> 1f; else -> 0.3f })
        videoBias = (videoBias - 0.15f).coerceIn(-3f, 3f)
        save(ctx)
    }

    fun pinSeen(ctx: Context, p: Pin, dwellMs: Long) {
        load(ctx); decay()
        val d = when { dwellMs < 1200 -> -0.15f; dwellMs < 4000 -> 0.05f; else -> 0.25f }
        topicsOf(p.title).forEach { bump(themeScore, it, d) }
        if (dwellMs > 4000) videoBias = (videoBias - 0.05f).coerceIn(-3f, 3f)
        save(ctx)
    }

    fun pinAction(ctx: Context, p: Pin, a: Action) {
        load(ctx)
        val d = when (a) { Action.LIKE -> 0.6f; Action.SAVE -> 0.8f; Action.SHARE -> 1f; else -> 0.3f }
        topicsOf(p.title).forEach { bump(themeScore, it, d) }
        videoBias = (videoBias - 0.15f).coerceIn(-3f, 3f)
        save(ctx)
    }

    fun videoSeen(ctx: Context, v: Video, dwellMs: Long) {
        load(ctx); decay()
        val d = when {
            dwellMs < 3000 -> -0.3f // skipped
            dwellMs < 15000 -> 0.1f
            dwellMs < 60000 -> 0.4f
            else -> 0.8f // really watched
        }
        bump(channelScore, v.channel, d)
        bump(kindScore, v.kind, d * 0.5f)
        topicsOf(v.title).forEach { bump(themeScore, it, d * 0.4f) }
        videoBias = (videoBias + if (dwellMs < 3000) -0.15f else if (dwellMs > 30000) 0.2f else 0.05f).coerceIn(-3f, 3f)
        save(ctx)
    }

    fun videoAction(ctx: Context, v: Video, a: Action) {
        load(ctx)
        val d = when (a) { Action.SAVE -> 1f; Action.SHARE -> 1f; Action.EXPAND -> 0.6f; else -> 0.5f }
        bump(channelScore, v.channel, d)
        bump(kindScore, v.kind, d * 0.4f)
        topicsOf(v.title).forEach { bump(themeScore, it, d * 0.4f) }
        videoBias = (videoBias + 0.2f).coerceIn(-3f, 3f)
        save(ctx)
    }

    /** Swipe right: more like this – its channel, its kind (short/sermon/story) and its topics. */
    fun videoMore(ctx: Context, v: Video) {
        load(ctx)
        muted -= v.channel
        bump(channelScore, v.channel, 2.5f)
        bump(kindScore, v.kind, 1f)
        topicsOf(v.title).forEach { bump(themeScore, it, 1.2f) }
        videoBias = (videoBias + 0.3f).coerceIn(-3f, 3f)
        save(ctx)
    }

    /** Swipe left: not this – the channel disappears from the feed, its kind and topics come less often. */
    fun videoLess(ctx: Context, v: Video) {
        load(ctx)
        muted += v.channel
        channelScore[v.channel] = MIN
        bump(kindScore, v.kind, -0.8f)
        topicsOf(v.title).forEach { bump(themeScore, it, -0.8f) }
        save(ctx)
    }

    fun isMuted(ctx: Context, channel: String): Boolean { load(ctx); return channel in muted }

    // ---------- Ranking ----------

    fun themeWeight(ctx: Context, theme: String): Float { load(ctx); return themeScore[theme] ?: 0f }

    fun topThemes(ctx: Context, n: Int = 3): List<String> {
        load(ctx)
        return themeScore.entries.filter { it.value > 0.5f }.sortedByDescending { it.value }.take(n).map { it.key }
    }

    fun scoreVideo(ctx: Context, v: Video): Float {
        load(ctx)
        val topic = topicsOf(v.title).maxOfOrNull { themeScore[it] ?: 0f } ?: 0f
        val shortBonus = if (v.kind == Video.KIND_SHORT) 0.6f else 0f // TikTok-style clips suit the feed
        val favBonus = if (YouTubeSubs.isFavouriteName(ctx, v.channel)) 2.5f else 0f // your favourite subscriptions
        return (channelScore[v.channel] ?: 0f) + (kindScore[v.kind] ?: 0f) * 0.7f + topic * 0.5f + shortBonus + favBonus
    }

    /** How many videos to show between illustrated messages (mostly videos, messages sprinkled in). */
    fun videosBetweenMessages(ctx: Context): Int {
        load(ctx)
        return when {
            videoBias > 1.5f -> 6
            videoBias > 0.5f -> 4
            videoBias > -0.8f -> 3
            videoBias > -1.8f -> 2
            else -> 1
        }
    }

    fun favouriteChannel(ctx: Context): String? {
        load(ctx)
        return channelScore.entries.filter { it.value > 1f }.maxByOrNull { it.value }?.key
    }

    /** Weighted random pick: higher scores win more often, but nothing is ever impossible. */
    fun <T> weightedPick(items: List<T>, score: (T) -> Float, temperature: Float = 1.2f, rnd: Random = Random): T? {
        if (items.isEmpty()) return null
        val w = items.map { exp((score(it) / temperature).toDouble()) }
        var r = rnd.nextDouble() * w.sum()
        for (i in items.indices) { r -= w[i]; if (r <= 0) return items[i] }
        return items.last()
    }
}
