package com.aquib.vpshost

import android.content.Context
import java.io.File

/** Runs cloudflared (shipped as libcloudflared.so so Android allows executing it) and extracts the quick-tunnel URL. */
class TunnelManager(private val ctx: Context, private val log: (String, Lvl) -> Unit) {
    private var proc: Process? = null
    @Volatile var url: String? = null; private set
    val alive get() = proc?.isAlive == true

    private val urlRegex = Regex("https://([a-z0-9-]+)\\.trycloudflare\\.com")

    fun start(onUrl: (String) -> Unit) {
        stop()
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libcloudflared.so")
        if (!bin.exists()) { log("cloudflared binary missing: ${bin.path}", Lvl.ERROR); return }
        val pb = ProcessBuilder(bin.path, "tunnel", "--no-autoupdate", "--protocol", "http2",
            "--url", "http://127.0.0.1:${Config.SERVER_PORT}").redirectErrorStream(true)
        pb.environment()["HOME"] = ctx.filesDir.path
        pb.environment()["TMPDIR"] = ctx.cacheDir.path
        pb.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts" // Go has no CA store on Android
        val p = pb.start(); proc = p; url = null
        Thread({
            // Stopping destroys the process, which makes this read throw; that is expected, not a crash.
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    logLine(line)
                    val m = urlRegex.find(line)
                    if (m != null && m.groupValues[1] != "api" && url == null) { url = m.value; onUrl(m.value) }
                }
            } catch (_: java.io.IOException) { }
        }, "cf-reader").start()
    }

    /** Condense cloudflared's output: drop its ASCII tables, keep level, strip the timestamp. */
    private fun logLine(line: String) {
        val m = Regex("^\\S+Z (INF|ERR|WRN) (.*)$").find(line)
        val msg = (m?.groupValues?.get(2) ?: line).trim()
        if (msg.startsWith("|") || msg.startsWith("+") || msg.isEmpty()) return
        // "local resolver" is cloudflared's optional private-DNS proxy; irrelevant here, so don't show it as an error.
        val lvl = when {
            msg.contains("DNS local resolver") -> Lvl.WARN
            msg.contains("Unable to reach the origin service") -> Lvl.WARN // a request arrived while Node was restarting
            m?.groupValues?.get(1) == "ERR" -> Lvl.ERROR
            m?.groupValues?.get(1) == "WRN" -> Lvl.WARN
            else -> Lvl.INFO
        }
        log(if (msg.contains("Unable to reach the origin service")) "tunnel: a request arrived while the Node server was restarting (harmless)" else "tunnel: ${msg.take(220)}", lvl)
    }

    fun stop() { proc?.destroyForcibly(); proc = null; url = null }
}
