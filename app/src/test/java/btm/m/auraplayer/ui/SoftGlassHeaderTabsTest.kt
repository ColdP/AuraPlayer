package btm.m.auraplayer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.ui.navigation.NavigationMaterial
import btm.m.auraplayer.ui.navigation.NavigationMaterialSettings
import btm.m.auraplayer.ui.navigation.NavigationStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftGlassHeaderTabsTest {
    @Test
    fun headerTabsUseMonetPrimaryAndContainerTokensInLightAndDarkThemes() {
        val light = resolveSoftGlassHeaderTabsColors(
            surface = Color(0xFFF8FAF4),
            surfaceContainer = Color(0xFFEEF2E9),
            primary = Color(0xFF2E6C00),
            primaryContainer = Color(0xFFB7F397),
            onSurface = Color(0xFF1A1C18),
        )
        assertEquals(Color(0xFF2E6C00), light.selectedContent)
        assertNotEquals(Color(0xFFEEF2E9), light.surfaceTint)
        assertTrue(light.selectedContainer.green > light.selectedContainer.red)
        assertEquals(0.38f, light.selectedContainer.alpha, .005f)
        assertEquals(0.72f, light.unselectedContent.alpha, .005f)

        val dark = resolveSoftGlassHeaderTabsColors(
            surface = Color(0xFF11140E),
            surfaceContainer = Color(0xFF1D211A),
            primary = Color(0xFF9CD67D),
            primaryContainer = Color(0xFF205107),
            onSurface = Color(0xFFE2E3DC),
        )
        assertEquals(Color(0xFF9CD67D), dark.selectedContent)
        assertNotEquals(Color(0xFF1D211A), dark.surfaceTint)
        assertTrue(dark.selectedContainer.green > dark.selectedContainer.blue)
        assertEquals(0.46f, dark.selectedContainer.alpha, .005f)
    }

    @Test
    fun searchGlassSpecFollowsNavigationMaterialParametersAcrossStyles() {
        val hyper = resolveFloatingGlassMaterialSpec(
            NavigationStyle.HYPER_OS_FLOATING.preferenceValue,
            NavigationMaterialSettings.defaults(NavigationStyle.HYPER_OS_FLOATING),
            dark = false,
        )
        assertEquals((18f * .24f * 2.44f), hyper.blurRadius.value, .01f)
        assertEquals(0.dp, hyper.refractionAmount)
        assertEquals(0.dp, hyper.refractionHeight)
        assertTrue(hyper.depthEffect)
        assertFalse(hyper.chromaticAberration)
        assertEquals(.42f * .42f, hyper.surfaceAlpha, .001f)

        val harmony = resolveFloatingGlassMaterialSpec(
            NavigationStyle.HARMONY_OS_FLOATING.preferenceValue,
            NavigationMaterialSettings.defaults(NavigationStyle.HARMONY_OS_FLOATING),
            dark = true,
        )
        assertEquals((18f * .29f * 1.60f), harmony.blurRadius.value, .01f)
        assertEquals(32f * .61f, harmony.refractionAmount.value, .01f)
        assertEquals(16f * 1.40f, harmony.refractionHeight.value, .01f)
        assertTrue(harmony.chromaticAberration)
        assertEquals(.36f * 1.10f, harmony.surfaceAlpha, .001f)

        val customLiquid = resolveFloatingGlassMaterialSpec(
            NavigationStyle.LIQUID_GLASS.preferenceValue,
            NavigationMaterial(blur = 150, opacity = 125, refraction = 80, thickness = 110, highlight = 50, dispersion = false),
            dark = false,
        )
        assertEquals(4.5f, customLiquid.blurRadius.value, .01f)
        assertEquals(24f * .80f, customLiquid.refractionAmount.value, .01f)
        assertEquals(24f * 1.10f, customLiquid.refractionHeight.value, .01f)
        assertFalse(customLiquid.depthEffect)
        assertFalse(customLiquid.chromaticAberration)
        assertEquals(.50f, customLiquid.surfaceAlpha, .001f)
    }
}
