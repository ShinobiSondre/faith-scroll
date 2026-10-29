package com.faithscroll.app.ui

import com.faithscroll.app.util.tr

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.data.Message
import com.faithscroll.app.util.Share
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val TAU = (2 * PI).toFloat()

/** A full-screen illustrated message card in the feed. */
@Composable
fun MessageCard(
    message: Message,
    reason: String?,
    isCurrent: Boolean,
    saved: Boolean,
    liked: Boolean,
    onLike: () -> Unit,
    onDoubleTapLike: () -> Unit,
    onSave: () -> Unit,
    onShared: () -> Unit,
    bottomInset: Dp
) {
    val ctx = LocalContext.current
    val palette = remember(message.theme) { Palettes.forTheme(message.theme) }
    val t = rememberInfiniteTransition(label = "scene")
    val loop by t.animateFloat(0f, 1f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "loop")
    val zoom by t.animateFloat(1f, 1.08f, infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom")

    val reveal = remember(message.id) { Animatable(0f) }
    LaunchedEffect(isCurrent) { if (isCurrent) reveal.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }

    var burstAt by remember { mutableStateOf<Offset?>(null) }
    var burstKey by remember { mutableIntStateOf(0) }
    val cfg = LocalConfiguration.current
    val wide = cfg.screenWidthDp > cfg.screenHeightDp

    Box(
        Modifier.fillMaxSize().pointerInput(message.id) {
            detectTapGestures(onDoubleTap = { pos -> onDoubleTapLike(); burstAt = pos; burstKey++ })
        }
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom }) {
            drawScene(message.scene, palette, loop)
        }
        // Soft scrim so the words always read clearly
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent, Color.Black.copy(alpha = 0.45f))))
        }

        Column(
            Modifier.fillMaxSize().padding(
                start = if (wide) 80.dp else 32.dp, end = if (wide) 120.dp else 76.dp,
                top = 70.dp, bottom = bottomInset + if (wide) 60.dp else 160.dp
            ),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val len = message.text.length
            val size = when { len > 45 -> 30.sp; len > 28 -> 36.sp; else -> 44.sp }
            Text(
                message.text,
                color = Color.White,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = size,
                lineHeight = size * 1.2f,
                textAlign = TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 4f), 16f)
                ),
                modifier = Modifier.graphicsLayer {
                    alpha = 0.1f + 0.9f * reveal.value
                    translationY = (1f - reveal.value) * 50f
                    val s = 0.94f + 0.06f * reveal.value
                    scaleX = s; scaleY = s
                }
            )
            if (message.ref.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    message.ref.uppercase(),
                    color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                    modifier = Modifier.graphicsLayer { alpha = ((reveal.value - 0.5f) * 2f).coerceIn(0f, 1f) }
                )
            }
        }

        if (reason != null) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = bottomInset + 30.dp)) { ReasonTag(reason) }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomInset + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            RailButton(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (liked) tr("Amen", "Amen") else tr("Like", "Liker"), liked, if (liked) HeartRed else Color.White, onLike)
            RailButton(if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (saved) tr("Saved", "Lagret") else tr("Save", "Lagre"), saved, if (saved) Gold else Color.White, onSave)
            RailButton(Icons.Filled.Share, tr("Share", "Del"), false, Color.White) { onShared(); Share.message(ctx, message, renderMessage(message)) }
        }

        burstAt?.let { HeartBurstAt(it, burstKey) }
    }
}

// ---------------------------------------------------------------------------
// Scenes. Each is drawn from simple shapes, so they look crisp at any size and
// can also be rendered into an image for sharing.
// ---------------------------------------------------------------------------

fun DrawScope.drawScene(scene: Int, palette: List<Color>, t: Float) {
    when (scene) {
        0 -> sunriseMountains(palette, t)
        1 -> starryNight(palette, t)
        2 -> ocean(palette, t)
        3 -> lightRays(palette, t)
        4 -> fieldTree(palette, t)
        else -> crossHill(palette, t)
    }
}

private fun dark(c: Color, f: Float) = lerp(c, Color.Black, f)
private fun light(c: Color, f: Float) = lerp(c, Color.White, f)

