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
        "if(bt.length<45&&/(avvis|reject|forbli utlogget|stay logged out|fortsett uten|continue without|ikke nå|not now|maybe later|kanskje senere)/i.test(bt)){bs[x].click();}" +
        // "Clear current chat?" (a new link while a chat is open): yes, start the new one.
        "else if(/^(clear chat|clear|tøm chat|tøm chatten|tøm|slett chat|fjern chat|start new chat|ny chat)$/i.test(bt)&&bs[x].closest('[role=\"dialog\"],[role=\"alertdialog\"],[aria-modal=\"true\"],dialog')){bs[x].click();}}" +
        // "Log in or sign up" sheets: close them (the X), we carry on logged out.
        "var dl=document.querySelectorAll('[role=\"dialog\"],dialog[open]');for(var y=0;y<dl.length;y++){var dt=dl[y].innerText||'';" +
        "if(/(log in|sign up|logg inn|registrer)/i.test(dt)&&!/(clear current chat|tøm)/i.test(dt)){var xb=dl[y].querySelector('button[aria-label*=\"lose\"],button[aria-label*=\"ukk\"],[data-testid=\"close-button\"]');if(xb)xb.click();}}" +
        "var a=document.querySelectorAll('[data-message-role=\"assistant\"]');" +
        "if(!a.length)a=document.querySelectorAll('[data-message-author-role=\"assistant\"]');" +
        "if(!a.length)a=document.querySelectorAll('[data-turn=\"assistant\"]');" +
        "var out=[];for(var i=0;i<a.length;i++)out.push(a[i].innerText);" +
        "if(!out.length){var m=document.querySelectorAll('article .markdown, .markdown.prose');for(var j=0;j<m.length;j++)out.push(m[j].innerText);}" +
        // Last resort: the page text itself – each answer follows a "ChatGPT said:" label.
        "if(!out.length&&document.body){var parts=document.body.innerText.split(/ChatGPT (?:said|sa):/);" +
        "for(var k=1;k<parts.length;k++){var t=parts[k].split(/(?:You said|Du sa):/)[0];" +
        "t=t.split(/(?:ChatGPT is AI and can make mistakes|ChatGPT can make mistakes|ChatGPT kan gjøre feil|ChatGPT er (?:KI|AI)|Chat with ChatGPT|Chat med ChatGPT|Ask anything|Spør om hva som helst|Log in|Sign up|Logg inn|Registrer deg|We use cookies|Vi bruker informasjonskapsler)/)[0];out.push(t.trim());}}" +
        "var busy=!!document.querySelector('[data-testid=\"stop-button\"],[data-message-streaming]');" +
        "var c=document.querySelector('#prompt-textarea')||document.querySelector('textarea');" +
        "var pending=c?((c.innerText||c.value||'').trim().length):0;" +
        "var users=document.querySelectorAll('[data-message-role=\"user\"],[data-message-author-role=\"user\"]').length;" +
        "if(!users&&/\\/(uc|c)\\//.test(location.pathname))users=1;" +
        "var complete=!!document.querySelector('[data-message-role=\"assistant\"][data-message-complete]');" +
        "return JSON.stringify({a:out,busy:busy,complete:complete,pending:pending,users:users,input:!!c,url:location.href,title:document.title," +
        "body:(document.body?document.body.innerText:'').slice(0,240)," +
        "tail:(document.body?document.body.innerText:'').slice(-300)," +
        "dialog:[].slice.call(document.querySelectorAll('[role=\"dialog\"], [aria-modal=\"true\"]')).map(function(e){return (e.innerText||'').replace(/\\s+/g,' ').slice(0,200);}).join(' | ')});})()"
    /**
     * Turns an answer's page elements back into light Markdown, so dish names (headings and bold
     * text) and lists survive. Plain innerText dropped that, and the titles looked like body text.
     */
    private val MD = """
        function mdN(n){
          if(n.nodeType===3)return n.nodeValue;
          if(n.nodeType!==1)return '';
          var t=n.tagName;
          if(t==='IMG'){var u=n.currentSrc||n.getAttribute('src')||'';var w=n.naturalWidth||n.width||0;
            return (u.indexOf('http')===0&&w>=80&&!/avatar|favicon|icon/i.test(u))?'\n![]('+u+')\n':'';}
          if(t==='BUTTON'||t==='svg'||t==='SVG'||t==='STYLE'||t==='SCRIPT'||n.getAttribute('aria-hidden')==='true'||n.hasAttribute('data-message-actions'))return '';
          var s='';for(var i=0;i<n.childNodes.length;i++)s+=mdN(n.childNodes[i]);
          if(/^H[1-6]$/.test(t))return '\n\n### '+s.trim()+'\n';
          if(t==='STRONG'||t==='B')return s.trim()?'**'+s.trim()+'** ':'';
          if(t==='LI')return '\n• '+s.trim();
          if(t==='BR')return '\n';
          if(t==='P'||t==='UL'||t==='OL'||t==='DIV'||t==='SECTION'||t==='TABLE'||t==='TR'||t==='PRE'||t==='BLOCKQUOTE')return '\n'+s+'\n';
          if(t==='TD'||t==='TH')return s+'  ';
          return s;
        }
        function mdOf(el){var s='';for(var i=0;i<el.childNodes.length;i++)s+=mdN(el.childNodes[i]);
          return s.replace(/\*\*\s+\*\*/g,' ').replace(/[ \t]+\n/g,'\n').replace(/\n{3,}/g,'\n\n').trim();}
    """.trimIndent()
    /** [READ] with the Markdown helper inside it. */
    private val READ_MD by lazy { READ.replaceFirst("(function(){", "(function(){$MD\n") }

    // How the page is built (for the diagnostics when no answer is found): which conversation markers
    // exist, the input box, and whether our question appears anywhere.
    private const val PAGE_SHAPE = "(function(){function n(s){return document.querySelectorAll(s).length;}" +
        "var ids={};[].slice.call(document.querySelectorAll('[data-testid]')).forEach(function(e){var k=e.getAttribute('data-testid').replace(/[0-9]+/g,'#');ids[k]=(ids[k]||0)+1;});" +
        "var c=document.querySelector('#prompt-textarea')||document.querySelector('textarea');" +
        "return JSON.stringify({role_user:n('[data-message-author-role=\"user\"]'),role_assistant:n('[data-message-author-role=\"assistant\"]')," +
        "turn:n('[data-turn]'),article:n('article'),markdown:n('.markdown'),composer:c?((c.innerText||c.value||'').length):-1," +
        "url:location.pathname,testids:Object.keys(ids).slice(0,25).join(',')});})()"

    // Sends what's in ChatGPT's input box. Tries every kind of send button ChatGPT has used, then the
    // form's own submit, then a real Enter key (its layout changes now and then).
    private const val SEND = "(function(){var e=document.querySelector('#prompt-textarea')||document.querySelector('textarea');" +
        "if(e){try{e.dispatchEvent(new InputEvent('input',{bubbles:true}));}catch(x){}}" +
        "var sels=['[data-testid=\"send-button\"]','#composer-submit-button','[data-testid=\"composer-send-button\"]'," +
        "'button[aria-label=\"Send prompt\"]','button[aria-label*=\"Send\"]','button[aria-label*=\"send\"]','button[aria-label*=\"Send inn\"]'," +
        "'form button[type=\"submit\"]'];" +
        "for(var i=0;i<sels.length;i++){var b=document.querySelector(sels[i]);if(b&&!b.disabled&&b.getAttribute('aria-disabled')!=='true'){b.click();return 'clicked '+sels[i];}}" +
        "var f=e&&e.closest?e.closest('form'):null;if(f&&f.requestSubmit){try{f.requestSubmit();return 'form';}catch(x){}}" +
        "if(e){try{e.focus();}catch(x){}['keydown','keypress','keyup'].forEach(function(t){e.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));});return 'enter';}" +
        "return 'none';})()"

    /** Debug: show ChatGPT's hidden page (and make the chat see-through) to watch what it does. */
    val seeThrough = MutableStateFlow(false)
    fun setSeeThrough(on: Boolean) {
        seeThrough.value = on
        (web?.parent as? android.view.View)?.let { place(it, on) }
    }

    /**
     * Where the hidden page sits. Android's WebView doesn't fully honour transparency, so the page
     * showed faintly through the app. It's now moved down until only 2 pixels are left on screen
     * (still "visible" to Android, so ChatGPT keeps writing), unless see-through mode is on.
     */
    private fun place(h: android.view.View, seeThrough: Boolean, hide: Boolean = !seeThrough && !fallback) {
        h.alpha = if (seeThrough) 0.6f else 0.01f
        h.translationY = if (hide) ((h.parent as? android.view.View)?.height ?: h.height).toFloat().let { (it - 2f).coerceAtLeast(0f) } else 0f
    }
    /** Set if ChatGPT stays silent while pushed down: then the page goes back on screen (at 1 %). */
    @Volatile private var fallback = false

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
        }.apply {
            // WebView draws itself outside its parent's alpha unless the parent has its own layer;
            // without this the "hidden" ChatGPT page showed faintly through the app.
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            alpha = if (seeThrough.value) 0.6f else 0.01f; isClickable = false; isFocusable = false; importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        root.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        holder.post { place(holder, seeThrough.value) }
        return WebView(a).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            isFocusable = false; isFocusableInTouchMode = false
            holder.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }.also { web = it }
    }

    /** Which conversation is open: video id, the moment asked about, and the link it started with. */
    data class Session(val videoId: String, val atMs: Long, val url: String)
    val session = MutableStateFlow<Session?>(null)

    /** Starts a new conversation – unless this exact one is already going (then it just continues). */
    fun ask(a: Activity, url: String, question: String, videoId: String, atMs: Long) {
        if (session.value?.let { it.videoId == videoId && it.atMs == atMs } == true && turns.value.isNotEmpty() && status.value != Status.FAILED) return
        session.value = Session(videoId, atMs, url)
        firstQuestion = android.net.Uri.parse(url).getQueryParameter("q").orEmpty()
        val w = webView(a) ?: run { fail("No window"); return }
        turns.value = listOf(Turn(true, question))
        history = turns.value
        sent.clear(); sent += firstQuestion
        currentQ = firstQuestion
        error.value = null
        status.value = Status.THINKING
        pageUrl.value = url
        freshLoad(w, url)
    }

    /**
     * Every question is a brand-new, logged-out ChatGPT: cookies and site storage are wiped first,
     * so ChatGPT never counts earlier chats and asks us to log in.
     */
    private fun freshLoad(@Suppress("UNUSED_PARAMETER") w: WebView, url: String) {
        // A brand-new browser for every question: the old page is closed first, and only then are
        // ChatGPT's cookies and storage wiped. Wiping while the old page still ran left the next
        // page hanging, which is why follow-up questions never got an answer.
        poll?.cancel()
        val a = host?.get()
        web?.let { old ->
            old.stopLoading()
            (old.parent as? ViewGroup)?.let { h -> (h.parent as? ViewGroup)?.removeView(h); h.removeView(old) }
            old.destroy()
        }
        web = null
        val cm = CookieManager.getInstance()
        cm.removeAllCookies {
            cm.flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
            val fresh = a?.let { webView(it) }
            if (fresh == null) { fail(com.faithscroll.app.util.tr("ChatGPT couldn't start. Try again.", "ChatGPT kunne ikke starte. Prøv igjen.")); return@removeAllCookies }
            // (The HTTP cache is kept: ChatGPT's scripts load from it, which saves several seconds.)
            fresh.loadUrl(url)
            watch() // start reading only once the new page is on its way
        }
    }

    /**
     * Opens ChatGPT quietly in the background before you ask (when a video is paused), so its
     * scripts are already loaded and cached. The question itself still goes to a fresh, wiped browser.
     */
    fun prewarm(a: Activity) {
        if (web != null || status.value == Status.THINKING) return
        webView(a)?.loadUrl("https://chatgpt.com/")
    }

    fun reset() { poll?.cancel(); session.value = null; turns.value = emptyList(); status.value = Status.STARTING; error.value = null }

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
        currentQ = q
        status.value = Status.THINKING
        error.value = null
        val url = android.net.Uri.parse("https://chatgpt.com/").buildUpon().appendQueryParameter("q", q).build().toString()
        pageUrl.value = url
        log("ask", "follow-up sent as a new link (${q.length} chars)")
        freshLoad(w, url)
    }

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
    private fun watch() {
        val expected = 1 // every question is a fresh ChatGPT page with exactly one answer
        poll?.cancel()
        poll = scope.launch {
            val t0 = System.currentTimeMillis()
            var last = ""
            var stableSince = 0L
            var nudged = 0L
            var nudges = 0
            var typed = false      // the question is typed in at most once (never twice into the chat)
            var snapshot = false   // one look at how the page is built, if no answer shows up
            while (true) {
                delay(300)
                val w = web ?: return@launch
                val raw = kotlinx.coroutines.suspendCancellableCoroutine<String?> { c -> w.evaluateJavascript(READ) { c.resumeWith(Result.success(it)) } }
                val o = runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull() ?: continue
                o.optString("url").takeIf { it.startsWith("http") }?.let { pageUrl.value = it }
                val answers = o.optJSONArray("a") ?: JSONArray()
                // Every question is a fresh page, so all assistant parts belong to this one answer.
                val text = (if (answers.length() >= expected) (0 until answers.length()).joinToString("\n\n") { answers.optString(it).trim() }.trim() else "").let(::clean)
                if (text.isNotEmpty()) {
                    // Everything before + the answer as it's being written.
                    turns.value = history + Turn(false, text)
                    if (last.isEmpty()) log("ask", "ChatGPT started answering after ${(System.currentTimeMillis() - t0) / 1000.0} s")
                    if (text != last) { last = text; stableSince = System.currentTimeMillis() }
                    val busy = o.optBoolean("busy")
                    // Finished: ChatGPT's stop button is gone and the text hasn't changed for 2.5 s.
                    // Done: ChatGPT marks the answer complete (instant), or the text stops changing for 1.5 s.
                    val complete = o.optBoolean("complete")
                    if (!busy && text.length >= 20 && (complete || System.currentTimeMillis() - stableSince > 1500)) {
                        status.value = Status.DONE
                        log("ask", "ChatGPT answered in ${(System.currentTimeMillis() - t0) / 1000.0} s (${text.length} chars)")
                        return@launch
                    }
                } else {
                    // The question is filled in but wasn't sent (happens when the page isn't on screen): press send.
                    val waited = System.currentTimeMillis() - t0
                    // ChatGPT sends a question from the link by itself (usually within 2 s). Only if it's still
                    // sitting unsent and no conversation started after 10 s, press send (at most twice).
                    if (o.optInt("pending") > 0 && o.optInt("users") == 0 && waited > 10_000 && nudges < 2 &&
                        System.currentTimeMillis() - nudged > 8_000) {
                        nudges++
                        nudged = System.currentTimeMillis()
                        w.evaluateJavascript(SEND) { r -> log("ask", "question was waiting unsent – pressed send ($r)") }
                    } else if (o.optInt("users") == 0 && o.optInt("pending") == 0 && o.optBoolean("input") &&
                        waited > 14_000 && !typed && currentQ.isNotBlank()) {
                        typed = true
                        // The link's question never arrived (e.g. a pop-up was in the way): type it in ourselves.
                        nudged = System.currentTimeMillis()
                        log("ask", "question didn't arrive from the link – typing it in")
                        w.evaluateJavascript(typeJs(currentQ), null)
                    }
                    if (waited > 12_000 && !fallback && !seeThrough.value) {
                        fallback = true
                        (w.parent as? android.view.View)?.let { place(it, false, hide = false) }
                        log("ask", "no answer while the page was pushed down – put it back on screen")
                    }
                    if (waited > 12_000 && !snapshot) {
                        snapshot = true
                        w.evaluateJavascript(PAGE_SHAPE) { r -> log("ask", "page shape: ${runCatching { JSONTokener(r).nextValue() as String }.getOrDefault(r)}") }
                    }
                    if (waited > 45_000) {
                        // What's blocking: a pop-up if there is one, else the end of the page (limits / log-in show there).
                        val dialog = o.optString("dialog").trim()
                        val tail = o.optString("tail").replace("\n", " ").replace(Regex("\\s+"), " ").trim().takeLast(220)
                        val seen = dialog.ifBlank { tail }.ifBlank { o.optString("body").replace("\n", " ").take(160) }
                        val limit = Regex("(?i)(limit|too many|for mange|try again later|prøv igjen senere|grense|reached)").containsMatchIn(dialog + " " + tail)
                        log("ask", "ChatGPT gave no answer: $seen")
                        fail(
                            if (limit) com.faithscroll.app.util.tr(
                                "ChatGPT wants you to log in, or the free limit without an account is reached. Tap Open ChatGPT and log in once – then it answers here again.",
                                "ChatGPT vil at du logger inn, eller grensen uten konto er nådd. Trykk «Åpne ChatGPT» og logg inn én gang – da svarer den her igjen.")
                            // What the page showed goes to the diagnostics log, not on screen.
                            else com.faithscroll.app.util.tr("ChatGPT didn't answer. Try again in a moment.", "ChatGPT svarte ikke. Prøv igjen om litt.")
                        )
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

    private fun log(tag: String, msg: String) = Diag.log(tag, msg)

    private fun fail(msg: String) { error.value = msg; status.value = Status.FAILED }
}
