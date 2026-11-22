package com.aquib.vpshost

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Commits server-url.json to the pointer repo and asks GitHub Pages to redeploy. */
class GitHubPublisher(private val token: () -> String) {
    private val api = "https://api.github.com/repos/${Config.POINTER_OWNER}/${Config.POINTER_REPO}"

    private fun call(method: String, url: String, body: JSONObject? = null, accept: String = "application/vnd.github+json"): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (token().isNotEmpty()) c.setRequestProperty("Authorization", "Bearer ${token()}")
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to text
    }

    suspend fun publish(tunnelUrl: String): String = withContext(Dispatchers.IO) {
        if (token().isEmpty()) throw IllegalStateException("No GitHub token set")
        val file = "$api/contents/${Config.POINTER_FILE}"
        val (gc, gt) = call("GET", "$file?ref=${Config.POINTER_BRANCH}")
        val sha = if (gc == 200) JSONObject(gt).getString("sha") else null
        val json = JSONObject().put("server_url", tunnelUrl).put("updated_at", Instant.now().toString()).put("status", "online")
        val body = JSONObject().put("message", "Update tunnel URL").put("branch", Config.POINTER_BRANCH)
            .put("content", Base64.encodeToString((json.toString(2) + "\n").toByteArray(), Base64.NO_WRAP))
        if (sha != null) body.put("sha", sha)
        val (pc, pt) = call("PUT", file, body)
        if (pc !in 200..201) throw IllegalStateException("GitHub commit failed ($pc): ${pt.take(160)}")
        // Pages (branch source) redeploys on push; also request a build explicitly.
        val (bc, _) = call("POST", "$api/pages/builds")
        "committed; pages build request HTTP $bc"
    }

    /** server_url currently stored in the repo (read via the API, so no Pages CDN lag). */
    suspend fun remoteUrl(): String? = withContext(Dispatchers.IO) {
        val (c, t) = call("GET", "$api/contents/${Config.POINTER_FILE}?ref=${Config.POINTER_BRANCH}", accept = "application/vnd.github.raw+json")
        if (c == 200) JSONObject(t).optString("server_url").ifEmpty { null } else null
    }

    /** What the public Pages site is serving right now. */
    suspend fun pagesUrl(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(Config.POINTER_PAGES_URL + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            JSONObject(c.inputStream.bufferedReader().readText()).optString("server_url").ifEmpty { null }
        }.getOrNull()
    }

    /** Settings > "Check token": can this token write to the pointer repo? */
    suspend fun checkAccess(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (token().isEmpty()) return@withContext false to "No token entered."
        val (c, t) = call("GET", api)
        when {
            c == 401 -> false to "GitHub rejected the token (invalid or expired)."
            c == 404 -> false to "Token cannot see ${Config.POINTER_OWNER}/${Config.POINTER_REPO}. Select that repository when creating it."
            c != 200 -> false to "GitHub returned HTTP $c."
            JSONObject(t).optJSONObject("permissions")?.optBoolean("push") == true ->
                true to "Token can write to ${Config.POINTER_OWNER}/${Config.POINTER_REPO}."
            else -> false to "Token can read the repository but not write. Grant Contents: read and write."
        }
    }
}
