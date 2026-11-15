package com.faithscroll.app.data

import android.content.Context

/**
 * Topics you don't want in your feed. Videos, TikToks and pictures whose title, caption or
 * description is about one of them are left out. Each topic can be turned off in the Me tab, and
 * you can add your own words.
 */
object TopicFilter {
    data class Topic(val key: String, val en: String, val no: String, val pattern: Regex)

    // Words are matched as whole words, so e.g. "pride" as a sin (Proverbs 16:18) isn't caught –
    // only Pride as the event / movement.
    val topics = listOf(
        Topic("pride", "Pride / LGBT", "Pride / LHBT", Regex(
            "(?i)(\\bpride[- ]?(month|parade|flag|flagg|festival|måned|maned|week|uke|march)|\\b(oslo|bergen|world|euro)\\s?pride\\b|#pride\\b|\\bprideparade|" +
                "\\blgbt\\w*|\\blhbt\\w*|\\bqueer\\b|\\bskeiv\\w*|\\bhomofil\\w*|\\bgay\\b|\\blesbi\\w*|\\bbifil\\w*|\\bbisexual\\w*|\\bsame[- ]sex\\b|\\blikekjønn\\w*|" +
                "\\bdrag ?queen\\w*|\\bregnbuefamilie\\w*|\\bhomosexual\\w*|\\bhomoseksu\\w*)")),
        Topic("gender", "Transgender", "Kjønnsidentitet", Regex(
            "(?i)(\\btransgender\\w*|\\btranskjønn\\w*|\\btransperson\\w*|\\btrans (woman|man|kvinne|mann|people|folk|youth|ungdom|kids|barn)\\b|\\bnon[- ]?binary\\b|" +
                "\\bikke[- ]binær\\w*|\\bkjønnsidentitet\\w*|\\bkjønnsinkongruens\\w*|\\bgender[- ](identity|affirming|transition|dysphoria)\\b|\\bkjønnsbekreftende\\b|" +
                "\\bwrong body\\b|\\bfeil kropp\\b|\\bborn in the wrong\\b|\\bfødt i feil\\b|\\bpronouns?\\b|\\bpronomen\\b)")),
        Topic("religions", "Other religions", "Andre religioner", Regex(
            "(?i)(\\bislam\\w*|\\bmuslim\\w*|\\bmuhammad\\b|\\bmohammed\\b|\\bkoran\\w*|\\bquran\\w*|\\bqur'an\\b|\\ballah\\b|\\bramadan\\b|" +
                "\\bhindu\\w*|\\bkrishna\\b|\\bbuddh\\w*|\\bsikh\\w*|\\bmormon\\w*|\\bjehovah'?s witness\\w*|\\bjehovas vitne\\w*|\\bscientolog\\w*|" +
                "\\bwicca\\w*|\\bpagan\\w*|\\bhedensk\\w*|\\bnew age\\b|\\bastrolog\\w*|\\btarot\\b|\\bhoroscope\\w*|\\bhoroskop\\w*|\\bsjamanis\\w*|\\bshaman\\w*)")),
        Topic("abortion", "Abortion", "Abort", Regex("(?i)(\\babort\\w*|\\bpro[- ]?choice\\b|\\bpro[- ]?life\\b|\\bselvbestemt abort\\b)"))
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("faithscroll", Context.MODE_PRIVATE)
    fun isOn(ctx: Context, key: String) = prefs(ctx).getBoolean("topic_$key", true)
    fun setOn(ctx: Context, key: String, on: Boolean) { prefs(ctx).edit().putBoolean("topic_$key", on).apply(); version++ }
    fun extraWords(ctx: Context): String = prefs(ctx).getString("topic_extra", "")!!
    fun setExtraWords(ctx: Context, s: String) { prefs(ctx).edit().putString("topic_extra", s).apply(); version++ }

    @Volatile var version = 0
        private set
    @Volatile private var app: Context? = null
    fun init(ctx: Context) { app = ctx.applicationContext }

    /** The topic this text is about (to be left out), or null if it's fine. */
    fun hit(text: String): String? {
        val ctx = app ?: return null
        if (text.isBlank()) return null
        topics.firstOrNull { isOn(ctx, it.key) && it.pattern.containsMatchIn(text) }?.let { return it.key }
        val extra = extraWords(ctx).split(',', '\n').map { it.trim() }.filter { it.length >= 3 }
        extra.firstOrNull { Regex("(?i)\\b" + Regex.escape(it)).containsMatchIn(text) }?.let { return "word '$it'" }
        return null
    }

    fun ok(text: String) = hit(text) == null
    fun ok(v: Video) = v.id !in rejected() && !LangFilter.isBlockedChannel(v.channel) && hit(v.title + " " + v.channel) == null

    // ---- Channels you don't want ----
    fun blockedChannels(): List<String> = app?.let { prefs(it).getString("blocked_channels", "")!! }.orEmpty()
        .split(',', '\n').map { it.trim() }.filter { it.length >= 2 }
    fun blockedChannelsText(ctx: Context): String = prefs(ctx).getString("blocked_channels", "")!!
    fun setBlockedChannels(ctx: Context, s: String) { prefs(ctx).edit().putString("blocked_channels", s).apply(); version++ }

    // ---- What's actually said (transcripts) ----

    /** Words that show a video is about the faith (Norwegian and English, as speech recognition writes them). */
    val faith = Regex("(?i)\\b(jesus|jesu|kristus|kristi|christ|gud|guds|god|lord|herren|herrens|bibel\\w*|bible|skriften|scripture|bønn\\w*|be til|pray\\w*|tro(en)?|faith|kirke\\w*|menighet\\w*|church|frelse\\w*|frelser|saved|salvation|nåde|grace|ånden|hellig\\w*|holy|spirit|evangeli\\w*|gospel|amen|halleluja\\w*|synd\\w*|sin|himmel\\w*|heaven|korset|cross|disippel\\w*|apostel\\w*|profet\\w*|salme\\w*|psalm|velsign\\w*|bless\\w*|lovsang|worship|tilbe\\w*|vitnesbyrd|testimony)\\b")

    /** Videos found (from what's said) to be about an unwanted topic or not about the faith; kept on the phone. */
    private val rejectedCache = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var rejectedLoaded = false
    fun rejected(): Set<String> {
        val ctx = app ?: return emptySet()
        if (!rejectedLoaded) { rejectedLoaded = true; rejectedCache += prefs(ctx).getStringSet("content_rejected", emptySet())!! }
        return rejectedCache
    }
    private fun reject(id: String, why: String) {
        val ctx = app ?: return
        rejected(); rejectedCache += id
        prefs(ctx).edit().putStringSet("content_rejected", rejectedCache.toSet()).apply()
        Diag.log("content", "$id: left out – $why")
        LangFilter.block(id, "content") // the feed swaps it out
    }

    /**
     * Checks what's been said so far in a video (its subtitles / the phone's transcription):
     * an unwanted topic → left out at once; a good stretch of speech with no word about God,
     * Jesus, the Bible, prayer … → not a faith video, left out. Returns true if it was left out.
     */
    fun checkSpeech(id: String, cues: List<Cue>): Boolean {
        if (cues.isEmpty() || id in rejected()) return false
        val text = cues.joinToString(" ") { it.text }
        hit(text)?.let { reject(id, "says something about '$it'"); return true }
        val spokenMs = cues.maxOf { it.end } - cues.minOf { it.start }
        if (spokenMs >= 100_000 && text.length >= 600 && !faith.containsMatchIn(text)) { reject(id, "${spokenMs / 1000} s of speech without anything about the faith"); return true }
        return false
    }
}
