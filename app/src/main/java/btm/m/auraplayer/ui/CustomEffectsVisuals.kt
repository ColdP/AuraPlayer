package btm.m.auraplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.auraplayer.audio.BuiltInEqBandPresets
import btm.m.auraplayer.audio.DspConfig
import btm.m.auraplayer.audio.DspResponseMath
import btm.m.auraplayer.audio.EqBand
import btm.m.auraplayer.audio.EqBandPreset
import btm.m.auraplayer.audio.FilterType
import btm.m.auraplayer.audio.HeadroomBreakdown
import com.materialkolor.hct.Hct
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val EQ_MIN_HZ = 20.0
private const val EQ_MAX_HZ = 20000.0
private val EQ_LOG_SPAN = ln(EQ_MAX_HZ / EQ_MIN_HZ)
private val EQ_SAMPLE_FREQS = DspResponseMath.logFrequencies(120, EQ_MIN_HZ, EQ_MAX_HZ)
private val EQ_GRID_FREQS = doubleArrayOf(50.0, 100.0, 250.0, 1000.0, 4000.0, 10000.0)

private val DefaultBandAccentColors = listOf(
    Color(0xFF4F8CFF),
    Color(0xFF2CB67D),
    Color(0xFFFF9F43),
    Color(0xFFE056FD),
    Color(0xFF00B8D9),
    Color(0xFFFF6B6B),
    Color(0xFF8C7AE6),
    Color(0xFF20BF6B),
)

@Immutable
private data class CustomVisualColors(
    val accent: Color,
    val secondary: Color,
    val warn: Color,
    val danger: Color,
    val surfaceTint: Color,
    val chipTint: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val gridLine: Color,
    val zeroLine: Color,
    val bandColors: List<Color>,
) {
    fun bandColor(index: Int): Color = bandColors.getOrElse(index % bandColors.size) { accent }
}

private fun deriveOklabBandPalette(scheme: OklabColorScheme): List<Color> {
    val primaryLab = OklabThemeEngine.srgbToOklab(scheme.primary.toArgb())
    val baseHue = atan2(primaryLab.b, primaryLab.a)
    val baseChroma = hypot(primaryLab.a, primaryLab.b)
    val isMono = baseChroma < 0.025f
    if (isMono) {
        val toneSteps = if (scheme.dark) {
            floatArrayOf(0.86f, 0.78f, 0.70f, 0.62f, 0.82f, 0.74f, 0.66f, 0.58f)
        } else {
            floatArrayOf(0.28f, 0.36f, 0.44f, 0.52f, 0.32f, 0.40f, 0.48f, 0.56f)
        }
        return toneSteps.map { l -> OklabThemeEngine.oklchColor(l, baseChroma.coerceAtMost(0.014f), baseHue) }
    }
    val targetL = if (scheme.dark) 0.74f else 0.50f
    val targetC = baseChroma.coerceIn(0.09f, 0.16f)
    val degToRad = (PI / 180.0).toFloat()
    val offsetsDeg = floatArrayOf(42f, -42f, 96f, -96f, 152f)
    return buildList(8) {
        add(scheme.primary)
        add(scheme.tertiary)
        add(scheme.secondary)
        offsetsDeg.forEachIndexed { idx, deg ->
            val lShift = if (idx % 2 == 0) 0.03f else -0.02f
            add(OklabThemeEngine.oklchColor((targetL + lShift).coerceIn(0.38f, 0.84f), targetC, baseHue + deg * degToRad))
        }
    }
}

private fun deriveMonetBandPalette(
    primary: Color,
    secondary: Color,
    tertiary: Color,
    isDark: Boolean,
): List<Color> {
    val hct = Hct.fromInt(primary.toArgb())
    val baseHue = hct.hue
    val baseChroma = hct.chroma
    val isMono = baseChroma < 8.0
    if (isMono) {
        val tones = if (isDark) {
            doubleArrayOf(84.0, 76.0, 68.0, 60.0, 80.0, 72.0, 64.0, 56.0)
        } else {
            doubleArrayOf(30.0, 38.0, 46.0, 54.0, 34.0, 42.0, 50.0, 58.0)
        }
        return tones.map { tone -> Color(Hct.from(baseHue, baseChroma.coerceAtMost(6.0), tone).toInt()) }
    }
    val tone = if (isDark) 72.0 else 46.0
    val chroma = baseChroma.coerceIn(32.0, 56.0)
    val offsets = doubleArrayOf(38.0, -38.0, 92.0, -92.0, 148.0)
    return buildList(8) {
        add(primary)
        add(tertiary)
        add(secondary)
        offsets.forEachIndexed { idx, delta ->
            val hue = ((baseHue + delta) % 360.0 + 360.0) % 360.0
            val t = (tone + if (idx % 2 == 0) 4.0 else -3.0).coerceIn(36.0, 82.0)
            add(Color(Hct.from(hue, chroma, t).toInt()))
        }
    }
}

