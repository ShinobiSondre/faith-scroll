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
    var savedMessages by mutableStateOf(Prefs.set(ctx, "saved_messages"))
        private set
    var likedMessages by mutableStateOf(Prefs.set(ctx, "liked_messages"))
        private set

    var savedPins by mutableStateOf(Prefs.savedPins(ctx))
        private set
    var likedPins by mutableStateOf(Prefs.set(ctx, "liked_pins"))
        private set

    fun isPinSaved(id: String) = savedPins.any { it.id == id }
    fun togglePin(p: com.faithscroll.app.data.Pin) {
        savedPins = if (isPinSaved(p.id)) savedPins.filterNot { it.id == p.id } else listOf(p) + savedPins
        Prefs.setSavedPins(ctx, savedPins)
    }
    fun togglePinLike(id: String) {
        likedPins = if (id in likedPins) likedPins - id else likedPins + id
        Prefs.putSet(ctx, "liked_pins", likedPins)
    }

    fun toggleMessageSave(id: String) {
        savedMessages = if (id in savedMessages) savedMessages - id else savedMessages + id
        Prefs.putSet(ctx, "saved_messages", savedMessages)
    }

    fun toggleMessageLike(id: String) {
        likedMessages = if (id in likedMessages) likedMessages - id else likedMessages + id
        Prefs.putSet(ctx, "liked_messages", likedMessages)
    }

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
