package com.faithscroll.app.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.faithscroll.app.data.Diag
import com.faithscroll.app.data.StreamResolver
import com.faithscroll.app.data.Video
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Google's ready-made receiver on the TV plays the video straight from its stream link. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder().setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Casting to the TV: finds Chromecasts / Google TVs / Cast TVs on the Wi-Fi, connects, and plays the
 * video there from the same second. For TVs without Google Cast (e.g. Samsung), [mirror] opens the
 * phone's screen mirroring (Smart View), which shows the whole app on the TV.
 */
object Cast {
    data class Device(val id: String, val name: String, val description: String)

    val devices = MutableStateFlow<List<Device>>(emptyList())
    /** Name of the TV we're connected to, or null. */
    val connectedTo = MutableStateFlow<String?>(null)
    /** The video playing on the TV right now. */
    val playing = MutableStateFlow<String?>(null)
    val status = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ctx: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var pending: Pair<Video, Long>? = null

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) { connected(s) }
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) { connected(s) }
        override fun onSessionEnded(s: CastSession, error: Int) { connectedTo.value = null; playing.value = null }
        override fun onSessionStartFailed(s: CastSession, error: Int) {
            status.value = com.faithscroll.app.util.tr("Couldn't connect to the TV.", "Fikk ikke koblet til TV-en."); pending = null
        }
        override fun onSessionStarting(s: CastSession) { status.value = com.faithscroll.app.util.tr("Connecting…", "Kobler til …") }
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionResumeFailed(s: CastSession, error: Int) {}
        override fun onSessionSuspended(s: CastSession, reason: Int) {}
    }

    /** False when the phone has no Google Play services (then only mirroring is offered). */
    fun available(context: Context): Boolean = init(context) != null

    private fun init(context: Context): CastContext? {
        ctx?.let { return it }
        return runCatching {
            @Suppress("DEPRECATION")
            CastContext.getSharedInstance(context.applicationContext).also { c ->
                ctx = c
                selector = c.mergedSelector
                router = MediaRouter.getInstance(context.applicationContext)
                c.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
                c.sessionManager.currentCastSession?.let { connected(it) }
            }
        }.onFailure { Diag.log("cast", "no Cast: ${it.message}") }.getOrNull()
    }

    /** Start looking for TVs (while the cast picker is open). */
    fun startSearch(context: Context) {
        init(context) ?: return
        val sel = selector ?: return
        router?.addCallback(sel, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
    }

    fun stopSearch() { router?.removeCallback(routerCallback) }

    private fun refresh() {
        val sel = selector ?: return
        devices.value = router?.routes.orEmpty()
            .filter { !it.isDefault && it.isEnabled && it.matchesSelector(sel) }
            .map { Device(it.id, it.name, it.description.orEmpty()) }
        if (devices.value.isNotEmpty()) Diag.log("cast", "Google Cast TVs: " + devices.value.joinToString { it.name })
    }

    /** Connect to [device] and play [video] there from [positionMs]. */
    fun castTo(context: Context, device: Device, video: Video, positionMs: Long) {
        init(context) ?: return
        val route = router?.routes?.firstOrNull { it.id == device.id } ?: return
        val hasVideo = video.id.isNotBlank() || video.direct.isNotBlank()
        pending = if (hasVideo) video to positionMs else null
        // YouTube videos open in the TV's YouTube app (like the YouTube app does); others in Google's player.
        val appId = if (isYouTube(video)) YouTubeLounge.APP_ID else CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        val session = ctx?.sessionManager?.currentCastSession
        if (session != null && session.isConnected && connectedTo.value == device.name && session.applicationMetadata?.applicationId == appId) {
            if (hasVideo) play(session, video, positionMs); return
        }
        if (session != null) ctx?.sessionManager?.endCurrentSession(true)
        runCatching { ctx?.setReceiverApplicationId(appId) }.onFailure { Diag.err("cast", "receiver app", it) }
        Diag.log("cast", "connecting to ${device.name} (${if (appId == YouTubeLounge.APP_ID) "YouTube app" else "media player"})")
        router?.selectRoute(route)
    }

    /** Already connected: send another video. */
    fun castVideo(video: Video, positionMs: Long) {
        val s = ctx?.sessionManager?.currentCastSession ?: return
        play(s, video, positionMs)
    }

    fun disconnect() {
        ctx?.sessionManager?.endCurrentSession(true)
        router?.let { it.unselect(MediaRouter.UNSELECT_REASON_STOPPED) }
        connectedTo.value = null; playing.value = null
    }

    private fun connected(s: CastSession) {
        connectedTo.value = s.castDevice?.friendlyName ?: "TV"
        status.value = null
        pending?.let { (v, pos) -> pending = null; play(s, v, pos) }
    }

    private fun isYouTube(v: Video) = v.direct.isBlank() && !v.isTikTok && v.id.isNotBlank()

    private fun play(s: CastSession, video: Video, positionMs: Long) {
        if (isYouTube(video) && s.applicationMetadata?.applicationId == YouTubeLounge.APP_ID) {
            scope.launch {
                status.value = com.faithscroll.app.util.tr("Starting on the TV…", "Starter på TV-en …")
                val err = YouTubeLounge.play(s, video.id, positionMs)
                if (err == null) { playing.value = video.id; status.value = null }
                else status.value = com.faithscroll.app.util.tr("The TV's YouTube app didn't start the video ($err).", "TV-ens YouTube-app startet ikke videoen ($err).")
            }
            return
        }
        scope.launch {
            status.value = com.faithscroll.app.util.tr("Starting on the TV…", "Starter på TV-en …")
            // The TV needs one file with both picture and sound (or an HLS stream).
            val url = video.direct.ifBlank {
                val r = StreamResolver.resolve(video.id)
                r?.muxed?.filter { it.height <= 1080 }?.maxByOrNull { it.height }?.url ?: r?.hls
            }
            if (url.isNullOrBlank()) {
                status.value = com.faithscroll.app.util.tr("This video can't be cast – try screen mirroring.", "Denne videoen kan ikke castes – prøv skjermdeling.")
                return@launch
            }
            val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, video.title)
                putString(MediaMetadata.KEY_SUBTITLE, video.channel)
                addImage(WebImage(Uri.parse(video.thumbnail)))
            }
            val info = MediaInfo.Builder(url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(if (url.contains(".m3u8") || url.contains("manifest/hls")) "application/x-mpegURL" else "video/mp4")
                .setMetadata(meta)
                .build()
            val req = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCurrentTime(positionMs).build()
            s.remoteMediaClient?.load(req)?.setResultCallback { res ->
                if (res.status.isSuccess) { playing.value = video.id; status.value = null; Diag.log("cast", "${video.id} playing on ${connectedTo.value}") }
                else {
                    status.value = com.faithscroll.app.util.tr("The TV couldn't play it – try screen mirroring.", "TV-en klarte ikke å spille den – prøv skjermdeling.")
                    Diag.log("cast", "${video.id} load failed: ${res.status.statusCode}")
                }
            }
        }
    }

    /** Where the TV is in the video now (to continue on the phone when you stop casting). */
    fun position(): Long? = ctx?.sessionManager?.currentCastSession?.remoteMediaClient?.approximateStreamPosition

    /**
     * Mirror the whole phone screen to the TV: Samsung's Smart View if it's there, otherwise
     * Android's own "Cast screen" settings.
     */
    fun mirror(context: Context): Boolean {
        val pm = context.packageManager
        val sv = "com.samsung.android.smartmirroring"
        // Samsung's Smart View: its own "choose a TV" screen (the same one as in the quick panel).
        val samsung = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(sv, android.content.pm.PackageManager.GET_ACTIVITIES).activities.orEmpty()
                .filter { it.exported }
                .also { a -> Diag.log("cast", "Smart View screens: " + a.joinToString { it.name.substringAfterLast('.') }) }
                .sortedBy { a -> listOf("Picker", "Capture", "Device", "Main", "Smart").indexOfFirst { a.name.contains(it, true) }.let { if (it < 0) 99 else it } }
                .map { Intent().setClassName(sv, it.name) }
        }.getOrDefault(emptyList())
        val tries = samsung + listOf(
            pm.getLaunchIntentForPackage(sv),
            Intent("android.settings.CAST_SETTINGS"),
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (i in tries) {
            if (i == null) continue
            val ok = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
            if (ok) { Diag.log("cast", "opened the phone's casting: ${i.component?.className ?: i.action}"); return true }
        }
        return false
    }

    /** True while the phone's screen is shown on a TV (Smart View / screen cast). */
    val mirroring = MutableStateFlow(false)
}
