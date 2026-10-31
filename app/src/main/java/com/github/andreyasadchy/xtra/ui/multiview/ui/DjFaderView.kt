package com.github.andreyasadchy.xtra.ui.multiview.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A tactile hardware-style DJ crossfader / volume slider designed for the Stream Deck Studio panel.
 *
 * Features:
 * - 11 exact calibration tick marks at 0%, 10%, 20%, ..., 100%.
 * - Integrated scale labels (0%, 50%, 100%) mathematically aligned with tick marks.
 * - Illuminated center indicator line on the metallic fader cap that precisely matches tick marks.
 * - Soft-magnetic tactile snapping near every 10% interval.
 * - Complete touch tracking with ACTION_CANCEL handling and parent touch disallow.
 * - Accessibility support with range info and increment/decrement actions.
 */
class DjFaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var volume: Float = 1f
    private var onVolumeChanged: ((Float) -> Unit)? = null

    // Paint configurations
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dpToPx(9f)
        typeface = android.graphics.Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0B14")
        style = Paint.Style.FILL
    }

    private val trackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#25283D")
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.5f)
    }

    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F5D4")
        strokeWidth = dpToPx(1.5f)
    }

    private val midTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A970FF")
        strokeWidth = dpToPx(1.5f)
    }

    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D415A")
        strokeWidth = dpToPx(1f)
    }

    private val capBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1E2D")
        style = Paint.Style.FILL
    }

    private val capBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A5E82")
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.5f)
    }

    private val capGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F5D4")
        strokeWidth = dpToPx(2f)
    }

    // Dimensions
    private val capWidth = dpToPx(16f)
    private val capHeight = dpToPx(24f)
    private val capRadius = dpToPx(3f)

    init {
        isFocusable = true
        isClickable = true
        setupAccessibility()
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    fun setVolume(newVol: Float) {
        val clamped = newVol.coerceIn(0f, 1f)
        if (abs(clamped - volume) > 0.001f) {
            volume = clamped
            invalidate()
            updateContentDescription()
        }
    }

    fun getVolume(): Float = volume

    fun setOnVolumeChangedListener(listener: (Float) -> Unit) {
        onVolumeChanged = listener
    }

    private fun setupAccessibility() {
        ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = DjFaderView::class.java.name
                info.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
                    AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_FLOAT,
                    0f,
                    100f,
                    volume * 100f,
                )
                info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
                info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD)
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                return when (action) {
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD -> {
                        setVolume((volume + 0.1f).coerceIn(0f, 1f))
                        onVolumeChanged?.invoke(volume)
                        true
                    }
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> {
                        setVolume((volume - 0.1f).coerceIn(0f, 1f))
                        onVolumeChanged?.invoke(volume)
                        true
                    }
                    else -> super.performAccessibilityAction(host, action, args)
                }
            }
        })
        updateContentDescription()
    }

    private fun updateContentDescription() {
        val pct = (volume * 100).roundToInt()
        contentDescription = "Volume $pct%"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dpToPx(42f).toInt()
        val desiredWidth = dpToPx(180f).toInt()
        val w = resolveSize(desiredWidth, widthMeasureSpec)
        val h = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val pad = capWidth / 2f + dpToPx(10f)
        val trackW = w - (pad * 2f)
        if (trackW <= 0) return

        // Vertically partitioned:
        // Top section (0..14dp): Labels & Ticks
        // Bottom section (15..42dp): Recessed track & fader cap
        val labelY = dpToPx(10f)
        val tickBottom = dpToPx(17f)
        val tickTopMajor = dpToPx(11f)
        val tickTopMinor = dpToPx(14f)

        val trackCenterY = dpToPx(28f)
        val trackTop = trackCenterY - dpToPx(3f)
        val trackBottom = trackCenterY + dpToPx(3f)

        // 1. Draw 11 Hardware Calibration Ticks (Exact 10% steps) & Ruler Labels
        for (i in 0..10) {
            val tickX = pad + (trackW * (i / 10f))
            val isMajor = (i == 0 || i == 5 || i == 10)
            val paint = when (i) {
                0, 10 -> majorTickPaint
                5 -> midTickPaint
                else -> minorTickPaint
            }
            val tTop = if (isMajor) tickTopMajor else tickTopMinor
            canvas.drawLine(tickX, tTop, tickX, tickBottom, paint)

            // Draw aligned calibration text labels right at major positions
            if (i == 0) {
                labelPaint.color = Color.parseColor("#00F5D4")
                canvas.drawText("0%", tickX, labelY, labelPaint)
            } else if (i == 5) {
                labelPaint.color = Color.parseColor("#A970FF")
                canvas.drawText("50%", tickX, labelY, labelPaint)
            } else if (i == 10) {
                labelPaint.color = Color.parseColor("#00F5D4")
                canvas.drawText("100%", tickX, labelY, labelPaint)
            }
        }

        // 2. Draw Recessed Metallic Fader Track
        val trackRect = RectF(pad, trackTop, pad + trackW, trackBottom)
        canvas.drawRoundRect(trackRect, dpToPx(2f), dpToPx(2f), trackPaint)
        canvas.drawRoundRect(trackRect, dpToPx(2f), dpToPx(2f), trackBorderPaint)

        // 3. Draw DJ Fader Cap
        val knobCenterX = pad + (trackW * volume)
        val knobLeft = knobCenterX - (capWidth / 2f)
        val knobRight = knobCenterX + (capWidth / 2f)
        val knobTop = trackCenterY - (capHeight / 2f)
        val knobBottom = knobTop + capHeight

        val knobRect = RectF(knobLeft, knobTop, knobRight, knobBottom)
        canvas.drawRoundRect(knobRect, capRadius, capRadius, capBodyPaint)
        canvas.drawRoundRect(knobRect, capRadius, capRadius, capBorderPaint)

        // Center Illuminated Indicator Stripe (perfectly aligned with tick mark at current volume)
        canvas.drawLine(knobCenterX, knobTop + dpToPx(3f), knobCenterX, knobBottom - dpToPx(3f), capGlowPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pad = capWidth / 2f + dpToPx(10f)
        val trackW = width.toFloat() - (pad * 2f)
        if (trackW <= 0) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, pad, trackW, snap = false)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x, pad, trackW, snap = false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                updateFromTouch(event.x, pad, trackW, snap = true)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(touchX: Float, pad: Float, trackW: Float, snap: Boolean) {
        var rawRatio = ((touchX - pad) / trackW).coerceIn(0f, 1f)
        val nearestStep = (Math.round(rawRatio * 10f) / 10f).coerceIn(0f, 1f)
        if (snap || abs(rawRatio - nearestStep) <= 0.035f) {
            rawRatio = nearestStep
        }
        val rounded = (Math.round(rawRatio * 100f) / 100f).coerceIn(0f, 1f)
        if (abs(rounded - volume) > 0.001f) {
            volume = rounded
            invalidate()
            updateContentDescription()
            onVolumeChanged?.invoke(volume)
        }
    }
}
