package com.faithscroll.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A short in-app log of what subtitles, language checks and connections are doing (Me → Diagnostics). */
object Diag {
    private const val MAX = 300
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    val lines = MutableStateFlow<List<String>>(emptyList())
    // Lines that matter for troubleshooting are kept apart, so hundreds of video checks can't push them out.
    private val importantTags = setOf("ask", "asr", "whisper", "prayer", "youtube", "discover", "test", "search", "play", "ai", "tiktok", "cast")
    private val important = ArrayDeque<String>()

    @Synchronized fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        android.util.Log.d("FaithScroll", line)
        lines.value = (lines.value + line).takeLast(MAX)
        if (tag in importantTags) { important.addLast(line); while (important.size > 200) important.removeFirst() }
    }

    fun err(tag: String, msg: String, e: Throwable) =
        log(tag, "$msg: ${e.javaClass.simpleName}: ${e.message?.take(200)}")

    /** What "Copy" gives: the important lines first, then the latest of everything else. */
    @Synchronized fun text(): String =
        (if (important.isNotEmpty()) "— important —\n" + important.joinToString("\n") + "\n— latest —\n" else "") +
            lines.value.filter { it !in important }.takeLast(120).joinToString("\n")
}
