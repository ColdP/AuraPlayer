// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.HomeSectionLayoutConfig
import btm.m.auraplayer.data.HomeSectionTarget
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

private sealed interface HomeOrderEntry {
    val key: String

    data class Section(val id: String, val hidden: Boolean) : HomeOrderEntry {
        override val key: String get() = "section:$id"
    }

    data object Divider : HomeOrderEntry {
        override val key: String get() = "__divider__"
    }
}

@Composable
internal fun HomeOrderPage(
    model: AuraViewModel,
    target: HomeSectionTarget,
    padding: PaddingValues,
) {
    val persistedLayout = model.settings.value.homeLayout(target)
    var layout by remember(target) { mutableStateOf(persistedLayout) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var draggingId by remember { mutableStateOf<String?>(null) }
    var draggingOffsetY by remember { mutableFloatStateOf(0f) }
    var settlingId by remember { mutableStateOf<String?>(null) }
    val settlingOffset = remember { Animatable(0f) }

    LaunchedEffect(persistedLayout) {
        if (draggingId == null) {
            layout = persistedLayout
        }
    }

    val visibleIds = remember(layout) { layout.order.filter { it !in layout.hidden } }
    val hiddenIds = remember(layout) { layout.order.filter { it in layout.hidden } }
    val entries = remember(visibleIds, hiddenIds) {
        buildList {
            visibleIds.forEach { add(HomeOrderEntry.Section(it, hidden = false)) }
            if (hiddenIds.isNotEmpty()) {
                add(HomeOrderEntry.Divider)
                hiddenIds.forEach { add(HomeOrderEntry.Section(it, hidden = true)) }
            }
        }
    }

    val itemSpacing = 12.dp
    val itemSpacingPx = with(density) { itemSpacing.toPx() }
    val currentEntries by rememberUpdatedState(entries)
    val currentLayout by rememberUpdatedState(layout)

    fun persistLayout(next: HomeSectionLayoutConfig) {
        val normalized = next.normalized(target)
        layout = normalized
        model.updateSettings { it.withHomeLayout(target) { normalized } }
    }

    fun startDrag(id: String) {
        settlingId = null
        scope.launch { settlingOffset.snapTo(0f) }
        draggingId = id
        draggingOffsetY = 0f
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun updateDrag(id: String, deltaY: Float) {
        if (draggingId != id) return
        draggingOffsetY += deltaY
        val activeEntries = currentEntries
        val currentIndex = activeEntries.indexOfFirst { it is HomeOrderEntry.Section && it.id == id }
        if (currentIndex < 0) return

        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val draggedInfo = visibleItems.firstOrNull { it.key == "section:$id" } ?: return
        // Wait for Compose layout to catch up with the latest swap before checking the next boundary.
        val layoutSynced = draggedInfo.index == currentIndex &&
            activeEntries.getOrNull(currentIndex)?.key == "section:$id"
        if (!layoutSynced) return

        val draggedCenterY = draggedInfo.offset + draggingOffsetY + draggedInfo.size / 2f

        if (draggingOffsetY > 0f && currentIndex < activeEntries.lastIndex) {
            val nextEntry = activeEntries[currentIndex + 1]
            val nextInfo = visibleItems.firstOrNull { it.key == nextEntry.key }
            if (nextInfo != null && draggedCenterY > nextInfo.offset + nextInfo.size / 2f) {
                val base = currentLayout
                layout = when (nextEntry) {
                    HomeOrderEntry.Divider -> base.copy(hidden = base.hidden + id).normalized(target)
                    is HomeOrderEntry.Section -> swapOrderItems(base, id, nextEntry.id, target)
                }
                draggingOffsetY -= (nextInfo.size + itemSpacingPx)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                return
            }
        }

        if (draggingOffsetY < 0f && currentIndex > 0) {
            val prevEntry = activeEntries[currentIndex - 1]
            val prevInfo = visibleItems.firstOrNull { it.key == prevEntry.key }
            if (prevInfo != null && draggedCenterY < prevInfo.offset + prevInfo.size / 2f) {
                val base = currentLayout
                layout = when (prevEntry) {
                    HomeOrderEntry.Divider -> base.copy(hidden = base.hidden - id).normalized(target)
                    is HomeOrderEntry.Section -> swapOrderItems(base, id, prevEntry.id, target)
                }
                draggingOffsetY += (prevInfo.size + itemSpacingPx)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    fun finishDrag(id: String) {
        if (draggingId != id) return
        val settledFrom = draggingOffsetY
        draggingId = null
        draggingOffsetY = 0f
        persistLayout(currentLayout)
        if (abs(settledFrom) > 0.5f) {
            settlingId = id
            scope.launch {
                settlingOffset.snapTo(settledFrom)
                settlingOffset.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f))
                if (settlingId == id) settlingId = null
            }
        }
    }

    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val hideIconColor = if (dark) Color(0xFFFF5252) else Color(0xFFE53935)
    val cardRadius = when (LocalAppUiStyle.current) {
        AppUiStyle.ONE -> OneUiCardRadius
        AppUiStyle.MATERIAL -> 22.dp
        AppUiStyle.HYPER -> 22.5.dp
    }
    val cardShape = RoundedCornerShape(cardRadius)
    val surfaceBg = MiuixTheme.colorScheme.surface
    val cardContainerColor = MiuixTheme.colorScheme.surfaceContainer
    val advancedMaterial = LocalAdvancedMaterialEnabled.current
    val listBackdrop = rememberLayerBackdrop {
        drawRect(surfaceBg)
        drawContent()
    }
    var containerWindowPos by remember { mutableStateOf(Offset.Zero) }
    var activeSlotWindowPos by remember { mutableStateOf(Offset.Zero) }
    var activeSlotWidthPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { containerWindowPos = it.positionInWindow() },
    ) {
        LazyColumn(
            modifier = pageContentModifier().layerBackdrop(listBackdrop),
            state = listState,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
            userScrollEnabled = draggingId == null,
        ) {
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    HomeOrderEntry.Divider -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(
                                    fadeInSpec = tween(220),
                                    fadeOutSpec = tween(200),
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MiuixTheme.colorScheme.dividerLine),
                            )
                        }
                    }
                    is HomeOrderEntry.Section -> {
                        val isDragging = draggingId == entry.id
                        val isSettling = settlingId == entry.id
                        val isFloating = isDragging || isSettling
                        val contentAlpha by animateFloatAsState(
                            targetValue = if (entry.hidden) 0.42f else 1f,
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                            label = "order_content_alpha_${entry.id}",
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

                        var cardCoords by remember(entry.id) { mutableStateOf<LayoutCoordinates?>(null) }
                        var handleCoords by remember(entry.id) { mutableStateOf<LayoutCoordinates?>(null) }
                        var lastCardWindowY by remember(entry.id) { mutableFloatStateOf(0f) }
                        var lastHandleWindowY by remember(entry.id) { mutableFloatStateOf(0f) }

                        SeamlessCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(placementModifier)
                                .graphicsLayer { alpha = if (isFloating) 0f else 1f }
                                .clip(cardShape)
                                .onGloballyPositioned {
                                    cardCoords = it
                                    if (isFloating) {
                                        activeSlotWindowPos = it.positionInWindow()
                                        activeSlotWidthPx = it.size.width
                                    }
                                }
                                .pointerInput(entry.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { startOffset ->
                                            val coords = cardCoords
                                            if (coords != null && coords.isAttached) {
                                                activeSlotWindowPos = coords.positionInWindow()
                                                activeSlotWidthPx = coords.size.width
                                                lastCardWindowY = coords.localToWindow(startOffset).y
                                            } else {
                                                lastCardWindowY = startOffset.y
                                            }
                                            startDrag(entry.id)
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val coords = cardCoords
                                            val deltaY = if (coords != null && coords.isAttached) {
                                                val windowY = coords.localToWindow(change.position).y
                                                val d = windowY - lastCardWindowY
                                                lastCardWindowY = windowY
                                                d
                                            } else {
                                                dragAmount.y
                                            }
                                            updateDrag(entry.id, deltaY)
                                        },
                                        onDragEnd = { finishDrag(entry.id) },
                                        onDragCancel = { finishDrag(entry.id) },
                                    )
                                },
                            cornerRadius = 22.5.dp,
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            HomeOrderCardRow(
                                title = homeSectionTitle(target, entry.id),
                                hidden = entry.hidden,
                                contentAlpha = contentAlpha,
                                hideIconColor = hideIconColor,
                                handleModifier = Modifier
                                    .onGloballyPositioned { handleCoords = it }
                                    .pointerInput(entry.id) {
                                        detectDragGestures(
                                            onDragStart = { startOffset ->
                                                val slotCoords = cardCoords
                                                if (slotCoords != null && slotCoords.isAttached) {
                                                    activeSlotWindowPos = slotCoords.positionInWindow()
                                                    activeSlotWidthPx = slotCoords.size.width
                                                }
                                                val coords = handleCoords
                                                lastHandleWindowY = if (coords != null && coords.isAttached) {
                                                    coords.localToWindow(startOffset).y
                                                } else {
                                                    startOffset.y
                                                }
                                                startDrag(entry.id)
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val coords = handleCoords
                                                val deltaY = if (coords != null && coords.isAttached) {
                                                    val windowY = coords.localToWindow(change.position).y
                                                    val d = windowY - lastHandleWindowY
                                                    lastHandleWindowY = windowY
                                                    d
                                                } else {
                                                    dragAmount.y
                                                }
                                                updateDrag(entry.id, deltaY)
                                            },
                                            onDragEnd = { finishDrag(entry.id) },
                                            onDragCancel = { finishDrag(entry.id) },
                                        )
                                    },
                                onHide = {
                                    if (draggingId == null) {
                                        val nextOrder = currentLayout.order.filter { it != entry.id } + entry.id
                                        persistLayout(
                                            currentLayout.copy(
                                                order = nextOrder,
                                                hidden = currentLayout.hidden + entry.id,
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        val activeId = draggingId ?: settlingId
        val activeEntry = activeId?.let { id ->
            entries.firstOrNull { it is HomeOrderEntry.Section && it.id == id } as? HomeOrderEntry.Section
        }
        if (activeEntry != null && activeSlotWidthPx > 0) {
            val isDragging = draggingId == activeEntry.id
            val activeOffsetY = if (isDragging) draggingOffsetY else settlingOffset.value
            val dragScale by animateFloatAsState(
                targetValue = if (isDragging) 1.025f else 1f,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 460f),
                label = "order_floating_scale",
            )
            val dragElevation by animateDpAsState(
                targetValue = if (isDragging) 12.dp else 0.dp,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f),
                label = "order_floating_elevation",
            )
            val contentAlpha by animateFloatAsState(
                targetValue = if (activeEntry.hidden) 0.42f else 1f,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                label = "order_floating_content_alpha",
            )
            val relX = (activeSlotWindowPos.x - containerWindowPos.x).roundToInt()
            val relY = (activeSlotWindowPos.y - containerWindowPos.y + activeOffsetY).roundToInt()
            val slotWidthDp = with(density) { activeSlotWidthPx.toDp() }
            val glassTint = cardContainerColor.copy(alpha = if (advancedMaterial) (if (dark) 0.62f else 0.72f) else 0.94f)
            val blurSurfaceModifier = if (advancedMaterial) {
                Modifier.drawPlainBackdrop(
                    backdrop = listBackdrop,
                    shape = { cardShape },
                    effects = {
                        vibrancy()
                        backdropBlur(22.dp.toPx())
                    },
                    onDrawSurface = { drawRect(glassTint) },
                )
            } else {
                Modifier.background(glassTint, cardShape)
            }
            SeamlessCard(
                modifier = Modifier
                    .offset { IntOffset(relX, relY) }
                    .width(slotWidthDp)
                    .zIndex(10f)
                    .graphicsLayer {
                        scaleX = dragScale
                        scaleY = dragScale
                        shape = cardShape
                        clip = true
                        shadowElevation = dragElevation.toPx()
                    }
                    .clip(cardShape)
                    .then(blurSurfaceModifier)
                    .border(0.75.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.14f else 0.08f), cardShape),
                cornerRadius = 22.5.dp,
                insideMargin = PaddingValues(0.dp),
                colors = CardDefaults.defaultColors(color = Color.Transparent),
            ) {
                HomeOrderCardRow(
                    title = homeSectionTitle(target, activeEntry.id),
                    hidden = activeEntry.hidden,
                    contentAlpha = contentAlpha,
                    hideIconColor = hideIconColor,
                    handleModifier = Modifier,
                    onHide = {},
                )
            }
        }
    }
}

@Composable
private fun HomeOrderCardRow(
    title: String,
    hidden: Boolean,
    contentAlpha: Float,
    hideIconColor: Color,
    handleModifier: Modifier,
    onHide: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = contentAlpha },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .then(handleModifier),
                contentAlignment = Alignment.CenterStart,
            ) {
                Image(
                    imageVector = AuraDragHandleIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val hideTransition = updateTransition(!hidden, label = "hide_btn_$title")
        val hideBlur by hideTransition.animateFloat(
            transitionSpec = { tween(220, easing = FastOutSlowInEasing) },
            label = "hide_btn_blur_$title",
        ) { visible -> if (visible) 0f else 8f }
        hideTransition.AnimatedVisibility(
            visible = { it },
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.82f),
            exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.82f),
        ) {
            Box(Modifier.blur(hideBlur.dp)) {
                IconButton(
                    onClick = onHide,
                    modifier = Modifier.size(36.dp).clip(CircleShape),
                ) {
                    Image(
                        imageVector = MiuixIcons.Regular.Hide,
                        contentDescription = tr("hide", "隐藏"),
                        modifier = Modifier.size(20.dp),
                        colorFilter = ColorFilter.tint(hideIconColor),
                    )
                }
            }
        }
    }
}

