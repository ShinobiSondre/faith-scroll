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
    @Volatile private var firstQuestion = ""

    // Reads ChatGPT's answers (two ways, in case the page layout differs), whether it's still
    // writing, and whether our question is still sitting unsent in the input box.
    private const val READ = "(function(){" +
        // Cookie banner (Norway/EU): choose "reject non-essential" (or close it) so it never blocks or gets read.
        // Cookie banner and "log in" pop-ups: reject non-essential cookies / stay logged out.
        "var bs=document.querySelectorAll('button, a, [role=\"button\"]');for(var x=0;x<bs.length;x++){var bt=(bs[x].innerText||'').trim();" +
        "if(bt.length<45&&/(avvis|reject|forbli utlogget|stay logged out|fortsett uten|continue without|ikke nå|not now|maybe later|kanskje senere)/i.test(bt)){bs[x].click();}}" +
        "var a=document.querySelectorAll('[data-message-author-role=\"assistant\"]');" +
        "if(!a.length)a=document.querySelectorAll('[data-turn=\"assistant\"]');" +
        "var out=[];for(var i=0;i<a.length;i++)out.push(a[i].innerText);" +
        "if(!out.length){var m=document.querySelectorAll('article .markdown, .markdown.prose');for(var j=0;j<m.length;j++)out.push(m[j].innerText);}" +
        // Last resort: the page text itself – each answer follows a "ChatGPT said:" label.
        "if(!out.length&&document.body){var parts=document.body.innerText.split(/ChatGPT (?:said|sa):/);" +
        "for(var k=1;k<parts.length;k++){var t=parts[k].split(/(?:You said|Du sa):/)[0];" +
        "t=t.split(/(?:ChatGPT is AI and can make mistakes|ChatGPT can make mistakes|ChatGPT kan gjøre feil|ChatGPT er (?:KI|AI)|Chat with ChatGPT|Chat med ChatGPT|Ask anything|Spør om hva som helst|Log in|Sign up|Logg inn|Registrer deg|We use cookies|Vi bruker informasjonskapsler)/)[0];out.push(t.trim());}}" +
        "var busy=!!document.querySelector('[data-testid=\"stop-button\"]');" +
        "var c=document.querySelector('#prompt-textarea')||document.querySelector('textarea');" +
        "var pending=c?((c.innerText||c.value||'').trim().length):0;" +
        "var users=document.querySelectorAll('[data-message-author-role=\"user\"]').length;" +
        "return JSON.stringify({a:out,busy:busy,pending:pending,users:users,input:!!c,url:location.href,title:document.title," +
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
    /** Which conversation is open: video id, the moment asked about, and the link it started with. */
    data class Session(val videoId: String, val atMs: Long, val url: String)
    val session = MutableStateFlow<Session?>(null)

    /** Starts a new conversation – unless this exact one is already going (then it just continues). */
    fun ask(a: Activity, url: String, question: String, videoId: String, atMs: Long) {
        if (session.value?.let { it.videoId == videoId && it.atMs == atMs } == true && turns.value.isNotEmpty()) return
        session.value = Session(videoId, atMs, url)
        firstQuestion = android.net.Uri.parse(url).getQueryParameter("q").orEmpty()
        val w = webView(a) ?: run { fail("No window"); return }
        turns.value = listOf(Turn(true, question))
        history = turns.value
        sent.clear(); sent += firstQuestion
        currentQ = firstQuestion
        pageQuestions = 0
        error.value = null
        status.value = Status.THINKING
        pageUrl.value = url
        w.loadUrl(url)
        watch(answerIndex = 0, expectUsers = 1, fallback = null)
    }

    /** Everything shown before the answer being written now; the new answer is added after it. */
    @Volatile private var history: List<Turn> = emptyList()
    /** What was actually sent to ChatGPT for each question (with transcript etc.). */
    private val sent = mutableListOf<String>()
    @Volatile private var currentQ = ""

    /** Types a follow-up question into the same conversation. */
    /**
     * A follow-up in the same conversation. [shown] is what the panel shows (short), [send] what
     * ChatGPT gets (e.g. with 5 minutes of transcript).
     */
    fun followUp(shown: String, send: String = shown) {
        val w = web ?: return
        // Typing into ChatGPT's page gets blocked by its log-in/cookie pop-ups for logged-out users,
        // so a follow-up goes the same way as the first question: a fresh ChatGPT link that carries
        // the video, the conversation so far and the new question. In the panel it's one chat.
        val q = followUpPrompt(send)
        turns.value = turns.value + Turn(true, shown)
        history = turns.value
        sent += send
        status.value = Status.THINKING
        error.value = null
        // First: the same conversation – close any pop-ups, then type the question in.
        w.evaluateJavascript(DISMISS, null)
        scope.launch {
            delay(500)
            w.evaluateJavascript(typeJs(send)) { r -> Diag.log("ask", "follow-up typed into the same chat: $r") }
        }
        currentQ = send
        watch(answerIndex = pageQuestions, expectUsers = pageQuestions + 1, fallback = q)
    }

    /** Loads a fresh ChatGPT link carrying the whole conversation (when typing into the page is blocked). */
    private fun sendAsLink(q: String) {
        val w = web ?: return
        currentQ = q
        val url = android.net.Uri.parse("https://chatgpt.com/").buildUpon().appendQueryParameter("q", q).build().toString()
        pageUrl.value = url
        Diag.log("ask", "follow-up sent as a new link instead (${q.length} chars, with the conversation so far)")
        w.loadUrl(url)
    }

    /** How many questions the current ChatGPT page holds (so the next answer is the one after them). */
    @Volatile private var pageQuestions = 0

    // Closes pop-ups: cookie banner, "log in / stay logged out", "not now", and dialog close (✕) buttons.
    private const val DISMISS = "(function(){var n=0;" +
        "var bs=document.querySelectorAll('button, a, [role=\"button\"]');for(var x=0;x<bs.length;x++){var bt=(bs[x].innerText||bs[x].getAttribute('aria-label')||'').trim();" +
        "if(bt.length<45&&/(avvis|reject|forbli utlogget|stay logged out|fortsett uten|continue without|ikke nå|not now|maybe later|kanskje senere|^lukk$|^close$|dismiss)/i.test(bt)){bs[x].click();n++;}}" +
        "var ds=document.querySelectorAll('[role=\"dialog\"] [aria-label*=\"lose\"], [role=\"dialog\"] [aria-label*=\"Lukk\"], [data-testid=\"close-button\"]');for(var y=0;y<ds.length;y++){ds[y].click();n++;}" +
        "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,bubbles:true}));return n;})()"

    // What's on screen when something blocks: dialog texts and button labels (for the diagnostics).
    private const val BLOCKERS = "(function(){var d=[].slice.call(document.querySelectorAll('[role=\"dialog\"], [aria-modal=\"true\"]')).map(function(e){return (e.innerText||'').replace(/\\s+/g,' ').slice(0,160);});" +
        "var b=[].slice.call(document.querySelectorAll('button')).map(function(e){return (e.innerText||e.getAttribute('aria-label')||'').trim();}).filter(function(t){return t&&t.length<40;}).slice(0,20);" +
        "return JSON.stringify({dialogs:d,buttons:b});})()"

    /** The video + the conversation so far + the new question, kept short enough for a link. */
    private fun followUpPrompt(send: String): String {
        val no = com.faithscroll.app.util.Lang.no
        val header = firstQuestion.lineSequence().firstOrNull().orEmpty()
        val past = buildString {
            var qi = 0
            for (t in turns.value) {
                if (t.user) {
                    // The first question is the long one with the transcript; summarise it.
                    val text = if (qi == 0) (if (no) "Hva mener de på dette tidspunktet?" else "What do they mean at this moment?") else sent.getOrElse(qi) { t.text }
                    append(if (no) "Spørsmål: " else "Question: ").append(text.take(1500)).append("\n"); qi++
                } else append(if (no) "Ditt svar: " else "Your answer: ").append(t.text.take(900)).append("\n\n")
            }
        }
        // The transcript around the moment (from the first question), if there's room.
        val transcript = firstQuestion.substringAfter("\n", "").substringBeforeLast("\n\n").take(2500)
        val ask = (if (no) "Nytt spørsmål: " else "New question: ") + send
        val intro = if (no) "Vi snakker om denne videoen. Fortsett samtalen og svar kort på norsk." else "We're talking about this video. Continue the conversation and answer briefly."
        val label = if (no) "Samtalen så langt" else "Conversation so far"
        var out = "$header\n$intro\n\n" + (if (send.length < 2000) "$transcript\n\n" else "") + "$label:\n$past$ask"
        if (out.length > 6000) out = "$header\n$intro\n\n$label:\n${past.takeLast(1500)}${ask.takeLast(4000)}"
        return out
    }

    /** Puts [text] into ChatGPT's input box (no keyboard focus needed) and presses send. */
    private fun typeJs(text: String): String =
        "(function(q){var e=document.querySelector('#prompt-textarea')||document.querySelector('textarea');" +
            "if(!e)return 'noinput';" +
            "if(e.tagName==='TEXTAREA'){var set=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;set.call(e,q);" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));}" +
            "else{try{e.focus();}catch(x){}var ok=false;try{ok=document.execCommand('insertText',false,q);}catch(x){}" +
            "if(!ok||!(e.innerText||'').trim()){var esc=q.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');" +
            "e.innerHTML=esc.split('\\n').map(function(l){return '<p>'+(l||'<br>')+'</p>';}).join('');" +
            "e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:q}));}}" +
            "var tries=0;(function send(){var b=document.querySelector('[data-testid=\"send-button\"]')||document.querySelector('#composer-submit-button');" +
            "if(b&&!b.disabled){b.click();return;}if(++tries<8)setTimeout(send,400);" +
            "else e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,bubbles:true}));})();" +
            "return 'ok';})(${JSONObject.quote(text)})"

    /** Does ChatGPT's latest answer say it needs more context? */
    fun wantsMoreContext(): Boolean = turns.value.lastOrNull()?.takeIf { !it.user }?.text?.let {
        Regex("(?i)(mer kontekst|mer sammenheng|more context|ikke nok (informasjon|kontekst|sammenheng)|uklart|unclear|vanskelig å (si|vite|avgjøre)|hard to (say|tell)|without more|need more|trenger mer)").containsMatchIn(it)
    } == true

    /** Reads the page every ~0.6 s; the answer streams into the panel and is done when it stops growing. */
    private fun watch(answerIndex: Int, expectUsers: Int, fallback: String?) {
        poll?.cancel()
        poll = scope.launch {
            var expected = answerIndex + 1
            var wantUsers = expectUsers
            var backup = fallback
            var t0 = System.currentTimeMillis()
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
                // The follow-up didn't show up in the chat within 8 s: something is blocking. Note what, then use the link.
                if (backup != null && o.optInt("users") < wantUsers && System.currentTimeMillis() - t0 > 8_000) {
                    w.evaluateJavascript(BLOCKERS) { b -> Diag.log("ask", "follow-up blocked on the page – ${runCatching { JSONTokener(b).nextValue() as String }.getOrDefault(b)}") }
                    sendAsLink(backup)
                    backup = null; expected = 1; wantUsers = 1; t0 = System.currentTimeMillis(); last = ""
                    continue
                }
                if (o.optInt("users") < wantUsers) { w.evaluateJavascript(DISMISS, null) }
                val text = (if (answers.length() >= expected) answers.optString(expected - 1).trim() else "").let(::clean)
                if (text.isNotEmpty()) {
                    // Everything before + the answer as it's being written.
                    turns.value = history + Turn(false, text)
                    if (last.isEmpty()) Diag.log("ask", "ChatGPT started answering after ${(System.currentTimeMillis() - t0) / 1000.0} s")
                    if (text != last) { last = text; stableSince = System.currentTimeMillis() }
                    val busy = o.optBoolean("busy")
                    // Finished: ChatGPT's stop button is gone and the text hasn't changed for 2.5 s.
                    if (!busy && text.length >= 20 && System.currentTimeMillis() - stableSince > 2500) {
                        pageQuestions = wantUsers
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
                    } else if (o.optInt("users") == 0 && o.optInt("pending") == 0 && o.optBoolean("input") &&
                        waited > 6_000 && System.currentTimeMillis() - nudged > 8_000 && currentQ.isNotBlank()) {
                        // The link's question never arrived (e.g. a pop-up was in the way): type it in ourselves.
                        nudged = System.currentTimeMillis()
                        Diag.log("ask", "question didn't arrive from the link – typing it in")
                        w.evaluateJavascript(typeJs(currentQ), null)
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

    /** Drops page furniture that isn't part of the answer (disclaimers, buttons). */
    private val junk = Regex("(ChatGPT is AI and can make mistakes\\.?|ChatGPT can make mistakes\\.?.*|Check important info\\.?|Chat with ChatGPT|Chat med ChatGPT|ChatGPT er (KI|AI) og kan gjøre feil\\.?.*|ChatGPT kan gjøre feil\\.?.*)", RegexOption.IGNORE_CASE)
    // Whole paragraphs/lines that are page furniture: cookie banners, login prompts, buttons.
    private val junkLine = Regex("(?i)(informasjonskapsl|cookie|Avvis ikke nødvendige|Godta alle|Accept all|Reject non-essential|Administrer innstillingene|Manage (cookie|preferences)|^Logg inn$|^Log in$|^Registrer deg|^Sign up|^Chat (med|with) ChatGPT$|Vi bruker informasjonskapsler|We use cookies)")
    private fun clean(s: String): String = s.replace(junk, "")
        .lines().filterNot { it.isNotBlank() && junkLine.containsMatchIn(it.trim()) }.joinToString("\n")
        .replace(Regex("\\n{3,}"), "\n\n").trim()

    private fun fail(msg: String) { error.value = msg; status.value = Status.FAILED }
}
