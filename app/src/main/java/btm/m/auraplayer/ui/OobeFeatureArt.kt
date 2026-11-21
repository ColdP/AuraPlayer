// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class OobeFeatureArtwork {
    SPATIAL_COVER,
    AURA_SING,
    LIVE_LYRICS_AI,
    PRO_DSP_USB,
    LOCAL_CLOUD,
    LIQUID_GLASS_UI,
    WIDGETS_REAR,
    TAG_LYRIC_SHARE,
}

@Composable
internal fun OobeFeatureAnimatedSvg(
    artwork: OobeFeatureArtwork,
    isDark: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val phase = if (animate) {
        val infinite = rememberInfiniteTransition(label = "oobeFeatureSvg_${artwork.name}")
        val value by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 7200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )
        value
    } else {
        0.16f
    }

    Canvas(modifier.fillMaxSize()) {
        when (artwork) {
            OobeFeatureArtwork.SPATIAL_COVER -> drawSpatialCoverSvg(phase, isDark)
            OobeFeatureArtwork.AURA_SING -> drawAuraSingSvg(phase, isDark)
            OobeFeatureArtwork.LIVE_LYRICS_AI -> drawLiveLyricsAiSvg(phase, isDark)
            OobeFeatureArtwork.PRO_DSP_USB -> drawProDspUsbSvg(phase, isDark)
            OobeFeatureArtwork.LOCAL_CLOUD -> drawLocalCloudSvg(phase, isDark)
            OobeFeatureArtwork.LIQUID_GLASS_UI -> drawLiquidGlassUiSvg(phase, isDark)
            OobeFeatureArtwork.WIDGETS_REAR -> drawWidgetsRearSvg(phase, isDark)
            OobeFeatureArtwork.TAG_LYRIC_SHARE -> drawTagLyricShareSvg(phase, isDark)
        }
    }
}

private const val TWO_PI = (PI * 2.0).toFloat()

private data class AppleMinimalPalette(
    val surfacePrimary: Color,
    val surfaceSecondary: Color,
    val surfaceTertiary: Color,
    val hairline: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val accentRose: Color,
    val accentBlue: Color,
    val accentIndigo: Color,
    val accentSage: Color,
    val accentSand: Color,
    val accentTeal: Color,
    val accentGraphite: Color,
    val accentPlum: Color,
)

private fun minimalPalette(isDark: Boolean): AppleMinimalPalette = if (isDark) {
    AppleMinimalPalette(
        surfacePrimary = Color(0xFF262629),
        surfaceSecondary = Color(0xFF1F1F22),
        surfaceTertiary = Color(0xFF333338),
        hairline = Color.White.copy(alpha = 0.14f),
        inkPrimary = Color(0xFFF2F2F7),
        inkSecondary = Color(0xFF98989F),
        accentRose = Color(0xFFC97884),
        accentBlue = Color(0xFF7096C6),
        accentIndigo = Color(0xFF8B88BD),
        accentSage = Color(0xFF6EA386),
        accentSand = Color(0xFFC49A6C),
        accentTeal = Color(0xFF6B9FA6),
        accentGraphite = Color(0xFF959DA8),
        accentPlum = Color(0xFFA8839D),
    )
} else {
    AppleMinimalPalette(
        surfacePrimary = Color(0xFFF7F7F9),
        surfaceSecondary = Color(0xFFECECF0),
        surfaceTertiary = Color(0xFFE2E2E8),
        hairline = Color(0xFF1C1C1E).copy(alpha = 0.10f),
        inkPrimary = Color(0xFF1C1C1E),
        inkSecondary = Color(0xFF8A8A8E),
        accentRose = Color(0xFFB86874),
        accentBlue = Color(0xFF567CB0),
        accentIndigo = Color(0xFF736FA8),
        accentSage = Color(0xFF568B6E),
        accentSand = Color(0xFFB08454),
        accentTeal = Color(0xFF52878E),
        accentGraphite = Color(0xFF6B727D),
        accentPlum = Color(0xFF8F6B84),
    )
}

/**
 * 1. Spatial Cover & Motion Visuals
 * Three solid minimalist squircle depth planes drifting gently in gyroscope parallax,
 * accented by crisp hairline orbit rings and a solid floating foreground emblem.
 */
