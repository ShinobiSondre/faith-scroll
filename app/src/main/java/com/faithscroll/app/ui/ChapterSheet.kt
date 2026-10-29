package com.faithscroll.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.data.BibleApi
import com.faithscroll.app.data.Chapter
import com.faithscroll.app.data.Verse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterSheet(verse: Verse, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var chapter by remember { mutableStateOf<Chapter?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()

    LaunchedEffect(attempt) {
        loading = true
        chapter = BibleApi.chapter(verse.chapterRef)
        loading = false
        val first = verse.verseRange.first
        val idx = chapter?.verses?.indexOfFirst { it.number == first } ?: -1
        if (idx > 1) list.animateScrollToItem(idx - 1)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Plum) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 22.dp)) {
            Text(verse.chapterRef, color = Color.White, fontSize = 24.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
            Text("King James Version · read it in context", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Gold)
                }
                chapter == null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load the chapter. Check your connection.", color = Color.White.copy(alpha = 0.8f))
                    TextButton(onClick = { attempt++ }) { Text("Try again", color = Gold) }
                }
                else -> LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(chapter!!.verses, key = { it.number }) { v ->
                        val highlight = v.number in verse.verseRange
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)) { append("${v.number}  ") }
                                append(v.text)
                            },
                            color = Color.White.copy(alpha = if (highlight) 1f else 0.82f),
                            fontFamily = FontFamily.Serif,
                            fontSize = 18.sp,
                            lineHeight = 27.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (highlight) Gold.copy(alpha = 0.14f) else Color.Transparent)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    item { Spacer(Modifier.height(40.dp)) }
                }
            }
        }
    }
}
