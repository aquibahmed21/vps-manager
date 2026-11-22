package com.aquib.vpshost

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Remote-management API for the VPS Monitor app. Listens on loopback only; the Node gateway forwards /manage here
 * (so it travels through the same Cloudflare tunnel). EVERY request must carry "Authorization: Bearer <admin token>".
 */
class ManagementServer(private val ctx: Context, private val prefs: Prefs) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false

    // Brute-force guard: too many wrong tokens in a minute locks the API for a minute.
    private val failures = ArrayDeque<Long>()
    @Volatile private var lockedUntil = 0L

    fun start() {
        if (running) return
        running = true
        server = ServerSocket(Config.MANAGE_PORT, 32, InetAddress.getByName("127.0.0.1"))
        Thread({
            while (running) {
                val s = try { server?.accept() } catch (_: Exception) { null } ?: break
                pool.execute { runCatching { handle(s) }; runCatching { s.close() } }
            }
        }, "manage-accept").start()
    }

    fun stop() { running = false; runCatching { server?.close() }; pool.shutdownNow() }

    // ---------------- HTTP plumbing ----------------
    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray) {
        fun json(): JSONObject = if (body.isEmpty()) JSONObject() else JSONObject(String(body))
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read(); if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar()); if (sb.length > 16384) throw IllegalStateException("header too long")
        }
    }

    private fun parse(inp: InputStream): Req? {
        val first = readLine(inp) ?: return null
        val parts = first.split(" "); if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) { val l = readLine(inp) ?: break; if (l.isEmpty()) break; val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > 1_000_000) throw IllegalStateException("body too large")
        val body = ByteArray(len); var read = 0
        while (read < len) { val n = inp.read(body, read, len - read); if (n < 0) break; read += n }
        val rawPath = parts[1].substringBefore('?')
        val q = HashMap<String, String>()
        parts[1].substringAfter('?', "").split('&').filter { it.isNotEmpty() }.forEach { kv ->
            val k = kv.substringBefore('='); val v = kv.substringAfter('=', "")
            q[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return Req(parts[0].uppercase(), rawPath, q, headers, body)
    }

    private fun respond(out: OutputStream, code: Int, body: ByteArray, type: String = "application/json", extra: Map<String, String> = emptyMap()) {
        val head = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n")
        extra.forEach { (k, v) -> head.append("$k: $v\r\n") }
        out.write((head.toString() + "\r\n").toByteArray()); out.write(body); out.flush()
    }
    private fun json(out: OutputStream, code: Int, o: Any) = respond(out, code, o.toString().toByteArray())
    private fun err(out: OutputStream, code: Int, msg: String) = json(out, code, JSONObject().put("error", msg))
    private fun reason(c: Int) = when (c) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 415 -> "Unsupported Media Type"; 429 -> "Too Many Requests"; else -> "Error" }

    private fun authorized(r: Req): Boolean {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return false
        val given = r.headers["authorization"]?.removePrefix("Bearer ")?.trim().orEmpty()
        val ok = MessageDigest.isEqual(given.toByteArray(), prefs.adminToken.toByteArray())
        if (!ok) synchronized(failures) {
            failures.addLast(now); while (failures.isNotEmpty() && now - failures.first() > 60_000) failures.removeFirst()
            if (failures.size >= 10) { lockedUntil = now + 60_000; failures.clear(); HostService.addLog("manage: too many wrong tokens; locked for 60 s", Lvl.WARN); Telemetry.event("problem", "Someone entered the wrong admin token too many times. Remote access is locked for one minute.") }
        }
        return ok
    }

    private fun handle(s: Socket) {
        s.soTimeout = 30_000
        val inp = BufferedInputStream(s.getInputStream()); val out = s.getOutputStream()
        val r = try { parse(inp) } catch (e: Exception) { err(out, 400, e.message ?: "bad request"); return } ?: return
        if (System.currentTimeMillis() < lockedUntil) { err(out, 429, "Too many wrong attempts. Try again in a minute."); return }
        if (!authorized(r)) { err(out, if (System.currentTimeMillis() < lockedUntil) 429 else 401, "Invalid or missing admin token"); return }
        try { route(r, out) } catch (e: Exception) { err(out, 500, e.message ?: e.javaClass.simpleName) }
    }

    // ---------------- routes ----------------
    private fun route(r: Req, out: OutputStream) {
        val p = r.path.removePrefix("/manage").trimEnd('/')
        when {
            r.method == "GET" && p == "/status" -> json(out, 200, status())
            r.method == "GET" && p == "/metrics" -> json(out, 200, Telemetry.query((r.query["hours"]?.toIntOrNull() ?: 24).coerceIn(1, 72)).toJson())
            r.method == "GET" && p == "/logs" -> json(out, 200, logs(r.query["source"] ?: "app", (r.query["n"]?.toIntOrNull() ?: 200).coerceIn(1, 1000)))
            r.method == "POST" && p == "/server/start" -> { remote("started the server"); HostService.start(ctx); ok(out) }
            r.method == "POST" && p == "/server/stop" -> { remote("stopped the server"); HostService.stop(ctx); ok(out) }
            r.method == "POST" && p == "/server/update" -> { remote("asked the server to check for code updates"); HostService.update(ctx); ok(out) }
            r.method == "POST" && p == "/server/restart-node" -> { remote("restarted the website server"); HostService.nodeRestartRequested = true; ok(out) }
            // Full restart: Node again, then a brand-new tunnel (new URL, published to the pointer). The reply goes out first.
            r.method == "POST" && p == "/server/restart-all" -> { remote("asked for a full restart with a new public address"); HostService.nodeRestartRequested = true; HostService.tunnelRestartRequested = true; ok(out) }
            r.method == "GET" && p == "/llm/models" -> json(out, 200, llmModels())
            r.method == "POST" && p == "/llm/select" -> {
                val name = r.json().optString("model")
                if (LlamaManager.models().none { it.name == name }) err(out, 404, "No such model") else { prefs.llmModel = name; ok(out) }
            }
            r.method == "POST" && p == "/llm/start" -> {
                remote("started the AI model")
                val b = r.json(); if (b.has("ctx")) prefs.llmCtx = b.getInt("ctx").coerceIn(256, 131072); if (b.has("threads")) prefs.llmThreads = b.getInt("threads").coerceIn(1, 16)
                if (b.optString("model").isNotEmpty()) prefs.llmModel = b.getString("model")
                HostService.llmStart(ctx); ok(out)
            }
            r.method == "POST" && p == "/llm/stop" -> { remote("stopped the AI model"); HostService.llmStop(ctx); ok(out) }
            r.method == "POST" && p == "/llm/download" -> { val u = r.json().optString("url"); if (!u.startsWith("http")) err(out, 400, "url required") else { HostService.download(ctx, u); ok(out) } }
            r.method == "GET" && p == "/fs/list" -> fsList(r, out)
            r.method == "GET" && p == "/fs/read" -> fsRead(r, out)
            r.method == "GET" && p == "/fs/download" -> fsDownload(r, out)
            r.method == "POST" && p == "/fs/delete" -> fsDelete(r, out)
            r.method == "POST" && p == "/fs/mkdir" -> fsMkdir(r, out)
            r.method == "POST" && p == "/fs/rename" -> fsRename(r, out)
            else -> err(out, 404, "Unknown endpoint: ${r.method} ${r.path}")
        }
    }
    private fun remote(what: String) = Telemetry.event("remote", "The Monitor app $what.")
    private fun ok(out: OutputStream) = json(out, 200, JSONObject().put("ok", true))

    private fun status(): JSONObject {
        val st = HostService.state.value; val bs = BatteryGuardService.state.value
        val am = ctx.getSystemService(ActivityManager::class.java); val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val root = Environment.getExternalStorageDirectory(); val sf = StatFs(root.path)
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        return JSONObject()
            .put("server", JSONObject().put("running", st.running).put("nodeUp", st.nodeUp).put("online", st.online).put("busy", st.busy)
                .put("tunnelUrl", st.tunnelUrl ?: JSONObject.NULL).put("publishedUrl", st.publishedUrl ?: JSONObject.NULL).put("repoSha", st.repoSha))
            .put("llm", JSONObject().put("running", st.llm.running).put("ready", st.llm.ready).put("model", st.llm.model).put("busy", st.llm.busy)
                .put("error", st.llm.error).put("download", JSONObject().put("name", st.llm.dlName).put("done", st.llm.dlDone).put("total", st.llm.dlTotal).put("error", st.llm.dlError)))
            .put("battery", JSONObject().put("level", bs.level).put("charging", bs.charging).put("tempC", temp).put("alarmEverySec", bs.beepEverySec))
            .put("memory", JSONObject().put("total", mi.totalMem).put("available", mi.availMem))
            .put("storage", JSONObject().put("total", sf.totalBytes).put("free", sf.availableBytes).put("root", root.path))
            .put("device", JSONObject().put("model", Build.MODEL).put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT).put("appVersion", ver))
            .put("uptimeMs", SystemClock.elapsedRealtime()).put("time", System.currentTimeMillis())
    }

    private fun logs(source: String, n: Int): JSONObject {
        val lines = JSONArray()
        when (source) {
            "server" -> { val f = RepoManager(ctx).logFile; if (f.exists()) f.readLines().takeLast(n).forEach { lines.put(JSONObject().put("level", if (it.contains("error", true)) "ERROR" else "INFO").put("msg", it)) } }
            else -> HostService.state.value.logs.filter { source != "llm" || it.msg.startsWith("llm:") }.takeLast(n)
                .forEach { lines.put(JSONObject().put("time", it.time).put("level", it.level.name).put("msg", it.msg)) }
        }
        return JSONObject().put("source", source).put("lines", lines)
    }

    private fun llmModels(): JSONObject {
        val a = JSONArray(); LlamaManager.models().forEach { a.put(JSONObject().put("name", it.name).put("size", it.length())) }
        val v = JSONArray(); LlamaManager.visionFiles().forEach { v.put(JSONObject().put("name", it.name).put("size", it.length())) }
        return JSONObject().put("models", a).put("vision", v).put("selected", prefs.llmModel).put("ctx", prefs.llmCtx).put("threads", prefs.llmThreads)
    }

    // ---------------- files ----------------
    private val storageRoot get() = Environment.getExternalStorageDirectory().canonicalFile
    private fun resolve(path: String?): File = File(if (path.isNullOrEmpty()) storageRoot.path else path).canonicalFile

    private fun fsList(r: Req, out: OutputStream) {
        val dir = resolve(r.query["path"])
        if (!dir.exists()) return err(out, 404, "Not found: ${dir.path}")
        if (!dir.isDirectory) return err(out, 400, "Not a folder: ${dir.path}")
        val kids = dir.listFiles() ?: return err(out, 403, "Can't read this folder (permission denied)")
        val arr = JSONArray()
        kids.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).take(3000).forEach {
            arr.put(JSONObject().put("name", it.name).put("dir", it.isDirectory).put("size", if (it.isDirectory) -1 else it.length())
                .put("modified", it.lastModified()).put("canWrite", it.canWrite()))
        }
        json(out, 200, JSONObject().put("path", dir.path).put("parent", dir.parentFile?.path ?: JSONObject.NULL).put("entries", arr).put("truncated", kids.size > 3000))
    }

    private fun fsRead(r: Req, out: OutputStream) {
        val f = resolve(r.query["path"]); val max = (r.query["max"]?.toIntOrNull() ?: 200_000).coerceIn(1, 1_000_000)
        if (!f.isFile) return err(out, 404, "Not a file")
        val buf = ByteArray(minOf(f.length(), max.toLong()).toInt()); f.inputStream().use { var o = 0; while (o < buf.size) { val n = it.read(buf, o, buf.size - o); if (n < 0) break; o += n } }
        if (buf.any { it == 0.toByte() }) return err(out, 415, "This looks like a binary file; use download instead")
        json(out, 200, JSONObject().put("path", f.path).put("size", f.length()).put("truncated", f.length() > max).put("text", String(buf)))
    }

    private fun fsDownload(r: Req, out: OutputStream) {
        val f = resolve(r.query["path"]); if (!f.isFile) return err(out, 404, "Not a file")
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${f.length()}\r\nContent-Disposition: attachment; filename=\"${f.name.replace("\"", "")}\"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        f.inputStream().use { it.copyTo(out, 64 * 1024) }; out.flush()
    }

    /** Refuses to touch the storage root itself or anything above it. */
    private fun protectedPath(f: File) = f == storageRoot || f.path.length < storageRoot.path.length || !f.path.startsWith(storageRoot.path)

    private fun fsDelete(r: Req, out: OutputStream) {
        val f = resolve(r.json().optString("path"))
        if (!f.exists()) return err(out, 404, "Not found")
        if (protectedPath(f)) return err(out, 403, "Refusing to delete the storage root or system folders")
        val done = if (f.isDirectory) f.deleteRecursively() else f.delete()
        HostService.addLog("manage: deleted ${f.path}" + if (done) "" else " (failed)", if (done) Lvl.WARN else Lvl.ERROR)
        Telemetry.event(if (done) "remote" else "problem", if (done) "The Monitor app deleted ${if (f.isDirectory) "the folder" else "the file"} ${f.name}." else "The Monitor app tried to delete ${f.name}, but it could not be removed.")
        if (done) ok(out) else err(out, 500, "Could not delete (in use or permission denied)")
    }

    private fun fsMkdir(r: Req, out: OutputStream) {
        val f = resolve(r.json().optString("path")); if (protectedPath(f)) return err(out, 403, "Not allowed here")
        if (f.mkdirs() || f.isDirectory) { Telemetry.event("remote", "The Monitor app created the folder ${f.name}."); ok(out) } else err(out, 500, "Could not create folder")
    }

    private fun fsRename(r: Req, out: OutputStream) {
        val b = r.json(); val f = resolve(b.optString("path")); val to = resolve(b.optString("to"))
        if (!f.exists()) return err(out, 404, "Not found")
        if (protectedPath(f) || protectedPath(to)) return err(out, 403, "Not allowed here")
        if (to.exists()) return err(out, 400, "Target already exists")
        if (f.renameTo(to)) { Telemetry.event("remote", "The Monitor app renamed ${f.name} to ${to.name}."); ok(out) } else err(out, 500, "Could not rename")
    }
}
