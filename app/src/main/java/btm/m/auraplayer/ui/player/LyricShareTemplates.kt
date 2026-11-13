// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import android.graphics.Typeface
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.auraplayer.lyrics.LyricLine
import btm.m.auraplayer.lyrics.LyricSize
import btm.m.auraplayer.ui.tr
import com.materialkolor.palettes.CorePalette
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val MAX_LYRIC_SHARE_LINES = 16

internal enum class LyricShareTemplateId {
    AURA_MESH,
    MIST_COVER,
    VINYL_SLEEVE,
    EDITORIAL_PAPER,
    SWISS_GRID,
    POLAROID,
    STAGE_SPOTLIGHT,
    MIDNIGHT_INK,
    SUNSET_CINEMA,
    FOREST_MIST,
    ABYSSAL_TIDE,
    NEON_PULSE,
}

internal data class LyricShareTemplate(
    val id: LyricShareTemplateId,
    val title: String,
    val lightPaper: Boolean = false,
)

internal fun lyricShareTemplates(): List<LyricShareTemplate> = listOf(
    LyricShareTemplate(LyricShareTemplateId.AURA_MESH, tr("share_tpl_aura_mesh", "极光流光")),
    LyricShareTemplate(LyricShareTemplateId.MIST_COVER, tr("share_tpl_mist_cover", "封面弥散")),
    LyricShareTemplate(LyricShareTemplateId.VINYL_SLEEVE, tr("share_tpl_vinyl", "黑胶唱片")),
    LyricShareTemplate(LyricShareTemplateId.EDITORIAL_PAPER, tr("share_tpl_paper", "素雅纸笺"), lightPaper = true),
    LyricShareTemplate(LyricShareTemplateId.SWISS_GRID, tr("share_tpl_swiss", "瑞士网格"), lightPaper = true),
    LyricShareTemplate(LyricShareTemplateId.POLAROID, tr("share_tpl_polaroid", "拍立得"), lightPaper = true),
    LyricShareTemplate(LyricShareTemplateId.STAGE_SPOTLIGHT, tr("share_tpl_spotlight", "沉浸舞台")),
    LyricShareTemplate(LyricShareTemplateId.MIDNIGHT_INK, tr("share_tpl_midnight", "深夜墨韵")),
    LyricShareTemplate(LyricShareTemplateId.SUNSET_CINEMA, tr("share_tpl_cinema", "落日胶片")),
    LyricShareTemplate(LyricShareTemplateId.FOREST_MIST, tr("share_tpl_forest", "森屿晨雾")),
    LyricShareTemplate(LyricShareTemplateId.ABYSSAL_TIDE, tr("share_tpl_ocean", "深海潮汐")),
    LyricShareTemplate(LyricShareTemplateId.NEON_PULSE, tr("share_tpl_neon", "霓虹声波")),
)

internal fun toggleLyricShareIndex(selected: Set<Int>, index: Int, limit: Int = MAX_LYRIC_SHARE_LINES): Set<Int> =
    if (index in selected) selected - index
    else if (selected.size < limit) selected + index
    else selected

internal enum class LyricShareLineSpacing(val gap: Dp, val lineHeightScale: Float) {
    COMPACT(6.dp, 0.88f),
    REGULAR(10.dp, 1.0f),
    RELAXED(15.dp, 1.12f),
    LOOSE(20.dp, 1.24f),
}

@Composable
internal fun lyricShareLineSpacingLabel(spacing: LyricShareLineSpacing): String = when (spacing) {
    LyricShareLineSpacing.COMPACT -> tr("share_line_spacing_compact", "紧凑")
    LyricShareLineSpacing.REGULAR -> tr("share_line_spacing_regular", "标准")
    LyricShareLineSpacing.RELAXED -> tr("share_line_spacing_relaxed", "宽松")
    LyricShareLineSpacing.LOOSE -> tr("share_line_spacing_loose", "疏朗")
}

internal enum class LyricShareFont {
    SYSTEM_DEFAULT,
    SERIF,
    ROUNDED,
    PRESET_FOLDER,
    CUSTOM,
}

@Composable
internal fun lyricShareFontLabel(font: LyricShareFont): String = when (font) {
    LyricShareFont.SYSTEM_DEFAULT -> tr("share_font_system", "系统默认")
    LyricShareFont.SERIF -> tr("share_font_serif", "典雅宋体")
    LyricShareFont.ROUNDED -> tr("share_font_rounded", "柔和圆体")
    LyricShareFont.PRESET_FOLDER, LyricShareFont.CUSTOM -> tr("share_font_custom", "自定义字体")
}

@Composable
internal fun rememberPosterFontFamily(
    font: LyricShareFont,
    customFontFamily: FontFamily?,
    savedFontFamily: FontFamily? = null,
): FontFamily {
    val roundedFamily = remember { FontFamily(Typeface.create("sans-serif-rounded", Typeface.NORMAL)) }
    return when (font) {
        LyricShareFont.SYSTEM_DEFAULT -> FontFamily.Default
        LyricShareFont.SERIF -> FontFamily.Serif
        LyricShareFont.ROUNDED -> roundedFamily
        LyricShareFont.PRESET_FOLDER -> savedFontFamily ?: FontFamily.Default
        LyricShareFont.CUSTOM -> customFontFamily ?: FontFamily.Default
    }
}

internal enum class LyricShareImageQuality(val scale: Float) {
    VERY_LOW(0.4f),
    LOW(0.65f),
    NORMAL(1.0f),
    HIGH(1.5f),
    ULTRA(2.0f),
}

@Composable
internal fun lyricShareImageQualityLabel(quality: LyricShareImageQuality): String = when (quality) {
    LyricShareImageQuality.VERY_LOW -> tr("share_quality_very_low", "极低")
    LyricShareImageQuality.LOW -> tr("share_quality_low", "低")
    LyricShareImageQuality.NORMAL -> tr("share_quality_normal", "常规")
    LyricShareImageQuality.HIGH -> tr("share_quality_high", "高")
    LyricShareImageQuality.ULTRA -> tr("share_quality_ultra", "极高")
}

internal fun computeExportDimensions(
    width: Int,
    height: Int,
    quality: LyricShareImageQuality,
    maxEdge: Int = 4096,
): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 1 to 1
    val rawW = (width * quality.scale).roundToInt().coerceAtLeast(1)
    val rawH = (height * quality.scale).roundToInt().coerceAtLeast(1)
    val longest = max(rawW, rawH)
    if (longest <= maxEdge) return rawW to rawH
    val capScale = maxEdge.toFloat() / longest.toFloat()
    return (rawW * capScale).roundToInt().coerceAtLeast(1) to (rawH * capScale).roundToInt().coerceAtLeast(1)
}

