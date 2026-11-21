// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.toArgb
import btm.m.auraplayer.playback.MaterialPlayerBackground
import btm.m.auraplayer.playback.NowPlayingConfig
import btm.m.auraplayer.playback.PlayerTheme
import btm.m.auraplayer.ui.OklabThemeEngine
import btm.m.auraplayer.ui.toOneUiMonetTones
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.CorePalette
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.score.Score
import dev.pascal.oneuikit.MonetTones
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val DEFAULT_WIDGET_SEED = 0xFF6750A4.toInt()

internal data class WidgetThemeColors(
    val titleColor: Int,
    val artistColor: Int,
    val mutedColor: Int,
    val usesButtonContainers: Boolean,
    val playBgColor: Int,
    val playIconColor: Int,
    val playUsesSquircleWhenPaused: Boolean,
    val secondaryBgColor: Int,
    val secondaryIconColor: Int,
    val queueCardBgColor: Int,
    val placeholderBgColor: Int,
    val placeholderIconColor: Int,
    val coverCornerDp: Float,
    val isDarkSurface: Boolean,
)

internal object WidgetThemeEngine {
    private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
    private const val COLOR_BLACK = 0xFF000000.toInt()

    fun extractMonetSeed(bitmap: Bitmap?): Int {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return DEFAULT_WIDGET_SEED
        val pixels = ArrayList<Int>(4096)
        val w = bitmap.width
        val h = bitmap.height
        for (y in 0 until 64) {
            val py = (y * h / 64).coerceIn(0, h - 1)
            for (x in 0 until 64) {
                val px = (x * w / 64).coerceIn(0, w - 1)
                val pixel = bitmap.getPixel(px, py)
                if (((pixel ushr 24) and 0xFF) >= 128) {
                    pixels.add(pixel or 0xFF000000.toInt())
                }
            }
        }
        if (pixels.isEmpty()) return DEFAULT_WIDGET_SEED
        return Score.score(QuantizerCelebi.quantize(pixels.toIntArray(), 128), 1, DEFAULT_WIDGET_SEED, true)
            .firstOrNull() ?: DEFAULT_WIDGET_SEED
    }

    fun extractOklabSeed(bitmap: Bitmap?): Int =
        OklabThemeEngine.extractOklabSeed(bitmap, DEFAULT_WIDGET_SEED)

