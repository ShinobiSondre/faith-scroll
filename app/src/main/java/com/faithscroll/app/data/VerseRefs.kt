package com.faithscroll.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds Bible references people say out loud ("John 3:16", "Johannes 3, 16", "Romans chapter 8
 * verse 28", "john three sixteen") in a transcript or a ChatGPT answer.
 */
object VerseRefs {
    /** A reference: book index 0–65 (BibleNo order), chapter, verses, when it was said, and whether it was said in Norwegian. */
    data class Ref(val book: Int, val chapter: Int, val from: Int, val to: Int, val atMs: Long, val norwegian: Boolean) {
        val key get() = "$book:$chapter:$from-$to"
    }

    // Spoken / written names for each book, English and Norwegian (in BibleNo's order).
    private val names: List<List<String>> = listOf(
        listOf("genesis", "1 mosebok", "første mosebok", "1 mos"), listOf("exodus", "2 mosebok", "andre mosebok", "2 mos"),
        listOf("leviticus", "3 mosebok", "tredje mosebok", "3 mos"), listOf("numbers", "4 mosebok", "fjerde mosebok", "4 mos"),
        listOf("deuteronomy", "5 mosebok", "femte mosebok", "5 mos"), listOf("joshua", "josva", "jos"), listOf("judges", "dommerne", "dommernes bok"),
        listOf("ruth", "rut"), listOf("1 samuel", "1 sam", "første samuelsbok", "1 samuelsbok"), listOf("2 samuel", "2 sam", "andre samuelsbok", "2 samuelsbok"),
        listOf("1 kings", "1 kongebok", "første kongebok"), listOf("2 kings", "2 kongebok", "andre kongebok"),
        listOf("1 chronicles", "1 krønikebok", "første krønikebok"), listOf("2 chronicles", "2 krønikebok", "andre krønikebok"),
        listOf("ezra", "esra"), listOf("nehemiah", "nehemja"), listOf("esther", "ester"), listOf("job"),
        listOf("psalms", "psalm", "salmene", "salme", "salmenes bok", "salm", "sal"), listOf("proverbs", "ordspråkene", "salomos ordspråk", "ordspr"),
        listOf("ecclesiastes", "forkynneren", "fork"), listOf("song of solomon", "song of songs", "høysangen"),
        listOf("isaiah", "jesaja", "jes"), listOf("jeremiah", "jeremia", "jer"), listOf("lamentations", "klagesangene"),
        listOf("ezekiel", "esekiel"), listOf("daniel", "dan"), listOf("hosea"), listOf("joel"), listOf("amos"), listOf("obadiah", "obadja"),
        listOf("jonah", "jona"), listOf("micah", "mika"), listOf("nahum"), listOf("habakkuk"), listOf("zephaniah", "sefanja"),
        listOf("haggai"), listOf("zechariah", "sakarja"), listOf("malachi", "malaki"),
        listOf("matthew", "matteus", "matt", "matt."), listOf("mark", "markus", "mark."), listOf("luke", "lukas", "luk"), listOf("john", "johannes", "joh"),
        listOf("acts", "apostlenes gjerninger", "apg"), listOf("romans", "romerne", "romerbrevet", "rom"),
        listOf("1 corinthians", "1 korinter", "1 korinterbrev", "første korinterbrev", "1 kor"), listOf("2 corinthians", "2 korinter", "2 korinterbrev", "andre korinterbrev", "2 kor"),
        listOf("galatians", "galaterne", "galaterbrevet", "gal"), listOf("ephesians", "efeserne", "efeserbrevet", "ef"),
        listOf("philippians", "filipperne", "filipperbrevet", "fil"), listOf("colossians", "kolosserne", "kolosserbrevet", "kol"),
        listOf("1 thessalonians", "1 tessaloniker", "1 tessalonikerbrev"), listOf("2 thessalonians", "2 tessaloniker", "2 tessalonikerbrev"),
        listOf("1 timothy", "1 timoteus", "1 tim"), listOf("2 timothy", "2 timoteus", "2 tim"), listOf("titus"), listOf("philemon", "filemon"),
        listOf("hebrews", "hebreerne", "hebreerbrevet", "hebr"), listOf("james", "jakob", "jakobs brev", "jak"),
        listOf("1 peter", "1 peters brev", "første peters brev", "1 pet"), listOf("2 peter", "2 peters brev", "andre peters brev", "2 pet"),
        listOf("1 john", "1 johannes", "1 johannesbrev", "første johannesbrev", "1 joh"), listOf("2 john", "2 johannes", "2 johannesbrev"),
        listOf("3 john", "3 johannes", "3 johannesbrev"), listOf("jude", "judas"), listOf("revelation", "revelations", "åpenbaringen", "johannes' åpenbaring", "åp")
    )
    private val norwegianNames: Set<String> = names.flatMap { it.drop(1) }.filter { n -> names.none { it.first() == n } }.toSet() - setOf("job", "joel", "amos", "nahum", "haggai", "titus", "daniel", "hosea", "habakkuk")

    // Spoken ordinals before a book ("first John", "andre Korinterbrev") → "1 ", "2 ", "3 ".
    private val ordinals = mapOf(
        "first" to "1", "1st" to "1", "second" to "2", "2nd" to "2", "third" to "3", "3rd" to "3",
        "første" to "1", "andre" to "2", "annen" to "2", "tredje" to "3", "1." to "1", "2." to "2", "3." to "3"
    )

    private val numberWords = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90,
        "en" to 1, "ett" to 1, "tre" to 3, "fire" to 4, "fem" to 5, "seks" to 6, "sju" to 7, "syv" to 7, "åtte" to 8, "ni" to 9,
        "ti" to 10, "elleve" to 11, "tolv" to 12, "tretten" to 13, "fjorten" to 14, "femten" to 15, "seksten" to 16, "sytten" to 17,
        "atten" to 18, "nitten" to 19, "tjue" to 20, "tjueen" to 21, "tjueto" to 22, "tjuetre" to 23, "tjuefire" to 24, "tjuefem" to 25,
        "tjueseks" to 26, "tjuesju" to 27, "tjueåtte" to 28, "tjueni" to 29, "tretti" to 30, "førti" to 40, "femti" to 50
    )

    /** Turns spoken numbers into digits ("twenty eight" → "28", "three sixteen" → "3 16"). */
    private fun digits(s: String): String {
        val words = s.split(Regex("(?<=\\s)|(?=\\s)"))
        val out = StringBuilder()
        var i = 0
        while (i < words.size) {
            val w = words[i].lowercase().trim(',', '.', '!', '?')
            val n = numberWords[w]
            if (n != null) {
                var v = n
                // "twenty eight" → 28
                if (n % 10 == 0 && n >= 20 && i + 2 < words.size) {
                    val next = numberWords[words[i + 2].lowercase().trim(',', '.', '!', '?')]
                    if (next != null && next < 10) { v += next; i += 2 }
                }
                out.append(v)
            } else out.append(words[i])
            i++
        }
        return out.toString()
    }

    private val pattern: Regex by lazy {
        // Every name, also without its number ("2. Korinterbrev" = "2." + "korinterbrev"), and with a dot after abbreviations.
        val all = (names.flatten() + names.flatten().map { it.replace(Regex("^[123] "), "") })
            .distinct().sortedByDescending { it.length }.joinToString("|") { Regex.escape(it).let { n -> if (it.length <= 5) "$n\\.?" else n } }
        Regex(
            "(?i)(?<![\\p{L}\\d])((?:(?:first|second|third|1st|2nd|3rd|første|andre|annen|tredje|[123]\\.?)\\s*)?(?:$all))" +
                "(?![\\p{L}])\\s*(?:chapter|kapittel|kap\\.?)?\\s*(\\d{1,3})" +
                "(?:(?:\\s*[:,.]\\s*|\\s+(?:verse|verses|vers|v\\.)\\s*|\\s+)(\\d{1,3})(?:\\s*(?:-|–|—|to|til|through)\\s*(\\d{1,3}))?)?(?![\\d\\p{L}])"
        )
    }

    private fun bookOf(raw: String): Pair<Int, Boolean>? {
        var s = raw.lowercase().replace(Regex("\\s+"), " ").trim()
        val first = s.substringBefore(' ')
        ordinals[first]?.let { s = it + " " + s.substringAfter(' ') }
        s = s.replace(Regex("^([123])\\.?\\s*(?=\\p{L})"), "$1 ")
        val i = names.indexOfFirst { s in it }
        if (i < 0) return null
        return i to (s in norwegianNames)
    }

    /** All references in [text] (said at [atMs]). */
    fun inText(text: String, atMs: Long = 0L): List<Ref> = pattern.findAll(digits(text)).mapNotNull { parse(it, atMs) }.toList()

    /** References in written text (a ChatGPT answer) with where they are, so each can be tapped. */
    fun hits(text: String): List<Pair<IntRange, Ref>> = pattern.findAll(text).mapNotNull { m -> parse(m, 0L)?.let { m.range to it } }.toList()

    // Book names that are also ordinary words or short abbreviations: only with a verse ("Job 1:21"), never chapter alone.
    private val needsVerse = setOf("job", "mark", "acts", "jude", "numbers", "judges", "ruth", "amos", "joel", "titus", "james", "daniel",
        "john", "sal", "rom", "fil", "kol", "gal", "ef", "apg", "jes", "jer", "dan", "luk", "joh", "matt", "jak", "hebr", "fork", "ordspr",
        "åp", "jos", "pet", "tim", "kor", "sam", "mos")

    private fun parse(m: MatchResult, atMs: Long): Ref? {
        val (book, no) = bookOf(m.groupValues[1].trimEnd('.')) ?: return null
        val c = m.groupValues[2].toInt()
        val a = m.groupValues[3].toIntOrNull()
        if (c !in 1..150) return null
        val bare = m.groupValues[1].lowercase().trimEnd('.').replace(Regex("^\\S*\\d\\.?\\s*|^(first|second|third|første|andre|annen|tredje)\\s+"), "")
        if (a == null) {
            // A whole chapter ("Efeserne 6", "Romans chapter 8").
            val said = m.value.lowercase()
            if (bare in needsVerse && !Regex("chapter|kapittel|kap").containsMatchIn(said)) return null
            return Ref(book, c, 0, 0, atMs, no)
        }
        val b = m.groupValues[4].toIntOrNull()?.takeIf { it >= a && it - a <= 40 } ?: a
        if (a !in 1..176) return null
        // Common words that are also book names need something that looks like a reference.
        if (book in setOf(17, 43, 3) && !Regex("[:,.]|verse|vers|chapter|kapittel").containsMatchIn(m.value.lowercase())) return null
        return Ref(book, c, a, b, atMs, no)
    }

    /**
     * References said in a transcript, with when each was said. Neighbouring lines are read together,
     * since "John 3" and "16" often end up in different caption lines.
     */
    fun inCues(cues: List<Cue>): List<Ref> {
        if (cues.isEmpty()) return emptyList()
        val found = ArrayList<Ref>()
        val conv = StringBuilder(); val starts = ArrayList<Pair<Int, Long>>()
        for (c in cues) { starts += conv.length to c.start; conv.append(digits(c.text.replace('\n', ' '))).append(' ') }
        for (m in pattern.findAll(conv)) {
            val at = starts.lastOrNull { it.first <= m.range.first }?.second ?: 0L
            found += inText(m.value, at)
        }
        // The same verse said twice in a row is one mention.
        return found.distinctBy { it.key + "@" + it.atMs / 30_000 }
    }

    /** How the reference is written in the app's language. */
    fun label(ctx: Context, r: Ref, norwegian: Boolean): String {
        val book = if (norwegian) BibleNo.bookName(ctx, r.book) else englishName(r.book)
        val sep = if (norwegian) "," else ":"
        if (r.from == 0) return "$book ${r.chapter}"
        return "$book ${r.chapter}$sep${r.from}" + if (r.to != r.from) "–${r.to}" else ""
    }

    private fun englishName(i: Int): String = names[i].first().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        .replace("Psalms", "Psalm").replace("Of", "of")

    /**
     * The verse text. In Norwegian (Bibelen 1930 / En Levende Bok for the NT) when the app is in
     * Norwegian, otherwise KJV. A psalm said in the other language keeps its own numbering's text.
     */
    suspend fun text(ctx: Context, r: Ref, norwegian: Boolean): String? = withContext(Dispatchers.IO) {
        val psalmMismatch = r.book == 18 && r.norwegian != norwegian
        val useNo = if (psalmMismatch) r.norwegian else norwegian
        if (useNo) {
            BibleNo.chapter(ctx, r.book, r.chapter)?.let { ch ->
                if (r.from == 0) ch.withIndex().joinToString("  ") { (i, t) -> "${i + 1} $t" }.ifBlank { null } // whole chapter, numbered
                else (r.from..r.to).mapNotNull { ch.getOrNull(it - 1) }.joinToString(" ").trim().ifBlank { null }
            }
        } else {
            BibleApi.chapter("${englishName(r.book)} ${r.chapter}")?.verses?.let { vs ->
                if (r.from == 0) vs.joinToString("  ") { "${it.number} ${it.text}" } else vs.filter { it.number in r.from..r.to }.joinToString(" ") { it.text }
            }?.ifBlank { null }
        }
    }
}
