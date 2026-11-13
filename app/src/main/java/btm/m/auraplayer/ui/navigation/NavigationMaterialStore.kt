// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from HyperChanger NavigationMaterialSettings for Aura's local preferences.
package btm.m.auraplayer.ui.navigation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal class NavigationMaterialStore(context: Context) {
    private val prefs = context.getSharedPreferences("aura.navigation-material.v1", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(NavigationMaterialCodec.decode(prefs.getString("materials", "{}").orEmpty()))
    val state = mutable.asStateFlow()

    fun update(style: NavigationStyle, change: (NavigationMaterial) -> NavigationMaterial) {
        require(style in NavigationMaterialSettings.styles)
        val value = mutable.value[style] ?: NavigationMaterialSettings.defaults(style)
        mutable.value = mutable.value + (style to change(value).normalized())
    }

    fun save() {
        prefs.edit().putString("materials", NavigationMaterialCodec.encode(mutable.value)).apply()
    }

    fun reset(style: NavigationStyle) {
        update(style) { NavigationMaterialSettings.defaults(style) }
        save()
    }
}

internal object NavigationMaterialCodec {
    fun decode(raw: String): Map<NavigationStyle, NavigationMaterial> {
        val json = runCatching { JSONObject(raw) }.getOrNull()
        return NavigationMaterialSettings.styles.associateWith { style ->
            val defaults = NavigationMaterialSettings.defaults(style)
            val item = json?.optJSONObject(style.preferenceValue) ?: return@associateWith defaults
            defaults.copy(
                blur = item.optInt("blur", defaults.blur),
                opacity = item.optInt("opacity", defaults.opacity),
                refraction = item.optInt("refraction", defaults.refraction),
                thickness = item.optInt("thickness", defaults.thickness),
                highlight = item.optInt("highlight", defaults.highlight),
                dispersion = if (!item.has("dispersion") || item.isNull("dispersion")) defaults.dispersion
                    else item.optBoolean("dispersion", defaults.dispersion ?: false),
                selectionOpacity = item.optInt("selectionOpacity", defaults.selectionOpacity),
                pressBoost = item.optInt("pressBoost", defaults.pressBoost),
            ).normalized()
        }
    }

    fun encode(materials: Map<NavigationStyle, NavigationMaterial>): String = JSONObject().apply {
        for (style in NavigationMaterialSettings.styles) {
            val material = (materials[style] ?: NavigationMaterialSettings.defaults(style)).normalized()
            put(style.preferenceValue, JSONObject().apply {
                put("blur", material.blur); put("opacity", material.opacity)
                put("refraction", material.refraction); put("thickness", material.thickness)
                put("highlight", material.highlight)
                put("dispersion", material.dispersion ?: JSONObject.NULL)
                put("selectionOpacity", material.selectionOpacity); put("pressBoost", material.pressBoost)
            })
        }
    }.toString()
}
