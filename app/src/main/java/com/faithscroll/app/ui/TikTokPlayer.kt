package com.faithscroll.app.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.faithscroll.app.data.AskPrompt
import com.faithscroll.app.data.TikTok
import com.faithscroll.app.data.WatchHistory
import com.faithscroll.app.util.Lang
import com.faithscroll.app.util.Share
import com.faithscroll.app.util.tr
import org.json.JSONObject

/**
 * A TikTok, full screen, with the same things a YouTube video has: tap to pause, double-tap left /
 * right to go back / forward 10 s, save, share, sound, TV (screen mirroring), "What do they mean
 * here?", watch history with where you stopped, and on to the next video when it ends.
 * TikTok's player only plays inside a page, so it sits in a frame on a small page of our own.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun TikTokCard(
    tiktok: TikTok,
    reason: String?,
    isActive: Boolean,
    bottomInset: Dp,
    saved: Boolean? = null,
    onSave: (() -> Unit)? = null,
    onEnded: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val video = remember(tiktok) { tiktok.asVideo() }
    var paused by remember(tiktok.id) { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var ready by remember(tiktok.id) { mutableStateOf(false) }
    var position by remember(tiktok.id) { mutableLongStateOf(0L) }
    var duration by remember(tiktok.id) { mutableLongStateOf(0L) }
    var progress by remember(tiktok.id) { mutableFloatStateOf(0f) }
    var showCast by remember(tiktok.id) { mutableStateOf(false) }
    var chatAsk by remember(tiktok.id) { mutableStateOf<Pair<Long, String>?>(null) }
    val openPage = LocalOpenPage.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ended by rememberUpdatedState(onEnded)
    val active by rememberUpdatedState(isActive)
    val seek = remember(tiktok.id) { SeekTaps() }

    fun send(type: String, value: String? = null) {
        val v = if (value != null) ",value:$value" else ""
        web?.evaluateJavascript("(function(){var f=document.getElementById('p');if(f&&f.contentWindow)f.contentWindow.postMessage({type:'$type'$v,'x-tiktok-player':true},'*');})()", null)
    }
    fun jump(side: Int) {
        val now = System.currentTimeMillis()
        if (now > seek.until || side != seek.side) seek.total = 0
        seek.side = side; seek.until = now + 800; seek.total += 10; seek.shown++
        val to = ((position + side * 10_000L).coerceIn(0L, (duration - 500).coerceAtLeast(0L))) / 1000.0
        send("seekTo", "$to")
    }

    // Pause / play follows the card being on screen and the tap.
    LaunchedEffect(isActive, paused, ready) { if (ready) send(if (isActive && !paused) "play" else "pause") }
    LaunchedEffect(isActive) { if (!isActive) paused = false }

    // Watch history (and "seen"): the spot every few seconds and when you leave it.
    val latestPos by rememberUpdatedState(position)
    val latestDur by rememberUpdatedState(duration)
    if (isActive) LaunchedEffect(tiktok.id, paused) {
        while (!paused) { kotlinx.coroutines.delay(5_000); WatchHistory.record(ctx, video, latestPos, latestDur) }
        WatchHistory.record(ctx, video, latestPos, latestDur, force = true)
    }
    if (isActive) DisposableEffect(tiktok.id) { onDispose { WatchHistory.record(ctx, video, latestPos, latestDur, force = true) } }

    // Screen stays on while it plays.
    val view = LocalView.current
    val keepAwake = isActive && !paused
    DisposableEffect(keepAwake, tiktok.id) {
        if (keepAwake) KeepAwake.hold(view, "tt" + tiktok.id)
        onDispose { KeepAwake.release(view, "tt" + tiktok.id) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isActive) {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(bottom = bottomInset),
                factory = { c ->
                    TikTokConsent.prepare()
                    WebView(c).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        TikTokConsent.injectInto(this)
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            // Keep everything inside the app (no jumping to the TikTok app).
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                                !(request.url.scheme == "http" || request.url.scheme == "https")
                        }
                        setOnTouchListener { _, _ -> true }
                        // Messages from TikTok's player: ready, playing / ended, the time.
                        addJavascriptInterface(object {
                            @JavascriptInterface fun msg(json: String) {
                                val o = runCatching { JSONObject(json) }.getOrNull() ?: return
                                post {
                                    when (o.optString("type")) {
                                        "onPlayerReady" -> {
                                            ready = true
                                            // Continue where you stopped last time.
                                            WatchHistory.resumeAt(ctx, tiktok.id)?.let { send("seekTo", "${it / 1000.0}") }
                                            if (!active) send("pause")
                                        }
                                        "onStateChange" -> if (o.optInt("value") == 0 && active) {
                                            WatchHistory.finished(ctx, tiktok.id); ended?.invoke()
                                        }
                                        "onCurrentTime" -> o.optJSONObject("value")?.let { v ->
                                            position = (v.optDouble("currentTime") * 1000).toLong()
                                            duration = (v.optDouble("duration") * 1000).toLong()
                                            progress = if (duration > 0) position.toFloat() / duration else 0f
                                        }
                                    }
                                }
                            }
                        }, "FS")
                        val src = "https://www.tiktok.com/player/v1/${tiktok.id}?autoplay=1&loop=0&controls=0&progress_bar=0&play_button=0&volume_control=0&fullscreen_button=0&music_info=0&description=0&rel=0&native_context_menu=0&closed_caption=0"
                        // The frame fills the page by position (100vh was 0 when first laid out – then only the sound played).
                        val html = "<!doctype html><html style='height:100%'><head><meta name=viewport content='width=device-width,initial-scale=1'></head>" +
                            "<body style='margin:0;height:100%;background:#000;overflow:hidden'><iframe id=p src='$src' allow='autoplay; fullscreen; encrypted-media' " +
                            "style='position:fixed;top:0;left:0;right:0;bottom:0;width:100%;height:100%;border:0;display:block'></iframe>" +
                            "<script>function fit(){var f=document.getElementById('p');f.style.height=window.innerHeight+'px';f.style.width=window.innerWidth+'px';}" +
                            "window.addEventListener('resize',fit);setTimeout(fit,300);setTimeout(fit,1500);" +
                            "window.addEventListener('message',function(e){try{if(e.data&&e.data['x-tiktok-player'])FS.msg(JSON.stringify(e.data));}catch(x){}});</script></body></html>"
                        loadDataWithBaseURL("https://faithscroll.app/", html, "text/html", "utf-8", null)
                        web = this
                    }
                },
                onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy(); web = null }
            )
        }
        if (!ready) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Gold.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
        }

        // Tap = pause / play. Double-tap left / right = back / forward 10 s (each extra quick tap adds 10 s).
        Box(Modifier.fillMaxSize().pointerInput(tiktok.id) {
            fun sideOf(x: Float) = when { x < size.width * 0.35f -> -1; x > size.width * 0.65f -> 1; else -> 0 }
            var consumed = false
            detectTapGestures(
                onPress = { o ->
                    val side = sideOf(o.x)
                    consumed = side != 0 && side == seek.side && System.currentTimeMillis() < seek.until
                    if (consumed) jump(side)
                },
                onTap = { if (!consumed) paused = !paused },
                onDoubleTap = { o -> if (!consumed) { val side = sideOf(o.x); if (side != 0) jump(side) else paused = !paused } }
            )
        })
        if (seek.shown > 0) key(seek.shown) {
            val a = remember { Animatable(1f) }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(450); a.animateTo(0f, tween(350)) }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = a.value }, contentAlignment = if (seek.side < 0) Alignment.CenterStart else Alignment.CenterEnd) {
                Box(
                    Modifier.fillMaxWidth(0.38f).fillMaxHeight().background(Brush.horizontalGradient(
                        if (seek.side < 0) listOf(Color.White.copy(alpha = 0.16f), Color.Transparent) else listOf(Color.Transparent, Color.White.copy(alpha = 0.16f)))),
                    contentAlignment = Alignment.Center
                ) { Text(if (seek.side < 0) "«  ${seek.total} s" else "${seek.total} s  »", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        if (paused && isActive) Box(
            Modifier.align(Alignment.Center).size(84.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable { paused = false },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.PlayArrow, tr("Play", "Spill av"), tint = Color.White, modifier = Modifier.size(52.dp)) }

        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.65f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))

        // Who made it and what it says.
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 90.dp, bottom = bottomInset + 30.dp)) {
            reason?.let { ReasonTag(it); Spacer(Modifier.height(8.dp)) }
            Text("TIKTOK", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            if (tiktok.author.isNotBlank()) Text("@${tiktok.author}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (tiktok.caption.isNotBlank()) Text(tiktok.caption, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // Progress line.
        if (duration > 0) Box(Modifier.align(Alignment.BottomStart).padding(bottom = bottomInset + 6.dp).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Gold))
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = bottomInset + 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (saved != null && onSave != null) RailButton(if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (saved) tr("Saved", "Lagret") else tr("Save", "Lagre"), saved, if (saved) Gold else Color.White, onSave)
            RailButton(Icons.Filled.Share, tr("Share", "Del"), false, Color.White) { Share.video(ctx, video) }
            RailButton(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (muted) tr("Muted", "Lydløs") else tr("Sound", "Lyd"), false, Color.White) {
                muted = !muted; send(if (muted) "mute" else "unMute")
            }
            RailButton(Icons.Filled.Cast, tr("TV", "TV"), false, Color.White) { paused = true; showCast = true }
            val chat by com.faithscroll.app.data.ChatGptBridge.session.collectAsState()
            chat?.takeIf { it.videoId == tiktok.id }?.let { c -> RailButton(Icons.Filled.Forum, tr("Answer", "Svar"), true, Gold) { paused = true; chatAsk = c.atMs to c.url } }
        }

        // "What do they mean here?" when paused – ChatGPT gets the TikTok link, the caption and the moment.
        if (isActive && paused) Text(
            "✦ " + tr("What do they mean here?", "Hva mener de her?"),
            color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center).offset(y = 82.dp).zIndex(5f)
                .shadow(8.dp, CircleShape).clip(CircleShape).background(Gold)
                .clickable { chatAsk = position to tikTokAskUrl(tiktok, position) }
                .padding(horizontal = 20.dp, vertical = 12.dp)
        )
        chatAsk?.let { (at, url) -> ChatGptSheet(video, at, url, onOpenPage = { chatAsk = null; openPage(it) }) { chatAsk = null } }
        if (showCast) com.faithscroll.app.cast.CastSheet(null, { 0L }) { showCast = false }
    }
    DisposableEffect(lifecycle, web) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> { send("pause"); web?.onPause() }
                Lifecycle.Event.ON_RESUME -> { web?.onResume(); if (!paused && isActive) send("play") }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
}

internal fun tikTokAskUrl(t: TikTok, atMs: Long): String {
    val at = AskPrompt.time(atMs)
    val q = if (Lang.no) "TikTok-video: ${t.url}" + (if (t.author.isNotBlank()) " av @${t.author}" else "") + ". Pauset ved $at.\n" +
        (if (t.caption.isNotBlank()) "Teksten til videoen: «${t.caption}»\n\n" else "\n") +
        "Hva mener de ved $at? Forklar kort på norsk, ut fra sammenhengen. Hvis de viser til eller siterer et bibelvers, skriv henvisningen (f.eks. Johannes 3,16)."
    else "TikTok video: ${t.url}" + (if (t.author.isNotBlank()) " by @${t.author}" else "") + ". Paused at $at.\n" +
        (if (t.caption.isNotBlank()) "The video's caption: \"${t.caption}\"\n\n" else "\n") +
        "What do they mean at $at? Explain briefly, using the context. If they refer to or quote a Bible verse, give the reference (e.g. John 3:16)."
    return Uri.parse("https://chatgpt.com/").buildUpon().appendQueryParameter("hints", "search").appendQueryParameter("q", q).build().toString()
}

/**
 * TikTok's cookie question, answered "Allow all" for you: the consent cookie is stored up front, and
 * if the banner still shows inside TikTok's player, its "Allow all" button is pressed (or it's hidden).
 */
