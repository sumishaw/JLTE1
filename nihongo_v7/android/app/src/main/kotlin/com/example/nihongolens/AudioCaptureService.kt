package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureService : Service() {

    companion object {
        var mediaProjection: MediaProjection? = null
        var resultCode: Int = 0
        var resultData: Intent? = null
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, "nihongo_lens")
            .setContentTitle("Nihongo Lens")
            .setContentText("Listening for Japanese audio...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (mediaProjection == null) {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            mediaProjection =
                projectionManager.getMediaProjection(resultCode, resultData!!)
        }

        startAudioCapture()

        return START_STICKY
    }

    private fun startAudioCapture() {

        if (isRecording.get()) return

        val config =
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

        val sampleRate = 16000

        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        audioRecord?.startRecording()

        isRecording.set(true)

        recordingThread = Thread {

            val buffer = ByteArray(bufferSize)

            while (isRecording.get()) {

                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (read > 0) {

                    // DEBUG LOG
                    android.util.Log.d(
                        "NIHONGO_AUDIO",
                        "Audio detected: $read bytes"
                    )

                    // TODO:
                    // Send PCM buffer to translator/transcriber
                }
            }
        }

        recordingThread?.start()
    }

    override fun onDestroy() {
        super.onDestroy()

        isRecording.set(false)

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                "nihongo_lens",
                "Nihongo Lens",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
