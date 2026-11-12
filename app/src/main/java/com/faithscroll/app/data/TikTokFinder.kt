package com.faithscroll.app.data

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Finds inspiring Bible / Christian TikToks. TikTok has no open search, but its public topic pages
 * (tiktok.com/discover/…) list videos once the page has run – so they're opened in a hidden browser
 * in the background and the video links are read from the page. Results are kept for 12 hours.
 */
object TikTokFinder {
    val found = MutableStateFlow<List<TikTok>>(emptyList())

    private val english = listOf(
        "bible-verse-of-the-day", "bible-verses-for-encouragement", "christian-motivation", "bible-verses-about-strength",
        "christian-encouragement", "jesus-loves-you", "god-is-good", "faith-over-fear", "bible-verses-about-love",
        "powerful-prayer", "christian-testimony", "worship-songs"
    )
    // Norwegian topic pages that exist and have videos (checked in a real browser).
    private val norwegian = listOf("bibelvers", "jesus-norsk", "norsk-kristen")

    private const val TTL = 12 * 60 * 60 * 1000L
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"

    // Video links on the page, with the caption text around each one.
    private const val READ = "(function(){var out={};document.querySelectorAll('a[href*=\"/video/\"]').forEach(function(a){" +
        "var m=a.href.match(/@([^/?#]+)\\/video\\/(\\d{15,})/);if(!m)return;" +
        "var box=a.closest('[data-e2e]')||a.parentElement;var t=(a.getAttribute('title')||a.innerText||'')+' '+((box&&box.innerText)||'');" +
        "var img=a.querySelector('img');if(img&&img.alt)t+=' '+img.alt;" +
        "out[m[2]]={author:m[1],text:t.replace(/\\s+/g,' ').slice(0,300),thumb:img?(img.getAttribute('src')||''):''};});" +
        "window.scrollBy(0,2500);return JSON.stringify(out);})()"

    private val faith = Regex("(?i)(jesus|jesu\\b|kristus|christ|\\bgud\\b|\\bgod\\b|lord|herren|bibel|bible|scripture|skriften|psalm|salme|bønn|\\bbe for\\b|pray|prayer|faith|\\btro\\b|church|kirke|menighet|worship|lovsang|tilbe|gospel|evangel|holy|hellig|amen|blessed|velsign|spirit|ånden|heaven|himmel|cross|korset|verse|vers\\b|christian|kristen|devotion|andakt|testimony|vitnesbyrd|grace|nåde|salvation|frelse)")

    /** The caption itself, without TikTok's "123 Likes, 4 Comments. TikTok video from …:" around it. */
    private fun caption(t: String, keepTags: Boolean = false): String = t
        .replace(Regex("^[\\d.,KMk\\s]+"), "")
        .replace(Regex("(?i)^.*?(TikTok video from|TikTok-video fra)[^:]*:\\s*"), "")
        .let { if (keepTags) it.replace("#", " ") else it.replace(Regex("#\\S+"), " ") }
        .replace(Regex("\\s+"), " ").trim().trim('"', '«', '»')

    /** How much of the feed's videos are TikToks, 0–100 % (the rest YouTube). Set in the Me tab. */
    fun share(ctx: Context): Int = ctx.applicationContext.getSharedPreferences("faithscroll", Context.MODE_PRIVATE).getInt("tiktokShare", 50)
    fun setShare(ctx: Context, pct: Int) = ctx.applicationContext.getSharedPreferences("faithscroll", Context.MODE_PRIVATE).edit().putInt("tiktokShare", pct.coerceIn(0, 100)).apply()

    /** Takes a TikTok out of the list (e.g. found to be in another language). */
    fun drop(id: String) { found.value = found.value.filter { it.id != id } }

    /** Unseen TikToks waiting. */
    fun waiting(ctx: Context) = found.value.count { !Seen.has(ctx, it.id) }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("tiktok_found", Context.MODE_PRIVATE)

    fun loadCached(ctx: Context) {
        if (found.value.isNotEmpty()) return
        found.value = runCatching {
            val a = JSONArray(prefs(ctx).getString("list2", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> TikTok(o.getString("id"), o.optString("author"), o.optString("caption"), o.optString("thumb")) } }
        }.getOrDefault(emptyList()).filter { !Seen.has(ctx, it.id) }
    }

    /** Looks for new TikToks if the last search is more than 12 hours old (or few unseen are left). */
    /** What the last search found on each page (for the test button). */
    val report = mutableListOf<String>()

