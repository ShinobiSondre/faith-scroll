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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

@Composable
fun VideoCard(
    video: Video,
    isActive: Boolean,
    saved: Boolean,
    onSave: () -> Unit,
    bottomInset: Dp
) {
    val ctx = LocalContext.current
    var paused by remember(video.id) { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var playing by remember(video.id) { mutableStateOf(false) }
    var failed by remember(video.id) { mutableStateOf(false) }
    LaunchedEffect(isActive) { if (!isActive) { paused = false; playing = false } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Thumbnail shows until the player is actually playing (and for off-screen cards).
        AsyncImage(
            model = video.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (playing) 0f else 0.35f)))

        if (isActive && !failed) {
            YouTubePlayer(
                videoId = video.id,
                paused = paused,
                muted = muted,
                onPlaying = { playing = true },
                onError = { failed = true },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Tap layer: tap to pause/play. Swipes pass through to the pager.
        Box(
            Modifier.fillMaxSize().pointerInput(video.id) {
                detectTapGestures(onTap = { paused = !paused })
            }
        )

        if (isActive && !playing && !failed && !paused) {
            CircularProgressIndicator(color = Gold, modifier = Modifier.align(Alignment.Center).size(42.dp))
        }
        AnimatedVisibility(
            visible = paused || failed,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 1.4f),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                    .clickable { if (failed) Share.openUrl(ctx, video.url) else paused = false },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(52.dp))
            }
        }

        // Caption
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxSizeBottomScrim()
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 90.dp, bottom = bottomInset + 22.dp)
        ) {
            Text(
                "REAL STORIES OF FAITH",
                color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(6.dp))
            Text("@${video.channel.replace(" ", "")}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(video.title, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (failed) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))
                        .clickable { Share.openUrl(ctx, video.url) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("This one plays on YouTube — tap to watch", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomInset + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            RailButton(if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (saved) "Saved" else "Save", saved, if (saved) Gold else Color.White, onSave)
            RailButton(Icons.Filled.Share, "Share", false, Color.White) { Share.video(ctx, video) }
            RailButton(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (muted) "Muted" else "Sound", false, Color.White) { muted = !muted }
            RailButton(Icons.AutoMirrored.Filled.OpenInNew, "YouTube", false, Color.White) { Share.openUrl(ctx, video.url) }
        }
    }
}

private fun Modifier.fillMaxSizeBottomScrim(): Modifier = this
    .fillMaxSize()
    .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)))

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
    modifier: Modifier = Modifier
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
                setOnTouchListener { _, _ -> true } // gestures handled by Compose
                addJavascriptInterface(
                    PlayerBridge(
                        onState = { s -> if (s == 1) post { onPlaying() } },
                        onError = { post { onError() } }
                    ),
                    "Android"
                )
                loadDataWithBaseURL("https://www.youtube.com", playerHtml(videoId, muted), "text/html", "utf-8", null)
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

private fun playerHtml(id: String, muted: Boolean) = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;padding:0;height:100%;background:transparent;overflow:hidden}#p{position:absolute;inset:0;width:100%;height:100%}</style>
</head><body><div id="p"></div>
<script>
var player,ready=false,want='play',muted=$muted;
var s=document.createElement('script');s.src='https://www.youtube.com/iframe_api';document.head.appendChild(s);
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{width:'100%',height:'100%',videoId:'$id',
    playerVars:{autoplay:1,playsinline:1,controls:0,rel:0,modestbranding:1,fs:0,iv_load_policy:3,loop:1,playlist:'$id',disablekb:1},
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
