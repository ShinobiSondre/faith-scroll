package com.faithscroll.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

// Follow the season and the day (see SeasonTheme); set once when the app starts.
val Gold: Color get() = SeasonTheme.current.accent
val Ink: Color get() = SeasonTheme.current.ink
val Plum: Color get() = SeasonTheme.current.plum
val HeartRed = Color(0xFFFF4D6D)

private fun scheme() = SeasonTheme.current.let { c ->
    darkColorScheme(
        primary = c.accent,
        onPrimary = c.ink,
        secondary = c.secondary,
        background = c.ink,
        onBackground = Color.White,
        surface = c.plum,
        onSurface = Color.White,
        surfaceVariant = androidx.compose.ui.graphics.lerp(c.plum, Color.White, 0.06f),
        onSurfaceVariant = Color(0xFFD6D0E6),
        surfaceContainer = c.plum,
        surfaceContainerHigh = androidx.compose.ui.graphics.lerp(c.plum, Color.White, 0.04f),
        surfaceContainerLow = androidx.compose.ui.graphics.lerp(c.plum, c.ink, 0.5f)
    )
}

@Composable
fun FaithTheme(content: @Composable () -> Unit) {
    // Re-made when the season/day colours are switched on or off in the Me tab.
    val version = SeasonTheme.version.value
    val colors = androidx.compose.runtime.remember(version) { scheme() }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Gradient palettes for verse cards, picked per theme so each topic has its own mood. */
object Palettes {
    private val all = mapOf(
        "hope" to listOf(Color(0xFF1E3C72), Color(0xFF6A4C93), Color(0xFFF7B267)),
        "love" to listOf(Color(0xFF42033D), Color(0xFFB5179E), Color(0xFFFF8FA3)),
        "strength" to listOf(Color(0xFF0B1D3A), Color(0xFF1F4E79), Color(0xFF4FB0C6)),
        "comfort" to listOf(Color(0xFF1B2A41), Color(0xFF324A5F), Color(0xFFCCC9DC)),
        "guidance" to listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C7873)),
        "peace" to listOf(Color(0xFF134E5E), Color(0xFF2E8B83), Color(0xFF71B280)),
        "courage" to listOf(Color(0xFF3A0CA3), Color(0xFF7209B7), Color(0xFFF72585)),
        "faith" to listOf(Color(0xFF141E30), Color(0xFF35577D), Color(0xFFE0C097)),
        "grace" to listOf(Color(0xFF2B1055), Color(0xFF7597DE), Color(0xFFFFD6A5)),
        "joy" to listOf(Color(0xFFFF6B35), Color(0xFFF7C59F), Color(0xFF7B2CBF)),
        "prayer" to listOf(Color(0xFF0D0A14), Color(0xFF3C2A6B), Color(0xFFC3A6FF))
    )
    fun forTheme(theme: String): List<Color> = all[theme] ?: all.getValue("faith")
    fun argb(theme: String): IntArray = forTheme(theme).map { it.toArgb() }.toIntArray()
}
