package com.faithscroll.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.util.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The opening screen: a different look for every day of the week, dressed for the season –
 * snow in winter, blossom in spring, sun in summer, leaves in autumn, and its own look in
 * Advent, at Christmas, Easter and Pentecost. Fades away by itself (or on a tap).
 */
@Composable
fun DaySplash(onDone: () -> Unit) {
    val look = remember { SplashLook.today() }
    val fade = remember { Animatable(1f) }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { rise.animateTo(1f, tween(900)) }
        delay(2000)
        fade.animateTo(0f, tween(550))
        onDone()
    }
    val t = rememberInfiniteTransition(label = "splash")
    val time by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "t")
    val glow by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "g")

    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }
            .background(Brush.verticalGradient(look.season.colors))
            .clickable(remember { MutableInteractionSource() }, null) { onDone() },
        contentAlignment = Alignment.Center
    ) {
        // Seasonal particles drifting across the screen.
        Canvas(Modifier.fillMaxSize()) { drawSeason(look.season, time) }

        Column(
            Modifier.padding(32.dp).widthIn(max = 460.dp).graphicsLayer { translationY = (1f - rise.value) * 40f; alpha = rise.value },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Canvas(Modifier.size(120.dp)) { drawGlowCross(look.season.accent, glow) }
            Spacer(Modifier.height(18.dp))
            Text(look.season.label.uppercase(), color = look.season.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(6.dp))
            Text(look.day.title, color = Color.White, fontSize = 34.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(look.day.line, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp, lineHeight = 23.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            Text(look.day.verse, color = Color.White.copy(alpha = 0.92f), fontSize = 17.sp, lineHeight = 25.sp, fontFamily = FontFamily.Serif, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(look.day.ref, color = look.season.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun DrawScope.drawGlowCross(accent: Color, glow: Float) {
    val c = center; val r = size.minDimension / 2
    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.45f * glow), Color.Transparent), c, r), r)
    val w = r * 0.16f; val h = r * 1.25f; val armY = c.y - h * 0.18f
    val col = Brush.verticalGradient(listOf(Color.White, accent.copy(alpha = 0.95f)), c.y - h / 2, c.y + h / 2)
    drawRoundRect(col, Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(w / 2))
    drawRoundRect(col, Offset(c.x - h * 0.36f, armY - w / 2), Size(h * 0.72f, w), CornerRadius(w / 2))
}

private fun DrawScope.drawSeason(s: Season, t: Float) {
    val n = 36
    for (i in 0 until n) {
        val seed = (i * 7919 % 1000) / 1000f
        val seed2 = (i * 104729 % 1000) / 1000f
        val speed = 0.6f + seed2 * 0.8f
        val p = ((t * speed + seed) % 1f)
        when (s.kind) {
            SeasonKind.WINTER, SeasonKind.ADVENT, SeasonKind.CHRISTMAS -> { // snow falling (gold sparks in Advent/Christmas)
                val x = (seed + 0.05f * sin((p * 6 + i).toDouble()).toFloat()) * size.width
                val y = p * size.height
                val col = if (s.kind == SeasonKind.WINTER || i % 3 != 0) Color.White.copy(alpha = 0.55f) else s.accent.copy(alpha = 0.8f)
                drawCircle(col, 2f + seed2 * 4f, Offset(x, y))
            }
            SeasonKind.SPRING -> { // petals drifting down and sideways
                val x = ((seed + p * 0.3f) % 1f) * size.width
                val y = p * size.height
                rotate(p * 360f + i * 20, Offset(x, y)) {
                    drawOval(Color(0xFFF8C8DC).copy(alpha = 0.6f), Offset(x - 6f, y - 3.5f), Size(12f + seed2 * 6f, 7f))
                }
            }
            SeasonKind.SUMMER, SeasonKind.PENTECOST -> { // floating light motes rising
                val x = seed * size.width + 20f * sin((p * 2 * PI + i).toFloat())
                val y = (1f - p) * size.height
                drawCircle(s.accent.copy(alpha = 0.25f + 0.35f * sin(p * PI.toFloat())), 3f + seed2 * 5f, Offset(x, y))
            }
            SeasonKind.AUTUMN -> { // leaves tumbling
                val x = ((seed + p * 0.4f) % 1f) * size.width
                val y = p * size.height
                val leaf = listOf(Color(0xFFE07A2F), Color(0xFFC0392B), Color(0xFFE9B949))[i % 3].copy(alpha = 0.65f)
                rotate(p * 540f + i * 30, Offset(x, y)) { drawOval(leaf, Offset(x - 9f, y - 5f), Size(18f, 10f)) }
            }
            SeasonKind.EASTER -> { // sunrise rays turning slowly behind everything
                if (i < 12) {
                    val a = (i / 12.0 * 2 * PI + t * 2 * PI / 6).toFloat()
                    val c = Offset(size.width / 2, size.height * 0.38f)
                    drawLine(s.accent.copy(alpha = 0.12f), c, Offset(c.x + cos(a) * size.maxDimension, c.y + sin(a) * size.maxDimension), strokeWidth = 40f)
                }
            }
        }
    }
}

/** Shown once per app start. */
object SplashGate { @Volatile var shown = false }

enum class SeasonKind { WINTER, SPRING, SUMMER, AUTUMN, ADVENT, CHRISTMAS, EASTER, PENTECOST }
data class Season(val kind: SeasonKind, val label: String, val colors: List<Color>, val accent: Color)
data class Day(val title: String, val line: String, val verse: String, val ref: String)

object SplashLook {
    data class Look(val season: Season, val day: Day)

    fun today(): Look {
        val c = Calendar.getInstance()
        return Look(season(c), day(c.get(Calendar.DAY_OF_WEEK)))
    }

    private fun easter(year: Int): Calendar { // Gregorian computus
        val a = year % 19; val b = year / 100; val cc = year % 100; val d = b / 4; val e = b % 4
        val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
        val i = cc / 4; val k = cc % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31; val day = (h + l - 7 * m + 114) % 31 + 1
        return Calendar.getInstance().apply { clear(); set(year, month - 1, day) }
    }

    fun season(c: Calendar = Calendar.getInstance()): Season {
        val year = c.get(Calendar.YEAR)
        val doy = c.get(Calendar.DAY_OF_YEAR)
        val month = c.get(Calendar.MONTH) // 0-based
        val dom = c.get(Calendar.DAY_OF_MONTH)
        val e = easter(year).get(Calendar.DAY_OF_YEAR)
        // First Sunday of Advent: the Sunday between 27 Nov and 3 Dec.
        val advent = Calendar.getInstance().apply { clear(); set(year, Calendar.DECEMBER, 25); add(Calendar.DAY_OF_YEAR, -(get(Calendar.DAY_OF_WEEK) - 1).let { if (it == 0) 7 else it } - 21) }
            .get(Calendar.DAY_OF_YEAR)
        return when {
            (month == Calendar.DECEMBER && dom >= 24) || (month == Calendar.JANUARY && dom <= 6) ->
                Season(SeasonKind.CHRISTMAS, tr("Christmas", "Jul"), listOf(Color(0xFF3A0D16), Color(0xFF14243A)), Color(0xFFF2C14E))
            doy >= advent && month == Calendar.DECEMBER || doy >= advent && month == Calendar.NOVEMBER ->
                Season(SeasonKind.ADVENT, tr("Advent", "Advent"), listOf(Color(0xFF2A1240), Color(0xFF120A24)), Color(0xFFD9A8FF))
            doy in (e - 7)..(e + 7) ->
                Season(SeasonKind.EASTER, tr("Easter", "Påske"), listOf(Color(0xFF3B2A5C), Color(0xFFE9A86B)), Color(0xFFFFE3A3))
            doy in (e + 48)..(e + 50) ->
                Season(SeasonKind.PENTECOST, tr("Pentecost", "Pinse"), listOf(Color(0xFF5A1010), Color(0xFF1E0A0A)), Color(0xFFFF9A4D))
            month in listOf(Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY) ->
                Season(SeasonKind.WINTER, tr("Winter", "Vinter"), listOf(Color(0xFF0F2342), Color(0xFF06101F)), Color(0xFFBFE3FF))
            month in Calendar.MARCH..Calendar.MAY ->
                Season(SeasonKind.SPRING, tr("Spring", "Vår"), listOf(Color(0xFF1F4A3A), Color(0xFF3D2348)), Color(0xFFF8C8DC))
            month in Calendar.JUNE..Calendar.AUGUST ->
                Season(SeasonKind.SUMMER, tr("Summer", "Sommer"), listOf(Color(0xFF1C4E80), Color(0xFFE6A35A)), Color(0xFFFFE08A))
            else ->
                Season(SeasonKind.AUTUMN, tr("Autumn", "Høst"), listOf(Color(0xFF3A1E12), Color(0xFF1A1010)), Color(0xFFE9B949))
        }
    }

    private fun day(d: Int): Day = when (d) {
        Calendar.MONDAY -> Day(tr("Monday", "Mandag"), tr("A new week – start it with Him.", "En ny uke – begynn den sammen med Ham."),
            tr("“His mercies are new every morning.”", "«Hans miskunn er ny hver morgen.»"), tr("Lamentations 3:23", "Klagesangene 3,23"))
        Calendar.TUESDAY -> Day(tr("Tuesday", "Tirsdag"), tr("Keep going. You're not walking alone.", "Fortsett. Du går ikke alene."),
            tr("“I am with you always.”", "«Jeg er med dere alle dager.»"), tr("Matthew 28:20", "Matteus 28,20"))
        Calendar.WEDNESDAY -> Day(tr("Wednesday", "Onsdag"), tr("Halfway – take a breath and pray.", "Midt i uka – pust ut og be."),
            tr("“Come to me, all you who are weary.”", "«Kom til meg, alle dere som strever.»"), tr("Matthew 11:28", "Matteus 11,28"))
        Calendar.THURSDAY -> Day(tr("Thursday", "Torsdag"), tr("Give thanks for one thing today.", "Takk for én ting i dag."),
            tr("“Give thanks in all circumstances.”", "«Takk Gud under alle forhold.»"), tr("1 Thessalonians 5:18", "1 Tessaloniker 5,18"))
        Calendar.FRIDAY -> Day(tr("Friday", "Fredag"), tr("Remember the cross – love that gave everything.", "Husk korset – kjærlighet som ga alt."),
            tr("“Greater love has no one than this.”", "«Ingen har større kjærlighet enn dette.»"), tr("John 15:13", "Johannes 15,13"))
        Calendar.SATURDAY -> Day(tr("Saturday", "Lørdag"), tr("Rest is a gift. Slow down with Him.", "Hvile er en gave. Senk tempoet sammen med Ham."),
            tr("“Be still, and know that I am God.”", "«Vær stille og kjenn at jeg er Gud.»"), tr("Psalm 46:10", "Salme 46,11"))
        else -> Day(tr("Sunday", "Søndag"), tr("The Lord's day – worship, rest and joy.", "Herrens dag – tilbedelse, hvile og glede."),
            tr("“This is the day the Lord has made.”", "«Dette er dagen Herren har gjort.»"), tr("Psalm 118:24", "Salme 118,24"))
    }
}
