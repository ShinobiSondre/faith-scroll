package com.faithscroll.app.data

import android.content.Context
import kotlin.random.Random

sealed interface FeedItem {
    val reason: String?
    data class VerseItem(val verse: Verse, override val reason: String?) : FeedItem
    data class VideoItem(val video: Video, override val reason: String?) : FeedItem
}

/**
 * Builds the feed a few cards at a time, re-reading your taste each time,
 * so the feed adapts while you scroll (like TikTok). Roughly 1 in 4 picks is
 * random on purpose, so you keep discovering new things.
 */
class FeedEngine(
    private val ctx: Context,
    private val verses: List<Verse>,
    private val videos: () -> List<Video>,
    private val startRef: String?
) {
    private val rnd = Random(System.nanoTime())
    private val recentVerses = ArrayDeque<String>()
    private val shownVideos = mutableSetOf<String>()
    private var sinceVideo = 0
    private var produced = 0

    private val explore = 0.25

    fun next(): FeedItem {
        produced++
        if (produced == 1) {
            verses.firstOrNull { it.ref == startRef }?.let { return verseItem(it, null) }
        }
        val vids = videos()
        val gap = Taste.versesBetweenVideos(ctx)
        // Never open with a video; then follow the learned rhythm (with a little jitter).
        if (produced > 2 && vids.isNotEmpty() && sinceVideo >= gap + (if (rnd.nextInt(4) == 0) 1 else 0)) {
            pickVideo(vids)?.let { sinceVideo = 0; return it }
        }
        sinceVideo++
        return pickVerse()
    }

    private fun verseItem(v: Verse, reason: String?): FeedItem {
        recentVerses.addLast(v.ref)
        while (recentVerses.size > minOf(60, verses.size / 2)) recentVerses.removeFirst()
        return FeedItem.VerseItem(v, reason)
    }

    private fun pickVerse(): FeedItem {
        val pool = verses.filter { it.ref !in recentVerses }.ifEmpty { verses }
        val tops = Taste.topThemes(ctx)
        if (tops.isEmpty() || rnd.nextDouble() < explore) {
            return verseItem(pool.random(rnd), if (tops.isNotEmpty()) "Something new" else null)
        }
        // Pick a theme by how much you've engaged with it, then a verse from that theme.
        val theme = Taste.weightedPick(Taste.themes, { Taste.themeWeight(ctx, it) }, rnd = rnd) ?: "faith"
        val themed = pool.filter { it.theme == theme }
        val v = if (themed.isNotEmpty()) themed.random(rnd) else pool.random(rnd)
        val label = Taste.themeLabels[v.theme]
        val reason = if (Taste.themeWeight(ctx, v.theme) > 0.5f && label != null) "For you · $label" else null
        return verseItem(v, reason)
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
            random -> "Something new"
            Taste.scoreVideo(ctx, v) > 1.2f -> {
                val topic = Taste.topicsOf(v.title).maxByOrNull { Taste.themeWeight(ctx, it) }
                if (topic != null && Taste.themeWeight(ctx, topic) > 1f) "For you · ${Taste.themeLabels[topic]}"
                else "Because you watch ${v.channel}"
            }
            else -> null
        }
        return FeedItem.VideoItem(v, reason)
    }
}
