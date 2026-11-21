// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import btm.m.auraplayer.data.HomeBackgroundStyle
import btm.m.auraplayer.data.LocalBannerStyle
import btm.m.auraplayer.data.LocalBannerStyleConfig
import btm.m.auraplayer.data.MotionPhotoSupport
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.*

@Immutable
internal data class HomeBannerTheme(
    val isCustom: Boolean,
    val surfaceColor: Color,
    val topBarTint: Color,
    val heroMixTint: Color,
    val auraStart: Color,
    val auraEnd: Color,
    val cardContainer: Color,
    val accent: Color,
    val onAccent: Color,
    val mutedInk: Color,
    val lowerRegionL: Float = 0.5f,
    val lowerRegionA: Float = 0f,
    val lowerRegionB: Float = 0f,
    val hueUnitA: Float = 0f,
    val hueUnitB: Float = 0f,
    val dominantChroma: Float = 0f,
)

@Immutable
internal data class BannerAdaptiveTextColors(
    val titleColor: Color,
    val subtitleColor: Color,
    val avatarRingColor: Color,
)

private data class OklabColor(
    val l: Float,
    val a: Float,
    val b: Float,
)

private data class ExtractedDominantLab(
    val l: Float,
    val unitA: Float,
    val unitB: Float,
    val chroma: Float,
    val lowerL: Float = l,
    val lowerA: Float = 0f,
    val lowerB: Float = 0f,
)

private const val THEME_CACHE_LIMIT = 64
private val extractedLabCache = LinkedHashMap<String, ExtractedDominantLab>(16, 0.75f, true)

/**
 * Self-contained restrained dominant color extractor using Oklab hue clustering and asymptotic
 * chroma soft-capping (independent of Material Kolor / Monet).
 */
