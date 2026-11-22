package com.aquib.vpshost

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RANGES = listOf(1 to "1 hour", 6 to "6 hours", 24 to "24 hours", 72 to "3 days")

private fun bytesText(b: Long): String = when { b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0); b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0); b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0); else -> "$b bytes" }

/** The same screen runs on the host phone (reads its own store) and in the Monitor app (reads over the tunnel). */
@Composable
fun InsightsScreen(load: suspend (hours: Int) -> Insights) {
    var hours by rememberSaveable { mutableIntStateOf(24) }
    var data by remember { mutableStateOf<Insights?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var importantOnly by rememberSaveable { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(hours, owner) { // only refreshes while the screen is visible
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try { data = load(hours); error = null } catch (e: Exception) { error = e.message ?: "Couldn't load" }
                delay(10_000)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RANGES.forEach { (h, l) -> FilterChip(hours == h, { hours = h; data = null }, { Text(l, fontSize = 12.sp) }) }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
        val d = data
        if (d == null) { if (error == null) Text("Loading...", style = MaterialTheme.typography.bodySmall); return@Column }
        val times = d.buckets.map { it.t }
        fun rate(b: Bucket, n: Int) = n * 60f / b.secs   // per minute
        val rangeLabel = RANGES.first { it.first == d.hours }.second

        Panel("In plain words") {
            plainWords(d, rangeLabel).forEach { (c, text) ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(10.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Panel("Requests") {
            Text("How many times people or apps asked the server for something, per minute.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LineChart(times, listOf(
                Series("AI chat", ChartIndigo, d.buckets.map { rate(it, it.ai) }),
                Series("Monitor app", ChartAmber, d.buckets.map { rate(it, it.manage) }),
                Series("Everything else", ChartTeal, d.buckets.map { rate(it, it.other) }),
            ), "requests per minute")
            Text("In total: ${d.buckets.sumOf { it.reqs }} requests (${d.buckets.sumOf { it.ai }} AI chat, ${d.buckets.sumOf { it.manage }} from the Monitor app, ${d.buckets.sumOf { it.other }} other).", style = MaterialTheme.typography.bodySmall)
        }

        Panel("Data coming in and going out") {
            Text("Incoming is what people send to the server; outgoing is what the server sends back (answers, pages, files).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LineChart(times, listOf(
                Series("Going out", ChartSky, d.buckets.map { it.bytesOut / 1024f * 60f / it.secs }),
                Series("Coming in", ChartRose, d.buckets.map { it.bytesIn / 1024f * 60f / it.secs }),
            ), "kilobytes per minute")
            Text("In total: ${bytesText(d.buckets.sumOf { it.bytesOut })} sent out, ${bytesText(d.buckets.sumOf { it.bytesIn })} received.", style = MaterialTheme.typography.bodySmall)
        }

        Panel("Restarts and updates") {
            val end = System.currentTimeMillis(); val slotMs = d.hours * 3_600_000L / 24; val start = end - d.hours * 3_600_000L
            val slots = (0 until 24).map { start + it * slotMs }
            fun count(kind: String, i: Int) = d.events.count { it.kind == kind && it.t >= slots[i] && it.t < slots[i] + slotMs }.toFloat()
            BarChart(slots, slots.indices.map { listOf(count("restart", it), count("update", it)) }, listOf(ChartAmber, ChartIndigo), listOf("Restarted", "Code updated"))
            val r = d.events.count { it.kind == "restart" }; val u = d.events.count { it.kind == "update" }; val n = d.events.count { it.kind == "tunnel" }
            Text("In the last $rangeLabel: restarted $r ${if (r == 1) "time" else "times"}, updated $u ${if (u == 1) "time" else "times"}, got $n new public ${if (n == 1) "address" else "addresses"}.", style = MaterialTheme.typography.bodySmall)
        }

        Panel("Was everything working?") {
            Text("Green means working, red means it was down.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatusStrip("Website server", times, d.buckets.map { it.nodeUp })
            StatusStrip("Reachable from the internet", times, d.buckets.map { it.tunnelUp })
            StatusStrip("Phone's own internet", times, d.buckets.map { it.online })
            StatusStrip("AI model ready", times, d.buckets.map { it.llmReady })
        }

        Panel("How fast the server answers") {
            LineChart(times, listOf(Series("Response time", ChartTeal, d.buckets.map { if (it.rtMs > 0) it.rtMs.toFloat() else null })), "milliseconds (lower is better)")
        }

        Panel("Phone battery") {
            LineChart(times, listOf(Series("Battery", Good, d.buckets.map { if (it.battery >= 0) it.battery.toFloat() else null })), "percent", yMax = 100f)
            val chargingSlots = d.buckets.count { it.charging }
            if (chargingSlots > 0) Text("It was charging for about ${(chargingSlots * 100 / d.buckets.size)}% of this time.", style = MaterialTheme.typography.bodySmall)
        }

        Panel("Memory and storage") {
            LineChart(times, listOf(
                Series("Memory in use", ChartIndigo, d.buckets.map { it.memPct.toFloat() }),
                Series("Storage used", ChartAmber, d.buckets.map { it.storPct.toFloat() }),
            ), "percent", yMax = 100f)
        }

        Panel("Phone temperature") {
            LineChart(times, listOf(Series("Temperature", ChartRose, d.buckets.map { it.tempC.toFloat() })), "degrees Celsius")
        }

        if (d.buckets.any { it.denied > 0 || it.errors > 0 }) Panel("Rejected and failed requests") {
            LineChart(times, listOf(
                Series("Rejected (wrong or missing key, blocked)", ChartAmber, d.buckets.map { it.denied.toFloat() }),
                Series("Server errors", ChartRose, d.buckets.map { it.errors.toFloat() }),
            ), "requests in each time slot")
        }

        Panel("What happened") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Newest first. Technical details are in the Logs tab.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilterChip(importantOnly, { importantOnly = !importantOnly }, { Text("Important only", fontSize = 11.sp) })
            }
            val shown = d.events.filter { !importantOnly || it.kind in setOf("restart", "update", "problem", "battery", "remote", "network") }.asReversed().take(80)
            if (shown.isEmpty()) Text("Nothing to report yet.", style = MaterialTheme.typography.bodySmall)
            shown.forEach { e ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(kindColor(e.kind))); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.text, style = MaterialTheme.typography.bodyMedium)
                        Text(whenText(e.t), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun kindColor(kind: String): Color = when (kind) {
    "problem" -> Bad; "restart" -> ChartAmber; "update" -> ChartIndigo; "tunnel" -> ChartTeal; "llm" -> ChartSky
    "battery" -> Warn; "remote" -> ChartRose; "network" -> ChartSky; else -> Color(0xFF94A3B8)
}

private fun whenText(t: Long): String {
    val age = System.currentTimeMillis() - t
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
    return when {
        age < 60_000 -> "just now"
        age < 3_600_000 -> "${age / 60_000} min ago  ·  $clock"
        age < 86_400_000 -> "${age / 3_600_000} h ago  ·  $clock"
        else -> SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(t))
    }
}

/** A few sentences anyone can read, worked out from the numbers. */
internal fun plainWords(d: Insights, range: String): List<Pair<Color, String>> {
    val b = d.buckets; val out = ArrayList<Pair<Color, String>>()
    if (b.isEmpty()) return listOf(Warn to "No measurements yet. The charts fill in as the server runs (a reading every 30 seconds).")
    val last = b.last()
    out += when {
        last.nodeUp > 0.5 && last.tunnelUp > 0.5 -> Good to "The server is online and can be reached from the internet."
        last.nodeUp <= 0.5 -> Bad to "The website server is not running right now."
        else -> Warn to "The server is running, but it has no public internet address at the moment."
    }
    val up = b.map { it.tunnelUp }.average() * 100
    out += (if (up >= 99) Good else Warn) to "It was reachable ${"%.0f".format(up)}% of the last $range."
    val reqs = b.sumOf { it.reqs }
    out += if (reqs == 0) Color(0xFF94A3B8) to "Nobody used the server in the last $range."
    else Color(0xFF94A3B8) to "It handled $reqs requests in the last $range: ${b.sumOf { it.ai }} AI chat, ${b.sumOf { it.manage }} from the Monitor app and ${b.sumOf { it.other }} other. It sent out ${bytesText(b.sumOf { it.bytesOut })} and received ${bytesText(b.sumOf { it.bytesIn })}."
    val r = d.events.count { it.kind == "restart" }; val u = d.events.count { it.kind == "update" }
    out += (if (r == 0) Good else Warn) to (if (r == 0 && u == 0) "No restarts or updates in the last $range." else "It restarted $r ${if (r == 1) "time" else "times"} and was updated $u ${if (u == 1) "time" else "times"} in the last $range.")
    out += (if (last.battery in 0..20 && !last.charging) Bad else if (last.battery in 0..30 && !last.charging) Warn else Good) to
        "The phone battery is ${last.battery}%" + (if (last.charging) " and charging." else " and not charging.")
    val first = b.firstOrNull { it.battery >= 0 }
    if (first != null && !last.charging && !b.any { it.charging } && last.t - first.t >= 30 * 60_000L && first.battery > last.battery) {
        val perHour = (first.battery - last.battery) * 3_600_000.0 / (last.t - first.t)
        out += Color(0xFF94A3B8) to "It is using about ${"%.1f".format(perHour)}% of battery per hour."
    }
    if (last.tempC >= 40) out += Warn to "The phone is warm (${"%.0f".format(last.tempC)} °C)."
    if (last.memPct >= 90) out += Warn to "Phone memory is almost full (${last.memPct}%)."
    val rts = b.map { it.rtMs }.filter { it > 0 }
    if (rts.isNotEmpty()) out += (if (rts.max() > 1500) Warn else Good) to ("The server answers in about ${rts.average().toInt()} ms" + (if (rts.max() > 1500) ", but it was slow at times (up to ${rts.max()} ms)." else "."))
    val denied = b.sumOf { it.denied }; val errors = b.sumOf { it.errors }
    if (denied > 0) out += Warn to (if (denied == 1) "1 request was turned away (wrong or missing key, or too many attempts)." else "$denied requests were turned away (wrong or missing key, or too many attempts).")
    if (errors > 0) out += Warn to (if (errors == 1) "1 request failed because of a server error." else "$errors requests failed because of a server error.")
    return out
}
