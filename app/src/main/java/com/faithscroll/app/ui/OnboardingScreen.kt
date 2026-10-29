package com.faithscroll.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faithscroll.app.data.Taste

private data class Opt(val key: String, val label: String)

private val heartOptions = listOf(
    Opt("peace", "🕊  Peace & less anxiety"), Opt("hope", "🌅  Hope for the future"),
    Opt("strength", "💪  Strength to keep going"), Opt("comfort", "🤍  Comfort in hard times"),
    Opt("love", "❤️  Love & relationships"), Opt("guidance", "🧭  Guidance & decisions"),
    Opt("faith", "✝️  Growing my faith"), Opt("grace", "🌿  Forgiveness & a fresh start"),
    Opt("courage", "🦁  Courage over fear"), Opt("joy", "☀️  Joy & gratitude"),
    Opt("prayer", "🙏  A deeper prayer life")
)
private val journeyOptions = listOf(
    Opt("exploring", "Just exploring who Jesus is"),
    Opt("new", "New to faith"),
    Opt("growing", "Growing — I want to go deeper"),
    Opt("mature", "Walking with God for years")
)
private val watchOptions = listOf(
    Opt("stories", "🎥  Real-life testimonies"),
    Opt("sermons", "⛪  Sermons & preaching"),
    Opt("teaching", "📖  Bible explained"),
    Opt("verses", "✨  Mostly verses, few videos")
)
private val voiceOptions = listOf(
    Opt("Desiring God", "John Piper · Desiring God"),
    Opt("Elevation Church", "Steven Furtick · Elevation"),
    Opt("In Touch Ministries", "Charles Stanley · In Touch"),
    Opt("Gospel in Life", "Tim Keller · Gospel in Life"),
    Opt("I Am Second", "I Am Second stories"),
    Opt("700 Club Testimonies", "700 Club testimonies"),
    Opt("BibleProject", "BibleProject")
)

/** A few quick questions so the feed fits you from the very first swipe. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var heart by remember { mutableStateOf(setOf<String>()) }
    var journey by remember { mutableStateOf<String?>(null) }
    var watch by remember { mutableStateOf(setOf<String>()) }
    var voices by remember { mutableStateOf(setOf<String>()) }

    fun finish() {
        Taste.applyOnboarding(ctx, Taste.Answers(heart, journey ?: "growing", watch.ifEmpty { setOf("stories", "sermons") }, voices))
        onDone()
    }

    Box(
        Modifier.fillMaxSize().background(Ink)
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { }
    ) {
        LivingBackground(Palettes.forTheme(listOf("prayer", "grace", "hope", "peace", "joy")[step.coerceIn(0, 4)]))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            // Progress dots
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(5) { i ->
                    val c by animateColorAsState(if (i <= step) Gold else Color.White.copy(alpha = 0.25f), label = "dot")
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c))
                }
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    (slideInHorizontally(tween(350)) { it / 3 } + fadeIn(tween(350))) togetherWith
                        (slideOutHorizontally(tween(250)) { -it / 3 } + fadeOut(tween(250)))
                },
                modifier = Modifier.weight(1f),
                label = "step"
            ) { s ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 36.dp)) {
                    when (s) {
                        0 -> {
                            Spacer(Modifier.height(60.dp))
                            Text("✦", color = Gold, fontSize = 44.sp)
                            Spacer(Modifier.height(12.dp))
                            Title("Welcome to FaithScroll")
                            Sub("A few quick questions so your feed speaks to where you are. You can change them any time.")
                        }
                        1 -> {
                            Title("What's on your heart right now?")
                            Sub("Pick as many as you like.")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                heartOptions.forEach { o -> Choice(o.label, o.key in heart) { heart = heart.toggle(o.key) } }
                            }
                        }
                        2 -> {
                            Title("Where are you in your walk with God?")
                            Sub("There's no wrong answer.")
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                journeyOptions.forEach { o -> Choice(o.label, journey == o.key, wide = true) { journey = o.key } }
                            }
                        }
                        3 -> {
                            Title("What would you like to see?")
                            Sub("Pick any.")
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                watchOptions.forEach { o -> Choice(o.label, o.key in watch, wide = true) { watch = watch.toggle(o.key) } }
                            }
                        }
                        else -> {
                            Title("Any voices you already love?")
                            Sub("Optional. We'll show more from them.")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                voiceOptions.forEach { o -> Choice(o.label, o.key in voices) { voices = voices.toggle(o.key) } }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step in 1..4) TextButton(onClick = { if (step == 4) finish() else step++ }) {
                    Text("Skip", color = Color.White.copy(alpha = 0.7f))
                }
                Spacer(Modifier.weight(1f))
                val enabled = when (step) { 1 -> heart.isNotEmpty(); 2 -> journey != null; 3 -> watch.isNotEmpty(); else -> true }
                Button(
                    onClick = { if (step == 4) finish() else step++ },
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink, disabledContainerColor = Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier.height(52.dp)
                ) {
                    Text(when (step) { 0 -> "Let's begin"; 4 -> "Show my feed"; else -> "Next" }, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
    }
}

private fun Set<String>.toggle(k: String) = if (k in this) this - k else this + k

@Composable
private fun Title(t: String) =
    Text(t, color = Color.White, fontSize = 30.sp, lineHeight = 36.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)

@Composable
private fun Sub(t: String) {
    Text(t, color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, wide: Boolean = false, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Gold else Color.Black.copy(alpha = 0.25f), tween(180), label = "bg")
    val fg by animateColorAsState(if (selected) Ink else Color.White, tween(180), label = "fg")
    Box(
        (if (wide) Modifier.fillMaxWidth() else Modifier)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, if (selected) Gold else Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Start)
    }
}
