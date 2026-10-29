package com.faithscroll.app.data

import android.content.Context

/**
 * Remembers what you've already watched or seen (across app launches), so the feed
 * keeps showing new things. Only when nothing new is left does it reuse old ones,
 * starting with what you saw longest ago.
 */
object Seen {
    private const val MAX = 4000
    private var loaded = false
    private val order = LinkedHashMap<String, Long>()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("seen", Context.MODE_PRIVATE)

    @Synchronized private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        prefs(ctx).getString("ids", "")!!.split(",").filter { it.isNotBlank() }.forEach {
            val (id, t) = it.split("@").let { p -> p[0] to (p.getOrNull(1)?.toLongOrNull() ?: 0L) }
            order[id] = t
        }
    }

    @Synchronized fun mark(ctx: Context, id: String) {
        load(ctx)
        order.remove(id)
        order[id] = System.currentTimeMillis()
        while (order.size > MAX) order.remove(order.keys.first())
        prefs(ctx).edit().putString("ids", order.entries.joinToString(",") { "${it.key}@${it.value}" }).apply()
    }

    @Synchronized fun has(ctx: Context, id: String): Boolean { load(ctx); return id in order }

    /** When seen, the time; unseen items sort first. */
    @Synchronized fun age(ctx: Context, id: String): Long { load(ctx); return order[id] ?: 0L }

    /** Unseen items first; if none are left, the ones seen longest ago. */
    fun <T> freshFirst(ctx: Context, items: List<T>, id: (T) -> String, keep: Int = 20): List<T> {
        val unseen = items.filter { !has(ctx, id(it)) }
        if (unseen.size >= 3) return unseen
        return unseen + items.filter { has(ctx, id(it)) }.sortedBy { age(ctx, id(it)) }.take(keep)
    }
}
