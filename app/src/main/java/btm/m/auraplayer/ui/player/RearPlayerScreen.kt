// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.view.RoundedCorner
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.R
import btm.m.auraplayer.lyrics.LyricLine
import btm.m.auraplayer.lyrics.PlayerLyricView
import btm.m.auraplayer.playback.PlayerTheme
import btm.m.auraplayer.rear.RearColorMode
import btm.m.auraplayer.rear.RearCutoutRect
import btm.m.auraplayer.rear.RearSafeInsetsPx
import btm.m.auraplayer.rear.RearScreenDevice
import btm.m.auraplayer.rear.resolveAppearance
import btm.m.auraplayer.ui.AppUiStyle
import btm.m.auraplayer.ui.GlassToolbarIconButton
import btm.m.auraplayer.ui.LocalAppUiStyle
import btm.m.auraplayer.ui.LocalToolbarBackdrop
import btm.m.auraplayer.ui.tr
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import kotlin.math.roundToInt

private enum class RearDisplayMode {
    CONTROL,
    LYRICS,
    AOD,
}

@Composable
fun RearPlayerScreen(
    model: AuraViewModel,
    isAodMode: Boolean,
    onKeepScreenOn: (Boolean) -> Unit,
    onExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as AuraApplication
    val mainConfig by app.nowPlaying.state.collectAsState()
    val rearConfig by app.rearScreen.state.collectAsState()
    val appSettings = app.settings.value

    val resolvedAppearance = remember(rearConfig, mainConfig) {
        rearConfig.resolveAppearance(mainConfig)
    }
    val renderConfig = remember(resolvedAppearance, mainConfig, rearConfig) {
        resolvedAppearance.toNowPlayingConfig(mainConfig, rearConfig)
    }

    val song = model.current
    val artwork = song?.artwork.orEmpty()
    val colorStyle = btm.m.auraplayer.ui.LocalAuraColorStyle.current
    val palette = rememberPlayerMonetPalette(artwork)
    val seed = rememberPlayerArtworkColorSeed(artwork, useOklab = colorStyle.isOklab)
    val amoledBlack = rearConfig.amoledBlack
    val baseDark = when (rearConfig.colorMode) {
        RearColorMode.DARK -> true
        RearColorMode.LIGHT -> false
        RearColorMode.SYSTEM -> isSystemInDarkTheme()
    }
    val effectiveDark = baseDark || amoledBlack
    val expressiveColors = if (resolvedAppearance.theme == PlayerTheme.MATERIAL) {
        rememberPlayerExpressiveColors(seed, effectiveDark, useOklab = colorStyle.isOklab, paletteIndex = colorStyle.palette)
    } else null
    val oneUiColors = if (resolvedAppearance.theme == PlayerTheme.ONE) {
        rememberPlayerOneUiColors(seed, effectiveDark, useOklab = colorStyle.isOklab, paletteIndex = colorStyle.palette)
    } else null
    val oklabAmbient = remember(colorStyle, seed, effectiveDark) {
        if (colorStyle.isOklab) btm.m.auraplayer.ui.OklabThemeEngine.generatePlayerAmbient(seed, effectiveDark, colorStyle.palette) else null
    }

    val isLightPalette = !amoledBlack && !effectiveDark

    var rearLyricsMode by rememberSaveable { mutableStateOf(false) }
    val displayMode = when {
        isAodMode -> RearDisplayMode.AOD
        rearLyricsMode -> RearDisplayMode.LYRICS
        else -> RearDisplayMode.CONTROL
    }

    LaunchedEffect(displayMode, rearConfig.keepLyricsAwake) {
        onKeepScreenOn(displayMode == RearDisplayMode.LYRICS && rearConfig.keepLyricsAwake)
    }

    val safeGeometry = rememberRearSafeGeometry()
    val safeInsets = safeGeometry.padding
    val transition = updateTransition(targetState = displayMode, label = "rearDisplayMode")
    val spatialSpec: androidx.compose.animation.core.Transition.Segment<RearDisplayMode>.() -> androidx.compose.animation.core.FiniteAnimationSpec<Dp> = {
        spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
    }
    val floatSpec: androidx.compose.animation.core.Transition.Segment<RearDisplayMode>.() -> androidx.compose.animation.core.FiniteAnimationSpec<Float> = {
        tween(340, easing = FastOutSlowInEasing)
    }
    val lyricsAlpha by transition.animateFloat(transitionSpec = { floatSpec() }, label = "lyricAlpha") { mode ->
        if (mode == RearDisplayMode.LYRICS) 1f else 0f
    }

    val lyricSpec = remember(resolvedAppearance.lyricTheme) { lyricThemeSpec(resolvedAppearance.lyricTheme) }
    val lyricInkBlend = if (!amoledBlack && !isAodMode && resolvedAppearance.lyricTheme != btm.m.auraplayer.playback.LyricTheme.BLUR) {
        lyricsAlpha.coerceIn(0f, 1f)
    } else {
        0f
    }
    val baseInk = when {
        isAodMode -> Color.White
        resolvedAppearance.theme == PlayerTheme.MATERIAL && expressiveColors != null -> expressiveColors.onSurface
        resolvedAppearance.theme == PlayerTheme.ONE && oneUiColors != null -> oneUiColors.text
        oklabAmbient != null -> oklabAmbient.activeInk
        isLightPalette -> Color(palette.n1.tone(10))
        else -> Color.White
    }
    val baseSubInk = when {
        isAodMode -> Color.White.copy(alpha = 0.72f)
        resolvedAppearance.theme == PlayerTheme.MATERIAL && expressiveColors != null -> expressiveColors.onSurfaceVariant
        resolvedAppearance.theme == PlayerTheme.ONE && oneUiColors != null -> oneUiColors.subText
        oklabAmbient != null -> oklabAmbient.secondaryInk
        isLightPalette -> Color(palette.n1.tone(32)).copy(alpha = 0.86f)
        else -> Color.White.copy(alpha = 0.72f)
    }
    val ink = if (lyricInkBlend > 0.001f) lerp(baseInk, lyricSpec.inkColor, lyricInkBlend) else baseInk
    val subInk = if (lyricInkBlend > 0.001f) lerp(baseSubInk, lyricSpec.secondaryInkColor.copy(alpha = 0.78f), lyricInkBlend) else baseSubInk

    val bgAlpha by transition.animateFloat(
        transitionSpec = { floatSpec() },
        label = "rearBgAlpha",
    ) { mode -> if (mode == RearDisplayMode.AOD) 0f else 1f }
    val exitAlpha by transition.animateFloat(
        transitionSpec = { floatSpec() },
        label = "rearExitAlpha",
    ) { mode -> if (mode == RearDisplayMode.AOD) 0f else 1f }

    val baseSurfaceColor = if (isLightPalette && !isAodMode) Color(0xFFF6F5FA) else Color.Black
    val rearBackdrop = rememberLayerBackdrop {
        drawRect(baseSurfaceColor)
        drawContent()
    }
    val exitUiStyle = when (resolvedAppearance.theme) {
        PlayerTheme.MATERIAL -> AppUiStyle.MATERIAL
        PlayerTheme.ONE -> AppUiStyle.ONE
        else -> AppUiStyle.HYPER
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(baseSurfaceColor)
            .playerTrackSwipe(
                enabled = !isAodMode && rearConfig.swipeTracks && song != null,
                next = model::next,
                previous = model::previous,
            ),
    ) {
        if (bgAlpha > 0.005f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = bgAlpha }
                    .layerBackdrop(rearBackdrop),
            ) {
                if (!amoledBlack) {
                    PlayerThemeBackground(
                        artwork = artwork,
                        palette = palette,
                        config = renderConfig,
                        compactProgress = lyricsAlpha,
                        animate = true,
                        expressive = expressiveColors,
                        oneUi = oneUiColors,
                        isDark = effectiveDark,
                        useOklab = colorStyle.isOklab,
                        oklabPaletteIndex = colorStyle.palette,
                        seed = seed,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(safeInsets),
        ) {
            if (song == null) {
                RearEmptyState(ink = ink, subInk = subInk)
            } else {
            val contentWidth = maxWidth
            val compact = maxHeight < 210.dp || contentWidth < 290.dp
            val controlCoverSize = if (compact) 59.dp else 68.dp
            val lyricsCoverSize = 32.dp
            val aodCoverSize = (maxHeight * 0.44f).coerceIn(66.dp, 88.dp)

            val controlExitSize = controlCoverSize * 0.50f
            val topVolumeRoom = contentWidth - controlCoverSize - controlExitSize - 28.dp
            val showTopVolume = rearConfig.showVolume && topVolumeRoom >= 64.dp
            val showBottomVolume = rearConfig.showVolume && !showTopVolume
            val infoBlockHeight = if (compact) 29.dp else 31.dp
            val sliderHeight = if (compact) 14.dp else 17.dp
            val narrowControls = compact || contentWidth < 235.dp
            val touchSize = if (narrowControls) 30.dp else 36.dp
            val iconSize = if (narrowControls) 17.dp else 20.dp
            val auxTouchSize = if (narrowControls) 26.dp else 30.dp
            val auxIconSize = if (narrowControls) 15.dp else 18.dp
            val extraVolumeHeight = if (showBottomVolume) 16.dp else 0.dp
            val gapCount = if (showBottomVolume) 5f else 4f
            val controlContentHeight =
                controlCoverSize + infoBlockHeight + sliderHeight + (touchSize + 2.dp) + auxTouchSize + extraVolumeHeight
            val bandGap = ((maxHeight - controlContentHeight) / gapCount).coerceIn(3.dp, 14.dp)

            val controlInfoY = controlCoverSize + bandGap
            val aodTotalHeight = aodCoverSize + 6.dp + 38.dp
            val aodCoverY = ((maxHeight - aodTotalHeight) / 2f).coerceAtLeast(0.dp)
            val aodInfoY = aodCoverY + aodCoverSize + 8.dp

            val coverSize by transition.animateDp(transitionSpec = { spatialSpec() }, label = "coverSize") { mode ->
                when (mode) {
                    RearDisplayMode.CONTROL -> controlCoverSize
                    RearDisplayMode.LYRICS -> lyricsCoverSize
                    RearDisplayMode.AOD -> aodCoverSize
                }
            }
            val coverCorner by transition.animateDp(transitionSpec = { spatialSpec() }, label = "coverCorner") { mode ->
                when (mode) {
                    RearDisplayMode.CONTROL -> 9.dp
                    RearDisplayMode.LYRICS -> 6.dp
                    RearDisplayMode.AOD -> 11.dp
                }
            }
            val coverY by transition.animateDp(transitionSpec = { spatialSpec() }, label = "coverY") { mode ->
                when (mode) {
                    RearDisplayMode.CONTROL -> 0.dp
                    RearDisplayMode.LYRICS -> 0.dp
                    RearDisplayMode.AOD -> aodCoverY
                }
            }
            val infoX by transition.animateDp(transitionSpec = { spatialSpec() }, label = "infoX") { mode ->
                when (mode) {
                    RearDisplayMode.CONTROL -> 0.dp
                    RearDisplayMode.LYRICS -> lyricsCoverSize + 8.dp
                    RearDisplayMode.AOD -> 0.dp
                }
            }
            val infoY by transition.animateDp(transitionSpec = { spatialSpec() }, label = "infoY") { mode ->
                when (mode) {
                    RearDisplayMode.CONTROL -> controlInfoY
                    RearDisplayMode.LYRICS -> 3.dp
                    RearDisplayMode.AOD -> aodInfoY
                }
            }
            val infoTextScale by transition.animateFloat(transitionSpec = { floatSpec() }, label = "infoScale") { mode ->
                when (mode) {
                    RearDisplayMode.CONTROL -> 1f
                    RearDisplayMode.LYRICS -> 0.86f
                    RearDisplayMode.AOD -> 1.16f
                }
            }

            val controlsAlpha by transition.animateFloat(transitionSpec = { floatSpec() }, label = "ctrlAlpha") { mode ->
                if (mode == RearDisplayMode.CONTROL) 1f else 0f
            }
            val controlsBlur by transition.animateDp(transitionSpec = { spatialSpec() }, label = "ctrlBlur") { mode ->
                if (mode == RearDisplayMode.CONTROL) 0.dp else 10.dp
            }
            val lyricsBlur by transition.animateDp(transitionSpec = { spatialSpec() }, label = "lyricBlur") { mode ->
                if (mode == RearDisplayMode.LYRICS) 0.dp else 10.dp
            }

            val infoTopPaddingInControl = controlInfoY + infoBlockHeight + bandGap
            val lyricsTopPadding = lyricsCoverSize + 4.dp

            // Controls layer: progress slider + [Prev, Play/Pause, Next] (+ inline volume) + bottom [Lyrics, Favorite, Loop]
            if (controlsAlpha > 0.01f) {
                val activePlaying = model.playing || model.playbackRequested
                var scrubbing by remember { mutableStateOf(false) }
                var scrubFraction by remember { mutableFloatStateOf(0f) }
                val safeDuration = model.duration.coerceAtLeast(1L)
                val progressFraction = if (scrubbing) {
                    scrubFraction
                } else {
                    (model.position.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = infoTopPaddingInControl)
                        .graphicsLayer { alpha = controlsAlpha }
                        .then(if (controlsBlur > 0.2.dp) Modifier.blur(controlsBlur) else Modifier),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    when {
                        resolvedAppearance.theme == PlayerTheme.MATERIAL && expressiveColors != null -> {
                            MaterialWavyProgressSlider(
                                value = progressFraction,
                                playing = activePlaying,
                                label = tr("seek_position", "播放进度"),
                                activeColor = expressiveColors.primary,
                                trackColor = expressiveColors.surfaceContainerHighest.copy(alpha = if (expressiveColors.dark) 0.55f else 0.85f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(sliderHeight),
                                onChange = {
                                    scrubbing = true
                                    scrubFraction = it
                                },
                                onFinished = {
                                    model.seekTo((scrubFraction * safeDuration).toLong())
                                    scrubbing = false
                                },
                            )
                        }
                        resolvedAppearance.theme == PlayerTheme.ONE && oneUiColors != null -> {
                            OneUiPlayerProgressSlider(
                                value = progressFraction,
                                label = tr("seek_position", "播放进度"),
                                colors = oneUiColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(sliderHeight),
                                onChange = {
                                    scrubbing = true
                                    scrubFraction = it
                                },
                                onFinished = {
                                    model.seekTo((scrubFraction * safeDuration).toLong())
                                    scrubbing = false
                                },
                            )
                        }
                        else -> {
                            RearPillSlider(
                                value = progressFraction,
                                activeColor = ink,
                                trackColor = ink.copy(alpha = 0.26f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(sliderHeight),
                                onChange = {
                                    scrubbing = true
                                    scrubFraction = it
                                },
                                onFinished = {
                                    model.seekTo((scrubFraction * safeDuration).toLong())
                                    scrubbing = false
                                },
                            )
                        }
                    }

                    val revision = model.history.revision
                    val favorite = remember(revision, song.key) { model.isFavorite(song) }
                    val favIcon = if (favorite) R.drawable.ic_player_favorite_on else R.drawable.ic_player_favorite
                    val favLabel = if (favorite) tr("unfavorite", "取消收藏") else tr("favorite", "收藏")
                    val lyricsIcon = if (rearLyricsMode) R.drawable.ic_player_lyrics_on else R.drawable.ic_player_lyrics
                    val lyricsLabel = tr("lyrics", "歌词")
                    val modeIcon = when (model.playbackMode) {
                        1 -> R.drawable.ic_player_shuffle
                        2 -> R.drawable.ic_player_repeat_one
                        btm.m.auraplayer.HEARTBEAT_PLAYBACK_MODE -> R.drawable.ic_player_heartbeat
                        btm.m.auraplayer.ROAMING_PLAYBACK_MODE -> R.drawable.ic_player_roaming
                        else -> R.drawable.ic_player_repeat
                    }
                    val modeLabel = when (model.playbackMode) {
                        1 -> tr("shuffle_mode", "随机播放")
                        2 -> tr("repeat_one_mode", "单曲循环")
                        btm.m.auraplayer.HEARTBEAT_PLAYBACK_MODE -> tr("heartbeat_mode", "心动模式")
                        btm.m.auraplayer.ROAMING_PLAYBACK_MODE -> tr("roaming_mode", "漫游模式")
                        else -> tr("repeat_all_mode", "列表循环")
                    }
                    val inlineVolumeWidth = (contentWidth * 0.30f).coerceIn(72.dp, 120.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            resolvedAppearance.theme == PlayerTheme.MATERIAL && expressiveColors != null -> {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    MaterialExpressiveTransportButton(
                                        icon = R.drawable.ic_player_previous,
                                        label = tr("previous_track", "上一首"),
                                        containerColor = expressiveColors.surfaceContainerHigh,
                                        contentColor = expressiveColors.iconInk,
                                        width = touchSize,
                                        height = touchSize,
                                        iconSize = iconSize,
                                        onClick = model::previous,
                                    )
                                }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    MaterialExpressivePlayPauseButton(
                                        playing = activePlaying,
                                        label = tr("play_pause", "播放/暂停"),
                                        colors = expressiveColors,
                                        touchHeight = touchSize + 2.dp,
                                        pausedWidth = touchSize + 10.dp,
                                        playingWidth = touchSize + 14.dp,
                                        iconSize = iconSize + 3.dp,
                                        onClick = model::togglePlayback,
                                    )
                                }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    MaterialExpressiveTransportButton(
                                        icon = R.drawable.ic_player_next,
                                        label = tr("next_track", "下一首"),
                                        containerColor = expressiveColors.surfaceContainerHigh,
                                        contentColor = expressiveColors.iconInk,
                                        width = touchSize,
                                        height = touchSize,
                                        iconSize = iconSize,
                                        onClick = model::next,
                                    )
                                }
                            }
                            resolvedAppearance.theme == PlayerTheme.ONE && oneUiColors != null -> {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    OneUiPlayerTransportButton(
                                        icon = R.drawable.ic_player_previous,
                                        label = tr("previous_track", "上一首"),
                                        colors = oneUiColors,
                                        touchSize = touchSize,
                                        iconSize = iconSize,
                                        onClick = model::previous,
                                    )
                                }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    OneUiPlayerPlayPauseButton(
                                        playing = activePlaying,
                                        label = tr("play_pause", "播放/暂停"),
                                        colors = oneUiColors,
                                        touchSize = touchSize + 2.dp,
                                        iconSize = iconSize + 3.dp,
                                        onClick = model::togglePlayback,
                                    )
                                }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    OneUiPlayerTransportButton(
                                        icon = R.drawable.ic_player_next,
                                        label = tr("next_track", "下一首"),
                                        colors = oneUiColors,
                                        touchSize = touchSize,
                                        iconSize = iconSize,
                                        onClick = model::next,
                                    )
                                }
                            }
                            else -> {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    RearCircleIconButton(
                                        icon = R.drawable.ic_player_previous,
                                        label = tr("previous_track", "上一首"),
                                        tint = ink,
                                        containerColor = ink.copy(alpha = 0.12f),
                                        touchSize = touchSize,
                                        iconSize = iconSize,
                                        onClick = model::previous,
                                    )
                                }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    RearCircleIconButton(
                                        icon = if (activePlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                                        label = tr("play_pause", "播放/暂停"),
                                        tint = ink,
                                        containerColor = ink.copy(alpha = 0.22f),
                                        touchSize = touchSize + 4.dp,
                                        iconSize = iconSize + 3.dp,
                                        onClick = model::togglePlayback,
                                    )
                                }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    RearCircleIconButton(
                                        icon = R.drawable.ic_player_next,
                                        label = tr("next_track", "下一首"),
                                        tint = ink,
                                        containerColor = ink.copy(alpha = 0.12f),
                                        touchSize = touchSize,
                                        iconSize = iconSize,
                                        onClick = model::next,
                                    )
                                }
                            }
                        }
                    }

                    if (showBottomVolume) {
                        RearVolumeBar(
                            ink = ink,
                            subInk = subInk,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp),
                        )
                    }

                    // Bottom/last row: Lyrics | Favorite | Loop Mode aligned under Previous | Play | Next
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            resolvedAppearance.theme == PlayerTheme.MATERIAL && expressiveColors != null -> {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    MaterialExpressiveBottomButton(
                                        icon = lyricsIcon,
                                        label = lyricsLabel,
                                        selected = rearLyricsMode,
                                        colors = expressiveColors,
                                        touchSize = auxTouchSize,
                                        iconSize = auxIconSize,
                                        onClick = { rearLyricsMode = !rearLyricsMode },
                                    )
                                }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    MaterialExpressiveBottomButton(
                                        icon = favIcon,
                                        label = favLabel,
                                        selected = favorite,
                                        colors = expressiveColors,
                                        touchSize = auxTouchSize,
                                        iconSize = auxIconSize,
                                        useTertiary = true,
                                        onClick = { model.toggleFavorite(song) },
                                    )
                                }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    MaterialExpressiveBottomButton(
                                        icon = modeIcon,
                                        label = modeLabel,
                                        selected = false,
                                        colors = expressiveColors,
                                        touchSize = auxTouchSize,
                                        iconSize = auxIconSize,
                                        onClick = model::cyclePlaybackMode,
                                    )
                                }
                            }
                            resolvedAppearance.theme == PlayerTheme.ONE && oneUiColors != null -> {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    OneUiPlayerBottomButton(
                                        icon = lyricsIcon,
                                        label = lyricsLabel,
                                        selected = rearLyricsMode,
                                        colors = oneUiColors,
                                        touchSize = auxTouchSize,
                                        iconSize = auxIconSize,
                                        onClick = { rearLyricsMode = !rearLyricsMode },
                                    )
                                }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    OneUiPlayerBottomButton(
                                        icon = favIcon,
                                        label = favLabel,
                                        selected = favorite,
                                        colors = oneUiColors,
                                        touchSize = auxTouchSize,
                                        iconSize = auxIconSize,
                                        onClick = { model.toggleFavorite(song) },
                                    )
                                }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    OneUiPlayerBottomButton(
                                        icon = modeIcon,
                                        label = modeLabel,
                                        selected = false,
                                        colors = oneUiColors,
                                        touchSize = auxTouchSize,
                                        iconSize = auxIconSize,
                                        onClick = model::cyclePlaybackMode,
                                    )
                                }
                            }
                            else -> {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    RearCircleIconButton(
                                        icon = lyricsIcon,
                                        label = lyricsLabel,
                                        tint = ink.copy(alpha = if (rearLyricsMode) 1f else 0.9f),
                                        containerColor = ink.copy(alpha = if (rearLyricsMode) 0.22f else 0.12f),
                                        touchSize = auxTouchSize,
                                        iconSize = auxIconSize,
                                        onClick = { rearLyricsMode = !rearLyricsMode },
                                    )
                                }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    RearCircleIconButton(
                                        icon = favIcon,
                                        label = favLabel,
                                        tint = ink.copy(alpha = if (favorite) 1f else 0.9f),
                                        containerColor = ink.copy(alpha = if (favorite) 0.22f else 0.12f),
                                        touchSize = auxTouchSize,
                                        iconSize = auxIconSize,
                                        onClick = { model.toggleFavorite(song) },
                                    )
                                }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    RearCircleIconButton(
                                        icon = modeIcon,
                                        label = modeLabel,
                                        tint = ink.copy(alpha = 0.9f),
                                        containerColor = ink.copy(alpha = 0.12f),
                                        touchSize = auxTouchSize,
                                        iconSize = auxIconSize,
                                        onClick = model::cyclePlaybackMode,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (controlsAlpha > 0.01f && showTopVolume) {
                val topVolWidth = topVolumeRoom.coerceIn(64.dp, 128.dp)
                val topVolX = (contentWidth - controlExitSize - 16.dp - topVolWidth)
                    .coerceAtLeast(controlCoverSize + 12.dp)
                val topVolY = ((controlExitSize - 20.dp) / 2f).coerceAtLeast(0.dp)
                RearVolumeBar(
                    ink = ink,
                    subInk = subInk,
                    modifier = Modifier
                        .offset { IntOffset(topVolX.roundToPx(), topVolY.roundToPx()) }
                        .width(topVolWidth)
                        .height(20.dp)
                        .graphicsLayer { alpha = controlsAlpha }
                        .then(if (controlsBlur > 0.2.dp) Modifier.blur(controlsBlur) else Modifier),
                )
            }

            // Lyrics layer, flush left-aligned with info & blurred/faded on mode switch
            if (lyricsAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = lyricsTopPadding)
                        .graphicsLayer { alpha = lyricsAlpha }
                        .then(if (lyricsBlur > 0.2.dp) Modifier.blur(lyricsBlur) else Modifier),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (model.lyrics.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = if (model.lyricsLoading) tr("lyrics_loading", "正在加载歌词…") else tr("lyrics_empty", "暂无歌词"),
                                color = subInk,
                                fontSize = 13.sp,
                            )
                        }
                    } else {
                        RearEmbeddedLyricView(
                            lyrics = model.lyrics,
                            position = model.position,
                            playing = model.playing,
                            showTranslation = rearConfig.showTranslation,
                            showPronunciation = rearConfig.showPronunciation,
                            lyricScale = rearConfig.lyricSize.scale,
                            lineSpacingMultiplier = rearConfig.lyricSpacing.multiplier,
                            allowParentHorizontalSwipe = rearConfig.swipeTracks,
                            keepScreenOn = displayMode == RearDisplayMode.LYRICS && rearConfig.keepLyricsAwake,
                            lyricsInteractive = displayMode == RearDisplayMode.LYRICS,
                            lyricTheme = resolvedAppearance.lyricTheme,
                            tokyoLandmark = resolvedAppearance.tokyoSceneLandmark,
                            ink = ink,
                            onSeek = model::seekTo,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            val coverShape = RoundedCornerShape(coverCorner)
            // Shared seamless Cover (1:1 aspect ratio, moderate corners, clickable to toggle lyrics)
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, coverY.roundToPx()) }
                    .size(coverSize)
                    .aspectRatio(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = displayMode != RearDisplayMode.AOD,
                        role = Role.Button,
                        onClick = { rearLyricsMode = !rearLyricsMode },
                    )
                    .semantics { contentDescription = tr("lyrics", "歌词") },
            ) {
                AnimatedPlayerArtwork(
                    artwork = song.artwork,
                    transitionKey = song.id.toString(),
                    animation = resolvedAppearance.coverAnimation,
                    enabled = true,
                    shape = coverShape,
                )
            }

            // Shared seamless Title + Artist block (scales & translates between Control / Lyrics / AOD)
            val lyricsEndReserve by transition.animateDp(
                transitionSpec = { spatialSpec() },
                label = "lyricsEndReserve",
            ) { mode ->
                if (mode == RearDisplayMode.LYRICS) lyricsCoverSize + 8.dp else 0.dp
            }
            Column(
                modifier = Modifier
                    .offset { IntOffset(infoX.roundToPx(), infoY.roundToPx()) }
                    .width((maxWidth - infoX - lyricsEndReserve).coerceAtLeast(60.dp))
                    .then(
                        if (displayMode == RearDisplayMode.LYRICS) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { rearLyricsMode = false },
                            )
                        } else Modifier,
                    ),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = song.title,
                    color = ink,
                    fontSize = (13.5f * infoTextScale).sp,
                    lineHeight = (16f * infoTextScale).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(),
                )
                Text(
                    text = song.artist,
                    color = subInk,
                    fontSize = (11f * infoTextScale).sp,
                    lineHeight = (13f * infoTextScale).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            }

            val exitCompact = maxHeight < 210.dp || maxWidth < 290.dp
            val exitCtrlCoverSize = if (exitCompact) 59.dp else 68.dp
            val exitCtrlSize = exitCtrlCoverSize * 0.50f
            val exitLyricsSize = 30.dp
            val exitButtonSize by transition.animateDp(
                transitionSpec = { spatialSpec() },
                label = "exitButtonSize",
            ) { mode ->
                when {
                    song == null -> 46.dp
                    mode == RearDisplayMode.LYRICS -> exitLyricsSize
                    else -> exitCtrlSize
                }
            }
            val controlButtonSize = if (song == null) 46.dp else exitCtrlSize
            val controlCornerMargin = if (safeGeometry.topRightCornerRadiusDp > 0.dp) {
                (safeGeometry.topRightCornerRadiusDp - controlButtonSize / 2f).coerceIn(8.dp, 20.dp)
            } else 0.dp
            val controlTopAdjust = if (
                safeGeometry.topRightCornerRadiusDp > 0.dp &&
                safeGeometry.topDp <= 16.dp
            ) (controlCornerMargin - safeGeometry.topDp).coerceIn(0.dp, 8.dp) else 0.dp
            val controlEndAdjust = if (
                safeGeometry.topRightCornerRadiusDp > 0.dp &&
                safeGeometry.rightDp <= 16.dp
            ) (controlCornerMargin - safeGeometry.rightDp).coerceIn(0.dp, 8.dp) else 0.dp
            val exitTopAdjust by transition.animateDp(
                transitionSpec = { spatialSpec() },
                label = "exitTopAdjust",
            ) { mode ->
                if (song != null && mode == RearDisplayMode.LYRICS) 0.dp else controlTopAdjust
            }
            val exitEndAdjust by transition.animateDp(
                transitionSpec = { spatialSpec() },
                label = "exitEndAdjust",
            ) { mode ->
                if (song != null && mode == RearDisplayMode.LYRICS) 0.dp else controlEndAdjust
            }

            if (exitAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(-exitEndAdjust.roundToPx(), exitTopAdjust.roundToPx()) }
                        .graphicsLayer { alpha = exitAlpha },
                ) {
                    val exitContent: @Composable () -> Unit = {
                        CompositionLocalProvider(
                            LocalAppUiStyle provides exitUiStyle,
                            LocalToolbarBackdrop provides rearBackdrop,
                        ) {
                            GlassToolbarIconButton(
                                icon = MiuixIcons.Regular.ChevronForward,
                                description = tr("rear_screen_exit", "退出背屏播放器"),
                                backdrop = rearBackdrop,
                                collapsedFraction = 1f,
                                containerPadding = PaddingValues(0.dp),
                                buttonSize = exitButtonSize,
                                iconSize = (exitButtonSize * 0.46f).coerceIn(13.dp, 20.dp),
                                onClick = onExit,
                            )
                        }
                    }
                    if (amoledBlack) {
                        MiuixTheme(colors = darkColorScheme()) { exitContent() }
                    } else {
                        exitContent()
                    }
                }
            }
        }
    }
}

