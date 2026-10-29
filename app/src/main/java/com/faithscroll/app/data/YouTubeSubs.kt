package com.faithscroll.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipInputStream

/** A channel you're subscribed to on YouTube. Favourites show up in your feed. */
data class SubChannel(val id: String, val title: String, val favourite: Boolean)

/**
 * Your YouTube subscriptions, from Google sign-in (YouTube Data API, read-only) or a Google
 * Takeout export. Stored only on this phone.
 */
object YouTubeSubs {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("yt_subs", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<SubChannel> = runCatching {
        val a = JSONArray(prefs(ctx).getString("list", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> SubChannel(o.getString("id"), o.optString("title"), o.optBoolean("fav")) } }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, l: List<SubChannel>) = prefs(ctx).edit().putString(
        "list", JSONArray().apply { l.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("fav", it.favourite)) } }.toString()
    ).apply()

    fun favourites(ctx: Context): List<SubChannel> = all(ctx).filter { it.favourite }
    private var favNames: Set<String>? = null
    fun isFavouriteName(ctx: Context, name: String): Boolean =
        (favNames ?: favourites(ctx).map { it.title }.toSet().also { favNames = it }).contains(name)

    fun setFavourite(ctx: Context, id: String, fav: Boolean) {
        save(ctx, all(ctx).map { if (it.id == id) it.copy(favourite = fav) else it }); favNames = null
    }
    fun setAllFavourite(ctx: Context, fav: Boolean) { save(ctx, all(ctx).map { it.copy(favourite = fav) }); favNames = null }

    /** Adds new channels (keeps favourite choices for ones already known). */
    private fun merge(ctx: Context, found: List<Pair<String, String>>) {
        val old = all(ctx).associateBy { it.id }
        save(ctx, found.distinctBy { it.first }.map { (id, t) -> old[id]?.copy(title = t) ?: SubChannel(id, t, false) }
            .sortedBy { it.title.lowercase() })
        favNames = null
    }

    fun clear(ctx: Context) { save(ctx, emptyList()); favNames = null }

    /** Reads subscriptions with a Google access token (scope youtube.readonly). Returns how many. */
    suspend fun fetchWithToken(ctx: Context, token: String): Int = withContext(Dispatchers.IO) {
        val http = OkHttpClient()
        val found = mutableListOf<Pair<String, String>>()
        var page: String? = null
        do {
            val url = "https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&mine=true&maxResults=50&order=alphabetical" +
                (page?.let { "&pageToken=$it" } ?: "")
            val body = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (!r.isSuccessful) error(runCatching { JSONObject(t).getJSONObject("error").getString("message") }.getOrDefault("HTTP ${r.code}"))
                t
            }
            val o = JSONObject(body)
            val items = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val sn = items.getJSONObject(i).getJSONObject("snippet")
                found += sn.getJSONObject("resourceId").getString("channelId") to sn.optString("title")
            }
            page = o.optString("nextPageToken").ifBlank { null }
        } while (page != null && found.size < 2000)
        merge(ctx, found)
        found.size
    }

    /**
     * Imports Google Takeout's subscriptions.csv (or the whole Takeout .zip).
     * Columns: Channel Id, Channel Url, Channel Title.
     */
    suspend fun importTakeout(ctx: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Can't open file")
        val csv = if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var text: String? = null
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name.endsWith(".csv") && (e.name.contains("subscri", true) || e.name.contains("abonnement", true))) {
                        text = zip.readBytes().toString(Charsets.UTF_8); break
                    }
                }
                text ?: error("No subscriptions.csv in the zip")
            }
        } else bytes.toString(Charsets.UTF_8)

        val found = csv.lineSequence().drop(1).mapNotNull { line ->
            val cols = splitCsv(line)
            val id = cols.getOrNull(0)?.trim().orEmpty()
            if (!id.startsWith("UC")) null else id to (cols.getOrNull(2)?.trim().orEmpty().ifBlank { id })
        }.toList()
        if (found.isEmpty()) error("No channels found in that file")
        merge(ctx, found)
        found.size
    }

    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder(); var q = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                c == ',' && !q -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
