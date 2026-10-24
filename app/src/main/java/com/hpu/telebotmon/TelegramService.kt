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
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import android.os.BatteryManager

class TelegramService : Service() {

    private var scope: CoroutineScope? = null
    private var lastUpdateId = 0L
    var botToken: String = ""
    var botChatId: String = ""

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

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
    }

    override fun onDestroy() {
        super.onDestroy()
        scope?.cancel()
        cleanupMediaProjection()
        unregisterReceiver(mediaProjectionReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        botToken = intent?.getStringExtra("token") ?: botToken
        botChatId = intent?.getStringExtra("chatId") ?: botChatId
        savePrefs()
        if (botToken.isNotEmpty()) startPolling()
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

    private fun startPolling() {
        scope?.launch {
            while (isActive) {
                try {
                    pollUpdates()
                    delay(1500L)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { delay(3000L) }
            }
        }
    }

    private suspend fun pollUpdates() {
        if (botToken.isEmpty()) return
        val url = buildString {
            append("https://api.telegram.org/bot").append(botToken).append("/getUpdates?timeout=25&limit=5")
            if (lastUpdateId > 0) append("&offset=").append(lastUpdateId + 1)
        }
        val resp = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute()
        }
        if (!resp.isSuccessful) return
        val body = resp.body?.string() ?: return
        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) return

        val arr = json.getJSONArray("result")
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            lastUpdateId = obj.optLong("update_id", lastUpdateId)
            val msg = obj.optJSONObject("message") ?: continue
            val text = msg.optString("text", "")
            val thisChatId = msg.optJSONObject("chat")?.optLong("id")?.toString() ?: ""
            if (botChatId.isNotEmpty() && thisChatId != botChatId) continue
            handleCommand(text.trim(), thisChatId)
        }
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
            cmd.contains("/help") || cmd == "help" || cmd == "?" -> {
                sendHelp(targetChatId)
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
            /help     - daftar ini
        """.trimIndent()
        sendText(chatId, helpText)
    }

    private suspend fun sendText(chatId: String, text: String) = withContext(Dispatchers.IO) {
        if (chatId.isEmpty() || botToken.isEmpty()) return@withContext
        try {
            val json = JSONObject().put("chat_id", chatId).put("text", text).toString()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/sendMessage")
                .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            http.newCall(req).execute().close()
        } catch (e: Exception) { /* ignore */ }
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
        val charging = status == android.os.BatteryManager.STATUS_CHARGING || 
                      status == android.os.BatteryManager.STATUS_FULL
        return Pair(pct, charging)
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
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val idx = y * stride + x * pixelStride
                        val b = bytes[idx].toInt() and 0xFF
                        val g = bytes[idx + 1].toInt() and 0xFF
                        val r = bytes[idx + 2].toInt() and 0xFF
                        val a = bytes[idx + 3].toInt() and 0xFF
                        bmp.setPixel(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
                    }
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
        const val ACTION_MEDIA_PROJECTION = "com.hpu.telebotmon.MEDIA_PROJECTION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_CHAT_ID = "chat_id"
    }
}