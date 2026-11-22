package com.aquib.vpshost

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** Does what `curl -i <url>` would: DNS, then an HTTP GET of / and /health, with timings. */
object TunnelTester {
    suspend fun run(url: String): Outcome = withContext(Dispatchers.IO) {
        val sb = StringBuilder("$ curl -i $url\n")
        val host = URL(url).host

        val t0 = System.currentTimeMillis()
        val dnsOk = runCatching { InetAddress.getAllByName(host) }
            .onSuccess { sb.append("DNS   OK  ${it.joinToString { a -> a.hostAddress ?: "?" }}  (${System.currentTimeMillis() - t0} ms)\n") }
            .onFailure { sb.append("DNS   FAIL  ${it.javaClass.simpleName}: ${it.message}\n") }
            .isSuccess

        var allOk = dnsOk
        if (dnsOk) for (path in listOf("/", "/health")) {
            val t = System.currentTimeMillis()
            try {
                val c = URL(url + path).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                val code = c.responseCode
                val body = (if (code in 200..399) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                sb.append("GET $path  HTTP $code  ${System.currentTimeMillis() - t} ms\n    ${body.take(160).replace("\n", " ")}\n")
                if (code !in 200..299) allOk = false
            } catch (e: Exception) {
                allOk = false
                sb.append("GET $path  FAIL  ${e.javaClass.simpleName}: ${e.message}\n")
            }
        }
        Outcome(if (allOk) Kind.OK else Kind.FAIL, if (allOk) "Working" else "Unreachable", sb.toString().trimEnd())
    }
}
