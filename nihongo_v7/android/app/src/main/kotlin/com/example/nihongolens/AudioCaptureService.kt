package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var isCapturing = false
    private var captureThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())
    private val audioBuffer = mutableListOf<Short>()
    private val SAMPLE_RATE = 16000
    private val CHUNK_SAMPLES = SAMPLE_RATE * 4

    companion object {
        const val CHANNEL_ID = "nihongo_cap"
        const val NOTIF_ID = 2
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Must call startForeground within 5 seconds of onCreate on Android 14+
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        }

        if (resultCode == -1 || data == null) {
            Log.e("NihongoLens", "No resultCode or data")
            updateOverlay("⚠️ Screen capture permission missing. Tap START again.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            updateOverlay("⚠️ Android 10+ required")
            stopSelf()
            return START_NOT_STICKY
        }

        // Get MediaProjection and start capture on background thread
        Thread {
            try {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mgr.getMediaProjection(resultCode, data)
                if (mediaProjection == null) {
                    handler.post { updateOverlay("⚠️ Could not get MediaProjection") }
                    return@Thread
                }
                startCapture()
            } catch (e: Exception) {
                Log.e("NihongoLens", "MediaProjection error: ${e.message}")
                handler.post { updateOverlay("⚠️ Capture setup failed: ${e.message}") }
            }
        }.start()

        return START_STICKY
    }

    private fun startCapture() {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val minBuf = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                4096
            )

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(minBuf * 4)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("NihongoLens", "AudioRecord not initialized, state=${audioRecord?.state}")
                handler.post { updateOverlay("⚠️ Audio capture init failed") }
                return
            }

            audioRecord?.startRecording()
            isCapturing = true
            handler.post { updateOverlay("🎧 Capturing! Open a Japanese video now...") }
            Log.d("NihongoLens", "AudioRecord started successfully")

            captureThread = Thread {
                val buf = ShortArray(minBuf / 2)
                while (isCapturing) {
                    val read = audioRecord?.read(buf, 0, buf.size) ?: break
                    if (read > 0) {
                        synchronized(audioBuffer) {
                            for (i in 0 until read) audioBuffer.add(buf[i])
                            if (audioBuffer.size >= CHUNK_SAMPLES) {
                                val chunk = audioBuffer.toShortArray()
                                audioBuffer.clear()
                                processChunk(chunk)
                            }
                        }
                    }
                }
                Log.d("NihongoLens", "Capture thread ended")
            }
            captureThread?.start()

        } catch (e: Exception) {
            Log.e("NihongoLens", "startCapture error: ${e.message}")
            handler.post { updateOverlay("⚠️ ${e.message}") }
        }
    }

    private fun processChunk(samples: ShortArray) {
        // RMS energy check - skip silence
        var sum = 0.0
        for (s in samples) sum += s.toLong() * s
        val rms = Math.sqrt(sum / samples.size)
        Log.d("NihongoLens", "Chunk RMS: $rms")
        if (rms < 100) return

        val bytes = ByteBuffer.allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { b -> samples.forEach { b.putShort(it) } }
            .array()

        Thread {
            try {
                Log.d("NihongoLens", "Sending ${bytes.size} bytes to STT")
                val japanese = googleStt(bytes)
                Log.d("NihongoLens", "STT result: $japanese")
                if (!japanese.isNullOrBlank()) {
                    handler.post { updateOverlay("🔄 $japanese", isPartial = true) }
                    val english = mlkitTranslate(japanese)
                    Log.d("NihongoLens", "Translation: $english")
                    handler.post {
                        updateOverlay(english, japanese = japanese)
                        MainActivity.instance?.sendTranslation(japanese, english)
                    }
                }
            } catch (e: Exception) {
                Log.e("NihongoLens", "processChunk error: ${e.message}")
            }
        }.start()
    }

    private fun googleStt(pcmBytes: ByteArray): String? {
        return try {
            val b64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
            val body = """{"config":{"encoding":"LINEAR16","sampleRateHertz":$SAMPLE_RATE,"languageCode":"ja-JP","maxAlternatives":1},"audio":{"content":"$b64"}}"""
            val url = URL("https://speech.googleapis.com/v1/speech:recognize?key=AIzaSyBOti4mM-6x9WDnZIjIeyEU21OpBXqWBgw")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            Log.d("NihongoLens", "STT HTTP $code")
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader().readText()
                Log.d("NihongoLens", "STT response: $resp")
                JSONObject(resp).optJSONArray("results")
                    ?.getJSONObject(0)?.optJSONArray("alternatives")
                    ?.getJSONObject(0)?.optString("transcript")
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText()
                Log.e("NihongoLens", "STT error body: $err")
                null
            }
        } catch (e: Exception) {
            Log.e("NihongoLens", "STT exception: ${e.message}")
            null
        }
    }

    private fun mlkitTranslate(japanese: String): String {
        return try {
            val options = com.google.mlkit.nl.translate.TranslatorOptions.Builder()
                .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.JAPANESE)
                .setTargetLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
                .build()
            val translator = com.google.mlkit.nl.translate.Translation.getClient(options)
            var result = japanese
            val latch = CountDownLatch(1)
            translator.translate(japanese)
                .addOnSuccessListener { r -> result = r; latch.countDown() }
                .addOnFailureListener { latch.countDown() }
            latch.await(8, TimeUnit.SECONDS)
            translator.close()
            result
        } catch (e: Exception) {
            Log.e("NihongoLens", "MLKit error: ${e.message}")
            japanese
        }
    }

    private fun updateOverlay(text: String, japanese: String = "", isPartial: Boolean = false) {
        OverlayService.latestSubtitle = text
        if (japanese.isNotEmpty()) OverlayService.latestJapanese = japanese
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Audio Capture", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, AudioCaptureService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎌 Nihongo Lens")
            .setContentText("Translating Japanese audio...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .build()
    }

    override fun onDestroy() {
        isCapturing = false
        captureThread?.join(500)
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
