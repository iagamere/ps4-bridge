package com.ps4bridge.core

import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.CRC32

/** Diagnostics that run BEFORE any big transfer. Anything not proven stays UNKNOWN. */
object Doctor {
    class Check(val name: String, val status: String, val detail: String)

    fun run(ps4Ip: String, rpiPort: Int, phoneIp: String?, sourceUrl: String): List<Check> {
        val res = ArrayList<Check>()
        fun add(name: String, ok: Boolean?, detail: String) {
            val st = when (ok) { true -> "PASS"; false -> "FAIL"; null -> "UNKNOWN" }
            res.add(Check(name, st, detail))
            Log.add("[$st] $name - $detail")
        }
        fun guarded(name: String, block: () -> Pair<Boolean?, String>) {
            try {
                val (ok, d) = block()
                add(name, ok, d)
            } catch (e: SourceError) {
                add(name, false, "HTTP ${e.code}: ${e.message}")
            } catch (e: Exception) {
                add(name, false, "${e.javaClass.simpleName}: ${e.message}")
            }
        }

        add("Phone IP (Wi-Fi)", phoneIp != null, phoneIp ?: "not found - connect to Wi-Fi")
        guarded("PS4 reachable (RPI port $rpiPort)") {
            val ok = RpiClient.tcpOk(ps4Ip, rpiPort)
            ok to (if (ok) "TCP connect ok" else "cannot connect - open Remote Package Installer on the PS4")
        }

        Progress.resetRequestEvidence()
        Source.setSourceUrl(sourceUrl)
        var total = 0L
        var rangeOk = false
        guarded("Source HEAD/Range probe") {
            val info = Source.probe(sourceUrl)
            total = info.size
            rangeOk = info.ranges
            Cfg.totalSize = info.size
            Cfg.fileName = info.name
            info.ranges to "size=${Fmt.bytes(info.size)} ($total B), Range=${if (info.ranges) "supported (206)" else "NOT supported (200)"}, name=${info.name}"
        }
        if (total <= 0 || !rangeOk) {
            add("Remaining tests", false, "skipped: the source must support Range and report its size")
            return res
        }

        guarded("Source Range in the middle of the file") {
            val mid = total / 2
            val bytes = direct(mid, mid + 1023)
            (bytes.size == 1024) to "read ${bytes.size} bytes at offset ${mid} (${Fmt.bytes(mid)})"
        }

        val startedHere = try { ProxyHolder.ensure() } catch (e: Exception) {
            add("Local proxy start", false, "port ${Cfg.localPort}: ${e.message}")
            return res
        }
        try {
            guarded("Proxy HEAD") {
                val r = local("HEAD", null, 0)
                (r.code == 200 && r.length == total) to "HTTP ${r.code}, Content-Length=${r.length}"
            }
            guarded("Proxy Range 0-1023") {
                val r = local("GET", "bytes=0-1023", 1024)
                val exp = "bytes 0-1023/$total"
                (r.code == 206 && r.contentRange == exp && r.read == 1024) to "HTTP ${r.code}, Content-Range='${r.contentRange}', read=${r.read}"
            }
            guarded("Proxy Range in the middle (offset > 4 GB if file is big)") {
                val mid = total / 2
                val r = local("GET", "bytes=$mid-${mid + 1023}", 1024)
                val direct = direct(mid, mid + 1023)
                val same = direct.contentEquals(r.data)
                (r.code == 206 && r.read == 1024 && same) to "HTTP ${r.code}, bytes identical to the source: $same"
            }
            guarded("Proxy open-ended Range (bytes=N-) like the PS4 uses") {
                val n = total - 2048
                val r = local("GET", "bytes=$n-", 2048)
                (r.code == 206 && r.read == 2048 && r.contentRange == "bytes $n-${total - 1}/$total") to "HTTP ${r.code}, Content-Range='${r.contentRange}', read=${r.read}"
            }
            guarded("Proxy multi-chunk ordering (~${(Cfg.chunkBytes * 3 + 123) shr 20} MB)") {
                val a = (total / 3).coerceAtMost(total - 1 - (Cfg.chunkBytes * 3 + 123))
                val b = a + Cfg.chunkBytes * 3 + 122
                val p = local("GET", "bytes=$a-$b", -1, crcOnly = true)
                val d = crcDirect(a, b)
                (p.code == 206 && p.crc == d) to "CRC via proxy=${p.crc} direct=$d"
            }
            add("Resume by the PS4", null,
                "NOT TESTED by Doctor: a local self-test cannot prove a real PS4 pause/resume. Start a real transfer, interrupt it, resume it, then inspect the log for 'PS4 RESUME VERIFIED'.")
        } finally {
            if (startedHere) ProxyHolder.stop()
        }
        return res
    }

    fun report(list: List<Check>): String =
        Log.redact(list.joinToString("\n") { "${it.status}  ${it.name}\n      ${it.detail}" })

    /** Local self test used right before sending the install request. Returns an error text or null. */
    fun selfTest(): String? = try {
        val total = Cfg.totalSize
        val h = local("HEAD", null, 0)
        if (h.code != 200 || h.length != total) "proxy HEAD failed (HTTP ${h.code})"
        else {
            val r = local("GET", "bytes=0-1023", 1024)
            if (r.code != 206 || r.read != 1024) "proxy Range failed (HTTP ${r.code})" else null
        }
    } catch (e: Exception) {
        "proxy self-test error: ${e.message}"
    }

    // ---- helpers -----------------------------------------------------------------------------
    private class Local(val code: Int, val length: Long, val contentRange: String, val read: Int, val data: ByteArray, val crc: Long)

    private fun local(method: String, range: String?, want: Int, crcOnly: Boolean = false): Local {
        val c = URL("http://127.0.0.1:${Cfg.localPort}/app.pkg").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 5000
            c.readTimeout = 60_000
            if (range != null) c.setRequestProperty("Range", range)
            val code = c.responseCode
            val cr = c.getHeaderField("Content-Range") ?: ""
            val len = c.contentLengthLong
            if (method == "HEAD" || code >= 400) return Local(code, len, cr, 0, ByteArray(0), 0)
            if (crcOnly) {
                val crc = CRC32()
                val buf = ByteArray(64 * 1024)
                var n: Int
                var count = 0
                c.inputStream.use { ins ->
                    while (true) { n = ins.read(buf); if (n < 0) break; crc.update(buf, 0, n); count += n }
                }
                return Local(code, len, cr, count, ByteArray(0), crc.value)
            }
            val data = ByteArray(want)
            var off = 0
            c.inputStream.use { ins ->
                while (off < want) { val n = ins.read(data, off, want - off); if (n < 0) break; off += n }
            }
            return Local(code, len, cr, off, if (off == want) data else data.copyOf(off), 0)
        } finally {
            c.disconnect()
        }
    }

    private fun direct(a: Long, b: Long): ByteArray {
        val c = Source.open(Source.url, a, b)
        try {
            if (c.responseCode != 206) throw SourceError(c.responseCode, "expected 206")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun crcDirect(a: Long, b: Long): Long {
        val c = Source.open(Source.url, a, b)
        try {
            if (c.responseCode != 206) throw SourceError(c.responseCode, "expected 206")
            val crc = CRC32()
            val buf = ByteArray(64 * 1024)
            c.inputStream.use { ins ->
                while (true) { val n = ins.read(buf); if (n < 0) break; crc.update(buf, 0, n) }
            }
            return crc.value
        } finally {
            c.disconnect()
        }
    }
}
