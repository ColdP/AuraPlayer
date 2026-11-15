// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTiledWallTest {
    @Test fun rowStrideStaggersVerticallyAndFindsNearestAdjacentTrack() {
        val trackCount = 12
        val stride = tiledRowStride(gridCols = 4, trackCount = trackCount)
        assertEquals(trackCount, trackCount)
        // Coprime stride guarantees column walk visits distinct tracks rather than repeating row after row.
        val firstColTracks = (0 until 6).map { r ->
            tiledTrackIndexForCell(col = 0, row = r, baseTrackOffset = 3, rowStride = stride, trackCount = trackCount)
        }.toSet()
        assertEquals(6, firstColTracks.size)

        // Adjacent next/prev songs sit 1 tile to the right/left
        assertEquals(4, tiledTrackIndexForCell(1, 0, baseTrackOffset = 3, rowStride = stride, trackCount = trackCount))
        assertEquals(2, tiledTrackIndexForCell(-1, 0, baseTrackOffset = 3, rowStride = stride, trackCount = trackCount))
        val nearestNext = findNearestCellForTrack(0, 0, targetTrackIndex = 4, baseTrackOffset = 3, rowStride = stride, trackCount = trackCount)
        assertEquals(TiledGridCell(1, 0), nearestNext)
    }

    @Test fun centeredCandidateResolvesInsideCaptureZoneAndReturnsNullOnGap() {
        assertEquals(TiledGridCell(0, 0), resolveCenteredTiledCandidate(0.10f, -0.15f))
        assertEquals(TiledGridCell(2, -1), resolveCenteredTiledCandidate(2.30f, -0.85f))
        // Paused right in the seam/gap between tiles -> no song selected
        assertNull(resolveCenteredTiledCandidate(0.49f, 0.10f))
        assertNull(resolveCenteredTiledCandidate(0.10f, -0.48f))
    }

    @Test fun visualEffectAppliesProgressiveBlurOutwardAndBoostsHeldCandidate() {
        val focal = computeTiledCellVisualEffect(
            distNorm = 0.05f,
            isHoveredCandidate = false,
            isArmedCandidate = false,
            isPlayingAnchored = true,
            dwellProgress = 0f,
        )
        assertEquals(0f, focal.blurDp, 0.001f)
        assertTrue(focal.scale >= 1.24f)

        val outer = computeTiledCellVisualEffect(
            distNorm = 1.35f,
            isHoveredCandidate = false,
            isArmedCandidate = false,
            isPlayingAnchored = false,
            dwellProgress = 0f,
        )
        assertTrue(outer.blurDp > 2.5f)
        assertTrue(outer.scale < 1f)
        assertTrue(outer.dimAlpha > focal.dimAlpha)
        assertTrue(outer.contentAlpha < focal.contentAlpha)

        val charging = computeTiledCellVisualEffect(
            distNorm = 0.08f,
            isHoveredCandidate = true,
            isArmedCandidate = false,
            isPlayingAnchored = false,
            dwellProgress = 0.75f,
        )
        val armed = computeTiledCellVisualEffect(
            distNorm = 0.08f,
            isHoveredCandidate = true,
            isArmedCandidate = true,
            isPlayingAnchored = false,
            dwellProgress = 1f,
        )
        assertEquals(0f, charging.blurDp, 0.001f)
        assertTrue(armed.scale > charging.scale)
    }

    @Test fun metricsAdaptCleanlyToPortraitAndLandscapeTiledWalls() {
        val portrait = computeTiledWallMetrics(
            width = 412.dp,
            height = 915.dp,
            leftInset = 0.dp,
            topInset = 44.dp,
            rightInset = 0.dp,
            bottomInset = 24.dp,
            trackCount = 24,
            showVolume = true,
        )
        assertFalse(portrait.isLandscape)
        assertTrue(portrait.includeVolumeInDock)
        assertTrue(portrait.tileSize in 106.dp..158.dp)

        val landscape = computeTiledWallMetrics(
            width = 840.dp,
            height = 380.dp,
            leftInset = 24.dp,
            topInset = 24.dp,
            rightInset = 24.dp,
            bottomInset = 16.dp,
            trackCount = 24,
            showVolume = true,
        )
        assertTrue(landscape.isLandscape)
        assertTrue(landscape.dockCompact)
        assertFalse(landscape.includeVolumeInDock)
        assertNotNull(landscape)
    }

    @Test fun enlargedHeroCoverPushesNeighborsOutwardWithoutOverlap() {
        assertEquals(
            TiledRepulsionOffset(0f, 0f),
            computeTiledRepulsionOffset(col = 2, row = 3, heroCol = 2, heroRow = 3, repulsionStrength = 1f),
        )
        assertEquals(
            TiledRepulsionOffset(0f, 0f),
            computeTiledRepulsionOffset(col = 3, row = 3, heroCol = 2, heroRow = 3, repulsionStrength = 0f),
        )

        val right1 = computeTiledRepulsionOffset(col = 3, row = 3, heroCol = 2, heroRow = 3, repulsionStrength = 1f)
        val left1 = computeTiledRepulsionOffset(col = 1, row = 3, heroCol = 2, heroRow = 3, repulsionStrength = 1f)
        val bottom1 = computeTiledRepulsionOffset(col = 2, row = 4, heroCol = 2, heroRow = 3, repulsionStrength = 1f)
        val right2 = computeTiledRepulsionOffset(col = 4, row = 3, heroCol = 2, heroRow = 3, repulsionStrength = 1f)
        val diag1 = computeTiledRepulsionOffset(col = 3, row = 4, heroCol = 2, heroRow = 3, repulsionStrength = 1f)

        // Hero at 1.825x reaches 0.9125 tile half-width, so ring 1 must clear 0.9125 + 0.50 - 1.0 = 0.4125
        assertTrue(right1.offsetXFactor > 0.4125f)
        assertEquals(0f, right1.offsetYFactor, 0.0001f)
        assertEquals(-right1.offsetXFactor, left1.offsetXFactor, 0.0001f)
        assertEquals(right1.offsetXFactor, bottom1.offsetYFactor, 0.0001f)
        assertTrue(right2.offsetXFactor > 0f && right2.offsetXFactor < right1.offsetXFactor)
        // Verify ring 1 -> ring 2 keeps positive separation even after shifting outward (1 + r2 - r1 > 0.92 tile width)
        assertTrue(1f + right2.offsetXFactor - right1.offsetXFactor > 0.92f)
        // Diagonal corners shift cleanly away along both X & Y
        assertTrue(diag1.offsetXFactor > 0.07f && diag1.offsetYFactor > 0.07f)
    }
}
