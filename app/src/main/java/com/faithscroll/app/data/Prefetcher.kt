package com.faithscroll.app.data

import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Gets upcoming cards ready in the background: finds stream links and downloads the first
 * seconds of the next videos (how many depends on Wi-Fi / mobile data / Data saver), and
 * warms up the images. Work for cards you've already scrolled away from is cancelled.
 */
object Prefetcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val asrJobs = HashMap<String, Job>()
    private val gate = Semaphore(2) // at most two downloads at once, so the playing video keeps priority

    @Synchronized
    fun ahead(ctx: Context, upcoming: List<FeedItem>) {
        val app = ctx.applicationContext
        val depth = VideoQuality.preloadDepth(app)
        val videos = upcoming.filterIsInstance<FeedItem.VideoItem>().map { it.video }.take(depth)
        val wanted = videos.map { it.id }.toSet()

        // Cancel preloads that are no longer just ahead.
        jobs.keys.filter { it !in wanted }.forEach { jobs.remove(it)?.cancel() }
        videos.forEach { v ->
            if (jobs[v.id]?.isActive == true) return@forEach
            jobs[v.id] = scope.launch { gate.withPermit { StreamResolver.preload(app, v.id) } }
        }

        // Subtitles: if the next video has no usable YouTube captions, transcribe its first 90 s now.
        val captionsOn = app.getSharedPreferences("captions", Context.MODE_PRIVATE).getBoolean("on", true)
        videos.firstOrNull()?.takeIf { captionsOn && it.direct.isBlank() }?.let { next ->
            if (asrJobs[next.id]?.isActive != true) asrJobs[next.id] = scope.launch {
                if (Transcripts.original(next.id) == null) Asr.ensure(app, next.id, 0, maxMs = 90_000)
                else Transcripts.inAppLanguage(next.id) // translate YouTube captions ahead too
            }
        }

        // Images are small: warm the next several thumbnails and illustrations.
        upcoming.take(6).mapNotNull {
            when (it) {
                is FeedItem.VideoItem -> it.video.thumbnail
                is FeedItem.PinItem -> it.pin.image
                else -> null
            }
        }.forEach { url -> app.imageLoader.enqueue(ImageRequest.Builder(app).data(url).build()) }
    }
}
