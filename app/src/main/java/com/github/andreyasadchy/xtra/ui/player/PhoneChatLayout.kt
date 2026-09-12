package com.github.andreyasadchy.xtra.ui.player

import android.content.Context
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.edit
import androidx.core.view.doOnLayout
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.isTelevision
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.math.roundToInt

data class PhoneChatOverlayConfig(
    val xPercent: Int = 100,
    val yPercent: Int = 0,
    val widthPercent: Int = 38,
    val heightPercent: Int = 58,
    val opacityPercent: Int = 88,
) {
    val safeXPercent get() = xPercent.coerceIn(0, 100)
    val safeYPercent get() = yPercent.coerceIn(0, 100)
    val safeWidthPercent get() = widthPercent.coerceIn(22, 70)
    val safeHeightPercent get() = heightPercent.coerceIn(25, 90)
    val safeOpacityPercent get() = opacityPercent.coerceIn(40, 100)
}

fun phoneChatOverlayEnabled(context: Context): Boolean =
    !context.isTelevision() && context.prefs().getBoolean(C.PHONE_CHAT_OVERLAY_ENABLED, false)

fun phoneChatOverlayConfig(context: Context): PhoneChatOverlayConfig {
    val prefs = context.prefs()
    return PhoneChatOverlayConfig(
        xPercent = prefs.getInt(C.PHONE_CHAT_OVERLAY_X_PERCENT, 100),
        yPercent = prefs.getInt(C.PHONE_CHAT_OVERLAY_Y_PERCENT, 0),
        widthPercent = prefs.getInt(C.PHONE_CHAT_OVERLAY_WIDTH_PERCENT, 38),
        heightPercent = prefs.getInt(C.PHONE_CHAT_OVERLAY_HEIGHT_PERCENT, 58),
        opacityPercent = prefs.getInt(C.PHONE_CHAT_OVERLAY_OPACITY, 88),
    )
}

private fun restoreChatBackground(chat: View) {
    val background = TypedValue()
    if (chat.context.theme.resolveAttribute(android.R.attr.colorBackground, background, true)) {
        if (background.resourceId != 0) {
            chat.setBackgroundResource(background.resourceId)
        } else {
            chat.setBackgroundColor(background.data)
        }
    }
}

fun resetPhoneChatOverlayPresentation(chat: View, dragHandle: View) {
    dragHandle.visibility = View.GONE
    restoreChatBackground(chat)
    chat.alpha = 1f
    chat.elevation = 0f
}

fun resetPhoneChatOverlayLayout(
    container: View,
    width: Int,
    height: Int,
    gravity: Int,
) {
    val params = container.layoutParams as? FrameLayout.LayoutParams ?: return
    params.width = width
    params.height = height
    params.gravity = gravity
    params.setMargins(0, 0, 0, 0)
    params.marginStart = 0
    params.marginEnd = 0
    container.layoutParams = params
}

fun persistPhoneChatOverlayConfig(context: Context, config: PhoneChatOverlayConfig) {
    context.prefs().edit {
        putInt(C.PHONE_CHAT_OVERLAY_X_PERCENT, config.safeXPercent)
        putInt(C.PHONE_CHAT_OVERLAY_Y_PERCENT, config.safeYPercent)
        putInt(C.PHONE_CHAT_OVERLAY_WIDTH_PERCENT, config.safeWidthPercent)
        putInt(C.PHONE_CHAT_OVERLAY_HEIGHT_PERCENT, config.safeHeightPercent)
        putInt(C.PHONE_CHAT_OVERLAY_OPACITY, config.safeOpacityPercent)
    }
}

private fun phoneChatSize(parentSizePx: Int, configuredPercent: Int, minSizePx: Int, maxPercent: Int): Int {
    if (parentSizePx <= 0) return 0
    val percent = configuredPercent.coerceIn(15, maxPercent)
    return (parentSizePx * percent / 100f).roundToInt()
        .coerceIn(minSizePx.coerceAtMost(parentSizePx), parentSizePx)
}

private fun phoneChatOverlayMarginPx(context: Context): Int =
    context.resources.getDimensionPixelSize(R.dimen.phone_chat_overlay_margin)

private fun phoneChatBounds(
    parent: ViewGroup,
    width: Int,
    height: Int,
): IntArray {
    val margin = phoneChatOverlayMarginPx(parent.context)
    val minLeft = parent.paddingLeft + margin
    val minTop = parent.paddingTop + margin
    val maxLeft = (parent.width - parent.paddingRight - margin - width).coerceAtLeast(minLeft)
    val maxTop = (parent.height - parent.paddingBottom - margin - height).coerceAtLeast(minTop)
    return intArrayOf(minLeft, minTop, maxLeft, maxTop)
}

