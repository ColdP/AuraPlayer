// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import btm.m.auraplayer.data.ColorExtractionMode
import btm.m.auraplayer.ui.player.PlayerExpressiveColors
import btm.m.auraplayer.ui.player.PlayerOneUiColors
import dev.pascal.oneuikit.MonetTones
import top.yukonga.miuix.kmp.theme.Colors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val DEFAULT_OKLAB_SEED: Int = 0xFF6E92D8.toInt()

@Immutable
internal data class AuraColorStyle(
    val mode: ColorExtractionMode = ColorExtractionMode.OFF,
    val palette: Int = 0,
) {
    val isOklab: Boolean get() = mode == ColorExtractionMode.OKLAB
    val isMonet: Boolean get() = mode == ColorExtractionMode.MONET
    val isDynamicEnabled: Boolean get() = mode != ColorExtractionMode.OFF
}

internal val LocalAuraColorStyle = staticCompositionLocalOf { AuraColorStyle() }

internal data class OklabTuple(
    val l: Float,
    val a: Float,
    val b: Float,
)

private data class OklabPaletteProfile(
    val primaryH: Float,
    val primaryC: Float,
    val secondaryH: Float,
    val secondaryC: Float,
    val tertiaryH: Float,
    val tertiaryC: Float,
    val neutralH: Float,
    val neutralC: Float,
    val neutralVariantC: Float,
    val isMonochrome: Boolean = false,
)

@Immutable
internal data class OklabColorScheme(
    val dark: Boolean,
    val surface: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val outline: Color,
    val outlineVariant: Color,
    val oneUiBackground: Color,
    val oneUiCard: Color,
    val oneUiCardAlt: Color,
    val oneUiText: Color,
    val oneUiSubText: Color,
    val oneUiDivider: Color,
    val oneUiAccent: Color,
    val oneUiAccentDeep: Color,
    val oneUiOnAccent: Color,
    val oneUiChip: Color,
    val oneUiOnChip: Color,
    val oneUiTrack: Color,
    val oneUiSegmentTrack: Color,
    val oneUiSegmentThumb: Color,
)

@Immutable
internal data class OklabPlayerAmbientPalette(
    val dark: Boolean,
    val base: Color,
    val surface: Color,
    val orbPrimary: Color,
    val orbSecondary: Color,
    val orbTertiary: Color,
    val menuTint: Color,
    val activeInk: Color,
    val secondaryInk: Color,
    val glowAccent: Color,
    val heroMixTint: Color,
    val accent: Color,
    val onAccent: Color,
)

internal object OklabThemeEngine {
    private const val DEG_TO_RAD = (PI / 180.0).toFloat()

