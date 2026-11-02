package com.hpu.telebotmon

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter

class App : Application() {

    companion object {
        const val CHANNEL_ID = "telebot_monitor"
        const val TAG = "TeleBotApp"
        private lateinit var instance: App
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        installCrashHandler()
    }

    /**
     * Catch semua uncaught exception di semua thread, log ke file + logcat,
     * lalu rethrow (biarkan app mati) — tapi minimal kita punya rekam jejak.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UNCAUGHT EXCEPTION in ${thread.name}: ${throwable.message}", throwable)
            try {
                val crashDir = File(filesDir, "crash_logs")
                if (!crashDir.exists()) crashDir.mkdirs()
                val logFile = File(crashDir, "crash_${System.currentTimeMillis()}.txt")
                FileWriter(logFile).use { fw ->
                    fw.write("Thread: ${thread.name}\n")
                    fw.write("Timestamp: ${java.util.Date()}\n")
                    fw.write("Exception: ${throwable.javaClass.name}: ${throwable.message}\n")
                    fw.write("Stack trace:\n")
                    val sw = java.io.StringWriter()
                    throwable.printStackTrace(java.io.PrintWriter(sw))
                    fw.write(sw.toString())
                }
                Log.e(TAG, "Crash logged to ${logFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log: ${e.message}")
            }
            // Teruskan ke handler default supaya app tetap mati (atau restart)
            previous?.uncaughtException(thread, throwable)
        }
        Log.d(TAG, "Crash handler installed")
    }

    /** Baca crash log terakhir (untuk dikirim ke Telegram via /diag). */
    fun getLatestCrashLog(): String? {
        val crashDir = File(filesDir, "crash_logs")
        if (!crashDir.exists()) return null
        val files = crashDir.listFiles() ?: return null
        if (files.isEmpty()) return null
        val latest = files.maxByOrNull { it.lastModified() } ?: return null
        return try {
            latest.readText().take(500)
        } catch (e: Exception) {
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TeleBot Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }
}