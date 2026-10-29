package com.faithscroll.app.data

import android.annotation.SuppressLint
import android.app.Activity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube only hands out caption files to its own player (with a fresh token). So, for a video
 * that's coming up, a tiny invisible YouTube player loads it muted, and we note the caption link
 * it requests. With that link we can fetch the captions, and YouTube's own auto-translation.
 */
object CaptionGrabber {
    private var host: WeakReference<Activity>? = null
    private val links = ConcurrentHashMap<String, String>()
    private val none = ConcurrentHashMap<String, Long>()
    private val lock = Mutex()

    fun attach(a: Activity) { host = WeakReference(a) }

    fun known(videoId: String): String? = links[videoId]
    fun remember(videoId: String, url: String) { links[videoId] = url }

    /** The caption link for [videoId], or null if YouTube has no captions for it (or it timed out). */
    suspend fun grab(videoId: String): String? {
        links[videoId]?.let { return it }
        none[videoId]?.let { if (System.currentTimeMillis() - it < 30 * 60_000) return null }
        return lock.withLock {
            links[videoId] ?: withContext(Dispatchers.Main) { run(videoId) }.also {
                if (it == null) { none[videoId] = System.currentTimeMillis(); Diag.log("subs", "$videoId: YouTube has no captions for it") }
                else Diag.log("subs", "$videoId: caption link from YouTube's player")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun run(videoId: String): String? {
        val activity = host?.get() ?: return null
        val root = activity.window?.decorView as? ViewGroup ?: return null
        val found = CompletableDeferred<String?>()
        val web = WebView(activity).apply {
            alpha = 0.01f
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url.toString()
                    if (u.contains("/api/timedtext") && !found.isCompleted) found.complete(u)
                    return null
                }
            }
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface fun noCaptions() { if (!found.isCompleted) found.complete(null) }
            }, "Grab")
        }
        root.addView(web, FrameLayout.LayoutParams(4, 4))
        web.loadDataWithBaseURL("https://www.youtube.com", html(videoId), "text/html", "utf-8", null)
        val url = withTimeoutOrNull(20_000) { found.await() }
        root.removeView(web)
        web.stopLoading(); web.loadUrl("about:blank"); web.destroy()
        return url?.also { links[videoId] = it }
    }

    private fun html(id: String) = """
<!DOCTYPE html><html><body style="margin:0"><div id="p"></div><script>
var s=document.createElement('script');s.src='https://www.youtube.com/iframe_api';document.head.appendChild(s);
var p,checked=0;
function onYouTubeIframeAPIReady(){
 p=new YT.Player('p',{width:4,height:4,videoId:'$id',playerVars:{autoplay:1,mute:1,playsinline:1,controls:0,cc_load_policy:1},
  events:{onReady:function(e){e.target.mute();e.target.playVideo();},
   onApiChange:function(){try{p.loadModule('captions');var tl=p.getOption('captions','tracklist')||[];
     if(tl.length){p.setOption('captions','track',{languageCode:tl[0].languageCode});}else if(++checked>3){Grab.noCaptions();}}catch(x){}}}});
}
</script></body></html>
"""
}
