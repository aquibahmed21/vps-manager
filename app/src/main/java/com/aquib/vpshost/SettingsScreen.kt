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

// ── ICE data types ────────────────────────────────────────────────────────────

private data class TurnEntry(val urls: String, val username: String = "", val credential: String = "")

// ── ICE config JSON helpers ───────────────────────────────────────────────────

private fun parseStunJson(json: String): MutableList<String> {
    if (json.isBlank()) return mutableListOf()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.toMutableList()
    } catch (_: Exception) { mutableListOf() }
}

private fun parseTurnJson(json: String): MutableList<TurnEntry> {
    if (json.isBlank()) return mutableListOf()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { obj ->
                val urls = obj.optString("urls").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TurnEntry(urls, obj.optString("username"), obj.optString("credential"))
            }
        }.toMutableList()
    } catch (_: Exception) { mutableListOf() }
}

private fun serializeStunJson(list: List<String>): String =
    JSONArray().also { a -> list.forEach { a.put(it) } }.toString()

private fun serializeTurnJson(list: List<TurnEntry>): String =
    JSONArray().also { a ->
        list.forEach { e ->
            a.put(JSONObject().apply {
                put("urls", e.urls)
                if (e.username.isNotBlank()) put("username", e.username)
                if (e.credential.isNotBlank()) put("credential", e.credential)
            })
        }
    }.toString()

private fun iceConfigToJson(prefs: Prefs): String = JSONObject().apply {
    put("stunServers", JSONArray(prefs.stunServersJson.ifBlank { "[]" }))
    put("turnServers", JSONArray(prefs.turnServersJson.ifBlank { "[]" }))
    if (prefs.cfTurnKeyId.isNotBlank()) put("cfTurnKeyId", prefs.cfTurnKeyId)
    if (prefs.cfTurnApiToken.isNotBlank()) put("cfTurnApiToken", prefs.cfTurnApiToken)
}.toString(2)

/** Applies a preset or flat config object — handles both new (arrays) and old (single-field) formats. */
private fun applyIcePreset(
    obj: JSONObject, prefs: Prefs,
    setStunList: (List<String>) -> Unit,
    setTurnList: (List<TurnEntry>) -> Unit,
    setCfKey: (String) -> Unit,
    setCfToken: (String) -> Unit,
) {
    when {
        obj.has("stunServers") -> {
            val list = parseStunJson(obj.getJSONArray("stunServers").toString())
            setStunList(list); prefs.stunServersJson = serializeStunJson(list)
        }
        obj.optString("stunServer").isNotBlank() -> {
            val list = listOf(obj.getString("stunServer"))
            setStunList(list); prefs.stunServersJson = serializeStunJson(list)
        }
    }
    when {
        obj.has("turnServers") -> {
            val list = parseTurnJson(obj.getJSONArray("turnServers").toString())
            setTurnList(list); prefs.turnServersJson = serializeTurnJson(list)
        }
        obj.optString("turnServer").isNotBlank() -> {
            val entry = TurnEntry(obj.getString("turnServer"), obj.optString("turnUsername"), obj.optString("turnCredential"))
            val list = listOf(entry)
            setTurnList(list); prefs.turnServersJson = serializeTurnJson(list)
        }
    }
    obj.optString("cfTurnKeyId").takeIf { it.isNotBlank() }?.also { setCfKey(it); prefs.cfTurnKeyId = it }
    obj.optString("cfTurnApiToken").takeIf { it.isNotBlank() }?.also { setCfToken(it); prefs.cfTurnApiToken = it }
}

// ── VoIP settings ─────────────────────────────────────────────────────────────

