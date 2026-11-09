package com.faithscroll.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.data.AskPrompt
import com.faithscroll.app.data.VerseRefs
import com.faithscroll.app.util.Lang
import com.faithscroll.app.util.tr

/** Gold pill: "📖 Johannes 3,16" – the verse just mentioned. [more] = how many others were mentioned before. */
@Composable
fun VersePill(ref: VerseRefs.Ref, more: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Gold.copy(alpha = 0.7f), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = Gold, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(VerseRefs.label(ctx, ref, Lang.no), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (more > 0) Text("  +$more", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Small chips under a ChatGPT answer for each verse it mentions. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun VerseChips(refs: List<VerseRefs.Ref>, onOpen: (VerseRefs.Ref) -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        refs.forEach { r ->
            Text(
                "📖 " + VerseRefs.label(ctx, r, Lang.no), color = Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Gold.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .clickable { onOpen(r) }.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * The verses mentioned in the video, newest first, each with what the verse says. [onSeek] jumps
 * to where it was said (null = no timestamps, e.g. verses from a ChatGPT answer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerseMentionsSheet(refs: List<VerseRefs.Ref>, title: String = tr("Verses mentioned", "Vers som nevnes"), onSeek: ((Long) -> Unit)?, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Plum) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✝", color = Gold, fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Text(title, color = Color.White, fontSize = 19.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(refs.reversed(), key = { it.key + "@" + it.atMs }) { r -> VerseCard(r, onSeek) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun VerseCard(r: VerseRefs.Ref, onSeek: ((Long) -> Unit)?) {
    val ctx = LocalContext.current
    var text by remember(r.key) { mutableStateOf<String?>(null) }
    var loading by remember(r.key) { mutableStateOf(true) }
    LaunchedEffect(r.key) { text = runCatching { VerseRefs.text(ctx, r, Lang.no) }.getOrNull(); loading = false }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.25f))
            .border(1.dp, Gold.copy(alpha = 0.25f), RoundedCornerShape(18.dp)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(VerseRefs.label(ctx, r, Lang.no), color = Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (onSeek != null) Text(
                "▶ " + tr("said at", "sagt ved") + " " + AskPrompt.time(r.atMs), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.1f))
                    .clickable { onSeek(r.atMs) }.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading -> CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            text != null -> Text("«${text}»".let { if (Lang.no) it else "“$text”" }, color = Color.White, fontSize = 16.sp, lineHeight = 24.sp, fontFamily = FontFamily.Serif)
            else -> Text(tr("Couldn't load the verse text.", "Klarte ikke å hente verset."), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}
