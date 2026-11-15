package com.faithscroll.app.cast

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.data.Video
import com.faithscroll.app.ui.Gold
import com.faithscroll.app.ui.Ink
import com.faithscroll.app.ui.Plum
import com.faithscroll.app.util.tr
import kotlinx.coroutines.launch

/**
 * "Cast to TV": the TVs found on your Wi-Fi (tap one and the video continues there), and
 * screen mirroring for TVs without Google Cast – that shows the whole app on the TV.
 * [video]/[positionMs] = what to send (null from the Me tab: then only connect / mirror).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSheet(video: Video?, positionMs: () -> Long, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val devices by Cast.devices.collectAsState()
    val connected by Cast.connectedTo.collectAsState()
    val playing by Cast.playing.collectAsState()
    val status by Cast.status.collectAsState()
    val castOk = androidx.compose.runtime.remember { Cast.available(ctx) }
    DisposableEffect(Unit) { Cast.startSearch(ctx); onDispose { Cast.stopSearch() } }
    // TVs with the YouTube app (found the way YouTube's own cast button finds them).
    val tvs by Dial.tvs.collectAsState()
    val tvSearching by Dial.searching.collectAsState()
    val playingOn by Dial.playingOn.collectAsState()
    var tvStatus by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) { Dial.search(ctx) }
    val youTubeId = video?.takeIf { it.direct.isBlank() && !it.isTikTok }?.id

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Plum) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (connected != null) Icons.Filled.CastConnected else Icons.Filled.Cast, null, tint = Gold)
                Spacer(Modifier.width(10.dp))
                Text(tr("Cast to TV", "Cast til TV"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))

            // 0) TVs linked with a code: the TV's YouTube app plays the video, like a phone linked to it.
            var linked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(YouTubeLounge.screens(ctx)) }
            var codeOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            Text(tr("YouTube on your TV (linked with a code)", "YouTube på TV-en (koblet med kode)"), color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
            linked.forEach { sc ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.25f))
                        .clickable(enabled = youTubeId != null) {
                            val id = youTubeId ?: return@clickable
                            tvStatus = tr("Sending to ${sc.name}…", "Sender til ${sc.name} …")
                            scope.launch {
                                val err = YouTubeLounge.playOnScreen(sc.id, id, positionMs())
                                tvStatus = err?.let { tr("Didn't work ($it). Is YouTube open on the TV?", "Virket ikke ($it). Er YouTube åpen på TV-en?") }
                                if (err == null) { android.widget.Toast.makeText(ctx, tr("Playing on ${sc.name}", "Spilles på ${sc.name}"), android.widget.Toast.LENGTH_SHORT).show(); onDismiss() }
                            }
                        }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Tv, null, tint = Gold)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sc.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(tr("Opens in the TV's YouTube app", "Åpnes i TV-ens YouTube-app"), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                    }
                    Text(tr("Remove", "Fjern"), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
                        modifier = Modifier.clickable { YouTubeLounge.forget(ctx, sc.id); linked = YouTubeLounge.screens(ctx) }.padding(6.dp))
                }
            }
            Text("+ " + tr("Link a TV with a code", "Koble til TV med kode"), color = Gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { codeOpen = true }.padding(vertical = 8.dp))
            if (codeOpen) {
                var code by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
                var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                var err by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { codeOpen = false }, containerColor = Plum,
                    title = { Text(tr("Link your TV", "Koble til TV-en"), color = Color.White) },
                    text = {
                        Column {
                            Text(tr("On the TV: open YouTube → Settings → \"Link with TV code\". Type the numbers shown there.",
                                "På TV-en: åpne YouTube → Innstillinger → «Koble til med TV-kode». Skriv inn tallene som vises der."),
                                color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                            Spacer(Modifier.height(10.dp))
                            androidx.compose.material3.OutlinedTextField(
                                value = code, onValueChange = { code = it.filter { c -> c.isDigit() || c == ' ' }.take(20) },
                                placeholder = { Text("123 456 789 012") }, singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, cursorColor = Gold, focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                            )
                            err?.let { Text(it, color = Gold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                val (sc, e) = YouTubeLounge.pair(ctx, code)
                                busy = false
                                if (sc != null) { linked = YouTubeLounge.screens(ctx); codeOpen = false; tvStatus = tr("Linked: ${sc.name}", "Koblet til: ${sc.name}") } else err = e
                            }
                        }) { Text(if (busy) tr("Linking…", "Kobler til …") else tr("Link", "Koble til"), color = Gold) }
                    },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { codeOpen = false }) { Text(tr("Cancel", "Avbryt"), color = Color.White) } }
                )
            }
            Spacer(Modifier.height(12.dp))

            // 1) TVs with YouTube: the video opens in the TV's YouTube app at the same second.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("TVs on your Wi-Fi", "TV-er på Wi-Fi-en"),
                    color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (!tvSearching) Text(tr("Search again", "Søk igjen"), color = Gold, fontSize = 13.sp,
                    modifier = Modifier.clickable { scope.launch { Dial.search(ctx) } }.padding(6.dp))
            }
            if (video != null && video.isTikTok) Text(
                tr("TikToks can't be sent to the TV – use \"Show the whole app\" below.", "TikToks kan ikke sendes til TV-en – bruk «Vis hele appen» under."),
                color = Gold, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
            if (tvs.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                if (tvSearching) CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (tvSearching) tr("Looking for TVs on your Wi-Fi…", "Leter etter TV-er på Wi-Fi-en …")
                    else tr("No TV found. Is the TV on and on the same Wi-Fi?", "Fant ingen TV. Er TV-en på og på samme Wi-Fi?"),
                    color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
            }
            tvs.forEach { tv ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.25f))
                        .clickable(enabled = video != null && (youTubeId != null || tv.canPlayLink)) {
                            val v = video ?: return@clickable
                            tvStatus = tr("Starting on ${tv.name}…", "Starter på ${tv.name} …")
                            scope.launch {
                                // The stream link for the TV's own player: one file with picture and sound.
                                val link: suspend () -> String? = {
                                    if (v.isTikTok) null
                                    else v.direct.ifBlank { null } ?: com.faithscroll.app.data.StreamResolver.resolve(v.id)?.muxed
                                        ?.filter { it.height <= 720 }?.maxByOrNull { it.height }?.url
                                }
                                val err = Dial.play(tv, youTubeId, link, v.title, positionMs())
                                tvStatus = err
                                if (err == null) {
                                    android.widget.Toast.makeText(ctx, tr("Playing on ${tv.name}", "Spilles på ${tv.name}"), android.widget.Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                            }
                        }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Tv, null, tint = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tv.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(if (playingOn == tv.name) tr("Playing now", "Spiller nå")
                            else if (tv.canYouTube && youTubeId != null) tr("Opens in the TV's YouTube app", "Åpnes i TV-ens YouTube-app")
                            else tr("Plays in the TV's own player", "Spilles i TV-ens egen spiller"),
                            color = if (playingOn == tv.name) Gold else Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                    }
                    if (playingOn == tv.name) Text(tr("Stop", "Stopp"), color = Gold, fontSize = 13.sp,
                        modifier = Modifier.clickable { scope.launch { Dial.stop(tv) } }.padding(6.dp))
                }
            }
            tvStatus?.let { Text(it, color = Gold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(16.dp))

            connected?.let { name ->
                Text(tr("Connected to $name", "Koblet til $name"), color = Gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (video != null && playing != video.id) Button(
                        onClick = { Cast.castVideo(video, positionMs()) },
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
                    ) { Text(tr("Play this video on the TV", "Spill denne på TV-en")) }
                    Button(
                        onClick = { Cast.disconnect(); onDismiss() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f), contentColor = Color.White)
                    ) { Text(tr("Stop casting", "Stopp casting")) }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (castOk) {
                Text(tr("Google Cast (like the YouTube app)", "Google Cast (som i YouTube-appen)"),
                    color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                if (devices.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                    CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(tr("Looking for TVs…", "Leter etter TV-er …"), color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                }
                devices.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.25f))
                            .clickable {
                                if (video != null) Cast.castTo(ctx, d, video, positionMs()) else Cast.castTo(ctx, d, Video("", "", ""), 0L)
                            }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Tv, null, tint = Color.White)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            if (d.description.isNotBlank()) Text(d.description, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                        }
                        if (connected == d.name) Icon(Icons.Filled.CastConnected, null, tint = Gold)
                    }
                }
                status?.let { Text(it, color = Gold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(18.dp))
            }

            // Whole screen – works with Samsung and most other smart TVs.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.1f))
                    .clickable {
                        if (!Cast.mirror(ctx)) Toast.makeText(ctx, tr("Swipe down from the top of the screen and tap Smart View / Screen cast.",
                            "Sveip ned fra toppen av skjermen og trykk Smart View / Skjermdeling."), Toast.LENGTH_LONG).show()
                        onDismiss()
                    }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.ScreenShare, null, tint = Gold)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Show the whole app on the TV", "Vis hele appen på TV-en"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(tr("Screen mirroring (Smart View on Samsung) – for any TV, also Bible reading and verses.",
                        "Skjermdeling (Smart View på Samsung) – for alle TV-er, også bibellesing og vers."), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
        }
    }
}
