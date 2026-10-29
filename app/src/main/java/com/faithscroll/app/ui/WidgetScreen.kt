package com.faithscroll.app.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.data.Prefs
import com.faithscroll.app.data.VerseRepository
import com.faithscroll.app.widget.VerseWidgetProvider
import kotlin.math.roundToInt

@Composable
fun WidgetScreen(state: AppState, bottomInset: Dp) {
    val ctx = LocalContext.current
    var opacity by remember { mutableFloatStateOf(Prefs.widgetOpacity(ctx).toFloat()) }
    var lightText by remember { mutableStateOf(Prefs.widgetLightText(ctx)) }
    var auto by remember { mutableStateOf(Prefs.widgetAutoChange(ctx)) }
    var previewRef by remember { mutableStateOf(Prefs.widgetRef(ctx)) }
    val preview = remember(previewRef) { VerseRepository.byRef(ctx, previewRef) ?: VerseRepository.all(ctx).first() }

    fun apply() = VerseWidgetProvider.refresh(ctx)

    Column(
        Modifier.fillMaxSize().background(Ink).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomInset + 24.dp)
    ) {
        Text("Home screen widget", color = Color.White, fontSize = 28.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))

        // Live preview over a mock wallpaper so transparency is visible
        Box(
            Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2E8B83), Color(0xFF6A4C93), Color(0xFFF7B267)))),
            contentAlignment = Alignment.Center
        ) {
            val bgColor by animateColorAsState(if (lightText) Color(0xFF16112A) else Color(0xFFFBF6EC), label = "bg")
            val textColor = if (lightText) Color.White else Plum
            Column(
                Modifier.padding(18.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(bgColor.copy(alpha = opacity / 100f))
                    .padding(18.dp)
            ) {
                Text("“${preview.text}”", color = textColor, fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 5)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("— ${preview.ref}", color = if (lightText) Gold else Color(0xFF8A5A00), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.Refresh, null, tint = textColor, modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Background", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                when { opacity < 5 -> "Transparent"; opacity < 85 -> "Semi-transparent · ${opacity.roundToInt()}%"; else -> "Solid" },
                color = Gold, fontSize = 14.sp
            )
        }
        Slider(
            value = opacity, onValueChange = { opacity = it }, valueRange = 0f..100f,
            onValueChangeFinished = { Prefs.setWidgetOpacity(ctx, opacity.roundToInt()); apply() },
            colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Clear" to 0f, "Semi" to 35f, "Frosted" to 65f, "Solid" to 100f).forEach { (label, v) ->
                Chip(label, selected = opacity.roundToInt() == v.roundToInt()) {
                    opacity = v; Prefs.setWidgetOpacity(ctx, v.roundToInt()); apply()
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("Text style", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Light text", lightText) { lightText = true; Prefs.setWidgetLightText(ctx, true); apply() }
            Chip("Dark text", !lightText) { lightText = false; Prefs.setWidgetLightText(ctx, false); apply() }
        }
        Text("Tip: use light text on dark wallpapers, dark text on light ones.", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("New verse every hour", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("Or tap ↻ on the widget anytime", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
            }
            Switch(checked = auto, onCheckedChange = { auto = it; Prefs.setWidgetAutoChange(ctx, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Gold, checkedThumbColor = Ink))
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ok = mgr.isRequestPinAppWidgetSupported &&
                    mgr.requestPinAppWidget(ComponentName(ctx, VerseWidgetProvider::class.java), null, null)
                if (!ok) Toast.makeText(ctx, "Long-press your home screen → Widgets → FaithScroll", Toast.LENGTH_LONG).show()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
        ) {
            Icon(Icons.Filled.AddToHomeScreen, null); Spacer(Modifier.width(8.dp)); Text("Add widget to home screen", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { VerseWidgetProvider.nextVerse(ctx); previewRef = Prefs.widgetRef(ctx) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Icon(Icons.Filled.Refresh, null, tint = Gold); Spacer(Modifier.width(8.dp)); Text("Show a new verse now", color = Gold)
        }

        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Plum).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.LocalFireDepartment, null, tint = Gold, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text("${state.streak.current}-day reading streak", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Best: ${state.streak.best} days · ${state.streak.totalDays} days in the Word", color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Scripture: King James Version (public domain). Videos stream from YouTube channels sharing testimonies and Bible teaching.",
            color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Gold else Color.Transparent)
            .border(1.dp, if (selected) Gold else Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (selected) Ink else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