@Composable
internal fun rememberHomeBannerTheme(bannerUri: String): HomeBannerTheme {
    val context = LocalContext.current
    val baseSurface = MiuixTheme.colorScheme.surface
    val dark = baseSurface.luminance() < 0.5f
    val defaultPrimary = MiuixTheme.colorScheme.primary
    val defaultOnPrimary = MiuixTheme.colorScheme.onPrimary
    val defaultPrimaryContainer = MiuixTheme.colorScheme.primaryContainer
    val defaultTertiaryContainer = MiuixTheme.colorScheme.tertiaryContainer
    val defaultCardContainer = MiuixTheme.colorScheme.surfaceContainer
    val defaultMutedInk = MiuixTheme.colorScheme.onSurfaceVariantSummary

    val initialLab = remember(bannerUri) {
        if (bannerUri.isBlank()) null else synchronized(extractedLabCache) { extractedLabCache[bannerUri] }
    }

    val extractedLab by produceState(initialValue = initialLab, bannerUri, context) {
        if (bannerUri.isBlank()) {
            value = null
            return@produceState
        }
        synchronized(extractedLabCache) { extractedLabCache[bannerUri] }?.let {
            value = it
            return@produceState
        }
        val computed = withContext(Dispatchers.IO) {
            val bitmap = decodeBannerSampleBitmap(context, bannerUri) ?: return@withContext null
            try {
                extractRestrainedDominantLab(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
        if (computed != null) {
            synchronized(extractedLabCache) {
                extractedLabCache[bannerUri] = computed
                while (extractedLabCache.size > THEME_CACHE_LIMIT) {
                    val oldest = extractedLabCache.entries.firstOrNull()?.key ?: break
                    extractedLabCache.remove(oldest)
                }
            }
        }
        value = computed
    }

    val rawTheme = remember(
        extractedLab,
        dark,
        baseSurface,
        defaultPrimary,
        defaultOnPrimary,
        defaultPrimaryContainer,
        defaultTertiaryContainer,
        defaultCardContainer,
        defaultMutedInk,
        bannerUri,
    ) {
        val lab = extractedLab
        if (bannerUri.isBlank() || lab == null) {
            HomeBannerTheme(
                isCustom = false,
                surfaceColor = baseSurface,
                topBarTint = baseSurface,
                heroMixTint = defaultPrimaryContainer,
                auraStart = defaultPrimaryContainer,
                auraEnd = defaultTertiaryContainer,
                cardContainer = defaultCardContainer,
                accent = defaultPrimary,
                onAccent = defaultOnPrimary,
                mutedInk = defaultMutedInk,
            )
        } else {
            deriveRestrainedTheme(lab = lab, dark = dark)
        }
    }

    val spec = tween<Color>(durationMillis = 420, easing = FastOutSlowInEasing)
    return HomeBannerTheme(
        isCustom = rawTheme.isCustom,
        surfaceColor = animateColorAsState(rawTheme.surfaceColor, spec, label = "bannerThemeSurface").value,
        topBarTint = animateColorAsState(rawTheme.topBarTint, spec, label = "bannerThemeTopBar").value,
        heroMixTint = animateColorAsState(rawTheme.heroMixTint, spec, label = "bannerThemeHeroMix").value,
        auraStart = animateColorAsState(rawTheme.auraStart, spec, label = "bannerThemeAuraStart").value,
        auraEnd = animateColorAsState(rawTheme.auraEnd, spec, label = "bannerThemeAuraEnd").value,
        cardContainer = animateColorAsState(rawTheme.cardContainer, spec, label = "bannerThemeCard").value,
        accent = animateColorAsState(rawTheme.accent, spec, label = "bannerThemeAccent").value,
        onAccent = animateColorAsState(rawTheme.onAccent, spec, label = "bannerThemeOnAccent").value,
        mutedInk = animateColorAsState(rawTheme.mutedInk, spec, label = "bannerThemeMutedInk").value,
        lowerRegionL = rawTheme.lowerRegionL,
        lowerRegionA = rawTheme.lowerRegionA,
        lowerRegionB = rawTheme.lowerRegionB,
        hueUnitA = rawTheme.hueUnitA,
        hueUnitB = rawTheme.hueUnitB,
        dominantChroma = rawTheme.dominantChroma,
    )
}

/**
 * Computes contrast-safe banner foreground colors in Oklab space from the actual composited
 * lower-banner region behind `HomeProfileRow`.
 */
@Composable
internal fun rememberAdaptiveBannerTextColors(
    bannerUri: String,
    bannerStyle: LocalBannerStyle,
    styleConfig: LocalBannerStyleConfig,
    bannerTheme: HomeBannerTheme,
): BannerAdaptiveTextColors {
    val surface = MiuixTheme.colorScheme.surface
    val surfaceContainer = MiuixTheme.colorScheme.surfaceContainer
    val dark = surface.luminance() < 0.5f
    val onSurface = MiuixTheme.colorScheme.onSurface
    val onSurfaceVar = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val onPrimaryContainer = MiuixTheme.colorScheme.onPrimaryContainer
    val advancedMaterial = LocalAdvancedMaterialEnabled.current

    val raw = remember(
        bannerUri,
        bannerStyle,
        styleConfig,
        bannerTheme,
        surface,
        surfaceContainer,
        dark,
        onSurface,
        onSurfaceVar,
        onPrimaryContainer,
        advancedMaterial,
    ) {
        if (bannerUri.isBlank() || !bannerTheme.isCustom) {
            return@remember when (bannerStyle) {
                LocalBannerStyle.ROUNDED_CARD -> BannerAdaptiveTextColors(
                    titleColor = onPrimaryContainer,
                    subtitleColor = onPrimaryContainer.copy(alpha = 0.8f),
                    avatarRingColor = onPrimaryContainer.copy(alpha = 0.25f),
                )
                else -> BannerAdaptiveTextColors(
                    titleColor = onSurface,
                    subtitleColor = onSurfaceVar,
                    avatarRingColor = onSurface.copy(alpha = 0.14f),
                )
            }
        }

        val lowerLab = OklabColor(bannerTheme.lowerRegionL, bannerTheme.lowerRegionA, bannerTheme.lowerRegionB)
        val surfaceLab = srgbToOklab(surface.toArgb())
        val themeSurfaceLab = srgbToOklab(bannerTheme.surfaceColor.toArgb())
        val surfaceContLab = srgbToOklab(surfaceContainer.toArgb())

        val advancedGlass = advancedMaterial && isRuntimeShaderSupported()
        val glassAlpha = if (!advancedGlass) {
            1f
        } else {
            (0.42f * styleConfig.glassOpacity / 100f).coerceIn(0f, 1f)
        }

        fun blendLab(a: OklabColor, b: OklabColor, weightB: Float): OklabColor {
            val w = weightB.coerceIn(0f, 1f)
            return OklabColor(
                l = a.l * (1f - w) + b.l * w,
                a = a.a * (1f - w) + b.a * w,
                b = a.b * (1f - w) + b.b * w,
            )
        }

        val effectiveBg = when (bannerStyle) {
            LocalBannerStyle.NONE -> surfaceLab
            LocalBannerStyle.ROUNDED_CARD -> {
                // Bottom dark gradient scrim composites ~54% black over the lower banner region.
                blendLab(lowerLab, OklabColor(0f, 0f, 0f), 0.54f)
            }
            LocalBannerStyle.IMMERSIVE -> when (styleConfig.backgroundStyle) {
                HomeBackgroundStyle.DEFAULT -> blendLab(lowerLab, surfaceLab, 0.58f)
                HomeBackgroundStyle.COLOR -> blendLab(lowerLab, themeSurfaceLab, 0.58f)
                HomeBackgroundStyle.COLOR_BLUR -> blendLab(lowerLab, themeSurfaceLab, 0.36f)
            }
            LocalBannerStyle.IMMERSIVE_GLASS -> {
                val behindGlass = when (styleConfig.backgroundStyle) {
                    HomeBackgroundStyle.DEFAULT -> blendLab(lowerLab, surfaceLab, 0.48f)
                    HomeBackgroundStyle.COLOR -> blendLab(lowerLab, themeSurfaceLab, 0.48f)
                    HomeBackgroundStyle.COLOR_BLUR -> blendLab(lowerLab, themeSurfaceLab, 0.34f)
                }
                blendLab(behindGlass, surfaceLab, glassAlpha)
            }
            LocalBannerStyle.GLASS_POSTER -> blendLab(lowerLab, surfaceLab, glassAlpha)
            LocalBannerStyle.AURORA_AMBIENT -> {
                val auraLab = srgbToOklab(bannerTheme.auraStart.toArgb())
                val photoBase = blendLab(surfaceContLab, lowerLab, if (dark) 0.34f else 0.28f)
                blendLab(photoBase, auraLab, 0.34f)
            }
        }

        // Local lower-region hue blended with dominant banner hue gives natural tinted ink.
        val localA = effectiveBg.a * 0.6f + bannerTheme.hueUnitA * bannerTheme.dominantChroma * 0.4f
        val localB = effectiveBg.b * 0.6f + bannerTheme.hueUnitB * bannerTheme.dominantChroma * 0.4f
        val localC = hypot(localA, localB)
        val unitA = if (localC > 1e-5f) localA / localC else bannerTheme.hueUnitA
        val unitB = if (localC > 1e-5f) localB / localC else bannerTheme.hueUnitB
        val hueSignal = max(localC, bannerTheme.dominantChroma * 0.7f)

        fun cap(maxC: Float): Float = (maxC * (hueSignal / (hueSignal + maxC * 0.8f))).coerceIn(0f, maxC)

        val needLightInk = effectiveBg.l < 0.57f
        val titleColor: Color
        val subtitleColor: Color
        if (needLightInk) {
            val cTitle = cap(0.022f)
            val cSub = cap(0.036f)
            titleColor = Color(oklabToSrgbSafe(0.965f, unitA * cTitle, unitB * cTitle))
            subtitleColor = Color(oklabToSrgbSafe(0.840f, unitA * cSub, unitB * cSub))
        } else {
            val cTitle = cap(0.030f)
            val cSub = cap(0.044f)
            titleColor = Color(oklabToSrgbSafe(0.210f, unitA * cTitle, unitB * cTitle))
            subtitleColor = Color(oklabToSrgbSafe(0.380f, unitA * cSub, unitB * cSub))
        }

        BannerAdaptiveTextColors(
            titleColor = titleColor,
            subtitleColor = subtitleColor,
            avatarRingColor = titleColor.copy(alpha = 0.24f),
        )
    }

    val animSpec = tween<Color>(durationMillis = 360, easing = FastOutSlowInEasing)
    return BannerAdaptiveTextColors(
        titleColor = animateColorAsState(raw.titleColor, animSpec, label = "bannerAdaptiveTitle").value,
        subtitleColor = animateColorAsState(raw.subtitleColor, animSpec, label = "bannerAdaptiveSub").value,
        avatarRingColor = animateColorAsState(raw.avatarRingColor, animSpec, label = "bannerAdaptiveRing").value,
    )
}

private suspend fun decodeBannerSampleBitmap(context: Context, bannerUri: String): Bitmap? {
    val file = MotionPhotoSupport.resolveLocalFile(bannerUri)
    if (file != null) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 64 && bounds.outHeight / (sample * 2) >= 64) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts)
            if (decoded != null) return decoded
        }
        val viaImageDecoder = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                if (longest > 96) {
                    val ratio = 96f / longest.toFloat()
                    decoder.setTargetSize(
                        (info.size.width * ratio).roundToInt().coerceAtLeast(1),
                        (info.size.height * ratio).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        }.getOrNull()
        if (viaImageDecoder != null) return viaImageDecoder
    }

    val imageLoader = SingletonImageLoader.get(context)
    val result = runCatching {
        (imageLoader.execute(
            ImageRequest.Builder(context)
                .data(bannerUri)
                .size(96)
                .allowHardware(false)
                .build(),
        ) as? SuccessResult)?.image?.toBitmap()
    }.getOrNull() ?: return null
    return result.copy(Bitmap.Config.ARGB_8888, false)
}

