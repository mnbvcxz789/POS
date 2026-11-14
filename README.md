# Service POS — Aplikasi Android

Aplikasi kasir & bengkel HP (versi 6) dalam bentuk APK Android. Berjalan **100% offline**, data tersimpan di perangkat.

Fitur khusus versi Android:
- WhatsApp langsung terbuka di aplikasi WhatsApp
- Cetak struk ke **printer thermal Bluetooth** (58/80 mm, ESC/POS) — pilih sekali, berikutnya langsung cetak
- Export CSV & backup JSON tersimpan ke folder pilihan Anda
- Upload logo & pulihkan backup dari file
- Tombol **Kembali** Android menutup jendela / kembali ke halaman Terima Unit
- Tampilan menyesuaikan HP dan tablet, tidak tertutup notch atau keyboard

---

## Cara 1 — Build otomatis di GitHub (tanpa install apa pun)

1. Buat akun di https://github.com (gratis).
2. Klik **New repository** → beri nama, mis. `service-pos` → pilih **Private** → **Create repository**.
3. Klik **uploading an existing file**, lalu seret **seluruh isi folder ini** (termasuk folder `.github`, `app`, `gradle`, dan file `gradlew`) → **Commit changes**.
   > Di Mac/Windows folder `.github` kadang tersembunyi. Pastikan ikut ter-upload, karena folder inilah yang menjalankan build otomatis.
4. Buka tab **Actions** → tunggu proses **Build APK** selesai (±5–8 menit, tanda centang hijau).
5. Klik proses tersebut → di bagian **Artifacts** unduh **ServicePOS-APK** (file .zip) → ekstrak → dapat file `.apk`.

Build ulang kapan saja: tab **Actions** → **Build APK** → **Run workflow**.

## Cara 2 — Android Studio

1. Install Android Studio (versi Ladybug 2024.2 atau lebih baru).
2. **File → Open** → pilih folder ini → tunggu sinkronisasi Gradle selesai.
3. **Build → Generate App Bundles or APKs → Generate APKs**
   atau lewat terminal: `./gradlew assembleRelease`
4. APK ada di `app/build/outputs/apk/release/app-release.apk`.

Saat build pertama, Gradle otomatis mengunduh ikon, generator QR, dan font ke `app/src/main/assets/www/vendor/` agar aplikasi bisa jalan offline.

---

## Install di HP / tablet

1. Kirim file `.apk` ke HP (WhatsApp, Google Drive, kabel USB).
2. Buka file → izinkan **Instal aplikasi tidak dikenal** bila diminta.
3. Buka **Service POS**. PIN owner awal: **1234** (segera ganti di Pengaturan → Keamanan & data).

### Memindahkan data dari versi browser
Data di browser dan di aplikasi **terpisah**. Untuk memindahkan:
1. Di browser lama: **Laporan → Backup** (atau Pengaturan → Keamanan & data → Download backup).
2. Di aplikasi: **Pengaturan → Keamanan & data → Pulihkan dari file** → pilih file backup.

### Printer thermal Bluetooth
1. Nyalakan printer, lalu **pasangkan (pairing)** lewat Pengaturan Bluetooth HP (PIN printer biasanya `0000` atau `1234`).
2. Di aplikasi: **Pengaturan → Toko & struk → Pilih printer**, atau tekan **Bluetooth** pada struk.
3. Izinkan akses **Perangkat terdekat** bila diminta.

---

## ⚠️ PENTING: Update aplikasi tanpa kehilangan data

- **Jangan uninstall** aplikasi saat update. Uninstall = semua data toko di perangkat terhapus.
- Install APK versi baru langsung **menimpa** versi lama, dan data tetap aman, **asalkan** di-build dengan kunci yang sama (`keystore/pos-release.jks`).
- Setiap rilis baru, naikkan `versionCode` di `app/build.gradle.kts` (mis. 1 → 2).
- **Simpan baik-baik** folder `keystore/` dan file `keystore.properties`. Kalau hilang, update berikutnya terpaksa uninstall dulu.
- Karena kata sandi kunci ada di repository, **repository GitHub wajib Private**.
- Rutin buat **backup JSON** dan simpan di luar HP (Drive/email).

---

## Mengubah aplikasi

| Yang ingin diubah | File |
|---|---|
| Tampilan & logika kasir | `app/src/main/assets/www/index.html` |
| Nama aplikasi di HP | `app/src/main/res/values/strings.xml` |
| Versi | `versionCode` / `versionName` di `app/build.gradle.kts` |
| Ikon | `app/src/main/res/mipmap-*` dan `drawable/ic_launcher_foreground.xml` |
| Fitur Android (WA, printer, file) | `app/src/main/java/id/digitalservice/pos/MainActivity.kt` |

File `index.html` yang sama juga tetap bisa dibuka langsung di browser.

Spesifikasi: Android 7.0 (API 24) ke atas · target Android 15 (API 35) · ukuran APK ±3–5 MB.
