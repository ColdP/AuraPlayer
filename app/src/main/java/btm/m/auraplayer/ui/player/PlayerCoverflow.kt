// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.R
import btm.m.auraplayer.auramix.AuraMixVisualTransition
import btm.m.auraplayer.data.Song
import btm.m.auraplayer.lyrics.scaleSpatialParallaxTilt
import btm.m.auraplayer.playback.CoverAnimation
import btm.m.auraplayer.playback.CoverTheme
import btm.m.auraplayer.playback.GlowProgressStyle
import btm.m.auraplayer.playback.NowPlayingConfig
import btm.m.auraplayer.spatialcover.SpatialCoverResolveState
import btm.m.auraplayer.ui.LocalAdaptIrregularCovers
import btm.m.auraplayer.ui.LocalAdvancedMaterialEnabled
import btm.m.auraplayer.ui.fitCoverAspectRatio
import btm.m.auraplayer.ui.rememberCoverAspectRatio
import btm.m.auraplayer.ui.tr
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.materialkolor.palettes.CorePalette
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LocalRuntimeShaderCache
import top.yukonga.miuix.kmp.blur.highlight.Highlight as MiuixHighlight
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt

internal fun resolveActiveCoverTheme(
    coverTheme: CoverTheme,
    isLandscape: Boolean,
    landscapeAutoCoverflow: Boolean,
): CoverTheme = when {
    coverTheme != CoverTheme.SQUARE -> coverTheme
    isLandscape && landscapeAutoCoverflow -> CoverTheme.COVERFLOW
    else -> CoverTheme.SQUARE
}

internal fun shouldUseLandscapeCoverflow(
    isLandscape: Boolean,
    landscapeAutoCoverflow: Boolean,
): Boolean = resolveActiveCoverTheme(
    coverTheme = CoverTheme.SQUARE,
    isLandscape = isLandscape,
    landscapeAutoCoverflow = landscapeAutoCoverflow,
) == CoverTheme.COVERFLOW

internal data class CoverflowMetrics(
    val isLandscape: Boolean,
    val cardSize: Dp,
    val pageSpacing: Dp,
    val reflectionHeight: Dp,
    val dockHorizontalMargin: Dp,
    val dockBottomPadding: Dp,
    val dockCornerRadius: Dp,
    val carouselTopPadding: Dp,
    val dockCompact: Boolean,
    val includeVolumeInDock: Boolean,
)

internal fun computeCoverflowMetrics(
    width: Dp,
    height: Dp,
    leftInset: Dp,
    topInset: Dp,
    rightInset: Dp,
    bottomInset: Dp,
    showVolume: Boolean = false,
): CoverflowMetrics {
    val landscape = isLandscapePlayerLayout(width, height)
    val availableWidth = (width - leftInset - rightInset).coerceAtLeast(300.dp)
    val availableHeight = (height - topInset - bottomInset).coerceAtLeast(240.dp)
    if (!landscape) {
        val dockCompact = availableHeight < 700.dp
        val includeVolumeInDock = showVolume && availableHeight >= 580.dp
        val dockBottomPadding = if (availableHeight < 660.dp) 12.dp else 18.dp
        // Reserve space matching Portrait SQUARE's Title + Seek + Transport + Volume + BottomActions layout.
        val dockReserve = (if (includeVolumeInDock) 318.dp else 274.dp) + dockBottomPadding
        val topHeaderReserve = if (availableHeight < 660.dp) 48.dp else 62.dp
        val carouselBudget = (availableHeight - topHeaderReserve - dockReserve).coerceAtLeast(180.dp)
        val reflectionRatio = 0.22f
        val maxCardFromHeight = (carouselBudget - 10.dp).coerceAtLeast(140.dp) / (1f + reflectionRatio)
        val maxCardFromWidth = availableWidth * 0.64f
        val cardSize = minOf(maxCardFromHeight, maxCardFromWidth).coerceIn(170.dp, 340.dp)
        val reflectionHeight = (cardSize * reflectionRatio).coerceIn(30.dp, 74.dp)
        val totalCarouselHeight = cardSize + 8.dp + reflectionHeight
        val extraVertical = (carouselBudget - totalCarouselHeight).coerceAtLeast(0.dp)
        val carouselTopPadding = topHeaderReserve + extraVertical * 0.44f
        val pageSpacing = -(cardSize * 0.24f)
        val dockHorizontalMargin = maxOf(16.dp, (availableWidth - 540.dp) / 2f)
        return CoverflowMetrics(
            isLandscape = false,
            cardSize = cardSize,
            pageSpacing = pageSpacing,
            reflectionHeight = reflectionHeight,
            dockHorizontalMargin = dockHorizontalMargin,
            dockBottomPadding = dockBottomPadding,
            dockCornerRadius = 28.dp,
            carouselTopPadding = carouselTopPadding,
            dockCompact = dockCompact,
            includeVolumeInDock = includeVolumeInDock,
        )
    }

    val dockCompact = availableHeight < 430.dp
    val dockBottomPadding = if (dockCompact) 10.dp else 18.dp
    val dockReserve = (if (dockCompact) 110.dp else 136.dp) + dockBottomPadding
    val topHeaderReserve = if (dockCompact) 36.dp else 52.dp
    val carouselBudget = (availableHeight - topHeaderReserve - dockReserve).coerceAtLeast(136.dp)
    val reflectionRatio = if (dockCompact) 0.18f else 0.24f
    val maxCardFromHeight = (carouselBudget - 8.dp).coerceAtLeast(110.dp) / (1f + reflectionRatio)
    val widthRatio = if (width > 920.dp) 0.34f else 0.38f
    val maxCardFromWidth = availableWidth * widthRatio
    val cardSize = minOf(maxCardFromHeight, maxCardFromWidth).coerceIn(116.dp, 340.dp)
    val reflectionHeight = (cardSize * reflectionRatio).coerceIn(20.dp, 78.dp)
    val totalCarouselHeight = cardSize + 8.dp + reflectionHeight
    val extraVertical = (carouselBudget - totalCarouselHeight).coerceAtLeast(0.dp)
    val carouselTopPadding = topHeaderReserve + extraVertical * 0.42f
    val pageSpacing = -(cardSize * 0.22f)
    val dockHorizontalMargin = maxOf(16.dp, (availableWidth - 860.dp) / 2f)
    val dockCornerRadius = if (dockCompact) 26.dp else 30.dp
    return CoverflowMetrics(
        isLandscape = true,
        cardSize = cardSize,
        pageSpacing = pageSpacing,
        reflectionHeight = reflectionHeight,
        dockHorizontalMargin = dockHorizontalMargin,
        dockBottomPadding = dockBottomPadding,
        dockCornerRadius = dockCornerRadius,
        carouselTopPadding = carouselTopPadding,
        dockCompact = dockCompact,
        includeVolumeInDock = false,
    )
}

