package com.aquib.vpshost

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

// ── Category model ────────────────────────────────────────────────────────────

private enum class SettingsPage(val label: String, val icon: ImageVector, val subtitle: String) {
    VOIP("VoIP", Icons.Filled.Call, "Users, roles, TURN & STUN"),
    FAMILY("Family", Icons.Filled.Home, "Database PIN & security"),
    SERVER("Server", Icons.Filled.Cloud, "GitHub, remote access & repos"),
    DEVICE("Device", Icons.Filled.PhoneAndroid, "Battery alarm & permissions"),
    ABOUT("About", Icons.Filled.Info, "Version & runtime info"),
}

// ── Root composable ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(tokenProbe: Probe) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var page by remember { mutableStateOf<SettingsPage?>(null) }

    if (page == null) {
        SettingsCategoryList { page = it }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(page!!.label) },
                    navigationIcon = {
                        IconButton({ page = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { pad ->
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (page) {
                    SettingsPage.VOIP   -> VoipSettings(prefs, tokenProbe)
                    SettingsPage.FAMILY -> FamilySettings(prefs)
                    SettingsPage.SERVER -> ServerSettings(prefs, tokenProbe)
                    SettingsPage.DEVICE -> DeviceSettings(prefs)
                    SettingsPage.ABOUT  -> AboutSettings(ctx)
                    null -> {}
                }
            }
        }
    }
}

// ── Category list ─────────────────────────────────────────────────────────────

@Composable
private fun SettingsCategoryList(onSelect: (SettingsPage) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        SettingsPage.values().forEach { p ->
            CategoryRow(p.label, p.icon, p.subtitle) { onSelect(p) }
        }
    }
}

