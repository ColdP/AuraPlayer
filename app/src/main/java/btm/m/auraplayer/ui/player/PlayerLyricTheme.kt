// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
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
import btm.m.auraplayer.playback.TokyoSceneLandmark
import btm.m.auraplayer.playback.TokyoSceneTime
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
    TOKYO_SKYLINE,
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

    LyricTheme.TOKYO_CITYSCAPE -> LyricThemeSpec(
        inkColor = Color(0xFFF6FAFF),
        secondaryInkColor = Color(0xFFC3D7F4),
        accentArgb = 0xFFFF8844.toInt(),
        secondaryAccentArgb = 0xFF4CD8FF.toInt(),
        glowArgb = 0x99FF7D45.toInt(),
        glowRadiusDp = 10f,
        wordGlowBoost = 1.38f,
        depthBlurMultiplier = 1.30f,
        cameraTiltXDeg = 16f,
        cameraTiltYDeg = 22f,
        cameraDepthZ = 185f,
        swayAmplitudeDp = 1.6f,
        arcShiftDp = 12f,
        fontFamilyName = "sans-serif-medium",
        italicMain = false,
        italicSub = false,
        mainWeight = 700,
        subWeight = 500,
        letterSpacing = 0.03f,
        pvEffect = LyricPvEffect.TOKYO_SKYLINE,
        heroScale = 1.13f,
        entrySlideDp = 24f,
        watermarkAlpha = 0.07f,
        staggerPattern = listOf(0.04f, 0.70f, 0.12f, 0.78f, 0.28f),
        tiltPatternDeg = listOf(-2.4f, 2.6f, -1.6f, 2.0f, -1.8f),
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
    tokyoSceneTime: TokyoSceneTime = TokyoSceneTime.FOLLOW_SYSTEM,
    tokyoSceneLandmark: TokyoSceneLandmark = TokyoSceneLandmark.CITY_WALK,
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
    val tokyoBlueprint = remember { TokyoCityBlueprint() }
    val tokyoScratch = remember { TokyoRenderScratch() }
    var previousWalkBlend by remember { mutableStateOf(resolveTokyoWalkBlend(tokyoSceneLandmark, phase)) }
    var initializedLandmark by remember { mutableStateOf(tokyoSceneLandmark) }
    val walkMorph = remember { Animatable(1f) }
    LaunchedEffect(tokyoSceneLandmark) {
        if (initializedLandmark != tokyoSceneLandmark) {
            previousWalkBlend = lerpWalkBlend(
                previousWalkBlend,
                resolveTokyoWalkBlend(initializedLandmark, phase),
                walkMorph.value,
            )
            initializedLandmark = tokyoSceneLandmark
            walkMorph.snapTo(0f)
            walkMorph.animateTo(1f, tween(durationMillis = 950, easing = FastOutSlowInEasing))
        }
    }
    val hasSpatial = abs(spatialTilt.x) > 0.0005f || abs(spatialTilt.y) > 0.0005f
    val tintA = remember(palette) { Color(palette.a1.tone(68)) }
    val tintB = remember(palette) { Color(palette.a3.tone(72)) }
    Box(modifier.graphicsLayer { alpha = blend.coerceIn(0f, 1f) }) {
        Crossfade(targetState = theme, animationSpec = tween(420), label = "lyricThemeCrossfade") { current ->
            if (current == LyricTheme.BLUR) return@Crossfade
            val shaderRenderer = remember(current) {
                agslShaderSourceForLyricTheme(current)?.let(::LyricThemeShaderRenderer)
            }
            val tokyoLighting = if (current == LyricTheme.TOKYO_CITYSCAPE) {
                resolveCurrentTokyoLighting(tokyoSceneTime)
            } else {
                null
            }
            val activeTintA = tokyoLighting?.let { it.zenithColor.copy(alpha = it.daylight) } ?: tintA
            val activeTintB = tokyoLighting?.let { it.horizonColor.copy(alpha = it.nightness) } ?: tintB
            val activeWalkBlend = if (current == LyricTheme.TOKYO_CITYSCAPE) {
                lerpWalkBlend(previousWalkBlend, resolveTokyoWalkBlend(tokyoSceneLandmark, phase), walkMorph.value)
            } else {
                null
            }
            Box(Modifier.fillMaxSize()) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (hasSpatial && current != LyricTheme.TOKYO_CITYSCAPE) {
                                scaleX = 1.06f
                                scaleY = 1.06f
                                translationX = -spatialTilt.x * 16.dp.toPx()
                                translationY = -spatialTilt.y * 12.dp.toPx()
                                rotationY = -spatialTilt.x * 4.2f
                                rotationX = spatialTilt.y * 3.2f
                                cameraDistance = 12f * density
                            }
                        },
                ) {
                    val drewShader = shaderRenderer?.draw(
                        scope = this,
                        time = phase,
                        tilt = spatialTilt,
                        tintA = activeTintA,
                        tintB = activeTintB,
                    ) == true
                    when (current) {
                        LyricTheme.BLUR -> Unit
                        LyricTheme.DEEP_STAR_SEA -> drawDeepStarSea(phase, particles, drewShader)
                        LyricTheme.AFTERGLOW_SHORE -> drawAfterglowShore(phase, particles, drewShader)
                        LyricTheme.STREAMING_SHADOWS -> drawStreamingShadows(phase, particles, palette, drewShader)
                        LyricTheme.LIGHT_YEAR_DRIFT -> drawLightYearDrift(phase, particles, drewShader)
                        LyricTheme.NEON_CITY -> drawNeonCity(phase, particles, drewShader)
                        LyricTheme.WIND_LETTER -> drawWindLetter(phase, particles, drewShader)
                        LyricTheme.STAR_MOON -> drawStarMoon(phase, particles, drewShader)
                        LyricTheme.LIME_SODA -> drawLimeSoda(phase, particles, drewShader)
                        LyricTheme.MONET_GARDEN -> drawMonetGarden(phase, particles, palette, drewShader)
                        LyricTheme.FADED_FILM -> drawFadedFilm(phase, particles, drewShader)
                        LyricTheme.TOKYO_CITYSCAPE -> drawTokyoCityscapeScene(
                            scope = this,
                            lighting = tokyoLighting ?: resolveCurrentTokyoLighting(tokyoSceneTime),
                            phase = phase,
                            spatialTilt = spatialTilt,
                            blend = 1f,
                            walkBlend = activeWalkBlend ?: resolveTokyoWalkBlend(tokyoSceneLandmark, phase),
                            drawFallbackSky = !drewShader,
                            blueprint = tokyoBlueprint,
                            scratch = tokyoScratch,
                        )
                    }
                }
                if (hasSpatial && current != LyricTheme.TOKYO_CITYSCAPE) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawSpatialDepthMidPlane(current, phase, particles, spatialTilt)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawSoftBokeh(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
    additive: Boolean = true,
) {
    if (radius <= 0.5f || alpha <= 0.002f) return
    drawCircle(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            0.38f to color.copy(alpha = alpha * 0.42f),
            0.78f to color.copy(alpha = alpha * 0.10f),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
        blendMode = if (additive) BlendMode.Plus else BlendMode.SrcOver,
    )
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
        // Near-plane motes shift forward with tilt while far-plane motes recede to reinforce stereo depth.
        val depthSign = (p.z - 0.45f) * 2f
        val px = ((p.x + 0.02f * sin(phase * 0.35f + p.seed)) % 1f + 1f) % 1f * size.width +
            spatialTilt.x * 24.dp.toPx() * depthSign
        val py = ((p.y + phase * p.speed * 0.05f) % 1f + 1f) % 1f * size.height +
            spatialTilt.y * 18.dp.toPx() * depthSign
        val pulse = 0.45f + 0.55f * (0.5f + 0.5f * sin(phase * 1.4f + p.seed))
        val orbColor = if (idx % 4 == 0) accent else secondary
        val haloR = (if (idx % 6 == 0) 14f + p.size * 14f else 4f + p.size * 4.5f).dp.toPx()
        drawSoftBokeh(
            center = Offset(px, py),
            radius = haloR,
            color = orbColor,
            alpha = (0.12f + 0.18f * p.z) * pulse,
        )
    }
}

