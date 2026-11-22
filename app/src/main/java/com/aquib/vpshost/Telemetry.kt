package com.aquib.vpshost

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Keeps three days of measurements (every 30 s) and a list of plain-language events, on disk so they survive restarts. */
object Telemetry {
    private const val SAMPLE_SECS = 30
    private const val KEEP_MS = 3L * 24 * 3600 * 1000
    private const val MAX_EVENTS = 1000
    private val samples = ArrayDeque<Bucket>()
    private val events = ArrayDeque<Event>()
    private lateinit var metricsFile: File
    private lateinit var eventsFile: File
    private var sampler: Job? = null
    /** Bumps whenever something new is recorded, so screens can refresh. */
    val version = MutableStateFlow(0)
    private var prev: JSONObject? = null
    private var lastHourly = 0L

    @Synchronized fun init(ctx: Context) {
        if (::metricsFile.isInitialized) return
        metricsFile = File(ctx.filesDir, "metrics.jsonl"); eventsFile = File(ctx.filesDir, "events.jsonl")
        val cutoff = System.currentTimeMillis() - KEEP_MS
        runCatching { metricsFile.readLines().forEach { l -> runCatching { Bucket.fromJson(JSONObject(l)).let { if (it.t >= cutoff) samples.addLast(it) } } } }
        runCatching { eventsFile.readLines().takeLast(MAX_EVENTS).forEach { l -> runCatching { events.addLast(Event.fromJson(JSONObject(l))) } } }
        // rewrite trimmed copies so the files stay small
        runCatching { metricsFile.writeText(samples.joinToString("") { it.toJson().toString() + "\n" }) }
        runCatching { eventsFile.writeText(events.joinToString("") { it.toJson().toString() + "\n" }) }
    }

    @Synchronized fun event(kind: String, text: String) {
        if (!::eventsFile.isInitialized) return
        val e = Event(System.currentTimeMillis(), kind, text)
        events.addLast(e); while (events.size > MAX_EVENTS) events.removeFirst()
        runCatching { eventsFile.appendText(e.toJson().toString() + "\n") }
        version.value++
    }

    fun start(ctx: Context, scope: CoroutineScope) {
        if (sampler?.isActive == true) return
        sampler = scope.launch(Dispatchers.IO) {
            while (isActive) { runCatching { sample(ctx) }; delay(SAMPLE_SECS * 1000L) }
        }
    }

    private fun sample(ctx: Context) {
        val st = HostService.state.value
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1; val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val mi = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val sf = StatFs(Environment.getExternalStorageDirectory().path)
        // Ask the Node gateway for its counters; the time this takes doubles as a "how fast is the server" reading.
        var rt = 0; var cur: JSONObject? = null
        if (st.nodeUp) {
            val t0 = System.nanoTime()
            cur = runCatching {
                val c = URL("http://127.0.0.1:${Config.SERVER_PORT}/__vps_metrics").openConnection() as HttpURLConnection
                c.connectTimeout = 2000; c.readTimeout = 2000
                JSONObject(c.inputStream.bufferedReader().readText())
            }.getOrNull()
            rt = ((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1)
        }
        fun d(k: String): Long { val c = cur?.optLong(k) ?: 0L; val p = prev?.optLong(k) ?: 0L; return if (cur == null) 0 else if (c >= p) c - p else c } // counters restart at 0 with Node
        val b = Bucket(System.currentTimeMillis(), SAMPLE_SECS, if (lvl < 0) -1 else lvl * 100 / scale, plugged, temp,
            ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt(), ((sf.totalBytes - sf.availableBytes) * 100 / sf.totalBytes).toInt(),
            d("req").toInt(), d("ai").toInt(), d("manage").toInt(), d("other").toInt(), d("bytesIn"), d("bytesOut"), rt,
            if (st.nodeUp) 1.0 else 0.0, if (st.tunnelUrl != null) 1.0 else 0.0, if (st.online) 1.0 else 0.0, if (st.llm.ready) 1.0 else 0.0,
            d("denied").toInt(), d("errors").toInt())
        if (cur != null) prev = cur
        synchronized(this) {
            samples.addLast(b); val cutoff = b.t - KEEP_MS; while (samples.isNotEmpty() && samples.first().t < cutoff) samples.removeFirst()
            runCatching { metricsFile.appendText(b.toJson().toString() + "\n") }
            // once an hour, say in words how busy it was
            if (b.t - lastHourly >= 3_600_000) {
                if (lastHourly != 0L) {
                    val hr = samples.filter { it.t > b.t - 3_600_000 }
                    val n = hr.sumOf { it.reqs }; val ai = hr.sumOf { it.ai }
                    val e = Event(b.t, "info", if (n == 0) "Quiet hour: nobody used the server." else "Last hour: $n requests handled" + if (ai > 0) " ($ai were AI chat requests)." else ".")
                    events.addLast(e); runCatching { eventsFile.appendText(e.toJson().toString() + "\n") }
                }
                lastHourly = b.t
            }
            version.value++
        }
    }

    /** Measurements for the last [hours] hours, squeezed into about 120 slots, plus the events in that period. */
    @Synchronized fun query(hours: Int): Insights {
        val now = System.currentTimeMillis(); val from = now - hours * 3_600_000L
        val raw = samples.filter { it.t >= from }
        // slots are at most range/120 wide, but finer when there is little data so charts are useful from the first minutes
        val span = if (raw.size < 2) 0L else raw.last().t - raw.first().t
        val slot = maxOf(SAMPLE_SECS * 1000L, minOf(hours * 3_600_000L / 120, span / 60))
        val out = raw.groupBy { it.t / slot }.toSortedMap().values.map { g ->
            fun avg(f: (Bucket) -> Double) = g.map(f).average()
            val rts = g.map { it.rtMs }.filter { it > 0 }
            Bucket(g.last().t, g.sumOf { it.secs }, avg { it.battery.toDouble() }.toInt(), g.any { it.charging }, avg { it.tempC }, avg { it.memPct.toDouble() }.toInt(),
                avg { it.storPct.toDouble() }.toInt(), g.sumOf { it.reqs }, g.sumOf { it.ai }, g.sumOf { it.manage }, g.sumOf { it.other },
                g.sumOf { it.bytesIn }, g.sumOf { it.bytesOut }, if (rts.isEmpty()) 0 else rts.average().toInt(),
                avg { it.nodeUp }, avg { it.tunnelUp }, avg { it.online }, avg { it.llmReady }, g.sumOf { it.denied }, g.sumOf { it.errors })
        }
        return Insights(hours, out, events.filter { it.t >= from })
    }
}
