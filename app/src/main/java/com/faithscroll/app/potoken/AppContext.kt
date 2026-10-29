package com.faithscroll.app.potoken

import android.content.Context

/** Gives the token code access to the app (set once at start). */
object AppContext {
    @Volatile private var ctx: Context? = null
    fun set(c: Context) { ctx = c.applicationContext }
    fun get(): Context = ctx ?: error("App context not set")
}
