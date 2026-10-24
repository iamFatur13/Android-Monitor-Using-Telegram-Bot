# Android-Monitor-Using-Telegram-Bot

Aplikasi Android yang dapat dikendalikan melalui Telegram Bot.

## Fitur
- 📸 Screenshot remote
- 🔋 Cek level baterai
- 📊 Info perangkat
- ⚡ Foreground service (tahan di background)

## Perintah yang Didukung

| Perintah | Fungsi |
|----------|--------|
| `/ping` | Cek koneksi |
| `/screenshot` | Ambil tangkapan layar |
| `/battery` | Level baterai |
| `/status` | Info perangkat |
| `/help` | Daftar perintah |

## Cara Pakai

### 1. Token Bot
1. Cari **@BotFather** di Telegram
2. `/newbot` → buat bot
3. Salin **token**

### 2. Chat ID
- Chat ke **@userinfobot** untuk dapat chat ID Anda
- Atau cari dari `@BotFather` → `/getUpdates`

### 3. Install Aplikasi
1. Download APK dari **GitHub Actions → Artifacts**
2. Install di HP Android
3. Buka aplikasi → isi Token & Chat ID
4. **Test Koneksi** → ✅
5. Aktifkan **Service**
6. Tap **Izinkan Screenshot** → beri izin

## Build
- GitHub Actions: push ke main → APK auto-build → download dari Actions/Artifacts
- Lokal: `./gradlew assembleDebug`

## Persyaratan
- Android 7.0+ (API 24)
- Target: API 34
- Min SDK: 24

## Struktur
```
app/src/main/java/com/hpu/telebotmon/
├── MainActivity.kt       (UI utama)
├── TelegramService.kt    (Foreground service)
└── App.kt                (Application)
```

## Permissions
| Permission | Alasan |
|------------|--------|
| INTERNET | Telegram API |
| FOREGROUND_SERVICE | Service berjalan |
| MEDIA_PROJECTION | Screenshot |
| SYSTEM_ALERT_WINDOW | Overlay |
