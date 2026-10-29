package com.faithscroll.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.faithscroll.app.data.Video
import com.faithscroll.app.util.Share

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Full in-app player: watch sermons and testimonies without leaving FaithScroll. */
@Composable
fun PlayerScreen(start: Video, queue: List<Video>, state: AppState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    var current by remember { mutableStateOf(start) }
    var fullscreen by remember { mutableStateOf(false) }
    var embedFailed by remember(current.id) { mutableStateOf(false) }

    BackHandler { if (fullscreen) fullscreen = false else onClose() }

    // Landscape + hidden system bars while full screen.
    DisposableEffect(fullscreen) {
        activity?.let { a ->
            val ctl = WindowCompat.getInsetsController(a.window, a.window.decorView)
            if (fullscreen) {
                a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctl.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                ctl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.let { a ->
                a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(a.window, a.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val upNext = remember(current.id, queue) {
        val rest = queue.filter { it.id != current.id }
        rest.filter { it.channel == current.channel } + rest.filter { it.channel != current.channel }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { }
    ) {
        if (!fullscreen) {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text(if (current.kind == Video.KIND_SERMON) "Sermon" else "Story of faith", color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (!embedFailed) IconButton(onClick = { fullscreen = true }) { Icon(Icons.Filled.Fullscreen, "Full screen", tint = Color.White) }
            }
        }

        if (embedFailed) {
            // YouTube doesn't allow this video in embedded players, so show the YouTube page inside the app.
            key(current.id) { InAppWebPage(current.mobileUrl, Modifier.fillMaxWidth().weight(1f)) }
            return@Column
        }

        Box(
            if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        ) {
            key(current.id) {
                YouTubePlayer(
                    videoId = current.id, paused = false, muted = false,
                    onPlaying = {}, onError = { embedFailed = true },
                    modifier = Modifier.fillMaxSize(), controls = true
                )
            }
            if (fullscreen) {
                IconButton(
                    onClick = { fullscreen = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
                ) { Icon(Icons.Filled.FullscreenExit, "Exit full screen", tint = Color.White) }
            }
        }

        if (!fullscreen) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f).background(Ink).navigationBarsPadding()) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(current.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(current.channel, color = Gold, fontSize = 14.sp)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val saved = state.isVideoSaved(current.id)
                            Pill(if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (saved) "Saved" else "Save", saved) { state.toggleVideo(current) }
                            Pill(Icons.Filled.Share, "Share", false) { Share.video(ctx, current) }
                            Pill(Icons.Filled.Fullscreen, "Full screen", false) { fullscreen = true }
                        }
                    }
                }
                if (upNext.isNotEmpty()) item {
                    Text("Up next", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 8.dp))
                }
                items(upNext.take(40), key = { it.id }) { v ->
                    VideoRow(v) { current = v }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun Pill(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (active) Gold.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (active) Gold else Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
fun VideoRow(v: Video, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(v.thumbnail, null, contentScale = ContentScale.Crop,
            modifier = Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Plum))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, color = Color.White, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            Spacer(Modifier.height(4.dp))
            Text(v.channel, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** A YouTube page shown inside the app; links never leave FaithScroll. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppWebPage(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { c ->
            WebView(c).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val scheme = request.url.scheme
                        return !(scheme == "http" || scheme == "https") // block "open in app" intents
                    }
                }
                loadUrl(url)
            }
        },
        onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() }
    )
}
