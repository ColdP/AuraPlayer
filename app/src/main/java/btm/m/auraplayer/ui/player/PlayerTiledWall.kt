// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.R
import btm.m.auraplayer.auramix.AuraMixVisualTransition
import btm.m.auraplayer.data.Song
import btm.m.auraplayer.lyrics.scaleSpatialParallaxTilt
import btm.m.auraplayer.playback.CoverAnimation
import btm.m.auraplayer.playback.NowPlayingConfig
import btm.m.auraplayer.spatialcover.SpatialCoverResolveState
import btm.m.auraplayer.ui.LocalAdvancedMaterialEnabled
import btm.m.auraplayer.ui.tr
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.materialkolor.palettes.CorePalette
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LocalRuntimeShaderCache
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.highlight.Highlight as MiuixHighlight
import top.yukonga.miuix.kmp.blur.layerBackdrop as miuixLayerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop as rememberMiuixLayerBackdrop
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal const val TILED_SWITCH_DWELL_MS = 2000

internal data class TiledGridCell(
    val col: Int,
    val row: Int,
)

internal data class TiledWallMetrics(
    val isLandscape: Boolean,
    val tileSize: Dp,
    val gap: Dp,
    val cornerRadius: Dp,
    val dockHorizontalMargin: Dp,
    val dockBottomPadding: Dp,
    val dockCornerRadius: Dp,
    val dockCompact: Boolean,
    val includeVolumeInDock: Boolean,
    val focusYFraction: Float,
    val gridCols: Int,
)

internal fun computeTiledWallMetrics(
    width: Dp,
    height: Dp,
    leftInset: Dp,
    topInset: Dp,
    rightInset: Dp,
    bottomInset: Dp,
    trackCount: Int,
    showVolume: Boolean = false,
): TiledWallMetrics {
    val landscape = isLandscapePlayerLayout(width, height)
    val availableWidth = (width - leftInset - rightInset).coerceAtLeast(300.dp)
    val availableHeight = (height - topInset - bottomInset).coerceAtLeast(240.dp)

    val tileSize: Dp
    val gap: Dp
    val cornerRadius: Dp
    val dockHorizontalMargin: Dp
    val dockBottomPadding: Dp
    val dockCornerRadius: Dp
    val dockCompact: Boolean
    val includeVolumeInDock: Boolean
    val focusYFraction: Float

    if (!landscape) {
        dockCompact = availableHeight < 700.dp
        includeVolumeInDock = showVolume && availableHeight >= 580.dp
        tileSize = ((width - 28.dp) / 3.12f).coerceIn(106.dp, 158.dp)
        gap = 10.dp
        cornerRadius = (tileSize * 0.145f).coerceIn(16.dp, 24.dp)
        dockHorizontalMargin = maxOf(16.dp, (availableWidth - 540.dp) / 2f)
        dockBottomPadding = if (availableHeight < 660.dp) 12.dp else 18.dp
        dockCornerRadius = 28.dp
        focusYFraction = 0.34f
    } else {
        dockCompact = availableHeight < 430.dp
        includeVolumeInDock = false
        tileSize = ((height - 30.dp) / 2.65f).coerceIn(104.dp, 156.dp)
        gap = if (dockCompact) 9.dp else 11.dp
        cornerRadius = (tileSize * 0.145f).coerceIn(16.dp, 24.dp)
        dockHorizontalMargin = maxOf(16.dp, (availableWidth - 860.dp) / 2f)
        dockBottomPadding = if (dockCompact) 10.dp else 16.dp
        dockCornerRadius = if (dockCompact) 26.dp else 30.dp
        focusYFraction = 0.40f
    }

    val safeTracks = trackCount.coerceAtLeast(1)
    val aspect = (width.value / height.value.coerceAtLeast(1f)).coerceIn(0.45f, 2.2f)
    val approxCols = ceil(sqrt(safeTracks.toDouble()) * aspect).toInt()
    val gridCols = approxCols.coerceIn(if (landscape) 4 else 3, 12)

    return TiledWallMetrics(
        isLandscape = landscape,
        tileSize = tileSize,
        gap = gap,
        cornerRadius = cornerRadius,
        dockHorizontalMargin = dockHorizontalMargin,
        dockBottomPadding = dockBottomPadding,
        dockCornerRadius = dockCornerRadius,
        dockCompact = dockCompact,
        includeVolumeInDock = includeVolumeInDock,
        focusYFraction = focusYFraction,
        gridCols = gridCols,
    )
}

private fun gcd(a: Int, b: Int): Int {
    var x = abs(a)
    var y = abs(b)
    while (y != 0) {
        val t = x % y
        x = y
        y = t
    }
    return x.coerceAtLeast(1)
}

internal fun tiledRowStride(gridCols: Int, trackCount: Int): Int {
    if (trackCount <= 1) return 1
    val base = gridCols.coerceAtLeast(3)
    for (candidate in base..(base + trackCount + 2)) {
        if (gcd(candidate, trackCount) == 1) return candidate
    }
    return base + 1
}

internal fun tiledTrackIndexForCell(
    col: Int,
    row: Int,
    baseTrackOffset: Int,
    rowStride: Int,
    trackCount: Int,
): Int {
    if (trackCount <= 1) return 0
    val raw = baseTrackOffset + row * rowStride + col
    return ((raw % trackCount) + trackCount) % trackCount
}