private fun DrawScope.drawSpatialCoverSvg(phase: Float, isDark: Boolean) {
    val p = minimalPalette(isDark)
    val cx = size.width * 0.5f
    val cy = size.height * 0.52f
    val unit = minOf(size.width, size.height)
    val t = phase * TWO_PI
    val tiltX = sin(t)
    val tiltY = cos(t * 2f) * 0.45f

    // Subtle concentric architectural rings
    for (i in 0..1) {
        val r = unit * (0.31f + i * 0.10f)
        drawCircle(
            color = p.hairline.copy(alpha = if (i == 0) 0.16f else 0.09f),
            radius = r,
            center = Offset(cx + tiltX * unit * 0.012f * (i + 1), cy + tiltY * unit * 0.010f * (i + 1)),
            style = Stroke(width = unit * 0.0055f),
        )
    }

    val cardSize = unit * 0.43f
    val corner = CornerRadius(cardSize * 0.25f)

    // Back depth plane
    withTransform({
        translate(
            left = cx - cardSize * 0.5f - tiltX * unit * 0.06f,
            top = cy - cardSize * 0.5f + unit * 0.025f - tiltY * unit * 0.03f,
        )
        rotate(degrees = -7.5f + tiltX * 3.5f, pivot = Offset(cardSize * 0.5f, cardSize * 0.5f))
    }) {
        drawRoundRect(color = p.surfaceTertiary, size = Size(cardSize, cardSize), cornerRadius = corner)
        drawRoundRect(
            color = p.hairline,
            size = Size(cardSize, cardSize),
            cornerRadius = corner,
            style = Stroke(width = unit * 0.007f),
        )
    }

    // Middle album plane (clean muted rose-clay solid fill)
    withTransform({
        translate(
            left = cx - cardSize * 0.5f + tiltX * unit * 0.018f,
            top = cy - cardSize * 0.5f + tiltY * unit * 0.016f,
        )
        rotate(degrees = tiltX * 4f, pivot = Offset(cardSize * 0.5f, cardSize * 0.5f))
    }) {
        drawRoundRect(color = p.accentRose, size = Size(cardSize, cardSize), cornerRadius = corner)
        // Minimal subtle top-edge white rim
        drawRoundRect(
            color = Color.White.copy(alpha = if (isDark) 0.24f else 0.45f),
            size = Size(cardSize, cardSize),
            cornerRadius = corner,
            style = Stroke(width = unit * 0.009f),
        )
        // Subtle geometric vinyl groove ring inside the cover
        drawCircle(
            color = Color.White.copy(alpha = 0.22f),
            radius = cardSize * 0.34f,
            center = Offset(cardSize * 0.5f, cardSize * 0.5f),
            style = Stroke(width = unit * 0.007f),
        )
    }

    // Floating foreground emblem (pops forward in parallax)
    val fgCx = cx + tiltX * unit * 0.062f
    val fgCy = cy + tiltY * unit * 0.044f
    val badgeR = unit * 0.135f
    drawCircle(
        color = Color.Black.copy(alpha = if (isDark) 0.28f else 0.09f),
        radius = badgeR,
        center = Offset(fgCx, fgCy + unit * 0.022f),
    )
    drawCircle(
        color = if (isDark) Color(0xFFF2F2F7) else Color.White,
        radius = badgeR,
        center = Offset(fgCx, fgCy),
    )
    drawAppleMusicNote(
        center = Offset(fgCx, fgCy),
        scale = badgeR * 0.54f,
        color = p.accentRose,
    )
}

/**
 * 2. Aura Sing On-Device Vocal Isolation
 * Crisp Apple studio mic medallion flanked by two clean solid-stroke sine waves
 * smoothly separating out of phase.
 */
