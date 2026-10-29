package com.faithscroll.app.prayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.faithscroll.app.MainActivity
import com.faithscroll.app.ui.FaithTheme
import com.faithscroll.app.ui.Gold
import com.faithscroll.app.ui.Ink
import com.faithscroll.app.ui.LivingBackground
import com.faithscroll.app.ui.Palettes
import com.faithscroll.app.ui.Plum
import com.faithscroll.app.util.Lang
import com.faithscroll.app.util.tr
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Opens from a prayer reminder as a popup card over whatever you're doing. */
class PrayerActivity : ComponentActivity() {
    companion object { const val EXTRA_BE_STILL = "be_still" }

    private val beStill = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.init(this)
        NotificationManagerCompat.from(this).cancel(7001)
        beStill.value = intent.getBooleanExtra(EXTRA_BE_STILL, false)
        setContent {
            FaithTheme {
                if (beStill.value) BeStillScreen(onClose = { finish() })
                else PrayerPopup(
                    onPray = { beStill.value = true },
                    onSnooze = { Reminders.snooze(this); finish() },
                    onDismiss = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_BE_STILL, false)) beStill.value = true
    }
}

@Composable
fun PrayerPopup(onPray: () -> Unit, onSnooze: () -> Unit, onDismiss: () -> Unit) {
    val (title, body) = remember { Reminders.prompt() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(shown, enter = fadeIn(tween(300)) + scaleIn(tween(400), initialScale = 0.85f)) {
            Column(
                Modifier.padding(28.dp).widthIn(max = 400.dp).clip(RoundedCornerShape(28.dp))
                    .clickable(remember { MutableInteractionSource() }, null) { }
            ) {
                Box(Modifier.fillMaxWidth().height(170.dp)) {
                    LivingBackground(Palettes.forTheme("prayer"))
                    BreathingLight(Modifier.align(Alignment.Center), 90f)
                }
                Column(
                    Modifier.fillMaxWidth().background(Plum).padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(title, color = Color.White, fontSize = 22.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(body, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onPray, modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
                    ) { Text(tr("Pray now", "Be nå"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onSnooze) { Text(tr("In 10 min", "Om 10 min"), color = Color.White.copy(alpha = 0.75f)) }
                        TextButton(onClick = onDismiss) { Text(tr("Not now", "Ikke nå"), color = Color.White.copy(alpha = 0.75f)) }
                    }
                }
            }
        }
    }
}

/** A soft glowing circle that grows as you breathe in and shrinks as you breathe out. */
@Composable
fun BreathingLight(modifier: Modifier = Modifier, size: Float = 220f, onPhase: ((Boolean) -> Unit)? = null) {
    val t = rememberInfiniteTransition(label = "breath")
    val k by t.animateFloat(0f, 1f, infiniteRepeatable(tween(5000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "k")
    var inhaling by remember { mutableStateOf(true) }
    var last by remember { mutableStateOf(0f) }
    LaunchedEffect(k) {
        val now = k > last
        if (now != inhaling) { inhaling = now; onPhase?.invoke(now) }
        last = k
    }
    Canvas(modifier.size(size.dp)) {
        val r = this.size.minDimension / 2
        val inner = r * (0.42f + 0.28f * k)
        drawCircle(Brush.radialGradient(listOf(Gold.copy(alpha = 0.35f + 0.25f * k), Color.Transparent), center, r), r)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), Gold.copy(alpha = 0.6f)), center, inner), inner)
    }
}

/**
 * "Be still": a quiet space to slow down and talk with God. Breathing light, gentle
 * prompts, an optional timer, and a private prayer journal.
 */
