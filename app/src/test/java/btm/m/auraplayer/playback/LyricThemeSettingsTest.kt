// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.playback

import android.view.Surface
import btm.m.auraplayer.lyrics.SpatialGyroFilter
import btm.m.auraplayer.lyrics.computePvRowShiftX
import btm.m.auraplayer.ui.player.LyricPvEffect
import btm.m.auraplayer.ui.player.lyricThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricThemeSettingsTest {
    @Test
    fun codecRoundTripsAllLyricThemesAndPowerNoticeState() {
        val defaultDecoded = NowPlayingCodec.decode("{}")
        assertEquals(LyricTheme.BLUR, defaultDecoded.lyricTheme)
        assertFalse(defaultDecoded.lyricThemePowerNoticeDismissed)

        for (theme in LyricTheme.entries) {
            val encoded = NowPlayingCodec.encode(
                NowPlayingConfig(
                    lyricTheme = theme,
                    lyricThemePowerNoticeDismissed = theme != LyricTheme.BLUR,
                ),
            )
            val decoded = NowPlayingCodec.decode(encoded)
            assertEquals(theme, decoded.lyricTheme)
            assertEquals(theme != LyricTheme.BLUR, decoded.lyricThemePowerNoticeDismissed)
        }
    }

    @Test
    fun powerNoticePromptsOnlyForNonBlurWhenNotDismissed() {
        assertFalse(shouldPromptLyricThemePowerNotice(LyricTheme.BLUR, dismissed = false))
        assertFalse(shouldPromptLyricThemePowerNotice(LyricTheme.BLUR, dismissed = true))

        for (theme in LyricTheme.entries.filter { it != LyricTheme.BLUR }) {
            assertTrue(shouldPromptLyricThemePowerNotice(theme, dismissed = false))
            assertFalse(shouldPromptLyricThemePowerNotice(theme, dismissed = true))
        }
    }

    @Test
    fun allElevenLyricThemesHaveValidSpecs() {
        assertEquals(11, LyricTheme.entries.size)
        val nonBlurEffects = mutableSetOf<LyricPvEffect>()
        for (theme in LyricTheme.entries) {
            val spec = lyricThemeSpec(theme)
            assertTrue(spec.fontFamilyName.isNotBlank())
            assertTrue(spec.mainWeight in 100..900)
            assertTrue(spec.subWeight in 100..900)
            assertTrue(spec.depthBlurMultiplier in 0f..1.5f)
            assertTrue(spec.staggerPattern.isNotEmpty())
            assertTrue(spec.tiltPatternDeg.isNotEmpty())
            if (theme == LyricTheme.BLUR) {
                assertEquals(LyricPvEffect.NONE, spec.pvEffect)
            } else {
                assertTrue(spec.pvEffect != LyricPvEffect.NONE)
                nonBlurEffects += spec.pvEffect
                assertTrue(spec.heroScale > 1.0f)
                assertTrue(spec.staggerPattern.any { it > 0.25f })
                assertTrue(
                    "Expected non-blur theme $theme to apply 3D depth or glow",
                    spec.cameraTiltXDeg != 0f || spec.cameraDepthZ > 0f || spec.glowRadiusDp > 0f,
                )
            }
        }
        assertEquals(10, nonBlurEffects.size)
    }

    @Test
    fun pvHorizontalShiftStaysInsideViewportWidthAndFlipsForOppositeLines() {
        assertEquals(0f, computePvRowShiftX(boxWidth = 200f, contentWidth = 240f, staggerRatio = 0.8f, opposite = false), 0.001f)
        assertEquals(60f, computePvRowShiftX(boxWidth = 200f, contentWidth = 100f, staggerRatio = 0.6f, opposite = false), 0.001f)
        assertEquals(-40f, computePvRowShiftX(boxWidth = 200f, contentWidth = 100f, staggerRatio = 0.6f, opposite = true), 0.001f)
        assertEquals(100f, computePvRowShiftX(boxWidth = 200f, contentWidth = 100f, staggerRatio = 2.0f, opposite = false), 0.001f)
    }

    @Test
    fun spatialLyricsCodecRoundtripsAndPowerNoticeTriggersOnlyOnEnable() {
        val defaultDecoded = NowPlayingCodec.decode("{}")
        assertFalse(defaultDecoded.spatialLyrics)
        assertFalse(defaultDecoded.spatialLyricsPowerNoticeDismissed)

        val encoded = NowPlayingCodec.encode(
            NowPlayingConfig(
                spatialLyrics = true,
                spatialLyricsPowerNoticeDismissed = true,
            ),
        )
        val decoded = NowPlayingCodec.decode(encoded)
        assertTrue(decoded.spatialLyrics)
        assertTrue(decoded.spatialLyricsPowerNoticeDismissed)

        assertTrue(shouldPromptSpatialLyricsPowerNotice(enabling = true, dismissed = false))
        assertFalse(shouldPromptSpatialLyricsPowerNotice(enabling = true, dismissed = true))
        assertFalse(shouldPromptSpatialLyricsPowerNotice(enabling = false, dismissed = false))
    }

    @Test
    fun spatialGyroFilterClampsTiltRotatesAxesAndRecenters() {
        assertEquals(2f to 1f, SpatialGyroFilter.remapAxes(wx = 1f, wy = 2f, Surface.ROTATION_0))
        assertEquals(-1f to 2f, SpatialGyroFilter.remapAxes(wx = 1f, wy = 2f, Surface.ROTATION_90))
        assertEquals(-2f to -1f, SpatialGyroFilter.remapAxes(wx = 1f, wy = 2f, Surface.ROTATION_180))
        assertEquals(1f to -2f, SpatialGyroFilter.remapAxes(wx = 1f, wy = 2f, Surface.ROTATION_270))

        val filter = SpatialGyroFilter()
        repeat(40) {
            filter.ingestAngularVelocity(wxRadPerSec = 5f, wyRadPerSec = 6f, dtSec = 0.02f, Surface.ROTATION_0)
            filter.step(0.02f)
        }
        assertTrue(filter.x in -1f..1f && filter.x > 0.3f)
        assertTrue(filter.y in -1f..1f && filter.y > 0.3f)

        val peakX = filter.x
        repeat(180) {
            filter.step(0.02f)
        }
        assertTrue(filter.x < peakX * 0.25f)
    }
}
