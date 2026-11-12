package com.faithscroll.app.data

import com.faithscroll.app.util.tr

import android.content.Context
import kotlin.random.Random

sealed interface FeedItem {
    val reason: String?
    data class MessageItem(val message: Message, override val reason: String?) : FeedItem
    data class VideoItem(val video: Video, override val reason: String?) : FeedItem
    data class PinItem(val pin: Pin, override val reason: String?) : FeedItem
    data class TikTokItem(val tiktok: TikTok, override val reason: String?) : FeedItem
}

/**
 * Builds the feed a few cards at a time, re-reading your taste each time, so it adapts
 * while you scroll (like TikTok). Mostly videos, with illustrated messages sprinkled in.
 * Roughly 1 in 4 picks is random on purpose, so you keep discovering new things.
 */
class FeedEngine(
    private val ctx: Context,
    private val messages: List<Message>,
    private val videos: () -> List<Video>,
    private val social: () -> SocialLibrary?
) {
    private val shownPins = mutableSetOf<String>()
    private val shownTikToks = mutableSetOf<String>()
    private val rnd = Random(System.nanoTime())
    private val recentMessages = ArrayDeque<String>()
    private val shownVideos = mutableSetOf<String>()
    private val recentChannels = mutableListOf<String>() // channels of the last ~10 videos shown
    private var sinceMessage = 0
    private var produced = 0
    private val explore = 0.25
    private val knownChannels = setOf("I Am Second", "700 Club Testimonies", "BibleProject", "Desiring God", "Elevation Church",
        "In Touch Ministries", "Gospel in Life", "Jesus Shorts", "Christian Daily Devotional", "Our Daily Bread")

    fun next(): FeedItem {
        produced++
        val vids = videos()
        if (vids.isEmpty()) return pickIllustration() // videos still loading (or offline)
        val gap = Taste.videosBetweenMessages(ctx) + (if (rnd.nextInt(3) == 0) 1 else 0)
        if (sinceMessage >= gap) { sinceMessage = 0; return pickIllustration() }
        sinceMessage++
        // Inspiring TikToks (found automatically, or added by you) are mixed in with the videos.
        val tiks = ((social()?.tiktoks.orEmpty()) + TikTokFinder.found.value)
            .filter { it.id !in shownTikToks && !Seen.has(ctx, it.id) }.distinctBy { it.id }
        if (tiks.isNotEmpty() && rnd.nextDouble() < 0.2) {
            val t = tiks.random(rnd); shownTikToks += t.id
            return FeedItem.TikTokItem(t, tr("From TikTok", "Fra TikTok"))
        }
        return pickVideo(vids) ?: pickIllustration()
    }

    /** Mostly real illustrated quotes from Pinterest; our own drawn ones when offline. */
    private fun pickIllustration(): FeedItem {
        val pins = social()?.pins.orEmpty()
        if (pins.isEmpty() || rnd.nextDouble() < 0.2) return pickMessage()
        val pool = Seen.freshFirst(ctx, pins.filter { it.id !in shownPins }.ifEmpty { shownPins.clear(); pins }, { it.id })
        val random = rnd.nextDouble() < explore
        val pin = if (random) pool.random(rnd) else Taste.weightedPick(pool, { p ->
            Taste.topicsOf(p.title).maxOfOrNull { Taste.themeWeight(ctx, it) } ?: 0f
        }, rnd = rnd)!!
        shownPins += pin.id
        val topic = Taste.topicsOf(pin.title).maxByOrNull { Taste.themeWeight(ctx, it) }
        val reason = if (!random && topic != null && Taste.themeWeight(ctx, topic) > 1f) tr("For you · ${Taste.themeLabels[topic]}", "For deg · ${Taste.themeLabels[topic]}") else null
        return FeedItem.PinItem(pin, reason)
    }

    private fun pickMessage(): FeedItem {
        val pool = messages.filter { it.id !in recentMessages }.ifEmpty { messages }
        val tops = Taste.topThemes(ctx)
        val m: Message
        val reason: String?
        if (tops.isEmpty() || rnd.nextDouble() < explore) {
            m = pool.random(rnd); reason = null
        } else {
            val theme = Taste.weightedPick(Taste.themes, { Taste.themeWeight(ctx, it) }, rnd = rnd) ?: "faith"
            m = pool.filter { it.theme == theme }.ifEmpty { pool }.random(rnd)
            val label = Taste.themeLabels[m.theme]
            reason = if (Taste.themeWeight(ctx, m.theme) > 0.5f && label != null) tr("For you · $label", "For deg · $label") else null
        }
        recentMessages.addLast(m.id)
        while (recentMessages.size > messages.size / 2) recentMessages.removeFirst()
        return FeedItem.MessageItem(m, reason)
    }

    private fun pickVideo(all: List<Video>): FeedItem? {
        val usable = all.filter { it.id !in LangFilter.blocked.value && !Taste.isMuted(ctx, it.channel) && !LangFilter.isBlockedChannel(it.channel) && !LangFilter.isNews(it.title, it.channel) }
        // Never a video you've already watched. If none are left, an illustration or message
        // fills the spot while new videos are being found in the background.
        val unwatched = usable.filter { !Seen.has(ctx, it.id) }
        // Ones you only scrolled past may come back once everything else has been shown.
        val fresh = unwatched.filter { it.id !in shownVideos }.ifEmpty { shownVideos.clear(); unwatched }
        // Variety: not a channel you just saw – none of the last 3 channels if there's anything else.
        val pool = fresh.filter { it.channel !in recentChannels.takeLast(3) }.ifEmpty { fresh }
        if (pool.isEmpty()) return null
        val random = rnd.nextDouble() < explore
        // Newer uploads get a small boost (feeds come newest-first per channel).
        val v = if (random) pool.random(rnd) else Taste.weightedPick(pool, { vid ->
            Taste.scoreVideo(ctx, vid) - 0.01f * all.indexOf(vid) + (if (vid.channel !in knownChannels) 0.3f else 0f) -
                1.2f * recentChannels.count { it == vid.channel } // seen this channel a lot lately → less likely
        }, rnd = rnd)!!
        shownVideos += v.id
        recentChannels += v.channel
        if (recentChannels.size > 10) recentChannels.removeAt(0)
        val reason = when {
            random -> tr("Something new", "Noe nytt")
            Taste.scoreVideo(ctx, v) > 1.2f -> {
                val topic = Taste.topicsOf(v.title).maxByOrNull { Taste.themeWeight(ctx, it) }
                if (topic != null && Taste.themeWeight(ctx, topic) > 1f) tr("For you · ${Taste.themeLabels[topic]}", "For deg · ${Taste.themeLabels[topic]}")
                else tr("Because you watch ${v.channel}", "Fordi du ser på ${v.channel}")
            }
            else -> null
        }
        return FeedItem.VideoItem(v, reason)
    }
}
