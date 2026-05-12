package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.TypedValue
import android.view.*
import android.view.animation.AlphaAnimation
import android.widget.*
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID = 3
        @Volatile var latestSubtitle = "🎧 Waiting for Japanese audio..."
        @Volatile var latestJapanese = ""
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var englishTv: TextView? = null
    private var japaneseTv: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var params: WindowManager.LayoutParams? = null
    @Volatile private var running = true

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Must call startForeground in onCreate for Android 14+
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.post { buildOverlay() }
        startUpdateLoop()
    }

    private fun buildOverlay() {
        try {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.argb(230, 0, 0, 0))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(2), Color.argb(220, 255, 59, 59))
                }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(this).apply {
                text = "🎌 Nihongo Lens"
                setTextColor(Color.argb(180, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            topRow.addView(TextView(this).apply {
                text = "  ✕  "
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setOnClickListener { stopSelf() }
            })

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).also { it.setMargins(0, dp(5), 0, dp(7)) }
                setBackgroundColor(Color.argb(150, 255, 59, 59))
            }

            japaneseTv = TextView(this).apply {
                text = ""
                setTextColor(Color.argb(160, 180, 180, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, dp(4)) }
            }

            englishTv = TextView(this).apply {
                text = "🎧 Waiting for Japanese audio..."
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                typeface = Typeface.DEFAULT_BOLD
                setLineSpacing(0f, 1.3f)
            }

            card.addView(topRow)
            card.addView(divider)
            card.addView(japaneseTv)
            card.addView(englishTv)
            overlayView = card

            val screenWidth = resources.displayMetrics.widthPixels
            params = WindowManager.LayoutParams(
                (screenWidth * 0.94).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(80)
            }

            // Draggable
            var sx = 0f; var sy = 0f; var ix = 0; var iy = 0
            card.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = ev.rawX; sy = ev.rawY
                        ix = params!!.x; iy = params!!.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params!!.x = ix + (ev.rawX - sx).toInt()
                        params!!.y = iy - (ev.rawY - sy).toInt()
                        try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                    }
                }
                true
            }

            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            android.util.Log.e("NihongoLens", "Overlay build error: ${e.message}")
        }
    }

    private fun startUpdateLoop() {
        var lastText = ""
        Thread {
            while (running) {
                try {
                    Thread.sleep(200)
                    val current = latestSubtitle
                    if (current != lastText) {
                        lastText = current
                        val jp = latestJapanese
                        handler.post {
                            if (jp.isNotEmpty()) japaneseTv?.text = jp
                            englishTv?.text = current
                            englishTv?.startAnimation(
                                AlphaAnimation(0.2f, 1f).apply { duration = 300 })
                        }
                    }
                } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Nihongo Overlay", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("🎌 Nihongo Lens — Active")
        .setContentText("Floating captions running")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        running = false
        try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
