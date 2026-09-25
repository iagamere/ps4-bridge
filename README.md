# PS4 PKG Bridge (Android) — v1.2 — reliability + resume + network hardening

يمرّر ملف PKG كبيرًا من رابط http/https إلى PS4 (GoldHEN + Remote Package Installer) عبر هاتفك،
**دون تخزين الملف على الهاتف** ودون حاسوب.

## كيف يعمل
الهاتف يشغّل خادم HTTP محليًا يدعم Range. تطلب الـPS4 جزءًا من الملف، فيجلبه الهاتف من المصدر
بعدة اتصالات متوازية ويعيده لها بالترتيب. الخادم لا يخزن الملف، والـPS4 هي مصدر الحقيقة لموضع النقل عبر طلبات `Range`.
التطبيق الآن يسجل طلبات الـPS4 ويفرق بين الطلب المتسلسل الطبيعي وبين دليل الاستئناف بعد انقطاع اتصال سابق.

## البناء (بلا حاسوب: من Termux + GitHub Actions)
```
pkg install git gh unzip -y
termux-setup-storage
cd ~ && unzip -o ~/storage/downloads/ps4-bridge.zip && cd ps4-bridge
git config --global user.name "name" && git config --global user.email "you@example.com"
git init -b main && git add . && git commit -m "init"
gh auth login
gh repo create ps4-bridge --public --source=. --push
```
عند `gh auth login`: GitHub.com ← HTTPS ← Yes ← Login with a web browser، ثم افتح
`https://github.com/login/device` في متصفح الهاتف وأدخل الرمز المعروض.

بعد الدفع يبدأ البناء تلقائيًا (5 إلى 10 دقائق). تابعه بـ`gh run watch` أو من تبويب Actions،
ثم حمّل الـAPK:
```
gh release download latest -p app-debug.apk -D ~/storage/downloads --clobber
```
وثبّته من مدير الملفات (اسمح بالتثبيت من مصادر غير معروفة).

## الاستخدام
1. الصق رابط المصدر المباشر (من 1DM) وعنوان IP للـPS4 (المنفذ 12800 افتراضيًا).
2. "فحص المصدر" ثم "تشغيل التشخيص". **لا تبدأ قبل أن تنجح الاختبارات.**
3. "اختبار قبول الـPS4 للروابط" يبيّن هل نسختك من RPI تقبل https (يرسل رابطين تجريبيين).
4. افتح Remote Package Installer على الـPS4 واتركه، ثم "ابدأ النقل".
5. إن انتهى الرابط الموقَّع: الصق رابطًا جديدًا واضغط "تحديث رابط المصدر" (يُرفض إن اختلف حجم الملف).
6. اضغط "استثناء التطبيق من توفير البطارية" مرة واحدة، وأبقِ الهاتف موصولًا بالشاحن.

## ما اختُبر فعليًا (على JVM مع خادم مصدر تجريبي)
- الملف كاملًا يصل مطابقًا بالبايت (MD5) بذاكرة أقصاها 96 MB لملف 100 MB: لا تخزين ولا تضخم في الذاكرة.
- Range من منتصف الملف ومن نهايته (`bytes=N-`) ولاحقة (`bytes=-N`) والطلب غير الصالح (416) وHEAD وPOST (405).
- ترتيب الأجزاء عبر عدة أجزاء (CRC مطابق) وعدة اتصالات متزامنة.
- انتهاء الرابط (403) أثناء النقل: ينتظر رابطًا جديدًا ثم يكمل بنفس البيانات.
- قطع اتصال العميل: تتوقف المهام ولا يبقى شيء عالقًا. وإعادة التوجيه (302).

