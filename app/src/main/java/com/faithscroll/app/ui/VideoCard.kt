package com.faithscroll.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Check
import com.faithscroll.app.util.tr

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.ClosedCaptionOff
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.faithscroll.app.data.Video
import com.faithscroll.app.util.Share
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.draw.blur
import com.faithscroll.app.data.StreamResolver

@Composable
fun VideoCard(
    video: Video,
    reason: String? = null,
    onShared: () -> Unit = {},
    isActive: Boolean,
    saved: Boolean,
    onSave: () -> Unit,
    bottomInset: Dp,
    onExpand: (() -> Unit)? = null,
    loop: Boolean = true,
    onRotate: (() -> Unit)? = null,
    landscape: Boolean = false,
    preload: Boolean = false, // the next card: player ready & paused so it starts instantly
    suspended: Boolean = false, // another tab / screen is on top: pause, but keep the place
    immersive: Boolean = false, // full screen: nothing but the video while it plays; everything returns on pause
    onPausedChange: (Boolean) -> Unit = {}
) {
    val ctx = LocalContext.current
    var paused by remember(video.id) { mutableStateOf(false) }
    var controls by remember(video.id) { mutableStateOf(false) }
    var expanded by remember(video.id) { mutableStateOf(false) }
    var asking by remember(video.id) { mutableStateOf<Long?>(null) }
    var askBusy by remember(video.id) { mutableStateOf<String?>(null) }
    val openPage = LocalOpenPage.current
    val isYouTube = video.direct.isBlank()
    LaunchedEffect(Unit) { CaptionSettings.load(ctx) }
    // Captions available for this video? If they don't load, subtitles are simply turned off for it.
    var hasCaptions by remember(video.id) { mutableStateOf<Boolean?>(null) }
    // Subtitles come from YouTube, or the phone makes them (English and Norwegian speech).
    if (isYouTube) LaunchedEffect(video.id) { hasCaptions = true }
    // Subscribe: follow this channel to get more of its videos.
    val subScope = rememberCoroutineScope()
    var channelId by remember(video.id) { mutableStateOf<String?>(null) }
    var subscribed by remember(video.id) { mutableStateOf(com.faithscroll.app.data.YouTubeSubs.isFavouriteName(ctx, video.channel)) }
    var controlsTouch by remember(video.id) { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(false) }
    var fill by remember(video.id) { mutableStateOf<Boolean?>(null) } // null = automatic
    val playback = remember(video.id) { PlaybackState() }
    var embedPlaying by remember(video.id) { mutableStateOf(false) }
    var embedFailed by remember(video.id) { mutableStateOf(false) }
    var embedPos by remember(video.id) { mutableStateOf(0L) }
    var embedStart by remember(video.id) { mutableStateOf(0) }
    val cardScope = androidx.compose.runtime.rememberCoroutineScope()
    // When the app's own player fails, continue in YouTube's player from the same second.
    LaunchedEffect(playback.failed) { if (playback.failed) { embedStart = (playback.position / 1000).toInt(); com.faithscroll.app.data.Diag.log("play", "${video.id}: own player failed at ${embedStart}s → YouTube player") } }
    // Coming from / going to full screen: continue from the same second.
    LaunchedEffect(isActive, playback.ready, suspended) {
        if (isActive && playback.ready && !suspended) com.faithscroll.app.data.PlaybackMemory.take(video.id)?.let { playback.seekTo(it) }
    }
    if (immersive) androidx.compose.runtime.DisposableEffect(video.id) {
        onDispose { com.faithscroll.app.data.PlaybackMemory.put(video.id, if (playback.failed) embedPos else playback.position) }
    }
    // Autoplay: every card starts playing when it comes on screen.
    LaunchedEffect(isActive) { paused = false; controls = false }
    // Back from full screen: keep playing here.
    var pausedForFull by remember(video.id) { mutableStateOf(false) }
    LaunchedEffect(suspended) { if (!suspended && pausedForFull) { pausedForFull = false; paused = false } }

    // Controls fade away 2.5 s after the last touch while the video is playing.
    LaunchedEffect(controls, controlsTouch, paused) {
        if (controls && !paused) { kotlinx.coroutines.delay(2500); controls = false }
    }

    // Prepare the stream URL as soon as the card exists, so it starts instantly when swiped to.
    LaunchedEffect(video.id) {
        StreamResolver.resolve(video.id)?.let { r ->
            if (video.direct.isBlank() && !com.faithscroll.app.data.LangFilter.spokenOk(r.spoken)) com.faithscroll.app.data.LangFilter.block(video.id, r.spoken)
        }
    }

    // Fill the screen when the video's shape matches the screen's (portrait on a phone,
    // landscape when turned or unfolded); otherwise show it whole over a blurred backdrop.
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val screenLandscape = cfg.screenWidthDp > cfg.screenHeightDp
    val useFill = fill ?: (playback.portrait != screenLandscape)
    val showControls = isActive && !embedFailed && (controls || paused)
    // Full screen: all buttons, text and the progress line fade out while playing.
    val uiAlpha by androidx.compose.animation.core.animateFloatAsState(if (immersive && !paused) 0f else 1f, tween(250), label = "ui")
    val uiMod = Modifier.graphicsLayer { alpha = uiAlpha }
    LaunchedEffect(paused, isActive) { if (isActive) onPausedChange(paused) }
    val showingVideo = (playback.ready && !playback.failed) || embedPlaying

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Blurred thumbnail backdrop so landscape videos still fill the screen beautifully.
        AsyncImage(
            model = video.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(if (showingVideo) 40.dp else 0.dp)
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (showingVideo) 0.55f else 0.3f)))

        if (isActive || preload) {
            if (!playback.failed) {
                NativeVideo(
                    videoId = video.id, paused = paused || !isActive || suspended, muted = muted || !isActive, loop = loop, fill = useFill,
                    state = playback, modifier = Modifier.fillMaxSize()
                )
            } else if (!embedFailed && isActive) {
                // Fallback: YouTube's own player, still full screen inside the app.
                YouTubePlayer(
                    startSec = remember(video.id) { (playback.position / 1000).toInt() },
                    captions = CaptionSettings.on || asking != null,
                    onTimedText = { url -> cardScope.launch { com.faithscroll.app.data.Transcripts.captureTimedText(video.id, url) } },
                    onTime = { embedPos = it },
                    videoId = video.id, paused = paused || suspended, muted = muted,
                    onPlaying = { embedPlaying = true }, onError = { embedFailed = true },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Tap = pause / play. Double-tap = fill / fit. Swipes go to the pager.
        Box(
            Modifier.fillMaxSize().pointerInput(video.id) {
                detectTapGestures(
                    // First touch shows the controls; touching again hides them.
                    onTap = { if (immersive) paused = !paused else { controls = !controls; controlsTouch++ } },
                    onDoubleTap = { fill = !useFill }
                )
            }
        )

        if (isActive && !paused && !embedFailed && (playback.buffering && !embedPlaying)) {
            CircularProgressIndicator(color = Gold, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(44.dp))
        }
        // When paused: ask what the speaker means at this moment.
        val usingEmbed = playback.failed && !embedFailed

        // Play / pause button: hidden while playing, shown when you touch the video (or it's paused).
        AnimatedVisibility(
            visible = showControls && !(immersive && !paused),
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 1.3f),
            exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.85f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                    .clickable { paused = !paused; controlsTouch++ },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) tr("Play", "Spill av") else tr("Pause", "Pause"),
                    tint = Color.White, modifier = Modifier.size(52.dp))
            }
        }
        if (embedFailed) {
            Text(tr("This video can't play right now. Swipe for the next one.", "Denne videoen kan ikke spilles nå. Sveip til neste."),
                color = Color.White, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }

        if (uiAlpha > 0f) Box(Modifier.fillMaxSize().then(uiMod).background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))

        // Subtitles (live, in the app language)
        if (isActive && isYouTube && CaptionSettings.on && hasCaptions == true && !playback.failed) {
            CaptionOverlay(
                video.id, playback.position,
                Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = bottomInset + 190.dp)
            )
        }

        // Caption: tap to see the full title, description and a link to the original page
        if (uiAlpha > 0f) Column(
            Modifier.align(Alignment.BottomStart).then(uiMod).padding(start = 20.dp, end = 90.dp, bottom = bottomInset + 44.dp)
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { expanded = true }
        ) {
            if (reason != null) {
                ReasonTag(reason)
                Spacer(Modifier.height(8.dp))
            }
            Text(
                when {
                    video.kind == Video.KIND_SERMON -> tr("SERMON", "PREKEN")
                    video.channel.startsWith("r/") -> tr("FROM REDDIT", "FRA REDDIT")
                    video.kind == Video.KIND_SHORT -> tr("SHORT", "KORT")
                    else -> tr("REAL STORIES OF FAITH", "EKTE TROSHISTORIER")
                },
                color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(video.channel, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(video.title, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(tr("more", "mer"), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        if (uiAlpha > 0f) Column(
            Modifier.align(Alignment.BottomEnd).then(uiMod).padding(end = 12.dp, bottom = bottomInset + 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RailButton(if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (saved) tr("Saved", "Lagret") else tr("Save", "Lagre"), saved, if (saved) Gold else Color.White, onSave)
            RailButton(Icons.Filled.Share, tr("Share", "Del"), false, Color.White) { onShared(); Share.video(ctx, video) }
            RailButton(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (muted) tr("Muted", "Lydløs") else tr("Sound", "Lyd"), false, Color.White) { muted = !muted }
            if (isYouTube) RailButton(
                if (subscribed) Icons.Filled.Check else Icons.Filled.PersonAdd,
                if (subscribed) tr("Subscribed", "Abonnerer") else tr("Subscribe", "Abonner"), subscribed, if (subscribed) Gold else Color.White
            ) {
                subScope.launch {
                    val id = channelId ?: com.faithscroll.app.data.WatchPage.info(video.id)?.channelId?.takeIf { it.isNotBlank() }?.also { channelId = it }
                    if (id == null) { android.widget.Toast.makeText(ctx, tr("Couldn't find the channel", "Fant ikke kanalen"), android.widget.Toast.LENGTH_SHORT).show(); return@launch }
                    if (subscribed) { com.faithscroll.app.data.YouTubeSubs.unfollow(ctx, id); subscribed = false }
                    else {
                        com.faithscroll.app.data.YouTubeSubs.follow(ctx, id, video.channel); subscribed = true
                        android.widget.Toast.makeText(ctx, tr("More from ${video.channel} coming to your feed", "Mer fra ${video.channel} kommer i feeden din"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (isYouTube && hasCaptions == true) RailButton(
                if (CaptionSettings.on) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionOff,
                tr("Subtitles", "Tekst"), CaptionSettings.on, if (CaptionSettings.on) Gold else Color.White
            ) { CaptionSettings.toggle(ctx) }
            if (onRotate != null) RailButton(Icons.Filled.ScreenRotation, tr("Rotate", "Roter"), landscape, Color.White, onRotate)
            if (onExpand != null) RailButton(Icons.Filled.OpenInFull, tr("Full", "Full"), false, Color.White) {
                com.faithscroll.app.data.PlaybackMemory.put(video.id, if (playback.failed) embedPos else playback.position)
                paused = true; pausedForFull = true; onExpand()
            }
        }

        // Expanded details
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded, enter = fadeIn(tween(200)), exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            VideoDetails(video, onClose = { expanded = false }, onGoToPage = { expanded = false; paused = true; openPage(video.url) })
        }
        asking?.let { at -> AskSheet(video, at) { asking = null } }

        // "What do they mean here?" – drawn last so it sits above the title, buttons and details,
        // just under the play button (clear of the text at the bottom, also on a small folded screen).
        if (isActive && paused && isYouTube && !expanded && askBusy == null && (!playback.failed || usingEmbed)) {
            Text(
                "✦ " + tr("What do they mean here?", "Hva mener de her?"),
                color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center).offset(y = 82.dp).zIndex(5f)
                    .shadow(8.dp, CircleShape).clip(CircleShape).background(Gold)
                    .clickable {
                        com.faithscroll.app.ui.AskChatGpt.open(ctx, video, if (usingEmbed) embedPos else playback.position,
                            onBusy = { askBusy = it }, openPage = openPage)
                    }
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
        // Loading while the moment is being transcribed.
        askBusy?.let { msg ->
            var secs by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); secs++ } }
            val lines by com.faithscroll.app.data.Asr.cues(video.id).collectAsState()
            Box(
                Modifier.fillMaxSize().zIndex(6f).background(Color.Black.copy(alpha = 0.7f))
                    .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    CircularProgressIndicator(color = Gold, strokeWidth = 4.dp, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(18.dp))
                    Text(msg, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tr("${secs} s · ${lines.size} lines so far", "${secs} s · ${lines.size} linjer så langt"),
                        color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp
                    )
                    lines.lastOrNull()?.let {
                        Spacer(Modifier.height(10.dp))
                        Text("«${it.text}»", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
            }
        }

        // Progress: a thin line while playing; the full seek bar appears with the controls.
        if (isActive && playback.duration > 0 && !playback.failed && uiAlpha > 0f) {
            if (showControls) {
                SeekBar(playback, { controlsTouch++ }, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 6.dp).fillMaxWidth())
            } else {
                val frac = (playback.position.toFloat() / playback.duration).coerceIn(0f, 1f)
                Box(Modifier.align(Alignment.BottomStart).padding(bottom = bottomInset + 16.dp).fillMaxWidth().height(2.5.dp).background(Color.White.copy(alpha = 0.22f)))
                Box(Modifier.align(Alignment.BottomStart).padding(bottom = bottomInset + 16.dp).fillMaxWidth(frac).height(2.5.dp).background(Gold))
            }
        }
    }
}

@Composable
private fun SeekBar(p: PlaybackState, onInteract: () -> Unit, modifier: Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val frac = dragging ?: (if (p.duration > 0) p.position.toFloat() / p.duration else 0f)
    Column(modifier.padding(horizontal = 8.dp)) {
        if (dragging != null) {
            Text(
                "${fmt((frac * p.duration).toLong())} / ${fmt(p.duration)}",
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
        Slider(
            value = frac.coerceIn(0f, 1f),
            onValueChange = { dragging = it; onInteract() },
            onValueChangeFinished = { dragging?.let { p.seekTo((it * p.duration).toLong()) }; dragging = null },
            colors = SliderDefaults.colors(
                thumbColor = if (dragging != null) Gold else Color.White.copy(alpha = 0.9f),
                activeTrackColor = Gold, inactiveTrackColor = Color.White.copy(alpha = 0.25f)
            ),
            modifier = Modifier.height(24.dp)
        )
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private class PlayerBridge(val onState: (Int) -> Unit, val onError: (Int) -> Unit, val onTime: (Long) -> Unit = {}) {
    @JavascriptInterface fun state(s: Int) = onState(s)
    @JavascriptInterface fun error(code: Int) = onError(code)
    @JavascriptInterface fun time(ms: Double) = onTime(ms.toLong())
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun YouTubePlayer(
    videoId: String,
    paused: Boolean,
    muted: Boolean,
    onPlaying: () -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
    controls: Boolean = false,
    startSec: Int = 0,
    captions: Boolean = false, // show YouTube's captions, translated into the app language
    onTimedText: (String) -> Unit = {}, // the caption file YouTube's player downloaded
    onTime: (Long) -> Unit = {}
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val lang = com.faithscroll.app.data.Transcripts.target()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    AndroidView(
        modifier = modifier,
        factory = { c ->
            WebView(c).apply {
                setBackgroundColor(AColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = object : android.webkit.WebViewClient() {
                    // Notice when YouTube's player fetches captions (the URL carries its token).
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                        val u = request.url.toString()
                        if (u.contains("/api/timedtext")) post { onTimedText(u) }
                        return null
                    }
                }
                if (!controls) setOnTouchListener { _, _ -> true } // feed: gestures handled by Compose
                addJavascriptInterface(
                    PlayerBridge(
                        onState = { s -> if (s == 1) post { onPlaying() } },
                        onError = { post { onError() } },
                        onTime = { ms -> post { onTime(ms) } }
                    ),
                    "Android"
                )
                loadDataWithBaseURL("https://www.youtube.com", playerHtml(videoId, muted, controls, startSec, captions, lang), "text/html", "utf-8", null)
                webView = this
            }
        },
        onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() }
    )

    LaunchedEffect(paused, webView) { webView?.evaluateJavascript(if (paused) "pauseV()" else "playV()", null) }
    LaunchedEffect(muted, webView) { webView?.evaluateJavascript("setMuted(${muted})", null) }
    LaunchedEffect(captions, webView) { webView?.evaluateJavascript("setCaptions(${captions})", null) }

    DisposableEffect(lifecycle, webView) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> { webView?.evaluateJavascript("pauseV()", null); webView?.onPause() }
                Lifecycle.Event.ON_RESUME -> { webView?.onResume(); if (!paused) webView?.evaluateJavascript("playV()", null) }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
}

private fun playerHtml(id: String, muted: Boolean, controls: Boolean, startSec: Int = 0, captions: Boolean = false, lang: String = "en"): String {
    val ctl = if (controls) 1 else 0
    val loop = if (controls) "" else "loop:1,playlist:'$id',"
    return """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;padding:0;height:100%;background:transparent;overflow:hidden}#p{position:absolute;inset:0;width:100%;height:100%}</style>
</head><body><div id="p"></div>
<script>
var player,ready=false,want='play',muted=$muted,capsOn=$captions,lang='$lang';
var s=document.createElement('script');s.src='https://www.youtube.com/iframe_api';document.head.appendChild(s);
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{width:'100%',height:'100%',videoId:'$id',
    playerVars:{autoplay:1,playsinline:1,controls:$ctl,rel:0,modestbranding:1,fs:0,iv_load_policy:3,${loop}disablekb:1,start:$startSec,cc_load_policy:${if (captions) 1 else 0},cc_lang_pref:lang,hl:lang},
    events:{
      onReady:function(e){ready=true;if(muted)e.target.mute();else e.target.unMute();if(want=='play')e.target.playVideo();
        applyCaptions();setInterval(function(){try{Android.time(player.getCurrentTime()*1000);}catch(x){}},500);},
      onApiChange:function(){applyCaptions();},
      onStateChange:function(e){Android.state(e.data);},
      onError:function(e){Android.error(e.data);}
    }});
}
function playV(){want='play';if(ready)player.playVideo();}
function pauseV(){want='pause';if(ready)player.pauseVideo();}
function setMuted(m){muted=m;if(ready){if(m)player.mute();else player.unMute();}}
function applyCaptions(){
  if(!ready)return;
  try{
    if(!capsOn){player.unloadModule('captions');return;}
    player.loadModule('captions');
    var tl=player.getOption('captions','tracklist')||[];
    if(!tl.length)return;
    var t=null;
    for(var i=0;i<tl.length;i++){if((tl[i].languageCode||'').split('-')[0]==lang){t=tl[i];break;}}
    if(t){player.setOption('captions','track',{languageCode:t.languageCode});}
    else{player.setOption('captions','track',{languageCode:tl[0].languageCode,translationLanguage:{languageCode:lang}});}
  }catch(x){}
}
function setCaptions(on){capsOn=on;applyCaptions();}
</script></body></html>
"""
}

/** Full title, description and a button to the original page. */
@Composable
private fun VideoDetails(video: Video, onClose: () -> Unit, onGoToPage: () -> Unit) {
    var description by remember(video.id) { mutableStateOf(StreamResolver.cached(video.id)?.description.orEmpty()) }
    LaunchedEffect(video.id) {
        if (description.isBlank() && video.direct.isBlank()) description = StreamResolver.resolve(video.id)?.description.orEmpty()
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f))
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onClick = onClose)
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
        ) {
            Spacer(Modifier.height(40.dp))
            Text(video.channel, color = Gold, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(video.title, color = Color.White, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Text(
                if (video.direct.isNotBlank()) tr("Shared on Reddit.", "Delt på Reddit.") else description.ifBlank { "…" },
                color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, lineHeight = 20.sp
            )
            Spacer(Modifier.height(20.dp))
            Text(
                tr("Go to page", "Gå til siden") + "  →",
                color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(Gold).clickable(onClick = onGoToPage).padding(horizontal = 18.dp, vertical = 10.dp)
            )
            Spacer(Modifier.height(80.dp))
        }
    }
}
