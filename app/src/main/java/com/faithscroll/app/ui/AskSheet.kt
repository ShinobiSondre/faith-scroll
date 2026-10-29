package com.faithscroll.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.data.AskSessions
import com.faithscroll.app.data.Video
import com.faithscroll.app.util.tr

/** "What do they mean here?": explains the paused moment using the transcript around it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskSheet(video: Video, atMs: Long, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The conversation lives outside this sheet, so it keeps going (and is still here) if you close and reopen.
    val session = remember(video.id, atMs) { AskSessions.get(video.id, atMs) }
    val turns by session.turns.collectAsState()
    val busy by session.busy.collectAsState()
    val wait by session.wait.collectAsState()
    val error by session.error.collectAsState()
    val source by session.source.collectAsState()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val first = tr("What do they mean here?", "Hva mener de her?")

    LaunchedEffect(Unit) { if (turns.isEmpty() && !busy) AskSessions.ask(ctx, session, video, first) }
    LaunchedEffect(turns.size, busy) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size - 1 + if (busy) 1 else 0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Plum) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 20.dp).imePadding()) {
            Text("✦ $first", color = Gold, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(video.title, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1)
            Spacer(Modifier.height(12.dp))

            LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(turns) { t ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (t.user) Alignment.CenterEnd else Alignment.CenterStart) {
                        Text(
                            t.text, color = if (t.user) Ink else Color.White, fontSize = 15.sp, lineHeight = 22.sp,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp))
                                .background(if (t.user) Gold else Color.White.copy(alpha = 0.08f))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
                if (busy) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (wait > 0) tr("The free AI is busy. Trying again in $wait s…", "Den gratis KI-en er opptatt. Prøver igjen om $wait s …")
                            else tr("Listening back and thinking… (can take 10–20 s)", "Lytter og tenker … (kan ta 10–20 s)"),
                            color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp
                        )
                    }
                }
                error?.let { e ->
                    item { Text(tr("Couldn't get an answer: ", "Fikk ikke svar: ") + e, color = HeartRed, fontSize = 13.sp) }
                }
                source?.let { s ->
                    item {
                        Text(
                            if (s == "offline") tr("Answered on this phone (offline)", "Besvart på denne telefonen (uten nett)")
                            else tr("Free AI: $s", "Gratis KI: $s"),
                            color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    placeholder = { Text(tr("Ask a follow-up…", "Still et oppfølgingsspørsmål …")) },
                    modifier = Modifier.weight(1f), maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, cursorColor = Gold, focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                IconButton(
                    onClick = { val q = input; input = ""; AskSessions.ask(ctx, session, video, q) },
                    enabled = input.isNotBlank() && !busy
                ) { Icon(Icons.AutoMirrored.Filled.Send, tr("Send", "Send"), tint = Gold) }
            }
        }
    }
}