@Composable
fun BeStillScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    val prompts = remember {
        listOf(
            tr("Be still, and know that I am God.", "Vær stille, og kjenn at jeg er Gud.") to tr("Psalm 46:10", "Salme 46,11"),
            tr("He is here with you, right now.", "Han er her hos deg, akkurat nå.") to "",
            tr("Tell Him what's on your heart.", "Fortell Ham hva som ligger på hjertet ditt.") to "",
            tr("Thank Him for one thing today.", "Takk Ham for én ting i dag.") to "",
            tr("Hand Him what you're carrying.", "Gi Ham det du bærer på.") to "",
            tr("Ask Him for what you need.", "Be Ham om det du trenger.") to "",
            tr("Pray for someone you love.", "Be for noen du er glad i.") to "",
            tr("Now just listen…", "Nå – bare lytt …") to ""
        )
    }
    var index by remember { mutableIntStateOf(0) }
    var inhale by remember { mutableStateOf(true) }
    var minutes by remember { mutableIntStateOf(0) } // 0 = no timer
    var left by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var journal by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { while (true) { delay(14_000); index = (index + 1) % prompts.size } }
    LaunchedEffect(minutes) {
        if (minutes > 0) {
            left = minutes * 60; done = false
            while (left > 0) { delay(1000); left-- }
            done = true
        }
    }

    Box(Modifier.fillMaxSize().background(Ink).clickable(remember { MutableInteractionSource() }, null) { }) {
        LivingBackground(Palettes.forTheme("prayer"))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Be still", "Vær stille"), color = Color.White, fontSize = 22.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, tr("Close", "Lukk"), tint = Color.White) }
            }

            if (journal) {
                JournalList(Modifier.weight(1f)) { journal = false }
                return@Column
            }

            Spacer(Modifier.weight(1f))
            Box(contentAlignment = Alignment.Center) {
                BreathingLight(size = 240f, onPhase = { inhale = it })
                AnimatedContent(inhale, transitionSpec = { fadeIn(tween(800)) togetherWith fadeOut(tween(800)) }, label = "b") { i ->
                    Text(if (i) tr("Breathe in", "Pust inn") else tr("Breathe out", "Pust ut"), color = Ink.copy(alpha = 0.8f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(36.dp))
            AnimatedContent(if (done) -1 else index, transitionSpec = { fadeIn(tween(1500)) togetherWith fadeOut(tween(1200)) }, label = "p") { i ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val (line, ref) = if (i == -1) tr("Amen.", "Amen.") to tr("Carry His peace with you.", "Ta med deg Hans fred.") else prompts[i]
                    Text(line, color = Color.White, fontSize = 26.sp, lineHeight = 34.sp, fontFamily = FontFamily.Serif, textAlign = TextAlign.Center)
                    if (ref.isNotBlank()) Text(ref, color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.weight(1f))

            if (writing) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text(tr("Dear God…", "Kjære Gud …")) },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Gold, unfocusedBorderColor = Color.White.copy(alpha = 0.4f), cursorColor = Gold,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.Black.copy(alpha = 0.25f), unfocusedContainerColor = Color.Black.copy(alpha = 0.25f)
                    )
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { writing = false }) { Text(tr("Cancel", "Avbryt"), color = Color.White.copy(alpha = 0.8f)) }
                    Button(
                        onClick = { Journal.add(ctx, text); text = ""; writing = false },
                        enabled = text.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
                    ) { Text(tr("Keep this prayer", "Ta vare på bønnen")) }
                }
            } else {
                // Timer
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 5, 10).forEach { m ->
                        val sel = minutes == m && !done
                        Text(
                            if (sel) "%d:%02d".format(left / 60, left % 60) else "$m min",
                            color = if (sel) Ink else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (sel) Gold else Color.Transparent)
                                .border(1.dp, if (sel) Gold else Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                                .clickable { minutes = if (sel) 0 else m; if (!sel) done = false }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { writing = true }, modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.16f), contentColor = Color.White)
                ) { Text(tr("Talk to God", "Snakk med Gud"), fontSize = 16.sp) }
                TextButton(onClick = { journal = true }) { Text(tr("My prayers", "Mine bønner"), color = Gold) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun JournalList(modifier: Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(Journal.all(ctx)) }
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxWidth()) {
        Text(tr("My prayers", "Mine bønner"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(tr("Private, only on this phone. Tap the circle when a prayer is answered.", "Privat, bare på denne telefonen. Trykk på sirkelen når en bønn er besvart."),
            color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            Text(tr("No prayers yet. Tap “Talk to God” to write one.", "Ingen bønner ennå. Trykk «Snakk med Gud» for å skrive en."),
                color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(top = 24.dp))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.3f)).padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    IconButton(onClick = { Journal.toggleAnswered(ctx, p.id); items = Journal.all(ctx) }, modifier = Modifier.size(28.dp)) {
                        Icon(if (p.answered) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = if (p.answered) Gold else Color.White.copy(alpha = 0.6f))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.text, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp)
                        Text(
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.id)) + if (p.answered) " · " + tr("Answered", "Besvart") else "",
                            color = if (p.answered) Gold else Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(tr("Back", "Tilbake"), color = Gold) }
    }
}
