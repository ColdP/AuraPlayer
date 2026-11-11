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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
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
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
}

@Composable
internal fun PlayerHost(model: AuraViewModel, backdrop: Backdrop, miniVisible: Boolean,
                        open: Boolean, onOpenChange: (Boolean) -> Unit) {
    val song = model.current ?: return
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
    val miniSwipeOffset = remember { Animatable(0f) }
    var miniSwipeDrag by remember { mutableFloatStateOf(0f) }
    var miniVerticalProgress by remember { mutableFloatStateOf(0f) }
    var miniHorizontal by remember { mutableStateOf(false) }
    var miniVertical by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (!gesturing) expansion.animateTo(if (open) 1f else 0f, spring(.88f, 300f, visibilityThreshold = .0005f))
    }
    LaunchedEffect(compactOpen) {
        if (!gesturing) lyricProgress.animateTo(if (compactOpen) 1f else 0f, spring(.86f, 280f, visibilityThreshold = .0005f))
    }
    LaunchedEffect(queueOpen, lyricsOpen) {
        queueBlend.animateTo(if (queueOpen) 1f else 0f, tween(280, easing = FastOutSlowInEasing))
    }
    fun back() {
        if (compactOpen) { lyricsOpen = false; queueOpen = false }
        else onOpenChange(false)
    }
    BackHandler(open && !model.settings.value.predictive && !lyricMenuOpen) { back() }
    PredictiveBackHandler(enabled = open && model.settings.value.predictive && !lyricMenuOpen && swipeDistance == 0f && !settlingSwipe) { events ->
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
    val material = rememberModuleNavigationMaterial(model.settings.value.navigation)
    val style = model.settings.value.navigation
    val liquid = style == "liquid_glass"
    val harmony = style == "harmony_os_floating"
    val tint = if (liquid) (if (dark) Color(0xFF121212) else Color(0xFFFAFAFA)).copy(alpha = .4f)
        else if (dark) Color.Black.copy(alpha = if (harmony) .36f else .30f)
        else Color(0xFFF5F5F5).copy(alpha = if (harmony) .48f else .42f)
    val playerBackdrop = rememberLayerBackdrop()
    val coverPalette = rememberPlayerMonetPalette(song.artwork)
    val hostAlpha by animateFloatAsState(if (miniVisible || open) 1f else 0f, tween(280, easing = FastOutSlowInEasing), label = "playerHostVisibility")
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer {
        alpha = hostAlpha
        translationY = (1f - hostAlpha) * 18.dp.toPx()
    }) {
        val screenWidth = maxWidth; val screenHeight = maxHeight
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val metrics = PlayerNavigationGeometry
        val screenCorner = rememberBottomScreenCornerRadius(0.dp)
        val density = LocalDensity.current
        val densityScale = density.density
        val screenWidthPx = with(density) { screenWidth.toPx() }
        val miniThresholdPx = with(density) { 42.dp.toPx() }
        val miniDragSlopPx = with(density) { 10.dp.toPx() }
        val miniY = screenHeight - nav - metrics.bottomSpacing - metrics.barHeight - metrics.miniGap - metrics.miniHeight
        val swipeRange = with(LocalDensity.current) { miniY.toPx().coerceAtLeast(1f) }
        val gestureExpansion = if (miniVertical) maxOf(expansion.value, miniVerticalProgress) else expansion.value
        val e = (gestureExpansion - swipeDistance / swipeRange).coerceIn(0f, 1f)
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
        val shape = RoundedCornerShape((metrics.miniHeight / 2) * (1f - e) + screenCorner * e)
        Box(Modifier.offset(metrics.outerInset * (1f - e), miniY * (1f - e))
            .size(screenWidth - metrics.outerInset * 2 * (1f - e), metrics.miniHeight + (screenHeight - metrics.miniHeight) * e)
            .drawBackdrop(backdrop = backdrop, shape = { shape }, effects = {
                vibrancy()
                backdropBlur((if (liquid) 3f else 18f * if (harmony) .29f else .24f).dp.toPx() * material.blur / 100f)
                if (liquid) lens(24.dp.toPx() * material.thickness / 100f, 24.dp.toPx() * material.refraction / 100f, chromaticAberration = material.dispersion ?: false)
                else lens(16.dp.toPx() * material.thickness / 100f, 32.dp.toPx() * material.refraction / 100f, depthEffect = true, chromaticAberration = material.dispersion ?: false)
            }, highlight = { Highlight.Default.copy(alpha = material.light((if (dark) .78f else .96f) * if (harmony) .72f else 1f) * (1f - e)) },
                shadow = { Shadow.Default.copy(radius = 6.dp, alpha = .35f * (1f - e)) },
                onDrawSurface = { drawRect(tint.copy(alpha = material.alpha(tint.alpha))) })
            .clip(shape)) {
            // Full content keeps its final measurement during the morph; only the container changes.
            Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(screenWidth, screenHeight)) {
                Box(Modifier.fillMaxSize()) {
                    PlayerAmbientBackground(coverPalette, Modifier.fillMaxSize().graphicsLayer { alpha = e }.layerBackdrop(playerBackdrop), animate = open && !gesturing)
                    FullPlayer(model, lyricProgress.value, expansion = e, miniColor = miniColor, miniSwipe = miniSwipeOffset.value + miniSwipeDrag,
                        onExpand = { onOpenChange(true) }, onClose = { onOpenChange(false) },
                        swipeEnabled = open && !settlingSwipe && !lyricMenuOpen,
                        onSwipeStart = { gesturing = true; scope.launch { expansion.stop() } },
                        onSwipeDelta = { swipeDistance = (swipeDistance + it).coerceIn(0f, swipeRange) },
                        onSwipeRelease = { settleSwipe(it, false) }, onSwipeCancel = { settleSwipe(0f, true) },
                        onLyrics = { lyricsOpen = !lyricsOpen; queueOpen = false }, queueOpen = queueOpen,
                        lyricsOpen = lyricsOpen, queueBlend = queueBlend.value, backdrop = playerBackdrop, menuTint = Color(coverPalette.a2.tone(20)),
                        onLyricMenuExpanded = { lyricMenuOpen = it },
                        onQueue = { queueOpen = !queueOpen; lyricsOpen = false })
                }
            }
            val miniAlpha = (1f - e / .34f).coerceIn(0f, 1f)
            if (miniAlpha > 0f) {
                Box(Modifier.offset(metrics.outerInset, miniY).size(screenWidth - metrics.outerInset * 2, metrics.miniHeight)
                    .clickable(enabled = !open, interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = { onOpenChange(true) })
                    .pointerInput(open, screenWidthPx, swipeRange) {
                        var totalX = 0f
                        var totalY = 0f
                        detectDragGestures(
                            onDragStart = {
                                totalX = 0f; totalY = 0f
                                miniHorizontal = false; miniVertical = false
                                miniSwipeDrag = 0f; miniVerticalProgress = 0f
                            },
                            onDragEnd = {
                                when {
                                    miniHorizontal && abs(miniSwipeOffset.value + miniSwipeDrag) > miniThresholdPx -> {
                                        val direction = if (miniSwipeOffset.value + miniSwipeDrag < 0f) 1 else -1
                                        scope.launch {
                                            miniSwipeOffset.snapTo(miniSwipeOffset.value + miniSwipeDrag)
                                            miniSwipeDrag = 0f
                                            miniSwipeOffset.animateTo(direction * screenWidthPx, tween(180, easing = FastOutSlowInEasing))
                                            if (direction > 0) model.next() else model.previous()
                                            miniSwipeOffset.snapTo(0f)
                                        }
                                    }
                                    miniVertical -> scope.launch {
                                        expansion.animateTo(1f, spring(.88f, 320f))
                                        miniVerticalProgress = 0f
                                        miniSwipeOffset.snapTo(0f)
                                    }
                                    else -> {
                                        onOpenChange(true)
                                        scope.launch {
                                            miniSwipeOffset.snapTo(miniSwipeOffset.value + miniSwipeDrag)
                                            miniSwipeDrag = 0f
                                            miniSwipeOffset.animateTo(0f, spring(.88f, 320f))
                                        }
                                    }
                                }
                                miniHorizontal = false; miniVertical = false
                                miniSwipeDrag = 0f; miniVerticalProgress = 0f
                            },
                            onDragCancel = {
                                miniHorizontal = false; miniVertical = false
                                miniVerticalProgress = 0f
                                scope.launch {
                                    miniSwipeOffset.snapTo(miniSwipeOffset.value + miniSwipeDrag)
                                    miniSwipeDrag = 0f
                                    miniSwipeOffset.animateTo(0f, spring(.88f, 320f))
                                }
                            },
                        ) { change, amount ->
                            totalX += amount.x; totalY += amount.y
                            if (!miniHorizontal && !miniVertical && max(abs(totalX), abs(totalY)) > miniDragSlopPx) {
                                if (abs(totalX) > abs(totalY)) miniHorizontal = true else if (totalY < 0f) {
                                    miniVertical = true
                                    onOpenChange(true)
                                    scope.launch { expansion.stop() }
                                }
                            }
                            when {
                                miniHorizontal -> { change.consume(); miniSwipeDrag = (miniSwipeDrag + amount.x).coerceIn(-screenWidthPx, screenWidthPx) }
                                miniVertical -> { change.consume(); miniVerticalProgress = (-totalY / swipeRange).coerceIn(0f, 1f) }
                            }
                        }
                    })
            }
            if (miniAlpha > 0f) Row(Modifier.fillMaxWidth().height(metrics.miniHeight)
                .graphicsLayer { alpha = miniAlpha }.blur((e * 24).dp)
                .padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PlayerButton(if (model.playbackRequested) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                    if (model.playbackRequested) tr("pause", "暂停") else tr("play", "播放"), miniColor, 38.dp * .8f * .92f, 40.dp, model::togglePlayback)
                PlayerButton(R.drawable.ic_player_next, tr("next_track", "下一首"), miniColor,
                    38.dp * .8f * .92f, 40.dp, model::next)
            }
        }
    }
}

