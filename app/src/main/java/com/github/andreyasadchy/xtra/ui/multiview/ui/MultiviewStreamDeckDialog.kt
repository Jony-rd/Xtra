package com.github.andreyasadchy.xtra.ui.multiview.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
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
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.TextViewCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.common.loadStreamProfileImage
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewSessionReducer
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewSessionState
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewViewModel
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewPlaybackSnapshot
import kotlin.math.abs
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

    fun show(
        context: Context,
        isFloatingWindow: Boolean,
        anchorBottomPx: Int? = null,
        stateProvider: () -> MultiviewSessionState,
        playbackProvider: () -> Map<String, MultiviewPlaybackSnapshot>,
        viewModel: MultiviewViewModel,
        onOpenSleepTimer: () -> Unit,
        onOpenNormalPlayer: (String) -> Unit,
        onUnlockControls: () -> Unit,
    ) {
        val initialState = stateProvider()
        if (initialState.streams.isEmpty()) {
            onUnlockControls()
            return
        }

        try {
            var dialog: Dialog? = null
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
            val shouldAnchorUnderHeader = !isLandscape && !isFloatingWindow &&
                (initialState.layoutMode == MultiviewLayoutMode.FOCUS || initialState.streams.size <= 1) &&
                anchorBottomPx != null

            val autoDismissHandler = Handler(Looper.getMainLooper())
            val dismissRunnable = Runnable {
                try {
                    if (dialog?.isShowing == true) {
                        dialog?.dismiss()
                    }
                } catch (_: Throwable) {
                }
            }
            fun resetDismissTimer() {
                autoDismissHandler.removeCallbacks(dismissRunnable)
                autoDismissHandler.postDelayed(dismissRunnable, 4000L)
            }

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
                            Color.parseColor(if (pressed) "#10FFFFFF" else "#2EFFFFFF"),
                            Color.TRANSPARENT,
                        ),
                    ).apply {
                        cornerRadius = radiusPx
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

            val baseMaxHeight = (metrics.heightPixels * (if (isLandscape) 0.92f else 0.84f)).roundToInt()
            val availBelowHeader = if (shouldAnchorUnderHeader && anchorBottomPx != null) {
                metrics.heightPixels - anchorBottomPx - dp(8)
            } else {
                baseMaxHeight
            }
            val maxDialogHeight = if (shouldAnchorUnderHeader && availBelowHeader >= dp(260)) {
                minOf(baseMaxHeight, availBelowHeader)
            } else {
                baseMaxHeight
            }

            var headerRef: LinearLayout? = null
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
                    listOfNotNull(headerRef, masterCardRef, bottomMacrosRef).forEach { child ->
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
                val pSide = dp(if (isLandscape) 9 else 8)
                val pVert = dp(if (isLandscape) 6 else 6)
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

            val chassisContainer = object : FrameLayout(context) {
                override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
                    resetDismissTimer()
                    return super.dispatchTouchEvent(ev)
                }
            }
            chassisContainer.addView(
                chassis,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

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
                centerKeycapText(this, depthDp = 2.5f, sidePadDp = 9f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(if (isLandscape) 24 else 25),
                ).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    try {
                        onOpenSleepTimer()
                    } catch (_: Throwable) {
                    }
                }
            }
            header.addView(sleepBtn)

            val closeBtn = TextView(context).apply {
                text = "✕"
                textSize = if (isLandscape) 12.5f else 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FF7096"))
                background = deckRipple(
                    make3DKeycap(COLOR_CRIMSON_GLASS_TOP, COLOR_CRIMSON_GLASS_BOTTOM, COLOR_CRIMSON_SHADOW, COLOR_CRIMSON_STROKE, 5.5f, 2.5f),
                )
                centerKeycapText(this, depthDp = 2.5f, sidePadDp = 0f)
                val btnSize = dp(if (isLandscape) 24 else 25)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                setOnClickListener {
                    try {
                        dialog?.dismiss()
                    } catch (_: Throwable) {
                    }
                }
            }
            header.addView(closeBtn)
            chassis.addView(header)

            val autoLabel = context.getString(R.string.multiview_quality_auto)
            val cardVolumeUpdaters = mutableMapOf<String, (Float) -> Unit>()
            val cardPillUpdaters = mutableListOf<() -> Unit>()

            // ==========================================
            // MASTER BUS (Transparent Cyber-Purple Glass)
            // ==========================================
            val masterCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pSide = dp(8)
                val pVert = dp(if (isLandscape) 4.5f else 5f)
                setPadding(pSide, pVert, pSide, pVert)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(
                        Color.parseColor("#33140D2B"),
                        Color.parseColor("#400A0717"),
                    ),
                ).apply {
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1.2f).coerceAtLeast(1), Color.parseColor("#99A855F7"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(if (isLandscape) 4.5f else 4.5f)
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
                    bottomMargin = dp(3.5f)
                }
            }

            val masterBadge = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#4D160D2E"))
                    cornerRadius = dp(5).toFloat()
                    setStroke(dp(1), Color.parseColor("#88A855F7"))
                }
                setPadding(dp(6), dp(2), dp(7), dp(2))
            }
            masterBadge.addView(TextView(context).apply {
                text = "●"
                textSize = 8f
                setTextColor(Color.parseColor(COLOR_VOLT))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(4) }
            })
            masterBadge.addView(TextView(context).apply {
                text = "MASTER BUS"
                textSize = if (isLandscape) 9.5f else 9.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
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
                    bottomMargin = dp(if (isLandscape) 4.5f else 4.5f)
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
                            topColorHex = COLOR_VOLT_GLASS_TOP,
                            bottomColorHex = COLOR_VOLT_GLASS_BOTTOM,
                            shadowColorHex = COLOR_VOLT_SHADOW,
                            strokeColorHex = COLOR_VOLT_STROKE,
                            cornerRadiusDp = 5.5f,
                            shadowDepthDp = 2.8f,
                        )
                    } else {
                        make3DKeycap(
                            topColorHex = COLOR_UNSEL_GLASS_TOP,
                            bottomColorHex = COLOR_UNSEL_GLASS_BOTTOM,
                            shadowColorHex = COLOR_UNSEL_SHADOW,
                            strokeColorHex = COLOR_UNSEL_STROKE,
                            cornerRadiusDp = 5.5f,
                            shadowDepthDp = 2.8f,
                        )
                    }
                    pill.background = deckRipple(bg)
                    pill.setTextColor(
                        Color.parseColor(if (isSelected) "#DCFF66" else "#CBD5E1"),
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
                        dp(if (isLandscape) 25 else 27),
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

            val masterFaderRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val masterMuteBtn = ImageButton(context).apply {
                val btnSize = dp(if (isLandscape) 25 else 26)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginEnd = dp(6)
                }
                background = deckRipple(
                    make3DKeycap(COLOR_UNSEL_GLASS_TOP, COLOR_UNSEL_GLASS_BOTTOM, COLOR_UNSEL_SHADOW, COLOR_UNSEL_STROKE, 5.5f, 2.5f),
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

            masterFader = DjFaderView(context, masterVol, isCompact = false) { newVol ->
                updateMasterAudioUi(newVol, updateFader = false)
                stateProvider().streams.forEach { stream ->
                    val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEach
                    viewModel.setAudioVolume(id, newVol)
                    cardVolumeUpdaters[id]?.invoke(newVol)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(if (isLandscape) 23 else 24), 1f)
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
            // STREAM MIXER CHANNEL STRIPS (Tight Wrap, Scroll Ceiling Only When Needed)
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
                    val pVert = dp(4.5f)
                    setPadding(pSide, pVert, pSide, pVert)
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.parseColor("#2B0D0F1C"),
                            Color.parseColor("#38070811"),
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

                // ROW 1: #N + Avatar + Streamer Name + ▲ ▼ + Mute + % + Expand + Remove
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
                    setTextColor(Color.parseColor("#E2C6FF"))
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#4D160D2E"))
                        cornerRadius = dp(4).toFloat()
                        setStroke(dp(1), Color.parseColor("#88A855F7"))
                    }
                    setPadding(dp(4.5f), dp(1.5f), dp(4.5f), dp(1.5f))
                }
                row1.addView(slotBadge)

                val avatar = ImageView(context).apply {
                    val avSize = dp(if (isGridCell) 17 else 18)
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
                    textSize = if (isGridCell) 11f else 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(5)
                        marginEnd = dp(3)
                    }
                }
                row1.addView(nameView)

                val btnSize = dp(if (isGridCell) 20 else 21)
                val upBtn = TextView(context).apply {
                    text = "▲"
                    setTextColor(Color.parseColor(if (index > 0) COLOR_PURPLE_BRIGHT else "#4A4366"))
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { marginEnd = dp(3) }
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
                row1.addView(upBtn)

                val downBtn = TextView(context).apply {
                    text = "▼"
                    setTextColor(Color.parseColor(if (index < totalCount - 1) COLOR_PURPLE_BRIGHT else "#4A4366"))
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { marginEnd = dp(3) }
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
                row1.addView(downBtn)

                val muteBtn = ImageButton(context).apply {
                    layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                        marginEnd = dp(3)
                    }
                    background = deckRipple(
                        make3DKeycap(COLOR_UNSEL_GLASS_TOP, COLOR_UNSEL_GLASS_BOTTOM, COLOR_UNSEL_SHADOW, "#776D28D9", 4f, 2f),
                    )
                    setPadding(dp(3), dp(3), dp(3), dp(4.5f))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                row1.addView(muteBtn)

                val percentView = TextView(context).apply {
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(2)
                    }
                }
                row1.addView(percentView)

                val openPlayerBtn = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                        marginEnd = dp(2)
                    }
                    setImageResource(R.drawable.ic_pref_fullscreen)
                    setColorFilter(Color.parseColor("#CBD5E1"))
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    contentDescription = context.getString(R.string.multiview_open_player)
                    setOnClickListener {
                        try {
                            dialog?.dismiss()
                        } catch (_: Throwable) {
                        }
                        onOpenNormalPlayer(identity)
                    }
                }
                row1.addView(openPlayerBtn)

                val closeStreamBtn = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(Color.parseColor(COLOR_CRIMSON))
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    contentDescription = context.getString(R.string.multiview_remove_stream)
                    setOnClickListener {
                        viewModel.remove(identity)
                        if (stateProvider().streams.isEmpty()) {
                            dialog?.dismiss()
                        } else {
                            populateCards()
                        }
                    }
                }
                row1.addView(closeStreamBtn)
                card.addView(row1)

                // ROW 2: CYBER DJ FADER
                val row2 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(2)
                        bottomMargin = dp(2)
                    }
                }
                row2.addView(TextView(context).apply {
                    text = "0%"
                    textSize = 7.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#94A3B8"))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(3) }
                })

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
                    layoutParams = LinearLayout.LayoutParams(0, dp(if (isGridCell) 24 else 23), 1f)
                }
                row2.addView(fader)

                row2.addView(TextView(context).apply {
                    text = "100%"
                    textSize = 7.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#94A3B8"))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(3) }
                })

                renderCardAudio(vol, updateFader = false)
                cardVolumeUpdaters[identity] = { newVol ->
                    renderCardAudio(newVol, updateFader = true)
                }
                muteBtn.setOnClickListener {
                    val targetVol = if (vol > 0f) 0f else viewModel.defaultAudioVolume().takeIf { it > 0f } ?: 1f
                    viewModel.setAudioVolume(identity, targetVol)
                    renderCardAudio(targetVol, updateFader = true)
                }
                card.addView(row2)

                // ROW 3: 3D SOLID-GLASS QUALITY KEYCAPS
                val row3 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
                val displayQualities = listOf(autoLabel, "1080p", "720p", "480p", "360p", "160p")
                val pillViews = mutableListOf<TextView>()

                fun refreshThisPills() {
                    val state = stateProvider()
                    val playback = playbackProvider()
                    val overrideQuality = state.qualityOverrides[identity]
                    val activePlayingQuality = playback[identity]?.qualityLabel.orEmpty()
                    val currentRes = overrideQuality ?: activePlayingQuality
                    pillViews.forEach { pill ->
                        val qual = pill.tag as? String ?: ""
                        val isSelected = when {
                            currentRes.contains("1080") -> qual.contains("1080")
                            currentRes.contains("720") -> qual.contains("720")
                            currentRes.contains("480") -> qual.contains("480")
                            currentRes.contains("360") -> qual.contains("360")
                            currentRes.contains("160") -> qual.contains("160")
                            currentRes.isBlank() -> qual == autoLabel
                            else -> qual.contains(currentRes, ignoreCase = true) || currentRes.contains(qual, ignoreCase = true)
                        }
                        val pillBg = if (isSelected) {
                            make3DKeycap(
                                topColorHex = COLOR_PURPLE_GLASS_TOP,
                                bottomColorHex = COLOR_PURPLE_GLASS_BOTTOM,
                                shadowColorHex = COLOR_PURPLE_SHADOW,
                                strokeColorHex = COLOR_PURPLE_STROKE,
                                cornerRadiusDp = 5f,
                                shadowDepthDp = 2.6f,
                            )
                        } else {
                            make3DKeycap(
                                topColorHex = COLOR_UNSEL_GLASS_TOP,
                                bottomColorHex = COLOR_UNSEL_GLASS_BOTTOM,
                                shadowColorHex = COLOR_UNSEL_SHADOW,
                                strokeColorHex = COLOR_UNSEL_STROKE,
                                cornerRadiusDp = 5f,
                                shadowDepthDp = 2.6f,
                            )
                        }
                        pill.background = deckRipple(pillBg)
                        pill.setTextColor(
                            Color.parseColor(if (isSelected) "#FFFFFF" else "#CBD5E1"),
                        )
                        centerKeycapText(pill, depthDp = 2.6f, sidePadDp = 1f)
                    }
                }
                cardPillUpdaters.add(::refreshThisPills)

                displayQualities.forEachIndexed { qIdx, qual ->
                    val shortName = if (qual == autoLabel) "Auto" else qual
                    val pill = TextView(context).apply {
                        tag = qual
                        text = shortName
                        maxLines = 1
                        typeface = Typeface.DEFAULT_BOLD
                        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                            this,
                            7,
                            if (isGridCell) 10 else 10,
                            1,
                            TypedValue.COMPLEX_UNIT_SP,
                        )
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            dp(if (isGridCell) 24 else 25),
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
                    dp(if (isLandscape) 32 else 33),
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
                    dialog?.dismiss()
                },
            )
            chassis.addView(bottomMacros)

            // ==========================================
            // FLOATING TRANSPARENT WINDOW PLACEMENT
            // ==========================================
            dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setContentView(chassisContainer)
                window?.apply {
                    WindowCompat.setDecorFitsSystemWindows(this, false)
                    decorView.setBackgroundColor(Color.TRANSPARENT)
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    setDimAmount(0f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        attributes = attributes.apply {
                            layoutInDisplayCutoutMode =
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                    }
                    WindowCompat.getInsetsController(this, decorView).apply {
                        systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }

                    val displayWidth = metrics.widthPixels
                    val displayHeight = metrics.heightPixels
                    val cardWidth = when {
                        isLandscape -> (displayWidth * 0.70f).roundToInt()
                        else -> (displayWidth * 0.94f).roundToInt()
                    }
                    chassisContainer.measure(
                        View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(maxDialogHeight, View.MeasureSpec.AT_MOST),
                    )
                    val measuredDeckHeight = chassisContainer.measuredHeight.coerceAtLeast(dp(180))

                    setLayout(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                    val p = attributes
                    when {
                        isLandscape || isFloatingWindow -> {
                            setGravity(Gravity.CENTER)
                            p.y = 0
                        }
                        initialState.layoutMode == MultiviewLayoutMode.FOCUS || initialState.streams.size <= 1 -> {
                            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                            val targetY = anchorBottomPx ?: dp(48)
                            val maxAllowedY = (displayHeight - measuredDeckHeight - dp(8)).coerceAtLeast(0)
                            p.y = targetY.coerceIn(0, maxAllowedY)
                        }
                        initialState.layoutMode == MultiviewLayoutMode.TIERED -> {
                            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                            p.y = (displayHeight * 0.14f).roundToInt()
                        }
                        else -> {
                            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                            p.y = dp(16)
                        }
                    }
                    attributes = p
                }
                setOnDismissListener {
                    autoDismissHandler.removeCallbacks(dismissRunnable)
                    viewModel.persistSession()
                    onUnlockControls()
                }
            }
            dialog?.show()
            resetDismissTimer()

            val fromTop = !isLandscape && !isFloatingWindow &&
                (initialState.layoutMode == MultiviewLayoutMode.FOCUS || initialState.streams.size <= 1)
            chassis.alpha = 0f
            chassis.translationY = dp(if (fromTop) -10 else 14).toFloat()

            chassis.post {
                val activeDialog = dialog
                val activeWindow = activeDialog?.window
                if (fromTop && anchorBottomPx != null && activeDialog?.isShowing == true && activeWindow != null) {
                    val deckLoc = IntArray(2)
                    chassis.getLocationOnScreen(deckLoc)
                    val deckHeight = chassis.height.takeIf { it > 0 } ?: chassisContainer.measuredHeight
                    val targetY = anchorBottomPx.coerceIn(0, (metrics.heightPixels - deckHeight - dp(8)).coerceAtLeast(0))
                    val delta = targetY - deckLoc[1]
                    if (abs(delta) > 2) {
                        val lp = activeWindow.attributes
                        lp.y = (lp.y + delta).coerceAtLeast(0)
                        activeWindow.attributes = lp
                    }
                }
                chassis.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(170L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        } catch (_: Throwable) {
            onUnlockControls()
        }
    }
}

