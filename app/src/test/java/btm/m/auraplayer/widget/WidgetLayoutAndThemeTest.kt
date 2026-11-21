package btm.m.auraplayer.widget

import androidx.media3.common.Player
import btm.m.auraplayer.playback.MaterialPlayerBackground
import btm.m.auraplayer.playback.NowPlayingConfig
import btm.m.auraplayer.playback.PlayerColorTheme
import btm.m.auraplayer.playback.PlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutAndThemeTest {
    @Test
    fun selectsAllFivePlayerLayoutTiersAccurately() {
        assertEquals(WidgetLayoutTier.SIZE_2X2, selectPlayerWidgetLayoutTier(160f, 160f))
        assertEquals(WidgetLayoutTier.SIZE_2X2, selectPlayerWidgetLayoutTier(190f, 185f))
        assertEquals(WidgetLayoutTier.SIZE_4X1, selectPlayerWidgetLayoutTier(320f, 88f))
        assertEquals(WidgetLayoutTier.SIZE_4X1, selectPlayerWidgetLayoutTier(270f, 108f))
        assertEquals(WidgetLayoutTier.SIZE_4X2, selectPlayerWidgetLayoutTier(320f, 165f))
        assertEquals(WidgetLayoutTier.SIZE_4X2, selectPlayerWidgetLayoutTier(280f, 220f))
        assertEquals(WidgetLayoutTier.SIZE_4X3, selectPlayerWidgetLayoutTier(320f, 255f))
        assertEquals(WidgetLayoutTier.SIZE_4X4, selectPlayerWidgetLayoutTier(320f, 340f))
        assertEquals(WidgetLayoutTier.SIZE_4X4, selectPlayerWidgetLayoutTier(360f, 390f))
    }

    @Test
    fun resolvesUpcomingIndicesRespectingRepeatAndQueueLength() {
        assertEquals(emptyList<Int>(), resolveSequentialUpcomingIndices(0, 0, Player.REPEAT_MODE_ALL))
        assertEquals(emptyList<Int>(), resolveSequentialUpcomingIndices(0, 1, Player.REPEAT_MODE_ALL))
        assertEquals(listOf(1), resolveSequentialUpcomingIndices(0, 2, Player.REPEAT_MODE_ALL))
        assertEquals(listOf(0), resolveSequentialUpcomingIndices(1, 2, Player.REPEAT_MODE_ALL))
        assertEquals(listOf(1, 2), resolveSequentialUpcomingIndices(0, 5, Player.REPEAT_MODE_OFF))
        assertEquals(listOf(4), resolveSequentialUpcomingIndices(3, 5, Player.REPEAT_MODE_OFF))
        assertEquals(emptyList<Int>(), resolveSequentialUpcomingIndices(4, 5, Player.REPEAT_MODE_OFF))
        assertEquals(listOf(4, 0), resolveSequentialUpcomingIndices(3, 5, Player.REPEAT_MODE_ALL))
        assertEquals(listOf(0, 1), resolveSequentialUpcomingIndices(4, 5, Player.REPEAT_MODE_ALL))
    }

    @Test
    fun resolvesDistinctThemeTokensForAllNowPlayingThemes() {
        val seed = 0xFF4B9BAA.toInt()
        for (theme in PlayerTheme.entries) {
            val config = NowPlayingConfig(theme = theme)
            val paused = WidgetThemeEngine.resolveThemeColors(config, appDark = false, seedArgb = seed, isPlaying = false)
            val playing = WidgetThemeEngine.resolveThemeColors(config, appDark = false, seedArgb = seed, isPlaying = true)
            assertNotEquals(0, paused.titleColor)
            if (theme == PlayerTheme.MATERIAL) {
                assertTrue(paused.usesButtonContainers)
                assertNotEquals(0, paused.playBgColor)
                assertFalse(paused.isDarkSurface)
                assertTrue(paused.playUsesSquircleWhenPaused)
                assertNotEquals(paused.playBgColor, playing.playBgColor)
                val blurMaterial = WidgetThemeEngine.resolveThemeColors(
                    NowPlayingConfig(theme = PlayerTheme.MATERIAL, materialBackground = MaterialPlayerBackground.BLUR)
                        .withColorTheme(PlayerColorTheme.DARK, PlayerTheme.MATERIAL),
                    appDark = false,
                    seedArgb = seed,
                    isPlaying = false,
                )
                assertTrue(blurMaterial.isDarkSurface)
            } else if (theme == PlayerTheme.ONE) {
                assertTrue(paused.usesButtonContainers)
                assertNotEquals(0, paused.playBgColor)
                assertFalse(paused.isDarkSurface)
                val darkOne = WidgetThemeEngine.resolveThemeColors(config, appDark = true, seedArgb = seed, isPlaying = false)
                assertTrue(darkOne.isDarkSurface)
                assertNotEquals(paused.titleColor, darkOne.titleColor)
            } else {
                assertFalse(paused.usesButtonContainers)
                assertEquals(0, paused.playBgColor)
                assertFalse(paused.isDarkSurface)
                val darkTheme = WidgetThemeEngine.resolveThemeColors(config, appDark = true, seedArgb = seed, isPlaying = false)
                assertTrue(darkTheme.isDarkSurface)
                assertNotEquals(paused.titleColor, darkTheme.titleColor)
            }
        }
    }
}