internal enum class LyricShareFilter {
    DEFAULT,
    LEICA_M3,
    LEICA_M9,
    LEICA_M10R,
    MILLENNIUM,
    ABAO,
    FUJI_NC,
    KODAK_GOLD,
    TEAL_ORANGE,
    POLAROID_FADE,
    MORIYAMA_BW,
    CYBER_NEON,
}

@Composable
internal fun lyricShareFilterLabel(filter: LyricShareFilter): String = when (filter) {
    LyricShareFilter.DEFAULT -> tr("share_filter_default", "默认")
    LyricShareFilter.LEICA_M3 -> tr("share_filter_m3", "徕卡 M3")
    LyricShareFilter.LEICA_M9 -> tr("share_filter_m9", "徕卡 M9")
    LyricShareFilter.LEICA_M10R -> tr("share_filter_m10r", "徕卡 M10R")
    LyricShareFilter.MILLENNIUM -> tr("share_filter_millennium", "千禧年")
    LyricShareFilter.ABAO -> tr("share_filter_abao", "阿宝色")
    LyricShareFilter.FUJI_NC -> tr("share_filter_fuji_nc", "富士 NC")
    LyricShareFilter.KODAK_GOLD -> tr("share_filter_kodak_gold", "柯达金 200")
    LyricShareFilter.TEAL_ORANGE -> tr("share_filter_teal_orange", "电影青橙")
    LyricShareFilter.POLAROID_FADE -> tr("share_filter_polaroid_fade", "宝丽来褪色")
    LyricShareFilter.MORIYAMA_BW -> tr("share_filter_moriyama", "高反差黑白")
    LyricShareFilter.CYBER_NEON -> tr("share_filter_cyber", "赛博霓虹")
}

private fun toneMatrix(
    saturation: Float = 1f,
    contrast: Float = 1f,
    rScale: Float = 1f,
    gScale: Float = 1f,
    bScale: Float = 1f,
    rBias: Float = 0f,
    gBias: Float = 0f,
    bBias: Float = 0f,
): ColorMatrix {
    val sat = ColorMatrix().apply { setToSaturation(saturation) }
    val cOffset = (-0.5f * contrast + 0.5f) * 255f
    val grade = ColorMatrix(
        floatArrayOf(
            contrast * rScale, 0f, 0f, 0f, cOffset + rBias,
            0f, contrast * gScale, 0f, 0f, cOffset + gBias,
            0f, 0f, contrast * bScale, 0f, cOffset + bBias,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    return grade.apply { timesAssign(sat) }
}

private fun posterFilterMatrix(filter: LyricShareFilter): ColorMatrix = when (filter) {
    LyricShareFilter.DEFAULT -> ColorMatrix()
    LyricShareFilter.LEICA_M3 -> toneMatrix(saturation = 0f, contrast = 1.14f, rScale = 1.02f, gScale = 1.0f, bScale = 0.96f, rBias = -2f, gBias = -3f, bBias = -5f)
    LyricShareFilter.LEICA_M9 -> toneMatrix(saturation = 1.18f, contrast = 1.09f, rScale = 1.06f, gScale = 0.99f, bScale = 0.96f, rBias = 2f, gBias = -2f, bBias = -4f)
    LyricShareFilter.LEICA_M10R -> toneMatrix(saturation = 1.06f, contrast = 1.04f, rScale = 1.01f, gScale = 1.01f, bScale = 0.98f, rBias = 3f, gBias = 3f, bBias = 1f)
    LyricShareFilter.MILLENNIUM -> toneMatrix(saturation = 0.88f, contrast = 0.95f, rScale = 0.90f, gScale = 1.04f, bScale = 1.08f, rBias = 4f, gBias = 10f, bBias = 14f)
    LyricShareFilter.ABAO -> toneMatrix(saturation = 0.92f, contrast = 1.02f, rScale = 1.04f, gScale = 1.00f, bScale = 1.07f, rBias = 7f, gBias = 4f, bBias = 12f)
    LyricShareFilter.FUJI_NC -> toneMatrix(saturation = 0.85f, contrast = 1.11f, rScale = 0.96f, gScale = 1.03f, bScale = 0.98f, rBias = 2f, gBias = 6f, bBias = 3f)
    LyricShareFilter.KODAK_GOLD -> toneMatrix(saturation = 1.10f, contrast = 1.04f, rScale = 1.08f, gScale = 1.02f, bScale = 0.86f, rBias = 8f, gBias = 4f, bBias = -4f)
    LyricShareFilter.TEAL_ORANGE -> toneMatrix(saturation = 1.12f, contrast = 1.12f, rScale = 1.06f, gScale = 0.98f, bScale = 1.02f, rBias = 6f, gBias = 2f, bBias = 8f)
    LyricShareFilter.POLAROID_FADE -> toneMatrix(saturation = 0.78f, contrast = 0.86f, rScale = 1.02f, gScale = 1.01f, bScale = 0.92f, rBias = 22f, gBias = 20f, bBias = 14f)
    LyricShareFilter.MORIYAMA_BW -> toneMatrix(saturation = 0f, contrast = 1.38f, rBias = -14f, gBias = -14f, bBias = -14f)
    LyricShareFilter.CYBER_NEON -> toneMatrix(saturation = 1.28f, contrast = 1.14f, rScale = 1.04f, gScale = 0.92f, bScale = 1.12f, rBias = 4f, gBias = -6f, bBias = 12f)
}

private fun Modifier.posterColorFilter(filter: LyricShareFilter): Modifier {
    if (filter == LyricShareFilter.DEFAULT) return this
    val matrix = posterFilterMatrix(filter)
    return drawWithContent {
        val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(matrix) }
        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), paint)
        drawContent()
        drawContext.canvas.restore()
        if (filter == LyricShareFilter.LEICA_M3 || filter == LyricShareFilter.MORIYAMA_BW) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = if (filter == LyricShareFilter.MORIYAMA_BW) .34f else .22f)),
                    center = center,
                    radius = size.maxDimension * .72f,
                )
            )
        }
    }
}

private data class PosterPalette(
    val ink: Color,
    val secondaryInk: Color,
    val accent: Color,
)

private data class PosterRenderProps(
    val title: String,
    val artist: String,
    val coverBitmap: ImageBitmap?,
    val coverPalette: CorePalette,
    val lines: List<LyricLine>,
    val showTranslation: Boolean,
    val showPronunciation: Boolean,
    val showSongInfo: Boolean,
    val showBrand: Boolean,
    val lyricSize: LyricSize,
    val lineSpacing: LyricShareLineSpacing,
    val fontFamily: FontFamily,
    val customBackground: ImageBitmap?,
    val customBackgroundBlur: Float,
    val paperDarkMode: Boolean,
    val colors: PosterPalette,
)