private fun DrawScope.drawDeepStarSea(
    phase: Float,
    particles: List<BackdropParticle>,
    hasShaderBase: Boolean,
) {
    if (!hasShaderBase) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF041529), Color(0xFF062542), Color(0xFF030B18)),
            ),
        )
    }
    // Soft bioluminescent plankton wisps and diffused deep-water light blooms.
    for ((idx, p) in particles.withIndex()) {
        val x = ((p.x + 0.03f * sin(phase * 0.42f + p.seed)) % 1f + 1f) % 1f * size.width
        val y = ((p.y - phase * p.speed * 0.16f) % 1f + 1f) % 1f * size.height
        val pulse = 0.35f + 0.65f * (0.5f + 0.5f * sin(phase * 1.5f + p.seed))
        val color = if (idx % 3 == 0) Color(0xFF5CF2FF) else Color(0xFF8BE2FF)
        if (idx % 9 == 0) {
            val haloR = (22f + p.size * 18f).dp.toPx()
            drawSoftBokeh(Offset(x, y), haloR, color, alpha = 0.16f * pulse)
        }
        val coreR = (2.4f + p.size * 2.2f).dp.toPx()
        drawSoftBokeh(Offset(x, y), coreR, color, alpha = (0.28f + 0.38f * p.z) * pulse)
    }
}

