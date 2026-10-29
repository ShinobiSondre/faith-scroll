package com.faithscroll.app.data

import android.content.Context
import com.faithscroll.app.util.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * "What do they mean here?": asks a free AI about a moment in a video, using the transcript
 * around that moment. Uses Pollinations.ai's free text API: no account, no key, but limited
 * to one question every ~30 seconds per phone.
 */
object Ai {
    private val http = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build()

    data class Turn(val user: Boolean, val text: String)

    /** Thrown when the free limit is reached; [seconds] until the next question is allowed. */
    class RateLimited(val seconds: Int) : Exception("Rate limited")

    private fun system(): String {
        val language = if (Lang.no) "Norwegian (Bokmål)" else "English"
        return """You are a warm, knowledgeable Bible teacher inside FaithScroll, a Christian app.
The user paused a Christian video and wants to understand what the speaker means at that moment.
You get the video title, channel, and the transcript around the paused moment (automatic transcripts may contain errors).
- Explain plainly what is being said and the idea behind it, in 2–4 short paragraphs.
- When it helps, point to the Bible passages it relates to (book chapter:verse) and briefly say how.
- If the transcript is unclear or missing, say so honestly and explain what you reasonably can from the title.
- Be respectful of different Christian traditions; don't invent quotes.
- Answer in $language. Keep it under 220 words unless asked for more. Plain text, no markdown headings."""
    }

    /** Returns the answer text; throws [RateLimited] or an exception with a readable message. */
    suspend fun ask(context: String, turns: List<Turn>): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system()))
        turns.forEachIndexed { i, t ->
            val content = if (i == 0) "$context\n\nQuestion: ${t.text}" else t.text
            messages.put(JSONObject().put("role", if (t.user) "user" else "assistant").put("content", content))
        }
        val body = JSONObject().put("messages", messages).put("private", true)
        val req = Request.Builder()
            .url("https://text.pollinations.ai/")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty().trim()
            if (r.code == 402 || r.code == 429) {
                throw RateLimited(r.header("Retry-After")?.toIntOrNull()?.coerceIn(1, 120) ?: 30)
            }
            if (!r.isSuccessful) error("HTTP ${r.code}")
            if (text.isBlank()) error("Empty answer")
            text
        }
    }
}
