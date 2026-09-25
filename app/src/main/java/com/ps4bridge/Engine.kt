package com.ps4bridge

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ps4bridge.core.Cfg
import com.ps4bridge.core.Doctor
import com.ps4bridge.core.Log
import com.ps4bridge.core.Progress
import com.ps4bridge.core.ProxyHolder
import com.ps4bridge.core.RpiClient
import com.ps4bridge.core.Source
import com.ps4bridge.core.SourceError
import java.net.Inet4Address
import kotlin.concurrent.thread

/** Orchestration: check everything first, start the proxy service, then (and only then) ask the PS4 to install. */
object Engine {
    const val ACTION_STOP = "com.ps4bridge.STOP"
    const val ACTION_FTP_START = "com.ps4bridge.FTP_START"

    @Suppress("DEPRECATION")
    fun phoneIp(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun ipOf(n: Network): String? {
            val lp = cm.getLinkProperties(n) ?: return null
            return lp.linkAddresses.map { it.address }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val ip = ipOf(n)
                if (ip != null) return ip
            }
        }
        val a = cm.activeNetwork
        return if (a != null) ipOf(a) else null
    }

    private fun failStart(ctx: Context, message: String, stopService: Boolean = false) {
        Store.init(ctx.applicationContext)
        Store.transferActive = false
        Store.markState("FAILED")
        Progress.onPs4Request = null
        if (stopService) stopAll(ctx)
    }

    fun startTransfer(ctx: Context, cb: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        thread(name = "start-transfer") {
            try {
                Store.applyToCfg()
                val url = Store.sourceUrl.trim()
                val ip = Store.ps4Ip.trim()
                val port = Store.rpiPort
                if (url.isEmpty()) return@thread cb(false, "الصق رابط المصدر أولًا")
                if (ip.isEmpty()) return@thread cb(false, "اكتب عنوان IP الخاص بالـPS4")

                Progress.reset()
                Store.transferMode = "RPI"
                Log.add("=== start transfer ===")
                Source.setSourceUrl(url)
                val info = try {
                    Source.probe(url)
                } catch (e: SourceError) {
                    return@thread cb(false, if (e.expired) "الرابط منتهي أو مرفوض (HTTP ${e.code}). انسخ رابطًا جديدًا." else "المصدر: ${e.message}")
                } catch (e: Exception) {
                    return@thread cb(false, "تعذّر الوصول إلى المصدر: ${e.message}")
                }
                if (!info.ranges) return@thread cb(false, "هذا المصدر لا يدعم Range (يرد 200 لا 206)، فلا يصلح لهذه الطريقة.")
                Cfg.totalSize = info.size
                Cfg.fileName = info.name
                Store.sourceEtag = info.etag ?: ""
                Store.sourceLastModified = info.lastModified ?: ""
                Progress.stateOverride = "STARTING"
                Store.transferSize = info.size
                Store.transferFileName = info.name
                Store.transferStartedAt = System.currentTimeMillis()
                Store.transferActive = true
                Store.taskState = "STARTING"
                Progress.onPs4Request = { a, b -> Store.recordPs4Request(a, b) }
                Log.add("source ok: ${info.size} bytes, Range supported")

                val phone = phoneIp(app) ?: run {
                    failStart(app, "لم أجد عنوان الهاتف على الواي فاي.")
                    return@thread cb(false, "لم أجد عنوان الهاتف على الواي فاي. اتصل بنفس شبكة الـPS4.")
                }
                if (!RpiClient.tcpOk(ip, port)) {
                    failStart(app, "RPI is unreachable")
                    return@thread cb(false, "لا أستطيع الاتصال بـ$ip:$port. افتح Remote Package Installer على الـPS4 وتأكد من الشبكة.")
                }

                app.startForegroundService(Intent(app, TransferService::class.java))
                var waited = 0
                while (ProxyHolder.server?.running != true && waited < 50) { Thread.sleep(100); waited++ }
                if (ProxyHolder.server?.running != true) {
                    Store.transferActive = false
                    Store.markState("FAILED")
                    stopAll(app)
                    return@thread cb(false, "تعذّر تشغيل الوسيط المحلي (المنفذ ${Cfg.localPort} مشغول؟). غيّره من الإعدادات المتقدمة.")
                }
                val err = Doctor.selfTest()
                if (err != null) {
                    stopAll(app)
                    return@thread cb(false, "فشل الاختبار الذاتي: $err")
                }

                val local = "http://$phone:${Cfg.localPort}/app.pkg"
                Log.add("asking the PS4 to install from $local")
                val reply = RpiClient.install(ip, port, local)
                Log.add("RPI answered: ${reply.body.trim().take(200)}")
                if (reply.ok) {
                    Progress.stateOverride = null
                    Store.taskState = "DOWNLOADING"
                    cb(true, "أُرسلت المهمة إلى الـPS4. تابع التقدم هنا وفي إشعارات الـPS4.")
                } else {
                    Store.transferActive = false
                    stopAll(app)
                    cb(false, if (reply.rejectedAtParse) "الـPS4 رفضت الرابط: ${reply.body.trim().take(120)}" else "ردّ RPI: ${reply.body.trim().take(160)}")
                }
            } catch (e: Exception) {
                Log.add("start failed: ${e.javaClass.simpleName}: ${e.message}")
                failStart(app, e.message ?: "unknown start failure", stopService = true)
                cb(false, "خطأ: ${e.message}")
            }
        }
    }

    /** Swap the signed source URL while a transfer is running. Progress is kept (the PS4 owns the offset). */

    fun startFtpTransfer(ctx: Context, cb: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        thread(name = "start-ftp-transfer") {
            try {
                Store.applyToCfg()
                val url = Store.sourceUrl.trim()
                val host = Store.ftpHost.trim()
                val path = Store.ftpRemotePath.trim()
                if (url.isEmpty()) return@thread cb(false, "الصق رابط المصدر أولًا")
                if (host.isEmpty()) return@thread cb(false, "اكتب IP خادم FTP الخاص بالـPS4")
                if (path.isEmpty()) return@thread cb(false, "اكتب مسار ملف PKG على FTP")

                Progress.reset()
                Progress.stateOverride = "STARTING_FTP"
                Log.add("=== start FTP transfer ===")
                Source.setSourceUrl(url)
                val info = try { Source.probe(url) } catch (e: SourceError) {
                    return@thread cb(false, if (e.expired) "الرابط منتهي أو مرفوض (HTTP ${e.code})" else "المصدر: ${e.message}")
                }
                if (!info.ranges) return@thread cb(false, "المصدر لا يدعم Range؛ لا يمكن ضمان الاستئناف عبر FTP.")
                Cfg.totalSize = info.size
                Cfg.fileName = info.name
                Store.transferSize = info.size
                Store.transferFileName = info.name
                Store.transferStartedAt = System.currentTimeMillis()
                Store.transferActive = true
                Store.taskState = "FTP_DOWNLOADING"
                Store.transferMode = "FTP"

                app.startForegroundService(Intent(app, TransferService::class.java).setAction(ACTION_FTP_START))
                cb(true, "بدأ نقل PKG عبر FTP إلى $path")
            } catch (e: Exception) {
                Log.add("FTP start failed: ${e.javaClass.simpleName}: ${e.message}")
                failStart(app, e.message ?: "FTP start failure")
                cb(false, "خطأ: ${e.message}")
            }
        }
    }

    fun updateSource(newUrl: String, cb: (Boolean, String) -> Unit) {
        thread(name = "update-source") {
            try {
                val info = Source.probe(newUrl.trim())
                if (!info.ranges) return@thread cb(false, "الرابط الجديد لا يدعم Range")
                val old = Cfg.totalSize
                if (old > 0 && info.size != old) {
                    return@thread cb(false, "حجم الملف الجديد (${info.size}) يختلف عن السابق ($old). لم أستبدل الرابط لأن الاستئناف سيفسد الملف.")
                }
                Cfg.totalSize = info.size
                Store.sourceEtag = info.etag ?: ""
                Store.sourceLastModified = info.lastModified ?: ""
                Store.sourceUrl = newUrl.trim()
                Source.setSourceUrl(newUrl.trim())
                Store.transferActive = true
                Store.taskState = "RESUMING"
                Progress.stateOverride = null
                Log.add("source URL replaced (size matches). Transfer continues.")
                cb(true, "تم تحديث الرابط. سيستمر النقل من حيث توقف.")
            } catch (e: SourceError) {
                cb(false, if (e.expired) "الرابط الجديد مرفوض (HTTP ${e.code})" else "الرابط الجديد: ${e.message}")
            } catch (e: Exception) {
                cb(false, "تعذّر فحص الرابط الجديد: ${e.message}")
            }
        }
    }

    fun stopAll(ctx: Context) {
        val app = ctx.applicationContext
        Store.init(app)
        Store.transferActive = false
        Store.markState("CANCELLED")
        Progress.onPs4Request = null
        try {
            app.startService(Intent(app, TransferService::class.java).setAction(ACTION_STOP))
        } catch (e: Exception) {
            Log.add("stop service: ${e.message}")
        }
        ProxyHolder.stop()
    }
}
