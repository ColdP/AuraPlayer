// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.playback.LyricTheme
import com.materialkolor.palettes.CorePalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

internal enum class LyricPvEffect {
    NONE,
    CAUSTIC_WAVE,
    WATER_MIRROR,
    PRISM_SLASH,
    SPACE_WARP,
    CYBER_GLITCH,
    VERTICAL_CALLIGRAPHY,
    CONSTELLATION,
    POP_STICKER,
    PAINTERLY_BLOOM,
    CINEMA_GATE,
}

@Immutable
internal data class LyricThemeSpec(
    val inkColor: Color,
    val secondaryInkColor: Color,
    val accentArgb: Int,
    val secondaryAccentArgb: Int,
    val glowArgb: Int,
    val glowRadiusDp: Float,
    val wordGlowBoost: Float,
    val depthBlurMultiplier: Float,
    val cameraTiltXDeg: Float,
    val cameraTiltYDeg: Float,
    val cameraDepthZ: Float,
    val swayAmplitudeDp: Float,
    val arcShiftDp: Float,
    val fontFamilyName: String,
    val italicMain: Boolean,
    val italicSub: Boolean,
    val mainWeight: Int,
    val subWeight: Int,
    val letterSpacing: Float,
    val pvEffect: LyricPvEffect,
    val heroScale: Float,
    val entrySlideDp: Float,
    val watermarkAlpha: Float,
    val staggerPattern: List<Float>,
    val tiltPatternDeg: List<Float>,
)

