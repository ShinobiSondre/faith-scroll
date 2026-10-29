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

    @Synchronized fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        android.util.Log.d("FaithScroll", line)
        lines.value = (lines.value + line).takeLast(MAX)
    }

    fun err(tag: String, msg: String, e: Throwable) =
        log(tag, "$msg: ${e.javaClass.simpleName}: ${e.message?.take(200)}")

    fun text(): String = lines.value.joinToString("\n")
}
