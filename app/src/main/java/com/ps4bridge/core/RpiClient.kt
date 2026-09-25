package com.ps4bridge.core

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Talks to Remote Package Installer. Only /api/install is used, because it is the only endpoint
 * we have actually seen working on the user's console. Pause/resume/cancel endpoints are NOT called:
 * TODO - verify their names on the user's RPI version before adding them.
 */
object RpiClient {
    class Reply(val http: Int, val body: String) {
        val ok: Boolean get() = Regex("\"status\"\\s*:\\s*\"success\"").containsMatchIn(body)
        val rejectedAtParse: Boolean get() = body.contains("Unexpected element value")
    }

    fun tcpOk(ip: String, port: Int, timeoutMs: Int = 3000): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs); true }
    } catch (e: Exception) {
        false
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    fun install(ip: String, port: Int, url: String): Reply {
        val json = "{\"type\":\"direct\",\"packages\":[\"${esc(url)}\"]}"
        val c = URL("http://$ip:$port/api/install").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = 30_000
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val body = stream?.bufferedReader()?.readText() ?: ""
            return Reply(code, body)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Sends two harmless example URLs (one https, one http) just to see how THIS RPI version reacts.
     * It may leave failed entries in the PS4 notifications; they can be deleted.
     */
    fun acceptanceTest(ip: String, port: Int): List<String> {
        val out = ArrayList<String>()
        for (u in listOf("https://example.com/test.pkg", "http://example.com/test.pkg")) {
            val proto = u.substringBefore(':').uppercase()
            try {
                val r = install(ip, port, u)
                val verdict = when {
                    r.ok -> "ACCEPTED"
                    r.rejectedAtParse -> "REJECTED at parse stage"
                    else -> "format accepted, failed later"
                }
                out.add("$proto: $verdict  [${r.body.trim().take(160)}]")
            } catch (e: Exception) {
                out.add("$proto: no answer (${e.message})")
            }
        }
        return out
    }
}
