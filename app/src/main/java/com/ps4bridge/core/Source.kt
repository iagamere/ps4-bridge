package com.ps4bridge.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class SourceError(val code: Int, msg: String, val expired: Boolean = false, val retryable: Boolean = false) : IOException(msg)
class SourceInfo(
    val size: Long,
    val ranges: Boolean,
    val name: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val contentType: String? = null
)

/** HTTP(S) source with strict Range validation, redirects, retry classification and hot URL swap. */
object Source {
    @Volatile var url: String = ""
    @Volatile private var generation: Long = 0L

    fun setSourceUrl(newUrl: String) {
        url = newUrl.trim()
        generation++
    }

    fun generation(): Long = generation

    fun open(u0: String, start: Long, end: Long?): HttpURLConnection {
        var u = u0
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = Cfg.connectTimeoutMs
            c.readTimeout = Cfg.readTimeoutMs
            c.setRequestProperty("Range", if (end != null) "bytes=$start-$end" else "bytes=$start-")
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) PS4Bridge/1.2")
            c.setRequestProperty("Accept-Encoding", "identity")
            val code = c.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = c.getHeaderField("Location") ?: throw SourceError(code, "redirect without Location")
                u = URL(URL(u), loc).toString(); c.disconnect(); return@repeat
            }
            return c
        }
        throw IOException("too many redirects")
    }

    fun probe(u: String): SourceInfo {
        val c = open(u, 0, 0)
        try {
            val code = c.responseCode
            val name = guessName(u, c.getHeaderField("Content-Disposition"))
            val common = SourceInfo(0, false, name, c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"), c.contentType)
            return when (code) {
                206 -> {
                    val cr = c.getHeaderField("Content-Range") ?: throw SourceError(code, "206 without Content-Range")
                    val m = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").find(cr.trim())
                        ?: throw SourceError(code, "bad Content-Range: $cr")
                    val total = m.groupValues[3].toLongOrNull() ?: throw SourceError(code, "bad source size")
                    if (total <= 0) throw SourceError(code, "invalid source size: $total")
                    SourceInfo(total, true, name, common.etag, common.lastModified, common.contentType)
                }
                200 -> SourceInfo(c.contentLengthLong, false, name, common.etag, common.lastModified, common.contentType)
                400 -> throw SourceError(code, "HTTP 400: bad request", retryable = false)
                401 -> throw SourceError(code, "HTTP 401: unauthorized", expired = true)
                403 -> throw SourceError(code, "HTTP 403: forbidden or signed URL expired", expired = looksSigned(u) || isLikelyExpired(c))
                404 -> throw SourceError(code, "HTTP 404: not found")
                408, 429, 500, 502, 503, 504 -> throw SourceError(code, "HTTP $code: temporary source failure", retryable = true)
                410 -> throw SourceError(code, "HTTP 410: gone", expired = true)
                else -> throw SourceError(code, "HTTP $code")
            }
        } finally { c.disconnect() }
    }

    fun classify(c: HttpURLConnection, u: String): SourceError? {
        val code = c.responseCode
        if (code in 200..299) return null
        val expired = code == 401 || code == 410 || (code == 403 && (looksSigned(u) || isLikelyExpired(c)))
        val retryable = code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504
        return SourceError(code, "HTTP $code", expired, retryable)
    }

    fun isLikelyExpired(c: HttpURLConnection): Boolean {
        val hints = listOf("x-amz-error-code", "x-amz-error-message", "server", "cf-cache-status")
            .joinToString(" ") { c.getHeaderField(it) ?: "" }.lowercase()
        val auth = (c.getHeaderField("WWW-Authenticate") ?: "").lowercase()
        return hints.contains("expired") || hints.contains("requesthasexpired") || auth.contains("expired")
    }

    private fun looksSigned(u: String): Boolean {
        val q = try { URL(u).query?.lowercase() ?: "" } catch (_: Exception) { "" }
        return q.contains("x-amz-signature=") || q.contains("x-amz-expires=") || q.contains("x-amz-credential=") || q.contains("signature=")
    }

    private fun guessName(u: String, cd: String?): String {
        if (cd != null) {
            val m = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)
            if (m != null) return try { URLDecoder.decode(m.groupValues[1], "UTF-8") } catch (_: Exception) { m.groupValues[1] }
        }
        val tail = try { URL(u).path.substringAfterLast('/') } catch (_: Exception) { "" }
        return if (tail.isNotBlank()) tail else "app.pkg"
    }
}
