// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import btm.m.auraplayer.ui.AuraMoreHorizontalIcon
import android.media.AudioManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.R
import btm.m.auraplayer.audio.usb.UsbExclusiveStatus
import btm.m.auraplayer.data.AppleMusicMotionCoverManager
import btm.m.auraplayer.lyrics.PlayerLyricView
import btm.m.auraplayer.lyrics.rememberSpatialGyroTilt
import btm.m.auraplayer.lyrics.scaleSpatialParallaxTilt
import btm.m.auraplayer.playback.lyricScaleMultiplier
import btm.m.auraplayer.spatialcover.SpatialCoverAssets
import btm.m.auraplayer.spatialcover.SpatialCoverManager
import btm.m.auraplayer.spatialcover.SpatialCoverRenderer
import btm.m.auraplayer.spatialcover.SpatialCoverResolveState
import btm.m.auraplayer.spatialcover.SpatialDepthModel
import btm.m.auraplayer.spatialcover.SpatialMattingModel
import btm.m.auraplayer.playback.StreamQuality
import btm.m.auraplayer.playback.NowPlayingConfig
import btm.m.auraplayer.playback.MaterialPlayerBackground
import btm.m.auraplayer.playback.PlayerTheme
import btm.m.auraplayer.playback.GlowProgressStyle
import btm.m.auraplayer.playback.CoverAnimation
import btm.m.auraplayer.playback.CoverTheme
import btm.m.auraplayer.ui.*
import btm.m.auraplayer.ui.GlassIconDropdownMenu
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.*
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.materialkolor.palettes.CorePalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.compose.LifecycleResumeEffect
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop as rememberMiuixBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop as miuixLayerBackdrop
import kotlin.math.*

private const val PlayerPaddingScale = 1.15f
private const val PlayerChromeRevealStart = .12f
internal const val PlayerIconScale = .85f

@Immutable
internal data class PlayerSurfaceStyle(
    val isDark: Boolean = true,
    val activeInk: Color = Color.White,
    val secondaryInk: Color = Color.White.copy(alpha = .85f),
    val glowAccent: Color? = null,
)

internal val LocalPlayerSurfaceStyle = staticCompositionLocalOf { PlayerSurfaceStyle() }

private fun GraphicsLayerScope.setPlayerLayerBlur(radius: Dp) {
    val pixels = radius.toPx()
    clip = pixels > 0f
    renderEffect = if (clip) BlurEffect(pixels, pixels, TileMode.Clamp) else null
}

/** Shared geometry for the floating navigation + search envelope and its mini player. */
internal object PlayerNavigationGeometry {
    val outerInset = 14.dp
    val barHeight = (72f * .82f * .95f).dp
    val bottomSpacing = (8f * .82f).dp
    val miniGap = 12.dp
    val miniHeight = 60.dp
    val searchGap = 8.dp
    fun bodyWidth(width: Dp) = width - outerInset * 2 - barHeight - searchGap
}

@Composable
internal fun PlayerHost(model: AuraViewModel, backdrop: Backdrop, miniVisible: Boolean,
                        secondaryProgress: Float = 0f,
                        chromeExitProgress: Float = 0f,
                        restoreLyricsRequest: Int = 0,
                        onExpansionProgress: (Float) -> Unit,
                        open: Boolean, onOpenChange: (Boolean) -> Unit, openPage: (String) -> Unit) {
    val liveSong = model.current
    var retainedSong by remember { mutableStateOf(liveSong) }
    if (liveSong != null) retainedSong = liveSong
    val song = liveSong ?: retainedSong ?: return
    val appearance by model.app.nowPlaying.state.collectAsState()
    val context = LocalContext.current
    val mixVisual by model.app.auraMix.visual.collectAsState()
    var foreground by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        foreground = true
        onPauseOrDispose { foreground = false }
    }
    val scope = rememberCoroutineScope()
    val expansion = remember { Animatable(if (open) 1f else 0f) }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    val compactOpen = lyricsOpen || queueOpen
    val lyricProgress = remember { Animatable(if (compactOpen) 1f else 0f) }
    val queueBlend = remember { Animatable(if (queueOpen) 1f else 0f) }
    var gesturing by remember { mutableStateOf(false) }
    var playerSwipeGesturing by remember { mutableStateOf(false) }
    var backExiting by remember { mutableStateOf(false) }
    var swipeDistance by remember { mutableFloatStateOf(0f) }
    var settlingSwipe by remember { mutableStateOf(false) }
    var lyricMenuOpen by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    var actionsBusy by remember { mutableStateOf(false) }
    var shareOpening by remember { mutableStateOf(false) }
    val lyricShareTransition = LocalLyricShareTransition.current
    val miniSwipeOffset = remember { Animatable(0f) }
    val miniDrag = remember { MiniPlayerDragState() }
    val onLyricsToggle = remember { { lyricsOpen = !lyricsOpen; queueOpen = false } }
    val onQueueToggle = remember { { queueOpen = !queueOpen; lyricsOpen = false } }
    val onMoreActions = remember { { actionsOpen = true } }
    LaunchedEffect(open, gesturing, shareOpening) {
        if (!gesturing && !shareOpening) expansion.animateTo(if (open) 1f else 0f, spring(.88f, 300f, visibilityThreshold = .0005f))
    }
    LaunchedEffect(compactOpen, shareOpening) {
        if (!gesturing && !shareOpening) lyricProgress.animateTo(if (compactOpen) 1f else 0f, spring(.86f, 280f, visibilityThreshold = .0005f))
    }
    LaunchedEffect(queueOpen, lyricsOpen) {
        queueBlend.animateTo(if (queueOpen) 1f else 0f, tween(280, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(restoreLyricsRequest) {
        if (restoreLyricsRequest > 0) {
            lyricsOpen = true
            queueOpen = false
        }
    }
    fun back() {
        if (compactOpen) { lyricsOpen = false; queueOpen = false }
        else onOpenChange(false)
    }
    BackHandler(open && !model.settings.value.predictive && !lyricMenuOpen && !actionsBusy && !shareOpening) { back() }
    PredictiveBackHandler(enabled = open && model.settings.value.predictive && !lyricMenuOpen && !actionsBusy && !shareOpening && swipeDistance == 0f && !settlingSwipe) { events ->
        val secondary = compactOpen
        val target = if (secondary) lyricProgress else expansion
        val start = target.value
        gesturing = true
        if (!secondary) backExiting = true
        try {
            events.collect { event -> target.snapTo(start * (1f - event.progress.coerceIn(0f, model.settings.value.maxBackProgress))) }
            target.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
            if (secondary) { lyricsOpen = false; queueOpen = false } else onOpenChange(false)
        } catch (cancel: CancellationException) {
            scope.launch { target.animateTo(1f, spring(.88f, 320f)) }
        } finally { gesturing = false; backExiting = false }
    }
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val miniColor = if (dark) Color.White else Color.Black
    val style = model.settings.value.navigation
    val colorStyle = LocalAuraColorStyle.current
    val playerBackdrop = rememberLayerBackdrop()
    val miuixBackdrop = rememberMiuixBackdrop()
    val isMaterialTheme = appearance.theme == PlayerTheme.MATERIAL
    val isOneTheme = appearance.theme == PlayerTheme.ONE
    val lyricThemeActiveOnScreen = appearance.lyricTheme != btm.m.auraplayer.playback.LyricTheme.BLUR && lyricsOpen && !queueOpen
    val effectivePlayerDark = appearance.resolveDarkTheme(dark) || lyricThemeActiveOnScreen
    val coverSeed = rememberPlayerArtworkColorSeed(song.artwork, useOklab = colorStyle.isOklab)
    val coverPalette = remember(coverSeed) { CorePalette.of(coverSeed) }
    val oklabAmbient = remember(colorStyle, coverSeed, effectivePlayerDark) {
        if (colorStyle.isOklab) {
            OklabThemeEngine.generatePlayerAmbient(coverSeed, effectivePlayerDark, colorStyle.palette)
        } else null
    }
    val expressive = rememberPlayerExpressiveColors(
        seed = coverSeed,
        isDark = effectivePlayerDark,
        useOklab = colorStyle.isOklab,
        paletteIndex = colorStyle.palette,
    )
    val activeExpressive = if (isMaterialTheme) expressive else null
    val oneUiColors = rememberPlayerOneUiColors(
        seed = coverSeed,
        isDark = effectivePlayerDark,
        useOklab = colorStyle.isOklab,
        paletteIndex = colorStyle.palette,
    )
    val activeOneUi = if (isOneTheme) oneUiColors else null
    val resolvedMenuTint = oklabAmbient?.menuTint ?: Color(coverPalette.a2.tone(if (effectivePlayerDark) 20 else 95))
    val baseActiveInk = oklabAmbient?.activeInk ?: if (effectivePlayerDark) Color.White else Color(coverPalette.n1.tone(10))
    val baseSecondaryInk = oklabAmbient?.secondaryInk ?: if (effectivePlayerDark) Color.White.copy(alpha = .85f) else Color(coverPalette.n1.tone(32)).copy(alpha = .88f)
    val surfaceStyle = PlayerSurfaceStyle(
        isDark = effectivePlayerDark,
        activeInk = activeExpressive?.activeInk ?: activeOneUi?.text ?: baseActiveInk,
        secondaryInk = activeExpressive?.secondaryInk ?: activeOneUi?.subText ?: baseSecondaryInk,
        glowAccent = oklabAmbient?.glowAccent,
    )
    val shareActive = lyricShareTransition?.active == true
    val shareProgress = if (shareActive) lyricShareTransition.progress.coerceIn(0f, 1f) else 0f
    val shareBgAlpha = if (shareActive) 1f - lyricShareSmoothStep(0f, .86f, shareProgress) else 1f
    val shareBgBlur = if (shareActive) (shareProgress * 28f).dp else 0.dp
    var amMotionFile by remember(song.key, appearance.appleMusicAnimatedCover) {
        mutableStateOf(
            if (appearance.appleMusicAnimatedCover) {
                AppleMusicMotionCoverManager.peekCachedFile(context, song)
            } else {
                null
            },
        )
    }
    LaunchedEffect(
        song.key,
        song.title,
        song.artist,
        song.album,
        song.albumArtist,
        song.duration,
        appearance.appleMusicAnimatedCover,
    ) {
        if (!appearance.appleMusicAnimatedCover || song.title.isBlank()) {
            amMotionFile = null
            return@LaunchedEffect
        }
        val cached = AppleMusicMotionCoverManager.peekCachedFile(context, song)
        if (cached != null) {
            amMotionFile = cached
            return@LaunchedEffect
        }
        if (btm.m.auraplayer.playback.PlaybackPreferences(context).wifiOnlyMotionCover &&
            btm.m.auraplayer.data.NetworkTrafficPolicy.isMetered(context)
        ) {
            amMotionFile = null
            return@LaunchedEffect
        }
        amMotionFile = null
        kotlinx.coroutines.delay(350L)
        amMotionFile = AppleMusicMotionCoverManager.resolve(
            context = context,
            song = song,
            activePlayingPath = amMotionFile?.absolutePath,
        )
    }
    val amMotionPath = if (appearance.appleMusicAnimatedCover && mixVisual == null) {
        amMotionFile?.takeIf { it.isFile && it.length() > 0L }?.absolutePath
    } else {
        null
    }
    val spatialCoverEligible = shouldShowSpatialCoverButton(
        spatialCoverEnabled = appearance.spatialCover,
        appleMusicAnimatedCoverEnabled = appearance.appleMusicAnimatedCover,
        motionVideoPath = amMotionPath,
    )
    val selectedDepthModel = SpatialDepthModel.byId(appearance.spatialCoverDepthModel)
    val selectedMattingModel = SpatialMattingModel.byId(appearance.spatialCoverMattingModel)
    var spatialCoverRequested by remember(song.key, spatialCoverEligible, selectedDepthModel, selectedMattingModel) {
        mutableStateOf(false)
    }
    var spatialCoverState by remember(song.key, spatialCoverEligible, selectedDepthModel, selectedMattingModel) {
        mutableStateOf<SpatialCoverResolveState>(SpatialCoverResolveState.Idle)
    }
    LaunchedEffect(song.artwork, spatialCoverEligible, selectedDepthModel, selectedMattingModel, spatialCoverRequested) {
        if (!spatialCoverEligible || !spatialCoverRequested || song.artwork.isBlank()) {
            spatialCoverState = SpatialCoverResolveState.Idle
            return@LaunchedEffect
        }
        SpatialCoverManager.resolve(
            context = context,
            artworkUri = song.artwork,
            depthModel = selectedDepthModel,
            mattingModel = selectedMattingModel,
            songTitle = song.title,
            songArtist = song.artist,
            songAlbum = song.album,
            songAlbumArtist = song.albumArtist,
            onState = { state ->
                spatialCoverState = state
                when (state) {
                    is SpatialCoverResolveState.MissingModels -> {
                        spatialCoverRequested = false
                        Toast.makeText(
                            context,
                            tr("spatial_models_missing_toast", "请先下载 ONNX Runtime 运行库与空间模型"),
                            Toast.LENGTH_SHORT,
                        ).show()
                        onOpenChange(false)
                        openPage("spatial_cover_downloads")
                    }
                    is SpatialCoverResolveState.Failed -> {
                        spatialCoverRequested = false
                        Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                    }
                    else -> Unit
                }
            },
        )
    }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val hostActiveCoverTheme = resolveActiveCoverTheme(
        appearance.coverTheme,
        isLandscapePlayerLayout(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp),
        appearance.landscapeAutoCoverflow,
    )
    val stageGyroParallaxActive = open && foreground && !compactOpen && !shareActive && (
        (hostActiveCoverTheme == CoverTheme.COVERFLOW && appearance.coverflowParallax) ||
            (hostActiveCoverTheme == CoverTheme.TILED && appearance.tiledParallax)
        )
    val spatialLyricsActive = appearance.spatialLyrics && open && foreground && lyricsOpen && !queueOpen && !shareActive
    val spatialCoverActive = spatialCoverEligible && spatialCoverRequested &&
        spatialCoverState is SpatialCoverResolveState.Ready && open && foreground && !compactOpen && !shareActive
    val spatialActive = spatialLyricsActive || spatialCoverActive || stageGyroParallaxActive
    val spatialTilt = rememberSpatialGyroTilt(enabled = spatialActive)
    val canPrepareBackground = foreground && miniVisible && !open
    var prepareBackground by remember { mutableStateOf(false) }
    LaunchedEffect(canPrepareBackground) {
        if (canPrepareBackground && !prepareBackground) {
            delay(300)
            prepareBackground = true
        }
    }
    val hostAlpha by animateFloatAsState(if (miniVisible || open) 1f else 0f, tween(280, easing = FastOutSlowInEasing), label = "playerHostVisibility")
    val chromeExitDistance = PlayerNavigationGeometry.barHeight +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer {
        val exit = chromeExitProgress * (1f - expansion.value)
        alpha = hostAlpha * (1f - exit)
        translationY = (1f - hostAlpha) * 18.dp.toPx() + exit * chromeExitDistance.toPx()
        val radius = (exit * 10f).dp.toPx()
        renderEffect = if (radius > .01f) BlurEffect(radius, radius, TileMode.Clamp) else null
    }) {
        val screenWidth = maxWidth; val screenHeight = maxHeight
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val metrics = PlayerNavigationGeometry
        val screenCorner = rememberBottomScreenCornerRadius(0.dp)
        val density = LocalDensity.current
        val densityScale = density.density
        val screenWidthPx = with(density) { screenWidth.toPx() }
        val miniThresholdPx = with(density) { 42.dp.toPx() }
        val dock = secondaryProgress.coerceIn(0f, 1f)
        val miniHeight = lerp(metrics.miniHeight, metrics.barHeight, dock)
        val miniWidth = lerp(screenWidth - metrics.outerInset * 2, metrics.bodyWidth(screenWidth), dock)
        val rootMiniY = screenHeight - nav - metrics.bottomSpacing - metrics.barHeight - metrics.miniGap - metrics.miniHeight
        val dockMiniY = screenHeight - nav - metrics.bottomSpacing - metrics.barHeight
        val miniY = lerp(rootMiniY, dockMiniY, dock) - model.settings.value.navigationOffsetDp.dp
        val swipeRange = with(LocalDensity.current) { miniY.toPx().coerceAtLeast(1f) }
        val morphProgress = remember(swipeRange) {
            derivedStateOf {
                val gestureExpansion = if (miniDrag.axis == MiniPlayerDragAxis.EXPAND) miniDrag.progress else expansion.value
                (gestureExpansion - swipeDistance / swipeRange).coerceIn(0f, 1f)
            }
        }
        val e = morphProgress.value
        val backgroundVisible = e > 0f
        val backgroundModifier = remember(morphProgress, shareBgAlpha, shareBgBlur, playerBackdrop, miuixBackdrop) {
            Modifier.fillMaxSize().graphicsLayer { alpha = morphProgress.value * shareBgAlpha }
                .blur(shareBgBlur).layerBackdrop(playerBackdrop).miuixLayerBackdrop(miuixBackdrop)
        }
        // Publish the actual morph, including drag/predictive back and cancelled gestures.
        // The shell blurs its own page layers so shared artwork/metadata stay sharp.
        SideEffect { onExpansionProgress(e * (1f - shareProgress)) }
        fun settleSwipe(velocity: Float, cancelled: Boolean) {
            val releaseExpansion = (expansion.value - swipeDistance / swipeRange).coerceIn(0f, 1f)
            val dismiss = !cancelled && PlayerDismissGesture.shouldDismiss(swipeDistance / densityScale,
                velocity / densityScale, screenHeight.value)
            settlingSwipe = true
            scope.launch {
                try {
                    expansion.snapTo(releaseExpansion)
                    swipeDistance = 0f
                    expansion.animateTo(if (dismiss) 0f else 1f, spring(.88f, 320f, visibilityThreshold = .0005f))
                    if (dismiss) onOpenChange(false)
                } finally { settlingSwipe = false; gesturing = false; playerSwipeGesturing = false }
            }
        }
        val playerBaseColor = when {
            isMaterialTheme && appearance.materialBackground == MaterialPlayerBackground.MATERIAL && activeExpressive != null -> activeExpressive.surface
            isOneTheme && activeOneUi != null -> activeOneUi.background
            else -> Color(coverPalette.a2.tone(14))
        }
        val settledFullScreen = open && !playerSwipeGesturing && !backExiting && !settlingSwipe &&
            !expansion.isRunning && miniDrag.axis != MiniPlayerDragAxis.EXPAND && e >= .999f
        val restingCorner by animateDpAsState(if (settledFullScreen) 18.dp else screenCorner,
            animationSpec = if (settledFullScreen) tween(180, easing = FastOutSlowInEasing) else snap(),
            label = "playerRestingCorner")
        val radius = if (settledFullScreen) restingCorner else playerMorphCornerRadius(e, miniHeight / 2, screenCorner)
        val shape = RoundedCornerShape(radius)
        Box(Modifier.offset(metrics.outerInset * (1f - e), miniY * (1f - e))
            .size(lerp(miniWidth, screenWidth, e), lerp(miniHeight, screenHeight, e))
            .then(when {
                shareActive && e > .995f -> Modifier
                settledFullScreen -> Modifier.background(playerBaseColor, shape)
                else -> Modifier.miniPlayerMaterial(backdrop, style, shape, e, playerBaseColor)
            })
            .clip(shape)
            .playerMiniDragGesture(enabled = !open && !settlingSwipe && !shareOpening && e < .34f,
                allowTrackSwipe = appearance.swipeTracks,
                onStart = { axis ->
                    miniDrag.begin(axis, expansion.value)
                    if (axis == MiniPlayerDragAxis.EXPAND) gesturing = true
                },
                onDelta = { miniDrag.update(it, swipeRange, screenWidthPx) },
                onEnd = { released ->
                    if (miniDrag.axis == MiniPlayerDragAxis.EXPAND) {
                        val releaseProgress = miniDrag.progress
                        settlingSwipe = true
                        scope.launch {
                            try {
                                // Transfer the exact visual progress before clearing the gesture.
                                expansion.snapTo(releaseProgress)
                                miniDrag.reset()
                                if (released) onOpenChange(true)
                                expansion.animateTo(if (released || open) 1f else 0f, spring(.88f, 320f))
                            } finally {
                                miniDrag.reset()
                                settlingSwipe = false
                                gesturing = false
                            }
                        }
                    } else if (miniDrag.axis == MiniPlayerDragAxis.TRACK) {
                        val offset = miniSwipeOffset.value + miniDrag.horizontalOffset
                        val changeTrack = released && abs(offset) > miniThresholdPx
                        val direction = if (offset < 0f) 1 else -1
                        scope.launch {
                            try {
                                miniSwipeOffset.snapTo(offset)
                                miniDrag.reset()
                                miniSwipeOffset.animateTo(if (changeTrack) direction * screenWidthPx else 0f,
                                    tween(180, easing = FastOutSlowInEasing))
                                if (changeTrack) { if (direction > 0) model.next() else model.previous() }
                            } finally {
                                miniDrag.reset()
                                miniSwipeOffset.snapTo(0f)
                            }
                        }
                    }
                })) {
            // Full content keeps its final measurement during the morph; only the container changes.
            Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(screenWidth, screenHeight)) {
                Box(Modifier.fillMaxSize()) {
                    PlayerThemeBackground(song.artwork, coverPalette, appearance, lyricProgress.value.coerceIn(0f, 1f),
                        animate = open && foreground && !gesturing && !shareActive,
                        queueBlend = queueBlend.value.coerceIn(0f, 1f),
                        spatialTilt = if (appearance.spatialLyrics && lyricProgress.value > 0f) {
                            scaleSpatialParallaxTilt(spatialTilt.value, appearance.spatialLyricsSensitivity) * lyricProgress.value.coerceIn(0f, 1f)
                        } else {
                            Offset.Zero
                        },
                        expressive = activeExpressive,
                        oneUi = activeOneUi,
                        modifier = backgroundModifier,
                        visible = backgroundVisible,
                        prepare = prepareBackground && foreground && miniVisible,
                        isDark = effectivePlayerDark,
                        useOklab = colorStyle.isOklab,
                        oklabPaletteIndex = colorStyle.palette,
                        seed = coverSeed)
                    CompositionLocalProvider(LocalPlayerSurfaceStyle provides surfaceStyle) {
                    FullPlayer(model, lyricProgress.value, expansion = morphProgress, miniColor = miniColor, miniSwipe = miniSwipeOffset.value + miniDrag.horizontalOffset,
                        miniWidth = miniWidth, miniHeight = miniHeight,
                        miniControlClearance = 110.dp - 50.dp * dock,
                        appearance = appearance, active = open && foreground && !shareActive, controlsUnobscured = !actionsBusy && !lyricMenuOpen && !shareActive,
                        spatialTilt = spatialTilt,
                        mixVisual = mixVisual,
                        shareTransition = lyricShareTransition,
                        onExpand = { onOpenChange(true) }, onClose = { onOpenChange(false) },
                        swipeEnabled = open && !settlingSwipe && !lyricMenuOpen && !actionsBusy && !shareOpening,
                        onSwipeStart = { gesturing = true; playerSwipeGesturing = true; scope.launch { expansion.stop() } },
                        onSwipeDelta = { swipeDistance = (swipeDistance + it).coerceIn(0f, swipeRange) },
                        onSwipeRelease = { settleSwipe(it, false) }, onSwipeCancel = { settleSwipe(0f, true) },
                        onLyrics = onLyricsToggle, queueOpen = queueOpen,
                        lyricsOpen = lyricsOpen, queueBlend = queueBlend.value, backdrop = playerBackdrop, menuTint = resolvedMenuTint,
                        coverPalette = coverPalette,
                        expressive = activeExpressive,
                        oneUi = activeOneUi,
                        onLyricMenuExpanded = { lyricMenuOpen = it },
                        miuixBackdrop = miuixBackdrop, onMore = onMoreActions,
                        spatialCoverRequested = spatialCoverRequested,
                        spatialCoverState = spatialCoverState,
                        onToggleSpatialCover = { spatialCoverRequested = !spatialCoverRequested },
                        motionVideoPath = amMotionPath,
                        motionVideoAllowed = foreground && !shareActive,
                        prepareStage = prepareBackground && foreground && miniVisible,
                        onQueue = onQueueToggle,
                        openSharePage = { index, rowBounds, viewportBounds ->
                            if (!shareOpening) {
                                shareOpening = true
                                model.prepareLyricShare(index)
                                val transition = lyricShareTransition
                                transition?.sourceRowBounds = rowBounds
                                transition?.sourceViewportBounds = viewportBounds
                                transition?.targetCardBounds = null
                                transition?.targetRowBounds = null
                                transition?.targetReady = false
                                transition?.progress = 0f
                                transition?.active = true
                                openPage("lyricShare")
                                scope.launch {
                                    try {
                                        if (transition != null) {
                                            withTimeoutOrNull(180L) {
                                                snapshotFlow { transition.targetReady }.first { it }
                                            }
                                            val anim = Animatable(0f)
                                            anim.animateTo(
                                                targetValue = 1f,
                                                animationSpec = tween(420, easing = FastOutSlowInEasing),
                                            ) {
                                                transition.progress = value
                                            }
                                            transition.progress = 1f
                                        } else {
                                            lyricProgress.animateTo(0f, tween(190, easing = FastOutSlowInEasing))
                                            withFrameNanos { }
                                        }
                                        expansion.snapTo(0f)
                                        lyricProgress.snapTo(0f)
                                        lyricsOpen = false
                                        queueOpen = false
                                        onOpenChange(false)
                                    } finally {
                                        transition?.active = false
                                        transition?.sourceRowBounds = null
                                        transition?.sourceViewportBounds = null
                                        transition?.targetCardBounds = null
                                        transition?.targetRowBounds = null
                                        shareOpening = false
                                    }
                                }
                            }
                        })
                    }
                }
            }
            val miniAlpha = (1f - e / .34f).coerceIn(0f, 1f)
            if (miniAlpha > 0f) {
                Box(Modifier.matchParentSize()
                    .clickable(enabled = !open && !shareOpening, interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = { onOpenChange(true) }))
            }
            if (miniAlpha > 0f) Box(Modifier.fillMaxWidth().height(miniHeight)
                .graphicsLayer {
                    val progress = morphProgress.value
                    alpha = (1f - progress / .34f).coerceIn(0f, 1f)
                    setPlayerLayerBlur((progress * 24f).dp)
                }
                .padding(end = 10.dp)) {
                // Share the page morph's progress so interrupted/predictive back reverses both
                // the play button's movement and the next button's disappearance in place.
                Box(Modifier.align(Alignment.CenterEnd).offset(x = (-50.dp) * (1f - dock))) {
                    PlayerButton(if (model.playbackRequested) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                        if (model.playbackRequested) tr("pause", "暂停") else tr("play", "播放"), miniColor,
                        38.dp * .8f * .92f, 40.dp, model::togglePlayback)
                }
                if (dock < .999f) Box(Modifier.align(Alignment.CenterEnd).graphicsLayer {
                    alpha = 1f - dock
                    scaleX = 1f - dock
                    scaleY = 1f - dock
                }.blur((dock * 12f).dp)) {
                    PlayerButton(R.drawable.ic_player_next, tr("next_track", "下一首"), miniColor,
                        38.dp * .8f * .92f, 40.dp, model::next)
                }
            }
        }
    }
    if (activeOneUi != null) {
        val oneSheetPalette = if (colorStyle.isOklab) {
            val tones = remember(coverSeed, activeOneUi.dark, colorStyle.palette) {
                OklabThemeEngine.generateScheme(coverSeed, activeOneUi.dark, colorStyle.palette).toOneUiMonetTones()
            }
            remember(context, activeOneUi.dark, tones) {
                dev.pascal.oneuikit.OneUiPalette(context = context, forceDark = activeOneUi.dark, useMonet = true, overrideTones = tones)
            }
        } else {
            rememberOneUiPalette(isDark = activeOneUi.dark, useMonet = true, customSeed = coverSeed)
        }
        CompositionLocalProvider(
            LocalDialogBackdrop provides playerBackdrop,
            LocalAppUiStyle provides AppUiStyle.ONE,
            LocalAuraOneUiPalette provides oneSheetPalette,
        ) {
            PlayerActionsSheet(model, actionsOpen, playerBackdrop,
                dismiss = { actionsOpen = false }, openPage = openPage, closePlayer = { onOpenChange(false) },
                onBusy = { actionsBusy = it }, expressive = activeExpressive)
        }
    } else {
        CompositionLocalProvider(LocalDialogBackdrop provides playerBackdrop) {
            PlayerActionsSheet(model, actionsOpen, playerBackdrop,
                dismiss = { actionsOpen = false }, openPage = openPage, closePlayer = { onOpenChange(false) },
                onBusy = { actionsBusy = it }, expressive = activeExpressive)
        }
    }
}

