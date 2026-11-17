package com.github.andreyasadchy.xtra.ui.multiview.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.common.loadStreamProfileImage
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewSessionReducer
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewSessionState
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewViewModel
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewLiveHealth
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewPlaybackSnapshot
import kotlin.math.roundToInt

object MultiviewStreamDeckDialog {
    // Cyber / Marathon Solid-Glass & Purple Accent Palette
    private const val COLOR_PURPLE = "#A855F7"
    private const val COLOR_PURPLE_BRIGHT = "#C084FC"
    private const val COLOR_PURPLE_GLASS_TOP = "#E6231040"
    private const val COLOR_PURPLE_GLASS_BOTTOM = "#F0120724"
    private const val COLOR_PURPLE_SHADOW = "#080212"
    private const val COLOR_PURPLE_STROKE = "#C084FC"

    private const val COLOR_VOLT = "#C2FF00"
    private const val COLOR_VOLT_GLASS_TOP = "#E6172207"
    private const val COLOR_VOLT_GLASS_BOTTOM = "#F00A1003"
    private const val COLOR_VOLT_SHADOW = "#040701"
    private const val COLOR_VOLT_STROKE = "#C2FF00"

    private const val COLOR_CYAN = "#00E5FF"
    private const val COLOR_CYAN_GLASS_TOP = "#E6081F26"
    private const val COLOR_CYAN_GLASS_BOTTOM = "#F0041014"
    private const val COLOR_CYAN_SHADOW = "#010709"
    private const val COLOR_CYAN_STROKE = "#00E5FF"

    private const val COLOR_CRIMSON = "#FF2E63"
    private const val COLOR_CRIMSON_GLASS_TOP = "#E6260813"
    private const val COLOR_CRIMSON_GLASS_BOTTOM = "#F0120409"
    private const val COLOR_CRIMSON_SHADOW = "#090104"
    private const val COLOR_CRIMSON_STROKE = "#FF2E63"

    private const val COLOR_AMBER = "#FF5E1A"
    private const val COLOR_AMBER_GLASS_TOP = "#E6291105"
    private const val COLOR_AMBER_GLASS_BOTTOM = "#F0140702"
    private const val COLOR_AMBER_SHADOW = "#090301"
    private const val COLOR_AMBER_STROKE = "#FF5E1A"

    private const val COLOR_UNSEL_GLASS_TOP = "#AD131524"
    private const val COLOR_UNSEL_GLASS_BOTTOM = "#BF090B14"
    private const val COLOR_UNSEL_SHADOW = "#85020206"
    private const val COLOR_UNSEL_STROKE = "#665B4B8A"

    private const val AUTO_DISMISS_MS = 3_000L
    private const val HEALTH_REFRESH_MS = 1_000L
    private val STANDARD_QUALITIES = listOf("1080p", "720p", "480p", "360p", "160p")

    private class ActiveOverlay(
        val hostContainer: FrameLayout,
        val dismissFn: () -> Unit,
    )

    private class DeckUiContext(
        val context: Context,
        val density: Float,
        val isLandscape: Boolean,
        val autoLabel: String,
        val viewModel: MultiviewViewModel,
        val stateProvider: () -> MultiviewSessionState,
        val playbackProvider: () -> Map<String, MultiviewPlaybackSnapshot>,
        val healthProvider: () -> Map<String, MultiviewLiveHealth>,
    ) {
        fun dp(value: Int): Int = (value * density).roundToInt()
        fun dp(value: Float): Int = (value * density).roundToInt()

        fun displayName(stream: Stream): String =
            stream.channelName?.takeIf { it.isNotBlank() }
                ?: stream.channelLogin?.takeIf { it.isNotBlank() }
                ?: stream.id.orEmpty()

        fun make3DKeycap(
            topColorHex: String,
            bottomColorHex: String,
            shadowColorHex: String,
            strokeColorHex: String,
            cornerRadiusDp: Float = 6f,
            shadowDepthDp: Float = 3f,
        ): Drawable {
            fun shade(c: Int, f: Float): Int = Color.argb(
                Color.alpha(c),
                (Color.red(c) * f).toInt().coerceIn(0, 255),
                (Color.green(c) * f).toInt().coerceIn(0, 255),
                (Color.blue(c) * f).toInt().coerceIn(0, 255),
            )

            fun build(depthPx: Int, pressed: Boolean): Drawable {
                val radiusPx = dp(cornerRadiusDp).toFloat()
                val plinth = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.parseColor(shadowColorHex),
                        shade(Color.parseColor(shadowColorHex), 0.7f),
                    ),
                ).apply {
                    cornerRadius = radiusPx
                    setStroke(dp(0.8f).coerceAtLeast(1), Color.parseColor("#66000000"))
                }

                val topColor = Color.parseColor(topColorHex)
                val bottomColor = Color.parseColor(bottomColorHex)
                val faceColors = if (pressed) {
                    intArrayOf(shade(topColor, 0.84f), shade(bottomColor, 0.84f))
                } else {
                    intArrayOf(shade(topColor, 1.15f), topColor, bottomColor)
                }
                val face = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    faceColors,
                ).apply {
                    cornerRadius = radiusPx
                    setStroke(
                        dp(1.1f).coerceAtLeast(1),
                        Color.parseColor(if (pressed) "#FFFFFF" else strokeColorHex),
                    )
                }