private data class RearSafeGeometry(
    val padding: PaddingValues,
    val topDp: Dp,
    val rightDp: Dp,
    val topRightCornerRadiusDp: Dp,
)

@Composable
private fun rememberRearSafeGeometry(): RearSafeGeometry {
    val view = LocalView.current
    val density = LocalDensity.current
    var insetsPx by remember { mutableStateOf(RearSafeInsetsPx(0, 0, 0, 0)) }
    var topRightCornerPx by remember { mutableStateOf(0) }

    DisposableEffect(view) {
        fun updateFromWindowInsets(windowInsets: WindowInsets?) {
            val cutout = windowInsets?.displayCutout
            val width = view.width.coerceAtLeast(1)
            val height = view.height.coerceAtLeast(1)
            val extra = with(density) { 10.dp.roundToPx() }
            topRightCornerPx = windowInsets
                ?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
                ?.radius
                ?.coerceAtLeast(0)
                ?: 0
            insetsPx = if (cutout != null) {
                RearScreenDevice.computeSafeInsetsPx(
                    screenWidthPx = width,
                    screenHeightPx = height,
                    safeLeft = cutout.safeInsetLeft,
                    safeTop = cutout.safeInsetTop,
                    safeRight = cutout.safeInsetRight,
                    safeBottom = cutout.safeInsetBottom,
                    boundingRects = cutout.boundingRects.map {
                        RearCutoutRect(it.left, it.top, it.right, it.bottom)
                    },
                    extraMarginPx = extra,
                )
            } else {
                RearSafeInsetsPx(extra, extra, extra, extra)
            }
        }

        updateFromWindowInsets(view.rootWindowInsets)
        val listener = androidx.core.view.OnApplyWindowInsetsListener { _, compatInsets ->
            updateFromWindowInsets(compatInsets.toWindowInsets())
            compatInsets
        }
        ViewCompat.setOnApplyWindowInsetsListener(view, listener)
        val layoutListener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateFromWindowInsets(view.rootWindowInsets)
        }
        view.addOnLayoutChangeListener(layoutListener)
        onDispose {
            ViewCompat.setOnApplyWindowInsetsListener(view, null)
            view.removeOnLayoutChangeListener(layoutListener)
        }
    }

    return with(density) {
        RearSafeGeometry(
            padding = PaddingValues(
                start = insetsPx.left.toDp(),
                top = insetsPx.top.toDp(),
                end = insetsPx.right.toDp(),
                bottom = insetsPx.bottom.toDp(),
            ),
            topDp = insetsPx.top.toDp(),
            rightDp = insetsPx.right.toDp(),
            topRightCornerRadiusDp = topRightCornerPx.toDp(),
        )
    }
}