@Composable
private fun FullPlayer(model: AuraViewModel, progress: Float, expansion: State<Float>,
                       appearance: NowPlayingConfig, active: Boolean, controlsUnobscured: Boolean,
                       spatialTilt: State<Offset>,
                       mixVisual: btm.m.auraplayer.auramix.AuraMixVisualTransition?,
                       shareTransition: LyricShareTransitionState? = null,
                       miniColor: Color, miniSwipe: Float, miniWidth: Dp, miniHeight: Dp,
                       miniControlClearance: Dp, onExpand: () -> Unit,
                       swipeEnabled: Boolean, onSwipeStart: () -> Unit, onSwipeDelta: (Float) -> Unit,
                       onSwipeRelease: (Float) -> Unit, onSwipeCancel: () -> Unit,
                       onClose: () -> Unit, onLyrics: () -> Unit, queueOpen: Boolean,
                       lyricsOpen: Boolean, queueBlend: Float, backdrop: Backdrop, menuTint: Color,
                       coverPalette: com.materialkolor.palettes.CorePalette,
                       onLyricMenuExpanded: (Boolean) -> Unit,
                       miuixBackdrop: top.yukonga.miuix.kmp.blur.Backdrop, onMore: () -> Unit,
                       spatialCoverRequested: Boolean,
                       spatialCoverState: SpatialCoverResolveState,
                       onToggleSpatialCover: () -> Unit,
                       motionVideoPath: String? = null,
                       motionVideoAllowed: Boolean = false,
                       prepareStage: Boolean = false,
                       onQueue: () -> Unit, openSharePage: (Int, Rect?, Rect?) -> Unit,
                       expressive: PlayerExpressiveColors? = null,
                       oneUi: PlayerOneUiColors? = null) {
    val song = model.current ?: return
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val isMultiWindow = activity?.isInMultiWindowMode == true
    val surfaceStyle = LocalPlayerSurfaceStyle.current
    val activeInk = expressive?.activeInk ?: oneUi?.text ?: surfaceStyle.activeInk
    val secondaryInk = expressive?.secondaryInk ?: oneUi?.subText ?: surfaceStyle.secondaryInk
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val p = progress.coerceIn(0f, 1f)
    val collapsed by remember(expansion) { derivedStateOf { expansion.value == 0f } }
    val chromeVisible by remember(expansion) { derivedStateOf { expansion.value > PlayerChromeRevealStart } }
    val stageVisible by remember(expansion) { derivedStateOf { expansion.value > .05f } }
    val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val effectiveStatus = maxOf(status, safeDrawing.calculateTopPadding(), if (isMultiWindow) 24.dp else 0.dp)
    val effectiveLeftInset = safeDrawing.calculateLeftPadding(layoutDirection)
    val effectiveRightInset = safeDrawing.calculateRightPadding(layoutDirection)
    val effectiveBottomInset = maxOf(nav, safeDrawing.calculateBottomPadding())
    val headerBottom = with(density) { (effectiveStatus + 48.dp * PlayerPaddingScale + 84.dp).toPx() }
    var lastInteraction by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    var touching by remember { mutableStateOf(false) }
    var touchPoint by remember { mutableStateOf(Offset.Zero) }
    var immersive by remember { mutableStateOf(false) }
    var qualityMenuOpen by remember { mutableStateOf(false) }
    val immersionEligible = active && lyricsOpen && !queueOpen && appearance.immersiveLyrics && controlsUnobscured && !qualityMenuOpen && p > .999f
    LaunchedEffect(immersionEligible, appearance.immersiveSeconds, lastInteraction, touching) {
        immersive = false
        if (immersionEligible && !touching) {
            // A 0 s timeout hides immediately on entry, while a wake touch remains usable.
            val waitMs = if (lastInteraction == 0L) appearance.immersiveSeconds * 1000L else maxOf(appearance.immersiveSeconds * 1000L, 700L)
            delay(waitMs)
            immersive = true
        }
    }
    LaunchedEffect(lyricsOpen, active) { lastInteraction = if (lyricsOpen && active) 0L else android.os.SystemClock.uptimeMillis() }
    val controlsVisibility by animateFloatAsState(if (immersive) 0f else 1f, tween(280, easing = FastOutSlowInEasing), label = "immersiveControls")
    val shareActive = shareTransition?.active == true
    val shareP = if (shareActive) shareTransition.progress.coerceIn(0f, 1f) else 0f
    val shareChromeAlpha = if (shareActive) 1f - lyricShareSmoothStep(0f, .82f, shareP) else 1f
    val shareChromeBlur = if (shareActive) (shareP * 24f).dp else 0.dp
    BoxWithConstraints(Modifier.fillMaxSize()
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    touching = event.changes.any { it.pressed }
                    event.changes.firstOrNull { it.pressed }?.let { touchPoint = it.position }
                    lastInteraction = android.os.SystemClock.uptimeMillis()
                }
            }
        }
        .playerDismissGesture(swipeEnabled, p > .5f, headerBottom, onSwipeStart, onSwipeDelta, onSwipeRelease, onSwipeCancel)
        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() },
            enabled = collapsed, onClick = onExpand)) {
        val m = rememberFullPlayerMetrics(
            w = maxWidth,
            h = maxHeight,
            density = density,
            isMultiWindow = isMultiWindow,
            appearance = appearance,
            p = p,
            expansion = expansion,
            controlsVisibility = controlsVisibility,
            effectiveLeftInset = effectiveLeftInset,
            effectiveStatus = effectiveStatus,
            effectiveRightInset = effectiveRightInset,
            effectiveBottomInset = effectiveBottomInset,
            miniWidth = miniWidth,
            miniHeight = miniHeight,
            miniSwipe = miniSwipe,
            miniControlClearance = miniControlClearance,
            queueCount = model.queue.size,
            queueBlend = queueBlend,
            motionVideoPath = motionVideoPath,
            spatialTilt = if (spatialCoverRequested) spatialTilt.value else Offset.Zero,
            spatialCoverRequested = spatialCoverRequested,
            spatialCoverState = spatialCoverState,
            mixVisual = mixVisual,
            shareTransition = shareTransition,
            activeInk = activeInk,
            secondaryInk = secondaryInk,
            expressive = expressive,
            oneUi = oneUi,
            coverPalette = coverPalette,
            sharedClick = { if (expansion.value < .5f) onExpand() else if (queueOpen) onQueue() else onLyrics() },
        )
        var textHeight by remember { mutableIntStateOf(0) }
        var tiledWallDragging by remember { mutableStateOf(false) }
        val onQualityMenuExpandedCb = remember { { expanded: Boolean -> qualityMenuOpen = expanded } }
        val onDragActiveChangeCb = remember { { dragging: Boolean -> tiledWallDragging = dragging } }
        val tiledChromeVis = remember { Animatable(1f) }
        LaunchedEffect(m.activeCoverTheme, active, tiledWallDragging, p < 0.05f) {
            if (m.activeCoverTheme != CoverTheme.TILED || !active) tiledWallDragging = false
            val target = if (m.activeCoverTheme == CoverTheme.TILED && active && tiledWallDragging && p < 0.05f) 0f else 1f
            tiledChromeVis.animateTo(target, tween(180))
        }

        if (m.glassCardRect != null && chromeVisible) {
            FullPlayerGlassCardLayer(
                m = m,
                glassCardRect = m.glassCardRect,
                touchPoint = touchPoint,
                touching = touching,
                appearance = appearance,
                backdrop = backdrop,
                miuixBackdrop = miuixBackdrop,
                menuTint = menuTint,
                active = active,
                model = model,
            )
        }

        FullPlayerArtworkLayer(
            m = m,
            song = song,
            appearance = appearance,
            coverPalette = coverPalette,
            expressive = expressive,
            oneUi = oneUi,
            active = active,
            mixVisual = mixVisual,
            spatialCoverState = spatialCoverState,
            motionVideoPath = motionVideoPath,
            motionVideoAllowed = motionVideoAllowed,
            model = model,
        )

        if (m.coverflowActive && (stageVisible || prepareStage)) {
            FullPlayerCoverThemeStageLayer(
                m = m,
                model = model,
                song = song,
                appearance = appearance,
                coverPalette = coverPalette,
                expressive = expressive,
                oneUi = oneUi,
                backdrop = backdrop,
                miuixBackdrop = miuixBackdrop,
                menuTint = menuTint,
                mixVisual = mixVisual,
                spatialCoverState = spatialCoverState,
                spatialTilt = spatialTilt,
                motionVideoPath = motionVideoPath,
                motionVideoAllowed = motionVideoAllowed,
                active = active,
                stageVisible = stageVisible,
                lyricsOpen = lyricsOpen,
                queueOpen = queueOpen,
                onLyrics = onLyrics,
                onQueue = onQueue,
                onQualityMenuExpanded = onQualityMenuExpandedCb,
                onDragActiveChange = onDragActiveChangeCb,
            )
        }

        if (chromeVisible) {
            FullPlayerChromeAndLyricsLayer(
                m = m,
                model = model,
                song = song,
                appearance = appearance,
                coverPalette = coverPalette,
                expressive = expressive,
                oneUi = oneUi,
                backdrop = backdrop,
                active = active,
                lyricsOpen = lyricsOpen,
                queueOpen = queueOpen,
                queueBlend = queueBlend,
                immersive = immersive,
                isMultiWindow = isMultiWindow,
                tiledChromeAlpha = tiledChromeVis.value,
                textHeight = textHeight,
                spatialTilt = if (appearance.spatialLyrics && p > 0f) spatialTilt.value else Offset.Zero,
                shareTransition = shareTransition,
                onClose = onClose,
                onLyrics = onLyrics,
                onQueue = onQueue,
                onQualityMenuExpanded = { qualityMenuOpen = it },
                openSharePage = openSharePage,
            )
        }

        FullPlayerMetadataAndActionsLayer(
            m = m,
            model = model,
            song = song,
            appearance = appearance,
            expressive = expressive,
            oneUi = oneUi,
            miuixBackdrop = miuixBackdrop,
            menuTint = menuTint,
            miniColor = miniColor,
            mixVisual = mixVisual,
            spatialCoverRequested = spatialCoverRequested,
            spatialCoverState = spatialCoverState,
            tiledChromeAlpha = tiledChromeVis.value,
            tiledWallDragging = tiledWallDragging,
            textHeight = textHeight,
            onTextHeightChange = { textHeight = it },
            queueBlend = queueBlend,
            onToggleSpatialCover = onToggleSpatialCover,
            onMore = onMore,
            onLyricMenuExpanded = onLyricMenuExpanded,
        )
    }
}

