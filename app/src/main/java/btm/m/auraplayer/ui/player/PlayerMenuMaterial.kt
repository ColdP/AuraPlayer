package btm.m.auraplayer.ui.player

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.blur.highlight.Highlight

/** All player menu surfaces share the same native MIUIX blur, Monet mixing and soft edge light. */
@Composable
internal fun Modifier.playerMenuMaterial(backdrop: Backdrop, tint: Color, radius: androidx.compose.ui.unit.Dp = 26.dp): Modifier {
    val colors = BlurDefaults.blurColors(listOf(BlendColorEntry(tint.copy(alpha = .62f))), saturation = 1.12f)
    return drawBackdrop(backdrop, shape = { RoundedCornerShape(radius) }, effects = {
        blur(7.dp.toPx()); blendColors(colors); noiseDither(.004f)
    }, highlight = { Highlight.GlassStrokeMiddleDark.copy(alpha = .32f) })
}
