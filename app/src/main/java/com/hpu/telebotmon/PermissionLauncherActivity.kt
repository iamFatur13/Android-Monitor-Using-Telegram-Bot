package com.hpu.telebotmon

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class PermissionLauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chatId = intent.getStringExtra(TelegramService.EXTRA_CHAT_ID) ?: ""
        val perm = intent.getStringExtra(TelegramService.EXTRA_PERMISSION) ?: ""

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val granted = when (perm) {
                "location" ->
                    checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                else -> false
            }
            if (granted) {
                finishWithResult(true, chatId, perm)
                return
            }
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ),
                1
            )
        } else {
            finishWithResult(true, chatId, perm)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        finishWithResult(granted, intent.getStringExtra(TelegramService.EXTRA_CHAT_ID) ?: "",
            intent.getStringExtra(TelegramService.EXTRA_PERMISSION) ?: "")
    }

    private fun finishWithResult(granted: Boolean, chatId: String, perm: String) {
        val data = Intent()
        data.putExtra(TelegramService.EXTRA_RESULT_CODE, if (granted) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED)
        data.putExtra(TelegramService.EXTRA_PERMISSION, perm)
        data.putExtra(TelegramService.EXTRA_CHAT_ID, chatId)
        setResult(if (granted) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED, data)
        finish()
    }
}
