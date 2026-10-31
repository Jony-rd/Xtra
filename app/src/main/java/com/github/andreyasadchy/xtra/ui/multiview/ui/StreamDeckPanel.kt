package com.github.andreyasadchy.xtra.ui.multiview.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.common.loadStreamProfileImage
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewSessionReducer
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewSessionState
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewViewModel
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewPlaybackSnapshot
import kotlin.math.roundToInt

/**
 * Dedicated controller for the 3D floating Stream Deck Studio hardware panel.
 *
 * Implemented as an in-view overlay within the Multiview player container.
 * This guarantees:
 * 1. Zero WindowManager crashes or display cutout token collisions on modern Android (API 34-36) / OEM ROMs.
 * 2. Automatic orientation handling and lifecycle safety (no leaked Dialog windows).
 * 3. 3D card floating aesthetics (45/50 width, 8dp hardware corners, 2dp purple accent, drop shadow).
 * 4. In-place live updates when volume, stream order, or playback qualities change.
 */
class StreamDeckPanel(
    private val host: ViewGroup,
    private val viewModel: MultiviewViewModel,
    private val onOpenPlayer: (String) -> Unit,
    private val onShowSleepTimer: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val context: Context = host.context
    private var overlayView: FrameLayout? = null
    private var streamListLayout: LinearLayout? = null
    private val cardPillUpdaters = mutableListOf<() -> Unit>()

    private var currentState: MultiviewSessionState? = null
    private var currentPlayback: Map<String, MultiviewPlaybackSnapshot> = emptyMap()

    val isShowing: Boolean
        get() = overlayView?.parent != null && overlayView?.isVisible == true

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun displayName(stream: Stream): String {
        return stream.channelName?.takeIf { it.isNotBlank() }
            ?: stream.channelLogin?.takeIf { it.isNotBlank() }
            ?: stream.id.orEmpty()
    }

    fun show(state: MultiviewSessionState, playback: Map<String, MultiviewPlaybackSnapshot>) {
        if (state.streams.isEmpty()) return
        currentState = state
        currentPlayback = playback

        if (overlayView == null) {
            overlayView = createOverlayView()
        }

        val overlay = overlayView ?: return
        if (overlay.parent == null) {
            host.addView(
                overlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        populateCards()

        overlay.alpha = 0f
        overlay.scaleX = 0.96f
        overlay.scaleY = 0.96f
        overlay.visibility = View.VISIBLE
        overlay.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180L)
            .setListener(null)
            .start()
    }

    fun update(state: MultiviewSessionState, playback: Map<String, MultiviewPlaybackSnapshot>) {
        if (!isShowing) return
        val prevStreams = currentState?.streams.orEmpty().mapNotNull(MultiviewSessionReducer::stableIdentity)
        val nextStreams = state.streams.mapNotNull(MultiviewSessionReducer::stableIdentity)

        currentState = state
        currentPlayback = playback

        if (nextStreams.isEmpty()) {
            dismiss()
            return
        }

        // If stream identities or order changed, reconstruct stream cards
        if (prevStreams != nextStreams) {
            populateCards()
        } else {
            // Live update existing card bindings (qualities, pills)
            cardPillUpdaters.forEach { it.invoke() }
        }
    }

    fun dismiss() {
        val overlay = overlayView ?: return
        if (overlay.parent == null) return

        overlay.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(140L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    host.removeView(overlay)
                    onDismiss.invoke()
                }
            })
            .start()
    }

    private fun createOverlayView(): FrameLayout {
        val overlay = FrameLayout(context).apply {
            // Subtle translucent scrim to enhance floating 3D depth
            setBackgroundColor(Color.parseColor("#33000000"))
            isClickable = true
            isFocusable = true
            setOnClickListener { dismiss() }
        }

        // 3D Floating Hardware Chassis (45/50 width = 90%, 8dp Elgato hardware corners, 2dp purple border)
        val chassis = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#0E0F18"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(2), Color.parseColor("#9146FF"))
            }
            background = bg
            elevation = dp(24).toFloat()
            clipToOutline = true
            isClickable = true // Prevent clicks inside card from dismissing scrim
        }

        val displayWidth = context.resources.displayMetrics.widthPixels
        val cardWidth = (displayWidth * 0.90f).toInt().coerceAtMost(dp(540))
        val chassisLp = FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(40) // Lower-center floating positioning
        }
        overlay.addView(chassis, chassisLp)

        // 1. Header: [:::] STREAM DECK [STUDIO]       [⏱️ SLEEP]   [✕]
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        val deckIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            setImageResource(R.drawable.ic_hw_stream_deck)
        }
        header.addView(deckIcon)

        val titleView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
            text = "STREAM DECK"
            setTextColor(Color.parseColor("#A970FF"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(titleView)

        val studioBadge = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            text = "STUDIO"
            setTextColor(Color.parseColor("#00F5D4"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }
        header.addView(studioBadge)

        val sleepBtn = TextView(context).apply {
            text = "⏱️ SLEEP"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1D2B"))
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.parseColor("#34364E"))
            }
            background = btnBg
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
            layoutParams = lp
            setOnClickListener { onShowSleepTimer.invoke() }
        }
        header.addView(sleepBtn)

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#A0A2B8"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener { dismiss() }
        }
        header.addView(closeBtn)
        chassis.addView(header)

        // 2. Master Global Quality Bar
        val autoLabel = context.getString(R.string.multiview_quality_auto)
        val masterCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#141522"))
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.parseColor("#00F5D4"))
            }
            background = bg
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
            layoutParams = lp
        }

        val masterLabel = TextView(context).apply {
            text = "⚡ MASTER:"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00F5D4"))
            setPadding(0, 0, dp(8), 0)
        }
        masterCard.addView(masterLabel)

        val masterScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val masterRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        StreamDeckQualityHelper.standardMasterQualities(autoLabel).forEach { qual ->
            val pill = TextView(context).apply {
                text = StreamDeckQualityHelper.shortLabel(qual, autoLabel)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                val pillBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E2030"))
                    cornerRadius = dp(4).toFloat()
                    setStroke(dp(1), Color.parseColor("#3A3E5E"))
                }
                background = pillBg
                setPadding(dp(7), dp(3), dp(7), dp(3))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(4)
                }
                layoutParams = lp
                setOnClickListener {
                    currentState?.streams?.forEach { stream ->
                        val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEach
                        val available = currentPlayback[id]?.availableQualities.orEmpty()
                        val target = StreamDeckQualityHelper.resolveTargetQuality(qual, autoLabel, available)
                        viewModel.setQualityOverride(id, target)
                    }
                    cardPillUpdaters.forEach { it.invoke() }
                }
            }
            masterRow.addView(pill)
        }
        masterScroll.addView(masterRow)
        masterCard.addView(masterScroll)
        chassis.addView(masterCard)

        // 3. Stream Rows Container (ScrollView safely bounded to max 50% screen height)
        val streamScroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = (resources.displayMetrics.heightPixels * 0.50f).toInt()
                val customHeightSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, customHeightSpec)
            }
        }.apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val listLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        streamListLayout = listLayout
        streamScroll.addView(listLayout)
        chassis.addView(streamScroll)

        // 4. Bottom Macro Actions: [MUTE ALL] [EQUALIZE] [SOLO ACTIVE]
        val bottomMacros = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        }

        bottomMacros.addView(createMacroBtn("MUTE ALL", "#FF5400") {
            currentState?.streams?.forEach { stream ->
                MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                    viewModel.setAudioVolume(id, 0f)
                }
            }
            populateCards()
        })

        bottomMacros.addView(createMacroBtn("EQUALIZE", "#00F5D4") {
            currentState?.streams?.forEach { stream ->
                MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                    viewModel.setAudioVolume(id, 0.5f)
                }
            }
            populateCards()
        })

        bottomMacros.addView(createMacroBtn("SOLO ACTIVE", "#9146FF") {
            val active = currentState?.activeIdentity
            currentState?.streams?.forEach { stream ->
                MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                    viewModel.setAudioVolume(id, if (id.equals(active, true)) 1f else 0f)
                }
            }
            populateCards()
        })

        chassis.addView(bottomMacros)
        return overlay
    }

    private fun createMacroBtn(label: String, colorHex: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1D2B"))
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.parseColor(colorHex))
            }
            background = bg
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            layoutParams = lp
            gravity = Gravity.CENTER
            setOnClickListener { onClick.invoke() }
        }
    }

    private fun populateCards() {
        val list = streamListLayout ?: return
        list.removeAllViews()
        cardPillUpdaters.clear()

        val state = currentState ?: return
        val autoLabel = context.getString(R.string.multiview_quality_auto)
        val streams = state.streams

        streams.forEachIndexed { index, stream ->
            val identity = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEachIndexed
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(10))
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#151623"))
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(1), Color.parseColor("#2A2B3D"))
                }
                background = bg
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                }
                layoutParams = lp
            }

            // Top row: [#1] [Avatar] [Streamer Name] [VU] [▲] [▼] [MUTE] [%] [⛶] [✕]
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // Slot badge
            val slotBadge = TextView(context).apply {
                text = "#${index + 1}"
                setTextColor(Color.parseColor("#00F5D4"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                val badgeBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A2B2F"))
                    cornerRadius = dp(4).toFloat()
                    setStroke(dp(1), Color.parseColor("#00F5D4"))
                }
                background = badgeBg
                setPadding(dp(5), dp(1), dp(5), dp(1))
            }
            topRow.addView(slotBadge)

            // Avatar with round-rect outline
            val avatar = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    marginStart = dp(6)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), dp(4).toFloat())
                    }
                }
                setImageResource(R.drawable.ic_person_placeholder)
            }
            try {
                loadStreamProfileImage(context, avatar, stream)
            } catch (_: Throwable) {}
            topRow.addView(avatar)

            // Streamer Name
            val nameView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6)
                }
                text = displayName(stream)
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            topRow.addView(nameView)

            // Hardware 5-segment LED volume level indicator
            val vuMeter = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            }
            val ledSegments = (0 until 5).map {
                View(context).apply {
                    val lp = LinearLayout.LayoutParams(dp(4), dp(11)).apply {
                        marginEnd = dp(2)
                    }
                    layoutParams = lp
                }
            }
            ledSegments.forEach { vuMeter.addView(it) }

            fun updateVuMeter(volume: Float) {
                val litCount = when {
                    volume <= 0.01f -> 0
                    volume < 0.25f -> 1
                    volume < 0.50f -> 2
                    volume < 0.75f -> 3
                    volume < 0.90f -> 4
                    else -> 5
                }
                ledSegments.forEachIndexed { idx, led ->
                    val isLit = idx < litCount
                    val colorHex = when (idx) {
                        0, 1, 2 -> if (isLit) "#00F5D4" else "#142523"
                        3 -> if (isLit) "#FFD600" else "#2B2513"
                        else -> if (isLit) "#FF1744" else "#2D161A"
                    }
                    val bg = GradientDrawable().apply {
                        setColor(Color.parseColor(colorHex))
                        cornerRadius = dp(2).toFloat()
                    }
                    led.background = bg
                }
            }

            var vol = viewModel.audioVolume(identity).coerceIn(0f, 1f)
            updateVuMeter(vol)
            topRow.addView(vuMeter)

            // Reorder ▲
            val upBtn = TextView(context).apply {
                text = "▲"
                setTextColor(if (index > 0) Color.parseColor("#A970FF") else Color.parseColor("#444654"))
                textSize = 14f
                setPadding(dp(6), dp(2), dp(4), dp(2))
                if (index > 0) {
                    setOnClickListener {
                        viewModel.reorder(identity, index - 1)
                        populateCards()
                    }
                }
            }
            topRow.addView(upBtn)

            // Reorder ▼
            val downBtn = TextView(context).apply {
                text = "▼"
                setTextColor(if (index < streams.lastIndex) Color.parseColor("#A970FF") else Color.parseColor("#444654"))
                textSize = 14f
                setPadding(dp(4), dp(2), dp(6), dp(2))
                if (index < streams.lastIndex) {
                    setOnClickListener {
                        viewModel.reorder(identity, index + 1)
                        populateCards()
                    }
                }
            }
            topRow.addView(downBtn)

            val percentView = TextView(context).apply {
                text = "${(vol * 100).roundToInt()}%"
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(2), 0, dp(2), 0)
            }

            val muteBtn = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                background = null
                setImageResource(if (vol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                setColorFilter(if (vol > 0f) Color.parseColor("#00F5D4") else Color.parseColor("#FF5400"))
            }
            topRow.addView(muteBtn)
            topRow.addView(percentView)

            // Open in Single Player [⛶] button
            val openPlayerBtn = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    marginStart = dp(6)
                    marginEnd = dp(2)
                }
                setImageResource(R.drawable.ic_pref_fullscreen)
                setColorFilter(Color.parseColor("#00F5D4"))
                setPadding(dp(2), dp(2), dp(2), dp(2))
                contentDescription = context.getString(R.string.multiview_open_player)
                setOnClickListener {
                    dismiss()
                    onOpenPlayer.invoke(identity)
                }
            }
            topRow.addView(openPlayerBtn)

            // Remove Stream [✕] button
            val removeStreamBtn = TextView(context).apply {
                text = "✕"
                setTextColor(Color.parseColor("#FF5400"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(2), dp(2), dp(2))
                setOnClickListener {
                    viewModel.remove(identity)
                    if (state.streams.size <= 1) {
                        dismiss()
                    } else {
                        populateCards()
                    }
                }
            }
            topRow.addView(removeStreamBtn)
            card.addView(topRow)

            // Middle: DjFaderView with Mathematically Aligned Ticks & Labels
            val fader = DjFaderView(context).apply {
                setVolume(vol)
                setOnVolumeChangedListener { newVol ->
                    viewModel.setAudioVolume(identity, newVol)
                    updateVuMeter(newVol)
                    percentView.text = "${(newVol * 100).roundToInt()}%"
                    muteBtn.setImageResource(if (newVol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                    muteBtn.setColorFilter(if (newVol > 0f) Color.parseColor("#00F5D4") else Color.parseColor("#FF5400"))
                }
            }

            muteBtn.setOnClickListener {
                val currentVol = fader.getVolume()
                val targetVol = if (currentVol > 0f) 0f else viewModel.defaultAudioVolume().takeIf { it > 0f } ?: 1f
                fader.setVolume(targetVol)
                viewModel.setAudioVolume(identity, targetVol)
                updateVuMeter(targetVol)
                percentView.text = "${(targetVol * 100).roundToInt()}%"
                muteBtn.setImageResource(if (targetVol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                muteBtn.setColorFilter(if (targetVol > 0f) Color.parseColor("#00F5D4") else Color.parseColor("#FF5400"))
            }

            card.addView(fader)

            // Bottom: Quality Selector Pills with Live Active Quality Detection
            val available = listOf(autoLabel) + currentPlayback[identity]?.availableQualities.orEmpty()
            val qualityScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
            }
            val qualityRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
            }

            val pillViews = mutableListOf<TextView>()
            fun refreshPills() {
                val overrideQuality = currentState?.qualityOverrides?.get(identity)
                val activePlayingQuality = currentPlayback[identity]?.qualityLabel
                pillViews.forEach { pill ->
                    val pillTag = pill.tag as? String ?: ""
                    val isSelected = StreamDeckQualityHelper.isSelected(
                        pillTag = pillTag,
                        overrideQuality = overrideQuality,
                        playingQuality = activePlayingQuality,
                        autoLabel = autoLabel,
                    )
                    val pillBg = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        if (isSelected) {
                            setColor(Color.parseColor("#9146FF"))
                            setStroke(dp(1), Color.parseColor("#D3B3FF"))
                        } else {
                            setColor(Color.parseColor("#1C1D2B"))
                            setStroke(dp(1), Color.parseColor("#34364E"))
                        }
                    }
                    pill.background = pillBg
                    pill.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#A0A2B8"))
                }
            }
            cardPillUpdaters.add(::refreshPills)

            available.forEach { qual ->
                val shortName = StreamDeckQualityHelper.shortLabel(qual, autoLabel)
                val pill = TextView(context).apply {
                    tag = qual
                    text = shortName
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(4)
                    }
                    layoutParams = lp
                    setOnClickListener {
                        val target = qual.takeUnless { it.equals(autoLabel, ignoreCase = true) }
                        viewModel.setQualityOverride(identity, target)
                        refreshPills()
                    }
                }
                pillViews.add(pill)
                qualityRow.addView(pill)
            }
            refreshPills()

            qualityScroll.addView(qualityRow)
            card.addView(qualityScroll)
            list.addView(card)
        }
    }
}
