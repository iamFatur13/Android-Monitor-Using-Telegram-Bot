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

        // Minta izin lokasi otomatis saat aplikasi dibuka
        requestLocationPermission()
        // Minta izin akses galeri (untuk fitur /photo)
        requestGalleryPermission()

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

    private fun requestLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val coarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (coarse == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            fine == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        requestPermissions(
            arrayOf(
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ),
            1001
        )
    }

    /** Minta izin akses galeri sesuai versi Android:
     *  - Android 13+ (API 33+): READ_MEDIA_IMAGES
     *  - Android ≤ 12: READ_EXTERNAL_STORAGE
     */
    private fun requestGalleryPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val perm = when {
            Build.VERSION.SDK_INT >= 33 -> android.Manifest.permission.READ_MEDIA_IMAGES
            else -> android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(perm), 1002)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val granted = grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            Toast.makeText(
                this,
                if (granted) "Izin lokasi diberikan" else "Izin lokasi ditolak — fitur /gps tidak akan bekerja",
                Toast.LENGTH_LONG
            ).show()
        }
        if (requestCode == 1002) {
            val granted = grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            Toast.makeText(
                this,
                if (granted) "Izin akses galeri diberikan" else "Izin galeri ditolak — fitur /photo tidak akan bekerja",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun notifyServiceMediaProjection(resultCode: Int, data: Intent?, chatId: String) {
        val act = Intent(TelegramService.ACTION_MEDIA_PROJECTION)
        act.putExtra(TelegramService.EXTRA_RESULT_CODE, resultCode)
        act.putExtra(TelegramService.EXTRA_RESULT_DATA, data)
        act.putExtra(TelegramService.EXTRA_CHAT_ID, chatId)
        sendBroadcast(act)
    }
}