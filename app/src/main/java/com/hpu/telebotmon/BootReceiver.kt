package com.hpu.telebotmon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restart service secara otomatis setelah device reboot, asalkan
 * user terakhir kali menyalakan service (prefs "service_enabled" = true).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val prefs = context.getSharedPreferences("telebot", Context.MODE_PRIVATE)
        val serviceWasOn = prefs.getBoolean("service_enabled", false)
        val token = prefs.getString("token", "") ?: ""
        val chatId = prefs.getString("chat_id", "") ?: ""

        Log.d("TeleBotBoot", "onReceive: $action, serviceWasOn=$serviceWasOn, tokenEmpty=${token.isEmpty()}")
        if (serviceWasOn && token.isNotEmpty()) {
            Log.d("TeleBotBoot", "Auto-restart TelegramService after boot")
            val svc = Intent(context, TelegramService::class.java)
            svc.putExtra("token", token)
            svc.putExtra("chatId", chatId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // startForegroundService butuh context dari app, bukan BroadcastReceiver
                // Tunda sedikit dengan AlarmManager sebagai fallback
                val startIntent = Intent(context, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(startIntent)
            } else {
                context.startService(svc)
            }
        }
    }
}
