package com.github.andreyasadchy.xtra.ui.multiview.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.common.loadStreamProfileImage
import com.github.andreyasadchy.xtra.ui.multiview.MultiviewSessionReducer
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import kotlin.math.min
import kotlin.math.roundToInt

internal object MultiviewReplaceDialog {
    sealed interface Plan {
        data class PickSlotForClicked(
            val clickedStream: Stream,
            val activeStreams: List<Stream>,
            val onPickSlot: (slotIndex: Int) -> Unit,
        ) : Plan

        data class HandleMiniPlayerOnly(
            val miniPlayerStream: Stream,
            val activeStreams: List<Stream>,
            val onOpenWithoutMiniPlayer: () -> Unit,
            val onReplaceSlotWithMiniPlayer: (slotIndex: Int) -> Unit,
        ) : Plan

        data class ThreeStreamsOverflow(
            val clickedStream: Stream,
            val miniPlayerStream: Stream,
            val activeStreams: List<Stream>,
            val onAddClickedToFreeSlotOnly: () -> Unit,
            val onReplaceSlotWithMiniAndAddClicked: (slotIndex: Int) -> Unit,
        ) : Plan

        data class FourStreamsWithMiniPlayer(
            val clickedStream: Stream,
            val miniPlayerStream: Stream,
            val activeStreams: List<Stream>,
            val onReplaceOnlyClicked: (slotIndex: Int) -> Unit,
            val onReplaceBoth: (clickedSlotIndex: Int, miniPlayerSlotIndex: Int) -> Unit,
        ) : Plan
    }

    private enum class TwoIncomingStep {
        CHOOSE_MODE,
        PICK_CLICKED_SINGLE,
        PICK_CLICKED_OF_BOTH,
        PICK_MINI_AFTER_CLICKED,
    }

    private const val COLOR_PURPLE_ACCENT = "#C084FC"
    private const val COLOR_CYAN_ACCENT = "#00E5FF"
    private const val COLOR_VOLT_ACCENT = "#C2FF00"
    private const val COLOR_EMERALD_ACCENT = "#34D399"
    private const val COLOR_SLATE_STROKE = "#8094A3B8"

    private var activeDialogRef: WeakReference<Dialog>? = null

    fun dismiss() {
        activeDialogRef?.get()?.let { dialog ->
            if (dialog.isShowing) {
                runCatching { dialog.dismiss() }
            }
        }
        activeDialogRef = null
    }