@Composable
private fun rememberCustomVisualColors(): CustomVisualColors {
    val colorStyle = LocalAuraColorStyle.current
    val oklabScheme = LocalAuraOklabScheme.current
    val uiStyle = LocalAppUiStyle.current

    // Dedicated Oklab perceptual palette across HyperOS / Material / One UI
    if (colorStyle.isOklab && oklabScheme != null) {
        val bandColors = remember(oklabScheme.primary, oklabScheme.secondary, oklabScheme.tertiary, oklabScheme.dark) {
            deriveOklabBandPalette(oklabScheme)
        }
        val warnColor = remember(oklabScheme.tertiary, oklabScheme.error, oklabScheme.secondary, oklabScheme.dark) {
            val lab = OklabThemeEngine.srgbToOklab(oklabScheme.primary.toArgb())
            val chroma = hypot(lab.a, lab.b)
            if (chroma < 0.025f) oklabScheme.secondary
            else lerp(oklabScheme.tertiary, oklabScheme.error, if (oklabScheme.dark) 0.40f else 0.34f)
        }
        return when (uiStyle) {
            AppUiStyle.ONE -> CustomVisualColors(
                accent = oklabScheme.oneUiAccent,
                secondary = oklabScheme.tertiary,
                warn = warnColor,
                danger = oklabScheme.error,
                surfaceTint = oklabScheme.oneUiCardAlt.copy(alpha = if (oklabScheme.dark) 0.78f else 0.88f),
                chipTint = oklabScheme.oneUiChip.copy(alpha = 0.78f),
                textPrimary = oklabScheme.oneUiText,
                textSecondary = oklabScheme.oneUiSubText,
                gridLine = oklabScheme.oneUiDivider.copy(alpha = 0.55f),
                zeroLine = oklabScheme.oneUiSubText.copy(alpha = 0.36f),
                bandColors = bandColors,
            )
            AppUiStyle.MATERIAL -> CustomVisualColors(
                accent = oklabScheme.primary,
                secondary = oklabScheme.tertiary,
                warn = warnColor,
                danger = oklabScheme.error,
                surfaceTint = oklabScheme.surfaceContainerHigh.copy(alpha = 0.76f),
                chipTint = oklabScheme.secondaryContainer.copy(alpha = 0.78f),
                textPrimary = oklabScheme.onSurface,
                textSecondary = oklabScheme.onSurfaceVariant,
                gridLine = oklabScheme.outlineVariant.copy(alpha = 0.55f),
                zeroLine = oklabScheme.onSurfaceVariant.copy(alpha = 0.36f),
                bandColors = bandColors,
            )
            AppUiStyle.HYPER -> CustomVisualColors(
                accent = oklabScheme.primary,
                secondary = oklabScheme.tertiary,
                warn = warnColor,
                danger = oklabScheme.error,
                surfaceTint = oklabScheme.primaryContainer.copy(alpha = if (oklabScheme.dark) 0.24f else 0.30f),
                chipTint = oklabScheme.secondaryContainer.copy(alpha = if (oklabScheme.dark) 0.42f else 0.56f),
                textPrimary = oklabScheme.onSurface,
                textSecondary = oklabScheme.onSurfaceVariant,
                gridLine = oklabScheme.outlineVariant.copy(alpha = 0.45f),
                zeroLine = oklabScheme.onSurfaceVariant.copy(alpha = 0.38f),
                bandColors = bandColors,
            )
        }
    }

    // Monet dynamic palette (HCT tonal derivation) across HyperOS / Material / One UI
    if (colorStyle.isMonet) {
        val expressive = rememberActiveExpressiveScheme()
        val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
        val accent = when (uiStyle) {
            AppUiStyle.ONE -> Color(rememberActiveOneUiPalette().accent)
            AppUiStyle.MATERIAL -> expressive.primary
            AppUiStyle.HYPER -> MiuixTheme.colorScheme.primary
        }
        val secondary = expressive.tertiary
        val warnColor = remember(accent, expressive.secondary, expressive.tertiary, expressive.error, isDark) {
            val hct = Hct.fromInt(accent.toArgb())
            if (hct.chroma < 8.0) expressive.secondary
            else Color(Hct.from((hct.hue + 46.0) % 360.0, hct.chroma.coerceIn(34.0, 56.0), if (isDark) 74.0 else 48.0).toInt())
        }
        val bandColors = remember(accent, expressive.secondary, secondary, isDark) {
            deriveMonetBandPalette(accent, expressive.secondary, secondary, isDark)
        }
        return when (uiStyle) {
            AppUiStyle.ONE -> {
                val palette = rememberActiveOneUiPalette()
                CustomVisualColors(
                    accent = accent,
                    secondary = secondary,
                    warn = warnColor,
                    danger = expressive.error,
                    surfaceTint = Color(palette.cardAlt).copy(alpha = if (palette.dark) 0.74f else 0.86f),
                    chipTint = Color(palette.chip).copy(alpha = 0.78f),
                    textPrimary = Color(palette.text),
                    textSecondary = Color(palette.subText),
                    gridLine = Color(palette.divider).copy(alpha = 0.55f),
                    zeroLine = Color(palette.subText).copy(alpha = 0.36f),
                    bandColors = bandColors,
                )
            }
            AppUiStyle.MATERIAL -> CustomVisualColors(
                accent = accent,
                secondary = secondary,
                warn = warnColor,
                danger = expressive.error,
                surfaceTint = expressive.surfaceContainerHigh.copy(alpha = 0.74f),
                chipTint = expressive.secondaryContainer.copy(alpha = 0.78f),
                textPrimary = expressive.onSurface,
                textSecondary = expressive.onSurfaceVariant,
                gridLine = expressive.outlineVariant.copy(alpha = 0.55f),
                zeroLine = expressive.onSurfaceVariant.copy(alpha = 0.36f),
                bandColors = bandColors,
            )
            AppUiStyle.HYPER -> {
                val sub = MiuixTheme.colorScheme.onSurfaceVariantSummary
                CustomVisualColors(
                    accent = accent,
                    secondary = secondary,
                    warn = warnColor,
                    danger = expressive.error,
                    surfaceTint = accent.copy(alpha = if (isDark) 0.10f else 0.075f),
                    chipTint = expressive.secondaryContainer.copy(alpha = if (isDark) 0.38f else 0.50f),
                    textPrimary = MiuixTheme.colorScheme.onSurface,
                    textSecondary = sub,
                    gridLine = sub.copy(alpha = 0.18f),
                    zeroLine = sub.copy(alpha = 0.36f),
                    bandColors = bandColors,
                )
            }
        }
    }

    // Default static theme mode (color extraction OFF)
    return when (uiStyle) {
        AppUiStyle.ONE -> {
            val palette = rememberActiveOneUiPalette()
            val accent = Color(palette.accent)
            CustomVisualColors(
                accent = accent,
                secondary = Color(0xFF2CB67D),
                warn = Color(0xFFF59E0B),
                danger = Color(0xFFEF4444),
                surfaceTint = Color(palette.cardAlt).copy(alpha = if (palette.dark) 0.72f else 0.85f),
                chipTint = Color(palette.chip).copy(alpha = 0.75f),
                textPrimary = Color(palette.text),
                textSecondary = Color(palette.subText),
                gridLine = Color(palette.divider).copy(alpha = 0.55f),
                zeroLine = Color(palette.subText).copy(alpha = 0.35f),
                bandColors = DefaultBandAccentColors,
            )
        }
        AppUiStyle.MATERIAL -> {
            val scheme = rememberActiveExpressiveScheme()
            CustomVisualColors(
                accent = scheme.primary,
                secondary = scheme.tertiary,
                warn = Color(0xFFF59E0B),
                danger = scheme.error,
                surfaceTint = scheme.surfaceContainerHigh.copy(alpha = 0.72f),
                chipTint = scheme.secondaryContainer.copy(alpha = 0.75f),
                textPrimary = scheme.onSurface,
                textSecondary = scheme.onSurfaceVariant,
                gridLine = scheme.outlineVariant.copy(alpha = 0.55f),
                zeroLine = scheme.onSurfaceVariant.copy(alpha = 0.35f),
                bandColors = DefaultBandAccentColors,
            )
        }
        AppUiStyle.HYPER -> {
            val primary = MiuixTheme.colorScheme.primary
            val sub = MiuixTheme.colorScheme.onSurfaceVariantSummary
            CustomVisualColors(
                accent = primary,
                secondary = Color(0xFF2CB67D),
                warn = Color(0xFFF59E0B),
                danger = Color(0xFFEF4444),
                surfaceTint = primary.copy(alpha = 0.065f),
                chipTint = primary.copy(alpha = 0.11f),
                textPrimary = MiuixTheme.colorScheme.onSurface,
                textSecondary = sub,
                gridLine = sub.copy(alpha = 0.16f),
                zeroLine = sub.copy(alpha = 0.36f),
                bandColors = DefaultBandAccentColors,
            )
        }
    }
}

