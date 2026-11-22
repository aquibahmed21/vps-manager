package com.aquib.vpshost

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(tokenProbe: Probe) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(prefs.githubToken) }
    var show by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) { delay(2000); saved = false } }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Panel("GitHub access") {
            Text("A fine-grained token lets the app save each new tunnel URL to ${Config.POINTER_OWNER}/${Config.POINTER_REPO}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                token, { token = it; saved = false }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 13.sp), placeholder = { Text("github_pat_...", fontSize = 13.sp) },
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton({ show = !show }) { Text(if (show) "Hide" else "Show", fontSize = 12.sp) } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallButton(if (saved) "Saved" else "Save", { prefs.githubToken = token; saved = true }, filled = true, enabled = token.trim() != prefs.githubToken)
                ProbeButton("Check token", "Checking...", tokenProbe, enabled = token.isNotBlank()) {
                    prefs.githubToken = token; saved = true
                    scope.launch {
                        tokenProbe.run {
                            val (ok, msg) = GitHubPublisher { prefs.githubToken }.checkAccess()
                            Outcome(if (ok) Kind.OK else Kind.FAIL, if (ok) "Token works" else "Token problem", msg)
                        }
                    }
                }
            }
            ResultBanner("Token", tokenProbe)
            Text("Required: Contents (read and write) on ${Config.POINTER_REPO}. Pages (read and write) is optional.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Panel("Remote access") {
            var adminToken by remember { mutableStateOf(prefs.adminToken) }
            var showAdmin by remember { mutableStateOf(false) }
            var copiedAdmin by remember { mutableStateOf(false) }
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            LaunchedEffect(copiedAdmin) { if (copiedAdmin) { delay(2000); copiedAdmin = false } }
            Text("The VPS Monitor app signs in with this admin token to see this phone, browse and delete its files, and control the server. Anyone who has it has full control, so keep it private.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (showAdmin) adminToken else adminToken.take(8) + "..." + adminToken.takeLast(4), style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(if (showAdmin) "Hide" else "Show", { showAdmin = !showAdmin })
                SmallButton(if (copiedAdmin) "Copied" else "Copy", { clip.setText(androidx.compose.ui.text.AnnotatedString(adminToken)); copiedAdmin = true }, filled = true)
                SmallButton("New token", { adminToken = "adm-" + ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }; prefs.adminToken = adminToken })
            }
            Text("A new token signs every monitor out until you enter it again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Panel("Low-battery alarm") {
            val bs by BatteryGuardService.state.collectAsState()
            var enabled by remember { mutableStateOf(prefs.batteryAlarm) }
            var testing by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Loud alarm when battery is low", style = MaterialTheme.typography.bodyMedium)
                    Text("Plays on the alarm channel at full volume, even in silent or Do Not Disturb. Stops while charging.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(enabled, { enabled = it; prefs.batteryAlarm = it; if (it) BatteryGuardService.start(ctx) else BatteryGuardService.stop(ctx) })
            }
            StatusRow("30% or below", "Beep every 30 seconds"); StatusRow("15% or below", "Beep every 15 seconds"); StatusRow("Below 10%", "Beep every 3 seconds")
            StatusRow("Battery now", if (bs.level < 0) "Unknown" else "${bs.level}%" + if (bs.charging) ", charging" else "",
                when { !enabled -> MaterialTheme.colorScheme.outline; bs.beepEverySec > 0 -> Bad; else -> Good })
            SmallButton(if (testing) "Beeping..." else "Test beep", { testing = true; scope.launch { runCatching { Beeper(ctx).beep() }; testing = false } }, enabled = !testing)
        }

        Panel("Repositories") {
            StatusRow("Server code", "${Config.EXPRESS_OWNER}/${Config.EXPRESS_REPO}")
            StatusRow("URL pointer", "${Config.POINTER_OWNER}/${Config.POINTER_REPO}")
            StatusRow("Pointer site", Config.POINTER_PAGES_URL.removePrefix("https://"), mono = true)
            StatusRow("Local folder", RepoManager(ctx).repoDir.path, mono = true)
        }

        Panel("Permissions") {
            Perm.values().forEach { p ->
                val ok = Permissions.has(ctx, p)
                StatusRow(p.title, if (ok) "Granted" else "Missing", if (ok) Good else Bad)
            }
        }

        Panel("About") {
            StatusRow("Version", version)
            StatusRow("Runtime", "Node.js 18 (embedded) + cloudflared")
            Text("The server restarts automatically after reboots, crashes and connection loss. Android's Force stop is the one thing no app can survive; open the app again after using it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