private fun extractRestrainedDominantLab(bitmap: Bitmap): ExtractedDominantLab {
    val grid = 40
    val stepX = (bitmap.width / grid.toFloat()).coerceAtLeast(1f)
    val stepY = (bitmap.height / grid.toFloat()).coerceAtLeast(1f)

    val binCount = 24
    val binWeight = FloatArray(binCount)
    val sampleCount = grid * grid
    val labs = ArrayList<OklabColor>(sampleCount)
    val weights = FloatArray(sampleCount)
    val chromas = FloatArray(sampleCount)
    val hues = FloatArray(sampleCount)
    var validCount = 0

    var totalChromaticWeight = 0f
    var totalWeight = 0f
    var neutralSumW = 0f
    var neutralSumL = 0f
    var neutralSumA = 0f
    var neutralSumB = 0f
    var lowerSumW = 0f
    var lowerSumL = 0f
    var lowerSumA = 0f
    var lowerSumB = 0f

    for (gy in 0 until grid) {
        val py = (gy * stepY).toInt().coerceIn(0, bitmap.height - 1)
        val ny = gy / (grid - 1f)
        for (gx in 0 until grid) {
            val px = (gx * stepX).toInt().coerceIn(0, bitmap.width - 1)
            val argb = bitmap.getPixel(px, py)
            if (android.graphics.Color.alpha(argb) < 128) continue

            val lab = srgbToOklab(argb)
            val chroma = hypot(lab.a, lab.b)
            val nx = gx / (grid - 1f)
            if (ny >= 0.50f && nx in 0.04f..0.88f) {
                lowerSumW += 1f
                lowerSumL += lab.l
                lowerSumA += lab.a
                lowerSumB += lab.b
            }
            val spatial = 0.72f + 0.28f * (1f - abs(nx - 0.5f) * 1.4f).coerceAtLeast(0f) * (0.7f + 0.45f * ny)
            val lumaGate = when {
                lab.l < 0.13f || lab.l > 0.94f -> 0.32f
                lab.l < 0.22f || lab.l > 0.86f -> 0.72f
                else -> 1f
            }
            val baseWeight = spatial * lumaGate
            totalWeight += baseWeight

            if (chroma >= 0.028f) {
                var angle = atan2(lab.b, lab.a)
                if (angle < 0f) angle += (2f * PI.toFloat())
                val bin = ((angle / (2f * PI.toFloat())) * binCount).toInt().coerceIn(0, binCount - 1)
                val chromaEmphasis = 1f + 3.0f * min(chroma, 0.16f)
                val w = baseWeight * chromaEmphasis
                binWeight[bin] += w
                totalChromaticWeight += w

                labs.add(lab)
                weights[validCount] = w
                chromas[validCount] = chroma
                hues[validCount] = angle
                validCount++
            } else {
                neutralSumW += baseWeight
                neutralSumL += lab.l * baseWeight
                neutralSumA += lab.a * baseWeight
                neutralSumB += lab.b * baseWeight
            }
        }
    }

    val avgLowerL = if (lowerSumW > 0f) lowerSumL / lowerSumW else 0.5f
    val avgLowerA = if (lowerSumW > 0f) lowerSumA / lowerSumW else 0f
    val avgLowerB = if (lowerSumW > 0f) lowerSumB / lowerSumW else 0f

    if (totalWeight <= 0f) {
        return ExtractedDominantLab(0.5f, 0f, 0f, 0f)
    }

    // Circular [0.22, 0.56, 0.22] smooth across adjacent hue sectors.
    val smoothed = FloatArray(binCount)
    var bestBin = 0
    var bestWeight = -1f
    for (i in 0 until binCount) {
        val prev = binWeight[(i + binCount - 1) % binCount]
        val curr = binWeight[i]
        val next = binWeight[(i + 1) % binCount]
        val valSmoothed = 0.22f * prev + 0.56f * curr + 0.22f * next
        smoothed[i] = valSmoothed
        if (valSmoothed > bestWeight) {
            bestWeight = valSmoothed
            bestBin = i
        }
    }

    // Monochrome / neutral photograph: keep subtle neutral warmth or slate cool tone.
    if (bestWeight <= 0f || totalChromaticWeight < totalWeight * 0.16f) {
        val w = neutralSumW.coerceAtLeast(1e-4f)
        val avgL = (neutralSumL / w).coerceIn(0.25f, 0.85f)
        val avgA = neutralSumA / w
        val avgB = neutralSumB / w
        val c = hypot(avgA, avgB)
        return if (c > 1e-4f) {
            ExtractedDominantLab(avgL, avgA / c, avgB / c, min(c, 0.016f), avgLowerL, avgLowerA, avgLowerB)
        } else {
            ExtractedDominantLab(avgL, 0f, 0f, 0f, avgLowerL, avgLowerA, avgLowerB)
        }
    }

    // Weighted centroid within +/- 28 deg around winning hue bin so opposing colors don't turn muddy.
    val centerHue = ((bestBin + 0.5f) / binCount) * (2f * PI.toFloat())
    val maxDelta = (28f * PI.toFloat()) / 180f
    var sumW = 0f
    var sumL = 0f
    var sumA = 0f
    var sumB = 0f
    for (i in 0 until validCount) {
        val delta = angularDistance(hues[i], centerHue)
        if (delta <= maxDelta) {
            val falloff = 1f - 0.5f * (delta / maxDelta)
            val w = weights[i] * falloff
            val lab = labs[i]
            sumW += w
            sumL += lab.l * w
            sumA += lab.a * w
            sumB += lab.b * w
        }
    }

    if (sumW <= 1e-4f) {
        return ExtractedDominantLab(0.55f, cos(centerHue), sin(centerHue), 0.04f, avgLowerL, avgLowerA, avgLowerB)
    }

    val meanL = sumL / sumW
    val meanA = sumA / sumW
    val meanB = sumB / sumW
    val chroma = hypot(meanA, meanB)
    return if (chroma > 1e-5f) {
        ExtractedDominantLab(meanL, meanA / chroma, meanB / chroma, chroma, avgLowerL, avgLowerA, avgLowerB)
    } else {
        ExtractedDominantLab(meanL, 0f, 0f, 0f, avgLowerL, avgLowerA, avgLowerB)
    }
}