@Composable
private fun posterPalette(id: LyricShareTemplateId, palette: CorePalette, paperDarkMode: Boolean): PosterPalette = when (id) {
    LyricShareTemplateId.EDITORIAL_PAPER ->
        if (paperDarkMode) PosterPalette(Color(0xFFF2EDE3), Color(0xFFA79F93), Color(0xFFE37C62))
        else PosterPalette(Color(0xFF1F1B18), Color(0xFF6B6257), Color(0xFFC2593F))
    LyricShareTemplateId.SWISS_GRID ->
        if (paperDarkMode) PosterPalette(Color(0xFFF4F4F1), Color(0xFF9B9BA3), Color(palette.a1.tone(72)))
        else PosterPalette(Color(0xFF121214), Color(0xFF5A5A60), Color(palette.a1.tone(44)))
    LyricShareTemplateId.POLAROID -> PosterPalette(Color(0xFFF9FAFB), Color(0xFFD0D5DD), Color(palette.a1.tone(75)))
    LyricShareTemplateId.AURA_MESH -> PosterPalette(Color.White, Color.White.copy(alpha = .74f), Color(palette.a1.tone(82)))
    LyricShareTemplateId.MIST_COVER -> PosterPalette(Color.White, Color.White.copy(alpha = .78f), Color.White.copy(alpha = .90f))
    LyricShareTemplateId.VINYL_SLEEVE -> PosterPalette(Color(0xFFF6F4EE), Color(0xFFB5B1A8), Color(0xFFDDBA73))
    LyricShareTemplateId.STAGE_SPOTLIGHT -> PosterPalette(Color.White, Color.White.copy(alpha = .74f), Color(palette.a1.tone(82)))
    LyricShareTemplateId.MIDNIGHT_INK -> PosterPalette(Color(0xFFF2F5FA), Color(0xFF99A4B8), Color(0xFFE07368))
    LyricShareTemplateId.SUNSET_CINEMA -> PosterPalette(Color(0xFFFFF5EC), Color(0xFFF3C6B4), Color(0xFFFFA56B))
    LyricShareTemplateId.FOREST_MIST -> PosterPalette(Color(0xFFF2F8F4), Color(0xFFB4CFC0), Color(0xFF8BE0B5))
    LyricShareTemplateId.ABYSSAL_TIDE -> PosterPalette(Color(0xFFECF7FF), Color(0xFF9EC9E2), Color(0xFF5CD6EE))
    LyricShareTemplateId.NEON_PULSE -> PosterPalette(Color(0xFFF7F4FF), Color(0xFFC4B6E8), Color(0xFF3BF0E8))
}

@Composable
internal fun SharePosterCard(
    template: LyricShareTemplate,
    title: String,
    artist: String,
    coverBitmap: ImageBitmap?,
    coverPalette: CorePalette,
    lines: List<LyricLine>,
    showTranslation: Boolean,
    showPronunciation: Boolean,
    showSongInfo: Boolean,
    showBrand: Boolean = true,
    lyricSize: LyricSize,
    lineSpacing: LyricShareLineSpacing = LyricShareLineSpacing.REGULAR,
    roundedCorners: Boolean = false,
    paperDarkMode: Boolean = false,
    lyricFont: LyricShareFont = LyricShareFont.SYSTEM_DEFAULT,
    customFontFamily: FontFamily? = null,
    savedFontFamily: FontFamily? = null,
    filter: LyricShareFilter = LyricShareFilter.DEFAULT,
    customBackground: ImageBitmap? = null,
    customBackgroundBlur: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val targetColors = posterPalette(template.id, coverPalette, paperDarkMode)
    val colorAnimSpec = tween<Color>(durationMillis = 380, easing = FastOutSlowInEasing)
    val animatedInk = animateColorAsState(targetColors.ink, colorAnimSpec, label = "posterInk").value
    val animatedSecondaryInk = animateColorAsState(targetColors.secondaryInk, colorAnimSpec, label = "posterSecondaryInk").value
    val animatedAccent = animateColorAsState(targetColors.accent, colorAnimSpec, label = "posterAccent").value
    val colors = PosterPalette(animatedInk, animatedSecondaryInk, animatedAccent)
    val activeFont = rememberPosterFontFamily(lyricFont, customFontFamily, savedFontFamily)
    val cornerDp = if (!roundedCorners) 0.dp else if (template.id == LyricShareTemplateId.POLAROID) 12.dp else 20.dp
    val props = PosterRenderProps(
        title = title,
        artist = artist,
        coverBitmap = coverBitmap,
        coverPalette = coverPalette,
        lines = lines,
        showTranslation = showTranslation,
        showPronunciation = showPronunciation,
        showSongInfo = showSongInfo,
        showBrand = showBrand,
        lyricSize = lyricSize,
        lineSpacing = lineSpacing,
        fontFamily = activeFont,
        customBackground = customBackground,
        customBackgroundBlur = customBackgroundBlur,
        paperDarkMode = paperDarkMode,
        colors = colors,
    )

    Box(modifier.clip(RoundedCornerShape(cornerDp)).clipToBounds().posterColorFilter(filter)) {
        PosterBackgroundLayer(
            id = template.id,
            coverBitmap = coverBitmap,
            coverPalette = coverPalette,
            customBackground = customBackground,
            customBackgroundBlur = customBackgroundBlur,
            roundedCorners = roundedCorners,
            paperDarkMode = paperDarkMode,
            modifier = Modifier.matchParentSize(),
        )
        when (template.id) {
            LyricShareTemplateId.AURA_MESH -> AuraMeshPosterLayout(props)
            LyricShareTemplateId.MIST_COVER -> MistCoverPosterLayout(props)
            LyricShareTemplateId.VINYL_SLEEVE -> VinylSleevePosterLayout(props)
            LyricShareTemplateId.EDITORIAL_PAPER -> EditorialPaperPosterLayout(props)
            LyricShareTemplateId.SWISS_GRID -> SwissGridPosterLayout(props)
            LyricShareTemplateId.POLAROID -> PolaroidPosterLayout(props)
            LyricShareTemplateId.STAGE_SPOTLIGHT -> StageSpotlightPosterLayout(props)
            LyricShareTemplateId.MIDNIGHT_INK -> MidnightInkPosterLayout(props)
            LyricShareTemplateId.SUNSET_CINEMA -> SunsetCinemaPosterLayout(props)
            LyricShareTemplateId.FOREST_MIST -> ForestMistPosterLayout(props)
            LyricShareTemplateId.ABYSSAL_TIDE -> AbyssalTidePosterLayout(props)
            LyricShareTemplateId.NEON_PULSE -> NeonPulsePosterLayout(props)
        }
    }
}

