package com.faithscroll.app.util

/**
 * Bible text quotes. The modern Norwegian NT writes ” for both opening and closing quotes (and ’
 * inside them), and the app also put “ ” around every verse – so a verse could show four or five
 * quote marks. Here they become proper «…» / ‘…’, a single verse never shows a half quote, and the
 * outer marks are only added when the verse has none of its own.
 */
object Quotes {
    private fun closes(next: Char?) = next == null || next.isWhitespace() || next in ".,;:!?)»’”"
    private fun opens(prev: Char?) = prev == null || prev.isWhitespace() || prev in "([«‘“:—–-"

    /** ” / ' / ’ used as quotes → «» and ‘’ (an apostrophe inside a word stays as it is). */
    fun normalize(s: String): String {
        val out = StringBuilder(s.length)
        for (i in s.indices) {
            val c = s[i]
            val prev = out.lastOrNull()
            val next = s.getOrNull(i + 1)
            out.append(
                when (c) {
                    // Closing when nothing follows it (end, space, punctuation), else opening when it starts a word.
                    '”', '“' -> if (closes(next)) '»' else if (opens(prev)) '«' else '»'
                    '’', '\'' -> when {
                        prev != null && prev.isLetter() && next != null && next.isLetter() -> c // apostrophe in a word
                        closes(next) -> '’'
                        opens(prev) -> '‘'
                        else -> '’'
                    }
                    else -> c
                }
            )
        }
        return out.toString().replace(Regex("([«‘])\\s+"), "$1").replace(Regex("\\s+([»’])(?=\\s|$|[.,;:!?»])"), "$1")
    }

    /** One verse on its own: drops quote marks whose partner is in another verse. */
    fun single(s: String): String {
        var t = normalize(s).trim()
        fun strip(open: Char, close: Char) {
            var depth = 0
            val keep = BooleanArray(t.length) { true }
            val opened = ArrayList<Int>()
            t.forEachIndexed { i, c ->
                if (c == open) { opened += i; depth++ }
                else if (c == close) { if (depth == 0) keep[i] = false else { depth--; opened.removeAt(opened.size - 1) } }
            }
            opened.forEach { keep[it] = false }
            t = t.filterIndexed { i, _ -> keep[i] }
        }
        strip('«', '»'); strip('‘', '’')
        return t.replace(Regex("\\s{2,}"), " ").trim()
    }

    /** A verse shown on its own, with quote marks around it only if it has no quotes inside. */
    fun wrap(s: String, norwegian: Boolean = Lang.no): String {
        val t = single(s)
        if (t.any { it in "«»‘’“”" }) return t
        return if (norwegian) "«$t»" else "“$t”"
    }
}
