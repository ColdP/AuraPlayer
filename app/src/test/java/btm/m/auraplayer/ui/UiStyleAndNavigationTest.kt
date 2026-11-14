package btm.m.auraplayer.ui

import btm.m.auraplayer.data.Appearance
import btm.m.auraplayer.data.AuraSettings
import btm.m.auraplayer.ui.navigation.NavigationMaterialSettings
import btm.m.auraplayer.ui.navigation.NavigationStyle
import dev.pascal.oneuikit.MonetTones
import dev.pascal.oneuikit.OneUiWidgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStyleAndNavigationTest {
    @Test
    fun appearanceDefaultsToHyperUiStyle() {
        assertEquals("hyper", Appearance().uiStyle)
    }

    @Test
    fun uiStyleNormalizationAcceptsHyperMaterialAndOne() {
        assertEquals("hyper", AuraSettings.normalizeUiStyle("hyper"))
        assertEquals("material", AuraSettings.normalizeUiStyle("material"))
        assertEquals("one", AuraSettings.normalizeUiStyle("one"))
        assertEquals("hyper", AuraSettings.normalizeUiStyle("unknown"))
        assertEquals("hyper", AuraSettings.normalizeUiStyle(null))
    }

    @Test
    fun navigationStyleParsesMaterialAndOneAndExcludesMaterialTuning() {
        assertEquals(NavigationStyle.MATERIAL, NavigationStyle.fromPreference("material"))
        assertEquals("material", NavigationStyle.MATERIAL.preferenceValue)
        assertFalse(NavigationStyle.MATERIAL in NavigationMaterialSettings.styles)

        assertEquals(NavigationStyle.ONE, NavigationStyle.fromPreference("one"))
        assertEquals("one", NavigationStyle.ONE.preferenceValue)
        assertFalse(NavigationStyle.ONE in NavigationMaterialSettings.styles)
    }

    @Test
    fun oneUiMonetTonesPreserveLightDarkSurfaceContrastAndStepRules() {
        val seed = 0xFF0381FE.toInt()
        val light = MonetTones.fromSeed(seed, dark = false)
        val dark = MonetTones.fromSeed(seed, dark = true)

        assertEquals(seed, light.accent)
        assertEquals(seed, dark.accent)
        assertTrue(MonetTones.luma(light.background) > 0.85)
        assertTrue(MonetTones.luma(light.card) > MonetTones.luma(light.background))
        assertTrue(MonetTones.luma(light.text) < 0.1)

        assertTrue(MonetTones.luma(dark.background) < 0.05)
        assertTrue(MonetTones.luma(dark.card) > MonetTones.luma(dark.background))
        assertTrue(MonetTones.luma(dark.text) > 0.85)

        assertEquals(1, OneUiWidgets.stepFor(10))
        assertEquals(2, OneUiWidgets.stepFor(20))
        assertEquals(5, OneUiWidgets.stepFor(100))
    }
}
