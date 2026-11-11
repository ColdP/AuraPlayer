package btm.m.auraplayer.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class PlayerLayoutGeometryTest {
    @Test fun fullWidthCoverNeverCreatesNegativePaddingAtFractionalDensities() {
        var reproducedRoundingError = false
        for (pixels in 720..1600 step 7) for (density in listOf(2.625f, 2.75f, 3f, 3.5f)) {
            val width = (pixels / density).dp
            val margin = width * .058f * 1.15f
            val cover = width - margin * 2
            if (((width - cover) / 2 - margin).value < 0f) reproducedRoundingError = true
            val inset = playerCoverInset(width, cover, margin)
            assertTrue(inset.value >= 0f)
            assertEquals(margin.value, (margin + inset).value, .0001f)
        }
        assertTrue("Regression inputs must reproduce the original negative padding", reproducedRoundingError)
    }

    @Test fun smallerCoverAndProgressRemainCenteredAndAligned() {
        val inset = playerCoverInset(400.dp, 280.dp, 26.dp)
        assertEquals(34f, inset.value, .0001f)
        assertEquals(60f, (26.dp + inset).value, .0001f)
    }
}
