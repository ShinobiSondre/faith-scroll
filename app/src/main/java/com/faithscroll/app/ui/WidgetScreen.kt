package com.faithscroll.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import com.faithscroll.app.util.tr
import com.faithscroll.app.util.Lang

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.data.Prefs
import com.faithscroll.app.data.Taste
import com.faithscroll.app.data.VideoQuality
import com.faithscroll.app.data.SocialRepository
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.AutoAwesome
import com.faithscroll.app.data.VerseRepository
import com.faithscroll.app.widget.VerseWidgetProvider
import kotlin.math.roundToInt

@Composable
fun WidgetScreen(
    state: AppState,
    bottomInset: Dp,
    onSourcesChanged: () -> Unit = {},
    onBeStill: () -> Unit = {},
    onNeedNotifications: () -> Unit = {},
    onPersonalize: () -> Unit
) {
    sourcesChanged = onSourcesChanged
    val ctx = LocalContext.current
    var opacity by remember { mutableFloatStateOf(Prefs.widgetOpacity(ctx).toFloat()) }
    var lightText by remember { mutableStateOf(Prefs.widgetLightText(ctx)) }
    var auto by remember { mutableStateOf(Prefs.widgetAutoChange(ctx)) }
    var previewRef by remember { mutableStateOf(Prefs.widgetRef(ctx)) }
    val preview = remember(previewRef) { VerseRepository.byRef(ctx, previewRef) ?: VerseRepository.all(ctx).first() }

    fun apply() = VerseWidgetProvider.refresh(ctx)

    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.TopCenter) { Column(
        Modifier.widthIn(max = 680.dp).fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomInset + 24.dp)
    ) {
        Text(tr("Me", "Meg"), color = Color.White, fontSize = 28.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))

        // Personalization
        val tops = remember { Taste.topThemes(ctx, 4).mapNotNull { Taste.themeLabels[it] } }
        val fav = remember { Taste.favouriteChannel(ctx) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Plum).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = Gold, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(tr("Your For You feed", "Din «For deg»-feed"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (tops.isEmpty()) tr("FaithScroll learns from what you linger on, like, save and watch.", "FaithScroll lærer av hva du blir værende ved, liker, lagrer og ser på.")
                else tr("Lately you're drawn to ${tops.joinToString(", ")}", "I det siste trekkes du mot ${tops.joinToString(", ")}") + (fav?.let { tr(", and you enjoy $it", ", og du liker $it") } ?: "") + ".",
                color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(tr("Retake questions", "Svar på nytt"), false) { onPersonalize() }
                Chip(tr("Start fresh", "Start på nytt"), false) {
                    Taste.reset(ctx)
                    Toast.makeText(ctx, tr("Cleared. Answer a few questions to start again.", "Nullstilt. Svar på noen spørsmål for å starte på nytt."), Toast.LENGTH_SHORT).show()
                    onPersonalize()
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        PrayerCard(onBeStill, onNeedNotifications)
        Spacer(Modifier.height(16.dp))
        AiCard()
        Spacer(Modifier.height(16.dp))
        LanguageCard()
        Spacer(Modifier.height(16.dp))
        QualityCard()
        Spacer(Modifier.height(16.dp))
        YouTubeCard(onChanged = { sourcesChanged() })
        Spacer(Modifier.height(16.dp))
        SourcesCard()

        Spacer(Modifier.height(28.dp))
        Text(tr("Home screen widget", "Widget på hjemskjermen"), color = Color.White, fontSize = 20.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))

        // Live preview over a mock wallpaper so transparency is visible
        Box(
            Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2E8B83), Color(0xFF6A4C93), Color(0xFFF7B267)))),
            contentAlignment = Alignment.Center
        ) {
            val bgColor by animateColorAsState(if (lightText) Color(0xFF16112A) else Color(0xFFFBF6EC), label = "bg")
            val textColor = if (lightText) Color.White else Plum
            Column(
                Modifier.padding(18.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(bgColor.copy(alpha = opacity / 100f))
                    .padding(18.dp)
            ) {
                Text("“${preview.text}”", color = textColor, fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 5)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("— ${preview.label}", color = if (lightText) Gold else Color(0xFF8A5A00), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.Refresh, null, tint = textColor, modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Background", "Bakgrunn"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                when { opacity < 5 -> tr("Transparent", "Gjennomsiktig"); opacity < 85 -> tr("Semi-transparent · ${opacity.roundToInt()}%", "Halvgjennomsiktig · ${opacity.roundToInt()} %"); else -> tr("Solid", "Heldekkende") },
                color = Gold, fontSize = 14.sp
            )
        }
        Slider(
            value = opacity, onValueChange = { opacity = it }, valueRange = 0f..100f,
            onValueChangeFinished = { Prefs.setWidgetOpacity(ctx, opacity.roundToInt()); apply() },
            colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Clear" to 0f, "Semi" to 35f, "Frosted" to 65f, "Solid" to 100f).forEach { (label, v) ->
                Chip(label, selected = opacity.roundToInt() == v.roundToInt()) {
                    opacity = v; Prefs.setWidgetOpacity(ctx, v.roundToInt()); apply()
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(tr("Text style", "Tekststil"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(tr("Light text", "Lys tekst"), lightText) { lightText = true; Prefs.setWidgetLightText(ctx, true); apply() }
            Chip(tr("Dark text", "Mørk tekst"), !lightText) { lightText = false; Prefs.setWidgetLightText(ctx, false); apply() }
        }
        Text(tr("Tip: use light text on dark wallpapers, dark text on light ones.", "Tips: bruk lys tekst på mørke bakgrunner og mørk tekst på lyse."), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("New verse every hour", "Nytt vers hver time"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(tr("Or tap ↻ on the widget anytime", "Eller trykk ↻ på widgeten når du vil"), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
            }
            Switch(checked = auto, onCheckedChange = { auto = it; Prefs.setWidgetAutoChange(ctx, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Gold, checkedThumbColor = Ink))
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ok = mgr.isRequestPinAppWidgetSupported &&
                    mgr.requestPinAppWidget(ComponentName(ctx, VerseWidgetProvider::class.java), null, null)
                if (!ok) Toast.makeText(ctx, tr("Long-press your home screen → Widgets → FaithScroll", "Hold inne på hjemskjermen → Moduler → FaithScroll"), Toast.LENGTH_LONG).show()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
        ) {
            Icon(Icons.Filled.AddToHomeScreen, null); Spacer(Modifier.width(8.dp)); Text(tr("Add widget to home screen", "Legg widgeten på hjemskjermen"), fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { VerseWidgetProvider.nextVerse(ctx); previewRef = Prefs.widgetRef(ctx) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Icon(Icons.Filled.Refresh, null, tint = Gold); Spacer(Modifier.width(8.dp)); Text(tr("Show a new verse now", "Vis et nytt vers nå"), color = Gold)
        }

        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Plum).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.LocalFireDepartment, null, tint = Gold, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(tr("${state.streak.current}-day reading streak", "${state.streak.current} dager på rad i Ordet"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(tr("Best: ${state.streak.best} days · ${state.streak.totalDays} days in the Word", "Beste: ${state.streak.best} dager · ${state.streak.totalDays} dager i Ordet totalt"), color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        DiagnosticsCard()
        Spacer(Modifier.height(16.dp))
        Text(
            tr("Scripture: King James Version (public domain). Videos stream from YouTube channels sharing testimonies and Bible teaching.", "Bibeltekst: Bibelen 1930, Det Norske Bibelselskap (fri bruk). Videoene strømmes fra YouTube-kanaler med vitnesbyrd og bibelundervisning."),
            color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp
        )
    }
}
}

private var sourcesChanged: () -> Unit = {}

/** What the app has been doing (subtitles, language checks, connections), to copy and send. */
@Composable
private fun DiagnosticsCard() {
    val lines by com.faithscroll.app.data.Diag.lines.collectAsState()
    var open by remember { mutableStateOf(false) }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Plum.copy(alpha = 0.6f)).clickable { open = !open }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tr("Diagnostics", "Diagnostikk") + " (${lines.size})", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            clip.setText(androidx.compose.ui.text.AnnotatedString("FaithScroll ${com.faithscroll.app.BuildConfig.VERSION_NAME}\n" + com.faithscroll.app.data.Diag.text()))
            Toast.makeText(ctx, tr("Copied. Paste it in your message.", "Kopiert. Lim det inn i meldingen din."), Toast.LENGTH_SHORT).show()
        }) { Text(tr("Copy", "Kopier"), color = Gold) }
    }
    // Runs the transcript chain on real videos from your feed, right here on the phone.
    var testing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<String>?>(null) }
    val testScope = rememberCoroutineScope()
    TextButton(enabled = !testing, onClick = {
        testing = true
        testScope.launch {
            val ids = com.faithscroll.app.data.VideoCheck.verified.value.take(6).map { it to it }.ifEmpty {
                listOf("dDYbiukIFBc" to "Best CBN Testimonies")
            }
            results = com.faithscroll.app.ui.AskChatGpt.selfTest(ids)
            testing = false
        }
    }) { Text(if (testing) tr("Testing transcripts…", "Tester tekster …") else tr("Test transcripts", "Test tekster"), color = Gold) }
    results?.let { r ->
        AlertDialog(
            onDismissRequest = { results = null }, containerColor = Plum,
            title = { Text(tr("Transcript test: ${r.count { it.startsWith("PASS") }}/${r.size} OK", "Teksttest: ${r.count { it.startsWith("PASS") }}/${r.size} OK"), color = Color.White) },
            text = { Text(r.joinToString("\n\n"), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = { results = null }) { Text("OK", color = Gold) } }
        )
    }
    if (open) {
        Text(
            lines.takeLast(60).joinToString("\n").ifBlank { tr("Nothing logged yet. Watch a few videos first.", "Ingenting logget ennå. Se noen videoer først.") },
            color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, lineHeight = 14.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
    }
}

/** Prayer reminders (2–3 a day, adjustable times) and the Be still space. */
@Composable
private fun PrayerCard(onBeStill: () -> Unit, onNeedNotifications: () -> Unit) {
    val ctx = LocalContext.current
    var count by remember { mutableStateOf(com.faithscroll.app.prayer.Reminders.count(ctx)) }
    var times by remember { mutableStateOf((0..2).map { com.faithscroll.app.prayer.Reminders.rawTime(ctx, it) }) }
    fun setCount(n: Int) {
        count = n; com.faithscroll.app.prayer.Reminders.setCount(ctx, n)
        com.faithscroll.app.prayer.Reminders.setAsked(ctx)
        if (n > 0) onNeedNotifications()
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Plum).padding(18.dp)) {
        Text("🙏 " + tr("Prayer", "Bønn"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(tr("Reminders to pause and pray, and a quiet place to be still with God.", "Påminnelser om å stoppe opp og be, og et stille sted å være hos Gud."),
            color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(tr("Off", "Av"), count == 0) { setCount(0) }
            Chip(tr("2 a day", "2 om dagen"), count == 2) { setCount(2) }
            Chip(tr("3 a day", "3 om dagen"), count == 3) { setCount(3) }
        }
        if (count > 0) {
            Spacer(Modifier.height(10.dp))
            val slots = if (count == 2) listOf(0, 2) else listOf(0, 1, 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                slots.forEach { slot ->
                    val m = times[slot]
                    Chip("%02d:%02d".format(m / 60, m % 60), false) {
                        android.app.TimePickerDialog(ctx, { _, h, min ->
                            com.faithscroll.app.prayer.Reminders.setTime(ctx, slot, h * 60 + min)
                            times = (0..2).map { com.faithscroll.app.prayer.Reminders.rawTime(ctx, it) }
                        }, m / 60, m % 60, true).show()
                    }
                }
            }
            Text(tr("Tap a time to change it.", "Trykk på et klokkeslett for å endre det."), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onBeStill, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) {
            Text(tr("Be still with God", "Vær stille hos Gud"), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Which AI answers "What do they mean here?": free online models, or a small one on this phone. */
@Composable
private fun AiCard() {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var offline by remember { mutableStateOf(com.faithscroll.app.data.Ai.preferOffline(ctx)) }
    val progress by com.faithscroll.app.data.OfflineAi.progress.collectAsState()
    var removed by remember { mutableStateOf(0) }
    val ready = remember(progress, removed) { com.faithscroll.app.data.OfflineAi.ready(ctx) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Plum).padding(18.dp)) {
        Text("✦ " + tr("AI explanations", "KI-forklaringer"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(tr("For \"What do they mean here?\". Free, no account.", "For «Hva mener de her?». Gratis, uten konto."), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(tr("Online (best)", "På nett (best)"), !offline) { offline = false; com.faithscroll.app.data.Ai.setPreferOffline(ctx, false) }
            Chip(tr("On this phone", "På telefonen"), offline) { offline = true; com.faithscroll.app.data.Ai.setPreferOffline(ctx, true) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (offline) tr("A small model (Qwen 2.5, 1.5B) runs on your phone. Works without internet; simpler answers.",
                "En liten modell (Qwen 2.5, 1,5B) kjører på telefonen. Virker uten nett; enklere svar.")
            else tr("Large open models (Llama 3.3 70B and others) via free services. The phone model is used as backup if downloaded.",
                "Store åpne modeller (Llama 3.3 70B m.fl.) via gratis tjenester. Telefonmodellen brukes som reserve hvis den er lastet ned."),
            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        when {
            progress >= 0f -> {
                androidx.compose.material3.LinearProgressIndicator(progress = { progress }, color = Gold, modifier = Modifier.fillMaxWidth())
                Text(tr("Downloading phone model… ${(progress * 100).toInt()} % (≈1.6 GB, Wi-Fi recommended)", "Laster ned telefonmodell … ${(progress * 100).toInt()} % (≈1,6 GB, bruk helst Wi-Fi)"),
                    color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Phone model ready", "Telefonmodell klar") + " ✓", color = Gold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { com.faithscroll.app.data.OfflineAi.delete(ctx); removed++ }) { Text(tr("Remove", "Fjern"), color = Color.White.copy(alpha = 0.7f)) }
            }
            else -> OutlinedButton(onClick = { com.faithscroll.app.data.OfflineAi.start(ctx) }) { Text(tr("Download phone model (≈1.6 GB)", "Last ned telefonmodell (≈1,6 GB)"), color = Gold) }
        }
    }
}

/** App language: follows the phone, or English / Norsk. Restarts the screen to apply. */
@Composable
private fun LanguageCard() {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(Lang.mode(ctx)) }
    fun pick(m: Lang.Mode) {
        if (m == mode) return
        mode = m; Lang.setMode(ctx, m)
        (ctx as? android.app.Activity)?.recreate()
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Plum).padding(18.dp)) {
        Text(tr("Language", "Språk"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(tr("Phone setting", "Som telefonen"), mode == Lang.Mode.AUTO) { pick(Lang.Mode.AUTO) }
            Chip("English", mode == Lang.Mode.EN) { pick(Lang.Mode.EN) }
            Chip("Norsk", mode == Lang.Mode.NO) { pick(Lang.Mode.NO) }
        }
        if (Lang.no) {
            var also by remember { mutableStateOf(Lang.alsoEnglish) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Også engelske videoer", color = Color.White, fontSize = 14.sp)
                    Text("Med norske undertekster. Av: bare videoer på norsk.", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                }
                androidx.compose.material3.Switch(checked = also, onCheckedChange = {
                    also = it; Lang.setAlsoEnglish(ctx, it); (ctx as? android.app.Activity)?.recreate()
                })
            }
        }
        Text(
            tr("Bible verses: King James Version.", "Bibelvers: Bibelen 1930 (Det Norske Bibelselskap)."),
            color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Video quality / data saver. */
@Composable
private fun QualityCard() {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(VideoQuality.mode(ctx)) }
    val now = remember(mode) { VideoQuality.targetHeight(ctx) }
    val mobile = remember { VideoQuality.onMobileData(ctx) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Plum).padding(18.dp)) {
        Text(tr("Video quality", "Videokvalitet"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(tr("Auto", "Auto"), mode == VideoQuality.Mode.AUTO) { mode = VideoQuality.Mode.AUTO; VideoQuality.setMode(ctx, mode) }
            Chip(tr("Data saver", "Datasparing"), mode == VideoQuality.Mode.SAVER) { mode = VideoQuality.Mode.SAVER; VideoQuality.setMode(ctx, mode) }
            Chip(tr("High", "Høy"), mode == VideoQuality.Mode.HIGH) { mode = VideoQuality.Mode.HIGH; VideoQuality.setMode(ctx, mode) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (mode) {
                VideoQuality.Mode.AUTO -> tr("720p on Wi-Fi, 480p on mobile data, 360p when your phone's Data Saver is on.", "720p på Wi-Fi, 480p på mobildata, 360p når datasparing er på i telefonen.")
                VideoQuality.Mode.SAVER -> tr("Always 360p and only the next video is preloaded. Uses the least data.", "Alltid 360p, og bare neste video lastes på forhånd. Bruker minst data.")
                VideoQuality.Mode.HIGH -> tr("1080p on Wi-Fi, 720p on mobile data.", "1080p på Wi-Fi, 720p på mobildata.")
            } + tr(" Right now: ${now}p on ${if (mobile) "mobile data" else "Wi-Fi"}.", " Nå: ${now}p på ${if (mobile) "mobildata" else "Wi-Fi"}."),
            color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp
        )
        Text(tr("Video cache: ${com.faithscroll.app.data.StreamResolver.cacheSizeMb()} MB of 300 MB", "Videobuffer: ${com.faithscroll.app.data.StreamResolver.cacheSizeMb()} MB av 300 MB"),
            color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Paste a Pinterest board or TikTok link to add it to your feed. */
@Composable
private fun SourcesCard() {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var boards by remember { mutableStateOf(SocialRepository.userBoards(ctx)) }
    var tiktoks by remember { mutableStateOf(SocialRepository.userTikToks(ctx)) }
    var subs by remember { mutableStateOf(SocialRepository.userSubs(ctx)) }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Plum).padding(18.dp)) {
        Text(tr("Your sources", "Dine kilder"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(tr("Add Christian images to your feed: paste a Pinterest board or a subreddit (like r/TrueChristian). Videos come from YouTube.", "Legg til kristne bilder i feeden: lim inn en Pinterest-tavle eller et subreddit (som r/TrueChristian). Videoene kommer fra YouTube."), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.OutlinedTextField(
            value = link, onValueChange = { link = it }, singleLine = true,
            placeholder = { Text("r/… · pinterest.com/…") },
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Gold, cursorColor = Gold, focusedTextColor = Color.White, unfocusedTextColor = Color.White
            )
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                busy = true
                scope.launch {
                    val added = SocialRepository.addFromLink(ctx, link)
                    busy = false
                    if (added != null) {
                        link = ""
                        boards = SocialRepository.userBoards(ctx); tiktoks = SocialRepository.userTikToks(ctx); subs = SocialRepository.userSubs(ctx)
                        Toast.makeText(ctx, tr("Added $added", "La til $added"), Toast.LENGTH_SHORT).show()
                        sourcesChanged()
                    } else Toast.makeText(ctx, tr("That isn't a subreddit or Pinterest board.", "Det er ikke et subreddit eller en Pinterest-tavle."), Toast.LENGTH_LONG).show()
                }
            },
            enabled = link.isNotBlank() && !busy,
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
        ) { Text(if (busy) tr("Adding…", "Legger til …") else tr("Add to my feed", "Legg til i feeden")) }
        if (boards.isNotEmpty() || tiktoks.isNotEmpty() || subs.isNotEmpty()) Spacer(Modifier.height(12.dp))
        val found = remember { com.faithscroll.app.data.Discovery.channels(ctx) }
        if (found.isNotEmpty()) {
            Text(tr("Discovered for you", "Oppdaget for deg"), color = Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            found.forEach { (id, name) -> SourceRow("YouTube · $name") { com.faithscroll.app.data.Discovery.removeChannel(ctx, id); sourcesChanged() } }
        }
        subs.forEach { r ->
            SourceRow("Reddit · r/$r") { SocialRepository.removeSub(ctx, r); subs = SocialRepository.userSubs(ctx); sourcesChanged() }
        }
        boards.forEach { b ->
            SourceRow("Pinterest · $b") { SocialRepository.removeBoard(ctx, b); boards = SocialRepository.userBoards(ctx); sourcesChanged() }
        }
        tiktoks.filter { false }.forEach { t ->
            SourceRow("TikTok · " + (if (t.author.isNotBlank()) "@${t.author}" else t.id)) {
                SocialRepository.removeTikTok(ctx, t.id); tiktoks = SocialRepository.userTikToks(ctx); sourcesChanged()
            }
        }
    }
}

@Composable
private fun SourceRow(label: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
        TextButton(onClick = onRemove) { Text(tr("Remove", "Fjern"), color = Gold) }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
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
