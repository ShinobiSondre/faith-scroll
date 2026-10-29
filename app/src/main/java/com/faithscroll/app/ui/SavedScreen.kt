package com.faithscroll.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.faithscroll.app.data.Verse
import com.faithscroll.app.util.Share

@Composable
fun SavedScreen(state: AppState, verses: List<Verse>, bottomInset: Dp, onRead: (Verse) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val savedVerses = remember(state.saved, verses) { verses.filter { it.ref in state.saved } }

    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding()) {
        Text("Saved", color = Color.White, fontSize = 28.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
        TabRow(
            selectedTabIndex = tab, containerColor = Ink, contentColor = Gold
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Verses (${savedVerses.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Stories (${state.savedVideos.size})") })
        }
        val pad = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomInset + 24.dp)
        if (tab == 0) {
            if (savedVerses.isEmpty()) Empty("Tap the bookmark on any verse to keep it here.")
            else LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(savedVerses, key = { it.ref }) { v ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                            .background(Brush.linearGradient(Palettes.forTheme(v.theme)))
                            .background(Color.Black.copy(alpha = 0.25f))
                            .padding(18.dp)
                    ) {
                        Text("“${v.text}”", color = Color.White, fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 24.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("— ${v.ref}", color = Gold, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRead(v) }) { Icon(Icons.AutoMirrored.Filled.MenuBook, "Read chapter", tint = Color.White) }
                            IconButton(onClick = { Share.verse(ctx, v, Palettes.argb(v.theme)) }) { Icon(Icons.Filled.Share, "Share", tint = Color.White) }
                            IconButton(onClick = { state.toggleSave(v.ref) }) { Icon(Icons.Filled.Bookmark, "Remove", tint = Gold) }
                        }
                    }
                }
            }
        } else {
            if (state.savedVideos.isEmpty()) Empty("Save testimony videos from the feed to watch again later.")
            else LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(state.savedVideos, key = { it.id }) { v ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Plum)
                            .clickable { Share.openUrl(ctx, v.url) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(v.thumbnail, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                            Text(v.channel, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                        IconButton(onClick = { Share.video(ctx, v) }) { Icon(Icons.Filled.Share, "Share", tint = Color.White) }
                        IconButton(onClick = { state.toggleVideo(v) }) { Icon(Icons.Filled.Bookmark, "Remove", tint = Gold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(msg: String) {
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Bookmark, null, tint = Gold.copy(alpha = 0.6f), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(msg, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
        }
    }
}
