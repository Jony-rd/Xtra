package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PlayerSwipeControlsPreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_player_swipe_controls_preview
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.findViewById<PlayerSwipeControlsPreviewView>(R.id.playerSwipeControlsPreview)
            .refreshPreview()
    }

    fun refreshPreview() = notifyChanged()
}

class PlayerSwipeControlsPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val frame = RectF()
    private var edgeWidthPercent = 37
    private var speedHeightPercent = 30
    private var leftGesture = "brightness"
    private var rightGesture = "volume"
    private var topGesture = "off"

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = false
        isClickable = false
    }

    fun refreshPreview() {
        val preferences = context.prefs()
        edgeWidthPercent = preferences.getInt(C.PLAYER_SWIPE_EDGE_WIDTH_PERCENT, 37).coerceIn(5, 50)
        speedHeightPercent = preferences.getInt(C.PLAYER_SWIPE_SPEED_ZONE_HEIGHT_PERCENT, 30).coerceIn(5, 75)
        leftGesture = preferences.getString(C.PLAYER_SWIPE_LEFT_GESTURE, "brightness") ?: "brightness"
        rightGesture = preferences.getString(C.PLAYER_SWIPE_RIGHT_GESTURE, "volume") ?: "volume"
        topGesture = preferences.getString(C.PLAYER_SWIPE_TOP_GESTURE, "off") ?: "off"
        contentDescription = context.getString(
            R.string.settings_player_swipe_zones_content_description,
            gestureLabel(leftGesture),
            edgeWidthPercent,
            100 - edgeWidthPercent * 2,
            gestureLabel(rightGesture),
            edgeWidthPercent,
            gestureLabel(topGesture),
            speedHeightPercent,
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = dp(4f)
        frame.set(inset, inset, width - inset, height - inset)
        if (frame.width() <= 0f || frame.height() <= 0f) return

        val radius = dp(6f)
        val outline = themeColor(com.google.android.material.R.attr.colorOutline, Color.GRAY)
        val surface = themeColor(com.google.android.material.R.attr.colorSurfaceContainerLow, Color.DKGRAY)
        val centerColor = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest, Color.BLACK)
        val brightnessColor = themeColor(com.google.android.material.R.attr.colorTertiaryContainer, 0xFF61511E.toInt())
        val volumeColor = themeColor(com.google.android.material.R.attr.colorPrimaryContainer, 0xFF17485B.toInt())
        val textPrimary = themeColor(com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val textSecondary = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.LTGRAY)
        val brightnessText = themeColor(com.google.android.material.R.attr.colorOnTertiaryContainer, textPrimary)
        val volumeText = themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer, textPrimary)
        val alpha = 1f

        fill.color = surface
        fill.alpha = (255 * alpha).roundToInt()
        canvas.drawRoundRect(frame, radius, radius, fill)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(frame, radius, radius, Path.Direction.CW) })

        val innerWidth = frame.width()
        val innerHeight = frame.height()
        val leftWidth = innerWidth * edgeWidthPercent / 100f
        val rightWidth = leftWidth
        val centerWidth = max(0f, innerWidth - leftWidth - rightWidth)
        val topHeight = innerHeight * speedHeightPercent / 100f
        val bottomTop = frame.top + topHeight

        drawZone(canvas, frame.left, bottomTop, frame.left + leftWidth, frame.bottom,
            actionColor(leftGesture, brightnessColor, volumeColor, centerColor), alpha)
        drawZone(canvas, frame.left + leftWidth, bottomTop, frame.left + leftWidth + centerWidth,
            frame.bottom, centerColor, alpha)
        drawZone(canvas, frame.right - rightWidth, bottomTop, frame.right,
            frame.bottom, actionColor(rightGesture, brightnessColor, volumeColor, centerColor), alpha)
        drawZone(canvas, frame.left, frame.top, frame.right, bottomTop,
            if (topGesture == "speed") volumeColor else centerColor, alpha)

        val topLabel = gestureLabel(topGesture)
        val topValue = "$speedHeightPercent%"
        val topCenterX = frame.centerX()
        if (topHeight >= dp(33f)) {
            drawLabel(canvas, topCenterX, frame.top + topHeight * 0.46f, topLabel,
                topValue, if (topGesture == "speed") volumeText else textSecondary,
                availableWidth = frame.width() - dp(12f))
        }

        val lowerHeight = frame.bottom - bottomTop
        val centerY = bottomTop + lowerHeight * 0.48f
        val actionLabelSize = 12f * scaledDensity
        val leftTextColor = actionText(leftGesture, brightnessText, volumeText, textSecondary)
        val rightTextColor = actionText(rightGesture, brightnessText, volumeText, textSecondary)
        val centerTextColor = textPrimary
        drawZoneLabel(canvas, frame.left, frame.left + leftWidth, centerY,
            gestureLabel(leftGesture), "$edgeWidthPercent%", leftTextColor, actionLabelSize)
        drawZoneLabel(canvas, frame.left + leftWidth, frame.left + leftWidth + centerWidth, centerY,
            context.getString(R.string.settings_native), "${100 - edgeWidthPercent * 2}%", centerTextColor, actionLabelSize)
        drawZoneLabel(canvas, frame.right - rightWidth, frame.right, centerY,
            gestureLabel(rightGesture), "$edgeWidthPercent%", rightTextColor, actionLabelSize)

        divider.color = outline
        divider.alpha = (200 * alpha).roundToInt()
        divider.strokeWidth = dp(1f)
        divider.pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
        canvas.drawLine(frame.left, bottomTop, frame.right, bottomTop, divider)
        divider.pathEffect = null
        divider.alpha = (255 * alpha).roundToInt()
        canvas.drawLine(frame.left + leftWidth, bottomTop, frame.left + leftWidth, frame.bottom, divider)
        canvas.drawLine(frame.right - rightWidth, bottomTop, frame.right - rightWidth, frame.bottom, divider)
        canvas.restore()

        border.color = outline
        border.strokeWidth = dp(1f)
        border.alpha = (255 * alpha).roundToInt()
        canvas.drawRoundRect(frame, radius, radius, border)
    }

    private fun drawZoneLabel(
        canvas: Canvas,
        left: Float,
        right: Float,
        centerY: Float,
        title: String,
        value: String,
        color: Int,
        titleSize: Float,
    ) {
        val zoneWidth = right - left
        titlePaint.textSize = titleSize
        valuePaint.textSize = 10f * scaledDensity
        if (zoneWidth < max(titlePaint.measureText(title), valuePaint.measureText(value)) + dp(8f)) return
        drawLabel(canvas, (left + right) / 2f, centerY, title, value, color, zoneWidth, titleSize)
    }

    private fun drawLabel(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        title: String,
        value: String,
        color: Int,
        availableWidth: Float,
        titleSize: Float = 12f * scaledDensity,
    ) {
        titlePaint.textSize = titleSize
        valuePaint.textSize = 10f * scaledDensity
        titlePaint.color = color
        valuePaint.color = color
        val paintAlpha = 255
        titlePaint.alpha = paintAlpha
        valuePaint.alpha = paintAlpha
        if (max(titlePaint.measureText(title), valuePaint.measureText(value)) + dp(8f) > availableWidth) return
        val gap = dp(3f)
        val combinedHeight = titlePaint.textSize + valuePaint.textSize + gap
        val titleBaseline = centerY - combinedHeight / 2f - titlePaint.ascent()
        canvas.drawText(title, centerX, titleBaseline, titlePaint)
        canvas.drawText(value, centerX, titleBaseline + titlePaint.descent() + gap - valuePaint.ascent(), valuePaint)
    }

    private fun drawZone(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int, alpha: Float) {
        if (right <= left || bottom <= top) return
        fill.color = color
        fill.alpha = (255 * alpha).roundToInt()
        canvas.drawRect(left, top, right, bottom, fill)
    }

    private fun actionColor(action: String, brightness: Int, volume: Int, native: Int): Int = when (action) {
        "brightness" -> brightness
        "volume" -> volume
        else -> native
    }

    private fun actionText(action: String, brightness: Int, volume: Int, native: Int): Int = when (action) {
        "brightness" -> brightness
        "volume" -> volume
        else -> native
    }

    private fun gestureLabel(action: String): String = when (action) {
        "brightness" -> context.getString(R.string.settings_player_swipe_brightness)
        "volume" -> context.getString(R.string.settings_player_swipe_volume)
        "speed" -> context.getString(R.string.settings_player_swipe_speed)
        else -> context.getString(R.string.settings_player_swipe_off)
    }

    private fun themeColor(attribute: Int, fallback: Int): Int = MaterialColors.getColor(this, attribute, fallback)

    private fun dp(value: Float): Float = value * density
}
