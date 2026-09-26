package com.ps4bridge.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** Direct URL -> FTP PKG streaming. Designed for a PS4 FTP server on the local network. */
object FtpPkgTransfer {
    data class Config(
        val host: String,
        val port: Int = 21,
        val username: String = "anonymous",
        val password: String = "anonymous@",
        val remotePath: String,
        val connectTimeoutMs: Int = 15_000,
        val readTimeoutMs: Int = 30_000,
        val bufferBytes: Int = 1024 * 1024,
        val maxRetries: Int = 5
    )

    data class Result(val success: Boolean, val bytes: Long, val total: Long, val message: String)

    fun transfer(
        sourceUrl: String,
        cfg: Config,
        onProgress: (written: Long, total: Long, speedBytesPerSec: Long) -> Unit = { _, _, _ -> },
        shouldStop: () -> Boolean = { false }
    ): Result {
        require(sourceUrl.startsWith("http://", true) || sourceUrl.startsWith("https://", true))
        require(cfg.remotePath.isNotBlank())
        val total = probeSize(sourceUrl, cfg.readTimeoutMs)
        var lastError: Exception? = null
        repeat(cfg.maxRetries.coerceAtLeast(1)) { attempt ->
            if (shouldStop()) return Result(false, 0, total, "stopped")
            try {
                val result = transferOnce(sourceUrl, total, cfg, onProgress, shouldStop)
                if (result.success) return result
                lastError = IOException(result.message)
            } catch (e: Exception) {
                lastError = e
                Log.add("FTP attempt ${attempt + 1} failed: ${e.message}")
            }
            if (attempt + 1 < cfg.maxRetries && !shouldStop()) Thread.sleep(((attempt + 1) * 1000L).coerceAtMost(5000L))
        }
        return Result(false, currentRemoteSize(cfg), total, lastError?.message ?: "FTP transfer failed")
    }

    private fun transferOnce(
        sourceUrl: String,
        total: Long,
        cfg: Config,
        onProgress: (Long, Long, Long) -> Unit,
        shouldStop: () -> Boolean
    ): Result {
        val ftp = FtpSession(cfg)
        ftp.connect()
        try {
            val offset = ftp.remoteSize(cfg.remotePath).coerceAtLeast(0L)
            if (offset > total) throw IOException("Remote file is larger than source: $offset > $total")
            if (offset == total) {
                onProgress(total, total, 0)
                return Result(true, total, total, "already complete")
            }

            ftp.prepareBinary()
            if (offset > 0) ftp.rest(offset)
            val data = ftp.openPassiveDataConnection()
            var http: HttpURLConnection? = null
            var written = offset
            try {
                http = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = cfg.connectTimeoutMs
                    readTimeout = cfg.readTimeoutMs
                    requestMethod = "GET"
                    setRequestProperty("Range", "bytes=$offset-")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "PS4Bridge/1.2")
                }
                val code = http.responseCode
                if (offset > 0L && code != HttpURLConnection.HTTP_PARTIAL) throw IOException("Source did not honor resume Range (HTTP $code)")
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) throw IOException("Source HTTP $code")

