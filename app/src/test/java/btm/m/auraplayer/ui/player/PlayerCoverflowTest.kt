// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import androidx.compose.ui.unit.dp
import btm.m.auraplayer.playback.CoverTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerCoverflowTest {
    @Test fun resolveActiveCoverThemeHonorsExplicitChoiceAndLandscapeAutoCoverflow() {
        assertEquals(
            CoverTheme.SQUARE,
            resolveActiveCoverTheme(CoverTheme.SQUARE, isLandscape = false, landscapeAutoCoverflow = false),
        )
        assertEquals(
            CoverTheme.SQUARE,
            resolveActiveCoverTheme(CoverTheme.SQUARE, isLandscape = false, landscapeAutoCoverflow = true),
        )
        assertEquals(
            CoverTheme.COVERFLOW,
            resolveActiveCoverTheme(CoverTheme.SQUARE, isLandscape = true, landscapeAutoCoverflow = true),
        )
        assertEquals(
            CoverTheme.COVERFLOW,
            resolveActiveCoverTheme(CoverTheme.COVERFLOW, isLandscape = false, landscapeAutoCoverflow = false),
        )
        assertEquals(
            CoverTheme.TILED,
            resolveActiveCoverTheme(CoverTheme.TILED, isLandscape = false, landscapeAutoCoverflow = true),
        )
        assertEquals(
            CoverTheme.TILED,
            resolveActiveCoverTheme(CoverTheme.TILED, isLandscape = true, landscapeAutoCoverflow = true),
        )
    }

    @Test fun landscapeAutoCoverflowActivatesOnlyInLandscapeWhenEnabled() {
        assertFalse(shouldUseLandscapeCoverflow(isLandscape = false, landscapeAutoCoverflow = false))
        assertFalse(shouldUseLandscapeCoverflow(isLandscape = false, landscapeAutoCoverflow = true))
        assertFalse(shouldUseLandscapeCoverflow(isLandscape = true, landscapeAutoCoverflow = false))
        assertTrue(shouldUseLandscapeCoverflow(isLandscape = true, landscapeAutoCoverflow = true))
    }

    @Test fun centerCardTransformIsUnrotatedFullScaleUnblurredHighestZIndex() {
        val center = computeCoverflowCardTransform(0f)
        assertEquals(0f, center.rotationY, 0.001f)
        assertEquals(1f, center.scale, 0.001f)
        assertEquals(0f, center.blurRadiusDp, 0.001f)
        assertEquals(0f, center.dimAlpha, 0.001f)
        assertEquals(1f, center.contentAlpha, 0.001f)

        val neighbor = computeCoverflowCardTransform(1f)
        assertTrue(center.zIndex > neighbor.zIndex)
    }

    @Test fun sideCardsAreSymmetricAndReceiveProgressiveDepthBlur() {
        val left = computeCoverflowCardTransform(-1f)
        val right = computeCoverflowCardTransform(1f)
        val farRight = computeCoverflowCardTransform(2.2f)

        assertTrue(left.rotationY > 0f)
        assertEquals(-left.rotationY, right.rotationY, 0.001f)
        assertEquals(left.scale, right.scale, 0.001f)
        assertEquals(left.blurRadiusDp, right.blurRadiusDp, 0.001f)
        assertEquals(left.dimAlpha, right.dimAlpha, 0.001f)
        assertEquals(-left.translationXFactor, right.translationXFactor, 0.001f)

        assertTrue(right.blurRadiusDp > 0f)
        assertTrue(farRight.blurRadiusDp >= right.blurRadiusDp)
        assertTrue(right.scale in 0.66f..0.95f)
        assertTrue(farRight.scale <= right.scale)
        assertTrue(farRight.contentAlpha < right.contentAlpha)
    }

    @Test fun metricsAdaptCleanlyToPortraitPhoneLandscapeAndTabletLandscape() {
        val portrait = computeCoverflowMetrics(
            width = 412.dp,
            height = 915.dp,
            leftInset = 0.dp,
            topInset = 44.dp,
            rightInset = 0.dp,
            bottomInset = 24.dp,
            showVolume = true,
        )
        assertFalse(portrait.isLandscape)
        assertFalse(portrait.dockCompact)
        assertTrue(portrait.includeVolumeInDock)
        assertTrue(portrait.cardSize >= 170.dp)
        assertTrue(portrait.pageSpacing < 0.dp)
        assertTrue(portrait.carouselTopPadding >= 62.dp)

        val phoneLandscape = computeCoverflowMetrics(
            width = 840.dp,
            height = 380.dp,
            leftInset = 24.dp,
            topInset = 24.dp,
            rightInset = 24.dp,
            bottomInset = 16.dp,
            showVolume = true,
        )
        assertTrue(phoneLandscape.isLandscape)
        assertTrue(phoneLandscape.dockCompact)
        assertFalse(phoneLandscape.includeVolumeInDock)
        assertTrue(phoneLandscape.cardSize >= 116.dp)
        assertTrue(phoneLandscape.cardSize + phoneLandscape.reflectionHeight + phoneLandscape.carouselTopPadding + 120.dp <= 380.dp)
        assertTrue(phoneLandscape.pageSpacing < 0.dp)

        val tabletLandscape = computeCoverflowMetrics(
            width = 1200.dp,
            height = 760.dp,
            leftInset = 0.dp,
            topInset = 24.dp,
            rightInset = 0.dp,
            bottomInset = 24.dp,
            showVolume = false,
        )
        assertTrue(tabletLandscape.isLandscape)
        assertFalse(tabletLandscape.dockCompact)
        assertTrue(tabletLandscape.cardSize > phoneLandscape.cardSize)
        assertTrue(tabletLandscape.cardSize <= 340.dp)
        assertTrue(tabletLandscape.dockHorizontalMargin > phoneLandscape.dockHorizontalMargin)
    }
}