    fun show(context: Context, plan: Plan) {
        dismiss()

        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).roundToInt()
        fun dp(value: Float): Int = (value * density).roundToInt()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }
        activeDialogRef = WeakReference(dialog)

        fun displayName(stream: Stream): String =
            stream.channelName?.takeIf { it.isNotBlank() }
                ?: stream.channelLogin?.takeIf { it.isNotBlank() }
                ?: stream.id.orEmpty()

        fun seedKey(stream: Stream): String =
            MultiviewSessionReducer.stableIdentity(stream)
                ?: displayName(stream)

        fun streamMetaLine(stream: Stream, fallback: String): String {
            val game = stream.gameName?.trim()?.takeIf { it.isNotEmpty() }
            val viewers = stream.viewerCount?.takeIf { it >= 0 }?.let {
                "${TwitchApiHelper.formatCount(it, true)} viewers"
            }
            val title = stream.title?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                game != null && viewers != null -> "$game • $viewers"
                game != null -> game
                viewers != null -> viewers
                title != null -> title
                else -> fallback
            }
        }

        fun blendRgb(@ColorInt color: Int, @ColorInt into: Int, ratio: Float): Int =
            ColorUtils.blendARGB(color, into, ratio)

        fun rippleWith(
            content: Drawable,
            @ColorInt rippleColor: Int = Color.parseColor("#55C084FC"),
        ): Drawable = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            content,
            null,
        )

        fun pillDrawable(
            @ColorInt fillColor: Int,
            @ColorInt strokeColor: Int,
            radiusDp: Float = 8f,
        ): Drawable = GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1f).coerceAtLeast(1), strokeColor)
        }

        fun makeShellBackground(@ColorInt accentColor: Int): Drawable {
            val radius = dp(18).toFloat()
            val base = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#F5111424"),
                    Color.parseColor("#F80A0C16"),
                    Color.parseColor("#FA06070E"),
                ),
            ).apply {
                cornerRadius = radius
                setStroke(dp(1.25f).coerceAtLeast(1), ColorUtils.setAlphaComponent(accentColor, 0xAA))
            }
            val topSheen = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    ColorUtils.setAlphaComponent(accentColor, 0x26),
                    Color.parseColor("#12FFFFFF"),
                    Color.TRANSPARENT,
                ),
            ).apply {
                cornerRadius = (radius - dp(1)).coerceAtLeast(0f)
            }
            return LayerDrawable(arrayOf(base, topSheen)).apply {
                setLayerInset(1, dp(1), dp(1), dp(1), dp(1))
            }
        }

        fun makeAccentCardDrawable(
            palette: StreamerPalette,
            cornerRadiusDp: Float = 13f,
            brightHighlight: Boolean = false,
        ): Drawable {
            val radius = dp(cornerRadiusDp).toFloat()
            val topTint = ColorUtils.setAlphaComponent(
                blendRgb(palette.primary, 0xFF0C0E1C.toInt(), if (brightHighlight) 0.48f else 0.60f),
                0xCC,
            )
            val bottomTint = ColorUtils.setAlphaComponent(
                blendRgb(palette.secondary, 0xFF070812.toInt(), if (brightHighlight) 0.54f else 0.68f),
                0xD9,
            )
            val base = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(topTint, bottomTint),
            ).apply {
                cornerRadius = radius
                setStroke(
                    dp(if (brightHighlight) 1.35f else 1.1f).coerceAtLeast(1),
                    ColorUtils.setAlphaComponent(
                        palette.highlight,
                        if (brightHighlight) 0xD9 else 0x8C,
                    ),
                )
            }
            val topSheen = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    ColorUtils.setAlphaComponent(palette.highlight, if (brightHighlight) 0x38 else 0x24),
                    Color.TRANSPARENT,
                ),
            ).apply {
                cornerRadius = (radius - dp(1)).coerceAtLeast(0f)
            }
            val barAlpha = if (brightHighlight) 0xF5 else 0xC8
            val accentBar = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    ColorUtils.setAlphaComponent(palette.highlight, barAlpha),
                    ColorUtils.setAlphaComponent(palette.primary, barAlpha),
                    ColorUtils.setAlphaComponent(palette.secondary, barAlpha),
                ),
            ).apply {
                val r = dp(3).toFloat()
                cornerRadii = floatArrayOf(r, 0f, 0f, r, r, 0f, 0f, r)
            }
            val barInsetV = dp(7)
            return LayerDrawable(arrayOf(base, topSheen, accentBar)).apply {
                setLayerInset(1, dp(1), dp(1), dp(1), dp(1))
                setLayerGravity(2, Gravity.START or Gravity.FILL_VERTICAL)
                setLayerWidth(2, dp(3).coerceAtLeast(2))
                setLayerInset(2, dp(1.5f), barInsetV, 0, barInsetV)
            }
        }

        fun makeActionCardDrawable(
            @ColorInt topColor: Int,
            @ColorInt bottomColor: Int,
            @ColorInt strokeColor: Int,
            cornerRadiusDp: Float = 13f,
        ): Drawable {
            val radius = dp(cornerRadiusDp).toFloat()
            val base = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(topColor, bottomColor),
            ).apply {
                cornerRadius = radius
                setStroke(dp(1.2f).coerceAtLeast(1), strokeColor)
            }
            val topSheen = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#2AFFFFFF"), Color.TRANSPARENT),
            ).apply {
                cornerRadius = (radius - dp(1)).coerceAtLeast(0f)
            }
            return rippleWith(
                LayerDrawable(arrayOf(base, topSheen)).apply {
                    setLayerInset(1, dp(1), dp(1), dp(1), dp(1))
                },
                ColorUtils.setAlphaComponent(strokeColor, 0x55),
            )
        }

        fun bindAvatarView(
            stream: Stream,
            sizeDp: Int,
            ringCornerDp: Float,
            palette: StreamerPalette,
        ): Pair<FrameLayout, (Int) -> Unit> {
            val avPx = dp(sizeDp)
            val pad = dp(1.5f).coerceAtLeast(1)
            val frame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(avPx + pad * 2, avPx + pad * 2)
                setPadding(pad, pad, pad, pad)
            }
            fun applyRing(@ColorInt strokeColor: Int) {
                frame.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#88070810"))
                    cornerRadius = dp(ringCornerDp).toFloat()
                    setStroke(dp(1.3f).coerceAtLeast(1), ColorUtils.setAlphaComponent(strokeColor, 0xD8))
                }
            }
            applyRing(palette.highlight)

            val imageView = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(
                            0,
                            0,
                            view.width,
                            view.height,
                            dp((ringCornerDp - 1.5f).coerceAtLeast(2f)).toFloat(),
                        )
                    }
                }
                setImageResource(R.drawable.ic_person_placeholder)
            }
            runCatching { loadStreamProfileImage(context, imageView, stream) }
            frame.addView(imageView)
            return frame to ::applyRing
        }

        fun buildIncomingSpotlight(
            stream: Stream,
            tagText: String,
            @ColorInt tagColor: Int,
            fallbackMeta: String,
        ): LinearLayout {
            val seed = seedKey(stream)
            val url = stream.channelImage
            var palette = StreamerPaletteCache.getCached(url) ?: StreamerPaletteCache.fallback(seed)

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(12)
                }
            }

            val (avatarFrame, updateRing) = bindAvatarView(stream, sizeDp = 36, ringCornerDp = 10f, palette = palette)
            row.addView(avatarFrame)

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                    marginEnd = dp(8)
                }
            }
            textCol.addView(TextView(context).apply {
                text = displayName(stream)
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            textCol.addView(TextView(context).apply {
                text = streamMetaLine(stream, fallbackMeta)
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 11.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(textCol)

            val badge = TextView(context).apply {
                text = tagText
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                maxLines = 1
                setTextColor(tagColor)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = pillDrawable(
                    fillColor = ColorUtils.setAlphaComponent(tagColor, 0x24),
                    strokeColor = ColorUtils.setAlphaComponent(tagColor, 0x99),
                    radiusDp = 7f,
                )
            }
            row.addView(badge)

            fun applyBackground() {
                row.background = makeAccentCardDrawable(palette, cornerRadiusDp = 13f, brightHighlight = true)
                updateRing(palette.highlight)
            }
            applyBackground()

            if (StreamerPaletteCache.getCached(url) == null && !url.isNullOrBlank()) {
                scope.launch {
                    val extracted = StreamerPaletteCache.loadOrExtract(context, url, seed)
                    if (!row.isAttachedToWindow) return@launch
                    palette = extracted
                    applyBackground()
                }
            }
            return row
        }

        fun buildCompactIncomingTile(
            stream: Stream,
            tagText: String,
            @ColorInt tagColor: Int,
            active: Boolean,
            marginEndDp: Int,
        ): LinearLayout {
            val seed = seedKey(stream)
            val url = stream.channelImage
            var palette = StreamerPaletteCache.getCached(url) ?: StreamerPaletteCache.fallback(seed)

            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), dp(8), dp(9), dp(8))
                alpha = if (active) 1f else 0.78f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(marginEndDp)
                }
            }

            val topLine = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topLine.addView(TextView(context).apply {
                text = tagText
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                maxLines = 1
                setTextColor(tagColor)
                setPadding(dp(6), dp(2.5f), dp(6), dp(2.5f))
                background = pillDrawable(
                    fillColor = ColorUtils.setAlphaComponent(tagColor, 0x26),
                    strokeColor = ColorUtils.setAlphaComponent(tagColor, 0x99),
                    radiusDp = 6f,
                )
            })
            tile.addView(topLine)

            val infoRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(7)
                }
            }
            val (avatarFrame, updateRing) = bindAvatarView(stream, sizeDp = 28, ringCornerDp = 8f, palette = palette)
            infoRow.addView(avatarFrame)

            infoRow.addView(TextView(context).apply {
                text = displayName(stream)
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(7)
                }
            })
            tile.addView(infoRow)

            fun applyBackground() {
                tile.background = makeAccentCardDrawable(palette, cornerRadiusDp = 12f, brightHighlight = active)
                updateRing(palette.highlight)
            }
            applyBackground()

            if (StreamerPaletteCache.getCached(url) == null && !url.isNullOrBlank()) {
                scope.launch {
                    val extracted = StreamerPaletteCache.loadOrExtract(context, url, seed)
                    if (!tile.isAttachedToWindow) return@launch
                    palette = extracted
                    applyBackground()
                }
            }
            return tile
        }

        fun buildActionCard(
            title: String,
            subtitle: String,
            badgeText: String,
            @ColorInt topColor: Int,
            @ColorInt bottomColor: Int,
            @ColorInt strokeColor: Int,
            @ColorInt badgeColor: Int,
            onClick: () -> Unit,
        ): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(60)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true
                isFocusable = true
                background = makeActionCardDrawable(topColor, bottomColor, strokeColor)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(8)
                }
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                }

                val textCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(8)
                    }
                }
                textCol.addView(TextView(context).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                textCol.addView(TextView(context).apply {
                    text = subtitle
                    setTextColor(Color.parseColor("#CBD5E1"))
                    textSize = 11.5f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(textCol)

                addView(TextView(context).apply {
                    text = badgeText
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    maxLines = 1
                    setTextColor(badgeColor)
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                    background = pillDrawable(
                        fillColor = ColorUtils.setAlphaComponent(badgeColor, 0x24),
                        strokeColor = ColorUtils.setAlphaComponent(badgeColor, 0xB3),
                        radiusDp = 8f,
                    )
                })
            }
        }

        fun buildSlotReplaceCard(
            slotIndex: Int,
            stream: Stream,
            actionPillText: String,
            @ColorInt actionPillColor: Int,
            onClick: () -> Unit,
        ): LinearLayout {
            val seed = seedKey(stream)
            val url = stream.channelImage
            var palette = StreamerPaletteCache.getCached(url) ?: StreamerPaletteCache.fallback(seed)

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(58)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(7)
                }
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                }
            }

            val slotPill = TextView(context).apply {
                text = "#${slotIndex + 1}"
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                minWidth = dp(28)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = dp(2)
                    marginEnd = dp(8)
                }
            }
            card.addView(slotPill)

            val (avatarFrame, updateRing) = bindAvatarView(stream, sizeDp = 34, ringCornerDp = 9f, palette = palette)
            card.addView(avatarFrame)

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(9)
                    marginEnd = dp(8)
                }
            }
            textCol.addView(TextView(context).apply {
                text = displayName(stream)
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
            })
            textCol.addView(TextView(context).apply {
                text = streamMetaLine(stream, "Slot #${slotIndex + 1}")
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 11.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            card.addView(textCol)

            card.addView(TextView(context).apply {
                text = actionPillText
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                maxLines = 1
                setTextColor(actionPillColor)
                setPadding(dp(8), dp(4.5f), dp(8), dp(4.5f))
                background = pillDrawable(
                    fillColor = ColorUtils.setAlphaComponent(actionPillColor, 0x20),
                    strokeColor = ColorUtils.setAlphaComponent(actionPillColor, 0x99),
                    radiusDp = 7f,
                )
            })

            fun applyPalette() {
                card.background = rippleWith(
                    makeAccentCardDrawable(palette, cornerRadiusDp = 12f, brightHighlight = false),
                    ColorUtils.setAlphaComponent(palette.highlight, 0x55),
                )
                slotPill.setTextColor(Color.WHITE)
                slotPill.background = pillDrawable(
                    fillColor = ColorUtils.setAlphaComponent(palette.primary, 0x45),
                    strokeColor = ColorUtils.setAlphaComponent(palette.highlight, 0xAA),
                    radiusDp = 6f,
                )
                updateRing(palette.highlight)
            }
            applyPalette()

            if (StreamerPaletteCache.getCached(url) == null && !url.isNullOrBlank()) {
                scope.launch {
                    val extracted = StreamerPaletteCache.loadOrExtract(context, url, seed)
                    if (!card.isAttachedToWindow) return@launch
                    palette = extracted
                    applyPalette()
                }
            }
            return card
        }

        fun buildSectionLabel(text: String): TextView = TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(7)
            }
        }

        // Outer translucent shell
        val shellCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = makeShellBackground(Color.parseColor(COLOR_PURPLE_ACCENT))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        // Top controls row: Back pill (optional) + Mode pill + Close X button
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(10)
            }
        }
        val backButton = TextView(context).apply {
            text = "← Back"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F1F5F9"))
            gravity = Gravity.CENTER
            includeFontPadding = false
            visibility = View.GONE
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = rippleWith(
                pillDrawable(
                    fillColor = Color.parseColor("#CC232942"),
                    strokeColor = Color.parseColor(COLOR_SLATE_STROKE),
                    radiusDp = 8f,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(8)
            }
        }
        val modePill = TextView(context).apply {
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            includeFontPadding = false
            setPadding(dp(9), dp(4.5f), dp(9), dp(4.5f))
        }
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val closeButton = TextView(context).apply {
            text = "✕"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E2E8F0"))
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            background = rippleWith(
                pillDrawable(
                    fillColor = Color.parseColor("#AA1E243B"),
                    strokeColor = Color.parseColor("#6694A3B8"),
                    radiusDp = 16f,
                ),
            )
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                dialog.dismiss()
            }
        }
        topRow.addView(backButton)
        topRow.addView(modePill)
        topRow.addView(spacer)
        topRow.addView(closeButton)
        shellCard.addView(topRow)

        val titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val subtitleView = TextView(context).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
            setLineSpacing(0f, 1.15f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(3)
                bottomMargin = dp(12)
            }
        }
        shellCard.addView(titleView)
        shellCard.addView(subtitleView)

        val bodyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        shellCard.addView(bodyContainer)

        val cancelButton = TextView(context).apply {
            text = context.getString(android.R.string.cancel)
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minimumHeight = dp(40)
            background = rippleWith(
                pillDrawable(
                    fillColor = Color.parseColor("#AA1B2036"),
                    strokeColor = Color.parseColor("#6694A3B8"),
                    radiusDp = 10f,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6)
            }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                dialog.dismiss()
            }
        }
        shellCard.addView(cancelButton)

        fun updateModePill(text: String, @ColorInt color: Int) {
            modePill.text = text
            modePill.setTextColor(color)
            modePill.background = pillDrawable(
                fillColor = ColorUtils.setAlphaComponent(color, 0x22),
                strokeColor = ColorUtils.setAlphaComponent(color, 0x99),
                radiusDp = 7f,
            )
            shellCard.background = makeShellBackground(color)
        }

        fun animateBodyTransition(forward: Boolean) {
            bodyContainer.animate().cancel()
            bodyContainer.alpha = 0f
            bodyContainer.translationX = dp(if (forward) 14 else -14).toFloat()
            bodyContainer.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(170L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        when (plan) {
            is Plan.PickSlotForClicked -> {
                val yName = displayName(plan.clickedStream)
                updateModePill("4/4 FULL", Color.parseColor(COLOR_PURPLE_ACCENT))
                titleView.text = "Replace a slot with $yName"
                subtitleView.text = "Multiview is full. Tap whichever stream you want to swap out."
                bodyContainer.addView(
                    buildIncomingSpotlight(
                        stream = plan.clickedStream,
                        tagText = "+ ADDING",
                        tagColor = Color.parseColor(COLOR_PURPLE_ACCENT),
                        fallbackMeta = "New stream",
                    ),
                )
                bodyContainer.addView(buildSectionLabel("CHOOSE SLOT TO REPLACE"))
                plan.activeStreams.forEachIndexed { idx, active ->
                    bodyContainer.addView(
                        buildSlotReplaceCard(
                            slotIndex = idx,
                            stream = active,
                            actionPillText = "SWAP",
                            actionPillColor = Color.parseColor(COLOR_PURPLE_ACCENT),
                        ) {
                            dialog.dismiss()
                            plan.onPickSlot(idx)
                        },
                    )
                }
            }

            is Plan.HandleMiniPlayerOnly -> {
                val xName = displayName(plan.miniPlayerStream)
                updateModePill("MINI-PLAYER", Color.parseColor(COLOR_CYAN_ACCENT))
                titleView.text = "Include $xName in Multiview?"
                subtitleView.text = "Your clicked stream is already open, while $xName is playing in mini-player."
                bodyContainer.addView(
                    buildIncomingSpotlight(
                        stream = plan.miniPlayerStream,
                        tagText = "MINI-PLAYER",
                        tagColor = Color.parseColor(COLOR_CYAN_ACCENT),
                        fallbackMeta = "Playing in mini-player",
                    ),
                )
                bodyContainer.addView(
                    buildActionCard(
                        title = "Keep current 4 streams",
                        subtitle = "Close mini-player ($xName) & open Multiview as-is",
                        badgeText = "KEEP 4 →",
                        topColor = Color.parseColor("#CC14332C"),
                        bottomColor = Color.parseColor("#E00A1D1B"),
                        strokeColor = Color.parseColor(COLOR_EMERALD_ACCENT),
                        badgeColor = Color.parseColor(COLOR_EMERALD_ACCENT),
                    ) {
                        dialog.dismiss()
                        plan.onOpenWithoutMiniPlayer()
                    },
                )
                bodyContainer.addView(buildSectionLabel("OR REPLACE A SLOT WITH ${xName.uppercase()}"))
                plan.activeStreams.forEachIndexed { idx, active ->
                    bodyContainer.addView(
                        buildSlotReplaceCard(
                            slotIndex = idx,
                            stream = active,
                            actionPillText = "PUT $xName".take(14),
                            actionPillColor = Color.parseColor(COLOR_CYAN_ACCENT),
                        ) {
                            dialog.dismiss()
                            plan.onReplaceSlotWithMiniPlayer(idx)
                        },
                    )
                }
            }

            is Plan.ThreeStreamsOverflow -> {
                val yName = displayName(plan.clickedStream)
                val xName = displayName(plan.miniPlayerStream)
                updateModePill("1 FREE SLOT", Color.parseColor(COLOR_VOLT_ACCENT))
                titleView.text = "2 streams, 1 empty slot"
                subtitleView.text = "$yName takes slot #4. Close $xName or swap one existing stream for it."

                val dualRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = dp(12)
                    }
                }
                dualRow.addView(
                    buildCompactIncomingTile(
                        stream = plan.clickedStream,
                        tagText = "SLOT #4",
                        tagColor = Color.parseColor(COLOR_VOLT_ACCENT),
                        active = true,
                        marginEndDp = 7,
                    ),
                )
                dualRow.addView(
                    buildCompactIncomingTile(
                        stream = plan.miniPlayerStream,
                        tagText = "MINI-PLAYER",
                        tagColor = Color.parseColor(COLOR_CYAN_ACCENT),
                        active = true,
                        marginEndDp = 0,
                    ),
                )
                bodyContainer.addView(dualRow)

                bodyContainer.addView(
                    buildActionCard(
                        title = "Add $yName to #4 & close $xName",
                        subtitle = "No swaps needed — closes mini-player",
                        badgeText = "QUICK ADD",
                        topColor = Color.parseColor("#CC14332C"),
                        bottomColor = Color.parseColor("#E00A1D1B"),
                        strokeColor = Color.parseColor(COLOR_EMERALD_ACCENT),
                        badgeColor = Color.parseColor(COLOR_EMERALD_ACCENT),
                    ) {
                        dialog.dismiss()
                        plan.onAddClickedToFreeSlotOnly()
                    },
                )
                bodyContainer.addView(buildSectionLabel("OR KEEP ${xName.uppercase()} BY REPLACING A SLOT"))
                plan.activeStreams.forEachIndexed { idx, active ->
                    bodyContainer.addView(
                        buildSlotReplaceCard(
                            slotIndex = idx,
                            stream = active,
                            actionPillText = "PUT $xName".take(14),
                            actionPillColor = Color.parseColor(COLOR_CYAN_ACCENT),
                        ) {
                            dialog.dismiss()
                            plan.onReplaceSlotWithMiniAndAddClicked(idx)
                        },
                    )
                }
            }

            is Plan.FourStreamsWithMiniPlayer -> {
                val yName = displayName(plan.clickedStream)
                val xName = displayName(plan.miniPlayerStream)
                var step = TwoIncomingStep.CHOOSE_MODE
                var pickedSlotForY: Int? = null

                fun renderStep(animate: Boolean, forward: Boolean) {
                    bodyContainer.removeAllViews()
                    when (step) {
                        TwoIncomingStep.CHOOSE_MODE -> {
                            backButton.visibility = View.GONE
                            updateModePill("4/4 FULL • MINI ACTIVE", Color.parseColor(COLOR_PURPLE_ACCENT))
                            titleView.text = "Include $xName with $yName?"
                            subtitleView.text = "You're adding $yName while $xName is in mini-player."

                            val dualRow = LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ).apply {
                                    bottomMargin = dp(12)
                                }
                            }
                            dualRow.addView(
                                buildCompactIncomingTile(
                                    stream = plan.clickedStream,
                                    tagText = "+ NEW",
                                    tagColor = Color.parseColor(COLOR_PURPLE_ACCENT),
                                    active = true,
                                    marginEndDp = 7,
                                ),
                            )
                            dualRow.addView(
                                buildCompactIncomingTile(
                                    stream = plan.miniPlayerStream,
                                    tagText = "MINI-PLAYER",
                                    tagColor = Color.parseColor(COLOR_CYAN_ACCENT),
                                    active = true,
                                    marginEndDp = 0,
                                ),
                            )
                            bodyContainer.addView(dualRow)

                            bodyContainer.addView(
                                buildActionCard(
                                    title = "Add both $yName & $xName",
                                    subtitle = "Pick 1 slot for $yName, then 1 slot for $xName",
                                    badgeText = "2 STEPS →",
                                    topColor = Color.parseColor("#CC281449"),
                                    bottomColor = Color.parseColor("#E6101B36"),
                                    strokeColor = Color.parseColor(COLOR_PURPLE_ACCENT),
                                    badgeColor = Color.parseColor(COLOR_PURPLE_ACCENT),
                                ) {
                                    step = TwoIncomingStep.PICK_CLICKED_OF_BOTH
                                    renderStep(animate = true, forward = true)
                                },
                            )
                            bodyContainer.addView(
                                buildActionCard(
                                    title = "Add $yName only • Close $xName",
                                    subtitle = "Closes mini-player & picks 1 slot for $yName",
                                    badgeText = "1 STEP →",
                                    topColor = Color.parseColor("#D6232942"),
                                    bottomColor = Color.parseColor("#E0161B2E"),
                                    strokeColor = Color.parseColor("#94A3B8"),
                                    badgeColor = Color.parseColor("#F1F5F9"),
                                ) {
                                    step = TwoIncomingStep.PICK_CLICKED_SINGLE
                                    renderStep(animate = true, forward = true)
                                },
                            )
                        }

                        TwoIncomingStep.PICK_CLICKED_SINGLE -> {
                            backButton.visibility = View.VISIBLE
                            backButton.setOnClickListener {
                                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                step = TwoIncomingStep.CHOOSE_MODE
                                renderStep(animate = true, forward = false)
                            }
                            updateModePill("1 SLOT • Y ONLY", Color.parseColor(COLOR_PURPLE_ACCENT))
                            titleView.text = "Replace which slot with $yName?"
                            subtitleView.text = "Mini-player ($xName) will close cleanly."
                            bodyContainer.addView(
                                buildIncomingSpotlight(
                                    stream = plan.clickedStream,
                                    tagText = "+ ADDING",
                                    tagColor = Color.parseColor(COLOR_PURPLE_ACCENT),
                                    fallbackMeta = "New stream",
                                ),
                            )
                            bodyContainer.addView(buildSectionLabel("CHOOSE SLOT FOR ${yName.uppercase()}"))
                            plan.activeStreams.forEachIndexed { idx, active ->
                                bodyContainer.addView(
                                    buildSlotReplaceCard(
                                        slotIndex = idx,
                                        stream = active,
                                        actionPillText = "SWAP",
                                        actionPillColor = Color.parseColor(COLOR_PURPLE_ACCENT),
                                    ) {
                                        dialog.dismiss()
                                        plan.onReplaceOnlyClicked(idx)
                                    },
                                )
                            }
                        }

                        TwoIncomingStep.PICK_CLICKED_OF_BOTH -> {
                            backButton.visibility = View.VISIBLE
                            backButton.setOnClickListener {
                                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                step = TwoIncomingStep.CHOOSE_MODE
                                renderStep(animate = true, forward = false)
                            }
                            updateModePill("STEP 1 OF 2 • PICK Y", Color.parseColor(COLOR_PURPLE_ACCENT))
                            titleView.text = "Step 1: Pick slot for $yName"
                            subtitleView.text = "Next you'll pick which remaining slot gets $xName."
                            bodyContainer.addView(
                                buildIncomingSpotlight(
                                    stream = plan.clickedStream,
                                    tagText = "STEP 1 • Y",
                                    tagColor = Color.parseColor(COLOR_PURPLE_ACCENT),
                                    fallbackMeta = "Clicked stream",
                                ),
                            )
                            bodyContainer.addView(buildSectionLabel("PICK SLOT FOR ${yName.uppercase()}"))
                            plan.activeStreams.forEachIndexed { idx, active ->
                                bodyContainer.addView(
                                    buildSlotReplaceCard(
                                        slotIndex = idx,
                                        stream = active,
                                        actionPillText = "PICK #${idx + 1}",
                                        actionPillColor = Color.parseColor(COLOR_PURPLE_ACCENT),
                                    ) {
                                        pickedSlotForY = idx
                                        step = TwoIncomingStep.PICK_MINI_AFTER_CLICKED
                                        renderStep(animate = true, forward = true)
                                    },
                                )
                            }
                        }

                        TwoIncomingStep.PICK_MINI_AFTER_CLICKED -> {
                            val chosenYSlot = pickedSlotForY ?: 0
                            backButton.visibility = View.VISIBLE
                            backButton.setOnClickListener {
                                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                step = TwoIncomingStep.PICK_CLICKED_OF_BOTH
                                renderStep(animate = true, forward = false)
                            }
                            updateModePill("STEP 2 OF 2 • PICK X", Color.parseColor(COLOR_CYAN_ACCENT))
                            titleView.text = "Step 2: Pick slot for $xName"
                            subtitleView.text = "Choose any remaining slot below for mini-player $xName."

                            bodyContainer.addView(
                                TextView(context).apply {
                                    text = "✓ Slot #${chosenYSlot + 1} → $yName (locked in)"
                                    setTextColor(Color.parseColor(COLOR_EMERALD_ACCENT))
                                    textSize = 11.5f
                                    typeface = Typeface.DEFAULT_BOLD
                                    setPadding(dp(10), dp(7), dp(10), dp(7))
                                    background = pillDrawable(
                                        fillColor = Color.parseColor("#24102C24"),
                                        strokeColor = ColorUtils.setAlphaComponent(
                                            Color.parseColor(COLOR_EMERALD_ACCENT),
                                            0x99,
                                        ),
                                        radiusDp = 9f,
                                    )
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ).apply {
                                        bottomMargin = dp(10)
                                    }
                                },
                            )

                            bodyContainer.addView(
                                buildIncomingSpotlight(
                                    stream = plan.miniPlayerStream,
                                    tagText = "STEP 2 • X",
                                    tagColor = Color.parseColor(COLOR_CYAN_ACCENT),
                                    fallbackMeta = "Mini-player stream",
                                ),
                            )
                            bodyContainer.addView(buildSectionLabel("PICK REMAINING SLOT FOR ${xName.uppercase()}"))
                            plan.activeStreams.forEachIndexed { idx, active ->
                                if (idx != chosenYSlot) {
                                    bodyContainer.addView(
                                        buildSlotReplaceCard(
                                            slotIndex = idx,
                                            stream = active,
                                            actionPillText = "PICK #${idx + 1}",
                                            actionPillColor = Color.parseColor(COLOR_CYAN_ACCENT),
                                        ) {
                                            dialog.dismiss()
                                            plan.onReplaceBoth(chosenYSlot, idx)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (animate) animateBodyTransition(forward)
                }

                renderStep(animate = false, forward = true)
            }
        }

        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(shellCard)
        }
        val rootContainer = FrameLayout(context).apply {
            addView(
                scrollView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        dialog.setContentView(rootContainer)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.72f)
            val screenWidth = context.resources.displayMetrics.widthPixels
            val dialogWidth = min(dp(420), (screenWidth * 0.92f).roundToInt())
            setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener {
            scope.cancel()
            if (activeDialogRef?.get() === dialog) {
                activeDialogRef = null
            }
        }

        shellCard.alpha = 0f
        shellCard.scaleX = 0.94f
        shellCard.scaleY = 0.94f
        shellCard.translationY = dp(10).toFloat()
        dialog.show()
        shellCard.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(190L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
