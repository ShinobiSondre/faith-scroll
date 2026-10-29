package com.faithscroll.app.prayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Your private prayers, kept only on this phone. */
data class Prayer(val id: Long, val text: String, val answered: Boolean)

object Journal {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("journal", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Prayer> = runCatching {
        val a = JSONArray(prefs(ctx).getString("items", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Prayer(o.getLong("id"), o.getString("text"), o.optBoolean("answered")) } }
    }.getOrDefault(emptyList()).sortedByDescending { it.id }

    private fun save(ctx: Context, l: List<Prayer>) = prefs(ctx).edit().putString(
        "items", JSONArray().apply { l.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("answered", it.answered)) } }.toString()
    ).apply()

    fun add(ctx: Context, text: String) { if (text.isNotBlank()) save(ctx, listOf(Prayer(System.currentTimeMillis(), text.trim(), false)) + all(ctx)) }
    fun toggleAnswered(ctx: Context, id: Long) = save(ctx, all(ctx).map { if (it.id == id) it.copy(answered = !it.answered) else it })
    fun remove(ctx: Context, id: Long) = save(ctx, all(ctx).filterNot { it.id == id })
}
