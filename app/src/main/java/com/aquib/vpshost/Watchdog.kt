package com.aquib.vpshost

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        val p = Prefs(c)
        if (p.batteryAlarm && Permissions.allGranted(c)) BatteryGuardService.start(c)
        if ((p.wantRunning || p.llmWant) && Permissions.allGranted(c)) HostService.resume(c)
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
