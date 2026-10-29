package com.faithscroll.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.data.Prefs
import com.faithscroll.app.data.Verse
import com.faithscroll.app.data.VerseRepository
import com.faithscroll.app.data.Video
import com.faithscroll.app.data.VideoLibrary
import com.faithscroll.app.data.VideoRepository
import com.faithscroll.app.ui.AppState
import com.faithscroll.app.ui.ChapterSheet
import com.faithscroll.app.ui.FaithTheme
import com.faithscroll.app.ui.FeedScreen
import com.faithscroll.app.ui.Gold
import com.faithscroll.app.ui.Ink
import com.faithscroll.app.ui.SavedScreen
import com.faithscroll.app.ui.WidgetScreen
import com.faithscroll.app.ui.WatchScreen
import com.faithscroll.app.ui.PlayerScreen
import com.faithscroll.app.widget.VerseWidgetProvider
import com.faithscroll.app.update.UpdateOverlay
import com.faithscroll.app.update.Updater

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_REF = "verse_ref" }

    private val startRef = mutableStateOf<String?>(null)
    private val openFeed = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startRef.value = intent?.getStringExtra(EXTRA_REF)
        val streak = Prefs.recordOpen(this)
        Updater.start(this)
        setContent {
            val appCtx = applicationContext
            val state = remember { AppState(appCtx, streak) }
            FaithTheme { App(state, startRef.value, openFeed.intValue) }
        }
    }

    override fun onResume() {
        super.onResume()
        Updater.onResume(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_REF)?.let { startRef.value = it; openFeed.intValue++ }
    }
}

@Composable
private fun App(state: AppState, startRef: String?, openFeedSignal: Int) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var verses by remember { mutableStateOf(VerseRepository.all(ctx)) }
    var library by remember { mutableStateOf<VideoLibrary?>(null) }
    var player by remember { mutableStateOf<Pair<Video, List<Video>>?>(null) }
    val openVideo: (Video, List<Video>) -> Unit = { v, q -> player = v to q }
    var reading by remember { mutableStateOf<Verse?>(null) }
    LaunchedEffect(openFeedSignal) { if (openFeedSignal > 0) { player = null; tab = 0 } }

    LaunchedEffect(Unit) {
        library = VideoRepository.load(ctx)
        VerseRepository.refreshRemote(ctx)
        verses = VerseRepository.all(ctx)
        if (VerseWidgetProvider.hasWidgets(ctx)) VerseWidgetProvider.refresh(ctx)
    }

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = navInset + 64.dp

    Box(Modifier.fillMaxSize().background(Ink)) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "tabs"
        ) { t ->
            when (t) {
                0 -> key(startRef) {
                    // Testimonies and sermons take turns between the verses.
                    val feedVideos = remember(library) {
                        val l = library ?: return@remember emptyList<Video>()
                        val out = mutableListOf<Video>()
                        for (i in 0 until maxOf(l.stories.size, l.sermons.size)) {
                            l.stories.getOrNull(i)?.let(out::add)
                            l.sermons.getOrNull(i)?.let(out::add)
                        }
                        out
                    }
                    FeedScreen(
                        state, verses, feedVideos, startRef, bottomInset,
                        suspended = player != null,
                        onOpenVideo = { v -> openVideo(v, feedVideos) },
                        onReadChapter = { reading = it }
                    )
                }
                1 -> WatchScreen(library, bottomInset, openVideo)
                2 -> SavedScreen(state, verses, bottomInset, openVideo) { reading = it }
                else -> WidgetScreen(state, bottomInset)
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .navigationBarsPadding().padding(top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(Icons.Filled.Home, "Feed", tab == 0, Modifier.weight(1f)) { tab = 0 }
            NavItem(Icons.Filled.PlayCircle, "Watch", tab == 1, Modifier.weight(1f)) { tab = 1 }
            NavItem(Icons.Filled.Bookmarks, "Saved", tab == 2, Modifier.weight(1f)) { tab = 2 }
            NavItem(Icons.Filled.Widgets, "Widget", tab == 3, Modifier.weight(1f)) { tab = 3 }
        }

        player?.let { (v, q) -> PlayerScreen(v, q, state) { player = null } }

        reading?.let { v -> ChapterSheet(v) { reading = null } }

        UpdateOverlay()
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = if (selected) Gold else Color.White.copy(alpha = 0.7f), modifier = Modifier.size(26.dp))
        Text(label, color = if (selected) Gold else Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}