private fun DrawScope.drawAfterglowShore(
    phase: Float,
    particles: List<BackdropParticle>,
    hasShaderBase: Boolean,
) {
    if (!hasShaderBase) {
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF160C22),
                0.44f to Color(0xFF702542),
                0.68f to Color(0xFFD86C42),
                0.76f to Color(0xFF341B2D),
                1f to Color(0xFF110B18),
            ),
        )
    }
    val horizonY = size.height * 0.67f
    val sunX = size.width * (0.5f + sin(phase * 0.16f) * 0.045f)
    // Soft diffused solar bokeh & coastal evening dust motes.
    drawSoftBokeh(
        center = Offset(sunX, horizonY - size.height * 0.06f),
        radius = size.width * 0.42f,
        color = Color(0xFFFFCA8A),
        alpha = 0.14f,
    )
    for ((idx, p) in particles.take(30).withIndex()) {
        val nearHorizon = idx % 2 == 0
        val x = if (nearHorizon) {
            sunX + (p.x - 0.5f) * size.width * 0.52f + sin(phase * 0.7f + p.seed) * 14.dp.toPx()
        } else {
            ((p.x + 0.015f * sin(phase * 0.3f + p.seed)) % 1f + 1f) % 1f * size.width
        }
        val y = if (nearHorizon) {
            horizonY + p.y.pow(1.4f) * (size.height - horizonY) * 0.85f
        } else {
            p.y * horizonY * 0.88f
        }
        val glow = (0.35f + 0.65f * (0.5f + 0.5f * sin(phase * 1.25f + p.seed)))
        val tint = if (nearHorizon) Color(0xFFFFD396) else Color(0xFFFFB4B4)
        val radius = if (nearHorizon) (8f + p.size * 10f).dp.toPx() else (3.5f + p.size * 3.5f).dp.toPx()
        drawSoftBokeh(Offset(x, y), radius, tint, alpha = if (nearHorizon) 0.14f * glow else 0.16f * glow)
    }
}

