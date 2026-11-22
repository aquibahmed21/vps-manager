package com.aquib.vpshost

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VpsTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { Root() } }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Filled.Home),
    MODELS("LLM", Icons.Filled.Star),
    INSIGHTS("Insights", Icons.Filled.Info),
    LOGS("Logs", Icons.AutoMirrored.Filled.List),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    var missing by remember { mutableStateOf(Permissions.missing(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) { // re-check whenever the user returns from a system settings screen
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                missing = Permissions.missing(ctx)
                if (missing.isEmpty()) Watchdog.ensure(ctx)
            }
        }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (missing.isNotEmpty()) PermissionGate(missing) { missing = Permissions.missing(ctx) } else AppShell()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell() {
    var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
    // Results live here so they survive switching tabs.
    val testProbe = remember { Probe() }
    val verifyProbe = remember { Probe() }
    val tokenProbe = remember { Probe() }
    val llmProbe = remember { Probe() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.height(64.dp)) {
                Tab.values().forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label, style = MaterialTheme.typography.labelMedium, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip) }, alwaysShowLabel = true)
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.DASHBOARD -> DashboardScreen(testProbe, verifyProbe) { tab = Tab.SETTINGS }
                Tab.MODELS -> ModelsScreen(llmProbe)
                Tab.INSIGHTS -> InsightsScreen { h -> Telemetry.query(h) }
                Tab.LOGS -> LogsScreen()
                Tab.SETTINGS -> SettingsScreen(tokenProbe)
            }
        }
    }
}

@Composable
private fun PermissionGate(missing: List<Perm>, refresh: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Permissions required", style = MaterialTheme.typography.headlineSmall)
        Text("The app stays locked until every permission is granted (${Perm.values().size - missing.size} of ${Perm.values().size} done).",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        missing.forEach { p ->
            Panel(p.title) {
                Text(p.why, style = MaterialTheme.typography.bodyMedium)
                SmallButton("Grant", { Permissions.request(activity, p) { notif.launch(Manifest.permission.POST_NOTIFICATIONS) } }, filled = true)
            }
        }
    }
}
