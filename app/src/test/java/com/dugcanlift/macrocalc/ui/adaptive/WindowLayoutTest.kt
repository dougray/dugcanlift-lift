package com.dugcanlift.macrocalc.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The width-to-layout mapping. Pure functions, so no Robolectric and no store singletons to reset.
 * The widths used are the real ones: a Pixel 8 portrait is 411 dp, landscape 914 dp; a 2560x1600
 * tablet at 320 dpi is 1280 x 800 dp; an unfolded foldable is about 673 dp wide portrait and
 * 841 dp landscape. With the 80 dp rail, a tablet in landscape leaves a 1200 dp pane.
 */
class WindowLayoutTest {

    private val rail = 80f

    /** Content width for a window of [windowDp], with the rail it would have. */
    private fun content(windowDp: Float): Float {
        val pane = if (AdaptiveLayout.usesNavigationRail(WindowWidth.fromDp(windowDp))) windowDp - rail else windowDp
        return AdaptiveLayout.contentWidth(pane)
    }

    @Test fun `size classes break at 600 and 840 dp`() {
        assertEquals(WindowWidth.COMPACT, WindowWidth.fromDp(0f))
        assertEquals(WindowWidth.COMPACT, WindowWidth.fromDp(411f))
        assertEquals(WindowWidth.COMPACT, WindowWidth.fromDp(599.9f))
        assertEquals(WindowWidth.MEDIUM, WindowWidth.fromDp(600f))
        assertEquals(WindowWidth.MEDIUM, WindowWidth.fromDp(673f))
        assertEquals(WindowWidth.MEDIUM, WindowWidth.fromDp(839.9f))
        assertEquals(WindowWidth.EXPANDED, WindowWidth.fromDp(840f))
        assertEquals(WindowWidth.EXPANDED, WindowWidth.fromDp(1280f))
    }

    @Test fun `a phone keeps its tabs and the rail starts at medium`() {
        assertFalse(AdaptiveLayout.usesNavigationRail(WindowWidth.COMPACT))
        assertTrue(AdaptiveLayout.usesNavigationRail(WindowWidth.MEDIUM))
        assertTrue(AdaptiveLayout.usesNavigationRail(WindowWidth.EXPANDED))
    }

    @Test fun `a phone's gutter is the 16 dp it always had, and wide pages are capped and centred`() {
        assertEquals(16f, AdaptiveLayout.sideGutter(411f), 0f)
        assertEquals(379f, AdaptiveLayout.contentWidth(411f), 0f)
        assertEquals(16f, AdaptiveLayout.sideGutter(1200f), 0f)
        assertEquals(200f, AdaptiveLayout.sideGutter(1600f), 0f)
        assertEquals(AdaptiveLayout.MAX_CONTENT_DP, AdaptiveLayout.contentWidth(1600f), 0f)
        // A form caps at the readable width instead.
        assertEquals(AdaptiveLayout.READABLE_DP, AdaptiveLayout.contentWidth(1200f, AdaptiveLayout.READABLE_DP), 0f)
        assertEquals(16f, AdaptiveLayout.sideGutter(411f, AdaptiveLayout.READABLE_DP), 0f)
    }

    @Test fun `every split is one column on a phone, portrait or narrow landscape split screen`() {
        for (width in listOf(320f, 360f, 411f, 599f)) {
            val c = AdaptiveLayout.contentWidth(width)
            assertEquals(1, AdaptiveLayout.homeColumns(c))
            assertFalse(AdaptiveLayout.foodIsTwoPane(c))
            assertFalse(AdaptiveLayout.trainIsTwoPane(c))
            assertFalse(AdaptiveLayout.roadFoodIsTwoPane(c))
            assertEquals(1, AdaptiveLayout.cardColumns(c))
            assertEquals(1, AdaptiveLayout.planDayColumns(c))
            assertEquals(1, AdaptiveLayout.shoppingColumns(c))
            assertFalse(AdaptiveLayout.routeBesideStats(width))
        }
    }