                val topGloss = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.parseColor("#30FFFFFF"),
                        Color.TRANSPARENT,
                    ),
                ).apply {
                    cornerRadius = (radiusPx - dp(1)).coerceAtLeast(0f)
                }

                val layer = LayerDrawable(arrayOf(plinth, face, topGloss))
                if (pressed) {
                    val pressInset = dp(shadowDepthDp)
                    layer.setLayerInset(0, 0, pressInset, 0, 0)
                    layer.setLayerInset(1, 0, pressInset, 0, 0)
                    layer.setLayerInset(2, dp(1), pressInset + dp(1), dp(1), pressInset / 2)
                } else {
                    layer.setLayerInset(0, 0, depthPx, 0, 0)
                    layer.setLayerInset(1, 0, 0, 0, depthPx)
                    layer.setLayerInset(2, dp(1), dp(1), dp(1), depthPx + dp(2))
                }
                return layer
            }

            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), build(0, true))
                addState(intArrayOf(), build(dp(shadowDepthDp), false))
            }
        }

        fun centerKeycapText(tv: TextView, depthDp: Float = 3f, sidePadDp: Float = 2f) {
            tv.gravity = Gravity.CENTER
            tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
            tv.includeFontPadding = false
            tv.setPadding(dp(sidePadDp), 0, dp(sidePadDp), dp(depthDp))
        }

        fun deckRipple(base: Drawable): Drawable =
            RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#55A855F7")),
                base,
                null,
            )
    }

    private var activeOverlay: ActiveOverlay? = null

    fun isShowing(): Boolean = activeOverlay != null

    fun dismiss(): Boolean {
        val current = activeOverlay ?: return false
        current.dismissFn()
        return true
    }

    fun show(
        context: Context,
        hostContainer: FrameLayout,
        toolbarView: View,
        toggleButton: View,
        isFloatingWindow: Boolean,
        anchorBottomPx: Int? = null,
        stateProvider: () -> MultiviewSessionState,
        playbackProvider: () -> Map<String, MultiviewPlaybackSnapshot>,
        healthProvider: () -> Map<String, MultiviewLiveHealth>,
        viewModel: MultiviewViewModel,
        onOpenSleepTimer: () -> Unit,
        onOpenNormalPlayer: (String) -> Unit,
        onUnlockControls: () -> Unit,
    ) {
        if (dismiss()) {
            onUnlockControls()
            return
        }

        val initialState = stateProvider()
        if (initialState.streams.isEmpty()) {
            onUnlockControls()
            return
        }

        var unlocked = false
        fun unlockOnce() {
            if (!unlocked) {
                unlocked = true
                onUnlockControls()
            }
        }

        try {
            val res = context.resources
            val metrics = res.displayMetrics
            val isLandscape = res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            // Measure against the always-visible parent container (multiviewScreenRoot)
            // because hostContainer starts View.GONE (width=0) and retains stale dimensions
            // from the previous orientation if rotated while hidden.
            val measureTarget = (hostContainer.parent as? View)
                ?.takeIf { it.width > 0 && it.height > 0 }
                ?: hostContainer.takeIf { it.isVisible && it.width > 0 && it.height > 0 }
            val rawW = measureTarget?.width ?: metrics.widthPixels
            val rawH = measureTarget?.height ?: metrics.heightPixels
            val needsAxisSwap = (isLandscape && rawW < rawH) || (!isLandscape && rawW > rawH)
            val hostWidth = (if (needsAxisSwap) rawH else rawW).coerceAtLeast(1)
            val hostHeight = (if (needsAxisSwap) rawW else rawH).coerceAtLeast(1)

            val hostLoc = IntArray(2)
            (measureTarget ?: hostContainer).getLocationOnScreen(hostLoc)
            val localAnchorBottom = anchorBottomPx?.let { (it - hostLoc[1]).coerceAtLeast(0) }

            val shouldAnchorUnderHeader = !isLandscape && !isFloatingWindow &&
                (initialState.layoutMode == MultiviewLayoutMode.FOCUS || initialState.streams.size <= 1) &&
                localAnchorBottom != null

            val ui = DeckUiContext(
                context = context,
                density = metrics.density,
                isLandscape = isLandscape,
                autoLabel = context.getString(R.string.multiview_quality_auto),
                viewModel = viewModel,
                stateProvider = stateProvider,
                playbackProvider = playbackProvider,
                healthProvider = healthProvider,
            )

            val appContext = context.applicationContext
            val uiHandler = Handler(Looper.getMainLooper())
            var overlayRef: ActiveOverlay? = null
            var cleanedUp = false
            var volumeReceiver: BroadcastReceiver? = null

            val healthUpdaters = mutableMapOf<String, (MultiviewLiveHealth?) -> Unit>()
            val cardVolumeUpdaters = mutableMapOf<String, (Float) -> Unit>()
            val cardPillUpdaters = mutableListOf<() -> Unit>()
            var syncSystemVolumeUi: (() -> Unit)? = null

            val dismissRunnable = Runnable { overlayRef?.dismissFn?.invoke() }
            fun resetDismissTimer() {
                uiHandler.removeCallbacks(dismissRunnable)
                uiHandler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
            }

            val healthTickerRunnable = object : Runnable {
                override fun run() {
                    if (cleanedUp || activeOverlay !== overlayRef) return
                    val healthMap = try {
                        healthProvider()
                    } catch (_: Throwable) {
                        emptyMap()
                    }
                    healthUpdaters.forEach { (id, updater) -> updater(healthMap[id]) }
                    syncSystemVolumeUi?.invoke()
                    uiHandler.postDelayed(this, HEALTH_REFRESH_MS)
                }
            }

            fun cleanupOverlay() {
                if (cleanedUp) return
                cleanedUp = true
                if (activeOverlay === overlayRef) {
                    activeOverlay = null
                }
                uiHandler.removeCallbacks(dismissRunnable)
                uiHandler.removeCallbacks(healthTickerRunnable)
                volumeReceiver?.let { receiver ->
                    try {
                        appContext.unregisterReceiver(receiver)
                    } catch (_: Throwable) {
                    }
                    volumeReceiver = null
                }
                hostContainer.removeAllViews()
                hostContainer.visibility = View.GONE
                viewModel.persistSession()
                unlockOnce()
            }

            overlayRef = ActiveOverlay(hostContainer, ::cleanupOverlay)
            activeOverlay = overlayRef

            val baseMaxHeight = (hostHeight * (if (isLandscape) 0.92f else 0.84f)).roundToInt()
            val availBelowHeader = if (shouldAnchorUnderHeader && localAnchorBottom != null) {
                hostHeight - localAnchorBottom - ui.dp(8)
            } else {
                baseMaxHeight
            }
            var maxDialogHeight = if (shouldAnchorUnderHeader && availBelowHeader >= ui.dp(260)) {
                minOf(baseMaxHeight, availBelowHeader)
            } else {
                baseMaxHeight
            }

            var headerRef: LinearLayout? = null
            var systemCardRef: LinearLayout? = null
            var masterCardRef: LinearLayout? = null
            var bottomMacrosRef: LinearLayout? = null
            var streamScrollMaxCapPx = (maxDialogHeight * 0.56f).roundToInt()

            val chassis = buildChassis(ui, { maxDialogHeight }) { innerWidth ->
                val fixedViews = listOfNotNull(headerRef, systemCardRef, masterCardRef, bottomMacrosRef)
                val innerWSpec = View.MeasureSpec.makeMeasureSpec(innerWidth, View.MeasureSpec.EXACTLY)
                val looseHSpec = View.MeasureSpec.makeMeasureSpec(maxDialogHeight, View.MeasureSpec.AT_MOST)
                var fixedH = paddingTop + paddingBottom
                fixedViews.forEach { child ->
                    child.measure(innerWSpec, looseHSpec)
                    val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
                    fixedH += child.measuredHeight + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
                }
                streamScrollMaxCapPx = (maxDialogHeight - fixedH).coerceAtLeast(ui.dp(84))
            }

            // 1. Header bar
            val header = buildHeaderBar(
                ui = ui,
                onSleepClick = {
                    cleanupOverlay()
                    try {
                        onOpenSleepTimer()
                    } catch (_: Throwable) {
                    }
                },
                onCloseClick = ::cleanupOverlay,
            )
            headerRef = header
            chassis.addView(header)

            // 2. Standalone top module: System Out
            val systemVolumeSetup = buildSystemVolumeModule(
                ui = ui,
                appContext = appContext,
                isActive = { !cleanedUp && activeOverlay === overlayRef },
            )
            systemCardRef = systemVolumeSetup.card
            syncSystemVolumeUi = systemVolumeSetup.syncUi
            volumeReceiver = systemVolumeSetup.receiver
            chassis.addView(systemVolumeSetup.card)

            // 3. Master bus module (Acid-Volt all-streams quality + volume)
            val masterBus = buildMasterBusModule(
                ui = ui,
                initialState = initialState,
                cardVolumeUpdaters = cardVolumeUpdaters,
                cardPillUpdaters = cardPillUpdaters,
            )
            masterCardRef = masterBus.card
            chassis.addView(masterBus.card)

            // 4. Stream mixer channel strips
            val streamScroll = object : ScrollView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val constrained = MeasureSpec.makeMeasureSpec(streamScrollMaxCapPx, MeasureSpec.AT_MOST)
                    super.onMeasure(widthMeasureSpec, constrained)
                }
            }.apply {
                isVerticalScrollBarEnabled = false
                isFillViewport = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val streamListLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            streamScroll.addView(streamListLayout)

            lateinit var populateCards: () -> Unit
            populateCards = {
                populateStreamCards(
                    ui = ui,
                    container = streamListLayout,
                    cardPillUpdaters = cardPillUpdaters,
                    cardVolumeUpdaters = cardVolumeUpdaters,
                    healthUpdaters = healthUpdaters,
                    onMasterRefresh = { masterBus.refreshPills() },
                    onOpenNormalPlayer = { id ->
                        cleanupOverlay()
                        onOpenNormalPlayer(id)
                    },
                    onDismissIfEmpty = ::cleanupOverlay,
                    onCardsChanged = { populateCards() },
                )
            }
            populateCards()
            chassis.addView(streamScroll)

            // 5. Bottom macro bar
            val bottomMacros = buildMacroDeck(
                ui = ui,
                onAfterMacro = { firstVol ->
                    masterBus.updateAudioUi(firstVol, updateFader = true)
                    populateCards()
                },
                onDismiss = ::cleanupOverlay,
            )
            bottomMacrosRef = bottomMacros
            chassis.addView(bottomMacros)

            // 6. Measure, position, and attach inside hostContainer
            val cardWidth = (hostWidth * if (isLandscape) 0.70f else 0.94f).roundToInt()
            chassis.measure(
                View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxDialogHeight, View.MeasureSpec.AT_MOST),
            )
            val measuredDeckHeight = chassis.measuredHeight.coerceAtLeast(ui.dp(180))

            val chassisLp = FrameLayout.LayoutParams(
                cardWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            when {
                isLandscape || isFloatingWindow -> chassisLp.gravity = Gravity.CENTER
                initialState.layoutMode == MultiviewLayoutMode.FOCUS || initialState.streams.size <= 1 -> {
                    chassisLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    val targetY = localAnchorBottom ?: ui.dp(48)
                    val maxAllowedY = (hostHeight - measuredDeckHeight - ui.dp(8)).coerceAtLeast(0)
                    chassisLp.topMargin = targetY.coerceIn(0, maxAllowedY)
                }
                initialState.layoutMode == MultiviewLayoutMode.TIERED -> {
                    chassisLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    chassisLp.bottomMargin = (hostHeight * 0.14f).roundToInt()
                }
                else -> {
                    chassisLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    chassisLp.bottomMargin = ui.dp(16)
                }
            }

            val overlayWrapper = createTouchInterceptWrapper(
                context = context,
                chassis = chassis,
                toolbarView = toolbarView,
                toggleButton = toggleButton,
                onInteraction = ::resetDismissTimer,
                onDismissImmediate = ::cleanupOverlay,
                onDismissDeferred = { view -> view.post { cleanupOverlay() } },
            )
            overlayWrapper.addView(chassis, chassisLp)

            hostContainer.removeAllViews()
            hostContainer.addView(
                overlayWrapper,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            hostContainer.visibility = View.VISIBLE
            hostContainer.bringToFront()

            val fromTop = !isLandscape && !isFloatingWindow &&
                (initialState.layoutMode == MultiviewLayoutMode.FOCUS || initialState.streams.size <= 1)
            chassis.alpha = 0f
            chassis.translationY = ui.dp(if (fromTop) -10 else 14).toFloat()
            chassis.post {
                if (cleanedUp || activeOverlay !== overlayRef) return@post
                val lp = chassis.layoutParams as FrameLayout.LayoutParams
                var lpChanged = false
                if (hostContainer.width > 0 && hostContainer.height > 0) {
                    val actualWidth = (hostContainer.width * if (isLandscape) 0.70f else 0.94f).roundToInt()
                    if (actualWidth > 0 && lp.width != actualWidth) {
                        lp.width = actualWidth
                        lpChanged = true
                    }
                    val actualBaseMaxH = (hostContainer.height * (if (isLandscape) 0.92f else 0.84f)).roundToInt()
                    val actualAvailBelow = if (shouldAnchorUnderHeader && localAnchorBottom != null) {
                        hostContainer.height - localAnchorBottom - ui.dp(8)
                    } else {
                        actualBaseMaxH
                    }
                    val actualMaxH = if (shouldAnchorUnderHeader && actualAvailBelow >= ui.dp(260)) {
                        minOf(actualBaseMaxH, actualAvailBelow)
                    } else {
                        actualBaseMaxH
                    }
                    if (actualMaxH > 0 && maxDialogHeight != actualMaxH) {
                        maxDialogHeight = actualMaxH
                        lpChanged = true
                    }
                }
                if (fromTop && localAnchorBottom != null) {
                    val deckHeight = chassis.height.takeIf { it > 0 } ?: chassis.measuredHeight
                    val containerH = hostContainer.height.takeIf { it > 0 } ?: hostHeight
                    val maxAllowedY = (containerH - deckHeight - ui.dp(8)).coerceAtLeast(0)
                    val targetY = localAnchorBottom.coerceIn(0, maxAllowedY)
                    if (lp.topMargin != targetY) {
                        lp.topMargin = targetY
                        lpChanged = true
                    }
                }
                if (lpChanged) {
                    chassis.layoutParams = lp
                    chassis.requestLayout()
                }
                chassis.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(170L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            resetDismissTimer()
            uiHandler.postDelayed(healthTickerRunnable, HEALTH_REFRESH_MS)
        } catch (_: Throwable) {
            hostContainer.removeAllViews()
            hostContainer.visibility = View.GONE
            activeOverlay = null
            unlockOnce()
        }
    }

    private fun buildChassis(
        ui: DeckUiContext,
        maxDialogHeightProvider: () -> Int,
        onPreMeasureFixed: LinearLayout.(Int) -> Unit,
    ): LinearLayout {
        val chassisRadius = ui.dp(14).toFloat()
        val glassFill = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#4706060D"),
                Color.parseColor("#59040409"),
                Color.parseColor("#4D0A0614"),
            ),
        ).apply {
            cornerRadius = chassisRadius
            setStroke(ui.dp(1.25f).coerceAtLeast(1), Color.parseColor("#B39D4EDD"))
        }
        val glassBevelHighlight = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.parseColor("#24C084FC"),
                Color.parseColor("#06FFFFFF"),
                Color.TRANSPARENT,
            ),
        ).apply {
            cornerRadius = chassisRadius
            setStroke(ui.dp(1), Color.parseColor("#445B21B6"))
        }
        val chassisLayer = LayerDrawable(arrayOf(glassFill, glassBevelHighlight)).apply {
            setLayerInset(1, ui.dp(1), ui.dp(1), ui.dp(1), ui.dp(1))
        }

        return object : LinearLayout(ui.context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val widthSize = MeasureSpec.getSize(widthMeasureSpec)
                val innerWidth = (widthSize - paddingLeft - paddingRight).coerceAtLeast(1)
                onPreMeasureFixed(innerWidth)

                val maxDialogH = maxDialogHeightProvider()
                val rawH = MeasureSpec.getSize(heightMeasureSpec)
                val capH = if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
                    val limit = if (rawH > 0) minOf(rawH, maxDialogH) else maxDialogH
                    MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
                } else {
                    heightMeasureSpec
                }
                super.onMeasure(widthMeasureSpec, capH)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(ui.dp(if (ui.isLandscape) 9 else 8), ui.dp(6), ui.dp(if (ui.isLandscape) 9 else 8), ui.dp(6))
            background = chassisLayer
            elevation = 0f
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, chassisRadius)
                }
            }
            clipToOutline = true
        }
    }

    private fun buildHeaderBar(
        ui: DeckUiContext,
        onSleepClick: () -> Unit,
        onCloseClick: () -> Unit,
    ): LinearLayout {
        val header = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(8), ui.dp(4), ui.dp(6), ui.dp(4))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#36120D26"), Color.parseColor("#42090714")),
            ).apply {
                cornerRadius = ui.dp(9).toFloat()
                setStroke(ui.dp(1), Color.parseColor("#6E8B5CF6"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = ui.dp(4)
            }
        }

        header.addView(ImageView(ui.context).apply {
            val iconSize = ui.dp(if (ui.isLandscape) 19 else 18)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setImageResource(R.drawable.ic_hw_stream_deck)
        })

        header.addView(TextView(ui.context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ui.dp(7)
            }
            text = "STREAM DECK"
            setTextColor(Color.WHITE)
            textSize = if (ui.isLandscape) 12.5f else 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setShadowLayer(4f, 0f, 1.5f, Color.BLACK)
        })

        header.addView(TextView(ui.context).apply {
            text = "SLEEP"
            textSize = if (ui.isLandscape) 10f else 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E9D5FF"))
            background = ui.deckRipple(
                ui.make3DKeycap(COLOR_PURPLE_GLASS_TOP, COLOR_PURPLE_GLASS_BOTTOM, COLOR_PURPLE_SHADOW, COLOR_PURPLE, 5.5f, 2.5f),
            )
            ui.centerKeycapText(this, depthDp = 2.5f, sidePadDp = 10f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ui.dp(if (ui.isLandscape) 26 else 27),
            ).apply {
                marginEnd = ui.dp(6)
            }
            setOnClickListener { onSleepClick() }
        })

        header.addView(TextView(ui.context).apply {
            text = "✕"
            textSize = if (ui.isLandscape) 13f else 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF7096"))
            background = ui.deckRipple(
                ui.make3DKeycap(COLOR_CRIMSON_GLASS_TOP, COLOR_CRIMSON_GLASS_BOTTOM, COLOR_CRIMSON_SHADOW, COLOR_CRIMSON_STROKE, 5.5f, 2.5f),
            )
            ui.centerKeycapText(this, depthDp = 2.5f, sidePadDp = 0f)
            val btnSize = ui.dp(if (ui.isLandscape) 26 else 28)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setOnClickListener { onCloseClick() }
        })

        return header
    }

    private class SystemVolumeModule(
        val card: LinearLayout,
        val syncUi: () -> Unit,
        val receiver: BroadcastReceiver?,
    )

    private fun buildSystemVolumeModule(
        ui: DeckUiContext,
        appContext: Context,
        isActive: () -> Boolean,
    ): SystemVolumeModule {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val sysMinStep = if (audioManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }
        val sysMaxStep = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(sysMinStep + 1)
        val isSysFixed = audioManager?.isVolumeFixed == true
        fun readSysStep(): Int =
            (audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: sysMinStep).coerceIn(sysMinStep, sysMaxStep)
        fun stepToFraction(step: Int): Float =
            ((step - sysMinStep).toFloat() / (sysMaxStep - sysMinStep).coerceAtLeast(1)).coerceIn(0f, 1f)
        fun fractionToStep(norm: Float): Int =
            (sysMinStep + norm.coerceIn(0f, 1f) * (sysMaxStep - sysMinStep)).roundToInt().coerceIn(sysMinStep, sysMaxStep)

        var lastSysStep = readSysStep()
        var lastNonZeroSysStep = lastSysStep.takeIf { it > sysMinStep } ?: fractionToStep(0.5f).coerceAtLeast(sysMinStep + 1)
        var isDraggingSysVol = false

        val sysCardFill = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#47041D26"), Color.parseColor("#5C020B12"), Color.parseColor("#4A061822")),
        ).apply {
            cornerRadius = ui.dp(10).toFloat()
            setStroke(ui.dp(1.3f).coerceAtLeast(1), Color.parseColor("#D900E5FF"))
        }
        val sysCardBevel = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#3367E8F9"), Color.parseColor("#0800E5FF"), Color.TRANSPARENT),
        ).apply {
            cornerRadius = ui.dp(10).toFloat()
            setStroke(ui.dp(0.8f).coerceAtLeast(1), Color.parseColor("#4400E5FF"))
        }
        val sysCardBg = LayerDrawable(arrayOf(sysCardFill, sysCardBevel)).apply {
            setLayerInset(1, ui.dp(1), ui.dp(1), ui.dp(1), ui.dp(1))
        }

        val systemCard = LinearLayout(ui.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5))
            background = sysCardBg
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = ui.dp(5)
            }
        }

        val sysHeaderRow = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = ui.dp(3)
            }
        }

        val sysTitlePill = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#6605212B"))
                cornerRadius = ui.dp(5).toFloat()
                setStroke(ui.dp(1), Color.parseColor("#AA00E5FF"))
            }
            setPadding(ui.dp(6), ui.dp(2), ui.dp(7), ui.dp(2))
        }
        sysTitlePill.addView(TextView(ui.context).apply {
            text = "◈"
            textSize = 9.5f
            setTextColor(Color.parseColor(COLOR_CYAN))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = ui.dp(4) }
        })
        sysTitlePill.addView(TextView(ui.context).apply {
            text = "SYSTEM OUT"
            textSize = 9.8f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        })
        sysHeaderRow.addView(sysTitlePill)

        sysHeaderRow.addView(TextView(ui.context).apply {
            text = if (isSysFixed) "FIXED" else "DEVICE"
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            setTextColor(Color.parseColor("#67E8F9"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = ui.dp(6) }
        })
        sysHeaderRow.addView(View(ui.context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        val sysPercentView = TextView(ui.context).apply {
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#66031820"))
                cornerRadius = ui.dp(5).toFloat()
                setStroke(ui.dp(1), Color.parseColor("#AA00E5FF"))
            }
            setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
        }
        sysHeaderRow.addView(sysPercentView)
        systemCard.addView(sysHeaderRow)

        val sysFaderRow = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val sysMuteBtn = ImageButton(ui.context).apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(30), ui.dp(28)).apply {
                marginEnd = ui.dp(6)
            }
            background = ui.deckRipple(
                ui.make3DKeycap(COLOR_CYAN_GLASS_TOP, COLOR_CYAN_GLASS_BOTTOM, COLOR_CYAN_SHADOW, COLOR_CYAN_STROKE, 5.5f, 2.5f),
            )
            setPadding(ui.dp(5), ui.dp(4), ui.dp(5), ui.dp(6.5f))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "System media volume mute"
            isEnabled = !isSysFixed
        }
        sysFaderRow.addView(sysMuteBtn)

        lateinit var sysFader: DjFaderView
        fun renderSysVolumeUi(norm: Float, updateFader: Boolean) {
            val clamped = norm.coerceIn(0f, 1f)
            if (updateFader) sysFader.setVolume(clamped)
            val pct = (clamped * 100).roundToInt()
            sysPercentView.text = if (pct == 0) "MUTE" else "$pct%"
            sysPercentView.setTextColor(Color.parseColor(if (pct > 0) "#7CEEFF" else "#FF7096"))
            (sysPercentView.background as? GradientDrawable)?.setStroke(
                ui.dp(1),
                Color.parseColor(if (pct > 0) "#AA00E5FF" else "#AAFF2E63"),
            )
            sysMuteBtn.setImageResource(
                if (pct > 0) R.drawable.baseline_volume_up_black_24
                else R.drawable.baseline_volume_off_black_24,
            )
            sysMuteBtn.setColorFilter(Color.parseColor(if (pct > 0) COLOR_CYAN else COLOR_CRIMSON))
        }

        sysFader = DjFaderView(
            context = ui.context,
            initialVolume = stepToFraction(lastSysStep),
            isCompact = false,
            fillHex = COLOR_CYAN,
            thumbBorderHex = "#67E8F9",
            thumbFillHex = "#062533",
            gripHex = "#0891B2",
            trackBorderHex = "#8800E5FF",
            majorTickHex = COLOR_CYAN,
            minorTickHex = "#6638BDF8",
            onDragStateChanged = { dragging ->
                isDraggingSysVol = dragging
                if (!dragging) {
                    val actual = readSysStep()
                    lastSysStep = actual
                    if (actual > sysMinStep) lastNonZeroSysStep = actual
                    renderSysVolumeUi(stepToFraction(actual), updateFader = true)
                }
            },
            onVolumeChanged = { newVol ->
                if (!isSysFixed) {
                    val targetStep = fractionToStep(newVol)
                    if (targetStep > sysMinStep) lastNonZeroSysStep = targetStep
                    if (targetStep != lastSysStep) {
                        lastSysStep = targetStep
                        try {
                            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetStep, 0)
                        } catch (_: Throwable) {
                        }
                    }
                    renderSysVolumeUi(newVol, updateFader = false)
                }
            },
        ).apply {
            isEnabled = !isSysFixed
            layoutParams = LinearLayout.LayoutParams(0, ui.dp(25), 1f)
        }
        renderSysVolumeUi(stepToFraction(lastSysStep), updateFader = false)
        sysFaderRow.addView(sysFader)

        sysMuteBtn.setOnClickListener {
            if (isSysFixed) return@setOnClickListener
            val cur = readSysStep()
            if (cur > sysMinStep) lastNonZeroSysStep = cur
            val nextStep = if (cur > sysMinStep) sysMinStep else lastNonZeroSysStep.coerceIn(sysMinStep + 1, sysMaxStep)
            try {
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, nextStep, 0)
            } catch (_: Throwable) {
            }
            val actual = readSysStep()
            lastSysStep = actual
            renderSysVolumeUi(stepToFraction(actual), updateFader = true)
        }

        val syncUi = {
            if (!isDraggingSysVol) {
                val actual = readSysStep()
                if (actual != lastSysStep) {
                    lastSysStep = actual
                    if (actual > sysMinStep) lastNonZeroSysStep = actual
                    renderSysVolumeUi(stepToFraction(actual), updateFader = true)
                }
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (!isActive()) return
                val streamType = intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC)
                if (streamType == null || streamType == AudioManager.STREAM_MUSIC) {
                    syncUi()
                }
            }
        }
        val registeredReceiver = try {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiver
        } catch (_: Throwable) {
            null
        }

        systemCard.addView(sysFaderRow)
        return SystemVolumeModule(systemCard, syncUi, registeredReceiver)
    }

    private class MasterBusModule(
        val card: LinearLayout,
        val refreshPills: () -> Unit,
        val updateAudioUi: (Float, Boolean) -> Unit,
    )

    private fun buildMasterBusModule(
        ui: DeckUiContext,
        initialState: MultiviewSessionState,
        cardVolumeUpdaters: Map<String, (Float) -> Unit>,
        cardPillUpdaters: List<() -> Unit>,
    ): MasterBusModule {
        val masterCard = LinearLayout(ui.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#3A172304"),
                    Color.parseColor("#4A080D02"),
                    Color.parseColor("#3D121B04"),
                ),
            ).apply {
                cornerRadius = ui.dp(9).toFloat()
                setStroke(ui.dp(1.25f).coerceAtLeast(1), Color.parseColor("#B8C2FF00"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = ui.dp(4.5f)
            }
        }

        val masterHeader = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = ui.dp(3)
            }
        }

        val masterBadge = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#59182604"))
                cornerRadius = ui.dp(5).toFloat()
                setStroke(ui.dp(1), Color.parseColor("#AAC2FF00"))
            }
            setPadding(ui.dp(6), ui.dp(2), ui.dp(7), ui.dp(2))
        }
        masterBadge.addView(TextView(ui.context).apply {
            text = "◆"
            textSize = 9.5f
            setTextColor(Color.parseColor(COLOR_VOLT))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = ui.dp(4) }
        })
        masterBadge.addView(TextView(ui.context).apply {
            text = "MASTER · ALL STREAMS"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        })
        masterHeader.addView(masterBadge)
        masterHeader.addView(View(ui.context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        val masterPercentView = TextView(ui.context).apply {
            textSize = if (ui.isLandscape) 10.5f else 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(COLOR_VOLT))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#4D0B1005"))
                cornerRadius = ui.dp(5).toFloat()
                setStroke(ui.dp(1), Color.parseColor("#88C2FF00"))
            }
            setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
        }
        masterHeader.addView(masterPercentView)
        masterCard.addView(masterHeader)

        val masterQualities = listOf(ui.autoLabel) + STANDARD_QUALITIES
        val masterQualityRow = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = ui.dp(4)
            }
        }
        val masterPillViews = mutableListOf<TextView>()

        fun resolveActiveMasterQuality(): String {
            val state = ui.stateProvider()
            val playback = ui.playbackProvider()
            val resList = state.streams.mapNotNull { stream ->
                val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@mapNotNull null
                val ov = state.qualityOverrides[id]
                val act = playback[id]?.qualityLabel.orEmpty()
                ov ?: act.takeIf { it.isNotBlank() }
            }
            return STANDARD_QUALITIES.firstOrNull { label ->
                val prefix = label.removeSuffix("p")
                resList.any { it.contains(prefix) }
            } ?: ui.autoLabel
        }

        var selectedMasterQuality = resolveActiveMasterQuality()
        fun refreshMasterPills(recomputeSelection: Boolean) {
            if (recomputeSelection) {
                selectedMasterQuality = resolveActiveMasterQuality()
            }
            masterPillViews.forEach { pill ->
                val pillQual = pill.tag as? String ?: ""
                val isSelected = pillQual == selectedMasterQuality
                val bg = if (isSelected) {
                    ui.make3DKeycap(
                        topColorHex = "#ED273B07",
                        bottomColorHex = "#F5121D03",
                        shadowColorHex = COLOR_VOLT_SHADOW,
                        strokeColorHex = COLOR_VOLT_STROKE,
                        cornerRadiusDp = 5.5f,
                        shadowDepthDp = 2.8f,
                    )
                } else {
                    ui.make3DKeycap(
                        topColorHex = "#BA131B07",
                        bottomColorHex = "#CC080C04",
                        shadowColorHex = "#85030501",
                        strokeColorHex = "#6694C21A",
                        cornerRadiusDp = 5.5f,
                        shadowDepthDp = 2.8f,
                    )
                }
                pill.background = ui.deckRipple(bg)
                pill.setTextColor(Color.parseColor(if (isSelected) "#EAFF80" else "#DCE8C8"))
                ui.centerKeycapText(pill, depthDp = 2.8f, sidePadDp = 1f)
            }
        }

        masterQualities.forEachIndexed { qIdx, qual ->
            val pill = TextView(ui.context).apply {
                tag = qual
                text = if (qual == ui.autoLabel) "Auto" else qual
                maxLines = 1
                typeface = Typeface.DEFAULT_BOLD
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this,
                    7,
                    if (ui.isLandscape) 11 else 10,
                    1,
                    TypedValue.COMPLEX_UNIT_SP,
                )
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ui.dp(if (ui.isLandscape) 24 else 26),
                    1f,
                ).apply {
                    if (qIdx < masterQualities.lastIndex) marginEnd = ui.dp(3.5f)
                }
                setOnClickListener {
                    selectedMasterQuality = qual
                    val state = ui.stateProvider()
                    val playback = ui.playbackProvider()
                    state.streams.forEach { stream ->
                        val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEach
                        val targetLabel = if (qual == ui.autoLabel) {
                            null
                        } else {
                            playback[id]?.availableQualities?.firstOrNull { it.contains(qual, ignoreCase = true) } ?: qual
                        }
                        ui.viewModel.setQualityOverride(id, targetLabel)
                    }
                    refreshMasterPills(recomputeSelection = false)
                    cardPillUpdaters.forEach { it.invoke() }
                }
            }
            masterPillViews.add(pill)
            masterQualityRow.addView(pill)
        }
        refreshMasterPills(recomputeSelection = false)
        masterCard.addView(masterQualityRow)

        val masterFaderRow = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val masterMuteBtn = ImageButton(ui.context).apply {
            val btnSize = ui.dp(27)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = ui.dp(6)
            }
            background = ui.deckRipple(
                ui.make3DKeycap(COLOR_VOLT_GLASS_TOP, COLOR_VOLT_GLASS_BOTTOM, COLOR_VOLT_SHADOW, "#99C2FF00", 5.5f, 2.5f),
            )
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(6))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        masterFaderRow.addView(masterMuteBtn)

        var masterVol = initialState.streams.firstOrNull()?.let { s ->
            MultiviewSessionReducer.stableIdentity(s)?.let { ui.viewModel.audioVolume(it) }
        } ?: 1f

        lateinit var masterFader: DjFaderView
        fun updateMasterAudioUi(vol: Float, updateFader: Boolean) {
            masterVol = vol.coerceIn(0f, 1f)
            if (updateFader) masterFader.setVolume(masterVol)
            masterPercentView.text = "${(masterVol * 100).roundToInt()}%"
            masterMuteBtn.setImageResource(
                if (masterVol > 0f) R.drawable.baseline_volume_up_black_24
                else R.drawable.baseline_volume_off_black_24,
            )
            masterMuteBtn.setColorFilter(Color.parseColor(if (masterVol > 0f) COLOR_VOLT else COLOR_CRIMSON))
        }

        masterFader = DjFaderView(
            context = ui.context,
            initialVolume = masterVol,
            isCompact = false,
            fillHex = COLOR_VOLT,
            thumbBorderHex = "#E4FF7A",
            thumbFillHex = "#172204",
            gripHex = "#65A30D",
            trackBorderHex = "#88C2FF00",
            majorTickHex = "#E4FF7A",
            minorTickHex = "#6684CC16",
        ) { newVol ->
            updateMasterAudioUi(newVol, updateFader = false)
            ui.stateProvider().streams.forEach { stream ->
                val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEach
                ui.viewModel.setAudioVolume(id, newVol)
                cardVolumeUpdaters[id]?.invoke(newVol)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ui.dp(24), 1f)
        }
        updateMasterAudioUi(masterVol, updateFader = false)
        masterFaderRow.addView(masterFader)

        masterMuteBtn.setOnClickListener {
            val target = if (masterVol > 0f) 0f else ui.viewModel.defaultAudioVolume().takeIf { it > 0f } ?: 1f
            updateMasterAudioUi(target, updateFader = true)
            ui.stateProvider().streams.forEach { stream ->
                val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEach
                ui.viewModel.setAudioVolume(id, target)
                cardVolumeUpdaters[id]?.invoke(target)
            }
        }
        masterCard.addView(masterFaderRow)

        return MasterBusModule(
            card = masterCard,
            refreshPills = { refreshMasterPills(recomputeSelection = true) },
            updateAudioUi = ::updateMasterAudioUi,
        )
    }

    private fun populateStreamCards(
        ui: DeckUiContext,
        container: LinearLayout,
        cardPillUpdaters: MutableList<() -> Unit>,
        cardVolumeUpdaters: MutableMap<String, (Float) -> Unit>,
        healthUpdaters: MutableMap<String, (MultiviewLiveHealth?) -> Unit>,
        onMasterRefresh: () -> Unit,
        onOpenNormalPlayer: (String) -> Unit,
        onDismissIfEmpty: () -> Unit,
        onCardsChanged: () -> Unit,
    ) {
        container.removeAllViews()
        cardPillUpdaters.clear()
        cardVolumeUpdaters.clear()
        healthUpdaters.clear()

        val streams = ui.stateProvider().streams.toList()
        val useTwoColumns = ui.isLandscape && streams.size > 1
        if (useTwoColumns) {
            val rows = streams.chunked(2)
            rows.forEachIndexed { rowIdx, pair ->
                val rowContainer = LinearLayout(ui.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (rowIdx < rows.lastIndex) bottomMargin = ui.dp(4.5f)
                    }
                }
                val firstCard = buildStreamCard(
                    ui = ui,
                    index = rowIdx * 2,
                    stream = pair[0],
                    isGridCell = true,
                    totalCount = streams.size,
                    cardPillUpdaters = cardPillUpdaters,
                    cardVolumeUpdaters = cardVolumeUpdaters,
                    healthUpdaters = healthUpdaters,
                    onMasterRefresh = onMasterRefresh,
                    onOpenNormalPlayer = onOpenNormalPlayer,
                    onDismissIfEmpty = onDismissIfEmpty,
                    onCardsChanged = onCardsChanged,
                ).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = ui.dp(3.5f)
                }
                rowContainer.addView(firstCard)

                if (pair.size > 1) {
                    val secondCard = buildStreamCard(
                        ui = ui,
                        index = rowIdx * 2 + 1,
                        stream = pair[1],
                        isGridCell = true,
                        totalCount = streams.size,
                        cardPillUpdaters = cardPillUpdaters,
                        cardVolumeUpdaters = cardVolumeUpdaters,
                        healthUpdaters = healthUpdaters,
                        onMasterRefresh = onMasterRefresh,
                        onOpenNormalPlayer = onOpenNormalPlayer,
                        onDismissIfEmpty = onDismissIfEmpty,
                        onCardsChanged = onCardsChanged,
                    ).apply {
                        (layoutParams as LinearLayout.LayoutParams).marginStart = ui.dp(3.5f)
                    }
                    rowContainer.addView(secondCard)
                } else {
                    rowContainer.addView(View(ui.context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                            marginStart = ui.dp(3.5f)
                        }
                    })
                }
                container.addView(rowContainer)
            }
        } else {
            streams.forEachIndexed { index, stream ->
                container.addView(
                    buildStreamCard(
                        ui = ui,
                        index = index,
                        stream = stream,
                        isGridCell = false,
                        totalCount = streams.size,
                        cardPillUpdaters = cardPillUpdaters,
                        cardVolumeUpdaters = cardVolumeUpdaters,
                        healthUpdaters = healthUpdaters,
                        onMasterRefresh = onMasterRefresh,
                        onOpenNormalPlayer = onOpenNormalPlayer,
                        onDismissIfEmpty = onDismissIfEmpty,
                        onCardsChanged = onCardsChanged,
                    ),
                )
            }
        }

        val initialHealth = try {
            ui.healthProvider()
        } catch (_: Throwable) {
            emptyMap()
        }
        healthUpdaters.forEach { (id, updater) -> updater(initialHealth[id]) }
    }

    private fun buildStreamCard(
        ui: DeckUiContext,
        index: Int,
        stream: Stream,
        isGridCell: Boolean,
        totalCount: Int,
        cardPillUpdaters: MutableList<() -> Unit>,
        cardVolumeUpdaters: MutableMap<String, (Float) -> Unit>,
        healthUpdaters: MutableMap<String, (MultiviewLiveHealth?) -> Unit>,
        onMasterRefresh: () -> Unit,
        onOpenNormalPlayer: (String) -> Unit,
        onDismissIfEmpty: () -> Unit,
        onCardsChanged: () -> Unit,
    ): LinearLayout {
        val identity = MultiviewSessionReducer.stableIdentity(stream) ?: ""
        var vol = ui.viewModel.audioVolume(identity)

        val card = LinearLayout(ui.context).apply {
            orientation = LinearLayout.VERTICAL
            val pSide = ui.dp(7.5f)
            val pVert = ui.dp(5f)
            setPadding(pSide, pVert, pSide, pVert)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#380D0F1C"), Color.parseColor("#47070811")),
            ).apply {
                cornerRadius = ui.dp(9).toFloat()
                setStroke(
                    ui.dp(1.1f).coerceAtLeast(1),
                    Color.parseColor(if (vol > 0f) "#AA9D4EDD" else "#4D453969"),
                )
            }
            layoutParams = if (isGridCell) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = ui.dp(if (index < totalCount - 1) 4 else 0)
                }
            }
        }

        // Row 1: #N + Avatar + Streamer Name + Health Pill + Open Player + Remove
        val row1 = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        row1.addView(TextView(ui.context).apply {
            text = "#${index + 1}"
            setTextColor(Color.parseColor("#E9D5FF"))
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#66160D2E"))
                cornerRadius = ui.dp(4).toFloat()
                setStroke(ui.dp(1), Color.parseColor("#88A855F7"))
            }
            setPadding(ui.dp(4.5f), ui.dp(1.5f), ui.dp(4.5f), ui.dp(1.5f))
        })

        val avatar = ImageView(ui.context).apply {
            val avSize = ui.dp(if (isGridCell) 18 else 19)
            layoutParams = LinearLayout.LayoutParams(avSize, avSize).apply {
                marginStart = ui.dp(5)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, ui.dp(4).toFloat())
                }
            }
            setImageResource(R.drawable.ic_person_placeholder)
        }
        try {
            loadStreamProfileImage(ui.context, avatar, stream)
        } catch (_: Throwable) {
        }
        row1.addView(avatar)

        row1.addView(TextView(ui.context).apply {
            text = ui.displayName(stream)
            setTextColor(Color.WHITE)
            textSize = if (isGridCell) 11.5f else 12f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(6f, 0f, 1.5f, Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ui.dp(6)
                marginEnd = ui.dp(4)
            }
        })

        val healthPill = TextView(ui.context).apply {
            textSize = if (isGridCell) 8.5f else 9f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            includeFontPadding = false
            setPadding(ui.dp(5), ui.dp(2), ui.dp(5), ui.dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = ui.dp(4)
            }
        }
        healthUpdaters[identity] = { health ->
            val label: String
            val textHex: String
            val strokeHex: String
            val fillHex: String
            when {
                health == null -> {
                    label = "--/--"
                    textHex = "#94A3B8"
                    strokeHex = "#55475569"
                    fillHex = "#66090B12"
                }
                health.isBuffering -> {
                    label = if (health.liveOffsetSeconds > 0) {
                        "${health.bufferSeconds}/${health.liveOffsetSeconds}s"
                    } else {
                        "BUF"
                    }
                    textHex = "#FF7096"
                    strokeHex = "#AAFF2E63"
                    fillHex = "#7A1F0610"
                }
                health.bufferSeconds <= 1 || health.isDecreasing || health.liveOffsetSeconds >= 9 -> {
                    val arrow = if (health.isDecreasing) "↓" else ""
                    label = "${health.bufferSeconds}$arrow/${health.liveOffsetSeconds}s"
                    textHex = "#FCD34D"
                    strokeHex = "#AAF59E0B"
                    fillHex = "#7A1E1304"
                }
                else -> {
                    label = "${health.bufferSeconds}/${health.liveOffsetSeconds}s"
                    textHex = "#5EEAD4"
                    strokeHex = "#9900E5FF"
                    fillHex = "#7A04181E"
                }
            }
            healthPill.text = label
            healthPill.setTextColor(Color.parseColor(textHex))
            healthPill.background = GradientDrawable().apply {
                setColor(Color.parseColor(fillHex))
                cornerRadius = ui.dp(4.5f).toFloat()
                setStroke(ui.dp(1), Color.parseColor(strokeHex))
            }
        }
        row1.addView(healthPill)

        val actionBtnW = ui.dp(if (isGridCell) 30 else 32)
        val actionBtnH = ui.dp(if (isGridCell) 27 else 28)

        row1.addView(ImageView(ui.context).apply {
            layoutParams = LinearLayout.LayoutParams(actionBtnW, actionBtnH).apply {
                marginEnd = ui.dp(5)
            }
            background = ui.deckRipple(
                ui.make3DKeycap(COLOR_UNSEL_GLASS_TOP, COLOR_UNSEL_GLASS_BOTTOM, COLOR_UNSEL_SHADOW, "#8864748B", 5f, 2f),
            )
            setImageResource(R.drawable.ic_pref_fullscreen)
            setColorFilter(Color.parseColor("#E2E8F0"))
            setPadding(ui.dp(5.5f), ui.dp(4.5f), ui.dp(5.5f), ui.dp(6.5f))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = ui.context.getString(R.string.multiview_open_player)
            setOnClickListener { onOpenNormalPlayer(identity) }
        })

        row1.addView(ImageView(ui.context).apply {
            layoutParams = LinearLayout.LayoutParams(actionBtnW, actionBtnH)
            background = ui.deckRipple(
                ui.make3DKeycap(COLOR_CRIMSON_GLASS_TOP, COLOR_CRIMSON_GLASS_BOTTOM, COLOR_CRIMSON_SHADOW, COLOR_CRIMSON_STROKE, 5f, 2f),
            )
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.parseColor("#FF7096"))
            setPadding(ui.dp(6f), ui.dp(5f), ui.dp(6f), ui.dp(7f))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = ui.context.getString(R.string.multiview_remove_stream)
            setOnClickListener {
                ui.viewModel.remove(identity)
                if (ui.stateProvider().streams.isEmpty()) {
                    onDismissIfEmpty()
                } else {
                    onCardsChanged()
                }
            }
        })
        card.addView(row1)

        // Row 2: Mute + Cyber DJ Fader + % + Reorder ▲ ▼
        val row2 = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = ui.dp(3)
                bottomMargin = ui.dp(3)
            }
        }

        val muteBtn = ImageButton(ui.context).apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(26), ui.dp(24)).apply {
                marginEnd = ui.dp(4)
            }
            background = ui.deckRipple(
                ui.make3DKeycap(COLOR_UNSEL_GLASS_TOP, COLOR_UNSEL_GLASS_BOTTOM, COLOR_UNSEL_SHADOW, "#776D28D9", 4.5f, 2f),
            )
            setPadding(ui.dp(3.5f), ui.dp(3f), ui.dp(3.5f), ui.dp(5f))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        row2.addView(muteBtn)

        val percentView = TextView(ui.context).apply {
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ui.dp(30), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ui.dp(3)
                marginEnd = ui.dp(3)
            }
        }

        lateinit var fader: DjFaderView
        fun renderCardAudio(newVol: Float, updateFader: Boolean) {
            vol = newVol.coerceIn(0f, 1f)
            if (updateFader) fader.setVolume(vol)
            percentView.text = "${(vol * 100).roundToInt()}%"
            percentView.setTextColor(Color.parseColor(if (vol > 0f) COLOR_PURPLE_BRIGHT else "#94A3B8"))
            muteBtn.setImageResource(
                if (vol > 0f) R.drawable.baseline_volume_up_black_24
                else R.drawable.baseline_volume_off_black_24,
            )
            muteBtn.setColorFilter(Color.parseColor(if (vol > 0f) COLOR_PURPLE_BRIGHT else COLOR_CRIMSON))
            (card.background as? GradientDrawable)?.setStroke(
                ui.dp(1.1f).coerceAtLeast(1),
                Color.parseColor(if (vol > 0f) "#AA9D4EDD" else "#4D453969"),
            )
        }

        fader = DjFaderView(ui.context, vol, isCompact = false) { newVol ->
            ui.viewModel.setAudioVolume(identity, newVol)
            renderCardAudio(newVol, updateFader = false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ui.dp(23), 1f)
        }
        renderCardAudio(vol, updateFader = false)
        cardVolumeUpdaters[identity] = { newVol -> renderCardAudio(newVol, updateFader = true) }

        muteBtn.setOnClickListener {
            val target = if (vol > 0f) 0f else ui.viewModel.defaultAudioVolume().takeIf { it > 0f } ?: 1f
            ui.viewModel.setAudioVolume(identity, target)
            renderCardAudio(target, updateFader = true)
        }

        row2.addView(fader)
        row2.addView(percentView)

        val orderBtnSize = ui.dp(24)
        row2.addView(TextView(ui.context).apply {
            text = "▲"
            setTextColor(Color.parseColor(if (index > 0) COLOR_PURPLE_BRIGHT else "#4A4366"))
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(orderBtnSize, orderBtnSize).apply { marginEnd = ui.dp(3) }
            if (index > 0) {
                background = ui.deckRipple(
                    ui.make3DKeycap(COLOR_UNSEL_GLASS_TOP, COLOR_UNSEL_GLASS_BOTTOM, COLOR_UNSEL_SHADOW, "#776D28D9", 4f, 2f),
                )
                ui.centerKeycapText(this, depthDp = 2f, sidePadDp = 0f)
                setOnClickListener {
                    ui.viewModel.reorder(identity, index - 1)
                    onCardsChanged()
                }
            } else {
                ui.centerKeycapText(this, depthDp = 0f, sidePadDp = 0f)
            }
        })

        row2.addView(TextView(ui.context).apply {
            text = "▼"
            setTextColor(Color.parseColor(if (index < totalCount - 1) COLOR_PURPLE_BRIGHT else "#4A4366"))
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(orderBtnSize, orderBtnSize)
            if (index < totalCount - 1) {
                background = ui.deckRipple(
                    ui.make3DKeycap(COLOR_UNSEL_GLASS_TOP, COLOR_UNSEL_GLASS_BOTTOM, COLOR_UNSEL_SHADOW, "#776D28D9", 4f, 2f),
                )
                ui.centerKeycapText(this, depthDp = 2f, sidePadDp = 0f)
                setOnClickListener {
                    ui.viewModel.reorder(identity, index + 1)
                    onCardsChanged()
                }
            } else {
                ui.centerKeycapText(this, depthDp = 0f, sidePadDp = 0f)
            }
        })
        card.addView(row2)

        // Row 3: Transparent 3D glass quality pills
        val row3 = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val pillViews = mutableListOf<TextView>()
        fun refreshThisPills() {
            val state = ui.stateProvider()
            val overrideQuality = state.qualityOverrides[identity]
            val activePlaying = ui.playbackProvider()[identity]?.qualityLabel.orEmpty()
            val targetMatch = when {
                overrideQuality != null -> overrideQuality
                activePlaying.isNotBlank() -> activePlaying
                else -> ui.autoLabel
            }

            pillViews.forEach { pill ->
                val pTag = pill.tag as? String ?: ""
                val isSelected = when {
                    pTag == ui.autoLabel && overrideQuality == null && activePlaying.isBlank() -> true
                    pTag == ui.autoLabel && overrideQuality == null && activePlaying.equals("AUTO", ignoreCase = true) -> true
                    pTag != ui.autoLabel && targetMatch.contains(pTag, ignoreCase = true) -> true
                    else -> false
                }
                val bg = if (isSelected) {
                    ui.make3DKeycap(
                        topColorHex = COLOR_PURPLE_GLASS_TOP,
                        bottomColorHex = COLOR_PURPLE_GLASS_BOTTOM,
                        shadowColorHex = COLOR_PURPLE_SHADOW,
                        strokeColorHex = COLOR_PURPLE_STROKE,
                        cornerRadiusDp = 4.5f,
                        shadowDepthDp = 2.2f,
                    )
                } else {
                    ui.make3DKeycap(
                        topColorHex = COLOR_UNSEL_GLASS_TOP,
                        bottomColorHex = COLOR_UNSEL_GLASS_BOTTOM,
                        shadowColorHex = COLOR_UNSEL_SHADOW,
                        strokeColorHex = COLOR_UNSEL_STROKE,
                        cornerRadiusDp = 4.5f,
                        shadowDepthDp = 2.2f,
                    )
                }
                pill.background = ui.deckRipple(bg)
                pill.setTextColor(Color.parseColor(if (isSelected) "#EFE1FF" else "#CBD5E1"))
                ui.centerKeycapText(pill, depthDp = 2.2f, sidePadDp = 1f)
            }
        }
        cardPillUpdaters.add(::refreshThisPills)

        val displayQualities = listOf(ui.autoLabel) + STANDARD_QUALITIES
        displayQualities.forEachIndexed { qIdx, qual ->
            val pill = TextView(ui.context).apply {
                tag = qual
                text = if (qual == ui.autoLabel) "Auto" else qual
                maxLines = 1
                typeface = Typeface.DEFAULT_BOLD
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this,
                    7,
                    if (isGridCell) 9 else 10,
                    1,
                    TypedValue.COMPLEX_UNIT_SP,
                )
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ui.dp(if (isGridCell) 23 else 24),
                    1f,
                ).apply {
                    if (qIdx < displayQualities.lastIndex) marginEnd = ui.dp(3)
                }
                setOnClickListener {
                    val available = ui.playbackProvider()[identity]?.availableQualities.orEmpty()
                    val match = if (qual == ui.autoLabel) {
                        null
                    } else {
                        available.firstOrNull { it.contains(qual, ignoreCase = true) } ?: qual
                    }
                    ui.viewModel.setQualityOverride(identity, match)
                    refreshThisPills()
                    onMasterRefresh()
                }
            }
            pillViews.add(pill)
            row3.addView(pill)
        }
        refreshThisPills()
        card.addView(row3)
        return card
    }

    private fun buildMacroDeck(
        ui: DeckUiContext,
        onAfterMacro: (Float) -> Unit,
        onDismiss: () -> Unit,
    ): LinearLayout {
        val bottomMacros = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(ui.dp(4.5f), ui.dp(4.5f), ui.dp(4.5f), ui.dp(4.5f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#330B0B16"))
                cornerRadius = ui.dp(9).toFloat()
                setStroke(ui.dp(1), Color.parseColor("#666D28D9"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = ui.dp(4.5f)
            }
        }

        fun createMacroBtn(
            label: String,
            topHex: String,
            bottomHex: String,
            shadowHex: String,
            strokeHex: String,
            textHex: String,
            onClick: () -> Unit,
        ): TextView = TextView(ui.context).apply {
            text = label
            maxLines = 1
            typeface = Typeface.DEFAULT_BOLD
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                8,
                if (ui.isLandscape) 11 else 10,
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
            setTextColor(Color.parseColor(textHex))
            background = ui.deckRipple(ui.make3DKeycap(topHex, bottomHex, shadowHex, strokeHex, 6f, 3f))
            ui.centerKeycapText(this, depthDp = 3f, sidePadDp = 3f)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ui.dp(if (ui.isLandscape) 31 else 32),
                1f,
            ).apply {
                marginStart = ui.dp(2.5f)
                marginEnd = ui.dp(2.5f)
            }
            setOnClickListener {
                onClick()
                val firstVol = ui.stateProvider().streams.firstOrNull()?.let { s ->
                    MultiviewSessionReducer.stableIdentity(s)?.let { ui.viewModel.audioVolume(it) }
                } ?: 0f
                onAfterMacro(firstVol)
            }
        }

        bottomMacros.addView(
            createMacroBtn("MUTE ALL", COLOR_CRIMSON_GLASS_TOP, COLOR_CRIMSON_GLASS_BOTTOM, COLOR_CRIMSON_SHADOW, COLOR_CRIMSON_STROKE, "#FF7096") {
                ui.stateProvider().streams.forEach { stream ->
                    MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                        ui.viewModel.setAudioVolume(id, 0f)
                    }
                }
            },
        )
        bottomMacros.addView(
            createMacroBtn("EQUALIZE", COLOR_PURPLE_GLASS_TOP, COLOR_PURPLE_GLASS_BOTTOM, COLOR_PURPLE_SHADOW, COLOR_PURPLE_STROKE, "#E2C6FF") {
                val streams = ui.stateProvider().streams
                val equalVol = (1.0f / streams.size.coerceAtLeast(1)).coerceIn(0.15f, 1f)
                streams.forEach { stream ->
                    MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                        ui.viewModel.setAudioVolume(id, equalVol)
                    }
                }
            },
        )
        bottomMacros.addView(
            createMacroBtn("SOLO ACTIVE", COLOR_CYAN_GLASS_TOP, COLOR_CYAN_GLASS_BOTTOM, COLOR_CYAN_SHADOW, COLOR_CYAN_STROKE, "#7CEEFF") {
                val state = ui.stateProvider()
                val active = state.activeIdentity
                    ?: state.streams.firstOrNull()?.let { MultiviewSessionReducer.stableIdentity(it) }
                state.streams.forEach { stream ->
                    MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                        ui.viewModel.setAudioVolume(id, if (id == active) 1f else 0f)
                    }
                }
            },
        )
        bottomMacros.addView(
            createMacroBtn("REMOVE ALL", COLOR_AMBER_GLASS_TOP, COLOR_AMBER_GLASS_BOTTOM, COLOR_AMBER_SHADOW, COLOR_AMBER_STROKE, "#FF9666") {
                ui.stateProvider().streams.toList().forEach { stream ->
                    MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                        ui.viewModel.remove(id)
                    }
                }
                onDismiss()
            },
        )
        return bottomMacros
    }

    private fun createTouchInterceptWrapper(
        context: Context,
        chassis: View,
        toolbarView: View,
        toggleButton: View,
        onInteraction: () -> Unit,
        onDismissImmediate: () -> Unit,
        onDismissDeferred: (View) -> Unit,
    ): FrameLayout {
        fun isPointInsideView(view: View, rawX: Int, rawY: Int): Boolean {
            if (!view.isShown || view.width <= 0 || view.height <= 0) return false
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            return rawX in loc[0] until (loc[0] + view.width) &&
                rawY in loc[1] until (loc[1] + view.height)
        }

        return object : FrameLayout(context) {
            private var touchStartedInsideChassis = false
            private var isDismissing = false

            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (isDismissing) return false
                val rawX = ev.rawX.roundToInt()
                val rawY = ev.rawY.roundToInt()
                val insideChassis = isPointInsideView(chassis, rawX, rawY)

                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartedInsideChassis = insideChassis
                        if (insideChassis) {
                            onInteraction()
                            return super.dispatchTouchEvent(ev)
                        }
                        if (isPointInsideView(toggleButton, rawX, rawY)) {
                            isDismissing = true
                            onDismissImmediate()
                            return true
                        }
                        if (toolbarView.isShown && isPointInsideView(toolbarView, rawX, rawY)) {
                            isDismissing = true
                            onDismissDeferred(this)
                            return false
                        }
                        isDismissing = true
                        onDismissImmediate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (touchStartedInsideChassis) {
                            onInteraction()
                            return super.dispatchTouchEvent(ev)
                        }
                        return false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasInside = touchStartedInsideChassis
                        touchStartedInsideChassis = false
                        if (wasInside) {
                            onInteraction()
                            return super.dispatchTouchEvent(ev)
                        }
                        return false
                    }
                }
                return if (touchStartedInsideChassis) super.dispatchTouchEvent(ev) else false
            }
        }
    }
}

