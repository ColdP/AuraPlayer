// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.ui.navigation.DampedDragAnimation
import btm.m.auraplayer.ui.navigation.InteractiveHighlight
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.*
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.abs

/** Shared toolbar material and touch/drag behavior, also used by the morphing lyric menu. */
@Composable
internal fun InteractiveToolbarGlass(
    modifier: Modifier,
    backdrop: Backdrop?,
    tint: Color,
    shape: Shape = CircleShape,
    glassAlpha: Float = 1f,
    opticalStrength: Float = 1f,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val animationScope = rememberCoroutineScope()
    val drag = remember(animationScope) {
        DampedDragAnimation(animationScope, 0f, -1f..1f, .001f, 1f, 1.08f,
            onDragStopped = { animateToValue(0f) },
            onDrag = { size, amount -> updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f)) })
    }
    val highlight = remember(animationScope) {
        InteractiveHighlight(animationScope) { size, offset ->
            Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height))
        }
    }
    Box(modifier.then(highlight.gestureModifier).then(drag.modifier)
        .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = glassAlpha }
            .then(if (backdrop == null) Modifier.background(tint, shape) else Modifier.drawBackdrop(
                backdrop = backdrop, shape = { shape },
                effects = {
                    vibrancy(); blur(TOOLBAR_GLASS_BLUR_RADIUS.toPx())
                    lens((16.dp + 4.dp * drag.pressProgress).toPx(),
                        (32.dp + 8.dp * drag.pressProgress).toPx() * opticalStrength,
                        depthEffect = true, chromaticAberration = opticalStrength >= .75f)
                },
                highlight = { toolbarGlassHighlight(drag.pressProgress).let { it.copy(alpha = it.alpha * opticalStrength) } },
                shadow = { Shadow.Default.copy(radius = 5.dp, color = Color.Black, alpha = .24f) },
                innerShadow = null,
                layerBlock = {
                    scaleX = drag.scaleX; scaleY = drag.scaleY
                    val velocity = abs(drag.velocity.coerceIn(-1f, 1f))
                    scaleX *= 1f + velocity * .12f; scaleY /= 1f + velocity * .08f
                    translationX = drag.value * 5.dp.toPx()
                }, onDrawSurface = { drawRect(tint) },
            )).then(highlight.modifier))
        content()
    }
}