    fun srgbToOklab(argb: Int): OklabTuple {
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
        return OklabTuple(
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

    fun oklabToSrgbSafe(targetL: Float, targetA: Float, targetB: Float): Int {
        val clampedL = targetL.coerceIn(0f, 1f)
        var scale = 1f
        val (r0, g0, b0) = oklabToLinearRgb(clampedL, targetA, targetB)
        if (r0 < 0f || r0 > 1f || g0 < 0f || g0 > 1f || b0 < 0f || b0 > 1f) {
            var lo = 0f
            var hi = 1f
            repeat(10) {
                val mid = (lo + hi) * 0.5f
                val (rm, gm, bm) = oklabToLinearRgb(clampedL, targetA * mid, targetB * mid)
                if (rm in 0f..1f && gm in 0f..1f && bm in 0f..1f) lo = mid else hi = mid
            }
            scale = lo
        }
        val (rLin, gLin, bLin) = oklabToLinearRgb(clampedL, targetA * scale, targetB * scale)
        fun toSrgb8(linear: Float): Int {
            val c = linear.coerceIn(0f, 1f)
            val gamma = if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
            return (gamma * 255f).roundToInt().coerceIn(0, 255)
        }
        return android.graphics.Color.argb(255, toSrgb8(rLin), toSrgb8(gLin), toSrgb8(bLin))
    }

    fun oklchToArgb(l: Float, c: Float, hRad: Float): Int {
        val safeC = c.coerceAtLeast(0f)
        return oklabToSrgbSafe(l, cos(hRad) * safeC, sin(hRad) * safeC)
    }

    fun oklchColor(l: Float, c: Float, hRad: Float): Color = Color(oklchToArgb(l, c, hRad))

    private fun relativeLuminance(argb: Int): Double {
        fun lin(ch: Int): Double {
            val v = ch / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = lin(android.graphics.Color.red(argb))
        val g = lin(android.graphics.Color.green(argb))
        val b = lin(android.graphics.Color.blue(argb))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun contrastRatio(fgArgb: Int, bgArgb: Int): Double {
        val l1 = relativeLuminance(fgArgb)
        val l2 = relativeLuminance(bgArgb)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun ensureContrastArgb(
        fgL: Float,
        fgC: Float,
        fgH: Float,
        bgArgb: Int,
        minRatio: Double,
    ): Int {
        val initial = oklchToArgb(fgL, fgC, fgH)
        if (contrastRatio(initial, bgArgb) >= minRatio) return initial
        val bgLum = relativeLuminance(bgArgb)
        val moveLighter = bgLum < 0.32
        var bestL = fgL
        var best = initial
        var bestRatio = contrastRatio(initial, bgArgb)
        for (step in 1..24) {
            val t = step / 24f
            val candL = if (moveLighter) fgL + (0.99f - fgL) * t else fgL * (1f - t)
            val candC = fgC * (1f - t * 0.45f)
            val cand = oklchToArgb(candL, candC, fgH)
            val ratio = contrastRatio(cand, bgArgb)
            if (ratio > bestRatio) {
                bestRatio = ratio
                best = cand
                bestL = candL
            }
            if (ratio >= minRatio) return cand
        }
        val fallback = if (moveLighter) 0xFFFFFFFF.toInt() else 0xFF101010.toInt()
        return if (contrastRatio(fallback, bgArgb) > contrastRatio(best, bgArgb) && bestL in 0.01f..0.99f) {
            fallback
        } else {
            best
        }
    }

    /**
     * Extracts a perceptually balanced dominant seed color from a bitmap using Oklab
     * 24-bin hue clustering and weighted centroid averaging.
     */
    fun extractOklabSeed(bitmap: Bitmap?, fallback: Int = DEFAULT_OKLAB_SEED): Int {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return fallback
        val grid = 40
        val stepX = (bitmap.width / grid.toFloat()).coerceAtLeast(1f)
        val stepY = (bitmap.height / grid.toFloat()).coerceAtLeast(1f)
        val binCount = 24
        val binWeight = FloatArray(binCount)
        val sampleCount = grid * grid
        val labs = ArrayList<OklabTuple>(sampleCount)
        val weights = FloatArray(sampleCount)
        val hues = FloatArray(sampleCount)
        var validCount = 0

        var totalWeight = 0f
        var totalChromaticWeight = 0f
        var neutralW = 0f
        var neutralL = 0f

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
                val spatial = 0.72f + 0.28f * (1f - abs(nx - 0.5f) * 1.4f).coerceAtLeast(0f) * (0.75f + 0.35f * ny)
                val lumaGate = when {
                    lab.l < 0.12f || lab.l > 0.94f -> 0.30f
                    lab.l < 0.22f || lab.l > 0.86f -> 0.72f
                    else -> 1f
                }
                val baseWeight = spatial * lumaGate
                totalWeight += baseWeight

                if (chroma >= 0.025f) {
                    var angle = atan2(lab.b, lab.a)
                    if (angle < 0f) angle += (2f * PI.toFloat())
                    val bin = ((angle / (2f * PI.toFloat())) * binCount).toInt().coerceIn(0, binCount - 1)
                    val w = baseWeight * (1f + 3.2f * min(chroma, 0.18f))
                    binWeight[bin] += w
                    totalChromaticWeight += w
                    labs.add(lab)
                    weights[validCount] = w
                    hues[validCount] = angle
                    validCount++
                } else {
                    neutralW += baseWeight
                    neutralL += lab.l * baseWeight
                }
            }
        }

        if (totalWeight <= 0f) return fallback

        var bestBin = 0
        var bestWeight = -1f
        for (i in 0 until binCount) {
            val prev = binWeight[(i + binCount - 1) % binCount]
            val curr = binWeight[i]
            val next = binWeight[(i + 1) % binCount]
            val smoothed = 0.22f * prev + 0.56f * curr + 0.22f * next
            if (smoothed > bestWeight) {
                bestWeight = smoothed
                bestBin = i
            }
        }

        if (bestWeight <= 0f || totalChromaticWeight < totalWeight * 0.14f) {
            val avgL = if (neutralW > 0f) (neutralL / neutralW).coerceIn(0.28f, 0.78f) else 0.52f
            return oklchToArgb(avgL, 0f, 0f)
        }

        val centerHue = ((bestBin + 0.5f) / binCount) * (2f * PI.toFloat())
        val maxDelta = 28f * DEG_TO_RAD
        var sumW = 0f
        var sumL = 0f
        var sumA = 0f
        var sumB = 0f
        for (i in 0 until validCount) {
            var diff = abs(hues[i] - centerHue) % (2f * PI.toFloat())
            if (diff > PI.toFloat()) diff = (2f * PI.toFloat()) - diff
            if (diff <= maxDelta) {
                val w = weights[i] * (1f - 0.5f * (diff / maxDelta))
                val lab = labs[i]
                sumW += w
                sumL += lab.l * w
                sumA += lab.a * w
                sumB += lab.b * w
            }
        }
        if (sumW <= 1e-4f) return oklchToArgb(0.58f, 0.11f, centerHue)
        val meanL = (sumL / sumW).coerceIn(0.32f, 0.78f)
        val meanA = sumA / sumW
        val meanB = sumB / sumW
        val c = hypot(meanA, meanB).coerceIn(0.03f, 0.20f)
        val h = atan2(meanB, meanA)
        return oklchToArgb(meanL, c, h)
    }

    private fun resolveProfile(seedArgb: Int, paletteIndex: Int): OklabPaletteProfile {
        val lab = srgbToOklab(seedArgb)
        val seedC = hypot(lab.a, lab.b)
        val h = atan2(lab.b, lab.a)
        if (paletteIndex == 6 || seedC < 0.012f) {
            return OklabPaletteProfile(
                primaryH = 0f,
                primaryC = 0f,
                secondaryH = 0f,
                secondaryC = 0f,
                tertiaryH = 0f,
                tertiaryC = 0f,
                neutralH = 0f,
                neutralC = 0f,
                neutralVariantC = 0f,
                isMonochrome = true,
            )
        }
        return when (paletteIndex.coerceIn(0, 8)) {
            // 0: 柔和彩调 (TonalSpot)
            0 -> OklabPaletteProfile(
                primaryH = h,
                primaryC = 0.135f,
                secondaryH = h,
                secondaryC = 0.056f,
                tertiaryH = h + 60f * DEG_TO_RAD,
                tertiaryC = 0.088f,
                neutralH = h,
                neutralC = 0.013f,
                neutralVariantC = 0.022f,
            )
            // 1: 中性 (Neutral)
            1 -> OklabPaletteProfile(
                primaryH = h,
                primaryC = 0.052f,
                secondaryH = h,
                secondaryC = 0.030f,
                tertiaryH = h,
                tertiaryC = 0.045f,
                neutralH = h,
                neutralC = 0.008f,
                neutralVariantC = 0.012f,
            )
            // 2: 鲜艳 (Vibrant)
            2 -> OklabPaletteProfile(
                primaryH = h,
                primaryC = 0.180f,
                secondaryH = h + 18f * DEG_TO_RAD,
                secondaryC = 0.095f,
                tertiaryH = h + 48f * DEG_TO_RAD,
                tertiaryC = 0.132f,
                neutralH = h,
                neutralC = 0.020f,
                neutralVariantC = 0.034f,
            )
            // 3: 丰富表现 (Expressive)
            3 -> OklabPaletteProfile(
                primaryH = h + 16f * DEG_TO_RAD,
                primaryC = 0.160f,
                secondaryH = h + 95f * DEG_TO_RAD,
                secondaryC = 0.105f,
                tertiaryH = h + 200f * DEG_TO_RAD,
                tertiaryC = 0.135f,
                neutralH = h + 12f * DEG_TO_RAD,
                neutralC = 0.022f,
                neutralVariantC = 0.036f,
            )
            // 4: 彩虹 (Rainbow)
            4 -> OklabPaletteProfile(
                primaryH = h,
                primaryC = 0.165f,
                secondaryH = h + 120f * DEG_TO_RAD,
                secondaryC = 0.135f,
                tertiaryH = h + 240f * DEG_TO_RAD,
                tertiaryC = 0.145f,
                neutralH = h,
                neutralC = 0.007f,
                neutralVariantC = 0.015f,
            )
            // 5: 水果沙拉 (FruitSalad)
            5 -> OklabPaletteProfile(
                primaryH = h - 42f * DEG_TO_RAD,
                primaryC = 0.160f,
                secondaryH = h + 42f * DEG_TO_RAD,
                secondaryC = 0.108f,
                tertiaryH = h,
                tertiaryC = 0.142f,
                neutralH = h,
                neutralC = 0.015f,
                neutralVariantC = 0.026f,
            )
            // 7: 保真 (Fidelity)
            7 -> OklabPaletteProfile(
                primaryH = h,
                primaryC = seedC.coerceIn(0.028f, 0.21f),
                secondaryH = h,
                secondaryC = (seedC * 0.56f).coerceAtMost(0.11f),
                tertiaryH = h + 32f * DEG_TO_RAD,
                tertiaryC = (seedC * 0.72f).coerceAtMost(0.14f),
                neutralH = h,
                neutralC = (seedC * 0.10f).coerceAtMost(0.018f),
                neutralVariantC = (seedC * 0.18f).coerceAtMost(0.030f),
            )
            // 8: 内容 (Content)
            else -> OklabPaletteProfile(
                primaryH = h,
                primaryC = (seedC * 0.86f).coerceIn(0.026f, 0.17f),
                secondaryH = h + 24f * DEG_TO_RAD,
                secondaryC = (seedC * 0.52f).coerceAtMost(0.095f),
                tertiaryH = h - 36f * DEG_TO_RAD,
                tertiaryC = (seedC * 0.68f).coerceAtMost(0.12f),
                neutralH = h,
                neutralC = (seedC * 0.12f).coerceAtMost(0.020f),
                neutralVariantC = (seedC * 0.22f).coerceAtMost(0.034f),
            )
        }
    }

    fun generateScheme(seedArgb: Int, isDark: Boolean, paletteIndex: Int): OklabColorScheme {
        val profile = resolveProfile(seedArgb, paletteIndex)
        val mono = profile.isMonochrome
        val errorH = 26f * DEG_TO_RAD

        if (isDark) {
            val surfaceArgb = oklchToArgb(0.155f, profile.neutralC * 0.85f, profile.neutralH)
            val surfaceLowestArgb = oklchToArgb(0.118f, profile.neutralC * 0.80f, profile.neutralH)
            val surfaceLowArgb = oklchToArgb(0.195f, profile.neutralC, profile.neutralH)
            val surfaceContArgb = oklchToArgb(0.226f, profile.neutralC * 1.05f, profile.neutralH)
            val surfaceHighArgb = oklchToArgb(0.262f, profile.neutralC * 1.15f, profile.neutralH)
            val surfaceHighestArgb = oklchToArgb(0.298f, profile.neutralC * 1.25f, profile.neutralH)

            val onSurfaceArgb = ensureContrastArgb(0.945f, profile.neutralC * 0.35f, profile.neutralH, surfaceArgb, 7.0)
            val onSurfaceVarArgb = ensureContrastArgb(0.775f, profile.neutralVariantC * 0.85f, profile.neutralH, surfaceContArgb, 4.5)

            val primaryArgb = if (mono) 0xFFEAEAEA.toInt() else oklchToArgb(0.755f, profile.primaryC * 0.88f, profile.primaryH)
            val onPrimaryArgb = ensureContrastArgb(0.215f, profile.primaryC * 0.45f, profile.primaryH, primaryArgb, 4.5)
            val primaryContArgb = if (mono) 0xFF353535.toInt() else oklchToArgb(0.345f, profile.primaryC * 0.72f, profile.primaryH)
            val onPrimaryContArgb = ensureContrastArgb(0.915f, profile.primaryC * 0.35f, profile.primaryH, primaryContArgb, 4.5)

            val secondaryArgb = if (mono) 0xFFD0D0D0.toInt() else oklchToArgb(0.740f, profile.secondaryC, profile.secondaryH)
            val onSecondaryArgb = ensureContrastArgb(0.215f, profile.secondaryC * 0.45f, profile.secondaryH, secondaryArgb, 4.5)
            val secondaryContArgb = if (mono) 0xFF2C2C2C.toInt() else oklchToArgb(0.325f, profile.secondaryC * 0.82f, profile.secondaryH)
            val onSecondaryContArgb = ensureContrastArgb(0.905f, profile.secondaryC * 0.35f, profile.secondaryH, secondaryContArgb, 4.5)

            val tertiaryArgb = if (mono) 0xFFBDBDBD.toInt() else oklchToArgb(0.770f, profile.tertiaryC * 0.85f, profile.tertiaryH)
            val onTertiaryArgb = ensureContrastArgb(0.215f, profile.tertiaryC * 0.45f, profile.tertiaryH, tertiaryArgb, 4.5)
            val tertiaryContArgb = if (mono) 0xFF3A3A3A.toInt() else oklchToArgb(0.355f, profile.tertiaryC * 0.72f, profile.tertiaryH)
            val onTertiaryContArgb = ensureContrastArgb(0.915f, profile.tertiaryC * 0.35f, profile.tertiaryH, tertiaryContArgb, 4.5)

            val errorArgb = oklchToArgb(0.745f, 0.135f, errorH)
            val onErrorArgb = ensureContrastArgb(0.220f, 0.06f, errorH, errorArgb, 4.5)
            val errorContArgb = oklchToArgb(0.340f, 0.105f, errorH)
            val onErrorContArgb = ensureContrastArgb(0.910f, 0.045f, errorH, errorContArgb, 4.5)

            val outlineArgb = oklchToArgb(0.555f, profile.neutralVariantC, profile.neutralH)
            val outlineVarArgb = oklchToArgb(0.345f, profile.neutralVariantC * 0.9f, profile.neutralH)

            // One UI Dark tokens in Oklab
            val oneBg = oklchToArgb(0.082f, profile.neutralC * 0.45f, profile.neutralH)
            val oneCard = oklchToArgb(0.198f, profile.neutralC * 0.90f, profile.neutralH)
            val oneCardAlt = oklchToArgb(0.246f, profile.neutralC * 1.05f, profile.neutralH)
            val oneText = ensureContrastArgb(0.960f, profile.neutralC * 0.30f, profile.neutralH, oneCard, 7.0)
            val oneSubText = ensureContrastArgb(0.755f, profile.neutralVariantC * 0.75f, profile.neutralH, oneCard, 4.5)
            val oneDivider = oklchToArgb(0.295f, profile.neutralVariantC * 0.75f, profile.neutralH)
            val oneAccent = if (mono) 0xFFE2E2E2.toInt() else oklchToArgb(0.715f, profile.primaryC * 0.92f, profile.primaryH)
            val oneAccentDeep = if (mono) 0xFFEAEAEA.toInt() else oklchToArgb(0.755f, profile.primaryC * 0.88f, profile.primaryH)
            val oneOnAccent = ensureContrastArgb(0.200f, profile.primaryC * 0.35f, profile.primaryH, oneAccentDeep, 4.5)
            val oneChip = if (mono) 0xFF2D2D2D.toInt() else oklchToArgb(0.315f, profile.secondaryC * 0.85f, profile.secondaryH)
            val oneOnChip = ensureContrastArgb(0.905f, profile.secondaryC * 0.40f, profile.secondaryH, oneChip, 4.5)

            return OklabColorScheme(
                dark = true,
                surface = Color(surfaceArgb),
                surfaceContainerLowest = Color(surfaceLowestArgb),
                surfaceContainerLow = Color(surfaceLowArgb),
                surfaceContainer = Color(surfaceContArgb),
                surfaceContainerHigh = Color(surfaceHighArgb),
                surfaceContainerHighest = Color(surfaceHighestArgb),
                onSurface = Color(onSurfaceArgb),
                onSurfaceVariant = Color(onSurfaceVarArgb),
                primary = Color(primaryArgb),
                onPrimary = Color(onPrimaryArgb),
                primaryContainer = Color(primaryContArgb),
                onPrimaryContainer = Color(onPrimaryContArgb),
                secondary = Color(secondaryArgb),
                onSecondary = Color(onSecondaryArgb),
                secondaryContainer = Color(secondaryContArgb),
                onSecondaryContainer = Color(onSecondaryContArgb),
                tertiary = Color(tertiaryArgb),
                onTertiary = Color(onTertiaryArgb),
                tertiaryContainer = Color(tertiaryContArgb),
                onTertiaryContainer = Color(onTertiaryContArgb),
                error = Color(errorArgb),
                onError = Color(onErrorArgb),
                errorContainer = Color(errorContArgb),
                onErrorContainer = Color(onErrorContArgb),
                outline = Color(outlineArgb),
                outlineVariant = Color(outlineVarArgb),
                oneUiBackground = Color(oneBg),
                oneUiCard = Color(oneCard),
                oneUiCardAlt = Color(oneCardAlt),
                oneUiText = Color(oneText),
                oneUiSubText = Color(oneSubText),
                oneUiDivider = Color(oneDivider),
                oneUiAccent = Color(oneAccent),
                oneUiAccentDeep = Color(oneAccentDeep),
                oneUiOnAccent = Color(oneOnAccent),
                oneUiChip = Color(oneChip),
                oneUiOnChip = Color(oneOnChip),
                oneUiTrack = oklchColor(0.320f, profile.neutralVariantC * 0.70f, profile.neutralH),
                oneUiSegmentTrack = oklchColor(0.240f, profile.neutralC * 0.80f, profile.neutralH),
                oneUiSegmentThumb = oklchColor(0.335f, profile.neutralC * 0.90f, profile.neutralH),
            )
        } else {
            val surfaceArgb = oklchToArgb(0.968f, profile.neutralC * 0.75f, profile.neutralH)
            val surfaceLowestArgb = oklchToArgb(0.996f, profile.neutralC * 0.35f, profile.neutralH)
            val surfaceLowArgb = oklchToArgb(0.952f, profile.neutralC * 0.85f, profile.neutralH)
            val surfaceContArgb = oklchToArgb(0.934f, profile.neutralC, profile.neutralH)
            val surfaceHighArgb = oklchToArgb(0.912f, profile.neutralC * 1.15f, profile.neutralH)
            val surfaceHighestArgb = oklchToArgb(0.888f, profile.neutralC * 1.25f, profile.neutralH)

            val onSurfaceArgb = ensureContrastArgb(0.205f, profile.neutralC * 0.60f, profile.neutralH, surfaceArgb, 7.0)
            val onSurfaceVarArgb = ensureContrastArgb(0.435f, profile.neutralVariantC * 0.90f, profile.neutralH, surfaceContArgb, 4.5)

            val primaryArgb = if (mono) 0xFF1E1E1E.toInt() else oklchToArgb(0.520f, profile.primaryC, profile.primaryH)
            val onPrimaryArgb = ensureContrastArgb(0.985f, profile.primaryC * 0.12f, profile.primaryH, primaryArgb, 4.5)
            val primaryContArgb = if (mono) 0xFFE0E0E0.toInt() else oklchToArgb(0.895f, profile.primaryC * 0.48f, profile.primaryH)
            val onPrimaryContArgb = ensureContrastArgb(0.245f, profile.primaryC * 0.68f, profile.primaryH, primaryContArgb, 4.5)

            val secondaryArgb = if (mono) 0xFF444444.toInt() else oklchToArgb(0.540f, profile.secondaryC, profile.secondaryH)
            val onSecondaryArgb = ensureContrastArgb(0.985f, profile.secondaryC * 0.12f, profile.secondaryH, secondaryArgb, 4.5)
            val secondaryContArgb = if (mono) 0xFFE7E7E7.toInt() else oklchToArgb(0.895f, profile.secondaryC * 0.55f, profile.secondaryH)
            val onSecondaryContArgb = ensureContrastArgb(0.255f, profile.secondaryC * 0.72f, profile.secondaryH, secondaryContArgb, 4.5)

            val tertiaryArgb = if (mono) 0xFF5A5A5A.toInt() else oklchToArgb(0.535f, profile.tertiaryC, profile.tertiaryH)
            val onTertiaryArgb = ensureContrastArgb(0.985f, profile.tertiaryC * 0.12f, profile.tertiaryH, tertiaryArgb, 4.5)
            val tertiaryContArgb = if (mono) 0xFFEBEBEB.toInt() else oklchToArgb(0.895f, profile.tertiaryC * 0.48f, profile.tertiaryH)
            val onTertiaryContArgb = ensureContrastArgb(0.255f, profile.tertiaryC * 0.68f, profile.tertiaryH, tertiaryContArgb, 4.5)

            val errorArgb = oklchToArgb(0.525f, 0.165f, errorH)
            val onErrorArgb = ensureContrastArgb(0.985f, 0.01f, errorH, errorArgb, 4.5)
            val errorContArgb = oklchToArgb(0.905f, 0.065f, errorH)
            val onErrorContArgb = ensureContrastArgb(0.250f, 0.105f, errorH, errorContArgb, 4.5)

            val outlineArgb = oklchToArgb(0.560f, profile.neutralVariantC, profile.neutralH)
            val outlineVarArgb = oklchToArgb(0.820f, profile.neutralVariantC * 0.90f, profile.neutralH)

            // One UI Light tokens in Oklab
            val oneBg = oklchToArgb(0.962f, profile.neutralC * 0.75f, profile.neutralH)
            val oneCard = oklchToArgb(0.993f, profile.neutralC * 0.30f, profile.neutralH)
            val oneCardAlt = oklchToArgb(0.935f, profile.neutralC * 0.90f, profile.neutralH)
            val oneText = ensureContrastArgb(0.195f, profile.neutralC * 0.55f, profile.neutralH, oneCard, 7.0)
            val oneSubText = ensureContrastArgb(0.485f, profile.neutralVariantC * 0.85f, profile.neutralH, oneCard, 4.5)
            val oneDivider = oklchToArgb(0.905f, profile.neutralVariantC * 0.65f, profile.neutralH)
            val oneAccent = if (mono) 0xFF282828.toInt() else oklchToArgb(0.560f, profile.primaryC * 0.95f, profile.primaryH)
            val oneAccentDeep = if (mono) 0xFF1B1B1B.toInt() else oklchToArgb(0.495f, profile.primaryC, profile.primaryH)
            val oneOnAccent = ensureContrastArgb(0.985f, profile.primaryC * 0.12f, profile.primaryH, oneAccentDeep, 4.5)
            val oneChip = if (mono) 0xFFE5E5E5.toInt() else oklchToArgb(0.910f, profile.secondaryC * 0.52f, profile.secondaryH)
            val oneOnChip = ensureContrastArgb(0.320f, profile.secondaryC * 0.75f, profile.secondaryH, oneChip, 4.5)

            return OklabColorScheme(
                dark = false,
                surface = Color(surfaceArgb),
                surfaceContainerLowest = Color(surfaceLowestArgb),
                surfaceContainerLow = Color(surfaceLowArgb),
                surfaceContainer = Color(surfaceContArgb),
                surfaceContainerHigh = Color(surfaceHighArgb),
                surfaceContainerHighest = Color(surfaceHighestArgb),
                onSurface = Color(onSurfaceArgb),
                onSurfaceVariant = Color(onSurfaceVarArgb),
                primary = Color(primaryArgb),
                onPrimary = Color(onPrimaryArgb),
                primaryContainer = Color(primaryContArgb),
                onPrimaryContainer = Color(onPrimaryContArgb),
                secondary = Color(secondaryArgb),
                onSecondary = Color(onSecondaryArgb),
                secondaryContainer = Color(secondaryContArgb),
                onSecondaryContainer = Color(onSecondaryContArgb),
                tertiary = Color(tertiaryArgb),
                onTertiary = Color(onTertiaryArgb),
                tertiaryContainer = Color(tertiaryContArgb),
                onTertiaryContainer = Color(onTertiaryContArgb),
                error = Color(errorArgb),
                onError = Color(onErrorArgb),
                errorContainer = Color(errorContArgb),
                onErrorContainer = Color(onErrorContArgb),
                outline = Color(outlineArgb),
                outlineVariant = Color(outlineVarArgb),
                oneUiBackground = Color(oneBg),
                oneUiCard = Color(oneCard),
                oneUiCardAlt = Color(oneCardAlt),
                oneUiText = Color(oneText),
                oneUiSubText = Color(oneSubText),
                oneUiDivider = Color(oneDivider),
                oneUiAccent = Color(oneAccent),
                oneUiAccentDeep = Color(oneAccentDeep),
                oneUiOnAccent = Color(oneOnAccent),
                oneUiChip = Color(oneChip),
                oneUiOnChip = Color(oneOnChip),
                oneUiTrack = oklchColor(0.880f, profile.neutralVariantC * 0.75f, profile.neutralH),
                oneUiSegmentTrack = oklchColor(0.938f, profile.neutralC * 0.70f, profile.neutralH),
                oneUiSegmentThumb = oklchColor(0.996f, profile.neutralC * 0.25f, profile.neutralH),
            )
        }
    }

    fun generatePlayerAmbient(seedArgb: Int, isDark: Boolean, paletteIndex: Int): OklabPlayerAmbientPalette {
        val profile = resolveProfile(seedArgb, paletteIndex)
        val mono = profile.isMonochrome
        return if (isDark) {
            val base = oklchColor(0.145f, profile.neutralC * 0.90f, profile.neutralH)
            val surface = oklchColor(0.195f, profile.secondaryC * 0.78f, profile.secondaryH)
            val orbPrimary = if (mono) Color(0xFF6A6A6A) else oklchColor(0.68f, profile.primaryC * 0.92f, profile.primaryH)
            val orbSecondary = if (mono) Color(0xFF525252) else oklchColor(0.66f, profile.secondaryC * 0.95f, profile.secondaryH)
            val orbTertiary = if (mono) Color(0xFF7D7D7D) else oklchColor(0.70f, profile.tertiaryC * 0.90f, profile.tertiaryH)
            val menuTint = oklchColor(0.245f, profile.secondaryC * 0.70f, profile.secondaryH)
            val glowAccent = if (mono) Color(0xFFE0E0E0) else oklchColor(0.74f, max(profile.primaryC, 0.145f), profile.primaryH)
            val accent = if (mono) Color(0xFFEAEAEA) else oklchColor(0.78f, profile.primaryC * 0.90f, profile.primaryH)
            val onAccent = Color(ensureContrastArgb(0.20f, profile.primaryC * 0.35f, profile.primaryH, accent.toArgb(), 4.5))
            OklabPlayerAmbientPalette(
                dark = true,
                base = base,
                surface = surface,
                orbPrimary = orbPrimary,
                orbSecondary = orbSecondary,
                orbTertiary = orbTertiary,
                menuTint = menuTint,
                activeInk = Color.White,
                secondaryInk = Color.White.copy(alpha = 0.84f),
                glowAccent = glowAccent,
                heroMixTint = lerp(
                    oklchColor(0.32f, profile.primaryC * 0.85f, profile.primaryH),
                    oklchColor(0.28f, profile.tertiaryC * 0.80f, profile.tertiaryH),
                    0.35f,
                ),
                accent = accent,
                onAccent = onAccent,
            )
        } else {
            val base = oklchColor(0.956f, profile.neutralC * 0.85f, profile.neutralH)
            val surface = oklchColor(0.935f, profile.secondaryC * 0.55f, profile.secondaryH)
            val orbPrimary = if (mono) Color(0xFFDADADA) else oklchColor(0.84f, profile.primaryC * 0.58f, profile.primaryH)
            val orbSecondary = if (mono) Color(0xFFE4E4E4) else oklchColor(0.86f, profile.secondaryC * 0.65f, profile.secondaryH)
            val orbTertiary = if (mono) Color(0xFFCECECE) else oklchColor(0.84f, profile.tertiaryC * 0.58f, profile.tertiaryH)
            val menuTint = oklchColor(0.968f, profile.secondaryC * 0.42f, profile.secondaryH)
            val activeInkArgb = ensureContrastArgb(0.195f, profile.neutralC * 0.65f, profile.neutralH, surface.toArgb(), 7.0)
            val secondaryInkArgb = ensureContrastArgb(0.375f, profile.neutralVariantC * 0.95f, profile.neutralH, surface.toArgb(), 4.5)
            val glowAccent = if (mono) Color(0xFF333333) else oklchColor(0.56f, max(profile.primaryC, 0.14f), profile.primaryH)
            val accent = if (mono) Color(0xFF222222) else oklchColor(0.49f, profile.primaryC, profile.primaryH)
            val onAccent = Color(ensureContrastArgb(0.985f, profile.primaryC * 0.10f, profile.primaryH, accent.toArgb(), 4.5))
            OklabPlayerAmbientPalette(
                dark = false,
                base = base,
                surface = surface,
                orbPrimary = orbPrimary,
                orbSecondary = orbSecondary,
                orbTertiary = orbTertiary,
                menuTint = menuTint,
                activeInk = Color(activeInkArgb),
                secondaryInk = Color(secondaryInkArgb).copy(alpha = 0.88f),
                glowAccent = glowAccent,
                heroMixTint = oklchColor(0.86f, profile.primaryC * 0.52f, profile.primaryH),
                accent = accent,
                onAccent = onAccent,
            )
        }
    }
}

internal fun OklabColorScheme.toAuraExpressiveScheme(): AuraExpressiveScheme = AuraExpressiveScheme(
    dark = dark,
    surface = surface,
    surfaceContainerLowest = surfaceContainerLowest,
    surfaceContainerLow = surfaceContainerLow,
    surfaceContainer = surfaceContainer,
    surfaceContainerHigh = surfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHighest,
    onSurface = onSurface,
    onSurfaceVariant = onSurfaceVariant,
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    error = error,
    onError = onError,
    errorContainer = errorContainer,
    onErrorContainer = onErrorContainer,
    outline = outline,
    outlineVariant = outlineVariant,
)

internal fun OklabColorScheme.toPlayerExpressiveColors(): PlayerExpressiveColors =
    toAuraExpressiveScheme().toPlayerExpressiveColors()

internal fun OklabColorScheme.toOneUiMonetTones(): MonetTones = MonetTones(
    background = oneUiBackground.toArgb(),
    card = oneUiCard.toArgb(),
    cardAlt = oneUiCardAlt.toArgb(),
    text = oneUiText.toArgb(),
    subText = oneUiSubText.toArgb(),
    divider = oneUiDivider.toArgb(),
    accent = oneUiAccent.toArgb(),
    accentDeep = oneUiAccentDeep.toArgb(),
    onAccent = oneUiOnAccent.toArgb(),
    chip = oneUiChip.toArgb(),
    onChip = oneUiOnChip.toArgb(),
    track = oneUiTrack.toArgb(),
    segmentTrack = oneUiSegmentTrack.toArgb(),
    segmentThumb = oneUiSegmentThumb.toArgb(),
)

internal fun OklabColorScheme.toPlayerOneUiColors(): PlayerOneUiColors = PlayerOneUiColors(
    dark = dark,
    background = oneUiBackground,
    card = oneUiCard,
    cardAlt = oneUiCardAlt,
    text = oneUiText,
    subText = oneUiSubText,
    divider = oneUiDivider,
    accent = oneUiAccent,
    accentDeep = oneUiAccentDeep,
    onAccent = oneUiOnAccent,
    chip = oneUiChip,
    onChip = oneUiOnChip,
    track = oneUiTrack,
)

internal fun Colors.withOklabColors(s: OklabColorScheme): Colors = copy(
    primary = s.primary,
    onPrimary = s.onPrimary,
    primaryVariant = s.primaryContainer,
    onPrimaryVariant = s.onPrimaryContainer,
    error = s.error,
    onError = s.onError,
    errorContainer = s.errorContainer,
    onErrorContainer = s.onErrorContainer,
    disabledPrimary = s.onSurface.copy(alpha = 0.12f),
    disabledOnPrimary = s.onSurface.copy(alpha = 0.38f),
    disabledPrimaryButton = s.onSurface.copy(alpha = 0.12f),
    disabledOnPrimaryButton = s.onSurface.copy(alpha = 0.38f),
    disabledPrimarySlider = s.onSurface.copy(alpha = 0.38f),
    primaryContainer = s.primaryContainer,
    onPrimaryContainer = s.onPrimaryContainer,
    secondary = s.secondary,
    onSecondary = s.onSecondary,
    secondaryVariant = s.secondaryContainer,
    onSecondaryVariant = s.onSecondaryContainer,
    disabledSecondary = s.onSurface.copy(alpha = 0.12f),
    disabledOnSecondary = s.onSurface.copy(alpha = 0.38f),
    disabledSecondaryVariant = s.onSurface.copy(alpha = 0.12f),
    disabledOnSecondaryVariant = s.onSurface.copy(alpha = 0.38f),
    secondaryContainer = s.secondaryContainer,
    onSecondaryContainer = s.onSecondaryContainer,
    secondaryContainerVariant = s.secondaryContainer,
    onSecondaryContainerVariant = s.onSecondaryContainer,
    tertiaryContainer = s.tertiaryContainer,
    onTertiaryContainer = s.onTertiaryContainer,
    tertiaryContainerVariant = s.tertiaryContainer,
    background = s.surface,
    onBackground = s.onSurface,
    onBackgroundVariant = s.onSurfaceVariant,
    surface = s.surface,
    onSurface = s.onSurface,
    surfaceVariant = s.surfaceContainerHigh,
    onSurfaceSecondary = s.onSurfaceVariant,
    onSurfaceVariantSummary = s.onSurfaceVariant,
    onSurfaceVariantActions = s.onSurfaceVariant,
    disabledOnSurface = s.onSurface.copy(alpha = 0.38f),
    surfaceContainer = s.surfaceContainer,
    onSurfaceContainer = s.onSurface,
    onSurfaceContainerVariant = s.onSurfaceVariant,
    surfaceContainerHigh = s.surfaceContainerHigh,
    onSurfaceContainerHigh = s.onSurface,
    surfaceContainerHighest = s.surfaceContainerHighest,
    onSurfaceContainerHighest = s.onSurface,
    outline = s.outline,
    dividerLine = s.outlineVariant.copy(alpha = 0.55f),
    sliderKeyPoint = s.primary.copy(alpha = 0.35f),
    sliderKeyPointForeground = s.primary,
    sliderBackground = s.secondaryContainer,
)

@Composable
internal fun rememberOklabScheme(seed: Int, isDark: Boolean, paletteIndex: Int): OklabColorScheme {
    val target = remember(seed, isDark, paletteIndex) {
        OklabThemeEngine.generateScheme(seed, isDark, paletteIndex)
    }
    val spec = tween<Color>(340, easing = FastOutSlowInEasing)
    return OklabColorScheme(
        dark = target.dark,
        surface = animateColorAsState(target.surface, spec, label = "oklabSurface").value,
        surfaceContainerLowest = animateColorAsState(target.surfaceContainerLowest, spec, label = "oklabSurfaceLowest").value,
        surfaceContainerLow = animateColorAsState(target.surfaceContainerLow, spec, label = "oklabSurfaceLow").value,
        surfaceContainer = animateColorAsState(target.surfaceContainer, spec, label = "oklabSurfaceCont").value,
        surfaceContainerHigh = animateColorAsState(target.surfaceContainerHigh, spec, label = "oklabSurfaceHigh").value,
        surfaceContainerHighest = animateColorAsState(target.surfaceContainerHighest, spec, label = "oklabSurfaceHighest").value,
        onSurface = animateColorAsState(target.onSurface, spec, label = "oklabOnSurface").value,
        onSurfaceVariant = animateColorAsState(target.onSurfaceVariant, spec, label = "oklabOnSurfaceVar").value,
        primary = animateColorAsState(target.primary, spec, label = "oklabPrimary").value,
        onPrimary = animateColorAsState(target.onPrimary, spec, label = "oklabOnPrimary").value,
        primaryContainer = animateColorAsState(target.primaryContainer, spec, label = "oklabPrimaryCont").value,
        onPrimaryContainer = animateColorAsState(target.onPrimaryContainer, spec, label = "oklabOnPrimaryCont").value,
        secondary = animateColorAsState(target.secondary, spec, label = "oklabSecondary").value,
        onSecondary = animateColorAsState(target.onSecondary, spec, label = "oklabOnSecondary").value,
        secondaryContainer = animateColorAsState(target.secondaryContainer, spec, label = "oklabSecondaryCont").value,
        onSecondaryContainer = animateColorAsState(target.onSecondaryContainer, spec, label = "oklabOnSecondaryCont").value,
        tertiary = animateColorAsState(target.tertiary, spec, label = "oklabTertiary").value,
        onTertiary = animateColorAsState(target.onTertiary, spec, label = "oklabOnTertiary").value,
        tertiaryContainer = animateColorAsState(target.tertiaryContainer, spec, label = "oklabTertiaryCont").value,
        onTertiaryContainer = animateColorAsState(target.onTertiaryContainer, spec, label = "oklabOnTertiaryCont").value,
        error = animateColorAsState(target.error, spec, label = "oklabError").value,
        onError = animateColorAsState(target.onError, spec, label = "oklabOnError").value,
        errorContainer = animateColorAsState(target.errorContainer, spec, label = "oklabErrorCont").value,
        onErrorContainer = animateColorAsState(target.onErrorContainer, spec, label = "oklabOnErrorCont").value,
        outline = animateColorAsState(target.outline, spec, label = "oklabOutline").value,
        outlineVariant = animateColorAsState(target.outlineVariant, spec, label = "oklabOutlineVar").value,
        oneUiBackground = animateColorAsState(target.oneUiBackground, spec, label = "oklabOneBg").value,
        oneUiCard = animateColorAsState(target.oneUiCard, spec, label = "oklabOneCard").value,
        oneUiCardAlt = animateColorAsState(target.oneUiCardAlt, spec, label = "oklabOneCardAlt").value,
        oneUiText = animateColorAsState(target.oneUiText, spec, label = "oklabOneText").value,
        oneUiSubText = animateColorAsState(target.oneUiSubText, spec, label = "oklabOneSubText").value,
        oneUiDivider = animateColorAsState(target.oneUiDivider, spec, label = "oklabOneDivider").value,
        oneUiAccent = animateColorAsState(target.oneUiAccent, spec, label = "oklabOneAccent").value,
        oneUiAccentDeep = animateColorAsState(target.oneUiAccentDeep, spec, label = "oklabOneAccentDeep").value,
        oneUiOnAccent = animateColorAsState(target.oneUiOnAccent, spec, label = "oklabOneOnAccent").value,
        oneUiChip = animateColorAsState(target.oneUiChip, spec, label = "oklabOneChip").value,
        oneUiOnChip = animateColorAsState(target.oneUiOnChip, spec, label = "oklabOneOnChip").value,
        oneUiTrack = animateColorAsState(target.oneUiTrack, spec, label = "oklabOneTrack").value,
        oneUiSegmentTrack = animateColorAsState(target.oneUiSegmentTrack, spec, label = "oklabOneSegTrack").value,
        oneUiSegmentThumb = animateColorAsState(target.oneUiSegmentThumb, spec, label = "oklabOneSegThumb").value,
    )
}