internal fun lyricThemeSpec(theme: LyricTheme): LyricThemeSpec = when (theme) {
    LyricTheme.BLUR -> LyricThemeSpec(
        inkColor = Color.White,
        secondaryInkColor = Color.White.copy(alpha = 0.80f),
        accentArgb = android.graphics.Color.WHITE,
        secondaryAccentArgb = android.graphics.Color.WHITE,
        glowArgb = 0,
        glowRadiusDp = 0f,
        wordGlowBoost = 1f,
        depthBlurMultiplier = 1f,
        cameraTiltXDeg = 0f,
        cameraTiltYDeg = 0f,
        cameraDepthZ = 0f,
        swayAmplitudeDp = 0f,
        arcShiftDp = 0f,
        fontFamilyName = "default",
        italicMain = false,
        italicSub = false,
        mainWeight = 700,
        subWeight = 500,
        letterSpacing = 0f,
        pvEffect = LyricPvEffect.NONE,
        heroScale = 1f,
        entrySlideDp = 0f,
        watermarkAlpha = 0f,
        staggerPattern = listOf(0f),
        tiltPatternDeg = listOf(0f),
    )

    LyricTheme.DEEP_STAR_SEA -> LyricThemeSpec(
        inkColor = Color(0xFFE0F7FF),
        secondaryInkColor = Color(0xFF9AD9EE),
        accentArgb = 0xFF64E8FF.toInt(),
        secondaryAccentArgb = 0xFF3E92FF.toInt(),
        glowArgb = 0x9934D4FF.toInt(),
        glowRadiusDp = 10f,
        wordGlowBoost = 1.35f,
        depthBlurMultiplier = 0.55f,
        cameraTiltXDeg = 20f,
        cameraTiltYDeg = -8f,
        cameraDepthZ = 155f,
        swayAmplitudeDp = 4.5f,
        arcShiftDp = 16f,
        fontFamilyName = "sans-serif-light",
        italicMain = false,
        italicSub = false,
        mainWeight = 600,
        subWeight = 400,
        letterSpacing = 0.035f,
        pvEffect = LyricPvEffect.CAUSTIC_WAVE,
        heroScale = 1.09f,
        entrySlideDp = 18f,
        watermarkAlpha = 0.09f,
        staggerPattern = listOf(0.04f, 0.58f, 0.24f, 0.72f),
        tiltPatternDeg = listOf(-2.2f, 2.6f, -1.4f, 1.8f),
    )

    LyricTheme.AFTERGLOW_SHORE -> LyricThemeSpec(
        inkColor = Color(0xFFFFF1E0),
        secondaryInkColor = Color(0xFFFFC9A8),
        accentArgb = 0xFFFFAF68.toInt(),
        secondaryAccentArgb = 0xFFFF6D82.toInt(),
        glowArgb = 0x88FF7E5F.toInt(),
        glowRadiusDp = 9f,
        wordGlowBoost = 1.25f,
        depthBlurMultiplier = 0.45f,
        cameraTiltXDeg = 16f,
        cameraTiltYDeg = 5f,
        cameraDepthZ = 110f,
        swayAmplitudeDp = 2.6f,
        arcShiftDp = 0f,
        fontFamilyName = "serif",
        italicMain = false,
        italicSub = true,
        mainWeight = 600,
        subWeight = 400,
        letterSpacing = 0.025f,
        pvEffect = LyricPvEffect.WATER_MIRROR,
        heroScale = 1.08f,
        entrySlideDp = 22f,
        watermarkAlpha = 0.07f,
        staggerPattern = listOf(0.05f, 0.68f, 0.34f),
        tiltPatternDeg = listOf(-1.6f, 1.9f, 0f),
    )

    LyricTheme.STREAMING_SHADOWS -> LyricThemeSpec(
        inkColor = Color(0xFFF6F9FF),
        secondaryInkColor = Color(0xFFC0D4FF),
        accentArgb = 0xFF9DE6FF.toInt(),
        secondaryAccentArgb = 0xFFD49BFF.toInt(),
        glowArgb = 0x99B49EFF.toInt(),
        glowRadiusDp = 10f,
        wordGlowBoost = 1.4f,
        depthBlurMultiplier = 0.30f,
        cameraTiltXDeg = 14f,
        cameraTiltYDeg = 16f,
        cameraDepthZ = 140f,
        swayAmplitudeDp = 2.4f,
        arcShiftDp = 10f,
        fontFamilyName = "sans-serif-medium",
        italicMain = false,
        italicSub = false,
        mainWeight = 800,
        subWeight = 500,
        letterSpacing = 0.02f,
        pvEffect = LyricPvEffect.PRISM_SLASH,
        heroScale = 1.13f,
        entrySlideDp = 28f,
        watermarkAlpha = 0.10f,
        staggerPattern = listOf(0.0f, 0.82f, 0.38f, 0.65f, 0.14f),
        tiltPatternDeg = listOf(-3.8f, 4.2f, -2.1f, 3.0f, -1.5f),
    )

    LyricTheme.LIGHT_YEAR_DRIFT -> LyricThemeSpec(
        inkColor = Color(0xFFECEFFF),
        secondaryInkColor = Color(0xFFA7B7F5),
        accentArgb = 0xFF8FB4FF.toInt(),
        secondaryAccentArgb = 0xFFC59BFF.toInt(),
        glowArgb = 0x996B8BFF.toInt(),
        glowRadiusDp = 11f,
        wordGlowBoost = 1.35f,
        depthBlurMultiplier = 0.25f,
        cameraTiltXDeg = 24f,
        cameraTiltYDeg = 10f,
        cameraDepthZ = 240f,
        swayAmplitudeDp = 1.6f,
        arcShiftDp = 0f,
        fontFamilyName = "sans-serif-light",
        italicMain = false,
        italicSub = false,
        mainWeight = 500,
        subWeight = 300,
        letterSpacing = 0.06f,
        pvEffect = LyricPvEffect.SPACE_WARP,
        heroScale = 1.15f,
        entrySlideDp = 24f,
        watermarkAlpha = 0.08f,
        staggerPattern = listOf(0.45f, 0.08f, 0.78f, 0.28f),
        tiltPatternDeg = listOf(0f, -2.8f, 2.8f, -1.2f),
    )

    LyricTheme.NEON_CITY -> LyricThemeSpec(
        inkColor = Color(0xFFF5FAFF),
        secondaryInkColor = Color(0xFF7BE9FF),
        accentArgb = 0xFFFF52DC.toInt(),
        secondaryAccentArgb = 0xFF00F0FF.toInt(),
        glowArgb = 0xBB00E5FF.toInt(),
        glowRadiusDp = 12f,
        wordGlowBoost = 1.55f,
        depthBlurMultiplier = 0.20f,
        cameraTiltXDeg = 14f,
        cameraTiltYDeg = -18f,
        cameraDepthZ = 130f,
        swayAmplitudeDp = 0f,
        arcShiftDp = 0f,
        fontFamilyName = "monospace",
        italicMain = false,
        italicSub = false,
        mainWeight = 800,
        subWeight = 500,
        letterSpacing = 0.03f,
        pvEffect = LyricPvEffect.CYBER_GLITCH,
        heroScale = 1.12f,
        entrySlideDp = 30f,
        watermarkAlpha = 0.09f,
        staggerPattern = listOf(0.02f, 0.76f, 0.26f, 0.58f),
        tiltPatternDeg = listOf(-4.5f, 4.2f, -2.5f, 3.6f),
    )

    LyricTheme.WIND_LETTER -> LyricThemeSpec(
        inkColor = Color(0xFFFDF3E3),
        secondaryInkColor = Color(0xFFE7CEAE),
        accentArgb = 0xFFFFD8A8.toInt(),
        secondaryAccentArgb = 0xFFE56B55.toInt(),
        glowArgb = 0x66D49B6A,
        glowRadiusDp = 6f,
        wordGlowBoost = 1.15f,
        depthBlurMultiplier = 0.35f,
        cameraTiltXDeg = 12f,
        cameraTiltYDeg = 12f,
        cameraDepthZ = 95f,
        swayAmplitudeDp = 4.2f,
        arcShiftDp = 8f,
        fontFamilyName = "serif",
        italicMain = true,
        italicSub = true,
        mainWeight = 600,
        subWeight = 400,
        letterSpacing = 0.025f,
        pvEffect = LyricPvEffect.VERTICAL_CALLIGRAPHY,
        heroScale = 1.09f,
        entrySlideDp = 16f,
        watermarkAlpha = 0.14f,
        staggerPattern = listOf(0.03f, 0.36f, 0.12f, 0.48f),
        tiltPatternDeg = listOf(-1.8f, 2.2f, -1.0f, 1.5f),
    )

    LyricTheme.STAR_MOON -> LyricThemeSpec(
        inkColor = Color(0xFFFAF5E4),
        secondaryInkColor = Color(0xFFD5D7F2),
        accentArgb = 0xFFFFE58F.toInt(),
        secondaryAccentArgb = 0xFF9DB8FF.toInt(),
        glowArgb = 0x99FFD875.toInt(),
        glowRadiusDp = 10f,
        wordGlowBoost = 1.32f,
        depthBlurMultiplier = 0.42f,
        cameraTiltXDeg = 18f,
        cameraTiltYDeg = 9f,
        cameraDepthZ = 125f,
        swayAmplitudeDp = 2.8f,
        arcShiftDp = 20f,
        fontFamilyName = "serif",
        italicMain = false,
        italicSub = false,
        mainWeight = 600,
        subWeight = 400,
        letterSpacing = 0.035f,
        pvEffect = LyricPvEffect.CONSTELLATION,
        heroScale = 1.10f,
        entrySlideDp = 18f,
        watermarkAlpha = 0.07f,
        staggerPattern = listOf(0.14f, 0.62f, 0.34f, 0.76f),
        tiltPatternDeg = listOf(-2.0f, 2.4f, 0f, 1.6f),
    )

    LyricTheme.LIME_SODA -> LyricThemeSpec(
        inkColor = Color(0xFFF3FFE6),
        secondaryInkColor = Color(0xFFBCEFA8),
        accentArgb = 0xFFB8FF4F.toInt(),
        secondaryAccentArgb = 0xFF28F0B4.toInt(),
        glowArgb = 0x887DFF6B.toInt(),
        glowRadiusDp = 8f,
        wordGlowBoost = 1.35f,
        depthBlurMultiplier = 0.22f,
        cameraTiltXDeg = 12f,
        cameraTiltYDeg = -10f,
        cameraDepthZ = 95f,
        swayAmplitudeDp = 4.4f,
        arcShiftDp = 0f,
        fontFamilyName = "sans-serif-condensed",
        italicMain = false,
        italicSub = false,
        mainWeight = 800,
        subWeight = 500,
        letterSpacing = 0.015f,
        pvEffect = LyricPvEffect.POP_STICKER,
        heroScale = 1.14f,
        entrySlideDp = 26f,
        watermarkAlpha = 0.08f,
        staggerPattern = listOf(0.06f, 0.78f, 0.24f, 0.64f, 0.40f),
        tiltPatternDeg = listOf(-4.2f, 4.8f, -3.0f, 3.6f, -2.0f),
    )

    LyricTheme.MONET_GARDEN -> LyricThemeSpec(
        inkColor = Color(0xFFFCF7FF),
        secondaryInkColor = Color(0xFFE3D5F5),
        accentArgb = 0xFFF2C6FF.toInt(),
        secondaryAccentArgb = 0xFF9EE8C8.toInt(),
        glowArgb = 0x88C8B6FF.toInt(),
        glowRadiusDp = 9f,
        wordGlowBoost = 1.25f,
        depthBlurMultiplier = 0.58f,
        cameraTiltXDeg = 15f,
        cameraTiltYDeg = 7f,
        cameraDepthZ = 100f,
        swayAmplitudeDp = 3.2f,
        arcShiftDp = 12f,
        fontFamilyName = "serif",
        italicMain = false,
        italicSub = true,
        mainWeight = 500,
        subWeight = 400,
        letterSpacing = 0.03f,
        pvEffect = LyricPvEffect.PAINTERLY_BLOOM,
        heroScale = 1.09f,
        entrySlideDp = 16f,
        watermarkAlpha = 0.08f,
        staggerPattern = listOf(0.08f, 0.56f, 0.26f, 0.70f),
        tiltPatternDeg = listOf(-2.2f, 2.0f, -1.2f, 2.4f),
    )

    LyricTheme.FADED_FILM -> LyricThemeSpec(
        inkColor = Color(0xFFF3E5C6),
        secondaryInkColor = Color(0xFFCDBB96),
        accentArgb = 0xFFF7CE8B.toInt(),
        secondaryAccentArgb = 0xFFD98468.toInt(),
        glowArgb = 0x66C79F5E,
        glowRadiusDp = 5f,
        wordGlowBoost = 1.15f,
        depthBlurMultiplier = 0.30f,
        cameraTiltXDeg = 10f,
        cameraTiltYDeg = 0f,
        cameraDepthZ = 75f,
        swayAmplitudeDp = 1.0f,
        arcShiftDp = 0f,
        fontFamilyName = "serif",
        italicMain = false,
        italicSub = false,
        mainWeight = 700,
        subWeight = 500,
        letterSpacing = 0.03f,
        pvEffect = LyricPvEffect.CINEMA_GATE,
        heroScale = 1.08f,
        entrySlideDp = 14f,
        watermarkAlpha = 0.09f,
        staggerPattern = listOf(0.42f, 0.08f, 0.68f, 0.28f),
        tiltPatternDeg = listOf(0f, -1.6f, 1.8f, 0f),
    )
}

