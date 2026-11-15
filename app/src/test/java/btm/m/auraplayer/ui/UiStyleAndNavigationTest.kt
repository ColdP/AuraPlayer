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

    @Test
    fun disableAdvancedMaterialDefaultsToFalse() {
        val defaults = Appearance()
        assertFalse(defaults.disableAdvancedMaterial)
        assertTrue(defaults.copy(disableAdvancedMaterial = true).disableAdvancedMaterial)
    }

    @Test
    fun bottomChromeFollowsNavigationStyleRegardlessOfUiStyle() {
        assertEquals(AppUiStyle.HYPER, resolveBottomBarChromeUiStyle("hyper_os_floating"))
        assertEquals(AppUiStyle.HYPER, resolveBottomBarChromeUiStyle("harmony_os_floating"))
        assertEquals(AppUiStyle.HYPER, resolveBottomBarChromeUiStyle("liquid_glass"))
        assertEquals(AppUiStyle.MATERIAL, resolveBottomBarChromeUiStyle("material"))
        assertEquals(AppUiStyle.ONE, resolveBottomBarChromeUiStyle("one"))
    }

    @Test
    fun materialTabCentersIconWhenUnselectedAndAlignsVerticallyWhenSelected() {
        assertEquals(11f * 1.08f, MATERIAL_NAV_SELECTED_LABEL_SP, 0.0001f)

        val unselected = computeMaterialTabPlacement(
            slotWidth = 120,
            slotHeight = 60,
            iconWidth = 24,
            iconHeight = 24,
            labelWidth = 36,
            labelHeight = 14,
            gapPx = 4,
            slideOffsetPx = 8f,
            spatialReveal = 0f,
        )
        assertEquals((120 - 24) / 2, unselected.iconX)
        assertEquals((60 - 24) / 2, unselected.iconY)

        val selected = computeMaterialTabPlacement(
            slotWidth = 120,
            slotHeight = 60,
            iconWidth = 24,
            iconHeight = 24,
            labelWidth = 36,
            labelHeight = 14,
            gapPx = 4,
            slideOffsetPx = 8f,
            spatialReveal = 1f,
        )
        assertEquals((120 - (24 + 4 + 36)) / 2, selected.iconX)
        assertEquals(selected.iconX + 24 + 4, selected.labelX)
        assertEquals(60 / 2f, selected.iconY + 24 / 2f, 0.5f)
        assertEquals(60 / 2f, selected.labelY + 14 / 2f, 0.5f)
    }

    @Test
    fun solidTopBarHeightFactorIs12PercentShorterThanProgressiveFactor() {
        assertEquals(PROGRESSIVE_TOP_BAR_HEIGHT_FACTOR * 0.88f, SOLID_TOP_BAR_HEIGHT_FACTOR, 0.0001f)
    }
}
