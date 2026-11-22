package com.aquib.vpshost

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads the Express repo (GitHub zip) into shared storage and unpacks the bundled node_modules. */
class RepoManager(private val ctx: Context) {
    /** Visible to the user: /sdcard/VpsHost/express (needs All-files access). */
    val baseDir = File(Environment.getExternalStorageDirectory(), "VpsHost")
    val repoDir = File(baseDir, "express")
    val logFile = File(baseDir, "node.log")
    val modulesDir = File(ctx.filesDir, "node_modules")
    val bootstrap = File(ctx.filesDir, "bootstrap.js")

    fun repoPresent() = File(repoDir, "package.json").exists()

    /** Latest commit SHA of the express repo, or null if offline. */
    fun remoteSha(): String? = runCatching {
        val c = URL(Config.EXPRESS_SHA_API).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github.sha")
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.inputStream.bufferedReader().readText().trim().takeIf { it.length >= 7 }
    }.getOrNull()

    /** Downloads and replaces the repo contents. Returns the sha it corresponds to. */
    fun download(log: (String) -> Unit): String {
        baseDir.mkdirs()
        val sha = remoteSha() ?: ""
        log("Downloading ${Config.EXPRESS_OWNER}/${Config.EXPRESS_REPO}@${sha.take(7)} ...")
        val tmp = File(baseDir, "express.new").apply { deleteRecursively(); mkdirs() }
        val conn = URL(Config.EXPRESS_ZIP).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        ZipInputStream(conn.inputStream).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val rel = e.name.substringAfter('/', "")  // strip "express-main/"
                if (rel.isNotEmpty()) {
                    val out = File(tmp, rel)
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath)) throw SecurityException("zip path escape")
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zin.copyTo(it) } }
                }
                e = zin.nextEntry
            }
        }
        if (!File(tmp, "package.json").exists()) throw IllegalStateException("Downloaded repo has no package.json")
        repoDir.deleteRecursively()
        if (!tmp.renameTo(repoDir)) throw IllegalStateException("Could not move repo into place")
        warnIfDepsChanged(log)
        log("Repo ready at ${repoDir.path}")
        return sha
    }

    /** Copies bootstrap.js and unpacks node_modules.zip (bundled express deps) once per app version. */
    fun prepareRuntime(log: (String) -> Unit) {
        ctx.assets.open("bootstrap.js").use { i -> bootstrap.outputStream().use { i.copyTo(it) } }
        val marker = File(modulesDir, ".unpacked-${BuildConfigVersion.code(ctx)}")
        if (marker.exists()) return
        log("Unpacking bundled node_modules ...")
        modulesDir.deleteRecursively(); modulesDir.mkdirs()
        ctx.assets.open("node_modules.zip").use { raw ->
            ZipInputStream(raw).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val rel = e.name.removePrefix("node_modules/") // zip has a top-level node_modules/ folder
                    if (rel.isNotEmpty()) {
                        val out = File(modulesDir, rel)
                        if (!out.canonicalPath.startsWith(modulesDir.canonicalPath)) throw SecurityException("zip path escape")
                        if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zin.copyTo(it) } }
                    }
                    e = zin.nextEntry
                }
            }
        }
        marker.createNewFile()
    }

    /** The app cannot run npm; it ships the dependency set the repo had at build time. */
    private fun warnIfDepsChanged(log: (String) -> Unit) = runCatching {
        val bundled = JSONObject(ctx.assets.open("deps.json").bufferedReader().readText())
        val now = JSONObject(File(repoDir, "package.json").readText()).optJSONObject("dependencies") ?: JSONObject()
        val missing = now.keys().asSequence().filter { !bundled.has(it) }.toList()
        if (missing.isNotEmpty()) log("WARNING: repo needs dependencies not bundled in this app: $missing. Rebuild the app (scripts/fetch-binaries.sh).")
    }
}

object BuildConfigVersion {
    fun code(c: Context): Long = c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime
}
