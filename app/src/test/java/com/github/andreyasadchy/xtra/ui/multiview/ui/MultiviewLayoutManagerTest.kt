package com.github.andreyasadchy.xtra.ui.multiview.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewLayoutManagerTest {
    private val ids = listOf("a", "b", "c", "d")

    @Test
    fun twoStreamLandscapeFillChatOnSplitsFiftyFifty() {
        val plan = MultiviewLayoutManager.plan(
            identities = ids.take(2),
            activeIdentity = "a",
            mode = MultiviewLayoutMode.AUTO,
            focusedIdentity = null,
            landscape = true,
            chatVisible = true,
            fillVideo = true,
        )
        assertEquals(2, plan.placements.size)
        val first = plan.placements[0]
        val second = plan.placements[1]
        assertEquals(0f, first.left, 0.001f)
        assertEquals(0.5f, first.right, 0.001f)
        assertEquals(0.5f, second.left, 0.001f)
        assertEquals(1.0f, second.right, 0.001f)
    }

    @Test
    fun twoStreamLandscapeFitChatOnPreservesStandardMainRight() {
        val plan = MultiviewLayoutManager.plan(
            identities = ids.take(2),
            activeIdentity = "a",
            mode = MultiviewLayoutMode.AUTO,
            focusedIdentity = null,
            landscape = true,
            chatVisible = true,
            fillVideo = false,
        )
        assertEquals(2, plan.placements.size)
        val first = plan.placements[0]
        val second = plan.placements[1]
        assertEquals(0f, first.left, 0.001f)
        assertEquals(0.75f, first.right, 0.001f)
        assertEquals(0.75f, second.left, 0.001f)
        assertEquals(1.0f, second.right, 0.001f)
    }

    @Test
    fun threeStreamLandscapeChatOnMainOccupiesSixtyPercentOverall() {
        val plan = MultiviewLayoutManager.plan(
            identities = ids.take(3),
            activeIdentity = "a",
            mode = MultiviewLayoutMode.AUTO,
            focusedIdentity = null,
            landscape = true,
            chatVisible = true,
            fillVideo = true,
        )
        assertEquals(3, plan.placements.size)
        val main = plan.placements[0]
        assertEquals(0f, main.left, 0.001f)
        assertEquals(0.75f, main.right, 0.001f) // 0.75 of 80% video area = 60% of screen
        val stack = plan.placements.drop(1)
        assertTrue(stack.all { it.left == 0.75f && it.right == 1.0f })
        assertEquals(0f, stack[0].top, 0.001f)
        assertEquals(0.5f, stack[0].bottom, 0.001f)
        assertEquals(0.5f, stack[1].top, 0.001f)
        assertEquals(1.0f, stack[1].bottom, 0.001f)
    }

    @Test
    fun twoStreamLandscapeChatOffPreservesSpecialLayout() {
        val plan = MultiviewLayoutManager.plan(
            identities = ids.take(2),
            activeIdentity = "a",
            mode = MultiviewLayoutMode.AUTO,
            focusedIdentity = null,
            landscape = true,
            chatVisible = false,
            fillVideo = false,
        )
        assertEquals(2, plan.placements.size)
        val main = plan.placements[0]
        val second = plan.placements[1]
        assertEquals(0.80f, main.right, 0.001f)
        assertEquals(0.80f, second.left, 0.001f)
        assertEquals(1.0f, second.right, 0.001f)
        assertEquals(0.5f, second.bottom, 0.001f)
    }

    @Test
    fun portraitLineGridAssignsEqualHeights() {
        val plan = MultiviewLayoutManager.plan(
            identities = ids.take(3),
            activeIdentity = "a",
            mode = MultiviewLayoutMode.GRID,
            focusedIdentity = null,
            landscape = false,
        )
        assertEquals(3, plan.placements.size)
        val expectedHeight = 1f / 3f
        plan.placements.forEachIndexed { index, tile ->
            assertEquals(0f, tile.left, 0.001f)
            assertEquals(1f, tile.right, 0.001f)
            assertEquals(index * expectedHeight, tile.top, 0.001f)
            assertEquals((index + 1) * expectedHeight, tile.bottom, 0.001f)
        }
    }
}
