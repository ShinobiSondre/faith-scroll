package com.faithscroll.app.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import java.util.Calendar

/**
 * The app's colours follow the church year and the season – icy blue in winter, blossom in spring,
 * warm gold in summer, amber in autumn, violet in Advent, red and gold at Christmas, dawn at Easter,
 * fire at Pentecost – and each weekday gives the backgrounds its own faint tint. Picked when the
 * app starts. Can be turned off in the Me tab (then the classic gold and plum).
 */
object SeasonTheme {
    data class Colors(val accent: Color, val ink: Color, val plum: Color, val secondary: Color, val name: String, val day: String)

    private val classic = Colors(Color(0xFFF3D27A), Color(0xFF0D0A14), Color(0xFF1B1530), Color(0xFFB9A6FF), "Classic", "")

    @Volatile var current: Colors = classic
        private set

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("faithscroll", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("seasonTheme", true)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("seasonTheme", on).apply(); init(ctx) }

    /** Goes up when the colours change, so the screens are drawn again in the new ones. */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    fun init(ctx: Context) {
        current = if (enabled(ctx)) compute() else classic
        version.intValue++
    }

    private fun compute(c: Calendar = Calendar.getInstance()): Colors {
        val s = SplashLook.season(c)
        val (ink, plum, secondary) = when (s.kind) {
            SeasonKind.WINTER -> Triple(Color(0xFF070D18), Color(0xFF14223A), Color(0xFF9FC9F0))
            SeasonKind.SPRING -> Triple(Color(0xFF0A1410), Color(0xFF1B2E25), Color(0xFFA8E0B8))
            SeasonKind.SUMMER -> Triple(Color(0xFF0C1220), Color(0xFF1A2B44), Color(0xFF8FD0FF))
            SeasonKind.AUTUMN -> Triple(Color(0xFF120B08), Color(0xFF2A1A12), Color(0xFFE0B080))
            SeasonKind.ADVENT -> Triple(Color(0xFF0E0718), Color(0xFF241238), Color(0xFFC3A6FF))
            SeasonKind.CHRISTMAS -> Triple(Color(0xFF12070A), Color(0xFF2E1018), Color(0xFFE8C9A0))
            SeasonKind.EASTER -> Triple(Color(0xFF100C18), Color(0xFF2A2140), Color(0xFFFFC9A8))
            SeasonKind.PENTECOST -> Triple(Color(0xFF140808), Color(0xFF321414), Color(0xFFFFB27A))
        }
        // Each weekday a faint tint of its own (Friday crimson for the cross, Sunday gold for the resurrection).
        val (tint, day) = when (c.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> Color(0xFF3A6FD8) to "monday"
            Calendar.TUESDAY -> Color(0xFF1FA39A) to "tuesday"
            Calendar.WEDNESDAY -> Color(0xFF3FAA55) to "wednesday"
            Calendar.THURSDAY -> Color(0xFFD99A2B) to "thursday"
            Calendar.FRIDAY -> Color(0xFFB0283E) to "friday"
            Calendar.SATURDAY -> Color(0xFF7A4FD0) to "saturday"
            else -> Color(0xFFE6B84A) to "sunday"
        }
        return Colors(
            accent = s.accent,
            ink = lerp(ink, tint, 0.05f),
            plum = lerp(plum, tint, 0.12f),
            secondary = secondary,
            name = s.label,
            day = day
        )
    }
}