private fun DrawScope.drawStreamingShadows(
    phase: Float,
    particles: List<BackdropParticle>,
    palette: CorePalette,
    hasShaderBase: Boolean,
) {
    val tintA = Color(palette.a1.tone(68))
    val tintB = Color(palette.a3.tone(72))
    if (!hasShaderBase) {
        drawRect(
            Brush.linearGradient(
                listOf(Color(0xFF070914), Color(0xFF141729), Color(0xFF070912)),
            ),
        )
    }
    // Soft anamorphic horizontal lens flares catching studio prismatic mist.
    for (i in 0..2) {
        val flareY = size.height * (0.24f + i * 0.25f) + sin(phase * 0.32f + i * 1.9f) * 28.dp.toPx()
        val flareX = size.width * (0.5f + 0.24f * cos(phase * 0.24f + i * 2.1f))
        val streakW = size.width * (0.72f + 0.18f * sin(phase * 0.4f + i))
        val streakH = (12f + i * 5f).dp.toPx()
        val flareColor = if (i % 2 == 0) tintA else tintB
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to flareColor.copy(alpha = 0.11f),
                1f to Color.Transparent,
                startX = flareX - streakW * 0.5f,
                endX = flareX + streakW * 0.5f,
            ),
            topLeft = Offset(flareX - streakW * 0.5f, flareY - streakH * 0.5f),
            size = Size(streakW, streakH),
            blendMode = BlendMode.Plus,
        )
        drawSoftBokeh(
            center = Offset(flareX, flareY),
            radius = (26f + i * 10f).dp.toPx(),
            color = flareColor,
            alpha = 0.12f,
        )
    }
    // Feathered out-of-focus aperture bokeh orbs (zero sharp polygon edges).
    for ((idx, p) in particles.take(26).withIndex()) {
        val x = ((p.x + 0.025f * sin(phase * 0.35f + p.seed)) % 1f + 1f) % 1f * size.width
        val y = ((p.y + phase * p.speed * 0.08f) % 1f + 1f) % 1f * size.height
        val pulse = 0.45f + 0.55f * (0.5f + 0.5f * sin(phase * 1.1f + p.seed))
        val orbColor = if (idx % 3 == 0) tintA else if (idx % 3 == 1) tintB else Color(0xFFBFE6FF)
        val radius = if (idx % 4 == 0) (22f + p.size * 18f).dp.toPx() else (6f + p.size * 6f).dp.toPx()
        drawSoftBokeh(
            center = Offset(x, y),
            radius = radius,
            color = orbColor,
            alpha = (if (idx % 4 == 0) 0.09f else 0.17f) * pulse,
        )
    }
}

private fun DrawScope.drawLightYearDrift(
    phase: Float,
    particles: List<BackdropParticle>,
    hasShaderBase: Boolean,
) {
    val vanish = Offset(size.width * 0.5f, size.height * 0.43f)
    if (!hasShaderBase) {
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFF14183A), Color(0xFF060714), Color(0xFF02030A)),
                center = vanish,
                radius = size.height * 0.85f,
            ),
        )
    }
    // Luminous central warp glow + tapered gradient starlight streaks (zero stroked rings).
    drawSoftBokeh(
        center = vanish,
        radius = size.width * 0.36f,
        color = Color(0xFF8FB4FF),
        alpha = 0.14f,
    )
    for ((idx, p) in particles.withIndex()) {
        val z = ((p.z - phase * (0.20f + p.speed * 0.58f)) % 1f + 1f) % 1f
        val depth = z.coerceAtLeast(0.06f)
        val prevDepth = (depth + 0.11f).coerceAtMost(1.08f)
        val dx = (p.x - 0.5f) * size.width * 0.85f
        val dy = (p.y - 0.5f) * size.height * 0.85f
        val x2 = vanish.x + dx / depth
        val y2 = vanish.y + dy / depth
        if (x2 < -60f || x2 > size.width + 60f || y2 < -60f || y2 > size.height + 60f) continue
        val x1 = vanish.x + dx / prevDepth
        val y1 = vanish.y + dy / prevDepth
        val alpha = ((1f - depth) * 0.82f).coerceIn(0.06f, 0.82f)
        val starTint = if (idx % 5 == 0) Color(0xFFCBB6FF) else Color(0xFFDCE8FF)
        drawLine(
            brush = Brush.linearGradient(
                0f to Color.Transparent,
                0.65f to starTint.copy(alpha = alpha * 0.45f),
                1f to starTint.copy(alpha = alpha),
                start = Offset(x1, y1),
                end = Offset(x2, y2),
            ),
            start = Offset(x1, y1),
            end = Offset(x2, y2),
            strokeWidth = (p.size * (1.3f - depth)).coerceAtLeast(0.6f).dp.toPx(),
            cap = StrokeCap.Round,
        )
        if (depth < 0.42f) {
            drawSoftBokeh(
                center = Offset(x2, y2),
                radius = (3.5f + p.size * 2.5f).dp.toPx(),
                color = starTint,
                alpha = alpha * 0.35f,
            )
        }
    }
}