private fun DrawScope.sky(top: Color, bottom: Color) =
    drawRect(Brush.verticalGradient(listOf(top, bottom)))

private fun DrawScope.glow(center: Offset, radius: Float, color: Color, alpha: Float = 0.55f) =
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center, radius), radius, center)

private fun DrawScope.ridge(baseY: Float, amp: Float, waves: Float, phase: Float, color: Color) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(0f, h)
        var x = 0f
        while (x <= w + 10f) {
            val y = baseY - amp * (0.6f * sin(x / w * waves * TAU + phase) + 0.4f * sin(x / w * waves * 2.3f * TAU + phase * 1.7f))
            lineTo(x, y); x += 8f
        }
        lineTo(w, h); close()
    }
    drawPath(p, color)
}

private fun DrawScope.particles(count: Int, seed: Int, t: Float, color: Color, rise: Boolean = true) {
    val rnd = java.util.Random(seed.toLong())
    repeat(count) {
        val x0 = rnd.nextFloat(); val y0 = rnd.nextFloat(); val sp = 0.3f + rnd.nextFloat(); val r = 1.5f + rnd.nextFloat() * 3.5f
        val ph = rnd.nextFloat()
        val y = if (rise) ((y0 - t * sp) % 1f + 1f) % 1f else y0
        val x = x0 + 0.02f * sin((t + ph) * TAU)
        val a = 0.3f + 0.7f * (0.5f + 0.5f * sin((t * 3 + ph) * TAU))
        drawCircle(color.copy(alpha = a * 0.8f), r * density, Offset(x * size.width, y * size.height))
    }
}

private fun DrawScope.sunriseMountains(p: List<Color>, t: Float) {
    val w = size.width; val h = size.height
    sky(dark(p[0], 0.2f), light(p[2], 0.25f))
    val sun = Offset(w * 0.5f, h * (0.56f - 0.03f * sin(t * TAU)))
    glow(sun, w * 0.9f, light(p[2], 0.4f), 0.5f)
    drawCircle(light(p[2], 0.55f), w * 0.13f, sun)
    ridge(h * 0.62f, h * 0.08f, 1.3f, 0.5f, dark(p[1], 0.25f).copy(alpha = 0.85f))
    ridge(h * 0.72f, h * 0.06f, 1.8f, 2f, dark(p[1], 0.5f))
    ridge(h * 0.84f, h * 0.04f, 2.4f, 4f, dark(p[0], 0.65f))
    particles(18, 7, t, Color.White)
}

private fun DrawScope.starryNight(p: List<Color>, t: Float) {
    val w = size.width; val h = size.height
    sky(dark(p[0], 0.6f), dark(p[1], 0.3f))
    val rnd = java.util.Random(42)
    repeat(110) {
        val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h * 0.75f
        val ph = rnd.nextFloat(); val r = (0.6f + rnd.nextFloat() * 1.6f) * density
        val a = 0.35f + 0.65f * (0.5f + 0.5f * sin((t * 4 + ph) * TAU))
        drawCircle(Color.White.copy(alpha = a), r, Offset(x, y))
    }
    // Shooting star
    val st = (t * 2f) % 1f
    if (st < 0.25f) {
        val k = st / 0.25f
        val start = Offset(w * (0.15f + 0.6f * k), h * (0.1f + 0.15f * k))
        drawLine(Brush.linearGradient(listOf(Color.Transparent, Color.White), start - Offset(w * 0.12f, h * 0.03f), start),
            start - Offset(w * 0.12f, h * 0.03f), start, strokeWidth = 2.5f * density, cap = StrokeCap.Round)
    }
    // Crescent moon
    val mc = Offset(w * 0.75f, h * 0.18f); val mr = w * 0.08f
    glow(mc, mr * 4, light(p[2], 0.5f), 0.35f)
    val moon = Path().apply { addOval(androidx.compose.ui.geometry.Rect(mc, mr)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(mc + Offset(mr * 0.45f, -mr * 0.2f), mr * 0.9f)) }
    drawPath(Path.combine(PathOperation.Difference, moon, bite), light(p[2], 0.75f))
    ridge(h * 0.82f, h * 0.04f, 1.5f, 1f, dark(p[0], 0.8f))
}

