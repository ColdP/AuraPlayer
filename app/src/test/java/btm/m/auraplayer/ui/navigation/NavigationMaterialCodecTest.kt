package btm.m.auraplayer.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationMaterialCodecTest {
    private val styles = NavigationMaterialSettings.styles
    private val defaults = styles.associateWith(NavigationMaterialSettings::defaults)

    @Test fun missingAndCorruptStoragePreserveExistingAuraRecipes() {
        for (raw in listOf("", "broken", "[]", "{}", "null")) {
            assertEquals(defaults, NavigationMaterialCodec.decode(raw))
        }
        assertEquals(244, defaults.getValue(NavigationStyle.HYPER_OS_FLOATING).blur)
        assertEquals(42, defaults.getValue(NavigationStyle.HYPER_OS_FLOATING).opacity)
        assertEquals(160, defaults.getValue(NavigationStyle.HARMONY_OS_FLOATING).blur)
        assertEquals(null, defaults.getValue(NavigationStyle.LIQUID_GLASS).dispersion)
    }

    @Test fun roundTripPreservesEveryFieldIndependentlyForEachStyle() {
        val materials = styles.mapIndexed { i, style ->
            style to NavigationMaterial(blur = 30 + i, opacity = 50 + i, refraction = 70 + i,
                thickness = 90 + i, highlight = 110 + i, dispersion = i % 2 == 0,
                selectionOpacity = 130 + i, pressBoost = 150 + i)
        }.toMap()
        assertEquals(materials, NavigationMaterialCodec.decode(NavigationMaterialCodec.encode(materials)))
    }

    @Test fun partialRecipeKeepsMissingFieldsAndOtherStylesAtDefaults() {
        val decoded = NavigationMaterialCodec.decode("""{"harmony_os_floating":{"blur":25}}""")
        assertEquals(defaults.getValue(NavigationStyle.HARMONY_OS_FLOATING).copy(blur = 25), decoded[NavigationStyle.HARMONY_OS_FLOATING])
        assertEquals(defaults[NavigationStyle.HYPER_OS_FLOATING], decoded[NavigationStyle.HYPER_OS_FLOATING])
        assertEquals(defaults[NavigationStyle.LIQUID_GLASS], decoded[NavigationStyle.LIQUID_GLASS])
    }

    @Test fun untrustedImportedValuesStayWithinRendererBounds() {
        val decoded = NavigationMaterialCodec.decode("""{"liquid_glass":{"blur":9999,"opacity":-42,"refraction":9999,"thickness":-1,"highlight":9999,"selectionOpacity":9999,"pressBoost":-1}}""")
        assertEquals(NavigationMaterial(blur = 300, opacity = 0, refraction = 200,
            thickness = 0, highlight = 200, selectionOpacity = 300, pressBoost = 0), decoded[NavigationStyle.LIQUID_GLASS])
    }

    @Test fun defaultRoundTripRetainsNullableDispersionAndResetIsolation() {
        assertEquals(defaults, NavigationMaterialCodec.decode(NavigationMaterialCodec.encode(defaults)))
        val custom = defaults.mapValues { (_, value) -> value.copy(blur = 0) }
        val reset = custom + (NavigationStyle.HYPER_OS_FLOATING to NavigationMaterialSettings.defaults(NavigationStyle.HYPER_OS_FLOATING))
        val decoded = NavigationMaterialCodec.decode(NavigationMaterialCodec.encode(reset))
        assertEquals(defaults[NavigationStyle.HYPER_OS_FLOATING], decoded[NavigationStyle.HYPER_OS_FLOATING])
        assertEquals(0, decoded.getValue(NavigationStyle.HARMONY_OS_FLOATING).blur)
        assertEquals(0, decoded.getValue(NavigationStyle.LIQUID_GLASS).blur)
    }
}
