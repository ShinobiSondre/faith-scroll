package com.faithscroll.app.data

import android.annotation.SuppressLint
import android.app.Activity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference

/**
 * Asks ChatGPT (chatgpt.com, no login) in an invisible web page behind the app and reads its
 * answer back as plain text, so it can be shown in the app's own answer panel. Follow-up
 * questions are typed into the same conversation.
 */
object ChatGptBridge {
    enum class Status { STARTING, THINKING, DONE, FAILED }
    data class Turn(val user: Boolean, val text: String)

    val turns = MutableStateFlow<List<Turn>>(emptyList())
    val status = MutableStateFlow(Status.STARTING)
    val error = MutableStateFlow<String?>(null)
    /** The conversation's address, to open it visibly if something goes wrong. */
    val pageUrl = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var web: WebView? = null
    private var host: WeakReference<Activity>? = null
    private var poll: Job? = null

    // Reads ChatGPT's answers (two ways, in case the page layout differs), whether it's still
    // writing, and whether our question is still sitting unsent in the input box.
    private const val READ = "(function(){var a=document.querySelectorAll('[data-message-author-role=\"assistant\"]');" +
        "var out=[];for(var i=0;i<a.length;i++)out.push(a[i].innerText);" +
        "if(!out.length){var m=document.querySelectorAll('article .markdown, .markdown.prose');for(var j=0;j<m.length;j++)out.push(m[j].innerText);}" +
        // Last resort: the page text itself – each answer follows a "ChatGPT said:" label.
        "if(!out.length&&document.body){var parts=document.body.innerText.split(/ChatGPT (?:said|sa):/);" +
        "for(var k=1;k<parts.length;k++){var t=parts[k].split(/(?:You said|Du sa):/)[0];" +
        "t=t.split(/\\n(?:Ask anything|Spør om hva som helst|ChatGPT can make mistakes|ChatGPT kan gjøre feil|Log in|Sign up)/)[0];out.push(t.trim());}}" +
        "var busy=!!document.querySelector('[data-testid=\"stop-button\"]');" +
        "var c=document.querySelector('#prompt-textarea')||document.querySelector('textarea');" +
        "var pending=c?((c.innerText||c.value||'').trim().length):0;" +
        "return JSON.stringify({a:out,busy:busy,pending:pending,url:location.href,title:document.title," +
        "body:(document.body?document.body.innerText:'').slice(0,240)});})()"
    private const val SEND = "(function(){var b=document.querySelector('[data-testid=\"send-button\"]')||document.querySelector('button[aria-label*=\"end\"]');" +
        "if(b&&!b.disabled){b.click();return 'clicked';}var e=document.querySelector('#prompt-textarea');" +
        "if(e){e.focus();e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,bubbles:true}));return 'enter';}return 'none';})()"

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(a: Activity): WebView? {
        if (web != null && host?.get() === a) return web
        web?.let { w -> (w.parent as? ViewGroup)?.let { h -> (h.parent as? ViewGroup)?.removeView(h); h.removeView(w) }; w.destroy() }
        val root = a.findViewById<ViewGroup>(android.R.id.content) ?: return null
        host = WeakReference(a)
        // On top of the app but practically transparent, and every touch passes straight through to
        // the app underneath. (Behind the app, ChatGPT never drew its streaming answer.)
        val holder = object : FrameLayout(a) {
            override fun dispatchTouchEvent(ev: android.view.MotionEvent?) = false
            override fun onInterceptTouchEvent(ev: android.view.MotionEvent?) = false
        }.apply { alpha = 0.01f; isClickable = false; isFocusable = false; importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        root.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return WebView(a).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            isFocusable = false; isFocusableInTouchMode = false
            holder.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }.also { web = it }
    }

