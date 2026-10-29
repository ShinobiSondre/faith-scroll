package com.faithscroll.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.faithscroll.app.MainActivity
import com.faithscroll.app.R
import com.faithscroll.app.data.Prefs
import com.faithscroll.app.data.Verse
import com.faithscroll.app.data.VerseRepository

class VerseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        // Called on add and on the periodic (hourly) update.
        val current = VerseRepository.byRef(ctx, Prefs.widgetRef(ctx))
        val verse = if (current == null || Prefs.widgetAutoChange(ctx)) {
            VerseRepository.random(ctx, current?.ref).also { Prefs.setWidgetRef(ctx, it.ref) }
        } else current
        ids.forEach { mgr.updateAppWidget(it, build(ctx, verse)) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_NEXT) nextVerse(ctx)
    }

    companion object {
        const val ACTION_NEXT = "com.faithscroll.app.NEXT_VERSE"

        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, VerseWidgetProvider::class.java))

        fun nextVerse(ctx: Context) {
            val v = VerseRepository.random(ctx, Prefs.widgetRef(ctx))
            Prefs.setWidgetRef(ctx, v.ref)
            render(ctx, v)
        }

        /** Re-draws all widgets (e.g. after opacity/style changes) without changing the verse. */
        fun refresh(ctx: Context) {
            val v = VerseRepository.byRef(ctx, Prefs.widgetRef(ctx))
                ?: VerseRepository.random(ctx).also { Prefs.setWidgetRef(ctx, it.ref) }
            render(ctx, v)
        }

        fun hasWidgets(ctx: Context): Boolean = ids(ctx).isNotEmpty()

        private fun render(ctx: Context, v: Verse) {
            val mgr = AppWidgetManager.getInstance(ctx)
            ids(ctx).forEach { mgr.updateAppWidget(it, build(ctx, v)) }
        }

        fun build(ctx: Context, verse: Verse): RemoteViews {
            val lightText = Prefs.widgetLightText(ctx)
            val layout = if (lightText) R.layout.widget_verse_light_text else R.layout.widget_verse_dark_text
            val rv = RemoteViews(ctx.packageName, layout)
            rv.setTextViewText(R.id.widget_verse, "“${verse.text}”")
            rv.setTextViewText(R.id.widget_ref, "— ${verse.ref}")
            rv.setInt(R.id.widget_bg, "setImageAlpha", Prefs.widgetOpacity(ctx) * 255 / 100)

            val open = Intent(ctx, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_REF, verse.ref)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            rv.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            val next = Intent(ctx, VerseWidgetProvider::class.java).setAction(ACTION_NEXT)
            rv.setOnClickPendingIntent(
                R.id.widget_refresh,
                PendingIntent.getBroadcast(ctx, 2, next, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            return rv
        }
    }
}
