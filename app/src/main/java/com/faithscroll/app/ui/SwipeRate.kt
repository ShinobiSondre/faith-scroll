package com.faithscroll.app.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.util.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Swipe a card right for "more like this", left for "not this". The card follows your finger,
 * a label fades in, and past a third of the screen the choice counts.
 */
@Composable
fun SwipeToRate(enabled: Boolean, onMore: () -> Unit, onLess: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    var flash by remember { mutableStateOf<Boolean?>(null) } // true = "more" confirmation
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(LocalDensity.current) { maxWidth.toPx() }
        val limit = w * 0.3f
        Box(
            Modifier.fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount -> change.consume(); scope.launch { dx.snapTo(dx.value + amount) } },
                        onDragCancel = { scope.launch { dx.animateTo(0f) } },
                        onDragEnd = {
                            scope.launch {
                                when {
                                    dx.value > limit -> { flash = true; onMore(); dx.animateTo(0f, tween(250)) }
                                    dx.value < -limit -> { dx.animateTo(-w * 1.2f, tween(220)); onLess(); dx.snapTo(0f) }
                                    else -> dx.animateTo(0f)
                                }
                            }
                        }
                    )
                }
                .graphicsLayer { translationX = dx.value; rotationZ = dx.value / w * 6f }
        ) { content() }

        val p = (abs(dx.value) / limit).coerceIn(0f, 1f)
        if (dx.value > 8f) Badge(tr("♥  More like this", "♥  Mer av dette"), Gold, Modifier.align(Alignment.CenterStart).padding(start = 24.dp), p)
        if (dx.value < -8f) Badge(tr("✕  Not for me", "✕  Ikke for meg"), Color(0xFFE57373), Modifier.align(Alignment.CenterEnd).padding(end = 24.dp), p)
        if (flash == true) {
            LaunchedEffect(Unit) { delay(1400); flash = null }
            Badge(tr("You'll see more of this", "Du får se mer av dette"), Gold, Modifier.align(Alignment.Center), 1f)
        }
        SwipeHint(Modifier.align(Alignment.Center), enabled)
    }
}

@Composable
private fun Badge(text: String, color: Color, modifier: Modifier, alpha: Float) {
    Text(
        text, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.graphicsLayer { this.alpha = alpha; scaleX = 0.8f + 0.2f * alpha; scaleY = 0.8f + 0.2f * alpha }
            .clip(RoundedCornerShape(50)).background(color).padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

/** Shown on the first few videos so people discover the gesture. */
@Composable
private fun SwipeHint(modifier: Modifier, enabled: Boolean) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("swipe_hint", Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(enabled && prefs.getInt("shown", 0) < 3) }
    if (!show) return
    LaunchedEffect(Unit) {
        prefs.edit().putInt("shown", prefs.getInt("shown", 0) + 1).apply()
        delay(3500); show = false
    }
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(tr("Swipe →  more like this", "Sveip →  mer av dette"), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
        Text(tr("Swipe ←  not for me", "Sveip ←  ikke for meg"), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}