    /** Starts a new conversation with [question] (shown short in the panel) using the prefilled [url]. */
    fun ask(a: Activity, url: String, question: String) {
        val w = webView(a) ?: run { fail("No window"); return }
        turns.value = listOf(Turn(true, question))
        error.value = null
        status.value = Status.THINKING
        pageUrl.value = url
        w.loadUrl(url)
        watch(expected = 1)
    }

    /** Types a follow-up question into the same conversation. */
    fun followUp(text: String) {
        val w = web ?: return
        turns.value = turns.value + Turn(true, text)
        status.value = Status.THINKING
        error.value = null
        val js = "(function(q){var e=document.querySelector('#prompt-textarea')||document.querySelector('textarea');" +
            "if(!e)return 'noinput';e.focus();document.execCommand('insertText',false,q);" +
            "setTimeout(function(){var b=document.querySelector('[data-testid=\"send-button\"]');if(b)b.click();" +
            "else{var k=new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,bubbles:true});e.dispatchEvent(k);}},400);" +
            "return 'ok';})(${JSONObject.quote(text)})"
        w.evaluateJavascript(js) { r -> if (r?.contains("noinput") == true) fail("Couldn't type the question") }
        watch(expected = turns.value.count { it.user })
    }

    /** Reads the page every ~0.6 s; the answer streams into the panel and is done when it stops growing. */
    private fun watch(expected: Int) {
        poll?.cancel()
        poll = scope.launch {
            val t0 = System.currentTimeMillis()
            var last = ""
            var stableSince = 0L
            var nudged = 0L
            while (true) {
                delay(600)
                val w = web ?: return@launch
                val raw = kotlinx.coroutines.suspendCancellableCoroutine<String?> { c -> w.evaluateJavascript(READ) { c.resumeWith(Result.success(it)) } }
                val o = runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull() ?: continue
                o.optString("url").takeIf { it.startsWith("http") }?.let { pageUrl.value = it }
                val answers = o.optJSONArray("a") ?: JSONArray()
                val text = if (answers.length() >= expected) answers.optString(expected - 1).trim() else ""
                if (text.isNotEmpty()) {
                    val users = turns.value.filter { it.user }
                    // Rebuild: question, answer, question, answer… with the newest answer growing.
                    val list = mutableListOf<Turn>()
                    users.forEachIndexed { i, u ->
                        list += u
                        val ans = if (i == expected - 1) text else answers.optString(i).trim()
                        if (ans.isNotEmpty()) list += Turn(false, ans)
                    }
                    turns.value = list
                    if (last.isEmpty()) Diag.log("ask", "ChatGPT started answering after ${(System.currentTimeMillis() - t0) / 1000.0} s")
                    if (text != last) { last = text; stableSince = System.currentTimeMillis() }
                    val busy = o.optBoolean("busy")
                    if (!busy && System.currentTimeMillis() - stableSince > 1500) {
                        status.value = Status.DONE
                        Diag.log("ask", "ChatGPT answered in ${(System.currentTimeMillis() - t0) / 1000.0} s (${text.length} chars)")
                        return@launch
                    }
                } else {
                    // The question is filled in but wasn't sent (happens when the page isn't on screen): press send.
                    val waited = System.currentTimeMillis() - t0
                    if (o.optInt("pending") > 0 && waited > 4_000 && System.currentTimeMillis() - nudged > 5_000) {
                        nudged = System.currentTimeMillis()
                        w.evaluateJavascript(SEND) { r -> Diag.log("ask", "question was waiting unsent – pressed send ($r)") }
                    }
                    if (waited > 45_000) {
                        val seen = "${o.optString("title")} | ${o.optString("body").replace("\n", " ").take(160)}"
                        Diag.log("ask", "ChatGPT gave no answer: $seen")
                        fail(com.faithscroll.app.util.tr("ChatGPT didn't answer. The page showed: ", "ChatGPT svarte ikke. Siden viste: ") + seen)
                        return@launch
                    }
                }
            }
        }
    }

    private fun fail(msg: String) { error.value = msg; status.value = Status.FAILED }
}
