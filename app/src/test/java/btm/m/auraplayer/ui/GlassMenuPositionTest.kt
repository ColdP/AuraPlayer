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
}