private data class BackdropParticle(
    val x: Float,
    val y: Float,
    val z: Float,
    val speed: Float,
    val size: Float,
    val seed: Float,
)

@Composable
internal fun PlayerLyricThemeBackdrop(
    theme: LyricTheme,
    blend: Float,
    phase: Float,
    palette: CorePalette,
    spatialTilt: Offset = Offset.Zero,
    modifier: Modifier = Modifier,
) {
    if (blend <= 0.001f) return
    val particles = remember {
        val rng = Random(20261115)
        List(72) {
            BackdropParticle(
                x = rng.nextFloat(),
                y = rng.nextFloat(),
                z = 0.15f + rng.nextFloat() * 0.85f,
                speed = 0.06f + rng.nextFloat() * 0.22f,
                size = 0.6f + rng.nextFloat() * 1.6f,
                seed = rng.nextFloat() * (2f * PI.toFloat()),
            )
        }
    }
    val hasSpatial = abs(spatialTilt.x) > 0.0005f || abs(spatialTilt.y) > 0.0005f
    Box(modifier.graphicsLayer { alpha = blend.coerceIn(0f, 1f) }) {
        Crossfade(targetState = theme, animationSpec = tween(420), label = "lyricThemeCrossfade") { current ->
            if (current == LyricTheme.BLUR) return@Crossfade
            Box(Modifier.fillMaxSize()) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (hasSpatial) {
                                scaleX = 1.06f
                                scaleY = 1.06f
                                translationX = -spatialTilt.x * 18.dp.toPx()
                                translationY = -spatialTilt.y * 13.dp.toPx()
                                rotationY = -spatialTilt.x * 4.5f
                                rotationX = spatialTilt.y * 3.4f
                                cameraDistance = 12f * density
                            }
                        },
                ) {
                    when (current) {
                        LyricTheme.BLUR -> Unit
                        LyricTheme.DEEP_STAR_SEA -> drawDeepStarSea(phase, particles)
                        LyricTheme.AFTERGLOW_SHORE -> drawAfterglowShore(phase, particles)
                        LyricTheme.STREAMING_SHADOWS -> drawStreamingShadows(phase, particles, palette)
                        LyricTheme.LIGHT_YEAR_DRIFT -> drawLightYearDrift(phase, particles)
                        LyricTheme.NEON_CITY -> drawNeonCity(phase, particles)
                        LyricTheme.WIND_LETTER -> drawWindLetter(phase, particles)
                        LyricTheme.STAR_MOON -> drawStarMoon(phase, particles)
                        LyricTheme.LIME_SODA -> drawLimeSoda(phase, particles)
                        LyricTheme.MONET_GARDEN -> drawMonetGarden(phase, particles, palette)
                        LyricTheme.FADED_FILM -> drawFadedFilm(phase, particles)
                    }
                }
                if (hasSpatial) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawSpatialDepthMidPlane(current, phase, particles, spatialTilt)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawSpatialDepthMidPlane(
    theme: LyricTheme,
    phase: Float,
    particles: List<BackdropParticle>,
    spatialTilt: Offset,
) {
    val spec = lyricThemeSpec(theme)
    val accent = Color(spec.accentArgb)
    val secondary = Color(spec.secondaryAccentArgb)
    for ((idx, p) in particles.withIndex()) {
        if (idx % 2 != 0) continue
        // Near-plane particles shift forward in the same direction as the user's tilt
        // while far-plane particles recede counter to it, reinforcing stereo depth.
        val depthSign = (p.z - 0.45f) * 2f
        val px = ((p.x + 0.02f * sin(phase * 0.35f + p.seed)) % 1f + 1f) % 1f * size.width +
            spatialTilt.x * 24.dp.toPx() * depthSign
        val py = ((p.y + phase * p.speed * 0.05f) % 1f + 1f) % 1f * size.height +
            spatialTilt.y * 18.dp.toPx() * depthSign
        val pulse = 0.45f + 0.55f * (0.5f + 0.5f * sin(phase * 1.4f + p.seed))
        val orbColor = if (idx % 4 == 0) accent else secondary
        if (idx % 8 == 0) {
            val haloR = (10f + p.z * 16f).dp.toPx()
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(orbColor.copy(alpha = 0.14f * pulse), Color.Transparent),
                    center = Offset(px, py),
                    radius = haloR,
                ),
                radius = haloR,
                center = Offset(px, py),
            )
        }
        drawCircle(
            color = orbColor.copy(alpha = (0.14f + 0.24f * p.z) * pulse),
            radius = (1.1f + p.size * p.z).dp.toPx(),
            center = Offset(px, py),
        )
    }
}

