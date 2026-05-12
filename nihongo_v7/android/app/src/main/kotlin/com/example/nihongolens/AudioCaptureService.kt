package com.example.nihongolens

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

class AudioCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_lens_channel"
        const val NOTIFICATION_ID = 1001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayText: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        showOverlay()

        startForeground(
            NOTIFICATION_ID,
            createNotification("🎧 Nihongo Lens running...")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // Android restarted service without permission data
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e("NihongoLens", "Screen capture permission missing")
            updateOverlay("⚠️ Tap START again and allow screen capture.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager

            mediaProjection =
                projectionManager.getMediaProjection(resultCode, data)

            startAudioCapture()

            updateOverlay("✅ Active! Open any Japanese video now.")

        } catch (e: Exception) {

            Log.e("NihongoLens", "Error starting capture", e)

            updateOverlay("❌ Failed to start capture")

            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startAudioCapture() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            updateOverlay("❌ Android 10+ required")
            return
        }

        val config =
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

        val sampleRate = 16000

        val channelConfig = AudioFormat.CHANNEL_IN_MONO

        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            audioFormat
        )

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        try {

            audioRecord?.startRecording()

            Log.d("NihongoLens", "Audio capture started")

            Thread {

                val buffer = ByteArray(bufferSize)

                while (audioRecord != null) {

                    try {

                        val read =
                            audioRecord?.read(buffer, 0, buffer.size) ?: 0

                        if (read > 0) {

                            Log.d(
                                "NihongoLens",
                                "Captured audio bytes: $read"
                            )

                            // TODO:
                            // Send PCM audio to translation engine
                            // Example:
                            // Whisper / Google Translate / Vosk

                            updateOverlay("🎌 Listening to Japanese audio...")
                        }

                    } catch (e: Exception) {

                        Log.e("NihongoLens", "Read error", e)
                    }
                }

            }.start()

        } catch (e: Exception) {

            Log.e("NihongoLens", "Recording failed", e)

            updateOverlay("❌ Audio capture failed")
        }
    }

    private fun showOverlay() {

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        overlayView = LayoutInflater.from(this)
            .inflate(R.layout.overlay_layout, null)

        overlayText =
            overlayView?.findViewById(R.id.overlayText)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.x = 0
        params.y = 120

        try {

            windowManager.addView(overlayView, params)

        } catch (e: Exception) {

            Log.e("NihongoLens", "Overlay error", e)
        }
    }

    private fun updateOverlay(text: String) {

        overlayView?.post {

            overlayText?.text = text
        }
    }

    private fun createNotification(text: String): Notification {

        val stopIntent = Intent(this, AudioCaptureService::class.java)
        stopIntent.action = "STOP"

        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nihongo Lens")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_delete,
                "STOP",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nihongo Lens",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        try {

            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

        } catch (_: Exception) {
        }

        try {

            mediaProjection?.stop()
            mediaProjection = null

        } catch (_: Exception) {
        }

        try {

            if (overlayView != null) {
                windowManager.removeView(overlayView)
                overlayView = null
            }

        } catch (_: Exception) {
        }
    }
}
