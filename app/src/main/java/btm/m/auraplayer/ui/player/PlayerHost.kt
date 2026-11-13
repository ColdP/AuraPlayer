// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import android.media.AudioManager
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
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.R
import btm.m.auraplayer.lyrics.PlayerLyricView
import btm.m.auraplayer.playback.StreamQuality
import btm.m.auraplayer.playback.NowPlayingConfig
import btm.m.auraplayer.playback.PlayerTheme
import btm.m.auraplayer.playback.CoverAnimation
import btm.m.auraplayer.ui.*
import btm.m.auraplayer.ui.GlassIconDropdownMenu
import coil3.compose.AsyncImage
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
        try {
            events.collect { event -> target.snapTo(start * (1f - event.progress.coerceIn(0f, model.settings.value.maxBackProgress))) }
            target.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
            if (secondary) { lyricsOpen = false; queueOpen = false } else onOpenChange(false)
        } catch (cancel: CancellationException) {
            scope.launch { target.animateTo(1f, spring(.88f, 320f)) }
        } finally { gesturing = false }
    }
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val miniColor = if (dark) Color.White else Color.Black
    val style = model.settings.value.navigation
    val playerBackdrop = rememberLayerBackdrop()
    val miuixBackdrop = rememberMiuixBackdrop()
    val coverPalette = rememberPlayerMonetPalette(song.artwork)
    val shareActive = lyricShareTransition?.active == true
    val shareProgress = if (shareActive) lyricShareTransition.progress.coerceIn(0f, 1f) else 0f
    val shareBgAlpha = if (shareActive) 1f - lyricShareSmoothStep(0f, .86f, shareProgress) else 1f
    val shareBgBlur = if (shareActive) (shareProgress * 28f).dp else 0.dp
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
                } finally { settlingSwipe = false; gesturing = false }
            }
        }
        val shape = RoundedCornerShape((miniHeight / 2) * (1f - e) + screenCorner * e)
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
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = e * shareBgAlpha }.blur(shareBgBlur).layerBackdrop(playerBackdrop).miuixLayerBackdrop(miuixBackdrop))
                    FullPlayer(model, lyricProgress.value, expansion = e, miniColor = miniColor, miniSwipe = miniSwipeOffset.value + miniDrag.horizontalOffset,
                        miniWidth = miniWidth, miniHeight = miniHeight,
                        miniControlClearance = 110.dp - 50.dp * dock,
                        appearance = appearance, active = open && foreground && !shareActive, controlsUnobscured = !actionsBusy && !lyricMenuOpen && !shareActive,
                        mixVisual = mixVisual,
                        shareTransition = lyricShareTransition,
                        onExpand = { onOpenChange(true) }, onClose = { onOpenChange(false) },
                        swipeEnabled = open && !settlingSwipe && !lyricMenuOpen && !actionsBusy && !shareOpening,
                        onSwipeStart = { gesturing = true; scope.launch { expansion.stop() } },
                        onSwipeDelta = { swipeDistance = (swipeDistance + it).coerceIn(0f, swipeRange) },
                        onSwipeRelease = { settleSwipe(it, false) }, onSwipeCancel = { settleSwipe(0f, true) },
                        onLyrics = { lyricsOpen = !lyricsOpen; queueOpen = false }, queueOpen = queueOpen,
                        lyricsOpen = lyricsOpen, queueBlend = queueBlend.value, backdrop = playerBackdrop, menuTint = Color(coverPalette.a2.tone(20)),
                        onLyricMenuExpanded = { lyricMenuOpen = it },
                        miuixBackdrop = miuixBackdrop, onMore = { actionsOpen = true },
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
    CompositionLocalProvider(LocalDialogBackdrop provides playerBackdrop) {
        PlayerActionsSheet(model, actionsOpen, playerBackdrop,
            dismiss = { actionsOpen = false }, openPage = openPage, closePlayer = { onOpenChange(false) }, onBusy = { actionsBusy = it })
    }
}

