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
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.widget.TextViewCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.common.loadStreamProfileImage
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewSessionReducer
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewSessionState
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewViewModel
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewPlaybackSnapshot
import kotlin.math.roundToInt

object MultiviewStreamDeckDialog {
    private const val COLOR_CYAN = "#00F5D4"
    private const val COLOR_CYAN_DARK = "#00A892"
    private const val COLOR_CYAN_SHADOW = "#003D35"
    private const val COLOR_CYAN_STROKE = "#99FFF0"

    private const val COLOR_VIOLET = "#A970FF"
    private const val COLOR_VIOLET_DARK = "#6F36CC"
    private const val COLOR_VIOLET_SHADOW = "#290D52"
    private const val COLOR_VIOLET_STROKE = "#DEC4FF"

    private const val COLOR_ORANGE = "#FF5400"
    private const val COLOR_ORANGE_DARK = "#B83B00"
    private const val COLOR_ORANGE_SHADOW = "#401200"
    private const val COLOR_ORANGE_STROKE = "#FFB28A"

    private const val COLOR_CRIMSON = "#FF3D68"
    private const val COLOR_CRIMSON_DARK = "#B81940"
    private const val COLOR_CRIMSON_SHADOW = "#420515"
    private const val COLOR_CRIMSON_STROKE = "#FFA8BE"

    fun show(
        context: Context,
        isFloatingWindow: Boolean,
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
            val isMultiLandscape = isLandscape && initialState.streams.size > 1

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
                fun shade(c: Int, f: Float): Int = Color.rgb(
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
                            shade(Color.parseColor(shadowColorHex), 0.65f),
                        ),
                    ).apply {
                        cornerRadius = radiusPx
                        setStroke(dp(0.8f).coerceAtLeast(1), Color.parseColor("#55000000"))
                    }

                    val topColor = Color.parseColor(topColorHex)
                    val bottomColor = Color.parseColor(bottomColorHex)
                    val faceColors = if (pressed) {
                        intArrayOf(shade(topColor, 0.82f), shade(bottomColor, 0.82f))
                    } else {
                        intArrayOf(shade(topColor, 1.18f), topColor, bottomColor)
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
                            Color.parseColor(if (pressed) "#10FFFFFF" else "#36FFFFFF"),
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
                    ColorStateList.valueOf(Color.parseColor("#5500F5D4")),
                    base,
                    null,
                )