private class FullPlayerMetrics(
    val w: Dp,
    val h: Dp,
    val density: Density,
    val fullscreen: Boolean,
    val landscape: Boolean,
    val activeCoverTheme: CoverTheme,
    val coverflowActive: Boolean,
    val squareCardFullscreen: Boolean,
    val usesStageDock: Boolean,
    val compactWindow: Boolean,
    val compactScale: Float,
    val portraitColX: Dp,
    val portraitColW: Dp,
    val landscapeGeom: LandscapePlayerGeometry?,
    val portraitNav: Dp,
    val fullCard: FullscreenPlayerCard,
    val normalMargin: Dp,
    val margin: Dp,
    val coverY: Dp,
    val baseControlsHeight: Dp,
    val seekCeiling: Dp,
    val coverSize: Dp,
    val coverX: Dp,
    val immGeom: ImmersiveCoverGeometry?,
    val normalTitleY: Dp,
    val small: Dp,
    val smallY: Dp,
    val smallX: Dp,
    val p: Float,
    private val expansion: State<Float>,
    val controlsVisibility: Float,
    val effectiveLeftInset: Dp,
    val effectiveStatus: Dp,
    val effectiveRightInset: Dp,
    val effectiveBottomInset: Dp,
    val normalSeekY: Dp,
    val seekY: Dp,
    val titleY: Dp,
    val portraitControlsScale: Float,
    val fullWidthPx: Float,
    val glassCardRect: FullscreenPlayerCard?,
    val showSmallLandscapeCover: Float,
    val coverflowMetrics: CoverflowMetrics?,
    val tiledWallMetrics: TiledWallMetrics?,
    val fullCoverSize: Dp,
    val targetCoverX: Dp,
    val targetCoverY: Dp,
    val miniScale: Float,
    val miniCoverSize: Dp,
    val miniCoverX: Dp,
    val miniCoverY: Dp,
    val miniWidth: Dp,
    val miniHeight: Dp,
    val miniControlClearance: Dp,
    val miniShift: Float,
    val miniAlpha: Float,
    val baseCoverRadius: Dp,
    val spatialCoverAllowed: Boolean,
    val hasCubeOnTitleRow: Boolean,
    val buttonSize: Dp,
    val cubeGap: Dp,
    val coverThemeHeaderTopPad: Dp,
    val coverThemeHeaderTop: Dp,
    private val spatialCoverRequested: Boolean,
    private val requestedSpatialTilt: Offset,
    private val spatialCoverReady: Boolean,
    val effLyricsProgress: Float,
    private val mixActive: Boolean,
    val shareActive: Boolean,
    val shareP: Float,
    val shareChromeAlpha: Float,
    val shareChromeBlur: Dp,
    val activeInk: Color,
    val secondaryInk: Color,
    val lyricInk: Color,
    val lyricSubInk: Color,
    val lyricChromeBlend: Float,
    val sharedClick: () -> Unit,
) {
    val e: Float get() = expansion.value
    val alpha: Float get() = ((e - PlayerChromeRevealStart) / (1f - PlayerChromeRevealStart)).coerceIn(0f, 1f)
    val coverflowReveal: Float get() = if (coverflowActive) alpha * (1f - p) else 0f
    val stageRevealAlpha: Float get() = if (coverflowActive) smoothstep01(.16f, .90f, coverflowReveal) else 0f
    val stageHeroContentAlpha: Float get() = if (coverflowActive) smoothstep01(.68f, .90f, coverflowReveal) else 1f
    val coverflowSharedVis: Float get() = if (coverflowActive) 1f - smoothstep01(.76f, .97f, coverflowReveal) else 1f
    val spatialCoverRendering: Boolean get() = spatialCoverAllowed && spatialCoverRequested && p < .99f && e > .25f
    val spatialCoverTilt: Offset get() = if (spatialCoverRendering) requestedSpatialTilt else Offset.Zero
    val motionOverlayAlpha: Float get() = computeMotionCoverOverlayAlpha(
        expansion = e,
        lyricsProgress = effLyricsProgress,
        mixActive = mixActive,
        spatialActive = spatialCoverRendering && spatialCoverReady,
    )

    fun controlSize(cardSize: Dp, normalSize: Dp): Dp =
        if (squareCardFullscreen) cardSize + (normalSize - cardSize) * p else normalSize
}

@Composable
private fun rememberFullPlayerMetrics(
    w: Dp,
    h: Dp,
    density: Density,
    isMultiWindow: Boolean,
    appearance: NowPlayingConfig,
    p: Float,
    expansion: State<Float>,
    controlsVisibility: Float,
    effectiveLeftInset: Dp,
    effectiveStatus: Dp,
    effectiveRightInset: Dp,
    effectiveBottomInset: Dp,
    miniWidth: Dp,
    miniHeight: Dp,
    miniSwipe: Float,
    miniControlClearance: Dp,
    queueCount: Int,
    queueBlend: Float,
    motionVideoPath: String?,
    spatialTilt: Offset,
    spatialCoverRequested: Boolean,
    spatialCoverState: SpatialCoverResolveState,
    mixVisual: btm.m.auraplayer.auramix.AuraMixVisualTransition?,
    shareTransition: LyricShareTransitionState?,
    activeInk: Color,
    secondaryInk: Color,
    expressive: PlayerExpressiveColors?,
    oneUi: PlayerOneUiColors?,
    coverPalette: com.materialkolor.palettes.CorePalette,
    sharedClick: () -> Unit,
): FullPlayerMetrics {
    val fullscreen = appearance.theme == PlayerTheme.FULLSCREEN
    val landscape = isLandscapePlayerLayout(w, h)
    val activeCoverTheme = resolveActiveCoverTheme(appearance.coverTheme, landscape, appearance.landscapeAutoCoverflow)
    val coverflowActive = activeCoverTheme != CoverTheme.SQUARE
    val squareCardFullscreen = fullscreen && !coverflowActive
    val usesStageDock = coverflowActive && landscape
    val compactWindow = isCompactPlayerWindow(w, h, isMultiWindow)
    val compactScale = if (compactWindow) (h.value / 640f).coerceIn(0.78f, 1.0f) else 1f
    val (portraitColX, portraitColW) = portraitControlColumnRange(w, w * (if (compactWindow) .055f else .058f * PlayerPaddingScale))
    val landscapeGeom = remember(w, h, effectiveLeftInset, effectiveStatus, effectiveRightInset, effectiveBottomInset, appearance.showVolume) {
        if (landscape) {
            computeLandscapePlayerGeometry(
                width = w,
                height = h,
                leftInset = effectiveLeftInset,
                topInset = effectiveStatus,
                rightInset = effectiveRightInset,
                bottomInset = effectiveBottomInset,
                showVolume = appearance.showVolume,
            )
        } else null
    }
    val portraitNav = effectiveBottomInset
    val fullCard = fullscreenPlayerCard(w, h, effectiveStatus, portraitNav)
    val normalMargin = w * (if (compactWindow) .055f else .058f * PlayerPaddingScale)
    fun controlSize(cardSize: Dp, normalSize: Dp): Dp = if (squareCardFullscreen) cardSize + (normalSize - cardSize) * p else normalSize
    val margin = controlSize(fullCard.left + 16.dp, normalMargin)
    val coverY = if (compactWindow) {
        effectiveStatus + 34.dp
    } else {
        maxOf(h * .142f, effectiveStatus + 44.dp * PlayerPaddingScale)
    }
    val baseControlsHeight = (if (appearance.showVolume) 244.dp else 200.dp) * compactScale
    val seekCeiling = h - portraitNav - baseControlsHeight - 10.dp
    val titleGap = (if (compactWindow) 12.dp else 20.dp * PlayerPaddingScale)
    val titleToSeek = (if (compactWindow) 48.dp else 63.dp * PlayerPaddingScale)
    val coverSize = if (landscapeGeom != null) {
        landscapeGeom.heroCoverSize
    } else {
        minOf(
            w - normalMargin * 2,
            (h - effectiveStatus - portraitNav) * (if (compactWindow) .40f else .46f),
            (seekCeiling - coverY - titleGap - titleToSeek).coerceAtLeast(1.dp),
        ).coerceAtLeast(1.dp)
    }
    val coverInset = playerCoverInset(w, coverSize, normalMargin)
    val coverX = normalMargin + coverInset
    val immersiveCover = shouldUseImmersiveCover(
        immersiveCoverSetting = appearance.immersiveCover,
        appleMusicAnimatedCoverEnabled = appearance.appleMusicAnimatedCover,
        appleMusicAnimatedCoverAvailable = !motionVideoPath.isNullOrBlank(),
        fullscreen = fullscreen,
        hasLandscapeGeometry = landscapeGeom != null,
    )
    val immGeom = if (immersiveCover && !coverflowActive) {
        if (landscapeGeom != null) {
            computeLandscapeImmersiveCoverGeometry(
                width = w,
                height = h,
                rightPaneX = landscapeGeom.rightPaneX,
                leftPaneWidth = landscapeGeom.leftPaneWidth,
            )
        } else {
            computeImmersiveCoverGeometry(
                width = w,
                height = h,
                statusTop = effectiveStatus,
                seekCeiling = seekCeiling,
                titleToSeek = titleToSeek,
                compactWindow = compactWindow,
            )
        }
    } else {
        null
    }
    val normalTitleY = immGeom?.titleY ?: (coverY + coverSize + titleGap)
    val small = if (landscapeGeom != null) {
        landscapeGeom.lyricsCompactCoverSize
    } else {
        minOf(if (compactWindow) 48.dp else 64.dp, coverSize * .44f) * .86f
    }
    val smallY = effectiveStatus + (if (compactWindow) 14.dp else 48.dp * PlayerPaddingScale)
    val smallX = if (compactWindow) normalMargin else 26.dp * PlayerPaddingScale
    val normalSeekY = immGeom?.seekY ?: maxOf(normalTitleY + titleToSeek, h * .637f).coerceAtMost(seekCeiling)
    val seekY = controlSize(fullCard.top + 64.dp, normalSeekY)
    val titleY = if (squareCardFullscreen && !landscape) fullCard.top + 14.dp else normalTitleY
    val portraitControlsScale = if (squareCardFullscreen) {
        ((fullCard.height - 76.dp) / 180.dp).coerceIn(.5f, 1.12f)
    } else {
        compactScale
    }
    val fullWidthPx = with(density) { w.toPx() }
    val glassCardRect = when {
        !fullscreen || coverflowActive -> null
        landscapeGeom != null -> FullscreenPlayerCard(
            left = (landscapeGeom.rightPaneX - 16.dp).coerceAtLeast(landscapeGeom.leftPaneX + landscapeGeom.leftPaneWidth + 6.dp),
            top = (landscapeGeom.controlStackTop - 16.dp).coerceAtLeast(landscapeGeom.usableTop),
            width = (landscapeGeom.rightPaneWidth + 32.dp).coerceAtMost(w - landscapeGeom.rightPaneX + 12.dp),
            height = (landscapeGeom.controlStackHeight + 32.dp).coerceAtMost(landscapeGeom.usableHeight + 16.dp),
        )
        else -> fullCard
    }
    val showSmallLandscapeCover = p * controlsVisibility
    val coverflowMetrics = if (activeCoverTheme == CoverTheme.COVERFLOW) {
        remember(w, h, effectiveLeftInset, effectiveStatus, effectiveRightInset, effectiveBottomInset, appearance.showVolume) {
            computeCoverflowMetrics(w, h, effectiveLeftInset, effectiveStatus, effectiveRightInset, effectiveBottomInset, appearance.showVolume)
        }
    } else null
    val tiledWallMetrics = if (activeCoverTheme == CoverTheme.TILED) {
        remember(w, h, effectiveLeftInset, effectiveStatus, effectiveRightInset, effectiveBottomInset, queueCount, appearance.showVolume) {
            computeTiledWallMetrics(w, h, effectiveLeftInset, effectiveStatus, effectiveRightInset, effectiveBottomInset, queueCount.coerceAtLeast(1), appearance.showVolume)
        }
    } else null
    val stageCoverHeroSize = when {
        coverflowMetrics != null -> coverflowMetrics.cardSize
        tiledWallMetrics != null -> tiledWallMetrics.tileSize * 1.825f
        landscapeGeom != null -> landscapeGeom.heroCoverSize
        else -> coverSize
    }
    val stageCoverHeroX = when {
        coverflowMetrics != null -> (w - coverflowMetrics.cardSize) / 2f
        tiledWallMetrics != null -> (w - stageCoverHeroSize) / 2f
        landscapeGeom != null -> landscapeGeom.heroCoverX
        else -> coverX
    }
    val stageCoverHeroY = when {
        coverflowMetrics != null -> effectiveStatus + coverflowMetrics.carouselTopPadding
        tiledWallMetrics != null -> h * tiledWallMetrics.focusYFraction - stageCoverHeroSize / 2f
        landscapeGeom != null -> landscapeGeom.heroCoverY
        else -> coverY
    }
    val fullCoverSize = if (landscapeGeom != null) {
        val lyricsSize = lerp(landscapeGeom.heroCoverSize, landscapeGeom.lyricsCompactCoverSize, controlsVisibility)
        lerp(stageCoverHeroSize, lyricsSize, p)
    } else {
        stageCoverHeroSize + (small - stageCoverHeroSize) * p
    }
    val targetCoverX = if (landscapeGeom != null) {
        val lyricsX = lerp(landscapeGeom.heroCoverX, landscapeGeom.lyricsCompactCoverX, controlsVisibility)
        lerp(stageCoverHeroX, lyricsX, p)
    } else {
        stageCoverHeroX + (smallX - stageCoverHeroX) * p
    }
    val targetCoverY = if (landscapeGeom != null) {
        val lyricsY = lerp(landscapeGeom.heroCoverY, landscapeGeom.lyricsCompactCoverY, controlsVisibility)
        lerp(stageCoverHeroY, lyricsY, p)
    } else {
        stageCoverHeroY + (smallY - stageCoverHeroY) * p
    }
    val miniScale = miniHeight / PlayerNavigationGeometry.miniHeight
    val miniCoverSize = 44.dp * miniScale
    val miniCoverX = 12.dp * miniScale
    val miniCoverY = (miniHeight - miniCoverSize) / 2
    val miniShift = miniSwipe / density.density
    val miniAlpha = (1f - abs(miniSwipe) / fullWidthPx.coerceAtLeast(1f) * .35f).coerceIn(.55f, 1f)
    val baseCoverRadius = when {
        expressive != null -> 28.dp
        oneUi != null -> OneUiCardRadius
        else -> 24.dp
    }
    val spatialCoverAllowed = shouldShowSpatialCoverButton(
        spatialCoverEnabled = appearance.spatialCover,
        appleMusicAnimatedCoverEnabled = appearance.appleMusicAnimatedCover,
        motionVideoPath = motionVideoPath,
    )
    val buttonSize = CoverThemeHeaderTouchHeight
    val cubeGap = CoverThemeSpatialCubeGap
    val coverThemeHeaderTopPad = coverThemeHeaderTopPadding(landscape || compactWindow)
    val coverThemeHeaderTop = effectiveStatus + coverThemeHeaderTopPad
    val requestedSpatialTilt = if (spatialCoverAllowed && spatialCoverRequested && p < .99f) {
        scaleSpatialParallaxTilt(spatialTilt, appearance.spatialCoverSensitivity)
    } else {
        Offset.Zero
    }
    val effLyricsProgress = if (landscapeGeom != null) showSmallLandscapeCover else p
    val shareActive = shareTransition?.active == true
    val shareP = if (shareActive) shareTransition.progress.coerceIn(0f, 1f) else 0f
    val shareChromeAlpha = if (shareActive) 1f - lyricShareSmoothStep(0f, .82f, shareP) else 1f
    val shareChromeBlur = if (shareActive) (shareP * 24f).dp else 0.dp
    val lyricVisual = remember(appearance.lyricTheme) { lyricThemeSpec(appearance.lyricTheme) }
    val lyricChromeBlend = if (appearance.lyricTheme == btm.m.auraplayer.playback.LyricTheme.BLUR) {
        0f
    } else {
        (p * (1f - queueBlend)).coerceIn(0f, 1f)
    }
    val lyricInk = if (lyricChromeBlend > 0f) lerp(activeInk, lyricVisual.inkColor, lyricChromeBlend) else activeInk
    val lyricSubInk = if (lyricChromeBlend > 0f) lerp(secondaryInk, lyricVisual.secondaryInkColor, lyricChromeBlend) else secondaryInk
    return FullPlayerMetrics(
        w = w,
        h = h,
        density = density,
        fullscreen = fullscreen,
        landscape = landscape,
        activeCoverTheme = activeCoverTheme,
        coverflowActive = coverflowActive,
        squareCardFullscreen = squareCardFullscreen,
        usesStageDock = usesStageDock,
        compactWindow = compactWindow,
        compactScale = compactScale,
        portraitColX = portraitColX,
        portraitColW = portraitColW,
        landscapeGeom = landscapeGeom,
        portraitNav = portraitNav,
        fullCard = fullCard,
        normalMargin = normalMargin,
        margin = margin,
        coverY = coverY,
        baseControlsHeight = baseControlsHeight,
        seekCeiling = seekCeiling,
        coverSize = coverSize,
        coverX = coverX,
        immGeom = immGeom,
        normalTitleY = normalTitleY,
        small = small,
        smallY = smallY,
        smallX = smallX,
        p = p,
        expansion = expansion,
        controlsVisibility = controlsVisibility,
        effectiveLeftInset = effectiveLeftInset,
        effectiveStatus = effectiveStatus,
        effectiveRightInset = effectiveRightInset,
        effectiveBottomInset = effectiveBottomInset,
        normalSeekY = normalSeekY,
        seekY = seekY,
        titleY = titleY,
        portraitControlsScale = portraitControlsScale,
        fullWidthPx = fullWidthPx,
        glassCardRect = glassCardRect,
        showSmallLandscapeCover = showSmallLandscapeCover,
        coverflowMetrics = coverflowMetrics,
        tiledWallMetrics = tiledWallMetrics,
        fullCoverSize = fullCoverSize,
        targetCoverX = targetCoverX,
        targetCoverY = targetCoverY,
        miniScale = miniScale,
        miniCoverSize = miniCoverSize,
        miniCoverX = miniCoverX,
        miniCoverY = miniCoverY,
        miniWidth = miniWidth,
        miniHeight = miniHeight,
        miniControlClearance = miniControlClearance,
        miniShift = miniShift,
        miniAlpha = miniAlpha,
        baseCoverRadius = baseCoverRadius,
        spatialCoverAllowed = spatialCoverAllowed,
        hasCubeOnTitleRow = spatialCoverAllowed,
        buttonSize = buttonSize,
        cubeGap = cubeGap,
        coverThemeHeaderTopPad = coverThemeHeaderTopPad,
        coverThemeHeaderTop = coverThemeHeaderTop,
        spatialCoverRequested = spatialCoverRequested,
        requestedSpatialTilt = requestedSpatialTilt,
        spatialCoverReady = spatialCoverState is SpatialCoverResolveState.Ready,
        effLyricsProgress = effLyricsProgress,
        mixActive = mixVisual != null,
        shareActive = shareActive,
        shareP = shareP,
        shareChromeAlpha = shareChromeAlpha,
        shareChromeBlur = shareChromeBlur,
        activeInk = activeInk,
        secondaryInk = secondaryInk,
        lyricInk = lyricInk,
        lyricSubInk = lyricSubInk,
        lyricChromeBlend = lyricChromeBlend,
        sharedClick = sharedClick,
    )
}

@Composable
private fun FullPlayerGlassCardLayer(
    m: FullPlayerMetrics,
    glassCardRect: FullscreenPlayerCard,
    touchPoint: Offset,
    touching: Boolean,
    appearance: NowPlayingConfig,
    backdrop: Backdrop,
    miuixBackdrop: top.yukonga.miuix.kmp.blur.Backdrop,
    menuTint: Color,
    active: Boolean,
    model: AuraViewModel,
) {
    val glassTop = glassCardRect.top
    val cardLeftPx = with(m.density) { glassCardRect.left.toPx() }
    val cardTopPx = with(m.density) { glassTop.toPx() }
    val cardWidthPx = with(m.density) { glassCardRect.width.toPx() }
    val cardHeightPx = with(m.density) { glassCardRect.height.toPx() }
    val localTouch = touchPoint - Offset(cardLeftPx, cardTopPx)
    val touchInside = touching && localTouch.x in 0f..cardWidthPx && localTouch.y in 0f..cardHeightPx
    val useFullscreenGlass = appearance.fullscreenGlass && btm.m.auraplayer.ui.LocalAdvancedMaterialEnabled.current
    val isLightGlass = !LocalPlayerSurfaceStyle.current.isDark
    val touchLight by animateFloatAsState(
        if (useFullscreenGlass && touchInside) .19f else 0f,
        tween(if (touchInside) 100 else 320),
        label = "playerCardTouchLight",
    )
    val cardMaterial = if (useFullscreenGlass) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedCornerShape(30.dp) },
            effects = {
                vibrancy()
                backdropBlur(5.dp.toPx())
                lens(28.dp.toPx(), 42.dp.toPx(), chromaticAberration = true)
            },
            highlight = { Highlight.Default.copy(alpha = if (isLightGlass) .75f else .60f) },
            onDrawSurface = { drawRect(menuTint.copy(alpha = if (isLightGlass) .46f else .18f)) },
        )
    } else {
        Modifier.playerMenuMaterial(miuixBackdrop, menuTint, 30.dp)
    }
    Box(
        Modifier
            .offset(glassCardRect.left, glassTop)
            .size(glassCardRect.width, glassCardRect.height)
            .graphicsLayer { this.alpha = if (m.coverflowActive) 0f else m.alpha * (1f - m.p) * m.shareChromeAlpha }
            .blur(m.shareChromeBlur)
            .then(cardMaterial)
            .clip(RoundedCornerShape(30.dp))
            .drawWithContent {
                drawContent()
                if (useFullscreenGlass && touchLight > .001f) {
                    val radius = size.width * .65f
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = touchLight), Color.White.copy(alpha = touchLight * .32f), Color.Transparent),
                            center = localTouch,
                            radius = radius.coerceAtLeast(1f),
                        ),
                    )
                }
            },
    )
    if (m.p < .01f && !m.landscape) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(glassTop.coerceAtLeast(0.dp))
                .playerTrackSwipe(appearance.swipeTracks && active, model::next, model::previous),
        )
    }
}

