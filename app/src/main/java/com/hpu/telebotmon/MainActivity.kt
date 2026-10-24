package com.hpu.telebotmon

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etToken: EditText
    private lateinit var etChatId: EditText
    private lateinit var btnTest: Button
    private lateinit var btnGrantScreenshot: Button
    private lateinit var switchService: Switch

    private lateinit var prefs: SharedPreferences
    private var mediaProjectionLauncher: ActivityResultLauncher<Intent>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("telebot", Context.MODE_PRIVATE)

        etToken = findViewById(R.id.et_token)
        etChatId = findViewById(R.id.et_chat_id)
        btnTest = findViewById(R.id.btn_test)
        btnGrantScreenshot = findViewById(R.id.btn_grant_screenshot)
        switchService = findViewById(R.id.switch_service)

        // Load saved values
        etToken.setText(prefs.getString("token", ""))
        etChatId.setText(prefs.getString("chat_id", ""))

        // MediaProjection result launcher
        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            when (result.resultCode) {
                Activity.RESULT_OK -> {
                    val data = result.data
                    val chatId = etChatId.text.toString().trim()
                    if (chatId.isNotEmpty()) {
                        notifyServiceMediaProjection(Activity.RESULT_OK, data, chatId)
                        Toast.makeText(this, "Izin tangkap layar diberikan", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> Toast.makeText(this, "Izin tangkap layar ditolak", Toast.LENGTH_SHORT).show()
            }
        }

        btnTest.setOnClickListener { testConnection() }
        btnGrantScreenshot.setOnClickListener { requestScreenshotPermission() }
        switchService.setOnCheckedChangeListener { _, isChecked ->
            savePrefs()
            if (isChecked) {
                if (etToken.text.isNullOrBlank()) {
                    Toast.makeText(this, "Masukkan token bot terlebih dahulu", Toast.LENGTH_SHORT).show()
                    switchService.isChecked = false
                    return@setOnCheckedChangeListener
                }
                val svc = Intent(this, TelegramService::class.java)
                svc.putExtra("token", etToken.text.toString().trim())
                svc.putExtra("chatId", etChatId.text.toString().trim())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc)
                } else {
                    startService(svc)
                }
                Toast.makeText(this, "Service dimulai", Toast.LENGTH_SHORT).show()
            } else {
                stopService(Intent(this, TelegramService::class.java))
                Toast.makeText(this, "Service dihentikan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("token", etToken.text.toString().trim())
            .putString("chat_id", etChatId.text.toString().trim())
            .apply()
    }

    private fun testConnection() {
        val token = etToken.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, "Masukkan token bot", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val url = "https://api.telegram.org/bot$token/getMe"
            val conn = java.net.URL(url).openConnection()
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val response = conn.getInputStream().bufferedReader().use { it.readText() }
            if (response.contains("\"ok\":true")) {
                val json = org.json.JSONObject(response)
                val botName = json.getJSONObject("result").optString("first_name", "unknown")
                Toast.makeText(this, "Terhubung ke @$botName", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Token tidak valid", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestScreenshotPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(android.net.Uri.parse("package:$packageName"))
                startActivity(intent)
                return
            }
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher?.launch(mgr.createScreenCaptureIntent())
    }

    private fun notifyServiceMediaProjection(resultCode: Int, data: Intent?, chatId: String) {
        val act = Intent(TelegramService.ACTION_MEDIA_PROJECTION)
        act.putExtra(TelegramService.EXTRA_RESULT_CODE, resultCode)
        act.putExtra(TelegramService.EXTRA_RESULT_DATA, data)
        act.putExtra(TelegramService.EXTRA_CHAT_ID, chatId)
        sendBroadcast(act)
    }
}