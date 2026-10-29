package com.faithscroll.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Carries the video and the second you were at between the feed and full screen. */
object PlaybackMemory {
    private val positions = ConcurrentHashMap<String, Long>()
    fun put(videoId: String, ms: Long) { if (ms > 1_500) positions[videoId] = ms }
    fun take(videoId: String): Long? = positions.remove(videoId)
    /** The video you were on when you left full screen; the feed moves to it. */
    val returnTo = MutableStateFlow<Video?>(null)
}
