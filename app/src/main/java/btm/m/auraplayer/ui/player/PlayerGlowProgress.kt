// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.audio.OutputWaveform
import btm.m.auraplayer.playback.GlowProgressStyle
import com.materialkolor.palettes.CorePalette
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Derives a rich saturated accent color from artwork Monet palette for glow progress bars. */
internal fun vibrantCoverGlowColor(palette: CorePalette): Color {
    val raw = Color(palette.a1.tone(62))
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(raw.toArgb(), hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.58f)
    hsv[2] = hsv[2].coerceIn(0.76f, 0.96f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

internal fun Modifier.playerScrubGesture(
    enabled: Boolean,
    displayedValue: Float,
    label: String,
    onDragStateChange: (Boolean) -> Unit,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
): Modifier = semantics {
    contentDescription = label
    progressBarRangeInfo = ProgressBarRangeInfo(displayedValue.coerceIn(0f, 1f), 0f..1f)
    if (enabled) {
        setProgress { target ->
            val clamped = target.coerceIn(0f, 1f)
            onChange(clamped)
            onFinished()
            true
        }
    } else {
        disabled()
    }
}.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        onDragStateChange(true)
        onChange((down.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
        var pressed = true
        while (pressed) {
            val event = awaitPointerEvent()
            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
            pointer.consume()
            onChange((pointer.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
            pressed = pointer.pressed
        }
        onDragStateChange(false)
        onFinished()
    }
}

/**
 * Renders the user-selected glowing progress bar (`HYPER` capsule bloom or `ONE` live wave + glowing ring thumb)
 * for any Now Playing theme.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun GlowProgressSlider(
    style: GlowProgressStyle,
    value: Float,
    playing: Boolean,
    label: String,
    accentColor: Color,
    trackColor: Color,
    isLightSurface: Boolean,
    waveform: OutputWaveform? = null,
    active: Boolean = true,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    enabled: Boolean = true,
) {
    val onChangeState by rememberUpdatedState(onChange)
    val onFinishedState by rememberUpdatedState(onFinished)
    val valueState by rememberUpdatedState(value.coerceIn(0f, 1f))
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var phasePrimary by remember { mutableFloatStateOf(0f) }
    var phaseSecondary by remember { mutableFloatStateOf(0f) }
    val smoothedBands = remember { FloatArray(OutputWaveform.SPECTRUM_BANDS) }
    var hasSpectrum by remember { mutableStateOf(false) }
    var avgEnergy by remember { mutableFloatStateOf(0f) }

    DisposableEffect(waveform, style, active) {
        waveform?.spectrumEnabled = (style == GlowProgressStyle.ONE && active)
        onDispose { waveform?.spectrumEnabled = false }
    }

    val animateWave = style == GlowProgressStyle.ONE && active && playing && !dragging && enabled
    LaunchedEffect(style, active, playing, dragging, enabled, waveform) {
        if (style != GlowProgressStyle.ONE || !active) {
            smoothedBands.fill(0f)
            hasSpectrum = false
            avgEnergy = 0f
            return@LaunchedEffect
        }
        val tau = 2f * PI.toFloat()
        var lastNanos = 0L
        while (isActive) {
            var shouldContinue = animateWave
            withFrameNanos { nanos ->
                val dt = if (lastNanos != 0L) ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f) else 0f
                lastNanos = nanos
                val snapshot = waveform?.snapshot
                val fresh = animateWave &&
                    snapshot != null &&
                    snapshot.spectrum.size == smoothedBands.size &&
                    (System.nanoTime() - snapshot.updatedNanos) < 280_000_000L
                hasSpectrum = fresh
                var sum = 0f
                var maxBand = 0f
                for (i in smoothedBands.indices) {
                    val target = if (fresh) snapshot.spectrum[i] else 0f
                    val rate = if (target > smoothedBands[i]) 0.30f else 0.12f
                    smoothedBands[i] += (target - smoothedBands[i]) * rate
                    if (smoothedBands[i] < 0.001f && target == 0f) smoothedBands[i] = 0f
                    sum += smoothedBands[i]
                    if (smoothedBands[i] > maxBand) maxBand = smoothedBands[i]
                }
                val mean = sum / smoothedBands.size
                avgEnergy = mean
                if (animateWave) {
                    val flowSpeed = if (fresh) (1.35f + mean * 1.45f) else 1.75f
                    phasePrimary = (phasePrimary + dt * flowSpeed) % tau
                    phaseSecondary = (phaseSecondary + dt * flowSpeed * 1.65f) % tau
                } else {
                    shouldContinue = maxBand > 0.002f
                }
            }
            if (!shouldContinue) break
        }
    }

    val oneWaveScale by animateFloatAsState(
        targetValue = if (style == GlowProgressStyle.ONE && playing && enabled) 1f else 0.14f,
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "oneGlowWaveScale",
    )
    val hyperThickness by animateDpAsState(
        targetValue = if (dragging) 8.5.dp else 6.5.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "hyperGlowThickness",
    )
    val oneThumbRadius by animateDpAsState(
        targetValue = if (dragging) 8.5.dp else 7.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "oneGlowThumbR",
    )

    val displayed = if (dragging) dragFraction else valueState

    Canvas(
        modifier = modifier.playerScrubGesture(
            enabled = enabled,
            displayedValue = displayed,
            label = label,
            onDragStateChange = { isDown ->
                if (isDown && !dragging) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                dragging = isDown
            },
            onChange = { next ->
                dragFraction = next
                onChangeState(next)
            },
            onFinished = onFinishedState,
        ),
    ) {
        when (style) {
            GlowProgressStyle.HYPER -> drawHyperGlowProgress(
                fraction = displayed,
                dragging = dragging,
                thicknessPx = hyperThickness.toPx().coerceAtMost(size.height),
                accentColor = accentColor,
                trackColor = trackColor,
                isLightSurface = isLightSurface,
            )
            GlowProgressStyle.ONE -> drawOneGlowProgress(
                fraction = displayed,
                phasePrimary = phasePrimary,
                phaseSecondary = phaseSecondary,
                spectrum = smoothedBands,
                hasSpectrum = hasSpectrum,
                avgEnergy = avgEnergy,
                waveScale = oneWaveScale,
                thumbRadiusPx = oneThumbRadius.toPx(),
                accentColor = accentColor,
                trackColor = trackColor,
                isLightSurface = isLightSurface,
            )
            GlowProgressStyle.OFF -> Unit
        }
    }
}

private fun DrawScope.drawHyperGlowProgress(
    fraction: Float,
    dragging: Boolean,
    thicknessPx: Float,
    accentColor: Color,
    trackColor: Color,
    isLightSurface: Boolean,
) {
    val cy = size.height / 2f
    val top = cy - thicknessPx / 2f
    val corner = CornerRadius(thicknessPx / 2f, thicknessPx / 2f)

    drawRoundRect(
        color = trackColor,
        topLeft = Offset(0f, top),
        size = Size(size.width, thicknessPx),
        cornerRadius = corner,
    )

    val rawFill = (size.width * fraction).coerceIn(0f, size.width)
    if (rawFill <= 0.5f) return

    val fillWidth = max(rawFill, thicknessPx).coerceAtMost(size.width)
    val headX = (rawFill - thicknessPx * 0.25f).coerceIn(thicknessPx * 0.5f, size.width)
    val expandY = (if (dragging) 1.55.dp else 1.1.dp).toPx()
    val maxBlurExtent = (cy - thicknessPx / 2f - expandY - 0.5.dp.toPx()).coerceAtLeast(1f)
    val blurPx = (if (dragging) 7.4.dp else 5.9.dp).toPx().coerceAtMost(maxBlurExtent * 0.72f)

    val auraStart = if (isLightSurface) accentColor.copy(alpha = 0.28f) else lerp(Color.White, accentColor, 0.35f).copy(alpha = 0.22f)
    val auraEnd = if (isLightSurface) accentColor.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.72f)

    drawIntoCanvas { canvas ->
        val glowPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            shader = LinearGradient(
                0f, cy, fillWidth.coerceAtLeast(1f), cy,
                intArrayOf(auraStart.toArgb(), auraEnd.toArgb()),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.nativeCanvas.drawRoundRect(
            0f,
            top - expandY,
            fillWidth,
            top + thicknessPx + expandY,
            (thicknessPx + expandY * 2f) / 2f,
            (thicknessPx + expandY * 2f) / 2f,
            glowPaint,
        )
    }

    val haloRadius = (if (dragging) 22.dp else 18.5.dp).toPx()
    val maxVerticalScale = ((cy - 0.5.dp.toPx()).coerceAtLeast(1f) / haloRadius).coerceIn(0.2f, 1f)
    val haloScaleY = minOf(0.616f, maxVerticalScale)
    val haloColor = if (isLightSurface) accentColor else Color.White
    withTransform({
        translate(headX, cy)
        scale(1.45f, haloScaleY, pivot = Offset.Zero)
    }) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    haloColor.copy(alpha = if (dragging) 0.72f else 0.58f),
                    lerp(haloColor, accentColor, 0.45f).copy(alpha = 0.20f),
                    Color.Transparent,
                ),
                center = Offset.Zero,
                radius = haloRadius,
            ),
            radius = haloRadius,
            center = Offset.Zero,
        )
    }

    val coreStart = if (isLightSurface) lerp(accentColor, Color.White, 0.16f) else lerp(Color.White, accentColor, 0.18f).copy(alpha = 0.86f)
    val coreEnd = if (isLightSurface) lerp(accentColor, Color.White, 0.62f) else Color.White
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(coreStart, coreEnd),
            startX = 0f,
            endX = fillWidth.coerceAtLeast(1f),
        ),
        topLeft = Offset(0f, top),
        size = Size(fillWidth, thicknessPx),
        cornerRadius = corner,
    )
}

private fun DrawScope.drawOneGlowProgress(
    fraction: Float,
    phasePrimary: Float,
    phaseSecondary: Float,
    spectrum: FloatArray,
    hasSpectrum: Boolean,
    avgEnergy: Float,
    waveScale: Float,
    thumbRadiusPx: Float,
    accentColor: Color,
    trackColor: Color,
    isLightSurface: Boolean,
) {
    val trackStroke = 4.5.dp.toPx().coerceAtMost(size.height * 0.3f)
    val trackY = (size.height * 0.72f).coerceIn(thumbRadiusPx + 2.dp.toPx(), (size.height - thumbRadiusPx).coerceAtLeast(size.height * 0.5f))
    val minX = thumbRadiusPx
    val maxX = (size.width - thumbRadiusPx).coerceAtLeast(minX)
    val thumbX = minX + (maxX - minX) * fraction.coerceIn(0f, 1f)

    // Inactive baseline track from thumb to right edge.
    drawLine(
        color = trackColor,
        start = Offset(thumbX, trackY),
        end = Offset(size.width, trackY),
        strokeWidth = trackStroke,
        cap = StrokeCap.Round,
    )

    // Glowing hill/wave silhouette driven by live post-DSP spectrum above the active track.
    val waveStart = 1.5.dp.toPx()
    val waveEnd = (thumbX - thumbRadiusPx * 0.5f).coerceAtLeast(waveStart)
    val span = waveEnd - waveStart
    val maxWaveH = (trackY - 2.5.dp.toPx()).coerceAtMost(21.6.dp.toPx()).coerceAtLeast(6.dp.toPx())
    val lenFade = (span / 32.dp.toPx()).coerceIn(0f, 1f)

    if (span > 4.dp.toPx() && lenFade > 0.01f) {
        val stepPx = 2.dp.toPx()
        val steps = ceil(span / stepPx).toInt().coerceAtLeast(16)
        val crestPath = Path()
        val fillPath = Path()
        fillPath.moveTo(waveStart, trackY)

        val bandLast = (spectrum.size - 1).coerceAtLeast(1)
        for (i in 0..steps) {
            val u = i.toFloat() / steps
            val x = waveStart + span * u
            val edge = sin(PI * u).toFloat().coerceAtLeast(0f).pow(0.85f)

            val bandPos = u * bandLast
            val idx0 = bandPos.toInt().coerceIn(0, bandLast)
            val idx1 = (idx0 + 1).coerceIn(0, bandLast)
            val frac = bandPos - idx0
            val cosWeight = (1f - cos(frac * PI.toFloat())) * 0.5f
            val bandLevel = spectrum[idx0] + (spectrum[idx1] - spectrum[idx0]) * cosWeight

            val w1 = 0.5f + 0.5f * sin(u * 2f * PI.toFloat() - phasePrimary)
            val w2 = 0.5f + 0.5f * sin(u * 4f * PI.toFloat() - phaseSecondary + 0.8f)
            val flowMotion = 0.65f * w1 + 0.35f * w2

            val dome = if (hasSpectrum) {
                (0.10f + 0.96f * bandLevel + 0.36f * (0.25f + 0.75f * avgEnergy) * flowMotion).coerceIn(0f, 1.14f)
            } else {
                0.24f + 0.56f * flowMotion
            }
            val y = trackY - maxWaveH * waveScale * lenFade * edge * dome
            if (i == 0) {
                crestPath.moveTo(x, y)
            } else {
                crestPath.lineTo(x, y)
            }
            fillPath.lineTo(x, y)
        }
        fillPath.lineTo(waveEnd, trackY)
        fillPath.close()

        val crestGlowTop = lerp(accentColor, Color.White, 0.55f).copy(alpha = 0.80f)
        val crestGlowBase = accentColor.copy(alpha = 0.30f)
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(crestGlowTop, crestGlowBase),
                startY = trackY - maxWaveH,
                endY = trackY,
            ),
        )

        val blurPx = 4.dp.toPx()
        drawIntoCanvas { canvas ->
            val bloomPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3.2.dp.toPx()
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
                color = lerp(accentColor, Color.White, 0.45f).copy(alpha = 0.56f).toArgb()
                maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.nativeCanvas.drawPath(crestPath.asAndroidPath(), bloomPaint)
        }

        drawPath(
            path = crestPath,
            color = lerp(accentColor, Color.White, 0.76f).copy(alpha = 0.92f),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    // Active baseline track up to the thumb center.
    drawLine(
        color = accentColor,
        start = Offset(0f, trackY),
        end = Offset(thumbX, trackY),
        strokeWidth = trackStroke,
        cap = StrokeCap.Round,
    )

    // Glowing One UI thumb ring.
    val haloBase = if (isLightSurface) accentColor else Color.White
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                haloBase.copy(alpha = 0.48f),
                accentColor.copy(alpha = 0.22f),
                Color.Transparent,
            ),
            center = Offset(thumbX, trackY),
            radius = thumbRadiusPx * 2.2f,
        ),
        radius = thumbRadiusPx * 2.2f,
        center = Offset(thumbX, trackY),
    )

    if (isLightSurface) {
        drawCircle(
            color = accentColor.copy(alpha = 0.36f),
            radius = thumbRadiusPx + 0.75.dp.toPx(),
            center = Offset(thumbX, trackY),
        )
    }

    drawCircle(
        color = Color.White,
        radius = thumbRadiusPx,
        center = Offset(thumbX, trackY),
    )
    drawCircle(
        color = accentColor,
        radius = (thumbRadiusPx - 2.3.dp.toPx()).coerceAtLeast(2.dp.toPx()),
        center = Offset(thumbX, trackY),
    )
}