@Composable
private fun FullPlayerArtworkLayer(
    m: FullPlayerMetrics,
    song: btm.m.auraplayer.data.Song,
    appearance: NowPlayingConfig,
    coverPalette: com.materialkolor.palettes.CorePalette,
    expressive: PlayerExpressiveColors?,
    oneUi: PlayerOneUiColors?,
    active: Boolean,
    mixVisual: btm.m.auraplayer.auramix.AuraMixVisualTransition?,
    spatialCoverState: SpatialCoverResolveState,
    motionVideoPath: String?,
    motionVideoAllowed: Boolean,
    model: AuraViewModel,
) {
    val immGeom = m.immGeom
    if (immGeom != null) {
        val topLeftDisplayCorner = rememberBottomScreenCornerRadius(0.dp, android.view.RoundedCorner.POSITION_TOP_LEFT)
        val topRightDisplayCorner = rememberBottomScreenCornerRadius(0.dp, android.view.RoundedCorner.POSITION_TOP_RIGHT)
        val bottomLeftDisplayCorner = rememberBottomScreenCornerRadius(0.dp, android.view.RoundedCorner.POSITION_BOTTOM_LEFT)
        val bottomRightDisplayCorner = rememberBottomScreenCornerRadius(0.dp, android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)
        val portraitTopCorner = when {
            m.compactWindow -> minOf(topLeftDisplayCorner, bottomRightDisplayCorner).coerceAtLeast(18.dp)
            topLeftDisplayCorner > 0.dp -> topLeftDisplayCorner
            else -> bottomRightDisplayCorner
        }
        val landscapeLeftTopCorner = if (m.compactWindow) topLeftDisplayCorner.coerceAtLeast(18.dp) else topLeftDisplayCorner
        val landscapeLeftBottomCorner = if (m.compactWindow) bottomLeftDisplayCorner.coerceAtLeast(18.dp) else bottomLeftDisplayCorner
        val landscapeGeom = m.landscapeGeom
        val immCompactX = landscapeGeom?.lyricsCompactCoverX ?: m.smallX
        val immCompactY = landscapeGeom?.lyricsCompactCoverY ?: m.smallY
        val immTargetW = lerp(immGeom.coverWidth, m.small, m.effLyricsProgress)
        val immTargetH = lerp(immGeom.coverHeight, m.small, m.effLyricsProgress)
        val immTargetX = lerp(immGeom.coverX, immCompactX, m.effLyricsProgress)
        val immTargetY = lerp(immGeom.coverY, immCompactY, m.effLyricsProgress)
        val immCurW = lerp(m.miniCoverSize, immTargetW, m.e)
        val immCurH = lerp(m.miniCoverSize, immTargetH, m.e)
        val immCurX = lerp(m.miniCoverX, immTargetX, m.e)
        val immCurY = lerp(m.miniCoverY, immTargetY, m.e)
        val miniCorner = 13.dp * m.miniScale
        val immCurTopStartRadius = lerp(
            miniCorner,
            lerp(if (landscapeGeom != null) landscapeLeftTopCorner else portraitTopCorner, 9.dp, m.effLyricsProgress),
            m.e,
        )
        val immCurTopEndRadius = lerp(
            miniCorner,
            lerp(if (landscapeGeom != null) 0.dp else topRightDisplayCorner.takeIf { it > 0.dp } ?: portraitTopCorner, 9.dp, m.effLyricsProgress),
            m.e,
        )
        val immCurBottomStartRadius = if (landscapeGeom != null) {
            lerp(miniCorner, lerp(landscapeLeftBottomCorner, 9.dp, m.effLyricsProgress), m.e)
        } else {
            immersiveCoverBottomCornerRadius(miniCorner, 9.dp, m.e, m.effLyricsProgress)
        }
        val immCurBottomEndRadius = immersiveCoverBottomCornerRadius(miniCorner, 9.dp, m.e, m.effLyricsProgress)
        val immFeather = (m.e * (1f - m.effLyricsProgress)).coerceIn(0f, 1f)
        Cover(
            uri = song.artwork,
            modifier = Modifier
                .offset(immCurX, immCurY)
                .size(immCurW, immCurH)
                .graphicsLayer {
                    translationX = m.miniShift
                    this.alpha = m.miniAlpha * (1f - m.coverflowReveal) * m.shareChromeAlpha
                }
                .blur(m.shareChromeBlur)
                .playerTrackSwipe(appearance.swipeTracks && active && m.p < .01f && !m.coverflowActive, model::next, model::previous),
            radius = immCurTopStartRadius,
            topEndRadius = immCurTopEndRadius,
            bottomRadius = immCurBottomStartRadius,
            bottomEndRadius = immCurBottomEndRadius,
            onClick = { if (!m.shareActive && m.coverflowReveal < 0.5f) m.sharedClick() },
            animation = appearance.coverAnimation,
            animate = active && m.e > .99f && m.coverflowReveal < 0.5f,
            transitionKey = song.key,
            mixVisual = mixVisual,
            placeholderTint = m.activeInk.copy(alpha = .3f),
            spatialState = spatialCoverState,
            spatialActive = m.spatialCoverRendering && m.coverflowReveal < 0.5f,
            spatialTilt = m.spatialCoverTilt,
            immersiveFeather = immFeather,
            immersiveHorizontal = landscapeGeom != null,
            motionVideoPath = motionVideoPath,
            motionPlaying = motionVideoAllowed && m.motionOverlayAlpha > 0.01f && m.coverflowReveal < 0.5f && mixVisual == null,
            motionOverlayAlpha = m.motionOverlayAlpha * (1f - m.coverflowReveal),
        )
        val immScrimStrength = m.alpha * (1f - m.effLyricsProgress) * (1f - m.coverflowReveal)
        if (immScrimStrength > 0.001f) {
            val isLightPlayer = !LocalPlayerSurfaceStyle.current.isDark
            val scrimTone = when {
                expressive != null -> expressive.surface
                oneUi != null -> oneUi.background
                else -> Color(coverPalette.a2.tone(if (isLightPlayer) 94 else 14))
            }
            if (landscapeGeom != null) {
                val scrimLeft = immGeom.coverWidth * 0.46f
                val scrimWidth = (immGeom.coverWidth - scrimLeft + 44.dp).coerceAtLeast(1.dp)
                Box(
                    Modifier
                        .offset(x = scrimLeft)
                        .width(scrimWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            translationX = m.miniShift
                            this.alpha = immScrimStrength * m.shareChromeAlpha
                        }
                        .blur(m.shareChromeBlur)
                        .background(
                            Brush.horizontalGradient(
                                0f to Color.Transparent,
                                0.40f to scrimTone.copy(alpha = 0.22f),
                                0.72f to scrimTone.copy(alpha = 0.44f),
                                1f to Color.Transparent,
                            ),
                        ),
                )
            } else {
                val scrimTop = immGeom.coverHeight * 0.44f
                val scrimHeight = (immGeom.coverHeight - scrimTop + 44.dp).coerceAtLeast(1.dp)
                Box(
                    Modifier
                        .offset(y = scrimTop)
                        .fillMaxWidth()
                        .height(scrimHeight)
                        .graphicsLayer {
                            translationX = m.miniShift
                            this.alpha = immScrimStrength * m.shareChromeAlpha
                        }
                        .blur(m.shareChromeBlur)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.40f to scrimTone.copy(alpha = 0.22f),
                                0.72f to scrimTone.copy(alpha = 0.44f),
                                1f to Color.Transparent,
                            ),
                        ),
                )
            }
        }
    } else {
        val sharedSize = lerp(m.miniCoverSize, m.fullCoverSize, m.e)
        val sharedX = lerp(m.miniCoverX, m.targetCoverX, m.e)
        val sharedY = lerp(m.miniCoverY, m.targetCoverY, m.e)
        val sharedScale = sharedSize / m.coverSize
        val stageCoverHeroRadius = when {
            m.coverflowMetrics != null -> (m.coverflowMetrics.cardSize * 0.088f).coerceIn(16.dp, 26.dp)
            m.tiledWallMetrics != null -> m.tiledWallMetrics.cornerRadius * 1.825f
            else -> m.baseCoverRadius
        }
        val cornerProgress = if (m.landscapeGeom != null) m.showSmallLandscapeCover else m.p
        val sharedRadius = lerp(13.dp * m.miniScale, stageCoverHeroRadius + (9.dp - stageCoverHeroRadius) * cornerProgress, m.e)
        Cover(
            uri = song.artwork,
            modifier = Modifier
                .offset(sharedX, sharedY)
                .size(m.coverSize)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = sharedScale
                    scaleY = sharedScale
                    translationX = m.miniShift
                    val portraitFullscreenHidden = m.fullscreen && !m.landscape && !m.coverflowActive
                    this.alpha = m.miniAlpha * (if (portraitFullscreenHidden) 1f - m.e * (1f - m.p) else 1f) * m.coverflowSharedVis * m.shareChromeAlpha
                }
                .blur(m.shareChromeBlur)
                .playerTrackSwipe(appearance.swipeTracks && active && m.p < .01f && !m.fullscreen && !m.coverflowActive, model::next, model::previous),
            radius = sharedRadius / sharedScale,
            onClick = { if (!m.shareActive && m.coverflowSharedVis > 0.5f) m.sharedClick() },
            animation = appearance.coverAnimation,
            animate = active && m.e > .99f && m.coverflowSharedVis > 0.05f,
            transitionKey = song.key,
            mixVisual = mixVisual,
            placeholderTint = m.activeInk.copy(alpha = .3f),
            spatialState = spatialCoverState,
            spatialActive = m.spatialCoverRendering && m.coverflowSharedVis > 0.05f,
            spatialTilt = m.spatialCoverTilt,
            motionVideoPath = motionVideoPath,
            motionPlaying = motionVideoAllowed && m.motionOverlayAlpha > 0.01f && m.coverflowSharedVis > 0.05f && mixVisual == null,
            motionOverlayAlpha = m.motionOverlayAlpha * m.coverflowSharedVis,
        )
    }
}

@Composable
private fun FullPlayerCoverThemeStageLayer(
    m: FullPlayerMetrics,
    model: AuraViewModel,
    song: btm.m.auraplayer.data.Song,
    appearance: NowPlayingConfig,
    coverPalette: com.materialkolor.palettes.CorePalette,
    expressive: PlayerExpressiveColors?,
    oneUi: PlayerOneUiColors?,
    backdrop: Backdrop,
    miuixBackdrop: top.yukonga.miuix.kmp.blur.Backdrop,
    menuTint: Color,
    mixVisual: btm.m.auraplayer.auramix.AuraMixVisualTransition?,
    spatialCoverState: SpatialCoverResolveState,
    spatialTilt: State<Offset>,
    motionVideoPath: String?,
    motionVideoAllowed: Boolean,
    active: Boolean,
    stageVisible: Boolean,
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onQualityMenuExpanded: (Boolean) -> Unit,
    onDragActiveChange: (Boolean) -> Unit,
) {
    val metrics by rememberUpdatedState(m)
    val stageVisibleState = rememberUpdatedState(stageVisible)
    val stageMod = remember {
        Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val stage = measurable.measure(constraints)
                layout(stage.width, stage.height) {
                    if (stageVisibleState.value) stage.place(0, 0)
                }
            }
            .graphicsLayer {
                val revealAlpha = metrics.stageRevealAlpha
                alpha = revealAlpha * metrics.shareChromeAlpha
                setPlayerLayerBlur(metrics.shareChromeBlur)
            }
    }
    val heroContentAlpha = remember { derivedStateOf { metrics.stageHeroContentAlpha } }
    val stageInteractive by remember {
        derivedStateOf { !metrics.shareActive && metrics.stageRevealAlpha > 0.45f }
    }
    val stageSettled by remember {
        derivedStateOf { !metrics.shareActive && metrics.e >= 0.985f && metrics.p < 0.01f }
    }
    val stageMotionVisible by remember { derivedStateOf { metrics.stageRevealAlpha > 0.30f } }
    val stageSpatialCoverRendering by remember { derivedStateOf { metrics.spatialCoverRendering } }
    val stageParams = CoverThemeStageParams(
        model = model,
        song = song,
        appearance = appearance,
        coverPalette = coverPalette,
        expressive = expressive,
        oneUi = oneUi,
        backdrop = backdrop,
        miuixBackdrop = miuixBackdrop,
        menuTint = menuTint,
        activeInk = m.activeInk,
        secondaryInk = m.secondaryInk,
        mixVisual = mixVisual,
        spatialCoverState = spatialCoverState,
        spatialCoverRendering = stageSpatialCoverRendering,
        spatialTilt = spatialTilt,
        motionVideoPath = motionVideoPath,
        motionVideoAllowed = motionVideoAllowed && stageMotionVisible,
        active = active,
        interactive = stageInteractive,
        lyricsOpen = lyricsOpen,
        queueOpen = queueOpen,
        topInset = m.effectiveStatus,
        leftInset = m.effectiveLeftInset,
        rightInset = m.effectiveRightInset,
        bottomInset = m.effectiveBottomInset,
        headerTopPadding = m.coverThemeHeaderTopPad,
        reserveSpatialCubeSlot = m.hasCubeOnTitleRow,
        maxWidth = m.w,
        maxHeight = m.h,
        heroContentAlpha = heroContentAlpha,
        stageVisible = stageVisible,
        stageSettled = stageSettled,
        onLyrics = onLyrics,
        onQueue = onQueue,
        onQualityMenuExpanded = onQualityMenuExpanded,
        onDragActiveChange = onDragActiveChange,
    )
    when (m.activeCoverTheme) {
        CoverTheme.COVERFLOW -> PlayerCoverflowStage(params = stageParams, modifier = stageMod)
        CoverTheme.TILED -> PlayerTiledStage(params = stageParams, modifier = stageMod)
        CoverTheme.SQUARE -> Unit
    }
}