private fun DrawScope.ocean(p: List<Color>, t: Float) {
    val w = size.width; val h = size.height
    sky(light(p[1], 0.1f), light(p[2], 0.35f))
    val sun = Offset(w * 0.5f, h * 0.58f)
    glow(sun, w * 0.8f, light(p[2], 0.5f), 0.55f)
    drawCircle(light(p[2], 0.65f), w * 0.11f, sun)
    for (i in 0 until 5) {
        val base = h * (0.6f + i * 0.085f)
        val c = lerp(dark(p[1], 0.15f), dark(p[0], 0.65f), i / 4f)
        val path = Path().apply {
            moveTo(0f, h)
            var x = 0f
            while (x <= w + 10f) {
                lineTo(x, base + h * 0.012f * (i + 1) * sin(x / w * (3 + i) * TAU + t * TAU * (if (i % 2 == 0) 1 else -1)))
                x += 8f
            }
            lineTo(w, h); close()
        }
        drawPath(path, c)
    }
    // Sun path glitter on the water
    val rnd = java.util.Random(3)
    repeat(40) {
        val y = h * (0.62f + rnd.nextFloat() * 0.3f); val spread = (y / h - 0.58f) * w * 0.5f
        val x = w * 0.5f + (rnd.nextFloat() - 0.5f) * spread
        val a = 0.5f + 0.5f * sin((t * 5 + rnd.nextFloat()) * TAU)
        drawLine(Color.White.copy(alpha = 0.5f * a), Offset(x - 8f, y), Offset(x + 8f, y), strokeWidth = 2f * density)
    }
}

private fun DrawScope.lightRays(p: List<Color>, t: Float) {
    val w = size.width; val h = size.height
    sky(light(p[1], 0.1f), light(p[2], 0.45f))
    val origin = Offset(w * 0.5f, h * 0.12f)
    val sway = 0.06f * sin(t * TAU)
    for (i in 0 until 9) {
        val a = -0.9f + i * 0.225f + sway
        val len = h * 1.3f
        val spread = 0.045f
        val ray = Path().apply {
            moveTo(origin.x, origin.y)
            lineTo(origin.x + len * sin(a - spread), origin.y + len * cos(a - spread))
            lineTo(origin.x + len * sin(a + spread), origin.y + len * cos(a + spread))
            close()
        }
        drawPath(ray, Brush.radialGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent), origin, len))
    }
    glow(origin, w * 0.6f, Color.White, 0.6f)
    // Clouds
    val drift = t * w * 0.1f
    fun cloud(cx: Float, cy: Float, s: Float, c: Color) {
        listOf(Offset(0f, 0f) to 1f, Offset(-0.9f, 0.25f) to 0.7f, Offset(0.9f, 0.2f) to 0.75f, Offset(-0.4f, -0.35f) to 0.65f, Offset(0.45f, -0.3f) to 0.6f)
            .forEach { (o, r) -> drawCircle(c, s * r, Offset(cx + o.x * s, cy + o.y * s)) }
    }
    cloud(w * 0.2f + drift, h * 0.16f, w * 0.13f, light(p[1], 0.7f).copy(alpha = 0.95f))
    cloud(w * 0.82f - drift, h * 0.13f, w * 0.15f, light(p[1], 0.8f).copy(alpha = 0.95f))
    particles(22, 11, t, Color.White)
}