private fun DrawScope.drawDeepStarSea(phase: Float, particles: List<BackdropParticle>) {
    drawRect(
        Brush.verticalGradient(
            listOf(Color(0xFF041326), Color(0xFF062542), Color(0xFF030B18)),
        ),
    )
    // Surface god-rays piercing downward.
    for (i in 0..3) {
        val sway = sin(phase * 0.35f + i * 1.4f) * size.width * 0.06f
        val topX = size.width * (0.18f + i * 0.21f) + sway
        val rayPath = Path().apply {
            moveTo(topX - size.width * 0.05f, 0f)
            lineTo(topX + size.width * 0.05f, 0f)
            lineTo(topX + size.width * 0.20f, size.height * 0.85f)
            lineTo(topX - size.width * 0.14f, size.height * 0.85f)
            close()
        }
        drawPath(
            path = rayPath,
            brush = Brush.verticalGradient(
                listOf(Color(0xFF53E0FF).copy(alpha = 0.13f), Color.Transparent),
            ),
        )
    }
    // Abyssal depth rings.
    val arcCenter = Offset(size.width * 0.5f, size.height * 0.38f)
    for (ring in 1..3) {
        val r = size.width * (0.28f + ring * 0.18f) + sin(phase * 0.6f + ring) * 10.dp.toPx()
        drawCircle(
            color = Color(0xFF6EF7FF).copy(alpha = 0.05f),
            radius = r,
            center = arcCenter,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
    // Bioluminescent jellyfish / deep ocean plankton.
    for ((idx, p) in particles.withIndex()) {
        val x = ((p.x + 0.03f * sin(phase * 0.45f + p.seed)) % 1f + 1f) % 1f * size.width
        val y = ((p.y - phase * p.speed * 0.18f) % 1f + 1f) % 1f * size.height
        val pulse = 0.4f + 0.6f * (0.5f + 0.5f * sin(phase * 1.6f + p.seed))
        val color = if (idx % 4 == 0) Color(0xFF6EF7FF) else Color(0xFF9FE8FF)
        if (idx % 9 == 0) {
            val r = (14f + p.size * 14f).dp.toPx()
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(color.copy(alpha = 0.24f * pulse), Color.Transparent),
                    center = Offset(x, y),
                    radius = r,
                ),
                radius = r,
                center = Offset(x, y),
            )
        }
        drawCircle(
            color = color.copy(alpha = (0.28f + 0.45f * p.z) * pulse),
            radius = (p.size * 1.35f).dp.toPx(),
            center = Offset(x, y),
        )
    }
}