                BufferedInputStream(http.inputStream, cfg.bufferBytes).use { input ->
                    BufferedOutputStream(data.getOutputStream(), cfg.bufferBytes).use { output ->
                        val buffer = ByteArray(cfg.bufferBytes)
                        var lastTime = System.nanoTime()
                        var lastBytes = written
                        while (true) {
                            if (shouldStop()) return Result(false, written, total, "stopped")
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            written += n
                            Progress.position = written
                            Progress.sent.set(written)
                            Progress.sourceRead.set(written)
                            val now = System.nanoTime()
                            if (now - lastTime >= 500_000_000L) {
                                val seconds = (now - lastTime) / 1_000_000_000.0
                                val speed = ((written - lastBytes) / seconds).toLong()
                                Progress.speed = speed.toDouble()
                                Progress.sourceSpeed = speed.toDouble()
                                onProgress(written, total, speed)
                                lastTime = now
                                lastBytes = written
                            }
                        }
                        output.flush()
                    }
                }
            } finally {
                try { data.close() } catch (_: Exception) {}
                http?.disconnect()
            }
            ftp.finishDataTransfer()
            if (written != total) throw IOException("Source ended at $written of $total bytes")
            val finalSize = ftp.remoteSize(cfg.remotePath)
            if (finalSize != total) throw IOException("FTP verification failed: remote=$finalSize source=$total")
            onProgress(finalSize, total, 0)
            return Result(true, finalSize, total, "complete")
        } finally {
            ftp.close()
        }
    }

    private fun probeSize(url: String, timeoutMs: Int): Long {
        val head = URL(url).openConnection() as HttpURLConnection
        try {
            head.connectTimeout = timeoutMs; head.readTimeout = timeoutMs; head.requestMethod = "HEAD"
            head.setRequestProperty("Accept-Encoding", "identity")
            if (head.responseCode in 200..299 && head.contentLengthLong > 0) return head.contentLengthLong
        } finally { head.disconnect() }

        val probe = URL(url).openConnection() as HttpURLConnection
        try {
            probe.connectTimeout = timeoutMs; probe.readTimeout = timeoutMs; probe.requestMethod = "GET"
            probe.setRequestProperty("Range", "bytes=0-0"); probe.setRequestProperty("Accept-Encoding", "identity")
            if (probe.responseCode != HttpURLConnection.HTTP_PARTIAL) throw IOException("Source does not support Range")
            val cr = probe.getHeaderField("Content-Range") ?: throw IOException("Missing Content-Range")
            return cr.substringAfterLast('/').trim().toLongOrNull() ?: throw IOException("Invalid Content-Range")
        } finally { probe.disconnect() }
    }

    private fun currentRemoteSize(cfg: Config): Long = try {
        FtpSession(cfg).useSize(cfg.remotePath)
    } catch (_: Exception) { 0L }

    private class FtpSession(private val cfg: Config) {
        private var control: Socket? = null
        private lateinit var input: BufferedInputStream
        private lateinit var output: BufferedOutputStream
        private var lastCode = -1

        fun connect() {
            control = Socket().also {
                it.connect(InetSocketAddress(cfg.host, cfg.port), cfg.connectTimeoutMs)
                it.soTimeout = cfg.readTimeoutMs
                input = BufferedInputStream(it.getInputStream()); output = BufferedOutputStream(it.getOutputStream())
            }
            expect(220)
            command("USER ${cfg.username}", 331, 230)
            if (lastCode == 331) command("PASS ${cfg.password}", 230)
        }
        fun prepareBinary() = command("TYPE I", 200)
        fun remoteSize(path: String): Long {
            val r = command("SIZE $path", 213, 550)
            return if (lastCode == 213) r.substringAfter(' ').trim().toLongOrNull() ?: 0L else 0L
        }
        fun useSize(path: String): Long { connect(); return try { remoteSize(path) } finally { close() } }
        fun rest(offset: Long) { command("REST $offset", 350) }
        fun openPassiveDataConnection(): Socket {
            val response = command("PASV", 227)
            val tuple = response.substringAfter('(').substringBefore(')').split(',').map { it.trim().toInt() }
            if (tuple.size != 6) throw IOException("Invalid PASV response")
            val host = tuple.take(4).joinToString(".")
            val port = tuple[4] * 256 + tuple[5]
            val data = Socket().also { it.connect(InetSocketAddress(host, port), cfg.connectTimeoutMs); it.soTimeout = cfg.readTimeoutMs }
            try { command("STOR ${cfg.remotePath}", 150, 125) } catch (e: Exception) { data.close(); throw e }
            return data
        }
        fun finishDataTransfer() = expect(226, 250)
        fun close() {
            try { if (control?.isConnected == true) command("QUIT", 221) } catch (_: Exception) {}
            try { control?.close() } catch (_: Exception) {}
        }
        private fun expect(vararg accepted: Int): String = command(null, *accepted)
        private fun command(cmd: String?, vararg accepted: Int): String {
            if (cmd != null) { output.write((cmd + "\r\n").toByteArray(Charsets.US_ASCII)); output.flush() }
            val response = readResponse(); lastCode = response.substring(0, 3).toIntOrNull() ?: -1
            if (lastCode !in accepted) throw IOException("FTP $lastCode: $response")
            return response
        }
        private fun readResponse(): String {
            val first = readLine() ?: throw IOException("FTP connection closed")
            if (first.length < 3) throw IOException("Invalid FTP response")
            if (first.length >= 4 && first[3] == '-') {
                val code = first.substring(0, 3)
                while (true) { val line = readLine() ?: throw IOException("FTP multiline response ended"); if (line.startsWith("$code ")) return line }
            }
            return first
        }
        private fun readLine(): String? {
            val line = StringBuilder()
            while (true) { val c = input.read(); if (c < 0) return null; if (c == '\n'.code) return line.toString().trimEnd('\r'); line.append(c.toChar()); if (line.length > 8192) throw IOException("FTP response line too long") }
        }
    }
}