private fun DrawScope.drawNeonCity(
    phase: Float,
    particles: List<BackdropParticle>,
    hasShaderBase: Boolean,
) {
    val horizonY = size.height * 0.56f
    if (!hasShaderBase) {
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF0A051D),
                0.55f to Color(0xFF210B3B),
                0.58f to Color(0xFF3E1058),
                1f to Color(0xFF090416),
            ),
        )
    }
    // Out-of-focus midnight street neon bokeh across depth planes (zero boxy bars or flat grids).
    for ((idx, p) in particles.take(28).withIndex()) {
        val x = ((p.x + 0.01f * sin(phase * 0.25f + p.seed)) % 1f + 1f) % 1f * size.width
        val y = (0.16f + p.y * 0.72f) * size.height
        val pulse = 0.42f + 0.58f * (0.5f + 0.5f * sin(phase * 1.5f + p.seed))
        val neonColor = when {
            idx % 5 == 0 -> Color(0xFFFFC15E)
            idx % 2 == 0 -> Color(0xFF00E8FF)
            else -> Color(0xFFFF42D2)
        }
        val radius = (10f + p.z * 26f).dp.toPx()
        drawSoftBokeh(
            center = Offset(x, y),
            radius = radius,
            color = neonColor,
            alpha = (if (y > horizonY) 0.14f else 0.18f) * pulse,
        )
    }
    // Delicate diagonal neon-lit raindrop streaks falling past glass.
    for ((idx, p) in particles.drop(28).take(32).withIndex()) {
        val dropY = ((p.y + phase * (0.28f + p.speed * 0.55f)) % 1f + 1f) % 1f * size.height
        val dropX = ((p.x + dropY / size.height * 0.12f) % 1f + 1f) % 1f * size.width
        val len = (14f + p.z * 24f).dp.toPx()
        val streakColor = if (idx % 2 == 0) Color(0xFF8CF2FF) else Color(0xFFFF9DE8)
        drawLine(
            brush = Brush.linearGradient(
                0f to Color.Transparent,
                0.7f to streakColor.copy(alpha = 0.16f),
                1f to streakColor.copy(alpha = 0.28f),
                start = Offset(dropX + len * 0.14f, dropY - len),
                end = Offset(dropX, dropY),
            ),
            start = Offset(dropX + len * 0.14f, dropY - len),
            end = Offset(dropX, dropY),
            strokeWidth = (0.65f + p.z * 0.5f).dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawWindLetter(
    phase: Float,
    particles: List<BackdropParticle>,
    hasShaderBase: Boolean,
) {
    if (!hasShaderBase) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF281D18), Color(0xFF392820), Color(0xFF221712)),
            ),
        )
    }
    // Delicate ink marginalia stationery rules faded smoothly at the edges.
    val ruleBrushH = Brush.horizontalGradient(
        0f to Color.Transparent,
        0.15f to Color(0xFFF6DEC0).copy(alpha = 0.045f),
        0.85f to Color(0xFFF6DEC0).copy(alpha = 0.045f),
        1f to Color.Transparent,
    )
    val stepY = 46.dp.toPx()
    var lineY = 84.dp.toPx()
    while (lineY < size.height - 64.dp.toPx()) {
        drawLine(
            brush = ruleBrushH,
            start = Offset(20.dp.toPx(), lineY),
            end = Offset(size.width - 20.dp.toPx(), lineY),
            strokeWidth = 0.7.dp.toPx(),
        )
        lineY += stepY
    }
    // Organic tapered botanical petals tumbling gently on evening breeze (replaces ovals).
    for ((idx, p) in particles.take(24).withIndex()) {
        val x = ((p.x + phase * 0.09f * p.speed + 0.06f * sin(phase * 0.9f + p.seed)) % 1f + 1f) % 1f * size.width
        val y = ((p.y + phase * 0.14f * p.speed) % 1f + 1f) % 1f * size.height
        val len = (7f + p.size * 5.5f).dp.toPx()
        val width = len * 0.62f
        val petalColor = if (idx % 3 == 0) Color(0xFFFFD8C8) else Color(0xFFF8E0BD)
        val alpha = 0.14f + 0.14f * p.z
        rotate(degrees = phase * 32f * (if (idx % 2 == 0) 1f else -0.8f) + p.seed * 60f, pivot = Offset(x, y)) {
            val petalPath = Path().apply {
                moveTo(x - len * 0.5f, y)
                cubicTo(
                    x - len * 0.25f, y - width * 0.72f,
                    x + len * 0.35f, y - width * 0.55f,
                    x + len * 0.5f, y - width * 0.08f,
                )
                lineTo(x + len * 0.36f, y)
                lineTo(x + len * 0.5f, y + width * 0.08f)
                cubicTo(
                    x + len * 0.35f, y + width * 0.55f,
                    x - len * 0.25f, y + width * 0.72f,
                    x - len * 0.5f, y,
                )
                close()
            }
            drawPath(
                path = petalPath,
                brush = Brush.linearGradient(
                    listOf(petalColor.copy(alpha = alpha * 0.45f), petalColor.copy(alpha = alpha)),
                    start = Offset(x - len * 0.5f, y),
                    end = Offset(x + len * 0.5f, y),
                ),
            )
        }
    }
}

