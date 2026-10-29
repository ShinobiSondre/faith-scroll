package com.faithscroll.app.data

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
    private var sinceMessage = 0
    private var produced = 0
    private val explore = 0.25

    fun next(): FeedItem {
        produced++
        val vids = videos()
        if (vids.isEmpty()) return pickIllustration() // videos still loading (or offline)
        val gap = Taste.videosBetweenMessages(ctx) + (if (rnd.nextInt(3) == 0) 1 else 0)
        if (sinceMessage >= gap) { sinceMessage = 0; return pickIllustration() }
        sinceMessage++
        // TikToks you've added are mixed in with the videos.
        val tiks = social()?.tiktoks.orEmpty().filter { it.id !in shownTikToks }
        if (tiks.isNotEmpty() && rnd.nextDouble() < 0.25) {
            val t = tiks.random(rnd); shownTikToks += t.id
            return FeedItem.TikTokItem(t, tr("From TikTok", "Fra TikTok"))
        }
        return pickVideo(vids) ?: pickIllustration()
    }

    /** Mostly real illustrated quotes from Pinterest; our own drawn ones when offline. */
    private fun pickIllustration(): FeedItem {
        val pins = social()?.pins.orEmpty()
        if (pins.isEmpty() || rnd.nextDouble() < 0.2) return pickMessage()
        val pool = pins.filter { it.id !in shownPins }.ifEmpty { shownPins.clear(); pins }
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
        val pool = all.filter { it.id !in shownVideos }.ifEmpty { shownVideos.clear(); all }
        if (pool.isEmpty()) return null
        val random = rnd.nextDouble() < explore
        // Newer uploads get a small boost (feeds come newest-first per channel).
        val v = if (random) pool.random(rnd) else Taste.weightedPick(pool, { vid ->
            Taste.scoreVideo(ctx, vid) - 0.01f * all.indexOf(vid)
        }, rnd = rnd)!!
        shownVideos += v.id
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