private fun hzToFraction(hz: Double): Float =
    ((ln(hz.coerceIn(EQ_MIN_HZ, EQ_MAX_HZ) / EQ_MIN_HZ)) / EQ_LOG_SPAN).toFloat().coerceIn(0f, 1f)

private fun dbToY(db: Float, height: Float, minDb: Float, maxDb: Float): Float {
    val norm = ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
    return height * (1f - norm)
}

internal fun formatFreqShort(hz: Double): String = when {
    hz >= 10000.0 -> "${(hz / 1000.0).roundToInt()}k Hz"
    hz >= 1000.0 -> {
        val k = hz / 1000.0
        val rounded = (k * 10.0).roundToInt() / 10.0
        if (abs(rounded - rounded.roundToInt()) < 0.05) "${rounded.roundToInt()}k Hz"
        else String.format(Locale.ROOT, "%.1fk Hz", rounded)
    }
    else -> "${hz.roundToInt()} Hz"
}

private fun signedDb(v: Double): String {
    val clean = if (abs(v) < 0.05) 0.0 else v
    val prefix = if (clean > 0.0) "+" else ""
    return "$prefix${String.format(Locale.ROOT, "%.1f", clean)} dB"
}

internal fun eqBandPresetName(preset: EqBandPreset): String = when (preset.id) {
    "flat" -> tr("eq_band_preset_flat", "平直复位")
    "sub_bass" -> tr("eq_band_preset_sub_bass", "超低潜沉")
    "bass_punch" -> tr("eq_band_preset_bass_punch", "低频冲击")
    "warm_body" -> tr("eq_band_preset_warm_body", "暖厚中低")
    "de_mud" -> tr("eq_band_preset_de_mud", "去浑浊切")
    "mid_scoop" -> tr("eq_band_preset_mid_scoop", "中频内凹")
    "vocal_presence" -> tr("eq_band_preset_vocal_presence", "人声凸显")
    "clarity_detail" -> tr("eq_band_preset_clarity_detail", "清晰解析")
    "soften_harsh" -> tr("eq_band_preset_soften_harsh", "柔化刺耳")
    "de_ess" -> tr("eq_band_preset_de_ess", "齿音抑制")
    "smooth_rolloff" -> tr("eq_band_preset_smooth_rolloff", "柔和高频")
    "air_sparkle" -> tr("eq_band_preset_air_sparkle", "高频空气")
    else -> preset.name
}

