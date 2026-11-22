package com.aquib.vpshost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

enum class Lvl { INFO, WARN, ERROR }
data class LogLine(val time: String, val level: Lvl, val msg: String)

data class LlmState(
    val running: Boolean = false,
    val ready: Boolean = false,
    val model: String = "",
    val busy: String = "",
    /** Model download: file name, bytes done, bytes total (-1 unknown). Empty name = no download. */
    val dlName: String = "",
    val dlDone: Long = 0,
    val dlTotal: Long = -1,
    val dlError: String = "",
    val error: String = "",
)

data class HostState(
    val llm: LlmState = LlmState(),
    val running: Boolean = false,
    val nodeUp: Boolean = false,
    val tunnelUrl: String? = null,
    val publishedUrl: String? = null,
    val online: Boolean = true,
    val repoSha: String = "",
    val repoDate: String = "",
    /** When the website server (node) last started, epoch ms. */
    val serverStartedAt: Long = 0,
    val busy: String = "",
    val logs: List<LogLine> = emptyList(),
)

/**
 * The supervisor. Loop: make sure repo exists -> make sure node is up -> start tunnel ->
 * publish new URL to GitHub -> watch everything; if anything dies, go round again.
 */
class HostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private lateinit var prefs: Prefs
    private lateinit var repo: RepoManager
    private lateinit var tunnel: TunnelManager
    private lateinit var github: GitHubPublisher
    private lateinit var llm: LlamaManager
    private var llmJob: Job? = null
    private var dlJob: Job? = null
    private var management: ManagementServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this); repo = RepoManager(this); github = GitHubPublisher { prefs.githubToken }
        tunnel = TunnelManager(this) { m, l -> log(m, l) }
        llm = LlamaManager(this) { m, l -> log(m, l) }
        startForeground(1, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vpshost:host").apply { acquire() }
        // keep the Wi-Fi radio awake with the screen off (otherwise the phone drops Wi-Fi while locked)
        wifiLock = runCatching { (applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
            .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vpshost:wifi").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        watchNetwork()
        management = ManagementServer(this, prefs).also { it.start() }
        state.update { it.copy(repoSha = prefs.repoSha, repoDate = prefs.repoDate, serverStartedAt = System.currentTimeMillis()) }
        Watchdog.scheduleDailyUpdate(this)
        Telemetry.init(this); Telemetry.start(this, scope)
        Telemetry.event("restart", "The VPS Host app started (after an app update, a crash or a phone restart).")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return if (prefs.llmWant) START_STICKY else START_NOT_STICKY }
            ACTION_UPDATE -> scope.launch { doUpdate(intent.getBooleanExtra("scheduled", false)) }
            ACTION_LLM_START -> { prefs.llmWant = true; startLlm(); return START_STICKY }
            ACTION_LLM_STOP -> { stopLlm(); if (!prefs.wantRunning) { stopSelf(); return START_NOT_STICKY }; return START_STICKY }
            ACTION_DL_START -> { startDownload(intent.getStringExtra("url") ?: ""); return START_STICKY }
            ACTION_DL_CANCEL -> { dlJob?.cancel(); return START_STICKY }
        }
        if (!Permissions.allGranted(this)) { log("Permissions missing; not starting"); stopSelf(); return START_NOT_STICKY }
        // Plain start/restart: resume whatever the user last asked for (server and/or LLM).
        if (!prefs.wantRunning && !prefs.llmWant) { stopSelf(); return START_NOT_STICKY }
        if (prefs.wantRunning) {
            if (loop?.isActive != true) loop = scope.launch { supervise() }
            state.update { it.copy(running = true) }
        }
        if (prefs.llmWant && llmJob?.isActive != true) startLlm()
        return START_STICKY
    }

    // ---- LLM ----
    private fun llmUpdate(f: (LlmState) -> LlmState) = state.update { it.copy(llm = f(it.llm)) }

    private fun startLlm() {
        val model = java.io.File(LlamaManager.modelsDir, prefs.llmModel)
        if (prefs.llmModel.isEmpty() || !model.exists()) { log("llm: no model selected", Lvl.WARN); prefs.llmWant = false; return }
        // Refuse a model the phone cannot hold: Android would kill the process (or the whole app) when memory runs out.
        val mi = android.app.ActivityManager.MemoryInfo().also { getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
        // Weights are memory-mapped (file-backed, evictable), so count half; the KV cache and compute buffers are real memory.
        val need = (model.length() + (LlamaManager.visionFor(model)?.length() ?: 0L)) / 2 + prefs.llmCtx.toLong() * 128 * 1024 + 300L * 1024 * 1024
        if (need > mi.availMem * 0.8 || model.length() > mi.availMem * 1.1) {
            val msg = "Not enough free memory: ${LlamaManager.sizeText(need)} needed, ${LlamaManager.sizeText(mi.availMem)} free. Pick a smaller model or lower the context."
            log("llm: $msg", Lvl.ERROR); prefs.llmWant = false; llmUpdate { it.copy(error = msg, running = false) }; return
        }
        llmUpdate { it.copy(error = "") }
        llmJob?.cancel()
        llmJob = scope.launch {
            llm.writeConf(prefs.llmKey, false)
            var backoff = 3_000L
            while (isActive) {
                val t0 = System.currentTimeMillis()
                llmUpdate { it.copy(running = true, ready = false, model = prefs.llmModel, busy = "Loading model") }
                log("llm: starting ${prefs.llmModel} (ctx ${prefs.llmCtx}, ${prefs.llmThreads} threads)")
                val vision = LlamaManager.visionFor(model)
                if (vision != null) log("llm: vision enabled (${vision.name})")
                llm.start(model, prefs.llmCtx, prefs.llmThreads, vision)
                var ready = false
                repeat(300) { if (!ready && llm.alive) { ready = llmHealthy(); if (!ready) delay(1000) } } // large models load slowly
                if (ready) { llm.writeConf(prefs.llmKey, true); llmUpdate { it.copy(ready = true, busy = "") }; log("llm: ready on :${Config.LLM_PORT}"); Telemetry.event("llm", "The AI model (${prefs.llmModel}) is loaded and ready to answer.") }
                while (isActive && llm.alive) delay(2000)
                llm.writeConf(prefs.llmKey, false)
                if (!isActive) break
                llmUpdate { it.copy(ready = false, busy = "Restarting") }
                log("llm: exited; restarting in ${backoff / 1000}s", Lvl.WARN); Telemetry.event("problem", "The AI model stopped unexpectedly. It will be started again automatically.")
                delay(backoff)
                backoff = if (System.currentTimeMillis() - t0 > 60_000) 3_000 else minOf(backoff * 2, 60_000)
            }
        }
    }

    private fun stopLlm() {
        prefs.llmWant = false
        llmJob?.cancel(); llmJob = null
        llm.stop(); llm.writeConf(prefs.llmKey, false)
        llmUpdate { it.copy(running = false, ready = false, busy = "") }
        log("llm: stopped"); Telemetry.event("llm", "The AI model was stopped.")
    }

    private fun llmHealthy() = runCatching {
        val c = URL("http://127.0.0.1:${Config.LLM_PORT}/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1000; c.readTimeout = 2000; c.responseCode == 200
    }.getOrDefault(false)

    private fun startDownload(url: String) {
        if (dlJob?.isActive == true) return
        dlJob = scope.launch(Dispatchers.IO) {
            val name = url.substringBefore('?').substringAfterLast('/')
            llmUpdate { it.copy(dlName = name, dlDone = 0, dlTotal = -1, dlError = "") }
            log("llm: downloading $name")
            try {
                val f = LlamaManager.download(url, { d, t -> llmUpdate { s -> s.copy(dlDone = d, dlTotal = t) } }, { !isActive })
                log("llm: downloaded ${f.name} (${LlamaManager.sizeText(f.length())})"); Telemetry.event("llm", "Finished downloading the model ${f.name} (${LlamaManager.sizeText(f.length())}).")
                llmUpdate { it.copy(dlName = "") }
            } catch (e: CancellationException) {
                log("llm: download cancelled (partial file kept, will resume)", Lvl.WARN); llmUpdate { it.copy(dlName = "") }
            } catch (e: Exception) {
                log("llm: download failed: ${e.message}", Lvl.ERROR); llmUpdate { it.copy(dlName = "", dlError = e.message ?: "failed") }
            }
        }
    }

    private suspend fun supervise() {
        while (currentCoroutineContext().isActive) {
            try {
                awaitNetwork()
                if (prefs.repoSha.isNotEmpty() && prefs.repoDate.isEmpty()) repo.dateOf(prefs.repoSha)?.let { prefs.repoDate = it; state.update { s -> s.copy(repoDate = it) } }
                if (!repo.repoPresent()) { status("Downloading server"); val v = repo.download({ log(it) }); prefs.repoSha = v.sha; prefs.repoDate = v.date; state.update { it.copy(repoSha = v.sha, repoDate = v.date) } }
                repo.prepareRuntime({ log(it) })
                ensureNode()
                runTunnelCycle()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log("Error: ${e.message}", Lvl.ERROR); status("Error, retrying"); delay(10_000) }
        }
    }

    private suspend fun ensureNode() {
        if (portOpen()) { state.update { it.copy(nodeUp = true) }; return }
        status("Starting server")
        repeat(2) { attempt ->
            NodeService.start(this)
            repeat(45) { if (portOpen()) { log("Node server is up on :${Config.SERVER_PORT}"); Telemetry.event("info", "The website server started and is answering."); state.update { it.copy(nodeUp = true, serverStartedAt = System.currentTimeMillis()) }; return }; delay(1000) }
            log("Node did not come up (attempt ${attempt + 1}); restarting its process")
            NodeService.stop(this); delay(2000)
        }
        throw IllegalStateException("Node server failed to start, see ${repo.logFile.path}")
    }

    private suspend fun runTunnelCycle() {
        status("Starting tunnel")
        val got = CompletableDeferred<String>()
        tunnel.start { got.complete(it) }
        val url = withTimeoutOrNull(90_000) { got.await() }
        if (url == null) { log("No tunnel URL after 90s; retrying"); tunnel.stop(); delay(5000); return }
        log("Tunnel URL: $url"); Telemetry.event("tunnel", "A new public address was created (${url.removePrefix("https://").substringBefore(".")}).")
        state.update { it.copy(tunnelUrl = url) }
        publishWithRetry(url)
        status("Running")

        var failures = 0; var tick = 0
        while (currentCoroutineContext().isActive) {
            delay(5000); tick++
            if (!tunnel.alive) { log("cloudflared exited"); Telemetry.event("problem", "The internet tunnel stopped unexpectedly. A new one is being created."); break }
            if (nodeRestartRequested) { nodeRestartRequested = false; Telemetry.event("restart", "The website server was restarted on request."); NodeService.stop(this); delay(1500); ensureNode() }
            if (tunnelRestartRequested) { tunnelRestartRequested = false; log("Restarting the tunnel for a new public URL"); Telemetry.event("tunnel", "The internet tunnel is being restarted to get a new public address."); break }
            if (!portOpen()) { log("Node down; restarting"); Telemetry.event("restart", "The website server stopped answering, so it was restarted automatically."); state.update { it.copy(nodeUp = false) }; ensureNode() }
            if (tick % 6 == 0 && state.value.online) { // every ~30s
                if (tunnelReachable(url)) failures = 0 else if (++failures >= 3) { log("Tunnel unreachable 3x; restarting it"); Telemetry.event("problem", "The public address stopped answering. A new tunnel is being created."); break }
            }
        }
        tunnel.stop()
        state.update { it.copy(tunnelUrl = null) }
    }

    private suspend fun publishWithRetry(url: String) {
        var wait = 5_000L
        while (currentCoroutineContext().isActive && tunnel.alive) {
            try {
                status("Publishing URL")
                val r = github.publish(url)
                prefs.lastPublishedUrl = url
                state.update { it.copy(publishedUrl = url) }
                log("GitHub: $r"); Telemetry.event("info", "The new address was saved to GitHub, so your apps can find the server."); return
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log("Publish failed: ${e.message}", Lvl.ERROR); delay(wait); wait = minOf(wait * 2, 30_000) }
        }
    }

    private suspend fun doUpdate(scheduled: Boolean = false) {
        status("Updating server code")
        try {
            val before = prefs.repoSha
            // Scheduled (2 am) check: don't download or restart if GitHub is still on the same commit.
            if (scheduled && before.isNotEmpty() && repo.repoPresent()) {
                val remote = repo.remoteVersion()
                if (remote == null || remote.sha == before) {
                    if (remote != null && prefs.repoDate.isEmpty()) { prefs.repoDate = remote.date; state.update { it.copy(repoDate = remote.date) } }
                    Telemetry.event("info", "Daily 2 am check: the server code is already the latest version."); status("Running"); return
                }
            }
            val v = repo.download({ log(it) })
            Telemetry.event(if (v.sha == before) "info" else "update", if (v.sha == before) "Checked for updates: the server code is already the latest version." else "The server code was updated to version ${v.sha.take(7)}.")
            prefs.repoSha = v.sha; prefs.repoDate = v.date; state.update { it.copy(repoSha = v.sha, repoDate = v.date) }
            if (v.sha != before) nodeRestartRequested = true
        } catch (e: Exception) { log("Update failed: ${e.message}", Lvl.ERROR) }
        status("Running")
    }

    private fun portOpen() = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", Config.SERVER_PORT), 500); true } }.getOrDefault(false)

    private fun tunnelReachable(url: String) = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000; c.responseCode in 200..499
    }.getOrDefault(false)

    // ---- network ----
    @Volatile private var online = true
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { if (!online) Telemetry.event("network", "The internet connection is back."); online = true; state.update { it.copy(online = true) }; log("Network available") }
                override fun onLost(n: Network) {
                    online = cm.activeNetwork != null; state.update { it.copy(online = online) }; log("Network lost", Lvl.WARN); if (!online) Telemetry.event("problem", "The internet connection was lost. The server will reconnect by itself.")
                }
            })
    }
    private suspend fun awaitNetwork() { while (!online) { status("Waiting for internet"); delay(2000) } }

    // ---- lifecycle ----
    private fun shutdown() {
        prefs.wantRunning = false
        loop?.cancel(); tunnel.stop(); NodeService.stop(this)
        state.update { HostState(repoSha = prefs.repoSha, logs = it.logs, llm = it.llm) }
        if (!prefs.llmWant) stopSelf() // the LLM keeps the service alive until it is stopped too
    }

    override fun onTaskRemoved(rootIntent: Intent?) { if (prefs.wantRunning || prefs.llmWant) Watchdog.schedule(this, 1000) }

    override fun onDestroy() {
        tunnel.stop(); llm.stop(); management?.stop()
        if (prefs.wantRunning || prefs.llmWant) Watchdog.schedule(this, 1000) // killed by the system: come back
        runCatching { wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        scope.cancel()
        super.onDestroy()
    }

    // ---- ui helpers ----
    private fun status(s: String) {
        state.update { it.copy(busy = s) }
        getSystemService(NotificationManager::class.java).notify(1, notification(s))
    }
    private fun log(m: String, lvl: Lvl = Lvl.INFO) = addLog(m, lvl)

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("host", "Server status", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "host").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("VPS Host").setContentText(text).setOngoing(true).setContentIntent(open).build()
    }

    companion object {
        @Volatile var nodeRestartRequested = false
        /** Set to drop the current tunnel and start a new one (new public URL, republished to GitHub). */
        @Volatile var tunnelRestartRequested = false
        const val ACTION_STOP = "stop"
        const val ACTION_UPDATE = "update"
        const val ACTION_LLM_START = "llm_start"
        const val ACTION_LLM_STOP = "llm_stop"
        const val ACTION_DL_START = "dl_start"
        const val ACTION_DL_CANCEL = "dl_cancel"
        val state = MutableStateFlow(HostState())
        /** Adds a line to the in-app log (usable from other services). */
        fun addLog(m: String, lvl: Lvl = Lvl.INFO) {
            Log.i("VpsHost", m)
            val line = LogLine(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()), lvl, m)
            state.update { it.copy(logs = (it.logs + line).takeLast(400)) }
        }
        fun clearLogs() { state.update { it.copy(logs = emptyList()) } }

        /** User pressed Start server. */
        fun start(c: Context) { Prefs(c).wantRunning = true; resume(c) }
        /** Bring back whatever the user last asked for (also used by the watchdog and boot receiver). */
        fun resume(c: Context) { c.startForegroundService(Intent(c, HostService::class.java)) }
        fun llmStart(c: Context) = c.startForegroundService(Intent(c, HostService::class.java).setAction(ACTION_LLM_START))
        fun llmStop(c: Context) = c.startForegroundService(Intent(c, HostService::class.java).setAction(ACTION_LLM_STOP))
        fun download(c: Context, url: String) = c.startForegroundService(Intent(c, HostService::class.java).setAction(ACTION_DL_START).putExtra("url", url))
        fun cancelDownload(c: Context) = c.startForegroundService(Intent(c, HostService::class.java).setAction(ACTION_DL_CANCEL))
        fun stop(c: Context) { c.startService(Intent(c, HostService::class.java).setAction(ACTION_STOP)) }
        fun update(c: Context, scheduled: Boolean = false) { c.startForegroundService(Intent(c, HostService::class.java).setAction(ACTION_UPDATE).putExtra("scheduled", scheduled)) }
    }
}