    @Test fun `panes go side by side once two 320 dp panes fit`() {
        assertEquals(561f, content(673f), 0f) // foldable inner, portrait
        assertFalse(AdaptiveLayout.foodIsTwoPane(content(673f)))
        assertEquals(688f, content(800f), 0f) // tablet portrait
        assertTrue(AdaptiveLayout.foodIsTwoPane(content(800f)))
        assertTrue(AdaptiveLayout.trainIsTwoPane(content(841f)))
        assertEquals(2, AdaptiveLayout.homeColumns(content(1280f)))
        assertFalse(AdaptiveLayout.foodIsTwoPane(655f))
        assertTrue(AdaptiveLayout.foodIsTwoPane(656f))
        assertFalse(AdaptiveLayout.roadFoodIsTwoPane(content(673f)))
        assertTrue(AdaptiveLayout.roadFoodIsTwoPane(content(800f)))
    }

    @Test fun `cards fill wider space in 280 dp cells, three at most`() {
        assertEquals(1, AdaptiveLayout.cardColumns(561f))
        assertEquals(2, AdaptiveLayout.cardColumns(600f))
        assertEquals(2, AdaptiveLayout.cardColumns(863f))
        assertEquals(3, AdaptiveLayout.cardColumns(864f))
        assertEquals(3, AdaptiveLayout.cardColumns(content(1280f)))
        assertEquals(3, AdaptiveLayout.cardColumns(4000f))
    }

    @Test fun `the plan is two days side by side, then the whole week from 1000 dp`() {
        assertEquals(2, AdaptiveLayout.planDayColumns(600f))
        assertEquals(2, AdaptiveLayout.planDayColumns(999f))
        assertEquals(7, AdaptiveLayout.planDayColumns(1000f))
        assertEquals(7, AdaptiveLayout.planDayColumns(content(1280f)))
        assertEquals(2, AdaptiveLayout.planDayColumns(content(841f)))
    }

    @Test fun `shopping is two columns from medium`() {
        assertEquals(2, AdaptiveLayout.shoppingColumns(600f))
        assertEquals(2, AdaptiveLayout.shoppingColumns(1168f))
    }

    @Test fun `a route goes beside its numbers from a 600 dp pane`() {
        assertFalse(AdaptiveLayout.routeBesideStats(599f))
        assertTrue(AdaptiveLayout.routeBesideStats(600f))
    }

    @Test fun `row-major fills across then down`() {
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5)), rowMajor(listOf(1, 2, 3, 4, 5), 3))
        assertEquals(listOf(listOf(1), listOf(2)), rowMajor(listOf(1, 2), 1))
    }

    @Test fun `column-major reads down each column, earlier columns taking the extra item`() {
        assertEquals(listOf(listOf(1, 4), listOf(2, 5), listOf(3)), columnMajor(listOf(1, 2, 3, 4, 5), 2))
        assertEquals(listOf(listOf(1, 3), listOf(2, 4)), columnMajor(listOf(1, 2, 3, 4), 2))
        assertEquals(emptyList<List<Int>>(), columnMajor(emptyList<Int>(), 2))
    }

    @Test fun `masonry puts each card under the shortest column`() {
        // A tall macro card on the left; steps, training and fuel all stack beside it until the
        // right column is the taller one.
        assertEquals(listOf(0, 1, 1, 1, 0), masonryColumns(listOf(400, 150, 150, 200, 100), 2, gap = 16))
        // Ties go to the leftmost column.
        assertEquals(listOf(0, 1, 0, 1), masonryColumns(listOf(100, 100, 100, 100), 2, gap = 16))
        // An empty item takes no slot.
        assertEquals(listOf(0, -1, 1), masonryColumns(listOf(100, 0, 100), 2, gap = 16))
        // One column is a plain stack.
        assertEquals(listOf(0, 0, 0), masonryColumns(listOf(10, 20, 30), 1, gap = 16))
    }
}