@Composable
private fun FullPlayer(model: AuraViewModel, progress: Float, expansion: Float,
                       miniColor: Color, miniSwipe: Float, onExpand: () -> Unit,
                       swipeEnabled: Boolean, onSwipeStart: () -> Unit, onSwipeDelta: (Float) -> Unit,
                       onSwipeRelease: (Float) -> Unit, onSwipeCancel: () -> Unit,
                       onClose: () -> Unit, onLyrics: () -> Unit, queueOpen: Boolean,
                       lyricsOpen: Boolean, queueBlend: Float, backdrop: Backdrop, menuTint: Color,
                       onLyricMenuExpanded: (Boolean) -> Unit,
                       onQueue: () -> Unit) {
    val song = model.current ?: return
    val density = LocalDensity.current
    val p = progress.coerceIn(0f, 1f)
    val alpha = ((expansion - .12f) / .88f).coerceIn(0f, 1f)
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val headerBottom = with(density) { (status + 48.dp * PlayerPaddingScale + 84.dp).toPx() }
    BoxWithConstraints(Modifier.fillMaxSize()
        .playerDismissGesture(swipeEnabled, p > .5f, headerBottom, onSwipeStart, onSwipeDelta, onSwipeRelease, onSwipeCancel)
        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() },
            enabled = expansion == 0f, onClick = onExpand)) {
        val w = maxWidth; val h = maxHeight
        val margin = w * .058f * PlayerPaddingScale
        val coverY = maxOf(h * .142f, status + 44.dp * PlayerPaddingScale)
        // Reserve the actual fixed controls height before sizing the cover on short screens.
        val controlsHeight = 250.dp
        val seekCeiling = h - nav - controlsHeight - 10.dp
        val titleGap = 20.dp * PlayerPaddingScale
        val titleToSeek = 63.dp * PlayerPaddingScale
        val coverSize = minOf(w - margin * 2, (h - status - nav) * .46f,
            (seekCeiling - coverY - titleGap - titleToSeek).coerceAtLeast(1.dp))
        // Subtracting equivalent Float expressions can produce a tiny negative Dp.
        val coverInset = playerCoverInset(w, coverSize, margin)
        val coverX = margin + coverInset
        val titleY = coverY + coverSize + titleGap
        val small = minOf(64.dp, coverSize * .4f) * .86f
        val smallY = status + 48.dp * PlayerPaddingScale
        val smallX = 26.dp * PlayerPaddingScale
        val e = expansion.coerceIn(0f, 1f)
        val seekY = maxOf(titleY + titleToSeek, h * .637f).coerceAtMost(seekCeiling)
        val fullWidthPx = with(density) { w.toPx() }
        // Only controls and secondary content blur/fade; artwork and metadata stay sharp.
        if (alpha > 0f) Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }
            .blur(((1f - alpha) * 14f).dp)) {
        Box(Modifier.align(Alignment.TopCenter).offset(y = status + 12.dp * PlayerPaddingScale).size(104.dp, 32.dp)
            .clip(RoundedCornerShape(50))
            .semantics { contentDescription = tr("collapse_player", "收起播放器") }
            .playerSurfaceClick(onClose), contentAlignment = Alignment.Center) {
            Box(Modifier.width(80.dp).height(6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .36f)))
        }
        if (p > 0f && queueBlend < .999f) {
            Box(Modifier.offset(y = smallY + small + 20.dp * PlayerPaddingScale).fillMaxWidth()
            .height((seekY - smallY - small - 35.dp * PlayerPaddingScale).coerceAtLeast(1.dp))
            .graphicsLayer { this.alpha = p * (1f - queueBlend); translationY = (1f - p) * 24.dp.toPx() }
            .blur(((1f - p) * 12f + queueBlend * (1f - queueBlend) * 20f).dp)) {
                if (model.lyrics.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (model.lyricsLoading) tr("lyrics_loading", "正在获取歌词…") else tr("lyrics_empty", "暂无歌词"), color = Color.White.copy(alpha = .55f), fontSize = 20.sp)
                } else AndroidView(factory = { PlayerLyricView(it) }, update = {
                    it.submit(model.lyrics, model.showTranslation, model.showPronunciation, model.lyricSize.scale)
                    it.position(model.position, model.playing)
                    it.onSeek = model::seekTo
                }, modifier = Modifier.fillMaxSize())
        }
        }
        if (queueBlend > .001f && p > 0f) PlayerQueue(model, Modifier
            .offset(y = smallY + small + 20.dp * PlayerPaddingScale)
            .fillMaxWidth().height((seekY + 50.dp - smallY - small - 28.dp * PlayerPaddingScale).coerceAtLeast(1.dp))
            .graphicsLayer { this.alpha = p * queueBlend; translationY = (1f - p) * 24.dp.toPx() }
            .blur(((1f - p) * 12f + queueBlend * (1f - queueBlend) * 20f).dp).padding(horizontal = smallX))
        if (p > 0f) Box(Modifier.offset(w - 68.dp, smallY + 10.dp * PlayerPaddingScale).size(46.dp)
            .clip(RoundedCornerShape(50)).clickable { /* Reserved for future player actions. */ }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(46.dp * .85f * .92f).background(Color.White.copy(alpha = .12f), RoundedCornerShape(50))) {
                Image(MiuixIcons.Regular.More, tr("more", "更多"), Modifier.align(Alignment.Center).size(24.dp * PlayerIconScale * .92f), colorFilter = ColorFilter.tint(Color.White))
            }
        }
        Column(Modifier.offset(y = seekY).fillMaxWidth().height((h - seekY - nav - 10.dp).coerceAtLeast(controlsHeight)).padding(horizontal = margin)) {
            var scrub by remember(song.key) { mutableStateOf<Float?>(null) }
            val value = scrub ?: if (model.duration > 0) model.position.toFloat() / model.duration else 0f
            Column(Modifier.graphicsLayer { this.alpha = 1f - queueBlend }) {
            PillSlider(value, tr("playback_progress", "播放进度"), Modifier.padding(horizontal = coverInset.coerceAtLeast(0.dp)).fillMaxWidth().height(24.dp)
                .then(if (queueBlend > .5f) Modifier.clearAndSetSemantics {} else Modifier),
                onChange = { scrub = it }, onFinished = { model.seekTo(((scrub ?: value) * model.duration).toLong()); scrub = null }, enabled = model.duration > 0 && !queueOpen)
            Box(Modifier.padding(horizontal = coverInset.coerceAtLeast(0.dp)).fillMaxWidth().height(26.dp)) {
                Text(timeText(((scrub?.let { it * model.duration } ?: model.position.toFloat())).toLong()), color = Color.White.copy(alpha = .85f), fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterStart))
                Text(timeText(model.duration), color = Color.White.copy(alpha = .85f), fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterEnd))
                val actualQuality = model.streamInfo?.level?.let { level -> StreamQuality.entries.firstOrNull { it.level == level } }
                if (song.cloud) {
                    val qualityItems = buildList {
                        if (model.qualityLoading) add(DropdownItem(tr("loading", "加载中…"), enabled = false))
                        if (model.qualityError) add(DropdownItem(tr("quality_retry", "获取音质失败，点击重试"), onClick = model::refreshQualities))
                        model.availableQualities.forEach { item ->
                            add(DropdownItem(qualityName(item), selected = item == (actualQuality ?: model.quality), onClick = { model.selectQuality(item) }))
                        }
                        if (isEmpty()) add(DropdownItem(tr("quality_unavailable", "此歌曲暂无可用音质"), enabled = false))
                    }
                    GlassIconDropdownMenu(
                        entry = DropdownEntry(qualityItems), backdrop = backdrop, modifier = Modifier.align(Alignment.Center), enabled = !queueOpen,
                        trigger = { toggle, enabled ->
                            Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) {
                                model.refreshQualities(); toggle()
                            }.padding(horizontal = 7.dp, vertical = 4.dp)) {
                                Text(qualityName(actualQuality ?: model.quality), color = Color.White.copy(alpha = .85f), fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            }
                        }, content = {})
                } else Text(localAudioText(model), color = Color.White.copy(alpha = .85f), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 45.dp))
            }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().height(100.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                PlayerButton(R.drawable.ic_player_previous, tr("previous_track", "上一首"), Color.White, 48.dp, 76.dp, model::previous)
                PlayerButton(if (model.playbackRequested) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                    if (model.playbackRequested) tr("pause", "暂停") else tr("play", "播放"), Color.White, 60.dp * .88f, 96.dp, model::togglePlayback)
                PlayerButton(R.drawable.ic_player_next, tr("next_track", "下一首"), Color.White, 48.dp, 76.dp, model::next)
            }
            Spacer(Modifier.weight(1f))
            VolumeControl(Modifier.fillMaxWidth().height(44.dp))
            Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).semantics { selected = lyricsOpen }) {
                    PlayerButton(if (lyricsOpen) R.drawable.ic_player_lyrics_on else R.drawable.ic_player_lyrics, tr("lyrics", "歌词"), Color.White.copy(alpha = .9f), 32.dp, 48.dp, onLyrics)

                }
                val modeDescription = when (model.playbackMode) { 1 -> tr("shuffle_mode", "随机播放"); 2 -> tr("repeat_one_mode", "单曲循环"); else -> tr("repeat_all_mode", "列表循环") }
                PlayerButton(when (model.playbackMode) { 1 -> R.drawable.ic_player_shuffle; 2 -> R.drawable.ic_player_repeat_one; else -> R.drawable.ic_player_repeat }, modeDescription, Color.White.copy(alpha = .9f), 32.dp, 48.dp, model::cyclePlaybackMode)
                val revision = model.history.revision
                val favorite = remember(song.key, revision) { song.key in model.history.favorites() }
                PlayerButton(if (favorite) R.drawable.ic_player_favorite_on else R.drawable.ic_player_favorite,
                    if (favorite) tr("unfavorite", "取消收藏") else tr("favorite", "收藏"), Color.White.copy(alpha = .9f), 32.dp, 48.dp) { model.history.toggle(song) }
                Box(Modifier.semantics { selected = queueOpen }) {
                    PlayerButton(if (queueOpen) R.drawable.ic_player_queue_on else R.drawable.ic_player_queue, tr("play_queue", "播放队列"), Color.White.copy(alpha = .9f), 32.dp, 48.dp, onQueue)
                }
            }
        }
        }
        val fullCoverSize = coverSize + (small - coverSize) * p
        val sharedSize = 44.dp + (fullCoverSize - 44.dp) * e
        val sharedX = 12.dp + (coverX + (smallX - coverX) * p - 12.dp) * e
        val sharedY = 8.dp + (coverY + (smallY - coverY) * p - 8.dp) * e
        val sharedScale = sharedSize / coverSize
        val sharedRadius = 13.dp + ((24.dp + (9.dp - 24.dp) * p) - 13.dp) * e
        val sharedClick = if (e < .5f) onExpand else if (queueOpen) onQueue else onLyrics
        val miniShift = miniSwipe / density.density
        val miniAlpha = (1f - abs(miniSwipe) / fullWidthPx.coerceAtLeast(1f) * .35f).coerceIn(.55f, 1f)
        Cover(song.artwork, Modifier.offset(sharedX, sharedY).size(coverSize).graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0f); scaleX = sharedScale; scaleY = sharedScale
            translationX = miniShift
            this.alpha = miniAlpha
        }, sharedRadius / sharedScale, onClick = sharedClick)
        val compactTextX = smallX + small + 12.dp * PlayerPaddingScale
        val fullTextX = coverX + (compactTextX - coverX) * p
        val fullTextY = titleY + (smallY + 10.dp * PlayerPaddingScale - titleY) * p
        val textScale = .7f + ((1f - .22f * p) - .7f) * e
        val miniTextWidth = (w - PlayerNavigationGeometry.outerInset * 2 - 66.dp - 12.dp - 124.dp - 3.dp).coerceAtLeast(24.dp)
        val fullTextWidth = coverSize + ((w - compactTextX - 68.dp).coerceAtLeast(24.dp) - coverSize) * p
        val textWidth = (miniTextWidth + (fullTextWidth - miniTextWidth) * e) / textScale
        val textColor = lerp(miniColor, Color.White, e)
        Column(Modifier.offset(66.dp + (fullTextX - 66.dp) * e, 14.dp + (fullTextY - 14.dp) * e)
            .width(textWidth).graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f); scaleX = textScale; scaleY = textScale
                translationX = miniShift
                this.alpha = miniAlpha
            }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = sharedClick)) {
            Text(song.title, color = textColor, fontSize = 20.sp, lineHeight = 25.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = textColor.copy(alpha = .8f), fontSize = 16.sp, lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        if (alpha > 0f && p > 0f && queueBlend < .01f) {
            LyricOptionsMenu(model, backdrop, menuTint, coverX, seekY - 52.dp,
                w, status + 8.dp,
                Modifier.graphicsLayer { this.alpha = alpha * p }
                    .blur(((1f - p) * 14f).dp), onLyricMenuExpanded)
        }

    }
}