private fun DrawScope.drawAfterglowShore(phase: Float, particles: List<BackdropParticle>) {
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF1D1026),
            0.42f to Color(0xFF7B2E46),
            0.70f to Color(0xFFD66E49),
            0.76f to Color(0xFF3E2235),
            1f to Color(0xFF130D1B),
        ),
    )
    val horizonY = size.height * 0.72f
    val sunCenter = Offset(size.width * 0.5f + sin(phase * 0.2f) * size.width * 0.04f, horizonY - size.height * 0.06f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFFFD39B).copy(alpha = 0.54f), Color(0xFFFF7A59).copy(alpha = 0.22f), Color.Transparent),
            center = sunCenter,
            radius = size.width * 0.74f,
        ),
        radius = size.width * 0.74f,
        center = sunCenter,
    )
    // Shimmering water reflection streaks below horizon.
    for (i in 0..17) {
        val t = i / 17f
        val y = horizonY + (size.height - horizonY) * t.pow(1.25f)
        val halfW = size.width * (0.07f + 0.22f * (1f - t)) * (0.75f + 0.25f * sin(phase * 1.4f + i * 0.9f))
        val cx = sunCenter.x + sin(phase * 0.9f + i * 1.2f) * 18.dp.toPx() * t
        drawLine(
            color = Color(0xFFFFC88D).copy(alpha = (0.38f * (1f - t * 0.75f)).coerceIn(0.04f, 0.42f)),
            start = Offset(cx - halfW, y),
            end = Offset(cx + halfW, y),
            strokeWidth = (2.2f + t * 2f).dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
    for (p in particles.take(26)) {
        val x = p.x * size.width
        val y = p.y * horizonY * 0.9f
        val glow = 0.4f + 0.6f * sin(phase * 1.1f + p.seed)
        drawCircle(
            color = Color(0xFFFFE1C2).copy(alpha = 0.16f * glow.coerceAtLeast(0f)),
            radius = (p.size * 1.1f).dp.toPx(),
            center = Offset(x, y),
        )
    }
}

private fun DrawScope.drawStreamingShadows(
    phase: Float,
    particles: List<BackdropParticle>,
    palette: CorePalette,
) {
    val tintA = Color(palette.a1.tone(60))
    val tintB = Color(palette.a3.tone(65))
    drawRect(
        Brush.linearGradient(
            listOf(Color(0xFF0A0D18), Color(0xFF14182A), Color(0xFF080A12)),
        ),
    )
    // Rotating prismatic caustic beams + crisp PV slash lines.
    for (i in 0..3) {
        rotate(degrees = -22f + i * 14f + sin(phase * 0.35f + i) * 7f, pivot = Offset(size.width * 0.5f, size.height * 0.3f)) {
            val beamLeft = size.width * (0.12f + i * 0.2f)
            val beamW = size.width * 0.16f
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, tintA.copy(alpha = 0.16f), tintB.copy(alpha = 0.13f), Color.Transparent),
                ),
                topLeft = Offset(beamLeft, -size.height * 0.2f),
                size = Size(beamW, size.height * 1.4f),
            )
            drawLine(
                color = Color.White.copy(alpha = 0.08f),
                start = Offset(beamLeft, -size.height * 0.2f),
                end = Offset(beamLeft, size.height * 1.2f),
                strokeWidth = 0.8.dp.toPx(),
            )
        }
    }
    // Floating translucent glass shards.
    for ((idx, p) in particles.take(22).withIndex()) {
        val cx = ((p.x + phase * 0.02f * (if (idx % 2 == 0) 1f else -1f)) % 1f + 1f) % 1f * size.width
        val cy = ((p.y + phase * p.speed * 0.22f) % 1f + 1f) % 1f * size.height
        val r = (16f + p.size * 14f).dp.toPx()
        rotate(degrees = phase * 18f * (if (idx % 2 == 0) 1f else -1f) + p.seed * 57f, pivot = Offset(cx, cy)) {
            val path = Path().apply {
                moveTo(cx, cy - r)
                lineTo(cx + r * 0.72f, cy + r * 0.15f)
                lineTo(cx - r * 0.25f, cy + r * 0.9f)
                close()
            }
            val shardColor = if (idx % 2 == 0) Color(0xFFA7F0FF) else Color(0xFFD5B8FF)
            drawPath(path, color = shardColor.copy(alpha = 0.09f))
            drawPath(path, color = shardColor.copy(alpha = 0.26f), style = Stroke(width = 1.dp.toPx()))
        }
    }
}

