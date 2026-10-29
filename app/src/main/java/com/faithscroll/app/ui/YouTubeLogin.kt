package com.faithscroll.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.faithscroll.app.data.Diag
import com.faithscroll.app.data.YouTubeAccount
import com.faithscroll.app.util.tr

/**
 * Google's own sign-in page for YouTube, shown inside the app. When YouTube's login cookies
 * appear, we're signed in and [onDone] is called.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginDialog(onDone: (Boolean) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var refused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    fun finish(ok: Boolean) { if (!finished) { finished = true; onDone(ok) } }

    Dialog(onDismissRequest = { finish(YouTubeAccount.signedIn()) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(tr("Sign in to YouTube", "Logg inn på YouTube"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(tr("Only used to read which channels you subscribe to. Stays on this phone.",
                        "Brukes bare til å lese hvilke kanaler du abonnerer på. Blir på denne telefonen."),
                        color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
                TextButton(onClick = { finish(YouTubeAccount.signedIn()) }) { Text(tr("Close", "Lukk"), color = Gold) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
            if (refused) Text(
                tr("Google won't allow sign-in inside apps on this phone. Close this and use \"Import from Google Takeout\" instead – it gives the same list.",
                    "Google tillater ikke innlogging inne i apper på denne telefonen. Lukk dette og bruk «Importer fra Google Takeout» i stedet – det gir samme liste."),
                color = Color.White, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(Plum).padding(12.dp)
            )
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.White)) {
                AndroidView(factory = { c ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(c).apply {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Look like a normal desktop browser, and don't announce "embedded app", so Google allows the sign-in.
                        settings.userAgentString = YouTubeAccount.LOGIN_UA
                        // Firefox has no navigator.userAgentData; hide WebView's so the page sees one consistent browser.
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) runCatching {
                            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this,
                                "try{Object.defineProperty(Navigator.prototype,'userAgentData',{get:function(){return undefined}});}catch(e){}", setOf("*"))
                        }
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                            runCatching { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet()) }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { loading = true }
                            override fun onPageFinished(view: WebView, url: String) {
                                loading = false
                                CookieManager.getInstance().flush()
                                val host = android.net.Uri.parse(url).host.orEmpty()
                                if (host.contains("accounts.google") && (url.contains("rejected") || url.contains("disallowed"))) {
                                    Diag.log("youtube", "Google refused sign-in in the app: $url"); refused = true
                                }
                                if (host.endsWith("youtube.com") && YouTubeAccount.signedIn()) {
                                    Diag.log("youtube", "signed in")
                                    finish(true)
                                }
                            }
                        }
                        loadUrl(YouTubeAccount.LOGIN_URL)
                    }
                }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