private fun DrawScope.drawStarMoon(
    phase: Float,
    particles: List<BackdropParticle>,
    hasShaderBase: Boolean,
) {
    if (!hasShaderBase) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF0A0E26), Color(0xFF151C42), Color(0xFF0A0D22)),
            ),
        )
    }
    val moonCenter = Offset(size.width * 0.80f, size.height * 0.19f)
    val moonR = 28.dp.toPx()
    // Ethereal atmospheric moonlight halo (no harsh cutout or solid wireframe rings).
    drawSoftBokeh(moonCenter, moonR * 4.8f, Color(0xFFFFF0BA), alpha = 0.20f)
    drawSoftBokeh(moonCenter, moonR * 2.2f, Color(0xFFFFF7DC), alpha = 0.24f)

    // True curved crescent Path (no mismatched opaque disk covering the night sky).
    val startDeg = -58f
    val sweepDeg = 244f
    val startRad = startDeg * (PI / 180f).toFloat()
    val topHorn = Offset(moonCenter.x + moonR * cos(startRad), moonCenter.y + moonR * sin(startRad))
    val crescentPath = Path().apply {
        moveTo(topHorn.x, topHorn.y)
        arcTo(
            rect = Rect(
                left = moonCenter.x - moonR,
                top = moonCenter.y - moonR,
                right = moonCenter.x + moonR,
                bottom = moonCenter.y + moonR,
            ),
            startAngleDegrees = startDeg,
            sweepAngleDegrees = sweepDeg,
            forceMoveTo = false,
        )
        cubicTo(
            moonCenter.x + moonR * 0.36f, moonCenter.y + moonR * 0.44f,
            moonCenter.x + moonR * 0.36f, moonCenter.y - moonR * 0.38f,
            topHorn.x, topHorn.y,
        )
        close()
    }
    drawPath(
        path = crescentPath,
        brush = Brush.radialGradient(
            listOf(Color(0xFFFFFCEB).copy(alpha = 0.96f), Color(0xFFFFE59E).copy(alpha = 0.82f)),
            center = Offset(moonCenter.x + moonR * 0.35f, moonCenter.y + moonR * 0.20f),
            radius = moonR * 1.4f,
        ),
    )

    // Concave 4-point astral star flares + twinkling stardust.
    for ((idx, p) in particles.withIndex()) {
        val x = p.x * size.width
        val y = p.y * size.height
        val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(phase * 2.0f + p.seed))
        val color = if (idx % 3 == 0) Color(0xFFFFE69A) else Color(0xFFE7EEFF)
        if (idx % 8 == 0) {
            val s = (5.5f + p.size * 3.0f).dp.toPx() * twinkle
            val pinch = s * 0.14f
            drawSoftBokeh(Offset(x, y), s * 1.6f, color, alpha = 0.22f * twinkle)
            val flare = Path().apply {
                moveTo(x, y - s)
                quadraticTo(x + pinch, y - pinch, x + s, y)
                quadraticTo(x + pinch, y + pinch, x, y + s)
                quadraticTo(x - pinch, y + pinch, x - s, y)
                quadraticTo(x - pinch, y - pinch, x, y - s)
                close()
            }
            drawPath(flare, color = color.copy(alpha = 0.72f * twinkle))
        } else {
            drawSoftBokeh(Offset(x, y), (1.8f + p.size * 1.4f).dp.toPx(), color, alpha = (0.28f + 0.45f * p.z) * twinkle)
        }
    }
}

