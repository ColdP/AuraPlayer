// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.playback

import android.view.Surface
import btm.m.auraplayer.lyrics.SpatialGyroFilter
import btm.m.auraplayer.lyrics.computePvRowShiftX
import btm.m.auraplayer.ui.player.LyricPvEffect
import btm.m.auraplayer.ui.player.agslShaderSourceForLyricTheme
import btm.m.auraplayer.ui.player.lerpWalkBlend
import btm.m.auraplayer.ui.player.lyricThemeSpec
import btm.m.auraplayer.ui.player.resolveCityWalkBillboardStyle
import btm.m.auraplayer.ui.player.resolveTokyoLighting
import btm.m.auraplayer.ui.player.resolveTokyoWalkBlend
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
    fun allTwelveLyricThemesHaveValidSpecs() {
        assertEquals(12, LyricTheme.entries.size)
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
        assertEquals(11, nonBlurEffects.size)
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
        assertEquals(1.0f, defaultDecoded.spatialLyricsSensitivity, 0.001f)
        assertFalse(defaultDecoded.spatialLyricsPowerNoticeDismissed)

        val encoded = NowPlayingCodec.encode(
            NowPlayingConfig(
                spatialLyrics = true,
                spatialLyricsSensitivity = 2.1f,
                spatialLyricsPowerNoticeDismissed = true,
            ),
        )
        val decoded = NowPlayingCodec.decode(encoded)
        assertTrue(decoded.spatialLyrics)
        assertEquals(2.1f, decoded.spatialLyricsSensitivity, 0.001f)
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

    @Test
    fun allNonBlurLyricThemesProvideConsistentAgslAtmosphereShaders() {
        assertEquals(null, agslShaderSourceForLyricTheme(LyricTheme.BLUR))
        val nonBlur = LyricTheme.entries.filter { it != LyricTheme.BLUR }
        val sources = nonBlur.map { theme ->
            val src = agslShaderSourceForLyricTheme(theme)
            assertTrue("Expected AGSL shader for $theme", !src.isNullOrBlank())
            for (uniform in listOf("uResolution", "uTime", "uTilt", "uTintA", "uTintB", "half4 main(float2 fragCoord)")) {
                assertTrue("Missing $uniform in $theme shader", src!!.contains(uniform))
            }
            src!!
        }
        assertEquals(nonBlur.size, sources.toSet().size)
    }

    @Test
    fun tokyoSceneTimeCodecRoundTripsAndLightingFollowsSystemClock() {
        val defaults = NowPlayingCodec.decode("{}")
        assertEquals(TokyoSceneTime.FOLLOW_SYSTEM, defaults.tokyoSceneTime)

        for (preset in TokyoSceneTime.entries) {
            val encoded = NowPlayingCodec.encode(
                NowPlayingConfig(
                    lyricTheme = LyricTheme.TOKYO_CITYSCAPE,
                    tokyoSceneTime = preset,
                ),
            )
            val decoded = NowPlayingCodec.decode(encoded)
            assertEquals(LyricTheme.TOKYO_CITYSCAPE, decoded.lyricTheme)
            assertEquals(preset, decoded.tokyoSceneTime)
        }

        val dawnFixed = resolveTokyoLighting(TokyoSceneTime.DAWN, hour24 = 23, minute = 0)
        val noonFixed = resolveTokyoLighting(TokyoSceneTime.NOON, hour24 = 2, minute = 0)
        val duskFixed = resolveTokyoLighting(TokyoSceneTime.DUSK, hour24 = 12, minute = 0)
        val nightFixed = resolveTokyoLighting(TokyoSceneTime.NIGHT, hour24 = 12, minute = 0)

        assertTrue(noonFixed.daylight > 0.9f && noonFixed.nightness < 0.05f)
        assertTrue(nightFixed.nightness > 0.9f && nightFixed.towerGlow > 0.9f && nightFixed.cityLights > 0.9f)
        assertTrue(duskFixed.towerGlow > dawnFixed.towerGlow && duskFixed.cityLights > noonFixed.cityLights)

        val sysMidnight = resolveTokyoLighting(TokyoSceneTime.FOLLOW_SYSTEM, hour24 = 0, minute = 30)
        val sysDawn = resolveTokyoLighting(TokyoSceneTime.FOLLOW_SYSTEM, hour24 = 5, minute = 45)
        val sysNoon = resolveTokyoLighting(TokyoSceneTime.FOLLOW_SYSTEM, hour24 = 12, minute = 30)
        val sysDusk = resolveTokyoLighting(TokyoSceneTime.FOLLOW_SYSTEM, hour24 = 17, minute = 51)
        val sysNight = resolveTokyoLighting(TokyoSceneTime.FOLLOW_SYSTEM, hour24 = 22, minute = 0)

        assertTrue(sysMidnight.nightness > 0.9f)
        assertTrue(sysNoon.daylight > sysDawn.daylight)
        assertTrue(sysNoon.daylight > sysDusk.daylight)
        assertTrue(sysNight.towerGlow > 0.95f && sysNight.cityLights > 0.95f)
    }

    @Test
    fun tokyoLandmarkCodecRoundTripsAndCityWalkSeamlessBlendCoversAllZones() {
        val defaults = NowPlayingCodec.decode("{}")
        assertEquals(TokyoSceneLandmark.CITY_WALK, defaults.tokyoSceneLandmark)

        for (landmark in TokyoSceneLandmark.entries) {
            val encoded = NowPlayingCodec.encode(
                NowPlayingConfig(
                    lyricTheme = LyricTheme.TOKYO_CITYSCAPE,
                    tokyoSceneLandmark = landmark,
                ),
            )
            val decoded = NowPlayingCodec.decode(encoded)
            assertEquals(landmark, decoded.tokyoSceneLandmark)
        }

        // Fixed landmark pins full 1.0 weight on that single scene.
        val pinnedTower = resolveTokyoWalkBlend(TokyoSceneLandmark.TOKYO_TOWER, timeSec = 10f)
        assertEquals(1.0f, pinnedTower.tokyoTower, 0.001f)
        assertEquals(0.0f, pinnedTower.shibuya, 0.001f)
        assertEquals(TokyoSceneLandmark.TOKYO_TOWER, pinnedTower.dominantLandmark)

        // CITY_WALK visits all 5 districts and blends continuously with weights summing to 1.0.
        val visited = mutableSetOf<TokyoSceneLandmark>()
        for (sec in 0..60 step 2) {
            val b = resolveTokyoWalkBlend(TokyoSceneLandmark.CITY_WALK, sec.toFloat())
            val sum = b.shibuya + b.shinjuku + b.tokyoTower + b.asakusa + b.odaiba
            assertEquals(1.0f, sum, 0.001f)
            visited += b.dominantLandmark
        }
        assertEquals(5, visited.size)

        // Mid-transition morph produces strictly mixed weights between adjacent stops.
        val morphMid = resolveTokyoWalkBlend(TokyoSceneLandmark.CITY_WALK, 9.9f)
        assertTrue(morphMid.shibuya in 0.1f..0.9f)
        assertTrue(morphMid.shinjuku in 0.1f..0.9f)

        val lerped = lerpWalkBlend(
            resolveTokyoWalkBlend(TokyoSceneLandmark.SHIBUYA_CROSSING, 0f),
            resolveTokyoWalkBlend(TokyoSceneLandmark.ODAIBA_BAY, 0f),
            0.5f,
        )
        assertEquals(0.5f, lerped.shibuya, 0.001f)
        assertEquals(0.5f, lerped.odaiba, 0.001f)

        val billboard = resolveCityWalkBillboardStyle(TokyoSceneLandmark.SHINJUKU_NEON, 4f)
        assertEquals("SHINJUKU · KABUKICHO", billboard.districtTag)
        assertTrue(billboard.wallTiltYDeg >= 25f)
    }
}
