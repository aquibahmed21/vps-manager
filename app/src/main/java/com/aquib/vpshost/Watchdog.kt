package com.aquib.vpshost

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * Restarts HostService if it is not running, and re-arms itself.
 * Works because the app is exempt from battery optimisation (a required permission), which also
 * allows starting a foreground service from the background.
 * Cannot beat "Force stop" in system settings: Android cancels all alarms and blocks the app until the user opens it again.
 */
object Watchdog {
    private const val INTERVAL_MS = 2 * 60 * 1000L

    fun ensure(c: Context) {
        schedule(c, INTERVAL_MS)
        scheduleDailyUpdate(c)
        val p = Prefs(c)
        if (p.batteryAlarm && Permissions.allGranted(c)) BatteryGuardService.start(c)
        if ((p.wantRunning || p.llmWant) && Permissions.allGranted(c)) HostService.resume(c)
    }

    /** Arms the once-a-day server code check for the next 2:00 am (local time). Safe to call repeatedly. */
    fun scheduleDailyUpdate(c: Context) {
        val t = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 2); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 60_000) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val pi = PendingIntent.getBroadcast(c, 8, Intent(c, DailyUpdateReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = c.getSystemService(AlarmManager::class.java)
        // Exact when the user allows "Alarms & reminders"; otherwise Android may delay it by up to ~an hour.
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.timeInMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.timeInMillis, pi)
    }

    fun schedule(c: Context, delayMs: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, 7, Intent(c, WatchdogReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi)
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Watchdog.ensure(c) }
}

/** 2 am: check GitHub for new server code (only if the user wants the server running), then arm tomorrow's check. */
class DailyUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Watchdog.scheduleDailyUpdate(c)
        if (Prefs(c).wantRunning && Permissions.allGranted(c)) HostService.update(c, scheduled = true)
    }
}
