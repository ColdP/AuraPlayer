package btm.m.auraplayer.playback

import org.junit.Assert.*
import org.junit.Test

class NowPlayingSettingsTest {
    @Test fun oldOrCorruptPreferencesKeepExistingPlayerAppearance() {
        val expected = NowPlayingConfig()
        assertEquals(expected, NowPlayingCodec.decode("{}"))
        assertEquals(expected, NowPlayingCodec.decode("not json"))
        assertEquals(PlayerTheme.AURORA, expected.theme)
        assertTrue(expected.showVolume && expected.showAudioInfo && expected.swipeTracks)
        assertFalse(expected.immersiveLyrics || expected.keepLyricsAwake || expected.lyricHdr)
        assertTrue(expected.fullscreenGlass)
    }

    @Test fun everyThemeAnimationAndToggleSurvivesRestart() {
        for (theme in PlayerTheme.entries) for (animation in CoverAnimation.entries) for (weather in PlayerWeather.entries) {
            val config = NowPlayingConfig(theme, -32, weather, animation,
                showVolume = false, openFromNotification = false, keepLyricsAwake = true,
                lyricHdr = true, showAudioInfo = false, swipeTracks = false,
                immersiveLyrics = true, immersiveSeconds = 27, fullscreenGlass = false)
            assertEquals(config, NowPlayingCodec.decode(NowPlayingCodec.encode(config)))
        }
    }

    @Test fun unknownEnumsFallBackIndividuallyAndSlidersClampToRequestedRanges() {
        val config = NowPlayingCodec.decode("""{"theme":"future","weather":"future","coverAnimation":"future","blur":999,"immersiveSeconds":99,"showVolume":false}""")
        assertEquals(PlayerTheme.AURORA, config.theme)
        assertEquals(PlayerWeather.NONE, config.weather)
        assertEquals(CoverAnimation.DEFAULT, config.coverAnimation)
        assertEquals(96, config.blur); assertEquals(30, config.immersiveSeconds)
        assertFalse(config.showVolume)
        val low = NowPlayingCodec.decode("""{"blur":-100,"immersiveSeconds":-1}""")
        assertEquals(-64, low.blur); assertEquals(0, low.immersiveSeconds)
    }

    @Test fun blurIsContinuousFromClearThroughDefaultToMaximumAndOnlyCorrectThemesExposeIt() {
        assertEquals(0f, NowPlayingConfig(blur = -64).blurRadius, 0f)
        assertEquals(48f, NowPlayingConfig(blur = 0).blurRadius, 0f)
        assertEquals(144f, NowPlayingConfig(blur = 96).blurRadius, 0f)
        val radii = (-64..96).map { NowPlayingConfig(blur = it).blurRadius }
        assertTrue(radii.zipWithNext().all { (a, b) -> a < b })
        for (theme in PlayerTheme.entries) assertEquals(theme == PlayerTheme.BLUR || theme == PlayerTheme.AURORA_BLUR,
            NowPlayingConfig(theme = theme).hasBlurControl)
    }
}