// 1. 极光流光：通透悬浮毛玻璃卡片 + 底部迷你播放器药丸栏
@Composable
private fun AuraMeshPosterLayout(props: PosterRenderProps) {
    Box(Modifier.fillMaxWidth().padding(18.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = .10f))
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .28f), Color.White.copy(alpha = .08f))), RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LyricsColumn(props, align = TextAlign.Start)
            if (props.showSongInfo) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color.Black.copy(alpha = .24f))
                        .border(.8.dp, Color.White.copy(alpha = .18f), RoundedCornerShape(99.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverThumb(props.coverBitmap, props.coverPalette, Modifier.size(34.dp), 17.dp, Color.White.copy(alpha = .22f))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(props.title, color = props.colors.ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (props.artist.isNotBlank()) {
                            Text(props.artist, color = props.colors.secondaryInk, fontSize = 11.sp, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(props.colors.accent))
                }
            }
            if (props.showBrand) BrandRow(props.colors, props.fontFamily)
        }
    }
}

// 2. 封面弥散：大尺寸居中专辑封面主视觉 + 居中叙事歌词
@Composable
private fun MistCoverPosterLayout(props: PosterRenderProps) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (props.showSongInfo) {
            Box(
                Modifier.size(116.dp).clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = .12f))
                    .border(1.dp, Color.White.copy(alpha = .28f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (props.coverBitmap != null) {
                    Image(props.coverBitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Image(MiuixIcons.Regular.Music, null, Modifier.size(36.dp), colorFilter = ColorFilter.tint(props.colors.ink))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(props.title, color = props.colors.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (props.artist.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(props.artist, color = props.colors.secondaryInk, fontSize = 12.5.sp, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.width(56.dp).height(1.dp).background(Color.White.copy(alpha = .28f)))
            Spacer(Modifier.height(18.dp))
        }
        LyricsColumn(props, align = TextAlign.Center)
        if (props.showBrand) {
            Spacer(Modifier.height(22.dp))
            Text("AURA PLAYER", color = props.colors.secondaryInk.copy(alpha = .85f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = props.fontFamily, letterSpacing = 1.6.sp)
        }
    }
}

// 3. 黑胶唱片：顶部大唱片出鞘双叠层 + 暗色内页袋歌词
@Composable
private fun VinylSleevePosterLayout(props: PosterRenderProps) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        if (props.showSongInfo) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(104.dp).height(78.dp), contentAlignment = Alignment.CenterStart) {
                    Canvas(Modifier.align(Alignment.CenterEnd).size(72.dp)) {
                        val r = size.minDimension / 2f
                        drawCircle(Color(0xFF0A0B0E), radius = r)
                        drawCircle(Color.White.copy(alpha = .10f), radius = r * .86f, style = Stroke(1f))
                        drawCircle(Color.White.copy(alpha = .08f), radius = r * .72f, style = Stroke(1f))
                        drawCircle(Color.White.copy(alpha = .07f), radius = r * .58f, style = Stroke(1f))
                        drawCircle(Color(props.coverPalette.a1.tone(62)), radius = r * .36f)
                        drawCircle(Color(0xFF17181E), radius = r * .10f)
                    }
                    CoverThumb(props.coverBitmap, props.coverPalette, Modifier.size(76.dp), 6.dp, Color.White.copy(alpha = .20f))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp))
                            .background(props.colors.accent.copy(alpha = .18f))
                            .border(.8.dp, props.colors.accent.copy(alpha = .45f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("SIDE A · LP", color = props.colors.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = props.fontFamily, letterSpacing = 1.sp)
                    }
                    Text(props.title, color = props.colors.ink, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (props.artist.isNotBlank()) {
                        Text(props.artist, color = props.colors.secondaryInk, fontSize = 12.sp, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = .26f))
                .border(.8.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(10.dp))
                .padding(18.dp),
        ) {
            LyricsColumn(props, align = TextAlign.Start)
        }
        if (props.showBrand) {
            Spacer(Modifier.height(18.dp))
            BrandRow(props.colors, props.fontFamily)
        }
    }
}

// 4. 素雅纸笺（原版保留）
@Composable
private fun EditorialPaperPosterLayout(props: PosterRenderProps) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp).heightIn(min = 280.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (props.showSongInfo) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CoverThumb(props.coverBitmap, props.coverPalette, Modifier.size(46.dp), 10.dp, props.colors.ink.copy(alpha = .12f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(props.title, color = props.colors.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (props.artist.isNotBlank()) {
                        Text(props.artist, color = props.colors.secondaryInk, fontSize = 12.sp, fontFamily = props.fontFamily, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(props.lineSpacing.gap)) {
            Text("“", color = props.colors.accent, fontSize = 34.sp, lineHeight = 32.sp, fontFamily = FontFamily.Serif)
            LyricsColumn(props, align = TextAlign.Start)
        }
        if (props.showBrand) {
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth().padding(bottom = 10.dp).height(.8.dp).background(props.colors.ink.copy(alpha = .14f)))
            BrandRow(props.colors, props.fontFamily)
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
}

// 5. 瑞士网格：包豪斯分割线结构 + 彩色竖条索引栏 + 双栏网格底栏
@Composable
private fun SwissGridPosterLayout(props: PosterRenderProps) {
    val lineColor = props.colors.ink.copy(alpha = .16f)
    Box(Modifier.fillMaxWidth().padding(14.dp).border(1.dp, lineColor)) {
        Column(Modifier.fillMaxWidth()) {
            if (props.showSongInfo) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(10.dp)) {
                        CoverThumb(props.coverBitmap, props.coverPalette, Modifier.size(54.dp), 0.dp, lineColor)
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(lineColor))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("ARCHIVE // LYRICS", color = props.colors.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = props.fontFamily, letterSpacing = 1.2.sp)
                        Text(props.title, color = props.colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (props.artist.isNotBlank()) {
                            Text(props.artist, color = props.colors.secondaryInk, fontSize = 11.5.sp, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(lineColor))
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(6.dp).fillMaxHeight().background(props.colors.accent))
                Box(Modifier.width(1.dp).fillMaxHeight().background(lineColor))
                Box(Modifier.weight(1f).padding(16.dp)) {
                    LyricsColumn(props, align = TextAlign.Start)
                }
            }
            if (props.showBrand) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(lineColor))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Text("AURA PLAYER", Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), color = props.colors.secondaryInk, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = props.fontFamily, letterSpacing = 1.4.sp)
                    Box(Modifier.width(1.dp).fillMaxHeight().background(lineColor))
                    Text("${props.lines.size.toString().padStart(2, '0')} LINES", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = props.colors.accent, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, fontFamily = props.fontFamily, letterSpacing = 1.sp)
                }
            }
        }
    }
}