class DjFaderView(
    context: Context,
    initialVolume: Float,
    private val isCompact: Boolean = false,
    fillHex: String = "#A855F7",
    thumbBorderHex: String = "#C084FC",
    thumbFillHex: String = "#1D1036",
    gripHex: String = "#7E22CE",
    trackBorderHex: String = "#776D28D9",
    majorTickHex: String = "#C2FF00",
    minorTickHex: String = "#6664748B",
    private val onDragStateChanged: ((Boolean) -> Unit)? = null,
    private val onVolumeChanged: (Float) -> Unit,
) : View(context) {

    private var volume: Float = initialVolume.coerceIn(0f, 1f)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5905050B")
        style = Paint.Style.FILL
    }
    private val trackFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(fillHex)
        style = Paint.Style.FILL
    }
    private val trackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(trackBorderHex)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(majorTickHex)
        strokeWidth = if (isCompact) 2f else 2.5f
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(minorTickHex)
        strokeWidth = if (isCompact) 1.2f else 1.5f
    }
    private val knobShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0000000")
        style = Paint.Style.FILL
    }
    private val capBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(thumbFillHex)
        style = Paint.Style.FILL
    }
    private val capBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(thumbBorderHex)
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }
    private val knobGripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(gripHex)
        strokeWidth = 1.4f
    }
    private val capGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = if (isCompact) 2.2f else 3f
    }

    private val capWidth = dpToPx(if (isCompact) 15f else 18f)
    private val capHeight = dpToPx(if (isCompact) 18f else 22f)
    private val capRadius = dpToPx(if (isCompact) 3f else 4f)

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    fun setVolume(newVol: Float) {
        volume = newVol.coerceIn(0f, 1f)
        invalidate()
    }

    fun getVolume(): Float = volume

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultH = dpToPx(if (isCompact) 20f else 24f).toInt()
        val h = resolveSize(defaultH, heightMeasureSpec)
        val w = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = capWidth / 2f + dpToPx(if (isCompact) 3f else 4f)
        val trackW = w - (pad * 2f)
        if (trackW <= 0f) return

        val centerY = h / 2f + dpToPx(2f)
        val trackHalfH = dpToPx(if (isCompact) 2.2f else 2.8f)
        val trackTop = centerY - trackHalfH
        val trackBottom = centerY + trackHalfH
        val trackRadius = trackHalfH

        val tickBottom = trackTop - dpToPx(2f)
        val tickTopMajor = tickBottom - dpToPx(if (isCompact) 4.5f else 5.5f)
        val tickTopMinor = tickBottom - dpToPx(if (isCompact) 2.8f else 3.2f)
        for (i in 0..20) {
            val tickX = pad + (trackW * (i / 20f))
            val isMajor = i == 0 || i == 10 || i == 20
            canvas.drawLine(
                tickX,
                if (isMajor) tickTopMajor else tickTopMinor,
                tickX,
                tickBottom,
                if (isMajor) majorTickPaint else minorTickPaint,
            )
        }

        val trackRect = RectF(pad, trackTop, pad + trackW, trackBottom)
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)

        val knobCenterX = pad + (trackW * volume)
        if (knobCenterX > pad + 1f) {
            val fillRect = RectF(pad, trackTop, knobCenterX, trackBottom)
            canvas.drawRoundRect(fillRect, trackRadius, trackRadius, trackFillPaint)
        }
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackBorderPaint)

        val knobLeft = knobCenterX - (capWidth / 2f)
        val knobRight = knobCenterX + (capWidth / 2f)
        val knobTop = centerY - (capHeight / 2f)
        val knobBottom = knobTop + capHeight

        val shadowOffset = dpToPx(2.2f)
        val knobShadowRect = RectF(knobLeft, knobTop + shadowOffset, knobRight, knobBottom + shadowOffset)
        canvas.drawRoundRect(knobShadowRect, capRadius, capRadius, knobShadowPaint)

        val knobRect = RectF(knobLeft, knobTop, knobRight, knobBottom)
        canvas.drawRoundRect(knobRect, capRadius, capRadius, capBodyPaint)
        canvas.drawRoundRect(knobRect, capRadius, capRadius, capBorderPaint)

        val gripInset = dpToPx(if (isCompact) 3.5f else 4.5f)
        val gripSpacing = dpToPx(if (isCompact) 2.5f else 3.2f)
        canvas.drawLine(knobCenterX - gripSpacing, knobTop + gripInset, knobCenterX - gripSpacing, knobBottom - gripInset, knobGripPaint)
        canvas.drawLine(knobCenterX + gripSpacing, knobTop + gripInset, knobCenterX + gripSpacing, knobBottom - gripInset, knobGripPaint)

        val stripeInset = dpToPx(if (isCompact) 2f else 2.8f)
        canvas.drawLine(knobCenterX, knobTop + stripeInset, knobCenterX, knobBottom - stripeInset, capGlowPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val pad = capWidth / 2f + dpToPx(if (isCompact) 3f else 4f)
        val trackW = width.toFloat() - (pad * 2f)
        if (trackW <= 0f) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onDragStateChanged?.invoke(true)
                updateFromTouch(event.x, pad, trackW, snap = false)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x, pad, trackW, snap = false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                updateFromTouch(event.x, pad, trackW, snap = true)
                onDragStateChanged?.invoke(false)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(touchX: Float, pad: Float, trackW: Float, snap: Boolean) {
        var newVol = ((touchX - pad) / trackW).coerceIn(0f, 1f)
        if (snap) {
            newVol = when {
                newVol < 0.03f -> 0f
                newVol > 0.97f -> 1f
                newVol in 0.48f..0.52f -> 0.5f
                else -> newVol
            }
        }
        volume = newVol
        invalidate()
        onVolumeChanged(newVol)
    }
}
