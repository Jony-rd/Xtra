package com.github.andreyasadchy.xtra.ui.multiview.ui

enum class MultiviewLayoutMode {
    AUTO,
    GRID,
    FOCUS,
}

data class TilePlacement(
    val identity: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class MultiviewLayoutPlan(
    val placements: List<TilePlacement>,
    val portraitHeightWidthRatio: Float,
)

object MultiviewLayoutManager {

    fun plan(
        identities: List<String>,
        activeIdentity: String?,
        mode: MultiviewLayoutMode,
        focusedIdentity: String?,
        landscape: Boolean,
        chatVisible: Boolean = false,
    ): MultiviewLayoutPlan {
        val ids = identities.distinct()
        if (ids.isEmpty()) {
            return MultiviewLayoutPlan(
                placements = emptyList(),
                portraitHeightWidthRatio = 1f,
            )
        }

        val primary = (focusedIdentity ?: activeIdentity ?: ids.first())
            .takeIf(ids::contains)
            ?: ids.first()

        return if (landscape) {
            landscape(
                ids = ids,
                primary = primary,
                chatVisible = chatVisible,
            )
        } else {
            when (mode) {
                MultiviewLayoutMode.FOCUS -> focusPortrait(ids, primary)
                MultiviewLayoutMode.AUTO -> autoPortrait(ids, primary)
                MultiviewLayoutMode.GRID -> gridPortrait(ids)
            }
        }
    }

    /**
     * Coordinates are normalized to the videoGrid (0f..1f).
     *
     * Chat ON:
     *   the videoGrid itself occupies 80% of the full landscape screen,
     *   so within videoGrid the main stream is 75% and the other-stream
     *   stack is 25%. That produces the requested 60% / 20% split overall.
     *
     * Chat OFF:
     *   the videoGrid occupies the whole screen. Main is 80%, the
     *   other-stream stack is 20%.
     *
     * Special case: exactly two streams with chat OFF. The second stream
     * occupies only the top half of the 20% right column. The Fragment
     * places functional chat underneath it in the separate right column.
     */
    private fun landscape(
        ids: List<String>,
        primary: String,
        chatVisible: Boolean,
    ): MultiviewLayoutPlan {
        val rest = ids.filterNot { it == primary }
        if (rest.isEmpty()) {
            return MultiviewLayoutPlan(
                placements = listOf(
                    TilePlacement(primary, 0f, 0f, 1f, 1f),
                ),
                portraitHeightWidthRatio = 1f,
            )
        }

        val mainRight = if (chatVisible) 0.75f else 0.80f
        val placements = mutableListOf(
            TilePlacement(primary, 0f, 0f, mainRight, 1f),
        )

        if (!chatVisible && rest.size == 1) {
            placements += TilePlacement(
                rest.first(),
                mainRight,
                0f,
                1f,
                0.5f,
            )
        } else {
            val count = rest.size
            rest.forEachIndexed { index, identity ->
                val top = index.toFloat() / count
                val bottom = (index + 1).toFloat() / count
                placements += TilePlacement(
                    identity,
                    mainRight,
                    top,
                    1f,
                    bottom,
                )
            }
        }

        return MultiviewLayoutPlan(
            placements = placements,
            portraitHeightWidthRatio = 1f,
        )
    }

    private fun autoPortrait(
        ids: List<String>,
        primary: String,
    ): MultiviewLayoutPlan {
        return when (ids.size) {
            1 -> singlePortrait(ids.first())
            2 -> stackedPortrait(ids)
            3 -> focusPortrait(ids, primary)
            else -> gridPortrait(ids)
        }
    }

    private fun focusPortrait(
        ids: List<String>,
        primary: String,
    ): MultiviewLayoutPlan {
        val rest = ids.filterNot { it == primary }
        if (rest.isEmpty()) return singlePortrait(primary)

        // With 16:9 video, the top full-width stream is 9/16 of the
        // screen width high. The bottom row has (n-1) streams side by side,
        // so its height is 9/16/(n-1) of the screen width.
        val bottomCount = rest.size
        val topFraction = bottomCount.toFloat() / (bottomCount + 1f)

        val placements = mutableListOf(
            TilePlacement(
                identity = primary,
                left = 0f,
                top = 0f,
                right = 1f,
                bottom = topFraction,
            ),
        )

        rest.forEachIndexed { index, identity ->
            val left = index.toFloat() / bottomCount
            val right = (index + 1).toFloat() / bottomCount
            placements += TilePlacement(
                identity = identity,
                left = left,
                top = topFraction,
                right = right,
                bottom = 1f,
            )
        }

        return MultiviewLayoutPlan(
            placements = placements,
            portraitHeightWidthRatio =
                (9f / 16f) * (1f + 1f / bottomCount),
        )
    }

    private fun stackedPortrait(ids: List<String>): MultiviewLayoutPlan {
        val rowHeight = 1f / ids.size
        return MultiviewLayoutPlan(
            placements = ids.mapIndexed { index, identity ->
                TilePlacement(
                    identity = identity,
                    left = 0f,
                    top = index * rowHeight,
                    right = 1f,
                    bottom = (index + 1) * rowHeight,
                )
            },
            portraitHeightWidthRatio = (9f / 16f) * ids.size,
        )
    }

    private fun singlePortrait(identity: String): MultiviewLayoutPlan {
        return MultiviewLayoutPlan(
            placements = listOf(
                TilePlacement(identity, 0f, 0f, 1f, 1f),
            ),
            portraitHeightWidthRatio = 9f / 16f,
        )
    }

    private fun gridPortrait(ids: List<String>): MultiviewLayoutPlan {
        val columns = when {
            ids.size <= 1 -> 1
            ids.size == 2 -> 1
            else -> 2
        }
        val rows = (ids.size + columns - 1) / columns
        val columnWidth = 1f / columns
        val rowHeight = 1f / rows

        return MultiviewLayoutPlan(
            placements = ids.mapIndexed { index, identity ->
                val row = index / columns
                val column = index % columns
                TilePlacement(
                    identity = identity,
                    left = column * columnWidth,
                    top = row * rowHeight,
                    right = (column + 1) * columnWidth,
                    bottom = (row + 1) * rowHeight,
                )
            },
            portraitHeightWidthRatio =
                (9f / 16f) * columns.toFloat().let { 1f / it } * rows,
        )
    }
}