internal fun findNearestCellForTrack(
    currentCol: Int,
    currentRow: Int,
    targetTrackIndex: Int,
    baseTrackOffset: Int,
    rowStride: Int,
    trackCount: Int,
    searchRadius: Int = 6,
): TiledGridCell {
    if (trackCount <= 1) return TiledGridCell(currentCol, currentRow)
    var best = TiledGridCell(currentCol, currentRow)
    var bestScore = Int.MAX_VALUE
    for (dr in -searchRadius..searchRadius) {
        for (dc in -searchRadius..searchRadius) {
            val c = currentCol + dc
            val r = currentRow + dr
            if (tiledTrackIndexForCell(c, r, baseTrackOffset, rowStride, trackCount) == targetTrackIndex) {
                val distSq = dc * dc + dr * dr
                if (distSq < bestScore) {
                    bestScore = distSq
                    best = TiledGridCell(c, r)
                }
            }
        }
    }
    return best
}

internal fun resolveCenteredTiledCandidate(
    panCol: Float,
    panRow: Float,
    captureRadiusTiles: Float = 0.36f,
): TiledGridCell? {
    val nearestCol = panCol.roundToInt()
    val nearestRow = panRow.roundToInt()
    val dx = abs(panCol - nearestCol)
    val dy = abs(panRow - nearestRow)
    return if (dx <= captureRadiusTiles && dy <= captureRadiusTiles) {
        TiledGridCell(nearestCol, nearestRow)
    } else {
        null
    }
}

internal data class TiledCellVisualEffect(
    val scale: Float,
    val blurDp: Float,
    val dimAlpha: Float,
    val contentAlpha: Float,
)

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun computeTiledCellVisualEffect(
    distNorm: Float,
    isHoveredCandidate: Boolean,
    isArmedCandidate: Boolean,
    isPlayingAnchored: Boolean,
    dwellProgress: Float,
    anchoredBoost: Float = if (isPlayingAnchored) 1f else 0f,
): TiledCellVisualEffect {
    val falloff = smoothstep(0.62f, 1.58f, distNorm)
    val baseScale = 1f - 0.10f * falloff
    val focusLift = when {
        isArmedCandidate -> 0.22f
        isHoveredCandidate -> 0.15f * dwellProgress.coerceIn(0f, 1f) + 0.05f
        else -> 0.26f * anchoredBoost.coerceIn(0f, 1f) * (1f - smoothstep(0f, 0.35f, distNorm))
    }
    val rawBlur = if (isHoveredCandidate || isArmedCandidate || isPlayingAnchored) 0f else falloff * 5.0f
    val blurDp = if (rawBlur < 0.35f) 0f else rawBlur
    val dimAlpha = if (isHoveredCandidate || isArmedCandidate || isPlayingAnchored) {
        falloff * 0.14f
    } else {
        falloff * 0.30f
    }
    val contentAlpha = (1f - falloff * 0.18f).coerceIn(0.76f, 1f)
    return TiledCellVisualEffect(
        scale = (baseScale + focusLift).coerceIn(0.84f, 1.32f),
        blurDp = blurDp,
        dimAlpha = dimAlpha.coerceIn(0f, 0.36f),
        contentAlpha = contentAlpha,
    )
}

internal data class TiledRepulsionOffset(
    val offsetXFactor: Float,
    val offsetYFactor: Float,
)

internal fun computeTiledRepulsionOffset(
    col: Int,
    row: Int,
    heroCol: Int,
    heroRow: Int,
    repulsionStrength: Float,
): TiledRepulsionOffset {
    val strength = repulsionStrength.coerceIn(0f, 1.5f)
    if (strength <= 0.0001f) return TiledRepulsionOffset(0f, 0f)
    val dc = (col - heroCol).toFloat()
    val dr = (row - heroRow).toFloat()
    val dist = hypot(dc, dr)
    if (dist <= 0.001f) return TiledRepulsionOffset(0f, 0f)
    val maxPush = 0.45f * strength
    val ripple = maxPush / dist.pow(0.22f)
    return TiledRepulsionOffset(
        offsetXFactor = (dc / dist) * ripple,
        offsetYFactor = (dr / dist) * ripple,
    )
}