@Composable
private fun RearEmptyState(ink: Color, subInk: Color) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "♫",
            fontSize = 22.sp,
            color = ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = tr("rear_screen_no_song", "当前暂无正在播放的歌曲"),
            color = ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = tr("rear_screen_no_song_hint", "请在主屏幕选择歌曲开始播放"),
            color = subInk,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val REAR_LYRIC_TAP_SEEK_DELAY_MS = 600L

@Composable
private fun RearEmbeddedLyricView(
    lyrics: List<LyricLine>,
    position: Long,
    playing: Boolean,
    showTranslation: Boolean,
    showPronunciation: Boolean,
    lyricScale: Float,
    lineSpacingMultiplier: Float,
    allowParentHorizontalSwipe: Boolean,
    keepScreenOn: Boolean,
    lyricsInteractive: Boolean,
    lyricTheme: btm.m.auraplayer.playback.LyricTheme,
    tokyoLandmark: btm.m.auraplayer.playback.TokyoSceneLandmark,
    ink: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnSeek by rememberUpdatedState(onSeek)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerLyricView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(AndroidColor.TRANSPARENT)
                this.tapSeekDelayMs = REAR_LYRIC_TAP_SEEK_DELAY_MS
                this.onSeek = { targetMs -> latestOnSeek(targetMs) }
                this.onLongPress = {}
            }
        },
        update = { view ->
            view.allowParentHorizontalSwipe = allowParentHorizontalSwipe
            view.keepScreenOn = keepScreenOn
            view.tapSeekDelayMs = REAR_LYRIC_TAP_SEEK_DELAY_MS
            if (!lyricsInteractive) {
                view.cancelPendingSeek()
            }
            view.onSeek = { targetMs -> latestOnSeek(targetMs) }
            view.lyricTheme(lyricTheme)
            view.tokyoLandmark(tokyoLandmark)
            view.inkColor(ink.toArgb())
            view.submit(
                lines = lyrics,
                translation = showTranslation,
                pronunciation = showPronunciation,
                textScale = lyricScale,
                lineSpacingMultiplier = lineSpacingMultiplier,
                horizontalPaddingDp = 0f,
            )
            view.position(position, playing)
        },
    )
}

