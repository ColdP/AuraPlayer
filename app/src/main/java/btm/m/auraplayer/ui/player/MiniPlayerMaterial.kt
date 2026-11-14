// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.ui.DEFAULT_MATERIAL_EXPRESSIVE_SEED
import btm.m.auraplayer.ui.LocalAuraExpressiveScheme
import btm.m.auraplayer.ui.rememberAuraExpressiveScheme
import btm.m.auraplayer.ui.rememberModuleNavigationMaterial
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One recipe for the real mini player and the material settings preview. */
@Composable
internal fun Modifier.miniPlayerMaterial(backdrop: Backdrop, style: String, shape: Shape, expansion: Float = 0f): Modifier {
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
    if (style == "material" || btm.m.auraplayer.ui.LocalAppUiStyle.current == btm.m.auraplayer.ui.AppUiStyle.MATERIAL) {
        val expressive = LocalAuraExpressiveScheme.current ?: rememberAuraExpressiveScheme(DEFAULT_MATERIAL_EXPRESSIVE_SEED, dark)
        val fade = (1f - expansion).coerceIn(0f, 1f)
        val container = expressive.surfaceContainerHigh
        return this
            .shadow(
                elevation = (6 * fade).dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = (if (dark) .45f else .18f) * fade),
                spotColor = Color.Black.copy(alpha = (if (dark) .45f else .18f) * fade),
            )
            .background(container, shape)
            .border(BorderStroke(1.dp, expressive.outlineVariant.copy(alpha = (if (dark) .32f else .5f) * fade)), shape)
    }
    if (style == "one" || btm.m.auraplayer.ui.LocalAppUiStyle.current == btm.m.auraplayer.ui.AppUiStyle.ONE) {
        val onePalette = btm.m.auraplayer.ui.rememberActiveOneUiPalette()
        val fade = (1f - expansion).coerceIn(0f, 1f)
        val container = Color(onePalette.card)
        return this
            .shadow(
                elevation = (6 * fade).dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = (if (dark) .48f else .14f) * fade),
                spotColor = Color.Black.copy(alpha = (if (dark) .48f else .14f) * fade),
            )
            .background(container, shape)
            .border(BorderStroke(0.75.dp, Color(onePalette.divider).copy(alpha = fade)), shape)
    }
    val material = rememberModuleNavigationMaterial(style)
    val liquid = style == "liquid_glass"
    val harmony = style == "harmony_os_floating"
    val tint = if (liquid) (if (dark) Color(0xFF121212) else Color(0xFFFAFAFA)).copy(alpha = .4f)
        else if (dark) Color.Black.copy(alpha = if (harmony) .36f else .30f)
        else Color(0xFFF5F5F5).copy(alpha = if (harmony) .48f else .42f)
    return drawBackdrop(backdrop = backdrop, shape = { shape }, effects = {
        vibrancy()
        blur(playerMiniBlurRadius(style, material.blur).toPx())
        if (liquid) lens(24.dp.toPx() * material.thickness / 100f, 24.dp.toPx() * material.refraction / 100f, chromaticAberration = material.dispersion ?: false)
        else lens(16.dp.toPx() * material.thickness / 100f, 32.dp.toPx() * material.refraction / 100f, depthEffect = true, chromaticAberration = material.dispersion ?: false)
    }, highlight = { Highlight.Default.copy(alpha = material.light((if (dark) .78f else .96f) * if (harmony) .72f else 1f) * (1f - expansion)) },
        shadow = { Shadow.Default.copy(radius = 6.dp, alpha = .35f * (1f - expansion)) },
        onDrawSurface = { drawRect(tint.copy(alpha = material.alpha(tint.alpha))) })
}