@Composable
private fun CategoryRow(label: String, icon: ImageVector, subtitle: String, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

// ── ICE config JSON helpers ───────────────────────────────────────────────────

private fun iceConfigToJson(prefs: Prefs): String = JSONObject().apply {
    put("stunServer",    prefs.stunServer)
    put("turnServer",    prefs.turnServer)
    put("turnUsername",  prefs.turnUsername)
    put("turnCredential", prefs.turnCredential)
    put("cfTurnKeyId",   prefs.cfTurnKeyId)
    put("cfTurnApiToken", prefs.cfTurnApiToken)
}.toString(2)

/** Applies a single preset object or a flat config object onto the prefs. */
private fun applyIceJson(obj: JSONObject, prefs: Prefs,
    setStun: (String) -> Unit, setTurn: (String) -> Unit,
    setUser: (String) -> Unit, setCred: (String) -> Unit,
    setCfKey: (String) -> Unit, setCfToken: (String) -> Unit) {
    obj.optString("stunServer").takeIf { it.isNotEmpty() }?.also { setStun(it); prefs.stunServer = it }
    obj.optString("turnServer").takeIf { it.isNotEmpty() }?.also { setTurn(it); prefs.turnServer = it }
    obj.optString("turnUsername").takeIf { it.isNotEmpty() }?.also { setUser(it); prefs.turnUsername = it }
    obj.optString("turnCredential").takeIf { it.isNotEmpty() }?.also { setCred(it); prefs.turnCredential = it }
    obj.optString("cfTurnKeyId").takeIf { it.isNotEmpty() }?.also { setCfKey(it); prefs.cfTurnKeyId = it }
    obj.optString("cfTurnApiToken").takeIf { it.isNotEmpty() }?.also { setCfToken(it); prefs.cfTurnApiToken = it }
}

// ── VoIP settings ─────────────────────────────────────────────────────────────

@Composable
private fun VoipSettings(prefs: Prefs, tokenProbe: Probe) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // Shared state for all TURN fields so import/presets can populate them
    var stunServer  by remember { mutableStateOf(prefs.stunServer) }
    var turnServer  by remember { mutableStateOf(prefs.turnServer) }
    var turnUser    by remember { mutableStateOf(prefs.turnUsername) }
    var turnCred    by remember { mutableStateOf(prefs.turnCredential) }
    var cfKeyId     by remember { mutableStateOf(prefs.cfTurnKeyId) }
    var cfToken     by remember { mutableStateOf(prefs.cfTurnApiToken) }

    var presets     by remember { mutableStateOf<List<Pair<String, JSONObject>>>(emptyList()) }
    var importMsg   by remember { mutableStateOf("") }

    // Export launcher — creates a JSON file chosen by the user
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(iceConfigToJson(prefs).toByteArray()) }
            importMsg = "Exported"
        } catch (e: Exception) { importMsg = "Export failed: ${e.message}" }
    }

    // Import launcher — reads a JSON file (flat config or preset list)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: ""
            val root = JSONObject(text)
            if (root.has("presets")) {
                // Preset file — show picker
                val arr = root.getJSONArray("presets")
                val list = mutableListOf<Pair<String, JSONObject>>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list += obj.optString("name", "Preset ${i + 1}") to obj
                }
                presets = list
                importMsg = "${list.size} presets loaded — pick one below"
            } else {
                // Flat config — apply directly
                applyIceJson(root, prefs,
                    { stunServer = it }, { turnServer = it }, { turnUser = it },
                    { turnCred = it }, { cfKeyId = it }, { cfToken = it })
                importMsg = "Imported"
            }
        } catch (e: Exception) { importMsg = "Import failed: ${e.message}" }
    }

    LaunchedEffect(importMsg) { if (importMsg.isNotEmpty()) { delay(4000); importMsg = "" } }

    // User roles
    Panel("User roles & passwords") {
        Text(
            "Set which phone numbers have elevated access in MeshCall. Passwords are applied every time the server starts — leave blank to keep the existing password.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var rootPhone by remember { mutableStateOf(prefs.rootPhone) }
        var rootPassword by remember { mutableStateOf(prefs.rootPassword) }
        var showRootPw by remember { mutableStateOf(false) }
        var adminPhones by remember { mutableStateOf(prefs.adminPhones) }
        var adminPasswords by remember { mutableStateOf(prefs.adminPasswords) }
        var showAdminPw by remember { mutableStateOf(false) }
        var saved by remember { mutableStateOf(false) }
        LaunchedEffect(saved) { if (saved) { delay(2000); saved = false } }

        Text("Root user", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            rootPhone, { rootPhone = it; saved = false },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Root phone number", fontSize = 12.sp) },
            placeholder = { Text("9876543210", fontSize = 13.sp) },
            textStyle = TextStyle(fontSize = 13.sp),
        )
        OutlinedTextField(
            rootPassword, { rootPassword = it; saved = false },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Root password", fontSize = 12.sp) },
            placeholder = { Text("Leave blank to keep existing", fontSize = 13.sp) },
            textStyle = TextStyle(fontSize = 13.sp),
            visualTransformation = if (showRootPw) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton({ showRootPw = !showRootPw }) { Text(if (showRootPw) "Hide" else "Show", fontSize = 12.sp) } },
        )
        Text("Admin users", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            adminPhones, { adminPhones = it; saved = false },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Admin phones (comma-separated)", fontSize = 12.sp) },
            placeholder = { Text("9876543211,9876543212", fontSize = 13.sp) },
            textStyle = TextStyle(fontSize = 13.sp),
        )
        OutlinedTextField(
            adminPasswords, { adminPasswords = it; saved = false },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Admin passwords (phone:pass,phone:pass)", fontSize = 12.sp) },
            placeholder = { Text("9876543211:pass1,9876543212:pass2", fontSize = 13.sp) },
            textStyle = TextStyle(fontSize = 13.sp),
            visualTransformation = if (showAdminPw) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton({ showAdminPw = !showAdminPw }) { Text(if (showAdminPw) "Hide" else "Show", fontSize = 12.sp) } },
        )
        Text("Passwords take effect on next server restart.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SmallButton(if (saved) "Saved ✓" else "Save", {
            prefs.rootPhone = rootPhone; prefs.rootPassword = rootPassword
            prefs.adminPhones = adminPhones; prefs.adminPasswords = adminPasswords
            saved = true
        }, filled = true)
    }

    // Cloudflare TURN
    Panel("Cloudflare TURN (recommended)") {
        Text(
            "Free relay service for calls behind firewalls. Create a TURN key at dash.cloudflare.com → Calls → TURN, then paste the Key ID and an API token with 'Cloudflare Calls: TURN Edit' permission. Takes effect after restart.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var showCfToken by remember { mutableStateOf(false) }
        var cfSaved by remember { mutableStateOf(false) }
        LaunchedEffect(cfSaved) { if (cfSaved) { delay(2000); cfSaved = false } }
        OutlinedTextField(
            cfKeyId, { cfKeyId = it; cfSaved = false },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("TURN Key ID", fontSize = 12.sp) },
            placeholder = { Text("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", fontSize = 13.sp) },
            textStyle = TextStyle(fontSize = 13.sp),
        )
        OutlinedTextField(
            cfToken, { cfToken = it; cfSaved = false },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("API Token", fontSize = 12.sp) },
            textStyle = TextStyle(fontSize = 13.sp),
            visualTransformation = if (showCfToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton({ showCfToken = !showCfToken }) { Text(if (showCfToken) "Hide" else "Show", fontSize = 12.sp) } },
        )
        SmallButton(if (cfSaved) "Saved ✓" else "Save", {
            prefs.cfTurnKeyId = cfKeyId; prefs.cfTurnApiToken = cfToken; cfSaved = true
        }, filled = true)
    }

    // Manual TURN/STUN
    Panel("TURN / STUN (manual)") {
        Text(
            "Use your own TURN/STUN server instead of Cloudflare. Leave blank if Cloudflare TURN is configured above. Changes take effect after restart.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var showTurnCred by remember { mutableStateOf(false) }
        var turnSaved by remember { mutableStateOf(false) }
        LaunchedEffect(turnSaved) { if (turnSaved) { delay(2000); turnSaved = false } }
        OutlinedTextField(
            stunServer, { stunServer = it; turnSaved = false },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("STUN server URL", fontSize = 12.sp) },
            placeholder = { Text("stun:stun.example.com:3478", fontSize = 13.sp) },
            textStyle = TextStyle(fontSize = 13.sp),
        )
        OutlinedTextField(
            turnServer, { turnServer = it; turnSaved = false },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("TURN server URL", fontSize = 12.sp) },
            placeholder = { Text("turn:turn.example.com:3478", fontSize = 13.sp) },
            textStyle = TextStyle(fontSize = 13.sp),
        )
        OutlinedTextField(
            turnUser, { turnUser = it; turnSaved = false },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("TURN username", fontSize = 12.sp) },
            textStyle = TextStyle(fontSize = 13.sp),
        )
        OutlinedTextField(
            turnCred, { turnCred = it; turnSaved = false },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("TURN credential", fontSize = 12.sp) },
            textStyle = TextStyle(fontSize = 13.sp),
            visualTransformation = if (showTurnCred) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton({ showTurnCred = !showTurnCred }) { Text(if (showTurnCred) "Hide" else "Show", fontSize = 12.sp) } },
        )
        SmallButton(if (turnSaved) "Saved ✓" else "Save", {
            prefs.stunServer = stunServer; prefs.turnServer = turnServer
            prefs.turnUsername = turnUser; prefs.turnCredential = turnCred; turnSaved = true
        }, filled = true)
    }

    // Import / Export / Presets
    Panel("Import & Export") {
        Text(
            "Export the current TURN/STUN config to a JSON file, or import from a preset file (ice-servers-preset.json). A preset file with multiple options shows a picker below.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Import", { importLauncher.launch(arrayOf("application/json", "*/*")) })
            SmallButton("Export", { exportLauncher.launch("meshcall-ice-config.json") }, filled = true)
        }
        if (importMsg.isNotEmpty()) Text(importMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (presets.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Select a preset to apply:", style = MaterialTheme.typography.labelMedium)
            presets.forEach { (name, obj) ->
                OutlinedButton(
                    onClick = {
                        applyIceJson(obj, prefs,
                            { stunServer = it }, { turnServer = it }, { turnUser = it },
                            { turnCred = it }, { cfKeyId = it }, { cfToken = it })
                        importMsg = "Applied: $name"
                        presets = emptyList()
                    },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { Text(name, fontSize = 13.sp, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

// ── Family settings ───────────────────────────────────────────────────────────

@Composable
private fun FamilySettings(prefs: Prefs) {
    Panel("Security") {
        Text(
            "The root PIN protects the family database. Set it once; changing it requires re-seeding the database. Changes take effect after the server restarts.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var rootPin by remember { mutableStateOf(prefs.rootPin) }
        var showPin by remember { mutableStateOf(false) }
        var pinSaved by remember { mutableStateOf(false) }
        LaunchedEffect(pinSaved) { if (pinSaved) { delay(2000); pinSaved = false } }
        OutlinedTextField(
            rootPin, { rootPin = it; pinSaved = false },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Root PIN", fontSize = 12.sp) },
            textStyle = TextStyle(fontSize = 13.sp),
            visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton({ showPin = !showPin }) { Text(if (showPin) "Hide" else "Show", fontSize = 12.sp) } },
        )
        SmallButton(if (pinSaved) "Saved ✓" else "Save", { prefs.rootPin = rootPin; pinSaved = true }, filled = true)
    }
}

// ── Server settings ───────────────────────────────────────────────────────────

@Composable
private fun ServerSettings(prefs: Prefs, tokenProbe: Probe) {
    val scope = rememberCoroutineScope()

    Panel("GitHub access") {
        Text(
            "A fine-grained token lets the app save each new tunnel URL to ${Config.POINTER_OWNER}/${Config.POINTER_REPO}.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var token by remember { mutableStateOf(prefs.githubToken) }
        var show by remember { mutableStateOf(false) }
        var saved by remember { mutableStateOf(false) }
        LaunchedEffect(saved) { if (saved) { delay(2000); saved = false } }
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
        val ctx = LocalContext.current
        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
        var adminToken by remember { mutableStateOf(prefs.adminToken) }
        var showAdmin by remember { mutableStateOf(false) }
        var copiedAdmin by remember { mutableStateOf(false) }
        LaunchedEffect(copiedAdmin) { if (copiedAdmin) { delay(2000); copiedAdmin = false } }
        Text(
            "The VPS Monitor app signs in with this admin token to see this phone, browse and delete its files, and control the server. Anyone who has it has full control, so keep it private.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(if (showAdmin) adminToken else adminToken.take(8) + "..." + adminToken.takeLast(4),
            style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(if (showAdmin) "Hide" else "Show", { showAdmin = !showAdmin })
            SmallButton(if (copiedAdmin) "Copied" else "Copy", { clip.setText(androidx.compose.ui.text.AnnotatedString(adminToken)); copiedAdmin = true }, filled = true)
            SmallButton("New token", { adminToken = "adm-" + ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }; prefs.adminToken = adminToken })
        }
        Text("A new token signs every monitor out until you enter it again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Panel("Repositories") {
        StatusRow("Server code", "${Config.EXPRESS_OWNER}/${Config.EXPRESS_REPO}")
        StatusRow("URL pointer", "${Config.POINTER_OWNER}/${Config.POINTER_REPO}")
        StatusRow("Pointer site", Config.POINTER_PAGES_URL.removePrefix("https://"), mono = true)
        StatusRow("Local folder", RepoManager(LocalContext.current).repoDir.path, mono = true)
    }
}

// ── Device settings ───────────────────────────────────────────────────────────

@Composable
private fun DeviceSettings(prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

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
        StatusRow("30% or below", "Beep every 30 seconds")
        StatusRow("15% or below", "Beep every 15 seconds")
        StatusRow("Below 10%", "Beep every 3 seconds")
        StatusRow(
            "Battery now",
            if (bs.level < 0) "Unknown" else "${bs.level}%" + if (bs.charging) ", charging" else "",
            when { !enabled -> MaterialTheme.colorScheme.outline; bs.beepEverySec > 0 -> Bad; else -> Good },
        )
        SmallButton(if (testing) "Beeping..." else "Test beep",
            { testing = true; scope.launch { runCatching { Beeper(ctx).beep() }; testing = false } }, enabled = !testing)
    }

    Panel("Permissions") {
        Perm.values().forEach { p ->
            val ok = Permissions.has(ctx, p)
            StatusRow(p.title, if (ok) "Granted" else "Missing", if (ok) Good else Bad)
        }
    }
}

// ── About ─────────────────────────────────────────────────────────────────────

@Composable
private fun AboutSettings(ctx: android.content.Context) {
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    Panel("About") {
        StatusRow("Version", version)
        StatusRow("Runtime", "Node.js 18 (embedded) + cloudflared")
        Text(
            "The server restarts automatically after reboots, crashes and connection loss. Android's Force stop is the one thing no app can survive; open the app again after using it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
