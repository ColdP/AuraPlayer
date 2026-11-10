package btm.m.auraplayer.ui.navigation

/** Shared recipe multipliers; 100 preserves the renderer's original per-style defaults. */
data class NavigationMaterial(
    val blur: Int = 100,
    val opacity: Int = 100,
    val refraction: Int = 100,
    val thickness: Int = 100,
    val highlight: Int = 100,
    val dispersion: Boolean? = null,
    val selectionOpacity: Int = 100,
    val pressBoost: Int = 100,
) {
    fun normalized() = copy(blur = blur.coerceIn(0, 300), opacity = opacity.coerceIn(0, 250),
        refraction = refraction.coerceIn(0, 200), thickness = thickness.coerceIn(0, 200),
        highlight = highlight.coerceIn(0, 200), selectionOpacity = selectionOpacity.coerceIn(0, 300), pressBoost = pressBoost.coerceIn(0, 200))
    fun alpha(original: Float): Float = (original * opacity / 100f).coerceIn(0f, 1f)
    fun light(original: Float): Float = (original * highlight / 100f).coerceIn(0f, 1f)
}

object NavigationMaterialSettings {
    const val PREFIX = "navigation_material_"
    const val KEY_FOLLOW = "navigation_material_use_global"
    const val KEY_MODULE = "${PREFIX}module_enabled"
    const val PREVIEW_FILE = "navigation_material/preview.jpg"
    val styles = listOf(NavigationStyle.HYPER_OS_FLOATING, NavigationStyle.LIQUID_GLASS, NavigationStyle.HARMONY_OS_FLOATING,  NavigationStyle.HYPER_OS)
    private val fields = listOf("blur", "opacity", "refraction", "thickness", "highlight", "dispersion", "selection_opacity", "press_boost")
    fun defaults(style: NavigationStyle): NavigationMaterial = when (style) {
        NavigationStyle.HYPER_OS_FLOATING -> NavigationMaterial(
            blur = 244, opacity = 42, refraction = 0, thickness = 0,
            highlight = 160, selectionOpacity = 169, dispersion = false,
        )
        NavigationStyle.HARMONY_OS_FLOATING -> NavigationMaterial(
            blur = 160, opacity = 110, refraction = 61, thickness = 140,
            highlight = 155, dispersion = true,
        )
        else -> NavigationMaterial()
    }
}