private fun DrawScope.drawLightYearDrift(phase: Float, particles: List<BackdropParticle>) {
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFF141838), Color(0xFF060714), Color(0xFF02030A)),
            center = Offset(size.width * 0.5f, size.height * 0.42f),
            radius = size.height * 0.85f,
        ),
    )
    val vanish = Offset(size.width * 0.5f, size.height * 0.42f)
    for (ring in 1..4) {
        val radiusProgress = ((phase * 0.20f + ring / 4f) % 1f)
        val r = radiusProgress * size.width * 0.9f
        drawCircle(
            color = Color(0xFF8AA8FF).copy(alpha = (1f - radiusProgress) * 0.16f),
            radius = r,
            center = vanish,
            style = Stroke(width = 1.1.dp.toPx()),
        )
    }
    for (p in particles) {
        val z = ((p.z - phase * (0.24f + p.speed * 0.65f)) % 1f + 1f) % 1f
        val depth = z.coerceAtLeast(0.06f)
        val prevDepth = (depth + 0.08f).coerceAtMost(1.06f)
        val dx = (p.x - 0.5f) * size.width * 0.85f
        val dy = (p.y - 0.5f) * size.height * 0.85f
        val x2 = vanish.x + dx / depth
        val y2 = vanish.y + dy / depth
        if (x2 < -60f || x2 > size.width + 60f || y2 < -60f || y2 > size.height + 60f) continue
        val x1 = vanish.x + dx / prevDepth
        val y1 = vanish.y + dy / prevDepth
        val alpha = ((1f - depth) * 0.88f).coerceIn(0.08f, 0.88f)
        drawLine(
            color = Color(0xFFD5E2FF).copy(alpha = alpha),
            start = Offset(x1, y1),
            end = Offset(x2, y2),
            strokeWidth = (p.size * (1.4f - depth)).coerceAtLeast(0.7f).dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawNeonCity(phase: Float, particles: List<BackdropParticle>) {
    val horizonY = size.height * 0.58f
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF0A051D),
            0.55f to Color(0xFF210B3B),
            0.58f to Color(0xFF3E1058),
            1f to Color(0xFF090416),
        ),
    )
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFFF3CC7).copy(alpha = 0.30f), Color(0xFF00E5FF).copy(alpha = 0.14f), Color.Transparent),
            center = Offset(size.width * 0.5f, horizonY),
            radius = size.width * 0.72f,
        ),
        radius = size.width * 0.72f,
        center = Offset(size.width * 0.5f, horizonY),
    )
    // Cyber equalizer skyline silhouettes on the horizon.
    val barCount = 24
    val barW = size.width / barCount
    for (b in 0 until barCount) {
        val level = (0.35f + 0.65f * sin(phase * 3.8f + b * 0.7f).pow(2)) * size.height * 0.10f
        drawRect(
            color = (if (b % 2 == 0) Color(0xFF00F0FF) else Color(0xFFFF45D8)).copy(alpha = 0.12f),
            topLeft = Offset(b * barW + barW * 0.15f, horizonY - level),
            size = Size(barW * 0.7f, level),
        )
    }
    // 3D perspective retro-grid below the horizon.
    val floorH = size.height - horizonY
    val gridLines = 10
    for (i in 0 until gridLines) {
        val raw = ((i.toFloat() / gridLines) + (phase * 0.35f)) % 1f
        val persp = raw.pow(2.1f)
        val y = horizonY + floorH * persp
        drawLine(
            color = Color(0xFF00E5FF).copy(alpha = 0.14f + 0.30f * persp),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = (0.8f + 0.8f * persp).dp.toPx(),
        )
    }
    val centerX = size.width * 0.5f
    for (col in -7..7) {
        val xTop = centerX + col * (size.width * 0.055f)
        val xBottom = centerX + col * (size.width * 0.30f)
        drawLine(
            color = Color(0xFFFF49DB).copy(alpha = 0.26f),
            start = Offset(xTop, horizonY),
            end = Offset(xBottom, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }
    for ((idx, p) in particles.take(20).withIndex()) {
        val x = p.x * size.width
        val y = p.y * horizonY * 0.92f
        val pulse = 0.45f + 0.55f * sin(phase * 1.8f + p.seed)
        val color = if (idx % 2 == 0) Color(0xFF00F0FF) else Color(0xFFFF45D8)
        drawCircle(
            color = color.copy(alpha = 0.24f * pulse),
            radius = (4f + p.size * 4f).dp.toPx(),
            center = Offset(x, y),
        )
    }
}

private fun DrawScope.drawWindLetter(phase: Float, particles: List<BackdropParticle>) {
    drawRect(
        Brush.verticalGradient(
            listOf(Color(0xFF281D18), Color(0xFF392820), Color(0xFF221712)),
        ),
    )
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFFFD3A0).copy(alpha = 0.18f), Color.Transparent),
            center = Offset(size.width * 0.82f, size.height * 0.18f),
            radius = size.width * 0.85f,
        ),
        radius = size.width * 0.85f,
        center = Offset(size.width * 0.82f, size.height * 0.18f),
    )
    // Vertical & horizontal stationery margin rules.
    drawLine(
        color = Color(0xFFF6DEC0).copy(alpha = 0.08f),
        start = Offset(size.width - 36.dp.toPx(), 48.dp.toPx()),
        end = Offset(size.width - 36.dp.toPx(), size.height - 48.dp.toPx()),
        strokeWidth = 0.9.dp.toPx(),
    )
    val stepY = 44.dp.toPx()
    var lineY = 80.dp.toPx()
    while (lineY < size.height - 60.dp.toPx()) {
        drawLine(
            color = Color(0xFFF6DEC0).copy(alpha = 0.05f),
            start = Offset(24.dp.toPx(), lineY),
            end = Offset(size.width - 24.dp.toPx(), lineY),
            strokeWidth = 0.8.dp.toPx(),
        )
        lineY += stepY
    }
    for ((idx, p) in particles.take(26).withIndex()) {
        val x = ((p.x + phase * 0.10f * p.speed + 0.05f * sin(phase + p.seed)) % 1f + 1f) % 1f * size.width
        val y = ((p.y + phase * 0.16f * p.speed) % 1f + 1f) % 1f * size.height
        val w = (5f + p.size * 4f).dp.toPx()
        val h = w * 0.52f
        rotate(degrees = phase * 35f + p.seed * 60f, pivot = Offset(x, y)) {
            drawOval(
                color = if (idx % 3 == 0) Color(0xFFFFD9C9).copy(alpha = 0.28f) else Color(0xFFF7E2C1).copy(alpha = 0.20f),
                topLeft = Offset(x - w / 2f, y - h / 2f),
                size = Size(w, h),
            )
        }
    }
}

