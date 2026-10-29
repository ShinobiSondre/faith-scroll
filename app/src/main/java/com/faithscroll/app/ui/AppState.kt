package com.faithscroll.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.faithscroll.app.data.Prefs
import com.faithscroll.app.data.Video

class AppState(private val ctx: Context, val streak: Prefs.Streak) {
    var saved by mutableStateOf(Prefs.savedVerses(ctx))
        private set
    var liked by mutableStateOf(Prefs.likedVerses(ctx))
        private set
    var savedVideos by mutableStateOf(Prefs.savedVideos(ctx))
        private set

    fun toggleSave(ref: String) {
        saved = if (ref in saved) saved - ref else saved + ref
        Prefs.setSavedVerses(ctx, saved)
    }

    fun toggleLike(ref: String) {
        liked = if (ref in liked) liked - ref else liked + ref
        Prefs.setLikedVerses(ctx, liked)
    }

    fun like(ref: String) {
        if (ref !in liked) toggleLike(ref)
    }

    fun isVideoSaved(id: String) = savedVideos.any { it.id == id }

    fun toggleVideo(v: Video) {
        savedVideos = if (isVideoSaved(v.id)) savedVideos.filterNot { it.id == v.id } else listOf(v) + savedVideos
        Prefs.setSavedVideos(ctx, savedVideos)
    }
}