private fun DrawScope.fieldTree(p: List<Color>, t: Float) {
    val w = size.width; val h = size.height
    sky(light(p[2], 0.2f), light(p[1], 0.5f))
    glow(Offset(w * 0.25f, h * 0.3f), w * 0.8f, Color.White, 0.45f)
    ridge(h * 0.74f, h * 0.035f, 0.8f, 1f, dark(p[1], 0.35f).copy(alpha = 0.7f))
    ridge(h * 0.8f, h * 0.03f, 0.6f, 3f, dark(p[0], 0.45f))
    // Tree
    val base = Offset(w * 0.66f, h * 0.79f)
    val trunk = dark(p[0], 0.7f)
    drawLine(trunk, base, base - Offset(0f, h * 0.17f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
    drawLine(trunk, base - Offset(0f, h * 0.11f), base - Offset(w * 0.07f, h * 0.18f), strokeWidth = w * 0.014f, cap = StrokeCap.Round)
    drawLine(trunk, base - Offset(0f, h * 0.13f), base - Offset(-w * 0.07f, h * 0.2f), strokeWidth = w * 0.012f, cap = StrokeCap.Round)
    val sway = sin(t * TAU) * w * 0.006f
    val leaf = dark(p[0], 0.55f)
    listOf(Offset(0f, -0.24f) to 0.11f, Offset(-0.08f, -0.2f) to 0.08f, Offset(0.08f, -0.21f) to 0.085f, Offset(-0.03f, -0.29f) to 0.075f, Offset(0.05f, -0.28f) to 0.07f)
        .forEach { (o, r) -> drawCircle(leaf, w * r, Offset(base.x + o.x * w + sway, base.y + o.y * h)) }
    particles(30, 5, t, light(p[2], 0.6f))
}

private fun DrawScope.crossHill(p: List<Color>, t: Float) {
    val w = size.width; val h = size.height
    sky(dark(p[0], 0.15f), light(p[2], 0.2f))
    val sun = Offset(w * 0.5f, h * 0.66f)
    glow(sun, w * 1.0f, light(p[2], 0.35f), 0.6f)
    drawCircle(light(p[2], 0.5f), w * 0.16f, sun)
    ridge(h * 0.78f, h * 0.05f, 0.5f, 1.2f, dark(p[0], 0.7f))
    // Cross on the hill
    val c = Offset(w * 0.5f, h * 0.72f)
    val col = dark(p[0], 0.85f)
    drawRect(col, Offset(c.x - w * 0.012f, c.y - h * 0.16f), Size(w * 0.024f, h * 0.17f))
    drawRect(col, Offset(c.x - w * 0.07f, c.y - h * 0.125f), Size(w * 0.14f, w * 0.022f))
    // Birds
    for (i in 0 until 4) {
        val k = ((t + i * 0.23f) % 1f)
        val bx = w * (-0.1f + 1.2f * k); val by = h * (0.25f + 0.05f * i + 0.02f * sin(k * TAU * 2))
        val flap = 0.6f + 0.4f * sin((t * 12 + i) * TAU)
        val s = w * 0.025f
        val bird = Path().apply { moveTo(bx - s, by - s * 0.4f * flap); quadraticTo(bx - s * 0.4f, by - s * 0.5f * flap, bx, by); quadraticTo(bx + s * 0.4f, by - s * 0.5f * flap, bx + s, by - s * 0.4f * flap) }
        drawPath(bird, col.copy(alpha = 0.8f), style = Stroke(width = 2.2f * density, cap = StrokeCap.Round))
    }
}

/** Renders a message as a 1080×1350 image for sharing. */
fun renderMessage(m: Message): Bitmap {
    val w = 1080; val h = 1350
    val img = ImageBitmap(w, h)
    CanvasDrawScope().draw(Density(2.5f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(w.toFloat(), h.toFloat())) {
        drawScene(m.scene, Palettes.forTheme(m.theme), 0.3f)
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent, Color.Black.copy(alpha = 0.45f))))
    }
    val bmp = img.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
    val c = android.graphics.Canvas(bmp)
    val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textSize = if (m.text.length > 45) 76f else 96f
        setShadowLayer(16f, 0f, 4f, 0x80000000.toInt())
    }
    val layout = StaticLayout.Builder.obtain(m.text, 0, m.text.length, tp, w - 200)
        .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.12f).build()
    val top = h * 0.4f - layout.height / 2f
    c.save(); c.translate(100f, top); layout.draw(c); c.restore()
    if (m.ref.isNotBlank()) {
        val rp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF3D27A.toInt(); textSize = 40f; textAlign = Paint.Align.CENTER; letterSpacing = 0.12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        c.drawText(m.ref.uppercase(), w / 2f, top + layout.height + 80f, rp)
    }
    val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); textSize = 30f; textAlign = Paint.Align.CENTER; letterSpacing = 0.2f }
    c.drawText("FAITHSCROLL", w / 2f, h - 60f, foot)
    return bmp
}
