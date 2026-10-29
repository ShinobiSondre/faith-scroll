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
 * "What do they mean here?": asks Claude (Anthropic API) about a moment in a video, using the
 * transcript around that moment. Uses your own API key, stored only on this phone.
 */
object Ai {
    private const val MODEL = "claude-haiku-5-5"
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("ai", Context.MODE_PRIVATE)
    fun key(ctx: Context): String = prefs(ctx).getString("key", "").orEmpty()
    fun setKey(ctx: Context, k: String) = prefs(ctx).edit().putString("key", k.trim()).apply()

    data class Turn(val user: Boolean, val text: String)

    private fun system(): String {
        val language = if (Lang.no) "Norwegian (Bokmål)" else "English"
        return """You are a warm, knowledgeable Bible teacher inside FaithScroll, a Christian app.
The user paused a Christian video and wants to understand what the speaker means at that moment.
You get the video title, channel, and the transcript around the paused moment (automatic captions may contain errors).
- Explain plainly what is being said and the idea behind it, in 2–4 short paragraphs.
- When it helps, point to the Bible passages it relates to (book chapter:verse) and briefly say how.
- If the transcript is unclear or missing, say so honestly and explain what you reasonably can from the title.
- Be respectful of different Christian traditions; don't invent quotes.
- Answer in $language. Keep it under 220 words unless asked for more."""
    }

    /** Returns the answer text, or throws with a readable message. */
    suspend fun ask(ctx: Context, context: String, turns: List<Turn>): String = withContext(Dispatchers.IO) {
        val key = key(ctx)
        require(key.isNotBlank()) { "No API key" }
        val messages = JSONArray()
        turns.forEachIndexed { i, t ->
            val content = if (i == 0) "$context\n\nQuestion: ${t.text}" else t.text
            messages.put(JSONObject().put("role", if (t.user) "user" else "assistant").put("content", content))
        }
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 700)
            .put("system", system())
            .put("messages", messages)
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault("HTTP ${r.code}")
                error(msg)
            }
            val content = JSONObject(text).getJSONArray("content")
            (0 until content.length()).map { content.getJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString("\n") { it.getString("text") }.trim()
        }
    }
}
