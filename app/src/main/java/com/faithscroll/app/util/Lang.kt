package com.faithscroll.app.util

import android.content.Context
import java.util.Locale

/** App language: follows the phone (Norwegian or English), or a choice made in Me. */
object Lang {
    @Volatile var no: Boolean = false
        private set
    /**
     * The languages videos may be in – one or several at once (Norsk, English, Svenska, Dansk).
     * Separate from the app's own language ([no]).
     */
    @Volatile var content: Set<String> = setOf("en")
        private set
    /** The languages that can be picked for videos. */
    val contentChoices = listOf("no" to "Norsk", "en" to "English", "sv" to "Svenska", "da" to "Dansk")
    fun setContent(ctx: Context, langs: Set<String>) {
        val l = langs.ifEmpty { setOf(if (no) "no" else "en") }
        prefs(ctx).edit().putString("content", l.joinToString(",")).apply(); content = l
    }
    /** Is a video in this language wanted? ("nb"/"nn" count as Norwegian.) */
    fun wants(lang: String): Boolean = norm(lang) in content
    fun norm(lang: String) = lang.lowercase().substringBefore('-').let { if (it == "nb" || it == "nn" || it == "nob" || it == "nno") "no" else it }
    /** Only Norwegian videos (then speech is always Norwegian). */
    val onlyNorwegian: Boolean get() = content == setOf("no")

    /** Kept for older code: Norwegian app with English videos as well. */
    val alsoEnglish: Boolean get() = no && "en" in content
    fun setAlsoEnglish(ctx: Context, on: Boolean) = setContent(ctx, if (on) content + "en" else content - "en")

    enum class Mode { AUTO, EN, NO }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("lang", Context.MODE_PRIVATE)
    fun mode(ctx: Context): Mode = runCatching { Mode.valueOf(prefs(ctx).getString("mode", "EN")!!) }.getOrDefault(Mode.EN) // English until you choose Norwegian

    fun setMode(ctx: Context, m: Mode) {
        prefs(ctx).edit().putString("mode", m.name).apply()
        init(ctx)
    }

    fun init(ctx: Context) {
        no = when (mode(ctx)) {
            Mode.NO -> true
            Mode.EN -> false
            Mode.AUTO -> Locale.getDefault().language in setOf("nb", "no", "nn")
        }
        // Video languages: your choice, or (first time) the app language – plus English if you had "also English" on.
        content = prefs(ctx).getString("content", null)?.split(',')?.filter { it.isNotBlank() }?.toSet()?.takeIf { it.isNotEmpty() }
            ?: if (no) setOf("no") + (if (prefs(ctx).getBoolean("also_en", false)) setOf("en") else emptySet()) else setOf("en")
    }
}

/** Picks the English or Norwegian text. */
fun tr(en: String, no: String): String = if (Lang.no) no else en