private fun swapOrderItems(
    layout: HomeSectionLayoutConfig,
    firstId: String,
    secondId: String,
    target: HomeSectionTarget,
): HomeSectionLayoutConfig {
    val mutable = layout.order.toMutableList()
    val a = mutable.indexOf(firstId)
    val b = mutable.indexOf(secondId)
    if (a < 0 || b < 0 || a == b) return layout
    val tmp = mutable[a]
    mutable[a] = mutable[b]
    mutable[b] = tmp
    return layout.copy(order = mutable).normalized(target)
}

@Composable
private fun homeSectionTitle(target: HomeSectionTarget, sectionId: String): String = when (target) {
    HomeSectionTarget.NETEASE -> when (sectionId) {
        "daily_radar" -> tr("today_for_you", "今日精选")
        "singles" -> tr("recommended_singles", "为你推荐的单曲")
        "recent" -> tr("recently_listened", "最近常听")
        "recommend" -> tr("recommended_playlists", "推荐歌单")
        "banners" -> tr("netease_banners", "网易云音乐推荐")
        "follow" -> tr("followed_artist_updates", "你关注的艺人的新动向")
        "discover_albums" -> tr("discover_albums", "发现专辑")
        else -> sectionId
    }
    HomeSectionTarget.QQ -> when (sectionId) {
        "singles" -> tr("recommended_singles", "为你推荐的单曲")
        "playlists" -> tr("recommended_playlists", "推荐歌单")
        else -> sectionId
    }
}