@Composable
private fun FullPlayer(model: AuraViewModel, progress: Float, expansion: Float,
                       appearance: NowPlayingConfig, active: Boolean, controlsUnobscured: Boolean,
                       mixVisual: btm.m.auraplayer.auramix.AuraMixVisualTransition?,
                       shareTransition: LyricShareTransitionState? = null,
                       miniColor: Color, miniSwipe: Float, miniWidth: Dp, miniHeight: Dp,
                       miniControlClearance: Dp, onExpand: () -> Unit,
                       swipeEnabled: Boolean, onSwipeStart: () -> Unit, onSwipeDelta: (Float) -> Unit,
                       onSwipeRelease: (Float) -> Unit, onSwipeCancel: () -> Unit,
                       onClose: () -> Unit, onLyrics: () -> Unit, queueOpen: Boolean,
                       lyricsOpen: Boolean, queueBlend: Float, backdrop: Backdrop, menuTint: Color,
                       onLyricMenuExpanded: (Boolean) -> Unit,
                       miuixBackdrop: top.yukonga.miuix.kmp.blur.Backdrop, onMore: () -> Unit,
                       onQueue: () -> Unit, openSharePage: (Int, Rect?, Rect?) -> Unit) {
    val song = model.current ?: return
    val density = LocalDensity.current
    val p = progress.coerceIn(0f, 1f)
    val alpha = ((expansion - .12f) / .88f).coerceIn(0f, 1f)
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val headerBottom = with(density) { (status + 48.dp * PlayerPaddingScale + 84.dp).toPx() }
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
        val fullCard = fullscreenPlayerCard(w, h, status, nav)
        val normalMargin = w * .058f * PlayerPaddingScale
        fun controlSize(cardSize: Dp, normalSize: Dp): Dp = if (fullscreen) cardSize + (normalSize - cardSize) * p else normalSize
        val margin = controlSize(fullCard.left + 16.dp, normalMargin)
        val coverY = maxOf(h * .142f, status + 44.dp * PlayerPaddingScale)
        // Reserve the actual fixed controls height before sizing the cover on short screens.
        val controlsHeight = if (appearance.showVolume) 250.dp else 206.dp
        val seekCeiling = h - nav - controlsHeight - 10.dp
        val titleGap = 20.dp * PlayerPaddingScale
        val titleToSeek = 63.dp * PlayerPaddingScale
        val coverSize = minOf(w - normalMargin * 2, (h - status - nav) * .46f,
            (seekCeiling - coverY - titleGap - titleToSeek).coerceAtLeast(1.dp)).coerceAtLeast(1.dp)
        // Subtracting equivalent Float expressions can produce a tiny negative Dp.
        val coverInset = playerCoverInset(w, coverSize, normalMargin)
        val coverX = normalMargin + coverInset
        val normalTitleY = coverY + coverSize + titleGap
        val small = minOf(64.dp, coverSize * .4f) * .86f
        val smallY = status + 48.dp * PlayerPaddingScale
        val smallX = 26.dp * PlayerPaddingScale
        val e = expansion.coerceIn(0f, 1f)
        val normalSeekY = maxOf(normalTitleY + titleToSeek, h * .637f).coerceAtMost(seekCeiling)
        val seekY = controlSize(fullCard.top + 64.dp, normalSeekY)
        val titleY = if (fullscreen) fullCard.top + 14.dp else normalTitleY
        val controlsScale = if (fullscreen) ((fullCard.height - 76.dp) / 180.dp).coerceIn(.5f, 1.12f) else 1f
        val fullWidthPx = with(density) { w.toPx() }
        if (fullscreen && alpha > 0f) {
            val glassTop = fullCard.top
            val cardLeftPx = with(density) { fullCard.left.toPx() }
            val cardTopPx = with(density) { glassTop.toPx() }
            val cardWidthPx = with(density) { fullCard.width.toPx() }
            val cardHeightPx = with(density) { fullCard.height.toPx() }
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
            Box(Modifier.offset(fullCard.left, glassTop).size(fullCard.width, fullCard.height)
                .graphicsLayer { this.alpha = alpha * (1f - p) * shareChromeAlpha }.blur(shareChromeBlur)
                .then(cardMaterial).clip(RoundedCornerShape(30.dp)).drawWithContent {
                    drawContent()
                    if (appearance.fullscreenGlass && touchLight > .001f) {
                        val radius = size.width * .65f
                        drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = touchLight), Color.White.copy(alpha = touchLight * .32f), Color.Transparent),
                            center = localTouch, radius = radius.coerceAtLeast(1f)))
                    }
                })
            if (p < .01f) Box(Modifier.fillMaxWidth().height(glassTop.coerceAtLeast(0.dp))
                .playerTrackSwipe(appearance.swipeTracks && active, model::next, model::previous))
        }
        // Only controls and secondary content blur/fade; artwork, metadata, and exiting lyrics transform independently.
        if (alpha > 0f) Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha * shareChromeAlpha }
            .blur(((1f - alpha) * 14f).dp + shareChromeBlur)) {
        Box(Modifier.align(Alignment.TopCenter).offset(y = status + 12.dp * PlayerPaddingScale).size(104.dp, 32.dp)
            .clip(RoundedCornerShape(50))
            .semantics { contentDescription = tr("collapse_player", "收起播放器") }
            .playerSurfaceClick(onClose), contentAlignment = Alignment.Center) {
            Box(Modifier.width(80.dp).height(6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .36f)))
        }
        if (queueBlend > .001f && p > 0f) PlayerQueue(model, Modifier
            .offset(y = smallY + small + 20.dp * PlayerPaddingScale)
            .fillMaxWidth().height((seekY + 50.dp - smallY - small - 28.dp * PlayerPaddingScale).coerceAtLeast(1.dp))
            .graphicsLayer { this.alpha = p * queueBlend; translationY = (1f - p) * 24.dp.toPx() }
            .blur(((1f - p) * 12f + queueBlend * (1f - queueBlend) * 20f).dp).padding(horizontal = smallX))
        if (controlsVisibility > .001f) Column(Modifier.offset(y = seekY).fillMaxWidth()
            .graphicsLayer { this.alpha = controlsVisibility }.blur(((1f - controlsVisibility) * 14f).dp)
            .height(controlSize((fullCard.height - 76.dp).coerceAtLeast(1.dp),
                (h - normalSeekY - nav - 10.dp).coerceAtLeast(controlsHeight))).padding(horizontal = margin)) {
            var scrub by remember(song.key) { mutableStateOf<Float?>(null) }
            val value = scrub ?: if (model.duration > 0) model.position.toFloat() / model.duration else 0f
            Column(Modifier.graphicsLayer { this.alpha = 1f - queueBlend }) {
            PillSlider(value, tr("playback_progress", "播放进度"), Modifier.padding(horizontal = controlSize(0.dp, coverInset.coerceAtLeast(0.dp))).fillMaxWidth().height(controlSize(20.dp * controlsScale, 24.dp))
                .then(if (queueBlend > .5f) Modifier.clearAndSetSemantics {} else Modifier),
                onChange = { scrub = it }, onFinished = { model.seekTo(((scrub ?: value) * model.duration).toLong()); scrub = null }, enabled = model.duration > 0 && !queueOpen)
            Box(Modifier.padding(horizontal = controlSize(0.dp, coverInset.coerceAtLeast(0.dp))).fillMaxWidth().height(controlSize(20.dp * controlsScale, 26.dp))) {
                Text(timeText(((scrub?.let { it * model.duration } ?: model.position.toFloat())).toLong()), color = Color.White.copy(alpha = .85f), fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterStart))
                Text(timeText(model.duration), color = Color.White.copy(alpha = .85f), fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterEnd))
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
                        entry = DropdownEntry(qualityItems), backdrop = backdrop, modifier = Modifier.align(Alignment.Center), enabled = !queueOpen,
                        onExpandedChange = { qualityMenuOpen = it },
                        trigger = { toggle, enabled ->
                            Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) {
                                model.refreshQualities(); toggle()
                            }.padding(horizontal = 7.dp, vertical = 4.dp)) {
                                Text(qualityName(actualQuality ?: model.quality), color = Color.White.copy(alpha = .85f), fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            }
                        }, content = {})
                } else if (appearance.showAudioInfo) Text(localAudioText(model), color = Color.White.copy(alpha = .85f), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 45.dp))
            }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().height(controlSize(64.dp * controlsScale, 100.dp)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                PlayerButton(R.drawable.ic_player_previous, tr("previous_track", "上一首"), Color.White, controlSize(32.dp * controlsScale, 48.dp), controlSize(52.dp * controlsScale, 76.dp), model::previous)
                PlayerButton(if (model.playbackRequested) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                    if (model.playbackRequested) tr("pause", "暂停") else tr("play", "播放"), Color.White, controlSize(44.dp * controlsScale, 60.dp * .88f), controlSize(64.dp * controlsScale, 96.dp), model::togglePlayback)
                PlayerButton(R.drawable.ic_player_next, tr("next_track", "下一首"), Color.White, controlSize(32.dp * controlsScale, 48.dp), controlSize(52.dp * controlsScale, 76.dp), model::next)
            }
            Spacer(Modifier.weight(1f))
            if (appearance.showVolume) VolumeControl(Modifier.fillMaxWidth().height(controlSize(32.dp * controlsScale, 44.dp)))
            val bottomTouch = controlSize(40.dp * controlsScale, 48.dp)
            val bottomIcon = controlSize(27.dp * controlsScale, 32.dp)
            Row(Modifier.fillMaxWidth().height(controlSize(44.dp * controlsScale, 56.dp)), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(bottomTouch).semantics { selected = lyricsOpen }) {
                    PlayerButton(if (lyricsOpen) R.drawable.ic_player_lyrics_on else R.drawable.ic_player_lyrics, tr("lyrics", "歌词"), Color.White.copy(alpha = .9f), bottomIcon, bottomTouch, onLyrics)

                }
                val modeDescription = when (model.playbackMode) { 1 -> tr("shuffle_mode", "随机播放"); 2 -> tr("repeat_one_mode", "单曲循环"); else -> tr("repeat_all_mode", "列表循环") }
                PlayerButton(when (model.playbackMode) { 1 -> R.drawable.ic_player_shuffle; 2 -> R.drawable.ic_player_repeat_one; else -> R.drawable.ic_player_repeat }, modeDescription, Color.White.copy(alpha = .9f), bottomIcon, bottomTouch, model::cyclePlaybackMode)
                val revision = model.history.revision
                val favorite = model.isFavorite(song)
                PlayerButton(if (favorite) R.drawable.ic_player_favorite_on else R.drawable.ic_player_favorite,
                    if (favorite) tr("unfavorite", "取消收藏") else tr("favorite", "收藏"), Color.White.copy(alpha = .9f), bottomIcon, bottomTouch) { model.toggleFavorite(song) }
                Box(Modifier.semantics { selected = queueOpen }) {
                    PlayerButton(if (queueOpen) R.drawable.ic_player_queue_on else R.drawable.ic_player_queue, tr("play_queue", "播放队列"), Color.White.copy(alpha = .9f), bottomIcon, bottomTouch, onQueue)
                }
            }
        }
        }
        if (alpha > 0f && p > 0f && queueBlend < .999f) {
            Box(Modifier.offset(y = smallY + small + 20.dp * PlayerPaddingScale).fillMaxWidth()
                .height((seekY - smallY - small - 35.dp * PlayerPaddingScale + (h - nav - seekY) * (1f - controlsVisibility)).coerceAtLeast(1.dp))
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
                    Text(if (model.lyricsLoading) tr("lyrics_loading", "正在获取歌词…") else tr("lyrics_empty", "暂无歌词"), color = Color.White.copy(alpha = .55f), fontSize = 20.sp)
                } else AndroidView(factory = { PlayerLyricView(it) }, update = {
                    it.submit(model.lyrics, model.showTranslation, model.showPronunciation, model.lyricSize.scale)
                    it.position(model.position, model.playing)
                    it.onSeek = model::seekTo
                    it.onLongPress = { index -> openSharePage(index, it.rowBoundsInWindow(index)?.toComposeRect(), it.viewportBoundsInWindow().toComposeRect()) }
                    it.keepScreenOn = appearance.keepLyricsAwake && active && lyricsOpen
                }, modifier = Modifier.fillMaxSize())
            }
        }
        val fullCoverSize = coverSize + (small - coverSize) * p
        val miniScale = miniHeight / PlayerNavigationGeometry.miniHeight
        val miniCoverSize = 44.dp * miniScale
        val miniCoverX = 12.dp * miniScale
        val miniCoverY = (miniHeight - miniCoverSize) / 2
        val sharedSize = lerp(miniCoverSize, fullCoverSize, e)
        val sharedX = lerp(miniCoverX, coverX + (smallX - coverX) * p, e)
        val sharedY = lerp(miniCoverY, coverY + (smallY - coverY) * p, e)
        val sharedScale = sharedSize / coverSize
        val sharedRadius = lerp(13.dp * miniScale, 24.dp + (9.dp - 24.dp) * p, e)
        val sharedClick = if (e < .5f) onExpand else if (queueOpen) onQueue else onLyrics
        val miniShift = miniSwipe / density.density
        val miniAlpha = (1f - abs(miniSwipe) / fullWidthPx.coerceAtLeast(1f) * .35f).coerceIn(.55f, 1f)
        Cover(song.artwork, Modifier.offset(sharedX, sharedY).size(coverSize).graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0f); scaleX = sharedScale; scaleY = sharedScale
            translationX = miniShift
            this.alpha = miniAlpha * (if (fullscreen) 1f - e * (1f - p) else 1f) * shareChromeAlpha
        }.blur(shareChromeBlur).playerTrackSwipe(appearance.swipeTracks && active && p < .01f && !fullscreen, model::next, model::previous),
            sharedRadius / sharedScale, onClick = { if (!shareActive) sharedClick() }, mixVisual = mixVisual,
            animation = appearance.coverAnimation, animate = active && e > .99f, transitionKey = song.key)
        val compactTextX = smallX + small + 12.dp * PlayerPaddingScale
        val expandedTextX = if (fullscreen) fullCard.left + 16.dp else coverX
        val fullTextX = expandedTextX + (compactTextX - expandedTextX) * p
        // Only the fullscreen controls card uses smaller metadata. Restore the normal
        // typography continuously as it morphs into lyrics/queue or the mini player.
        val metadataSize = if (fullscreen) 1f - .12f * e * (1f - p) else 1f
        var textHeight by remember { mutableIntStateOf(0) }
        val measuredTextHeight = with(density) { if (textHeight > 0) textHeight.toDp() else (46.sp * metadataSize).toDp() }
        val compactTextY = smallY + (small - measuredTextHeight * .78f) / 2
        val fullTextY = titleY + (compactTextY - titleY) * p
        val textScale = .7f * miniScale + ((1f - .22f * p) - .7f * miniScale) * e
        val miniTextX = 66.dp * miniScale
        val miniTextWidth = (miniWidth - miniTextX - miniControlClearance).coerceAtLeast(24.dp)
        val expandedTextWidth = if (fullscreen) (fullCard.width - 84.dp).coerceAtLeast(24.dp) else coverSize
        val fullTextWidth = expandedTextWidth + ((w - compactTextX - 68.dp).coerceAtLeast(24.dp) - expandedTextWidth) * p
        val textWidth = (miniTextWidth + (fullTextWidth - miniTextWidth) * e) / textScale
        val textColor = lerp(miniColor, Color.White, e)
        val miniTextY = (miniHeight - measuredTextHeight * .7f * miniScale) / 2
        val mixProgress = mixVisual?.progress?.coerceIn(0f, 1f)
        val mixFromAlpha = mixProgress?.let { (1f - it).coerceIn(0f, 1f) } ?: 0f
        val mixToAlpha = mixProgress?.coerceIn(0f, 1f) ?: 0f
        Box(Modifier.offset(lerp(miniTextX, fullTextX, e), miniTextY + (fullTextY - miniTextY) * e)
            .width(textWidth).height(measuredTextHeight).graphicsLayer {
                translationX = miniShift
                this.alpha = miniAlpha * shareChromeAlpha
            }.blur(shareChromeBlur)) {
        Column(Modifier.fillMaxSize()
            .width(textWidth).onSizeChanged { textHeight = it.height }.graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f); scaleX = textScale; scaleY = textScale
                this.alpha = miniAlpha * if (mixVisual == null) 1f else mixFromAlpha
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !shareActive, onClick = sharedClick)) {
            Text(mixVisual?.fromTitle ?: song.title, color = textColor, fontSize = 20.sp * metadataSize, lineHeight = 25.sp * metadataSize,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(mixVisual?.fromArtist ?: song.artist, color = textColor.copy(alpha = .8f), fontSize = 16.sp * metadataSize, lineHeight = 21.sp * metadataSize,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (mixVisual != null) Column(Modifier.fillMaxSize().graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0f); scaleX = textScale; scaleY = textScale
            this.alpha = miniAlpha * mixToAlpha
        }) {
            Text(mixVisual.toTitle, color = textColor, fontSize = 20.sp * metadataSize, lineHeight = 25.sp * metadataSize,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(mixVisual.toArtist, color = textColor.copy(alpha = .8f), fontSize = 16.sp * metadataSize, lineHeight = 21.sp * metadataSize,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        /* Keep the normal click target over the two metadata layers. */
        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !shareActive, onClick = sharedClick))
        }

        // One button moves and scales between both layouts, outside the content's blur layer.
        if (alpha > 0f) {
            val buttonSize = 46.dp
            val bigX = if (fullscreen) fullCard.left + fullCard.width - 16.dp - buttonSize else coverX + coverSize - buttonSize
            val bigY = if (fullscreen) titleY + (measuredTextHeight - buttonSize) / 2 else status + 8.dp
            val compactX = w - 68.dp
            val compactY = smallY + (small - buttonSize) / 2
            Box(Modifier.offset(bigX + (compactX - bigX) * p, bigY + (compactY - bigY) * p)
                .size(buttonSize).graphicsLayer { this.alpha = alpha * shareChromeAlpha; scaleX = 1f - .08f * p; scaleY = scaleX }
                .blur(shareChromeBlur)
                .clip(RoundedCornerShape(50)).clickable(enabled = !shareActive, onClick = onMore), contentAlignment = Alignment.Center) {
                Box(Modifier.size(46.dp * .85f * .92f).playerMenuMaterial(miuixBackdrop, menuTint, 50.dp)) {
                    Image(MiuixIcons.Regular.More, tr("more", "更多"), Modifier.align(Alignment.Center).size(24.dp * PlayerIconScale * .92f), colorFilter = ColorFilter.tint(Color.White))
                }
            }
        }

        if (alpha > 0f && p > 0f && queueBlend < .01f && controlsVisibility > .001f) {
            LyricOptionsMenu(model, miuixBackdrop, menuTint, if (fullscreen) margin else coverX, seekY - 52.dp,
                w, status + 8.dp,
                Modifier.graphicsLayer { this.alpha = alpha * p * controlsVisibility * shareChromeAlpha }
                    .blur(((1f - p + 1f - controlsVisibility) * 14f).dp + shareChromeBlur), onLyricMenuExpanded)
        }

    }
}

