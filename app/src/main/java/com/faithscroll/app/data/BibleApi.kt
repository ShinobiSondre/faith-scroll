package com.faithscroll.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

data class ChapterVerse(val number: Int, val text: String)
data class Chapter(val reference: String, val verses: List<ChapterVerse>)

/** Loads a full chapter (KJV) from bible-api.com so people can read the verse in context. */
object BibleApi {
    suspend fun chapter(chapterRef: String): Chapter? = withContext(Dispatchers.IO) {
        val query = chapterRef.replace(Regex("^Psalm "), "Psalms ")
        val url = "https://bible-api.com/" + URLEncoder.encode(query, "UTF-8") + "?translation=kjv"
        val body = Http.get(url, 15_000) ?: return@withContext null
        runCatching {
            val o = JSONObject(body)
            val a = o.getJSONArray("verses")
            val verses = (0 until a.length()).map { i ->
                val v = a.getJSONObject(i)
                ChapterVerse(v.getInt("verse"), v.getString("text").trim().replace(Regex("\\s+"), " "))
            }
            Chapter(chapterRef, verses)
        }.getOrNull()
    }
}
