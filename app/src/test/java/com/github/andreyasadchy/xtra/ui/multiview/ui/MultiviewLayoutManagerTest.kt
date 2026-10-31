package com.github.andreyasadchy.xtra.ui.multiview.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewLayoutManagerTest {

    private val ids = listOf("stream-a", "stream-b", "stream-c", "stream-d")

    @Test
    fun emptyStreamListReturnsEmptyPlan() {
        val plan = MultiviewLayoutManager.plan(
            identities = emptyList(),
            activeIdentity = null,
            mode = MultiviewLayoutMode.AUTO,
            focusedIdentity = null,
            landscape = false,
        )
        assertTrue(plan.placements.isEmpty())
    }

    @Test
    fun singleStreamOccupiesFullScreen() {
        val plan = MultiviewLayoutManager.plan(
            identities = listOf("stream-a"),
            activeIdentity = "stream-a",
            mode = MultiviewLayoutMode.AUTO,
            focusedIdentity = null,
            landscape = false,
        )
        assertEquals(1, plan.placements.size)
        val placement = plan.placements.first()
        assertEquals("stream-a", placement.identity)
        assertEquals(0f, placement.left, 0.001f)
        assertEquals(0f, placement.top, 0.001f)
        assertEquals(1f, placement.right, 0.001f)
        assertEquals(1f, placement.bottom, 0.001f)
    }

    @Test
    fun autoTwoStreamsPortraitStacksVertically() {
        val plan = MultiviewLayoutManager.plan(
            identities = ids.take(2),
            activeIdentity = "stream-a",
            mode = MultiviewLayoutMode.AUTO,
            focusedIdentity = null,
            landscape = false,
        )
        assertEquals(2, plan.placements.size)
        val first = plan.placements[0]
        val second = plan.placements[1]
        assertEquals("stream-a", first.identity)
        assertEquals(0f, first.top, 0.001f)
        assertEquals(0.5f, first.bottom, 0.001f)
        assertEquals("stream-b", second.identity)
        assertEquals(0.5f, second.top, 0.001f)
        assertEquals(1f, second.bottom, 0.001f)
    }

    @Test
    fun autoThreeStreamsPortraitPlacesPrimaryAboveTwoThumbnails() {
        val plan = MultiviewLayoutManager.plan(
            identities = ids.take(3),
            activeIdentity = "stream-a",
            mode = MultiviewLayoutMode.AUTO,
            focusedIdentity = null,
            landscape = false,
        )
        assertEquals(3, plan.placements.size)
        val primary = plan.placements[0]
        assertEquals("stream-a", primary.identity)
        assertEquals(0f, primary.left, 0.001f)
        assertEquals(1f, primary.right, 0.001f)

        val bottomRow = plan.placements.drop(1)
        assertEquals(2, bottomRow.size)
        assertTrue(bottomRow.all { it.top == primary.bottom })
        assertEquals(0f, bottomRow[0].left, 0.001f)
        assertEquals(0.5f, bottomRow[0].right, 0.001f)
        assertEquals(0.5f, bottomRow[1].left, 0.001f)
        assertEquals(1f, bottomRow[1].right, 0.001f)
    }

    @Test
    fun gridFourStreamsPortraitDividesIntoTwoByTwo() {
        val plan = MultiviewLayoutManager.plan(
            identities = ids,
            activeIdentity = "stream-a",
            mode = MultiviewLayoutMode.GRID,
            focusedIdentity = null,
            landscape = false,
        )
        assertEquals(4, plan.placements.size)
        plan.placements.forEach { tile ->
            assertTrue(tile.right - tile.left in 0.49f..0.51f)
            assertTrue(tile.bottom - tile.top in 0.49f..0.51f)
        }
    }

    @Test
    fun focusModePromotesFocusedIdentity() {
        val plan = MultiviewLayoutManager.plan(
            identities = ids,
            activeIdentity = "stream-a",
            mode = MultiviewLayoutMode.FOCUS,
            focusedIdentity = "stream-c",
            landscape = false,
        )
        assertEquals(4, plan.placements.size)
        val primary = plan.placements.first()
        assertEquals("stream-c", primary.identity)
        assertEquals(0f, primary.left, 0.001f)
        assertEquals(1f, primary.right, 0.001f)
    }

    @Test
    fun landscapeAllocatesMainAreaAndSideColumn() {
        val plan = MultiviewLayoutManager.plan(
            identities = ids.take(3),
            activeIdentity = "stream-b",
            mode = MultiviewLayoutMode.AUTO,
            focusedIdentity = null,
            landscape = true,
            chatVisible = false,
        )
        assertEquals(3, plan.placements.size)
        val primary = plan.placements.first()
        assertEquals("stream-b", primary.identity)
        assertEquals(0f, primary.left, 0.001f)
        assertEquals(0.80f, primary.right, 0.001f)
        assertEquals(1f, primary.bottom, 0.001f)

        val sideColumn = plan.placements.drop(1)
        sideColumn.forEach { tile ->
            assertEquals(0.80f, tile.left, 0.001f)
            assertEquals(1f, tile.right, 0.001f)
        }
    }

    @Test
    fun allPlacementsStayWithinBounds() {
        listOf(
            MultiviewLayoutMode.AUTO,
            MultiviewLayoutMode.GRID,
            MultiviewLayoutMode.FOCUS,
            MultiviewLayoutMode.TIERED,
        ).forEach { mode ->
            (1..4).forEach { count ->
                val plan = MultiviewLayoutManager.plan(
                    identities = ids.take(count),
                    activeIdentity = "stream-a",
                    mode = mode,
                    focusedIdentity = "stream-b".takeIf { count >= 2 },
                    landscape = false,
                )
                plan.placements.forEach { tile ->
                    assertTrue(tile.left in 0f..1f)
                    assertTrue(tile.top in 0f..1f)
                    assertTrue(tile.right in 0f..1f)
                    assertTrue(tile.bottom in 0f..1f)
                    assertTrue(tile.right > tile.left)
                    assertTrue(tile.bottom > tile.top)
                }
            }
        }
    }
}