@Composable
private fun Cover(uri: String, modifier: Modifier, radius: Dp, onClick: (() -> Unit)? = null,
                  animation: CoverAnimation = CoverAnimation.DEFAULT, animate: Boolean = false, transitionKey: String = uri,
                  mixVisual: btm.m.auraplayer.auramix.AuraMixVisualTransition? = null) {
    val progress = mixVisual?.progress?.coerceIn(0f, 1f)
    Box(modifier.clip(RoundedCornerShape(radius))
        .then(if (onClick == null) Modifier else Modifier.playerSurfaceClick(onClick))
        .background(Color.White.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
        if (mixVisual == null) {
            if (uri.isNotBlank()) AnimatedPlayerArtwork(uri, transitionKey, animation, animate)
            else Image(painterResource(R.drawable.ic_player_queue), null, Modifier.fillMaxSize(.3f * PlayerIconScale), colorFilter = ColorFilter.tint(Color.White.copy(alpha = .3f)))
        } else {
            val fromAlpha = 1f - progress!!
            val toAlpha = progress
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = fromAlpha }.blur((progress * 26f).dp)) {
                if (mixVisual.fromArtwork.isNotBlank()) AsyncImage(mixVisual.fromArtwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else if (uri.isNotBlank()) AnimatedPlayerArtwork(uri, transitionKey, animation, animate)
            }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = toAlpha }.blur(((1f - progress) * 26f).dp)) {
                if (mixVisual.toArtwork.isNotBlank()) AsyncImage(mixVisual.toArtwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    }
}

@Composable
private fun QueueSwipeRow(song: btm.m.auraplayer.data.Song, current: Boolean, index: Int, model: AuraViewModel,
                          dragging: Boolean, onDragState: (Boolean) -> Unit, reorderHandle: (Modifier) -> Modifier) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val deleteWidth = with(density) { 92.dp.toPx() }
    val offset = remember(song.key) { Animatable(0f) }
    val progress = (-offset.value / deleteWidth).coerceIn(0f, 1f)
    val scale by animateFloatAsState(if (dragging) 0.975f else 1f, spring(dampingRatio = .72f, stiffness = 430f), label = "queueRowScale")
    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp))) {
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = if (progress > .001f) .08f + progress * .22f else 0f)), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.padding(end = 16.dp).size(38.dp).graphicsLayer {
                scaleX = .72f + .28f * progress; scaleY = .72f + .28f * progress
                alpha = progress; this.alpha = progress
            }.blur(((1f - progress) * 8f).dp).clip(CircleShape).background(Color.White.copy(alpha = .22f)), contentAlignment = Alignment.Center) {
                Image(MiuixIcons.Regular.Delete, tr("delete", "删除"), Modifier.size(21.dp), colorFilter = ColorFilter.tint(Color.White))
            }
        }
        Row(Modifier.fillMaxWidth().graphicsLayer { translationX = offset.value; scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp)).background(if (current || dragging) Color.White.copy(alpha = if (dragging) .14f else .10f) else Color.Transparent)
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
                Text(song.title, color = Color.White, fontSize = 15.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = Color.White.copy(alpha = .66f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(reorderHandle(Modifier.size(40.dp, 44.dp)).semantics {
                contentDescription = tr("reorder_queue", "调整播放顺序")
                customActions = listOf(
                    CustomAccessibilityAction(tr("move_up", "上移")) { if (index > 0) { model.moveQueue(index, index - 1); true } else false },
                    CustomAccessibilityAction(tr("move_down", "下移")) { if (index < model.queue.lastIndex) { model.moveQueue(index, index + 1); true } else false },
                )
            }, contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_player_reorder), null, Modifier.size(22.dp * PlayerIconScale), colorFilter = ColorFilter.tint(Color.White.copy(alpha = .42f)))
            }
        }
    }
}

@Composable
private fun PlayerQueue(model: AuraViewModel, modifier: Modifier = Modifier) {
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
                } })
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

@Composable
private fun VolumeControl(modifier: Modifier) {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val max = remember(manager) { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(manager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }
    var adjusting by remember { mutableStateOf(false) }
    LaunchedEffect(manager) { while (true) { if (!adjusting) volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat(); delay(300) } }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Speaker(false, Modifier.size(40.dp))
        PillSlider(volume, tr("media_volume", "媒体音量"), Modifier.weight(1f).height(44.dp), onChange = {
            adjusting = true; volume = it; manager.setStreamVolume(AudioManager.STREAM_MUSIC, (it * max).roundToInt(), 0)
        }, onFinished = { adjusting = false }, enabled = !manager.isVolumeFixed)
        Speaker(true, Modifier.size(40.dp))
    }
}

@Composable
private fun Speaker(loud: Boolean, modifier: Modifier) {
    Image(painterResource(if (loud) R.drawable.ic_player_volume_full else R.drawable.ic_player_volume_muted),
        null, modifier.padding(10.dp).size(24.dp * PlayerIconScale), colorFilter = ColorFilter.tint(Color.White.copy(alpha = .72f)))
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