@Composable
private fun RearCircleIconButton(
    icon: Int,
    label: String,
    tint: Color,
    containerColor: Color,
    touchSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(touchSize)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}

@Composable
private fun RearPillSlider(
    value: Float,
    activeColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    val onChangeState by rememberUpdatedState(onChange)
    val onFinishedState by rememberUpdatedState(onFinished)
    val clamped = value.coerceIn(0f, 1f)
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    onChangeState(fraction)
                    onFinishedState()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val fraction = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        onChangeState(fraction)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        onChangeState(fraction)
                    },
                    onDragEnd = { onFinishedState() },
                    onDragCancel = { onFinishedState() },
                )
            },
    ) {
        val trackHeight = 6.dp.toPx().coerceAtMost(size.height * 0.55f)
        val cy = size.height / 2f
        val radius = trackHeight / 2f
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, cy - radius),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(radius, radius),
        )
        val fillWidth = (size.width * clamped).coerceAtLeast(if (clamped > 0f) trackHeight else 0f)
        if (fillWidth > 0f) {
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(0f, cy - radius),
                size = Size(fillWidth, trackHeight),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
    }
}

@Composable
private fun RearVolumeBar(
    ink: Color,
    subInk: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val max = remember(manager) { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var value by remember {
        mutableFloatStateOf(manager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat())
    }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(manager) {
        while (true) {
            if (!dragging) value = manager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
            delay(300)
        }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ic_player_volume_muted),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            colorFilter = ColorFilter.tint(subInk),
        )
        Spacer(Modifier.width(4.dp))
        RearPillSlider(
            value = value,
            activeColor = ink.copy(alpha = 0.85f),
            trackColor = ink.copy(alpha = 0.22f),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            onChange = { next ->
                dragging = true
                value = next
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, (next * max).roundToInt(), 0)
            },
            onFinished = { dragging = false },
        )
        Spacer(Modifier.width(4.dp))
        Image(
            painter = painterResource(R.drawable.ic_player_volume_full),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            colorFilter = ColorFilter.tint(ink),
        )
    }
}
