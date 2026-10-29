package com.faithscroll.app.ui

import com.faithscroll.app.util.tr

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
import com.faithscroll.app.data.MessageRepository
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.faithscroll.app.data.Video
import com.faithscroll.app.util.Share

@Composable
fun SavedScreen(state: AppState, verses: List<Verse>, bottomInset: Dp, onOpenVideo: (Video, List<Video>) -> Unit, onRead: (Verse) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val savedVerses = remember(state.saved, verses) { verses.filter { it.ref in state.saved } }
    val savedMessages = remember(state.savedMessages) { MessageRepository.all(ctx).filter { it.id in state.savedMessages } }

    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding()) {
        Text(tr("Saved", "Lagret"), color = Color.White, fontSize = 28.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
        TabRow(selectedTabIndex = tab, containerColor = Ink, contentColor = Gold) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("Videos (${state.savedVideos.size})", "Videoer (${state.savedVideos.size})")) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("Messages (${savedMessages.size + state.savedPins.size})", "Budskap (${savedMessages.size + state.savedPins.size})")) })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(tr("Verses (${savedVerses.size})", "Vers (${savedVerses.size})")) })
        }
        val pad = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomInset + 24.dp)
        val grid = GridCells.Adaptive(340.dp)
        when (tab) {
            0 -> if (state.savedVideos.isEmpty()) Empty(tr("Save sermons and stories from the feed to watch again.", "Lagre prekener og historier fra feeden for å se dem igjen."))
            else LazyVerticalGrid(grid, contentPadding = pad, verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(state.savedVideos, key = { it.id }) { v ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Plum)
                            .clickable { onOpenVideo(v, state.savedVideos) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(v.thumbnail, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                            Text(v.channel, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                        IconButton(onClick = { Share.video(ctx, v) }) { Icon(Icons.Filled.Share, tr("Share", "Del"), tint = Color.White) }
                        IconButton(onClick = { state.toggleVideo(v) }) { Icon(Icons.Filled.Bookmark, tr("Remove", "Fjern"), tint = Gold) }
                    }
                }
            }
            1 -> if (savedMessages.isEmpty() && state.savedPins.isEmpty()) Empty(tr("Tap the bookmark on an illustrated message to keep it here.", "Trykk på bokmerket på et illustrert budskap for å ta vare på det her."))
            else LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.savedPins, key = { "pin-" + it.id }) { pn ->
                    Box(Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(18.dp)).background(Plum)) {
                        AsyncImage(pn.image, pn.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        IconButton(onClick = { state.togglePin(pn) }, modifier = Modifier.align(Alignment.BottomEnd)) {
                            Icon(Icons.Filled.Bookmark, tr("Remove", "Fjern"), tint = Gold)
                        }
                    }
                }
                items(savedMessages, key = { it.id }) { m ->
                    Box(Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(18.dp))) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            drawScene(m.scene, Palettes.forTheme(m.theme), 0.3f)
                            drawRect(Color.Black.copy(alpha = 0.25f))
                        }
                        Text(m.text, color = Color.White, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                            lineHeight = 21.sp, modifier = Modifier.align(Alignment.Center).padding(14.dp))
                        Row(Modifier.align(Alignment.BottomEnd)) {
                            IconButton(onClick = { Share.message(ctx, m, renderMessage(m)) }) { Icon(Icons.Filled.Share, tr("Share", "Del"), tint = Color.White) }
                            IconButton(onClick = { state.toggleMessageSave(m.id) }) { Icon(Icons.Filled.Bookmark, tr("Remove", "Fjern"), tint = Gold) }
                        }
                    }
                }
            }
            else -> if (savedVerses.isEmpty()) Empty(tr("Verses you save from the widget appear here.", "Vers du lagrer fra widgeten vises her."))
            else LazyVerticalGrid(grid, contentPadding = pad, verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
                            IconButton(onClick = { onRead(v) }) { Icon(Icons.AutoMirrored.Filled.MenuBook, tr("Read chapter", "Les kapittelet"), tint = Color.White) }
                            IconButton(onClick = { Share.verse(ctx, v, Palettes.argb(v.theme)) }) { Icon(Icons.Filled.Share, tr("Share", "Del"), tint = Color.White) }
                            IconButton(onClick = { state.toggleSave(v.ref) }) { Icon(Icons.Filled.Bookmark, tr("Remove", "Fjern"), tint = Gold) }
                        }
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