    fun resolveThemeColors(
        config: NowPlayingConfig,
        appDark: Boolean,
        seedArgb: Int,
        isPlaying: Boolean,
        useOklab: Boolean = false,
        paletteIndex: Int = 0,
    ): WidgetThemeColors {
        val palette = CorePalette.of(seedArgb)
        val effectiveDark = config.resolveDarkTheme(appDark)
        val oklabScheme = if (useOklab) OklabThemeEngine.generateScheme(seedArgb, effectiveDark, paletteIndex) else null
        val oklabAmbient = if (useOklab) OklabThemeEngine.generatePlayerAmbient(seedArgb, effectiveDark, paletteIndex) else null
        return when (config.theme) {
            PlayerTheme.MATERIAL -> {
                if (oklabScheme != null) {
                    val title = oklabScheme.onSurface.toArgb()
                    val artist = lerpColor(oklabScheme.onSurfaceVariant.toArgb(), oklabScheme.primary.toArgb(), if (effectiveDark) 0.40f else 0.32f)
                    val muted = lerpColor(oklabScheme.onSurfaceVariant.toArgb(), oklabScheme.primary.toArgb(), 0.24f)
                    val playBg = if (isPlaying) oklabScheme.primary.toArgb() else oklabScheme.primaryContainer.toArgb()
                    val playIcon = if (isPlaying) oklabScheme.onPrimary.toArgb() else oklabScheme.onPrimaryContainer.toArgb()
                    val queueBg = if (config.materialBackground == MaterialPlayerBackground.MATERIAL) {
                        oklabScheme.surfaceContainerHigh.toArgb()
                    } else {
                        withAlpha(oklabScheme.surfaceContainerHigh.toArgb(), 0.84f)
                    }
                    WidgetThemeColors(
                        titleColor = title,
                        artistColor = artist,
                        mutedColor = muted,
                        usesButtonContainers = true,
                        playBgColor = playBg,
                        playIconColor = playIcon,
                        playUsesSquircleWhenPaused = !isPlaying,
                        secondaryBgColor = oklabScheme.secondaryContainer.toArgb(),
                        secondaryIconColor = oklabScheme.onSecondaryContainer.toArgb(),
                        queueCardBgColor = queueBg,
                        placeholderBgColor = oklabScheme.surfaceContainerHighest.toArgb(),
                        placeholderIconColor = oklabScheme.onSurfaceVariant.toArgb(),
                        coverCornerDp = 12f,
                        isDarkSurface = effectiveDark,
                    )
                } else {
                    val scheme = SchemeExpressive(Hct.fromInt(seedArgb), effectiveDark, 0.0, ColorSpec.SpecVersion.SPEC_2025)
                    val title = scheme.onSurface
                    val artist = lerpColor(scheme.onSurfaceVariant, scheme.primary, if (effectiveDark) 0.40f else 0.32f)
                    val muted = lerpColor(scheme.onSurfaceVariant, scheme.primary, 0.24f)
                    val playBg = if (isPlaying) scheme.primary else scheme.primaryContainer
                    val playIcon = if (isPlaying) scheme.onPrimary else scheme.onPrimaryContainer
                    val queueBg = if (config.materialBackground == MaterialPlayerBackground.MATERIAL) {
                        scheme.surfaceContainerHigh
                    } else {
                        withAlpha(scheme.surfaceContainerHigh, 0.84f)
                    }
                    WidgetThemeColors(
                        titleColor = title,
                        artistColor = artist,
                        mutedColor = muted,
                        usesButtonContainers = true,
                        playBgColor = playBg,
                        playIconColor = playIcon,
                        playUsesSquircleWhenPaused = !isPlaying,
                        secondaryBgColor = scheme.secondaryContainer,
                        secondaryIconColor = scheme.onSecondaryContainer,
                        queueCardBgColor = queueBg,
                        placeholderBgColor = scheme.surfaceContainerHighest,
                        placeholderIconColor = scheme.onSurfaceVariant,
                        coverCornerDp = 12f,
                        isDarkSurface = effectiveDark,
                    )
                }
            }

            PlayerTheme.ONE -> {
                val monet = oklabScheme?.toOneUiMonetTones() ?: MonetTones.fromSeed(seedArgb, effectiveDark)
                WidgetThemeColors(
                    titleColor = monet.text,
                    artistColor = monet.subText,
                    mutedColor = monet.subText,
                    usesButtonContainers = true,
                    playBgColor = monet.accentDeep,
                    playIconColor = monet.onAccent,
                    playUsesSquircleWhenPaused = !isPlaying,
                    secondaryBgColor = monet.cardAlt,
                    secondaryIconColor = monet.text,
                    queueCardBgColor = monet.card,
                    placeholderBgColor = monet.cardAlt,
                    placeholderIconColor = monet.subText,
                    coverCornerDp = 12f,
                    isDarkSurface = effectiveDark,
                )
            }

            PlayerTheme.FULLSCREEN -> {
                val fg = oklabAmbient?.activeInk?.toArgb()
                    ?: if (effectiveDark) COLOR_WHITE else palette.n1.tone(10)
                val sub = oklabAmbient?.secondaryInk?.toArgb()
                    ?: if (effectiveDark) withAlpha(COLOR_WHITE, 0.82f) else withAlpha(fg, 0.78f)
                val muted = if (effectiveDark) withAlpha(fg, 0.68f) else withAlpha(fg, 0.64f)
                val cardAlpha = if (config.fullscreenGlass) 0.24f else 0.16f
                val cardBase = if (effectiveDark) COLOR_BLACK else COLOR_WHITE
                WidgetThemeColors(
                    titleColor = fg,
                    artistColor = sub,
                    mutedColor = muted,
                    usesButtonContainers = false,
                    playBgColor = 0,
                    playIconColor = fg,
                    playUsesSquircleWhenPaused = false,
                    secondaryBgColor = 0,
                    secondaryIconColor = fg,
                    queueCardBgColor = withAlpha(cardBase, cardAlpha + 0.14f),
                    placeholderBgColor = oklabAmbient?.surface?.toArgb() ?: palette.a2.tone(if (effectiveDark) 24 else 88),
                    placeholderIconColor = withAlpha(fg, 0.72f),
                    coverCornerDp = 12f,
                    isDarkSurface = effectiveDark,
                )
            }

            PlayerTheme.AURORA,
            PlayerTheme.AURORA_BLUR,
            PlayerTheme.BLUR,
            PlayerTheme.COLOR,
            -> {
                val fg = oklabAmbient?.activeInk?.toArgb()
                    ?: if (effectiveDark) COLOR_WHITE else palette.n1.tone(10)
                val sub = oklabAmbient?.secondaryInk?.toArgb()
                    ?: if (effectiveDark) withAlpha(COLOR_WHITE, 0.78f) else withAlpha(fg, 0.76f)
                val muted = if (effectiveDark) withAlpha(fg, 0.64f) else withAlpha(fg, 0.62f)
                WidgetThemeColors(
                    titleColor = fg,
                    artistColor = sub,
                    mutedColor = muted,
                    usesButtonContainers = false,
                    playBgColor = 0,
                    playIconColor = fg,
                    playUsesSquircleWhenPaused = false,
                    secondaryBgColor = 0,
                    secondaryIconColor = fg,
                    queueCardBgColor = withAlpha(fg, if (effectiveDark) 0.13f else 0.08f),
                    placeholderBgColor = oklabAmbient?.surface?.toArgb() ?: palette.a2.tone(if (effectiveDark) 24 else 88),
                    placeholderIconColor = withAlpha(fg, 0.72f),
                    coverCornerDp = 12f,
                    isDarkSurface = effectiveDark,
                )
            }
        }
    }

