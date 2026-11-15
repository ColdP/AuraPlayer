// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.*
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One material implementation for flat and cascading dropdown surfaces. */
@Composable
internal fun Modifier.glassMenuSurface(backdrop: Backdrop?, shape: Shape): Modifier {
    val uiStyle = LocalAppUiStyle.current
    if (uiStyle == AppUiStyle.MATERIAL) {
        val scheme = rememberActiveExpressiveScheme()
        val container = if (scheme.dark) scheme.surfaceContainerHigh else scheme.surfaceContainerLow
        return shadow(12.dp, shape, clip = false)
            .background(container, shape)
            .border(BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = if (scheme.dark) .28f else .18f)), shape)
    }
    val isOne = uiStyle == AppUiStyle.ONE
    val onePalette = if (isOne) rememberActiveOneUiPalette() else null
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val light = if (onePalette != null) !onePalette.dark else surface.luminance() > .5f
    val tint = if (onePalette != null) {
        Color(onePalette.card).copy(alpha = .82f)
    } else {
        (if (light) Color(0xFFF8F8F8) else surface).copy(alpha = .80f)
    }
    val blurRadius = if (isOne) 14.dp else 4.dp
    val lensHeight = if (isOne) 8.dp else 10.dp
    val lensAmount = if (isOne) 4.dp else 28.dp
    val chromatic = !isOne
    val highlightAlpha = .70f
    val advancedMaterial = LocalAdvancedMaterialEnabled.current && top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported()
    if (!advancedMaterial) {
        val solidTint = tint.copy(alpha = 1f)
        return shadow(10.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = if (light) .16f else .35f), spotColor = Color.Black.copy(alpha = if (light) .16f else .35f))
            .clip(shape)
            .background(solidTint, shape)
    }
    return if (backdrop == null) background(tint, shape) else drawBackdrop(
        backdrop = backdrop, shape = { shape },
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
            lens(lensHeight.toPx(), lensAmount.toPx(), chromaticAberration = chromatic)
        },
        highlight = { Highlight.Default.copy(alpha = highlightAlpha) },
        shadow = { Shadow.Default.copy(radius = 12.dp, color = if (light) Color.Gray else Color.Black, alpha = .35f) },
        innerShadow = null, onDrawSurface = { drawRect(tint) },
    )
}