private fun DrawScope.drawAuraSingSvg(phase: Float, isDark: Boolean) {
    val p = minimalPalette(isDark)
    val cx = size.width * 0.5f
    val cy = size.height * 0.52f
    val unit = minOf(size.width, size.height)
    val t = phase * TWO_PI

    val waveWidth = size.width * 0.80f
    val startX = cx - waveWidth * 0.5f
    val steps = 52
    val separation = (0.5f + 0.5f * sin(t)) * unit * 0.068f

    fun buildWavePath(phaseShift: Float, ampFactor: Float, yBias: Float): Path = Path().apply {
        for (i in 0..steps) {
            val u = i.toFloat() / steps
            val x = startX + u * waveWidth
            val envelope = sin(u * PI.toFloat()).let { it * it }
            val y = cy + yBias + sin(u * TWO_PI * 2.2f - t * 2f + phaseShift) * unit * ampFactor * envelope
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }

    // Center reference baseline
    drawLine(
        color = p.hairline,
        start = Offset(startX, cy),
        end = Offset(startX + waveWidth, cy),
        strokeWidth = unit * 0.005f,
    )

    drawPath(
        path = buildWavePath(phaseShift = 0f, ampFactor = 0.11f, yBias = -separation),
        color = p.accentBlue.copy(alpha = 0.86f),
        style = Stroke(width = unit * 0.018f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = buildWavePath(phaseShift = 1.75f, ampFactor = 0.10f, yBias = separation),
        color = p.accentRose.copy(alpha = 0.86f),
        style = Stroke(width = unit * 0.018f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // Minimal symmetrical level pills flanking the microphone
    for (side in listOf(-1f, 1f)) {
        for (i in 0..2) {
            val dist = unit * (0.23f + i * 0.07f)
            val x = cx + side * dist
            val wave = 0.35f + 0.65f * ((sin(t * 2f - i * 0.9f + if (side > 0) 0.6f else 0f) + 1f) * 0.5f)
            val barH = unit * (0.065f + 0.11f * wave * (1f - i * 0.18f))
            val barW = unit * 0.022f
            drawRoundRect(
                color = if (side < 0) p.accentBlue.copy(alpha = 0.45f) else p.accentRose.copy(alpha = 0.45f),
                topLeft = Offset(x - barW * 0.5f, cy - barH * 0.5f),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW * 0.5f),
            )
        }
    }

    // Solid minimalist studio mic badge
    val emblemR = unit * 0.165f
    drawCircle(
        color = p.accentBlue.copy(alpha = if (isDark) 0.18f else 0.12f),
        radius = emblemR * (1.16f + 0.08f * sin(t * 2f)),
        center = Offset(cx, cy),
    )
    drawCircle(color = p.accentBlue, radius = emblemR, center = Offset(cx, cy))

    val micW = emblemR * 0.40f
    val micH = emblemR * 0.68f
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(cx - micW * 0.5f, cy - micH * 0.64f),
        size = Size(micW, micH),
        cornerRadius = CornerRadius(micW * 0.5f),
    )
    val arcR = emblemR * 0.38f
    drawArc(
        color = Color.White,
        startAngle = 14f,
        sweepAngle = 152f,
        useCenter = false,
        topLeft = Offset(cx - arcR, cy - arcR * 0.52f),
        size = Size(arcR * 2f, arcR * 1.80f),
        style = Stroke(width = unit * 0.015f, cap = StrokeCap.Round),
    )
    drawLine(
        color = Color.White,
        start = Offset(cx, cy + arcR * 1.25f),
        end = Offset(cx, cy + emblemR * 0.62f),
        strokeWidth = unit * 0.015f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = Color.White,
        start = Offset(cx - emblemR * 0.22f, cy + emblemR * 0.62f),
        end = Offset(cx + emblemR * 0.22f, cy + emblemR * 0.62f),
        strokeWidth = unit * 0.015f,
        cap = StrokeCap.Round,
    )
}

/**
 * 3. Word-by-Word Lyrics & AI Translation
 * Clean minimalist lyric sheet card with smooth solid syllable fill sweep
 * and a refined monochrome-accent translation sparkle.
 */
private fun DrawScope.drawLiveLyricsAiSvg(phase: Float, isDark: Boolean) {
    val p = minimalPalette(isDark)
    val cx = size.width * 0.5f
    val cy = size.height * 0.52f
    val unit = minOf(size.width, size.height)
    val t = phase * TWO_PI

    val frameW = unit * 0.78f
    val frameH = unit * 0.52f
    val frameLeft = cx - frameW * 0.5f
    val frameTop = cy - frameH * 0.5f
    val frameCorner = CornerRadius(unit * 0.10f)

    drawRoundRect(
        color = p.surfacePrimary,
        topLeft = Offset(frameLeft, frameTop),
        size = Size(frameW, frameH),
        cornerRadius = frameCorner,
    )
    drawRoundRect(
        color = p.hairline,
        topLeft = Offset(frameLeft, frameTop),
        size = Size(frameW, frameH),
        cornerRadius = frameCorner,
        style = Stroke(width = unit * 0.007f),
    )

    val padX = frameLeft + frameW * 0.11f
    val lineMaxW = frameW * 0.78f
    val dimBar = p.inkSecondary.copy(alpha = if (isDark) 0.28f else 0.22f)

    // Previous lyric line
    val line1H = unit * 0.042f
    val line1Y = frameTop + frameH * 0.20f
    drawRoundRect(
        color = dimBar,
        topLeft = Offset(padX, line1Y),
        size = Size(lineMaxW * 0.60f, line1H),
        cornerRadius = CornerRadius(line1H * 0.5f),
    )

    // Active word-by-word lyric line with smooth solid fill wipe
    val line2H = unit * 0.064f
    val line2Y = frameTop + frameH * 0.43f - sin(t * 2f) * unit * 0.006f
    val line2W = lineMaxW * 0.90f
    val line2Rect = Rect(padX, line2Y, padX + line2W, line2Y + line2H)
    val line2Clip = Path().apply { addRoundRect(RoundRect(line2Rect, CornerRadius(line2H * 0.5f))) }

    drawRoundRect(
        color = dimBar,
        topLeft = Offset(padX, line2Y),
        size = Size(line2W, line2H),
        cornerRadius = CornerRadius(line2H * 0.5f),
    )
    val sweepFraction = ((phase * 1.25f) % 1f).coerceIn(0f, 1f)
    val activeFillW = (line2W * (0.14f + 0.86f * sweepFraction)).coerceAtMost(line2W)
    clipPath(line2Clip) {
        drawRoundRect(
            color = p.accentIndigo,
            topLeft = Offset(padX, line2Y),
            size = Size(activeFillW, line2H),
            cornerRadius = CornerRadius(line2H * 0.5f),
        )
    }

    // Translated subtitle line softly breathing
    val line3H = unit * 0.036f
    val line3Y = frameTop + frameH * 0.69f
    val translationAlpha = 0.45f + 0.35f * ((sin(t * 2f - 0.8f) + 1f) * 0.5f)
    drawRoundRect(
        color = p.accentIndigo.copy(alpha = translationAlpha),
        topLeft = Offset(padX, line3Y),
        size = Size(lineMaxW * 0.70f, line3H),
        cornerRadius = CornerRadius(line3H * 0.5f),
    )

    // Subtle crisp 4-point sparkle in top-right corner
    val starCenter = Offset(frameLeft + frameW * 0.87f, frameTop + frameH * 0.16f)
    val starScale = unit * (0.068f + 0.008f * sin(t * 2f))
    rotate(degrees = sin(t) * 10f, pivot = starCenter) {
        drawFourPointSparkle(center = starCenter, radius = starScale, color = p.accentIndigo)
    }
}

/**
 * 4. Pro DSP Equalizer & Exclusive USB DAC
 * Dieter Rams / Apple Audio Units style precision parametric EQ curve
 * with clean solid stroke and crisp hardware fader caps.
 */
private fun DrawScope.drawProDspUsbSvg(phase: Float, isDark: Boolean) {
    val p = minimalPalette(isDark)
    val cx = size.width * 0.5f
    val cy = size.height * 0.52f
    val unit = minOf(size.width, size.height)
    val t = phase * TWO_PI

    val gridW = unit * 0.78f
    val gridH = unit * 0.46f
    val left = cx - gridW * 0.5f
    val top = cy - gridH * 0.5f
    val bottom = top + gridH

    // Precision horizontal grid lines
    for (row in 0..2) {
        val y = top + gridH * (row / 2f)
        drawLine(
            color = p.hairline,
            start = Offset(left, y),
            end = Offset(left + gridW, y),
            strokeWidth = unit * 0.005f,
        )
    }

    // 5 parametric bands
    val bandCount = 5
    val points = List(bandCount) { i ->
        val u = i.toFloat() / (bandCount - 1)
        val x = left + gridW * (0.08f + 0.84f * u)
        val wave = sin(t + i * 1.15f) * 0.32f + cos(t * 2f - i * 0.65f) * 0.12f
        val y = cy - wave * gridH * 0.72f
        Offset(x, y)
    }

    // Vertical fader tracks
    points.forEach { pt ->
        drawLine(
            color = p.surfaceTertiary,
            start = Offset(pt.x, top + unit * 0.015f),
            end = Offset(pt.x, bottom - unit * 0.015f),
            strokeWidth = unit * 0.011f,
            cap = StrokeCap.Round,
        )
    }

    // Smooth cubic Bezier curve + subtle flat tint beneath
    val curvePath = Path().apply {
        moveTo(left, cy + sin(t) * gridH * 0.06f)
        lineTo(points.first().x, points.first().y)
        for (i in 0 until points.lastIndex) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val midX = (p0.x + p1.x) * 0.5f
            cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
        }
        lineTo(left + gridW, cy - sin(t) * gridH * 0.06f)
    }

    val fillPath = Path().apply {
        addPath(curvePath)
        lineTo(left + gridW, bottom)
        lineTo(left, bottom)
        close()
    }

    drawPath(path = fillPath, color = p.accentSage.copy(alpha = if (isDark) 0.12f else 0.09f))
    drawPath(
        path = curvePath,
        color = p.accentSage,
        style = Stroke(width = unit * 0.020f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // Crisp hardware knob handles
    points.forEach { pt ->
        drawCircle(color = if (isDark) Color(0xFF1C1C1E) else Color.White, radius = unit * 0.032f, center = pt)
        drawCircle(
            color = p.accentSage,
            radius = unit * 0.032f,
            center = pt,
            style = Stroke(width = unit * 0.012f),
        )
    }
}

/**
 * 5. Unified Local & Cloud Library
 * Clean minimalist hardware-and-cloud schematic connected by a crisp orbital hairline.
 */
private fun DrawScope.drawLocalCloudSvg(phase: Float, isDark: Boolean) {
    val p = minimalPalette(isDark)
    val cx = size.width * 0.5f
    val cy = size.height * 0.52f
    val unit = minOf(size.width, size.height)
    val t = phase * TWO_PI

    // Subtle hairline connecting ellipse
    val orbitW = unit * 0.66f
    val orbitH = unit * 0.30f
    val orbitRect = Rect(cx - orbitW * 0.5f, cy - orbitH * 0.5f, cx + orbitW * 0.5f, cy + orbitH * 0.5f)
    drawOval(
        color = p.hairline,
        topLeft = orbitRect.topLeft,
        size = orbitRect.size,
        style = Stroke(
            width = unit * 0.008f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(unit * 0.024f, unit * 0.020f), 0f),
        ),
    )

    // Two clean orbiting sync dots
    for (i in 0..1) {
        val angle = t + i * PI.toFloat()
        val bx = cx + cos(angle) * (orbitW * 0.5f)
        val by = cy + sin(angle) * (orbitH * 0.5f)
        drawCircle(
            color = if (i == 0) p.accentSand else p.accentBlue,
            radius = unit * 0.020f,
            center = Offset(bx, by),
        )
    }

    // Left node: Local library rounded tile
    val leftBob = sin(t) * unit * 0.018f
    val tileSize = unit * 0.28f
    val localCenter = Offset(cx - unit * 0.20f, cy + leftBob)
    val localTopLeft = Offset(localCenter.x - tileSize * 0.5f, localCenter.y - tileSize * 0.5f)
    val tileCorner = CornerRadius(tileSize * 0.27f)

    drawRoundRect(
        color = p.accentSand,
        topLeft = localTopLeft,
        size = Size(tileSize, tileSize),
        cornerRadius = tileCorner,
    )
    drawAppleMusicNote(center = localCenter, scale = tileSize * 0.26f, color = Color.White)

    // Right node: Minimal solid cloud glyph
    val rightBob = -sin(t) * unit * 0.018f
    drawAppleCloudGlyph(
        center = Offset(cx + unit * 0.20f, cy + rightBob),
        scale = unit * 0.195f,
        color = p.accentBlue,
    )
}

/**
 * 6. Multi-Style Interface & Liquid Glass Material
 * Clean minimalist window frame with three subtle overlapping UI style cards
 * and a floating pill dock sliding smoothly between tabs.
 */
private fun DrawScope.drawLiquidGlassUiSvg(phase: Float, isDark: Boolean) {
    val p = minimalPalette(isDark)
    val cx = size.width * 0.5f
    val cy = size.height * 0.52f
    val unit = minOf(size.width, size.height)
    val t = phase * TWO_PI

    // Outer sleek viewport frame
    val frameW = unit * 0.72f
    val frameH = unit * 0.56f
    val left = cx - frameW * 0.5f
    val top = cy - frameH * 0.5f
    val frameCorner = CornerRadius(unit * 0.11f)

    drawRoundRect(
        color = p.surfaceSecondary,
        topLeft = Offset(left, top),
        size = Size(frameW, frameH),
        cornerRadius = frameCorner,
    )
    drawRoundRect(
        color = p.hairline,
        topLeft = Offset(left, top),
        size = Size(frameW, frameH),
        cornerRadius = frameCorner,
        style = Stroke(width = unit * 0.007f),
    )

    // Three minimalist UI style cards (Hyper / Material / One) subtly breathing
    val cardW = frameW * 0.25f
    val cardH = frameH * 0.42f
    val gap = frameW * 0.05f
    val rowStartX = cx - (cardW * 3f + gap * 2f) * 0.5f
    val baseCardY = top + frameH * 0.16f
    val cardColors = listOf(p.accentTeal, p.surfaceTertiary, p.surfaceTertiary)
    val activeIdx = ((phase * 3f).toInt() % 3)

    for (i in 0..2) {
        val x = rowStartX + i * (cardW + gap)
        val lift = if (i == activeIdx) -unit * 0.018f else 0f
        val fill = if (i == activeIdx) p.accentTeal else p.surfacePrimary
        val cardCorner = CornerRadius(
            when (i) {
                0 -> unit * 0.055f // Hyper rounded
                1 -> unit * 0.075f // Material expressive
                else -> unit * 0.038f // One UI crisp
            },
        )
        drawRoundRect(
            color = fill,
            topLeft = Offset(x, baseCardY + lift),
            size = Size(cardW, cardH),
            cornerRadius = cardCorner,
        )
        drawRoundRect(
            color = if (i == activeIdx) Color.White.copy(alpha = 0.25f) else p.hairline,
            topLeft = Offset(x, baseCardY + lift),
            size = Size(cardW, cardH),
            cornerRadius = cardCorner,
            style = Stroke(width = unit * 0.006f),
        )
        // Tiny minimalist title bar inside each tile
        val lineColor = if (i == activeIdx) Color.White.copy(alpha = 0.78f) else p.inkSecondary.copy(alpha = 0.35f)
        drawRoundRect(
            color = lineColor,
            topLeft = Offset(x + cardW * 0.20f, baseCardY + lift + cardH * 0.66f),
            size = Size(cardW * 0.60f, unit * 0.018f),
            cornerRadius = CornerRadius(unit * 0.009f),
        )
    }

    // Floating liquid-glass pill navigation dock at bottom of frame
    val dockW = frameW * 0.62f
    val dockH = unit * 0.115f
    val dockLeft = cx - dockW * 0.5f
    val dockTop = top + frameH * 0.71f
    drawRoundRect(
        color = p.surfacePrimary,
        topLeft = Offset(dockLeft, dockTop),
        size = Size(dockW, dockH),
        cornerRadius = CornerRadius(dockH * 0.5f),
    )
    drawRoundRect(
        color = Color.White.copy(alpha = if (isDark) 0.22f else 0.80f),
        topLeft = Offset(dockLeft, dockTop),
        size = Size(dockW, dockH),
        cornerRadius = CornerRadius(dockH * 0.5f),
        style = Stroke(width = unit * 0.007f),
    )

    // Sliding active capsule indicator inside dock
    val pillW = dockW * 0.32f
    val pillH = dockH * 0.72f
    val slideU = (sin(t) + 1f) * 0.5f
    val pillX = dockLeft + dockW * 0.05f + slideU * (dockW * 0.90f - pillW)
    drawRoundRect(
        color = p.accentTeal.copy(alpha = if (isDark) 0.32f else 0.20f),
        topLeft = Offset(pillX, dockTop + (dockH - pillH) * 0.5f),
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(pillH * 0.5f),
    )
}

/**
 * 7. Rear Screen & Home Widgets
 * Minimalist 2x2 desktop widget card paired with a compact rear-display playback module
 * and gentle monochrome acoustic bars.
 */
private fun DrawScope.drawWidgetsRearSvg(phase: Float, isDark: Boolean) {
    val p = minimalPalette(isDark)
    val cx = size.width * 0.5f
    val cy = size.height * 0.52f
    val unit = minOf(size.width, size.height)
    val t = phase * TWO_PI

    // Left: 2x2 Widget square card
    val wSize = unit * 0.42f
    val wLeft = cx - unit * 0.39f
    val wTop = cy - wSize * 0.5f + sin(t) * unit * 0.012f
    val wCorner = CornerRadius(unit * 0.085f)

    drawRoundRect(
        color = p.surfacePrimary,
        topLeft = Offset(wLeft, wTop),
        size = Size(wSize, wSize),
        cornerRadius = wCorner,
    )
    drawRoundRect(
        color = p.hairline,
        topLeft = Offset(wLeft, wTop),
        size = Size(wSize, wSize),
        cornerRadius = wCorner,
        style = Stroke(width = unit * 0.007f),
    )

    // Album square inside widget
    val artSize = wSize * 0.42f
    val artPad = wSize * 0.12f
    drawRoundRect(
        color = p.accentGraphite,
        topLeft = Offset(wLeft + artPad, wTop + artPad),
        size = Size(artSize, artSize),
        cornerRadius = CornerRadius(artSize * 0.24f),
    )
    // Minimal play badge in widget top-right
    val playCx = wLeft + wSize - artPad - unit * 0.045f
    val playCy = wTop + artPad + artSize * 0.5f
    drawCircle(color = p.accentGraphite.copy(alpha = 0.20f), radius = unit * 0.050f, center = Offset(playCx, playCy))
    val tri = Path().apply {
        val r = unit * 0.022f
        moveTo(playCx - r * 0.55f, playCy - r * 0.85f)
        lineTo(playCx + r * 0.95f, playCy)
        lineTo(playCx - r * 0.55f, playCy + r * 0.85f)
        close()
    }
    drawPath(tri, color = p.inkPrimary)

    // Two track info bars inside widget
    val textY = wTop + artPad + artSize + wSize * 0.11f
    drawRoundRect(
        color = p.inkPrimary.copy(alpha = 0.72f),
        topLeft = Offset(wLeft + artPad, textY),
        size = Size(wSize * 0.62f, unit * 0.026f),
        cornerRadius = CornerRadius(unit * 0.013f),
    )
    drawRoundRect(
        color = p.inkSecondary.copy(alpha = 0.35f),
        topLeft = Offset(wLeft + artPad, textY + unit * 0.044f),
        size = Size(wSize * 0.42f, unit * 0.020f),
        cornerRadius = CornerRadius(unit * 0.010f),
    )

    // Right: Compact rear display dark module
    val rW = unit * 0.30f
    val rH = unit * 0.36f
    val rLeft = cx + unit * 0.10f
    val rTop = cy - rH * 0.5f - sin(t) * unit * 0.012f
    val rCorner = CornerRadius(unit * 0.075f)
    drawRoundRect(
        color = if (isDark) Color(0xFF121214) else Color(0xFF232327),
        topLeft = Offset(rLeft, rTop),
        size = Size(rW, rH),
        cornerRadius = rCorner,
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.22f),
        topLeft = Offset(rLeft, rTop),
        size = Size(rW, rH),
        cornerRadius = rCorner,
        style = Stroke(width = unit * 0.008f),
    )

    // Subtle animated equalizer bars inside rear screen
    val eqCount = 4
    val eqSpacing = rW * 0.16f
    val eqStartX = rLeft + rW * 0.5f - (eqCount - 1) * eqSpacing * 0.5f
    val eqCy = rTop + rH * 0.46f
    for (i in 0 until eqCount) {
        val hFactor = 0.35f + 0.65f * ((sin(t * 2f + i * 1.05f) + 1f) * 0.5f)
        val barH = rH * (0.14f + 0.22f * hFactor)
        val barW = unit * 0.020f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.88f),
            topLeft = Offset(eqStartX + i * eqSpacing - barW * 0.5f, eqCy - barH * 0.5f),
            size = Size(barW, barH),
            cornerRadius = CornerRadius(barW * 0.5f),
        )
    }
    // Mini progress bar at bottom of rear screen
    val progW = rW * 0.64f
    val progX = rLeft + (rW - progW) * 0.5f
    val progY = rTop + rH * 0.78f
    drawRoundRect(
        color = Color.White.copy(alpha = 0.20f),
        topLeft = Offset(progX, progY),
        size = Size(progW, unit * 0.014f),
        cornerRadius = CornerRadius(unit * 0.007f),
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.85f),
        topLeft = Offset(progX, progY),
        size = Size(progW * (0.25f + 0.55f * ((sin(t) + 1f) * 0.5f)), unit * 0.014f),
        cornerRadius = CornerRadius(unit * 0.007f),
    )
}

