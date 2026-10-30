package com.faithscroll.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/** Opens a web page inside the app (used by "Go to page"). */
val LocalOpenPage = compositionLocalOf<(String) -> Unit> { {} }

@Composable
fun WebPageScreen(url: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(
        Modifier.fillMaxSize().background(Ink)
            .clickable(remember { MutableInteractionSource() }, null) { }
            .statusBarsPadding().navigationBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, com.faithscroll.app.util.tr("Close", "Lukk"), tint = Color.White) }
            // For "What do they mean here?": a short title instead of the address.
            val question = android.net.Uri.parse(url).takeIf { it.host?.contains("chatgpt.com") == true }
                ?.getQueryParameter("q")?.let { q -> Regex("(Hva mener de ved [0-9:]+\\?|What do they mean at [0-9:]+\\?)").find(q)?.value }
            Text(
                question ?: (android.net.Uri.parse(url).host?.removePrefix("www.") ?: url),
                color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        InAppWebPage(url, Modifier.fillMaxWidth().weight(1f))
    }
}
