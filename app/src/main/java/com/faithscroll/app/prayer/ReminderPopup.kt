package com.faithscroll.app.prayer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.data.VerseRepository
import com.faithscroll.app.ui.Gold
import com.faithscroll.app.ui.Ink
import com.faithscroll.app.ui.LivingBackground
import com.faithscroll.app.ui.Palettes
import com.faithscroll.app.ui.Plum
import com.faithscroll.app.util.tr
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The prayer reminder: the screen dims, golden light rays turn slowly behind a glowing centre,
 * sparks of light drift upward, and the card springs up with its words appearing one after another.
 */
@Composable
fun ReminderPopup(onPray: () -> Unit, onSnooze: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val (title, body) = remember { Reminders.prompt() }
    val verse = remember { VerseRepository.all(ctx).filter { it.theme == "prayer" || it.theme == "peace" }.randomOrNull() }

    val appear = remember { Animatable(0f) }      // whole scene
    val card = remember { Animatable(0f) }        // the card's spring
    val lines = remember { List(4) { Animatable(0f) } } // title, text, verse, buttons – one after another
    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        launch { appear.animateTo(1f, tween(500)) }
        kotlinx.coroutines.delay(150)
        launch { card.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow)) }
        lines.forEachIndexed { i, a -> launch { kotlinx.coroutines.delay(320L + i * 140L); a.animateTo(1f, tween(450)) } }
    }
    fun leave(then: () -> Unit) = scope.launch {
        launch { card.animateTo(0f, tween(260)) }
        appear.animateTo(0f, tween(300)); then()
    }
    BackHandler { leave(onDismiss) }

    // Endless motion: rays turning, glow breathing, sparks rising.
    val t = rememberInfiniteTransition(label = "reminder")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(40_000, easing = LinearEasing)), label = "spin")
    val glow by t.animateFloat(0.75f, 1.1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "glow")
    val float by t.animateFloat(-6f, 6f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "float")
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { now = it } }
    val sparks = remember { List(28) { Triple(Random.nextFloat(), 0.3f + Random.nextFloat() * 0.7f, Random.nextFloat()) } } // x, speed, phase

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Brush.verticalGradient(listOf(Color(0xE6120E22), Color(0xF0080612))))
            .clickable(remember { MutableInteractionSource() }, null) { leave(onDismiss) },
        contentAlignment = Alignment.Center
    ) {
        // Light rays + glow + sparks
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height * 0.36f)
            val r = size.maxDimension
            rotate(spin, c) {
                for (i in 0 until 14) {
                    val a = (i * 360f / 14) * (PI / 180).toFloat()
                    val w = 0.07f
                    val p = Path().apply {
                        moveTo(c.x, c.y)
                        lineTo(c.x + r * kotlin.math.cos(a - w), c.y + r * kotlin.math.sin(a - w))
                        lineTo(c.x + r * kotlin.math.cos(a + w), c.y + r * kotlin.math.sin(a + w))
                        close()
                    }
                    drawPath(p, Brush.radialGradient(listOf(Gold.copy(alpha = 0.16f), Color.Transparent), c, r * 0.7f))
                }
            }
            drawCircle(Brush.radialGradient(listOf(Gold.copy(alpha = 0.45f * glow), Gold.copy(alpha = 0.08f), Color.Transparent), c, 260.dp.toPx() * glow), 260.dp.toPx() * glow, c)
            val secs = now / 1_000_000_000.0
            sparks.forEach { (x, speed, phase) ->
                val prog = ((secs * speed * 0.12 + phase) % 1.0).toFloat()
                val y = size.height * (1f - prog)
                val sx = size.width * x + sin((secs * 0.8 + phase * 6).toFloat()) * 14.dp.toPx()
                val fade = sin(prog * PI.toFloat()) // in, then out
                drawCircle(Gold.copy(alpha = 0.55f * fade), (1.5f + 2.5f * speed).dp.toPx(), Offset(sx, y))
            }
        }

        // The card
        Column(
            Modifier.padding(26.dp).widthIn(max = 420.dp)
                .graphicsLayer {
                    translationY = (1f - card.value) * 220.dp.toPx()
                    scaleX = 0.88f + 0.12f * card.value; scaleY = scaleX
                    alpha = card.value.coerceIn(0f, 1f)
                }
                .shadow(30.dp, RoundedCornerShape(30.dp), ambientColor = Gold, spotColor = Gold)
                .clip(RoundedCornerShape(30.dp))
                .clickable(remember { MutableInteractionSource() }, null) { },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                LivingBackground(Palettes.forTheme("prayer"))
                BreathingLight(Modifier.align(Alignment.Center), 120f)
                Text("🙏", fontSize = 46.sp, modifier = Modifier.graphicsLayer { translationY = float.dp.toPx() })
            }
            Column(Modifier.fillMaxWidth().background(Plum).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = Color.White, fontSize = 24.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.reveal(lines[0].value))
                Spacer(Modifier.height(8.dp))
                Text(body, color = Color.White.copy(alpha = 0.82f), fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.reveal(lines[1].value))
                verse?.let { v ->
                    Spacer(Modifier.height(16.dp))
                    Column(
                        Modifier.reveal(lines[2].value).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.06f)).padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("«${v.text}»", color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, lineHeight = 21.sp,
                            fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center, maxLines = 5)
                        Spacer(Modifier.height(4.dp))
                        Text(v.label, color = Gold, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Column(Modifier.reveal(lines[3].value)) {
                    Button(
                        onClick = { leave(onPray) }, modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
                    ) { Text(tr("Pray now", "Be nå"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { leave(onSnooze) }) { Text(tr("In 10 min", "Om 10 min"), color = Color.White.copy(alpha = 0.75f)) }
                        TextButton(onClick = { leave(onDismiss) }) { Text(tr("Not now", "Ikke nå"), color = Color.White.copy(alpha = 0.75f)) }
                    }
                }
            }
        }
    }
}

/** Fades in and rises a little as [p] goes 0 → 1. */
private fun Modifier.reveal(p: Float) = graphicsLayer { alpha = p; translationY = (1f - p) * 14.dp.toPx() }