class DjFaderView(
    context: Context,
    initialVolume: Float,
    private val isCompact: Boolean = false,
    private val onVolumeChanged: (Float) -> Unit,
) : View(context) {
    constructor(
        context: Context,
        initialVolume: Float,
        onVolumeChanged: (Float) -> Unit,
    ) : this(context, initialVolume, false, onVolumeChanged)

    private var volume: Float = initialVolume.coerceIn(0f, 1f)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5905050B")
        style = Paint.Style.FILL
    }
    private val trackFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A855F7")
        style = Paint.Style.FILL
    }
    private val trackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#776D28D9")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C2FF00")
        strokeWidth = if (isCompact) 2f else 2.5f
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6664748B")
        strokeWidth = if (isCompact) 1.2f else 1.5f
    }
    private val knobShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0000000")
        style = Paint.Style.FILL
    }
    private val capBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D1036")
        style = Paint.Style.FILL
    }
    private val capBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C084FC")
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }
    private val knobGripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7E22CE")
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
        val pad = capWidth / 2f + dpToPx(if (isCompact) 3f else 4f)
        val trackW = width.toFloat() - (pad * 2f)
        if (trackW <= 0f) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, pad, trackW, snap = false)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x, pad, trackW, snap = false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                updateFromTouch(event.x, pad, trackW, snap = true)
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
