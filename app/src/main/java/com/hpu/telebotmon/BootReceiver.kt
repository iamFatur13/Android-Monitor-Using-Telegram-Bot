package com.hpu.telebotmon

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Restart service secara otomatis setelah device reboot, asalkan
 * user terakhir kali menyalakan service (prefs "service_enabled" = true).
 *
 * Menggunakan AlarmManager (30 detik delay) karena BroadcastReceiver
 * tidak boleh start activity langsung dari BOOT_COMPLETED (Android 12+).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val prefs = context.getSharedPreferences("telebot", Context.MODE_PRIVATE)
        val serviceWasOn = prefs.getBoolean("service_enabled", false)
        val token = prefs.getString("token", "") ?: ""
        val chatId = prefs.getString("chat_id", "") ?: ""

        Log.d(TAG, "onReceive: $action, serviceWasOn=$serviceWasOn, tokenEmpty=${token.isEmpty()}")
        if (!serviceWasOn || token.isEmpty()) return

        // Gunakan AlarmManager agar aman di semua versi Android
        val restartIntent = Intent(context, BootRestartReceiver::class.java)
            .putExtra("token", token)
            .putExtra("chatId", chatId)
            .setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

        val pi = PendingIntent.getBroadcast(
            context,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + 30_000L
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        Log.d(TAG, "Scheduled restart in 30s via AlarmManager")
    }

    companion object {
        private const val TAG = "TeleBotBoot"
    }
}

/**
 * Dipanggil oleh AlarmManager 30 detik setelah BOOT_COMPLETED.
 * Start service + buka MainActivity.
 */
class BootRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val token = intent?.getStringExtra("token") ?: ""
        val chatId = intent?.getStringExtra("chatId") ?: ""
        Log.d("TeleBotBoot", "BootRestartReceiver: starting service with token=$token")
        if (token.isNotEmpty()) {
            val svc = Intent(context, TelegramService::class.java)
                .putExtra("token", token)
                .putExtra("chatId", chatId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
            // Buka app juga agar user lihat status
            val uiIntent = Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(uiIntent)
        }
    }
}
