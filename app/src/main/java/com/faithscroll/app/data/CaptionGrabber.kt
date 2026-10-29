package com.faithscroll.app.data

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube only hands out caption files to its own player (with a fresh token). So one tiny,
 * invisible YouTube player stays loaded, and for each video we just switch it to that video
 * (muted) and note the caption link it requests: about 1–2 seconds, versus 5–8 for building a
 * new player each time. The video on screen always goes first; the next ones queue behind it.
 * Found links are kept on the phone for a few hours, so revisiting or pausing to ask is instant.
 */
object CaptionGrabber {
    private const val TIMEOUT_MS = 9_000L
    private const val KEEP_MS = 4 * 60 * 60_000L // caption links stay valid for several hours

    private var host: WeakReference<Activity>? = null
    private var web: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    private val links = ConcurrentHashMap<String, Pair<Long, String>>()
    /** Caption tracks per video: (language code, kind) where kind "asr" = YouTube's automatic captions from the audio. */
    private val trackLists = ConcurrentHashMap<String, List<Pair<String, String>>>()
    fun tracks(videoId: String): List<Pair<String, String>> = trackLists[videoId].orEmpty()

    /** How many grabs worked / failed, to tell "this video has no captions" from "captions don't work on this phone". */
    @Volatile var successes = 0
    @Volatile var failures = 0
    val working: Boolean get() = successes > 0 || failures < 6

    private val texts = ConcurrentHashMap<Int, CompletableDeferred<String?>>()
    private var nextReq = 0

    /**
     * Downloads a caption file *inside* YouTube's player page (same site, same session and cookies),
     * so YouTube treats it like normal playback. Returns null if that fails.
     */
    suspend fun fetchText(url: String): String? {
        if (!ensurePlayer()) return null
        val d = CompletableDeferred<String?>()
        val id = synchronized(this) { ++nextReq }
        texts[id] = d
        withContext(Dispatchers.Main) {
            web?.evaluateJavascript("getText($id, ${JSONObject.quote(url)})", null)
        }
        return withTimeoutOrNull(15_000) { d.await() }.also { texts.remove(id) }
    }
    private val none = ConcurrentHashMap<String, Long>()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val queue = ArrayDeque<String>() // video ids, front = most urgent
    private var current: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var store: File? = null

    fun attach(a: Activity) {
        host = WeakReference(a)
        if (store == null) { store = File(a.filesDir, "caption_links.json"); runCatching { load() } }
        scope.launch { ensurePlayer() }
    }

    fun known(videoId: String): String? = links[videoId]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.second
    fun remember(videoId: String, url: String) { links[videoId] = System.currentTimeMillis() to url; save() }

    /**
     * The caption link for [videoId], or null if YouTube has none. [urgent] = the video on screen:
     * it jumps ahead of the background queue.
     */
    suspend fun grab(videoId: String, urgent: Boolean = false): String? {
        known(videoId)?.let { return it }
        none[videoId]?.let { if (System.currentTimeMillis() - it < 30 * 60_000) return null }
        val d = synchronized(this) {
            waiting[videoId]?.also { if (urgent) bump(videoId) } ?: CompletableDeferred<String?>().also {
                waiting[videoId] = it
                if (urgent) queue.addFirst(videoId) else queue.addLast(videoId)
            }
        }
        scope.launch { pump() }
        return d.await()
    }

    @Synchronized private fun bump(id: String) { if (queue.remove(id)) queue.addFirst(id) }

    /** Works through the queue one video at a time on the shared player. */
    private suspend fun pump() {
        if (current != null) return
        while (true) {
            val id = synchronized(this) { queue.removeFirstOrNull() } ?: return
            current = id
            val started = System.currentTimeMillis()
            val url = runCatching { load(id) }.getOrNull()
            val secs = "%.1f".format((System.currentTimeMillis() - started) / 1000f)
            if (url != null) { successes++; remember(id, url); Diag.log("subs", "$id: YouTube caption link in $secs s") }
            else { failures++; none[id] = System.currentTimeMillis(); Diag.log("subs", "$id: no YouTube captions ($secs s)") }
            waiting.remove(id)?.complete(url)
            current = null
        }
    }

    private var found: CompletableDeferred<String?>? = null
    private var foundFor: String? = null

    private suspend fun load(id: String): String? {
        if (!ensurePlayer()) return null
        val d = CompletableDeferred<String?>()
        found = d; foundFor = id
        web?.evaluateJavascript("go('$id')", null)
        return withTimeoutOrNull(TIMEOUT_MS) { d.await() }.also {
            found = null; foundFor = null
            web?.evaluateJavascript("try{cur=null;p.stopVideo();}catch(x){}", null) // got what we need; stop downloading video
        }
    }

