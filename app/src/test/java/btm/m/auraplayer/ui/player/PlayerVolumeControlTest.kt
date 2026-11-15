// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerVolumeControlTest {
    @Test
    fun standardFifteenStepScaleAdjustsByOneAndClampsBounds() {
        assertEquals(6, nextMediaVolumeStep(current = 7, max = 15, increase = false))
        assertEquals(8, nextMediaVolumeStep(current = 7, max = 15, increase = true))
        assertEquals(0, nextMediaVolumeStep(current = 0, max = 15, increase = false))
        assertEquals(15, nextMediaVolumeStep(current = 15, max = 15, increase = true))
    }

    @Test
    fun highResolutionScaleStepsProportionally() {
        assertEquals(80, nextMediaVolumeStep(current = 90, max = 150, increase = false))
        assertEquals(100, nextMediaVolumeStep(current = 90, max = 150, increase = true))
        assertEquals(0, nextMediaVolumeStep(current = 5, max = 150, increase = false))
        assertEquals(150, nextMediaVolumeStep(current = 145, max = 150, increase = true))
    }
}