// 6. 拍立得：复古白框相纸 + 留白手写感下栏
@Composable
private fun PolaroidPosterLayout(props: PosterRenderProps) {
    val colorAnimSpec = tween<Color>(durationMillis = 380, easing = FastOutSlowInEasing)
    val titleColor = animateColorAsState(if (props.paperDarkMode) Color(0xFFF2F4F8) else Color(0xFF1A1B1E), colorAnimSpec, label = "polaroidTitle").value
    val artistColor = animateColorAsState(if (props.paperDarkMode) Color(0xFF9AA2AE) else Color(0xFF636872), colorAnimSpec, label = "polaroidArtist").value
    val brandColor = animateColorAsState(if (props.paperDarkMode) Color(0xFF7D8593) else Color(0xFF8A8F99), colorAnimSpec, label = "polaroidBrand").value
    val customBlurModifier = if (props.customBackgroundBlur > 0f) Modifier.blur(props.customBackgroundBlur.dp) else Modifier
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 22.dp)) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 240.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF151821))
                .then(if (props.paperDarkMode) Modifier.border(.8.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(6.dp)) else Modifier),
        ) {
            if (props.customBackground != null) {
                Image(props.customBackground, null, Modifier.matchParentSize().then(customBlurModifier), contentScale = ContentScale.Crop)
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .34f)))
            } else {
                if (props.coverBitmap != null) {
                    Image(props.coverBitmap, null, Modifier.matchParentSize().blur(22.dp), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(Color(props.coverPalette.a1.tone(30)), Color(props.coverPalette.a2.tone(18))))))
                }
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .44f)))
            }
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                LyricsColumn(props, align = TextAlign.Start)
            }
        }
        if (props.showSongInfo || props.showBrand) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (props.showSongInfo) {
                    if (props.coverBitmap != null) {
                        Image(props.coverBitmap, null, Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(props.title.ifBlank { "Aura Player" }, color = titleColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (props.artist.isNotBlank()) {
                            Text(props.artist, color = artistColor, fontSize = 11.5.sp, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (props.showBrand) {
                    Text("AURA PLAYER", color = brandColor, fontSize = 9.5.sp, fontWeight = FontWeight.Medium, fontFamily = props.fontFamily, letterSpacing = 1.1.sp)
                }
            }
        }
    }
}

// 7. 沉浸舞台：歌词优先置顶 + 聚光地平线 + 底部圆形曲目徽章
@Composable
private fun StageSpotlightPosterLayout(props: PosterRenderProps) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Box(Modifier.width(28.dp).height(3.dp).clip(CircleShape).background(props.colors.accent))
        LyricsColumn(props, align = TextAlign.Center)
        if (props.showSongInfo || props.showBrand) {
            Box(Modifier.fillMaxWidth().height(.8.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .24f), Color.Transparent))))
        }
        if (props.showSongInfo) {
            Row(
                modifier = Modifier.clip(CircleShape)
                    .background(Color.Black.copy(alpha = .22f))
                    .border(.8.dp, Color.White.copy(alpha = .14f), CircleShape)
                    .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverThumb(props.coverBitmap, props.coverPalette, Modifier.size(30.dp), 15.dp, Color.White.copy(alpha = .25f))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (props.artist.isNotBlank()) "${props.title} · ${props.artist}" else props.title,
                    color = props.colors.ink.copy(alpha = .92f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = props.fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (props.showBrand) {
            Text("AURA PLAYER", color = props.colors.secondaryInk.copy(alpha = .75f), fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = props.fontFamily, letterSpacing = 1.5.sp)
        }
    }
}

// 8. 深夜墨韵：右上方印章 + 左侧时间竖线 + 右下角曲目落款
@Composable
private fun MidnightInkPosterLayout(props: PosterRenderProps) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (props.showBrand) {
                Text("AURA PLAYER", color = props.colors.secondaryInk.copy(alpha = .78f), fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = props.fontFamily, letterSpacing = 1.4.sp)
            } else {
                Spacer(Modifier.width(1.dp))
            }
            Box(
                Modifier.size(26.dp).border(1.2.dp, props.colors.accent.copy(alpha = .85f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("音", color = props.colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Column(Modifier.padding(top = 6.dp, end = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(7.dp).border(1.5.dp, props.colors.accent, CircleShape))
                Box(Modifier.width(1.dp).weight(1f).background(props.colors.ink.copy(alpha = .18f)))
            }
            Box(Modifier.weight(1f)) {
                LyricsColumn(props, align = TextAlign.Start)
            }
        }
        if (props.showSongInfo) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("—— ${props.title}", color = props.colors.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                    if (props.artist.isNotBlank()) {
                        Text(props.artist, color = props.colors.secondaryInk, fontSize = 11.5.sp, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                    }
                }
                Spacer(Modifier.width(10.dp))
                CoverThumb(props.coverBitmap, props.coverPalette, Modifier.size(38.dp), 8.dp, props.colors.ink.copy(alpha = .16f))
            }
        }
    }
}

// 9. 落日胶片：左右 35mm 竖向齿孔 + 上下宽银幕黑边 + 居中字幕排版
@Composable
private fun SunsetCinemaPosterLayout(props: PosterRenderProps) {
    Box(Modifier.fillMaxWidth()) {
        Canvas(Modifier.matchParentSize()) {
            val barH = 14.dp.toPx()
            drawRect(Color.Black.copy(alpha = .42f), size = Size(size.width, barH))
            drawRect(Color.Black.copy(alpha = .42f), topLeft = Offset(0f, size.height - barH), size = Size(size.width, barH))
            val holeW = 6.dp.toPx()
            val holeH = 9.dp.toPx()
            val step = 18.dp.toPx()
            var y = barH + 8.dp.toPx()
            while (y + holeH < size.height - barH) {
                drawRoundRect(Color.Black.copy(alpha = .36f), Offset(7.dp.toPx(), y), Size(holeW, holeH), CornerRadius(1.5.dp.toPx()))
                drawRoundRect(Color.Black.copy(alpha = .36f), Offset(size.width - 7.dp.toPx() - holeW, y), Size(holeW, holeH), CornerRadius(1.5.dp.toPx()))
                y += step
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (props.showBrand) {
                Text("2.39:1 // AURA PLAYER", color = props.colors.accent.copy(alpha = .85f), fontSize = 9.sp, fontWeight = FontWeight.Medium, fontFamily = props.fontFamily, letterSpacing = 1.6.sp)
            }
            LyricsColumn(props, align = TextAlign.Center)
            if (props.showSongInfo) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverThumb(props.coverBitmap, props.coverPalette, Modifier.size(24.dp), 4.dp, props.colors.accent.copy(alpha = .45f))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (props.artist.isNotBlank()) "♪ ${props.title}  —  ${props.artist}" else "♪ ${props.title}",
                        color = props.colors.secondaryInk,
                        fontSize = 11.5.sp,
                        fontFamily = props.fontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// 10. 森屿晨雾：分栏书签头带 + 右侧浮动封面徽章 + 左侧森林引线
@Composable
private fun ForestMistPosterLayout(props: PosterRenderProps) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (props.showSongInfo) {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = .08f))
                    .border(.8.dp, Color(0xFF7EC8A7).copy(alpha = .24f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(props.title, color = props.colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (props.artist.isNotBlank()) {
                            Text(props.artist, color = props.colors.secondaryInk, fontSize = 12.sp, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    CoverThumb(props.coverBitmap, props.coverPalette, Modifier.size(50.dp), 25.dp, props.colors.accent.copy(alpha = .5f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(props.colors.accent.copy(alpha = .85f)))
            Spacer(Modifier.width(14.dp))
            Box(Modifier.weight(1f)) {
                LyricsColumn(props, align = TextAlign.Start)
            }
        }
        if (props.showBrand) BrandRow(props.colors, props.fontFamily)
    }
}

// 11. 深海潮汐：大字号标题 + 右侧水波纹刻度标 + 巨幅水印背景字
@Composable
private fun AbyssalTidePosterLayout(props: PosterRenderProps) {
    Box(Modifier.fillMaxWidth().padding(24.dp)) {
        if (props.showSongInfo && props.title.isNotBlank()) {
            Text(
                text = props.title,
                color = props.colors.ink.copy(alpha = .05f),
                fontSize = 46.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.Black,
                fontFamily = props.fontFamily,
                maxLines = 2,
                overflow = TextOverflow.Clip,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp),
            )
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (props.showSongInfo) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(props.title, color = props.colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (props.artist.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(props.artist, color = props.colors.accent, fontSize = 12.sp, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(46.dp).border(1.5.dp, props.colors.accent.copy(alpha = .6f), CircleShape).padding(3.dp)) {
                        CoverThumb(props.coverBitmap, props.coverPalette, Modifier.fillMaxSize(), 20.dp, Color.Transparent)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.weight(1f)) {
                    LyricsColumn(props, align = TextAlign.Start)
                }
                Canvas(Modifier.padding(start = 10.dp).width(10.dp).fillMaxHeight()) {
                    var y = 6.dp.toPx()
                    var index = 0
                    while (y < size.height - 6.dp.toPx()) {
                        val len = if (index % 3 == 0) size.width else size.width * .5f
                        drawLine(props.colors.accent.copy(alpha = .45f), Offset(size.width - len, y), Offset(size.width, y), strokeWidth = 1.2.dp.toPx())
                        y += 10.dp.toPx()
                        index++
                    }
                }
            }
            if (props.showBrand) BrandRow(props.colors, props.fontFamily)
        }
    }
}

// 12. 霓虹声波：HUD 四角瞄准框 + 顶部均衡器声波条
@Composable
private fun NeonPulsePosterLayout(props: PosterRenderProps) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (props.showSongInfo) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CoverThumb(props.coverBitmap, props.coverPalette, Modifier.size(44.dp), 8.dp, props.colors.accent.copy(alpha = .85f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("TRK // PLAY", color = props.colors.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = props.fontFamily, letterSpacing = 1.2.sp)
                    Text(props.title, color = props.colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (props.artist.isNotBlank()) {
                        Text(props.artist, color = props.colors.secondaryInk, fontSize = 11.5.sp, fontFamily = props.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Canvas(Modifier.width(42.dp).height(24.dp)) {
                    val heights = listOf(.35f, .75f, .5f, .95f, .65f, .4f, .82f)
                    val barW = 3.dp.toPx()
                    val gap = (size.width - barW * heights.size) / (heights.size - 1)
                    heights.forEachIndexed { idx, h ->
                        val bh = size.height * h
                        drawRoundRect(
                            color = props.colors.accent.copy(alpha = if (idx % 2 == 0) .9f else .55f),
                            topLeft = Offset(idx * (barW + gap), (size.height - bh) / 2f),
                            size = Size(barW, bh),
                            cornerRadius = CornerRadius(1.5.dp.toPx()),
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(4.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val len = 14.dp.toPx()
                val stroke = 1.6.dp.toPx()
                val cyan = props.colors.accent
                drawLine(cyan, Offset(0f, 0f), Offset(len, 0f), stroke)
                drawLine(cyan, Offset(0f, 0f), Offset(0f, len), stroke)
                drawLine(cyan, Offset(size.width - len, 0f), Offset(size.width, 0f), stroke)
                drawLine(cyan, Offset(size.width, 0f), Offset(size.width, len), stroke)
                drawLine(cyan, Offset(0f, size.height), Offset(len, size.height), stroke)
                drawLine(cyan, Offset(0f, size.height - len), Offset(0f, size.height), stroke)
                drawLine(cyan, Offset(size.width - len, size.height), Offset(size.width, size.height), stroke)
                drawLine(cyan, Offset(size.width, size.height - len), Offset(size.width, size.height), stroke)
            }
            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                LyricsColumn(props, align = TextAlign.Start)
            }
        }
        if (props.showBrand) BrandRow(props.colors, props.fontFamily)
    }
}

@Composable
private fun CoverThumb(
    coverBitmap: ImageBitmap?,
    coverPalette: CorePalette,
    modifier: Modifier,
    radius: Dp,
    borderColor: Color,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.clip(shape)
            .background(Color(coverPalette.a1.tone(36)).copy(alpha = .35f))
            .then(if (borderColor != Color.Transparent) Modifier.border(.8.dp, borderColor, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (coverBitmap != null) {
            Image(coverBitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Image(MiuixIcons.Regular.Music, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White.copy(alpha = .85f)))
        }
    }
}

@Composable
private fun BrandRow(colors: PosterPalette, fontFamily: FontFamily) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            "AURA PLAYER",
            color = colors.secondaryInk.copy(alpha = .85f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = fontFamily,
            letterSpacing = 1.4.sp,
        )
        Box(Modifier.size(6.dp).clip(CircleShape).background(colors.accent.copy(alpha = .85f)))
    }
}

@Composable
private fun LyricsColumn(props: PosterRenderProps, align: TextAlign) {
    val mainSize = (17.5f * props.lyricSize.scale).sp
    val subSize = (12f * props.lyricSize.scale).sp
    val spacingScale = props.lineSpacing.lineHeightScale
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(props.lineSpacing.gap),
        horizontalAlignment = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        props.lines.forEach { line ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                horizontalAlignment = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                Text(
                    text = line.text,
                    color = props.colors.ink,
                    fontSize = mainSize,
                    lineHeight = (24.5f * props.lyricSize.scale * spacingScale).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = props.fontFamily,
                    textAlign = align,
                )
                if (props.showPronunciation && !line.roma.isNullOrBlank()) {
                    Text(
                        text = line.roma.orEmpty(),
                        color = props.colors.secondaryInk,
                        fontSize = subSize,
                        lineHeight = (16f * props.lyricSize.scale * spacingScale).sp,
                        fontFamily = props.fontFamily,
                        textAlign = align,
                    )
                }
                if (props.showTranslation && !line.translation.isNullOrBlank()) {
                    Text(
                        text = line.translation.orEmpty(),
                        color = props.colors.secondaryInk,
                        fontSize = subSize,
                        lineHeight = (17f * props.lyricSize.scale * spacingScale).sp,
                        fontFamily = props.fontFamily,
                        textAlign = align,
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterBackgroundLayer(
    id: LyricShareTemplateId,
    coverBitmap: ImageBitmap?,
    coverPalette: CorePalette,
    customBackground: ImageBitmap?,
    customBackgroundBlur: Float,
    roundedCorners: Boolean,
    paperDarkMode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colorAnimSpec = tween<Color>(durationMillis = 380, easing = FastOutSlowInEasing)
    if (customBackground != null && id != LyricShareTemplateId.POLAROID) {
        val targetScrim = when {
            (id == LyricShareTemplateId.EDITORIAL_PAPER || id == LyricShareTemplateId.SWISS_GRID) && !paperDarkMode ->
                Color.White.copy(alpha = .70f)
            (id == LyricShareTemplateId.EDITORIAL_PAPER || id == LyricShareTemplateId.SWISS_GRID) && paperDarkMode ->
                Color(0xFF100F0D).copy(alpha = .66f)
            else -> Color.Black.copy(alpha = .38f)
        }
        val animatedScrim = animateColorAsState(targetScrim, colorAnimSpec, label = "customBgScrim").value
        Box(modifier) {
            Image(
                bitmap = customBackground,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().then(if (customBackgroundBlur > 0f) Modifier.blur(customBackgroundBlur.dp) else Modifier),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(animatedScrim))
        }
        return
    }
    when (id) {
        LyricShareTemplateId.AURA_MESH -> Canvas(modifier) {
            drawRect(Color(coverPalette.n1.tone(8)))
            val orbs = listOf(
                Triple(Offset(size.width * .18f, size.height * .22f), Color(coverPalette.a1.tone(66)), .85f),
                Triple(Offset(size.width * .86f, size.height * .46f), Color(coverPalette.a2.tone(64)), .80f),
                Triple(Offset(size.width * .34f, size.height * .88f), Color(coverPalette.a3.tone(68)), .78f),
            )
            orbs.forEach { (center, color, scale) ->
                val radius = size.width * scale
                drawCircle(
                    brush = Brush.radialGradient(listOf(color.copy(alpha = .68f), Color.Transparent), center = center, radius = radius),
                    radius = radius,
                    center = center,
                )
            }
        }

        LyricShareTemplateId.MIST_COVER -> Box(modifier) {
            if (coverBitmap != null) {
                Image(coverBitmap, null, Modifier.fillMaxSize().blur(38.dp), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .28f), Color.Black.copy(alpha = .54f)))))
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(coverPalette.a2.tone(24)), Color(coverPalette.n1.tone(10))))))
            }
        }

        LyricShareTemplateId.VINYL_SLEEVE -> Box(
            modifier.background(Brush.verticalGradient(listOf(Color(0xFF15161B), Color(0xFF21232C), Color(0xFF131419))))
        )

        LyricShareTemplateId.EDITORIAL_PAPER -> {
            val paperBg = animateColorAsState(if (paperDarkMode) Color(0xFF1C1A17) else Color(0xFFF6F1E7), colorAnimSpec, label = "editorialBg").value
            val ruleInk = animateColorAsState(if (paperDarkMode) Color(0xFFF2EDE3).copy(alpha = .07f) else Color(0xFF1F1B18).copy(alpha = .06f), colorAnimSpec, label = "editorialRule").value
            Canvas(modifier) {
                drawRect(paperBg)
                val step = 28.dp.toPx()
                var y = step * 1.5f
                while (y < size.height - step) {
                    drawLine(ruleInk, Offset(20.dp.toPx(), y), Offset(size.width - 20.dp.toPx(), y), strokeWidth = 1f)
                    y += step
                }
            }
        }

        LyricShareTemplateId.SWISS_GRID -> {
            val swissBg = animateColorAsState(if (paperDarkMode) Color(0xFF141416) else Color(0xFFF4F3EF), colorAnimSpec, label = "swissBg").value
            Box(modifier.background(swissBg))
        }

        LyricShareTemplateId.POLAROID -> {
            val polaroidBg = animateColorAsState(if (paperDarkMode) Color(0xFF1A1B1F) else Color(0xFFFAF9F5), colorAnimSpec, label = "polaroidBg").value
            val polaroidBorder = animateColorAsState(if (paperDarkMode) Color.White.copy(alpha = .10f) else Color.Black.copy(alpha = .06f), colorAnimSpec, label = "polaroidBorder").value
            Box(
                modifier.background(polaroidBg)
                    .border(1.dp, polaroidBorder, RoundedCornerShape(if (roundedCorners) 12.dp else 0.dp))
            )
        }

        LyricShareTemplateId.STAGE_SPOTLIGHT -> Canvas(modifier) {
            drawRect(Brush.verticalGradient(listOf(Color(coverPalette.a1.tone(28)), Color(coverPalette.a2.tone(14)), Color(coverPalette.n1.tone(7)))))
            drawCircle(
                Brush.radialGradient(listOf(Color(coverPalette.a1.tone(68)).copy(alpha = .38f), Color.Transparent), center = Offset(size.width * .5f, 0f), radius = size.width * .95f),
                radius = size.width * .95f,
                center = Offset(size.width * .5f, 0f),
            )
        }

        LyricShareTemplateId.MIDNIGHT_INK -> Canvas(modifier) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF080A10), Color(0xFF121723))))
            val stars = listOf(.14f to .18f, .82f to .14f, .68f to .32f, .24f to .74f, .88f to .66f, .46f to .86f)
            stars.forEachIndexed { idx, (fx, fy) ->
                drawCircle(Color.White.copy(alpha = if (idx % 2 == 0) .28f else .14f), radius = (1.5f + idx % 2).dp.toPx(), center = Offset(size.width * fx, size.height * fy))
            }
        }

        LyricShareTemplateId.SUNSET_CINEMA -> Box(
            modifier.background(Brush.verticalGradient(listOf(Color(0xFF1E0D24), Color(0xFF691735), Color(0xFFC94A39))))
        )

        LyricShareTemplateId.FOREST_MIST -> Canvas(modifier) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF0E201A), Color(0xFF1A382F), Color(0xFF285042))))
            drawCircle(Color(0xFF7EC8A7).copy(alpha = .12f), radius = size.width * .52f, center = Offset(size.width * .88f, size.height * .20f), style = Stroke(1.5.dp.toPx()))
            drawCircle(Color(0xFF7EC8A7).copy(alpha = .08f), radius = size.width * .74f, center = Offset(size.width * .88f, size.height * .20f), style = Stroke(1.dp.toPx()))
        }

        LyricShareTemplateId.ABYSSAL_TIDE -> Canvas(modifier) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF061324), Color(0xFF0D314F), Color(0xFF185D6E))))
            val wave1 = Path().apply {
                moveTo(0f, size.height * .80f)
                for (i in 0..24) {
                    val x = size.width * i / 24f
                    val y = size.height * .80f + sin(i / 24f * 2f * PI).toFloat() * 14.dp.toPx()
                    lineTo(x, y)
                }
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            val wave2 = Path().apply {
                moveTo(0f, size.height * .88f)
                for (i in 0..24) {
                    val x = size.width * i / 24f
                    val y = size.height * .88f + sin(i / 24f * 2f * PI + 1.4).toFloat() * 10.dp.toPx()
                    lineTo(x, y)
                }
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(wave1, Color.White.copy(alpha = .06f))
            drawPath(wave2, Color(0xFF5CD6EE).copy(alpha = .09f))
        }

        LyricShareTemplateId.NEON_PULSE -> Canvas(modifier) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF100B22), Color(0xFF1D123D), Color(0xFF0E233A))))
            val step = 18.dp.toPx()
            var x = step
            while (x < size.width) {
                var y = step
                while (y < size.height) {
                    drawCircle(Color.White.copy(alpha = .06f), radius = 1.dp.toPx(), center = Offset(x, y))
                    y += step
                }
                x += step
            }
        }
    }
}

