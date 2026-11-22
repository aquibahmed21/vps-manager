package com.aquib.vpshost

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(testProbe: Probe, verifyProbe: Probe, openSettings: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val st by HostService.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val tokenSet = prefs.githubToken.isNotEmpty()
    val url = st.tunnelUrl

    val online = st.running && url != null && st.nodeUp
    val (title, subtitle, accent) = when {
        !st.running -> Triple("Stopped", "Start the server to go online.", Bad)
        online -> Triple("Online", "Reachable from anywhere through Cloudflare.", Good)
        else -> Triple(st.busy.ifEmpty { "Starting" }, "This can take up to a minute.", Warn)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // hero
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = accent, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(if (online) Icons.Filled.Check else if (st.running) Icons.Filled.Refresh else Icons.Filled.PlayArrow, null, tint = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!st.running) Button({ HostService.start(ctx) }, Modifier.fillMaxWidth().height(40.dp)) { Text("Start server", fontSize = 14.sp) }
                else OutlinedButton({ HostService.stop(ctx) }, Modifier.fillMaxWidth().height(40.dp)) { Text("Stop server", fontSize = 14.sp) }
            }
        }

        if (!tokenSet) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Warn.copy(alpha = 0.14f))) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = Warn, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("GitHub token missing. The tunnel URL can't be published.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(openSettings) { Text("Add token", fontSize = 12.sp) }
            }
        }

        Panel("Public URL") {
            SelectionContainer { Text(url ?: "Not available yet", style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallButton(if (copied) "Copied" else "Copy", { clipboard.setText(AnnotatedString(url!!)); copied = true }, enabled = url != null)
                ProbeButton("Test tunnel", "Testing...", testProbe, enabled = url != null, filled = true) {
                    scope.launch { testProbe.run { TunnelTester.run(url!!) } }
                }
                ProbeButton("Verify repo", "Checking...", verifyProbe, enabled = url != null) {
                    scope.launch { verifyProbe.run { verifyRepo(prefs, url!!) } }
                }
            }
            ResultBanner("Tunnel test", testProbe)
            ResultBanner("Repo check", verifyProbe)
        }

        Panel("Status") {
            StatusRow("Node server", if (st.nodeUp) "Running on port ${Config.SERVER_PORT}" else "Not running", if (st.nodeUp) Good else Bad)
            StatusRow("LLM", when { st.llm.ready -> "Ready: ${st.llm.model}"; st.llm.running -> "Loading..."; else -> "Stopped" },
                when { st.llm.ready -> Good; st.llm.running -> Warn; else -> MaterialTheme.colorScheme.outline })
            StatusRow("Internet", if (st.online) "Connected" else "Offline", if (st.online) Good else Bad)
            val pub = when {
                url != null && st.publishedUrl == url -> "Up to date on GitHub" to Good
                !tokenSet -> "Not published (no token)" to Warn
                url != null -> "Publishing..." to Warn
                else -> "Waiting for tunnel" to Warn
            }
            StatusRow("GitHub", pub.first, pub.second)
        }

        Panel("Server code") {
            StatusRow("Repository", "${Config.EXPRESS_OWNER}/${Config.EXPRESS_REPO}")
            StatusRow("Version", st.repoSha.take(7).ifEmpty { "Not downloaded" }, mono = true)
            SmallButton("Check for updates", { HostService.update(ctx) }, enabled = st.running)
        }
    }
}

private suspend fun verifyRepo(prefs: Prefs, current: String): Outcome {
    val gh = GitHubPublisher { prefs.githubToken }
    val repoUrl = runCatching { gh.remoteUrl() }.getOrNull()
        ?: return Outcome(Kind.FAIL, "Can't read repo", "Check the GitHub token in Settings and the internet connection.")
    val pages = gh.pagesUrl()
    return when {
        repoUrl == current && pages == current -> Outcome(Kind.OK, "Matches", "Repository and live site both point to this tunnel.")
        repoUrl == current -> Outcome(Kind.WARN, "Pending", "Repository is correct. The live site still serves ${pages ?: "an older URL"} and should update within a few minutes.")
        else -> Outcome(Kind.FAIL, "Mismatch", "Repository has:\n$repoUrl\nTunnel is:\n$current")
    }
}
