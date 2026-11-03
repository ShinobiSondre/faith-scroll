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
        // Android checks in every hour; the verse only changes once a day (the first check after midnight).
        val current = VerseRepository.byRef(ctx, Prefs.widgetRef(ctx))
        val newDay = Prefs.widgetDay(ctx) != Prefs.today()
        val verse = if (current == null || (Prefs.widgetAutoChange(ctx) && newDay)) {
            VerseRepository.random(ctx, current?.ref).also { Prefs.setWidgetRef(ctx, it.ref) }
        } else current
        ids.forEach { mgr.updateAppWidget(it, build(ctx, verse, it)) }
    }

    // Resized on the home screen: redraw the text for the new size.
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: android.os.Bundle) {
        val v = VerseRepository.byRef(ctx, Prefs.widgetRef(ctx)) ?: return
        mgr.updateAppWidget(id, build(ctx, v, id))
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
            ids(ctx).forEach { mgr.updateAppWidget(it, build(ctx, v, it)) }
        }

        fun build(ctx: Context, verse0: Verse, widgetId: Int = 0): RemoteViews {
            com.faithscroll.app.util.Lang.init(ctx)
            // The stored verse may be from before a language switch; show it in the current language.
            val verse = VerseRepository.byRef(ctx, verse0.ref) ?: verse0
            val lightText = Prefs.widgetLightText(ctx)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_verse_drawn)
            rv.setImageViewResource(R.id.widget_bg, if (lightText) R.drawable.widget_bg_dark else R.drawable.widget_bg_light)
            rv.setInt(R.id.widget_bg, "setImageAlpha", Prefs.widgetOpacity(ctx) * 255 / 100)
            // The text, drawn at the widget's real size with your shadow/colour/font/size settings.
            val d = ctx.resources.displayMetrics.density
            val opts = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(widgetId)
            val wDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)?.takeIf { it > 0 } ?: 320
            val hDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)?.takeIf { it > 0 } ?: 170
            val style = WidgetText.style(ctx)
            rv.setContentDescription(R.id.widget_text, "${verse.text} — ${verse.label}")
            rv.setImageViewBitmap(R.id.widget_text, WidgetText.render(verse, style, (wDp * d).toInt().coerceAtMost(1400), (hDp * d).toInt().coerceAtMost(1400), d))
            rv.setInt(R.id.widget_refresh, "setColorFilter", WidgetText.textColors(style).first)

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
