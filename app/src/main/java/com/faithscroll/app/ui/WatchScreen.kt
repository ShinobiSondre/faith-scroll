package com.faithscroll.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.faithscroll.app.data.Video
import com.faithscroll.app.data.VideoLibrary

/** Browse sermons and testimonies; everything plays in the in-app player. */
@Composable
fun WatchScreen(library: VideoLibrary?, bottomInset: Dp, onOpen: (Video, List<Video>) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("For you") }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding()) {
        Text(tr("Watch", "Se"), color = Color.White, fontSize = 28.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp))
        Text(tr("Sermons and real stories of faith", "Prekener og ekte troshistorier"), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))

        if (library == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Gold) }
            return@Column
        }

        val channels = remember(library) { library.all.map { it.channel }.distinct() }
        val chips = listOf("For you", "Shorts", "Sermons", "Stories") + channels
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chips) { c -> FilterChip(chipLabel(c), c == filter) { filter = c } }
        }
        val list = remember(library, filter) {
            when (filter) {
                "For you" -> {
                    // Ranked by your taste with a dash of randomness, so it never feels stale.
                    val r = kotlin.random.Random(System.currentTimeMillis() / 600_000)
                    library.all.sortedByDescending { com.faithscroll.app.data.Taste.scoreVideo(ctx, it) + r.nextFloat() * 1.5f }
                }
                "Shorts" -> library.shorts
                "Sermons" -> library.sermons
                "Stories" -> library.stories
                else -> library.all.filter { it.channel == filter }
            }
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(tr("Couldn't load videos right now. Check your connection and reopen the app.", "Kunne ikke laste videoer nå. Sjekk nettforbindelsen og åpne appen på nytt."),
                    color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
            }
            return@Column
        }
        // One column on a phone, more when turned sideways or unfolded.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(340.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = bottomInset + 16.dp)
        ) {
            item(key = "hero-" + list.first().id, span = { GridItemSpan(minOf(maxLineSpan, 2)) }) { Hero(list.first()) { onOpen(list.first(), list) } }
            gridItems(list.drop(1), key = { it.id }) { v -> VideoRow(v) { onOpen(v, list) } }
        }
    }
}

@Composable
private fun Hero(v: Video, onClick: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(Plum)) {
            AsyncImage(v.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
            Box(Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, tr("Play", "Spill av"), tint = Color.White, modifier = Modifier.size(40.dp))
            }
            Text("LATEST", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(14.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(v.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(v.channel, color = Gold, fontSize = 13.sp)
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Gold else Color.Transparent)
            .border(1.dp, if (selected) Gold else Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (selected) Ink else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun chipLabel(key: String) = when (key) {
    "For you" -> tr("For you", "For deg")
    "Shorts" -> tr("Shorts", "Korte")
    "Sermons" -> tr("Sermons", "Prekener")
    "Stories" -> tr("Stories", "Historier")
    else -> key
}
