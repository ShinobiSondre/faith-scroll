package com.faithscroll.app.data

import android.content.Context
import org.json.JSONArray

/** A short illustrated Christian message for the feed. */
data class Message(val id: String, val text: String, val ref: String, val theme: String) {
    /** Which illustrated scene to draw behind it. */
    val scene: Int get() = Math.floorMod(id.hashCode() * 31 + theme.hashCode(), SCENES)

    companion object { const val SCENES = 6 }
}

object MessageRepository {
    @Volatile private var cache: List<Message>? = null

    fun all(ctx: Context): List<Message> = cache ?: synchronized(this) {
        cache ?: run {
            val a = JSONArray(ctx.assets.open("messages.json").bufferedReader().use { it.readText() })
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Message(o.getString("id"), o.getString("text"), o.optString("ref"), o.optString("theme", "faith"))
            }
        }.also { cache = it }
    }

    fun byId(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }
}
