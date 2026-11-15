package btm.m.auraplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.blur.highlight.Highlight

/** All player menu surfaces share the same native MIUIX blur, Monet mixing and soft edge light. */
@Composable
internal fun Modifier.playerMenuMaterial(backdrop: Backdrop, tint: Color, radius: androidx.compose.ui.unit.Dp = 26.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    if (!btm.m.auraplayer.ui.LocalAdvancedMaterialEnabled.current) {
        val solidColor = if (tint.alpha >= .99f) tint else lerp(Color(0xFF222226), tint.copy(alpha = 1f), tint.alpha)
        return background(solidColor, shape)
    }
    val colors = BlurDefaults.blurColors(listOf(BlendColorEntry(tint.copy(alpha = .62f))), saturation = 1.12f)
    return drawBackdrop(backdrop, shape = { shape }, effects = {
        blur(7.dp.toPx()); blendColors(colors); noiseDither(.004f)
    }, highlight = { Highlight.GlassStrokeMiddleDark.copy(alpha = .32f) })
}

/** Draws a native MIUIX [Highlight] rim via its public [HighlightStyle.createShader] contract. */
internal fun DrawScope.drawMiuixHighlight(
    highlight: Highlight,
    shape: Shape,
    shaderCache: RuntimeShaderCache,
    paint: Paint,
) {
    if (highlight.width <= 0.dp || highlight.alpha <= 0f) return
    if (size.width <= 0f || size.height <= 0f) return
    val strokePx = minOf(highlight.width.toPx(), size.minDimension / 2f)
    val style = highlight.style
    val shader = with(style) { createShader(shape, strokePx, highlight.alpha, shaderCache) } ?: return
    paint.shader = shader
    paint.blendMode = style.blendMode
    drawContext.canvas.drawRect(0f, 0f, size.width, size.height, paint)
    paint.shader = null
}