@Composable
private fun FullPlayerChromeAndLyricsLayer(
    m: FullPlayerMetrics,
    model: AuraViewModel,
    song: btm.m.auraplayer.data.Song,
    appearance: NowPlayingConfig,
    coverPalette: com.materialkolor.palettes.CorePalette,
    expressive: PlayerExpressiveColors?,
    oneUi: PlayerOneUiColors?,
    backdrop: Backdrop,
    active: Boolean,
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    queueBlend: Float,
    immersive: Boolean,
    isMultiWindow: Boolean,
    tiledChromeAlpha: Float,
    textHeight: Int,
    spatialTilt: Offset,
    shareTransition: LyricShareTransitionState?,
    onClose: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onQualityMenuExpanded: (Boolean) -> Unit,
    openSharePage: (Int, Rect?, Rect?) -> Unit,
) {
    val landscapeGeom = m.landscapeGeom
    val immGeom = m.immGeom
    val lyricInk = m.lyricInk
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val morphAlpha = m.alpha
                this.alpha = morphAlpha * m.shareChromeAlpha
                setPlayerLayerBlur(((1f - morphAlpha) * 14f).dp + m.shareChromeBlur)
            },
    ) {
        val handleAlpha = when {
            m.landscape && immersive -> 0.48f
            immGeom != null && m.p < 0.5f -> 0.48f + 0.52f * (m.p / 0.5f)
            else -> 1f
        }
        val handleY = when {
            m.coverflowActive -> m.coverThemeHeaderTop + (m.buttonSize - 28.dp) / 2f
            m.landscape || m.compactWindow -> m.effectiveStatus + 2.dp
            immGeom != null -> m.effectiveStatus + 6.dp
            else -> m.effectiveStatus + 12.dp * PlayerPaddingScale
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = handleY)
                .size(104.dp, 28.dp)
                .graphicsLayer { this.alpha = handleAlpha * tiledChromeAlpha }
                .clip(RoundedCornerShape(50))
                .semantics { contentDescription = tr("collapse_player", "收起播放器") }
                .playerSurfaceClick(onClose),
            contentAlignment = Alignment.Center,
        ) {
            val pillWidth = if (m.landscape || isMultiWindow) 52.dp else 80.dp
            val pillHeight = if (m.landscape || isMultiWindow) 4.5.dp else 6.dp
            when {
                expressive != null -> Box(Modifier.width(48.dp).height(4.5.dp).clip(RoundedCornerShape(50)).background(expressive.dragHandleColor))
                oneUi != null -> Box(Modifier.width(48.dp).height(4.5.dp).clip(RoundedCornerShape(50)).background(oneUi.track))
                else -> {
                    val handleAlpha = if (LocalPlayerSurfaceStyle.current.isDark) .36f else .24f
                    Box(Modifier.width(pillWidth).height(pillHeight).clip(RoundedCornerShape(50)).background(m.activeInk.copy(alpha = handleAlpha)))
                }
            }
        }
        if (queueBlend > .001f && m.p > 0f) {
            val queueInnerHorizontalPad = 8.dp
            val queueMod = if (landscapeGeom != null) {
                Modifier
                    .offset(x = landscapeGeom.rightPaneX, y = landscapeGeom.usableTop)
                    .size(
                        landscapeGeom.rightPaneWidth,
                        (landscapeGeom.usableBottom - landscapeGeom.usableTop).coerceAtLeast(1.dp),
                    )
            } else {
                val qTop = m.smallY + m.small + (if (m.compactWindow) 12.dp else 20.dp * PlayerPaddingScale)
                Modifier
                    .offset(y = qTop)
                    .fillMaxWidth()
                    .height((m.seekY + 50.dp - qTop - 8.dp).coerceAtLeast(1.dp))
                    .padding(horizontal = (m.smallX - queueInnerHorizontalPad).coerceAtLeast(0.dp))
            }
            PlayerQueue(
                model,
                backdrop = backdrop,
                horizontalPadding = if (landscapeGeom != null) 4.dp else queueInnerHorizontalPad,
                expressive = expressive,
                oneUi = oneUi,
                modifier = queueMod
                    .graphicsLayer { this.alpha = m.p * queueBlend; translationY = (1f - m.p) * 24.dp.toPx() }
                    .blur(((1f - m.p) * 12f + queueBlend * (1f - queueBlend) * 20f).dp),
            )
        }
        if (landscapeGeom != null) {
            val normalCtrlAlpha = if (m.coverflowActive) 0f else ((1f - m.p) * m.controlsVisibility).coerceIn(0f, 1f)
            val landscapeHeaderReserve = with(m.density) {
                maxOf(46.dp, if (textHeight > 0) textHeight.toDp() else 48.dp)
            }
            val rightCtrlTop = landscapeGeom.controlStackTop + landscapeHeaderReserve + 8.dp
            val rightCtrlHeight = (landscapeGeom.controlStackTop + landscapeGeom.controlStackHeight - rightCtrlTop).coerceAtLeast(110.dp)
            if (normalCtrlAlpha > 0.001f) {
                val lScale = landscapeGeom.controlsScale
                val sliderH = when (appearance.glowProgress) {
                    GlowProgressStyle.ONE -> 32.dp
                    GlowProgressStyle.HYPER -> 26.dp
                    GlowProgressStyle.OFF -> 22.dp
                } * lScale.coerceAtLeast(0.84f)
                Column(
                    Modifier
                        .offset(x = landscapeGeom.rightPaneX, y = rightCtrlTop)
                        .size(landscapeGeom.rightPaneWidth, rightCtrlHeight)
                        .graphicsLayer { this.alpha = normalCtrlAlpha }
                        .blur(((1f - normalCtrlAlpha) * 12f).dp, BlurredEdgeTreatment.Unbounded),
                ) {
                    PlayerProgressSection(
                        model = model,
                        song = song,
                        appearance = appearance,
                        coverPalette = coverPalette,
                        expressive = expressive,
                        oneUi = oneUi,
                        backdrop = backdrop,
                        active = active,
                        queueOpen = queueOpen,
                        controlsVisibility = normalCtrlAlpha,
                        sliderHeight = sliderH,
                        footerHeight = (26.dp * lScale.coerceAtLeast(0.88f)).coerceAtLeast(24.dp),
                        horizontalInset = 0.dp,
                        onQualityMenuExpanded = onQualityMenuExpanded,
                        modifier = Modifier.graphicsLayer { this.alpha = 1f - queueBlend },
                    )
                    Spacer(Modifier.weight(1f))
                    PlayerTransportSection(
                        model = model,
                        expressive = expressive,
                        oneUi = oneUi,
                        scale = lScale.coerceIn(0.80f, 1.0f),
                        rowHeight = (74.dp * lScale).coerceIn(54.dp, 82.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    if (appearance.showVolume) {
                        PlayerVolumeSection(
                            expressive = expressive,
                            oneUi = oneUi,
                            height = (38.dp * lScale).coerceIn(30.dp, 42.dp),
                        )
                    }
                    PlayerBottomActionsSection(
                        model = model,
                        song = song,
                        lyricsOpen = lyricsOpen,
                        queueOpen = queueOpen,
                        expressive = expressive,
                        oneUi = oneUi,
                        rowHeight = (46.dp * lScale).coerceIn(36.dp, 50.dp),
                        bottomTouch = (44.dp * lScale).coerceIn(34.dp, 48.dp),
                        bottomIcon = (28.dp * lScale).coerceIn(22.dp, 31.dp),
                        onLyrics = onLyrics,
                        onQueue = onQueue,
                    )
                }
            }
            val leftCtrlAlpha = (m.p * m.controlsVisibility).coerceIn(0f, 1f)
            if (leftCtrlAlpha > 0.001f) {
                val leftCtrlTop = landscapeGeom.lyricsCompactCoverY + landscapeGeom.lyricsCompactCoverSize + 8.dp
                val leftCtrlHeight = (landscapeGeom.usableBottom - leftCtrlTop).coerceAtLeast(100.dp)
                val clScale = (leftCtrlHeight.value / 190f).coerceIn(0.60f, 0.88f)
                val showLeftVolume = appearance.showVolume && leftCtrlHeight >= 158.dp
                Column(
                    Modifier
                        .offset(x = landscapeGeom.leftPaneX, y = leftCtrlTop)
                        .size(landscapeGeom.leftPaneWidth, leftCtrlHeight)
                        .graphicsLayer { this.alpha = leftCtrlAlpha }
                        .blur(((1f - leftCtrlAlpha) * 12f).dp, BlurredEdgeTreatment.Unbounded),
                ) {
                    PlayerProgressSection(
                        model = model,
                        song = song,
                        appearance = appearance,
                        coverPalette = coverPalette,
                        expressive = expressive,
                        oneUi = oneUi,
                        backdrop = backdrop,
                        active = active,
                        queueOpen = queueOpen,
                        controlsVisibility = leftCtrlAlpha,
                        sliderHeight = 20.dp * clScale.coerceAtLeast(0.84f),
                        footerHeight = 20.dp * clScale.coerceAtLeast(0.84f),
                        horizontalInset = 0.dp,
                        onQualityMenuExpanded = onQualityMenuExpanded,
                        modifier = Modifier.graphicsLayer { this.alpha = 1f - queueBlend },
                    )
                    Spacer(Modifier.weight(1f))
                    PlayerTransportSection(
                        model = model,
                        expressive = expressive,
                        oneUi = oneUi,
                        scale = clScale.coerceIn(0.72f, 0.88f),
                        rowHeight = (58.dp * clScale).coerceIn(46.dp, 64.dp),
                    )
                    if (showLeftVolume) {
                        Spacer(Modifier.weight(0.6f))
                        PlayerVolumeSection(
                            expressive = expressive,
                            oneUi = oneUi,
                            height = (32.dp * clScale).coerceIn(26.dp, 36.dp),
                        )
                    }
                    Spacer(Modifier.weight(0.6f))
                    PlayerBottomActionsSection(
                        model = model,
                        song = song,
                        lyricsOpen = lyricsOpen,
                        queueOpen = queueOpen,
                        expressive = expressive,
                        oneUi = oneUi,
                        rowHeight = (42.dp * clScale).coerceIn(34.dp, 46.dp),
                        bottomTouch = (40.dp * clScale).coerceIn(32.dp, 44.dp),
                        bottomIcon = (26.dp * clScale).coerceIn(20.dp, 29.dp),
                        onLyrics = onLyrics,
                        onQueue = onQueue,
                    )
                }
            }
        } else {
            val portraitCtrlVis = (m.controlsVisibility * (if (m.usesStageDock) m.p else 1f)).coerceIn(0f, 1f)
            if (portraitCtrlVis > .001f) {
                Column(
                    Modifier
                        .offset(y = m.seekY)
                        .fillMaxWidth()
                        .graphicsLayer { this.alpha = portraitCtrlVis * tiledChromeAlpha }
                        .blur(((1f - portraitCtrlVis) * 14f).dp, BlurredEdgeTreatment.Unbounded)
                        .height(
                            m.controlSize(
                                (m.fullCard.height - 76.dp).coerceAtLeast(1.dp),
                                (m.h - m.normalSeekY - m.portraitNav - 10.dp).coerceAtLeast(m.baseControlsHeight),
                            ),
                        )
                        .padding(horizontal = m.margin),
                ) {
                    val sliderNormalHeight = when (appearance.glowProgress) {
                        GlowProgressStyle.ONE -> 36.dp
                        GlowProgressStyle.HYPER -> 30.dp
                        GlowProgressStyle.OFF -> 24.dp
                    } * m.compactScale
                    val sliderFullHeight = when (appearance.glowProgress) {
                        GlowProgressStyle.ONE -> 31.dp
                        GlowProgressStyle.HYPER -> 26.dp
                        GlowProgressStyle.OFF -> 20.dp
                    } * m.portraitControlsScale
                    PlayerProgressSection(
                        model = model,
                        song = song,
                        appearance = appearance,
                        coverPalette = coverPalette,
                        expressive = expressive,
                        oneUi = oneUi,
                        backdrop = backdrop,
                        active = active,
                        queueOpen = queueOpen,
                        controlsVisibility = portraitCtrlVis,
                        sliderHeight = m.controlSize(sliderFullHeight, sliderNormalHeight),
                        footerHeight = m.controlSize(20.dp * m.portraitControlsScale, 26.dp * m.compactScale),
                        horizontalInset = 0.dp,
                        onQualityMenuExpanded = onQualityMenuExpanded,
                        modifier = Modifier.graphicsLayer { this.alpha = 1f - queueBlend },
                    )
                    Spacer(Modifier.weight(1f))
                    PlayerTransportSection(
                        model = model,
                        expressive = expressive,
                        oneUi = oneUi,
                        scale = m.portraitControlsScale,
                        rowHeight = m.controlSize(64.dp * m.portraitControlsScale, 100.dp * m.compactScale),
                    )
                    Spacer(Modifier.weight(1f))
                    if (appearance.showVolume) {
                        PlayerVolumeSection(
                            expressive = expressive,
                            oneUi = oneUi,
                            height = m.controlSize(32.dp * m.portraitControlsScale, 44.dp * m.compactScale),
                        )
                    }
                    PlayerBottomActionsSection(
                        model = model,
                        song = song,
                        lyricsOpen = lyricsOpen,
                        queueOpen = queueOpen,
                        expressive = expressive,
                        oneUi = oneUi,
                        rowHeight = m.controlSize(44.dp * m.portraitControlsScale, 56.dp * m.compactScale),
                        bottomTouch = m.controlSize(40.dp * m.portraitControlsScale, 48.dp * m.compactScale),
                        bottomIcon = m.controlSize(27.dp * m.portraitControlsScale, 32.dp * m.compactScale),
                        onLyrics = onLyrics,
                        onQueue = onQueue,
                    )
                }
            }
        }
    }
    if (m.p > 0f && queueBlend < .999f) {
        val lyricBoxModifier = if (landscapeGeom != null) {
            val bottomReserve = 52.dp * m.controlsVisibility
            Modifier
                .offset(x = landscapeGeom.rightPaneX, y = landscapeGeom.usableTop)
                .size(
                    landscapeGeom.rightPaneWidth,
                    (landscapeGeom.usableBottom - landscapeGeom.usableTop - bottomReserve).coerceAtLeast(1.dp),
                )
        } else {
            val portraitLyricsTop = m.smallY + m.small + (if (m.compactWindow) 10.dp else 20.dp * PlayerPaddingScale)
            val bottomReserve = 56.dp * m.controlsVisibility
            val portraitLyricsBottom = m.seekY - bottomReserve + (m.h - m.portraitNav - m.seekY) * (1f - m.controlsVisibility)
            Modifier
                .offset(y = portraitLyricsTop)
                .fillMaxWidth()
                .height((portraitLyricsBottom - portraitLyricsTop).coerceAtLeast(1.dp))
        }
        val lyricHorizontalPadDp = when {
            landscapeGeom != null -> 10f
            m.w < 330.dp -> 14f
            else -> 26f
        }
        val effectiveLyricScale = model.lyricSize.scale * when {
            landscapeGeom != null && landscapeGeom.rightPaneWidth < 360.dp -> (landscapeGeom.rightPaneWidth.value / 370f).coerceIn(0.76f, 1f)
            !m.landscape && m.w < 330.dp -> (m.w.value / 345f).coerceIn(0.76f, 1f)
            else -> 1f
        }
        val mainLyricScale = effectiveLyricScale * lyricScaleMultiplier(appearance.mainLyricScalePercent)
        val bgLyricScale = effectiveLyricScale * lyricScaleMultiplier(appearance.bgLyricScalePercent)
        Box(
            lyricBoxModifier
                .graphicsLayer {
                    val morphAlpha = m.alpha
                    val shareExitAlpha = if (m.shareActive) 1f - lyricShareSmoothStep(.06f, .62f, m.shareP) else 1f
                    this.alpha = morphAlpha * m.p * (1f - queueBlend) * shareExitAlpha
                    setPlayerLayerBlur(((1f - morphAlpha) * 14f + (1f - m.p) * 12f + queueBlend * (1f - queueBlend) * 20f).dp)
                    var tx = 0f
                    var ty = (1f - m.p) * 24.dp.toPx()
                    if (m.shareActive && shareTransition != null) {
                        val vp = shareTransition.sourceViewportBounds
                        val srcCenter = shareTransition.sourceRowBounds?.center ?: vp?.center
                        val dstCenter = shareTransition.targetRowBounds?.center ?: shareTransition.targetCardBounds?.center
                        val srcH = shareTransition.sourceRowBounds?.height?.takeIf { it > 0f }
                        val dstH = shareTransition.targetRowBounds?.height?.takeIf { it > 0f }
                        val endScale = if (srcH != null && dstH != null) (dstH / srcH).coerceIn(.48f, .88f) else .62f
                        val scale = 1f + (endScale - 1f) * m.shareP
                        if (vp != null && vp.width > 0f && vp.height > 0f && srcCenter != null) {
                            transformOrigin = TransformOrigin(
                                ((srcCenter.x - vp.left) / vp.width).coerceIn(0f, 1f),
                                ((srcCenter.y - vp.top) / vp.height).coerceIn(0f, 1f),
                            )
                        }
                        scaleX = scale
                        scaleY = scale
                        if (srcCenter != null && dstCenter != null) {
                            tx += (dstCenter.x - srcCenter.x) * m.shareP
                            ty += (dstCenter.y - srcCenter.y) * m.shareP
                        }
                    }
                    translationX = tx
                    translationY = ty
                },
        ) {
            if (model.lyrics.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (model.lyricsLoading) tr("lyrics_loading", "正在获取歌词…") else tr("lyrics_empty", "暂无歌词"),
                        color = lyricInk.copy(alpha = .55f),
                        fontSize = 20.sp,
                    )
                }
            } else {
                AndroidView(
                    factory = { PlayerLyricView(it) },
                    update = {
                        val lyricTilt = if (appearance.spatialLyrics) {
                            scaleSpatialParallaxTilt(spatialTilt, appearance.spatialLyricsSensitivity)
                        } else {
                            Offset.Zero
                        }
                        it.lyricTheme(appearance.lyricTheme)
                        it.tokyoLandmark(appearance.tokyoSceneLandmark)
                        it.spatialLyrics(appearance.spatialLyrics)
                        it.spatialTilt(lyricTilt.x, lyricTilt.y)
                        it.inkColor(lyricInk.toArgb())
                        it.submit(
                            lines = model.lyrics,
                            translation = model.showTranslation,
                            pronunciation = model.showPronunciation,
                            textScale = mainLyricScale,
                            horizontalPaddingDp = lyricHorizontalPadDp,
                            bgTextScale = bgLyricScale,
                        )
                        it.position(model.position, model.playing)
                        it.onSeek = model::seekTo
                        it.onLongPress = { index -> openSharePage(index, it.rowBoundsInWindow(index)?.toComposeRect(), it.viewportBoundsInWindow().toComposeRect()) }
                        it.keepScreenOn = appearance.keepLyricsAwake && active && lyricsOpen
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun FullPlayerMetadataAndActionsLayer(
    m: FullPlayerMetrics,
    model: AuraViewModel,
    song: btm.m.auraplayer.data.Song,
    appearance: NowPlayingConfig,
    expressive: PlayerExpressiveColors?,
    oneUi: PlayerOneUiColors?,
    miuixBackdrop: top.yukonga.miuix.kmp.blur.Backdrop,
    menuTint: Color,
    miniColor: Color,
    mixVisual: btm.m.auraplayer.auramix.AuraMixVisualTransition?,
    spatialCoverRequested: Boolean,
    spatialCoverState: SpatialCoverResolveState,
    tiledChromeAlpha: Float,
    tiledWallDragging: Boolean,
    textHeight: Int,
    onTextHeightChange: (Int) -> Unit,
    queueBlend: Float,
    onToggleSpatialCover: () -> Unit,
    onMore: () -> Unit,
    onLyricMenuExpanded: (Boolean) -> Unit,
) {
    val landscapeGeom = m.landscapeGeom
    val immGeom = m.immGeom
    val metadataSize = if (m.squareCardFullscreen && !m.landscape) 1f - .12f * m.e * (1f - m.p) else 1f
    val measuredTextHeight = with(m.density) { if (textHeight > 0) textHeight.toDp() else (50.sp * metadataSize).toDp() }
    val miniTextX = 66.dp * m.miniScale
    val miniTextWidth = (m.miniWidth - miniTextX - m.miniControlClearance).coerceAtLeast(24.dp)
    val miniTextY = (m.miniHeight - measuredTextHeight * .7f * m.miniScale) / 2
    val coverThemeDockLayout = if (m.usesStageDock) {
        resolveCoverThemeDockLayout(
            isLandscape = landscapeGeom != null,
            dockCompact = m.coverflowMetrics?.dockCompact ?: m.tiledWallMetrics?.dockCompact ?: false,
            showCardContainer = false,
            glowProgress = appearance.glowProgress,
            hasExpressive = expressive != null,
            hasOneUi = oneUi != null,
            playing = model.playbackRequested,
        )
    } else {
        null
    }
    val compactTargetTextScale = 1f - (if (landscapeGeom != null) .14f else .22f)
    val dockMetaScale = coverThemeDockLayout?.let { it.titleSizeSp / 20f }
    val expandedTextScale = dockMetaScale?.let { it + (compactTargetTextScale - it) * m.p }
        ?: (1f - (if (landscapeGeom != null) .14f else .22f) * m.p)
    val textScale = .7f * m.miniScale + (expandedTextScale - .7f * m.miniScale) * m.e
    val dockHorizontalMargin = m.coverflowMetrics?.dockHorizontalMargin ?: m.tiledWallMetrics?.dockHorizontalMargin ?: 0.dp
    val dockBottomPadding = m.coverflowMetrics?.dockBottomPadding ?: m.tiledWallMetrics?.dockBottomPadding ?: 0.dp
    val dockIncludeVolume = m.coverflowMetrics?.includeVolumeInDock ?: m.tiledWallMetrics?.includeVolumeInDock ?: false
    val dockMetaX = if (coverThemeDockLayout != null) {
        m.effectiveLeftInset + dockHorizontalMargin + coverThemeDockLayout.horizontalPad
    } else {
        0.dp
    }
    val dockMetaH = if (coverThemeDockLayout != null) measuredTextHeight * (dockMetaScale ?: 1f) else 0.dp
    val dockMetaY = if (coverThemeDockLayout != null) {
        val dockBottom = m.h - m.effectiveBottomInset - dockBottomPadding
        if (landscapeGeom != null) {
            val row2H = maxOf(dockMetaH, coverThemeDockLayout.transportHeight)
            dockBottom - coverThemeDockLayout.verticalPad - row2H + (row2H - dockMetaH) / 2f
        } else {
            val progressH = coverThemeDockLayout.sliderHeight + maxOf(coverThemeDockLayout.footerHeight, 24.dp)
            val volumeHAndGap = if (dockIncludeVolume) coverThemeDockLayout.itemGap + coverThemeDockLayout.volumeHeight else 0.dp
            dockBottom -
                coverThemeDockLayout.verticalPad -
                coverThemeDockLayout.actionHeight -
                coverThemeDockLayout.itemGap -
                volumeHAndGap -
                coverThemeDockLayout.transportHeight -
                coverThemeDockLayout.itemGap -
                progressH -
                coverThemeDockLayout.itemGap -
                dockMetaH
        }
    } else {
        0.dp
    }
    val dockMetaRowW = if (coverThemeDockLayout != null) {
        val dockInnerW = (m.w - m.effectiveLeftInset - m.effectiveRightInset - dockHorizontalMargin * 2 - coverThemeDockLayout.horizontalPad * 2)
            .coerceAtLeast(24.dp)
        if (landscapeGeom != null) {
            (dockInnerW - coverThemeDockLayout.transportWidth - coverThemeDockLayout.landscapeMetaRowGap).coerceAtLeast(24.dp)
        } else {
            dockInnerW
        }
    } else {
        0.dp
    }
    val dockMetaW = if (coverThemeDockLayout != null) {
        val cubeReserve = if (m.hasCubeOnTitleRow) m.buttonSize + m.cubeGap else 0.dp
        (dockMetaRowW - cubeReserve).coerceAtLeast(24.dp)
    } else {
        0.dp
    }
    val fullTextX = if (landscapeGeom != null) {
        val normalMetaX = if (coverThemeDockLayout != null) dockMetaX else landscapeGeom.rightPaneX
        val compactMetaX = landscapeGeom.lyricsCompactCoverX + landscapeGeom.lyricsCompactCoverSize + 12.dp
        lerp(normalMetaX, compactMetaX, m.p)
    } else {
        val compactTextX = m.smallX + m.small + 12.dp * PlayerPaddingScale
        val expandedTextX = when {
            coverThemeDockLayout != null -> dockMetaX
            m.squareCardFullscreen -> m.fullCard.left + 16.dp
            else -> m.portraitColX
        }
        expandedTextX + (compactTextX - expandedTextX) * m.p
    }
    val fullTextY = if (landscapeGeom != null) {
        val normalMetaY = if (coverThemeDockLayout != null) dockMetaY else landscapeGeom.controlStackTop
        val compactMetaY = landscapeGeom.lyricsCompactCoverY + (landscapeGeom.lyricsCompactCoverSize - measuredTextHeight * .86f) / 2
        lerp(normalMetaY, compactMetaY, m.p)
    } else {
        val compactTextY = m.smallY + (m.small - measuredTextHeight * .78f) / 2
        val expandedTextY = if (coverThemeDockLayout != null) dockMetaY else m.titleY
        expandedTextY + (compactTextY - expandedTextY) * m.p
    }
    val fullTextWidth = if (landscapeGeom != null) {
        val cubeReserve = if (m.hasCubeOnTitleRow) m.buttonSize + m.cubeGap else 0.dp
        val normalMetaW = if (coverThemeDockLayout != null) {
            dockMetaW
        } else {
            (landscapeGeom.rightPaneWidth - 52.dp - cubeReserve).coerceAtLeast(24.dp)
        }
        val compactMetaW = (landscapeGeom.leftPaneWidth - landscapeGeom.lyricsCompactCoverSize - 12.dp - 48.dp).coerceAtLeast(24.dp)
        lerp(normalMetaW, compactMetaW, m.p)
    } else {
        val compactTextX = m.smallX + m.small + 12.dp * PlayerPaddingScale
        val cubeReserve = if (m.hasCubeOnTitleRow) m.buttonSize + m.cubeGap else 0.dp
        val expandedTextWidth = when {
            coverThemeDockLayout != null -> dockMetaW
            m.squareCardFullscreen -> (m.fullCard.width - 84.dp - cubeReserve).coerceAtLeast(24.dp)
            immGeom != null -> (m.portraitColW - m.buttonSize - 8.dp - cubeReserve).coerceAtLeast(24.dp)
            else -> (m.portraitColW - cubeReserve).coerceAtLeast(24.dp)
        }
        expandedTextWidth + ((m.w - compactTextX - 68.dp).coerceAtLeast(24.dp) - expandedTextWidth) * m.p
    }
    val textWidth = (miniTextWidth + (fullTextWidth - miniTextWidth) * m.e) / textScale
    val textColor = lerp(miniColor, m.lyricInk, m.e)
    val baseArtistColor = oneUi?.subText ?: expressive?.secondaryInk ?: m.secondaryInk
    val artistColor = lerp(
        miniColor.copy(alpha = .8f),
        if (m.lyricChromeBlend > 0f) lerp(baseArtistColor, m.lyricSubInk, m.lyricChromeBlend) else baseArtistColor,
        m.e,
    )
    val mixProgress = mixVisual?.progress?.coerceIn(0f, 1f)
    val mixFromAlpha = mixProgress?.let { (1f - it).coerceIn(0f, 1f) } ?: 0f
    val mixToAlpha = mixProgress?.coerceIn(0f, 1f) ?: 0f
    val sharedMetaVis = if (m.usesStageDock) m.coverflowSharedVis else 1f
    val landscapeMetaChrome = (if (landscapeGeom != null) 1f - m.e * m.p * (1f - m.controlsVisibility) else 1f) * sharedMetaVis
    Box(
        Modifier
            .offset(lerp(miniTextX, fullTextX, m.e), miniTextY + (fullTextY - miniTextY) * m.e)
            .width(textWidth)
            .wrapContentHeight(Alignment.Top, unbounded = true)
            .graphicsLayer {
                translationX = m.miniShift
                this.alpha = m.miniAlpha * m.shareChromeAlpha * landscapeMetaChrome * tiledChromeAlpha
            }
            .blur(m.shareChromeBlur + if (landscapeGeom != null || m.usesStageDock) ((1f - landscapeMetaChrome) * (if (m.coverflowActive) 6f else 14f)).dp else 0.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .onSizeChanged { onTextHeightChange(it.height) }
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = textScale
                    scaleY = textScale
                    this.alpha = m.miniAlpha * if (mixVisual == null) 1f else mixFromAlpha
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !m.shareActive && !tiledWallDragging && landscapeMetaChrome > 0.1f && (!m.usesStageDock || m.coverflowSharedVis > 0.5f),
                    onClick = m.sharedClick,
                ),
        ) {
            Text(
                mixVisual?.fromTitle ?: song.title,
                color = textColor,
                fontSize = 20.sp * metadataSize,
                lineHeight = 25.sp * metadataSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                mixVisual?.fromArtist ?: song.artist,
                color = artistColor,
                fontSize = 16.sp * metadataSize,
                lineHeight = 21.sp * metadataSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (mixVisual != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = textScale
                        scaleY = textScale
                        this.alpha = m.miniAlpha * mixToAlpha
                    },
            ) {
                Text(
                    mixVisual.toTitle,
                    color = textColor,
                    fontSize = 20.sp * metadataSize,
                    lineHeight = 25.sp * metadataSize,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    mixVisual.toArtist,
                    color = artistColor,
                    fontSize = 16.sp * metadataSize,
                    lineHeight = 21.sp * metadataSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !m.shareActive && !tiledWallDragging && landscapeMetaChrome > 0.1f && (!m.usesStageDock || m.coverflowSharedVis > 0.5f),
                    onClick = m.sharedClick,
                ),
        )
    }

    if (m.alpha > 0f) {
        val compactX = m.w - (if (m.compactWindow) m.normalMargin + m.buttonSize else 68.dp)
        val compactY = m.smallY + (m.small - m.buttonSize) / 2
        val moreX: Dp
        val moreY: Dp
        if (landscapeGeom != null) {
            val normalMoreX = if (m.coverflowActive) {
                m.w - m.effectiveRightInset - 20.dp - m.buttonSize
            } else {
                landscapeGeom.rightPaneX + landscapeGeom.rightPaneWidth - m.buttonSize
            }
            val compactMoreX = landscapeGeom.leftPaneX + landscapeGeom.leftPaneWidth - m.buttonSize
            val normalMoreY = if (m.coverflowActive) {
                m.coverThemeHeaderTop
            } else {
                landscapeGeom.controlStackTop + (measuredTextHeight - m.buttonSize) / 2
            }
            val compactMoreY = landscapeGeom.lyricsCompactCoverY + (landscapeGeom.lyricsCompactCoverSize - m.buttonSize) / 2
            moreX = lerp(normalMoreX, compactMoreX, m.p)
            moreY = lerp(normalMoreY, compactMoreY, m.p)
        } else {
            val useRightCornerAnchor = m.squareCardFullscreen || m.compactWindow || m.coverSize < m.w * .66f
            val bigX = if (m.coverflowActive) {
                m.w - m.effectiveRightInset - 20.dp - m.buttonSize
            } else if (m.squareCardFullscreen) {
                m.fullCard.left + m.fullCard.width - 16.dp - m.buttonSize
            } else if (immGeom != null) {
                m.portraitColX + m.portraitColW - m.buttonSize
            } else if (useRightCornerAnchor) {
                maxOf(m.w / 2 + 52.dp, m.w - m.normalMargin - m.buttonSize).coerceAtMost(m.w - m.buttonSize - 6.dp)
            } else {
                m.coverX + m.coverSize - m.buttonSize
            }
            val bigY = if (m.coverflowActive) {
                m.coverThemeHeaderTop
            } else if (m.squareCardFullscreen || immGeom != null) {
                m.titleY + (measuredTextHeight - m.buttonSize) / 2
            } else {
                m.effectiveStatus + (if (m.compactWindow) 2.dp else 8.dp)
            }
            moreX = bigX + (compactX - bigX) * m.p
            moreY = bigY + (compactY - bigY) * m.p
        }
        val cubeModeVis = (1f - (m.p / 0.72f).coerceIn(0f, 1f))
        val cubeRevealVis = if (m.usesStageDock) m.stageRevealAlpha else 1f
        val cubeCombinedVis = cubeModeVis * cubeRevealVis
        if (m.hasCubeOnTitleRow && cubeCombinedVis > 0.001f) {
            val cubeX: Dp
            val cubeY: Dp
            if (coverThemeDockLayout != null) {
                cubeX = dockMetaX + dockMetaRowW - m.buttonSize
                cubeY = dockMetaY + (dockMetaH - m.buttonSize) / 2f
            } else if (landscapeGeom != null) {
                cubeX = landscapeGeom.rightPaneX + landscapeGeom.rightPaneWidth - m.buttonSize * 2 - m.cubeGap
                cubeY = landscapeGeom.controlStackTop + (measuredTextHeight - m.buttonSize) / 2
            } else if (m.squareCardFullscreen) {
                cubeX = m.fullCard.left + m.fullCard.width - 16.dp - m.buttonSize * 2 - m.cubeGap
                cubeY = m.titleY + (measuredTextHeight - m.buttonSize) / 2
            } else if (immGeom != null) {
                cubeX = m.portraitColX + m.portraitColW - m.buttonSize * 2 - m.cubeGap
                cubeY = m.titleY + (measuredTextHeight - m.buttonSize) / 2
            } else {
                cubeX = m.portraitColX + m.portraitColW - m.buttonSize
                cubeY = m.titleY + (measuredTextHeight - m.buttonSize) / 2
            }
            val cubeBlur = ((1f - cubeCombinedVis) * 14f).dp + m.shareChromeBlur
            val cubeGenerating = spatialCoverState is SpatialCoverResolveState.Generating
            PlayerSpatialCubeButton(
                active = spatialCoverRequested,
                generating = cubeGenerating,
                expressive = expressive,
                oneUi = oneUi,
                miuixBackdrop = miuixBackdrop,
                menuTint = menuTint,
                inkColor = m.activeInk,
                enabled = !m.shareActive && !tiledWallDragging && cubeCombinedVis > 0.1f,
                onClick = onToggleSpatialCover,
                modifier = Modifier
                    .offset(cubeX, cubeY)
                    .size(m.buttonSize)
                    .graphicsLayer {
                        val visScale = cubeCombinedVis * tiledChromeAlpha
                        this.alpha = m.alpha * m.shareChromeAlpha * visScale
                        scaleX = 0.84f + 0.16f * visScale
                        scaleY = scaleX
                    }
                    .blur(cubeBlur, BlurredEdgeTreatment.Unbounded),
            )
        }
        val moreVis = if (landscapeGeom != null) (1f - m.p * (1f - m.controlsVisibility)) else 1f
        if (moreVis > 0.001f) {
            PlayerMoreActionButton(
                expressive = expressive,
                oneUi = oneUi,
                miuixBackdrop = miuixBackdrop,
                menuTint = menuTint,
                enabled = !m.shareActive && !tiledWallDragging && moreVis > 0.1f,
                onMore = onMore,
                modifier = Modifier
                    .offset(moreX, moreY)
                    .size(m.buttonSize)
                    .graphicsLayer {
                        this.alpha = m.alpha * m.shareChromeAlpha * moreVis * tiledChromeAlpha
                        scaleX = 1f - .08f * m.p
                        scaleY = scaleX
                    }
                    .blur(m.shareChromeBlur + if (landscapeGeom != null) ((1f - moreVis) * 14f).dp else 0.dp),
            )
        }
    }

    if (m.alpha > 0f && m.p > 0f && queueBlend < .01f && m.controlsVisibility > .001f) {
        val menuAnchorX = if (landscapeGeom != null) {
            landscapeGeom.rightPaneX
        } else if (m.squareCardFullscreen) {
            m.margin
        } else {
            m.coverX
        }
        val menuAnchorY = if (landscapeGeom != null) {
            landscapeGeom.usableBottom - 46.dp
        } else {
            m.seekY - 50.dp
        }
        val menuMaxTop = if (landscapeGeom != null) landscapeGeom.usableTop else m.effectiveStatus + 8.dp
        LyricOptionsMenu(
            model,
            miuixBackdrop,
            menuTint,
            menuAnchorX,
            menuAnchorY,
            m.w,
            menuMaxTop,
            Modifier
                .graphicsLayer { this.alpha = m.alpha * m.p * m.controlsVisibility * m.shareChromeAlpha }
                .blur(((1f - m.p + 1f - m.controlsVisibility) * 14f).dp + m.shareChromeBlur),
            onLyricMenuExpanded,
        )
        val singAnchorX = if (landscapeGeom != null) {
            landscapeGeom.rightPaneX + landscapeGeom.rightPaneWidth - 44.dp
        } else {
            m.w - m.margin - 44.dp
        }
        AuraSingControl(
            backdrop = miuixBackdrop,
            monetTint = menuTint,
            anchorX = singAnchorX,
            anchorY = menuAnchorY,
            topInset = menuMaxTop,
            paneLeftX = menuAnchorX,
            modifier = Modifier
                .graphicsLayer { this.alpha = m.alpha * m.p * m.controlsVisibility * m.shareChromeAlpha }
                .blur(((1f - m.p + 1f - m.controlsVisibility) * 14f).dp + m.shareChromeBlur),
        )
    }
}

@Composable
internal fun PlayerProgressSection(
    model: AuraViewModel,
    song: btm.m.auraplayer.data.Song,
    appearance: NowPlayingConfig,
    coverPalette: com.materialkolor.palettes.CorePalette,
    expressive: PlayerExpressiveColors?,
    oneUi: PlayerOneUiColors?,
    backdrop: Backdrop,
    active: Boolean,
    queueOpen: Boolean,
    controlsVisibility: Float,
    sliderHeight: Dp,
    footerHeight: Dp,
    horizontalInset: Dp,
    onQualityMenuExpanded: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrub by remember(song.key) { mutableStateOf<Float?>(null) }
    val value = scrub ?: if (model.duration > 0) model.position.toFloat() / model.duration else 0f
    val sliderMod = Modifier
        .padding(horizontal = horizontalInset)
        .fillMaxWidth()
        .height(sliderHeight)
        .then(if (queueOpen) Modifier.clearAndSetSemantics {} else Modifier)
    Column(modifier) {
        val surfaceStyle = LocalPlayerSurfaceStyle.current
        when {
            appearance.glowProgress != GlowProgressStyle.OFF -> {
                val isLightGlowSurface = (expressive?.dark == false) || (oneUi?.dark == false) || (!surfaceStyle.isDark && expressive == null && oneUi == null)
                val glowAccent = oneUi?.accentDeep
                    ?: expressive?.primary
                    ?: surfaceStyle.glowAccent
                    ?: vibrantCoverGlowColor(coverPalette, isLightSurface = isLightGlowSurface)
                val glowTrack = when {
                    oneUi != null -> oneUi.track.copy(alpha = if (oneUi.dark) .75f else .85f)
                    expressive != null -> expressive.surfaceContainerHighest.copy(alpha = if (expressive.dark) .55f else .85f)
                    else -> surfaceStyle.activeInk.copy(alpha = if (isLightGlowSurface) .18f else .28f)
                }
                GlowProgressSlider(
                    style = appearance.glowProgress,
                    value = value,
                    playing = model.playing && scrub == null,
                    label = tr("playback_progress", "播放进度"),
                    accentColor = glowAccent,
                    trackColor = glowTrack,
                    isLightSurface = isLightGlowSurface,
                    waveform = model.app.effects.outputWaveform,
                    active = active && !queueOpen && controlsVisibility > .01f,
                    modifier = sliderMod,
                    onChange = { scrub = it },
                    onFinished = { model.seekTo(((scrub ?: value) * model.duration).toLong()); scrub = null },
                    enabled = model.duration > 0 && !queueOpen,
                )
            }
            expressive != null -> MaterialWavyProgressSlider(
                value = value,
                playing = model.playing && scrub == null,
                label = tr("playback_progress", "播放进度"),
                activeColor = expressive.primary,
                trackColor = expressive.surfaceContainerHighest.copy(alpha = if (expressive.dark) .55f else .85f),
                modifier = sliderMod,
                onChange = { scrub = it },
                onFinished = { model.seekTo(((scrub ?: value) * model.duration).toLong()); scrub = null },
                enabled = model.duration > 0 && !queueOpen,
            )
            oneUi != null -> OneUiPlayerProgressSlider(
                value = value,
                label = tr("playback_progress", "播放进度"),
                colors = oneUi,
                modifier = sliderMod,
                onChange = { scrub = it },
                onFinished = { model.seekTo(((scrub ?: value) * model.duration).toLong()); scrub = null },
                enabled = model.duration > 0 && !queueOpen,
            )
            else -> PillSlider(
                value = value,
                label = tr("playback_progress", "播放进度"),
                modifier = sliderMod,
                onChange = { scrub = it },
                onFinished = { model.seekTo(((scrub ?: value) * model.duration).toLong()); scrub = null },
                enabled = model.duration > 0 && !queueOpen,
                inkColor = surfaceStyle.activeInk,
            )
        }
        Box(
            Modifier
                .padding(horizontal = horizontalInset)
                .fillMaxWidth()
                .heightIn(min = maxOf(footerHeight, 24.dp)),
        ) {
            val timeColor = expressive?.timeInk ?: oneUi?.subText ?: surfaceStyle.secondaryInk
            val timeStyle = if (expressive != null || oneUi != null) {
                androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
            } else {
                androidx.compose.ui.text.TextStyle.Default
            }
            Text(
                timeText(((scrub?.let { it * model.duration } ?: model.position.toFloat())).toLong()),
                color = timeColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                style = timeStyle,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                timeText(model.duration),
                color = timeColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                style = timeStyle,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            val actualQuality = model.streamInfo?.level?.let { level -> StreamQuality.entries.firstOrNull { it.level == level } }
            val usbStatus by model.app.effects.usbStatus.collectAsState()
            val usbExclusivePrefix = if (usbStatus is UsbExclusiveStatus.Active) "${tr("player_usb_exclusive_badge", "USB 独占")} · " else ""
            if (appearance.showAudioInfo && song.cloud) {
                val qualityItems = buildList {
                    if (model.qualityLoading) add(DropdownItem(tr("loading", "加载中…"), enabled = false))
                    if (model.qualityError) add(DropdownItem(tr("quality_retry", "获取音质失败，点击重试"), onClick = model::refreshQualities))
                    val menuQualities = model.availableQualities.ifEmpty { actualQuality?.let { listOf(it) }.orEmpty() }
                    menuQualities.forEach { item ->
                        add(DropdownItem(qualityName(item), selected = item == (actualQuality ?: model.quality), onClick = { model.selectQuality(item) }))
                    }
                    if (isEmpty()) add(DropdownItem(tr("quality_unavailable", "此歌曲暂无可用音质"), enabled = false))
                }
                GlassIconDropdownMenu(
                    entry = DropdownEntry(qualityItems),
                    backdrop = backdrop,
                    modifier = Modifier.align(Alignment.Center),
                    enabled = !queueOpen,
                    onExpandedChange = onQualityMenuExpanded,
                    trigger = { toggle, enabled ->
                        val badgeShape = RoundedCornerShape(if (expressive != null || oneUi != null) 50.dp else 8.dp)
                        val badgeBg = when {
                            expressive != null -> expressive.tertiaryContainer
                            oneUi != null -> oneUi.cardAlt
                            else -> null
                        }
                        val badgeFg = when {
                            expressive != null -> expressive.onTertiaryContainer
                            oneUi != null -> oneUi.accentDeep
                            else -> timeColor
                        }
                        Box(
                            Modifier
                                .clip(badgeShape)
                                .then(if (badgeBg != null) Modifier.background(badgeBg) else Modifier)
                                .clickable(enabled = enabled) {
                                    model.refreshQualities()
                                    toggle()
                                }
                                .padding(horizontal = if (badgeBg != null) 11.dp else 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                usbExclusivePrefix + qualityName(actualQuality ?: model.quality),
                                color = badgeFg,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    },
                    content = {},
                )
            } else if (appearance.showAudioInfo) {
                Text(
                    usbExclusivePrefix + localAudioText(model),
                    color = timeColor,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 45.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayerTransportSection(
    model: AuraViewModel,
    expressive: PlayerExpressiveColors?,
    oneUi: PlayerOneUiColors?,
    scale: Float,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(rowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        if (expressive != null) {
            MaterialExpressiveTransportButton(
                icon = R.drawable.ic_player_previous,
                label = tr("previous_track", "上一首"),
                containerColor = expressive.secondaryContainer,
                contentColor = expressive.onSecondaryContainer,
                width = 60.dp * scale,
                height = 64.dp * scale,
                iconSize = 36.dp * scale * PlayerIconScale,
                onClick = model::previous,
            )
            MaterialExpressivePlayPauseButton(
                playing = model.playbackRequested,
                label = if (model.playbackRequested) tr("pause", "暂停") else tr("play", "播放"),
                colors = expressive,
                touchHeight = 76.dp * scale,
                pausedWidth = 86.dp * scale,
                playingWidth = 108.dp * scale,
                iconSize = 44.dp * scale * PlayerIconScale,
                onClick = model::togglePlayback,
            )
            MaterialExpressiveTransportButton(
                icon = R.drawable.ic_player_next,
                label = tr("next_track", "下一首"),
                containerColor = expressive.secondaryContainer,
                contentColor = expressive.onSecondaryContainer,
                width = 60.dp * scale,
                height = 64.dp * scale,
                iconSize = 36.dp * scale * PlayerIconScale,
                onClick = model::next,
            )
        } else if (oneUi != null) {
            OneUiPlayerTransportButton(
                icon = R.drawable.ic_player_previous,
                label = tr("previous_track", "上一首"),
                colors = oneUi,
                touchSize = 60.dp * scale,
                iconSize = 34.dp * scale * PlayerIconScale,
                onClick = model::previous,
            )
            OneUiPlayerPlayPauseButton(
                playing = model.playbackRequested,
                label = if (model.playbackRequested) tr("pause", "暂停") else tr("play", "播放"),
                colors = oneUi,
                touchSize = 76.dp * scale,
                iconSize = 44.dp * scale * PlayerIconScale,
                onClick = model::togglePlayback,
            )
            OneUiPlayerTransportButton(
                icon = R.drawable.ic_player_next,
                label = tr("next_track", "下一首"),
                colors = oneUi,
                touchSize = 60.dp * scale,
                iconSize = 34.dp * scale * PlayerIconScale,
                onClick = model::next,
            )
        } else {
            val ink = LocalPlayerSurfaceStyle.current.activeInk
            PlayerButton(R.drawable.ic_player_previous, tr("previous_track", "上一首"), ink, 48.dp * scale, 76.dp * scale, model::previous)
            PlayerButton(
                if (model.playbackRequested) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                if (model.playbackRequested) tr("pause", "暂停") else tr("play", "播放"),
                ink,
                60.dp * .88f * scale,
                96.dp * scale,
                model::togglePlayback,
            )
            PlayerButton(R.drawable.ic_player_next, tr("next_track", "下一首"), ink, 48.dp * scale, 76.dp * scale, model::next)
        }
    }
}

@Composable
internal fun PlayerVolumeSection(
    expressive: PlayerExpressiveColors?,
    oneUi: PlayerOneUiColors?,
    height: Dp,
) {
    val volMod = Modifier.fillMaxWidth().height(height)
    when {
        expressive != null -> MaterialExpressiveVolumeSlider(colors = expressive, modifier = volMod)
        oneUi != null -> OneUiPlayerVolumeSlider(colors = oneUi, modifier = volMod)
        else -> VolumeControl(volMod)
    }
}

@Composable
private fun PlayerBottomActionsSection(
    model: AuraViewModel,
    song: btm.m.auraplayer.data.Song,
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    expressive: PlayerExpressiveColors?,
    oneUi: PlayerOneUiColors?,
    rowHeight: Dp,
    bottomTouch: Dp,
    bottomIcon: Dp,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(rowHeight),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
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
        val revision = model.history.revision
        val favorite = model.isFavorite(song)
        if (expressive != null) {
            Box(Modifier.semantics { selected = lyricsOpen }) {
                MaterialExpressiveBottomButton(
                    if (lyricsOpen) R.drawable.ic_player_lyrics_on else R.drawable.ic_player_lyrics,
                    tr("lyrics", "歌词"), lyricsOpen, expressive, bottomTouch, bottomIcon * PlayerIconScale, onClick = onLyrics,
                )
            }
            MaterialExpressiveBottomButton(
                modeIcon, modeDescription, false, expressive, bottomTouch, bottomIcon * PlayerIconScale, onClick = model::cyclePlaybackMode,
            )
            MaterialExpressiveBottomButton(
                if (favorite) R.drawable.ic_player_favorite_on else R.drawable.ic_player_favorite,
                if (favorite) tr("unfavorite", "取消收藏") else tr("favorite", "收藏"),
                favorite, expressive, bottomTouch, bottomIcon * PlayerIconScale, useTertiary = true,
            ) { model.toggleFavorite(song) }
            Box(Modifier.semantics { selected = queueOpen }) {
                MaterialExpressiveBottomButton(
                    if (queueOpen) R.drawable.ic_player_queue_on else R.drawable.ic_player_queue,
                    tr("play_queue", "播放队列"), queueOpen, expressive, bottomTouch, bottomIcon * PlayerIconScale, onClick = onQueue,
                )
            }
        } else if (oneUi != null) {
            Box(Modifier.semantics { selected = lyricsOpen }) {
                OneUiPlayerBottomButton(
                    if (lyricsOpen) R.drawable.ic_player_lyrics_on else R.drawable.ic_player_lyrics,
                    tr("lyrics", "歌词"), lyricsOpen, oneUi, bottomTouch, bottomIcon * PlayerIconScale, onClick = onLyrics,
                )
            }
            OneUiPlayerBottomButton(
                modeIcon, modeDescription, false, oneUi, bottomTouch, bottomIcon * PlayerIconScale, onClick = model::cyclePlaybackMode,
            )
            OneUiPlayerBottomButton(
                if (favorite) R.drawable.ic_player_favorite_on else R.drawable.ic_player_favorite,
                if (favorite) tr("unfavorite", "取消收藏") else tr("favorite", "收藏"),
                favorite, oneUi, bottomTouch, bottomIcon * PlayerIconScale,
            ) { model.toggleFavorite(song) }
            Box(Modifier.semantics { selected = queueOpen }) {
                OneUiPlayerBottomButton(
                    if (queueOpen) R.drawable.ic_player_queue_on else R.drawable.ic_player_queue,
                    tr("play_queue", "播放队列"), queueOpen, oneUi, bottomTouch, bottomIcon * PlayerIconScale, onClick = onQueue,
                )
            }
        } else {
            val ink = LocalPlayerSurfaceStyle.current.activeInk.copy(alpha = .9f)
            Box(Modifier.size(bottomTouch).semantics { selected = lyricsOpen }) {
                PlayerButton(if (lyricsOpen) R.drawable.ic_player_lyrics_on else R.drawable.ic_player_lyrics, tr("lyrics", "歌词"), ink, bottomIcon, bottomTouch, onLyrics)
            }
            PlayerButton(modeIcon, modeDescription, ink, bottomIcon, bottomTouch, model::cyclePlaybackMode)
            PlayerButton(
                if (favorite) R.drawable.ic_player_favorite_on else R.drawable.ic_player_favorite,
                if (favorite) tr("unfavorite", "取消收藏") else tr("favorite", "收藏"),
                ink,
                bottomIcon,
                bottomTouch,
            ) { model.toggleFavorite(song) }
            Box(Modifier.semantics { selected = queueOpen }) {
                PlayerButton(if (queueOpen) R.drawable.ic_player_queue_on else R.drawable.ic_player_queue, tr("play_queue", "播放队列"), ink, bottomIcon, bottomTouch, onQueue)
            }
        }
    }
}

@Composable
private fun PlayerMoreActionButton(
    expressive: PlayerExpressiveColors?,
    oneUi: PlayerOneUiColors?,
    miuixBackdrop: top.yukonga.miuix.kmp.blur.Backdrop,
    menuTint: Color,
    enabled: Boolean,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.clip(RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
        when {
            expressive != null -> MaterialExpressiveMoreButton(
                label = tr("more", "更多"),
                colors = expressive,
                buttonSize = 46.dp * .85f * .92f,
                iconSize = 24.dp * PlayerIconScale * .92f,
                enabled = enabled,
                onClick = onMore,
            )
            oneUi != null -> OneUiPlayerMoreButton(
                label = tr("more", "更多"),
                colors = oneUi,
                buttonSize = 46.dp * .85f * .92f,
                iconSize = 24.dp * PlayerIconScale * .92f,
                enabled = enabled,
                onClick = onMore,
            )
            else -> Box(Modifier.fillMaxSize().clickable(enabled = enabled, onClick = onMore), contentAlignment = Alignment.Center) {
                val ink = LocalPlayerSurfaceStyle.current.activeInk
                Box(Modifier.size(46.dp * .85f * .92f).playerMenuMaterial(miuixBackdrop, menuTint, 50.dp)) {
                    Image(
                        AuraMoreHorizontalIcon,
                        tr("more", "更多"),
                        Modifier.align(Alignment.Center).size(24.dp * PlayerIconScale * .92f),
                        colorFilter = ColorFilter.tint(ink),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerSpatialCubeButton(
    active: Boolean,
    generating: Boolean,
    expressive: PlayerExpressiveColors?,
    oneUi: PlayerOneUiColors?,
    miuixBackdrop: top.yukonga.miuix.kmp.blur.Backdrop,
    menuTint: Color,
    inkColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconAlpha by animateFloatAsState(
        targetValue = when {
            generating -> 0.52f
            active -> 1f
            else -> 0.56f
        },
        animationSpec = tween(220),
        label = "spatialCubeIconAlpha",
    )
    val isDarkSurface = LocalPlayerSurfaceStyle.current.isDark
    val bgTint = when {
        expressive != null && active -> expressive.primaryContainer
        expressive != null -> expressive.surfaceContainerHigh
        oneUi != null && active -> oneUi.chip
        oneUi != null -> oneUi.cardAlt
        active -> inkColor.copy(alpha = if (isDarkSurface) 0.24f else 0.14f)
        else -> menuTint
    }
    val tintColor = when {
        expressive != null && active -> expressive.onPrimaryContainer
        expressive != null -> inkColor
        oneUi != null && active -> oneUi.onChip
        oneUi != null -> inkColor
        else -> inkColor
    }.copy(alpha = iconAlpha)
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(46.dp * .85f * .92f)
                .then(
                    if (expressive != null || oneUi != null) {
                        Modifier.clip(CircleShape).background(bgTint)
                    } else {
                        Modifier.playerMenuMaterial(miuixBackdrop, bgTint, 50.dp)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_player_cube),
                tr("player_spatial_cover", "空间封面效果"),
                Modifier.size(21.dp * PlayerIconScale),
                colorFilter = ColorFilter.tint(tintColor),
            )
        }
    }
}

internal fun shouldUseImmersiveCover(
    immersiveCoverSetting: Boolean,
    appleMusicAnimatedCoverEnabled: Boolean,
    appleMusicAnimatedCoverAvailable: Boolean,
    fullscreen: Boolean,
    @Suppress("UNUSED_PARAMETER") hasLandscapeGeometry: Boolean = false,
): Boolean =
    (immersiveCoverSetting || (appleMusicAnimatedCoverEnabled && appleMusicAnimatedCoverAvailable)) &&
        !fullscreen

internal fun shouldShowSpatialCoverButton(
    spatialCoverEnabled: Boolean,
    appleMusicAnimatedCoverEnabled: Boolean,
    motionVideoPath: String?,
): Boolean = spatialCoverEnabled && !(appleMusicAnimatedCoverEnabled && !motionVideoPath.isNullOrBlank())

internal fun immersiveCoverBottomCornerRadius(
    miniCorner: Dp,
    lyricsCorner: Dp,
    expansion: Float,
    lyricsProgress: Float,
): Dp = lerp(
    miniCorner,
    lerp(0.dp, lyricsCorner, lyricsProgress.coerceIn(0f, 1f)),
    expansion.coerceIn(0f, 1f),
)

private fun coverSmoothstep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun computeMotionCoverOverlayAlpha(
    expansion: Float,
    lyricsProgress: Float,
    mixActive: Boolean,
    spatialActive: Boolean,
): Float {
    if (mixActive || spatialActive) return 0f
    val expandFade = coverSmoothstep(0.14f, 0.56f, expansion)
    val lyricsFade = 1f - coverSmoothstep(0.08f, 0.52f, lyricsProgress)
    return (expandFade * lyricsFade).coerceIn(0f, 1f)
}

@Composable
internal fun Cover(uri: String, modifier: Modifier, radius: Dp, bottomRadius: Dp = radius,
                  topEndRadius: Dp = radius, bottomEndRadius: Dp = bottomRadius,
                  onClick: (() -> Unit)? = null,
                  animation: CoverAnimation = CoverAnimation.DEFAULT, animate: Boolean = false, transitionKey: String = uri,
                  mixVisual: btm.m.auraplayer.auramix.AuraMixVisualTransition? = null,
                  placeholderTint: Color = Color.White.copy(alpha = .3f),
                  showPlaceholderBackground: Boolean = true,
                  spatialState: SpatialCoverResolveState = SpatialCoverResolveState.Idle,
                  spatialActive: Boolean = false,
                  spatialTilt: Offset = Offset.Zero,
                  immersiveFeather: Float = 0f,
                  immersiveHorizontal: Boolean = false,
                  motionVideoPath: String? = null,
                  motionPlaying: Boolean = animate,
                  motionOverlayAlpha: Float = 1f) {
    val progress = mixVisual?.progress?.coerceIn(0f, 1f)
    val isGenerating = spatialActive && spatialState is SpatialCoverResolveState.Generating && mixVisual == null
    val generatingProgress = (spatialState as? SpatialCoverResolveState.Generating)?.progress ?: 0f
    val spatialAssets = (spatialState as? SpatialCoverResolveState.Ready)?.assets
    val coverBlurDp by animateFloatAsState(
        targetValue = if (isGenerating) 22f else 0f,
        animationSpec = tween(durationMillis = if (isGenerating) 380 else 440, easing = FastOutSlowInEasing),
        label = "spatialCoverComputeBlur",
    )
    val progressOverlayAlpha by animateFloatAsState(
        targetValue = if (isGenerating) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "spatialProgressOverlayAlpha",
    )
    val animatedGenProgress by animateFloatAsState(
        targetValue = generatingProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "spatialGenProgressBar",
    )
    val showSpatial by animateFloatAsState(
        targetValue = if (spatialActive && spatialAssets != null && mixVisual == null) 1f else 0f,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "spatialCoverCrossfade",
    )
    val feather = immersiveFeather.coerceIn(0f, 1f)
    val coverBackdrop = rememberMiuixBackdrop { drawContent() }
    val featherMask = if (feather > 0.001f) {
        Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fadeStart = 1f - 0.42f * feather
                val mid1 = 1f - 0.28f * feather
                val mid2 = 1f - 0.13f * feather
                val endAlpha = 1f - feather
                val stops = arrayOf(
                    0f to Color.Black,
                    fadeStart to Color.Black,
                    mid1 to Color.Black.copy(alpha = 1f - 0.26f * feather),
                    mid2 to Color.Black.copy(alpha = 1f - 0.76f * feather),
                    1f to Color.Black.copy(alpha = endAlpha),
                )
                drawRect(
                    brush = if (immersiveHorizontal) {
                        Brush.horizontalGradient(*stops)
                    } else {
                        Brush.verticalGradient(*stops)
                    },
                    blendMode = BlendMode.DstIn,
                )
            }
    } else {
        Modifier
    }
    val coverShape = RoundedCornerShape(topStart = radius, topEnd = topEndRadius, bottomStart = bottomRadius, bottomEnd = bottomEndRadius)
    val placeholderColor = if (showPlaceholderBackground) Color.White.copy(alpha = .12f * (1f - feather)) else Color.Transparent
    val adaptIrregular = LocalAdaptIrregularCovers.current && feather <= 0.001f && !immersiveHorizontal && uri.isNotBlank()
    val cachedRatio = rememberCoverAspectRatio(uri, enabled = adaptIrregular)
    var measuredRatio by remember(uri, adaptIrregular) { mutableStateOf<Float?>(null) }
    val coverAspectRatio = measuredRatio ?: cachedRatio
    Box(
        modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .then(if (adaptIrregular) Modifier.fitCoverAspectRatio(coverAspectRatio) else Modifier.fillMaxSize())
                .then(if (onClick == null) Modifier else Modifier.playerSurfaceClick(onClick, coverShape))
                .then(featherMask),
            contentAlignment = Alignment.Center,
        ) {
        if (mixVisual == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (coverBlurDp > 0.01f) Modifier.clip(coverShape) else Modifier)
                    .blur(coverBlurDp.dp),
            ) {
                val advancedMaterial = btm.m.auraplayer.ui.LocalAdvancedMaterialEnabled.current
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (feather > 0.001f && advancedMaterial) Modifier.miuixLayerBackdrop(coverBackdrop) else Modifier),
                ) {
                    if (uri.isNotBlank()) {
                        AnimatedPlayerArtwork(
                            artwork = uri,
                            transitionKey = transitionKey,
                            animation = animation,
                            enabled = animate,
                            shape = coverShape,
                            placeholderBackgroundColor = placeholderColor,
                            onAspectRatioResolved = if (adaptIrregular) ({ measuredRatio = it }) else null,
                        )
                    } else if (showPlaceholderBackground) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(coverShape)
                                .background(placeholderColor),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(painterResource(R.drawable.ic_player_queue), null, Modifier.fillMaxSize(.3f * PlayerIconScale), colorFilter = ColorFilter.tint(placeholderTint))
                        }
                    }
                    if (!motionVideoPath.isNullOrBlank()) {
                        AnimatedMotionCoverVideo(
                            videoPath = motionVideoPath,
                            playing = motionPlaying && !(spatialActive && spatialAssets != null),
                            visibleAlpha = motionOverlayAlpha,
                            modifier = Modifier.fillMaxSize().clip(coverShape),
                        )
                    }
                    if (spatialAssets != null && showSpatial > 0.001f) {
                        Box(Modifier.fillMaxSize().clip(coverShape).graphicsLayer { this.alpha = showSpatial }) {
                            SpatialCoverRenderer(
                                assets = spatialAssets,
                                tiltProvider = { spatialTilt },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                if (feather > 0.01f && advancedMaterial) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .clip(coverShape)
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .progressiveTextureBlur(
                                backdrop = coverBackdrop,
                                shape = RectangleShape,
                                blurRadius = 3.8f * feather,
                                gradient = if (immersiveHorizontal) ProgressiveBlur.Right else ProgressiveBlur.Bottom,
                            ).drawWithContent {
                                drawContent()
                                val blurStops = arrayOf(
                                    0f to Color.Transparent,
                                    .54f to Color.Transparent,
                                    .92f to Color.White.copy(alpha = feather),
                                    1f to Color.White.copy(alpha = feather),
                                )
                                drawRect(
                                    if (immersiveHorizontal) Brush.horizontalGradient(*blurStops) else Brush.verticalGradient(*blurStops),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                    )
                }
            }
            if (progressOverlayAlpha > 0.01f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(coverShape)
                        .background(Color.Black.copy(alpha = 0.36f * progressOverlayAlpha))
                        .graphicsLayer { this.alpha = progressOverlayAlpha },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            tr("spatial_generating_label", "正在计算空间效果"),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            "${(animatedGenProgress * 100f).roundToInt().coerceIn(0, 100)}%",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Box(
                            Modifier
                                .width(120.dp)
                                .height(5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.28f)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(animatedGenProgress.coerceIn(0.04f, 1f))
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White),
                            )
                        }
                    }
                }
            }
        } else {
            val fromAlpha = 1f - progress!!
            val toAlpha = progress
            val context = LocalContext.current
            val fromReq = remember(mixVisual.fromArtwork, context) {
                if (mixVisual.fromArtwork.isNotBlank()) ImageRequest.Builder(context).data(mixVisual.fromArtwork).size(1600).precision(Precision.INEXACT).build() else null
            }
            val toReq = remember(mixVisual.toArtwork, context) {
                if (mixVisual.toArtwork.isNotBlank()) ImageRequest.Builder(context).data(mixVisual.toArtwork).size(1600).precision(Precision.INEXACT).build() else null
            }
            Box(Modifier.fillMaxSize().clip(coverShape).graphicsLayer { alpha = fromAlpha }.blur((progress * 26f).dp)) {
                if (fromReq != null) AsyncImage(fromReq, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else if (uri.isNotBlank()) AnimatedPlayerArtwork(uri, transitionKey, animation, animate, shape = coverShape, placeholderBackgroundColor = placeholderColor)
            }
            Box(Modifier.fillMaxSize().clip(coverShape).graphicsLayer { alpha = toAlpha }.blur(((1f - progress) * 26f).dp)) {
                if (toReq != null) AsyncImage(toReq, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        }
    }
}

private data class QueueDisplayItem(
    val id: String,
    val originalIndex: Int,
    val song: btm.m.auraplayer.data.Song,
)

private fun buildQueueDisplayItems(songs: List<btm.m.auraplayer.data.Song>): List<QueueDisplayItem> {
    val counts = HashMap<String, Int>(songs.size)
    return songs.mapIndexed { index, song ->
        val occurrence = counts.getOrDefault(song.key, 0)
        counts[song.key] = occurrence + 1
        QueueDisplayItem(
            id = "${song.key}#$occurrence",
            originalIndex = index,
            song = song,
        )
    }
}

private fun swapQueueDisplayItems(
    items: List<QueueDisplayItem>,
    firstId: String,
    secondId: String,
): List<QueueDisplayItem> {
    val mutable = items.toMutableList()
    val a = mutable.indexOfFirst { it.id == firstId }
    val b = mutable.indexOfFirst { it.id == secondId }
    if (a < 0 || b < 0 || a == b) return items
    val tmp = mutable[a]
    mutable[a] = mutable[b]
    mutable[b] = tmp
    return mutable
}

@Composable
private fun QueueSwipeRow(
    song: btm.m.auraplayer.data.Song,
    current: Boolean,
    index: Int,
    model: AuraViewModel,
    dragging: Boolean,
    backdrop: Backdrop?,
    rowModifier: Modifier = Modifier,
    reorderHandle: (Modifier) -> Modifier,
    expressive: PlayerExpressiveColors? = null,
    oneUi: PlayerOneUiColors? = null,
) {
    val surfaceStyle = LocalPlayerSurfaceStyle.current
    val ink = expressive?.activeInk ?: oneUi?.text ?: surfaceStyle.activeInk
    val advancedMaterial = LocalAdvancedMaterialEnabled.current
    val rowShape = RoundedCornerShape(14.dp)
    val rowHighlight = when {
        expressive != null && dragging -> expressive.surfaceContainerHighest.copy(alpha = if (backdrop != null && advancedMaterial) .48f else .85f)
        expressive != null && current -> expressive.secondaryContainer.copy(alpha = if (backdrop != null && advancedMaterial) .55f else .85f)
        oneUi != null && dragging -> oneUi.cardAlt.copy(alpha = if (backdrop != null && advancedMaterial) .52f else 1f)
        oneUi != null && current -> oneUi.chip.copy(alpha = if (backdrop != null && advancedMaterial) .56f else 1f)
        dragging -> if (surfaceStyle.isDark) Color.White.copy(alpha = if (backdrop != null && advancedMaterial) .12f else .22f)
            else Color.White.copy(alpha = if (backdrop != null && advancedMaterial) .36f else .56f)
        current -> if (surfaceStyle.isDark) Color.White.copy(alpha = if (backdrop != null && advancedMaterial) .08f else .14f)
            else Color.White.copy(alpha = if (backdrop != null && advancedMaterial) .26f else .38f)
        else -> Color.Transparent
    }
    val rowTitleInk = when {
        expressive != null && current -> expressive.onSecondaryContainer
        oneUi != null && current -> oneUi.onChip
        else -> ink
    }
    val rowBgModifier = if ((dragging || current) && backdrop != null && advancedMaterial) {
        Modifier.drawPlainBackdrop(
            backdrop = backdrop,
            shape = { rowShape },
            effects = {
                vibrancy()
                backdropBlur(22.dp.toPx())
            },
            onDrawSurface = { drawRect(rowHighlight) },
        )
    } else {
        Modifier.background(rowHighlight, rowShape)
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val currentIndex by rememberUpdatedState(index)
    val deleteWidth = with(density) { 92.dp.toPx() }
    val offset = remember(song.key) { Animatable(0f) }
    val progress = (-offset.value / deleteWidth).coerceIn(0f, 1f)
    Box(
        rowModifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(rowShape)
            .then(if (dragging) Modifier.border(0.65.dp, ink.copy(alpha = 0.20f), rowShape) else Modifier),
    ) {
        Box(Modifier.fillMaxSize().background(ink.copy(alpha = if (progress > .001f) .08f + progress * .22f else 0f)), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.padding(end = 16.dp).size(38.dp).graphicsLayer {
                scaleX = .72f + .28f * progress; scaleY = .72f + .28f * progress
                alpha = progress; this.alpha = progress
            }.blur(((1f - progress) * 8f).dp).clip(CircleShape).background(ink.copy(alpha = .22f)), contentAlignment = Alignment.Center) {
                Image(MiuixIcons.Regular.Delete, tr("delete", "删除"), Modifier.size(21.dp), colorFilter = ColorFilter.tint(ink))
            }
        }
        Row(Modifier.fillMaxWidth().graphicsLayer { translationX = offset.value }
            .clip(rowShape)
            .then(rowBgModifier)
            .pointerInput(song.key, dragging) {
                if (dragging) return@pointerInput
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        if (amount < 0f || offset.value < -1f) { change.consume(); scope.launch { offset.snapTo((offset.value + amount).coerceIn(-deleteWidth, 0f)) } }
                    },
                    onDragEnd = {
                        scope.launch { if (offset.value <= -deleteWidth * .78f) { offset.animateTo(-deleteWidth, spring(.78f, 520f)); model.removeQueue(currentIndex) } else offset.animateTo(0f, spring(.82f, 380f)) }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, spring(.82f, 380f)) } },
                )
            }.clickable(enabled = !dragging) { if (offset.value > -1f) model.queueTo(currentIndex) }
            .padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(song.artwork, Modifier.size(44.dp), 8.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(song.title, color = rowTitleInk, fontSize = 15.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = rowTitleInk.copy(alpha = .66f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(reorderHandle(Modifier.size(40.dp, 44.dp).clip(CircleShape)).semantics {
                contentDescription = tr("reorder_queue", "调整播放顺序")
                customActions = listOf(
                    CustomAccessibilityAction(tr("move_up", "上移")) { if (currentIndex > 0) { model.moveQueue(currentIndex, currentIndex - 1); true } else false },
                    CustomAccessibilityAction(tr("move_down", "下移")) { if (currentIndex < model.queue.lastIndex) { model.moveQueue(currentIndex, currentIndex + 1); true } else false },
                )
            }, contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_player_reorder), null, Modifier.size(22.dp * PlayerIconScale), colorFilter = ColorFilter.tint(ink.copy(alpha = .42f)))
            }
        }
    }
}

@Composable
private fun PlayerQueue(
    model: AuraViewModel,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    horizontalPadding: Dp = 0.dp,
    expressive: PlayerExpressiveColors? = null,
    oneUi: PlayerOneUiColors? = null,
) {
    val visibleQueue = remember(model.queue, model.queueIndex, model.playbackMode) {
        if (model.playbackMode == btm.m.auraplayer.ROAMING_PLAYBACK_MODE) {
            model.queue.take((model.queueIndex + 1).coerceIn(0, model.queue.size))
        } else {
            model.queue
        }
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = model.queueIndex.coerceIn(0, visibleQueue.lastIndex.coerceAtLeast(0)))
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val itemSpacing = 2.dp
    val itemSpacingPx = with(density) { itemSpacing.toPx() }
    val rowShape = RoundedCornerShape(14.dp)

    var displayItems by remember { mutableStateOf(buildQueueDisplayItems(visibleQueue)) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var draggingOffsetY by remember { mutableFloatStateOf(0f) }
    var settlingId by remember { mutableStateOf<String?>(null) }
    val settlingOffset = remember { Animatable(0f) }
    var pendingExpectedKeys by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(visibleQueue) {
        if (draggingId != null) return@LaunchedEffect
        val incomingKeys = visibleQueue.map { it.key }
        val expected = pendingExpectedKeys
        if (expected != null && incomingKeys != expected) {
            delay(380L)
            if (draggingId != null) return@LaunchedEffect
        }
        pendingExpectedKeys = null
        displayItems = buildQueueDisplayItems(visibleQueue)
    }

    val currentItems by rememberUpdatedState(displayItems)

    fun startDrag(id: String) {
        val startIdx = currentItems.indexOfFirst { it.id == id }
        if (startIdx < 0) return
        settlingId = null
        scope.launch { settlingOffset.snapTo(0f) }
        draggingId = id
        dragStartIndex = startIdx
        draggingOffsetY = 0f
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun updateDrag(id: String, deltaY: Float) {
        if (draggingId != id) return
        draggingOffsetY += deltaY
        val activeItems = currentItems
        val currentIndex = activeItems.indexOfFirst { it.id == id }
        if (currentIndex < 0) return

        val visibleItems = list.layoutInfo.visibleItemsInfo
        val draggedInfo = visibleItems.firstOrNull { it.key == id } ?: return
        val layoutSynced = draggedInfo.index == currentIndex &&
            activeItems.getOrNull(currentIndex)?.id == id
        if (!layoutSynced) return

        val draggedCenterY = draggedInfo.offset + draggingOffsetY + draggedInfo.size / 2f

        if (draggingOffsetY > 0f && currentIndex < activeItems.lastIndex) {
            val nextItem = activeItems[currentIndex + 1]
            val nextInfo = visibleItems.firstOrNull { it.key == nextItem.id }
            if (nextInfo != null && draggedCenterY > nextInfo.offset + nextInfo.size / 2f) {
                displayItems = swapQueueDisplayItems(activeItems, id, nextItem.id)
                draggingOffsetY -= (nextInfo.size + itemSpacingPx)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                return
            }
        }

        if (draggingOffsetY < 0f && currentIndex > 0) {
            val prevItem = activeItems[currentIndex - 1]
            val prevInfo = visibleItems.firstOrNull { it.key == prevItem.id }
            if (prevInfo != null && draggedCenterY < prevInfo.offset + prevInfo.size / 2f) {
                displayItems = swapQueueDisplayItems(activeItems, id, prevItem.id)
                draggingOffsetY += (prevInfo.size + itemSpacingPx)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    fun finishDrag(id: String) {
        if (draggingId != id) return
        val settledFrom = draggingOffsetY
        val from = dragStartIndex
        val to = currentItems.indexOfFirst { it.id == id }
        draggingId = null
        dragStartIndex = -1
        draggingOffsetY = 0f
        if (from >= 0 && to >= 0 && from != to) {
            pendingExpectedKeys = currentItems.map { it.song.key }
            model.moveQueue(from, to)
        }
        if (abs(settledFrom) > 0.5f) {
            settlingId = id
            scope.launch {
                settlingOffset.snapTo(settledFrom)
                settlingOffset.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f))
                if (settlingId == id) settlingId = null
            }
        }
    }

    Box(modifier) {
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    .08f to Color.White,
                    .92f to Color.White,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        },
        contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = 8.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(itemSpacing),
        userScrollEnabled = draggingId == null,
    ) {
        itemsIndexed(displayItems, key = { _, item -> item.id }) { index, item ->
            val isDragging = draggingId == item.id
            val isSettling = settlingId == item.id
            val activeOffsetY = when {
                isDragging -> draggingOffsetY
                isSettling -> settlingOffset.value
                else -> 0f
            }
            val dragScale by animateFloatAsState(
                targetValue = if (isDragging) 1.02f else 1f,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 460f),
                label = "queue_drag_scale_${item.id}",
            )
            val dragAlpha by animateFloatAsState(
                targetValue = if (isDragging) 0.98f else 1f,
                animationSpec = tween(150),
                label = "queue_drag_alpha_${item.id}",
            )
            val placementModifier = if (isDragging) {
                Modifier
            } else {
                Modifier.animateItem(
                    fadeInSpec = tween(220),
                    fadeOutSpec = tween(200),
                    placementSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
            }
            val current = if (draggingId != null || pendingExpectedKeys != null) {
                item.originalIndex == model.queueIndex
            } else {
                index == model.queueIndex
            }
            val activeIndex = if (draggingId != null || pendingExpectedKeys != null) {
                item.originalIndex
            } else {
                index
            }
            var rowCoords by remember(item.id) { mutableStateOf<LayoutCoordinates?>(null) }
            var handleCoords by remember(item.id) { mutableStateOf<LayoutCoordinates?>(null) }
            var lastRowWindowY by remember(item.id) { mutableFloatStateOf(0f) }
            var lastHandleWindowY by remember(item.id) { mutableFloatStateOf(0f) }

            QueueSwipeRow(
                song = item.song,
                current = current,
                index = activeIndex,
                model = model,
                dragging = isDragging,
                backdrop = backdrop,
                rowModifier = Modifier
                    .zIndex(if (isDragging) 10f else if (isSettling) 5f else 0f)
                    .then(placementModifier)
                    .graphicsLayer {
                        translationY = activeOffsetY
                        scaleX = dragScale
                        scaleY = dragScale
                        alpha = dragAlpha
                        shape = rowShape
                        clip = true
                    }
                    .clip(rowShape)
                    .onGloballyPositioned { rowCoords = it }
                    .pointerInput(item.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { startOffset ->
                                val coords = rowCoords
                                lastRowWindowY = if (coords != null && coords.isAttached) {
                                    coords.localToWindow(startOffset).y
                                } else {
                                    startOffset.y
                                }
                                startDrag(item.id)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val coords = rowCoords
                                val deltaY = if (coords != null && coords.isAttached) {
                                    val windowY = coords.localToWindow(change.position).y
                                    val d = windowY - lastRowWindowY
                                    lastRowWindowY = windowY
                                    d
                                } else {
                                    dragAmount.y
                                }
                                updateDrag(item.id, deltaY)
                            },
                            onDragEnd = { finishDrag(item.id) },
                            onDragCancel = { finishDrag(item.id) },
                        )
                    },
                reorderHandle = { base ->
                    base
                        .onGloballyPositioned { handleCoords = it }
                        .pointerInput(item.id) {
                            detectVerticalDragGestures(
                                onDragStart = { startOffset ->
                                    val coords = handleCoords
                                    lastHandleWindowY = if (coords != null && coords.isAttached) {
                                        coords.localToWindow(startOffset).y
                                    } else {
                                        startOffset.y
                                    }
                                    startDrag(item.id)
                                },
                                onDragEnd = { finishDrag(item.id) },
                                onDragCancel = { finishDrag(item.id) },
                            ) { change, amount ->
                                change.consume()
                                val coords = handleCoords
                                val deltaY = if (coords != null && coords.isAttached) {
                                    val windowY = coords.localToWindow(change.position).y
                                    val d = windowY - lastHandleWindowY
                                    lastHandleWindowY = windowY
                                    d
                                } else {
                                    amount
                                }
                                updateDrag(item.id, deltaY)
                            }
                        }
                },
                expressive = expressive,
                oneUi = oneUi,
            )
        }
    }
    }
}

/** Feedback is clipped by the actual cover/handle shape, including during shared transforms. */
@Composable
private fun Modifier.playerSurfaceClick(onClick: () -> Unit, shape: Shape = RectangleShape): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val feedback by animateFloatAsState(if (pressed) .14f else if (hovered) .07f else 0f, tween(120), label = "playerSurfaceFeedback")
    return clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
        .drawWithContent {
            drawContent()
            if (feedback > 0f) {
                drawOutline(
                    outline = shape.createOutline(size, layoutDirection, this),
                    color = Color.White.copy(alpha = feedback),
                )
            }
        }
}

@Composable
internal fun PlayerButton(icon: Int, label: String, color: Color, iconSize: Dp, touchSize: Dp, onClick: () -> Unit) {
    Box(Modifier.size(touchSize).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Image(painterResource(icon), null, Modifier.size(iconSize * PlayerIconScale), colorFilter = ColorFilter.tint(color))
    }
}

@Composable
private fun PillSlider(value: Float, label: String, modifier: Modifier, onChange: (Float) -> Unit,
                       onFinished: () -> Unit, enabled: Boolean = true,
                       inkColor: Color = LocalPlayerSurfaceStyle.current.activeInk) {
    val isDark = LocalPlayerSurfaceStyle.current.isDark
    val change by rememberUpdatedState(onChange); val finish by rememberUpdatedState(onFinished)
    Canvas(modifier.semantics {
        contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
        if (enabled) setProgress { change(it.coerceIn(0f, 1f)); finish(); true } else disabled()
    }.pointerInput(enabled) {
        if (enabled) awaitEachGesture {
            val down = awaitFirstDown(); down.consume(); change((down.position.x / size.width).coerceIn(0f, 1f))
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent(); val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                pointer.consume(); change((pointer.position.x / size.width).coerceIn(0f, 1f)); pressed = pointer.pressed
            }
            finish()
        }
    }) {
        val thickness = min(8.dp.toPx(), size.height)
        val top = (size.height - thickness) / 2
        drawRoundRect(inkColor.copy(alpha = if (isDark) .32f else .18f), Offset(0f, top), Size(size.width, thickness), CornerRadius(thickness / 2))
        val fill = size.width * value.coerceIn(0f, 1f)
        if (fill > 0) drawRoundRect(inkColor.copy(alpha = if (isDark) .82f else .78f), Offset(0f, top), Size(fill, thickness), CornerRadius(thickness / 2))
    }
}

internal fun nextMediaVolumeStep(current: Int, max: Int, increase: Boolean): Int {
    val safeMax = max.coerceAtLeast(1)
    val safeCurrent = current.coerceIn(0, safeMax)
    val step = (safeMax / 15).coerceAtLeast(1)
    return if (increase) {
        (safeCurrent + step).coerceAtMost(safeMax)
    } else {
        (safeCurrent - step).coerceAtLeast(0)
    }
}

internal fun stepMediaVolume(manager: AudioManager, max: Int, increase: Boolean): Float {
    val safeMax = max.coerceAtLeast(1)
    val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
    if (!manager.isVolumeFixed) {
        val next = nextMediaVolumeStep(current = current, max = safeMax, increase = increase)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        return manager.getStreamVolume(AudioManager.STREAM_MUSIC) / safeMax.toFloat()
    }
    return current / safeMax.toFloat()
}

@Composable
private fun VolumeControl(modifier: Modifier) {
    val context = LocalContext.current
    val effects = remember(context) { (context.applicationContext as AuraApplication).effects }
    val usbStatus by effects.usbStatus.collectAsState()
    val effectsState by effects.state.collectAsState()
    val usbActive = usbStatus is UsbExclusiveStatus.Active
    val manager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val max = remember(manager) { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(manager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }
    var adjusting by remember { mutableStateOf(false) }
    LaunchedEffect(manager, usbActive) {
        while (true) {
            if (!adjusting && !usbActive) volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
            delay(300)
        }
    }
    val displayedVolume = if (usbActive) (if (adjusting) volume else effectsState.usbVolume) else volume
    val sliderEnabled = usbActive || !manager.isVolumeFixed
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Speaker(
            loud = false,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(enabled = sliderEnabled, role = Role.Button) {
                    if (usbActive) {
                        volume = effects.stepUsbVolume(increase = false, showHud = false)
                    } else {
                        volume = stepMediaVolume(manager, max, increase = false)
                    }
                },
        )
        PillSlider(displayedVolume, tr("media_volume", "媒体音量"), Modifier.weight(1f).height(44.dp), onChange = {
            adjusting = true
            volume = it
            if (usbActive) {
                effects.setUsbVolume(it, showHud = false)
            } else {
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, (it * max).roundToInt(), 0)
            }
        }, onFinished = { adjusting = false }, enabled = sliderEnabled)
        Speaker(
            loud = true,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(enabled = sliderEnabled, role = Role.Button) {
                    if (usbActive) {
                        volume = effects.stepUsbVolume(increase = true, showHud = false)
                    } else {
                        volume = stepMediaVolume(manager, max, increase = true)
                    }
                },
        )
    }
}

@Composable
private fun Speaker(loud: Boolean, modifier: Modifier) {
    val ink = LocalPlayerSurfaceStyle.current.activeInk
    Image(
        painter = painterResource(if (loud) R.drawable.ic_player_volume_full else R.drawable.ic_player_volume_muted),
        contentDescription = if (loud) tr("volume_up", "增大音量") else tr("volume_down", "减小音量"),
        modifier = modifier.padding(10.dp).size(24.dp * PlayerIconScale),
        colorFilter = ColorFilter.tint(ink.copy(alpha = .72f)),
    )
}

internal fun qualityName(value: StreamQuality): String = when (value) {
    StreamQuality.STANDARD -> tr("quality_standard", "标准")
    StreamQuality.HIGHER -> tr("quality_higher", "较高")
    StreamQuality.HIGH -> tr("quality_high", "极高")
    StreamQuality.LOSSLESS -> tr("quality_lossless", "无损")
    StreamQuality.HIRES -> tr("quality_hires", "Hi-Res")
    StreamQuality.SPATIAL -> tr("quality_spatial", "高清臻音")
    StreamQuality.SURROUND -> tr("quality_surround", "沉浸环绕声")
    StreamQuality.DOLBY -> tr("quality_dolby", "杜比全景声")
    StreamQuality.VIVID -> tr("quality_vivid", "臻音全景声")
    StreamQuality.MASTER -> tr("quality_master", "超清母带")
}

private fun timeText(ms: Long) = "${ms.coerceAtLeast(0) / 60000}:${(ms.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"

private fun smoothstep01(edge0: Float, edge1: Float, x: Float): Float {
    val span = (edge1 - edge0).coerceAtLeast(1e-4f)
    val t = ((x - edge0) / span).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun localAudioText(model: AuraViewModel): String = model.localAudioDetails?.let { detail ->
    buildList {
        if (detail.codec.isNotBlank()) add(detail.codec)
        val specs = buildList {
            if (detail.sampleRate > 0) add("${java.text.DecimalFormat("0.###").format(detail.sampleRate / 1000.0)} ${tr("khz", "kHz")}")
            if (detail.bits > 0) add("${detail.bits}-${tr("bit", "bit")}")
        }.joinToString(" / ")
        if (specs.isNotBlank()) add(specs)
        if (detail.bitrate > 0) add("${(detail.bitrate / 1000.0).roundToInt()} ${tr("kbps", "kb/s")}")
    }.joinToString(" · ")
}?.takeIf(String::isNotBlank) ?: tr("audio_details_loading", "正在读取音频信息…")

