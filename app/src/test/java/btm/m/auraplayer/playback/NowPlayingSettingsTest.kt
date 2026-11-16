package btm.m.auraplayer.playback

import androidx.compose.ui.geometry.Offset
import btm.m.auraplayer.lyrics.scaleSpatialParallaxTilt
import org.junit.Assert.*
import org.junit.Test

class NowPlayingSettingsTest {
    @Test fun oldOrCorruptPreferencesKeepExistingPlayerAppearance() {
        val expected = NowPlayingConfig()
        assertEquals(expected, NowPlayingCodec.decode("{}"))
        assertEquals(expected, NowPlayingCodec.decode("not json"))
        assertEquals(PlayerTheme.AURORA, expected.theme)
        assertEquals(MaterialPlayerBackground.MATERIAL, expected.materialBackground)
        assertEquals(GlowProgressStyle.OFF, expected.glowProgress)
        assertEquals(50, expected.materialColorMix)
        assertTrue(expected.showVolume && expected.showAudioInfo && expected.swipeTracks && expected.artistWordmarkLogo)
        assertEquals(CoverTheme.SQUARE, expected.coverTheme)
        assertEquals(2.0f, expected.tiledSwitchDwellSeconds, 0.001f)
        assertEquals(1.0f, expected.coverflowParallaxSensitivity, 0.001f)
        assertEquals(1.0f, expected.tiledParallaxSensitivity, 0.001f)
        assertEquals(1.0f, expected.spatialLyricsSensitivity, 0.001f)
        assertEquals(1.0f, expected.spatialCoverSensitivity, 0.001f)
        assertEquals(TokyoSceneTime.FOLLOW_SYSTEM, expected.tokyoSceneTime)
        assertEquals(TokyoSceneLandmark.CITY_WALK, expected.tokyoSceneLandmark)
        assertEquals(0f, expected.mainLyricScalePercent, 0f)
        assertEquals(0f, expected.bgLyricScalePercent, 0f)
        assertFalse(expected.immersiveLyrics || expected.keepLyricsAwake || expected.immersiveCover || expected.landscapeAutoCoverflow || expected.coverflowParallax || expected.tiledParallax || expected.appleMusicAnimatedCover || expected.albumAnimatedCover || expected.appleMusicArtistArtwork || expected.artistAnimatedCover)
        assertTrue(expected.fullscreenGlass)
    }

    @Test fun everyThemeAnimationAndToggleSurvivesRestart() {
        for (theme in PlayerTheme.entries) for (bg in MaterialPlayerBackground.entries) for (glow in GlowProgressStyle.entries) for (animation in CoverAnimation.entries) for (weather in PlayerWeather.entries) for (coverTheme in CoverTheme.entries) {
            val config = NowPlayingConfig(theme, -32, weather, animation,
                immersiveCover = true,
                coverTheme = coverTheme,
                tiledSwitchDwellSeconds = 3.6f,
                coverflowParallax = true,
                coverflowParallaxSensitivity = 1.8f,
                tiledParallax = true,
                tiledParallaxSensitivity = 0.6f,
                spatialLyrics = true,
                spatialLyricsSensitivity = 1.9f,
                spatialCover = true,
                spatialCoverSensitivity = 0.4f,
                landscapeAutoCoverflow = true,
                appleMusicAnimatedCover = true,
                albumAnimatedCover = true,
                appleMusicArtistArtwork = true,
                artistAnimatedCover = true,
                artistWordmarkLogo = false,
                showVolume = false, openFromNotification = false, keepLyricsAwake = true,
                showAudioInfo = false, swipeTracks = false,
                immersiveLyrics = true, immersiveSeconds = 27, fullscreenGlass = false,
                materialBackground = bg, materialColorMix = 78, glowProgress = glow,
                mainLyricScalePercent = -35.4f, bgLyricScalePercent = 142.8f)
            assertEquals(config, NowPlayingCodec.decode(NowPlayingCodec.encode(config)))
        }
    }

    @Test fun removedHdrSettingIsIgnoredWithoutResettingOtherPreferences() {
        val config = NowPlayingCodec.decode("""{"lyricHdr":true,"theme":"BLUR","blur":12,"keepLyricsAwake":true,"immersiveLyrics":true}""")
        assertEquals(PlayerTheme.BLUR, config.theme)
        assertEquals(12, config.blur)
        assertTrue(config.keepLyricsAwake && config.immersiveLyrics)
        assertFalse(NowPlayingCodec.encode(config).contains("lyricHdr"))
    }