internal data class CoverflowCardTransform(
    val rotationY: Float,
    val scale: Float,
    val translationXFactor: Float,
    val blurRadiusDp: Float,
    val dimAlpha: Float,
    val contentAlpha: Float,
    val zIndex: Float,
)

internal fun computeCoverflowCardTransform(pageOffset: Float): CoverflowCardTransform {
    val absOffset = abs(pageOffset).coerceIn(0f, 4f)
    val sign = if (pageOffset >= 0f) 1f else -1f
    val angle = (atan(absOffset * 2.1f) / (PI.toFloat() / 2f)) * 64f
    val rotationY = if (absOffset < 0.001f) 0f else -sign * angle
    val scale = (1f - 0.16f * minOf(absOffset, 1f) - 0.045f * (absOffset - 1f).coerceAtLeast(0f))
        .coerceIn(0.66f, 1f)
    val translationXFactor = -sign * ((absOffset - 0.35f).coerceAtLeast(0f) * 0.055f).coerceAtMost(0.18f)
    val rawBlur = (absOffset - 0.14f).coerceAtLeast(0f) * 3.8f
    val blurRadiusDp = if (rawBlur < 0.35f) 0f else rawBlur.coerceAtMost(14f)
    val dimAlpha = ((absOffset - 0.12f).coerceAtLeast(0f) * 0.20f).coerceIn(0f, 0.44f)
    val contentAlpha = (1f - (absOffset - 1.6f).coerceAtLeast(0f) * 0.26f).coerceIn(0.32f, 1f)
    val zIndex = 10f - absOffset
    return CoverflowCardTransform(
        rotationY = rotationY,
        scale = scale,
        translationXFactor = translationXFactor,
        blurRadiusDp = blurRadiusDp,
        dimAlpha = dimAlpha,
        contentAlpha = contentAlpha,
        zIndex = zIndex,
    )
}

@Composable
internal fun Modifier.playerDockGlassSurface(
    advancedMaterial: Boolean,
    backdrop: Backdrop,
    miuixBackdrop: top.yukonga.miuix.kmp.blur.Backdrop,
    shape: Shape,
    cornerRadius: Dp,
    menuTint: Color,
    activeInk: Color,
    expressive: PlayerExpressiveColors?,
    oneUi: PlayerOneUiColors?,
    lensWidth: Dp = 24.dp,
    lensEdge: Dp = 38.dp,
    blurRadius: Dp = 10.dp,
): Modifier {
    val isLightDock = menuTint.luminance() >= 0.5f
    val dockSurfaceTint = when {
        oneUi != null -> oneUi.card.copy(alpha = 0.68f)
        expressive != null -> expressive.surfaceContainer.copy(alpha = 0.68f)
        else -> menuTint.copy(alpha = if (isLightDock) 0.52f else 0.26f)
    }
    return if (advancedMaterial) {
        this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                backdropBlur(blurRadius.toPx())
                lens(lensWidth.toPx(), lensEdge.toPx(), chromaticAberration = true)
            },
            highlight = { Highlight.Default.copy(alpha = 0.58f) },
            onDrawSurface = { drawRect(dockSurfaceTint) },
        )
    } else {
        val solidTint = oneUi?.card ?: expressive?.surfaceContainer ?: menuTint
        this
            .playerMenuMaterial(miuixBackdrop, solidTint, cornerRadius)
            .border(0.75.dp, activeInk.copy(alpha = 0.12f), shape)
    }
}

internal val CoverThemeHeaderTouchHeight = 46.dp
internal val CoverThemeHeaderVisualSize = CoverThemeHeaderTouchHeight * 0.85f * 0.92f
internal val CoverThemeSpatialCubeGap = 4.dp
internal val CoverThemeSpatialCubeReserve = CoverThemeHeaderTouchHeight + CoverThemeSpatialCubeGap

internal fun coverThemeHeaderTopPadding(compactOrLandscape: Boolean): Dp = if (compactOrLandscape) 4.dp else 6.dp

@Composable
internal fun PlayerQueueCounterBadge(
    currentOneBased: Int,
    totalCount: Int,
    miuixBackdrop: top.yukonga.miuix.kmp.blur.Backdrop,
    menuTint: Color,
    expressive: PlayerExpressiveColors?,
    oneUi: PlayerOneUiColors?,
    modifier: Modifier = Modifier,
) {
    if (totalCount <= 1) return
    val pillShape = RoundedCornerShape(50)
    val badgeModifier = when {
        expressive != null -> Modifier
            .clip(pillShape)
            .background(expressive.secondaryContainer)
        oneUi != null -> Modifier
            .clip(pillShape)
            .background(oneUi.cardAlt)
            .border(0.75.dp, oneUi.divider, pillShape)
        else -> Modifier.playerMenuMaterial(miuixBackdrop, menuTint, 50.dp)
    }
    val textColor = when {
        expressive != null -> expressive.onSecondaryContainer
        oneUi != null -> oneUi.text
        else -> if (menuTint.luminance() >= 0.5f) Color(0xFF1A1A1E) else Color.White
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = badgeModifier
                .height(CoverThemeHeaderVisualSize)
                .padding(horizontal = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$currentOneBased / $totalCount",
                color = textColor,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                style = androidx.compose.ui.text.TextStyle(
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                    fontFeatureSettings = "tnum",
                ),
            )
        }
    }
}

