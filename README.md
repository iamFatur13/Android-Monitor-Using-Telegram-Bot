# Android-Monitor-Using-Telegram-Bot

Aplikasi Android yang dapat dikendalikan melalui Telegram Bot.

## Fitur
- 📸 Screenshot remote (via MediaProjection)
- 🖼️ Kirim foto galeri terakhir (`/photo N`)
- 📍 Lokasi GPS
- 🔋 Cek level baterai
- 📊 Info perangkat
- 🔔 Monitor notifikasi — semua notifikasi masuk otomatis dikirim ke chat bot
- ⚡ Foreground service (tahan di background)
- 📝 Monitoring logcat (semua perintah & error ter-log di tag `TeleBotService`)

## Perintah yang Didukung

| Perintah | Fungsi |
|----------|--------|
| `/ping` | Cek koneksi |
| `/screenshot` | Ambil tangkapan layar |
| `/photo [N]` | Kirim N foto terakhir dari galeri (default 5, max 20) |
| `/battery` | Level baterai |
| `/status` | Info perangkat |
| `/gps` | Lokasi GPS terakhir + link Google Maps |
| `/notifications` | Cek status monitor notifikasi |
| `/help` | Daftar perintah |

## Monitor Notifikasi
Semua notifikasi yang masuk (WhatsApp, SMS, email, app lain) otomatis dikirim ke chat bot dengan format:
```
🔔 [10:35:12] example.app
Judul: Pesan baru
Teks: Halo
App: com.example.app
```
**Cara aktifkan:**
1. Buka app → tap "Izinkan Monitor Notifikasi"
2. Di halaman Settings yang muncul, centang **TeleBot Monitor**
3. Kembali ke app — status berubah jadi "✅ Monitor notifikasi AKTIF"
4. (Opsional) Ketik `/notifications` di Telegram untuk verifikasi

Notifikasi akan masuk terus selama app terinstall & izin tidak dicabut.

## Cara Pakai

### 1. Token Bot
1. Cari **@BotFather** di Telegram
2. `/newbot` → buat bot
3. Salin **token**

### 2. Chat ID
- Chat ke **@userinfobot** untuk dapat chat ID Anda
- Atau dari `@BotFather` → `/getUpdates`

### 3. Install Aplikasi
1. Download APK dari **GitHub Actions → Artifacts** (atau dari folder `apk/`)
2. Install di HP Android
3. Buka aplikasi → isi Token & Chat ID
4. **Test Koneksi** → ✅
5. Aktifkan **Service**
6. Tap **Izinkan Screenshot** → beri izin
7. (Opsional) Izinkan lokasi & galeri saat diminta

## Debugging

Semua aktivitas bot ter-log di logcat dengan tag `TeleBotService`:

```bash
adb logcat -s TeleBotService
```

Kamu akan lihat:
- `pollUpdates: GET ...getUpdates?timeout=0` — polling berjalan
- `COMMAND: "/photo" from <chatId> -> reply to <chatId>` — perintah diterima
- `sendText: success to chat <chatId>` / `HTTP <kode> ...` — respons terkirim / gagal
- `polling loop error: <Exception>` — error polling

## Build
- GitHub Actions: push ke main → APK auto-build → download dari Actions/Artifacts
- Lokal: `./gradlew assembleDebug` (hasil di `app/build/outputs/apk/debug/app-debug.apk`)

## Persyaratan
- Android 7.0+ (API 24)
- Target: API 34
- Min SDK: 24

## Struktur
```
app/src/main/java/com/hpu/telebotmon/
├── MainActivity.kt              (UI utama + permintaan izin + tombol notif)
├── TelegramService.kt           (Foreground service: polling, perintah, kirim foto/GPS)
├── NotificationWatcherService.kt(Notification listener: kirim semua notifikasi ke bot)
├── PermissionLauncherActivity.kt(Launcher izin lokasi dari service)
└── App.kt                       (Application)
```

## Permissions
| Permission | Alasan |
|------------|--------|
| INTERNET | Telegram API |
| FOREGROUND_SERVICE | Service berjalan |
| FOREGROUND_SERVICE_MEDIA_PROJECTION | Screenshot via MediaProjection |
| MEDIA_PROJECTION | Screenshot |
| SYSTEM_ALERT_WINDOW | Overlay |
| ACCESS_COARSE/FINE_LOCATION | `/gps` |
| READ_EXTERNAL_STORAGE (≤ API 32) | `/photo` (galeri) |
| READ_MEDIA_IMAGES (API 33+) | `/photo` (galeri) |
| BIND_NOTIFICATION_LISTENER_SERVICE | Monitor notifikasi |

## Catatan
- `getUpdates` memakai `timeout=0` (non-blocking, poll tiap 2 detik) agar OkHttp read-timeout 30s tidak sempat ke-jadi masalah.
- Pembatasan jumlah foto `/photo` dilakukan di memori (bukan `LIMIT` di SQL), karena query `MediaStore` Android menolak `LIMIT` di parameter sort-order.
