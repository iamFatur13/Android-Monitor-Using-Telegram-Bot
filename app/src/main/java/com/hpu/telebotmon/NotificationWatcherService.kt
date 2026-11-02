package com.hpu.telebotmon

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Pantau semua notifikasi masuk dan kirim teksnya ke chat bot.
 *
 * Aktif saat user mengizinkan "Notification access" untuk app ini.
 * Format pesan ke Telegram:
 *   🔔 [10:35] example.app
 *   Judul: ...
 *   Teks: ...
 */
class NotificationWatcherService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var botToken: String = ""
    private var botChatId: String = ""
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: notif watcher siap")
    }

    override fun onDestroy() {
        try {
            scope.cancel()
            http.dispatcher.executorService.shutdownNow()
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy cleanup: ${e.message}")
        }
        super.onDestroy()
    }

    /** Dipanggil saat user pertama kali centang app di Notification access. */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Seluruh body di-wrap try/catch: tidak boleh ada notifikasi
        // yang crash service (yang akan membuat app force-close).
        try {
            if (sbn == null) return
            // Muat ulang token/chatId dari prefs — user mungkin baru isinya setelah monitor aktif
            loadTokenAndChat()
            val extras = sbn.notification.extras
            val pkg = sbn.packageName ?: "unknown"
            // Beberapa notifikasi tidak punya EXTRA_TITLE/EXTRA_TEXT
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            Log.d(TAG, "onNotificationPosted: [$time] $pkg | $title | $text")
            if (botToken.isEmpty() || botChatId.isEmpty()) {
                Log.w(TAG, "skip: token/chatId belum diisi di app")
                return
            }
            val msg = buildString {
                append("🔔 [").append(time).append("] ").append(pkg.replace("com.", ""))
                if (title.isNotEmpty()) append("\nJudul: ").append(title)
                if (text.isNotEmpty()) append("\nTeks: ").append(text)
                append("\nApp: ").append(pkg)
            }
            sendToBot(msg)
        } catch (e: Exception) {
            // Jangan biarkan crash menyebar — log & lanjut
            Log.e(TAG, "onNotificationPosted crash (caught): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun loadTokenAndChat() {
        val prefs = getSharedPreferences("telebot", Context.MODE_PRIVATE)
        botToken = prefs.getString("token", "") ?: ""
        botChatId = prefs.getString("chat_id", "") ?: ""
    }

    private fun sendToBot(text: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().put("chat_id", botChatId).put("text", text).toString()
                val req = Request.Builder()
                    .url("https://api.telegram.org/bot$botToken/sendMessage")
                    .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                val resp = http.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    Log.e(TAG, "sendToBot: HTTP ${resp.code}: ${body.take(200)}")
                } else {
                    Log.d(TAG, "sendToBot: ok")
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendToBot: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Cek apakah listener service ini sudah diaktifkan user. */
    companion object {
        const val TAG = "NotifWatcher"
        fun isEnabled(context: Context): Boolean {
            val cn = ComponentName(context, NotificationWatcherService::class.java)
            val enabled =
                androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)
            return enabled.contains(cn.packageName)
        }
    }
}

