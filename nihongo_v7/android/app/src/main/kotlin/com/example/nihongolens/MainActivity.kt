package com.example.nihongolens

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        var instance: MainActivity? = null
        const val REQUEST_PROJECTION = 1001
    }

    private val CHANNEL = "overlay_channel"
    private val WHISPER_CHANNEL = "whisper_channel"
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var whisperChannel: MethodChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Download MLKit model in background
        Thread {
            try {
                val options = com.google.mlkit.nl.translate.TranslatorOptions.Builder()
                    .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.JAPANESE)
                    .setTargetLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
                    .build()
                val t = com.google.mlkit.nl.translate.Translation.getClient(options)
                t.downloadModelIfNeeded()
            } catch (_: Exception) {}
        }.start()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        whisperChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, WHISPER_CHANNEL)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startOverlay" -> {
                        if (!Settings.canDrawOverlays(this)) {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")))
                            result.success(false)
                            return@setMethodCallHandler
                        }
                        val i = Intent(this, OverlayService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            startForegroundService(i) else startService(i)
                        result.success(true)
                    }
                    "startInternalAudioCapture" -> {
                        startActivityForResult(
                            mediaProjectionManager.createScreenCaptureIntent(),
                            REQUEST_PROJECTION
                        )
                        result.success(true)
                    }
                    "stopCapture" -> {
                        stopService(Intent(this, AudioCaptureService::class.java))
                        stopService(Intent(this, OverlayService::class.java))
                        result.success(true)
                    }
                    "hasOverlayPermission" -> result.success(Settings.canDrawOverlays(this))
                    else -> result.notImplemented()
                }
            }
    }

    fun sendTranslation(japanese: String, english: String) {
        runOnUiThread {
            whisperChannel?.invokeMethod("onTranscription", mapOf(
                "japanese" to japanese,
                "english" to english
            ))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(this, AudioCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(i) else startService(i)
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
