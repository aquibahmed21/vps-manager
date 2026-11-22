package com.aquib.vpshost

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen() {
    val ctx = LocalContext.current
    val st by HostService.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    var source by remember { mutableStateOf("App") }
    var errorsOnly by remember { mutableStateOf(false) }
    var serverLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }

    // Node's own log file, refreshed while this tab is open.
    LaunchedEffect(source) {
        if (source != "Server") return@LaunchedEffect
        val f = RepoManager(ctx).logFile
        while (true) {
            serverLog = withContext(Dispatchers.IO) { if (f.exists()) f.readLines().takeLast(300) else listOf("No server log yet.") }
            delay(3000)
        }
    }

    val app = st.logs.filter { (!errorsOnly || it.level != Lvl.INFO) && (source != "LLM" || it.msg.startsWith("llm:")) }
    val text = if (source != "Server") app.joinToString("\n") { "${it.time} ${it.level.name} ${it.msg}" } else serverLog.joinToString("\n")

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(source == "App", { source = "App" }, { Text("App", fontSize = 12.sp) })
            FilterChip(source == "Server", { source = "Server" }, { Text("Server", fontSize = 12.sp) })
            FilterChip(source == "LLM", { source = "LLM" }, { Text("LLM", fontSize = 12.sp) })
            if (source != "Server") FilterChip(errorsOnly, { errorsOnly = !errorsOnly }, { Text("Problems only", fontSize = 12.sp) })
            Spacer(Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (source != "Server") "${app.size} entries" else "node.log (last 300 lines)", Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SmallButton(if (copied) "Copied" else "Copy all", { clipboard.setText(AnnotatedString(text)); copied = true }, enabled = text.isNotEmpty())
            if (source != "Server") SmallButton("Clear", { HostService.clearLogs() }, enabled = st.logs.isNotEmpty())
        }

        Card(Modifier.fillMaxSize(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            SelectionContainer {
                if (source != "Server") LazyColumn(Modifier.fillMaxSize().padding(10.dp), reverseLayout = true, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (app.isEmpty()) item { Text("Nothing logged yet.", style = MaterialTheme.typography.bodySmall) }
                    items(app.reversed()) { l ->
                        val c = when (l.level) { Lvl.ERROR -> Bad; Lvl.WARN -> Warn; else -> MaterialTheme.colorScheme.onSurface }
                        Row {
                            Text(l.time, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(l.msg, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = c)
                        }
                    }
                } else LazyColumn(Modifier.fillMaxSize().padding(10.dp), reverseLayout = true) {
                    items(serverLog.reversed()) { line ->
                        val c = if (line.contains("error", true) || line.contains("uncaught", true)) Bad else MaterialTheme.colorScheme.onSurface
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = c)
                    }
                }
            }
        }
    }
}