object TikTokConsent {
    private var prepared = false
    fun prepare() {
        if (prepared) return
        prepared = true
        val v = Uri.encode("{\"optional\":true,\"ga\":true,\"af\":true,\"fbp\":true,\"lip\":true,\"bing\":true,\"ttads\":true,\"reddit\":true,\"hubspot\":true,\"version\":\"v10\"}")
        val cm = CookieManager.getInstance()
        listOf("https://www.tiktok.com", "https://tiktok.com").forEach { cm.setCookie(it, "cookie-consent=$v; Domain=.tiktok.com; Path=/; Max-Age=31536000; Secure; SameSite=None") }
        cm.flush()
    }

    private const val CLICK = "(function(){var n=0;function walk(r,o){r.querySelectorAll('*').forEach(function(e){o.push(e);if(e.shadowRoot)walk(e.shadowRoot,o);});return o;}" +
        "function go(){n++;var all=walk(document,[]);var b=all.find(function(e){return (e.tagName==='BUTTON'||(e.getAttribute&&e.getAttribute('role')==='button'))&&" +
        "/^(allow all|tillat alle|godta alle|accept all|aksepter alle)$/i.test((e.textContent||'').trim());});" +
        "if(b){b.click();}var bn=document.querySelector('tiktok-cookie-banner');if(bn&&n>6)bn.style.display='none';" +
        "if(n<40&&(!b||bn))setTimeout(go,500);}setTimeout(go,400);})();"

