package com.ps4bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.ps4bridge.core.Cfg
import com.ps4bridge.core.Fmt
import com.ps4bridge.core.FtpPkgTransfer
import com.ps4bridge.core.Log
import com.ps4bridge.core.Progress
import com.ps4bridge.core.Source
import com.ps4bridge.core.ProxyHolder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Foreground service so Android does not kill the proxy when the screen turns off or the app is closed. */
class TransferService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var timer: ScheduledExecutorService? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var ftpThread: Thread? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Engine.ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        stopping = false
        Store.init(this)
        Store.applyToCfg()
        if (intent?.action == Engine.ACTION_FTP_START || Store.transferMode == "FTP") {
            startAsForeground()
            acquireLocks()
            registerNetworkCallback()
            startFtpWorker()
            return START_STICKY
        }
        if (Store.transferActive && Store.sourceUrl.isNotBlank()) {
            Source.setSourceUrl(Store.sourceUrl)
            if (Store.transferSize > 0) Cfg.totalSize = Store.transferSize
            if (Store.transferFileName.isNotBlank()) Cfg.fileName = Store.transferFileName
            Progress.onPs4Request = { a, b -> Store.recordPs4Request(a, b) }
            if (Store.lastPs4Offset >= 0) Progress.position = Store.lastPs4Offset
            Log.add("service restored transfer metadata; PS4 still owns the Range offset ${Store.lastPs4Offset}")
        }
        startAsForeground()
        acquireLocks()
        registerNetworkCallback()
        try {
            ProxyHolder.ensure()
        } catch (e: Exception) {
            Log.add("proxy failed to start: ${e.message}")
            shutdown()
            return START_NOT_STICKY
        }
        if (timer == null) {
            timer = Executors.newSingleThreadScheduledExecutor().also { t ->
                t.scheduleAtFixedRate({
                    try {
                        Progress.sample()
                        if (wake?.isHeld != true) wake?.acquire()
                        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
                    } catch (_: Exception) {}
                }, 1, 2, TimeUnit.SECONDS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        ftpThread?.interrupt()
        ftpThread = null
        unregisterNetworkCallback()
        releaseLocks()
        timer?.shutdownNow()
        super.onDestroy()
    }

    private fun shutdown() {
        stopping = true
        Log.add("service stopping")
        timer?.shutdownNow()
        timer = null
        ftpThread?.interrupt()
        ftpThread = null
        ProxyHolder.stop()
        if (Store.taskState != "FAILED") Store.markState("CANCELLED")
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startFtpWorker() {
        if (ftpThread?.isAlive == true) return
        ftpThread = Thread({
            try {
                val cfg = FtpPkgTransfer.Config(
                    host = Store.ftpHost.trim(),
                    port = Store.ftpPort.coerceIn(1, 65535),
                    username = Store.ftpUser,
                    password = Store.ftpPassword,
                    remotePath = Store.ftpRemotePath.trim(),
                    connectTimeoutMs = Cfg.connectTimeoutMs,
                    readTimeoutMs = Cfg.readTimeoutMs,
                    bufferBytes = 1024 * 1024,
                    maxRetries = Store.maxRetries.coerceIn(1, 20)
                )
                Store.markState("FTP_DOWNLOADING")
                Progress.stateOverride = "FTP_DOWNLOADING"
                Log.add("FTP target: ${cfg.host}:${cfg.port}${cfg.remotePath}")
                val result = FtpPkgTransfer.transfer(Store.sourceUrl, cfg,
                    onProgress = { written, total, speed ->
                        Progress.position = written
                        Progress.sent.set(written)
                        Progress.sourceRead.set(written)
                        Progress.speed = speed.toDouble()
                        Progress.sourceSpeed = speed.toDouble()
                    },
                    shouldStop = { stopping }
                )
                if (result.success) {
                    Progress.position = result.total
                    Progress.stateOverride = "COMPLETED"
                    Store.transferActive = false
                    Store.markState("COMPLETED")
                    Log.add("FTP transfer completed: ${result.bytes} bytes")
                } else if (!stopping) {
                    Progress.stateOverride = "FAILED"
                    Store.transferActive = false
                    Store.markState("FAILED")
                    Log.add("FTP transfer failed: ${result.message}; remote=${result.bytes}/${result.total}")
                }
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
                if (!stopping) stopSelf()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                if (!stopping) {
                    Store.transferActive = false
                    Store.markState("FAILED")
                    Progress.stateOverride = "FAILED"
                    Log.add("FTP worker error: ${e.message}")
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
                    stopSelf()
                }
            }
        }, "ftp-transfer")
        ftpThread?.isDaemon = true
        ftpThread?.start()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "PS4 transfer", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val total = Cfg.totalSize
        val pos = Progress.position
        val pct = if (total > 0) (pos * 100 / total).toInt() else 0
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(Engine.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PS4 Bridge: ${Progress.stateText()}")
            .setContentText("${Fmt.bytes(pos)} / ${Fmt.bytes(total)} · ${Fmt.bytes(Progress.speed.toLong())}/s · ETA ${Fmt.eta((total - pos).coerceAtLeast(0), Progress.speed)}")
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build())
            .build()
    }


    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                val current = cm.activeNetwork
                val caps = current?.let { cm.getNetworkCapabilities(it) }
                val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                if (!wifi && Store.transferActive) {
                    Progress.stateOverride = "WAITING_FOR_WIFI"
                    Store.markState("WAITING_FOR_WIFI")
                    Log.add("Wi-Fi unavailable; transfer waiting for Wi-Fi")
                    if (Store.transferMode != "FTP") ProxyHolder.stop()
                }
            }
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && Store.transferActive) {
                    Progress.stateOverride = null
                    Store.markState(if (Store.transferMode == "FTP") "FTP_DOWNLOADING" else "RECONNECTING")
                    if (Store.transferMode != "FTP") {
                        try { ProxyHolder.ensure(); Log.add("Wi-Fi restored; local proxy restarted") } catch (e: Exception) { Log.add("proxy restart failed: ${e.message}") }
                    }
                }
            }
        }
        networkCallback = cb
        try { cm.registerDefaultNetworkCallback(cb) } catch (e: Exception) { Log.add("network callback unavailable: ${e.message}") }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        try { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wake == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ps4bridge:transfer").apply { setReferenceCounted(false) }
        }
        if (wake?.isHeld != true) wake?.acquire()
        if (wifi == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = wm.createWifiLock(mode, "ps4bridge:wifi").apply { setReferenceCounted(false) }
        }
        try { if (wifi?.isHeld != true) wifi?.acquire() } catch (e: Exception) { Log.add("wifi lock: ${e.message}") }
    }

    private fun releaseLocks() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        try { if (wifi?.isHeld == true) wifi?.release() } catch (_: Exception) {}
    }

    companion object {
        private const val CHANNEL = "transfer"
        private const val NOTIF_ID = 42
    }
}
