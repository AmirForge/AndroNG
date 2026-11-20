![AndroNG](https://raw.githubusercontent.com/AmirForge/AndroNG/main/docs/assets/androng-galaxy-banner.png)

## 🌌 AndroNG 1.5.0 · Build 775

<div dir="rtl">

### فارسی ✨

همان تجربهٔ کهکشانی AndroNG، این بار با خروجی‌های جداگانه برای هر چهار معماری و یک نسخهٔ Universal.

**📱 حداقل نسخهٔ اندروید برای همهٔ فایل‌ها: اندروید ۹**

- **[ARM64 — بیشتر گوشی‌های جدید](https://github.com/AmirForge/AndroNG/releases/download/v1.5.0-775/AndroNG-1.5.0-775-arm64-v8a-release.apk)**
- **[ARM32 — armeabi-v7a](https://github.com/AmirForge/AndroNG/releases/download/v1.5.0-775/AndroNG-1.5.0-775-armeabi-v7a-release.apk)**
- **[x86 — اینتل سی‌ودوبیتی](https://github.com/AmirForge/AndroNG/releases/download/v1.5.0-775/AndroNG-1.5.0-775-x86-release.apk)**
- **[x86_64 — اینتل شصت‌وچهاربیتی](https://github.com/AmirForge/AndroNG/releases/download/v1.5.0-775/AndroNG-1.5.0-775-x86_64-release.apk)**
- **[Universal — همهٔ معماری‌ها در یک فایل](https://github.com/AmirForge/AndroNG/releases/download/v1.5.0-775/AndroNG-1.5.0-775-universal-release.apk)**

اگر نوع پردازندهٔ گوشی‌ات را نمی‌دانی، **Universal** را انتخاب کن. این فایل حجیم‌تر است، چون همهٔ معماری‌ها را دارد.

طراحی، کانفیگ‌ها و امکانات نسخهٔ قبلی حفظ شده‌اند. این انتشار برای اضافه کردن خروجی‌های معماری‌های دیگر است. برای نصب، فایل **.apk** را دانلود کن.

</div>

### English ✨

The same space-inspired AndroNG experience, now with dedicated APKs for all four architectures and a Universal package.

**📱 Every APK requires Android 9 or newer.**

Choose **ARM64** for most modern phones, **ARM32** for armeabi-v7a devices, or the matching **x86 / x86_64** build for Intel devices and emulators. If you are unsure, choose **Universal**; it includes all architectures and is a larger download.

The existing interface and features are retained. This release adds architecture coverage. Install the **.apk** file; native input ZIPs and source archives are for developers.

---

**[💬 Support / پشتیبانی](https://t.me/v2meda_admin)** · **[📣 Telegram](https://t.me/v2meda)**

<details>
<summary>Source &amp; validation / سورس و بررسی فایل‌ها</summary>

Source tag: `v1.5.0-775`. Native input archive: `AndroNG-native-inputs-775.zip`.
The native source revisions are pinned in `.gitmodules` and `release-manifest.json`.
Checksums are supplied in `SHA256SUMS`.

Release APKs are signed locally with the same certificate as build 774.
No release signing key or administrator credentials are included.

Build and APK architecture checks passed. Android 9 emulator checks passed for
x86/x86_64 JNI load, local exit open/close, Aether executable startup and Universal
debug-app cold launch. These are startup checks, not end-to-end VPN tests.

Not run: physical ARM32/ARM64 checks, end-to-end VPN/proxy/root connectivity on
each ABI, and installation on a 16 KiB page-size device.

[Build and validation run](https://github.com/AmirForge/AndroNG/actions/runs/44808827874)

</details>