@Composable
private fun Cover(uri: String, modifier: Modifier, radius: Dp, onClick: (() -> Unit)? = null) {
    Box(modifier.clip(RoundedCornerShape(radius))
        .then(if (onClick == null) Modifier else Modifier.playerSurfaceClick(onClick))
        .background(Color.White.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_player_queue), null, Modifier.fillMaxSize(.3f * PlayerIconScale), colorFilter = ColorFilter.tint(Color.White.copy(alpha = .3f)))
        if (uri.isNotBlank()) AsyncImage(uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (draggingIndex == index) Color.White.copy(alpha = .12f) else Color.Transparent)
                    .clickable { model.queueTo(index) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(song.artwork, Modifier.size(44.dp), 8.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(song.title, color = Color.White, fontSize = 15.sp,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = Color.White.copy(alpha = .66f), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(40.dp, 44.dp).pointerInput(rowStep) {
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
                }.semantics {
                    contentDescription = tr("reorder_queue", "调整播放顺序")
                    customActions = listOf(
                        CustomAccessibilityAction(tr("move_up", "上移")) { if (index > 0) { model.moveQueue(index, index - 1); true } else false },
                        CustomAccessibilityAction(tr("move_down", "下移")) { if (index < model.queue.lastIndex) { model.moveQueue(index, index + 1); true } else false },
                    )
                }, contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_player_reorder), null,
                        Modifier.size(22.dp * PlayerIconScale), colorFilter = ColorFilter.tint(Color.White.copy(alpha = .42f)))
                }
            }
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