@Composable
private fun VoipSettings(prefs: Prefs, tokenProbe: Probe) {
    val ctx = LocalContext.current

    var cfKeyId by remember { mutableStateOf(prefs.cfTurnKeyId) }
    var cfToken by remember { mutableStateOf(prefs.cfTurnApiToken) }

    val stunList = remember { androidx.compose.runtime.mutableStateListOf<String>().also { it.addAll(parseStunJson(prefs.stunServersJson)) } }
    val turnList = remember { androidx.compose.runtime.mutableStateListOf<TurnEntry>().also { it.addAll(parseTurnJson(prefs.turnServersJson)) } }

    var presets   by remember { mutableStateOf<List<Pair<String, JSONObject>>>(emptyList()) }
    var importMsg by remember { mutableStateOf("") }
    LaunchedEffect(importMsg) { if (importMsg.isNotEmpty()) { delay(4000); importMsg = "" } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(iceConfigToJson(prefs).toByteArray()) }
            importMsg = "Exported"
        } catch (e: Exception) { importMsg = "Export failed: ${e.message}" }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: ""
            val root = JSONObject(text)
            if (root.has("presets")) {
                val arr = root.getJSONArray("presets")
                val list = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i); o.optString("name", "Preset ${i + 1}") to o
                }
                presets = list
                importMsg = "${list.size} presets loaded — pick one below"
            } else {
                applyIcePreset(root, prefs,
                    { stunList.clear(); stunList.addAll(it) },
                    { turnList.clear(); turnList.addAll(it) },
                    { cfKeyId = it }, { cfToken = it })
                importMsg = "Imported"
            }
        } catch (e: Exception) { importMsg = "Import failed: ${e.message}" }
    }

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

    // STUN servers list
    Panel("STUN servers") {
        Text(
            "STUN discovers your public IP/port. Add multiple for failover. First entry is tried first. If Cloudflare TURN is configured above these are ignored.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var addingStun by remember { mutableStateOf(false) }
        var newStunUrl by remember { mutableStateOf("") }
        var stunSaved  by remember { mutableStateOf(false) }
        LaunchedEffect(stunSaved) { if (stunSaved) { delay(2000); stunSaved = false } }

        stunList.forEachIndexed { idx, url ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(url, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                IconButton(onClick = { if (idx > 0) { stunList.add(idx - 1, stunList.removeAt(idx)) } },
                    modifier = Modifier.size(32.dp), enabled = idx > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, "Move up", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { if (idx < stunList.lastIndex) { stunList.add(idx + 1, stunList.removeAt(idx)) } },
                    modifier = Modifier.size(32.dp), enabled = idx < stunList.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Move down", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { stunList.removeAt(idx) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Delete, "Remove", modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (addingStun) {
            OutlinedTextField(
                newStunUrl, { newStunUrl = it },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("STUN URL", fontSize = 12.sp) },
                placeholder = { Text("stun:stun.example.com:3478", fontSize = 13.sp) },
                textStyle = TextStyle(fontSize = 13.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Add", {
                    if (newStunUrl.isNotBlank()) { stunList.add(newStunUrl.trim()); newStunUrl = ""; addingStun = false }
                }, filled = true, enabled = newStunUrl.isNotBlank())
                SmallButton("Cancel", { addingStun = false; newStunUrl = "" })
            }
        } else {
            SmallButton("+ Add STUN", { addingStun = true })
        }
        SmallButton(if (stunSaved) "Saved ✓" else "Save list", {
            prefs.stunServersJson = serializeStunJson(stunList); stunSaved = true
        }, filled = true, enabled = !stunSaved)
    }

    // TURN servers list
    Panel("TURN servers") {
        Text(
            "TURN relays media when direct P2P fails (symmetric NAT, strict firewalls). Add multiple for failover. First entry is tried first.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var addingTurn  by remember { mutableStateOf(false) }
        var newTurnUrls by remember { mutableStateOf("") }
        var newTurnUser by remember { mutableStateOf("") }
        var newTurnCred by remember { mutableStateOf("") }
        var showNewCred by remember { mutableStateOf(false) }
        var expandedIdx by remember { mutableStateOf(-1) }
        var turnSaved   by remember { mutableStateOf(false) }
        LaunchedEffect(turnSaved) { if (turnSaved) { delay(2000); turnSaved = false } }

        turnList.forEachIndexed { idx, entry ->
            val expanded = expandedIdx == idx
            Card(
                Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f).clickable { expandedIdx = if (expanded) -1 else idx }) {
                            Text(entry.urls, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if (entry.username.isNotBlank())
                                Text(entry.username, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        IconButton(onClick = { if (idx > 0) { turnList.add(idx - 1, turnList.removeAt(idx)); if (expandedIdx == idx) expandedIdx = idx - 1 } },
                            modifier = Modifier.size(32.dp), enabled = idx > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Move up", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { if (idx < turnList.lastIndex) { turnList.add(idx + 1, turnList.removeAt(idx)); if (expandedIdx == idx) expandedIdx = idx + 1 } },
                            modifier = Modifier.size(32.dp), enabled = idx < turnList.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Move down", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { turnList.removeAt(idx); if (expandedIdx == idx) expandedIdx = -1 },
                            modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Delete, "Remove", modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (expanded) {
                        var editUrls by remember { mutableStateOf(entry.urls) }
                        var editUser by remember { mutableStateOf(entry.username) }
                        var editCred by remember { mutableStateOf(entry.credential) }
                        var showEditCred by remember { mutableStateOf(false) }
                        OutlinedTextField(editUrls, { editUrls = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text("TURN URL", fontSize = 12.sp) }, textStyle = TextStyle(fontSize = 13.sp))
                        OutlinedTextField(editUser, { editUser = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Username", fontSize = 12.sp) }, textStyle = TextStyle(fontSize = 13.sp))
                        OutlinedTextField(editCred, { editCred = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Credential", fontSize = 12.sp) }, textStyle = TextStyle(fontSize = 13.sp),
                            visualTransformation = if (showEditCred) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { TextButton({ showEditCred = !showEditCred }) { Text(if (showEditCred) "Hide" else "Show", fontSize = 12.sp) } })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallButton("Update", {
                                if (editUrls.isNotBlank()) {
                                    turnList[idx] = TurnEntry(editUrls.trim(), editUser.trim(), editCred)
                                    expandedIdx = -1
                                }
                            }, filled = true, enabled = editUrls.isNotBlank())
                            SmallButton("Cancel", { expandedIdx = -1 })
                        }
                    }
                }
            }
        }
        if (addingTurn) {
            OutlinedTextField(newTurnUrls, { newTurnUrls = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("TURN URL", fontSize = 12.sp) },
                placeholder = { Text("turn:turn.example.com:3478", fontSize = 13.sp) },
                textStyle = TextStyle(fontSize = 13.sp))
            OutlinedTextField(newTurnUser, { newTurnUser = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Username", fontSize = 12.sp) }, textStyle = TextStyle(fontSize = 13.sp))
            OutlinedTextField(newTurnCred, { newTurnCred = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Credential", fontSize = 12.sp) }, textStyle = TextStyle(fontSize = 13.sp),
                visualTransformation = if (showNewCred) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton({ showNewCred = !showNewCred }) { Text(if (showNewCred) "Hide" else "Show", fontSize = 12.sp) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Add", {
                    if (newTurnUrls.isNotBlank()) {
                        turnList.add(TurnEntry(newTurnUrls.trim(), newTurnUser.trim(), newTurnCred))
                        newTurnUrls = ""; newTurnUser = ""; newTurnCred = ""; addingTurn = false
                    }
                }, filled = true, enabled = newTurnUrls.isNotBlank())
                SmallButton("Cancel", { addingTurn = false; newTurnUrls = ""; newTurnUser = ""; newTurnCred = "" })
            }
        } else {
            SmallButton("+ Add TURN", { addingTurn = true })
        }
        SmallButton(if (turnSaved) "Saved ✓" else "Save list", {
            prefs.turnServersJson = serializeTurnJson(turnList); turnSaved = true
        }, filled = true, enabled = !turnSaved)
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
                        applyIcePreset(obj, prefs,
                            { stunList.clear(); stunList.addAll(it) },
                            { turnList.clear(); turnList.addAll(it) },
                            { cfKeyId = it }, { cfToken = it })
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
