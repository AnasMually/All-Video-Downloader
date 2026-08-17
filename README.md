# All Video Downloader

تطبيق Android أصلي بـ Kotlin وJetpack Compose وMaterial 3 يعتمد على **VideoFlow API** لتحليل روابط الوسائط والحصول على الجودات وروابط المصدر المؤقتة، ثم ينزّل المستخدم الملف مباشرة على جهازه.

> استخدم التطبيق فقط مع المحتوى الذي تملكه أو لديك إذن صريح بتنزيله. التطبيق والـAPI لا يتجاوزان DRM، وقد يتغير دعم بعض المنصات عندما تغيّر آلياتها.

## البنية الحالية

```text
Android
   ↓ URL
VideoFlow PHP API
   ↓
yt-dlp + Deno على الـVPS
   ↓
Metadata + formats + temporary direct URLs
   ↓
Android downloads from source
```

الـAPI المستخدمة افتراضيًا:

```text
https://anasmugally.vps.webdock.cloud/VideoDownloader/api/v1/
```

وتستخدم الواجهات:

- `health.php` لفحص جاهزية الخدمة.
- `extract.php` لاستخراج معلومات الفيديو وخيارات التنزيل.
- `resolve.php` لتجديد رابط الوسائط المؤقت مباشرة قبل بدء التنزيل.

## ما تم حذفه من التطبيق

التطبيق لا يضم بعد الآن:

- `youtubedl-android`.
- Python runtime.
- yt-dlp binary/runtime داخل APK.
- QuickJS runtime.
- تحديث yt-dlp من الهاتف.
- ملفات Cookies الخاصة بمحرك yt-dlp المحلي.
- منطق استخراج الجودات محليًا.
- ABI splits التي كانت مطلوبة بسبب runtime المحلي.
- ExoPlayer وواجهة المشغل الداخلي.

صيانة yt-dlp وتحديث Extractors تتم مركزيًا على الـVPS، لذلك لا يحتاج المستخدم تحديث APK عندما يتغير extractor فقط.

## المزايا

- تحليل YouTube وFacebook وInstagram وX/Twitter وTikTok والمنصات المدعومة بواسطة VideoFlow API.
- عرض الجودات والصيغة والحجم عندما يوفرها المصدر.
- تجديد رابط التنزيل من `resolve.php` فور بدء المهمة لتقليل مشاكل انتهاء صلاحية الروابط.
- تنزيل الملف من المصدر إلى الهاتف، وليس تمرير ملف تطبيق Android عبر VPS.
- إرسال HTTP headers التي يعيدها الـAPI عند طلب رابط الوسائط.
- دعم استئناف التنزيل عبر HTTP Range عندما يدعمه المصدر.
- طابور تنزيلات مع إيقاف مؤقت، استئناف، إلغاء، وإعادة محاولة.
- نسبة تقدم، bytes المنزلة، الحجم، السرعة والوقت المتبقي في الشاشة والإشعار.
- حفظ النتيجة عبر MediaStore داخل `Movies` أو `Music` دون صلاحيات إدارة الملفات.
- إذا أعاد الـAPI فيديو وصوت منفصلين لجودة عالية، ينزلهما التطبيق مباشرة ثم يدمجهما محليًا باستخدام Android MediaMuxer/Media3.
- سجل تنزيلات محلي، وفتح الفيديو أو الصوت عبر مشغل خارجي باستخدام Android `ACTION_VIEW`، ومشاركة الملف الناتج.
- Wi-Fi only، اسم مجلد مخصص، خيارات تسمية، الوضع الداكن والألوان الديناميكية.
- استقبال الروابط عبر نافذة المشاركة.
- واجهة عربية وإنجليزية مع RTL.

## البناء

| المكوّن | الإصدار |
|---|---:|
| Android Gradle Plugin | 8.11.0 |
| Gradle Wrapper | 8.13 |
| Kotlin | 1.9.22 |
| Java source/target | 17 |
| compileSdk / targetSdk | 36 |
| minSdk | 29 |

```bash
./gradlew :app:assembleDebug
```

بعد إزالة runtime المحلي أصبح البناء APK عاديًا واحدًا بدل ملفات منفصلة لكل ABI:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## الصلاحيات

| الصلاحية | السبب |
|---|---|
| `INTERNET` | الاتصال بالـAPI وتنزيل رابط الوسائط المباشر |
| `ACCESS_NETWORK_STATE` | اكتشاف الاتصال وخيار Wi-Fi only |
| `WAKE_LOCK` | إبقاء عملية تنزيل بدأها المستخدم فعالة |
| `POST_NOTIFICATIONS` | إشعارات تقدم التنزيل على Android 13+ |
| `FOREGROUND_SERVICE` | التنزيلات الطويلة |
| `FOREGROUND_SERVICE_DATA_SYNC` | نوع foreground service المطلوب على Android الحديث |

لا يطلب التطبيق `MANAGE_EXTERNAL_STORAGE` أو `WRITE_EXTERNAL_STORAGE`، ولا يحتاج إلى `READ_MEDIA_*` لحفظ التنزيلات.

## الملفات الرئيسية

- `data/VideoFlowApi.kt`: عميل VideoFlow API (`health`, `extract`, `resolve`).
- `data/AppRepository.kt`: إعدادات وسجل التنزيلات عبر DataStore.
- `download/DownloadService.kt`: تنزيل HTTP مباشر من روابط المصدر وإدارة الطابور والتقدم.
- `download/OnDeviceMediaProcessor.kt`: دمج video/audio أو تحويل الصوت عند الحاجة.
- `download/MediaStoreWriter.kt`: حفظ الملف النهائي عبر MediaStore.
- `ui/`: واجهات Material 3؛ تشغيل الملفات يتم عبر مشغل خارجي ولا يوجد Player داخلي.

## التحقق

GitHub Actions ينفذ:

```bash
./gradlew --stacktrace :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

كما يتحقق من عدم رجوع runtimes أو media binaries الثقيلة إلى APK.

## ملاحظات تشغيلية

- روابط الوسائط التي ترجعها المنصات مؤقتة؛ لذلك التطبيق يستدعي `resolve.php` قبل التنزيل.
- بعض المصادر قد تربط رابط الوسائط بعنوان IP أو session محددة؛ عندها قد يحتاج الـAPI أو downloader إلى معالجة خاصة بالمنصة.
- التحديث اليومي لـyt-dlp يتم على السيرفر، وليس داخل التطبيق.
- رابط API موجود في `BuildConfig.VIDEOFLOW_API_BASE_URL` ويمكن تغييره من `app/build.gradle.kts`.
- وضع API key داخل APK ليس سرًا حقيقيًا؛ يفضل rate limiting أو آلية توثيق مناسبة إذا أصبحت الخدمة غير عامة.

## الترخيص

المشروع مرخّص تحت GNU GPL v3. راجع [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) لمكونات الطرف الثالث المستخدمة في تطبيق Android.