@Composable
internal fun PlayerTiledStage(
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
    val parallaxTilt = if (appearance.tiledParallax) {
        scaleSpatialParallaxTilt(spatialTilt, appearance.tiledParallaxSensitivity)
    } else {
        Offset.Zero
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
    val heroContentAlpha = params.heroContentAlpha
    val onLyrics = params.onLyrics
    val onQueue = params.onQueue
    val onQualityMenuExpanded = params.onQualityMenuExpanded
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val advancedMaterial = LocalAdvancedMaterialEnabled.current

    val tracks = remember(model.queue, song.key) {
        model.queue.ifEmpty { listOf(song) }
    }
    val targetIndex = remember(tracks, model.queueIndex, song.key) {
        val idx = model.queueIndex
        when {
            idx in tracks.indices && tracks[idx].key == song.key -> idx
            else -> tracks.indexOfFirst { it.key == song.key }.takeIf { it >= 0 }
                ?: idx.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        }
    }

    val metrics = remember(
        maxWidth,
        maxHeight,
        leftInset,
        topInset,
        rightInset,
        bottomInset,
        tracks.size,
        appearance.showVolume,
    ) {
        computeTiledWallMetrics(
            width = maxWidth,
            height = maxHeight,
            leftInset = leftInset,
            topInset = topInset,
            rightInset = rightInset,
            bottomInset = bottomInset,
            trackCount = tracks.size,
            showVolume = appearance.showVolume,
        )
    }

    val rowStride = remember(metrics.gridCols, tracks.size) {
        tiledRowStride(metrics.gridCols, tracks.size)
    }
    var baseTrackOffset by remember(tracks.size) { mutableIntStateOf(targetIndex) }
    var anchoredCell by remember(tracks.size) { mutableStateOf(TiledGridCell(0, 0)) }

    val panCol = remember { Animatable(0f) }
    val panRow = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val dwellProgress = remember { Animatable(0f) }
    var armedCell by remember { mutableStateOf<TiledGridCell?>(null) }

    val tiltXAnim = remember { Animatable(0f) }
    val tiltYAnim = remember { Animatable(0f) }

    // Keep external track changes (Next / Prev / Queue click) smoothly glided by spring.
    LaunchedEffect(targetIndex, tracks.size) {
        if (isDragging) return@LaunchedEffect
        val curTrackAtAnchor = tiledTrackIndexForCell(
            col = anchoredCell.col,
            row = anchoredCell.row,
            baseTrackOffset = baseTrackOffset,
            rowStride = rowStride,
            trackCount = tracks.size,
        )
        if (curTrackAtAnchor != targetIndex) {
            val nearest = findNearestCellForTrack(
                currentCol = anchoredCell.col,
                currentRow = anchoredCell.row,
                targetTrackIndex = targetIndex,
                baseTrackOffset = baseTrackOffset,
                rowStride = rowStride,
                trackCount = tracks.size,
            )
            anchoredCell = nearest
            val spec = spring<Float>(dampingRatio = 0.80f, stiffness = 240f)
            launch { panCol.animateTo(nearest.col.toFloat(), spec) }
            launch { panRow.animateTo(nearest.row.toFloat(), spec) }
        }
    }

    val centeredCandidate by remember {
        derivedStateOf {
            if (!isDragging || tracks.size <= 1) null
            else resolveCenteredTiledCandidate(panCol.value, panRow.value)
        }
    }
    val eligibleCandidate by remember(targetIndex, baseTrackOffset, rowStride, tracks.size) {
        derivedStateOf {
            val cand = centeredCandidate ?: return@derivedStateOf null
            val candTrack = tiledTrackIndexForCell(
                col = cand.col,
                row = cand.row,
                baseTrackOffset = baseTrackOffset,
                rowStride = rowStride,
                trackCount = tracks.size,
            )
            if (candTrack != targetIndex) cand else null
        }
    }

    val dwellSeconds = appearance.tiledSwitchDwellSeconds.coerceIn(0.1f, 5.0f)
    val dwellDurationMs = (dwellSeconds * 1000f).roundToInt().coerceIn(100, 5000)

    LaunchedEffect(isDragging) {
        params.onDragActiveChange(isDragging)
    }
    DisposableEffect(Unit) {
        onDispose { params.onDragActiveChange(false) }
    }

    LaunchedEffect(isDragging, eligibleCandidate, dwellDurationMs) {
        if (!isDragging || eligibleCandidate == null) {
            dwellProgress.snapTo(0f)
            armedCell = null
            return@LaunchedEffect
        }
        armedCell = null
        dwellProgress.snapTo(0f)
        dwellProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = dwellDurationMs, easing = LinearEasing),
        )
        armedCell = eligibleCandidate
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val glowColor = remember(coverPalette, expressive, oneUi) {
        oneUi?.accent ?: expressive?.primary ?: vibrantCoverGlowColor(coverPalette)
    }
    val anchoredBoostAnim = remember { Animatable(1f) }
    val candBoostAnim = remember { Animatable(0f) }
    LaunchedEffect(isDragging) {
        if (isDragging) {
            anchoredBoostAnim.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 260f))
        } else {
            anchoredBoostAnim.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 225f))
        }
    }
    var lastCandidateCell by remember { mutableStateOf<TiledGridCell?>(null) }
    LaunchedEffect(isDragging, eligibleCandidate) {
        if (isDragging && eligibleCandidate != null) {
            lastCandidateCell = eligibleCandidate
        }
    }
    LaunchedEffect(isDragging, eligibleCandidate, armedCell, dwellDurationMs) {
        if (!isDragging || eligibleCandidate == null) {
            candBoostAnim.animateTo(0f, spring(dampingRatio = 0.76f, stiffness = 270f))
        } else if (armedCell != null) {
            candBoostAnim.animateTo(0.88f, spring(dampingRatio = 0.76f, stiffness = 270f))
        } else {
            candBoostAnim.animateTo(0.18f, spring(dampingRatio = 0.78f, stiffness = 320f))
            candBoostAnim.animateTo(0.84f, tween(durationMillis = dwellDurationMs, easing = LinearEasing))
        }
    }
    val widthPx = with(density) { maxWidth.toPx() }
    val heightPx = with(density) { maxHeight.toPx() }
    val tileSizePx = with(density) { metrics.tileSize.toPx() }
    val tileDecodePx = remember(tileSizePx) { tileSizePx.roundToInt().coerceIn(128, 280) }
    val gapPx = with(density) { metrics.gap.toPx() }
    val pitchPx = (tileSizePx + gapPx).coerceAtLeast(1f)
    val focusX = widthPx * 0.5f
    val focusY = heightPx * metrics.focusYFraction
    val focusRadiusPx = (minOf(widthPx, heightPx) * 0.62f).coerceAtLeast(1f)
    val tileShape = remember(metrics.cornerRadius) { RoundedCornerShape(metrics.cornerRadius) }
    val baseTileOffset = remember(focusX, focusY, tileSizePx) {
        IntOffset(
            (focusX - tileSizePx / 2f).roundToInt(),
            (focusY - tileSizePx / 2f).roundToInt(),
        )
    }

    // Quantized integer grid anchor & zero-churn fixed modulo viewport pool.
    val gridCenterCol by remember { derivedStateOf { panCol.value.roundToInt() } }
    val gridCenterRow by remember { derivedStateOf { panRow.value.roundToInt() } }
    val minRelCol = remember(focusX, tileSizePx, pitchPx) {
        -ceil((focusX + tileSizePx * 0.75f) / pitchPx).toInt()
    }
    val maxRelCol = remember(widthPx, focusX, tileSizePx, pitchPx) {
        ceil((widthPx - focusX + tileSizePx * 0.75f) / pitchPx).toInt()
    }
    val minRelRow = remember(focusY, tileSizePx, pitchPx) {
        -ceil((focusY + tileSizePx * 0.75f) / pitchPx).toInt()
    }
    val maxRelRow = remember(heightPx, focusY, tileSizePx, pitchPx) {
        ceil((heightPx - focusY + tileSizePx * 0.75f) / pitchPx).toInt()
    }
    val poolCols = (maxRelCol - minRelCol + 1).coerceAtLeast(1)
    val poolRows = (maxRelRow - minRelRow + 1).coerceAtLeast(1)

    // Keep latest state readable inside gesture callbacks without restarting pointerInput mid-drag.
    val latestTracks by rememberUpdatedState(tracks)
    val latestTargetIdx by rememberUpdatedState(targetIndex)
    val latestBaseOffset by rememberUpdatedState(baseTrackOffset)
    val latestRowStride by rememberUpdatedState(rowStride)
    val latestAnchored by rememberUpdatedState(anchoredCell)
    val latestArmed by rememberUpdatedState(armedCell)
    val latestInteractive by rememberUpdatedState(interactive)
    val latestOnLyrics by rememberUpdatedState(onLyrics)

    val dockChromeAlphaAnim = remember { Animatable(1f) }
    LaunchedEffect(isDragging) {
        dockChromeAlphaAnim.animateTo(
            targetValue = if (isDragging) 0f else 1f,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 360f),
        )
    }
    val dockChromeVisible by remember { derivedStateOf { !isDragging || dockChromeAlphaAnim.value > 0.01f } }
    // Capture MIUIX progressive blur only once panning + spring settle finish so scroll stays 100% fluid.
    val wallSettled by remember { derivedStateOf { !isDragging && !panCol.isRunning && !panRow.isRunning } }
    val progressiveBlurAlphaAnim = remember { Animatable(1f) }
    LaunchedEffect(wallSettled) {
        if (!wallSettled) {
            progressiveBlurAlphaAnim.snapTo(0f)
        } else {
            progressiveBlurAlphaAnim.animateTo(1f, tween(200))
        }
    }
    val captureBackdrop = advancedMaterial && dockChromeVisible && wallSettled
    val scrimTone = oneUi?.background ?: expressive?.surface ?: Color(coverPalette.a2.tone(22))
    val coverMixAccent = expressive?.primaryContainer ?: oneUi?.card ?: Color(coverPalette.a1.tone(32))
    val coverMixTint = remember(scrimTone, coverMixAccent) { lerp(scrimTone, coverMixAccent, 0.42f) }
    val wallBackdropBase = remember(coverMixTint) { coverMixTint.copy(alpha = 0.92f) }
    val wallBackdrop = rememberMiuixLayerBackdrop {
        drawRect(wallBackdropBase)
        drawContent()
    }
    val focusShadowAmbient = remember { Color.Black.copy(alpha = 0.24f) }
    val focusShadowSpot = remember { Color.Black.copy(alpha = 0.34f) }
    val shaderCache = LocalRuntimeShaderCache.current
    val highlightPaint = remember { Paint() }
    val useMiuixHighlight = advancedMaterial && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val miuixHeroHighlight = remember { MiuixHighlight.Default.copy(width = 0.55.dp, alpha = 0.88f) }

    Box(modifier = modifier.fillMaxSize()) {
        // 2D Interactive Edge-to-Edge Album Mosaic Wall (captured for MIUIX progressive blur behind controls)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (captureBackdrop) Modifier.miuixLayerBackdrop(wallBackdrop) else Modifier)
                .pointerInput(pitchPx, focusX, focusY, tileSizePx) {
                    detectTapGestures { tapOffset ->
                        if (!latestInteractive || isDragging) return@detectTapGestures
                        val half = tileSizePx * 0.92f
                        if (abs(tapOffset.x - focusX) <= half && abs(tapOffset.y - focusY) <= half) {
                            latestOnLyrics()
                        }
                    }
                }
                .pointerInput(pitchPx) {
                    detectDragGestures(
                        onDragStart = {
                            if (!latestInteractive) return@detectDragGestures
                            isDragging = true
                            scope.launch {
                                panCol.stop()
                                panRow.stop()
                                tiltXAnim.stop()
                                tiltYAnim.stop()
                            }
                        },
                        onDrag = { change, dragAmount ->
                            if (!latestInteractive) return@detectDragGestures
                            change.consume()
                            val nextCol = panCol.value - dragAmount.x / pitchPx
                            val nextRow = panRow.value - dragAmount.y / pitchPx
                            val targetTx = ((-dragAmount.y / pitchPx) * 40f).coerceIn(-7.5f, 7.5f)
                            val targetTy = ((dragAmount.x / pitchPx) * 40f).coerceIn(-7.5f, 7.5f)
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                panCol.snapTo(nextCol)
                                panRow.snapTo(nextRow)
                                tiltXAnim.snapTo(tiltXAnim.value + (targetTx - tiltXAnim.value) * 0.28f)
                                tiltYAnim.snapTo(tiltYAnim.value + (targetTy - tiltYAnim.value) * 0.28f)
                            }
                        },
                        onDragEnd = {
                            val locked = latestArmed
                            val centeredNow = resolveCenteredTiledCandidate(panCol.value, panRow.value)
                            isDragging = false
                            val tiltSpec = spring<Float>(dampingRatio = 0.68f, stiffness = 260f)
                            scope.launch { tiltXAnim.animateTo(0f, tiltSpec) }
                            scope.launch { tiltYAnim.animateTo(0f, tiltSpec) }
                            if (locked != null && locked == centeredNow && latestTracks.isNotEmpty()) {
                                val chosenTrack = tiledTrackIndexForCell(
                                    col = locked.col,
                                    row = locked.row,
                                    baseTrackOffset = latestBaseOffset,
                                    rowStride = latestRowStride,
                                    trackCount = latestTracks.size,
                                )
                                anchoredCell = locked
                                armedCell = null
                                val snapBoost = candBoostAnim.value.coerceAtLeast(0.72f)
                                val lockSpec = spring<Float>(dampingRatio = 0.78f, stiffness = 310f)
                                scope.launch {
                                    anchoredBoostAnim.snapTo(snapBoost)
                                    anchoredBoostAnim.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 225f))
                                }
                                scope.launch { panCol.animateTo(locked.col.toFloat(), lockSpec) }
                                scope.launch { panRow.animateTo(locked.row.toFloat(), lockSpec) }
                                if (chosenTrack != latestTargetIdx) {
                                    model.queueTo(chosenTrack)
                                }
                            } else {
                                val backTo = latestAnchored
                                val returnSpec = spring<Float>(dampingRatio = 0.76f, stiffness = 215f)
                                scope.launch { panCol.animateTo(backTo.col.toFloat(), returnSpec) }
                                scope.launch { panRow.animateTo(backTo.row.toFloat(), returnSpec) }
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            val tiltSpec = spring<Float>(dampingRatio = 0.68f, stiffness = 260f)
                            scope.launch { tiltXAnim.animateTo(0f, tiltSpec) }
                            scope.launch { tiltYAnim.animateTo(0f, tiltSpec) }
                            val backTo = latestAnchored
                            val returnSpec = spring<Float>(dampingRatio = 0.76f, stiffness = 215f)
                            scope.launch { panCol.animateTo(backTo.col.toFloat(), returnSpec) }
                            scope.launch { panRow.animateTo(backTo.row.toFloat(), returnSpec) }
                        },
                    )
                },
        ) {
            // Subtle center optical halo behind focal point (tracks gyro parallax when enabled)
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (focusX - tileSizePx * 1.1f).roundToInt(),
                            (focusY - tileSizePx * 1.1f).roundToInt(),
                        )
                    }
                    .size(metrics.tileSize * 2.2f)
                    .graphicsLayer {
                        if (appearance.tiledParallax) {
                            translationX = -parallaxTilt.x * 30.dp.toPx()
                            translationY = -parallaxTilt.y * 20.dp.toPx()
                        }
                    }
                    .background(
                        Brush.radialGradient(
                            listOf(
                                glowColor.copy(alpha = 0.24f),
                                glowColor.copy(alpha = 0.08f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            val minCol = gridCenterCol + minRelCol
            val minRow = gridCenterRow + minRelRow
            val minColMod = ((minCol % poolCols) + poolCols) % poolCols
            val minRowMod = ((minRow % poolRows) + poolRows) % poolRows
            val anchoredTrack = tracks[
                tiledTrackIndexForCell(
                    col = anchoredCell.col,
                    row = anchoredCell.row,
                    baseTrackOffset = baseTrackOffset,
                    rowStride = rowStride,
                    trackCount = tracks.size,
                ),
            ]
            val heroOverlayVisible = !isDragging && anchoredTrack.key == song.key

            for (slotR in 0 until poolRows) {
                val row = minRow + ((slotR - minRowMod + poolRows) % poolRows)
                for (slotC in 0 until poolCols) {
                    val col = minCol + ((slotC - minColMod + poolCols) % poolCols)
                    val cell = TiledGridCell(col, row)
                    val trackIdx = tiledTrackIndexForCell(
                        col = col,
                        row = row,
                        baseTrackOffset = baseTrackOffset,
                        rowStride = rowStride,
                        trackCount = tracks.size,
                    )
                    val track = tracks[trackIdx]

                    val isPlayingAnchored = !isDragging && cell == anchoredCell
                    val isHoveredCandidate = isDragging && cell == eligibleCandidate
                    val isArmedCandidate = isDragging && cell == armedCell
                    val isFocusedTile = isPlayingAnchored || isHoveredCandidate || isArmedCandidate
                    val cardZ = when {
                        isArmedCandidate || isHoveredCandidate -> 24f
                        isPlayingAnchored -> 18f
                        else -> 1f
                    }

                    key(slotC * 64 + slotR) {
                        val thumbRequest = remember(track.artwork, tileDecodePx, context) {
                            if (track.artwork.isBlank()) null
                            else ImageRequest.Builder(context)
                                .data(track.artwork)
                                .size(tileDecodePx)
                                .precision(Precision.INEXACT)
                                .build()
                        }
                        val drawPooledHighlight = isHoveredCandidate || isArmedCandidate || (isPlayingAnchored && !heroOverlayVisible)

                        Box(
                            modifier = Modifier
                                .offset { baseTileOffset }
                                .size(metrics.tileSize)
                                .zIndex(cardZ)
                                .graphicsLayer {
                                    val aBoost = anchoredBoostAnim.value
                                    val cBoost = candBoostAnim.value
                                    val useCand = cBoost > aBoost && lastCandidateCell != null
                                    val heroCol = if (useCand) lastCandidateCell!!.col else anchoredCell.col
                                    val heroRow = if (useCand) lastCandidateCell!!.row else anchoredCell.row
                                    val heroStrength = if (useCand) cBoost else aBoost

                                    val repX: Float
                                    val repY: Float
                                    if (heroStrength > 0.0001f && (col != heroCol || row != heroRow)) {
                                        val dc = (col - heroCol).toFloat()
                                        val dr = (row - heroRow).toFloat()
                                        val dHero = hypot(dc, dr)
                                        val maxPush = 0.45f * heroStrength.coerceIn(0f, 1.5f)
                                        val ripple = maxPush / dHero.pow(0.22f)
                                        repX = (dc / dHero) * ripple
                                        repY = (dr / dHero) * ripple
                                    } else {
                                        repX = 0f
                                        repY = 0f
                                    }

                                    val dxPx = (col - panCol.value + repX) * pitchPx
                                    val dyPx = (row - panRow.value + repY) * pitchPx
                                    val distNorm = (hypot(dxPx, dyPx) / focusRadiusPx).coerceIn(0f, 1.8f)
                                    val tFalloff = ((distNorm - 0.62f) / 0.96f).coerceIn(0f, 1f)
                                    val falloff = tFalloff * tFalloff * (3f - 2f * tFalloff)

                                    val baseScale = 1f - 0.08f * falloff
                                    val focusLift = when {
                                        isArmedCandidate -> 0.74f
                                        isHoveredCandidate -> 0.20f + 0.48f * dwellProgress.value.coerceIn(0f, 1f)
                                        else -> {
                                            val tDist = (distNorm / 0.35f).coerceIn(0f, 1f)
                                            val nearCenter = 1f - tDist * tDist * (3f - 2f * tDist)
                                            0.825f * aBoost.coerceIn(0f, 1f) * nearCenter
                                        }
                                    }
                                    val tileScale = (baseScale + focusLift).coerceIn(0.84f, 1.88f)

                                    if (appearance.tiledParallax) {
                                        if (isFocusedTile) {
                                            cameraDistance = 7.0f * density.density
                                            translationX = dxPx + parallaxTilt.x * 24.dp.toPx()
                                            translationY = dyPx + parallaxTilt.y * 18.dp.toPx()
                                            rotationX = tiltXAnim.value + parallaxTilt.y * 19f
                                            rotationY = tiltYAnim.value - parallaxTilt.x * 22f
                                        } else {
                                            cameraDistance = 8.5f * density.density
                                            translationX = dxPx - parallaxTilt.x * (14.dp + 6.dp * falloff).toPx()
                                            translationY = dyPx - parallaxTilt.y * (12.dp + 5.dp * falloff).toPx()
                                            rotationX = tiltXAnim.value + parallaxTilt.y * 11f
                                            rotationY = tiltYAnim.value - parallaxTilt.x * 13f
                                        }
                                    } else {
                                        cameraDistance = 16f * density.density
                                        translationX = dxPx
                                        translationY = dyPx
                                        rotationX = tiltXAnim.value
                                        rotationY = tiltYAnim.value
                                    }
                                    scaleX = tileScale
                                    scaleY = tileScale
                                    val edgeAlpha = (1f - falloff * 0.08f).coerceIn(0.90f, 1f)
                                    alpha = edgeAlpha * (if (isPlayingAnchored) heroContentAlpha else 1f)
                                    if (isFocusedTile) {
                                        shadowElevation = if (isArmedCandidate || isHoveredCandidate) 18.dp.toPx() else 12.dp.toPx()
                                        ambientShadowColor = focusShadowAmbient
                                        spotShadowColor = focusShadowSpot
                                    } else {
                                        shadowElevation = 0f
                                    }
                                    shape = tileShape
                                    clip = true
                                }
                                .clip(tileShape)
                                .drawWithContent {
                                    drawContent()
                                    if (!isFocusedTile) {
                                        val halfW = (widthPx * 0.5f).coerceAtLeast(1f)
                                        val halfH = (heightPx * 0.5f).coerceAtLeast(1f)
                                        val tileCenterX = focusX + (col - panCol.value) * pitchPx
                                        val tileCenterY = focusY + (row - panRow.value) * pitchPx
                                        val edgeX = abs(tileCenterX - halfW) / halfW
                                        val edgeY = abs(tileCenterY - halfH) / halfH
                                        val radialEdge = hypot(edgeX * 0.88f, edgeY * 0.92f).coerceIn(0f, 1.4f)
                                        val tEdge = ((radialEdge - 0.34f) / 0.82f).coerceIn(0f, 1f)
                                        val smoothEdge = tEdge * tEdge * (3f - 2f * tEdge)
                                        val darkenAlpha = (smoothEdge * 0.44f).coerceIn(0f, 0.44f)
                                        if (darkenAlpha > 0.005f) {
                                            drawRect(color = Color.Black, alpha = darkenAlpha)
                                        }
                                    } else if (drawPooledHighlight && useMiuixHighlight) {
                                        drawMiuixHighlight(miuixHeroHighlight, tileShape, shaderCache, highlightPaint)
                                    }
                                }
                                .then(
                                    if (!isFocusedTile || !useMiuixHighlight) {
                                        Modifier.border(
                                            width = 0.6.dp,
                                            color = if (isArmedCandidate) glowColor.copy(alpha = 0.55f) else Color.White.copy(alpha = if (isFocusedTile) 0.28f else 0.12f),
                                            shape = tileShape,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (thumbRequest != null) {
                                AsyncImage(
                                    model = thumbRequest,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.White.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.ic_player_queue),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(0.3f * PlayerIconScale),
                                        colorFilter = ColorFilter.tint(activeInk.copy(alpha = 0.3f)),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Single persistent live Cover hero overlay positioned on anchoredCell when idle
            key(-99999) {
                Box(
                    modifier = Modifier
                        .offset { baseTileOffset }
                        .size(metrics.tileSize)
                        .zIndex(if (heroOverlayVisible) 20f else -1f)
                        .graphicsLayer {
                            if (!heroOverlayVisible) {
                                alpha = 0f
                                return@graphicsLayer
                            }
                            val aBoost = anchoredBoostAnim.value
                            val dxPx = (anchoredCell.col - panCol.value) * pitchPx
                            val dyPx = (anchoredCell.row - panRow.value) * pitchPx
                            val distNorm = (hypot(dxPx, dyPx) / focusRadiusPx).coerceIn(0f, 1.8f)
                            val tFalloff = ((distNorm - 0.62f) / 0.96f).coerceIn(0f, 1f)
                            val falloff = tFalloff * tFalloff * (3f - 2f * tFalloff)
                            val baseScale = 1f - 0.08f * falloff
                            val tDist = (distNorm / 0.35f).coerceIn(0f, 1f)
                            val nearCenter = 1f - tDist * tDist * (3f - 2f * tDist)
                            val focusLift = 0.825f * aBoost.coerceIn(0f, 1f) * nearCenter
                            val tileScale = (baseScale + focusLift).coerceIn(0.84f, 1.88f)

                            if (appearance.tiledParallax) {
                                cameraDistance = 7.0f * density.density
                                translationX = dxPx + parallaxTilt.x * 24.dp.toPx()
                                translationY = dyPx + parallaxTilt.y * 18.dp.toPx()
                                rotationX = tiltXAnim.value + parallaxTilt.y * 19f
                                rotationY = tiltYAnim.value - parallaxTilt.x * 22f
                            } else {
                                cameraDistance = 16f * density.density
                                translationX = dxPx
                                translationY = dyPx
                                rotationX = tiltXAnim.value
                                rotationY = tiltYAnim.value
                            }
                            scaleX = tileScale
                            scaleY = tileScale
                            val edgeAlpha = (1f - falloff * 0.08f).coerceIn(0.90f, 1f)
                            alpha = edgeAlpha * heroContentAlpha
                            shadowElevation = 12.dp.toPx()
                            ambientShadowColor = focusShadowAmbient
                            spotShadowColor = focusShadowSpot
                            shape = tileShape
                            clip = true
                        }
                        .clip(tileShape)
                        .then(
                            if (useMiuixHighlight) {
                                Modifier.drawWithContent {
                                    drawContent()
                                    drawMiuixHighlight(miuixHeroHighlight, tileShape, shaderCache, highlightPaint)
                                }
                            } else {
                                Modifier.border(width = 0.6.dp, color = Color.White.copy(alpha = 0.28f), shape = tileShape)
                            },
                        ),
                ) {
                    Cover(
                        uri = song.artwork.ifBlank { anchoredTrack.artwork },
                        modifier = Modifier.fillMaxSize(),
                        radius = metrics.cornerRadius,
                        animation = appearance.coverAnimation,
                        animate = false,
                        transitionKey = song.key,
                        mixVisual = mixVisual,
                        placeholderTint = activeInk.copy(alpha = 0.3f),
                        showPlaceholderBackground = false,
                        spatialState = spatialCoverState,
                        spatialActive = spatialCoverRendering && !isDragging,
                        spatialTilt = if (spatialCoverRendering && !isDragging) {
                            scaleSpatialParallaxTilt(spatialTilt, appearance.spatialCoverSensitivity)
                        } else {
                            Offset.Zero
                        },
                        motionVideoPath = motionVideoPath,
                        motionPlaying = motionVideoAllowed && mixVisual == null && !isDragging,
                        motionOverlayAlpha = if (mixVisual == null && !spatialCoverRendering) 1f else 0f,
                    )
                }
            }
        }

        // Controls area backdrop: MIUIX progressive blur + cover-extracted color mix (no black shadow buildup)
        val topFeatherHeight = topInset + 56.dp
        val bottomControlBackdropHeight = if (metrics.isLandscape) {
            bottomInset + (if (metrics.dockCompact) 144.dp else 166.dp)
        } else {
            minOf(bottomInset + (if (appearance.showVolume) 396.dp else 348.dp), maxHeight * 0.56f)
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(topFeatherHeight)
                .graphicsLayer { alpha = dockChromeAlphaAnim.value }
                .background(
                    Brush.verticalGradient(
                        0f to scrimTone.copy(alpha = 0.22f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        if (dockChromeVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bottomControlBackdropHeight)
                    .graphicsLayer { alpha = dockChromeAlphaAnim.value },
            ) {
                if (captureBackdrop && progressiveBlurAlphaAnim.value > 0.01f) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                alpha = progressiveBlurAlphaAnim.value
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .progressiveTextureBlur(
                                backdrop = wallBackdrop,
                                shape = RectangleShape,
                                blurRadius = 4.2f,
                                gradient = ProgressiveBlur.Bottom.copy(startFraction = 0.14f, endFraction = 0.96f),
                            )
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.30f to Color.Black,
                                        1f to Color.Black,
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                    )
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.30f to coverMixTint.copy(alpha = if (advancedMaterial) 0.16f else 0.42f),
                                0.68f to coverMixTint.copy(alpha = if (advancedMaterial) 0.34f else 0.68f),
                                1f to coverMixTint.copy(alpha = if (advancedMaterial) 0.48f else 0.86f),
                            ),
                        ),
                )
            }
        }

        // Center Target Reticle + Progressive Switch Ring (visible while dragging; Draw-phase only)
        val reticleHoverPopAnim = remember { Animatable(1f) }
        val reticlePopTarget = when {
            armedCell != null -> 1.66f
            eligibleCandidate != null -> 1.22f
            else -> 1.08f
        }
        LaunchedEffect(reticlePopTarget) {
            reticleHoverPopAnim.animateTo(reticlePopTarget, spring(dampingRatio = 0.74f, stiffness = 320f))
        }
        val reticleCornerPx = with(density) { metrics.cornerRadius.toPx() }
        val reticleStrokePx = with(density) { 3.5.dp.toPx() }
        val reticlePath = remember { Path() }
        val reticleMeasure = remember { PathMeasure() }
        val reticleSegment = remember { Path() }

        Box(
            modifier = Modifier
                .offset { baseTileOffset }
                .size(metrics.tileSize),
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = isDragging,
                enter = fadeIn(tween(180)) + scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.92f),
                exit = fadeOut(tween(220)) + scaleOut(tween(200), targetScale = 0.94f),
                modifier = Modifier.fillMaxSize(),
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val progress = if (eligibleCandidate != null && armedCell == null) dwellProgress.value.coerceIn(0f, 1f) else 0f
                            val s = reticleHoverPopAnim.value + 0.38f * progress
                            scaleX = s
                            scaleY = s
                        },
                ) {
                    reticlePath.reset()
                    reticlePath.addRoundRect(
                        RoundRect(
                            left = 0f,
                            top = 0f,
                            right = size.width,
                            bottom = size.height,
                            cornerRadius = CornerRadius(reticleCornerPx, reticleCornerPx),
                        ),
                    )
                    drawPath(
                        path = reticlePath,
                        color = Color.White.copy(alpha = if (eligibleCandidate != null) 0.28f else 0.42f),
                        style = Stroke(width = reticleStrokePx * 0.55f),
                    )
                    val progress = dwellProgress.value.coerceIn(0f, 1f)
                    if (eligibleCandidate != null && progress > 0.005f) {
                        reticleMeasure.setPath(reticlePath, forceClosed = false)
                        reticleSegment.reset()
                        reticleMeasure.getSegment(0f, reticleMeasure.length * progress, reticleSegment, startWithMoveTo = true)
                        val activeStrokeColor = if (armedCell != null) glowColor else Color.White
                        drawPath(
                            path = reticleSegment,
                            color = activeStrokeColor,
                            style = Stroke(width = reticleStrokePx, cap = StrokeCap.Round),
                        )
                    }
                }
            }
        }

        // Floating Dwell Hint / Selected Track Preview Pill above the Reticle
        val candidateTrack = eligibleCandidate?.let { cand ->
            tracks[
                tiledTrackIndexForCell(
                    col = cand.col,
                    row = cand.row,
                    baseTrackOffset = baseTrackOffset,
                    rowStride = rowStride,
                    trackCount = tracks.size,
                ),
            ]
        }
        val pillShape = RoundedCornerShape(50)
        val pillTint = oneUi?.card ?: expressive?.surfaceContainer ?: menuTint
        val pillSurfaceMod = Modifier
            .background(pillTint.copy(alpha = 0.84f), pillShape)
            .border(0.75.dp, Color.White.copy(alpha = 0.18f), pillShape)

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = with(density) { (focusY - tileSizePx * 0.90f - 44.dp.toPx()).toDp().coerceAtLeast(topInset + 44.dp) }),
        ) {
            AnimatedVisibility(
                visible = isDragging,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(180)),
            ) {
                TiledDwellHintPill(
                    armed = armedCell != null,
                    candidateTitle = candidateTrack?.title,
                    dwellSeconds = dwellSeconds,
                    dwellProgressProvider = { dwellProgress.value },
                    glowColor = glowColor,
                    activeInk = activeInk,
                    pillShape = pillShape,
                    pillSurfaceMod = pillSurfaceMod,
                    maxWidth = maxWidth - leftInset - rightInset - 48.dp,
                )
            }
        }

        // Top-left queue badge (dimmed cleanly while dragging)
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
                .height(CoverThemeHeaderTouchHeight)
                .graphicsLayer { alpha = dockChromeAlphaAnim.value },
        )

        // Landscape keeps compact two-row controls over the progressive blur mix; portrait directly reuses SQUARE layout in PlayerHost.
        if (metrics.isLandscape) {
            PlayerCoverThemeControlDock(
                params = params.copy(interactive = interactive && !isDragging),
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
                    .fillMaxWidth()
                    .graphicsLayer { alpha = dockChromeAlphaAnim.value },
            )
        }
    }
}

