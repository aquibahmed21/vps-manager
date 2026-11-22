package com.aquib.vpshost

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs llama.cpp's llama-server (shipped as libllama-server.so so Android allows executing it) on 127.0.0.1:LLM_PORT
 * and downloads GGUF models. The Node gateway (bootstrap.js) exposes it under /v1 behind an API key.
 */
class LlamaManager(private val ctx: Context, private val log: (String, Lvl) -> Unit) {
    private var proc: Process? = null
    val alive get() = proc?.isAlive == true

    fun start(model: File, contextSize: Int, threads: Int, mmproj: File? = null) {
        stop()
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libllama-server.so")
        if (!bin.exists()) { log("llm: binary missing (${bin.path}); run scripts/build-llama.sh and rebuild", Lvl.ERROR); return }
        val pb = ProcessBuilder(bin.path, "-m", model.path, "--host", "127.0.0.1", "--port", Config.LLM_PORT.toString(),
            "-c", contextSize.toString(), "-t", threads.toString(), "-np", "1", "--no-webui").apply { if (mmproj != null) { command().add("--mmproj"); command().add(mmproj.path) } }.redirectErrorStream(true)
        pb.environment()["HOME"] = ctx.filesDir.path
        pb.environment()["TMPDIR"] = ctx.cacheDir.path
        val p = pb.start(); proc = p
        Thread({
            try { p.inputStream.bufferedReader().forEachLine { line -> logLine(line) } } catch (_: java.io.IOException) { }
        }, "llm-reader").start()
    }

    private fun logLine(line: String) {
        // llama.cpp lines look like "0.12.345.678 I message"; keep level, drop the timestamp.
        val m = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+ ([IWEDV]) (.*)$").find(line)
        val msg = (m?.groupValues?.get(2) ?: line).trim()
        if (msg.isEmpty()) return
        val lvl = when (m?.groupValues?.get(1)) { "E" -> Lvl.ERROR; "W" -> Lvl.WARN; else -> Lvl.INFO }
        if (m?.groupValues?.get(1) == "D" || m?.groupValues?.get(1) == "V") return
        log("llm: ${msg.take(220)}", lvl)
    }

    fun stop() { proc?.destroy(); proc?.let { p -> Thread { Thread.sleep(2000); if (p.isAlive) p.destroyForcibly() }.start() }; proc = null }

    /** Tells the Node gateway the key and whether the model is ready. */
    fun writeConf(key: String, ready: Boolean, requireKey: Boolean = Prefs(ctx).llmRequireKey) {
        runCatching { File(ctx.filesDir, "llm.json").writeText(JSONObject().put("key", key).put("requireKey", requireKey).put("ctx", Prefs(ctx).llmCtx).put("port", Config.LLM_PORT).put("ready", ready).toString()) }
    }

    companion object {
        val modelsDir = File(Environment.getExternalStorageDirectory(), "VpsHost/models")
        private fun ggufs() = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) }?.sortedBy { it.name.lowercase() } ?: emptyList()
        /** Chat models; vision add-on files ("mmproj") are not models and are listed separately. */
        fun models(): List<File> = ggufs().filter { !it.name.contains("mmproj", true) }
        fun visionFiles(): List<File> = ggufs().filter { it.name.contains("mmproj", true) }
        /** The vision add-on that belongs to a model: same family name (e.g. "gemma-4-E2B"), so images work automatically. */
        fun visionFor(model: File): File? {
            val key = model.name.lowercase().substringBefore("-it").substringBefore("-instruct").substringBefore("-q")
            return visionFiles().firstOrNull { it.name.lowercase().contains(key) }
        }
        fun sizeText(b: Long) = if (b >= 1L shl 30) "%.1f GB".format(b / 1073741824.0) else "%d MB".format(b shr 20)

        /** Downloads (resumable) into modelsDir; returns the final file. [progress] gets (bytesDone, bytesTotal or -1). */
        fun download(url: String, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): File {
            modelsDir.mkdirs()
            val name = Uri(url)
            require(name.endsWith(".gguf", true)) { "URL must point to a .gguf file" }
            val dest = File(modelsDir, name); val part = File(modelsDir, "$name.part")
            if (dest.exists()) return dest
            val have = if (part.exists()) part.length() else 0L
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            check(code == 200 || code == 206) { "Server answered HTTP $code" + if (code == 401 || code == 403) " (model may need a login)" else "" }
            val resumed = code == 206
            val total = (if (resumed) have else 0L) + c.contentLengthLong.let { if (it < 0) -1L else it }
            RandomAccessFile(part, "rw").use { out ->
                if (resumed) out.seek(have) else out.setLength(0)
                var done = if (resumed) have else 0L; var last = 0L
                val buf = ByteArray(256 * 1024)
                c.inputStream.use { inp ->
                    while (true) {
                        if (cancelled()) throw java.util.concurrent.CancellationException("Download cancelled")
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 500) { last = now; progress(done, total) }
                    }
                }
                progress(done, total)
                check(total < 0 || done == total) { "Download incomplete ($done of $total bytes)" }
            }
            check(part.renameTo(dest)) { "Could not move the finished download into place" }
            return dest
        }

        private fun Uri(url: String) = url.substringBefore('?').substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