    suspend fun refresh(a: Activity, force: Boolean = false, onStatus: (String) -> Unit = {}) {
        loadCached(a)
        val last = prefs(a).getLong("at", 0L)
        val unseen = found.value.count { !Seen.has(a, it.id) }
        if (!force && share(a) == 0) return
        // Search again when it's been a while – or sooner when few unseen ones are left.
        val age = System.currentTimeMillis() - last
        if (!force && (age < 30 * 60_000L || (age < TTL && unseen >= 25) || (unseen >= 8 && age < 60 * 60 * 1000L))) return
        report.clear()
        val no = com.faithscroll.app.util.Lang.no
        val slugs = if (no) norwegian + (if (com.faithscroll.app.util.Lang.alsoEnglish) english.shuffled().take(2) else emptyList()) else english.shuffled().take(4)
        val results = LinkedHashMap<String, TikTok>()
        val web = withWebView(a) ?: return
        try {
            for (slug in slugs) {
                onStatus("tiktok.com/discover/$slug …")
                val before = results.size
                web.loadUrl("https://www.tiktok.com/discover/" + android.net.Uri.encode(slug))
                var got = 0
                for (i in 0 until 12) {
                    delay(1500)
                    val raw = suspendCancellableCoroutine<String?> { c -> web.evaluateJavascript(READ) { c.resumeWith(Result.success(it)) } }
                    val o = runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull() ?: continue
                    o.keys().forEach { id ->
                        val it = o.getJSONObject(id)
                        val author = it.optString("author")
                        val withTags = caption(it.optString("text"), keepTags = true)
                        val caption = caption(it.optString("text"))
                        // Only Christian videos (topic pages mix in other things), in the app's languages.
                        val christian = faith.containsMatchIn(withTags)
                        // Same strict rule as YouTube. Short captions (e.g. just "Psalm 91") are checked
                        // again from the video's page (its speech captions) before it's played.
                        val detected = if (caption.length >= 20) LangFilter.detect(caption) else null
                        val langOk = detected == null || TikTokResolver.languageOk(if (detected == "nb" || detected == "nn") "no" else detected)
                        if (christian && langOk) results.putIfAbsent(id, TikTok(id, author, caption.take(200), it.optString("thumb").takeIf { t -> t.startsWith("http") }.orEmpty()))
                    }
                    got = o.length()
                    if (got >= 20) break
                }
                Diag.log("tiktok", "discover/$slug: $got videos, ${results.size - before} Christian in the app's language")
                report += "discover/$slug: $got " + com.faithscroll.app.util.tr("videos on the page, ${results.size - before} kept", "videoer på siden, ${results.size - before} beholdt")
            }
        } finally {
            (web.parent as? ViewGroup)?.let { h -> (h.parent as? ViewGroup)?.removeView(h) }
            web.destroy()
        }
        if (results.isEmpty()) return
        // A test always shows something, even if you've seen it.
        if (force) found.value = (results.values.toList() + found.value).distinctBy { it.id }
        val fresh = results.values.filter { !Seen.has(a, it.id) }.shuffled()
        val all = (fresh + found.value).distinctBy { it.id }.take(200)
        found.value = all
        prefs(a).edit().putLong("at", System.currentTimeMillis())
            .putString("list2", JSONArray().apply { all.forEach { put(JSONObject().put("id", it.id).put("author", it.author).put("caption", it.caption).put("thumb", it.thumb)) } }.toString()).apply()
        Diag.log("tiktok", "found ${fresh.size} new TikToks (${all.size} kept)")
    }

    /** A browser that's part of the screen (so the page lays out and loads its videos) but pushed out of sight. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun withWebView(a: Activity): WebView? {
        val root = a.findViewById<ViewGroup>(android.R.id.content) ?: return null
        val holder = object : FrameLayout(a) {
            override fun dispatchTouchEvent(ev: android.view.MotionEvent?) = false
            override fun onInterceptTouchEvent(ev: android.view.MotionEvent?) = false
        }.apply { alpha = 0f; isClickable = false; isFocusable = false; importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        root.addView(holder, 0, FrameLayout.LayoutParams(1280, 2000))
        holder.translationX = -5000f
        return WebView(a).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = DESKTOP_UA
            settings.mediaPlaybackRequiresUserGesture = true
            settings.blockNetworkImage = true // only the links are needed
            webViewClient = WebViewClient()
            holder.addView(this, FrameLayout.LayoutParams(1280, 2000))
        }
    }
}
