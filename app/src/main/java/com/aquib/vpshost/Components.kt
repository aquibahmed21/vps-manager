package com.aquib.vpshost

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---------- transient results (Test tunnel, Verify repo, Check token) ----------
enum class Kind { OK, WARN, FAIL }
class Outcome(val kind: Kind, val title: String, val detail: String = "")

/** Holds one action's state: idle -> running -> outcome (auto-clears after 10 s, buttons return to idle). */
class Probe {
    var running by mutableStateOf(false); private set
    var outcome by mutableStateOf<Outcome?>(null); private set
    suspend fun run(block: suspend () -> Outcome) {
        running = true; outcome = null
        outcome = try { block() } catch (e: Exception) { Outcome(Kind.FAIL, "Failed", e.message ?: e.javaClass.simpleName) }
        running = false
    }
    fun clear() { outcome = null }
}

private fun Kind.color() = when (this) { Kind.OK -> Good; Kind.WARN -> Warn; Kind.FAIL -> Bad }

@Composable
fun ProbeButton(label: String, busyLabel: String, probe: Probe, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    val o = probe.outcome
    val tint = o?.kind?.color()
    val text = when { probe.running -> busyLabel; o != null -> o.title; else -> label }
    val pad = PaddingValues(horizontal = 14.dp)
    val mod = Modifier.height(34.dp)
    val on = enabled && !probe.running
    val content: @Composable RowScope.() -> Unit = {
        if (probe.running) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = LocalContentColor.current); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 13.sp, maxLines = 1)
    }
    when {
        tint != null -> Button(onClick, mod, on, colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = Color.White), contentPadding = pad, content = content)
        filled -> Button(onClick, mod, on, contentPadding = pad, content = content)
        else -> OutlinedButton(onClick, mod, on, contentPadding = pad, content = content)
    }
}

@Composable
fun ResultBanner(heading: String, probe: Probe) {
    val o = probe.outcome ?: return
    var progress by remember(o) { mutableFloatStateOf(1f) }
    LaunchedEffect(o) { // auto-hide after 10 seconds, with a visible countdown
        val t0 = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - t0
            if (elapsed >= 10_000) break
            progress = 1f - elapsed / 10_000f
            delay(100)
        }
        probe.clear()
    }
    val c = o.kind.color()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.copy(alpha = 0.12f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(8.dp))
                Text("$heading: ${o.title}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = c)
                TextButton({ probe.clear() }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(28.dp)) { Text("Dismiss", fontSize = 12.sp) }
            }
            if (o.detail.isNotEmpty()) SelectionContainer { Text(o.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().height(2.dp), color = c, trackColor = c.copy(alpha = 0.15f))
        }
    }
}

// ---------- layout pieces ----------
@Composable
fun Panel(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun StatusRow(label: String, value: String, color: Color? = null, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(112.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (color != null) { Box(Modifier.size(7.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(7.dp)) }
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null, maxLines = 2)
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit, enabled: Boolean = true, filled: Boolean = false) {
    val pad = PaddingValues(horizontal = 14.dp); val m = Modifier.height(34.dp)
    if (filled) Button(onClick, m, enabled, contentPadding = pad) { Text(text, fontSize = 13.sp) }
    else OutlinedButton(onClick, m, enabled, contentPadding = pad) { Text(text, fontSize = 13.sp) }
}
