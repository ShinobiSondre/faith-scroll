package com.faithscroll.app.data

import com.faithscroll.app.util.Lang
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Keeps content in the app's language: English, or Norwegian + English when the app is in
 * Norwegian. Titles are checked on the phone (offline) before anything reaches the feed.
 */
object LangFilter {
    private val client by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.45f).build())
    }
    private val cache = ConcurrentHashMap<String, Boolean>()

    private fun allowed(): Set<String> = if (Lang.no) setOf("en", "no", "nb", "nn", "da", "sv") else setOf("en")

    /** Blocking; call off the main thread. */
    fun ok(text: String): Boolean {
        val key = (if (Lang.no) "no|" else "en|") + text
        cache[key]?.let { return it }
        val result = check(text)
        cache[key] = result
        return result
    }

    private fun check(raw: String): Boolean {
        // Drop links, hashtags, @names and emoji before judging.
        val text = raw.replace(Regex("https?://\\S+|#\\S+|@\\S+"), " ")
        var latin = 0; var other = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp)) {
                when (Character.UnicodeScript.of(cp)) {
                    Character.UnicodeScript.LATIN -> latin++
                    Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> Unit
                    else -> other++
                }
            }
            i += Character.charCount(cp)
        }
        // Another alphabet (Hindi, Arabic, Russian, Korean…) → not for this feed.
        if (other > 2 && other * 3 > latin) return false
        if (latin < 12) return true // too short to tell; give it the benefit of the doubt
        val tag = runCatching { Tasks.await(client.identifyLanguage(text), 2, TimeUnit.SECONDS) }.getOrNull() ?: return true
        return tag == "und" || tag in allowed()
    }

    suspend fun videos(list: List<Video>): List<Video> = withContext(Dispatchers.Default) { list.filter { ok(it.title) } }
    suspend fun pins(list: List<Pin>): List<Pin> = withContext(Dispatchers.Default) { list.filter { it.title.isBlank() || ok(it.title) } }

    /** YouTube search language/country for discovery. */
    val searchLanguage: Pair<String, String> get() = if (Lang.no) "nb" to "NO" else "en" to "US"
}
