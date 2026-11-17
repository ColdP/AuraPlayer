package btm.m.auraplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassMenuPositionTest {
    @Test fun longMenusStayWithinScreenFromEveryAnchorWithoutScaling() {
        for ((top, bottom) in listOf(72 to 2200, 48 to 950)) {
            val popupHeight = bottom - top
            for (preferred in listOf(-1200, 0, 200, 800, 1800, 2400)) {
                val y = glassMenuPositionInWindow(preferred, popupHeight, top, bottom)
                assertTrue(y >= top)
                assertTrue(y + popupHeight <= bottom)
            }
        }
    }

    @Test fun shortMenusStayAttachedWhenThereIsRoomAndMoveInsideAtEdges() {
        assertEquals(600, glassMenuPositionInWindow(600, 300, 80, 2200))
        assertEquals(80, glassMenuPositionInWindow(-40, 300, 80, 2200))
        assertEquals(1900, glassMenuPositionInWindow(2100, 300, 80, 2200))
    }

    @Test fun expandingSortMenuKeepsItsTopAndCapsTheScrollableViewport() {
        for (density in listOf(1f, 2.625f, 3f, 3.5f)) {
            val anchoredTop = (80 * density).toInt()
            val safeBottom = (780 * density).toInt()
            val cap = (440 * density).toInt()
            val viewport = glassMenuHeightInWindow(cap, safeBottom, anchoredTop, safeBottom)
            assertEquals(cap, viewport)
            // The growing contents exceed the cap; only the scroll viewport is measured.
            for (naturalHeightDp in listOf(180, 240, 360, 440, 600, 900)) {
                val measured = minOf((naturalHeightDp * density).toInt(), viewport)
                assertEquals(anchoredTop,
                    glassMenuPositionInWindow(anchoredTop, measured, anchoredTop, safeBottom))
                assertTrue(anchoredTop + measured <= safeBottom)
            }
        }
    }

    @Test fun smallWindowsAndLargeInsetsNeverProduceInvalidHeightConstraints() {
        assertEquals(120, glassMenuHeightInWindow(440, 300, 180, 300))
        assertEquals(80, glassMenuHeightInWindow(440, 80, 0, 300))
        assertEquals(300, glassMenuHeightInWindow(null, 500, 0, 300))
        assertEquals(0, glassMenuHeightInWindow(440, 300, 300, 180))
        assertEquals(0, glassMenuHeightInWindow(440, 0, 0, 300))
        assertEquals(0, glassMenuHeightInWindow(-10, 300, 0, 300))
    }

    @Test fun screenEdgeSpacingExcludesTheTransparentShadowPadding() {
        for (density in listOf(1f, 2.625f, 3f, 3.5f)) {
            val windowWidth = (412 * density).toInt()
            val inset = (12 * density).toInt()
            val surfaceWidth = (200 * density).toInt()
            val shadow = (28 * density).toInt()
            val popupWidth = surfaceWidth + 2 * shadow
            val x = glassMenuPositionWithShadow(windowWidth - inset - surfaceWidth,
                popupWidth, shadow, inset, windowWidth - inset)
            assertEquals(inset, windowWidth - (x + shadow + surfaceWidth))
            val rtlX = glassMenuPositionWithShadow(inset, popupWidth, shadow, inset, windowWidth - inset)
            assertEquals(inset, rtlX + shadow)
        }
    }
}
