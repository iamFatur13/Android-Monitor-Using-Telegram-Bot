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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class TelegramService : Service() {

    private var scope: CoroutineScope? = null
    private var lastUpdateId = 0L
    var botToken: String = ""
    var botChatId: String = ""

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

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
    }

    override fun onDestroy() {
        super.onDestroy()
        scope?.cancel()
        cleanupMediaProjection()
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

    private fun handleCommand(text: String, targetChatId: String) {
        val cmd = text.lowercase()
        when {
            cmd.contains("/ping") || cmd == "ping" ->
                sendText(targetChatId, "PONG! \u26f3 ${Build.MODEL}")
            cmd.contains("/screenshot") || cmd == "screenshot" ->
                takeScreenshot(targetChatId)
            cmd.contains("/battery") || cmd == "battery" ->
                getBattery { level, charging ->
                    sendText(targetChatId, "\u{1f50b} Baterai: $level% ${if (charging) "\u{1f50c}" else ""}")
                }
            cmd.contains("/status") || cmd == "status" ->
                getDeviceStatus(targetChatId)
            cmd.contains("/help") || cmd == "help" || cmd == "?" ->
                sendHelp(targetChatId)
            else ->
                sendText(targetChatId, "Perintah tidak dikenal. Ketik '/help' untuk bantuan.")
        }
    }

    private fun sendHelp(chatId: String) {
        sendText(chatId, """
            \u{1f4cb} Perintah tersedia:
            \u2022 /ping     \u2013 cek koneksi
            \u2022 /screenshot \u2013 ambil layar ponsel
            \u2022 /battery  \u2013 level baterai
            \u2022 /status   \u2013 info perangkat
            \u2022 /help     \u2013 daftar ini
        """.trimIndent())
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
                .addFormDataPart("caption", "\u{1f4f8} Screenshot: ${Build.MODEL}")
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

    private fun getBattery(callback: (Int, Boolean) -> Unit) {
        val i = registerReceiver(null, Intent(Intent.ACTION_BATTERY_CHANGED))
        if (i == null) { callback(0, false); return }
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 1)
        val pct = if (level > 0 && scale > 0) level * 100 / scale else 0
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.STATUS_CHARGING || status == BatteryManager.STATUS_FULL
        callback(pct, charging)
    }

    private fun getDeviceStatus(chatId: String) {
        getBattery { level, charging ->
            val free = filesDir.freeSpace
            val total = filesDir.totalSpace
            val msg = """
                \u{1f4f1} Model: ${Build.MODEL}
                \u{1f3ed} Produsen: ${Build.MANUFACTURER}
                \u{1f916} Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
                \u{1f50b} Baterai: $level% ${if (charging) "\u{1f50c}" else ""}
                \u{1f4be} Storage: ${String.format("%.1f", free / 1e9)} GB / ${String.format("%.1f", total / 1e9)} GB
            """.trimIndent()
            CoroutineScope(Dispatchers.IO).launch { sendText(chatId, msg) }
        }
    }

    private fun takeScreenshot(chatId: String) {
        if (mediaProjection == null) {
            // Request permission via MainActivity
            val act = Intent(this, MainActivity::class.java)
            act.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            act.putExtra("request_screenshot", true)
            act.putExtra("chat_id", chatId)
            startActivity(act)
            CoroutineScope(Dispatchers.IO).launch {
                sendText(chatId, "\u{1f534} Tap aplikasi untuk izinkan screenshot.")
            }
            return
        }
        ensureVirtualDisplay()
        CoroutineScope(Dispatchers.Main).launch {
            val bmp = captureFrame()
            if (bmp != null) {
                sendPhoto(chatId, bmp)
            } else {
                sendText(chatId, "\u26a0\ufe0f Gagal ambil screenshot.")
            }
        }
    }

    private fun ensureVirtualDisplay() {
        if (virtualDisplay != null) return
        val dm = DisplayMetrics()
        val dmd = getSystemService(DisplayManager::class.java)
        @Suppress("DEPRECATION")
        dmd.defaultDisplay.getRealMetrics(dm)
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
        mediaProjection?.setCallback(object : MediaProjection.Callback() {
            override fun onStop() { cleanupMediaProjection() }
        }, null)
        ensureVirtualDisplay()
        CoroutineScope(Dispatchers.IO).launch {
            sendText(chatId, "\u2705 Izin tangkap layar diberikan!")
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
