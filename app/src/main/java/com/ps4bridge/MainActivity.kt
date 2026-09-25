package com.ps4bridge

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ps4bridge.core.Cfg
import com.ps4bridge.core.Doctor
import com.ps4bridge.core.Fmt
import com.ps4bridge.core.Log
import com.ps4bridge.core.Progress
import com.ps4bridge.core.RpiClient
import com.ps4bridge.core.Source
import com.ps4bridge.core.SourceError
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var etUrl: EditText
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var etWorkers: EditText
    private lateinit var etChunk: EditText
    private lateinit var etLocal: EditText
    private lateinit var etRetries: EditText
    private lateinit var etBuffer: EditText
    private lateinit var etFtpHost: EditText
    private lateinit var etFtpPort: EditText
    private lateinit var etFtpUser: EditText
    private lateinit var etFtpPassword: EditText
    private lateinit var etFtpPath: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var bar: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastDoctor = ""

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Store.applyToCfg()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        buildUi()
        if (Store.transferActive) {
            Log.add("Recovered task: ${Store.transferFileName}, last PS4 Range=${Store.lastPs4Offset}")
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    // ---- UI -----------------------------------------------------------------------------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(12), 0, 0) }
        fun field(h: String, v: String, number: Boolean = false, uri: Boolean = false) = EditText(this).apply {
            hint = h
            setText(v)
            setSingleLine(true)
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
            if (uri) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        fun button(t: String, fn: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { fn() }
        }

        root.addView(TextView(this).apply { text = "PS4 PKG Bridge"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply {
            text = "يمرّر الملف من الإنترنت إلى الـPS4 عبر هاتفك دون تخزينه. افحص أولًا ثم ابدأ."
            textSize = 12f
        })

        root.addView(label("رابط المصدر (http/https مباشر يدعم Range)"))
        etUrl = field("https://...", Store.sourceUrl, uri = true); root.addView(etUrl)
        root.addView(label("عنوان IP للـPS4"))
        etIp = field("192.168.x.x", Store.ps4Ip); root.addView(etIp)
        root.addView(label("منفذ Remote Package Installer"))
        etPort = field("12800", Store.rpiPort.toString(), number = true); root.addView(etPort)
        root.addView(label("إعدادات متقدمة: الاتصالات المتوازية / حجم الجزء MB / منفذ الوسيط"))
        val adv = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etWorkers = field("8", Store.workers.toString(), number = true)
        etChunk = field("4", Store.chunkMb.toString(), number = true)
        etLocal = field("8000", Store.localPort.toString(), number = true)
        etRetries = field("10", Store.maxRetries.toString(), number = true)
        etBuffer = field("64", Store.maxBufferMb.toString(), number = true)
        for (e in listOf(etWorkers, etChunk, etLocal, etRetries, etBuffer)) adv.addView(e, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(adv)

        root.addView(button("1) فحص المصدر") { doProbe() })
        root.addView(button("2) تشغيل التشخيص (Doctor)") { doDoctor() })
        root.addView(button("3) اختبار قبول الـPS4 للروابط (https/http)") { doRpiTest() })
        root.addView(button("4) ابدأ النقل عبر RPI (الطريقة القديمة)") { doStart() })

        root.addView(TextView(this).apply {
            text = "وضع FTP: URL → هاتف → FTP → ملف PKG على الـPS4"
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(18), 0, 0)
        })
        root.addView(label("IP خادم FTP على الـPS4"))
        etFtpHost = field("192.168.x.x", Store.ftpHost); root.addView(etFtpHost)
        root.addView(label("منفذ FTP"))
        etFtpPort = field("21", Store.ftpPort.toString(), number = true); root.addView(etFtpPort)
        root.addView(label("اسم مستخدم FTP"))
        etFtpUser = field("anonymous", Store.ftpUser); root.addView(etFtpUser)
        root.addView(label("كلمة مرور FTP"))
        etFtpPassword = field("anonymous@", Store.ftpPassword); etFtpPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; root.addView(etFtpPassword)
        root.addView(label("المسار البعيد لملف PKG (مثال: /data/pkg/app.pkg)"))
        etFtpPath = field("/data/pkg/app.pkg", Store.ftpRemotePath); root.addView(etFtpPath)
        root.addView(button("5) ابدأ نقل PKG عبر FTP") { doStartFtp() })
        root.addView(button("إيقاف النقل") { save(); Engine.stopAll(this); toast("تم الإيقاف") })
        root.addView(button("تحديث رابط المصدر أثناء النقل") { doUpdateUrl() })

        tvStatus = TextView(this).apply { textSize = 14f; setPadding(0, dp(14), 0, dp(4)) }
        root.addView(tvStatus)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar)

        root.addView(button("استثناء التطبيق من توفير البطارية") { batteryExempt() })
        root.addView(button("نسخ التقرير والسجل") { copyReport() })

        tvLog = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(tvLog)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun save() {
        Store.sourceUrl = etUrl.text.toString().trim()
        Store.ps4Ip = etIp.text.toString().trim()
        Store.rpiPort = etPort.text.toString().toIntOrNull() ?: 12800
        Store.workers = etWorkers.text.toString().toIntOrNull() ?: 4
        Store.chunkMb = etChunk.text.toString().toIntOrNull() ?: 4
        Store.localPort = etLocal.text.toString().toIntOrNull() ?: 8000
        Store.maxRetries = etRetries.text.toString().toIntOrNull() ?: 10
        Store.maxBufferMb = etBuffer.text.toString().toIntOrNull() ?: 64
        Store.ftpHost = etFtpHost.text.toString().trim()
        Store.ftpPort = etFtpPort.text.toString().toIntOrNull() ?: 21
        Store.ftpUser = etFtpUser.text.toString()
        Store.ftpPassword = etFtpPassword.text.toString()
        Store.ftpRemotePath = etFtpPath.text.toString().trim()
        Store.applyToCfg()
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }

    private fun refresh() {
        val total = Cfg.totalSize
        val pos = Progress.position
        val pct = if (total > 0) (pos * 100 / total).toInt().coerceIn(0, 100) else 0
        bar.progress = pct
        val remaining = if (total > pos) total - pos else 0L
        tvStatus.text = "الحالة: ${Progress.stateText()}\n" +
            "${Fmt.bytes(pos)} / ${Fmt.bytes(total)}  ($pct%)\n" +
            "السرعة: ${Fmt.bytes(Progress.speed.toLong())}/s   ·   المتبقي: ${Fmt.eta(remaining, Progress.speed)}\n" +
            "طلبات الـPS4: ${Progress.requests} · النشطة: ${Progress.ps4Streams} · إعادة المحاولة: ${Progress.retries}\n" +
            "المصدر: ${Fmt.bytes(Progress.sourceSpeed.toLong())}/s · PS4: ${Fmt.bytes(Progress.speed.toLong())}/s\n" +
            "الاستئناف: " + (if (Progress.resumeSeenAt > 0) "مؤكد من ${Progress.resumeSeenAt shr 20} MB" else "غير مؤكد") +
            " · آخر Range: ${if (Progress.lastRequestStart >= 0) Fmt.bytes(Progress.lastRequestStart) else "--"}"
        Progress.sample()
        tvLog.text = Log.tail(70)
    }

    // ---- actions ------------------------------------------------------------------------------
    private fun doProbe() {
        save()
        val url = Store.sourceUrl
        thread {
            try {
                Source.setSourceUrl(url)
                val i = Source.probe(url)
                Log.add("source: ${Fmt.bytes(i.size)} (${i.size} B), Range=${if (i.ranges) "supported" else "NOT supported"}, name=${i.name}")
                toast(if (i.ranges) "المصدر سليم: ${Fmt.bytes(i.size)}" else "المصدر لا يدعم Range")
            } catch (e: SourceError) {
                Log.add("source error: HTTP ${e.code} ${e.message}")
                toast(if (e.expired) "الرابط منتهي أو مرفوض (HTTP ${e.code})" else "المصدر: ${e.message}")
            } catch (e: Exception) {
                Log.add("source error: ${e.message}")
                toast("تعذّر الوصول: ${e.message}")
            }
        }
    }

    private fun doDoctor() {
        save()
        toast("يعمل التشخيص… قد يستغرق دقيقة")
        thread {
            val r = Doctor.run(Store.ps4Ip, Store.rpiPort, Engine.phoneIp(this), Store.sourceUrl)
            lastDoctor = Doctor.report(r)
            val fails = r.count { it.status == "FAIL" }
            toast(if (fails == 0) "انتهى التشخيص: لا أخطاء" else "انتهى التشخيص: $fails فشل (راجع السجل)")
        }
    }

    private fun doRpiTest() {
        save()
        toast("يرسل رابطين تجريبيين إلى الـPS4؛ قد يظهر إشعاران فاشلان يمكن حذفهما")
        thread {
            RpiClient.acceptanceTest(Store.ps4Ip, Store.rpiPort).forEach { Log.add("RPI $it") }
        }
    }

    private fun doStart() {
        save()
        Engine.startTransfer(this) { ok, msg -> toast((if (ok) "✔ " else "✘ ") + msg); Log.add((if (ok) "OK: " else "FAILED: ") + msg) }
    }

    private fun doStartFtp() {
        save()
        Engine.startFtpTransfer(this) { ok, msg ->
            toast((if (ok) "✔ " else "✘ ") + msg)
            Log.add((if (ok) "OK: " else "FAILED: ") + msg)
        }
    }

    private fun doUpdateUrl() {
        save()
        Engine.updateSource(etUrl.text.toString()) { ok, msg -> toast((if (ok) "✔ " else "✘ ") + msg); Log.add(msg) }
    }

    private fun batteryExempt() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("التطبيق مستثنى بالفعل")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun copyReport() {
        val header = "PS4 PKG Bridge report\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Source size: ${Cfg.totalSize}\n\n"
        val text = Log.redact(header + lastDoctor + "\n\n--- log ---\n" + Log.text())
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("report", text))
        toast("نُسخ التقرير (بلا أي رابط موقَّع)")
    }
}
