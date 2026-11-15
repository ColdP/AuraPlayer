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
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
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
import btm.m.auraplayer.data.AppleMusicMotionCoverManager
import btm.m.auraplayer.lyrics.PlayerLyricView
import btm.m.auraplayer.lyrics.rememberSpatialGyroTilt
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
import btm.m.auraplayer.ui.*
import btm.m.auraplayer.ui.GlassIconDropdownMenu
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.*
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
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
private const val PlayerIconScale = .85f

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
    val playerBackdrop = rememberLayerBackdrop()
    val miuixBackdrop = rememberMiuixBackdrop()
    val coverPalette = rememberPlayerMonetPalette(song.artwork)
    val isMaterialTheme = appearance.theme == PlayerTheme.MATERIAL
    val isOneTheme = appearance.theme == PlayerTheme.ONE
    val lyricThemeActiveOnScreen = appearance.lyricTheme != btm.m.auraplayer.playback.LyricTheme.BLUR && lyricsOpen && !queueOpen
    val isDarkScheme = !isMaterialTheme || dark || appearance.materialBackground != MaterialPlayerBackground.MATERIAL || lyricThemeActiveOnScreen
    val coverSeed = rememberPlayerArtworkSeed(song.artwork)
    val expressive = rememberPlayerExpressiveColors(coverSeed, isDark = isDarkScheme)
    val activeExpressive = if (isMaterialTheme) expressive else null
    val oneUiColors = rememberPlayerOneUiColors(coverSeed, isDark = dark || lyricThemeActiveOnScreen)
    val activeOneUi = if (isOneTheme) oneUiColors else null
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
    val spatialLyricsActive = appearance.spatialLyrics && open && foreground && lyricsOpen && !queueOpen && !shareActive
    val spatialCoverActive = spatialCoverEligible && spatialCoverRequested &&
        spatialCoverState is SpatialCoverResolveState.Ready && open && foreground && !compactOpen && !shareActive
    val spatialActive = spatialLyricsActive || spatialCoverActive
    val spatialTilt by rememberSpatialGyroTilt(enabled = spatialActive)
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
        val miniY = lerp(rootMiniY, dockMiniY, dock)
        val swipeRange = with(LocalDensity.current) { miniY.toPx().coerceAtLeast(1f) }
        val gestureExpansion = if (miniDrag.axis == MiniPlayerDragAxis.EXPAND) miniDrag.progress else expansion.value
        val e = (gestureExpansion - swipeDistance / swipeRange).coerceIn(0f, 1f)
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
        val settledFullScreen = open && !playerSwipeGesturing && !backExiting && !settlingSwipe &&
            !expansion.isRunning && miniDrag.axis != MiniPlayerDragAxis.EXPAND && e >= .999f
        val shape = RoundedCornerShape(if (settledFullScreen) 0.dp else (miniHeight / 2) * (1f - e) + screenCorner * e)
        Box(Modifier.offset(metrics.outerInset * (1f - e), miniY * (1f - e))
            .size(lerp(miniWidth, screenWidth, e), lerp(miniHeight, screenHeight, e))
            .then(if (shareActive && e > .995f) Modifier else Modifier.miniPlayerMaterial(backdrop, style, shape, e))
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
                    if (e > 0f) PlayerThemeBackground(song.artwork, coverPalette, appearance, lyricProgress.value.coerceIn(0f, 1f),
                        animate = open && foreground && !gesturing && !shareActive,
                        queueBlend = queueBlend.value.coerceIn(0f, 1f),
                        spatialTilt = if (appearance.spatialLyrics) spatialTilt * lyricProgress.value.coerceIn(0f, 1f) else Offset.Zero,
                        expressive = activeExpressive,
                        oneUi = activeOneUi,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = e * shareBgAlpha }.blur(shareBgBlur).layerBackdrop(playerBackdrop).miuixLayerBackdrop(miuixBackdrop))
                    FullPlayer(model, lyricProgress.value, expansion = e, miniColor = miniColor, miniSwipe = miniSwipeOffset.value + miniDrag.horizontalOffset,
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
                        onLyrics = { lyricsOpen = !lyricsOpen; queueOpen = false }, queueOpen = queueOpen,
                        lyricsOpen = lyricsOpen, queueBlend = queueBlend.value, backdrop = playerBackdrop, menuTint = Color(coverPalette.a2.tone(20)),
                        coverPalette = coverPalette,
                        expressive = activeExpressive,
                        oneUi = activeOneUi,
                        onLyricMenuExpanded = { lyricMenuOpen = it },
                        miuixBackdrop = miuixBackdrop, onMore = { actionsOpen = true },
                        spatialCoverRequested = spatialCoverRequested,
                        spatialCoverState = spatialCoverState,
                        onToggleSpatialCover = { spatialCoverRequested = !spatialCoverRequested },
                        motionVideoPath = amMotionPath,
                        motionVideoAllowed = foreground && !shareActive,
                        onQueue = { queueOpen = !queueOpen; lyricsOpen = false },
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
            val miniAlpha = (1f - e / .34f).coerceIn(0f, 1f)
            if (miniAlpha > 0f) {
                Box(Modifier.matchParentSize()
                    .clickable(enabled = !open && !shareOpening, interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = { onOpenChange(true) }))
            }
            if (miniAlpha > 0f) Box(Modifier.fillMaxWidth().height(miniHeight)
                .graphicsLayer { alpha = miniAlpha }.blur((e * 24).dp)
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
        val oneSheetPalette = rememberOneUiPalette(isDark = activeOneUi.dark, useMonet = true, customSeed = coverSeed)
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
private fun FullPlayer(model: AuraViewModel, progress: Float, expansion: Float,
                       appearance: NowPlayingConfig, active: Boolean, controlsUnobscured: Boolean,
                       spatialTilt: Offset = Offset.Zero,
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
                       onQueue: () -> Unit, openSharePage: (Int, Rect?, Rect?) -> Unit,
                       expressive: PlayerExpressiveColors? = null,
                       oneUi: PlayerOneUiColors? = null) {
    val song = model.current ?: return
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val isMultiWindow = activity?.isInMultiWindowMode == true
    val activeInk = expressive?.activeInk ?: oneUi?.text ?: Color.White
    val secondaryInk = expressive?.secondaryInk ?: oneUi?.subText ?: Color.White.copy(alpha = .85f)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val p = progress.coerceIn(0f, 1f)
    val alpha = ((expansion - .12f) / .88f).coerceIn(0f, 1f)
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
            enabled = expansion == 0f, onClick = onExpand)) {
        val w = maxWidth; val h = maxHeight
        val fullscreen = appearance.theme == PlayerTheme.FULLSCREEN
        val landscape = isLandscapePlayerLayout(w, h)
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

        // Portrait & compact freeform layout metrics
        val portraitNav = effectiveBottomInset
        val fullCard = fullscreenPlayerCard(w, h, effectiveStatus, portraitNav)
        val normalMargin = w * (if (compactWindow) .055f else .058f * PlayerPaddingScale)
        fun controlSize(cardSize: Dp, normalSize: Dp): Dp = if (fullscreen) cardSize + (normalSize - cardSize) * p else normalSize
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
        val immGeom = if (immersiveCover) {
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
        val e = expansion.coerceIn(0f, 1f)
        val normalSeekY = immGeom?.seekY ?: maxOf(normalTitleY + titleToSeek, h * .637f).coerceAtMost(seekCeiling)
        val seekY = controlSize(fullCard.top + 64.dp, normalSeekY)
        val titleY = if (fullscreen && !landscape) fullCard.top + 14.dp else normalTitleY
        val portraitControlsScale = if (fullscreen) {
            ((fullCard.height - 76.dp) / 180.dp).coerceIn(.5f, 1.12f)
        } else {
            compactScale
        }
        var textHeight by remember { mutableIntStateOf(0) }
        val fullWidthPx = with(density) { w.toPx() }
        val glassCardRect = when {
            !fullscreen -> null
            landscapeGeom != null -> FullscreenPlayerCard(
                left = (landscapeGeom.rightPaneX - 16.dp).coerceAtLeast(landscapeGeom.leftPaneX + landscapeGeom.leftPaneWidth + 6.dp),
                top = (landscapeGeom.controlStackTop - 16.dp).coerceAtLeast(landscapeGeom.usableTop),
                width = (landscapeGeom.rightPaneWidth + 32.dp).coerceAtMost(w - landscapeGeom.rightPaneX + 12.dp),
                height = (landscapeGeom.controlStackHeight + 32.dp).coerceAtMost(landscapeGeom.usableHeight + 16.dp),
            )
            else -> fullCard
        }
        if (glassCardRect != null && alpha > 0f) {
            val glassTop = glassCardRect.top
            val cardLeftPx = with(density) { glassCardRect.left.toPx() }
            val cardTopPx = with(density) { glassTop.toPx() }
            val cardWidthPx = with(density) { glassCardRect.width.toPx() }
            val cardHeightPx = with(density) { glassCardRect.height.toPx() }
            val localTouch = touchPoint - Offset(cardLeftPx, cardTopPx)
            val touchInside = touching && localTouch.x in 0f..cardWidthPx && localTouch.y in 0f..cardHeightPx
            val touchLight by animateFloatAsState(if (appearance.fullscreenGlass && touchInside) .19f else 0f,
                tween(if (touchInside) 100 else 320), label = "playerCardTouchLight")
            val cardMaterial = if (appearance.fullscreenGlass) Modifier.drawBackdrop(
                backdrop = backdrop, shape = { RoundedCornerShape(30.dp) }, effects = {
                    vibrancy(); backdropBlur(5.dp.toPx()); lens(28.dp.toPx(), 42.dp.toPx(), chromaticAberration = true)
                }, highlight = { Highlight.Default.copy(alpha = .60f) },
                onDrawSurface = { drawRect(menuTint.copy(alpha = .18f)) })
            else Modifier.playerMenuMaterial(miuixBackdrop, menuTint, 30.dp)
            Box(Modifier.offset(glassCardRect.left, glassTop).size(glassCardRect.width, glassCardRect.height)
                .graphicsLayer { this.alpha = alpha * (1f - p) * shareChromeAlpha }.blur(shareChromeBlur)
                .then(cardMaterial).clip(RoundedCornerShape(30.dp)).drawWithContent {
                    drawContent()
                    if (appearance.fullscreenGlass && touchLight > .001f) {
                        val radius = size.width * .65f
                        drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = touchLight), Color.White.copy(alpha = touchLight * .32f), Color.Transparent),
                            center = localTouch, radius = radius.coerceAtLeast(1f)))
                    }
                })
            if (p < .01f && !landscape) Box(Modifier.fillMaxWidth().height(glassTop.coerceAtLeast(0.dp))
                .playerTrackSwipe(appearance.swipeTracks && active, model::next, model::previous))
        }
        val showSmallLandscapeCover = p * controlsVisibility
        val fullCoverSize = if (landscapeGeom != null) {
            lerp(landscapeGeom.heroCoverSize, landscapeGeom.lyricsCompactCoverSize, showSmallLandscapeCover)
        } else {
            coverSize + (small - coverSize) * p
        }
        val targetCoverX = if (landscapeGeom != null) {
            lerp(landscapeGeom.heroCoverX, landscapeGeom.lyricsCompactCoverX, showSmallLandscapeCover)
        } else {
            coverX + (smallX - coverX) * p
        }
        val targetCoverY = if (landscapeGeom != null) {
            lerp(landscapeGeom.heroCoverY, landscapeGeom.lyricsCompactCoverY, showSmallLandscapeCover)
        } else {
            coverY + (smallY - coverY) * p
        }
        val miniScale = miniHeight / PlayerNavigationGeometry.miniHeight
        val miniCoverSize = 44.dp * miniScale
        val miniCoverX = 12.dp * miniScale
        val miniCoverY = (miniHeight - miniCoverSize) / 2
        val sharedClick = if (e < .5f) onExpand else if (queueOpen) onQueue else onLyrics
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
        val spatialCoverRendering = spatialCoverAllowed && spatialCoverRequested && p < .99f && e > .25f
        val effLyricsProgress = if (landscapeGeom != null) showSmallLandscapeCover else p
        val motionOverlayAlpha = computeMotionCoverOverlayAlpha(
            expansion = e,
            lyricsProgress = effLyricsProgress,
            mixActive = mixVisual != null,
            spatialActive = spatialCoverRendering && spatialCoverState is SpatialCoverResolveState.Ready,
        )
        if (immGeom != null) {
            val topLeftDisplayCorner = rememberBottomScreenCornerRadius(0.dp, android.view.RoundedCorner.POSITION_TOP_LEFT)
            val topRightDisplayCorner = rememberBottomScreenCornerRadius(0.dp, android.view.RoundedCorner.POSITION_TOP_RIGHT)
            val bottomLeftDisplayCorner = rememberBottomScreenCornerRadius(0.dp, android.view.RoundedCorner.POSITION_BOTTOM_LEFT)
            val bottomRightDisplayCorner = rememberBottomScreenCornerRadius(0.dp, android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)
            val portraitTopCorner = when {
                compactWindow -> minOf(topLeftDisplayCorner, bottomRightDisplayCorner).coerceAtLeast(18.dp)
                topLeftDisplayCorner > 0.dp -> topLeftDisplayCorner
                else -> bottomRightDisplayCorner
            }
            val landscapeLeftTopCorner = if (compactWindow) topLeftDisplayCorner.coerceAtLeast(18.dp) else topLeftDisplayCorner
            val landscapeLeftBottomCorner = if (compactWindow) bottomLeftDisplayCorner.coerceAtLeast(18.dp) else bottomLeftDisplayCorner
            val immCompactX = if (landscapeGeom != null) landscapeGeom.lyricsCompactCoverX else smallX
            val immCompactY = if (landscapeGeom != null) landscapeGeom.lyricsCompactCoverY else smallY
            val immTargetW = lerp(immGeom.coverWidth, small, effLyricsProgress)
            val immTargetH = lerp(immGeom.coverHeight, small, effLyricsProgress)
            val immTargetX = lerp(immGeom.coverX, immCompactX, effLyricsProgress)
            val immTargetY = lerp(immGeom.coverY, immCompactY, effLyricsProgress)
            val immCurW = lerp(miniCoverSize, immTargetW, e)
            val immCurH = lerp(miniCoverSize, immTargetH, e)
            val immCurX = lerp(miniCoverX, immTargetX, e)
            val immCurY = lerp(miniCoverY, immTargetY, e)
            val miniCorner = 13.dp * miniScale
            val immCurTopStartRadius = lerp(
                miniCorner,
                lerp(if (landscapeGeom != null) landscapeLeftTopCorner else portraitTopCorner, 9.dp, effLyricsProgress),
                e,
            )
            val immCurTopEndRadius = lerp(
                miniCorner,
                lerp(if (landscapeGeom != null) 0.dp else topRightDisplayCorner.takeIf { it > 0.dp } ?: portraitTopCorner, 9.dp, effLyricsProgress),
                e,
            )
            val immCurBottomStartRadius = if (landscapeGeom != null) {
                lerp(miniCorner, lerp(landscapeLeftBottomCorner, 9.dp, effLyricsProgress), e)
            } else {
                immersiveCoverBottomCornerRadius(miniCorner, 9.dp, e, effLyricsProgress)
            }
            val immCurBottomEndRadius = immersiveCoverBottomCornerRadius(miniCorner, 9.dp, e, effLyricsProgress)
            val immFeather = (e * (1f - effLyricsProgress)).coerceIn(0f, 1f)
            Cover(
                uri = song.artwork,
                modifier = Modifier
                    .offset(immCurX, immCurY)
                    .size(immCurW, immCurH)
                    .graphicsLayer {
                        translationX = miniShift
                        this.alpha = miniAlpha * shareChromeAlpha
                    }
                    .blur(shareChromeBlur)
                    .playerTrackSwipe(appearance.swipeTracks && active && p < .01f, model::next, model::previous),
                radius = immCurTopStartRadius,
                topEndRadius = immCurTopEndRadius,
                bottomRadius = immCurBottomStartRadius,
                bottomEndRadius = immCurBottomEndRadius,
                onClick = { if (!shareActive) sharedClick() },
                animation = appearance.coverAnimation,
                animate = active && e > .99f,
                transitionKey = song.key,
                mixVisual = mixVisual,
                placeholderTint = activeInk.copy(alpha = .3f),
                spatialState = spatialCoverState,
                spatialActive = spatialCoverRendering,
                spatialTilt = spatialTilt,
                immersiveFeather = immFeather,
                immersiveHorizontal = landscapeGeom != null,
                motionVideoPath = motionVideoPath,
                motionPlaying = motionVideoAllowed && motionOverlayAlpha > 0.01f && mixVisual == null,
                motionOverlayAlpha = motionOverlayAlpha,
            )
            val immScrimStrength = alpha * (1f - effLyricsProgress)
            if (immScrimStrength > 0.001f) {
                val scrimTone = when {
                    expressive != null -> expressive.surface
                    oneUi != null -> oneUi.background
                    else -> Color(coverPalette.a2.tone(14))
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
                                translationX = miniShift
                                this.alpha = immScrimStrength * shareChromeAlpha
                            }
                            .blur(shareChromeBlur)
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
                                translationX = miniShift
                                this.alpha = immScrimStrength * shareChromeAlpha
                            }
                            .blur(shareChromeBlur)
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
            val sharedSize = lerp(miniCoverSize, fullCoverSize, e)
            val sharedX = lerp(miniCoverX, targetCoverX, e)
            val sharedY = lerp(miniCoverY, targetCoverY, e)
            val sharedScale = sharedSize / coverSize
            val sharedRadius = lerp(13.dp * miniScale, baseCoverRadius + (9.dp - baseCoverRadius) * p, e)
            Cover(
                uri = song.artwork,
                modifier = Modifier
                    .offset(sharedX, sharedY)
                    .size(coverSize)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f); scaleX = sharedScale; scaleY = sharedScale
                        translationX = miniShift
                        val portraitFullscreenHidden = fullscreen && !landscape
                        this.alpha = miniAlpha * (if (portraitFullscreenHidden) 1f - e * (1f - p) else 1f) * shareChromeAlpha
                    }
                    .blur(shareChromeBlur)
                    .playerTrackSwipe(appearance.swipeTracks && active && p < .01f && !fullscreen, model::next, model::previous),
                radius = sharedRadius / sharedScale,
                onClick = { if (!shareActive) sharedClick() },
                animation = appearance.coverAnimation,
                animate = active && e > .99f,
                transitionKey = song.key,
                mixVisual = mixVisual,
                placeholderTint = activeInk.copy(alpha = .3f),
                spatialState = spatialCoverState,
                spatialActive = spatialCoverRendering,
                spatialTilt = spatialTilt,
                motionVideoPath = motionVideoPath,
                motionPlaying = motionVideoAllowed && motionOverlayAlpha > 0.01f && mixVisual == null,
                motionOverlayAlpha = motionOverlayAlpha,
            )
        }
        // Only controls and secondary content blur/fade; artwork, metadata, and exiting lyrics transform independently.
        if (alpha > 0f) Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha * shareChromeAlpha }
            .blur(((1f - alpha) * 14f).dp + shareChromeBlur)) {
            val handleAlpha = when {
                landscape && immersive -> 0.48f
                immGeom != null && p < 0.5f -> 0.48f + 0.52f * (p / 0.5f)
                else -> 1f
            }
            val handleY = when {
                landscape || compactWindow -> effectiveStatus + 2.dp
                immGeom != null -> effectiveStatus + 6.dp
                else -> effectiveStatus + 12.dp * PlayerPaddingScale
            }
            Box(Modifier.align(Alignment.TopCenter).offset(y = handleY).size(104.dp, 28.dp)
                .graphicsLayer { this.alpha = handleAlpha }
                .clip(RoundedCornerShape(50))
                .semantics { contentDescription = tr("collapse_player", "收起播放器") }
                .playerSurfaceClick(onClose), contentAlignment = Alignment.Center) {
                val pillWidth = if (landscape || isMultiWindow) 52.dp else 80.dp
                val pillHeight = if (landscape || isMultiWindow) 4.5.dp else 6.dp
                when {
                    expressive != null -> Box(Modifier.width(48.dp).height(4.5.dp).clip(RoundedCornerShape(50)).background(expressive.dragHandleColor))
                    oneUi != null -> Box(Modifier.width(48.dp).height(4.5.dp).clip(RoundedCornerShape(50)).background(oneUi.track))
                    else -> Box(Modifier.width(pillWidth).height(pillHeight).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .36f)))
                }
            }
            if (queueBlend > .001f && p > 0f) {
                val queueMod = if (landscapeGeom != null) {
                    val qBottomClearance = 0.dp
                    Modifier
                        .offset(x = landscapeGeom.rightPaneX, y = landscapeGeom.usableTop)
                        .size(
                            landscapeGeom.rightPaneWidth,
                            (landscapeGeom.usableBottom - landscapeGeom.usableTop - qBottomClearance).coerceAtLeast(1.dp),
                        )
                } else {
                    val qTop = smallY + small + (if (compactWindow) 12.dp else 20.dp * PlayerPaddingScale)
                    Modifier
                        .offset(y = qTop)
                        .fillMaxWidth()
                        .height((seekY + 50.dp - qTop - 8.dp).coerceAtLeast(1.dp))
                        .padding(horizontal = smallX)
                }
                PlayerQueue(
                    model,
                    expressive = expressive,
                    oneUi = oneUi,
                    modifier = queueMod
                        .graphicsLayer { this.alpha = p * queueBlend; translationY = (1f - p) * 24.dp.toPx() }
                        .blur(((1f - p) * 12f + queueBlend * (1f - queueBlend) * 20f).dp),
                )
            }
            if (landscapeGeom != null) {
                // Landscape Normal Controls inside Right Column
                val normalCtrlAlpha = ((1f - p) * controlsVisibility).coerceIn(0f, 1f)
                val landscapeHeaderReserve = with(density) {
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
                            footerHeight = 22.dp * lScale.coerceAtLeast(0.84f),
                            horizontalInset = 0.dp,
                            onQualityMenuExpanded = { qualityMenuOpen = it },
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
                // Landscape Non-Immersed Lyrics/Queue Controls inside Left Column below compact cover
                val leftCtrlAlpha = (p * controlsVisibility).coerceIn(0f, 1f)
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
                            onQualityMenuExpanded = { qualityMenuOpen = it },
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
            } else if (controlsVisibility > .001f) {
                Column(
                    Modifier
                        .offset(y = seekY)
                        .fillMaxWidth()
                        .graphicsLayer { this.alpha = controlsVisibility }
                        .blur(((1f - controlsVisibility) * 14f).dp, BlurredEdgeTreatment.Unbounded)
                        .height(
                            controlSize(
                                (fullCard.height - 76.dp).coerceAtLeast(1.dp),
                                (h - normalSeekY - portraitNav - 10.dp).coerceAtLeast(baseControlsHeight),
                            ),
                        )
                        .padding(horizontal = margin),
                ) {
                    val sliderNormalHeight = when (appearance.glowProgress) {
                        GlowProgressStyle.ONE -> 36.dp
                        GlowProgressStyle.HYPER -> 30.dp
                        GlowProgressStyle.OFF -> 24.dp
                    } * compactScale
                    val sliderFullHeight = when (appearance.glowProgress) {
                        GlowProgressStyle.ONE -> 31.dp
                        GlowProgressStyle.HYPER -> 26.dp
                        GlowProgressStyle.OFF -> 20.dp
                    } * portraitControlsScale
                    val footerNormal = 26.dp * compactScale
                    val footerFull = 20.dp * portraitControlsScale
                    val sliderInset = 0.dp
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
                        controlsVisibility = controlsVisibility,
                        sliderHeight = controlSize(sliderFullHeight, sliderNormalHeight),
                        footerHeight = controlSize(footerFull, footerNormal),
                        horizontalInset = sliderInset,
                        onQualityMenuExpanded = { qualityMenuOpen = it },
                        modifier = Modifier.graphicsLayer { this.alpha = 1f - queueBlend },
                    )
                    Spacer(Modifier.weight(1f))
                    PlayerTransportSection(
                        model = model,
                        expressive = expressive,
                        oneUi = oneUi,
                        scale = portraitControlsScale,
                        rowHeight = controlSize(64.dp * portraitControlsScale, 100.dp * compactScale),
                    )
                    Spacer(Modifier.weight(1f))
                    if (appearance.showVolume) {
                        PlayerVolumeSection(
                            expressive = expressive,
                            oneUi = oneUi,
                            height = controlSize(32.dp * portraitControlsScale, 44.dp * compactScale),
                        )
                    }
                    val bottomTouch = controlSize(40.dp * portraitControlsScale, 48.dp * compactScale)
                    val bottomIcon = controlSize(27.dp * portraitControlsScale, 32.dp * compactScale)
                    PlayerBottomActionsSection(
                        model = model,
                        song = song,
                        lyricsOpen = lyricsOpen,
                        queueOpen = queueOpen,
                        expressive = expressive,
                        oneUi = oneUi,
                        rowHeight = controlSize(44.dp * portraitControlsScale, 56.dp * compactScale),
                        bottomTouch = bottomTouch,
                        bottomIcon = bottomIcon,
                        onLyrics = onLyrics,
                        onQueue = onQueue,
                    )
                }
            }
        }
        val lyricVisual = remember(appearance.lyricTheme) { lyricThemeSpec(appearance.lyricTheme) }
        val lyricChromeBlend = if (appearance.lyricTheme == btm.m.auraplayer.playback.LyricTheme.BLUR) {
            0f
        } else {
            (p * (1f - queueBlend)).coerceIn(0f, 1f)
        }
        val lyricInk = if (lyricChromeBlend > 0f) lerp(activeInk, lyricVisual.inkColor, lyricChromeBlend) else activeInk
        val lyricSubInk = if (lyricChromeBlend > 0f) lerp(secondaryInk, lyricVisual.secondaryInkColor, lyricChromeBlend) else secondaryInk
        if (alpha > 0f && p > 0f && queueBlend < .999f) {
            val lyricBoxModifier = if (landscapeGeom != null) {
                val bottomReserve = 52.dp * controlsVisibility
                Modifier
                    .offset(x = landscapeGeom.rightPaneX, y = landscapeGeom.usableTop)
                    .size(
                        landscapeGeom.rightPaneWidth,
                        (landscapeGeom.usableBottom - landscapeGeom.usableTop - bottomReserve).coerceAtLeast(1.dp),
                    )
            } else {
                val portraitLyricsTop = smallY + small + (if (compactWindow) 10.dp else 20.dp * PlayerPaddingScale)
                val bottomReserve = 56.dp * controlsVisibility
                val portraitLyricsBottom = seekY - bottomReserve + (h - portraitNav - seekY) * (1f - controlsVisibility)
                Modifier
                    .offset(y = portraitLyricsTop)
                    .fillMaxWidth()
                    .height((portraitLyricsBottom - portraitLyricsTop).coerceAtLeast(1.dp))
            }
            val lyricHorizontalPadDp = when {
                landscapeGeom != null -> 10f
                w < 330.dp -> 14f
                else -> 26f
            }
            val effectiveLyricScale = model.lyricSize.scale * when {
                landscapeGeom != null && landscapeGeom.rightPaneWidth < 360.dp -> (landscapeGeom.rightPaneWidth.value / 370f).coerceIn(0.76f, 1f)
                !landscape && w < 330.dp -> (w.value / 345f).coerceIn(0.76f, 1f)
                else -> 1f
            }
            Box(lyricBoxModifier
                .graphicsLayer {
                    val shareExitAlpha = if (shareActive) 1f - lyricShareSmoothStep(.06f, .62f, shareP) else 1f
                    this.alpha = alpha * p * (1f - queueBlend) * shareExitAlpha
                    var tx = 0f
                    var ty = (1f - p) * 24.dp.toPx()
                    if (shareActive) {
                        val tState = shareTransition
                        val vp = tState.sourceViewportBounds
                        val srcCenter = tState.sourceRowBounds?.center ?: vp?.center
                        val dstCenter = tState.targetRowBounds?.center ?: tState.targetCardBounds?.center
                        val srcH = tState.sourceRowBounds?.height?.takeIf { it > 0f }
                        val dstH = tState.targetRowBounds?.height?.takeIf { it > 0f }
                        val endScale = if (srcH != null && dstH != null) (dstH / srcH).coerceIn(.48f, .88f) else .62f
                        val scale = 1f + (endScale - 1f) * shareP
                        if (vp != null && vp.width > 0f && vp.height > 0f && srcCenter != null) {
                            transformOrigin = TransformOrigin(
                                ((srcCenter.x - vp.left) / vp.width).coerceIn(0f, 1f),
                                ((srcCenter.y - vp.top) / vp.height).coerceIn(0f, 1f),
                            )
                        }
                        scaleX = scale
                        scaleY = scale
                        if (srcCenter != null && dstCenter != null) {
                            tx += (dstCenter.x - srcCenter.x) * shareP
                            ty += (dstCenter.y - srcCenter.y) * shareP
                        }
                    }
                    translationX = tx
                    translationY = ty
                }
                .blur(((1f - alpha) * 14f + (1f - p) * 12f + queueBlend * (1f - queueBlend) * 20f).dp)) {
                if (model.lyrics.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (model.lyricsLoading) tr("lyrics_loading", "正在获取歌词…") else tr("lyrics_empty", "暂无歌词"), color = lyricInk.copy(alpha = .55f), fontSize = 20.sp)
                } else AndroidView(factory = { PlayerLyricView(it) }, update = {
                    it.lyricTheme(appearance.lyricTheme)
                    it.spatialLyrics(appearance.spatialLyrics)
                    it.spatialTilt(spatialTilt.x, spatialTilt.y)
                    it.inkColor(lyricInk.toArgb())
                    it.submit(
                        lines = model.lyrics,
                        translation = model.showTranslation,
                        pronunciation = model.showPronunciation,
                        textScale = effectiveLyricScale,
                        horizontalPaddingDp = lyricHorizontalPadDp,
                    )
                    it.position(model.position, model.playing)
                    it.onSeek = model::seekTo
                    it.onLongPress = { index -> openSharePage(index, it.rowBoundsInWindow(index)?.toComposeRect(), it.viewportBoundsInWindow().toComposeRect()) }
                    it.keepScreenOn = appearance.keepLyricsAwake && active && lyricsOpen
                }, modifier = Modifier.fillMaxSize())
            }
        }
        val metadataSize = if (fullscreen && !landscape) 1f - .12f * e * (1f - p) else 1f
        val measuredTextHeight = with(density) { if (textHeight > 0) textHeight.toDp() else (46.sp * metadataSize).toDp() }
        val miniTextX = 66.dp * miniScale
        val miniTextWidth = (miniWidth - miniTextX - miniControlClearance).coerceAtLeast(24.dp)
        val miniTextY = (miniHeight - measuredTextHeight * .7f * miniScale) / 2
        val textScale = if (landscapeGeom != null) {
            .7f * miniScale + ((1f - .14f * p) - .7f * miniScale) * e
        } else {
            .7f * miniScale + ((1f - .22f * p) - .7f * miniScale) * e
        }
        val hasCubeOnTitleRow = spatialCoverAllowed
        val buttonSize = 46.dp
        val cubeGap = 4.dp
        val fullTextX = if (landscapeGeom != null) {
            val normalMetaX = landscapeGeom.rightPaneX
            val compactMetaX = landscapeGeom.lyricsCompactCoverX + landscapeGeom.lyricsCompactCoverSize + 12.dp
            lerp(normalMetaX, compactMetaX, p)
        } else {
            val compactTextX = smallX + small + 12.dp * PlayerPaddingScale
            val expandedTextX = if (fullscreen) fullCard.left + 16.dp else portraitColX
            expandedTextX + (compactTextX - expandedTextX) * p
        }
        val fullTextY = if (landscapeGeom != null) {
            val normalMetaY = landscapeGeom.controlStackTop
            val compactMetaY = landscapeGeom.lyricsCompactCoverY + (landscapeGeom.lyricsCompactCoverSize - measuredTextHeight * .86f) / 2
            lerp(normalMetaY, compactMetaY, p)
        } else {
            val compactTextY = smallY + (small - measuredTextHeight * .78f) / 2
            titleY + (compactTextY - titleY) * p
        }
        val fullTextWidth = if (landscapeGeom != null) {
            val cubeReserve = if (hasCubeOnTitleRow) buttonSize + cubeGap else 0.dp
            val normalMetaW = (landscapeGeom.rightPaneWidth - 52.dp - cubeReserve).coerceAtLeast(24.dp)
            val compactMetaW = (landscapeGeom.leftPaneWidth - landscapeGeom.lyricsCompactCoverSize - 12.dp - 48.dp).coerceAtLeast(24.dp)
            lerp(normalMetaW, compactMetaW, p)
        } else {
            val compactTextX = smallX + small + 12.dp * PlayerPaddingScale
            val cubeReserve = if (hasCubeOnTitleRow) buttonSize + cubeGap else 0.dp
            val expandedTextWidth = if (fullscreen) {
                (fullCard.width - 84.dp - cubeReserve).coerceAtLeast(24.dp)
            } else if (immGeom != null) {
                (portraitColW - buttonSize - 8.dp - cubeReserve).coerceAtLeast(24.dp)
            } else {
                (portraitColW - cubeReserve).coerceAtLeast(24.dp)
            }
            expandedTextWidth + ((w - compactTextX - 68.dp).coerceAtLeast(24.dp) - expandedTextWidth) * p
        }
        val textWidth = (miniTextWidth + (fullTextWidth - miniTextWidth) * e) / textScale
        val textColor = lerp(miniColor, lyricInk, e)
        val baseArtistColor = oneUi?.subText ?: expressive?.secondaryInk ?: Color.White.copy(alpha = .8f)
        val artistColor = lerp(miniColor.copy(alpha = .8f), if (lyricChromeBlend > 0f) lerp(baseArtistColor, lyricSubInk, lyricChromeBlend) else baseArtistColor, e)
        val mixProgress = mixVisual?.progress?.coerceIn(0f, 1f)
        val mixFromAlpha = mixProgress?.let { (1f - it).coerceIn(0f, 1f) } ?: 0f
        val mixToAlpha = mixProgress?.coerceIn(0f, 1f) ?: 0f
        val landscapeMetaChrome = if (landscapeGeom != null) 1f - e * p * (1f - controlsVisibility) else 1f
        Box(Modifier.offset(lerp(miniTextX, fullTextX, e), miniTextY + (fullTextY - miniTextY) * e)
            .width(textWidth).height(measuredTextHeight).graphicsLayer {
                translationX = miniShift
                this.alpha = miniAlpha * shareChromeAlpha * landscapeMetaChrome
            }.blur(shareChromeBlur + if (landscapeGeom != null) ((1f - landscapeMetaChrome) * 14f).dp else 0.dp)) {
            Column(Modifier.fillMaxSize()
                .width(textWidth).onSizeChanged { textHeight = it.height }.graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f); scaleX = textScale; scaleY = textScale
                    this.alpha = miniAlpha * if (mixVisual == null) 1f else mixFromAlpha
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !shareActive && landscapeMetaChrome > 0.1f, onClick = sharedClick)) {
                Text(mixVisual?.fromTitle ?: song.title, color = textColor, fontSize = 20.sp * metadataSize, lineHeight = 25.sp * metadataSize,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(mixVisual?.fromArtist ?: song.artist, color = artistColor, fontSize = 16.sp * metadataSize, lineHeight = 21.sp * metadataSize,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (mixVisual != null) Column(Modifier.fillMaxSize().graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f); scaleX = textScale; scaleY = textScale
                this.alpha = miniAlpha * mixToAlpha
            }) {
                Text(mixVisual.toTitle, color = textColor, fontSize = 20.sp * metadataSize, lineHeight = 25.sp * metadataSize,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(mixVisual.toArtist, color = artistColor, fontSize = 16.sp * metadataSize, lineHeight = 21.sp * metadataSize,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            /* Keep the normal click target over the two metadata layers. */
            Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !shareActive && landscapeMetaChrome > 0.1f, onClick = sharedClick))
        }

        // Action buttons live outside the content's blur layer so glass/blur remains sharp.
        if (alpha > 0f) {
            val compactX = w - (if (compactWindow) normalMargin + buttonSize else 68.dp)
            val compactY = smallY + (small - buttonSize) / 2
            val moreX: Dp
            val moreY: Dp
            if (landscapeGeom != null) {
                val normalMoreX = landscapeGeom.rightPaneX + landscapeGeom.rightPaneWidth - buttonSize
                val compactMoreX = landscapeGeom.leftPaneX + landscapeGeom.leftPaneWidth - buttonSize
                val normalMoreY = landscapeGeom.controlStackTop + (measuredTextHeight - buttonSize) / 2
                val compactMoreY = landscapeGeom.lyricsCompactCoverY + (landscapeGeom.lyricsCompactCoverSize - buttonSize) / 2
                moreX = lerp(normalMoreX, compactMoreX, p)
                moreY = lerp(normalMoreY, compactMoreY, p)
            } else {
                val useRightCornerAnchor = fullscreen || compactWindow || coverSize < w * .66f
                val bigX = if (fullscreen) {
                    fullCard.left + fullCard.width - 16.dp - buttonSize
                } else if (immGeom != null) {
                    portraitColX + portraitColW - buttonSize
                } else if (useRightCornerAnchor) {
                    maxOf(w / 2 + 52.dp, w - normalMargin - buttonSize).coerceAtMost(w - buttonSize - 6.dp)
                } else {
                    coverX + coverSize - buttonSize
                }
                val bigY = if (fullscreen || immGeom != null) {
                    titleY + (measuredTextHeight - buttonSize) / 2
                } else {
                    effectiveStatus + (if (compactWindow) 2.dp else 8.dp)
                }
                moreX = bigX + (compactX - bigX) * p
                moreY = bigY + (compactY - bigY) * p
            }
            val cubeModeVis = (1f - (p / 0.72f).coerceIn(0f, 1f))
            if (hasCubeOnTitleRow && cubeModeVis > 0.001f) {
                val cubeX: Dp
                val cubeY: Dp
                if (landscapeGeom != null) {
                    cubeX = landscapeGeom.rightPaneX + landscapeGeom.rightPaneWidth - buttonSize * 2 - cubeGap
                    cubeY = landscapeGeom.controlStackTop + (measuredTextHeight - buttonSize) / 2
                } else if (fullscreen) {
                    cubeX = fullCard.left + fullCard.width - 16.dp - buttonSize * 2 - cubeGap
                    cubeY = titleY + (measuredTextHeight - buttonSize) / 2
                } else if (immGeom != null) {
                    cubeX = portraitColX + portraitColW - buttonSize * 2 - cubeGap
                    cubeY = titleY + (measuredTextHeight - buttonSize) / 2
                } else {
                    cubeX = portraitColX + portraitColW - buttonSize
                    cubeY = titleY + (measuredTextHeight - buttonSize) / 2
                }
                val cubeBlur = ((1f - cubeModeVis) * 14f).dp + shareChromeBlur
                val cubeGenerating = spatialCoverState is SpatialCoverResolveState.Generating
                PlayerSpatialCubeButton(
                    active = spatialCoverRequested,
                    generating = cubeGenerating,
                    expressive = expressive,
                    oneUi = oneUi,
                    miuixBackdrop = miuixBackdrop,
                    menuTint = menuTint,
                    inkColor = activeInk,
                    enabled = !shareActive && cubeModeVis > 0.1f,
                    onClick = onToggleSpatialCover,
                    modifier = Modifier
                        .offset(cubeX, cubeY)
                        .size(buttonSize)
                        .graphicsLayer {
                            this.alpha = alpha * shareChromeAlpha * cubeModeVis
                            scaleX = 0.84f + 0.16f * cubeModeVis
                            scaleY = scaleX
                        }
                        .blur(cubeBlur, BlurredEdgeTreatment.Unbounded),
                )
            }
            val moreVis = if (landscapeGeom != null) (1f - p * (1f - controlsVisibility)) else 1f
            if (moreVis > 0.001f) {
                PlayerMoreActionButton(
                    expressive = expressive,
                    oneUi = oneUi,
                    miuixBackdrop = miuixBackdrop,
                    menuTint = menuTint,
                    enabled = !shareActive && moreVis > 0.1f,
                    onMore = onMore,
                    modifier = Modifier
                        .offset(moreX, moreY)
                        .size(buttonSize)
                        .graphicsLayer {
                            this.alpha = alpha * shareChromeAlpha * moreVis
                            scaleX = 1f - .08f * p
                            scaleY = scaleX
                        }
                        .blur(shareChromeBlur + if (landscapeGeom != null) ((1f - moreVis) * 14f).dp else 0.dp),
                )
            }
        }

        if (alpha > 0f && p > 0f && queueBlend < .01f && controlsVisibility > .001f) {
            val menuAnchorX = if (landscapeGeom != null) {
                landscapeGeom.rightPaneX
            } else if (fullscreen) {
                margin
            } else {
                coverX
            }
            val menuAnchorY = if (landscapeGeom != null) {
                landscapeGeom.usableBottom - 46.dp
            } else {
                seekY - 50.dp
            }
            val menuMaxTop = if (landscapeGeom != null) landscapeGeom.usableTop else effectiveStatus + 8.dp
            LyricOptionsMenu(
                model,
                miuixBackdrop,
                menuTint,
                menuAnchorX,
                menuAnchorY,
                w,
                menuMaxTop,
                Modifier.graphicsLayer { this.alpha = alpha * p * controlsVisibility * shareChromeAlpha }
                    .blur(((1f - p + 1f - controlsVisibility) * 14f).dp + shareChromeBlur),
                onLyricMenuExpanded,
            )
        }

    }
}

