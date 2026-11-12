package com.faithscroll.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.faithscroll.app.data.AskPrompt
import com.faithscroll.app.data.Video
import com.faithscroll.app.data.WatchHistory
import com.faithscroll.app.util.tr
import java.text.DateFormat
import java.util.Date

/** Everything you've watched, newest first, searchable by title, channel and description. Tap to continue. */
@Composable
fun HistoryScreen(bottomInset: Dp, onOpenVideo: (Video, List<Video>) -> Unit) {
    val ctx = LocalContext.current
    remember { WatchHistory.load(ctx) }
    val all by WatchHistory.items.collectAsState()
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = remember(all, query) { WatchHistory.search(ctx, query) }

    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("History", "Historikk"), color = Color.White, fontSize = 28.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (all.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(tr("Clear", "Tøm"), color = Color.White.copy(alpha = 0.6f)) }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Color.White.copy(alpha = 0.6f)) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, tr("Clear", "Tøm"), tint = Color.White.copy(alpha = 0.6f)) } },
            placeholder = { Text(tr("Search what you've watched…", "Søk i det du har sett …"), color = Color.White.copy(alpha = 0.45f)) },
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                cursorColor = Gold, focusedTextColor = Color.White, unfocusedTextColor = Color.White)
        )
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.History, null, tint = Gold.copy(alpha = 0.6f), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (query.isBlank()) tr("Videos you watch show up here, and you can continue right where you stopped.", "Videoer du ser kommer her, og du kan fortsette akkurat der du slapp.")
                        else tr("Nothing you've watched matches “$query”.", "Ingenting du har sett passer med «$query»."),
                        color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp
                    )
                }
            }
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Adaptive(340.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomInset + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(shown, key = { it.video.id }) { e ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Plum)
                        .clickable { onOpenVideo(e.video, shown.map { it.video }) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(10.dp))) {
                        AsyncImage(e.video.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        // How far you got.
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f)))
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(e.progress).height(4.dp).background(Gold))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.video.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(e.video.channel, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
                        Text(
                            (if (e.finished) tr("Watched", "Sett ferdig") else tr("Stopped at ", "Stoppet ved ") + AskPrompt.time(e.positionMs)) +
                                " · " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.lastWatched)),
                            color = if (e.finished) Color.White.copy(alpha = 0.45f) else Gold, fontSize = 12.sp, maxLines = 1
                        )
                    }
                    IconButton(onClick = { WatchHistory.remove(ctx, e.video.id) }) {
                        Icon(Icons.Filled.Close, tr("Remove", "Fjern"), tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    if (confirmClear) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmClear = false }, containerColor = Plum,
        title = { Text(tr("Clear history?", "Tømme historikken?"), color = Color.White) },
        text = { Text(tr("Your watch history and saved places in videos are removed from this phone. Videos you've seen still won't come back in the feed.",
            "Historikken og stedene du stoppet i videoer fjernes fra telefonen. Videoer du har sett kommer fortsatt ikke tilbake i feeden."), color = Color.White.copy(alpha = 0.8f)) },
        confirmButton = { TextButton(onClick = { WatchHistory.clear(ctx); confirmClear = false }) { Text(tr("Clear", "Tøm"), color = Gold) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Cancel", "Avbryt"), color = Color.White) } }
    )
}