            // 3D Translucent Smoky AMOLED Glass Chassis
            val chassisRadius = dp(14).toFloat()
            val glassFill = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#BD0C0E17"),
                    Color.parseColor("#CC07080E"),
                    Color.parseColor("#C20A0D16"),
                ),
            ).apply {
                cornerRadius = chassisRadius
                setStroke(dp(1.25f).coerceAtLeast(1), Color.parseColor("#8800F5D4"))
            }
            val glassBevelHighlight = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#26FFFFFF"),
                    Color.parseColor("#08FFFFFF"),
                    Color.TRANSPARENT,
                ),
            ).apply {
                cornerRadius = chassisRadius
                setStroke(dp(1), Color.parseColor("#44383E5E"))
            }
            val chassisLayer = LayerDrawable(arrayOf(glassFill, glassBevelHighlight)).apply {
                setLayerInset(1, dp(1), dp(1), dp(1), dp(1))
            }

            val maxPortraitHeight = (metrics.heightPixels * 0.86f).roundToInt()
            val chassis = object : LinearLayout(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val constrainedHeight = if (!isLandscape && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
                        val rawSize = MeasureSpec.getSize(heightMeasureSpec)
                        val cap = if (rawSize > 0) minOf(rawSize, maxPortraitHeight) else maxPortraitHeight
                        MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
                    } else {
                        heightMeasureSpec
                    }
                    super.onMeasure(widthMeasureSpec, constrainedHeight)
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
                val pSide = dp(if (isLandscape) 10 else 9)
                val pVert = dp(if (isLandscape) 6 else 7)
                setPadding(pSide, pVert, pSide, pVert)
                background = chassisLayer
                elevation = dp(24).toFloat()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    outlineSpotShadowColor = Color.parseColor("#AA00F5D4")
                    outlineAmbientShadowColor = Color.parseColor("#6600F5D4")
                }
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
                    if (isMultiLandscape) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            // ==========================================
            // HEADER BAR (Clean, MT-matched, zero clutter)
            // ==========================================
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), dp(if (isLandscape) 4 else 4), dp(7), dp(if (isLandscape) 4 else 4))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.parseColor("#781B1E30"),
                        Color.parseColor("#6E121422"),
                    ),
                ).apply {
                    cornerRadius = dp(9).toFloat()
                    setStroke(dp(1), Color.parseColor("#75383E5E"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(if (isLandscape) 4 else 5)
                }
            }

            val deckIcon = ImageView(context).apply {
                val iconSize = dp(if (isLandscape) 20 else 19)
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
                textSize = if (isLandscape) 13f else 12.5f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.04f
            }
            header.addView(titleView)

            val sleepBtn = TextView(context).apply {
                text = "SLEEP"
                textSize = if (isLandscape) 10.5f else 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = deckRipple(
                    make3DKeycap("#262B45", "#16192B", "#07080F", "#525C8A", 5.5f, 2.5f),
                )
                centerKeycapText(this, depthDp = 2.5f, sidePadDp = 9f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(if (isLandscape) 25 else 26),
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
                textSize = if (isLandscape) 13f else 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = deckRipple(
                    make3DKeycap(COLOR_CRIMSON, COLOR_CRIMSON_DARK, COLOR_CRIMSON_SHADOW, COLOR_CRIMSON_STROKE, 5.5f, 2.5f),
                )
                centerKeycapText(this, depthDp = 2.5f, sidePadDp = 0f)
                val btnSize = dp(if (isLandscape) 25 else 26)
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
            // MASTER BUS (Translucent Violet-Trimmed Glass)
            // ==========================================
            val masterCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pSide = dp(if (isLandscape) 9 else 8)
                val pVert = dp(if (isLandscape) 5 else 6)
                setPadding(pSide, pVert, pSide, pVert)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(
                        Color.parseColor("#7019162B"),
                        Color.parseColor("#7D131121"),
                    ),
                ).apply {
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1.2f).coerceAtLeast(1), Color.parseColor("#99A970FF"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(if (isLandscape) 6 else 5)
                }
            }

            val masterHeader = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(if (isLandscape) 3.5f else 4f)
                }
            }

            val masterBadge = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#8817132A"))
                    cornerRadius = dp(5).toFloat()
                    setStroke(dp(1), Color.parseColor("#88A970FF"))
                }
                setPadding(dp(6), dp(2), dp(7), dp(2))
            }
            masterBadge.addView(TextView(context).apply {
                text = "●"
                textSize = 8f
                setTextColor(Color.parseColor(COLOR_CYAN))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(4) }
            })
            masterBadge.addView(TextView(context).apply {
                text = "MASTER BUS"
                textSize = if (isLandscape) 10f else 9.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })
            masterHeader.addView(masterBadge)

            masterHeader.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })

            val masterPercentView = TextView(context).apply {
                textSize = if (isLandscape) 11f else 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(COLOR_CYAN))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#99070B12"))
                    cornerRadius = dp(5).toFloat()
                    setStroke(dp(1), Color.parseColor("#7700F5D4"))
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
                    bottomMargin = dp(if (isLandscape) 6 else 5)
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
                            topColorHex = COLOR_VIOLET,
                            bottomColorHex = COLOR_VIOLET_DARK,
                            shadowColorHex = COLOR_VIOLET_SHADOW,
                            strokeColorHex = COLOR_VIOLET_STROKE,
                            cornerRadiusDp = 5.5f,
                            shadowDepthDp = 3f,
                        )
                    } else {
                        make3DKeycap(
                            topColorHex = "#25293E",
                            bottomColorHex = "#151827",
                            shadowColorHex = "#06070C",
                            strokeColorHex = "#485075",
                            cornerRadiusDp = 5.5f,
                            shadowDepthDp = 3f,
                        )
                    }
                    pill.background = deckRipple(bg)
                    pill.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#E5E7EB"))
                    centerKeycapText(pill, depthDp = 3f, sidePadDp = 1f)
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
                        if (isLandscape) 8 else 8,
                        if (isLandscape) 12 else 11,
                        1,
                        TypedValue.COMPLEX_UNIT_SP,
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        dp(if (isLandscape) 27 else 30),
                        1f,
                    ).apply {
                        if (qIdx < standardQualities.lastIndex) marginEnd = dp(4)
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
                val btnSize = dp(if (isLandscape) 27 else 28)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginEnd = dp(6)
                }
                background = deckRipple(make3DKeycap("#262A40", "#161826", "#06070C", "#4A5278", 5.5f, 2.5f))
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
                    Color.parseColor(if (masterVol > 0f) COLOR_CYAN else COLOR_CRIMSON),
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
                layoutParams = LinearLayout.LayoutParams(0, dp(if (isLandscape) 25 else 26), 1f)
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
            val streamScroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                isFillViewport = isLandscape && initialState.streams.size == 2
                layoutParams = if (isMultiLandscape) {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                } else {
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        weight = 1f
                    }
                }
            }
            val streamListLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (isLandscape && initialState.streams.size == 2) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            streamScroll.addView(streamListLayout)

            lateinit var populateCards: () -> Unit

            fun buildStreamCard(
                index: Int,
                stream: Stream,
                isGridCell: Boolean,
                expandCellHeight: Boolean,
                totalCount: Int,
            ): LinearLayout {
                val identity = MultiviewSessionReducer.stableIdentity(stream) ?: ""
                var vol = viewModel.audioVolume(identity)

                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val pSide = dp(if (isGridCell) 8 else 8)
                    val pVert = dp(if (isGridCell) 5f else 5.5f)
                    setPadding(pSide, pVert, pSide, pVert)
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.parseColor("#66131624"),
                            Color.parseColor("#750D0F19"),
                        ),
                    ).apply {
                        cornerRadius = dp(10).toFloat()
                        setStroke(
                            dp(1.1f).coerceAtLeast(1),
                            Color.parseColor(if (vol > 0f) "#8800F5D4" else "#66383E5E"),
                        )
                    }
                    layoutParams = if (isGridCell) {
                        LinearLayout.LayoutParams(
                            0,
                            if (expandCellHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                    } else {
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            bottomMargin = dp(if (index < totalCount - 1) 5 else 0)
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
                    setTextColor(Color.parseColor(COLOR_CYAN))
                    textSize = if (isGridCell) 9.5f else 9.5f
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#9907171B"))
                        cornerRadius = dp(4).toFloat()
                        setStroke(dp(1), Color.parseColor("#8800F5D4"))
                    }
                    setPadding(dp(4.5f), dp(1.5f), dp(4.5f), dp(1.5f))
                }
                row1.addView(slotBadge)

                val avatar = ImageView(context).apply {
                    val avSize = dp(if (isGridCell) 18 else 20)
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
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(5)
                        marginEnd = dp(3)
                    }
                }
                row1.addView(nameView)

                val btnSize = dp(if (isGridCell) 21 else 22)
                val upBtn = TextView(context).apply {
                    text = "▲"
                    setTextColor(Color.parseColor(if (index > 0) COLOR_CYAN else "#4A5270"))
                    textSize = if (isGridCell) 9.5f else 9f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { marginEnd = dp(3) }
                    if (index > 0) {
                        background = deckRipple(make3DKeycap("#262A40", "#161826", "#06070C", "#4A5278", 4f, 2f))
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
                    setTextColor(Color.parseColor(if (index < totalCount - 1) COLOR_CYAN else "#4A5270"))
                    textSize = if (isGridCell) 9.5f else 9f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { marginEnd = dp(3) }
                    if (index < totalCount - 1) {
                        background = deckRipple(make3DKeycap("#262A40", "#161826", "#06070C", "#4A5278", 4f, 2f))
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
                    background = deckRipple(make3DKeycap("#262A40", "#161826", "#06070C", "#4A5278", 4f, 2f))
                    setPadding(dp(3), dp(3), dp(3), dp(4.5f))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                row1.addView(muteBtn)

                val percentView = TextView(context).apply {
                    textSize = if (isGridCell) 9.5f else 9.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(if (isGridCell) 34 else 32), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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

                if (expandCellHeight) {
                    card.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) })
                }

                // ROW 2: DJ FADER
                val row2 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(if (isGridCell) 2 else 3)
                        bottomMargin = dp(if (isGridCell) 2 else 3)
                    }
                }
                row2.addView(TextView(context).apply {
                    text = "0%"
                    textSize = 8f
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
                    percentView.setTextColor(Color.parseColor(if (vol > 0f) COLOR_CYAN else "#94A3B8"))
                    muteBtn.setImageResource(
                        if (vol > 0f) R.drawable.baseline_volume_up_black_24
                        else R.drawable.baseline_volume_off_black_24,
                    )
                    muteBtn.setColorFilter(
                        Color.parseColor(if (vol > 0f) COLOR_CYAN else COLOR_CRIMSON),
                    )
                    (card.background as? GradientDrawable)?.setStroke(
                        dp(1.1f).coerceAtLeast(1),
                        Color.parseColor(if (vol > 0f) "#8800F5D4" else "#66383E5E"),
                    )
                }

                fader = DjFaderView(context, vol, isCompact = false) { newVol ->
                    viewModel.setAudioVolume(identity, newVol)
                    renderCardAudio(newVol, updateFader = false)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(if (isGridCell) 27 else 25), 1f)
                }
                row2.addView(fader)

                row2.addView(TextView(context).apply {
                    text = "100%"
                    textSize = 8f
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

                if (expandCellHeight) {
                    card.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) })
                }

                // ROW 3: 3D PHYSICAL QUALITY KEYCAPS
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
                                topColorHex = COLOR_CYAN,
                                bottomColorHex = COLOR_CYAN_DARK,
                                shadowColorHex = COLOR_CYAN_SHADOW,
                                strokeColorHex = COLOR_CYAN_STROKE,
                                cornerRadiusDp = 5f,
                                shadowDepthDp = 2.8f,
                            )
                        } else {
                            make3DKeycap(
                                topColorHex = "#25293E",
                                bottomColorHex = "#151827",
                                shadowColorHex = "#06070C",
                                strokeColorHex = "#485075",
                                cornerRadiusDp = 5f,
                                shadowDepthDp = 2.8f,
                            )
                        }
                        pill.background = deckRipple(pillBg)
                        pill.setTextColor(
                            if (isSelected) Color.parseColor("#041414")
                            else Color.parseColor("#E5E7EB"),
                        )
                        centerKeycapText(pill, depthDp = 2.8f, sidePadDp = 1f)
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
                            if (isGridCell) 11 else 10,
                            1,
                            TypedValue.COMPLEX_UNIT_SP,
                        )
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            dp(if (isGridCell) 27 else 27),
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
                    val expandSingleRow = rows.size == 1
                    rows.forEachIndexed { rowIdx, pair ->
                        val rowContainer = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = if (expandSingleRow) {
                                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                            } else {
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ).apply {
                                    if (rowIdx < rows.lastIndex) bottomMargin = dp(5)
                                }
                            }
                        }
                        val firstCard = buildStreamCard(rowIdx * 2, pair[0], isGridCell = true, expandCellHeight = expandSingleRow, totalCount = streams.size).apply {
                            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(4)
                        }
                        rowContainer.addView(firstCard)
                        if (pair.size > 1) {
                            val secondCard = buildStreamCard(rowIdx * 2 + 1, pair[1], isGridCell = true, expandCellHeight = expandSingleRow, totalCount = streams.size).apply {
                                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(4)
                            }
                            rowContainer.addView(secondCard)
                        } else {
                            rowContainer.addView(View(context).apply {
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                                    marginStart = dp(4)
                                }
                            })
                        }
                        streamListLayout.addView(rowContainer)
                    }
                } else {
                    streams.forEachIndexed { index, stream ->
                        streamListLayout.addView(
                            buildStreamCard(index, stream, isGridCell = false, expandCellHeight = false, totalCount = streams.size),
                        )
                    }
                }
            }
            populateCards()
            chassis.addView(streamScroll)

            // ==========================================
            // SINGLE-ROW 3D MACRO DECK ([MUTE ALL] [EQUALIZE] [SOLO ACTIVE] [REMOVE ALL])
            // ==========================================
            val bottomMacros = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(5), dp(5), dp(5))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#6E121523"))
                    cornerRadius = dp(9).toFloat()
                    setStroke(dp(1), Color.parseColor("#75383E5E"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(if (isLandscape) 6 else 5)
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
            ): TextView = TextView(context).apply {
                text = label
                maxLines = 1
                typeface = Typeface.DEFAULT_BOLD
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this,
                    8,
                    if (isLandscape) 12 else 11,
                    1,
                    TypedValue.COMPLEX_UNIT_SP,
                )
                setTextColor(Color.parseColor(textHex))
                background = deckRipple(make3DKeycap(topHex, bottomHex, shadowHex, strokeHex, 6f, 3.2f))
                centerKeycapText(this, depthDp = 3.2f, sidePadDp = 3f)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    dp(if (isLandscape) 35 else 35),
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
                createMacroBtn("MUTE ALL", COLOR_CRIMSON, COLOR_CRIMSON_DARK, COLOR_CRIMSON_SHADOW, COLOR_CRIMSON_STROKE, "#FFFFFF") {
                    stateProvider().streams.forEach { stream ->
                        MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                            viewModel.setAudioVolume(id, 0f)
                        }
                    }
                },
            )
            bottomMacros.addView(
                createMacroBtn("EQUALIZE", COLOR_CYAN, COLOR_CYAN_DARK, COLOR_CYAN_SHADOW, COLOR_CYAN_STROKE, "#041414") {
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
                createMacroBtn("SOLO ACTIVE", COLOR_VIOLET, COLOR_VIOLET_DARK, COLOR_VIOLET_SHADOW, COLOR_VIOLET_STROKE, "#FFFFFF") {
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
                createMacroBtn("REMOVE ALL", COLOR_ORANGE, COLOR_ORANGE_DARK, COLOR_ORANGE_SHADOW, COLOR_ORANGE_STROKE, "#FFFFFF") {
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
            // FLOATING WINDOW PLACEMENT
            // ==========================================
            val themedContext = ContextThemeWrapper(context, R.style.MaterialDialog)
            dialog = Dialog(themedContext).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setContentView(chassisContainer)
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    val displayWidth = metrics.widthPixels
                    val displayHeight = metrics.heightPixels
                    val cardWidth = when {
                        isLandscape -> (displayWidth * 0.70f).roundToInt()
                        isFloatingWindow -> (displayWidth * 0.94f).roundToInt()
                        else -> (displayWidth * 0.94f).roundToInt()
                    }
                    val cardHeight = if (isMultiLandscape) {
                        (displayHeight * 0.94f).roundToInt()
                    } else {
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    setLayout(cardWidth, cardHeight)
                    val p = attributes
                    when {
                        isLandscape || isFloatingWindow -> {
                            setGravity(Gravity.CENTER)
                            p.y = 0
                        }
                        initialState.layoutMode == MultiviewLayoutMode.FOCUS || initialState.streams.size <= 1 -> {
                            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                            p.y = dp(48)
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
            chassis.translationY = dp(if (fromTop) -14 else 14).toFloat()
            chassis.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .start()
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
        color = Color.parseColor("#AA07080E")
        style = Paint.Style.FILL
    }
    private val trackFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F5D4")
        style = Paint.Style.FILL
    }
    private val trackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80383E5E")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F5D4")
        strokeWidth = if (isCompact) 2f else 2.5f
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569")
        strokeWidth = if (isCompact) 1.2f else 1.5f
    }
    private val knobShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0000000")
        style = Paint.Style.FILL
    }
    private val capBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F5D4")
        style = Paint.Style.FILL
    }
    private val capBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D8FFF9")
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }
    private val knobGripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00897B")
        strokeWidth = 1.4f
    }
    private val capGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = if (isCompact) 2.2f else 3f
    }

    private val capWidth = dpToPx(if (isCompact) 15f else 18f)
    private val capHeight = dpToPx(if (isCompact) 18f else 24f)
    private val capRadius = dpToPx(if (isCompact) 3f else 4f)

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    fun setVolume(newVol: Float) {
        volume = newVol.coerceIn(0f, 1f)
        invalidate()
    }

    fun getVolume(): Float = volume

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultH = dpToPx(if (isCompact) 20f else 28f).toInt()
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
        val tickTopMajor = tickBottom - dpToPx(if (isCompact) 4.5f else 6f)
        val tickTopMinor = tickBottom - dpToPx(if (isCompact) 2.8f else 3.5f)
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

        val gripInset = dpToPx(if (isCompact) 3.5f else 5f)
        val gripSpacing = dpToPx(if (isCompact) 2.5f else 3.5f)
        canvas.drawLine(knobCenterX - gripSpacing, knobTop + gripInset, knobCenterX - gripSpacing, knobBottom - gripInset, knobGripPaint)
        canvas.drawLine(knobCenterX + gripSpacing, knobTop + gripInset, knobCenterX + gripSpacing, knobBottom - gripInset, knobGripPaint)

        val stripeInset = dpToPx(if (isCompact) 2f else 3f)
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
                parent?.requestDisallowInterceptTouchEvent(false)
                updateFromTouch(event.x, pad, trackW, snap = true)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(touchX: Float, pad: Float, trackW: Float, snap: Boolean) {
        var rawRatio = ((touchX - pad) / trackW).coerceIn(0f, 1f)
        val nearestStep = Math.round(rawRatio * 50f) / 50f
        if (snap || Math.abs(rawRatio - nearestStep) <= 0.008f) {
            rawRatio = nearestStep
        }
        val rounded = (Math.round(rawRatio * 100f) / 100f).coerceIn(0f, 1f)
        if (rounded != volume) {
            volume = rounded
            invalidate()
            onVolumeChanged(volume)
        }
    }
}