@Composable
internal fun ShareTemplateThumbnail(
    template: LyricShareTemplate,
    coverBitmap: ImageBitmap?,
    coverPalette: CorePalette,
    modifier: Modifier = Modifier,
) {
    val colors = posterPalette(template.id, coverPalette, paperDarkMode = false)
    Box(modifier.clip(RoundedCornerShape(14.dp)).clipToBounds()) {
        PosterBackgroundLayer(
            id = template.id,
            coverBitmap = coverBitmap,
            coverPalette = coverPalette,
            customBackground = null,
            customBackgroundBlur = 0f,
            roundedCorners = true,
            modifier = Modifier.matchParentSize(),
        )
        when (template.id) {
            LyricShareTemplateId.POLAROID -> Column(Modifier.fillMaxSize().padding(6.dp)) {
                Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1B1F29)))
                Spacer(Modifier.height(14.dp))
            }

            LyricShareTemplateId.MIST_COVER -> Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(colors.accent.copy(alpha = .75f)))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth(.82f).height(4.dp).clip(CircleShape).background(colors.ink.copy(alpha = .9f)))
                    Box(Modifier.fillMaxWidth(.58f).height(4.dp).clip(CircleShape).background(colors.ink.copy(alpha = .7f)))
                }
                Box(Modifier.width(16.dp).height(3.dp).clip(CircleShape).background(colors.secondaryInk.copy(alpha = .5f)))
            }

            LyricShareTemplateId.SWISS_GRID -> Box(Modifier.fillMaxSize().padding(6.dp).border(.8.dp, colors.ink.copy(alpha = .24f))) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(colors.accent))
                    Column(Modifier.weight(1f).padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Box(Modifier.size(12.dp).background(colors.ink.copy(alpha = .22f)))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(Modifier.fillMaxWidth(.9f).height(4.dp).background(colors.ink.copy(alpha = .85f)))
                            Box(Modifier.fillMaxWidth(.65f).height(4.dp).background(colors.ink.copy(alpha = .65f)))
                        }
                        Box(Modifier.fillMaxWidth().height(.8.dp).background(colors.ink.copy(alpha = .2f)))
                    }
                }
            }

            LyricShareTemplateId.STAGE_SPOTLIGHT -> Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.width(12.dp).height(2.dp).clip(CircleShape).background(colors.accent))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth(.88f).height(5.dp).clip(CircleShape).background(colors.ink.copy(alpha = .9f)))
                    Box(Modifier.fillMaxWidth(.64f).height(5.dp).clip(CircleShape).background(colors.ink.copy(alpha = .72f)))
                }
                Box(Modifier.fillMaxWidth(.72f).height(10.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)))
            }

            LyricShareTemplateId.MIDNIGHT_INK -> Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.align(Alignment.End).size(10.dp).border(1.dp, colors.accent, RoundedCornerShape(2.dp)))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(1.dp).height(26.dp).background(colors.ink.copy(alpha = .3f)))
                    Spacer(Modifier.width(5.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.width(34.dp).height(4.dp).clip(CircleShape).background(colors.ink.copy(alpha = .85f)))
                        Box(Modifier.width(24.dp).height(4.dp).clip(CircleShape).background(colors.ink.copy(alpha = .65f)))
                    }
                }
                Box(Modifier.align(Alignment.End).width(22.dp).height(3.dp).clip(CircleShape).background(colors.secondaryInk.copy(alpha = .6f)))
            }

            else -> Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(colors.accent.copy(alpha = .75f)))
                    Box(Modifier.width(24.dp).height(4.dp).clip(CircleShape).background(colors.secondaryInk.copy(alpha = .6f)))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth(.88f).height(5.dp).clip(CircleShape).background(colors.ink.copy(alpha = .9f)))
                    Box(Modifier.fillMaxWidth(.64f).height(5.dp).clip(CircleShape).background(colors.ink.copy(alpha = .72f)))
                    Box(Modifier.fillMaxWidth(.45f).height(3.5.dp).clip(CircleShape).background(colors.secondaryInk.copy(alpha = .5f)))
                }
                Box(Modifier.width(18.dp).height(3.dp).clip(CircleShape).background(colors.accent.copy(alpha = .85f)))
            }
        }
    }
}