/**
 * 8. Lyric Share Cards & Lossless Tag Editor
 * Crisp minimalist poster card gently tilting beside a floating metadata tag pill.
 */
private fun DrawScope.drawTagLyricShareSvg(phase: Float, isDark: Boolean) {
    val p = minimalPalette(isDark)
    val cx = size.width * 0.5f
    val cy = size.height * 0.52f
    val unit = minOf(size.width, size.height)
    val t = phase * TWO_PI

    val cardW = unit * 0.44f
    val cardH = unit * 0.56f
    val corner = CornerRadius(unit * 0.075f)

    // Back stacked template sheet
    withTransform({
        translate(left = cx - cardW * 0.5f - unit * 0.06f, top = cy - cardH * 0.5f + unit * 0.015f)
        rotate(degrees = -7f + sin(t) * 2f, pivot = Offset(cardW * 0.5f, cardH * 0.5f))
    }) {
        drawRoundRect(color = p.surfaceTertiary, size = Size(cardW, cardH), cornerRadius = corner)
        drawRoundRect(
            color = p.hairline,
            size = Size(cardW, cardH),
            cornerRadius = corner,
            style = Stroke(width = unit * 0.006f),
        )
    }

    // Front minimalist lyric share card
    withTransform({
        translate(left = cx - cardW * 0.5f - unit * 0.01f, top = cy - cardH * 0.5f)
        rotate(degrees = 2.5f + sin(t) * 2.5f, pivot = Offset(cardW * 0.5f, cardH * 0.5f))
    }) {
        drawRoundRect(color = p.surfacePrimary, size = Size(cardW, cardH), cornerRadius = corner)
        drawRoundRect(
            color = p.hairline,
            size = Size(cardW, cardH),
            cornerRadius = corner,
            style = Stroke(width = unit * 0.007f),
        )

        val pad = cardW * 0.13f
        val artH = cardW - pad * 2f
        drawRoundRect(
            color = p.accentPlum,
            topLeft = Offset(pad, pad),
            size = Size(artH, artH * 0.72f),
            cornerRadius = CornerRadius(unit * 0.040f),
        )

        // Lyric lines on the card
        val lineY0 = pad + artH * 0.72f + cardH * 0.09f
        drawRoundRect(
            color = p.inkPrimary.copy(alpha = 0.78f),
            topLeft = Offset(pad, lineY0),
            size = Size(artH * 0.92f, unit * 0.024f),
            cornerRadius = CornerRadius(unit * 0.012f),
        )
        drawRoundRect(
            color = p.inkSecondary.copy(alpha = 0.45f),
            topLeft = Offset(pad, lineY0 + unit * 0.044f),
            size = Size(artH * 0.68f, unit * 0.022f),
            cornerRadius = CornerRadius(unit * 0.011f),
        )
        drawRoundRect(
            color = p.inkSecondary.copy(alpha = 0.28f),
            topLeft = Offset(pad, lineY0 + unit * 0.084f),
            size = Size(artH * 0.48f, unit * 0.018f),
            cornerRadius = CornerRadius(unit * 0.009f),
        )
    }

    // Floating audio tag pill badge (clean solid capsule)
    val pillW = unit * 0.25f
    val pillH = unit * 0.105f
    val pillX = cx + unit * 0.12f
    val pillY = cy - unit * 0.22f + cos(t) * unit * 0.018f
    drawRoundRect(
        color = p.accentPlum,
        topLeft = Offset(pillX, pillY),
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(pillH * 0.5f),
    )
    // Minimal tag hole dot + bar inside pill
    drawCircle(
        color = Color.White,
        radius = unit * 0.016f,
        center = Offset(pillX + pillH * 0.48f, pillY + pillH * 0.5f),
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.88f),
        topLeft = Offset(pillX + pillH * 0.82f, pillY + pillH * 0.5f - unit * 0.011f),
        size = Size(pillW - pillH * 1.15f, unit * 0.022f),
        cornerRadius = CornerRadius(unit * 0.011f),
    )
}

