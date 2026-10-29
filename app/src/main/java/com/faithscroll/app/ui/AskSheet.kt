package com.faithscroll.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.data.Ai
import com.faithscroll.app.data.Transcripts
import com.faithscroll.app.data.Video
import com.faithscroll.app.util.tr
import kotlinx.coroutines.launch

/** "What do they mean here?": explains the paused moment using the transcript around it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskSheet(video: Video, atMs: Long, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val turns = remember { mutableStateListOf<Ai.Turn>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var wait by remember { mutableStateOf(0) } // seconds until the free AI accepts the next question
    var context by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()

    val first = tr("What do they mean here?", "Hva mener de her?")

    suspend fun send(question: String) {
        busy = true; error = null
        turns += Ai.Turn(true, question)
        val ctxText = context ?: run {
            val words = runCatching { Transcripts.textAround(video.id, atMs) }.getOrDefault("")
            val mm = atMs / 60000; val ss = (atMs / 1000) % 60
            buildString {
                append("Video: \"${video.title}\" by ${video.channel}\n")
                append("Paused at %d:%02d\n".format(mm, ss))
                append(if (words.isNotBlank()) "Transcript around this moment:\n\"$words\"" else "No transcript is available for this video.")
            }.also { context = it }
        }
        while (true) {
            val res = runCatching { Ai.ask(ctxText, turns.toList()) }
            val e = res.exceptionOrNull()
            if (e is Ai.RateLimited) {
                // Free tier: one question every ~30 s. Wait, then ask automatically.
                wait = e.seconds
                while (wait > 0) { kotlinx.coroutines.delay(1000); wait-- }
                continue
            }
            res.onFailure { com.faithscroll.app.data.Diag.err("ai", "ask failed", it) }
            res.onSuccess { turns += Ai.Turn(false, it) }
                .onFailure { error = it.message ?: tr("Something went wrong.", "Noe gikk galt."); turns.removeAt(turns.size - 1) }
            break
        }
        busy = false
        list.animateScrollToItem(maxOf(0, turns.size))
    }

    LaunchedEffect(Unit) { if (turns.isEmpty()) send(first) }

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
                            if (wait > 0) tr("The free AI allows one question every 30 seconds. Asking in $wait s…", "Den gratis KI-en tillater ett spørsmål hvert 30. sekund. Spør om $wait s …")
                            else tr("Thinking…", "Tenker …"),
                            color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp
                        )
                    }
                }
                error?.let { e ->
                    item {
                        Text(tr("Couldn't get an answer: ", "Fikk ikke svar: ") + e, color = HeartRed, fontSize = 13.sp)
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
                    onClick = { val q = input; input = ""; scope.launch { send(q) } },
                    enabled = input.isNotBlank() && !busy
                ) { Icon(Icons.AutoMirrored.Filled.Send, tr("Send", "Send"), tint = Gold) }
            }
        }
    }
}