private fun deriveRestrainedTheme(lab: ExtractedDominantLab, dark: Boolean): HomeBannerTheme {
    fun softCapChroma(rawC: Float, limit: Float): Float {
        if (rawC <= 1e-5f || limit <= 0f) return 0f
        return (limit * (rawC / (rawC + limit * 0.72f))).coerceIn(0f, limit)
    }

    fun buildColor(targetL: Float, maxC: Float): Color {
        val c = softCapChroma(lab.chroma, maxC)
        return Color(oklabToSrgbSafe(targetL, lab.unitA * c, lab.unitB * c))
    }

    fun rotateColor(targetL: Float, maxC: Float, radOffset: Float): Color {
        val c = softCapChroma(lab.chroma, maxC)
        val baseAngle = atan2(lab.unitB, lab.unitA)
        val shifted = baseAngle + radOffset
        return Color(oklabToSrgbSafe(targetL, cos(shifted) * c, sin(shifted) * c))
    }

    return if (dark) {
        HomeBannerTheme(
            isCustom = true,
            surfaceColor = buildColor(targetL = 0.175f, maxC = 0.028f),
            topBarTint = buildColor(targetL = 0.190f, maxC = 0.034f),
            heroMixTint = buildColor(targetL = 0.265f, maxC = 0.072f),
            auraStart = buildColor(targetL = 0.300f, maxC = 0.085f),
            auraEnd = rotateColor(targetL = 0.255f, maxC = 0.070f, radOffset = 0.45f),
            cardContainer = buildColor(targetL = 0.245f, maxC = 0.044f),
            accent = buildColor(targetL = 0.720f, maxC = 0.105f),
            onAccent = buildColor(targetL = 0.180f, maxC = 0.024f),
            mutedInk = buildColor(targetL = 0.740f, maxC = 0.026f),
            lowerRegionL = lab.lowerL,
            lowerRegionA = lab.lowerA,
            lowerRegionB = lab.lowerB,
            hueUnitA = lab.unitA,
            hueUnitB = lab.unitB,
            dominantChroma = lab.chroma,
        )
    } else {
        HomeBannerTheme(
            isCustom = true,
            surfaceColor = buildColor(targetL = 0.946f, maxC = 0.020f),
            topBarTint = buildColor(targetL = 0.938f, maxC = 0.025f),
            heroMixTint = buildColor(targetL = 0.855f, maxC = 0.056f),
            auraStart = buildColor(targetL = 0.875f, maxC = 0.058f),
            auraEnd = rotateColor(targetL = 0.895f, maxC = 0.050f, radOffset = 0.45f),
            cardContainer = buildColor(targetL = 0.916f, maxC = 0.036f),
            accent = buildColor(targetL = 0.500f, maxC = 0.110f),
            onAccent = Color.White,
            mutedInk = buildColor(targetL = 0.440f, maxC = 0.036f),
            lowerRegionL = lab.lowerL,
            lowerRegionA = lab.lowerA,
            lowerRegionB = lab.lowerB,
            hueUnitA = lab.unitA,
            hueUnitB = lab.unitB,
            dominantChroma = lab.chroma,
        )
    }
}

