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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Plum) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (connected != null) Icons.Filled.CastConnected else Icons.Filled.Cast, null, tint = Gold)
                Spacer(Modifier.width(10.dp))
                Text(tr("Cast to TV", "Cast til TV"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))

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
                Text(tr("TVs on your Wi-Fi (Chromecast, Google TV, Cast TVs)", "TV-er på Wi-Fi-en (Chromecast, Google TV, Cast-TV-er)"),
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
