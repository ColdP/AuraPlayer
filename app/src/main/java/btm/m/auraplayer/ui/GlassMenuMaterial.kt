// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
