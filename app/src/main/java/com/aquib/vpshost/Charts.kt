package com.aquib.vpshost

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

val ChartTeal = Color(0xFF14B8A6)
val ChartIndigo = Color(0xFF6366F1)
val ChartAmber = Color(0xFFF59E0B)
val ChartRose = Color(0xFFF43F5E)
val ChartSky = Color(0xFF38BDF8)

class Series(val label: String, val color: Color, val values: List<Float?>)

private fun timeLabel(t: Long, spanMs: Long): String =
    SimpleDateFormat(if (spanMs > 20 * 3_600_000L) "d MMM HH:mm" else "HH:mm", Locale.getDefault()).format(Date(t))

/** Rounds a maximum up to a tidy axis value (1, 2, 5, 10, 20, 50 ...). */
private fun niceMax(v: Float): Float {
    if (v <= 0f) return 1f
    val p = 10f.pow(kotlin.math.floor(log10(v.toDouble())).toFloat()); val f = v / p
    return (when { f <= 1f -> 1f; f <= 2f -> 2f; f <= 5f -> 5f; else -> 10f }) * p
}
private fun axisText(v: Float): String = when { v >= 1000f -> "%.1fk".format(v / 1000f); v >= 10f || v == 0f -> v.toInt().toString(); else -> "%.1f".format(v) }

@Composable
fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (l, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(5.dp))
                Text(l, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun NoData() = Text("Collecting data. A chart appears after a couple of minutes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(vertical = 18.dp))

/** Line chart over time. Values are plotted at their real time position; null leaves a gap. */
@Composable
fun LineChart(times: List<Long>, series: List<Series>, unit: String, height: Dp = 120.dp, yMax: Float? = null) {
    if (times.size < 2) { NoData(); return }
    val tm = rememberTextMeasurer(); val muted = MaterialTheme.colorScheme.onSurfaceVariant; val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val top = (yMax ?: niceMax(series.flatMap { it.values }.filterNotNull().maxOrNull() ?: 0f))
    if (series.size > 1) { Legend(series.map { it.label to it.color }); Spacer(Modifier.height(4.dp)) }
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val style = TextStyle(fontSize = 10.sp, color = muted)
        val left = 34.dp.toPx(); val right = 6.dp.toPx(); val topPad = 6.dp.toPx(); val bottom = 16.dp.toPx()
        val w = size.width - left - right; val h = size.height - topPad - bottom
        val t0 = times.first(); val t1 = times.last(); val span = max(1L, t1 - t0)
        for (i in 0..2) {
            val y = topPad + h * i / 2f
            drawLine(grid, Offset(left, y), Offset(left + w, y), 1.dp.toPx())
            val lbl = tm.measure(axisText(top * (2 - i) / 2f), style); drawText(lbl, topLeft = Offset(left - lbl.size.width - 4.dp.toPx(), y - lbl.size.height / 2f))
        }
        tm.measure(timeLabel(t0, span), style).let { drawText(it, topLeft = Offset(left, size.height - it.size.height)) }
        tm.measure(timeLabel(t1, span), style).let { drawText(it, topLeft = Offset(left + w - it.size.width, size.height - it.size.height)) }
        fun x(t: Long) = left + w * (t - t0) / span
        fun y(v: Float) = topPad + h * (1f - (v / top).coerceIn(0f, 1f))
        series.forEachIndexed { si, s ->
            val line = Path(); var pen = false; var lastX = 0f; var firstX = 0f
            s.values.forEachIndexed { i, v ->
                if (v == null) { pen = false; return@forEachIndexed }
                val px = x(times[i]); val py = y(v)
                if (!pen) { line.moveTo(px, py); pen = true; firstX = px } else line.lineTo(px, py); lastX = px
                if (times.size < 40) drawCircle(s.color, 2.2.dp.toPx(), Offset(px, py))
            }
            if (si == 0 && s.values.all { it != null }) { // soft fill under the main line
                val fill = Path().apply { addPath(line); lineTo(lastX, topPad + h); lineTo(firstX, topPad + h); close() }
                drawPath(fill, s.color.copy(alpha = 0.12f))
            }
            drawPath(line, s.color, style = Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
    Text(unit, style = MaterialTheme.typography.bodySmall, color = muted)
}

/** Stacked columns, one per time slot (used for restarts and updates). */
@Composable
fun BarChart(slotStarts: List<Long>, stacks: List<List<Float>>, colors: List<Color>, legend: List<String>, height: Dp = 100.dp) {
    val tm = rememberTextMeasurer(); val muted = MaterialTheme.colorScheme.onSurfaceVariant; val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    Legend(legend.zip(colors)); Spacer(Modifier.height(4.dp))
    val tops = stacks.map { it.sum() }; val top = max(2f, ceil((tops.maxOrNull() ?: 0f).toDouble()).toFloat())
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val style = TextStyle(fontSize = 10.sp, color = muted)
        val left = 34.dp.toPx(); val right = 6.dp.toPx(); val topPad = 6.dp.toPx(); val bottom = 16.dp.toPx()
        val w = size.width - left - right; val h = size.height - topPad - bottom
        for (i in 0..2) {
            val y = topPad + h * i / 2f
            drawLine(grid, Offset(left, y), Offset(left + w, y), 1.dp.toPx())
            val lbl = tm.measure(axisText(top * (2 - i) / 2f), style); drawText(lbl, topLeft = Offset(left - lbl.size.width - 4.dp.toPx(), y - lbl.size.height / 2f))
        }
        val span = (slotStarts.last() - slotStarts.first()) + (if (slotStarts.size > 1) slotStarts[1] - slotStarts[0] else 0)
        tm.measure(timeLabel(slotStarts.first(), span), style).let { drawText(it, topLeft = Offset(left, size.height - it.size.height)) }
        val n = stacks.size; val slotW = w / n; val barW = slotW * 0.7f
        stacks.forEachIndexed { i, st ->
            var base = topPad + h
            st.forEachIndexed { k, v ->
                if (v > 0f) { val bh = h * v / top; drawRect(colors[k], Offset(left + slotW * i + (slotW - barW) / 2f, base - bh), Size(barW, bh)); base -= bh }
            }
        }
    }
}

/** A coloured strip showing whether something was working through time: green = yes, red = no, amber = partly. */
@Composable
fun StatusStrip(label: String, times: List<Long>, fractions: List<Double>) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(label, style = MaterialTheme.typography.bodySmall, color = muted)
    if (times.size < 2) { NoData(); return }
    Canvas(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp))) {
        val t0 = times.first(); val t1 = times.last(); val span = max(1L, t1 - t0).toFloat()
        val ok = Good; val bad = Bad
        times.forEachIndexed { i, t ->
            val a = if (i == 0) 0f else (times[i - 1] - t0) / span * size.width; val b = (t - t0) / span * size.width
            val f = fractions[i].toFloat().coerceIn(0f, 1f)
            val c = if (f >= 0.99f) ok else if (f <= 0.01f) bad else lerp(bad, ok, f)
            drawRect(c, Offset(a, 0f), Size(max(1f, b - a + 1f), size.height))
        }
    }
    Spacer(Modifier.height(6.dp))
}
