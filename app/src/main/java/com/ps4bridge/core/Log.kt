package com.ps4bridge.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory log. Every message passes through redact(): query strings of URLs are never stored. */
object Log {
    private val lines = ArrayDeque<String>()
    private const val MAX = 500

    fun redact(s: String): String =
        s.replace(Regex("(https?://[^\\s?\"']+)\\?[^\\s\"']*"), "\$1?[REDACTED]")

    @Synchronized
    fun add(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.add("$t ${redact(msg)}")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun tail(n: Int): String = lines.toList().takeLast(n).joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