@Composable
private fun PlayerProgressSection(
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
        when {
            appearance.glowProgress != GlowProgressStyle.OFF -> {
                val isLightGlowSurface = (expressive?.dark == false) || (oneUi?.dark == false)
                val glowAccent = oneUi?.accentDeep
                    ?: expressive?.primary
                    ?: vibrantCoverGlowColor(coverPalette)
                val glowTrack = when {
                    oneUi != null -> oneUi.track.copy(alpha = if (oneUi.dark) .75f else .85f)
                    expressive != null -> expressive.surfaceContainerHighest.copy(alpha = if (expressive.dark) .55f else .85f)
                    else -> Color.White.copy(alpha = .28f)
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
            )
        }
        Box(
            Modifier
                .padding(horizontal = horizontalInset)
                .fillMaxWidth()
                .height(footerHeight),
        ) {
            val timeColor = expressive?.timeInk ?: oneUi?.subText ?: Color.White.copy(alpha = .85f)
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
                                .padding(horizontal = if (badgeBg != null) 11.dp else 7.dp, vertical = 4.dp),
                        ) {
                            Text(
                                qualityName(actualQuality ?: model.quality),
                                color = badgeFg,
                                fontSize = 11.sp,
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
                    localAudioText(model),
                    color = timeColor,
                    fontSize = 11.sp,
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
            PlayerButton(R.drawable.ic_player_previous, tr("previous_track", "上一首"), Color.White, 48.dp * scale, 76.dp * scale, model::previous)
            PlayerButton(
                if (model.playbackRequested) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                if (model.playbackRequested) tr("pause", "暂停") else tr("play", "播放"),
                Color.White,
                60.dp * .88f * scale,
                96.dp * scale,
                model::togglePlayback,
            )
            PlayerButton(R.drawable.ic_player_next, tr("next_track", "下一首"), Color.White, 48.dp * scale, 76.dp * scale, model::next)
        }
    }
}

@Composable
private fun PlayerVolumeSection(
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
            else -> R.drawable.ic_player_repeat
        }
        val modeDescription = when (model.playbackMode) {
            1 -> tr("shuffle_mode", "随机播放")
            2 -> tr("repeat_one_mode", "单曲循环")
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
            Box(Modifier.size(bottomTouch).semantics { selected = lyricsOpen }) {
                PlayerButton(if (lyricsOpen) R.drawable.ic_player_lyrics_on else R.drawable.ic_player_lyrics, tr("lyrics", "歌词"), Color.White.copy(alpha = .9f), bottomIcon, bottomTouch, onLyrics)
            }
            PlayerButton(modeIcon, modeDescription, Color.White.copy(alpha = .9f), bottomIcon, bottomTouch, model::cyclePlaybackMode)
            PlayerButton(
                if (favorite) R.drawable.ic_player_favorite_on else R.drawable.ic_player_favorite,
                if (favorite) tr("unfavorite", "取消收藏") else tr("favorite", "收藏"),
                Color.White.copy(alpha = .9f),
                bottomIcon,
                bottomTouch,
            ) { model.toggleFavorite(song) }
            Box(Modifier.semantics { selected = queueOpen }) {
                PlayerButton(if (queueOpen) R.drawable.ic_player_queue_on else R.drawable.ic_player_queue, tr("play_queue", "播放队列"), Color.White.copy(alpha = .9f), bottomIcon, bottomTouch, onQueue)
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
                Box(Modifier.size(46.dp * .85f * .92f).playerMenuMaterial(miuixBackdrop, menuTint, 50.dp)) {
                    Image(
                        AuraMoreHorizontalIcon,
                        tr("more", "更多"),
                        Modifier.align(Alignment.Center).size(24.dp * PlayerIconScale * .92f),
                        colorFilter = ColorFilter.tint(Color.White),
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
    val bgTint = when {
        expressive != null && active -> expressive.primaryContainer
        expressive != null -> expressive.surfaceContainerHigh
        oneUi != null && active -> oneUi.chip
        oneUi != null -> oneUi.cardAlt
        active -> Color.White.copy(alpha = 0.24f)
        else -> menuTint
    }
    val tintColor = when {
        expressive != null && active -> expressive.onPrimaryContainer
        expressive != null -> inkColor
        oneUi != null && active -> oneUi.onChip
        oneUi != null -> inkColor
        else -> Color.White
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
private fun Cover(uri: String, modifier: Modifier, radius: Dp, bottomRadius: Dp = radius,
                  topEndRadius: Dp = radius, bottomEndRadius: Dp = bottomRadius,
                  onClick: (() -> Unit)? = null,
                  animation: CoverAnimation = CoverAnimation.DEFAULT, animate: Boolean = false, transitionKey: String = uri,
                  mixVisual: btm.m.auraplayer.auramix.AuraMixVisualTransition? = null,
                  placeholderTint: Color = Color.White.copy(alpha = .3f),
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
    Box(modifier.clip(RoundedCornerShape(topStart = radius, topEnd = topEndRadius, bottomStart = bottomRadius, bottomEnd = bottomEndRadius))
        .then(if (onClick == null) Modifier else Modifier.playerSurfaceClick(onClick))
        .then(featherMask)
        .background(Color.White.copy(alpha = .12f * (1f - feather))), contentAlignment = Alignment.Center) {
        if (mixVisual == null) {
            Box(Modifier.fillMaxSize().blur(coverBlurDp.dp)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (feather > 0.001f) Modifier.miuixLayerBackdrop(coverBackdrop) else Modifier),
                ) {
                    if (uri.isNotBlank()) AnimatedPlayerArtwork(uri, transitionKey, animation, animate)
                    else Image(painterResource(R.drawable.ic_player_queue), null, Modifier.fillMaxSize(.3f * PlayerIconScale), colorFilter = ColorFilter.tint(placeholderTint))
                    if (!motionVideoPath.isNullOrBlank()) {
                        AnimatedMotionCoverVideo(
                            videoPath = motionVideoPath,
                            playing = motionPlaying && !(spatialActive && spatialAssets != null),
                            visibleAlpha = motionOverlayAlpha,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (spatialAssets != null && showSpatial > 0.001f) {
                        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = showSpatial }) {
                            SpatialCoverRenderer(
                                assets = spatialAssets,
                                tiltProvider = { spatialTilt },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                if (feather > 0.01f) {
                    Box(
                        Modifier
                            .matchParentSize()
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
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = fromAlpha }.blur((progress * 26f).dp)) {
                if (fromReq != null) AsyncImage(fromReq, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else if (uri.isNotBlank()) AnimatedPlayerArtwork(uri, transitionKey, animation, animate)
            }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = toAlpha }.blur(((1f - progress) * 26f).dp)) {
                if (toReq != null) AsyncImage(toReq, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    }
}

@Composable
private fun QueueSwipeRow(song: btm.m.auraplayer.data.Song, current: Boolean, index: Int, model: AuraViewModel,
                          dragging: Boolean, onDragState: (Boolean) -> Unit, reorderHandle: (Modifier) -> Modifier,
                          expressive: PlayerExpressiveColors? = null,
                          oneUi: PlayerOneUiColors? = null) {
    val ink = expressive?.activeInk ?: oneUi?.text ?: Color.White
    val rowHighlight = when {
        expressive != null && dragging -> expressive.surfaceContainerHighest.copy(alpha = .75f)
        expressive != null && current -> expressive.secondaryContainer.copy(alpha = .85f)
        oneUi != null && dragging -> oneUi.cardAlt
        oneUi != null && current -> oneUi.chip
        dragging -> Color.White.copy(alpha = .14f)
        current -> Color.White.copy(alpha = .10f)
        else -> Color.Transparent
    }
    val rowTitleInk = when {
        expressive != null && current -> expressive.onSecondaryContainer
        oneUi != null && current -> oneUi.onChip
        else -> ink
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val deleteWidth = with(density) { 92.dp.toPx() }
    val offset = remember(song.key) { Animatable(0f) }
    val progress = (-offset.value / deleteWidth).coerceIn(0f, 1f)
    val scale by animateFloatAsState(if (dragging) 0.975f else 1f, spring(dampingRatio = .72f, stiffness = 430f), label = "queueRowScale")
    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp))) {
        Box(Modifier.fillMaxSize().background(ink.copy(alpha = if (progress > .001f) .08f + progress * .22f else 0f)), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.padding(end = 16.dp).size(38.dp).graphicsLayer {
                scaleX = .72f + .28f * progress; scaleY = .72f + .28f * progress
                alpha = progress; this.alpha = progress
            }.blur(((1f - progress) * 8f).dp).clip(CircleShape).background(ink.copy(alpha = .22f)), contentAlignment = Alignment.Center) {
                Image(MiuixIcons.Regular.Delete, tr("delete", "删除"), Modifier.size(21.dp), colorFilter = ColorFilter.tint(ink))
            }
        }
        Row(Modifier.fillMaxWidth().graphicsLayer { translationX = offset.value; scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp)).background(rowHighlight)
            .pointerInput(song.key) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        if (amount < 0f || offset.value < -1f) { change.consume(); onDragState(true); scope.launch { offset.snapTo((offset.value + amount).coerceIn(-deleteWidth, 0f)) } }
                    },
                    onDragEnd = {
                        scope.launch { if (offset.value <= -deleteWidth * .78f) { offset.animateTo(-deleteWidth, spring(.78f, 520f)); model.removeQueue(index) } else offset.animateTo(0f, spring(.82f, 380f)); onDragState(false) }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, spring(.82f, 380f)); onDragState(false) } },
                )
            }.clickable { if (offset.value > -1f) model.queueTo(index) }
            .padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(song.artwork, Modifier.size(44.dp), 8.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(song.title, color = rowTitleInk, fontSize = 15.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = rowTitleInk.copy(alpha = .66f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(reorderHandle(Modifier.size(40.dp, 44.dp)).semantics {
                contentDescription = tr("reorder_queue", "调整播放顺序")
                customActions = listOf(
                    CustomAccessibilityAction(tr("move_up", "上移")) { if (index > 0) { model.moveQueue(index, index - 1); true } else false },
                    CustomAccessibilityAction(tr("move_down", "下移")) { if (index < model.queue.lastIndex) { model.moveQueue(index, index + 1); true } else false },
                )
            }, contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_player_reorder), null, Modifier.size(22.dp * PlayerIconScale), colorFilter = ColorFilter.tint(ink.copy(alpha = .42f)))
            }
        }
    }
}

@Composable
private fun PlayerQueue(model: AuraViewModel, modifier: Modifier = Modifier, expressive: PlayerExpressiveColors? = null, oneUi: PlayerOneUiColors? = null) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = model.queueIndex.coerceAtLeast(0))
    val density = LocalDensity.current
    val rowStep = with(density) { 58.dp.toPx() }
    val queueSize by rememberUpdatedState(model.queue.size)
    var draggingIndex by remember { mutableIntStateOf(-1) }
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
        contentPadding = PaddingValues(top = 8.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(model.queue) { index, song ->
            val current = index == model.queueIndex
            QueueSwipeRow(song, current, index, model, draggingIndex == index,
                onDragState = { draggingIndex = if (it) index else -1 },
                reorderHandle = { base -> base.pointerInput(rowStep) {
                    var remainder = 0f
                    var from = index
                    detectVerticalDragGestures(
                        onDragStart = { from = index; draggingIndex = from; remainder = 0f },
                        onDragEnd = { draggingIndex = -1 }, onDragCancel = { draggingIndex = -1 },
                    ) { change, amount ->
                        change.consume(); remainder += amount
                        while (abs(remainder) >= rowStep) {
                            val direction = if (remainder > 0f) 1 else -1
                            val to = (from + direction).coerceIn(0, (queueSize - 1).coerceAtLeast(0))
                            if (to == from) { remainder = 0f; break }
                            model.moveQueue(from, to); from = to; draggingIndex = to
                            remainder -= direction * rowStep
                        }
                    }
                } },
                expressive = expressive,
                oneUi = oneUi)
        }
    }
    }
}