    /** Builds the hidden player once and waits until YouTube's player API is ready. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensurePlayer(): Boolean = withContext(Dispatchers.Main) {
        if (web == null) {
            val activity = host?.get() ?: return@withContext false
            // Behind the app's own content: a normal-sized player YouTube treats as visible, but you never see or touch it.
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@withContext false
            ready = CompletableDeferred()
            web = WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val u = request.url
                        if (u.path?.contains("/api/timedtext") == true) {
                            val v = u.getQueryParameter("v")
                            if (v != null && v == foundFor) found?.complete(u.toString())
                            else if (v != null) remember(v, u.toString())
                        }
                        return null
                    }
                }
                addJavascriptInterface(Bridge, "Grab")
            }
            root.addView(web, 0, FrameLayout.LayoutParams(320, 180))
            web!!.loadDataWithBaseURL("https://www.youtube.com", HTML, "text/html", "utf-8", null)
        }
        withTimeoutOrNull(15_000) { ready.await() } != null
    }

    private object Bridge {
        @JavascriptInterface fun ready() { ready.complete(Unit) }
        @JavascriptInterface fun none(id: String) { if (id == foundFor) found?.complete(null) }
        @JavascriptInterface fun text(req: Int, body: String?) { texts[req]?.complete(body?.takeIf { it.isNotBlank() }) }
        @JavascriptInterface fun tracks(id: String, json: String) {
            runCatching {
                val a = org.json.JSONArray(json)
                trackLists[id] = (0 until a.length()).map { a.getJSONObject(it).let { t -> t.optString("c") to t.optString("k") } }
            }
        }
    }

    /** Frees the hidden player (e.g. when the app is closed); it's rebuilt when needed. */
    fun release() {
        scope.launch {
            web?.let { w -> (w.parent as? ViewGroup)?.removeView(w); w.stopLoading(); w.destroy() }
            web = null
        }
    }

    private fun save() {
        val f = store ?: return
        runCatching {
            val now = System.currentTimeMillis()
            val o = JSONObject()
            links.entries.filter { now - it.value.first < KEEP_MS }.forEach { (k, v) -> o.put(k, JSONObject().put("t", v.first).put("u", v.second)) }
            f.writeText(o.toString())
        }
    }

    private fun load() {
        val f = store ?: return
        if (!f.exists()) return
        val o = JSONObject(f.readText())
        val now = System.currentTimeMillis()
        o.keys().forEach { k ->
            val e = o.getJSONObject(k)
            if (now - e.getLong("t") < KEEP_MS) links[k] = e.getLong("t") to e.getString("u")
        }
    }

    private const val HTML = """
<!DOCTYPE html><html><body style="margin:0"><div id="p"></div><script>
var s=document.createElement('script');s.src='https://www.youtube.com/iframe_api';document.head.appendChild(s);
var p,cur=null,tries=0;
function onYouTubeIframeAPIReady(){
 p=new YT.Player('p',{width:320,height:180,playerVars:{autoplay:1,mute:1,playsinline:1,controls:0,cc_load_policy:1},
  events:{onReady:function(){Grab.ready();},
   onApiChange:function(){caps();},
   onStateChange:function(e){if(e.data==1){caps();}}}});
}
function caps(){
 if(!cur)return;
 try{p.loadModule('captions');var tl=p.getOption('captions','tracklist')||[];
  if(tl.length){Grab.tracks(cur,JSON.stringify(tl.map(function(t){return {c:t.languageCode||'',k:t.kind||''};})));
   p.setOption('captions','track',{languageCode:tl[0].languageCode});}
  else if(++tries>3){Grab.none(cur);}
 }catch(x){}
}
function getText(req,url){
 fetch(url,{credentials:'include'}).then(function(r){return r.text();})
  .then(function(t){Grab.text(req,t);}).catch(function(){Grab.text(req,null);});
}
function go(id){cur=id;tries=0;p.mute();p.loadVideoById({videoId:id,startSeconds:0});setTimeout(function(){try{p.playVideo();}catch(x){}},300);
 setTimeout(function(){if(cur==id)caps();},1500);setTimeout(function(){if(cur==id)caps();},3000);
 setTimeout(function(){if(cur==id&&tries>0)Grab.none(id);},6000);}
</script></body></html>
"""
}