    /** Runs [CLICK] in TikTok's frame (pages from www.tiktok.com), where the banner lives. */
    fun injectInto(w: WebView) {
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, CLICK, setOf("https://www.tiktok.com"))
        }
    }
}

/**
 * A TikTok account inside the app: its own TikTok page (all its videos), with a Follow button.
 * Tapping a video plays it in our own player – the page's other videos come next.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TikTokChannelScreen(user: String, onOpenVideo: (com.faithscroll.app.data.Video, List<com.faithscroll.app.data.Video>) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onClose)
    var following by remember(user) { mutableStateOf(com.faithscroll.app.data.TikTokFollows.isFollowed(ctx, user)) }
    var loading by remember { mutableStateOf(true) }
    // Catches taps on videos (links or the page switching to a video) and hands them to the app.
    val hook = "(function(){if(window.__fs)return;window.__fs=1;" +
        "function all(){var o=[];document.querySelectorAll('a[href*=\"/video/\"]').forEach(function(a){if(o.indexOf(a.href)<0)o.push(a.href);});return JSON.stringify(o);}" +
        "function go(u){try{FS.open(u,all());}catch(x){}}" +
        "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[href*=\"/video/\"]'):null;if(a){e.preventDefault();e.stopPropagation();go(a.href);}},true);" +
        "var ps=history.pushState;history.pushState=function(s,t,u){if(u&&/\\/video\\/\\d+/.test(String(u))){go(new URL(u,location.href).href);return;}return ps.apply(this,arguments);};})();"
    Column(Modifier.fillMaxSize().background(Ink).zIndex(20f).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { }) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().statusBarsPaddingCompat().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Tilbake"), tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text("@$user", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("TikTok", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
            Text(
                if (following) "✓ " + tr("Following", "Følger") else "+ " + tr("Follow", "Følg"),
                color = if (following) Gold else Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(if (following) Color.White.copy(alpha = 0.12f) else Gold)
                    .clickable {
                        if (following) com.faithscroll.app.data.TikTokFollows.unfollow(ctx, user) else com.faithscroll.app.data.TikTokFollows.follow(ctx, user)
                        following = !following
                    }.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    TikTokConsent.prepare()
                    WebView(c).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        TikTokConsent.injectInto(this)
                        runCatching {
                            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
                                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, hook, setOf("https://www.tiktok.com"))
                        }
                        addJavascriptInterface(object {
                            @JavascriptInterface fun open(url: String, list: String) {
                                fun parse(u: String) = Regex("@([^/?#]+)/video/(\\d{15,})").find(u)?.let { m -> com.faithscroll.app.data.TikTok(m.groupValues[2], m.groupValues[1]).asVideo() }
                                val v = parse(url) ?: return
                                val all = runCatching { org.json.JSONArray(list).let { a -> (0 until a.length()).mapNotNull { parse(a.getString(it)) } } }.getOrDefault(emptyList())
                                post { onOpenVideo(v, (listOf(v) + all.filter { it.id != v.id })) }
                            }
                        }, "FS")
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url.toString()
                                // A video: play it in the app. Other TikTok pages: stay here. Apps / other sites: not opened.
                                Regex("@([^/?#]+)/video/(\\d{15,})").find(u)?.let { m ->
                                    val v = com.faithscroll.app.data.TikTok(m.groupValues[2], m.groupValues[1]).asVideo()
                                    onOpenVideo(v, listOf(v)); return true
                                }
                                return !(request.url.host.orEmpty().endsWith("tiktok.com"))
                            }
                            override fun onPageFinished(view: WebView, url: String?) { loading = false; view.evaluateJavascript(hook, null) }
                        }
                        loadUrl("https://www.tiktok.com/@$user")
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() }
            )
            if (loading) CircularProgressIndicator(color = Gold, modifier = Modifier.align(Alignment.Center).size(36.dp))
        }
    }
}

private fun Modifier.statusBarsPaddingCompat() = this.then(Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars))
