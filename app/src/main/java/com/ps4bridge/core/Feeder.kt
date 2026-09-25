package com.ps4bridge.core

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fetches one PS4 Range using several source Range requests and writes them strictly in order.
 * The number of prefetched chunks is bounded by both worker count and a global memory budget.
 */
object Feeder {
    private var lastExpiredLog = 0L

    fun stream(start: Long, end: Long, out: OutputStream, stop: AtomicBoolean, countStats: Boolean = true) {
        val workers = Cfg.workers.coerceIn(1, 32)
        val chunk = Cfg.chunkBytes.coerceIn(64 * 1024L, 32L * 1024 * 1024)
        val memoryCap = Cfg.maxInFlightBytes.coerceIn(chunk, 256L * 1024 * 1024)
        val maxQueued = minOf(workers + 2, maxOf(1, (memoryCap / chunk).toInt()))
        val exec = Executors.newFixedThreadPool(workers)
        val q = ArrayDeque<Pair<Long, Future<ByteArray>>>()
        var next = start
        try {
            while ((next <= end || q.isNotEmpty()) && !stop.get()) {
                while (next <= end && q.size < maxQueued && !stop.get()) {
                    val a = next
                    val b = minOf(a + chunk - 1, end)
                    q.add(a to exec.submit(Callable { fetch(a, b, stop) }))
                    next = b + 1
                }
                val (a, future) = q.removeFirst()
                val data = try {
                    future.get()
                } catch (e: ExecutionException) {
                    throw (e.cause ?: e)
                }
                if (stop.get()) throw IOException("stopped")
                out.write(data)
                if (countStats) {
                    Progress.sent.addAndGet(data.size.toLong())
                    Progress.noteDelivered(a, a + data.size - 1)
                }
            }
            if (!stop.get()) out.flush()
        } finally {
            q.forEach { it.second.cancel(true) }
            exec.shutdownNow()
        }
    }

    /** Retries transient failures a bounded number of times. Expired signed URLs wait for a replacement. */
    fun fetch(a: Long, b: Long, stop: AtomicBoolean): ByteArray {
        var attempt = 0
        while (!stop.get()) {
            val urlNow = Source.url
            val generation = Source.generation()
            try {
                val data = readRange(urlNow, a, b, stop)
                // If the user rotated a signed URL while this request was in flight, never
                // mix bytes from the old and new source generations. Discard and refetch.
                if (generation != Source.generation()) {
                    Log.add("source URL changed during chunk ${a}-${b}; discarding old-source bytes")
                    continue
                }
                return data
            } catch (e: SourceError) {
                if (e.expired) {
                    waitForNewUrl(urlNow, e.code, stop)
                    attempt = 0
                    continue
                }
                if (!e.retryable) throw e
                attempt++
                if (!retryOrFail(attempt, e, stop)) throw e
            } catch (e: IOException) {
                if (stop.get()) break
                attempt++
                if (!retryOrFail(attempt, e, stop)) throw e
            }
        }
        throw IOException("stopped")
    }

    private fun retryOrFail(attempt: Int, e: Exception, stop: AtomicBoolean): Boolean {
        if (attempt > Cfg.maxRetries.coerceIn(1, 20)) {
            Log.add("source retry limit reached (${Cfg.maxRetries}): ${e.message}")
            return false
        }
        Progress.retries.incrementAndGet()
        if (attempt <= 3 || attempt == Cfg.maxRetries || attempt % 5 == 0) {
            Log.add("source error (attempt $attempt/${Cfg.maxRetries}): ${e.message}")
        }
        val ms = minOf(1000L shl minOf(attempt - 1, 4), 15_000L)
        var waited = 0L
        while (waited < ms && !stop.get()) {
            try { Thread.sleep(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            waited += 250
        }
        return !stop.get()
    }

    private fun waitForNewUrl(old: String, code: Int, stop: AtomicBoolean) {
        Progress.waitingNewUrl.incrementAndGet()
        try {
            synchronized(this) {
                val now = System.currentTimeMillis()
                if (now - lastExpiredLog > 5000) {
                    lastExpiredLog = now
                    Log.add("Source returned HTTP $code: source URL needs replacement. Paste a NEW link; PS4 Range state is kept.")
                }
            }
            while (!stop.get() && Source.url == old) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        } finally {
            Progress.waitingNewUrl.decrementAndGet()
        }
    }

    private fun readRange(url: String, a: Long, b: Long, stop: AtomicBoolean): ByteArray {
        val c = Source.open(url, a, b)
        try {
            val code = c.responseCode
            val classified = Source.classify(c, url)
            if (classified != null) throw classified
            if (code != 206) throw SourceError(code, "expected 206, got $code")
            val cr = c.getHeaderField("Content-Range") ?: ""
            val m = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").find(cr.trim())
            if (m == null || m.groupValues[1].toLong() != a || m.groupValues[2].toLong() != b || m.groupValues[3].toLong() != Cfg.totalSize) {
                throw SourceError(code, "unexpected Content-Range: $cr (wanted $a-$b)")
            }
            val len = (b - a + 1).toInt()
            val buf = ByteArray(len)
            var off = 0
            c.inputStream.use { ins ->
                while (off < len) {
                    if (stop.get()) throw IOException("stopped")
                    val n = ins.read(buf, off, len - off)
                    if (n < 0) throw IOException("short read at ${a + off}")
                    if (n == 0) continue
                    off += n
                    Progress.sourceRead.addAndGet(n.toLong())
                }
            }
            return buf
        } finally {
            c.disconnect()
        }
    }
}