    fun renderThemeBackgroundBitmap(
        widthPx: Int,
        heightPx: Int,
        artwork: Bitmap?,
        config: NowPlayingConfig,
        advancedMaterial: Boolean,
        appDark: Boolean,
        seedArgb: Int,
        useOklab: Boolean = false,
        paletteIndex: Int = 0,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(32)
        val h = heightPx.coerceAtLeast(32)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val palette = CorePalette.of(seedArgb)

        val isMaterial = config.theme == PlayerTheme.MATERIAL
        val isOne = config.theme == PlayerTheme.ONE
        val effectiveDark = config.resolveDarkTheme(appDark)
        val oklabScheme = if (useOklab) OklabThemeEngine.generateScheme(seedArgb, effectiveDark, paletteIndex) else null
        val oklabAmbient = if (useOklab) OklabThemeEngine.generatePlayerAmbient(seedArgb, effectiveDark, paletteIndex) else null
        val expressive = if (isMaterial && oklabScheme == null) {
            SchemeExpressive(Hct.fromInt(seedArgb), effectiveDark, 0.0, ColorSpec.SpecVersion.SPEC_2025)
        } else {
            null
        }
        val expressiveSurface = oklabScheme?.surface?.toArgb() ?: expressive?.surface
        val expressivePrimaryCont = oklabScheme?.primaryContainer?.toArgb() ?: expressive?.primaryContainer
        val expressiveSecondaryCont = oklabScheme?.secondaryContainer?.toArgb() ?: expressive?.secondaryContainer
        val expressiveTertiaryCont = oklabScheme?.tertiaryContainer?.toArgb() ?: expressive?.tertiaryContainer
        val oneUi = if (isOne) (oklabScheme?.toOneUiMonetTones() ?: MonetTones.fromSeed(seedArgb, effectiveDark)) else null

        val baseColor = when {
            isMaterial && config.materialBackground == MaterialPlayerBackground.MATERIAL && expressiveSurface != null -> expressiveSurface
            isOne && oneUi != null -> oneUi.background
            oklabAmbient != null -> oklabAmbient.surface.toArgb()
            else -> palette.a2.tone(if (effectiveDark) 14 else 93)
        }
        canvas.drawColor(baseColor)

        if (!advancedMaterial) {
            if (isMaterial && config.materialBackground != MaterialPlayerBackground.MATERIAL && expressiveSurface != null) {
                val mix = (config.materialColorMix / 100f).coerceIn(0f, 1f)
                if (mix > 0.001f) {
                    canvas.drawColor(withAlpha(expressiveSurface, mix * 0.94f))
                }
            }
            return out
        }

        if (isMaterial && config.materialBackground == MaterialPlayerBackground.MATERIAL && expressiveSurface != null) {
            val mix = (config.materialColorMix / 100f).coerceIn(0f, 1f)
            val washStrength = 1f - mix * 0.92f
            if (washStrength > 0.005f) {
                val primaryAlpha = (if (effectiveDark) 0.36f else 0.48f) * washStrength
                val tertiaryAlpha = (if (effectiveDark) 0.30f else 0.42f) * washStrength
                val secondaryAlpha = (if (effectiveDark) 0.25f else 0.34f) * washStrength
                expressivePrimaryCont?.let { drawRadialOrb(canvas, w * 0.22f, h * 0.18f, max(w, h) * 0.90f, withAlpha(it, primaryAlpha)) }
                expressiveTertiaryCont?.let { drawRadialOrb(canvas, w * 0.78f, h * 0.78f, max(w, h) * 0.88f, withAlpha(it, tertiaryAlpha)) }
                expressiveSecondaryCont?.let { drawRadialOrb(canvas, w * 0.72f, h * 0.36f, max(w, h) * 0.68f, withAlpha(it, secondaryAlpha)) }
            }
        }

        if (isOne && oneUi != null) {
            drawRadialOrb(canvas, w * 0.50f, h * 0.22f, max(w, h) * 0.90f, withAlpha(oneUi.accent, if (effectiveDark) 0.18f else 0.12f))
            drawRadialOrb(canvas, w * 0.52f, h * 0.84f, max(w, h) * 0.85f, withAlpha(oneUi.chip, if (effectiveDark) 0.22f else 0.16f))
        }

        val showAurora = config.theme == PlayerTheme.AURORA ||
            config.theme == PlayerTheme.AURORA_BLUR ||
            (isMaterial && (config.materialBackground == MaterialPlayerBackground.AURORA || config.materialBackground == MaterialPlayerBackground.AURORA_BLUR))
        if (showAurora) {
            if (oklabAmbient != null) {
                canvas.drawColor(oklabAmbient.base.toArgb())
                drawRadialOrb(canvas, w * 0.24f, h * 0.24f, w * 0.85f, withAlpha(oklabAmbient.orbPrimary.toArgb(), 0.72f))
                drawRadialOrb(canvas, w * 0.78f, h * 0.50f, w * 0.85f, withAlpha(oklabAmbient.orbSecondary.toArgb(), 0.72f))
                drawRadialOrb(canvas, w * 0.38f, h * 0.84f, w * 0.85f, withAlpha(oklabAmbient.orbTertiary.toArgb(), 0.72f))
            } else if (!effectiveDark) {
                canvas.drawColor(palette.n1.tone(96))
                drawRadialOrb(canvas, w * 0.24f, h * 0.24f, w * 0.85f, withAlpha(palette.a1.tone(84), 0.76f))
                drawRadialOrb(canvas, w * 0.78f, h * 0.50f, w * 0.85f, withAlpha(palette.a2.tone(86), 0.76f))
                drawRadialOrb(canvas, w * 0.38f, h * 0.84f, w * 0.85f, withAlpha(palette.a3.tone(84), 0.76f))
            } else {
                drawAuroraBackground(canvas, w, h, palette)
            }
        }

        val showArtwork = config.theme == PlayerTheme.AURORA_BLUR ||
            config.theme == PlayerTheme.BLUR ||
            config.theme == PlayerTheme.FULLSCREEN ||
            (isMaterial && (config.materialBackground == MaterialPlayerBackground.BLUR || config.materialBackground == MaterialPlayerBackground.AURORA_BLUR))

        if (showArtwork && artwork != null && artwork.width > 0 && artwork.height > 0) {
            val flowing = config.theme == PlayerTheme.AURORA_BLUR ||
                (isMaterial && config.materialBackground == MaterialPlayerBackground.AURORA_BLUR)
            val fullscreen = config.theme == PlayerTheme.FULLSCREEN
            val scaledArt = cropCenterBitmap(artwork, w, h, overscan = if (fullscreen) 1.04f else 1.16f)
            val blurPx = if (fullscreen) {
                0
            } else {
                ((config.blurRadius / 48f) * (min(w, h) * 0.06f)).roundToInt().coerceIn(0, 28)
            }
            if (blurPx > 0) {
                stackBlurInPlace(scaledArt, blurPx)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (flowing) {
                paint.alpha = ((if (effectiveDark) 0.40f else 0.30f) * 255f).roundToInt()
            }
            canvas.drawBitmap(scaledArt, 0f, 0f, paint)
            scaledArt.recycle()

            val mix = (config.materialColorMix / 100f).coerceIn(0f, 1f)
            val scrimScale = if (isMaterial) 1f - mix * 0.6f else 1f
            val veilBase = if (effectiveDark) COLOR_BLACK else (expressiveSurface ?: oklabAmbient?.base?.toArgb() ?: palette.n1.tone(96))
            if (fullscreen) {
                val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f,
                        0f,
                        0f,
                        h.toFloat(),
                        if (effectiveDark) {
                            intArrayOf(
                                withAlpha(COLOR_BLACK, 0.22f),
                                withAlpha(COLOR_BLACK, 0.34f),
                                withAlpha(COLOR_BLACK, 0.56f),
                            )
                        } else {
                            intArrayOf(
                                withAlpha(veilBase, 0.24f),
                                withAlpha(veilBase, 0.42f),
                                withAlpha(veilBase, 0.62f),
                            )
                        },
                        floatArrayOf(0f, 0.45f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), gradientPaint)
            } else {
                val scrimAlpha = scrimScale * if (effectiveDark) {
                    if (flowing) 0.14f else 0.18f
                } else {
                    if (flowing) 0.52f else 0.62f
                }
                canvas.drawColor(withAlpha(veilBase, scrimAlpha))
                val depthGradient = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f,
                        0f,
                        0f,
                        h.toFloat(),
                        intArrayOf(
                            0,
                            withAlpha(veilBase, scrimScale * if (effectiveDark) 0.16f else 0.14f),
                        ),
                        floatArrayOf(0.25f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), depthGradient)
            }
        } else if (config.theme == PlayerTheme.COLOR) {
            val orbTint = oklabAmbient?.orbPrimary?.toArgb()
                ?: palette.a1.tone(if (effectiveDark) 24 else 86)
            drawRadialOrb(canvas, w * 0.28f, h * 0.24f, max(w, h) * 0.78f, withAlpha(orbTint, 0.32f))
        }

