// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
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
    val material = rememberModuleNavigationMaterial(style)
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
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
