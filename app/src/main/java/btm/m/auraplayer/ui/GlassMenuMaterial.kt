// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

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
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.*
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One material implementation for flat and cascading dropdown surfaces. */
@Composable
internal fun Modifier.glassMenuSurface(backdrop: Backdrop?, shape: Shape): Modifier {
    when (LocalAppUiStyle.current) {
        AppUiStyle.MATERIAL -> {
            val scheme = rememberActiveExpressiveScheme()
            val container = if (scheme.dark) scheme.surfaceContainerHigh else scheme.surfaceContainerLow
            return shadow(12.dp, shape, clip = false)
                .background(container, shape)
                .border(BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = if (scheme.dark) .28f else .18f)), shape)
        }
        AppUiStyle.ONE -> {
            val palette = rememberActiveOneUiPalette()
            return shadow(10.dp, shape, clip = false)
                .background(Color(palette.card), shape)
                .border(BorderStroke(0.75.dp, Color(palette.divider)), shape)
        }
        AppUiStyle.HYPER -> Unit
    }
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val light = surface.luminance() > .5f
    val tint = (if (light) Color(0xFFF8F8F8) else surface).copy(alpha = .80f)
    return if (backdrop == null) background(tint, shape) else drawBackdrop(
        backdrop = backdrop, shape = { shape },
        effects = { vibrancy(); blur(4.dp.toPx()); lens(10.dp.toPx(), 28.dp.toPx(), chromaticAberration = true) },
        highlight = { Highlight.Default.copy(alpha = .70f) },
        shadow = { Shadow.Default.copy(radius = 12.dp, color = if (light) Color.Gray else Color.Black, alpha = .35f) },
        innerShadow = null, onDrawSurface = { drawRect(tint) },
    )
}
