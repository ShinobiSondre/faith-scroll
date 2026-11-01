package com.faithscroll.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.data.Discovery
import com.faithscroll.app.data.Taste
import com.faithscroll.app.data.Verse
import com.faithscroll.app.data.VerseRepository
import com.faithscroll.app.data.Video
import com.faithscroll.app.data.VideoCheck
import com.faithscroll.app.util.Lang
import com.faithscroll.app.util.tr

/** A feeling or a kind of video to look for: label, theme (for verses and your taste), search words. */
private data class Pick(val en: String, val no: String, val theme: String?, val qEn: String, val qNo: String, val sermon: Boolean = false, val short: Boolean = false) {
    val label get() = tr(en, no)
    val query get() = if (Lang.no) qNo else qEn
}

private val feelings = listOf(
    Pick("😟 Worried", "😟 Bekymret", "peace", "peace instead of anxiety faith", "fred bekymring Gud"),
    Pick("😢 Sad", "😢 Lei meg", "comfort", "comfort in sadness God", "trøst sorg Gud"),
    Pick("🫂 Lonely", "🫂 Ensom", "love", "lonely God's love", "ensom Guds kjærlighet"),
    Pick("😨 Afraid", "😨 Redd", "courage", "fear courage faith", "frykt mot tro Jesus"),
    Pick("😮‍💨 Tired", "😮‍💨 Sliten", "strength", "strength when weary God", "styrke kraft Gud sliten"),
    Pick("🙌 Thankful", "🙌 Takknemlig", "joy", "gratitude joy in God", "takknemlig glede Gud"),
    Pick("🌅 Need hope", "🌅 Trenger håp", "hope", "hope in Jesus", "håp Jesus"),
    Pick("🕊 Guilt", "🕊 Skyld", "grace", "forgiveness grace Jesus", "tilgivelse nåde Jesus"),
    Pick("❓ Doubting", "❓ Tviler", "faith", "doubt and faith", "tvil tro Gud"),
    Pick("🧭 Unsure of my way", "🧭 Usikker på veien", "guidance", "God's will guidance", "Guds vilje veiledning"),
    Pick("🙏 Want to pray", "🙏 Vil be", "prayer", "prayer", "bønn")
)
private val kinds = listOf(
    Pick("Testimonies", "Vitnesbyrd", null, "christian testimony", "vitnesbyrd jesus"),
    Pick("Sermons", "Prekener", null, "sermon", "preken", sermon = true),
    Pick("Worship", "Lovsang", "joy", "worship", "lovsang"),
    Pick("Bible teaching", "Bibelundervisning", "faith", "bible teaching", "bibelundervisning"),
    Pick("Prayer", "Bønn", "prayer", "prayer", "bønn"),
    Pick("Shorts", "Shorts", null, "christian #shorts", "kristen #shorts", short = true)
)

/**
 * Search by how you feel or what you want to see: Bible verses for it, videos already in the app,
 * and a fresh YouTube search (checked for language and content like everything else).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(inApp: List<Video>, onOpenVideo: (Video, List<Video>) -> Unit, onOpenVerse: (Verse) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf<Pick?>(null) }
    var query by remember { mutableStateOf<String?>(null) } // what's being searched right now
    var found by remember { mutableStateOf<List<Video>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val ok by VideoCheck.verified.collectAsState()

    fun run(p: Pick?, free: String?) {
        pick = p; focus.clearFocus()
        p?.theme?.let { Taste.boostTheme(ctx, it, 0.6f) }
        query = free ?: p?.query
    }

    LaunchedEffect(query) {
        val q = query ?: return@LaunchedEffect
        searching = true; found = emptyList()
        val words = if (pick == null) q + (if (Lang.no) " kristen" else " christian") else q
        found = Discovery.searchQuery(words).filter { r -> pick?.short != true || r.kind == Video.KIND_SHORT }
        VideoCheck.checkExtra(ctx, found)
        searching = false
    }

    // Results
    val words = (query ?: "").lowercase().split(" ").filter { it.length >= 4 }
    val verses = remember(query, pick) {
        val all = VerseRepository.all(ctx)
        when {
            pick?.theme != null -> all.filter { it.theme == pick!!.theme }.shuffled().take(5)
            words.isNotEmpty() -> all.filter { v -> words.any { v.text.lowercase().contains(it) } }.take(5)
            else -> emptyList()
        }
    }
    val local = remember(query, pick, inApp) {
        if (query == null) emptyList() else inApp.filter { v ->
            val t = v.title.lowercase()
            (pick?.theme != null && pick!!.theme in Taste.topicsOf(v.title)) || words.any { t.contains(it) } ||
                (pick?.sermon == true && v.kind == Video.KIND_SERMON) || (pick?.short == true && v.kind == Video.KIND_SHORT)
        }.take(12)
    }
    val videos = (local + found.filter { it.id in ok }).distinctBy { it.id }
    val waiting = found.count { it.id !in ok && VideoCheck.checking.value }
    // After picking something, scroll down to the results.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(query, pick) { if (query != null) runCatching { listState.animateScrollToItem(5) } }

    Box(Modifier.fillMaxSize().background(Ink).clickable(remember { MutableInteractionSource() }, null) { }) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            state = listState,
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Tilbake"), tint = Color.White) }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        placeholder = { Text(tr("What do you need today?", "Hva trenger du i dag?"), color = Color.White.copy(alpha = 0.45f)) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, tint = Color.White.copy(alpha = 0.6f)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (text.isNotBlank()) run(null, text.trim()) }),
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        shape = RoundedCornerShape(50),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Gold, unfocusedBorderColor = Color.White.copy(alpha = 0.2f), cursorColor = Gold)
                    )
                }
            }
            item { Section(tr("How are you feeling?", "Hvordan har du det?")) }
            item {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    feelings.forEach { f -> PickChip(f.label, pick == f) { run(f, null) } }
                }
            }
            item { Section(tr("What do you want to see?", "Hva vil du se?")) }
            item {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    kinds.forEach { k -> PickChip(k.label, pick == k) { run(k, null) } }
                }
            }
            if (query != null) {
                // Verses: one compact row you swipe sideways, so the videos are right below.
                if (verses.isNotEmpty()) {
                    item(key = "results") { Section(tr("From the Bible", "Fra Bibelen")) }
                    item {
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(verses, key = { "v" + it.ref }) { v ->
                                Column(
                                    Modifier.width(260.dp).height(120.dp).clip(RoundedCornerShape(16.dp))
                                        .background(Plum).clickable { onOpenVerse(v) }.padding(14.dp)
                                ) {
                                    Text(v.text, color = Color.White, fontSize = 14.sp, lineHeight = 19.sp, fontFamily = FontFamily.Serif,
                                        maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(v.label, color = Gold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                item(key = "videos") { Section(tr("Videos", "Videoer") + if (videos.isNotEmpty()) " (${videos.size})" else "") }
                items(videos, key = { "x" + it.id }) { v -> VideoRow(v) { onOpenVideo(v, videos) } }
                if (searching || waiting > 0) item {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(if (searching) tr("Searching…", "Søker …") else tr("Checking language and content… more appear here", "Sjekker språk og innhold … flere dukker opp her"),
                            color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                } else if (videos.isEmpty()) item {
                    Text(tr("No videos found. Try another word.", "Fant ingen videoer. Prøv et annet ord."),
                        color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(20.dp))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 10.dp))
}

@Composable
private fun PickChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (selected) Ink else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Gold else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp)
    )
}
