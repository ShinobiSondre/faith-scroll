package com.faithscroll.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.faithscroll.app.data.Encouragement
import com.faithscroll.app.data.FeedEngine
import com.faithscroll.app.data.Prefetcher
import com.faithscroll.app.data.MessageRepository
import com.faithscroll.app.data.SocialLibrary
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.faithscroll.app.data.FeedItem
import com.faithscroll.app.data.Taste
import com.faithscroll.app.data.Verse
import com.faithscroll.app.data.Video
import com.faithscroll.app.util.Share
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(
    state: AppState,
    videos: List<Video>,
    social: SocialLibrary?,
    bottomInset: androidx.compose.ui.unit.Dp,
    suspended: Boolean = false,
    twoPane: Boolean = false,
    onOpenVideo: (Video) -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestVideos by rememberUpdatedState(videos)
    val latestSocial by rememberUpdatedState(social)
    val engine = remember { FeedEngine(ctx, MessageRepository.all(ctx), { latestVideos }, { latestSocial }) }
    val items = remember(engine) { mutableStateListOf<FeedItem>().apply { add(engine.next()) } }
    val pager = rememberPagerState(pageCount = { items.size })

    // Keep cards ready ahead. While videos are still loading, only one card is made,
    // so the feed doesn't fill up with illustrations before the videos arrive.
    LaunchedEffect(pager.currentPage, items.size, videos.isNotEmpty()) {
        val ahead = if (videos.isEmpty()) 1 else 5
        while (items.size <= pager.currentPage + ahead) items.add(engine.next())
    }

    // Like TikTok: get the next few videos ready while you watch this one.
    LaunchedEffect(pager.settledPage, items.size) {
        Prefetcher.ahead(ctx, (pager.settledPage + 1 until minOf(items.size, pager.settledPage + 7)).map { items[it] })
    }

    // Learn from time spent on each card.
    LaunchedEffect(pager) {
        var lastPage = pager.settledPage
        var since = System.currentTimeMillis()
        snapshotFlow { pager.settledPage }.collect { page ->
            if (page != lastPage) {
                val dwell = System.currentTimeMillis() - since
                when (val it = items.getOrNull(lastPage)) {
                    is FeedItem.MessageItem -> Taste.messageSeen(ctx, it.message, dwell)
                    is FeedItem.VideoItem -> Taste.videoSeen(ctx, it.video, dwell)
                    is FeedItem.PinItem -> Taste.pinSeen(ctx, it.pin, dwell)
                    is FeedItem.TikTokItem -> Unit
                    null -> Unit
                }
                lastPage = page
                since = System.currentTimeMillis()
            }
        }
    }

    val feed: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.background(Ink)) {
            VerticalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { it }
            ) { page ->
                val offset = pageOffset(pager, page)
                val item = items[page]
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val o = abs(offset).coerceIn(0f, 1f)
                            val sc = 1f - 0.08f * o
                            scaleX = sc; scaleY = sc
                            alpha = 1f - 0.35f * o
                        }
                ) {
                    when (item) {
                        is FeedItem.MessageItem -> {
                            val m = item.message
                            MessageCard(
                                message = m,
                                reason = item.reason,
                                isCurrent = pager.currentPage == page,
                                saved = m.id in state.savedMessages,
                                liked = m.id in state.likedMessages,
                                onLike = {
                                    if (m.id !in state.likedMessages) Taste.messageAction(ctx, m, Taste.Action.LIKE)
                                    state.toggleMessageLike(m.id)
                                },
                                onDoubleTapLike = {
                                    if (m.id !in state.likedMessages) { Taste.messageAction(ctx, m, Taste.Action.LIKE); state.toggleMessageLike(m.id) }
                                },
                                onSave = {
                                    if (m.id !in state.savedMessages) Taste.messageAction(ctx, m, Taste.Action.SAVE)
                                    state.toggleMessageSave(m.id)
                                },
                                onShared = { Taste.messageAction(ctx, m, Taste.Action.SHARE) },
                                bottomInset = bottomInset
                            )
                        }
                        is FeedItem.VideoItem -> VideoCard(
                            video = item.video,
                            reason = item.reason,
                            onShared = { Taste.videoAction(ctx, item.video, Taste.Action.SHARE) },
                            isActive = pager.settledPage == page && !suspended,
                            preload = page == pager.settledPage + 1 && !suspended,
                            saved = state.isVideoSaved(item.video.id),
                            onSave = {
                                if (!state.isVideoSaved(item.video.id)) Taste.videoAction(ctx, item.video, Taste.Action.SAVE)
                                state.toggleVideo(item.video)
                            },
                            onExpand = { Taste.videoAction(ctx, item.video, Taste.Action.EXPAND); onOpenVideo(item.video) },
                            bottomInset = bottomInset
                        )
                        is FeedItem.PinItem -> {
                            val pn = item.pin
                            PinCard(
                                pin = pn, reason = item.reason,
                                saved = state.isPinSaved(pn.id), liked = pn.id in state.likedPins,
                                onLike = {
                                    if (pn.id !in state.likedPins) Taste.pinAction(ctx, pn, Taste.Action.LIKE)
                                    state.togglePinLike(pn.id)
                                },
                                onDoubleTapLike = { if (pn.id !in state.likedPins) { Taste.pinAction(ctx, pn, Taste.Action.LIKE); state.togglePinLike(pn.id) } },
                                onSave = {
                                    if (!state.isPinSaved(pn.id)) Taste.pinAction(ctx, pn, Taste.Action.SAVE)
                                    state.togglePin(pn)
                                },
                                onShared = { Taste.pinAction(ctx, pn, Taste.Action.SHARE) },
                                bottomInset = bottomInset
                            )
                        }
                        is FeedItem.TikTokItem -> TikTokCard(
                            tiktok = item.tiktok, reason = item.reason,
                            isActive = pager.settledPage == page && !suspended,
                            bottomInset = bottomInset
                        )
                    }
                }
            }
            FeedHeader(state)
        }
    }

    if (!twoPane) {
        feed(Modifier.fillMaxSize())
    } else {
        // Unfolded / tablet: the feed plus a side panel with details and what's next.
        Row(Modifier.fillMaxSize().background(Ink)) {
            feed(Modifier.weight(1.4f).fillMaxHeight())
            SidePanel(
                current = items.getOrNull(pager.currentPage),
                upcoming = (pager.currentPage + 1 until items.size).map { it to items[it] },
                state = state,
                onJump = { scope.launch { pager.animateScrollToPage(it) } },
                onOpenVideo = onOpenVideo,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun FeedHeader(state: AppState) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("FaithScroll", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.3f)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.LocalFireDepartment, null, tint = Gold, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(tr("${state.streak.current} day${if (state.streak.current == 1) "" else "s"}", "${state.streak.current} ${if (state.streak.current == 1) "dag" else "dager"}"), color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SidePanel(
    current: FeedItem?,
    upcoming: List<Pair<Int, FeedItem>>,
    state: AppState,
    onJump: (Int) -> Unit,
    onOpenVideo: (Video) -> Unit,
    modifier: Modifier
) {
    val ctx = LocalContext.current
    LazyColumn(
        modifier.background(Plum.copy(alpha = 0.6f)).statusBarsPadding().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)
    ) {
        item {
            when (current) {
                is FeedItem.VideoItem -> {
                    val v = current.video
                    Text(if (v.kind == Video.KIND_SERMON) tr("SERMON", "PREKEN") else tr("STORY OF FAITH", "TROSHISTORIE"), color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(v.title, color = Color.White, fontSize = 22.sp, lineHeight = 28.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(v.channel, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
                    current.reason?.let { Spacer(Modifier.height(12.dp)); ReasonTag(it) }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val saved = state.isVideoSaved(v.id)
                        PanelButton(if (saved) tr("Saved", "Lagret") else tr("Save", "Lagre")) { state.toggleVideo(v) }
                        PanelButton(tr("Share", "Del")) { Share.video(ctx, v) }
                        PanelButton(tr("Full screen", "Fullskjerm")) { onOpenVideo(v) }
                    }
                }
                is FeedItem.MessageItem -> {
                    val m = current.message
                    Text(tr("A WORD FOR YOU", "ET ORD TIL DEG"), color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(m.text, color = Color.White, fontSize = 24.sp, lineHeight = 30.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
                    if (m.ref.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(m.ref, color = Gold, fontSize = 14.sp) }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PanelButton(if (m.id in state.savedMessages) tr("Saved", "Lagret") else tr("Save", "Lagre")) { state.toggleMessageSave(m.id) }
                        PanelButton(tr("Share", "Del")) { Share.message(ctx, m, renderMessage(m)) }
                    }
                }
                is FeedItem.PinItem -> {
                    val pn = current.pin
                    Text(if (pn.board.startsWith("r/")) tr("FROM REDDIT", "FRA REDDIT") else tr("FROM PINTEREST", "FRA PINTEREST"), color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(8.dp))
                    if (pn.title.isNotBlank()) Text(pn.title, color = Color.White, fontSize = 20.sp, lineHeight = 26.sp, fontFamily = FontFamily.Serif)
                    Text(pn.board, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    PanelButton(if (state.isPinSaved(pn.id)) tr("Saved", "Lagret") else tr("Save", "Lagre")) { state.togglePin(pn) }
                }
                is FeedItem.TikTokItem -> {
                    Text(tr("FROM TIKTOK", "FRA TIKTOK"), color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    if (current.tiktok.author.isNotBlank()) Text("@${current.tiktok.author}", color = Color.White, fontSize = 20.sp)
                }
                null -> Unit
            }
            Spacer(Modifier.height(28.dp))
            Text(tr("Up next", "Neste"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
        }
        items(upcoming, key = { it.first }) { (index, item) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onJump(index) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (item) {
                    is FeedItem.VideoItem -> {
                        coil.compose.AsyncImage(
                            item.video.thumbnail, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(10.dp)).background(Plum)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.video.title, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(item.video.channel, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                    }
                    is FeedItem.PinItem -> {
                        coil.compose.AsyncImage(
                            item.pin.image, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(10.dp)).background(Plum)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(item.pin.title.ifBlank { tr("Illustrated message", "Illustrert budskap") }, color = Color.White, fontSize = 14.sp, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    is FeedItem.TikTokItem -> {
                        Box(Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                            Text("TikTok", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(if (item.tiktok.author.isNotBlank()) "@${item.tiktok.author}" else tr("TikTok video", "TikTok-video"), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                    is FeedItem.MessageItem -> {
                        Box(
                            Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(10.dp))
                                .background(Brush.linearGradient(Palettes.forTheme(item.message.theme))),
                            contentAlignment = Alignment.Center
                        ) { Text("✦", color = Color.White, fontSize = 22.sp) }
                        Spacer(Modifier.width(12.dp))
                        Text(item.message.text, color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Serif, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelButton(label: String, onClick: () -> Unit) {
    Text(
        label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp)
    )
}

/** A verse opened from the home-screen widget, shown full screen over the app. */
@Composable
fun VerseOverlay(verse: Verse, state: AppState, onRead: (Verse) -> Unit, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Ink)
            .clickable(remember { MutableInteractionSource() }, null) { }
    ) {
        VerseCard(
            verse = verse, reason = tr("From your widget", "Fra widgeten din"), isCurrent = true,
            saved = verse.ref in state.saved, liked = verse.ref in state.liked,
            onLike = { state.toggleLike(verse.ref) }, onDoubleTapLike = { state.like(verse.ref) },
            onSave = { state.toggleSave(verse.ref) }, onShared = {}, onRead = { onRead(verse) },
            bottomInset = 24.dp
        )
        androidx.compose.material3.IconButton(
            onClick = onClose,
            modifier = Modifier.statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f))
        ) { Icon(Icons.Filled.Close, tr("Close", "Lukk"), tint = Color.White) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun pageOffset(pager: PagerState, page: Int): Float =
    (pager.currentPage - page) + pager.currentPageOffsetFraction

@Composable
private fun VerseCard(
    verse: Verse,
    reason: String?,
    isCurrent: Boolean,
    saved: Boolean,
    liked: Boolean,
    onLike: () -> Unit,
    onDoubleTapLike: () -> Unit,
    onSave: () -> Unit,
    onShared: () -> Unit,
    onRead: () -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp
) {
    val ctx = LocalContext.current
    val palette = remember(verse.theme) { Palettes.forTheme(verse.theme) }

    // Text reveal each time the card comes into view.
    val reveal = remember(verse.ref) { Animatable(0f) }
    LaunchedEffect(isCurrent) {
        if (isCurrent) reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }

    // Double-tap heart burst
    var burstAt by remember { mutableStateOf<Offset?>(null) }
    var burstKey by remember { mutableIntStateOf(0) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(verse.ref) {
                detectTapGestures(onDoubleTap = { pos ->
                    onDoubleTapLike()
                    burstAt = pos
                    burstKey++
                })
            }
    ) {
        LivingBackground(palette)

        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 32.dp, end = 76.dp, top = 80.dp, bottom = bottomInset + 120.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val len = verse.text.length
            val size = when { len > 260 -> 20.sp; len > 170 -> 23.sp; len > 100 -> 26.sp; else -> 30.sp }
            Text(
                "“${verse.text}”",
                color = Color.White,
                fontFamily = FontFamily.Serif,
                fontSize = size,
                lineHeight = size * 1.35f,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    alpha = 0.15f + 0.85f * reveal.value
                    translationY = (1f - reveal.value) * 48f
                }
            )
            Spacer(Modifier.height(22.dp))
            Text(
                "— ${verse.ref}",
                color = Gold,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.graphicsLayer {
                    val r = ((reveal.value - 0.4f) / 0.6f).coerceIn(0f, 1f)
                    alpha = r
                    translationY = (1f - r) * 24f
                }
            )
        }

        // Bottom-left: encouragement + read chapter
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 90.dp, bottom = bottomInset + 20.dp)
        ) {
            if (reason != null) {
                ReasonTag(reason)
                Spacer(Modifier.height(8.dp))
            }
            Text(Encouragement.forVerse(verse), color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(onClick = onRead)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Read ${verse.chapterRef}", "Les ${verse.chapterRef}"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        // Right action rail
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomInset + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            RailButton(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (liked) tr("Amen", "Amen") else tr("Like", "Liker"), liked, if (liked) HeartRed else Color.White, onLike)
            RailButton(if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (saved) tr("Saved", "Lagret") else tr("Save", "Lagre"), saved, if (saved) Gold else Color.White, onSave)
            RailButton(Icons.Filled.Share, tr("Share", "Del"), false, Color.White) { onShared(); Share.verse(ctx, verse, Palettes.argb(verse.theme)) }
        }

        burstAt?.let { pos -> HeartBurstAt(pos, burstKey) }
    }
}

/** Small tag telling you why a card is in your feed. */
@Composable
fun ReasonTag(text: String) {
    Text(
        "✦ $text",
        color = Gold,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.28f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun RailButton(icon: ImageVector, label: String, active: Boolean, tint: Color, onClick: () -> Unit) {
    var pops by remember { mutableIntStateOf(0) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pops) {
        if (pops > 0) {
            scale.snapTo(0.7f)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(50.dp)
                .scale(scale.value)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.25f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { pops++; onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
fun HeartBurstAt(pos: Offset, key: Int) {
    val scale = remember(key) { Animatable(0f) }
    val alpha = remember(key) { Animatable(1f) }
    LaunchedEffect(key) {
        scale.animateTo(1.25f, tween(200, easing = FastOutSlowInEasing))
        launch { scale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f)) }
        delay(350)
        alpha.animateTo(0f, tween(400))
    }
    val sizePx = with(LocalDensity.current) { 110.dp.toPx() }
    Icon(
        Icons.Filled.Favorite, null, tint = HeartRed,
        modifier = Modifier
            .offset { IntOffset((pos.x - sizePx / 2).roundToInt(), (pos.y - sizePx / 2).roundToInt()) }
            .size(110.dp)
            .graphicsLayer {
                scaleX = scale.value; scaleY = scale.value
                this.alpha = alpha.value
                rotationZ = -12f + 12f * scale.value
            }
    )
}

private data class Orb(val x: Float, val y: Float, val r: Float, val phase: Float, val a: Float)

/** Slowly shifting gradient with drifting soft light, so verse cards feel alive. */
@Composable
fun LivingBackground(palette: List<Color>) {
    val t = rememberInfiniteTransition(label = "bg")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse), label = "shift")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(22_000, easing = LinearEasing), RepeatMode.Restart), label = "drift")
    val orbs = remember(palette) {
        val rnd = java.util.Random(palette.hashCode().toLong())
        List(6) { Orb(rnd.nextFloat(), rnd.nextFloat(), 0.25f + rnd.nextFloat() * 0.35f, rnd.nextFloat(), 0.06f + rnd.nextFloat() * 0.10f) }
    }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        drawRect(
            Brush.linearGradient(
                palette,
                start = Offset(-w * 0.5f * shift, 0f),
                end = Offset(w * (1f + 0.5f * (1f - shift)), h)
            )
        )
        orbs.forEach { o ->
            val ang = 2f * PI.toFloat() * (drift + o.phase)
            val c = Offset(w * (o.x + 0.08f * sin(ang)), h * (o.y + 0.06f * cos(ang)))
            val rad = w * o.r
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = o.a), Color.Transparent), center = c, radius = rad), radius = rad, center = c)
        }
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.45f))))
    }
}