private fun angularDistance(a: Float, b: Float): Float {
    var diff = abs(a - b) % (2f * PI.toFloat())
    if (diff > PI.toFloat()) diff = (2f * PI.toFloat()) - diff
    return diff
}

private fun srgbToOklab(argb: Int): OklabColor {
    fun toLinear(channel: Int): Float {
        val c = channel / 255f
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    val r = toLinear(android.graphics.Color.red(argb))
    val g = toLinear(android.graphics.Color.green(argb))
    val b = toLinear(android.graphics.Color.blue(argb))

    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)

    return OklabColor(
        l = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        b = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
}

private fun oklabToLinearRgb(lVal: Float, aVal: Float, bVal: Float): Triple<Float, Float, Float> {
    val l = lVal + 0.3963377774f * aVal + 0.2158037573f * bVal
    val m = lVal - 0.1055613458f * aVal - 0.0638541728f * bVal
    val s = lVal - 0.0894841775f * aVal - 1.2914855480f * bVal

    val l3 = l * l * l
    val m3 = m * m * m
    val s3 = s * s * s

    val r = +4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
    val g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
    val b = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
    return Triple(r, g, b)
}

private fun oklabToSrgbSafe(targetL: Float, targetA: Float, targetB: Float): Int {
    // Keep hue constant by scaling (a, b) radially inside sRGB gamut if clipped.
    var scale = 1f
    val (r0, g0, b0) = oklabToLinearRgb(targetL, targetA, targetB)
    if (r0 < 0f || r0 > 1f || g0 < 0f || g0 > 1f || b0 < 0f || b0 > 1f) {
        var lo = 0f
        var hi = 1f
        repeat(8) {
            val mid = (lo + hi) * 0.5f
            val (rm, gm, bm) = oklabToLinearRgb(targetL, targetA * mid, targetB * mid)
            if (rm in 0f..1f && gm in 0f..1f && bm in 0f..1f) lo = mid else hi = mid
        }
        scale = lo
    }

    val (rLin, gLin, bLin) = oklabToLinearRgb(targetL, targetA * scale, targetB * scale)
    fun toSrgb8(linear: Float): Int {
        val c = linear.coerceIn(0f, 1f)
        val gamma = if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
        return (gamma * 255f).roundToInt().coerceIn(0, 255)
    }

    return android.graphics.Color.argb(255, toSrgb8(rLin), toSrgb8(gLin), toSrgb8(bLin))
}