private fun filterTypeLabel(type: FilterType): String = when (type) {
    FilterType.PEAK -> tr("filter_peak", "峰值")
    FilterType.LOW_SHELF -> tr("filter_low", "低频搁架")
    FilterType.HIGH_SHELF -> tr("filter_high", "高频搁架")
}

@Composable
internal fun EqBandPresetDropdown(
    band: EqBand,
    enabled: Boolean,
    onSelectBand: (EqBand) -> Unit,
) {
    val matched = remember(band) { BuiltInEqBandPresets.match(band) }
    val summary = matched?.let(::eqBandPresetName) ?: tr("eq_band_preset_custom", "自定义参数")
    val items = BuiltInEqBandPresets.all.map { preset ->
        val label = if (preset.id == "flat") {
            "${eqBandPresetName(preset)} · 0.0 dB"
        } else {
            "${eqBandPresetName(preset)} · ${formatFreqShort(preset.band.frequency)} ${signedDb(preset.band.gainDb)}"
        }
        DropdownItem(
            text = label,
            selected = matched?.id == preset.id,
            enabled = true,
            onClick = { onSelectBand(preset.band) },
        )
    }
    GlassDropdownMenu(
        entry = DropdownEntry(items = items),
        title = tr("eq_band_preset", "频段预设"),
        enabled = enabled,
        backdrop = LocalToolbarBackdrop.current,
        maxHeight = 360.dp,
        summaryOverride = summary,
    )
}

