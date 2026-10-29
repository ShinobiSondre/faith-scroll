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

    /** Is this spoken language OK for the feed? Unknown counts as OK. */
    fun spokenOk(lang: String): Boolean {
        val l = lang.lowercase().substringBefore('-')
        return l.isBlank() || l in allowed()
    }

    /** Videos found to be in another language (from their audio); the feed swaps them out. */
    val blocked = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    fun block(id: String, lang: String) {
        if (id in blocked.value) return
        Diag.log("lang", "blocked $id: spoken '$lang'")
        blocked.value = blocked.value + id
    }

    // Common English words: a title with two of these is English, whatever the detector guesses.
    private val english = Regex(
        "\\b(the|and|of|to|you|your|is|in|for|with|how|why|what|when|who|god|jesus|christ|lord|sermon|faith|prayer|pray|love|grace|hope|bible|church|my|me|we|our|be|are|this|that|from|on|it|life|story|testimony|peace|fear|heart|word|day|will|can|not|do|about)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Channels known to be English (the built-in ones); their titles are never rejected. */
    private val trusted = setOf(
        "I Am Second", "700 Club Testimonies", "BibleProject", "Desiring God", "Elevation Church",
        "In Touch Ministries", "Gospel in Life", "Jesus Shorts", "Christian Daily Devotional", "Our Daily Bread"
    )

    // Common Spanish / Portuguese words: two or more in a title is a strong sign.
    private val romance = Regex(
        "\\b(el|los|las|del|que|dios|señor|senhor|jesús|cristo|testimonio|testemunho|iglesia|igreja|oración|oração|oracion|palabra|palavra|cómo|como|para|con|una|uno|est[aá]|mi|tu|su|vida|amor|fe|hermanos|irmãos|predica|prédica|pregação|culto|alabanza|louvor|mensaje|mensagem|hoy|hoje|nunca|siempre|sempre)\\b",
        RegexOption.IGNORE_CASE
    )

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
        val romanceHits = romance.findAll(text).count()
        if (romanceHits >= 2) { Diag.log("lang", "rejected (es/pt words): ${raw.take(60)}"); return false }
        if (english.findAll(text).count() >= 2) return true
        if (latin < 12) return true // too short to tell; give it the benefit of the doubt
        // Second opinion: accept if any allowed language is a plausible reading (short titles fool detectors).
        val options = runCatching { Tasks.await(client.identifyPossibleLanguages(text), 2, TimeUnit.SECONDS) }.getOrNull()
            ?: return true
        val best = options.maxByOrNull { it.confidence }?.languageTag ?: "und"
        val ok = best == "und" || best in allowed() || options.any { it.languageTag in allowed() && it.confidence >= 0.12f }
        val tag = best
        if (!ok) Diag.log("lang", "rejected ($tag): ${raw.take(60)}")
        return ok
    }

    // Words that only (or mostly) Norwegian uses – tells it apart from Danish/Swedish.
    private val norwegianOnly = Regex(
        "\\b(hva|hvem|hvorfor|bønn|bønner|preken|vitnesbyrd|kirka|ikkje|kva|korleis|eg|frelst|tilgivelse|hjerte|kjærlighet|kjærleik|herren|ånd|ånden|nåde|også|veldig|møte|gudstjeneste|menigheten|lovsang|andakt|fortelling|livet mitt|jeg|mitt|forandret|forandra|helbredet)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Spoken language guessed from a video's title and description; null if unsure. Blocking. */
    fun spokenFromText(text: String): String? {
        val clean = text.replace(Regex("https?://\\S+|#\\S+|@\\S+"), " ").take(1500)
        val nor = norwegianOnly.findAll(clean).count()
        val eng = english.findAll(clean).count()
        val guess = detect(clean)
        return when {
            guess == "no" -> "no"
            guess in setOf("da", "sv") && nor >= 2 -> "no"
            guess == null && nor >= 2 && nor > eng / 2 -> "no"
            guess == "en" || (guess == null && eng >= 3 && nor == 0) -> "en"
            romance.findAll(clean).count() >= 2 -> "es"
            else -> guess
        }
    }

    /** Most likely language of a longer text (e.g. captions), or null if unsure. Blocking. */
    fun detect(text: String): String? {
        if (text.length < 40) return null
        val tag = runCatching { Tasks.await(client.identifyLanguage(text.take(1500)), 3, TimeUnit.SECONDS) }.getOrNull() ?: return null
        if (tag == "und") return null
        return tag.substringBefore('-').let { if (it == "nb" || it == "nn") "no" else it }
    }

    suspend fun videos(list: List<Video>): List<Video> = withContext(Dispatchers.Default) {
        list.filter { it.channel in trusted || com.faithscroll.app.data.YouTubeSubsCheck.isFav(it.channel) || ok(it.title) }
    }
    suspend fun pins(list: List<Pin>): List<Pin> = withContext(Dispatchers.Default) { list.filter { it.title.isBlank() || ok(it.title) } }

    /** YouTube search language/country for discovery. */
    val searchLanguage: Pair<String, String> get() = if (Lang.no) "nb" to "NO" else "en" to "US"
}
