package com.faithscroll.app.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.data.YouTubeSubs
import com.faithscroll.app.util.tr
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.launch

private const val YT_SCOPE = "https://www.googleapis.com/auth/youtube.readonly"

/** Connect YouTube: pick subscribed channels whose new videos should be favourites in your feed. */
@Composable
fun YouTubeCard(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val scope = rememberCoroutineScope()
    var subs by remember { mutableStateOf(YouTubeSubs.all(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var setupHelp by remember { mutableStateOf<String?>(null) }

    fun afterImport(n: Int) {
        com.faithscroll.app.data.Diag.log("youtube", "imported $n channels")
        subs = YouTubeSubs.all(ctx)
        Toast.makeText(ctx, tr("Found $n channels. Pick your favourites.", "Fant $n kanaler. Velg favorittene dine."), Toast.LENGTH_SHORT).show()
        picking = true
    }

    fun fetch(token: String?) {
        if (token.isNullOrBlank()) { busy = false; return }
        scope.launch {
            runCatching { YouTubeSubs.fetchWithToken(ctx, token) }
                .onSuccess { afterImport(it) }
                .onFailure { com.faithscroll.app.data.Diag.err("youtube", "reading subscriptions failed", it); setupHelp = it.message }
            busy = false
        }
    }

    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val token = runCatching { activity?.let { Identity.getAuthorizationClient(it).getAuthorizationResultFromIntent(res.data).accessToken } }.getOrNull()
        if (token == null) { busy = false; com.faithscroll.app.data.Diag.log("youtube", "sign-in returned no token (resultCode=${res.resultCode})") }
        fetch(token)
    }

    val takeout = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching { YouTubeSubs.importTakeout(ctx, uri) }
                .onSuccess { afterImport(it) }
                .onFailure { com.faithscroll.app.data.Diag.err("youtube", "Takeout import failed", it); Toast.makeText(ctx, it.message ?: "Error", Toast.LENGTH_LONG).show() }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Plum).padding(18.dp)) {
        Text("▶ YouTube", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            tr("Bring in channels you subscribe to. Your favourites' new videos show up first in your feed.",
                "Hent inn kanaler du abonnerer på. Nye videoer fra favorittene dine vises først i feeden."),
            color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val act = activity ?: return@Button
                busy = true
                val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(YT_SCOPE))).build()
                Identity.getAuthorizationClient(act).authorize(req)
                    .addOnSuccessListener { r ->
                        val pi = r.pendingIntent
                        if (r.hasResolution() && pi != null) signIn.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        else fetch(r.accessToken)
                    }
                    .addOnFailureListener { e -> busy = false; com.faithscroll.app.data.Diag.err("youtube", "Google sign-in failed", e); setupHelp = e.message ?: e.toString() }
            },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) tr("Connecting…", "Kobler til …") else tr("Sign in with Google", "Logg inn med Google")) }
        OutlinedButton(
            onClick = { takeout.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/zip", "application/octet-stream")) },
            enabled = !busy, modifier = Modifier.fillMaxWidth()
        ) { Text(tr("Import from Google Takeout", "Importer fra Google Takeout"), color = Gold) }

        if (subs.isNotEmpty()) {
            val favs = subs.count { it.favourite }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("$favs of ${subs.size} channels are favourites", "$favs av ${subs.size} kanaler er favoritter"),
                    color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { picking = true }) { Text(tr("Choose", "Velg"), color = Gold) }
            }
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false; onChanged() },
            containerColor = Plum,
            title = { Text(tr("Favourite channels", "Favorittkanaler"), color = Color.White) },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { YouTubeSubs.setAllFavourite(ctx, true); subs = YouTubeSubs.all(ctx) }) { Text(tr("All", "Alle"), color = Gold) }
                        TextButton(onClick = { YouTubeSubs.setAllFavourite(ctx, false); subs = YouTubeSubs.all(ctx) }) { Text(tr("None", "Ingen"), color = Gold) }
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(subs, key = { it.id }) { c ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    YouTubeSubs.setFavourite(ctx, c.id, !c.favourite); subs = YouTubeSubs.all(ctx)
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = c.favourite,
                                    onCheckedChange = { YouTubeSubs.setFavourite(ctx, c.id, it); subs = YouTubeSubs.all(ctx) },
                                    colors = CheckboxDefaults.colors(checkedColor = Gold, checkmarkColor = Ink)
                                )
                                Text(c.title, color = Color.White, fontSize = 15.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false; onChanged() }) { Text(tr("Done", "Ferdig"), color = Gold) } }
        )
    }

    setupHelp?.let { msg ->
        AlertDialog(
            onDismissRequest = { setupHelp = null },
            containerColor = Plum,
            title = { Text(tr("Google sign-in isn't set up yet", "Google-innlogging er ikke satt opp ennå"), color = Color.White) },
            text = {
                Text(
                    tr("FaithScroll must be registered once in a Google Cloud project before Google lets it read your subscriptions. Until then, use \"Import from Google Takeout\" (takeout.google.com → select only YouTube → subscriptions).",
                        "FaithScroll må registreres én gang i et Google Cloud-prosjekt før Google lar den lese abonnementene dine. Til da kan du bruke «Importer fra Google Takeout» (takeout.google.com → velg bare YouTube → abonnementer).") +
                        "\n\n($msg)",
                    color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp
                )
            },
            confirmButton = { TextButton(onClick = { setupHelp = null }) { Text("OK", color = Gold) } }
        )
    }
}