/** Feedback is clipped by the actual cover/handle shape, including during shared transforms. */
@Composable
private fun Modifier.playerSurfaceClick(onClick: () -> Unit): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val feedback by animateFloatAsState(if (pressed) .14f else if (hovered) .07f else 0f, tween(120), label = "playerSurfaceFeedback")
    return clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
        .drawWithContent { drawContent(); if (feedback > 0f) drawRect(Color.White.copy(alpha = feedback)) }
}

@Composable
private fun PlayerButton(icon: Int, label: String, color: Color, iconSize: Dp, touchSize: Dp, onClick: () -> Unit) {
    Box(Modifier.size(touchSize).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Image(painterResource(icon), null, Modifier.size(iconSize * PlayerIconScale), colorFilter = ColorFilter.tint(color))
    }
}

@Composable
private fun PillSlider(value: Float, label: String, modifier: Modifier, onChange: (Float) -> Unit,
                       onFinished: () -> Unit, enabled: Boolean = true) {
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
        drawRoundRect(Color.White.copy(alpha = .32f), Offset(0f, top), Size(size.width, thickness), CornerRadius(thickness / 2))
        val fill = size.width * value.coerceIn(0f, 1f)
        if (fill > 0) drawRoundRect(Color.White.copy(alpha = .82f), Offset(0f, top), Size(fill, thickness), CornerRadius(thickness / 2))
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
    val manager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val max = remember(manager) { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(manager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }
    var adjusting by remember { mutableStateOf(false) }
    LaunchedEffect(manager) { while (true) { if (!adjusting) volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat(); delay(300) } }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Speaker(
            loud = false,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(enabled = !manager.isVolumeFixed, role = Role.Button) {
                    volume = stepMediaVolume(manager, max, increase = false)
                },
        )
        PillSlider(volume, tr("media_volume", "媒体音量"), Modifier.weight(1f).height(44.dp), onChange = {
            adjusting = true; volume = it; manager.setStreamVolume(AudioManager.STREAM_MUSIC, (it * max).roundToInt(), 0)
        }, onFinished = { adjusting = false }, enabled = !manager.isVolumeFixed)
        Speaker(
            loud = true,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(enabled = !manager.isVolumeFixed, role = Role.Button) {
                    volume = stepMediaVolume(manager, max, increase = true)
                },
        )
    }
}

@Composable
private fun Speaker(loud: Boolean, modifier: Modifier) {
    Image(
        painter = painterResource(if (loud) R.drawable.ic_player_volume_full else R.drawable.ic_player_volume_muted),
        contentDescription = if (loud) tr("volume_up", "增大音量") else tr("volume_down", "减小音量"),
        modifier = modifier.padding(10.dp).size(24.dp * PlayerIconScale),
        colorFilter = ColorFilter.tint(Color.White.copy(alpha = .72f)),
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

