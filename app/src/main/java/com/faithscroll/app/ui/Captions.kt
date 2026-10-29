package com.faithscroll.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.data.Transcript
import com.faithscroll.app.data.Transcripts

/** Subtitles on/off, remembered between videos and launches. */
object CaptionSettings {
    private var loaded = false
    var on by mutableStateOf(false)
        private set

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        on = ctx.getSharedPreferences("captions", Context.MODE_PRIVATE).getBoolean("on", false)
    }

    fun toggle(ctx: Context) {
        on = !on
        ctx.getSharedPreferences("captions", Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
    }
}

/**
 * Live subtitles in the app language: the video's captions, synced to playback and translated
 * on the phone when the video speaks another language.
 */
@Composable
fun CaptionOverlay(videoId: String, positionMs: Long, modifier: Modifier = Modifier) {
    var transcript by remember(videoId) { mutableStateOf<Transcript?>(null) }
    var missing by remember(videoId) { mutableStateOf(false) }
    LaunchedEffect(videoId) {
        val t = Transcripts.inAppLanguage(videoId) { partial -> transcript = partial }
        if (t == null) missing = true else transcript = t
    }
    val line = transcript?.let { Transcripts.cueAt(it, positionMs) }
    if (line != null) {
        Text(
            line,
            color = Color.White, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 5.dp)
        )
    } else if (missing) {
        Text(
            com.faithscroll.app.util.tr("No subtitles for this video", "Ingen undertekster for denne videoen"),
            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
            modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