/** Master overview graph combining all active EQ bands and node positions on a 20 Hz - 20 kHz log scale. */
@Composable
internal fun OverallEqResponseCard(
    config: DspConfig,
    headroom: HeadroomBreakdown,
    enabled: Boolean,
) {
    val colors = rememberCustomVisualColors()
    val combinedDb = remember(config.bands) { DspResponseMath.combinedResponseDb(config.bands, EQ_SAMPLE_FREQS) }
    val perBandCurves = remember(config.bands) {
        config.bands.map { DspResponseMath.bandResponseDb(it, EQ_SAMPLE_FREQS) }
    }
    val title = tr("effect_combined_response", "综合频响曲线")
    val metaText = if (!enabled) {
        tr("effect_combined_response_bypass", "处理已关闭（原声直通）")
    } else {
        tr("effect_combined_response_meta", "%d 个频段 · 峰值 %s dB")
            .format(config.bands.size, String.format(Locale.ROOT, "+%.1f", headroom.eqPeakDb))
    }

    EffectsCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(10.5.dp))
                .background(colors.surfaceTint)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .alpha(if (enabled) 1f else 0.52f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = metaText,
                    fontSize = 11.5.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(126.dp)
                    .semantics { contentDescription = title },
            ) {
                val minDb = -12f
                val maxDb = 9f
                val w = size.width
                val h = size.height
                val zeroY = dbToY(0f, h, minDb, maxDb)

                // Horizontal dB gridlines
                for (db in listOf(6f, 3f, -3f, -6f, -9f)) {
                    val y = dbToY(db, h, minDb, maxDb)
                    drawLine(colors.gridLine, Offset(0f, y), Offset(w, y), strokeWidth = 0.8.dp.toPx())
                }
                // Vertical log-frequency gridlines
                for (hz in EQ_GRID_FREQS) {
                    val x = hzToFraction(hz) * w
                    drawLine(colors.gridLine, Offset(x, 0f), Offset(x, h), strokeWidth = 0.8.dp.toPx())
                }
                // 0 dB reference line
                drawLine(
                    color = colors.zeroLine,
                    start = Offset(0f, zeroY),
                    end = Offset(w, zeroY),
                    strokeWidth = 1.2.dp.toPx(),
                )

                // Ghost curves for each individual band
                perBandCurves.forEachIndexed { idx, curve ->
                    val b = config.bands.getOrNull(idx) ?: return@forEachIndexed
                    if (abs(b.gainDb) < 0.05) return@forEachIndexed
                    val path = Path()
                    curve.forEachIndexed { i, db ->
                        val x = i.toFloat() / (curve.size - 1) * w
                        val y = dbToY(db, h, minDb, maxDb)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = colors.bandColor(idx).copy(alpha = 0.28f),
                        style = Stroke(width = 1.1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }

                // Combined response curve & gradient fill
                val curvePath = Path()
                val fillPath = Path()
                combinedDb.forEachIndexed { i, db ->
                    val x = i.toFloat() / (combinedDb.size - 1) * w
                    val y = dbToY(db, h, minDb, maxDb)
                    if (i == 0) {
                        curvePath.moveTo(x, y)
                        fillPath.moveTo(x, zeroY)
                        fillPath.lineTo(x, y)
                    } else {
                        curvePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                fillPath.lineTo(w, zeroY)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.accent.copy(alpha = 0.26f), colors.accent.copy(alpha = 0.03f)),
                        startY = 0f,
                        endY = h,
                    ),
                )
                drawPath(
                    path = curvePath,
                    color = colors.accent,
                    style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )

                // Band anchor markers along the spectrum
                config.bands.forEachIndexed { idx, b ->
                    val x = hzToFraction(b.frequency) * w
                    val sampleIdx = ((x / w) * (combinedDb.size - 1)).roundToInt().coerceIn(0, combinedDb.lastIndex)
                    val y = dbToY(combinedDb[sampleIdx], h, minDb, maxDb)
                    val dotColor = colors.bandColor(idx)
                    drawCircle(color = dotColor.copy(alpha = 0.24f), radius = 7.dp.toPx(), center = Offset(x, y))
                    drawCircle(color = dotColor, radius = 3.6.dp.toPx(), center = Offset(x, y))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("20 Hz", "100", "250", "1k", "4k", "16k Hz").forEach { tick ->
                    Text(tick, fontSize = 10.sp, color = colors.textSecondary)
                }
            }
            if (config.bands.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    config.bands.forEachIndexed { idx, b ->
                        val dotColor = colors.bandColor(idx)
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.chipTint)
                                .padding(horizontal = 5.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "${idx + 1}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Graphical breakdown meter for preamp, EQ peak reserve, stereo width reserve, and limiter safety margin. */
@Composable
internal fun HeadroomVisualizer(
    breakdown: HeadroomBreakdown,
    enabled: Boolean,
) {
    val colors = rememberCustomVisualColors()
    val title = tr("effect_headroom_meter", "实时增益余量分布")
    val totalLabel = if (!enabled) {
        tr("effect_headroom_bypass", "已直通 · 0.0 dB")
    } else {
        "${tr("effect_headroom_total", "总增益余量")} ${signedDb(breakdown.totalHeadroomDb)}"
    }

    val preampMag = abs(min(0.0, breakdown.preampDb)).toFloat()
    val eqMag = max(0.0, breakdown.eqPeakDb).toFloat()
    val widthMag = max(0.0, breakdown.widthReserveDb).toFloat()
    val safetyMag = max(0.0, breakdown.safetyMarginDb).toFloat()
    val totalMag = abs(breakdown.totalHeadroomDb).toFloat()
    val scaleMaxDb = max(24f, totalMag + 2f)

    val preampColor = colors.accent
    val eqColor = colors.warn
    val widthColor = colors.secondary
    val safetyColor = colors.danger

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(10.5.dp))
            .background(colors.surfaceTint)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.52f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text(totalLabel, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = colors.accent)
        }
        Spacer(Modifier.height(10.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .semantics { contentDescription = title },
        ) {
            val w = size.width
            val barTop = 4.dp.toPx()
            val barH = 14.dp.toPx()
            val radius = CornerRadius(7.dp.toPx(), 7.dp.toPx())

            // Full track (-scaleMaxDb .. 0 dBFS)
            drawRoundRect(
                color = colors.gridLine.copy(alpha = 0.45f),
                topLeft = Offset(0f, barTop),
                size = Size(w, barH),
                cornerRadius = radius,
            )

            // Draw stacked attenuation segments anchored from 0 dBFS on the right moving left
            fun dbToWidth(db: Float): Float = (db / scaleMaxDb).coerceIn(0f, 1f) * w
            var cursorRight = w
            val segments = listOf(
                safetyMag to safetyColor,
                widthMag to widthColor,
                eqMag to eqColor,
                preampMag to preampColor,
            )
            segments.forEach { (db, color) ->
                if (db > 0.01f && cursorRight > 0f) {
                    val segW = min(dbToWidth(db), cursorRight)
                    val left = (cursorRight - segW).coerceAtLeast(0f)
                    drawRoundRect(
                        color = color.copy(alpha = 0.88f),
                        topLeft = Offset(left, barTop),
                        size = Size(segW, barH),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    )
                    cursorRight = left
                }
            }

            // Net signal ceiling marker line
            val markerX = cursorRight.coerceIn(2.dp.toPx(), w - 2.dp.toPx())
            drawLine(
                color = colors.textPrimary,
                start = Offset(markerX, 0f),
                end = Offset(markerX, barTop + barH + 5.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // Scale tick marks below bar
            val tickY1 = barTop + barH + 4.dp.toPx()
            val tickY2 = barTop + barH + 9.dp.toPx()
            for (step in listOf(0f, 6f, 12f, 18f, 24f)) {
                if (step <= scaleMaxDb) {
                    val x = w - dbToWidth(step)
                    drawLine(colors.zeroLine, Offset(x, tickY1), Offset(x, tickY2), strokeWidth = 1.dp.toPx())
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("-${scaleMaxDb.roundToInt()} dB", fontSize = 10.sp, color = colors.textSecondary)
            Text("-12 dB", fontSize = 10.sp, color = colors.textSecondary)
            Text("-6 dB", fontSize = 10.sp, color = colors.textSecondary)
            Text("0 dBFS", fontSize = 10.sp, color = colors.textSecondary)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeadroomLegendChip(
                modifier = Modifier.weight(1f),
                dotColor = preampColor,
                label = tr("effect_preamp", "前置增益"),
                value = signedDb(breakdown.preampDb),
                colors = colors,
            )
            HeadroomLegendChip(
                modifier = Modifier.weight(1f),
                dotColor = eqColor,
                label = tr("effect_headroom_eq_peak", "EQ 峰值预留"),
                value = signedDb(-breakdown.eqPeakDb),
                colors = colors,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeadroomLegendChip(
                modifier = Modifier.weight(1f),
                dotColor = widthColor,
                label = tr("effect_headroom_width_reserve", "声场预留"),
                value = signedDb(-breakdown.widthReserveDb),
                colors = colors,
            )
            HeadroomLegendChip(
                modifier = Modifier.weight(1f),
                dotColor = safetyColor,
                label = tr("effect_headroom_limiter_guard", "限幅保护"),
                value = signedDb(-breakdown.safetyMarginDb),
                colors = colors,
            )
        }
    }
}

@Composable
private fun HeadroomLegendChip(
    modifier: Modifier,
    dotColor: Color,
    label: String,
    value: String,
    colors: CustomVisualColors,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.chipTint)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 10.5.sp, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1)
        }
    }
}

/** Per-band frequency response preview graph rendered at the top of every EQ band group. */
@Composable
internal fun BandResponseVisualizer(
    band: EqBand,
    bandIndex: Int,
    allBands: List<EqBand>,
    enabled: Boolean,
) {
    val colors = rememberCustomVisualColors()
    val accent = colors.bandColor(bandIndex)
    val bandCurve = remember(band) { DspResponseMath.bandResponseDb(band, EQ_SAMPLE_FREQS) }
    val combinedCurve = remember(allBands) { DspResponseMath.combinedResponseDb(allBands, EQ_SAMPLE_FREQS) }
    val description = tr("effect_band_response_preview", "单频段频响示意")
    val readout = "${formatFreqShort(band.frequency)} · ${signedDb(band.gainDb)} · Q ${String.format(Locale.ROOT, "%.2f", band.q)}"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(10.5.dp))
            .background(colors.surfaceTint)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.52f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(accent.copy(alpha = 0.16f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(5.dp))
                Text(
                    text = filterTypeLabel(band.type),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
            Text(
                text = readout,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp)
                .semantics { contentDescription = description },
        ) {
            val minDb = -10.5f
            val maxDb = 7.5f
            val w = size.width
            val h = size.height
            val zeroY = dbToY(0f, h, minDb, maxDb)

            // Grid lines
            for (db in listOf(6f, 3f, -3f, -6f, -9f)) {
                val y = dbToY(db, h, minDb, maxDb)
                drawLine(colors.gridLine, Offset(0f, y), Offset(w, y), strokeWidth = 0.7.dp.toPx())
            }
            for (hz in EQ_GRID_FREQS) {
                val x = hzToFraction(hz) * w
                drawLine(colors.gridLine, Offset(x, 0f), Offset(x, h), strokeWidth = 0.7.dp.toPx())
            }
            drawLine(colors.zeroLine, Offset(0f, zeroY), Offset(w, zeroY), strokeWidth = 1.1.dp.toPx())

            // Faint combined reference outline so the user sees how this band contributes to the whole EQ
            if (allBands.size > 1) {
                val refPath = Path()
                combinedCurve.forEachIndexed { i, db ->
                    val x = i.toFloat() / (combinedCurve.size - 1) * w
                    val y = dbToY(db, h, minDb, maxDb)
                    if (i == 0) refPath.moveTo(x, y) else refPath.lineTo(x, y)
                }
                drawPath(
                    path = refPath,
                    color = colors.textSecondary.copy(alpha = 0.25f),
                    style = Stroke(
                        width = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                    ),
                )
            }

            // Highlight curve and shaded fill for this single band
            val bandPath = Path()
            val fillPath = Path()
            bandCurve.forEachIndexed { i, db ->
                val x = i.toFloat() / (bandCurve.size - 1) * w
                val y = dbToY(db, h, minDb, maxDb)
                if (i == 0) {
                    bandPath.moveTo(x, y)
                    fillPath.moveTo(x, zeroY)
                    fillPath.lineTo(x, y)
                } else {
                    bandPath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(w, zeroY)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.04f)),
                    startY = 0f,
                    endY = h,
                ),
            )
            drawPath(
                path = bandPath,
                color = accent,
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            // Center frequency vertical guide + anchor dot
            val centerX = hzToFraction(band.frequency) * w
            val sampleIdx = ((centerX / w) * (bandCurve.size - 1)).roundToInt().coerceIn(0, bandCurve.lastIndex)
            val centerY = dbToY(bandCurve[sampleIdx], h, minDb, maxDb)
            drawLine(
                color = accent.copy(alpha = 0.45f),
                start = Offset(centerX, 0f),
                end = Offset(centerX, h),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)),
            )
            drawCircle(color = accent.copy(alpha = 0.25f), radius = 7.dp.toPx(), center = Offset(centerX, centerY))
            drawCircle(color = accent, radius = 3.5.dp.toPx(), center = Offset(centerX, centerY))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("20", "100", "250", "1k", "4k", "20k Hz").forEach { tick ->
                Text(tick, fontSize = 9.5.sp, color = colors.textSecondary)
            }
        }
    }
}