@Composable
private fun TiledDwellHintPill(
    armed: Boolean,
    candidateTitle: String?,
    dwellSeconds: Float,
    dwellProgressProvider: () -> Float,
    glowColor: Color,
    activeInk: Color,
    pillShape: RoundedCornerShape,
    pillSurfaceMod: Modifier,
    maxWidth: Dp,
) {
    // Quantize countdown readout to 0.1s tenths so the pill recomposes at most 10Hz during dwell.
    val remainingTenths by remember(dwellSeconds, candidateTitle, armed) {
        derivedStateOf {
            if (candidateTitle == null || armed) 0
            else ((1f - dwellProgressProvider()) * dwellSeconds * 10f).roundToInt().coerceAtLeast(1)
        }
    }
    val labelText = when {
        armed && candidateTitle != null ->
            "${tr("player_tiled_release_to_switch", "松手立即切歌")} · $candidateTitle"
        candidateTitle != null ->
            "${remainingTenths / 10f}s · $candidateTitle"
        else ->
            "${"%.1f".format(dwellSeconds)}s · ${tr("player_tiled_dwell_action", "拖到中间停留切换")}"
    }
    Box(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .then(pillSurfaceMod)
            .clip(pillShape)
            .border(
                width = 0.9.dp,
                color = if (armed) glowColor.copy(alpha = 0.85f) else activeInk.copy(alpha = 0.16f),
                shape = pillShape,
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = labelText,
            color = activeInk,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