@Composable
internal fun PlayerTrackMetadataBlock(
    song: Song,
    mixVisual: AuraMixVisualTransition?,
    activeInk: Color,
    secondaryInk: Color,
    titleSizeSp: Float,
    artistSizeSp: Float,
    interactive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mixProgress = mixVisual?.progress?.coerceIn(0f, 1f)
    val fromAlpha = mixProgress?.let { (1f - it).coerceIn(0f, 1f) } ?: 1f
    val toAlpha = mixProgress?.coerceIn(0f, 1f) ?: 0f
    Box(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = interactive,
            onClick = onClick,
        ),
    ) {
        Column(modifier = Modifier.graphicsLayer { alpha = fromAlpha }) {
            Text(
                text = mixVisual?.fromTitle ?: song.title,
                color = activeInk,
                fontSize = titleSizeSp.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = mixVisual?.fromArtist ?: song.artist,
                color = secondaryInk,
                fontSize = artistSizeSp.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (mixVisual != null) {
            Column(modifier = Modifier.graphicsLayer { alpha = toAlpha }) {
                Text(
                    text = mixVisual.toTitle,
                    color = activeInk,
                    fontSize = titleSizeSp.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = mixVisual.toArtist,
                    color = secondaryInk,
                    fontSize = artistSizeSp.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun PlayerTransportRow(
    model: AuraViewModel,
    expressive: PlayerExpressiveColors?,
    oneUi: PlayerOneUiColors?,
    activeInk: Color,
    scale: Float,
    spacing: Dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
    ) {
        when {
            expressive != null -> {
                MaterialExpressiveTransportButton(
                    icon = R.drawable.ic_player_previous,
                    label = tr("previous_track", "上一首"),
                    containerColor = expressive.secondaryContainer,
                    contentColor = expressive.onSecondaryContainer,
                    width = 50.dp * scale,
                    height = 50.dp * scale,
                    iconSize = 30.dp * scale * PlayerIconScale,
                    onClick = model::previous,
                )
                MaterialExpressivePlayPauseButton(
                    playing = model.playbackRequested,
                    label = if (model.playbackRequested) tr("pause", "暂停") else tr("play", "播放"),
                    colors = expressive,
                    touchHeight = 58.dp * scale,
                    pausedWidth = 66.dp * scale,
                    playingWidth = 82.dp * scale,
                    iconSize = 36.dp * scale * PlayerIconScale,
                    onClick = model::togglePlayback,
                )
                MaterialExpressiveTransportButton(
                    icon = R.drawable.ic_player_next,
                    label = tr("next_track", "下一首"),
                    containerColor = expressive.secondaryContainer,
                    contentColor = expressive.onSecondaryContainer,
                    width = 50.dp * scale,
                    height = 50.dp * scale,
                    iconSize = 30.dp * scale * PlayerIconScale,
                    onClick = model::next,
                )
            }
            oneUi != null -> {
                OneUiPlayerTransportButton(
                    icon = R.drawable.ic_player_previous,
                    label = tr("previous_track", "上一首"),
                    colors = oneUi,
                    touchSize = 50.dp * scale,
                    iconSize = 30.dp * scale * PlayerIconScale,
                    onClick = model::previous,
                )
                OneUiPlayerPlayPauseButton(
                    playing = model.playbackRequested,
                    label = if (model.playbackRequested) tr("pause", "暂停") else tr("play", "播放"),
                    colors = oneUi,
                    touchSize = 60.dp * scale,
                    iconSize = 38.dp * scale * PlayerIconScale,
                    onClick = model::togglePlayback,
                )
                OneUiPlayerTransportButton(
                    icon = R.drawable.ic_player_next,
                    label = tr("next_track", "下一首"),
                    colors = oneUi,
                    touchSize = 50.dp * scale,
                    iconSize = 30.dp * scale * PlayerIconScale,
                    onClick = model::next,
                )
            }
            else -> {
                PlayerButton(
                    icon = R.drawable.ic_player_previous,
                    label = tr("previous_track", "上一首"),
                    color = activeInk,
                    iconSize = 38.dp * scale,
                    touchSize = 52.dp * scale,
                    onClick = model::previous,
                )
                PlayerButton(
                    icon = if (model.playbackRequested) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                    label = if (model.playbackRequested) tr("pause", "暂停") else tr("play", "播放"),
                    color = activeInk,
                    iconSize = 48.dp * scale,
                    touchSize = 62.dp * scale,
                    onClick = model::togglePlayback,
                )
                PlayerButton(
                    icon = R.drawable.ic_player_next,
                    label = tr("next_track", "下一首"),
                    color = activeInk,
                    iconSize = 38.dp * scale,
                    touchSize = 52.dp * scale,
                    onClick = model::next,
                )
            }
        }
    }
}

@Composable
internal fun PlayerActionRow(
    model: AuraViewModel,
    song: Song,
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    expressive: PlayerExpressiveColors?,
    oneUi: PlayerOneUiColors?,
    activeInk: Color,
    bottomTouch: Dp,
    bottomIcon: Dp,
    horizontalArrangement: Arrangement.Horizontal,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modeIcon = when (model.playbackMode) {
        1 -> R.drawable.ic_player_shuffle
        2 -> R.drawable.ic_player_repeat_one
        btm.m.auraplayer.HEARTBEAT_PLAYBACK_MODE -> R.drawable.ic_player_heartbeat
        btm.m.auraplayer.ROAMING_PLAYBACK_MODE -> R.drawable.ic_player_roaming
        else -> R.drawable.ic_player_repeat
    }
    val modeDescription = when (model.playbackMode) {
        1 -> tr("shuffle_mode", "随机播放")
        2 -> tr("repeat_one_mode", "单曲循环")
        btm.m.auraplayer.HEARTBEAT_PLAYBACK_MODE -> tr("heartbeat_mode", "心动模式")
        btm.m.auraplayer.ROAMING_PLAYBACK_MODE -> tr("roaming_mode", "漫游模式")
        else -> tr("repeat_all_mode", "列表循环")
    }
    @Suppress("UNUSED_VARIABLE")
    val revision = model.history.revision
    val favorite = model.isFavorite(song)

    Row(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            expressive != null -> {
                Box(Modifier.semantics { selected = lyricsOpen }) {
                    MaterialExpressiveBottomButton(
                        icon = if (lyricsOpen) R.drawable.ic_player_lyrics_on else R.drawable.ic_player_lyrics,
                        label = tr("lyrics", "歌词"),
                        selected = lyricsOpen,
                        colors = expressive,
                        touchSize = bottomTouch,
                        iconSize = bottomIcon * PlayerIconScale,
                        onClick = onLyrics,
                    )
                }
                MaterialExpressiveBottomButton(
                    icon = modeIcon,
                    label = modeDescription,
                    selected = false,
                    colors = expressive,
                    touchSize = bottomTouch,
                    iconSize = bottomIcon * PlayerIconScale,
                    onClick = model::cyclePlaybackMode,
                )
                MaterialExpressiveBottomButton(
                    icon = if (favorite) R.drawable.ic_player_favorite_on else R.drawable.ic_player_favorite,
                    label = if (favorite) tr("unfavorite", "取消收藏") else tr("favorite", "收藏"),
                    selected = favorite,
                    colors = expressive,
                    touchSize = bottomTouch,
                    iconSize = bottomIcon * PlayerIconScale,
                    useTertiary = true,
                    onClick = { model.toggleFavorite(song) },
                )
                Box(Modifier.semantics { selected = queueOpen }) {
                    MaterialExpressiveBottomButton(
                        icon = if (queueOpen) R.drawable.ic_player_queue_on else R.drawable.ic_player_queue,
                        label = tr("play_queue", "播放队列"),
                        selected = queueOpen,
                        colors = expressive,
                        touchSize = bottomTouch,
                        iconSize = bottomIcon * PlayerIconScale,
                        onClick = onQueue,
                    )
                }
            }
            oneUi != null -> {
                Box(Modifier.semantics { selected = lyricsOpen }) {
                    OneUiPlayerBottomButton(
                        icon = if (lyricsOpen) R.drawable.ic_player_lyrics_on else R.drawable.ic_player_lyrics,
                        label = tr("lyrics", "歌词"),
                        selected = lyricsOpen,
                        colors = oneUi,
                        touchSize = bottomTouch,
                        iconSize = bottomIcon * PlayerIconScale,
                        onClick = onLyrics,
                    )
                }
                OneUiPlayerBottomButton(
                    icon = modeIcon,
                    label = modeDescription,
                    selected = false,
                    colors = oneUi,
                    touchSize = bottomTouch,
                    iconSize = bottomIcon * PlayerIconScale,
                    onClick = model::cyclePlaybackMode,
                )
                OneUiPlayerBottomButton(
                    icon = if (favorite) R.drawable.ic_player_favorite_on else R.drawable.ic_player_favorite,
                    label = if (favorite) tr("unfavorite", "取消收藏") else tr("favorite", "收藏"),
                    selected = favorite,
                    colors = oneUi,
                    touchSize = bottomTouch,
                    iconSize = bottomIcon * PlayerIconScale,
                    onClick = { model.toggleFavorite(song) },
                )
                Box(Modifier.semantics { selected = queueOpen }) {
                    OneUiPlayerBottomButton(
                        icon = if (queueOpen) R.drawable.ic_player_queue_on else R.drawable.ic_player_queue,
                        label = tr("play_queue", "播放队列"),
                        selected = queueOpen,
                        colors = oneUi,
                        touchSize = bottomTouch,
                        iconSize = bottomIcon * PlayerIconScale,
                        onClick = onQueue,
                    )
                }
            }
            else -> {
                Box(Modifier.size(bottomTouch).semantics { selected = lyricsOpen }) {
                    PlayerButton(
                        icon = if (lyricsOpen) R.drawable.ic_player_lyrics_on else R.drawable.ic_player_lyrics,
                        label = tr("lyrics", "歌词"),
                        color = if (lyricsOpen) activeInk else activeInk.copy(alpha = 0.86f),
                        iconSize = bottomIcon,
                        touchSize = bottomTouch,
                        onClick = onLyrics,
                    )
                }
                PlayerButton(
                    icon = modeIcon,
                    label = modeDescription,
                    color = activeInk.copy(alpha = 0.86f),
                    iconSize = bottomIcon,
                    touchSize = bottomTouch,
                    onClick = model::cyclePlaybackMode,
                )
                val favTint = if (favorite) Color(0xFFFF4F6F) else activeInk.copy(alpha = 0.86f)
                PlayerButton(
                    icon = if (favorite) R.drawable.ic_player_favorite_on else R.drawable.ic_player_favorite,
                    label = if (favorite) tr("unfavorite", "取消收藏") else tr("favorite", "收藏"),
                    color = favTint,
                    iconSize = bottomIcon,
                    touchSize = bottomTouch,
                    onClick = { model.toggleFavorite(song) },
                )
                Box(Modifier.size(bottomTouch).semantics { selected = queueOpen }) {
                    PlayerButton(
                        icon = if (queueOpen) R.drawable.ic_player_queue_on else R.drawable.ic_player_queue,
                        label = tr("play_queue", "播放队列"),
                        color = if (queueOpen) activeInk else activeInk.copy(alpha = 0.86f),
                        iconSize = bottomIcon,
                        touchSize = bottomTouch,
                        onClick = onQueue,
                    )
                }
            }
        }
    }
}

internal data class CoverThemeDockLayout(
    val horizontalPad: Dp,
    val verticalPad: Dp,
    val itemGap: Dp,
    val sliderHeight: Dp,
    val footerHeight: Dp,
    val transportScale: Float,
    val transportSpacing: Dp,
    val transportWidth: Dp,
    val transportHeight: Dp,
    val volumeHeight: Dp,
    val actionHeight: Dp,
    val bottomTouch: Dp,
    val bottomIcon: Dp,
    val titleSizeSp: Float,
    val artistSizeSp: Float,
    val landscapeMetaRowGap: Dp,
    val landscapeRow2TopPad: Dp,
)

internal fun resolveCoverThemeDockLayout(
    isLandscape: Boolean,
    dockCompact: Boolean,
    showCardContainer: Boolean,
    glowProgress: GlowProgressStyle,
    hasExpressive: Boolean,
    hasOneUi: Boolean,
    playing: Boolean,
): CoverThemeDockLayout {
    val sliderHeight = when (glowProgress) {
        GlowProgressStyle.ONE -> if (dockCompact) 26.dp else 30.dp
        GlowProgressStyle.HYPER -> if (dockCompact) 22.dp else 26.dp
        GlowProgressStyle.OFF -> if (dockCompact) 20.dp else 22.dp
    }
    val footerHeight = if (dockCompact) 24.dp else 26.dp

    if (!isLandscape) {
        val tScale = if (dockCompact) 0.86f else 0.94f
        val tSpacing = 12.dp
        val tWidth = when {
            hasExpressive -> (if (playing) 182.dp else 166.dp) * tScale + tSpacing * 2
            hasOneUi -> 160.dp * tScale + tSpacing * 2
            else -> 166.dp * tScale + tSpacing * 2
        }
        val actionH = if (dockCompact) 42.dp else 46.dp
        return CoverThemeDockLayout(
            horizontalPad = when {
                !showCardContainer -> if (dockCompact) 6.dp else 8.dp
                dockCompact -> 18.dp
                else -> 22.dp
            },
            verticalPad = when {
                !showCardContainer -> if (dockCompact) 4.dp else 8.dp
                dockCompact -> 12.dp
                else -> 16.dp
            },
            itemGap = if (dockCompact) 4.dp else 6.dp,
            sliderHeight = sliderHeight,
            footerHeight = footerHeight,
            transportScale = tScale,
            transportSpacing = tSpacing,
            transportWidth = tWidth,
            transportHeight = if (dockCompact) 54.dp else 60.dp,
            volumeHeight = if (dockCompact) 32.dp else 36.dp,
            actionHeight = actionH,
            bottomTouch = actionH,
            bottomIcon = if (dockCompact) 22.dp else 24.dp,
            titleSizeSp = if (dockCompact) 17.5f else 19f,
            artistSizeSp = if (dockCompact) 13.5f else 14.5f,
            landscapeMetaRowGap = 12.dp,
            landscapeRow2TopPad = 0.dp,
        )
    }

    val tScale = if (dockCompact) 0.78f else 0.88f
    val tSpacing = if (dockCompact) 6.dp else 10.dp
    val tWidth = when {
        hasExpressive -> (if (playing) 182.dp else 166.dp) * tScale + tSpacing * 2
        hasOneUi -> 160.dp * tScale + tSpacing * 2
        else -> 166.dp * tScale + tSpacing * 2
    }
    val tHeight = when {
        hasExpressive -> 58.dp * tScale
        hasOneUi -> 60.dp * tScale
        else -> 62.dp * tScale
    }
    val bottomTouch = if (dockCompact) 38.dp else 44.dp
    return CoverThemeDockLayout(
        horizontalPad = when {
            !showCardContainer -> if (dockCompact) 6.dp else 8.dp
            dockCompact -> 18.dp
            else -> 24.dp
        },
        verticalPad = when {
            !showCardContainer -> if (dockCompact) 4.dp else 6.dp
            dockCompact -> 9.dp
            else -> 13.dp
        },
        itemGap = if (dockCompact) 4.dp else 8.dp,
        sliderHeight = sliderHeight,
        footerHeight = footerHeight,
        transportScale = tScale,
        transportSpacing = tSpacing,
        transportWidth = tWidth,
        transportHeight = tHeight,
        volumeHeight = 0.dp,
        actionHeight = bottomTouch,
        bottomTouch = bottomTouch,
        bottomIcon = if (dockCompact) 21.dp else 23.dp,
        titleSizeSp = if (dockCompact) 15f else 17f,
        artistSizeSp = if (dockCompact) 12f else 13.5f,
        landscapeMetaRowGap = 12.dp,
        landscapeRow2TopPad = if (dockCompact) 4.dp else 8.dp,
    )
}

@Immutable
internal data class CoverThemeStageParams(
    val model: AuraViewModel,
    val song: Song,
    val appearance: NowPlayingConfig,
    val coverPalette: CorePalette,
    val expressive: PlayerExpressiveColors?,
    val oneUi: PlayerOneUiColors?,
    val backdrop: Backdrop,
    val miuixBackdrop: top.yukonga.miuix.kmp.blur.Backdrop,
    val menuTint: Color,
    val activeInk: Color,
    val secondaryInk: Color,
    val mixVisual: AuraMixVisualTransition?,
    val spatialCoverState: SpatialCoverResolveState,
    val spatialCoverRendering: Boolean,
    val spatialTilt: State<Offset>,
    val motionVideoPath: String?,
    val motionVideoAllowed: Boolean,
    val active: Boolean,
    val interactive: Boolean,
    val lyricsOpen: Boolean,
    val queueOpen: Boolean,
    val topInset: Dp,
    val leftInset: Dp,
    val rightInset: Dp,
    val bottomInset: Dp,
    val headerTopPadding: Dp,
    val reserveSpatialCubeSlot: Boolean,
    val maxWidth: Dp,
    val maxHeight: Dp,
    val heroContentAlpha: State<Float>,
    val stageVisible: Boolean = true,
    val stageSettled: Boolean = true,
    val onLyrics: () -> Unit,
    val onQueue: () -> Unit,
    val onQualityMenuExpanded: (Boolean) -> Unit,
    val onDragActiveChange: (Boolean) -> Unit = {},
)

@Composable
internal fun PlayerCoverThemeControlDock(
    params: CoverThemeStageParams,
    isLandscape: Boolean,
    dockCompact: Boolean,
    includeVolume: Boolean,
    dockCornerRadius: Dp,
    showCardContainer: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val model = params.model
    val song = params.song
    val appearance = params.appearance
    val coverPalette = params.coverPalette
    val expressive = params.expressive
    val oneUi = params.oneUi
    val backdrop = params.backdrop
    val miuixBackdrop = params.miuixBackdrop
    val menuTint = params.menuTint
    val activeInk = params.activeInk
    val secondaryInk = params.secondaryInk
    val mixVisual = params.mixVisual
    val active = params.active
    val interactive = params.interactive
    val lyricsOpen = params.lyricsOpen
    val queueOpen = params.queueOpen
    val onLyrics = params.onLyrics
    val onQueue = params.onQueue
    val onQualityMenuExpanded = params.onQualityMenuExpanded
    val advancedMaterial = LocalAdvancedMaterialEnabled.current
    val dockShape = remember(dockCornerRadius) { RoundedCornerShape(dockCornerRadius) }
    val dockMaterial = if (showCardContainer) {
        Modifier
            .playerDockGlassSurface(
                advancedMaterial = advancedMaterial,
                backdrop = backdrop,
                miuixBackdrop = miuixBackdrop,
                shape = dockShape,
                cornerRadius = dockCornerRadius,
                menuTint = menuTint,
                activeInk = activeInk,
                expressive = expressive,
                oneUi = oneUi,
            )
            .clip(dockShape)
    } else {
        Modifier
    }

    val layout = resolveCoverThemeDockLayout(
        isLandscape = isLandscape,
        dockCompact = dockCompact,
        showCardContainer = showCardContainer,
        glowProgress = appearance.glowProgress,
        hasExpressive = expressive != null,
        hasOneUi = oneUi != null,
        playing = model.playbackRequested,
    )

    if (!isLandscape) {
        Column(
            modifier = modifier
                .then(dockMaterial)
                .padding(horizontal = layout.horizontalPad, vertical = layout.verticalPad),
            verticalArrangement = Arrangement.spacedBy(layout.itemGap),
        ) {
            PlayerTrackMetadataBlock(
                song = song,
                mixVisual = mixVisual,
                activeInk = activeInk,
                secondaryInk = secondaryInk,
                titleSizeSp = layout.titleSizeSp,
                artistSizeSp = layout.artistSizeSp,
                interactive = interactive,
                onClick = onLyrics,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = if (params.reserveSpatialCubeSlot) CoverThemeSpatialCubeReserve else 0.dp)
                    .graphicsLayer { alpha = params.heroContentAlpha.value },
            )
            PlayerProgressSection(
                model = model,
                song = song,
                appearance = appearance,
                coverPalette = coverPalette,
                expressive = expressive,
                oneUi = oneUi,
                backdrop = backdrop,
                active = active && interactive,
                queueOpen = queueOpen,
                controlsVisibility = 1f,
                sliderHeight = layout.sliderHeight,
                footerHeight = layout.footerHeight,
                horizontalInset = 0.dp,
                onQualityMenuExpanded = onQualityMenuExpanded,
                modifier = Modifier.fillMaxWidth(),
            )
            PlayerTransportRow(
                model = model,
                expressive = expressive,
                oneUi = oneUi,
                activeInk = activeInk,
                scale = layout.transportScale,
                spacing = layout.transportSpacing,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(layout.transportHeight),
            )
            if (includeVolume) {
                PlayerVolumeSection(
                    expressive = expressive,
                    oneUi = oneUi,
                    height = layout.volumeHeight,
                )
            }
            PlayerActionRow(
                model = model,
                song = song,
                lyricsOpen = lyricsOpen,
                queueOpen = queueOpen,
                expressive = expressive,
                oneUi = oneUi,
                activeInk = activeInk,
                bottomTouch = layout.bottomTouch,
                bottomIcon = layout.bottomIcon,
                horizontalArrangement = Arrangement.SpaceAround,
                onLyrics = onLyrics,
                onQueue = onQueue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(layout.actionHeight),
            )
        }
        return
    }

    val actionGap = if (dockCompact) 2.dp else 4.dp

    Column(
        modifier = modifier
            .then(dockMaterial)
            .padding(horizontal = layout.horizontalPad, vertical = layout.verticalPad),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (dockCompact) 12.dp else 16.dp),
        ) {
            PlayerProgressSection(
                model = model,
                song = song,
                appearance = appearance,
                coverPalette = coverPalette,
                expressive = expressive,
                oneUi = oneUi,
                backdrop = backdrop,
                active = active && interactive,
                queueOpen = queueOpen,
                controlsVisibility = 1f,
                sliderHeight = layout.sliderHeight,
                footerHeight = layout.footerHeight,
                horizontalInset = 0.dp,
                onQualityMenuExpanded = onQualityMenuExpanded,
                modifier = Modifier.weight(1f),
            )
            PlayerActionRow(
                model = model,
                song = song,
                lyricsOpen = lyricsOpen,
                queueOpen = queueOpen,
                expressive = expressive,
                oneUi = oneUi,
                activeInk = activeInk,
                bottomTouch = layout.bottomTouch,
                bottomIcon = layout.bottomIcon,
                horizontalArrangement = Arrangement.spacedBy(actionGap, Alignment.End),
                onLyrics = onLyrics,
                onQueue = onQueue,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = layout.landscapeRow2TopPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(layout.landscapeMetaRowGap),
        ) {
            PlayerTrackMetadataBlock(
                song = song,
                mixVisual = mixVisual,
                activeInk = activeInk,
                secondaryInk = secondaryInk,
                titleSizeSp = layout.titleSizeSp,
                artistSizeSp = layout.artistSizeSp,
                interactive = interactive,
                onClick = onLyrics,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = if (params.reserveSpatialCubeSlot) CoverThemeSpatialCubeReserve else 0.dp)
                    .graphicsLayer { alpha = params.heroContentAlpha.value },
            )
            PlayerTransportRow(
                model = model,
                expressive = expressive,
                oneUi = oneUi,
                activeInk = activeInk,
                scale = layout.transportScale,
                spacing = layout.transportSpacing,
            )
        }
    }
}

@Composable
internal fun PlayerCoverflowStage(
    params: CoverThemeStageParams,
    modifier: Modifier = Modifier,
) {
    val model = params.model
    val song = params.song
    val appearance = params.appearance
    val coverPalette = params.coverPalette
    val expressive = params.expressive
    val oneUi = params.oneUi
    val backdrop = params.backdrop
    val miuixBackdrop = params.miuixBackdrop
    val menuTint = params.menuTint
    val activeInk = params.activeInk
    val secondaryInk = params.secondaryInk
    val mixVisual = params.mixVisual
    val spatialCoverState = params.spatialCoverState
    val spatialCoverRendering = params.spatialCoverRendering
    val spatialTilt = params.spatialTilt
    val parallaxTilt by remember(spatialTilt, appearance.coverflowParallaxSensitivity) {
        derivedStateOf { scaleSpatialParallaxTilt(spatialTilt.value, appearance.coverflowParallaxSensitivity) }
    }
    val motionVideoPath = params.motionVideoPath
    val motionVideoAllowed = params.motionVideoAllowed
    val active = params.active
    val interactive = params.interactive
    val lyricsOpen = params.lyricsOpen
    val queueOpen = params.queueOpen
    val topInset = params.topInset
    val leftInset = params.leftInset
    val rightInset = params.rightInset
    val bottomInset = params.bottomInset
    val maxWidth = params.maxWidth
    val maxHeight = params.maxHeight
    val onLyrics = params.onLyrics
    val onQueue = params.onQueue
    val onQualityMenuExpanded = params.onQualityMenuExpanded
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val advancedMaterial = LocalAdvancedMaterialEnabled.current

    val metrics = remember(
        maxWidth,
        maxHeight,
        leftInset,
        topInset,
        rightInset,
        bottomInset,
        appearance.showVolume,
    ) {
        computeCoverflowMetrics(
            width = maxWidth,
            height = maxHeight,
            leftInset = leftInset,
            topInset = topInset,
            rightInset = rightInset,
            bottomInset = bottomInset,
            showVolume = appearance.showVolume,
        )
    }

    val visibleQueue = remember(model.queue, model.queueIndex, model.playbackMode) {
        if (model.playbackMode == btm.m.auraplayer.ROAMING_PLAYBACK_MODE) {
            model.queue.take((model.queueIndex + 1).coerceIn(0, model.queue.size))
        } else {
            model.queue
        }
    }
    val tracks = remember(visibleQueue, song.key) {
        visibleQueue.ifEmpty { listOf(song) }
    }
    val targetIndex = remember(tracks, model.queueIndex, song.key) {
        val idx = model.queueIndex
        when {
            idx in tracks.indices && tracks[idx].key == song.key -> idx
            else -> tracks.indexOfFirst { it.key == song.key }.takeIf { it >= 0 }
                ?: idx.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        }
    }

    val pagerState = rememberPagerState(
        initialPage = targetIndex,
        pageCount = { tracks.size.coerceAtLeast(1) },
    )
    val latestTargetIndex by rememberUpdatedState(targetIndex)
    val latestStageVisible by rememberUpdatedState(params.stageVisible)
    var syncingExternalTarget by remember { mutableStateOf(false) }

    LaunchedEffect(targetIndex, tracks.size) {
        if (tracks.isEmpty()) return@LaunchedEffect
        val clamped = targetIndex.coerceIn(0, tracks.lastIndex)
        if (pagerState.currentPage != clamped || pagerState.settledPage != clamped) {
            syncingExternalTarget = true
            try {
                if (latestStageVisible && abs(pagerState.currentPage - clamped) <= 3) {
                    pagerState.animateScrollToPage(clamped)
                } else {
                    pagerState.scrollToPage(clamped)
                }
            } finally {
                syncingExternalTarget = false
            }
        }
    }

    LaunchedEffect(pagerState) {
        var lastTickPage = pagerState.currentPage
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (!latestStageVisible) {
                    lastTickPage = page
                    return@collect
                }
                if (scrolling && page != lastTickPage) {
                    lastTickPage = page
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                } else if (!scrolling) {
                    lastTickPage = page
                }
            }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
            .collect { (settled, scrolling) ->
                if (!scrolling && !syncingExternalTarget && latestStageVisible && settled != latestTargetIndex && settled in tracks.indices) {
                    model.queueTo(settled)
                }
            }
    }

    val glowColor = remember(coverPalette, expressive, oneUi) {
        oneUi?.accent ?: expressive?.primary ?: vibrantCoverGlowColor(coverPalette)
    }
    val cornerRadius = (metrics.cardSize * 0.088f).coerceIn(16.dp, 26.dp)
    val cardShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val shaderCache = LocalRuntimeShaderCache.current
    val highlightPaint = remember { Paint() }
    val useMiuixHighlight = advancedMaterial && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val miuixCenteredHighlight = remember { MiuixHighlight.Default.copy(width = 0.85.dp, alpha = 0.86f) }

    Box(modifier = modifier.fillMaxSize()) {
        PlayerQueueCounterBadge(
            currentOneBased = targetIndex + 1,
            totalCount = tracks.size,
            miuixBackdrop = miuixBackdrop,
            menuTint = menuTint,
            expressive = expressive,
            oneUi = oneUi,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = leftInset + 20.dp, top = topInset + params.headerTopPadding)
                .height(CoverThemeHeaderTouchHeight),
        )

        val stageHorizontalPadding = maxOf(0.dp, (maxWidth - metrics.cardSize) / 2f)
        val stageTopHeadroom = 36.dp
        val stageBottomHeadroom = 24.dp
        val desiredStageTop = topInset + metrics.carouselTopPadding
        val stageTop = (desiredStageTop - stageTopHeadroom).coerceAtLeast(0.dp)
        val stageTopOffset = desiredStageTop - stageTop
        val stageContentHeight = metrics.cardSize + 8.dp + metrics.reflectionHeight
        val stageHeight = stageTopOffset + stageContentHeight + stageBottomHeadroom

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = stageTop)
                .fillMaxWidth()
                .height(stageHeight),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Soft ambient halo behind center album (tracks deep gyro background parallax when enabled)
            val haloGyroActive = appearance.coverflowParallax
            Box(
                modifier = Modifier
                    .padding(top = stageTopOffset)
                    .width(metrics.cardSize * 2.15f)
                    .height(metrics.cardSize * 1.25f)
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        if (haloGyroActive) {
                            translationX = -parallaxTilt.x * 32.dp.toPx()
                            translationY = -parallaxTilt.y * 18.dp.toPx()
                        }
                    }
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                glowColor.copy(alpha = 0.28f),
                                glowColor.copy(alpha = 0.10f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            // Soft horizon mist along mirror floor plane
            Box(
                modifier = Modifier
                    .padding(top = stageTopOffset + metrics.cardSize - 8.dp)
                    .width(metrics.cardSize * 1.9f)
                    .height(22.dp)
                    .graphicsLayer {
                        if (haloGyroActive) {
                            translationX = -parallaxTilt.x * 20.dp.toPx()
                            translationY = -parallaxTilt.y * 9.dp.toPx()
                        }
                    }
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.10f),
                                glowColor.copy(alpha = 0.06f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            val reflectionDecodePx = remember(metrics.cardSize, density) {
                with(density) { metrics.cardSize.toPx().roundToInt().coerceIn(128, 320) }
            }
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = if (metrics.isLandscape) 3 else 2,
                pageSize = PageSize.Fixed(metrics.cardSize),
                pageSpacing = metrics.pageSpacing,
                contentPadding = PaddingValues(horizontal = stageHorizontalPadding),
                userScrollEnabled = interactive && tracks.size > 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val track = tracks[page]
                val pageOffset by remember(page) {
                    derivedStateOf {
                        (page - pagerState.currentPage) - pagerState.currentPageOffsetFraction
                    }
                }
                val transform = computeCoverflowCardTransform(pageOffset)
                val absOffset = abs(pageOffset)
                val isCentered = absOffset < 0.28f
                val isActiveTrack = track.key == song.key && absOffset < 0.45f
                val blurDp = if (advancedMaterial) transform.blurRadiusDp.dp else 0.dp
                val translationPx = with(density) { metrics.cardSize.toPx() * transform.translationXFactor }
                val cameraDist = 14f * density.density
                val elevationPx = with(density) { (if (isCentered) 26.dp else 10.dp * transform.scale).toPx() }
                val cardClick: (() -> Unit)? = if (!interactive) {
                    null
                } else if (isCentered) {
                    onLyrics
                } else {
                    { scope.launch { pagerState.animateScrollToPage(page) } }
                }

                Column(
                    modifier = Modifier
                        .width(metrics.cardSize)
                        .height(stageHeight)
                        .zIndex(transform.zIndex)
                        .graphicsLayer {
                            transformOrigin = TransformOrigin.Center
                            cameraDistance = if (appearance.coverflowParallax) 8.5f * density.density else cameraDist
                            if (appearance.coverflowParallax) {
                                val depthFactor = 1f - absOffset.coerceAtMost(1.8f) * 0.78f
                                val centerFocus = (1f - (absOffset / 1.5f).coerceIn(0f, 1f))
                                rotationY = transform.rotationY
                                translationX = translationPx + parallaxTilt.x * (22.dp.toPx() * depthFactor)
                                translationY = parallaxTilt.y * (16.dp.toPx() * (0.25f + 0.75f * centerFocus))
                            } else {
                                rotationY = transform.rotationY
                                translationX = translationPx
                            }
                            scaleX = transform.scale
                            scaleY = transform.scale
                            val slotHeroAlpha = if (isActiveTrack) params.heroContentAlpha.value else 1f
                            alpha = transform.contentAlpha * slotHeroAlpha
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(stageTopOffset))
                    val centerFocus = (1f - (absOffset / 1.5f).coerceIn(0f, 1f))
                    val gyroTiltScale = 0.65f + 0.35f * centerFocus
                    val effectiveUri = if (isActiveTrack) song.artwork.ifBlank { track.artwork } else track.artwork
                    val adaptIrregular = LocalAdaptIrregularCovers.current && effectiveUri.isNotBlank()
                    val cardRatio = rememberCoverAspectRatio(effectiveUri, enabled = adaptIrregular)
                    Box(
                        modifier = Modifier.size(metrics.cardSize),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                    Box(
                        modifier = Modifier
                            .then(if (adaptIrregular) Modifier.fitCoverAspectRatio(cardRatio) else Modifier.fillMaxSize())
                            .graphicsLayer {
                                transformOrigin = TransformOrigin.Center
                                if (appearance.coverflowParallax) {
                                    cameraDistance = 7.0f * density.density
                                    rotationY = -parallaxTilt.x * 20f * gyroTiltScale
                                    rotationX = parallaxTilt.y * 17f * gyroTiltScale
                                }
                                shadowElevation = elevationPx
                                shape = cardShape
                                clip = true
                            }
                            .then(
                                if (blurDp > 0.dp) {
                                    Modifier.blur(blurDp, BlurredEdgeTreatment(cardShape))
                                } else {
                                    Modifier
                                },
                            )
                            .clip(cardShape),
                    ) {
                        // Single persistent Cover composable keyed by card page/track so toggling isActiveTrack never drops the bitmap.
                        Cover(
                            uri = effectiveUri,
                            modifier = Modifier.fillMaxSize(),
                            radius = cornerRadius,
                            onClick = cardClick,
                            animation = if (isActiveTrack) appearance.coverAnimation else CoverAnimation.DEFAULT,
                            animate = isActiveTrack && active,
                            transitionKey = track.key,
                            mixVisual = if (isActiveTrack) mixVisual else null,
                            placeholderTint = activeInk.copy(alpha = 0.3f),
                            spatialState = if (isActiveTrack) spatialCoverState else SpatialCoverResolveState.Idle,
                            spatialActive = isActiveTrack && spatialCoverRendering,
                            spatialTilt = if (isActiveTrack && spatialCoverRendering) {
                                scaleSpatialParallaxTilt(spatialTilt.value, appearance.spatialCoverSensitivity)
                            } else {
                                Offset.Zero
                            },
                            motionVideoPath = if (isActiveTrack) motionVideoPath else null,
                            motionPlaying = isActiveTrack && motionVideoAllowed && mixVisual == null,
                            motionOverlayAlpha = if (isActiveTrack && mixVisual == null && !spatialCoverRendering) 1f else 0f,
                        )

                        if (transform.dimAlpha > 0.005f) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color.Black.copy(alpha = transform.dimAlpha)),
                            )
                        }

                        if (isCentered && useMiuixHighlight) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .drawWithContent {
                                        drawContent()
                                        drawMiuixHighlight(miuixCenteredHighlight, cardShape, shaderCache, highlightPaint)
                                    },
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .border(
                                        width = if (isCentered) 0.75.dp else 0.6.dp,
                                        brush = Brush.verticalGradient(
                                            colors = listOf(
                                                Color.White.copy(alpha = if (isCentered) 0.24f else 0.14f),
                                                Color.White.copy(alpha = 0.04f),
                                            ),
                                        ),
                                        shape = cardShape,
                                    ),
                            )
                        }
                    }
                    }

                    val reflectionArt = if (isActiveTrack) {
                        mixVisual?.toArtwork ?: song.artwork.ifBlank { track.artwork }
                    } else {
                        track.artwork
                    }
                    val reflectionRequest = remember(reflectionArt, reflectionDecodePx, context) {
                        ImageRequest.Builder(context)
                            .data(reflectionArt)
                            .size(reflectionDecodePx)
                            .precision(Precision.INEXACT)
                            .build()
                    }
                    val reflectionWidth = if (adaptIrregular && cardRatio < 1f) {
                        metrics.cardSize * cardRatio
                    } else {
                        metrics.cardSize
                    }
                    val reflectionImageHeight = if (adaptIrregular && cardRatio > 1f) {
                        metrics.cardSize / cardRatio
                    } else {
                        metrics.cardSize
                    }
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(reflectionWidth, metrics.reflectionHeight)
                            .graphicsLayer {
                                if (appearance.coverflowParallax) {
                                    cameraDistance = 8.0f * density.density
                                    rotationY = -parallaxTilt.x * 16f * gyroTiltScale
                                    rotationX = -parallaxTilt.y * 10f * gyroTiltScale
                                    translationY = parallaxTilt.y * 4.dp.toPx()
                                }
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
                            .drawWithCache {
                                val fade = Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.28f),
                                    0.52f to Color.Black.copy(alpha = 0.09f),
                                    1f to Color.Transparent,
                                )
                                onDrawWithContent {
                                    drawContent()
                                    drawRect(brush = fade, blendMode = BlendMode.DstIn)
                                }
                            },
                    ) {
                        val reflectionBlur = if (advancedMaterial) (6f + transform.blurRadiusDp * 0.55f).dp else 4.dp
                        AsyncImage(
                            model = reflectionRequest,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.BottomCenter,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(reflectionImageHeight)
                                .align(Alignment.TopCenter)
                                .graphicsLayer { scaleY = -1f }
                                .blur(reflectionBlur, BlurredEdgeTreatment.Rectangle),
                        )
                    }
                }
            }
        }

        // Landscape keeps the compact two-row controls; portrait directly reuses SQUARE layout in PlayerHost.
        if (metrics.isLandscape) {
            PlayerCoverThemeControlDock(
                params = params,
                isLandscape = true,
                dockCompact = metrics.dockCompact,
                includeVolume = false,
                dockCornerRadius = metrics.dockCornerRadius,
                showCardContainer = false,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = leftInset + metrics.dockHorizontalMargin,
                        end = rightInset + metrics.dockHorizontalMargin,
                        bottom = bottomInset + metrics.dockBottomPadding,
                    )
                    .fillMaxWidth(),
            )
        }
    }
}
