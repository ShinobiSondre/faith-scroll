package com.faithscroll.app.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.ui.Gold
import com.faithscroll.app.ui.Ink
import com.faithscroll.app.ui.Plum
import kotlin.math.roundToInt

/** The update card shown over the app while an update is on its way. */
@Composable
fun UpdateOverlay() {
    val u = Updater
    val ctx = LocalContext.current
    val show = u.stage != UpdateStage.Idle
    AnimatedVisibility(show, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(remember { MutableInteractionSource() }, null) { },
            contentAlignment = Alignment.Center
        ) {
            val (icon, title, body) = when (u.stage) {
                UpdateStage.Downloading -> Triple(Icons.Filled.Downloading, tr("A new FaithScroll is here!", "En ny FaithScroll er her!"), tr("Downloading build ${u.newBuild}…", "Laster ned versjon ${u.newBuild} …"))
                UpdateStage.NeedsPermission -> Triple(Icons.Filled.LockOpen, tr("One quick switch", "Én liten bryter"),
                    tr("Android needs to let FaithScroll update itself. Tap Allow, turn the switch on, then come back.", "Android må la FaithScroll oppdatere seg selv. Trykk Tillat, slå på bryteren og kom tilbake."))
                UpdateStage.Installing -> Triple(Icons.Filled.SystemUpdate, tr("Installing…", "Installerer …"),
                    tr("Tap Update. When it's done, tap Open to start the new version.", "Trykk Oppdater. Når den er ferdig, trykk Åpne for å starte den nye versjonen."))
                UpdateStage.Ready -> Triple(Icons.Filled.SystemUpdate, tr("Ready to update", "Klar til å oppdatere"), tr("Build ${u.newBuild} is downloaded.", "Versjon ${u.newBuild} er lastet ned."))
                UpdateStage.Failed -> Triple(Icons.Filled.CloudOff, tr("No update this time", "Ingen oppdatering nå"), u.error.orEmpty())
                UpdateStage.Idle -> Triple(Icons.Filled.SystemUpdate, "", "")
            }
            Column(
                Modifier.padding(24.dp).widthIn(max = 380.dp).clip(RoundedCornerShape(22.dp)).background(Plum)
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xFF3C2A6B), Color(0xFF1B1530))))
                        .padding(top = 22.dp, bottom = 18.dp, start = 20.dp, end = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Badge(icon, spinning = u.stage == UpdateStage.Downloading || u.stage == UpdateStage.Installing)
                    Spacer(Modifier.height(10.dp))
                    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                    if (u.newBuild > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(tr("Build ${u.currentBuild}  →  ${u.newBuild}", "Versjon ${u.currentBuild}  →  ${u.newBuild}"), color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                    }
                }
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
                    Text(body, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    if (u.notes.isNotBlank() && u.stage != UpdateStage.Failed) {
                        Spacer(Modifier.height(10.dp))
                        Text("“${u.notes}”", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontStyle = FontStyle.Italic,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    if (u.stage == UpdateStage.Downloading) {
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { u.progress },
                            modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(8.dp)),
                            color = Gold, trackColor = Color.White.copy(alpha = 0.12f)
                        )
                        Spacer(Modifier.height(6.dp))
                        val mb = u.totalBytes / (1024f * 1024f)
                        val pct = (u.progress * 100).roundToInt().coerceIn(0, 100)
                        Text(
                            if (mb > 0) "$pct %  ·  ${"%.1f".format(mb * u.progress)} / ${"%.1f".format(mb)} MB" else "$pct %",
                            color = Color.White.copy(alpha = 0.6f), fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                    if (u.stage != UpdateStage.Failed) TextButton(onClick = { u.later() }) { Text(tr("Later", "Senere"), color = Color.White.copy(alpha = 0.8f)) }
                    val gold = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
                    when (u.stage) {
                        UpdateStage.NeedsPermission -> Button(onClick = { u.allowInstalls(ctx) }, colors = gold) { Text(tr("Allow", "Tillat")) }
                        UpdateStage.Ready -> Button(onClick = { u.installNow(ctx) }, colors = gold) { Text(tr("Update now", "Oppdater nå")) }
                        UpdateStage.Failed -> Button(onClick = { u.later() }, colors = gold) { Text("OK") }
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** Round badge with a slowly turning golden ring while something is happening. */
@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, spinning: Boolean) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "a")
    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(72.dp).rotate(if (spinning) angle else 0f)) {
            drawCircle(Color.White.copy(alpha = 0.14f))
            drawArc(Gold, startAngle = 0f, sweepAngle = 270f, useCenter = false, style = Stroke(width = 3.dp.toPx()))
        }
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(32.dp))
    }
}