private fun DrawScope.drawLimeSoda(
    phase: Float,
    particles: List<BackdropParticle>,
    hasShaderBase: Boolean,
) {
    if (!hasShaderBase) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF072B20), Color(0xFF104933), Color(0xFF0A281F)),
            ),
        )
    }
    // Sunlit summer bokeh + effervescent soda micro-bubbles (replaces '+' crosshairs & hard circles).
    for ((idx, p) in particles.take(46).withIndex()) {
        val x = ((p.x + 0.025f * sin(phase * 1.9f + p.seed)) % 1f + 1f) % 1f * size.width
        val y = ((p.y - phase * (0.12f + p.speed * 0.42f)) % 1f + 1f) % 1f * size.height
        if (idx < 10) {
            val bokehR = (16f + p.size * 18f).dp.toPx()
            val pulse = 0.5f + 0.5f * sin(phase * 1.2f + p.seed)
            drawSoftBokeh(Offset(x, y), bokehR, Color(0xFFB8FF5C), alpha = 0.11f * pulse)
        } else {
            val r = (2.2f + p.size * 4.4f).dp.toPx()
            val center = Offset(x, y)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.62f to Color(0xFFD2FFAA).copy(alpha = 0.06f),
                    0.90f to Color(0xFFE9FFD4).copy(alpha = 0.26f),
                    1f to Color.Transparent,
                    center = center,
                    radius = r,
                ),
                radius = r,
                center = center,
                blendMode = BlendMode.Plus,
            )
            drawArc(
                color = Color.White.copy(alpha = 0.42f),
                startAngle = -140f,
                sweepAngle = 72f,
                useCenter = false,
                topLeft = Offset(x - r * 0.66f, y - r * 0.66f),
                size = Size(r * 1.32f, r * 1.32f),
                style = Stroke(width = (0.7f + r * 0.07f).coerceAtMost(1.3.dp.toPx()), cap = StrokeCap.Round),
            )
        }
    }
}

