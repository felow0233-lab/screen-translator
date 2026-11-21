package com.screentranslator.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Управляет двумя окнами поверх экрана:
 * 1) пузырёк-переключатель (тап — вкл/выкл перевод, перетаскивание — перемещение);
 * 2) полноэкранный оверлей с переведёнными блоками (пропускает касания в приложение под ним).
 */
class OverlayManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubble: View? = null
    private var overlayView: OverlayView? = null
    private var active = false

    /** Вызывается при включении/выключении перевода (тап по пузырьку). */
    var onToggle: ((Boolean) -> Unit)? = null

    private fun baseParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showBubble() {
        if (bubble != null) return
        val view = TextView(context).apply {
            text = "П"
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            val circle = GradientDrawable()
            circle.shape = GradientDrawable.OVAL
            circle.setColor(Color.parseColor("#CC2962FF"))
            background = circle
        }
        val size = (56 * context.resources.displayMetrics.density).roundToInt()
        val params = baseParams(size, size, touchable = true).apply {
            x = 0
            y = 200
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).roundToInt()
                    val dy = (e.rawY - downY).roundToInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) setActive(!active)
                    true
                }
                else -> false
            }
        }
        wm.addView(view, params)
        bubble = view
    }

    /** Включает/выключает показ перевода. */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (active) addOverlayView() else removeOverlayView()
        bubble?.alpha = if (active) 1.0f else 0.45f
        onToggle?.invoke(active)
    }

    fun setBlocks(blocks: List<TranslatedBlock>) {
        overlayView?.setBlocks(blocks)
    }

    fun clearBlocks() {
        overlayView?.setBlocks(emptyList())
    }

    fun removeAll() {
        removeOverlayView()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }

    private fun addOverlayView() {
        if (overlayView != null) return
        val view = OverlayView(context)
        wm.addView(view, baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            touchable = false
        ))
        overlayView = view
    }

    private fun removeOverlayView() {
        overlayView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlayView = null
    }
}

/** Рисует переведённые блоки поверх исходного текста. */
class OverlayView(context: Context) : View(context) {

    private val blocks = mutableListOf<TranslatedBlock>()
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D9FFFFFF")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2962FF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    fun setBlocks(newBlocks: List<TranslatedBlock>) {
        blocks.clear()
        blocks.addAll(newBlocks)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (b in blocks) {
            val r = RectF(b.rect)
            if (r.width() < 4 || r.height() < 4) continue
            canvas.drawRoundRect(r, 10f, 10f, boxPaint)
            canvas.drawRoundRect(r, 10f, 10f, borderPaint)

            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = (r.height() * 0.3f).coerceIn(20f, 46f)
            }
            val textWidth = (r.width() - 16).roundToInt().coerceAtLeast(20)
            val layout = StaticLayout.Builder
                .obtain(b.text, 0, b.text.length, textPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
            canvas.save()
            canvas.translate(r.left + 8f, r.top + 6f)
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
