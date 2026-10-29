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
import com.faithscroll.app.data.Encouragement
import com.faithscroll.app.data.Verse
import com.faithscroll.app.data.Video
import com.faithscroll.app.util.Share
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private sealed interface FeedItem {
    data class VerseItem(val verse: Verse) : FeedItem
    data class VideoItem(val video: Video) : FeedItem
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(
    state: AppState,
    verses: List<Verse>,
    videos: List<Video>,
    startRef: String?,
    bottomInset: androidx.compose.ui.unit.Dp,
    onReadChapter: (Verse) -> Unit
) {
    val order = remember(verses, startRef) {
        val shuffled = verses.shuffled()
        val first = shuffled.firstOrNull { it.ref == startRef }
        if (first != null) listOf(first) + (shuffled - first) else shuffled
    }
    val pager = rememberPagerState(pageCount = { 100_000 })

    // Videos join the feed a couple of pages ahead, so the card you're looking at never changes under you.
    var videoStart by remember { mutableIntStateOf(Int.MAX_VALUE) }
    LaunchedEffect(videos.isNotEmpty()) {
        if (videos.isNotEmpty() && videoStart == Int.MAX_VALUE) videoStart = pager.currentPage + 2
    }

    fun itemAt(page: Int): FeedItem {
        if (videos.isNotEmpty() && page >= videoStart && (page - videoStart) % 4 == 3) {
            return FeedItem.VideoItem(videos[((page - videoStart) / 4) % videos.size])
        }
        val skip = if (page >= videoStart && videos.isNotEmpty()) (page - videoStart + 1) / 4 else 0
        return FeedItem.VerseItem(order[(page - skip) % order.size])
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (order.isNotEmpty()) {
            VerticalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { it }
            ) { page ->
                val offset = pageOffset(pager, page)
                val item = itemAt(page)
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val o = abs(offset).coerceIn(0f, 1f)
                            val s = 1f - 0.08f * o
                            scaleX = s; scaleY = s
                            alpha = 1f - 0.35f * o
                        }
                ) {
                    when (item) {
                        is FeedItem.VerseItem -> VerseCard(
                            verse = item.verse,
                            isCurrent = pager.currentPage == page,
                            saved = item.verse.ref in state.saved,
                            liked = item.verse.ref in state.liked,
                            onLike = { state.toggleLike(item.verse.ref) },
                            onDoubleTapLike = { state.like(item.verse.ref) },
                            onSave = { state.toggleSave(item.verse.ref) },
                            onRead = { onReadChapter(item.verse) },
                            bottomInset = bottomInset
                        )
                        is FeedItem.VideoItem -> VideoCard(
                            video = item.video,
                            isActive = pager.settledPage == page,
                            saved = state.isVideoSaved(item.video.id),
                            onSave = { state.toggleVideo(item.video) },
                            bottomInset = bottomInset
                        )
                    }
                }
            }
        }

        // Header
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
                Text("${state.streak.current} day${if (state.streak.current == 1) "" else "s"}", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun pageOffset(pager: PagerState, page: Int): Float =
    (pager.currentPage - page) + pager.currentPageOffsetFraction

@Composable
private fun VerseCard(
    verse: Verse,
    isCurrent: Boolean,
    saved: Boolean,
    liked: Boolean,
    onLike: () -> Unit,
    onDoubleTapLike: () -> Unit,
    onSave: () -> Unit,
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
                Text("Read ${verse.chapterRef}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        // Right action rail
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomInset + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            RailButton(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (liked) "Amen" else "Like", liked, if (liked) HeartRed else Color.White, onLike)
            RailButton(if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (saved) "Saved" else "Save", saved, if (saved) Gold else Color.White, onSave)
            RailButton(Icons.Filled.Share, "Share", false, Color.White) { Share.verse(ctx, verse, Palettes.argb(verse.theme)) }
        }

        burstAt?.let { pos -> HeartBurst(pos, burstKey) }
    }
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
private fun HeartBurst(pos: Offset, key: Int) {
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
