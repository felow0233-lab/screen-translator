package com.screentranslator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Foreground-сервис автоперевода:
 * 1) держит MediaProjection и периодически делает кадры экрана;
 * 2) прогоняет кадр через OCR + перевод;
 * 3) если текст на экране не изменился (подпись совпала) — не перерисовывает оверлей;
 * 4) показывает результат через OverlayManager.
 */
class TranslationService : Service() {

    companion object {
        const val ACTION_START = "com.screentranslator.app.START"
        const val ACTION_STOP = "com.screentranslator.app.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "screen_translate"
        private const val NOTIF_ID = 1
        private const val TAG = "TranslationService"
    }

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0

    private lateinit var overlay: OverlayManager
    private lateinit var engine: TranslatorEngine
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var busy = false
    private var lastSignature: String? = null
    private var intervalMs = 2000L

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (!busy) {
                busy = true
                captureAndTranslate()
            }
            handler.postDelayed(this, intervalMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayManager(this)
        engine = TranslatorEngine(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                engine.mode =
                    if (prefs.getString("mode", "offline") == "online")
                        TranslatorEngine.Mode.ONLINE
                    else
                        TranslatorEngine.Mode.OFFLINE
                engine.apiKey = prefs.getString("apiKey", "") ?: ""
                intervalMs = prefs.getLong("intervalMs", 2000L)

                startForeground(NOTIF_ID, buildNotification())

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (data == null) {
                    stopAll()
                    return START_NOT_STICKY
                }
                startProjection(resultCode, data)
            }
        }
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data) ?: run {
            stopAll()
            return
        }
        mediaProjection = projection
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                stopAll()
            }
        }
        projection.registerCallback(callback, handler)
        projectionCallback = callback

        val metrics = Resources.getSystem().displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "screen_translate",
            screenWidth, screenHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )

        // Скачиваем офлайн-модель (в офлайн-режиме), затем включаем автоперевод
        engine.prepare { ok ->
            if (!ok) Log.e(TAG, "Offline model unavailable, trying anyway")
            overlay.onToggle = { enabled ->
                running = enabled
                lastSignature = null
                handler.removeCallbacks(captureRunnable)
                if (enabled) {
                    handler.post(captureRunnable)
                } else {
                    overlay.clearBlocks()
                }
            }
            overlay.showBubble()
            overlay.setActive(true) // автоматический режим включён сразу
        }
    }

    private fun captureAndTranslate() {
        val reader = imageReader
        if (reader == null) {
            busy = false
            return
        }
        val image = try { reader.acquireLatestImage() } catch (e: Exception) {
            Log.e(TAG, "acquireLatestImage failed", e)
            null
        }
        if (image == null) {
            busy = false
            return
        }
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        val fullWidth = screenWidth + rowPadding / pixelStride

        var bitmap = Bitmap.createBitmap(fullWidth, screenHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(plane.buffer)
        image.close()
        if (fullWidth != screenWidth) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        }

        engine.process(bitmap) { blocks ->
            val signature = blocks.joinToString("|") { it.text }
            if (signature.isNotEmpty() && signature == lastSignature) {
                // Экран не изменился — оверлей уже корректен
            } else {
                lastSignature = signature
                overlay.setBlocks(blocks)
            }
            busy = false
        }
    }

    private fun stopAll() {
        running = false
        handler.removeCallbacks(captureRunnable)
        overlay.removeAll()
        engine.close()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        virtualDisplay = null
        imageReader = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(captureRunnable)
        overlay.removeAll()
        engine.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Перевод экрана", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, TranslationService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Перевод экрана")
            .setContentText("Автоперевод EN → RU активен. Тап по пузырьку — вкл/выкл.")
            .setSmallIcon(R.drawable.ic_stat_translate)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "Остановить", stopPending).build()
            )
            .build()
    }
}