private fun DrawScope.drawMonetGarden(
    phase: Float,
    particles: List<BackdropParticle>,
    palette: CorePalette,
    hasShaderBase: Boolean,
) {
    val lilyGreen = Color(0xFF69B58C)
    val lavender = Color(0xFFB59CF2)
    val rose = Color(0xFFF09EB4)
    val coverHint = Color(palette.a1.tone(72))
    val brushColors = listOf(lilyGreen, lavender, rose, coverHint)
    if (!hasShaderBase) {
        drawRect(Color(0xFF141C2E))
        for (i in 0..5) {
            val cx = size.width * (0.18f + (i % 3) * 0.31f) + sin(phase * 0.28f + i) * 24.dp.toPx()
            val cy = size.height * (0.14f + i * 0.14f) + cos(phase * 0.22f + i) * 18.dp.toPx()
            drawSoftBokeh(Offset(cx, cy), size.width * 0.48f, brushColors[i % brushColors.size], alpha = 0.18f, additive = false)
        }
    }
    // Impressionist feathered horizontal water-reflection glints + floating pond fireflies.
    for ((idx, p) in particles.take(36).withIndex()) {
        val x = ((p.x + 0.025f * sin(phase * 0.42f + p.seed)) % 1f + 1f) % 1f * size.width
        val y = ((p.y + 0.008f * cos(phase * 0.35f + p.seed)) % 1f + 1f) % 1f * size.height
        val color = brushColors[idx % brushColors.size]
        val pulse = 0.45f + 0.55f * (0.5f + 0.5f * sin(phase * 1.15f + p.seed))
        if (idx % 3 == 0) {
            drawSoftBokeh(Offset(x, y), (4.5f + p.size * 4.5f).dp.toPx(), Color(0xFFFFE5B4), alpha = 0.18f * pulse)
        } else {
            val strokeW = (18f + p.size * 22f).dp.toPx()
            val strokeH = (2.2f + p.size * 1.4f).dp.toPx()
            drawLine(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to color.copy(alpha = 0.11f * pulse),
                    1f to Color.Transparent,
                    startX = x - strokeW * 0.5f,
                    endX = x + strokeW * 0.5f,
                ),
                start = Offset(x - strokeW * 0.5f, y),
                end = Offset(x + strokeW * 0.5f, y),
                strokeWidth = strokeH,
                cap = StrokeCap.Round,
                blendMode = BlendMode.Plus,
            )
        }
    }
}

private fun DrawScope.drawFadedFilm(
    phase: Float,
    particles: List<BackdropParticle>,
    hasShaderBase: Boolean,
) {
    val flicker = 0.96f + 0.04f * sin(phase * 11f)
    if (!hasShaderBase) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF1A1611), Color(0xFF2B231A), Color(0xFF17130E)),
            ),
        )
    }
    // Warm analog film edge halation & subtle cinema matte border hairlines (replaces fake sprocket rects).
    drawSoftBokeh(
        center = Offset(size.width * (0.08f + 0.05f * sin(phase * 0.25f)), size.height * 0.32f),
        radius = size.width * 0.48f,
        color = Color(0xFFFF883B),
        alpha = 0.11f * flicker,
    )
    val gateInset = 12.dp.toPx()
    drawLine(
        brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.5f to Color(0xFFF3E2C3).copy(alpha = 0.055f),
            1f to Color.Transparent,
        ),
        start = Offset(gateInset, size.height * 0.11f),
        end = Offset(size.width - gateInset, size.height * 0.11f),
        strokeWidth = 0.7.dp.toPx(),
    )
    drawLine(
        brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.5f to Color(0xFFF3E2C3).copy(alpha = 0.055f),
            1f to Color.Transparent,
        ),
        start = Offset(gateInset, size.height * 0.89f),
        end = Offset(size.width - gateInset, size.height * 0.89f),
        strokeWidth = 0.7.dp.toPx(),
    )
    // Soft analog dust motes & subtle hair-wisps.
    for ((idx, p) in particles.take(26).withIndex()) {
        val x = ((p.x + sin(phase * 2.4f + idx) * 0.012f) % 1f + 1f) % 1f * size.width
        val yPos = ((p.y + cos(phase * 1.9f + idx) * 0.018f) % 1f + 1f) % 1f * size.height
        if (idx % 9 == 0) {
            val h = (18f + p.size * 22f).dp.toPx()
            drawLine(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.5f to Color(0xFFF5E3C6).copy(alpha = 0.07f * flicker),
                    1f to Color.Transparent,
                    startY = yPos,
                    endY = yPos + h,
                ),
                start = Offset(x, yPos),
                end = Offset(x + 1.5.dp.toPx() * sin(phase + idx), yPos + h),
                strokeWidth = 0.65.dp.toPx(),
            )
        } else {
            drawSoftBokeh(
                center = Offset(x, yPos),
                radius = (1.6f + p.size * 1.1f).dp.toPx(),
                color = Color(0xFFF2DFBE),
                alpha = 0.14f * flicker,
            )
        }
    }
}
