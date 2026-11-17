package com.github.andreyasadchy.xtra.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.ui.common.ExpandedStreamCardTouchDelegate
import kotlin.math.abs

/** Lets a horizontal child scroll without stealing vertical drags or card taps it cannot consume. */
class DirectionalHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.horizontalScrollViewStyle,
) : HorizontalScrollView(context, attrs, defStyleAttr) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downScrollX = 0
    private var movedBeyondSlop = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downScrollX = scrollX
                movedBeyondSlop = false
                val canScroll = canScrollHorizontally(1) || canScrollHorizontally(-1)
                parent?.requestDisallowInterceptTouchEvent(canScroll)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = downX - event.x
                val dy = downY - event.y
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                    movedBeyondSlop = true
                    val horizontalGesture = abs(dx) > abs(dy)
                    val canScrollWithGesture = horizontalGesture &&
                        canScrollHorizontally(if (dx > 0f) 1 else -1)
                    parent?.requestDisallowInterceptTouchEvent(canScrollWithGesture)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isClickable && !isLongClickable) {
            if (!canScrollHorizontally(1) && !canScrollHorizontally(-1)) {
                return false
            }
            val handled = super.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP &&
                !movedBeyondSlop &&
                abs(scrollX - downScrollX) <= touchSlop &&
                ev.x in 0f..width.toFloat() &&
                ev.y in 0f..height.toFloat()
            ) {
                findClickableCardAncestor()?.performClick()
            }
            return handled
        }
        return super.onTouchEvent(ev)
    }

    private fun findClickableCardAncestor(): View? {
        var current = parent
        while (current is View && current !is RecyclerView) {
            if (current.isClickable && current.isEnabled) {
                val delegate = current.touchDelegate as? ExpandedStreamCardTouchDelegate
                if (delegate?.isDispatchingTagTouch == true) return null
                return current
            }
            current = current.parent
        }
        return null
    }
}