fun applyPhoneChatOverlayLayout(
    container: View,
    parent: ViewGroup,
    config: PhoneChatOverlayConfig,
    deferIfLayoutRequested: Boolean = true,
) {
    if (parent.context.isTelevision()) return
    if (parent.width <= 0 || parent.height <= 0 || (deferIfLayoutRequested && parent.isLayoutRequested)) {
        parent.doOnLayout { applyPhoneChatOverlayLayout(container, parent, config, deferIfLayoutRequested = false) }
        return
    }

    val availableWidth = (parent.width - parent.paddingLeft - parent.paddingRight).coerceAtLeast(1)
    val availableHeight = (parent.height - parent.paddingTop - parent.paddingBottom).coerceAtLeast(1)
    val width = phoneChatSize(
        availableWidth,
        config.safeWidthPercent,
        container.resources.getDimensionPixelSize(R.dimen.phone_chat_overlay_min_width),
        70,
    )
    val height = phoneChatSize(
        availableHeight,
        config.safeHeightPercent,
        container.resources.getDimensionPixelSize(R.dimen.phone_chat_overlay_min_height),
        90,
    )
    val bounds = phoneChatBounds(parent, width, height)
    val leftTravel = (bounds[2] - bounds[0]).coerceAtLeast(0)
    val topTravel = (bounds[3] - bounds[1]).coerceAtLeast(0)
    val left = bounds[0] + (leftTravel * config.safeXPercent / 100f).roundToInt()
    val top = bounds[1] + (topTravel * config.safeYPercent / 100f).roundToInt()

    container.layoutParams = FrameLayout.LayoutParams(width, height).apply {
        gravity = Gravity.TOP or Gravity.START
        leftMargin = left
        topMargin = top
    }
    container.alpha = 1f
    container.background?.alpha = (config.safeOpacityPercent * 255 / 100f).roundToInt()
    container.elevation = container.resources.displayMetrics.density * 8f
}

fun applyPhoneChatOverlayPresentation(
    chat: View,
    player: View,
    parent: ViewGroup,
    dragHandle: View,
    visible: Boolean,
    deferIfLayoutRequested: Boolean = true,
    isCurrent: () -> Boolean = { true },
): Boolean {
    if (parent.context.isTelevision() || !phoneChatOverlayEnabled(parent.context)) {
        resetPhoneChatOverlayPresentation(chat, dragHandle)
        return false
    }
    if (parent.width <= 0 || parent.height <= 0 || (deferIfLayoutRequested && parent.isLayoutRequested)) {
        parent.doOnLayout {
            if (isCurrent()) {
                applyPhoneChatOverlayPresentation(
                    chat,
                    player,
                    parent,
                    dragHandle,
                    visible,
                    deferIfLayoutRequested = false,
                    isCurrent = isCurrent,
                )
            }
        }
        return true
    }

    val playerParams = player.layoutParams as? FrameLayout.LayoutParams ?: return true
    if (!visible) {
        dragHandle.visibility = View.GONE
        chat.visibility = View.GONE
        playerParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        playerParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        playerParams.marginEnd = 0
        player.layoutParams = playerParams
        return true
    }

    playerParams.width = ViewGroup.LayoutParams.MATCH_PARENT
    playerParams.height = ViewGroup.LayoutParams.MATCH_PARENT
    playerParams.marginEnd = 0
    player.layoutParams = playerParams
    chat.background = chat.context.getDrawable(R.drawable.phone_chat_overlay_background)
    chat.visibility = View.VISIBLE
    dragHandle.visibility = View.VISIBLE
    applyPhoneChatOverlayLayout(
        chat,
        parent,
        phoneChatOverlayConfig(parent.context),
        deferIfLayoutRequested = false,
    )
    return true
}

/**
 * Keeps the normal chat touch forwarding intact while adding two-finger resize
 * and a small, explicit drag handle for the phone fullscreen overlay.
 */
