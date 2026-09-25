package com.ps4bridge.core

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

/** Small HTTP/1.1 server implementing GET/HEAD + single-byte-range semantics for the PS4. */
class ProxyServer(private val port: Int) {
    private var ss: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool(ThreadFactory { r -> Thread(r).apply { isDaemon = true } })

    @Volatile var running = false
        private set

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        ss = s
        running = true
        Thread({ acceptLoop(s) }, "proxy-accept").apply { isDaemon = true }.start()
        Log.add("Local proxy listening on :$port")
    }

    fun stop() {
        running = false
        try { ss?.close() } catch (_: Exception) {}
        pool.shutdownNow()
        Log.add("Local proxy stopped")
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val c = try { s.accept() } catch (_: IOException) { break }
            pool.execute {
                try { handle(c) }
                catch (e: Exception) { Log.add("connection error: ${e.message}") }
                finally { try { c.close() } catch (_: Exception) {} }
            }
        }
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 20_000
        try { sock.sendBufferSize = 512 * 1024 } catch (_: Exception) {}
        sock.tcpNoDelay = true
        val clientIp = sock.inetAddress.hostAddress ?: "unknown"
        val fromPs4 = !sock.inetAddress.isLoopbackAddress
        val ins = BufferedInputStream(sock.getInputStream())
        val reqLine = readLine(ins) ?: return
        val parts = reqLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        var rangeHdr: String? = null
        while (true) {
            val h = readLine(ins) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0 && h.substring(0, i).trim().equals("Range", ignoreCase = true)) rangeHdr = h.substring(i + 1).trim()
        }
        sock.soTimeout = 0
        val out = sock.getOutputStream()

        if (method != "GET" && method != "HEAD") {
            writeHead(out, "405 Method Not Allowed", mapOf("Content-Length" to "0", "Allow" to "GET, HEAD", "Connection" to "close"))
            return
        }

        var total = Cfg.totalSize
        if (total <= 0) {
            try {
                total = Source.probe(Source.url).size
                Cfg.totalSize = total
            } catch (e: Exception) {
                Log.add("cannot read source size: ${e.message}")
                writeHead(out, "502 Bad Gateway", mapOf("Content-Length" to "0", "Connection" to "close"))
                return
            }
        }

        val partial = rangeHdr != null
        val range = if (rangeHdr == null) {
            longArrayOf(0L, total - 1)
        } else {
            parseRange(rangeHdr, total) ?: run {
                if (fromPs4) Log.add("PS4 $method bad/unsatisfiable Range '$rangeHdr' -> 416")
                writeHead(out, "416 Range Not Satisfiable", mapOf("Content-Range" to "bytes */$total", "Content-Length" to "0", "Connection" to "close"))
                return
            }
        }
        val start = range[0]
        val end = range[1]

        val hdr = linkedMapOf(
            "Accept-Ranges" to "bytes",
            "Content-Type" to "application/octet-stream",
            "Content-Length" to (end - start + 1).toString(),
            "Connection" to "close"
        )
        if (partial) hdr["Content-Range"] = "bytes $start-$end/$total"
        writeHead(out, if (partial) "206 Partial Content" else "200 OK", hdr)
        if (method == "HEAD") {
            if (fromPs4) Log.add("PS4 HEAD from $clientIp")
            return
        }

        if (fromPs4) {
            Progress.notePs4Request(start, end, clientIp)
            Log.add("PS4 GET ${rangeHdr ?: "(no Range)"} from $clientIp -> ${start shr 20}..${end shr 20} MB")
            if (Progress.resumeSeenAt == start && start > 0) {
                Log.add("PS4 RESUME VERIFIED: new request after a prior disconnected stream, starting at ${start shr 20} MB")
            }
        } else {
            Log.add("self-test GET ${rangeHdr ?: "(no Range)"}")
        }

        Progress.streams.incrementAndGet()
        if (fromPs4) Progress.ps4Streams.incrementAndGet()
        val stop = AtomicBoolean(false)
        try {
            Feeder.stream(start, end, out, stop, countStats = fromPs4)
            Log.add("range finished: ${start shr 20}..${end shr 20} MB")
        } catch (e: IOException) {
            Log.add("${if (fromPs4) "PS4" else "self-test"} stream ended near ${Progress.position shr 20} MB: ${e.message}")
        } finally {
            stop.set(true)
            Progress.streams.decrementAndGet()
            if (fromPs4) {
                Progress.ps4Streams.decrementAndGet()
                Progress.ps4RequestFinished()
            }
        }
    }

    /** Only one Range is supported. Multi-range is deliberately rejected. */
    private fun parseRange(h: String, total: Long): LongArray? {
        val m = Regex("^bytes=(\\d*)-(\\d*)$").find(h.trim()) ?: return null
        val s = m.groupValues[1]
        val e = m.groupValues[2]
        val start: Long
        val end: Long
        try {
            if (s.isEmpty()) {
                val n = e.toLong()
                if (n <= 0) return null
                start = maxOf(0L, total - n)
                end = total - 1
            } else {
                start = s.toLong()
                end = if (e.isEmpty()) total - 1 else minOf(e.toLong(), total - 1)
            }
        } catch (_: NumberFormatException) { return null }
        if (start < 0 || start >= total || end < start) return null
        return longArrayOf(start, end)
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun writeHead(out: OutputStream, status: String, h: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $status\r\n")
        for ((k, v) in h) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }
}

object ProxyHolder {
    @Volatile var server: ProxyServer? = null

    @Synchronized
    fun ensure(): Boolean {
        if (server?.running == true) return false
        val s = ProxyServer(Cfg.localPort)
        s.start()
        server = s
        return true
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }
}