private fun DrawScope.drawStarMoon(phase: Float, particles: List<BackdropParticle>) {
    drawRect(
        Brush.verticalGradient(
            listOf(Color(0xFF0A0E26), Color(0xFF151C42), Color(0xFF0A0D22)),
        ),
    )
    val moonCenter = Offset(size.width * 0.80f, size.height * 0.19f)
    val moonR = 28.dp.toPx()
    // Subtle rotating astrolabe gold rings around crescent moon.
    for (ring in 1..2) {
        rotate(degrees = phase * (if (ring == 1) 8f else -5f), pivot = moonCenter) {
            drawCircle(
                color = Color(0xFFFFE599).copy(alpha = 0.14f),
                radius = moonR * (1.8f + ring * 0.7f),
                center = moonCenter,
                style = Stroke(width = 0.8.dp.toPx()),
            )
        }
    }
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFFFF2C2).copy(alpha = 0.34f), Color(0xFFB3C4FF).copy(alpha = 0.11f), Color.Transparent),
            center = moonCenter,
            radius = moonR * 4.5f,
        ),
        radius = moonR * 4.5f,
        center = moonCenter,
    )
    drawCircle(
        color = Color(0xFFFFF5D4).copy(alpha = 0.88f),
        radius = moonR,
        center = moonCenter,
    )
    drawCircle(
        color = Color(0xFF0F1434),
        radius = moonR * 0.90f,
        center = Offset(moonCenter.x - moonR * 0.38f, moonCenter.y - moonR * 0.16f),
    )
    for ((idx, p) in particles.withIndex()) {
        val x = p.x * size.width
        val y = p.y * size.height
        val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(phase * 2.1f + p.seed))
        val color = if (idx % 3 == 0) Color(0xFFFFE599) else Color(0xFFE7EEFF)
        if (idx % 8 == 0) {
            val s = (4.5f + p.size * 2.5f).dp.toPx() * twinkle
            val star = Path().apply {
                moveTo(x, y - s)
                lineTo(x + s * 0.24f, y - s * 0.24f)
                lineTo(x + s, y)
                lineTo(x + s * 0.24f, y + s * 0.24f)
                lineTo(x, y + s)
                lineTo(x - s * 0.24f, y + s * 0.24f)
                lineTo(x - s, y)
                lineTo(x - s * 0.24f, y - s * 0.24f)
                close()
            }
            drawPath(star, color = color.copy(alpha = 0.65f * twinkle))
        } else {
            drawCircle(
                color = color.copy(alpha = (0.25f + 0.45f * p.z) * twinkle),
                radius = (p.size * 1.1f).dp.toPx(),
                center = Offset(x, y),
            )
        }
    }
}

