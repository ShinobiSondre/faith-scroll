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
import com.faithscroll.app.data.Asr
import com.faithscroll.app.util.tr
import androidx.compose.runtime.collectAsState

/** Subtitles on/off, remembered between videos and launches. */
object CaptionSettings {
    private var loaded = false
    var on by mutableStateOf(false)
        private set

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        on = ctx.getSharedPreferences("captions", Context.MODE_PRIVATE).getBoolean("on", true) // on by default
    }

    fun toggle(ctx: Context) {
        on = !on
        ctx.getSharedPreferences("captions", Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
    }
}

/**
 * Live subtitles in the app language. Uses the video's own captions when YouTube provides them;
 * otherwise the phone listens and writes its own (see Asr), translated line by line.
 */
@Composable
fun CaptionOverlay(videoId: String, positionMs: Long, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var youtube by remember(videoId) { mutableStateOf<Transcript?>(null) }
    var useAsr by remember(videoId) { mutableStateOf(false) }
    LaunchedEffect(videoId) {
        val t = Transcripts.inAppLanguage(videoId, urgent = true) { partial -> youtube = partial }
        if (t == null) useAsr = true else youtube = t // no YouTube captions: the phone listens and makes them
    }

    var line: String? = null
    var status: String? = null
    if (youtube != null) {
        line = Transcripts.cueAt(youtube!!, positionMs)
    } else if (useAsr) {
        // Keep the phone's transcript running from where you're watching.
        // Start once the video is playing (so it loads first), then keep following the playhead.
        LaunchedEffect(videoId, positionMs / 5000) { if (positionMs >= 300) Asr.ensure(ctx, videoId, positionMs) }
        val live = remember(videoId) { Asr.live(videoId) }
        val cues by live.cues.collectAsState()
        val state by live.state.collectAsState()
        val progress by Asr.modelProgress.collectAsState()
        val raw = cues.lastOrNull { it.start <= positionMs && positionMs <= it.end + 900 }?.text
        var shown by remember(videoId) { mutableStateOf<Pair<String, String>?>(null) }
        LaunchedEffect(raw) { if (raw != null) shown = raw to Transcripts.translateLine(raw, Asr.engineLang[videoId] ?: "en") }
        line = shown?.takeIf { it.first == raw }?.second ?: raw
        if (line == null) status = when (state) {
            Asr.State.DOWNLOADING_MODEL -> tr("Getting subtitles ready (one-time download ${(progress * 100).toInt()} %)…", "Gjør klar undertekster (engangsnedlasting ${(progress * 100).toInt()} %) …")
            Asr.State.LOADING_AUDIO, Asr.State.IDLE -> tr("Preparing subtitles…", "Lager undertekster …")
            Asr.State.FAILED -> tr("Subtitles failed", "Undertekster feilet") + (live.error?.let { ": $it" } ?: "")
            else -> null
        }
    }

    if (line != null) {
        Text(
            line,
            color = Color.White, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 5.dp)
        )
    } else if (status != null) {
        Text(
            status, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp,
            modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
