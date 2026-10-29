package com.faithscroll.app.prayer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.faithscroll.app.R
import com.faithscroll.app.util.Lang
import com.faithscroll.app.util.tr
import java.util.Calendar

/**
 * Prayer reminders 2–3 times a day. Each one pops up as a heads-up notification;
 * tapping it opens the prayer card (PrayerActivity) over whatever you're doing.
 */
object Reminders {
    private const val CHANNEL = "prayer"
    const val ACTION_FIRE = "com.faithscroll.app.PRAY"
    const val ACTION_SNOOZE = "com.faithscroll.app.PRAY_SNOOZE"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("prayer", Context.MODE_PRIVATE)

    /** 0 = off, 2 or 3 reminders a day. */
    fun count(ctx: Context): Int = prefs(ctx).getInt("count", 0)
    fun asked(ctx: Context): Boolean = prefs(ctx).getBoolean("asked", false)
    fun setAsked(ctx: Context) = prefs(ctx).edit().putBoolean("asked", true).apply()

    /** Minutes after midnight for each reminder. */
    fun times(ctx: Context): List<Int> {
        val all = listOf(
            prefs(ctx).getInt("t0", 8 * 60),
            prefs(ctx).getInt("t1", 13 * 60),
            prefs(ctx).getInt("t2", 21 * 60)
        )
        return when (count(ctx)) { 2 -> listOf(all[0], all[2]); 3 -> all; else -> emptyList() }
    }
    fun rawTime(ctx: Context, slot: Int): Int = prefs(ctx).getInt("t$slot", listOf(8 * 60, 13 * 60, 21 * 60)[slot])
    fun setTime(ctx: Context, slot: Int, minutes: Int) { prefs(ctx).edit().putInt("t$slot", minutes).apply(); schedule(ctx) }

    fun setCount(ctx: Context, n: Int) {
        prefs(ctx).edit().putInt("count", n).apply()
        schedule(ctx)
    }

    private fun pending(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, ReminderReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Sets the next reminder (called at start, after each reminder, after reboot, and on changes). */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, ACTION_FIRE, 10)
        am.cancel(pi)
        val times = times(ctx)
        if (times.isEmpty()) return
        val now = Calendar.getInstance()
        val next = times.map { m ->
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, m / 60); set(Calendar.MINUTE, m % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
            }
        }.minByOrNull { it.timeInMillis } ?: return
        // A 5-minute window lets Android save battery without needing exact-alarm permission.
        am.setWindow(AlarmManager.RTC_WAKEUP, next.timeInMillis, 5 * 60 * 1000L, pi)
    }

    fun snooze(ctx: Context, minutes: Int = 10) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setWindow(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + minutes * 60_000L, 2 * 60_000L, pending(ctx, ACTION_FIRE + ".once", 11))
    }

    fun prompt(): Pair<String, String> {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 11 -> tr("Good morning 🙏", "God morgen 🙏") to tr("Give Him your day before it begins.", "Gi Ham dagen din før den begynner.")
            h < 17 -> tr("A moment with God 🙏", "Et øyeblikk med Gud 🙏") to tr("Pause. Breathe. He's right here with you.", "Stopp opp. Pust. Han er her hos deg.")
            else -> tr("Time to pray 🙏", "Tid for bønn 🙏") to tr("Thank Him for today and rest in His peace.", "Takk Ham for i dag og hvil i Hans fred.")
        }
    }

    fun notify(ctx: Context) {
        Lang.init(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, tr("Prayer reminders", "Bønnepåminnelser"), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = tr("A gentle nudge to pray during the day", "Et vennlig dytt om å be i løpet av dagen")
                }
            )
        }
        val (title, body) = prompt()
        fun open(code: Int, beStill: Boolean) = PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, PrayerActivity::class.java).putExtra(PrayerActivity.EXTRA_BE_STILL, beStill)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify_pray)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open(20, false))
            .addAction(0, tr("Pray now", "Be nå"), open(21, true))
            .addAction(0, tr("In 10 min", "Om 10 min"), pending(ctx, ACTION_SNOOZE, 22))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(7001, n) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Reminders.ACTION_FIRE -> { Reminders.notify(ctx); Reminders.schedule(ctx) }
            Reminders.ACTION_FIRE + ".once" -> Reminders.notify(ctx)
            Reminders.ACTION_SNOOZE -> {
                NotificationManagerCompat.from(ctx).cancel(7001)
                Reminders.snooze(ctx)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.TIME_SET", "android.intent.action.TIMEZONE_CHANGED" ->
                Reminders.schedule(ctx)
        }
    }
}