private fun DrawScope.drawAppleMusicNote(center: Offset, scale: Float, color: Color) {
    val leftX = center.x - scale * 0.55f
    val rightX = center.x + scale * 0.55f
    val topLeftY = center.y - scale * 0.62f
    val topRightY = center.y - scale * 0.82f
    val bottomLeftY = center.y + scale * 0.58f
    val bottomRightY = center.y + scale * 0.42f
    val strokeW = scale * 0.22f

    val beam = Path().apply {
        moveTo(leftX + strokeW * 0.2f, topLeftY)
        lineTo(rightX + strokeW * 0.2f, topRightY)
        lineTo(rightX + strokeW * 0.2f, topRightY + scale * 0.34f)
        lineTo(leftX + strokeW * 0.2f, topLeftY + scale * 0.34f)
        close()
    }
    drawPath(beam, color = color)

    drawLine(
        color = color,
        start = Offset(leftX + strokeW * 0.45f, topLeftY + scale * 0.12f),
        end = Offset(leftX + strokeW * 0.45f, bottomLeftY),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(rightX + strokeW * 0.05f, topRightY + scale * 0.12f),
        end = Offset(rightX + strokeW * 0.05f, bottomRightY),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
    )

    drawOval(
        color = color,
        topLeft = Offset(leftX - scale * 0.32f, bottomLeftY - scale * 0.24f),
        size = Size(scale * 0.66f, scale * 0.48f),
    )
    drawOval(
        color = color,
        topLeft = Offset(rightX - scale * 0.54f, bottomRightY - scale * 0.24f),
        size = Size(scale * 0.66f, scale * 0.48f),
    )
}

private fun DrawScope.drawFourPointSparkle(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x, center.y, center.x + radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + radius)
        quadraticTo(center.x, center.y, center.x - radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - radius)
        close()
    }
    drawPath(path, color = color)
}

private fun DrawScope.drawAppleCloudGlyph(center: Offset, scale: Float, color: Color) {
    val baseW = scale * 1.56f
    val baseH = scale * 0.70f
    val baseTop = center.y - scale * 0.02f
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - baseW * 0.5f, baseTop),
        size = Size(baseW, baseH),
        cornerRadius = CornerRadius(baseH * 0.5f),
    )
    drawCircle(
        color = color,
        radius = scale * 0.46f,
        center = Offset(center.x - scale * 0.28f, center.y - scale * 0.14f),
    )
    drawCircle(
        color = color,
        radius = scale * 0.56f,
        center = Offset(center.x + scale * 0.16f, center.y - scale * 0.24f),
    )
}
