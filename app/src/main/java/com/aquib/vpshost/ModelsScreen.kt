package com.aquib.vpshost

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private class Suggested(val name: String, val size: String, val url: String)
private val SUGGESTED = listOf(
    Suggested("Gemma 4 E2B (Q4_K_M)", "3.1 GB", "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf"),
    Suggested("Gemma 4 E2B vision add-on (read images)", "0.6 GB", "https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/mmproj-gemma-4-E2B-it-Q8_0.gguf"),
    Suggested("Qwen 2.5 0.5B (Q4_K_M)", "0.5 GB", "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"),
)

@Composable
fun ModelsScreen(testProbe: Probe) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val st by HostService.state.collectAsState()
    val llm = st.llm
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var models by remember { mutableStateOf(LlamaManager.models()) }
    var selected by remember { mutableStateOf(prefs.llmModel) }
    var key by remember { mutableStateOf(prefs.llmKey) }
    var requireKey by remember { mutableStateOf(prefs.llmRequireKey) }
    var copied by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var ctxText by remember { mutableStateOf(prefs.llmCtx.toString()) }
    var thrText by remember { mutableStateOf(prefs.llmThreads.toString()) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(copied) { if (copied.isNotEmpty()) { delay(2000); copied = "" } }
    LaunchedEffect(llm.dlName) { models = withContext(Dispatchers.IO) { LlamaManager.models() } }
    LaunchedEffect(Unit) { while (true) { models = withContext(Dispatchers.IO) { LlamaManager.models() }; delay(4000) } }
    // Drop a stale selection (file removed outside the app).
    if (selected.isNotEmpty() && models.none { it.name == selected } && !llm.running) { selected = ""; prefs.llmModel = "" }

    val (title, subtitle, accent) = when {
        llm.ready -> Triple("Ready", "Serving ${llm.model}", Good)
        llm.running -> Triple(llm.busy.ifEmpty { "Starting" }, "Loading ${llm.model}. Large models take a while.", Warn)
        llm.error.isNotEmpty() -> Triple("Not started", llm.error, Bad)
        else -> Triple("Stopped", if (selected.isEmpty()) "Pick a model, then start." else "Start to load $selected.", Bad)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = accent, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(if (llm.ready) Icons.Filled.Check else if (llm.running) Icons.Filled.Refresh else Icons.Filled.PlayArrow, null, tint = Color.White)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("LLM: $title", style = MaterialTheme.typography.titleLarge)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!llm.running) Button({ prefs.llmModel = selected; prefs.llmCtx = ctxText.toIntOrNull()?.coerceIn(256, 131072) ?: 4096
                    prefs.llmThreads = thrText.toIntOrNull()?.coerceIn(1, 16) ?: 4; HostService.llmStart(ctx) },
                    Modifier.fillMaxWidth().height(40.dp), enabled = selected.isNotEmpty()) { Text("Start LLM", fontSize = 14.sp) }
                else OutlinedButton({ HostService.llmStop(ctx) }, Modifier.fillMaxWidth().height(40.dp)) { Text("Stop LLM", fontSize = 14.sp) }
            }
        }

        Panel("Models on this phone") {
            Text(LlamaManager.modelsDir.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (models.isEmpty()) Text("No .gguf files yet. Copy one into that folder or download one below.", style = MaterialTheme.typography.bodyMedium)
            models.forEach { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == f.name, { if (!llm.running) { selected = f.name; prefs.llmModel = f.name } }, enabled = !llm.running)
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text(LlamaManager.sizeText(f.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ confirmDelete = f }, enabled = !(llm.running && llm.model == f.name)) { Text("Delete", fontSize = 12.sp, color = Bad) }
                }
            }
            LlamaManager.visionFiles().forEach { v ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Vision add-on: ${v.name}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text(LlamaManager.sizeText(v.length()) + ", used automatically by its model so it can read images", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ confirmDelete = v }, enabled = !llm.running) { Text("Delete", fontSize = 12.sp, color = Bad) }
                }
            }
            if (llm.running) Text("Stop the LLM to change the model.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Panel("Download a model") {
            Text("Tap a suggestion to fill the link, or paste any direct .gguf link (Hugging Face: .../resolve/main/model.gguf), then press Download. Interrupted downloads resume.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Suggested", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SUGGESTED.forEach { s ->
                OutlinedButton({ url = s.url }, Modifier.fillMaxWidth().height(40.dp), enabled = llm.dlName.isEmpty(), contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text(s.name, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(s.size, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = llm.dlName.isEmpty(),
                textStyle = TextStyle(fontSize = 13.sp), placeholder = { Text("https://huggingface.co/.../model.gguf", fontSize = 13.sp) })
            if (llm.dlName.isNotEmpty()) {
                val frac = if (llm.dlTotal > 0) (llm.dlDone.toFloat() / llm.dlTotal).coerceIn(0f, 1f) else null
                Text("${llm.dlName}\n${LlamaManager.sizeText(llm.dlDone)}" + if (llm.dlTotal > 0) " of ${LlamaManager.sizeText(llm.dlTotal)}  (${(frac!! * 100).toInt()}%)" else "",
                    style = MaterialTheme.typography.bodySmall)
                if (frac != null) LinearProgressIndicator({ frac }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                SmallButton("Cancel", { HostService.cancelDownload(ctx) })
            } else {
                if (llm.dlError.isNotEmpty()) Text("Download failed: ${llm.dlError}", style = MaterialTheme.typography.bodySmall, color = Bad)
                SmallButton("Download", { HostService.download(ctx, url.trim()) }, filled = true, enabled = url.trim().startsWith("http"))
            }
        }

        Panel("Connect") {
            val base = st.tunnelUrl?.let { "$it/v1" }
            Text("Endpoint", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(base ?: "Start the server to get a public URL", style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (base != null) FontFamily.Monospace else null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Require API key", style = MaterialTheme.typography.bodyMedium)
                    Text(if (requireKey) "Clients must send Authorization: Bearer <key>." else "Open: anyone with the URL can use the LLM.",
                        style = MaterialTheme.typography.bodySmall, color = if (requireKey) MaterialTheme.colorScheme.onSurfaceVariant else Warn)
                }
                Switch(requireKey, { requireKey = it; prefs.llmRequireKey = it; LlamaManager(ctx) { _, _ -> }.writeConf(key, llm.ready, it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(if (copied == "ep") "Copied" else "Copy URL", { clipboard.setText(AnnotatedString(base!!)); copied = "ep" }, enabled = base != null)
                if (requireKey) {
                    SmallButton(if (copied == "key") "Copied" else "Copy key", { clipboard.setText(AnnotatedString(key)); copied = "key" })
                    SmallButton("New key", { key = Prefs.newKey(); prefs.llmKey = key; LlamaManager(ctx) { _, _ -> }.writeConf(key, llm.ready) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ProbeButton("Test prompt", "Asking...", testProbe, enabled = llm.ready, filled = true) {
                    scope.launch { testProbe.run { testPrompt(base ?: "http://127.0.0.1:${Config.LLM_PORT}/v1", key, base != null) } }
                }
            }
            ResultBanner("LLM test", testProbe)
            Text("Any OpenAI-compatible client works with the base URL above. One request runs at a time (up to 4 wait), replies are capped at 1024 tokens and 95 seconds" + if (requireKey) " plus the key." else ". The server is open for now; switch on the key before sharing the URL.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Panel("Options") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ctxText, { ctxText = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), singleLine = true, enabled = !llm.running,
                    label = { Text("Context tokens", fontSize = 11.sp) }, textStyle = TextStyle(fontSize = 13.sp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(thrText, { thrText = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), singleLine = true, enabled = !llm.running,
                    label = { Text("CPU threads", fontSize = 11.sp) }, textStyle = TextStyle(fontSize = 13.sp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Text("Bigger context uses more memory. 4 threads suits most phones. Applied the next time the LLM starts.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    confirmDelete?.let { f ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete model?") },
            text = { Text("${f.name} (${LlamaManager.sizeText(f.length())}) will be removed from this phone.") },
            confirmButton = { TextButton({
                f.delete(); File(f.path + ".part").delete()
                if (selected == f.name) { selected = ""; prefs.llmModel = "" }
                models = LlamaManager.models(); confirmDelete = null }) { Text("Delete", color = Bad) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } })
    }
}

/** One chat completion through the same path a client would use (the tunnel when it exists). */
private suspend fun testPrompt(base: String, key: String, viaTunnel: Boolean): Outcome = withContext(Dispatchers.IO) {
    val body = JSONObject().put("max_tokens", 48).put("messages", org.json.JSONArray().put(
        JSONObject().put("role", "user").put("content", "Reply with one short friendly sentence.")))
    val t0 = System.currentTimeMillis()
    val c = URL("$base/chat/completions").openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 120_000
    c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Authorization", "Bearer $key")
    c.outputStream.use { it.write(body.toString().toByteArray()) }
    val code = c.responseCode
    val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
    val ms = System.currentTimeMillis() - t0
    val where = if (viaTunnel) "via public tunnel" else "local only"
    if (code !in 200..299) return@withContext Outcome(Kind.FAIL, "HTTP $code", "$where\n${text.take(300)}")
    val j = JSONObject(text)
    val reply = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
    val tps = j.optJSONObject("timings")?.optDouble("predicted_per_second", 0.0) ?: 0.0
    Outcome(Kind.OK, "Working", "$where, $ms ms" + (if (tps > 0) ", %.1f tokens/s".format(tps) else "") + "\n\n$reply")
}
