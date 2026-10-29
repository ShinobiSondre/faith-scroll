package com.faithscroll.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import coil.compose.AsyncImagePainter
import com.faithscroll.app.data.Pin
import com.faithscroll.app.data.TikTok

/** A full-screen Christian quote / illustration from Pinterest. */
@Composable
fun PinCard(
    pin: Pin,
    reason: String?,
    saved: Boolean,
    liked: Boolean,
    onLike: () -> Unit,
    onDoubleTapLike: () -> Unit,
    onSave: () -> Unit,
    onShared: () -> Unit,
    bottomInset: Dp
) {
    val ctx = LocalContext.current
    val t = rememberInfiniteTransition(label = "pin")
    val zoom by t.animateFloat(1f, 1.06f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom")
    var burstAt by remember { mutableStateOf<Offset?>(null) }
    var burstKey by remember { mutableIntStateOf(0) }
    var loading by remember(pin.id) { mutableStateOf(true) }

    Box(
        Modifier.fillMaxSize().background(Ink).pointerInput(pin.id) {
            detectTapGestures(onDoubleTap = { pos -> onDoubleTapLike(); burstAt = pos; burstKey++ })
        }
    ) {
        // Blurred copy behind, so any shape of image fills the screen nicely.
        AsyncImage(pin.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(36.dp))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        AsyncImage(
            pin.image, pin.title, contentScale = ContentScale.Fit,
            onState = { loading = it is AsyncImagePainter.State.Loading },
            modifier = Modifier.fillMaxSize().padding(top = 56.dp, bottom = bottomInset + 8.dp)
                .graphicsLayer { scaleX = zoom; scaleY = zoom }
        )
        if (loading) CircularProgressIndicator(color = Gold, modifier = Modifier.align(Alignment.Center).size(40.dp))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))

        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 90.dp, bottom = bottomInset + 26.dp)) {
            reason?.let { ReasonTag(it); Spacer(Modifier.height(8.dp)) }
            Text(if (pin.board.startsWith("r/")) "via Reddit · ${pin.board}" else "via Pinterest · ${pin.board.substringBefore("/")}", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (pin.title.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(pin.title, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomInset + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            RailButton(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (liked) "Amen" else "Like", liked, if (liked) HeartRed else Color.White, onLike)
            RailButton(if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (saved) "Saved" else "Save", saved, if (saved) Gold else Color.White, onSave)
            RailButton(Icons.Filled.Share, "Share", false, Color.White) {
                onShared()
                val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, listOf(pin.title, pin.link).filter { it.isNotBlank() }.joinToString("\n"))
                ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        burstAt?.let { HeartBurstAt(it, burstKey) }
    }
}

/** A TikTok, played full screen with TikTok's official embedded player. */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun TikTokCard(tiktok: TikTok, reason: String?, isActive: Boolean, bottomInset: Dp) {
    var paused by remember(tiktok.id) { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    fun send(type: String, value: String? = null) {
        val v = if (value != null) ",value:$value" else ""
        web?.evaluateJavascript("window.postMessage({type:'$type'$v,'x-tiktok-player':true},'*')", null)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isActive) {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(bottom = bottomInset),
                factory = { c ->
                    WebView(c).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            // Keep everything inside the app (no jumping to the TikTok app).
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                                !(request.url.scheme == "http" || request.url.scheme == "https")
                        }
                        setOnTouchListener { _, _ -> true }
                        loadUrl("https://www.tiktok.com/player/v1/${tiktok.id}?autoplay=1&loop=1&controls=0&progress_bar=1&play_button=0&volume_control=0&fullscreen_button=0&music_info=0&description=0&rel=0&native_context_menu=0&closed_caption=0")
                        web = this
                    }
                },
                onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy(); web = null }
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Gold.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
            }
        }
        // Tap = pause / play; swipes go to the feed.
        Box(Modifier.fillMaxSize().pointerInput(tiktok.id) {
            detectTapGestures(onTap = { paused = !paused; send(if (paused) "pause" else "play") })
        })
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 90.dp, bottom = bottomInset + 30.dp)) {
            reason?.let { ReasonTag(it); Spacer(Modifier.height(8.dp)) }
            if (tiktok.author.isNotBlank()) Text("@${tiktok.author}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomInset + 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RailButton(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (muted) "Muted" else "Sound", false, Color.White) {
                muted = !muted; send(if (muted) "mute" else "unMute")
            }
        }
    }
    DisposableEffect(lifecycle, web) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> { send("pause"); web?.onPause() }
                Lifecycle.Event.ON_RESUME -> { web?.onResume(); if (!paused) send("play") }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(isActive) { if (!isActive) paused = false }
}
