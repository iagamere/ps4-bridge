package com.ps4bridge.core

import java.util.TreeMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Runtime configuration shared by the proxy, downloader and UI. */
object Cfg {
    @Volatile var workers = 4
    @Volatile var chunkBytes = 4L * 1024 * 1024
    @Volatile var localPort = 8000
    @Volatile var totalSize = 0L
    @Volatile var fileName = "app.pkg"

    /** Hard cap for completed/in-flight source chunks retained in memory. */
    @Volatile var maxInFlightBytes = 64L * 1024 * 1024
    @Volatile var maxRetries = 10
    @Volatile var connectTimeoutMs = 30_000
    @Volatile var readTimeoutMs = 30_000
}

/** Live counters and evidence collected from the real PS4 connection. */
object Progress {
    val sent = AtomicLong(0)                 // bytes actually written to PS4 sockets
    val sourceRead = AtomicLong(0)           // bytes read from Internet source
    val streams = AtomicInteger(0)
    val ps4Streams = AtomicInteger(0)
    val waitingNewUrl = AtomicInteger(0)
    val retries = AtomicLong(0)
    val requests = AtomicInteger(0)
    val activeRequests = AtomicInteger(0)

    @Volatile var position = 0L             // unique contiguous bytes from byte 0
    @Volatile var speed = 0.0               // Phone -> PS4 effective speed, bytes/s
    @Volatile var sourceSpeed = 0.0         // Internet -> Phone source speed, bytes/s
    @Volatile var resumeSeenAt = -1L
    @Volatile var lastRequestStart = -1L
    @Volatile var lastRequestEnd = -1L
    @Volatile var lastClientIp = ""
    @Volatile var currentStreamBase = 0L
    @Volatile var stateOverride: String? = null
    @Volatile var onPs4Request: ((Long, Long) -> Unit)? = null

    private var hadPs4Request = false
    private var sawCompletedPs4Stream = false
    private val delivered = TreeMap<Long, Long>()
    private val samples = ArrayDeque<Sample>()

    private data class Sample(val time: Long, val sent: Long, val source: Long)

    @Synchronized
    fun sample() {
        val now = System.currentTimeMillis()
        samples.add(Sample(now, sent.get(), sourceRead.get()))
        while (samples.size > 2 && now - samples.first().time > 20_000) samples.removeFirst()
        if (samples.size >= 2) {
            val f = samples.first()
            val l = samples.last()
            val dt = (l.time - f.time) / 1000.0
            if (dt > 0) {
                speed = (l.sent - f.sent).coerceAtLeast(0) / dt
                sourceSpeed = (l.source - f.source).coerceAtLeast(0) / dt
            }
        }
    }

    @Synchronized
    fun reset() {
        sent.set(0); sourceRead.set(0); streams.set(0); ps4Streams.set(0)
        waitingNewUrl.set(0); retries.set(0); requests.set(0); activeRequests.set(0)
        position = 0; speed = 0.0; sourceSpeed = 0.0; resumeSeenAt = -1
        lastRequestStart = -1; lastRequestEnd = -1; lastClientIp = ""; currentStreamBase = 0L
        stateOverride = null; hadPs4Request = false; sawCompletedPs4Stream = false
        delivered.clear(); samples.clear()
    }

    @Synchronized
    fun resetRequestEvidence() {
        requests.set(0); activeRequests.set(0); resumeSeenAt = -1
        lastRequestStart = -1; lastRequestEnd = -1; lastClientIp = ""; currentStreamBase = 0L
        hadPs4Request = false; sawCompletedPs4Stream = false
    }

    /** Register an actual request from the PS4. A new non-contiguous range after a disconnected stream is resume evidence. */
    @Synchronized
    fun notePs4Request(start: Long, end: Long, clientIp: String) {
        val previousEnd = lastRequestEnd
        val wasDisconnected = hadPs4Request && sawCompletedPs4Stream && activeRequests.get() == 0
        requests.incrementAndGet(); activeRequests.incrementAndGet()
        hadPs4Request = true; lastRequestStart = start; lastRequestEnd = end; lastClientIp = clientIp; currentStreamBase = start
        if (wasDisconnected && start > 0 && start != previousEnd + 1) resumeSeenAt = start
        try { onPs4Request?.invoke(start, end) } catch (_: Exception) {}
    }

    @Synchronized
    fun ps4RequestFinished() {
        if (activeRequests.get() > 0) activeRequests.decrementAndGet()
        sawCompletedPs4Stream = true
    }

    @Synchronized
    fun noteDelivered(start: Long, end: Long) {
        if (end < start) return
        var a = start; var b = end
        val floor = delivered.floorEntry(a)
        if (floor != null && floor.value + 1 >= a) {
            a = minOf(a, floor.key); b = maxOf(b, floor.value); delivered.remove(floor.key)
        }
        var e = delivered.ceilingEntry(a)
        while (e != null && e.key <= b + 1) {
            b = maxOf(b, e.value); delivered.remove(e.key); e = delivered.ceilingEntry(a)
        }
        delivered[a] = b
        val first = delivered.firstEntry()
        val contiguousFromZero = if (first != null && first.key == 0L) first.value + 1 else 0L
        position = maxOf(contiguousFromZero, if (start <= currentStreamBase && end >= currentStreamBase) end + 1 else 0L)
    }

    fun stateText(): String {
        stateOverride?.let { return it }
        return when {
            waitingNewUrl.get() > 0 -> "WAITING_FOR_NEW_SOURCE"
            streams.get() > 0 -> "DOWNLOADING"
            position > 0 && Cfg.totalSize > position -> "WAITING_FOR_PS4"
            position >= Cfg.totalSize && Cfg.totalSize > 0 -> "COMPLETED"
            else -> "IDLE"
        }
    }
}

object Fmt {
    fun bytes(n: Long): String {
        val d = n.toDouble()
        return when {
            n >= 1L shl 30 -> String.format("%.2f GB", d / (1L shl 30))
            n >= 1L shl 20 -> String.format("%.1f MB", d / (1L shl 20))
            n >= 1L shl 10 -> String.format("%.0f KB", d / (1L shl 10))
            else -> "$n B"
        }
    }

    fun eta(remaining: Long, speed: Double): String {
        if (speed < 1.0 || remaining <= 0) return "--"
        val s = (remaining / speed).toLong()
        return "${s / 3600}h ${(s % 3600) / 60}m"
    }
}
