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
            jobs[v.id] = scope.launch {
                gate.withPermit {
                    StreamResolver.resolve(v.id)?.let { r -> if (!LangFilter.spokenOk(r.spoken)) { LangFilter.block(v.id, r.spoken); return@withPermit } }
                    StreamResolver.preload(app, v.id)
                }
            }
        }

        // Subtitles: fetch YouTube captions (and YouTube's translation) for the next 3 videos now,
        // so they're ready the moment you swipe. Phone transcription only if YouTube has none.
        val captionsOn = app.getSharedPreferences("captions", Context.MODE_PRIVATE).getBoolean("on", true)
        // (Subtitles are made on the phone only for the video you're watching, so upcoming videos
        //  get all the bandwidth and processor time to load fast.)
        if (captionsOn && false) Unit

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
