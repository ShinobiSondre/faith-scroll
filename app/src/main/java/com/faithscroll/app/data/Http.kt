package com.faithscroll.app.data

import java.net.HttpURLConnection
import java.net.URL

object Http {
    fun get(url: String, timeoutMs: Int = 10_000): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "FaithScroll/1.0 (Android)")
        try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally { c.disconnect() }
    } catch (e: Exception) { null }
}
