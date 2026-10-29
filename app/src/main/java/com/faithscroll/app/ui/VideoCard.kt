package com.faithscroll.app.ui

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
    preload: Boolean = false // the next card: player ready & paused so it starts instantly
) {
    val ctx = LocalContext.current
    var paused by remember(video.id) { mutableStateOf(false) }
    var controls by remember(video.id) { mutableStateOf(false) }
    var controlsTouch by remember(video.id) { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(false) }
    var fill by remember(video.id) { mutableStateOf<Boolean?>(null) } // null = automatic
    val playback = remember(video.id) { PlaybackState() }
    var embedPlaying by remember(video.id) { mutableStateOf(false) }
    var embedFailed by remember(video.id) { mutableStateOf(false) }
    // Autoplay: every card starts playing when it comes on screen.
    LaunchedEffect(isActive) { paused = false; controls = false }

    // Controls fade away 2.5 s after the last touch while the video is playing.
    LaunchedEffect(controls, controlsTouch, paused) {
        if (controls && !paused) { kotlinx.coroutines.delay(2500); controls = false }
    }

    // Prepare the stream URL as soon as the card exists, so it starts instantly when swiped to.
    LaunchedEffect(video.id) { StreamResolver.resolve(video.id) }

    // Fill the screen when the video's shape matches the screen's (portrait on a phone,
    // landscape when turned or unfolded); otherwise show it whole over a blurred backdrop.
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val screenLandscape = cfg.screenWidthDp > cfg.screenHeightDp
    val useFill = fill ?: (playback.portrait != screenLandscape)
    val showControls = isActive && !embedFailed && (controls || paused)
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
                    videoId = video.id, paused = paused || !isActive, muted = muted || !isActive, loop = loop, fill = useFill,
                    state = playback, modifier = Modifier.fillMaxSize()
                )
            } else if (!embedFailed && isActive) {
                // Fallback: YouTube's own player, still full screen inside the app.
                YouTubePlayer(
                    videoId = video.id, paused = paused, muted = muted,
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
                    onTap = { controls = !controls; controlsTouch++ },
                    onDoubleTap = { fill = !useFill }
                )
            }
        )

        if (isActive && !paused && !embedFailed && (playback.buffering && !embedPlaying)) {
            CircularProgressIndicator(color = Gold, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(44.dp))
        }
        // Play / pause button: hidden while playing, shown when you touch the video (or it's paused).
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 1.3f),
            exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.85f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                    .clickable { paused = !paused; controlsTouch++ },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) "Play" else "Pause",
                    tint = Color.White, modifier = Modifier.size(52.dp))
            }
        }
        if (embedFailed) {
            Text("This video can't play right now. Swipe for the next one.",
                color = Color.White, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }

        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))

        // Caption
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 90.dp, bottom = bottomInset + 30.dp)
        ) {
            if (reason != null) {
                ReasonTag(reason)
                Spacer(Modifier.height(8.dp))
            }
            Text(
                when {
                    video.kind == Video.KIND_SERMON -> "SERMON"
                    video.channel.startsWith("r/") -> "FROM REDDIT"
                    video.kind == Video.KIND_SHORT -> "SHORT"
                    else -> "REAL STORIES OF FAITH"
                },
                color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(video.channel, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(video.title, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomInset + 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RailButton(if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (saved) "Saved" else "Save", saved, if (saved) Gold else Color.White, onSave)
            RailButton(Icons.Filled.Share, "Share", false, Color.White) { onShared(); Share.video(ctx, video) }
            RailButton(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (muted) "Muted" else "Sound", false, Color.White) { muted = !muted }
            if (onRotate != null) RailButton(Icons.Filled.ScreenRotation, "Rotate", landscape, Color.White, onRotate)
            if (onExpand != null) RailButton(Icons.Filled.OpenInFull, "Full", false, Color.White) { paused = true; onExpand() }
        }

        // Progress: a thin line while playing; the full seek bar appears with the controls.
        if (isActive && playback.duration > 0 && !playback.failed) {
            if (showControls) {
                SeekBar(playback, { controlsTouch++ }, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset).fillMaxWidth())
            } else {
                val frac = (playback.position.toFloat() / playback.duration).coerceIn(0f, 1f)
                Box(Modifier.align(Alignment.BottomStart).padding(bottom = bottomInset).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.2f)))
                Box(Modifier.align(Alignment.BottomStart).padding(bottom = bottomInset).fillMaxWidth(frac).height(2.dp).background(Gold))
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

private class PlayerBridge(val onState: (Int) -> Unit, val onError: (Int) -> Unit) {
    @JavascriptInterface fun state(s: Int) = onState(s)
    @JavascriptInterface fun error(code: Int) = onError(code)
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
    controls: Boolean = false
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
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
                if (!controls) setOnTouchListener { _, _ -> true } // feed: gestures handled by Compose
                addJavascriptInterface(
                    PlayerBridge(
                        onState = { s -> if (s == 1) post { onPlaying() } },
                        onError = { post { onError() } }
                    ),
                    "Android"
                )
                loadDataWithBaseURL("https://www.youtube.com", playerHtml(videoId, muted, controls), "text/html", "utf-8", null)
                webView = this
            }
        },
        onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() }
    )

    LaunchedEffect(paused, webView) { webView?.evaluateJavascript(if (paused) "pauseV()" else "playV()", null) }
    LaunchedEffect(muted, webView) { webView?.evaluateJavascript("setMuted(${muted})", null) }

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

private fun playerHtml(id: String, muted: Boolean, controls: Boolean): String {
    val ctl = if (controls) 1 else 0
    val loop = if (controls) "" else "loop:1,playlist:'$id',"
    return """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;padding:0;height:100%;background:transparent;overflow:hidden}#p{position:absolute;inset:0;width:100%;height:100%}</style>
</head><body><div id="p"></div>
<script>
var player,ready=false,want='play',muted=$muted;
var s=document.createElement('script');s.src='https://www.youtube.com/iframe_api';document.head.appendChild(s);
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{width:'100%',height:'100%',videoId:'$id',
    playerVars:{autoplay:1,playsinline:1,controls:$ctl,rel:0,modestbranding:1,fs:0,iv_load_policy:3,${loop}disablekb:1},
    events:{
      onReady:function(e){ready=true;if(muted)e.target.mute();else e.target.unMute();if(want=='play')e.target.playVideo();},
      onStateChange:function(e){Android.state(e.data);},
      onError:function(e){Android.error(e.data);}
    }});
}
function playV(){want='play';if(ready)player.playVideo();}
function pauseV(){want='pause';if(ready)player.pauseVideo();}
function setMuted(m){muted=m;if(ready){if(m)player.mute();else player.unMute();}}
</script></body></html>
"""
}
