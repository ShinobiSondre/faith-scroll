package com.faithscroll.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

object Prefs {
    private fun p(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("faithscroll", Context.MODE_PRIVATE)

    fun set(ctx: Context, key: String): Set<String> = p(ctx).getStringSet(key, emptySet())!!.toSet()
    fun putSet(ctx: Context, key: String, s: Set<String>) = p(ctx).edit().putStringSet(key, s).apply()

    fun savedPins(ctx: Context): List<Pin> = runCatching {
        val a = JSONArray(p(ctx).getString("saved_pins", "[]"))
        (0 until a.length()).map { Pin.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    fun setSavedPins(ctx: Context, l: List<Pin>) =
        p(ctx).edit().putString("saved_pins", JSONArray().apply { l.forEach { put(it.toJson()) } }.toString()).apply()

    fun savedVerses(ctx: Context): Set<String> = p(ctx).getStringSet("saved_verses", emptySet())!!.toSet()
    fun setSavedVerses(ctx: Context, s: Set<String>) = p(ctx).edit().putStringSet("saved_verses", s).apply()

    fun likedVerses(ctx: Context): Set<String> = p(ctx).getStringSet("liked_verses", emptySet())!!.toSet()
    fun setLikedVerses(ctx: Context, s: Set<String>) = p(ctx).edit().putStringSet("liked_verses", s).apply()

    fun savedVideos(ctx: Context): List<Video> {
        val raw = p(ctx).getString("saved_videos", "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Video(o.getString("id"), o.optString("title"), o.optString("channel"), o.optString("kind", Video.KIND_STORY),
                    o.optString("direct"), o.optString("thumb"), o.optString("link"))
            }
        }.getOrDefault(emptyList())
    }
    fun setSavedVideos(ctx: Context, list: List<Video>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel).put("kind", it.kind)
            .put("direct", it.direct).put("thumb", it.thumb).put("link", it.link)) }
        p(ctx).edit().putString("saved_videos", a.toString()).apply()
    }

    // Widget
    fun widgetOpacity(ctx: Context): Int = p(ctx).getInt("widget_opacity", 35)
    fun setWidgetOpacity(ctx: Context, v: Int) = p(ctx).edit().putInt("widget_opacity", v.coerceIn(0, 100)).apply()
    fun widgetLightText(ctx: Context): Boolean = p(ctx).getBoolean("widget_light_text", true)
    fun setWidgetLightText(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("widget_light_text", v).apply()
    fun widgetAutoChange(ctx: Context): Boolean = p(ctx).getBoolean("widget_auto", true)
    fun setWidgetAutoChange(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("widget_auto", v).apply()
    fun widgetRef(ctx: Context): String? = p(ctx).getString("widget_ref", null)
    fun setWidgetRef(ctx: Context, ref: String) = p(ctx).edit().putString("widget_ref", ref).apply()

    // Reading streak
    data class Streak(val current: Int, val best: Int, val totalDays: Int)

    fun recordOpen(ctx: Context): Streak {
        val prefs = p(ctx)
        val today = LocalDate.now().toEpochDay()
        val last = prefs.getLong("last_day", -1)
        var current = prefs.getInt("streak", 0)
        var total = prefs.getInt("total_days", 0)
        if (last != today) {
            current = if (last == today - 1) current + 1 else 1
            total += 1
        }
        val best = maxOf(prefs.getInt("best_streak", 0), current)
        prefs.edit().putLong("last_day", today).putInt("streak", current)
            .putInt("best_streak", best).putInt("total_days", total).apply()
        return Streak(current, best, total)
    }
}
