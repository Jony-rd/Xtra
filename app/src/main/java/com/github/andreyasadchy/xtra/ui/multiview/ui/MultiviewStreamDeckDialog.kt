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

    private class ActiveOverlay(
        val hostContainer: FrameLayout,
        val dismissFn: () -> Unit,
    )

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
            val density = metrics.density
            fun dp(value: Int): Int = (value * density).roundToInt()
            fun dp(value: Float): Int = (value * density).roundToInt()
            fun displayName(stream: Stream): String =
                stream.channelName?.takeIf { it.isNotBlank() }
                    ?: stream.channelLogin?.takeIf { it.isNotBlank() }
                    ?: stream.id.orEmpty()

            val isLandscape = res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val hostWidth = hostContainer.width.takeIf { it > 0 } ?: metrics.widthPixels
            val hostHeight = hostContainer.height.takeIf { it > 0 } ?: metrics.heightPixels
            val hostLoc = IntArray(2)
            hostContainer.getLocationOnScreen(hostLoc)
            val localAnchorBottom = anchorBottomPx?.let { (it - hostLoc[1]).coerceAtLeast(0) }

            val shouldAnchorUnderHeader = !isLandscape && !isFloatingWindow &&
                (initialState.layoutMode == MultiviewLayoutMode.FOCUS || initialState.streams.size <= 1) &&
                localAnchorBottom != null

            val appContext = context.applicationContext
            val uiHandler = Handler(Looper.getMainLooper())
            var overlayRef: ActiveOverlay? = null
            var cleanedUp = false
            var volumeReceiver: BroadcastReceiver? = null

            lateinit var overlayWrapper: FrameLayout
            val healthUpdaters = mutableMapOf<String, (MultiviewLiveHealth?) -> Unit>()
            var syncSystemVolumeUi: (() -> Unit)? = null

            val dismissRunnable = Runnable {
                overlayRef?.dismissFn?.invoke()
            }
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
                    healthUpdaters.forEach { (id, updater) ->
                        updater(healthMap[id])
                    }
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

            // True Transparent Smoked Obsidian Glass Chassis + Cyber Purple Rim
            val chassisRadius = dp(14).toFloat()
            val glassFill = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#4706060D"),
                    Color.parseColor("#59040409"),
                    Color.parseColor("#4D0A0614"),
                ),
            ).apply {
                cornerRadius = chassisRadius
                setStroke(dp(1.25f).coerceAtLeast(1), Color.parseColor("#B39D4EDD"))
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
                setStroke(dp(1), Color.parseColor("#445B21B6"))
            }
            val chassisLayer = LayerDrawable(arrayOf(glassFill, glassBevelHighlight)).apply {
                setLayerInset(1, dp(1), dp(1), dp(1), dp(1))
            }

            val baseMaxHeight = (hostHeight * (if (isLandscape) 0.92f else 0.84f)).roundToInt()
            val availBelowHeader = if (shouldAnchorUnderHeader && localAnchorBottom != null) {
                hostHeight - localAnchorBottom - dp(8)
            } else {
                baseMaxHeight
            }
            val maxDialogHeight = if (shouldAnchorUnderHeader && availBelowHeader >= dp(260)) {
                minOf(baseMaxHeight, availBelowHeader)
            } else {
                baseMaxHeight
            }

            var headerRef: LinearLayout? = null
            var systemCardRef: LinearLayout? = null
            var masterCardRef: LinearLayout? = null
            var bottomMacrosRef: LinearLayout? = null
            var streamScrollMaxCapPx = (maxDialogHeight * 0.56f).roundToInt()

            val chassis = object : LinearLayout(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val widthSize = MeasureSpec.getSize(widthMeasureSpec)
                    val innerWidth = (widthSize - paddingLeft - paddingRight).coerceAtLeast(1)
                    val innerWSpec = MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY)
                    val looseHSpec = MeasureSpec.makeMeasureSpec(maxDialogHeight, MeasureSpec.AT_MOST)

                    var fixedH = paddingTop + paddingBottom
                    listOfNotNull(headerRef, systemCardRef, masterCardRef, bottomMacrosRef).forEach { child ->
                        child.measure(innerWSpec, looseHSpec)
                        val lp = child.layoutParams as? MarginLayoutParams
                        fixedH += child.measuredHeight + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
                    }
                    streamScrollMaxCapPx = (maxDialogHeight - fixedH).coerceAtLeast(dp(84))

                    val rawH = MeasureSpec.getSize(heightMeasureSpec)
                    val capH = if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
                        val limit = if (rawH > 0) minOf(rawH, maxDialogHeight) else maxDialogHeight
                        MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
                    } else {
                        heightMeasureSpec
                    }
                    super.onMeasure(widthMeasureSpec, capH)
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                val pSide = dp(if (isLandscape) 9 else 8)
                val pVert = dp(6)
                setPadding(pSide, pVert, pSide, pVert)
                background = chassisLayer
                elevation = 0f
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, chassisRadius)
                    }
                }
                clipToOutline = true
            }

            // ==========================================
            // HEADER BAR (Transparent Smoked Cyber Glass)
            // ==========================================
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(6), dp(4))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.parseColor("#36120D26"),
                        Color.parseColor("#42090714"),
                    ),
                ).apply {
                    cornerRadius = dp(9).toFloat()
                    setStroke(dp(1), Color.parseColor("#6E8B5CF6"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(4)
                }
            }
            headerRef = header

            val deckIcon = ImageView(context).apply {
                val iconSize = dp(if (isLandscape) 19 else 18)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                setImageResource(R.drawable.ic_hw_stream_deck)
            }
            header.addView(deckIcon)

            val titleView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(7)
                }
                text = "STREAM DECK"
                setTextColor(Color.WHITE)
                textSize = if (isLandscape) 12.5f else 12f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.05f
                setShadowLayer(4f, 0f, 1.5f, Color.BLACK)
            }
            header.addView(titleView)

            val sleepBtn = TextView(context).apply {
                text = "SLEEP"
                textSize = if (isLandscape) 10f else 9.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#E9D5FF"))
                background = deckRipple(
                    make3DKeycap(COLOR_PURPLE_GLASS_TOP, COLOR_PURPLE_GLASS_BOTTOM, COLOR_PURPLE_SHADOW, COLOR_PURPLE, 5.5f, 2.5f),
                )
                centerKeycapText(this, depthDp = 2.5f, sidePadDp = 10f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(if (isLandscape) 26 else 27),
                ).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    cleanupOverlay()
                    try {
                        onOpenSleepTimer()
                    } catch (_: Throwable) {
                    }
                }
            }
            header.addView(sleepBtn)

            val closeBtn = TextView(context).apply {
                text = "✕"
                textSize = if (isLandscape) 13f else 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FF7096"))
                background = deckRipple(
                    make3DKeycap(COLOR_CRIMSON_GLASS_TOP, COLOR_CRIMSON_GLASS_BOTTOM, COLOR_CRIMSON_SHADOW, COLOR_CRIMSON_STROKE, 5.5f, 2.5f),
                )
                centerKeycapText(this, depthDp = 2.5f, sidePadDp = 0f)
                val btnSize = dp(if (isLandscape) 26 else 28)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                setOnClickListener { cleanupOverlay() }
            }
            header.addView(closeBtn)
            chassis.addView(header)

            // ==========================================
            // STANDALONE TOP HARDWARE MODULE: SYSTEM OUT
            // ==========================================
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
                intArrayOf(
                    Color.parseColor("#47041D26"),
                    Color.parseColor("#5C020B12"),
                    Color.parseColor("#4A061822"),
                ),
            ).apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1.3f).coerceAtLeast(1), Color.parseColor("#D900E5FF"))
            }
            val sysCardBevel = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#3367E8F9"),
                    Color.parseColor("#0800E5FF"),
                    Color.TRANSPARENT,
                ),
            ).apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(0.8f).coerceAtLeast(1), Color.parseColor("#4400E5FF"))
            }
            val sysCardBg = LayerDrawable(arrayOf(sysCardFill, sysCardBevel)).apply {
                setLayerInset(1, dp(1), dp(1), dp(1), dp(1))
            }

            val systemCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = sysCardBg
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(5)
                }
            }
            systemCardRef = systemCard

            val sysHeaderRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(3)
                }
            }

            val sysTitlePill = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#6605212B"))
                    cornerRadius = dp(5).toFloat()
                    setStroke(dp(1), Color.parseColor("#AA00E5FF"))
                }
                setPadding(dp(6), dp(2), dp(7), dp(2))
            }
            sysTitlePill.addView(TextView(context).apply {
                text = "◈"
                textSize = 9.5f
                setTextColor(Color.parseColor(COLOR_CYAN))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(4) }
            })
            sysTitlePill.addView(TextView(context).apply {
                text = "SYSTEM OUT"
                textSize = 9.8f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setTextColor(Color.WHITE)
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
            })
            sysHeaderRow.addView(sysTitlePill)

            sysHeaderRow.addView(TextView(context).apply {
                text = if (isSysFixed) "FIXED" else "DEVICE"
                textSize = 8f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.06f
                setTextColor(Color.parseColor("#67E8F9"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(6) }
            })

            sysHeaderRow.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })

            val sysPercentView = TextView(context).apply {
                textSize = if (isLandscape) 10.5f else 10.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#66031820"))
                    cornerRadius = dp(5).toFloat()
                    setStroke(dp(1), Color.parseColor("#AA00E5FF"))
                }
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }
            sysHeaderRow.addView(sysPercentView)
            systemCard.addView(sysHeaderRow)

            val sysFaderRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

            val sysMuteBtn = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(28)).apply {
                    marginEnd = dp(6)
                }
                background = deckRipple(
                    make3DKeycap(COLOR_CYAN_GLASS_TOP, COLOR_CYAN_GLASS_BOTTOM, COLOR_CYAN_SHADOW, COLOR_CYAN_STROKE, 5.5f, 2.5f),
                )
                setPadding(dp(5), dp(4), dp(5), dp(6.5f))
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
                    dp(1),
                    Color.parseColor(if (pct > 0) "#AA00E5FF" else "#AAFF2E63"),
                )
                sysMuteBtn.setImageResource(
                    if (pct > 0) R.drawable.baseline_volume_up_black_24
                    else R.drawable.baseline_volume_off_black_24,
                )
                sysMuteBtn.setColorFilter(
                    Color.parseColor(if (pct > 0) COLOR_CYAN else COLOR_CRIMSON),
                )
            }

            sysFader = DjFaderView(
                context = context,
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
                layoutParams = LinearLayout.LayoutParams(0, dp(25), 1f)
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

            syncSystemVolumeUi = {
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
                    if (cleanedUp || activeOverlay !== overlayRef) return
                    val streamType = intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC)
                    if (streamType == null || streamType == AudioManager.STREAM_MUSIC) {
                        syncSystemVolumeUi?.invoke()
                    }
                }
            }
            volumeReceiver = receiver
            try {
                ContextCompat.registerReceiver(
                    appContext,
                    receiver,
                    IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                    ContextCompat.RECEIVER_EXPORTED,
                )
            } catch (_: Throwable) {
                volumeReceiver = null
            }

            systemCard.addView(sysFaderRow)
            chassis.addView(systemCard)

            val autoLabel = context.getString(R.string.multiview_quality_auto)
            val cardVolumeUpdaters = mutableMapOf<String, (Float) -> Unit>()
            val cardPillUpdaters = mutableListOf<() -> Unit>()

            // ==========================================
            // MASTER BUS (ALL-STREAMS MIXER + GLOBAL QUALITY)
            // ==========================================
            val masterCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(
                        Color.parseColor("#3A172304"),
                        Color.parseColor("#4A080D02"),
                        Color.parseColor("#3D121B04"),
                    ),
                ).apply {
                    cornerRadius = dp(9).toFloat()
                    setStroke(dp(1.25f).coerceAtLeast(1), Color.parseColor("#B8C2FF00"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(4.5f)
                }
            }
            masterCardRef = masterCard

            val masterHeader = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(3)
                }
            }

            val masterBadge = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#59182604"))
                    cornerRadius = dp(5).toFloat()
                    setStroke(dp(1), Color.parseColor("#AAC2FF00"))
                }
                setPadding(dp(6), dp(2), dp(7), dp(2))
            }
            masterBadge.addView(TextView(context).apply {
                text = "◆"
                textSize = 9.5f
                setTextColor(Color.parseColor(COLOR_VOLT))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(4) }
            })
            masterBadge.addView(TextView(context).apply {
                text = "MASTER · ALL STREAMS"
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.05f
                setTextColor(Color.WHITE)
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
            })
            masterHeader.addView(masterBadge)

            masterHeader.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })

            val masterPercentView = TextView(context).apply {
                textSize = if (isLandscape) 10.5f else 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(COLOR_VOLT))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#4D0B1005"))
                    cornerRadius = dp(5).toFloat()
                    setStroke(dp(1), Color.parseColor("#88C2FF00"))
                }
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }
            masterHeader.addView(masterPercentView)
            masterCard.addView(masterHeader)

            val standardQualities = listOf(autoLabel, "1080p", "720p", "480p", "360p", "160p")
            val masterQualityRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(4)
                }
            }
            val masterPillViews = mutableListOf<TextView>()

            fun resolveActiveMasterQuality(): String {
                val state = stateProvider()
                val playback = playbackProvider()
                val resList = state.streams.mapNotNull { stream ->
                    val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@mapNotNull null
                    val ov = state.qualityOverrides[id]
                    val act = playback[id]?.qualityLabel.orEmpty()
                    ov ?: act.takeIf { it.isNotBlank() }
                }
                return when {
                    resList.any { it.contains("1080") } -> "1080p"
                    resList.any { it.contains("720") } -> "720p"
                    resList.any { it.contains("480") } -> "480p"
                    resList.any { it.contains("360") } -> "360p"
                    resList.any { it.contains("160") } -> "160p"
                    else -> autoLabel
                }
            }

            var selectedMasterQuality = resolveActiveMasterQuality()
            fun refreshMasterPills() {
                masterPillViews.forEach { pill ->
                    val pillQual = pill.tag as? String ?: ""
                    val isSelected = pillQual == selectedMasterQuality
                    val bg = if (isSelected) {
                        make3DKeycap(
                            topColorHex = "#ED273B07",
                            bottomColorHex = "#F5121D03",
                            shadowColorHex = COLOR_VOLT_SHADOW,
                            strokeColorHex = COLOR_VOLT_STROKE,
                            cornerRadiusDp = 5.5f,
                            shadowDepthDp = 2.8f,
                        )
                    } else {
                        make3DKeycap(
                            topColorHex = "#BA131B07",
                            bottomColorHex = "#CC080C04",
                            shadowColorHex = "#85030501",
                            strokeColorHex = "#6694C21A",
                            cornerRadiusDp = 5.5f,
                            shadowDepthDp = 2.8f,
                        )
                    }
                    pill.background = deckRipple(bg)
                    pill.setTextColor(
                        Color.parseColor(if (isSelected) "#EAFF80" else "#DCE8C8"),
                    )
                    centerKeycapText(pill, depthDp = 2.8f, sidePadDp = 1f)
                }
            }

            standardQualities.forEachIndexed { qIdx, qual ->
                val pill = TextView(context).apply {
                    tag = qual
                    text = if (qual == autoLabel) "Auto" else qual
                    maxLines = 1
                    typeface = Typeface.DEFAULT_BOLD
                    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                        this,
                        7,
                        if (isLandscape) 11 else 10,
                        1,
                        TypedValue.COMPLEX_UNIT_SP,
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        dp(if (isLandscape) 24 else 26),
                        1f,
                    ).apply {
                        if (qIdx < standardQualities.lastIndex) marginEnd = dp(3.5f)
                    }
                    setOnClickListener {
                        selectedMasterQuality = qual
                        val state = stateProvider()
                        val playback = playbackProvider()
                        state.streams.forEach { stream ->
                            val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEach
                            val targetLabel = if (qual == autoLabel) {
                                null
                            } else {
                                playback[id]?.availableQualities?.firstOrNull { it.contains(qual, ignoreCase = true) } ?: qual
                            }
                            viewModel.setQualityOverride(id, targetLabel)
                        }
                        refreshMasterPills()
                        cardPillUpdaters.forEach { it.invoke() }
                    }
                }
                masterPillViews.add(pill)
                masterQualityRow.addView(pill)
            }
            refreshMasterPills()
            masterCard.addView(masterQualityRow)

            // App Master Volume Fader Row
            val masterFaderRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val masterMuteBtn = ImageButton(context).apply {
                val btnSize = dp(27)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginEnd = dp(6)
                }
                background = deckRipple(
                    make3DKeycap(COLOR_VOLT_GLASS_TOP, COLOR_VOLT_GLASS_BOTTOM, COLOR_VOLT_SHADOW, "#99C2FF00", 5.5f, 2.5f),
                )
                setPadding(dp(4), dp(4), dp(4), dp(6))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            masterFaderRow.addView(masterMuteBtn)

            var masterVol = initialState.streams.firstOrNull()?.let { s ->
                MultiviewSessionReducer.stableIdentity(s)?.let { viewModel.audioVolume(it) }
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
                masterMuteBtn.setColorFilter(
                    Color.parseColor(if (masterVol > 0f) COLOR_VOLT else COLOR_CRIMSON),
                )
            }

            masterFader = DjFaderView(
                context = context,
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
                stateProvider().streams.forEach { stream ->
                    val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEach
                    viewModel.setAudioVolume(id, newVol)
                    cardVolumeUpdaters[id]?.invoke(newVol)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f)
            }
            updateMasterAudioUi(masterVol, updateFader = false)
            masterFaderRow.addView(masterFader)

            masterMuteBtn.setOnClickListener {
                val target = if (masterVol > 0f) 0f else viewModel.defaultAudioVolume().takeIf { it > 0f } ?: 1f
                updateMasterAudioUi(target, updateFader = true)
                stateProvider().streams.forEach { stream ->
                    val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEach
                    viewModel.setAudioVolume(id, target)
                    cardVolumeUpdaters[id]?.invoke(target)
                }
            }
            masterCard.addView(masterFaderRow)
            chassis.addView(masterCard)

            // ==========================================
            // STREAM MIXER CHANNEL STRIPS
            // ==========================================
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

            fun buildStreamCard(
                index: Int,
                stream: Stream,
                isGridCell: Boolean,
                totalCount: Int,
            ): LinearLayout {
                val identity = MultiviewSessionReducer.stableIdentity(stream) ?: ""
                var vol = viewModel.audioVolume(identity)

                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val pSide = dp(7.5f)
                    val pVert = dp(5f)
                    setPadding(pSide, pVert, pSide, pVert)
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.parseColor("#380D0F1C"),
                            Color.parseColor("#47070811"),
                        ),
                    ).apply {
                        cornerRadius = dp(9).toFloat()
                        setStroke(
                            dp(1.1f).coerceAtLeast(1),
                            Color.parseColor(if (vol > 0f) "#AA9D4EDD" else "#4D453969"),
                        )
                    }
                    layoutParams = if (isGridCell) {
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                    } else {
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            bottomMargin = dp(if (index < totalCount - 1) 4 else 0)
                        }
                    }
                }

                // ROW 1: #N + Avatar + Bright Streamer Name + 4/3s Live Health Pill + Big [ ] + Big ✕
                val row1 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }

                val slotBadge = TextView(context).apply {
                    text = "#${index + 1}"
                    setTextColor(Color.parseColor("#E9D5FF"))
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#66160D2E"))
                        cornerRadius = dp(4).toFloat()
                        setStroke(dp(1), Color.parseColor("#88A855F7"))
                    }
                    setPadding(dp(4.5f), dp(1.5f), dp(4.5f), dp(1.5f))
                }
                row1.addView(slotBadge)

                val avatar = ImageView(context).apply {
                    val avSize = dp(if (isGridCell) 18 else 19)
                    layoutParams = LinearLayout.LayoutParams(avSize, avSize).apply {
                        marginStart = dp(5)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(4).toFloat())
                        }
                    }
                    setImageResource(R.drawable.ic_person_placeholder)
                }
                try {
                    loadStreamProfileImage(context, avatar, stream)
                } catch (_: Throwable) {
                }
                row1.addView(avatar)

                val nameView = TextView(context).apply {
                    text = displayName(stream)
                    setTextColor(Color.WHITE)
                    textSize = if (isGridCell) 11.5f else 12f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setShadowLayer(6f, 0f, 1.5f, Color.BLACK)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(6)
                        marginEnd = dp(4)
                    }
                }
                row1.addView(nameView)

                // Live Buffer / Latency Pill (same "4/3s" readout as single player)
                val healthPill = TextView(context).apply {
                    textSize = if (isGridCell) 8.5f else 9f
                    typeface = Typeface.MONOSPACE
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    includeFontPadding = false
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginEnd = dp(4)
                    }
                }

                fun updateHealthPill(health: MultiviewLiveHealth?) {
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
                        cornerRadius = dp(4.5f).toFloat()
                        setStroke(dp(1), Color.parseColor(strokeHex))
                    }
                }
                healthUpdaters[identity] = ::updateHealthPill
                row1.addView(healthPill)

                val actionBtnW = dp(if (isGridCell) 30 else 32)
                val actionBtnH = dp(if (isGridCell) 27 else 28)

                val openPlayerBtn = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(actionBtnW, actionBtnH).apply {
                        marginEnd = dp(5)
                    }
                    background = deckRipple(
                        make3DKeycap(COLOR_UNSEL_GLASS_TOP, COLOR_UNSEL_GLASS_BOTTOM, COLOR_UNSEL_SHADOW, "#8864748B", 5f, 2f),
                    )
                    setImageResource(R.drawable.ic_pref_fullscreen)
                    setColorFilter(Color.parseColor("#E2E8F0"))
                    setPadding(dp(5.5f), dp(4.5f), dp(5.5f), dp(6.5f))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = context.getString(R.string.multiview_open_player)
                    setOnClickListener {
                        cleanupOverlay()
                        onOpenNormalPlayer(identity)
                    }
                }
                row1.addView(openPlayerBtn)

                val closeStreamBtn = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(actionBtnW, actionBtnH)
                    background = deckRipple(
                        make3DKeycap(COLOR_CRIMSON_GLASS_TOP, COLOR_CRIMSON_GLASS_BOTTOM, COLOR_CRIMSON_SHADOW, COLOR_CRIMSON_STROKE, 5f, 2f),
                    )
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(Color.parseColor("#FF7096"))
                    setPadding(dp(6f), dp(5f), dp(6f), dp(7f))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = context.getString(R.string.multiview_remove_stream)
                    setOnClickListener {
                        viewModel.remove(identity)
                        if (stateProvider().streams.isEmpty()) {
                            cleanupOverlay()
                        } else {
                            populateCards()
                        }
                    }
                }
                row1.addView(closeStreamBtn)
                card.addView(row1)

                // ROW 2: MUTE + CYBER DJ FADER + % + REORDER ▲ ▼
                val row2 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(3)
                        bottomMargin = dp(3)
                    }
                }

                val muteBtn = ImageButton(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(26), dp(24)).apply {
                        marginEnd = dp(4)
                    }
                    background = deckRipple(
                        make3DKeycap(COLOR_UNSEL_GLASS_TOP, COLOR_UNSEL_GLASS_BOTTOM, COLOR_UNSEL_SHADOW, "#776D28D9", 4.5f, 2f),
                    )
                    setPadding(dp(3.5f), dp(3f), dp(3.5f), dp(5f))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                row2.addView(muteBtn)

                val percentView = TextView(context).apply {
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(3)
                        marginEnd = dp(3)
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
                    muteBtn.setColorFilter(
                        Color.parseColor(if (vol > 0f) COLOR_PURPLE_BRIGHT else COLOR_CRIMSON),
                    )
                    (card.background as? GradientDrawable)?.setStroke(
                        dp(1.1f).coerceAtLeast(1),
                        Color.parseColor(if (vol > 0f) "#AA9D4EDD" else "#4D453969"),
                    )
                }

                fader = DjFaderView(context, vol, isCompact = false) { newVol ->
                    viewModel.setAudioVolume(identity, newVol)
                    renderCardAudio(newVol, updateFader = false)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(23), 1f)
                }
                renderCardAudio(vol, updateFader = false)
                cardVolumeUpdaters[identity] = { newVol ->
                    renderCardAudio(newVol, updateFader = true)
                }

                muteBtn.setOnClickListener {
                    val target = if (vol > 0f) 0f else viewModel.defaultAudioVolume().takeIf { it > 0f } ?: 1f
                    viewModel.setAudioVolume(identity, target)
                    renderCardAudio(target, updateFader = true)
                }

                row2.addView(fader)
                row2.addView(percentView)

                val orderBtnSize = dp(24)
                val upBtn = TextView(context).apply {
                    text = "▲"
                    setTextColor(Color.parseColor(if (index > 0) COLOR_PURPLE_BRIGHT else "#4A4366"))
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(orderBtnSize, orderBtnSize).apply { marginEnd = dp(3) }
                    if (index > 0) {
                        background = deckRipple(
                            make3DKeycap(COLOR_UNSEL_GLASS_TOP, COLOR_UNSEL_GLASS_BOTTOM, COLOR_UNSEL_SHADOW, "#776D28D9", 4f, 2f),
                        )
                        centerKeycapText(this, depthDp = 2f, sidePadDp = 0f)
                        setOnClickListener {
                            viewModel.reorder(identity, index - 1)
                            populateCards()
                        }
                    } else {
                        centerKeycapText(this, depthDp = 0f, sidePadDp = 0f)
                    }
                }
                row2.addView(upBtn)

                val downBtn = TextView(context).apply {
                    text = "▼"
                    setTextColor(Color.parseColor(if (index < totalCount - 1) COLOR_PURPLE_BRIGHT else "#4A4366"))
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(orderBtnSize, orderBtnSize)
                    if (index < totalCount - 1) {
                        background = deckRipple(
                            make3DKeycap(COLOR_UNSEL_GLASS_TOP, COLOR_UNSEL_GLASS_BOTTOM, COLOR_UNSEL_SHADOW, "#776D28D9", 4f, 2f),
                        )
                        centerKeycapText(this, depthDp = 2f, sidePadDp = 0f)
                        setOnClickListener {
                            viewModel.reorder(identity, index + 1)
                            populateCards()
                        }
                    } else {
                        centerKeycapText(this, depthDp = 0f, sidePadDp = 0f)
                    }
                }
                row2.addView(downBtn)
                card.addView(row2)

                // ROW 3: TRANSPARENT 3D GLASS QUALITY PILLS
                val row3 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }

                val pillViews = mutableListOf<TextView>()
                fun refreshThisPills() {
                    val state = stateProvider()
                    val overrideQuality = state.qualityOverrides[identity]
                    val activePlaying = playbackProvider()[identity]?.qualityLabel.orEmpty()
                    val targetMatch = when {
                        overrideQuality != null -> overrideQuality
                        activePlaying.isNotBlank() -> activePlaying
                        else -> autoLabel
                    }

                    pillViews.forEach { pill ->
                        val pTag = pill.tag as? String ?: ""
                        val isSelected = when {
                            pTag == autoLabel && overrideQuality == null && activePlaying.isBlank() -> true
                            pTag == autoLabel && overrideQuality == null && activePlaying.equals("AUTO", ignoreCase = true) -> true
                            pTag != autoLabel && targetMatch.contains(pTag, ignoreCase = true) -> true
                            else -> false
                        }
                        val bg = if (isSelected) {
                            make3DKeycap(
                                topColorHex = COLOR_PURPLE_GLASS_TOP,
                                bottomColorHex = COLOR_PURPLE_GLASS_BOTTOM,
                                shadowColorHex = COLOR_PURPLE_SHADOW,
                                strokeColorHex = COLOR_PURPLE_STROKE,
                                cornerRadiusDp = 4.5f,
                                shadowDepthDp = 2.2f,
                            )
                        } else {
                            make3DKeycap(
                                topColorHex = COLOR_UNSEL_GLASS_TOP,
                                bottomColorHex = COLOR_UNSEL_GLASS_BOTTOM,
                                shadowColorHex = COLOR_UNSEL_SHADOW,
                                strokeColorHex = COLOR_UNSEL_STROKE,
                                cornerRadiusDp = 4.5f,
                                shadowDepthDp = 2.2f,
                            )
                        }
                        pill.background = deckRipple(bg)
                        pill.setTextColor(
                            Color.parseColor(if (isSelected) "#EFE1FF" else "#CBD5E1"),
                        )
                        centerKeycapText(pill, depthDp = 2.2f, sidePadDp = 1f)
                    }
                }
                cardPillUpdaters.add(::refreshThisPills)

                val displayQualities = listOf(autoLabel, "1080p", "720p", "480p", "360p", "160p")
                displayQualities.forEachIndexed { qIdx, qual ->
                    val pill = TextView(context).apply {
                        tag = qual
                        text = if (qual == autoLabel) "Auto" else qual
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
                            dp(if (isGridCell) 23 else 24),
                            1f,
                        ).apply {
                            if (qIdx < displayQualities.lastIndex) marginEnd = dp(3)
                        }
                        setOnClickListener {
                            val available = playbackProvider()[identity]?.availableQualities.orEmpty()
                            val match = if (qual == autoLabel) {
                                null
                            } else {
                                available.firstOrNull { it.contains(qual, ignoreCase = true) } ?: qual
                            }
                            viewModel.setQualityOverride(identity, match)
                            refreshThisPills()
                            selectedMasterQuality = resolveActiveMasterQuality()
                            refreshMasterPills()
                        }
                    }
                    pillViews.add(pill)
                    row3.addView(pill)
                }
                refreshThisPills()
                card.addView(row3)
                return card
            }

            populateCards = {
                streamListLayout.removeAllViews()
                cardPillUpdaters.clear()
                cardVolumeUpdaters.clear()
                healthUpdaters.clear()
                val streams = stateProvider().streams.toList()
                val useTwoColumns = isLandscape && streams.size > 1
                if (useTwoColumns) {
                    val rows = streams.chunked(2)
                    rows.forEachIndexed { rowIdx, pair ->
                        val rowContainer = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                if (rowIdx < rows.lastIndex) bottomMargin = dp(4.5f)
                            }
                        }
                        val firstCard = buildStreamCard(rowIdx * 2, pair[0], isGridCell = true, totalCount = streams.size).apply {
                            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(3.5f)
                        }
                        rowContainer.addView(firstCard)
                        if (pair.size > 1) {
                            val secondCard = buildStreamCard(rowIdx * 2 + 1, pair[1], isGridCell = true, totalCount = streams.size).apply {
                                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(3.5f)
                            }
                            rowContainer.addView(secondCard)
                        } else {
                            rowContainer.addView(View(context).apply {
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                                    marginStart = dp(3.5f)
                                }
                            })
                        }
                        streamListLayout.addView(rowContainer)
                    }
                } else {
                    streams.forEachIndexed { index, stream ->
                        streamListLayout.addView(
                            buildStreamCard(index, stream, isGridCell = false, totalCount = streams.size),
                        )
                    }
                }
                val initialHealth = try {
                    healthProvider()
                } catch (_: Throwable) {
                    emptyMap()
                }
                healthUpdaters.forEach { (id, updater) -> updater(initialHealth[id]) }
            }
            populateCards()
            chassis.addView(streamScroll)

            // ==========================================
            // SINGLE-ROW DARK SOLID-GLASS MACRO DECK
            // ==========================================
            val bottomMacros = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(4.5f), dp(4.5f), dp(4.5f), dp(4.5f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#330B0B16"))
                    cornerRadius = dp(9).toFloat()
                    setStroke(dp(1), Color.parseColor("#666D28D9"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(4.5f)
                }
            }
            bottomMacrosRef = bottomMacros

            fun createMacroBtn(
                label: String,
                topHex: String,
                bottomHex: String,
                shadowHex: String,
                strokeHex: String,
                textHex: String,
                onClick: () -> Unit,
            ): TextView = TextView(context).apply {
                text = label
                maxLines = 1
                typeface = Typeface.DEFAULT_BOLD
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this,
                    8,
                    if (isLandscape) 11 else 10,
                    1,
                    TypedValue.COMPLEX_UNIT_SP,
                )
                setTextColor(Color.parseColor(textHex))
                background = deckRipple(make3DKeycap(topHex, bottomHex, shadowHex, strokeHex, 6f, 3f))
                centerKeycapText(this, depthDp = 3f, sidePadDp = 3f)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    dp(if (isLandscape) 31 else 32),
                    1f,
                ).apply {
                    marginStart = dp(2.5f)
                    marginEnd = dp(2.5f)
                }
                setOnClickListener {
                    onClick()
                    val firstVol = stateProvider().streams.firstOrNull()?.let { s ->
                        MultiviewSessionReducer.stableIdentity(s)?.let { viewModel.audioVolume(it) }
                    } ?: 0f
                    updateMasterAudioUi(firstVol, updateFader = true)
                    populateCards()
                }
            }

            bottomMacros.addView(
                createMacroBtn("MUTE ALL", COLOR_CRIMSON_GLASS_TOP, COLOR_CRIMSON_GLASS_BOTTOM, COLOR_CRIMSON_SHADOW, COLOR_CRIMSON_STROKE, "#FF7096") {
                    stateProvider().streams.forEach { stream ->
                        MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                            viewModel.setAudioVolume(id, 0f)
                        }
                    }
                },
            )
            bottomMacros.addView(
                createMacroBtn("EQUALIZE", COLOR_PURPLE_GLASS_TOP, COLOR_PURPLE_GLASS_BOTTOM, COLOR_PURPLE_SHADOW, COLOR_PURPLE_STROKE, "#E2C6FF") {
                    val streams = stateProvider().streams
                    val equalVol = (1.0f / streams.size.coerceAtLeast(1)).coerceIn(0.15f, 1f)
                    streams.forEach { stream ->
                        MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                            viewModel.setAudioVolume(id, equalVol)
                        }
                    }
                },
            )
            bottomMacros.addView(
                createMacroBtn("SOLO ACTIVE", COLOR_CYAN_GLASS_TOP, COLOR_CYAN_GLASS_BOTTOM, COLOR_CYAN_SHADOW, COLOR_CYAN_STROKE, "#7CEEFF") {
                    val state = stateProvider()
                    val active = state.activeIdentity
                        ?: state.streams.firstOrNull()?.let { MultiviewSessionReducer.stableIdentity(it) }
                    state.streams.forEach { stream ->
                        MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                            viewModel.setAudioVolume(id, if (id == active) 1f else 0f)
                        }
                    }
                },
            )
            bottomMacros.addView(
                createMacroBtn("REMOVE ALL", COLOR_AMBER_GLASS_TOP, COLOR_AMBER_GLASS_BOTTOM, COLOR_AMBER_SHADOW, COLOR_AMBER_STROKE, "#FF9666") {
                    stateProvider().streams.toList().forEach { stream ->
                        MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                            viewModel.remove(id)
                        }
                    }
                    cleanupOverlay()
                },
            )
            chassis.addView(bottomMacros)

            // ==========================================
            // IN-FRAGMENT OVERLAY + MT TOOLBAR CLICK-THROUGH
            // ==========================================
            val cardWidth = if (isLandscape) {
                (hostWidth * 0.70f).roundToInt()
            } else {
                (hostWidth * 0.94f).roundToInt()
            }
            chassis.measure(
                View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxDialogHeight, View.MeasureSpec.AT_MOST),
            )
            val measuredDeckHeight = chassis.measuredHeight.coerceAtLeast(dp(180))

            val chassisLp = FrameLayout.LayoutParams(
                cardWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            when {
                isLandscape || isFloatingWindow -> {
                    chassisLp.gravity = Gravity.CENTER
                }
                initialState.layoutMode == MultiviewLayoutMode.FOCUS || initialState.streams.size <= 1 -> {
                    chassisLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    val targetY = localAnchorBottom ?: dp(48)
                    val maxAllowedY = (hostHeight - measuredDeckHeight - dp(8)).coerceAtLeast(0)
                    chassisLp.topMargin = targetY.coerceIn(0, maxAllowedY)
                }
                initialState.layoutMode == MultiviewLayoutMode.TIERED -> {
                    chassisLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    chassisLp.bottomMargin = (hostHeight * 0.14f).roundToInt()
                }
                else -> {
                    chassisLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    chassisLp.bottomMargin = dp(16)
                }
            }

            fun isPointInsideView(view: View, rawX: Int, rawY: Int): Boolean {
                if (!view.isShown || view.width <= 0 || view.height <= 0) return false
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                return rawX in loc[0] until (loc[0] + view.width) &&
                    rawY in loc[1] until (loc[1] + view.height)
            }

            overlayWrapper = object : FrameLayout(context) {
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
                                resetDismissTimer()
                                return super.dispatchTouchEvent(ev)
                            }
                            if (isPointInsideView(toggleButton, rawX, rawY)) {
                                isDismissing = true
                                cleanupOverlay()
                                return true
                            }
                            if (toolbarView.isShown && isPointInsideView(toolbarView, rawX, rawY)) {
                                isDismissing = true
                                post { cleanupOverlay() }
                                return false
                            }
                            isDismissing = true
                            cleanupOverlay()
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (touchStartedInsideChassis) {
                                resetDismissTimer()
                                return super.dispatchTouchEvent(ev)
                            }
                            return false
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            val wasInside = touchStartedInsideChassis
                            touchStartedInsideChassis = false
                            if (wasInside) {
                                resetDismissTimer()
                                return super.dispatchTouchEvent(ev)
                            }
                            return false
                        }
                    }
                    return if (touchStartedInsideChassis) super.dispatchTouchEvent(ev) else false
                }
            }

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
            chassis.translationY = dp(if (fromTop) -10 else 14).toFloat()
            chassis.post {
                if (cleanedUp || activeOverlay !== overlayRef) return@post
                if (fromTop && localAnchorBottom != null) {
                    val deckHeight = chassis.height.takeIf { it > 0 } ?: chassis.measuredHeight
                    val maxAllowedY = (hostContainer.height.takeIf { it > 0 }?.let { it - deckHeight - dp(8) }
                        ?: (hostHeight - deckHeight - dp(8))).coerceAtLeast(0)
                    val targetY = localAnchorBottom.coerceIn(0, maxAllowedY)
                    val lp = chassis.layoutParams as FrameLayout.LayoutParams
                    if (lp.topMargin != targetY) {
                        lp.topMargin = targetY
                        chassis.layoutParams = lp
                    }
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