## ما لم يُختبر (لا تفترض أنه يعمل)
- التطبيق نفسه على جهاز أندرويد حقيقي: الشيفرة تُترجم مقابل مكتبة أندرويد، لكن لم تُشغَّل على هاتف.
- الاتصال بـRPI الفعلي: يُستخدم `/api/install` فقط. **أوامر الإيقاف/الاستئناف/الإلغاء في RPI غير مستخدمة**
  لأن أسماءها لم تُتحقق على نسختك (TODO). زر "إيقاف" يوقف الوسيط فقط.
- هل تستأنف الـPS4 بـRange فعلًا: يقرره سجل التطبيق (سطر "PS4 requested a range starting at…").
- FTP غير مضمَّن عمدًا.

## ملاحظات v1.2 مهمة
- زر إيقاف التطبيق يوقف الوسيط؛ لا توجد أوامر RPI غير موثقة لإجبار PS4 على Pause/Resume.
- `START_STICKY` لا يستطيع تجاوز Force Stop أو قيود النظام؛ وهو وسيلة للتعافي من قتل عادي للخدمة، وليس ضمانًا مطلقًا.
- إذا انتهى رابط R2، يجب إدخال رابط جديد يدويًا من 1DM؛ التطبيق لا يستطيع تخمين توقيع URL جديد.

## حدود لا يحلّها أي تطبيق
- الملف يدخل الهاتف ويخرج منه عبر الواي فاي نفسه: السرعة قد تقل عن سرعة إنترنتك.
- روابط vikingfile المؤقتة تُنسخ يدويًا (لا طريقة موثّقة لتوليدها آليًا).
- تنبيه: النسخة الموقّعة بتوقيع debug (لا تصلح لنشر على متجر، وتصلح للاستخدام الشخصي).

## هيكل المشروع
- `core/` شيفرة JVM صرفة (الوسيط، التحميل بالتوازي، التشخيص، RPI): مختبَرة خارج أندرويد.
- `Engine.kt` `TransferService.kt` `MainActivity.kt` `Store.kt` طبقة أندرويد.
- `targetSdk = 34` عمدًا: التطبيقات المستهدفة 35+ تخضع لمهلة نحو 6 ساعات لخدمات المقدمة من نوع dataSync.


## v1.2 changes
- Safe default: 4 source workers, 4 MiB chunks, bounded 64 MiB in-flight buffer.
- Strict source HTTP classification: transient 408/429/5xx errors retry; permanent 400/404 and unsupported Range fail clearly; signed 401/403/410 URLs enter source-replacement waiting when identifiable.
- Source `Content-Range` must match the requested start/end **and total file size**.
- Separate source throughput and effective PS4 throughput counters.
- Persist last PS4 Range offset/end and task state so a process restart can display the last known resume position.
- Wi-Fi loss pauses the local proxy to avoid silently switching a long transfer to mobile data; Wi-Fi return restarts the proxy.
- Foreground notification shows effective speed and ETA.
- Source ETag/Last-Modified metadata is retained for diagnostics.

### 1.2 verification status
تم تعديل شجرة المصدر، لكن بيئة التنفيذ الحالية لا تحتوي على Android SDK/Gradle قابل للتشغيل، لذلك لم أزعم نجاح APK build هنا. مسار GitHub Actions في المستودع هو مسار البناء المدعوم.

## FTP mode (1.2)

A second transfer path is now integrated:

`HTTP/HTTPS PKG URL -> Android phone -> PS4 FTP server -> remote PKG file`

The FTP mode is intended for a PS4 FTP service that exposes the destination directory. The destination is configurable in the app, for example `/data/pkg/app.pkg`.

Features:
- streams directly without loading the PKG into RAM;
- checks the existing remote file size before transfer;
- resumes with HTTP `Range` + FTP `REST/STOR` when both sides support it;
- retries after transient connection failures;
- verifies the final remote file size;
- runs inside the Android foreground service with a Wi-Fi/wake lock;
- keeps the remote file as the resume source of truth, so restarting the app/service can continue from the existing byte count.

The original RPI/proxy mode remains available as a separate button.
