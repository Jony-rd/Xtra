package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.NestedScrollingChild3
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat

/**
 * A [CoordinatorLayout] that can forward vertical nested scrolling and flings to an ancestor
 * [CoordinatorLayout] (for example, when embedded inside a `ViewPager2` tab page of
 * `BrowsePagerFragment`).
 */
class NestedScrollForwardingCoordinatorLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : CoordinatorLayout(context, attrs, defStyleAttr), NestedScrollingChild3 {

    private val childHelper = NestedScrollingChildHelper(this)
    private val parentConsumed = IntArray(2)
    private val childConsumed = IntArray(2)

    var forwardScrollToParent: Boolean = false
        set(value) {
            field = value
            childHelper.isNestedScrollingEnabled = value
        }

    override fun isNestedScrollingEnabled(): Boolean =
        if (forwardScrollToParent) childHelper.isNestedScrollingEnabled else super.isNestedScrollingEnabled()

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        childHelper.isNestedScrollingEnabled = enabled
        super.setNestedScrollingEnabled(enabled)
    }

    override fun startNestedScroll(axes: Int): Boolean = startNestedScroll(axes, ViewCompat.TYPE_TOUCH)

    override fun startNestedScroll(axes: Int, type: Int): Boolean =
        if (forwardScrollToParent) childHelper.startNestedScroll(axes, type) else false

    override fun stopNestedScroll() = stopNestedScroll(ViewCompat.TYPE_TOUCH)

    override fun stopNestedScroll(type: Int) {
        childHelper.stopNestedScroll(type)
    }

    override fun hasNestedScrollingParent(): Boolean = hasNestedScrollingParent(ViewCompat.TYPE_TOUCH)

    override fun hasNestedScrollingParent(type: Int): Boolean =
        forwardScrollToParent && childHelper.hasNestedScrollingParent(type)

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?,
    ): Boolean = dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, ViewCompat.TYPE_TOUCH)

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?,
        type: Int,
    ): Boolean =
        forwardScrollToParent && childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
    ): Boolean = dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, ViewCompat.TYPE_TOUCH)

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int,
    ): Boolean =
        forwardScrollToParent && childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type)

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int,
        consumed: IntArray,
    ) {
        if (forwardScrollToParent) {
            childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type, consumed)
        }
    }

    override fun dispatchNestedPreFling(velocityX: Float, velocityY: Float): Boolean =
        forwardScrollToParent && childHelper.dispatchNestedPreFling(velocityX, velocityY)

    override fun dispatchNestedFling(velocityX: Float, velocityY: Float, consumed: Boolean): Boolean =
        forwardScrollToParent && childHelper.dispatchNestedFling(velocityX, velocityY, consumed)

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean {
        val superAccepted = super.onStartNestedScroll(child, target, axes, type)
        val parentAccepted = if (forwardScrollToParent && (axes and ViewCompat.SCROLL_AXIS_VERTICAL) != 0) {
            startNestedScroll(axes and ViewCompat.SCROLL_AXIS_VERTICAL, type)
        } else {
            false
        }
        return superAccepted || parentAccepted
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        super.onStopNestedScroll(target, type)
        if (forwardScrollToParent) {
            stopNestedScroll(type)
        }
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        if (!forwardScrollToParent) {
            super.onNestedPreScroll(target, dx, dy, consumed, type)
            return
        }
        parentConsumed[0] = 0
        parentConsumed[1] = 0
        dispatchNestedPreScroll(dx, dy, parentConsumed, null, type)

        val remainingDx = dx - parentConsumed[0]
        val remainingDy = dy - parentConsumed[1]
        childConsumed[0] = 0
        childConsumed[1] = 0
        super.onNestedPreScroll(target, remainingDx, remainingDy, childConsumed, type)

        consumed[0] = parentConsumed[0] + childConsumed[0]
        consumed[1] = parentConsumed[1] + childConsumed[1]
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray,
    ) {
        childConsumed[0] = 0
        childConsumed[1] = 0
        super.onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, childConsumed)
        consumed[0] += childConsumed[0]
        consumed[1] += childConsumed[1]

        if (forwardScrollToParent) {
            val remainingUnconsumedX = dxUnconsumed - childConsumed[0]
            val remainingUnconsumedY = dyUnconsumed - childConsumed[1]
            parentConsumed[0] = 0
            parentConsumed[1] = 0
            dispatchNestedScroll(
                dxConsumed + childConsumed[0],
                dyConsumed + childConsumed[1],
                remainingUnconsumedX,
                remainingUnconsumedY,
                null,
                type,
                parentConsumed,
            )
            consumed[0] += parentConsumed[0]
            consumed[1] += parentConsumed[1]
        }
    }

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean {
        if (forwardScrollToParent && dispatchNestedPreFling(velocityX, velocityY)) {
            return true
        }
        return super.onNestedPreFling(target, velocityX, velocityY)
    }

    override fun onNestedFling(
        target: View,
        velocityX: Float,
        velocityY: Float,
        consumed: Boolean,
    ): Boolean {
        val superConsumed = super.onNestedFling(target, velocityX, velocityY, consumed)
        val parentConsumed = if (forwardScrollToParent) {
            dispatchNestedFling(velocityX, velocityY, consumed || superConsumed)
        } else {
            false
        }
        return superConsumed || parentConsumed
    }
}
