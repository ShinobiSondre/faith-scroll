package com.faithscroll.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.faithscroll.app.data.ChannelVideos
import com.faithscroll.app.data.Video
import com.faithscroll.app.data.WatchPage
import com.faithscroll.app.data.YouTubeSubs
import com.faithscroll.app.util.tr

/** Opens a channel's page from a video (the video tells which channel). */
val LocalOpenChannel = compositionLocalOf<(Video) -> Unit> { {} }

/** A YouTube channel inside the app: all its videos, newest first; scroll for more, tap to watch. */
@Composable
fun ChannelScreen(from: Video, onOpenVideo: (Video, List<Video>) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    var source by remember { mutableStateOf<ChannelVideos?>(null) }
    val videos = remember { mutableStateListOf<Video>() }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(from.channel) }
    var avatar by remember { mutableStateOf<String?>(null) }
    var subscribed by remember { mutableStateOf(YouTubeSubs.isFavouriteName(ctx, from.channel)) }
    val grid = rememberLazyGridState()

    LaunchedEffect(from.id) {
        val id = WatchPage.info(from.id)?.channelId?.takeIf { it.isNotBlank() }
        if (id == null) { loading = false; failed = true; return@LaunchedEffect }
        val c = ChannelVideos(id); source = c
        videos.addAll(c.first().distinctBy { it.id })
        if (c.name.isNotBlank()) name = c.name
        avatar = c.avatar
        loading = false; failed = videos.isEmpty()
    }
    // Near the bottom: load the next page.
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= videos.size - 4 } }
    LaunchedEffect(nearEnd, videos.size) {
        val c = source ?: return@LaunchedEffect
        if (nearEnd && !loading && !c.done) {
            loading = true
            val more = c.more()
            videos.addAll(more.filter { m -> videos.none { it.id == m.id } })
            loading = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(Ink)
            .clickable(remember { MutableInteractionSource() }, null) { }
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            state = grid,
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Tilbake"), tint = Color.White) }
                        avatar?.let { AsyncImage(it, null, Modifier.size(44.dp).clip(CircleShape)); Spacer(Modifier.width(10.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(tr("${videos.size} videos", "${videos.size} videoer") + if (source?.done == false) "+" else "",
                                color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
                        }
                        Text(
                            if (subscribed) tr("✓ Subscribed", "✓ Abonnerer") else tr("Subscribe", "Abonner"),
                            color = if (subscribed) Gold else Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (subscribed) Color.White.copy(alpha = 0.1f) else Gold)
                                .clickable {
                                    val id = source?.channelId ?: return@clickable
                                    if (subscribed) YouTubeSubs.unfollow(ctx, id) else YouTubeSubs.follow(ctx, id, name)
                                    subscribed = !subscribed
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                    if (failed) Text(tr("Couldn't load this channel right now.", "Klarte ikke å hente kanalen nå."),
                        color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(16.dp))
                    Spacer(Modifier.height(6.dp))
                }
            }
            itemsIndexed(videos, key = { _, v -> v.id }) { _, v ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { onOpenVideo(v, videos.toList()) }) {
                    AsyncImage(
                        v.thumbnail, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Plum)
                    )
                    Text(v.title, color = Color.White, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp))
                }
            }
            if (loading) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Gold, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}