private fun DrawScope.drawLimeSoda(phase: Float, particles: List<BackdropParticle>) {
    drawRect(
        Brush.verticalGradient(
            listOf(Color(0xFF0A3327), Color(0xFF135037), Color(0xFF0C2E24)),
        ),
    )
    val swirl = Offset(
        size.width * (0.35f + 0.25f * sin(phase * 0.3f)),
        size.height * (0.38f + 0.18f * cos(phase * 0.25f)),
    )
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFA2FF4A).copy(alpha = 0.28f), Color(0xFF28E09B).copy(alpha = 0.14f), Color.Transparent),
            center = swirl,
            radius = size.width * 0.85f,
        ),
        radius = size.width * 0.85f,
        center = swirl,
    )
    // Pop-art subtle geometric accents (+ / rings) for upbeat PV energy.
    for ((idx, p) in particles.take(10).withIndex()) {
        val x = p.x * size.width
        val y = ((p.y - phase * 0.05f) % 1f + 1f) % 1f * size.height
        val arm = 6.dp.toPx()
        rotate(degrees = phase * 24f + idx * 36f, pivot = Offset(x, y)) {
            val popColor = Color(0xFFD0FF66).copy(alpha = 0.18f)
            drawLine(popColor, Offset(x - arm, y), Offset(x + arm, y), 1.6.dp.toPx(), StrokeCap.Round)
            drawLine(popColor, Offset(x, y - arm), Offset(x, y + arm), 1.6.dp.toPx(), StrokeCap.Round)
        }
    }
    for (p in particles.take(40)) {
        val x = ((p.x + 0.02f * sin(phase * 2.2f + p.seed)) % 1f + 1f) % 1f * size.width
        val y = ((p.y - phase * (0.14f + p.speed * 0.48f)) % 1f + 1f) % 1f * size.height
        val r = (2.5f + p.size * 5.2f).dp.toPx()
        drawCircle(
            color = Color(0xFFD6FFB3).copy(alpha = 0.10f + 0.10f * p.z),
            radius = r,
            center = Offset(x, y),
        )
        drawCircle(
            color = Color(0xFFEAFFD6).copy(alpha = 0.34f),
            radius = r,
            center = Offset(x, y),
            style = Stroke(width = 0.9.dp.toPx()),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.45f),
            radius = r * 0.22f,
            center = Offset(x - r * 0.32f, y - r * 0.32f),
        )
    }
}

private fun DrawScope.drawMonetGarden(
    phase: Float,
    particles: List<BackdropParticle>,
    palette: CorePalette,
) {
    val base = Color(0xFF1B2336)
    drawRect(base)
    val lilyGreen = Color(0xFF69B58C)
    val lavender = Color(0xFFB59CF2)
    val rose = Color(0xFFF09EB4)
    val coverHint = Color(palette.a1.tone(72))
    val brushColors = listOf(lilyGreen, lavender, rose, coverHint)
    for (i in 0..7) {
        val cx = size.width * (0.18f + (i % 3) * 0.31f) + sin(phase * 0.28f + i) * 28.dp.toPx()
        val cy = size.height * (0.14f + i * 0.11f) + cos(phase * 0.22f + i) * 20.dp.toPx()
        val r = size.width * (0.42f + (i % 2) * 0.16f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(brushColors[i % brushColors.size].copy(alpha = 0.24f), Color.Transparent),
                center = Offset(cx, cy),
                radius = r,
            ),
            radius = r,
            center = Offset(cx, cy),
        )
    }
    for ((idx, p) in particles.take(36).withIndex()) {
        val x = ((p.x + 0.025f * sin(phase * 0.4f + p.seed)) % 1f + 1f) % 1f * size.width
        val y = p.y * size.height
        val w = (14f + p.size * 18f).dp.toPx()
        val h = w * 0.34f
        val color = brushColors[idx % brushColors.size]
        rotate(degrees = 14f * sin(p.seed), pivot = Offset(x, y)) {
            drawOval(
                color = color.copy(alpha = 0.11f + 0.08f * sin(phase + p.seed)),
                topLeft = Offset(x - w / 2f, y - h / 2f),
                size = Size(w, h),
            )
        }
    }
}

private fun DrawScope.drawFadedFilm(phase: Float, particles: List<BackdropParticle>) {
    val flicker = 0.96f + 0.04f * sin(phase * 11f)
    drawRect(
        Brush.verticalGradient(
            listOf(Color(0xFF1A1611), Color(0xFF2B231A), Color(0xFF17130E)),
        ),
    )
    drawRect(Color(0xFFFFD8A0).copy(alpha = 0.05f * flicker))
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.Transparent, Color(0xFF0A0806).copy(alpha = 0.58f)),
            center = Offset(size.width * 0.5f, size.height * 0.48f),
            radius = size.height * 0.72f,
        ),
        radius = size.height,
        center = Offset(size.width * 0.5f, size.height * 0.48f),
    )
    val sprocketW = 7.dp.toPx()
    val sprocketH = 12.dp.toPx()
    val pitch = 28.dp.toPx()
    val scrollOffset = (phase * 16f) % pitch
    var y = -pitch + scrollOffset
    while (y < size.height + pitch) {
        val color = Color(0xFFF3E2C3).copy(alpha = 0.07f)
        drawRoundRect(
            color = color,
            topLeft = Offset(5.dp.toPx(), y),
            size = Size(sprocketW, sprocketH),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - 5.dp.toPx() - sprocketW, y),
            size = Size(sprocketW, sprocketH),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )
        y += pitch
    }
    for ((idx, p) in particles.take(28).withIndex()) {
        val x = ((p.x + sin(phase * 3.1f + idx) * 0.015f) % 1f + 1f) % 1f * size.width
        val yPos = ((p.y + cos(phase * 2.4f + idx) * 0.02f) % 1f + 1f) % 1f * size.height
        if (idx % 7 == 0) {
            drawLine(
                color = Color(0xFFF5E3C6).copy(alpha = 0.08f * flicker),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 0.7.dp.toPx(),
            )
        } else {
            drawCircle(
                color = Color(0xFFF2DFBE).copy(alpha = 0.14f * flicker),
                radius = (0.7f + p.size * 0.5f).dp.toPx(),
                center = Offset(x, yPos),
            )
        }
    }
}
