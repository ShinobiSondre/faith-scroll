package com.faithscroll.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.faithscroll.app.data.Prefs
import com.faithscroll.app.data.Verse

/**
 * Draws the widget's verse and reference as a picture, so the text can have any shadow, colour,
 * font, size and alignment (a home-screen widget can't change these on its own). The app's preview
 * uses the same drawing, so what you see in settings is exactly what's on the home screen.
 */
object WidgetText {
    data class Style(val shadow: Int, val color: String, val font: String, val size: Int, val align: String, val lightText: Boolean)

    fun style(ctx: Context) = Style(Prefs.widgetShadow(ctx), Prefs.widgetColor(ctx), Prefs.widgetFont(ctx),
        Prefs.widgetSize(ctx), Prefs.widgetAlign(ctx), Prefs.widgetLightText(ctx))

    /** Colour choices: name → (text colour, reference colour). */
    val colors: Map<String, Pair<Int, Int>> = linkedMapOf(
        "white" to (0xFFFFFFFF.toInt() to 0xFFF3D27A.toInt()),
        "cream" to (0xFFFBF1DC.toInt() to 0xFFE8C27A.toInt()),
        "gold" to (0xFFF3D27A.toInt() to 0xFFFFFFFF.toInt()),
        "rose" to (0xFFFFD6E0.toInt() to 0xFFF3D27A.toInt()),
        "sky" to (0xFFCDE8FF.toInt() to 0xFFF3D27A.toInt()),
        "dark" to (0xFF1B1530.toInt() to 0xFF8A5A00.toInt())
    )

    fun textColors(s: Style): Pair<Int, Int> =
        colors[s.color] ?: if (s.lightText) colors.getValue("white") else colors.getValue("dark")

    private fun isLight(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 > 140

    private fun typeface(font: String): Typeface = when (font) {
        "sans" -> Typeface.create("sans-serif", Typeface.NORMAL)
        "bold" -> Typeface.create("sans-serif", Typeface.BOLD)
        "script" -> Typeface.create("cursive", Typeface.NORMAL)
        else -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }

    /**
     * The verse drawn on a transparent picture of [w]×[h] pixels. [d] = screen density.
     * Leaves room bottom-right for the ↻ button.
     */
    fun render(verse: Verse, s: Style, w: Int, h: Int, d: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val (textColor, refColor) = textColors(s)
        val shadowColor = if (isLight(textColor)) Color.BLACK else Color.WHITE
        // Shadow strength → softness, offset and darkness.
        val k = s.shadow / 100f
        fun shadow(p: TextPaint) {
            if (s.shadow <= 0) p.clearShadowLayer()
            else p.setShadowLayer((0.6f + 9f * k) * d, 0f, (0.4f + 1.6f * k) * d, Color.argb((70 + 185 * k).toInt().coerceAtMost(255), Color.red(shadowColor), Color.green(shadowColor), Color.blue(shadowColor)))
        }
        val padL = 18 * d; val padR = 14 * d; val padT = 14 * d; val padB = 10 * d
        val refH = 26 * d
        val width = (w - padL - padR).toInt().coerceAtLeast(10)
        val maxH = h - padT - padB - refH
        val align = if (s.align == "center") Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { color = textColor; typeface = typeface(s.font); shadow(this) }
        val text = com.faithscroll.app.util.Quotes.wrap(verse.text)
        // Biggest size that fits (your size setting sets the upper limit).
        var size = 22f * s.size / 100f
        var layout: StaticLayout
        while (true) {
            paint.textSize = size * d
            layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(align)
                .setLineSpacing(0f, 1.12f).setIncludePad(false).build()
            if (layout.height <= maxH || size <= 9f) break
            size -= 0.5f
        }
        val top = padT + ((maxH - layout.height) / 2f).coerceAtLeast(0f)
        c.save(); c.translate(padL, top)
        if (s.shadow > 65) layout.draw(c) // strong: a second pass for a heavier halo
        layout.draw(c)
        c.restore()

        val refPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = refColor; textSize = 13.5f * d * (s.size / 100f).coerceIn(0.8f, 1.3f)
            typeface = Typeface.create(typeface(s.font), Typeface.BOLD); shadow(this)
        }
        val ref = "— ${verse.label}"
        val refW = refPaint.measureText(ref)
        val y = h - padB - 9 * d
        val x = if (s.align == "center") (w - refW) / 2f else padL
        if (s.shadow > 65) c.drawText(ref, x, y, refPaint)
        c.drawText(ref, x, y, refPaint)
        return bmp
    }
}
