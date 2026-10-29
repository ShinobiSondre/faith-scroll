package com.faithscroll.app.util

import android.content.Context
import java.util.Locale

/** App language: follows the phone (Norwegian or English), or a choice made in Me. */
object Lang {
    @Volatile var no: Boolean = false
        private set
    /** Norwegian mode only: also show English videos (with Norwegian subtitles). */
    @Volatile var alsoEnglish: Boolean = false
        private set
    fun setAlsoEnglish(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("also_en", on).apply(); alsoEnglish = on }

    enum class Mode { AUTO, EN, NO }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("lang", Context.MODE_PRIVATE)
    fun mode(ctx: Context): Mode = runCatching { Mode.valueOf(prefs(ctx).getString("mode", "AUTO")!!) }.getOrDefault(Mode.AUTO)

    fun setMode(ctx: Context, m: Mode) {
        prefs(ctx).edit().putString("mode", m.name).apply()
        init(ctx)
    }

    fun init(ctx: Context) {
        alsoEnglish = prefs(ctx).getBoolean("also_en", false)
        no = when (mode(ctx)) {
            Mode.NO -> true
            Mode.EN -> false
            Mode.AUTO -> Locale.getDefault().language in setOf("nb", "no", "nn")
        }
    }
}

/** Picks the English or Norwegian text. */
fun tr(en: String, no: String): String = if (Lang.no) no else en
