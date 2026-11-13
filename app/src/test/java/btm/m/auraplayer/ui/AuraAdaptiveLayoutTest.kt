package btm.m.auraplayer.ui

import org.junit.Assert.*
import org.junit.Test

class AuraAdaptiveLayoutTest {
    @Test fun phoneAndLandscapeUseRetroColumnDefaults() {
        assertEquals(2, AuraLayoutInfo(393f, 873f).gridColumns)
        assertEquals(1, AuraLayoutInfo(393f, 873f).listColumns)
        assertEquals(4, AuraLayoutInfo(873f, 393f).gridColumns)
        assertEquals(2, AuraLayoutInfo(873f, 393f).listColumns)
    }

    @Test fun tabletUsesSmallestWidthAndNotOnlyWindowWidth() {
        assertFalse(AuraLayoutInfo(700f, 580f).tablet)
        assertTrue(AuraLayoutInfo(600f, 960f).tablet)
        assertEquals(4, AuraLayoutInfo(600f, 960f).gridColumns)
        assertEquals(6, AuraLayoutInfo(960f, 600f).gridColumns)
        assertEquals(3, AuraLayoutInfo(960f, 600f).listColumns)
    }

    @Test fun homeArtworkDoesNotGrowWithTabletWidth() {
        assertTrue(AuraLayoutInfo(360f, 800f).homeTileWidth.value in 112f..156f)
        assertEquals(156f, AuraLayoutInfo(1600f, 900f).homeTileWidth.value, .001f)
    }

    @Test fun bottomChromeHidesOnAboutAndReappearsOnDismissOrLegalSubpage() {
        assertFalse(shouldHideBottomChrome(emptyList(), -1))
        assertFalse(shouldHideBottomChrome(listOf("settings"), -1))
        assertTrue(shouldHideBottomChrome(listOf("settings", "about"), -1))
        // Starts animating back in as soon as About begins closing.
        assertFalse(shouldHideBottomChrome(listOf("settings", "about"), 1))
        // Legal pages opened from About restore chrome, and closing them returns cleanly to hidden state.
        assertFalse(shouldHideBottomChrome(listOf("settings", "about", "license"), -1))
        assertTrue(shouldHideBottomChrome(listOf("settings", "about", "license"), 2))
    }
}
