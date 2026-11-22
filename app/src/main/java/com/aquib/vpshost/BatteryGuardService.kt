package com.aquib.vpshost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.sin

data class BatteryState(val level: Int = -1, val charging: Boolean = false, val beepEverySec: Int = 0, val lastBeep: Long = 0)

object BatteryPolicy {
    /** Seconds between beeps for a battery level, or null when no alarm is needed. */
    fun intervalSec(level: Int): Int? = when {
        level < 0 -> null
        level < 10 -> 3
        level <= 15 -> 15
        level <= 30 -> 30
        else -> null
    }
    const val BEEP_MS = 1500
}

/** Plays one loud 1.5 s alarm tone on the ALARM stream: ignores silent mode, and DND when access is granted. */
class Beeper(private val ctx: Context) {
    suspend fun beep() {
        val am = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val oldVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val oldFilter = if (nm.isNotificationPolicyAccessGranted) nm.currentInterruptionFilter else null
        var track: AudioTrack? = null
        try {
            // Total silence / alarms-blocked DND would mute us, so lift it for the duration of the beep.
            if (oldFilter != null && oldFilter != NotificationManager.INTERRUPTION_FILTER_ALL) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
            val rate = 44100; val n = rate * BatteryPolicy.BEEP_MS / 1000
            val pcm = ShortArray(n) { i -> // two-tone warble, near full scale
                val f = if ((i * 1000L / rate / 125) % 2 == 0L) 1800.0 else 2400.0
                (sin(2 * PI * f * i / rate) * 0.95 * Short.MAX_VALUE).toInt().toShort()
            }
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(BatteryPolicy.BEEP_MS.toLong())
        } finally {
            runCatching { track?.stop() }; runCatching { track?.release() }
            runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, oldVol, 0) }
            if (oldFilter != null && oldFilter != NotificationManager.INTERRUPTION_FILTER_ALL) runCatching { nm.setInterruptionFilter(oldFilter) }
        }
    }
}

/**
 * Always-on foreground service. Battery <= 30%: beep every 30 s; <= 15%: every 15 s; < 10%: every 3 s.
 * Silent while charging. Holds a wake lock only while an alarm is due, so it costs nothing above 30%.
 */
class BatteryGuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var beeper: Beeper

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        beeper = Beeper(this)
        startForeground(3, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        registerReceiver(object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { changed.trySend(Unit) } }
            .also { receiver = it }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vpshost:battery")
        scope.launch { loop() }
    }
    private var receiver: BroadcastReceiver? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs(this).batteryAlarm) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    private fun read(): BatteryState {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return state.value
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val level = if (lvl < 0) -1 else lvl * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return state.value.copy(level = level, charging = charging, beepEverySec = if (charging) 0 else BatteryPolicy.intervalSec(level) ?: 0)
    }

    private var lastCharging: Boolean? = null
    private var lastTier = 0
    private suspend fun loop() {
        var lastBeep = 0L
        while (currentCoroutineContext().isActive) {
            val s = read(); state.value = s
            if (lastCharging != null && lastCharging != s.charging) Telemetry.event("battery", if (s.charging) "The charger was connected (battery ${s.level}%)." else "The charger was disconnected (battery ${s.level}%).")
            lastCharging = s.charging
            if (s.beepEverySec != lastTier) { if (s.beepEverySec > 0 && s.beepEverySec < (if (lastTier == 0) Int.MAX_VALUE else lastTier)) Telemetry.event("battery", "The battery is low (${s.level}%). The alarm will beep every ${s.beepEverySec} seconds until it is charged."); lastTier = s.beepEverySec }
            if (s.beepEverySec == 0) {
                runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
                lastBeep = 0
                withTimeoutOrNull(60_000) { changed.receive() } // sleep until the battery changes
                continue
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
            val now = System.currentTimeMillis()
            if (now - lastBeep >= s.beepEverySec * 1000L) {
                lastBeep = now; state.value = s.copy(lastBeep = now)
                Log.i("VpsHost", "battery ${s.level}%: beep (every ${s.beepEverySec}s)")
                runCatching { beeper.beep() }.onFailure { Log.w("VpsHost", "beep failed: $it") }
            }
            delay(250)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) { Watchdog.schedule(this, 1000) }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        scope.cancel()
        if (Prefs(this).batteryAlarm) Watchdog.schedule(this, 1000)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("battery", "Battery guard", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "battery").setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Battery guard").setContentText("Alarm sounds at 30%, 15% and 10% or below").setOngoing(true).setContentIntent(open).build()
    }

    companion object {
        val state = MutableStateFlow(BatteryState())
        fun start(c: Context) { c.startForegroundService(Intent(c, BatteryGuardService::class.java)) }
        fun stop(c: Context) { c.stopService(Intent(c, BatteryGuardService::class.java)) }
    }
}
