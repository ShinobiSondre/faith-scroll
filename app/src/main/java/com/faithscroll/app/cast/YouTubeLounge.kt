package com.faithscroll.app.cast

import com.faithscroll.app.data.Diag
import com.google.android.gms.cast.framework.CastSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Plays a video in the YouTube app on a Cast TV – the way the YouTube app does it: the TV's
 * YouTube app is started over Cast, tells us its screen id, and the video is then queued on that
 * screen through YouTube's "lounge" (the TV remote service the phone app uses).
 */
object YouTubeLounge {
    const val APP_ID = "233637DE" // YouTube's Cast receiver
    private const val NS = "urn:x-cast:com.google.youtube.mdx"
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private const val BIND = "https://www.youtube.com/api/lounge/bc/bind"

    /** Returns null when the TV started the video, else what went wrong. */
    suspend fun play(s: CastSession, videoId: String, positionMs: Long): String? {
        // 1) The TV's YouTube app tells us its screen id.
        val screenId = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<String?> { c ->
                runCatching {
                    s.setMessageReceivedCallbacks(NS) { _, _, msg ->
                        val o = runCatching { JSONObject(msg) }.getOrNull()
                        if (o?.optString("type") == "mdxSessionStatus") {
                            runCatching { s.removeMessageReceivedCallbacks(NS) }
                            if (c.isActive) c.resumeWith(Result.success(o.optJSONObject("data")?.optString("screenId")))
                        }
                    }
                    s.sendMessage(NS, "{\"type\":\"getMdxSessionStatus\"}")
                }.onFailure { if (c.isActive) c.resumeWith(Result.success(null)) }
            }
        }
        if (screenId.isNullOrBlank()) return "no screen id from the TV's YouTube app".also { Diag.log("cast", "YouTube on Cast: $it") }
        return playOnScreen(screenId, videoId, positionMs)
    }

    /** Sends [videoId] to a TV's YouTube app by its screen id (works over the internet, like a linked TV). */
    suspend fun playOnScreen(screenId: String, videoId: String, positionMs: Long): String? = withContext(Dispatchers.IO) {
            runCatching {
                // 2) A lounge token for that screen.
                val tokenJson = http.newCall(Request.Builder().url("https://www.youtube.com/api/lounge/pairing/get_lounge_token_batch")
                    .post(FormBody.Builder().add("screen_ids", screenId).build()).build()).execute().use { it.body!!.string() }
                val token = JSONObject(tokenJson).getJSONArray("screens").getJSONObject(0).getString("loungeToken")
                // 3) Join the TV's session as a remote.
                val id = UUID.randomUUID().toString()
                var rid = 1000 + (Math.random() * 8000).toInt()
                fun common(b: okhttp3.HttpUrl.Builder) = b.addQueryParameter("device", "REMOTE_CONTROL").addQueryParameter("id", id)
                    .addQueryParameter("name", "FaithScroll").addQueryParameter("app", "youtube-desktop").addQueryParameter("mdx-version", "3")
                    .addQueryParameter("loungeIdToken", token).addQueryParameter("VER", "8").addQueryParameter("v", "2")
                    .addQueryParameter("CVER", "1").addQueryParameter("zx", UUID.randomUUID().toString().take(12))
                val bindUrl = common(okhttp3.HttpUrl.Builder().scheme("https").host("www.youtube.com").encodedPath("/api/lounge/bc/bind"))
                    .addQueryParameter("RID", (rid++).toString()).build()
                val bindBody = http.newCall(Request.Builder().url(bindUrl).post(FormBody.Builder().add("count", "0").build()).build())
                    .execute().use { it.body!!.string() }
                val sid = Regex("\"c\",\"([^\"]+)\"").find(bindBody)?.groupValues?.get(1) ?: error("no session (${bindBody.take(120)})")
                val gsession = Regex("\"S\",\"([^\"]+)\"").find(bindBody)?.groupValues?.get(1) ?: error("no gsessionid")
                // 4) Play the video from the right second.
                val playUrl = common(okhttp3.HttpUrl.Builder().scheme("https").host("www.youtube.com").encodedPath("/api/lounge/bc/bind"))
                    .addQueryParameter("SID", sid).addQueryParameter("gsessionid", gsession).addQueryParameter("RID", (rid++).toString()).build()
                val form = FormBody.Builder().add("count", "1").add("ofs", "0")
                    .add("req0__sc", "setPlaylist").add("req0_videoId", videoId).add("req0_videoIds", videoId)
                    .add("req0_currentTime", (positionMs / 1000).toString()).add("req0_currentIndex", "-1")
                    .add("req0_audioOnly", "false").add("req0_params", "").add("req0_playerParams", "").add("req0_listId", "").build()
                val code = http.newCall(Request.Builder().url(playUrl).post(form).build()).execute().use { it.code }
                Diag.log("cast", "YouTube on Cast: setPlaylist → HTTP $code")
                if (code in 200..299) null else "YouTube answered $code"
            }.getOrElse { Diag.err("cast", "YouTube on Cast", it); it.message ?: "error" }
        }

    // ---- TVs linked with a code ("Link with TV code" in the TV's YouTube app), kept on the phone ----

    data class Screen(val id: String, val name: String)

    private fun prefs(ctx: android.content.Context) = ctx.applicationContext.getSharedPreferences("yt_screens", android.content.Context.MODE_PRIVATE)
    fun screens(ctx: android.content.Context): List<Screen> = runCatching {
        val a = JSONArray(prefs(ctx).getString("list", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Screen(o.getString("id"), o.optString("name", "TV")) } }
    }.getOrDefault(emptyList())
    fun forget(ctx: android.content.Context, id: String) = save(ctx, screens(ctx).filter { it.id != id })
    private fun save(ctx: android.content.Context, l: List<Screen>) =
        prefs(ctx).edit().putString("list", JSONArray().apply { l.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } }.toString()).apply()

    /** Links a TV with the code its YouTube app shows. Returns the TV, or an error text. */
    suspend fun pair(ctx: android.content.Context, code: String): Pair<Screen?, String?> = withContext(Dispatchers.IO) {
        val digits = code.filter { it.isDigit() }
        if (digits.length < 10) return@withContext null to com.faithscroll.app.util.tr("Type all the digits shown on the TV.", "Skriv inn alle tallene som vises på TV-en.")
        runCatching {
            val body = http.newCall(Request.Builder().url("https://www.youtube.com/api/lounge/pairing/get_screen")
                .post(FormBody.Builder().add("pairing_code", digits).build()).build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string()
            }
            val o = JSONObject(body).getJSONObject("screen")
            val screen = Screen(o.getString("screenId"), o.optString("name").ifBlank { "TV" })
            save(ctx, screens(ctx).filter { it.id != screen.id } + screen)
            Diag.log("cast", "linked TV '${screen.name}' with a code")
            screen to null
        }.getOrElse {
            Diag.err("cast", "linking TV", it)
            null to com.faithscroll.app.util.tr("That code didn't work – check it, or get a new one on the TV.", "Den koden virket ikke – sjekk den, eller hent en ny på TV-en.")
        }
    }
}