        if (isMaterial && config.materialBackground != MaterialPlayerBackground.MATERIAL && expressiveSurface != null) {
            val mix = (config.materialColorMix / 100f).coerceIn(0f, 1f)
            if (mix > 0.001f) {
                canvas.drawColor(withAlpha(expressiveSurface, mix * 0.94f))
            }
        }

        return out
    }

    fun renderRoundedCoverBitmap(
        artwork: Bitmap?,
        widthPx: Int,
        heightPx: Int = widthPx,
        cornerRadiusPx: Float,
        placeholderBg: Int,
        placeholderIconColor: Int,
        brandDrawable: Drawable?,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(24)
        val h = heightPx.coerceAtLeast(24)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val radius = cornerRadiusPx.coerceIn(0f, min(w, h) / 2f)
        val path = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)

        if (artwork != null && artwork.width > 0 && artwork.height > 0) {
            val cropped = cropCenterBitmap(artwork, w, h, overscan = 1.0f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(cropped, 0f, 0f, paint)
            cropped.recycle()
        } else {
            canvas.drawColor(placeholderBg)
            if (brandDrawable != null) {
                val iconSize = (min(w, h) * 0.42f).roundToInt().coerceAtLeast(12)
                val left = (w - iconSize) / 2
                val top = (h - iconSize) / 2
                val copy = brandDrawable.mutate()
                copy.colorFilter = PorterDuffColorFilter(placeholderIconColor, PorterDuff.Mode.SRC_IN)
                copy.setBounds(left, top, left + iconSize, top + iconSize)
                copy.draw(canvas)
            }
        }
        canvas.restore()
        return out
    }

    private fun drawAuroraBackground(canvas: Canvas, w: Int, h: Int, palette: CorePalette) {
        canvas.drawColor(palette.n1.tone(8))
        val orbRadius = max(w, h) * 0.85f
        drawRadialOrb(canvas, w * 0.22f, h * 0.25f, orbRadius, withAlpha(palette.a1.tone(70), 0.72f))
        drawRadialOrb(canvas, w * 0.78f, h * 0.50f, orbRadius, withAlpha(palette.a2.tone(70), 0.72f))
        drawRadialOrb(canvas, w * 0.38f, h * 0.85f, orbRadius, withAlpha(palette.a3.tone(70), 0.72f))
    }

    private fun drawRadialOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float, centerColor: Int) {
        if (radius <= 1f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx,
                cy,
                radius,
                centerColor,
                centerColor and 0x00FFFFFF,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, radius, paint)
    }

    private fun cropCenterBitmap(source: Bitmap, targetW: Int, targetH: Int, overscan: Float): Bitmap {
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val srcW = source.width.toFloat().coerceAtLeast(1f)
        val srcH = source.height.toFloat().coerceAtLeast(1f)
        val scale = max(targetW / srcW, targetH / srcH) * overscan
        val drawW = srcW * scale
        val drawH = srcH * scale
        val left = (targetW - drawW) / 2f
        val top = (targetH - drawH) / 2f
        val dst = RectF(left, top, left + drawW, top + drawH)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, dst, paint)
        return out
    }

    internal fun withAlpha(argb: Int, alphaFraction: Float): Int {
        val alphaChannel = (argb ushr 24) and 0xFF
        val a = (alphaChannel * alphaFraction.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        return (argb and 0x00FFFFFF) or (a shl 24)
    }

    internal fun lerpColor(startArgb: Int, endArgb: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        val sa = (startArgb ushr 24) and 0xFF
        val sr = (startArgb ushr 16) and 0xFF
        val sg = (startArgb ushr 8) and 0xFF
        val sb = startArgb and 0xFF
        val ea = (endArgb ushr 24) and 0xFF
        val er = (endArgb ushr 16) and 0xFF
        val eg = (endArgb ushr 8) and 0xFF
        val eb = endArgb and 0xFF
        val a = (sa + (ea - sa) * t).roundToInt().coerceIn(0, 255)
        val r = (sr + (er - sr) * t).roundToInt().coerceIn(0, 255)
        val g = (sg + (eg - sg) * t).roundToInt().coerceIn(0, 255)
        val b = (sb + (eb - sb) * t).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Fast O(N) software StackBlur for ARGB_8888 bitmaps. */
    private fun stackBlurInPlace(bitmap: Bitmap, radius: Int) {
        if (radius < 1) return
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = idx / divsum
        }

        yi = 0
        yw = 0
        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        y = 0
        while (y < h) {
            rinsum = 0; ginsum = 0; binsum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            i = -radius
            while (i <= radius) {
                p = pix[yi + min(wm, max(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
                i++
            }
            stackpointer = radius

            x = 0
            while (x < w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vmin[x] = min(x + radius + 1, wm)
                }
                p = pix[yw + vmin[x]]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
                x++
            }
            yw += w
            y++
        }

        x = 0
        while (x < w) {
            rinsum = 0; ginsum = 0; binsum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = max(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
                rbs = r1 - abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
                if (i < hm) yp += w
                i++
            }
            yi = x
            stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = (0xff000000.toInt() and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vmin[y] = min(y + r1, hm) * w
                }
                p = x + vmin[y]
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
                y++
            }
            x++
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }
}
