package com.ps4bridge

import android.content.Context
import android.content.SharedPreferences
import com.ps4bridge.core.Cfg

/** Persistent settings and recovery metadata. PS4 Range remains the source of truth for resume. */
object Store {
    private var p: SharedPreferences? = null

    fun init(c: Context) { if (p == null) p = c.applicationContext.getSharedPreferences("bridge", Context.MODE_PRIVATE) }
    private fun sp(): SharedPreferences = p ?: error("Store not initialised")
    private fun edit(block: SharedPreferences.Editor.() -> Unit) { sp().edit().apply(block).commit() }
    private fun editAsync(block: SharedPreferences.Editor.() -> Unit) { sp().edit().apply(block).apply() }

    var sourceUrl: String get() = sp().getString("url", "") ?: "" set(v) = edit { putString("url", v) }
    var ps4Ip: String get() = sp().getString("ps4ip", "") ?: "" set(v) = edit { putString("ps4ip", v) }
    var rpiPort: Int get() = sp().getInt("rpiport", 12800) set(v) = edit { putInt("rpiport", v) }
    var workers: Int get() = sp().getInt("workers", 4) set(v) = edit { putInt("workers", v) }
    var chunkMb: Int get() = sp().getInt("chunkmb", 4) set(v) = edit { putInt("chunkmb", v) }
    var localPort: Int get() = sp().getInt("localport", 8000) set(v) = edit { putInt("localport", v) }
    var maxRetries: Int get() = sp().getInt("maxretries", 10) set(v) = edit { putInt("maxretries", v) }
    var maxBufferMb: Int get() = sp().getInt("buffer_mb", 64) set(v) = edit { putInt("buffer_mb", v) }

    var transferMode: String get() = sp().getString("transfer_mode", "RPI") ?: "RPI" set(v) = edit { putString("transfer_mode", v) }
    var ftpHost: String get() = sp().getString("ftp_host", "") ?: "" set(v) = edit { putString("ftp_host", v) }
    var ftpPort: Int get() = sp().getInt("ftp_port", 21) set(v) = edit { putInt("ftp_port", v) }
    var ftpUser: String get() = sp().getString("ftp_user", "anonymous") ?: "anonymous" set(v) = edit { putString("ftp_user", v) }
    var ftpPassword: String get() = sp().getString("ftp_password", "anonymous@") ?: "anonymous@" set(v) = edit { putString("ftp_password", v) }
    var ftpRemotePath: String get() = sp().getString("ftp_path", "/data/pkg/app.pkg") ?: "/data/pkg/app.pkg" set(v) = edit { putString("ftp_path", v) }

    var transferActive: Boolean get() = sp().getBoolean("transfer_active", false) set(v) = edit { putBoolean("transfer_active", v) }
    var transferSize: Long get() = sp().getLong("transfer_size", 0L) set(v) = edit { putLong("transfer_size", v) }
    var transferFileName: String get() = sp().getString("transfer_file", "app.pkg") ?: "app.pkg" set(v) = edit { putString("transfer_file", v) }
    var transferStartedAt: Long get() = sp().getLong("transfer_started", 0L) set(v) = edit { putLong("transfer_started", v) }
    var lastPs4Offset: Long get() = sp().getLong("last_ps4_offset", -1L) set(v) = edit { putLong("last_ps4_offset", v) }
    var lastPs4End: Long get() = sp().getLong("last_ps4_end", -1L) set(v) = edit { putLong("last_ps4_end", v) }
    var lastActivity: Long get() = sp().getLong("last_activity", 0L) set(v) = edit { putLong("last_activity", v) }
    var taskState: String get() = sp().getString("task_state", "IDLE") ?: "IDLE" set(v) = edit { putString("task_state", v) }
    var sourceEtag: String get() = sp().getString("source_etag", "") ?: "" set(v) = edit { putString("source_etag", v) }
    var sourceLastModified: String get() = sp().getString("source_last_modified", "") ?: "" set(v) = edit { putString("source_last_modified", v) }

    fun applyToCfg() {
        Cfg.workers = workers.coerceIn(1, 8)
        Cfg.chunkBytes = chunkMb.coerceIn(2, 8) * 1024L * 1024L
        Cfg.localPort = localPort.coerceIn(1024, 65535)
        Cfg.maxRetries = maxRetries.coerceIn(1, 20)
        Cfg.maxInFlightBytes = maxBufferMb.coerceIn(16, 128) * 1024L * 1024L
    }

    fun recordPs4Request(start: Long, end: Long) {
        // This can run on the PS4 socket thread. Never block it on a synchronous disk commit.
        editAsync {
            putLong("last_ps4_offset", start)
            putLong("last_ps4_end", end)
            putLong("last_activity", System.currentTimeMillis())
            putString("task_state", "DOWNLOADING")
        }
    }

    fun markState(state: String) { edit { putString("task_state", state); putLong("last_activity", System.currentTimeMillis()) } }
}
