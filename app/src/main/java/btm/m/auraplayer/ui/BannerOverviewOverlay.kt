// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur as contentBlur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import btm.m.auraplayer.data.CloudHomeItem
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

internal data class BannerOverviewRequest(
    val items: List<CloudHomeItem>,
    val initialIndex: Int,
    val sourceBounds: Rect,
    val onSelectIndex: (Int) -> Unit,
    val onOpenItem: (CloudHomeItem) -> Unit,
)

internal val LocalBannerOverviewOpener = staticCompositionLocalOf<((BannerOverviewRequest) -> Unit)?> { null }

@Composable
internal fun BannerOverviewOverlay(
    request: BannerOverviewRequest,
    onProgress: (Float) -> Unit,
    onClosed: () -> Unit,
) {
    if (request.items.isEmpty()) {
        LaunchedEffect(request) { onClosed() }
        return
    }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val backdrop = LocalDialogBackdrop.current
    val surface = MiuixTheme.colorScheme.surface
    val darkTheme = surface.luminance() < 0.5f

    val openProgress = remember(request) { Animatable(0f) }
    var closing by remember(request) { mutableStateOf(false) }
    var predictiveOffsetX by remember(request) { mutableFloatStateOf(0f) }

    val initialIdx = request.initialIndex.coerceIn(0, request.items.lastIndex)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIdx)
    val snapFling = rememberSnapFlingBehavior(lazyListState = listState, snapPosition = SnapPosition.Center)

    val centerIndex by remember(request.items) {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) initialIdx
            else {
                val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                visible.minByOrNull { item -> abs((item.offset + item.size / 2f) - viewportCenter) }?.index ?: initialIdx
            }
        }
    }

    LaunchedEffect(request) {
        openProgress.animateTo(1f, spring(dampingRatio = 0.88f, stiffness = 360f))
    }

    val currentProgress = openProgress.value.coerceIn(0f, 1f)
    SideEffect { onProgress(currentProgress) }

    fun dismiss(openItem: CloudHomeItem? = null) {
        if (closing) return
        closing = true
        val targetIndex = centerIndex.coerceIn(0, request.items.lastIndex)
        request.onSelectIndex(targetIndex)
        scope.launch {
            openProgress.animateTo(0f, tween(290, easing = FastOutSlowInEasing))
            onProgress(0f)
            onClosed()
            if (openItem != null) request.onOpenItem(openItem)
        }
    }

    PredictiveBackHandler(enabled = !closing) { events ->
        openProgress.stop()
        val start = openProgress.value
        var lastEdgeSign = 1f
        try {
            events.collect { event ->
                val p = event.progress.coerceIn(0f, 1f)
                lastEdgeSign = if (event.swipeEdge == androidx.activity.BackEventCompat.EDGE_LEFT) 1f else -1f
                predictiveOffsetX = lastEdgeSign * with(density) { 26.dp.toPx() } * p
                openProgress.snapTo((start * (1f - 0.36f * p)).coerceIn(0.05f, 1f))
            }
            predictiveOffsetX = 0f
            dismiss()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                predictiveOffsetX = 0f
                if (!closing) openProgress.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 380f))
            }
            throw e
        }
    }

    val scrimColor = if (darkTheme) Color.Black.copy(alpha = 0.42f) else Color.Black.copy(alpha = 0.24f)
    val backgroundModifier = if (backdrop != null) {
        Modifier
            .fillMaxSize()
            .drawPlainBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = {
                    val radius = (30.dp * currentProgress).toPx()
                    if (radius > 0.5f) blur(radius)
                },
                onDrawSurface = { drawRect(scrimColor.copy(alpha = scrimColor.alpha * currentProgress)) },
            )
    } else {
        Modifier
            .fillMaxSize()
            .background(scrimColor.copy(alpha = (scrimColor.alpha + 0.35f) * currentProgress))
    }

    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val cardHeight = 148.dp
    val cardSpacing = 14.dp
    val horizontalMargin = 28.dp

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.positionInWindow() }
            .then(backgroundModifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !closing,
            ) { dismiss() },
    ) {
        val viewportW = maxWidth
        val viewportH = maxHeight
        val cardW = (viewportW - horizontalMargin * 2).coerceAtLeast(180.dp)
        val verticalPad = ((viewportH - cardHeight) / 2).coerceAtLeast(32.dp)

        val cardWPx = with(density) { cardW.toPx() }
        val cardHPx = with(density) { cardHeight.toPx() }
        val spacingPx = with(density) { cardSpacing.toPx() }
        val targetLeftPx = (with(density) { viewportW.toPx() } - cardWPx) / 2f
        val targetTopPx = (with(density) { viewportH.toPx() } - cardHPx) / 2f

        val src = request.sourceBounds
        val hasValidSource = src.width > 0f && src.height > 0f
        val srcDx = if (hasValidSource) (src.left - overlayOrigin.x) - targetLeftPx else 0f
        val srcDy = if (hasValidSource) (src.top - overlayOrigin.y) - targetTopPx else 0f
        val srcScaleX = if (hasValidSource && cardWPx > 0f) src.width / cardWPx else 1f
        val srcScaleY = if (hasValidSource && cardHPx > 0f) src.height / cardHPx else 1f

        LazyColumn(
            state = listState,
            flingBehavior = snapFling,
            contentPadding = PaddingValues(vertical = verticalPad),
            verticalArrangement = Arrangement.spacedBy(cardSpacing),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !closing && currentProgress > 0.85f,
        ) {
            items(request.items.size, key = { idx -> "overview-$idx-${request.items[idx].id}" }) { idx ->
                val item = request.items[idx]
                val isCenter = idx == centerIndex
                val info = listState.layoutInfo
                val visibleItem = info.visibleItemsInfo.firstOrNull { it.index == idx }
                val dist = if (visibleItem != null) {
                    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                    val itemCenter = visibleItem.offset + visibleItem.size / 2f
                    abs(itemCenter - viewportCenter) / (visibleItem.size + spacingPx).coerceAtLeast(1f)
                } else {
                    abs(idx - centerIndex).toFloat()
                }

                val scrollScale = (1f - dist * 0.055f).coerceIn(0.84f, 1f)
                val scrollAlpha = (1f - (dist - 0.85f).coerceAtLeast(0f) * 0.34f).coerceIn(0.45f, 1f)
                val edgeBlurDp = ((dist - 0.95f).coerceAtLeast(0f) / 1.0f).coerceIn(0f, 1f) * 10f

                val cardScaleX = if (isCenter && hasValidSource) {
                    srcScaleX + (scrollScale - srcScaleX) * currentProgress
                } else {
                    0.88f + (scrollScale - 0.88f) * currentProgress
                }
                val cardScaleY = if (isCenter && hasValidSource) {
                    srcScaleY + (scrollScale - srcScaleY) * currentProgress
                } else {
                    0.88f + (scrollScale - 0.88f) * currentProgress
                }
                val tx = if (isCenter && hasValidSource) {
                    srcDx * (1f - currentProgress) + predictiveOffsetX
                } else {
                    predictiveOffsetX * (1f - (dist * 0.15f).coerceIn(0f, 0.45f))
                }
                val ty = if (isCenter && hasValidSource) {
                    srcDy * (1f - currentProgress)
                } else {
                    (idx - centerIndex) * -with(density) { 26.dp.toPx() } * (1f - currentProgress)
                }
                val cardAlpha = if (isCenter) {
                    1f
                } else {
                    scrollAlpha * ((currentProgress - 0.12f) / 0.88f).coerceIn(0f, 1f)
                }
                val cardBlur = if (isCenter) 0.dp else (edgeBlurDp * currentProgress).dp

                CloudHomeBannerCard(
                    item = item,
                    modifier = Modifier
                        .width(cardW)
                        .height(cardHeight)
                        .zIndex(if (isCenter) 2f else (1f - dist.coerceIn(0f, 1f)))
                        .graphicsLayer {
                            translationX = tx
                            translationY = ty
                            scaleX = cardScaleX
                            scaleY = cardScaleY
                            alpha = cardAlpha
                            shadowElevation = with(density) { (if (isCenter) 14.dp else 6.dp).toPx() * currentProgress }
                            shape = RoundedCornerShape(18.dp)
                            clip = true
                        }
                        .then(if (cardBlur > 0.2.dp) Modifier.contentBlur(cardBlur) else Modifier)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = !closing && currentProgress > 0.85f,
                        ) {
                            if (isCenter) {
                                dismiss(openItem = item)
                            } else {
                                scope.launch { listState.animateScrollToItem(idx) }
                            }
                        },
                )
            }
        }
    }
}
