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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.faithscroll.app.data.Taste
import com.faithscroll.app.data.Prefetcher
import com.faithscroll.app.data.Video
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Full-screen, immersive player: the video fills the screen, swipe up/down for the
 * next/previous one, tap to pause, drag the bar to seek, rotate for landscape.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(start: Video, queue: List<Video>, state: AppState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val list = remember(start, queue) { if (queue.any { it.id == start.id }) queue else listOf(start) + queue }
    val pager = rememberPagerState(initialPage = list.indexOfFirst { it.id == start.id }.coerceAtLeast(0)) { list.size }
    var landscape by remember { mutableStateOf(false) }

    BackHandler { if (landscape) landscape = false else onClose() }

    LaunchedEffect(pager.settledPage) {
        Prefetcher.ahead(ctx, list.drop(pager.settledPage + 1).take(4).map { com.faithscroll.app.data.FeedItem.VideoItem(it, null) })
    }

    // Learn from how long each video is watched.
    LaunchedEffect(pager) {
        var last = pager.settledPage
        var since = System.currentTimeMillis()
        snapshotFlow { pager.settledPage }.collect { page ->
            if (page != last) {
                list.getOrNull(last)?.let { Taste.videoSeen(ctx, it, System.currentTimeMillis() - since) }
                last = page; since = System.currentTimeMillis()
            }
        }
    }
    DisposableEffect(Unit) {
        val opened = System.currentTimeMillis()
        onDispose { list.getOrNull(pager.settledPage)?.let { Taste.videoSeen(ctx, it, System.currentTimeMillis() - opened) } }
    }

    // Immersive: hide the status and navigation bars while watching.
    DisposableEffect(Unit) {
        val ctl = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(landscape) {
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { }
    ) {
        VerticalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { list[it].id },
            userScrollEnabled = !landscape
        ) { page ->
            val v = list[page]
            VideoCard(
                video = v,
                isActive = pager.settledPage == page,
                preload = page == pager.settledPage + 1,
                saved = state.isVideoSaved(v.id),
                onSave = {
                    if (!state.isVideoSaved(v.id)) Taste.videoAction(ctx, v, Taste.Action.SAVE)
                    state.toggleVideo(v)
                },
                onShared = { Taste.videoAction(ctx, v, Taste.Action.SHARE) },
                bottomInset = 12.dp,
                loop = false,
                onRotate = { landscape = !landscape },
                landscape = landscape
            )
        }
        IconButton(
            onClick = { if (landscape) landscape = false else onClose() },
            modifier = Modifier.statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
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

/** A web page shown inside the app; links never leave FaithScroll. */
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
                        return !(scheme == "http" || scheme == "https")
                    }
                }
                loadUrl(url)
            }
        },
        onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() }
    )
}