class PhoneChatOverlayGestureController(
    context: Context,
    private val chat: View,
    private val parent: ViewGroup,
    private val dragHandle: View,
    private val dispatchChatTouch: (MotionEvent) -> Unit,
    private val onChatTouchActiveChanged: (Boolean) -> Unit = {},
    private val isInteractionLocked: () -> Boolean = { false },
) {
    private var active = false
    private var forwarding = false
    private var gestureConsumed = false
    private var scaling = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartLeft = 0
    private var dragStartTop = 0

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!active || !scaling || chat.width <= 0 || chat.height <= 0) return false

                val oldWidth = chat.width
                val oldHeight = chat.height
                val focusX = chat.left + detector.focusX
                val focusY = chat.top + detector.focusY
                val width = (oldWidth * detector.scaleFactor).roundToInt()
                val height = (oldHeight * detector.scaleFactor).roundToInt()
                val left = (focusX - detector.focusX / oldWidth * width).roundToInt()
                val top = (focusY - detector.focusY / oldHeight * height).roundToInt()
                updateBounds(left, top, width, height)
                return true
            }
        },
    )

    init {
        dragHandle.setOnTouchListener { _, event -> onDragHandleTouch(event) }
    }

    fun setActive(value: Boolean) {
        if (!value) cancel()
        active = value
        dragHandle.visibility = if (value) View.VISIBLE else View.GONE
    }

    fun onOverlayTouch(event: MotionEvent): Boolean {
        if (!active) return false
        if (isInteractionLocked()) {
            cancel()
            return true
        }

        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                forwarding = true
                gestureConsumed = false
                scaling = false
                onChatTouchActiveChanged(true)
                dispatchChatTouch(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    gestureConsumed = true
                    scaling = true
                    cancelForwardedTouch(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gestureConsumed && forwarding) {
                    dispatchChatTouch(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gestureConsumed && event.pointerCount <= 2) {
                    scaling = false
                    persistCurrentLayout()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!gestureConsumed && forwarding) {
                    dispatchChatTouch(event)
                }
                resetTouchState()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (forwarding) dispatchChatTouch(event)
                resetTouchState()
            }
        }
        return true
    }

    fun cancel() {
        if (forwarding) {
            MotionEvent.obtain(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL,
                0f,
                0f,
                0,
            ).also { event ->
                dispatchChatTouch(event)
                event.recycle()
            }
        }
        resetTouchState()
    }

    fun detach() {
        cancel()
        dragHandle.setOnTouchListener(null)
        active = false
    }

    private fun onDragHandleTouch(event: MotionEvent): Boolean {
        if (!active || isInteractionLocked()) {
            if (isInteractionLocked()) cancel()
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartLeft = chat.left
                dragStartTop = chat.top
            }
            MotionEvent.ACTION_MOVE -> {
                updateBounds(
                    dragStartLeft + (event.rawX - dragStartRawX).roundToInt(),
                    dragStartTop + (event.rawY - dragStartRawY).roundToInt(),
                    chat.width,
                    chat.height,
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> persistCurrentLayout()
        }
        return true
    }

    private fun cancelForwardedTouch(event: MotionEvent) {
        if (!forwarding) return
        MotionEvent.obtain(event).also { cancelEvent ->
            cancelEvent.action = MotionEvent.ACTION_CANCEL
            dispatchChatTouch(cancelEvent)
            cancelEvent.recycle()
        }
        forwarding = false
        onChatTouchActiveChanged(false)
    }

    private fun resetTouchState() {
        forwarding = false
        gestureConsumed = false
        scaling = false
        onChatTouchActiveChanged(false)
    }

    private fun updateBounds(left: Int, top: Int, requestedWidth: Int, requestedHeight: Int) {
        if (parent.width <= 0 || parent.height <= 0) return
        val minWidth = chat.resources.getDimensionPixelSize(R.dimen.phone_chat_overlay_min_width)
        val minHeight = chat.resources.getDimensionPixelSize(R.dimen.phone_chat_overlay_min_height)
        val maxWidth = (parent.width - parent.paddingLeft - parent.paddingRight - 2 * phoneChatOverlayMarginPx(parent.context)).coerceAtLeast(minWidth)
        val maxHeight = (parent.height - parent.paddingTop - parent.paddingBottom - 2 * phoneChatOverlayMarginPx(parent.context)).coerceAtLeast(minHeight)
        val width = requestedWidth.coerceIn(minWidth, maxWidth)
        val height = requestedHeight.coerceIn(minHeight, maxHeight)
        val bounds = phoneChatBounds(parent, width, height)
        val params = chat.layoutParams as? FrameLayout.LayoutParams ?: return
        params.width = width
        params.height = height
        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = left.coerceIn(bounds[0], bounds[2])
        params.topMargin = top.coerceIn(bounds[1], bounds[3])
        params.rightMargin = 0
        params.bottomMargin = 0
        chat.layoutParams = params
    }

    private fun persistCurrentLayout() {
        if (parent.width <= 0 || parent.height <= 0 || chat.width <= 0 || chat.height <= 0) return
        val bounds = phoneChatBounds(parent, chat.width, chat.height)
        val leftTravel = (bounds[2] - bounds[0]).coerceAtLeast(0)
        val topTravel = (bounds[3] - bounds[1]).coerceAtLeast(0)
        persistPhoneChatOverlayConfig(
            parent.context,
            phoneChatOverlayConfig(parent.context).copy(
                xPercent = if (leftTravel == 0) 0 else ((chat.left - bounds[0]) * 100f / leftTravel).roundToInt(),
                yPercent = if (topTravel == 0) 0 else ((chat.top - bounds[1]) * 100f / topTravel).roundToInt(),
                widthPercent = ((chat.width * 100f) / (parent.width - parent.paddingLeft - parent.paddingRight).coerceAtLeast(1)).roundToInt(),
                heightPercent = ((chat.height * 100f) / (parent.height - parent.paddingTop - parent.paddingBottom).coerceAtLeast(1)).roundToInt(),
            ),
        )
    }
}
