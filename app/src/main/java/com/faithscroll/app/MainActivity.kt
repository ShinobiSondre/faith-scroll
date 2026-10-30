package com.faithscroll.app

import com.faithscroll.app.util.tr

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
import androidx.compose.runtime.collectAsState
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
import com.faithscroll.app.data.Taste
import com.faithscroll.app.ui.OnboardingScreen
import androidx.compose.material.icons.filled.Person
import com.faithscroll.app.data.Verse
import com.faithscroll.app.data.VerseRepository
import com.faithscroll.app.data.Video
import com.faithscroll.app.data.VideoLibrary
import com.faithscroll.app.data.SocialLibrary
import com.faithscroll.app.data.SocialRepository
import com.faithscroll.app.ui.VerseOverlay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalConfiguration
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
    private val crashReport = mutableStateOf<String?>(null)
    private val openFeed = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.faithscroll.app.util.Lang.init(this)
        // Save the details of any crash so they can be shown (and copied) on the next launch.
        val crashFile = java.io.File(filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                crashFile.writeText("FaithScroll ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n\n" + e.stackTraceToString().take(6000))
            }
            previous?.uncaughtException(t, e)
        }
        crashReport.value = crashFile.takeIf { it.exists() }?.readText()?.also { crashFile.delete() }
        enableEdgeToEdge()
        startRef.value = intent?.getStringExtra(EXTRA_REF)
        val streak = Prefs.recordOpen(this)
        Updater.start(this)
        com.faithscroll.app.prayer.Reminders.schedule(this)
        com.faithscroll.app.data.StreamResolver.setup(this)
        // Let the stream finder pass YouTube's bot check (proof-of-origin tokens), so playback,
        // audio for subtitles and captions aren't cut off with HTTP 403.
        com.faithscroll.app.potoken.AppContext.set(this)
        // Subtitles are on by default: fetch the speech model in the background now, not on first use.
        com.faithscroll.app.data.Asr.prepare(this) // speech model ready for "What do they mean here?"
        org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
            .setPoTokenProvider(com.faithscroll.app.potoken.PoTokenProviderImpl)
        setContent {
            val appCtx = applicationContext
            val state = remember { AppState(appCtx, streak) }
            FaithTheme {
                App(state, startRef.value, openFeed.intValue)
                crashReport.value?.let { report ->
                    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { crashReport.value = null },
                        containerColor = com.faithscroll.app.ui.Plum,
                        title = { Text(com.faithscroll.app.util.tr("Sorry, FaithScroll crashed", "Beklager, FaithScroll krasjet"), color = Color.White) },
                        text = {
                            Text(
                                com.faithscroll.app.util.tr("Copy the details and send them to get it fixed.", "Kopier detaljene og send dem, så kan det fikses.") +
                                    "\n\n" + report.lineSequence().take(6).joinToString("\n"),
                                color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                clip.setText(androidx.compose.ui.text.AnnotatedString(report)); crashReport.value = null
                            }) { Text(com.faithscroll.app.util.tr("Copy details", "Kopier detaljer"), color = Gold) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { crashReport.value = null }) {
                                Text("OK", color = Color.White.copy(alpha = 0.7f))
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) com.faithscroll.app.data.CaptionGrabber.release()
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
    var social by remember { mutableStateOf<SocialLibrary?>(null) }
    var socialVersion by remember { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<Pair<Video, List<Video>>?>(null) }
    var playerFromFeed by remember { mutableStateOf(false) }
    val openVideo: (Video, List<Video>) -> Unit = { v, q -> playerFromFeed = false; player = v to q }
    var reading by remember { mutableStateOf<Verse?>(null) }
    var widgetVerse by remember { mutableStateOf<Verse?>(null) }
    var onboarding by remember { mutableStateOf(!Taste.isOnboarded(ctx)) }
    var tasteVersion by remember { mutableIntStateOf(0) }
    var beStill by remember { mutableStateOf(false) }
    var webPage by remember { mutableStateOf<String?>(null) }
    var askReminders by remember { mutableStateOf(false) }
    // Android 13+ needs permission to show the prayer reminders.
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    // A tap on the home-screen widget opens that verse.
    LaunchedEffect(openFeedSignal, startRef) {
        VerseRepository.byRef(ctx, startRef)?.let { widgetVerse = it; player = null }
    }

    LaunchedEffect(Unit) {
        library = VideoRepository.load(ctx)
        VerseRepository.refreshRemote(ctx)
        verses = VerseRepository.all(ctx)
        if (VerseWidgetProvider.hasWidgets(ctx)) VerseWidgetProvider.refresh(ctx)
    }
    // Subscribing on a video reloads the library so that channel's videos join the feed.
    val subsChanged by com.faithscroll.app.data.YouTubeSubs.changed.collectAsState()
    LaunchedEffect(subsChanged) { if (subsChanged > 0) socialVersion++ }
    LaunchedEffect(socialVersion) {
        social = SocialRepository.load(ctx)
        if (socialVersion > 0) library = VideoRepository.load(ctx) // e.g. new favourite YouTube channels
    }
    // Existing users get the one-time prayer reminder invitation too.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4000)
        if (!onboarding && !com.faithscroll.app.prayer.Reminders.asked(ctx)) askReminders = true
    }
    // Always looking for something new: fresh searches every launch, new subreddits now and then.
    var discovered by remember { mutableStateOf<List<Video>>(emptyList()) }
    LaunchedEffect(Unit) {
        discovered = com.faithscroll.app.data.Discovery.search(ctx)
        com.faithscroll.app.data.Discovery.maybeFindSubreddits(ctx)
    }

    // Phone upright: tabs at the bottom. Turned sideways, unfolded or tablet: a rail on the side.
    val cfg = LocalConfiguration.current
    val sideNav = cfg.screenWidthDp >= 600 && cfg.screenWidthDp > cfg.screenHeightDp || cfg.screenWidthDp >= 840
    val twoPane = cfg.screenWidthDp >= 700 && cfg.screenHeightDp >= 560
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = if (sideNav) navInset + 8.dp else navInset + 64.dp

    val tabs = listOf(
        Triple(Icons.Filled.Home, tr("Feed", "Feed"), 0), Triple(Icons.Filled.PlayCircle, tr("Watch", "Se"), 1),
        Triple(Icons.Filled.Bookmarks, tr("Saved", "Lagret"), 2), Triple(Icons.Filled.Person, tr("Me", "Meg"), 3)
    )

    // Mostly videos: shorts, testimonies and sermons take turns.
    val feedVideos = remember(library, social, discovered) {
        val l = library
        // YouTube is the only video source (every video gets captions, translation and Ask).
        val clips = emptyList<Video>()
        if (l == null && discovered.isEmpty()) return@remember emptyList<Video>()
        val shorts = l?.shorts.orEmpty(); val stories = l?.stories.orEmpty(); val sermons = l?.sermons.orEmpty()
        val out = mutableListOf<Video>()
        for (i in 0 until maxOf(stories.size, sermons.size, shorts.size, clips.size, discovered.size)) {
            shorts.getOrNull(i)?.let(out::add)
            clips.getOrNull(i)?.let(out::add)
            discovered.getOrNull(i)?.let(out::add)
            stories.getOrNull(i)?.let(out::add)
            sermons.getOrNull(i)?.let(out::add)
        }
        // Norwegian only: the built-in channels are English, so the Norwegian finds go first.
        (if (com.faithscroll.app.util.Lang.no && !com.faithscroll.app.util.Lang.alsoEnglish) discovered + out else out).distinctBy { it.id }
    }

    androidx.compose.runtime.CompositionLocalProvider(com.faithscroll.app.ui.LocalOpenPage provides { url -> webPage = url }) {
    // Check videos in the background: feed candidates first, then the rest of Watch.
    LaunchedEffect(feedVideos, library) {
        com.faithscroll.app.data.VideoCheck.queue(ctx, (feedVideos + (library?.all ?: emptyList())).distinctBy { it.id })
    }
    Box(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxSize()) {
            if (sideNav) {
                Column(
                    Modifier.fillMaxHeight().width(84.dp).background(Color.Black)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("✦", color = Gold, fontSize = 22.sp)
                    tabs.forEach { (icon, label, i) -> NavItem(icon, label, tab == i, Modifier.fillMaxWidth()) { tab = i } }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
            // The feed stays alive under the other tabs, so you come back to the exact same
            // card and moment in the video. It just pauses while you're elsewhere.
            key(tasteVersion) {
                val ok by com.faithscroll.app.data.VideoCheck.verified.collectAsState()
                val verifiedFeed = remember(feedVideos, ok) { feedVideos.filter { it.id in ok } }
                FeedScreen(
                    state, verifiedFeed, social, bottomInset,
                    suspended = tab != 0 || player != null || widgetVerse != null || onboarding || beStill || webPage != null,
                    twoPane = twoPane,
                    onOpenVideo = { v -> openVideo(v, verifiedFeed); playerFromFeed = true },
                    onBeStill = { beStill = true }
                )
            }
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                modifier = Modifier.fillMaxSize(),
                label = "tabs"
            ) { t ->
                when (t) {
                    0 -> Unit // the feed underneath shows through
                    1 -> {
                        val ok by com.faithscroll.app.data.VideoCheck.verified.collectAsState()
                        val checkedLibrary = remember(library, ok) {
                            library?.let { l -> VideoLibrary(l.stories.filter { it.id in ok }, l.sermons.filter { it.id in ok }, l.shorts.filter { it.id in ok }) }
                        }
                        WatchScreen(checkedLibrary, bottomInset, openVideo)
                    }
                    2 -> SavedScreen(state, verses, bottomInset, openVideo) { reading = it }
                    else -> WidgetScreen(
                        state, bottomInset, onSourcesChanged = { socialVersion++ }, onBeStill = { beStill = true },
                        onNeedNotifications = {
                            if (android.os.Build.VERSION.SDK_INT >= 33) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    ) { onboarding = true }
                }
            }
            }
        }

        if (!sideNav) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .navigationBarsPadding().padding(top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { (icon, label, i) -> NavItem(icon, label, tab == i, Modifier.weight(1f)) { tab = i } }
            }
        }

        widgetVerse?.let { v -> VerseOverlay(v, state, onRead = { reading = it }) { widgetVerse = null } }

        player?.let { (v, q) ->
            PlayerScreen(v, q, state, onLeaveAt = { if (playerFromFeed) com.faithscroll.app.data.PlaybackMemory.returnTo.value = it }) { player = null }
        }

        if (onboarding) OnboardingScreen {
            onboarding = false; tasteVersion++; tab = 0
            if (!com.faithscroll.app.prayer.Reminders.asked(ctx)) askReminders = true
        }

        if (beStill) com.faithscroll.app.prayer.BeStillScreen(onClose = { beStill = false })

        webPage?.let { url -> com.faithscroll.app.ui.WebPageScreen(url) { webPage = null } }

        // One-time invitation to turn on prayer reminders.
        if (askReminders) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { askReminders = false; com.faithscroll.app.prayer.Reminders.setAsked(ctx) },
                containerColor = com.faithscroll.app.ui.Plum,
                title = { Text(com.faithscroll.app.util.tr("Prayer reminders?", "Bønnepåminnelser?"), color = Color.White) },
                text = {
                    Text(
                        com.faithscroll.app.util.tr(
                            "A gentle nudge to pause and pray: morning, midday and evening. You can change the times in Me.",
                            "Et vennlig dytt om å stoppe opp og be: morgen, midt på dagen og kveld. Du kan endre tidene under Meg."
                        ), color = Color.White.copy(alpha = 0.8f)
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        com.faithscroll.app.prayer.Reminders.setCount(ctx, 3)
                        com.faithscroll.app.prayer.Reminders.setAsked(ctx)
                        askReminders = false
                        if (android.os.Build.VERSION.SDK_INT >= 33) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text(com.faithscroll.app.util.tr("Yes, remind me", "Ja, minn meg på det"), color = com.faithscroll.app.ui.Gold) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { askReminders = false; com.faithscroll.app.prayer.Reminders.setAsked(ctx) }) {
                        Text(com.faithscroll.app.util.tr("Not now", "Ikke nå"), color = Color.White.copy(alpha = 0.7f))
                    }
                }
            )
        }

        reading?.let { v -> ChapterSheet(v) { reading = null } }

        UpdateOverlay()
    }
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
