package com.faithscroll.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything you've watched (kept on the phone): where you stopped in each video, whether you
 * finished it, and its title and description so the history can be searched.
 */
object WatchHistory {
    data class Entry(
        val video: Video,
        val positionMs: Long,
        val durationMs: Long,
        val finished: Boolean,
        val lastWatched: Long,
        val description: String = ""
    ) {
        /** 0–1, how far you got. */
        val progress: Float get() = if (finished) 1f else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    private const val MAX = 1500
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<Entry>>(emptyList())
    /** Newest first. */
    val items: StateFlow<List<Entry>> = _items
    @Volatile private var loaded = false
    private var lastSave = 0L

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "watch_history.json")

    @Synchronized fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        _items.value = runCatching {
            val a = JSONArray(file(ctx).readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Entry(
                    Video(o.getString("id"), o.optString("title"), o.optString("channel"), o.optString("kind", Video.KIND_STORY),
                        o.optString("direct"), o.optString("thumb"), o.optString("link")),
                    o.optLong("pos"), o.optLong("dur"), o.optBoolean("done"), o.optLong("at"), o.optString("desc")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 4_000) return
        lastSave = now
        val snapshot = _items.value
        io.launch {
            val a = JSONArray()
            snapshot.forEach { e ->
                a.put(JSONObject().put("id", e.video.id).put("title", e.video.title).put("channel", e.video.channel).put("kind", e.video.kind)
                    .put("direct", e.video.direct).put("thumb", e.video.thumb).put("link", e.video.link)
                    .put("pos", e.positionMs).put("dur", e.durationMs).put("done", e.finished).put("at", e.lastWatched).put("desc", e.description))
            }
            runCatching { file(ctx).writeText(a.toString()) }
        }
    }

    @Synchronized private fun update(ctx: Context, id: String, force: Boolean = false, change: (Entry?) -> Entry?) {
        load(ctx)
        val list = _items.value
        val old = list.firstOrNull { it.video.id == id }
        val new = change(old) ?: return
        _items.value = (listOf(new) + list.filter { it.video.id != id }).take(MAX)
        save(ctx, force)
    }

    /** Called every few seconds while a video plays: remembers the spot (and that you've seen it). */
    fun record(ctx: Context, video: Video, positionMs: Long, durationMs: Long, force: Boolean = false) {
        if (positionMs < 3_000) return
        Seen.mark(ctx, video.id)
        update(ctx, video.id, force) { old ->
            // Near the end counts as finished; next time it starts from the top.
            val done = durationMs > 0 && positionMs > durationMs - 8_000
            Entry(video, if (done) 0L else positionMs, durationMs.takeIf { it > 0 } ?: old?.durationMs ?: 0L,
                done || (old?.finished == true && positionMs < 3_000), System.currentTimeMillis(), old?.description.orEmpty())
        }
        if (_items.value.firstOrNull()?.description.isNullOrBlank()) fillDescription(ctx, video.id)
    }

    fun finished(ctx: Context, id: String) = update(ctx, id, force = true) { old -> old?.copy(positionMs = 0L, finished = true, lastWatched = System.currentTimeMillis()) }

    /** Where to continue this video, if you stopped part-way. */
    fun resumeAt(ctx: Context, id: String): Long? {
        load(ctx)
        val e = _items.value.firstOrNull { it.video.id == id } ?: return null
        return e.positionMs.takeIf { !e.finished && it > 5_000 }
    }

    /** The video you were last watching, if you left it part-way in the last few days. */
    fun lastUnfinished(ctx: Context): Entry? {
        load(ctx)
        return _items.value.firstOrNull()?.takeIf { !it.finished && it.positionMs > 10_000 && System.currentTimeMillis() - it.lastWatched < 3 * 86_400_000L }
    }

    fun remove(ctx: Context, id: String) { load(ctx); _items.value = _items.value.filter { it.video.id != id }; save(ctx, true) }
    fun clear(ctx: Context) { _items.value = emptyList(); save(ctx, true) }

    /** Searches titles, channels and descriptions of what you've watched (all words must match). */
    fun search(ctx: Context, query: String): List<Entry> {
        load(ctx)
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return _items.value
        return _items.value.filter { e ->
            val hay = (e.video.title + " " + e.video.channel + " " + e.description).lowercase()
            words.all { it in hay }
        }
    }

    private val fetching = mutableSetOf<String>()
    private fun fillDescription(ctx: Context, id: String) {
        if (!fetching.add(id)) return
        io.launch {
            val desc = runCatching { StreamResolver.cached(id)?.description?.takeIf { it.isNotBlank() } ?: WatchPage.info(id)?.description }.getOrNull().orEmpty()
            if (desc.isNotBlank()) update(ctx, id, force = true) { it?.copy(description = desc.take(2000)) }
        }
    }
}
