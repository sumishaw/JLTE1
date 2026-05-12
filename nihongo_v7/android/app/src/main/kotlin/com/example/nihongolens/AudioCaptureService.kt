package com.example.nihongolens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null

    private val isRecording = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification: Notification =
            NotificationCompat.Builder(this, "nihongo_lens")
                .setContentTitle("Nihongo Lens")
                .setContentText("Listening for Japanese audio...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()

        startForeground(1, notification)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        try {

            val resultCode =
                intent?.getIntExtra("resultCode", -1) ?: -1

            val data: Intent? =
                intent?.getParcelableExtra("data")

            if (resultCode == -1 || data == null) {
                Log.e("NIHONGO_AUDIO", "Projection permission missing")
                stopSelf()
                return START_NOT_STICKY
            }

            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager

            mediaProjection =
                projectionManager.getMediaProjection(resultCode, data)

            startAudioCapture()

        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }

        return START_STICKY
    }

    private fun startAudioCapture() {

        if (isRecording.get()) return

        try {

            val config =
                AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()

            val sampleRate = 16000

            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .build()

            audioRecord?.startRecording()

            isRecording.set(true)

            Thread {

                val buffer = ByteArray(bufferSize)

                while (isRecording.get()) {

                    val read =
                        audioRecord?.read(buffer, 0, buffer.size) ?: 0

                    if (read > 0) {

                        Log.d(
                            "NIHONGO_AUDIO",
                            "Audio detected: $read bytes"
                        )

                        OverlayService.latestSubtitle =
                            "🎤 Japanese audio detected"
                    }
                }

            }.start()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        isRecording.set(false)

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord = null
        mediaProjection?.stop()
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                "nihongo_lens",
                "Nihongo Lens",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }
}
