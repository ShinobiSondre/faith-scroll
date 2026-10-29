package com.faithscroll.app.ui

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.faithscroll.app.R
import com.faithscroll.app.data.StreamResolver
import com.faithscroll.app.data.VideoQuality
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.delay

/** Observable playback state the overlay (progress bar, buttons) draws from. */
@Stable
class PlaybackState {
    var player by mutableStateOf<ExoPlayer?>(null)
    var ready by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var portrait by mutableStateOf(true)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffering by mutableStateOf(true)

    fun seekTo(ms: Long) { player?.seekTo(ms); position = ms }
}

/**
 * Plays a video natively from its direct stream URL. Only the card on screen
 * gets a player; others just show the thumbnail behind.
 */
@OptIn(UnstableApi::class)
@Composable
fun NativeVideo(
    videoId: String,
    paused: Boolean,
    muted: Boolean,
    loop: Boolean,
    fill: Boolean,
    state: PlaybackState,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val target = remember(videoId) { VideoQuality.targetHeight(ctx) }
    val player = remember(videoId) {
        // Start playing after half a second of video instead of the default 2.5 s.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, if (target <= 360) 20_000 else 40_000, 500, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val selector = DefaultTrackSelector(ctx).apply {
            setParameters(buildUponParameters().setMaxVideoBitrate(VideoQuality.maxBitrate(target)))
        }
        ExoPlayer.Builder(ctx).setLoadControl(load).setTrackSelector(selector).build().apply {
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }

    DisposableEffect(player) {
        state.player = player
        state.failed = false; state.ready = false; state.buffering = true
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                state.buffering = s == Player.STATE_BUFFERING || s == Player.STATE_IDLE
                if (s == Player.STATE_READY) state.ready = true
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { state.playing = isPlaying }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0) state.portrait = videoSize.height >= videoSize.width
            }
            override fun onPlayerError(error: PlaybackException) { state.failed = true }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            if (state.player === player) state.player = null
            state.playing = false
        }
    }

    LaunchedEffect(videoId) {
        val stream = StreamResolver.resolve(videoId)
        if (stream == null) { state.failed = true; return@LaunchedEffect }
        player.setMediaSource(StreamResolver.mediaSource(videoId, stream, target))
        player.prepare()
    }
    LaunchedEffect(paused, player) { player.playWhenReady = !paused }
    LaunchedEffect(muted, player) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(player) {
        while (true) {
            state.position = player.currentPosition
            state.duration = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }
    DisposableEffect(lifecycle, player) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> if (!paused) player.play()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    AndroidView(
        modifier = modifier,
        factory = { c ->
            (LayoutInflater.from(c).inflate(R.layout.video_surface, null, false) as PlayerView).also { it.player = player }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        },
        onRelease = { it.player = null }
    )
}
