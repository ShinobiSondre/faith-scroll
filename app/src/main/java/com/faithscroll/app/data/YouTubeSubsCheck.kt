package com.faithscroll.app.data

/** Lets the language filter trust channels you picked as favourites (no Context needed). */
object YouTubeSubsCheck {
    @Volatile var favouriteNames: Set<String> = emptySet()
    fun isFav(name: String) = name in favouriteNames
}