/** Side-by-side stereo stage + harmonic saturation visual preview for the Spatial & Tone group. */
@Composable
internal fun SpatialVisualPreview(
    width: Double,
    crossfeed: Double,
    saturation: Double,
    enabled: Boolean,
) {
    val colors = rememberCustomVisualColors()
    val stageTitle = tr("effect_spatial_stage_preview", "声场与交叉馈送")
    val satTitle = tr("effect_spatial_saturation_preview", "谐波染色曲线")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
            .alpha(if (enabled) 1f else 0.52f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Soundstage + Crossfeed radar
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.5.dp))
                .background(colors.surfaceTint)
                .padding(10.dp),
        ) {
            Text(stageTitle, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp)
                    .semantics { contentDescription = stageTitle },
            ) {
                val cx = size.width / 2f
                val cy = size.height * 0.88f
                val radius = min(size.width * 0.44f, size.height * 0.78f)
                val arcRect = Size(radius * 2, radius * 2)
                val arcTopLeft = Offset(cx - radius, cy - radius)

                // Reference 180-degree stage arc
                drawArc(
                    color = colors.gridLine,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcRect,
                    style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    color = colors.gridLine.copy(alpha = 0.5f),
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(cx - radius * 0.55f, cy - radius * 0.55f),
                    size = Size(radius * 1.1f, radius * 1.1f),
                    style = Stroke(0.9.dp.toPx()),
                )

                // Stereo width sweep centered at 270 deg (top)
                val sweepDeg = (width.toFloat().coerceIn(0.5f, 1.4f) / 1.4f * 150f).coerceIn(36f, 156f)
                val startDeg = 270f - sweepDeg / 2f
                drawArc(
                    color = colors.accent.copy(alpha = 0.22f),
                    startAngle = startDeg,
                    sweepAngle = sweepDeg,
                    useCenter = true,
                    topLeft = arcTopLeft,
                    size = arcRect,
                )
                drawArc(
                    color = colors.accent,
                    startAngle = startDeg,
                    sweepAngle = sweepDeg,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcRect,
                    style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round),
                )

                // L and R emitter points
                val leftRad = Math.toRadians(startDeg.toDouble())
                val rightRad = Math.toRadians((startDeg + sweepDeg).toDouble())
                val lx = cx + (cos(leftRad) * radius).toFloat()
                val ly = cy + (sin(leftRad) * radius).toFloat()
                val rx = cx + (cos(rightRad) * radius).toFloat()
                val ry = cy + (sin(rightRad) * radius).toFloat()

                // Crossfeed blend bridge between L and R
                if (crossfeed > 0.005) {
                    val cfNorm = (crossfeed / 0.30).toFloat().coerceIn(0f, 1f)
                    val bridge = Path().apply {
                        moveTo(lx, ly)
                        quadraticTo(cx, cy - radius * (0.28f + 0.25f * cfNorm), rx, ry)
                    }
                    drawPath(
                        path = bridge,
                        color = colors.secondary.copy(alpha = 0.35f + 0.6f * cfNorm),
                        style = Stroke(width = (1.2f + 1.8f * cfNorm).dp.toPx(), cap = StrokeCap.Round),
                    )
                }

                drawCircle(colors.accent, radius = 3.5.dp.toPx(), center = Offset(lx, ly))
                drawCircle(colors.accent, radius = 3.5.dp.toPx(), center = Offset(rx, ry))
                drawCircle(colors.textPrimary, radius = 3.dp.toPx(), center = Offset(cx, cy))
            }
            Text(
                text = "${(width * 100).roundToInt()}% · CF ${(crossfeed * 100).roundToInt()}%",
                fontSize = 10.sp,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }

        // Harmonic saturation transfer curve
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.5.dp))
                .background(colors.surfaceTint)
                .padding(10.dp),
        ) {
            Text(satTitle, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp)
                    .semantics { contentDescription = satTitle },
            ) {
                val w = size.width
                val h = size.height
                val cx = w / 2f
                val cy = h / 2f
                drawLine(colors.gridLine, Offset(0f, cy), Offset(w, cy), strokeWidth = 0.8.dp.toPx())
                drawLine(colors.gridLine, Offset(cx, 0f), Offset(cx, h), strokeWidth = 0.8.dp.toPx())
                // Linear reference y = x
                drawLine(
                    color = colors.zeroLine,
                    start = Offset(0f, h),
                    end = Offset(w, 0f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)),
                )
                // Actual tanh saturation transfer curve matching DspEngine
                val steps = 48
                val curvePath = Path()
                for (i in 0..steps) {
                    val xNorm = -1.0 + 2.0 * i / steps
                    val yNorm = DspResponseMath.saturationTransfer(xNorm, saturation)
                    val px = ((xNorm + 1.0) * 0.5 * w).toFloat()
                    val py = ((1.0 - (yNorm + 1.0) * 0.5) * h).toFloat()
                    if (i == 0) curvePath.moveTo(px, py) else curvePath.lineTo(px, py)
                }
                drawPath(
                    path = curvePath,
                    color = if (saturation > 0.005) colors.warn else colors.accent,
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            Text(
                text = "${tr("effect_saturation", "谐波染色")} ${(saturation * 100).roundToInt()}%",
                fontSize = 10.sp,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

/** Side-by-side compressor transfer curve + attack/release envelope preview for the Dynamics group. */
@Composable
internal fun DynamicsVisualPreview(
    thresholdDb: Double,
    ratio: Double,
    attackMs: Double,
    releaseMs: Double,
    enabled: Boolean,
) {
    val colors = rememberCustomVisualColors()
    val transferTitle = tr("effect_dynamics_transfer_preview", "压缩传递曲线")
    val envTitle = tr("effect_dynamics_envelope_preview", "包络响应示意")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
            .alpha(if (enabled) 1f else 0.52f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Compressor I/O transfer curve (-36 dB .. 0 dB)
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.5.dp))
                .background(colors.surfaceTint)
                .padding(10.dp),
        ) {
            Text(transferTitle, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp)
                    .semantics { contentDescription = transferTitle },
            ) {
                val minDb = -36.0
                val maxDb = 0.0
                val w = size.width
                val h = size.height
                fun xOf(db: Double): Float = ((db - minDb) / (maxDb - minDb)).toFloat().coerceIn(0f, 1f) * w
                fun yOf(db: Double): Float = (1f - ((db - minDb) / (maxDb - minDb)).toFloat().coerceIn(0f, 1f)) * h

                // Subtle grid
                for (db in listOf(-24.0, -12.0)) {
                    drawLine(colors.gridLine, Offset(xOf(db), 0f), Offset(xOf(db), h), strokeWidth = 0.7.dp.toPx())
                    drawLine(colors.gridLine, Offset(0f, yOf(db)), Offset(w, yOf(db)), strokeWidth = 0.7.dp.toPx())
                }
                // 1:1 reference diagonal
                drawLine(
                    color = colors.zeroLine,
                    start = Offset(0f, h),
                    end = Offset(w, 0f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)),
                )

                val kneeX = xOf(thresholdDb)
                val kneeY = yOf(thresholdDb)
                val endOutDb = DspResponseMath.compressorOutputDb(0.0, thresholdDb, ratio)
                val endY = yOf(endOutDb)

                // Shaded gain-reduction wedge above threshold
                if (ratio > 1.02) {
                    val reductionArea = Path().apply {
                        moveTo(kneeX, kneeY)
                        lineTo(w, 0f)
                        lineTo(w, endY)
                        close()
                    }
                    drawPath(reductionArea, color = colors.accent.copy(alpha = 0.22f))
                }

                // Active transfer line
                val curve = Path().apply {
                    moveTo(0f, h)
                    lineTo(kneeX, kneeY)
                    lineTo(w, endY)
                }
                drawPath(
                    path = curve,
                    color = colors.accent,
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawCircle(colors.accent, radius = 3.5.dp.toPx(), center = Offset(kneeX, kneeY))
            }
            Text(
                text = "${String.format(Locale.ROOT, "%.1f", thresholdDb)} dB · ${String.format(Locale.ROOT, "%.1f", ratio)}:1",
                fontSize = 10.sp,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }

        // Attack / release step envelope visualization
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.5.dp))
                .background(colors.surfaceTint)
                .padding(10.dp),
        ) {
            Text(envTitle, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp)
                    .semantics { contentDescription = envTitle },
            ) {
                val w = size.width
                val h = size.height
                val baseY = h * 0.18f
                val depth = if (ratio <= 1.02) 0f else ((1.0 - 1.0 / ratio).toFloat() * h * 0.68f)
                val triggerX = w * 0.16f
                val releaseX = w * 0.46f
                val attackTau = ((attackMs / 100.0).coerceIn(0.03, 1.0) * (releaseX - triggerX) * 0.65).toFloat()
                val releaseTau = ((releaseMs / 1000.0).coerceIn(0.05, 1.0) * (w - releaseX) * 0.55).toFloat()

                drawLine(colors.zeroLine, Offset(0f, baseY), Offset(w, baseY), strokeWidth = 0.9.dp.toPx())
                drawLine(
                    colors.gridLine,
                    Offset(triggerX, 0f),
                    Offset(triggerX, h),
                    strokeWidth = 0.8.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                )
                drawLine(
                    colors.gridLine,
                    Offset(releaseX, 0f),
                    Offset(releaseX, h),
                    strokeWidth = 0.8.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                )

                val steps = 64
                val envPath = Path()
                val fillPath = Path()
                val holdLevel = 1f - exp(-((releaseX - triggerX) / max(1f, attackTau)))
                for (i in 0..steps) {
                    val x = i.toFloat() / steps * w
                    val gr = when {
                        x < triggerX -> 0f
                        x < releaseX -> 1f - exp(-((x - triggerX) / max(1f, attackTau)))
                        else -> holdLevel * exp(-((x - releaseX) / max(1f, releaseTau)))
                    }
                    val y = baseY + gr * depth
                    if (i == 0) {
                        envPath.moveTo(x, y)
                        fillPath.moveTo(x, baseY)
                        fillPath.lineTo(x, y)
                    } else {
                        envPath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                fillPath.lineTo(w, baseY)
                fillPath.close()

                drawPath(fillPath, color = colors.secondary.copy(alpha = 0.22f))
                drawPath(
                    path = envPath,
                    color = colors.secondary,
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            Text(
                text = "A ${attackMs.roundToInt()} ms · R ${releaseMs.roundToInt()} ms",
                fontSize = 10.sp,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}
