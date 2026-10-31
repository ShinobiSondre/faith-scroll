package com.faithscroll.app.data

import android.content.Context
import org.json.JSONArray
import java.util.zip.GZIPInputStream

/**
 * Norwegian Bible text, offline: the New Testament in modern bokmål from "En Levende Bok"
 * (Biblica Open, CC BY-SA 4.0), the Old Testament from the Norwegian Bible Society's 1930
 * translation (public domain – there's no free modern Norwegian Old Testament).
 */
object BibleNo {
    const val NAME = "Bibelen 1930"

    /** English book names in order (the same order as the Norwegian data). */
    private val english = listOf(
        "Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy", "Joshua", "Judges", "Ruth", "1 Samuel", "2 Samuel",
        "1 Kings", "2 Kings", "1 Chronicles", "2 Chronicles", "Ezra", "Nehemiah", "Esther", "Job", "Psalm", "Proverbs",
        "Ecclesiastes", "Song of Solomon", "Isaiah", "Jeremiah", "Lamentations", "Ezekiel", "Daniel", "Hosea", "Joel", "Amos",
        "Obadiah", "Jonah", "Micah", "Nahum", "Habakkuk", "Zephaniah", "Haggai", "Zechariah", "Malachi", "Matthew",
        "Mark", "Luke", "John", "Acts", "Romans", "1 Corinthians", "2 Corinthians", "Galatians", "Ephesians", "Philippians",
        "Colossians", "1 Thessalonians", "2 Thessalonians", "1 Timothy", "2 Timothy", "Titus", "Philemon", "Hebrews", "James", "1 Peter",
        "2 Peter", "1 John", "2 John", "3 John", "Jude", "Revelation"
    )

    fun bookIndex(english: String): Int =
        this.english.indexOf(if (english == "Psalms") "Psalm" else english)

    @Volatile private var books: List<Pair<String, List<List<String>>>>? = null

    private fun load(ctx: Context): List<Pair<String, List<List<String>>>> = books ?: synchronized(this) {
        books ?: run {
            val text = GZIPInputStream(ctx.assets.open("bible_no.json.gz")).bufferedReader().use { it.readText() }
            val a = JSONArray(text)
            (0 until a.length()).map { i ->
                val b = a.getJSONArray(i)
                val chs = b.getJSONArray(1)
                b.getString(0) to (0 until chs.length()).map { c -> chs.getJSONArray(c).let { vs -> (0 until vs.length()).map { vs.getString(it) } } }
            }
        }.also { books = it }
    }

    fun bookName(ctx: Context, index: Int): String = load(ctx).getOrNull(index)?.first.orEmpty().let { if (it == "Salmene") "Salme" else it }

    /** All verses of a chapter (1-based chapter), or null. New Testament: modern text when available. */
    fun chapter(ctx: Context, book: Int, chapter: Int): List<String>? =
        modern(ctx, book, chapter)?.takeIf { it.any { v -> v.isNotBlank() } } ?: load(ctx).getOrNull(book)?.second?.getOrNull(chapter - 1)

    /** True when this book is read from the modern translation. */
    fun isModern(ctx: Context, book: Int): Boolean = modernBooks(ctx)?.has(book.toString()) == true

    @Volatile private var nt: org.json.JSONObject? = null
    @Volatile private var ntTried = false
    private fun modernBooks(ctx: Context): org.json.JSONObject? = nt ?: synchronized(this) {
        if (!ntTried) {
            ntTried = true
            nt = runCatching {
                org.json.JSONObject(GZIPInputStream(ctx.assets.open("bible_no_nt.json.gz")).bufferedReader().use { it.readText() })
            }.getOrNull()
        }
        nt
    }
    private fun modern(ctx: Context, book: Int, chapter: Int): List<String>? {
        if (book < 39) return null
        val chs = modernBooks(ctx)?.optJSONArray(book.toString()) ?: return null
        val vs = chs.optJSONArray(chapter - 1) ?: return null
        return (0 until vs.length()).map { vs.optString(it) }
    }

    /** Where the text comes from, for the credits. */
    const val CREDITS = "Det nye testamentet: En Levende Bok (Biblica Open, CC BY-SA 4.0). Det gamle testamentet: Bibelen 1930 (Det Norske Bibelselskap)."

    /** A verse range for an English reference like "John 3:16" or "Romans 8:38-39" (not Psalms, whose numbering differs). */
    fun lookup(ctx: Context, ref: String): Triple<String, String, IntArray>? {
        val m = Regex("^(.+) (\\d+):(\\d+)(?:-(\\d+))?$").find(ref) ?: return null
        val book = m.groupValues[1]
        if (book == "Psalm" || book == "Psalms") return null
        val bi = bookIndex(book).takeIf { it >= 0 } ?: return null
        val c = m.groupValues[2].toInt(); val a = m.groupValues[3].toInt(); val b = m.groupValues[4].toIntOrNull() ?: a
        val ch = chapter(ctx, bi, c) ?: return null
        val text = (a..b).mapNotNull { ch.getOrNull(it - 1) }.joinToString(" ").trim().trimEnd(',', ';', ':')
            .replaceFirstChar { it.uppercase() }
        if (text.isBlank()) return null
        val label = "${bookName(ctx, bi)} $c,$a" + if (b != a) "–$b" else ""
        return Triple(text, label, intArrayOf(bi, c, a, b))
    }
}