    @Test fun unknownEnumsFallBackIndividuallyAndSlidersClampToRequestedRanges() {
        val config = NowPlayingCodec.decode("""{"theme":"future","materialBackground":"future","glowProgress":"future","weather":"future","coverAnimation":"future","coverTheme":"future","lyricTheme":"future","tokyoSceneTime":"future","tokyoSceneLandmark":"future","blur":999,"immersiveSeconds":99,"materialColorMix":150,"tiledSwitchDwellSeconds":12.4,"coverflowParallaxSensitivity":8.7,"tiledParallaxSensitivity":4.2,"spatialLyricsSensitivity":6.0,"spatialCoverSensitivity":9.5,"showVolume":false}""")
        assertEquals(PlayerTheme.AURORA, config.theme)
        assertEquals(MaterialPlayerBackground.MATERIAL, config.materialBackground)
        assertEquals(GlowProgressStyle.OFF, config.glowProgress)
        assertEquals(PlayerWeather.NONE, config.weather)
        assertEquals(CoverAnimation.DEFAULT, config.coverAnimation)
        assertEquals(CoverTheme.SQUARE, config.coverTheme)
        assertEquals(LyricTheme.BLUR, config.lyricTheme)
        assertEquals(TokyoSceneTime.FOLLOW_SYSTEM, config.tokyoSceneTime)
        assertEquals(TokyoSceneLandmark.CITY_WALK, config.tokyoSceneLandmark)
        assertEquals(96, config.blur); assertEquals(30, config.immersiveSeconds); assertEquals(100, config.materialColorMix)
        assertEquals(5.0f, config.tiledSwitchDwellSeconds, 0.001f)
        assertEquals(2.5f, config.coverflowParallaxSensitivity, 0.001f)
        assertEquals(2.5f, config.tiledParallaxSensitivity, 0.001f)
        assertEquals(2.5f, config.spatialLyricsSensitivity, 0.001f)
        assertEquals(2.5f, config.spatialCoverSensitivity, 0.001f)
        assertFalse(config.showVolume)
        val low = NowPlayingCodec.decode("""{"blur":-100,"immersiveSeconds":-1,"materialColorMix":-15,"tiledSwitchDwellSeconds":-2.0,"coverflowParallaxSensitivity":-1.0,"tiledParallaxSensitivity":0.05,"spatialLyricsSensitivity":-0.5,"spatialCoverSensitivity":0.0}""")
        assertEquals(-64, low.blur); assertEquals(0, low.immersiveSeconds); assertEquals(0, low.materialColorMix)
        assertEquals(0.1f, low.tiledSwitchDwellSeconds, 0.001f)
        assertEquals(0.2f, low.coverflowParallaxSensitivity, 0.001f)
        assertEquals(0.2f, low.tiledParallaxSensitivity, 0.001f)
        assertEquals(0.2f, low.spatialLyricsSensitivity, 0.001f)
        assertEquals(0.2f, low.spatialCoverSensitivity, 0.001f)
    }

    @Test fun parallaxSensitivityScalesTiltMonotonicallyAndRemainsBounded() {
        assertEquals(Offset.Zero, scaleSpatialParallaxTilt(Offset.Zero, 2.5f))
        val sample = Offset(0.35f, -0.25f)
        assertEquals(sample, scaleSpatialParallaxTilt(sample, 1.0f))
        val low = scaleSpatialParallaxTilt(sample, 0.2f)
        val high = scaleSpatialParallaxTilt(sample, 2.5f)
        assertTrue(low.x > 0f && low.x < sample.x)
        assertTrue(high.x > sample.x)
        assertTrue(low.y < 0f && low.y > sample.y)
        assertTrue(high.y < sample.y)
        val extreme = scaleSpatialParallaxTilt(Offset(1.0f, -1.0f), 2.5f)
        assertTrue(extreme.x <= 1.55f && extreme.y >= -1.55f)
    }

    @Test fun lyricScalePercentClampsToTenthsAndMultipliesSimultaneously() {
        val high = NowPlayingCodec.decode("""{"mainLyricScalePercent":250.4,"bgLyricScalePercent":123.46}""")
        assertEquals(200f, high.mainLyricScalePercent, 0f)
        assertEquals(123.5f, high.bgLyricScalePercent, 0.001f)
        val low = NowPlayingCodec.decode("""{"mainLyricScalePercent":-150.0,"bgLyricScalePercent":-0.04}""")
        assertEquals(-100f, low.mainLyricScalePercent, 0f)
        assertEquals(0f, low.bgLyricScalePercent, 0f)
        assertEquals("0.0%", formatLyricScalePercent(-0.04f))
        assertEquals("-100.0%", formatLyricScalePercent(-100f))
        assertEquals("200.0%", formatLyricScalePercent(200f))
        assertEquals(0f, lyricScaleMultiplier(-100f), 0.0001f)
        assertEquals(1f, lyricScaleMultiplier(0f), 0.0001f)
        assertEquals(1.5f, lyricScaleMultiplier(50f), 0.0001f)
        assertEquals(3f, lyricScaleMultiplier(200f), 0.0001f)
        assertEquals(1.8f, 1.2f * lyricScaleMultiplier(50f), 0.0001f)
    }

    @Test fun blurIsContinuousFromClearThroughDefaultToMaximumAndOnlyCorrectThemesExposeIt() {
        assertEquals(0f, NowPlayingConfig(blur = -64).blurRadius, 0f)
        assertEquals(48f, NowPlayingConfig(blur = 0).blurRadius, 0f)
        assertEquals(144f, NowPlayingConfig(blur = 96).blurRadius, 0f)
        val radii = (-64..96).map { NowPlayingConfig(blur = it).blurRadius }
        assertTrue(radii.zipWithNext().all { (a, b) -> a < b })
        for (theme in PlayerTheme.entries) assertEquals(theme == PlayerTheme.BLUR || theme == PlayerTheme.AURORA_BLUR,
            NowPlayingConfig(theme = theme).hasBlurControl)
        for (bg in MaterialPlayerBackground.entries) {
            val cfg = NowPlayingConfig(theme = PlayerTheme.MATERIAL, materialBackground = bg)
            val expectBlur = bg == MaterialPlayerBackground.BLUR || bg == MaterialPlayerBackground.AURORA_BLUR
            assertEquals(expectBlur, cfg.hasBlurControl)
            assertEquals(expectBlur, cfg.usesArtworkBackground)
        }
    }
}
