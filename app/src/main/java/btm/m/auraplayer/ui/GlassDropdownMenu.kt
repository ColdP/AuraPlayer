// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.auraplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import btm.m.auraplayer.ui.navigation.InteractiveHighlight
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.IconButtonDefaults
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberListPopupLayoutInfo
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils
import kotlin.math.roundToInt
import androidx.compose.runtime.rememberCoroutineScope

private val GlassMenuCornerRadius = 25.dp
private val GlassMenuShadowPadding = 28.dp
private val GlassMenuHorizontalOffset = 18.dp

/** Keep the measured popup inside the safe window; its list scrolls at full item size. */
internal fun glassMenuPositionInWindow(preferred: Int, popupSize: Int, safeStart: Int, safeEnd: Int): Int =
    preferred.coerceIn(safeStart, (safeEnd - popupSize).coerceAtLeast(safeStart))

@Composable
fun GlassDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
    showValueOnEnd: Boolean = false,
    disabledIndices: Set<Int> = emptySet(),
    onSelectedIndexChange: (Int) -> Unit,
) {
    val selected = selectedIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0))
    GlassDropdownMenu(
        entry = DropdownEntry(
            items = items.mapIndexed { index, text ->
                DropdownItem(
                    text = text,
                    selected = index == selected,
                    enabled = index !in disabledIndices,
                    onClick = { onSelectedIndexChange(index) },
                )
            },
        ),
        title = title,
        modifier = modifier,
        enabled = enabled,
        backdrop = backdrop,
        showValueOnEnd = showValueOnEnd,
    )
}

private val MaterialExpandMoreIcon: ImageVector by lazy {
    ImageVector.Builder("M3ExpandMore", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16.59f, 8.59f)
            lineTo(12f, 13.17f)
            lineTo(7.41f, 8.59f)
            lineTo(6f, 10f)
            lineToRelative(6f, 6f)
            lineToRelative(6f, -6f)
            close()
        }
    }.build()
}

@Composable
fun GlassDropdownMenu(
    entry: DropdownEntry,
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
    maxHeight: Dp? = null,
    showValueOnEnd: Boolean = false,
) {
    val expanded = remember { mutableStateOf(false) }
    val entries = remember(entry) { listOf(entry) }
    val available = enabled && entry.items.isNotEmpty()
    val colors = glassDropdownColors()
    val selectedSummary = entry.items.firstOrNull { it.selected }?.text
    val uiStyle = LocalAppUiStyle.current

    Box(modifier) {
        when (uiStyle) {
            AppUiStyle.MATERIAL -> {
                val scheme = rememberActiveExpressiveScheme()
                val alpha = if (available) 1f else .38f
                val chevronRotation by animateFloatAsState(
                    targetValue = if (expanded.value) 180f else 0f,
                    animationSpec = m3ExpressiveSpatialSpring(),
                    label = "m3eDropdownChevron",
                )
                MaterialExpressivePreferenceRow(
                    title = title,
                    summary = selectedSummary.takeUnless { showValueOnEnd },
                    enabled = available,
                    onClick = { if (available) expanded.value = !expanded.value },
                    trailing = {
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(scheme.secondaryContainer.copy(alpha = alpha))
                                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selectedSummary != null) {
                                Text(
                                    text = selectedSummary,
                                    color = scheme.onSecondaryContainer.copy(alpha = alpha),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Image(
                                imageVector = MaterialExpandMoreIcon,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(18.dp)
                                    .graphicsLayer { rotationZ = chevronRotation },
                                colorFilter = ColorFilter.tint(scheme.onSecondaryContainer.copy(alpha = alpha)),
                            )
                        }
                    },
                )
            }
            AppUiStyle.ONE -> {
                val palette = rememberActiveOneUiPalette()
                val alpha = if (available) 1f else .42f
                val chevronRotation by animateFloatAsState(
                    targetValue = if (expanded.value) 180f else 0f,
                    animationSpec = tween(180),
                    label = "oneDropdownChevron",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 58.dp)
                        .clickable(enabled = available, role = Role.DropdownList) {
                            if (available) expanded.value = !expanded.value
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .graphicsLayer { this.alpha = alpha },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(palette.text),
                        )
                        if (!showValueOnEnd && selectedSummary != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = selectedSummary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(palette.accentDeep),
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    if (showValueOnEnd && selectedSummary != null) {
                        Text(
                            text = selectedSummary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(palette.accentDeep),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Image(
                        imageVector = MaterialExpandMoreIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = chevronRotation },
                        colorFilter = ColorFilter.tint(Color(palette.subText)),
                    )
                }
            }
            AppUiStyle.HYPER -> {
                BasicComponent(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = CompactPreferencePadding,
                    interactionSource = remember { MutableInteractionSource() },
                    title = title,
                    summary = selectedSummary.takeUnless { showValueOnEnd },
                    endActions = {
                        if (showValueOnEnd && selectedSummary != null) {
                            Text(
                                selectedSummary,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        DropdownArrowEndAction(MiuixTheme.colorScheme.onSurfaceVariantActions)
                    },
                    onClick = { if (available) expanded.value = !expanded.value },
                    role = Role.DropdownList,
                    holdDownState = false,
                    enabled = available,
                )
            }
        }
        GlassDropdownPopup(
            entries = entries,
            show = expanded.value,
            onDismiss = { expanded.value = false },
            maxHeight = maxHeight,
            dropdownColors = colors,
            backdrop = backdrop,
        )
    }
}

@Composable
fun GlassIconDropdownMenu(
    entry: DropdownEntry,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onExpandedChange: (Boolean) -> Unit = {},
    trigger: (@Composable (onClick: () -> Unit, enabled: Boolean) -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val expanded = remember { mutableStateOf(false) }
    LaunchedEffect(expanded.value) { onExpandedChange(expanded.value) }
    DisposableEffect(Unit) { onDispose { onExpandedChange(false) } }
    val available = enabled && entry.items.isNotEmpty()
    Box(modifier) {
        val toggleExpanded = { if (available) expanded.value = !expanded.value }
        if (trigger != null) {
            trigger(toggleExpanded, available)
        } else {
            IconButton(
                onClick = toggleExpanded,
                enabled = available,
                holdDownState = false,
                content = content,
            )
        }
        GlassDropdownPopup(
            entries = listOf(entry),
            show = expanded.value,
            onDismiss = { expanded.value = false },
            dropdownColors = glassDropdownColors(),
            backdrop = backdrop,
            alignment = PopupPositionProvider.Align.End,
            horizontalOffset = 0.dp,
            useAnchorVerticalPosition = true,
        )
    }
}

@Composable
private fun glassDropdownColors(): DropdownColors = DropdownDefaults.dropdownColors(
    containerColor = Color.Transparent,
    selectedContainerColor = Color.Transparent,
)

@Composable
private fun GlassDropdownPopup(
    entries: List<DropdownEntry>,
    show: Boolean,
    onDismiss: () -> Unit,
    dropdownColors: DropdownColors,
    backdrop: Backdrop?,
    maxHeight: Dp? = null,
    alignment: PopupPositionProvider.Align = PopupPositionProvider.Align.End,
    horizontalOffset: Dp = GlassMenuHorizontalOffset,
    useAnchorVerticalPosition: Boolean = false,
) {
    val fraction = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    var hostVisible by remember { mutableStateOf(false) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    var parentBounds by remember { mutableStateOf(IntRect.Zero) }
    var hostPosition by remember { mutableStateOf(Offset.Zero) }
    var popupSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(show) {
        if (show) {
            hostVisible = true
            launch {
                fraction.animateTo(
                    1f,
                    spring(dampingRatio = .78f, stiffness = 232f, visibilityThreshold = .0001f),
                )
            }
            launch { alpha.animateTo(1f, tween(120)) }
        } else if (hostVisible) {
            launch {
                fraction.animateTo(
                    0f,
                    spring(dampingRatio = .78f, stiffness = 400f, visibilityThreshold = .0001f),
                )
            }
            alpha.animateTo(0f, tween(320))
            fraction.snapTo(0f)
            hostVisible = false
        }
    }

    if (!show && !hostVisible) return

    androidx.compose.foundation.layout.Spacer(
        Modifier.onGloballyPositioned { coordinates ->
            coordinates.parentLayoutCoordinates?.let { parent ->
                val position = parent.positionInWindow()
                parentBounds = IntRect(
                    position.x.toInt(),
                    position.y.toInt(),
                    position.x.toInt() + parent.size.width,
                    position.y.toInt() + parent.size.height,
                )
            }
        },
    )
    if (parentBounds == IntRect.Zero) return

    val positionProvider = ListPopupDefaults.DropdownPositionProvider
    val shadowPadding = with(LocalDensity.current) { GlassMenuShadowPadding.roundToPx() }
    val horizontalOffsetPx = with(LocalDensity.current) { horizontalOffset.roundToPx() }
    val layoutInfo = rememberListPopupLayoutInfo(
        alignment = alignment,
        popupPositionProvider = positionProvider,
        parentBounds = parentBounds,
        popupContentSize = popupSize,
    )

    MiuixPopupUtils.Companion.PopupLayout(
        visible = remember(hostVisible) { mutableStateOf(hostVisible) }.also { it.value = hostVisible },
        enableWindowDim = false,
        enableBackHandler = false,
        enterTransition = androidx.compose.animation.EnterTransition.None,
        exitTransition = androidx.compose.animation.ExitTransition.None,
        renderInRootScaffold = true,
    ) {
        BackHandler(enabled = show) { currentDismiss() }
        Box(Modifier.fillMaxSize()) {
            Layout(
                content = {
                    GlassPopupSurface(
                        fraction = fraction.value,
                        alpha = alpha.value,
                        backdrop = backdrop,
                        onSizeChanged = { popupSize = it },
                    ) {
                        GlassDropdownEntries(entries, dropdownColors) { entryIndex, itemIndex ->
                            entries[entryIndex].items[itemIndex].onClick?.invoke()
                            currentDismiss()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { hostPosition = it.positionInWindow() }
                    .pointerInput(Unit) { detectTapGestures(onTap = { currentDismiss() }) },
            ) { measurables, constraints ->
                val measurable = measurables.single()
                val safeTop = layoutInfo.windowBounds.top + layoutInfo.popupMargin.top
                val safeBottom = layoutInfo.windowBounds.bottom - layoutInfo.popupMargin.bottom
                // ListPopupColumn already provides vertical scrolling. Bound its
                // viewport to the safe window without shrinking any menu items.
                val maxPopupHeight = minOf(maxHeight?.roundToPx() ?: constraints.maxHeight,
                    constraints.maxHeight, (safeBottom - safeTop).coerceAtLeast(1))
                val placeable = measurable.measure(
                    constraints.copy(minWidth = 0, minHeight = 0, maxHeight = maxPopupHeight.coerceAtLeast(1)),
                )
                val result = positionProvider.calculatePosition(
                    parentBounds,
                    layoutInfo.windowBounds,
                    layoutDirection,
                    IntSize(placeable.width, placeable.height),
                    layoutInfo.popupMargin,
                    alignment,
                )
                val preferredY = if (useAnchorVerticalPosition) {
                    // GlassPopupSurface reserves shadow padding inside the popup. Align
                    // the visible glass surface with the preference anchor instead of
                    // leaving a full preference-height gap below it.
                    if (result.y < parentBounds.top) {
                        parentBounds.top - placeable.height + shadowPadding
                    } else {
                        parentBounds.bottom - shadowPadding
                    }
                } else {
                    val safeInset = (parentBounds.height * .10f).roundToInt()
                    val minY = layoutInfo.windowBounds.top + layoutInfo.popupMargin.top
                    val maxY = (layoutInfo.windowBounds.bottom - placeable.height - layoutInfo.popupMargin.bottom)
                        .coerceAtLeast(minY)
                    (parentBounds.top + safeInset - shadowPadding).coerceIn(minY, maxY)
                }
                // Anchor placement is a preference, never permission to bypass
                // the provider's screen bounds (especially for long quality lists).
                val popupY = glassMenuPositionInWindow(preferredY, placeable.height, safeTop, safeBottom)
                val popupX = glassMenuPositionInWindow(result.x + horizontalOffsetPx, placeable.width,
                    layoutInfo.windowBounds.left + layoutInfo.popupMargin.left,
                    layoutInfo.windowBounds.right - layoutInfo.popupMargin.right)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(
                        popupX - hostPosition.x.toInt(),
                        popupY - hostPosition.y.toInt(),
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassPopupSurface(
    fraction: Float,
    alpha: Float,
    backdrop: Backdrop?,
    onSizeChanged: (IntSize) -> Unit,
    content: @Composable () -> Unit,
) {
    val uiStyle = LocalAppUiStyle.current
    if (uiStyle == AppUiStyle.MATERIAL) {
        val scheme = rememberActiveExpressiveScheme()
        val m3Shape = RoundedCornerShape(24.dp)
        val container = if (scheme.dark) scheme.surfaceContainerHigh else scheme.surfaceContainerLow
        Box(Modifier.onGloballyPositioned { onSizeChanged(it.size) }.padding(GlassMenuShadowPadding)) {
            Box(
                Modifier
                    .sizeIn(minWidth = 200.dp)
                    .graphicsLayer {
                        val scale = .88f + .12f * fraction
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                        transformOrigin = TransformOrigin(.5f, 0f)
                    }
                    .shadow(12.dp, m3Shape, clip = false)
                    .clip(m3Shape)
                    .background(container, m3Shape)
                    .border(BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = if (scheme.dark) .28f else .18f)), m3Shape),
            ) {
                content()
            }
        }
        return
    }
    if (uiStyle == AppUiStyle.ONE) {
        val palette = rememberActiveOneUiPalette()
        val oneShape = RoundedCornerShape(OneUiCardRadius)
        Box(Modifier.onGloballyPositioned { onSizeChanged(it.size) }.padding(GlassMenuShadowPadding)) {
            Box(
                Modifier
                    .sizeIn(minWidth = 200.dp)
                    .graphicsLayer {
                        val scale = .90f + .10f * fraction
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                        transformOrigin = TransformOrigin(.5f, 0f)
                    }
                    .shadow(12.dp, oneShape, clip = false)
                    .clip(oneShape)
                    .background(Color(palette.card), oneShape)
                    .border(BorderStroke(0.75.dp, Color(palette.divider)), oneShape),
            ) {
                content()
            }
        }
        return
    }
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val isLightTheme = surface.luminance() > .5f
    val tint = (if (isLightTheme) Color(0xFFF8F8F8) else surface).copy(alpha = .80f)
    val shadowColor = if (isLightTheme) Color.Gray else Color.Black
    val shape = RoundedCornerShape(GlassMenuCornerRadius)
    val animationScope = rememberCoroutineScope()
    val touchHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope,
            radiusMultiplier = .4f,
            surfaceAlpha = 0f,
            falloffMultiplier = .25f,
        ) { size, offset ->
            Offset(
                offset.x.coerceIn(0f, size.width),
                offset.y.coerceIn(0f, size.height),
            )
        }
    }
    // Report the full popup bounds (including the shadow inset) to the position
    // provider. Reporting only the inner glass surface makes Miuix position the
    // popup as if it were shorter, leaving roughly one preference row of gap.
    Box(Modifier.onGloballyPositioned { onSizeChanged(it.size) }.padding(GlassMenuShadowPadding)) {
        Box(
            Modifier
                .sizeIn(minWidth = DropdownDefaults.MinWidth)
                .graphicsLayer {
                    val scale = .24f + .76f * fraction
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                    transformOrigin = TransformOrigin(.5f, 0f)
                }
                .glassMenuSurface(backdrop, shape)
                .then(touchHighlight.gestureModifier)
                .then(touchHighlight.modifier),
        ) {
            content()
        }
    }
}

@Composable
private fun GlassDropdownEntries(
    entries: List<DropdownEntry>,
    colors: DropdownColors,
    onItemClick: (Int, Int) -> Unit,
) {
    if (LocalAppUiStyle.current == AppUiStyle.ONE) {
        val palette = rememberActiveOneUiPalette()
        ListPopupColumn {
            entries.forEachIndexed { entryIndex, entry ->
                entry.items.forEachIndexed { itemIndex, item ->
                    val enabled = entry.enabled && item.enabled
                    val selected = item.selected
                    val rowBg = if (selected) Color(palette.chip).copy(alpha = 0.55f) else Color.Transparent
                    val rowFg = when {
                        !enabled -> Color(palette.subText).copy(alpha = .45f)
                        selected -> Color(palette.accentDeep)
                        else -> Color(palette.text)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .background(rowBg)
                            .clickable(enabled = enabled) { onItemClick(entryIndex, itemIndex) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item.icon?.let { iconContent ->
                            iconContent(Modifier.padding(end = 10.dp))
                        }
                        Text(
                            text = item.text,
                            color = rowFg,
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Spacer(Modifier.width(12.dp))
                            Canvas(Modifier.size(16.dp)) {
                                val stroke = 2.dp.toPx()
                                drawLine(
                                    Color(palette.accentDeep),
                                    Offset(size.width * .18f, size.height * .52f),
                                    Offset(size.width * .42f, size.height * .76f),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round,
                                )
                                drawLine(
                                    Color(palette.accentDeep),
                                    Offset(size.width * .42f, size.height * .76f),
                                    Offset(size.width * .84f, size.height * .26f),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                    }
                    if (itemIndex < entry.items.lastIndex) {
                        HorizontalDivider(
                            Modifier.padding(horizontal = 20.dp),
                            thickness = 0.5.dp,
                            color = Color(palette.divider),
                        )
                    }
                }
                if (entryIndex != entries.lastIndex) {
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        thickness = 1.dp,
                        color = Color(palette.divider),
                    )
                }
            }
        }
        return
    }
    if (LocalAppUiStyle.current == AppUiStyle.MATERIAL) {
        val scheme = rememberActiveExpressiveScheme()
        val itemSurface = if (scheme.dark) scheme.surfaceContainerHighest else scheme.surfaceContainer
        ListPopupColumn {
            entries.forEachIndexed { entryIndex, entry ->
                entry.items.forEachIndexed { itemIndex, item ->
                    val enabled = entry.enabled && item.enabled
                    val selected = item.selected
                    val isFirst = itemIndex == 0
                    val isLast = itemIndex == entry.items.lastIndex
                    val outerTop = if (isFirst && entryIndex == 0) 6.dp else 1.5.dp
                    val outerBottom = if (isLast && entryIndex == entries.lastIndex) 6.dp else 1.5.dp
                    val itemShape = when {
                        isFirst && isLast -> RoundedCornerShape(18.dp)
                        isFirst -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
                        isLast -> RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                        else -> RoundedCornerShape(6.dp)
                    }
                    val rowBg = if (selected) scheme.secondaryContainer else itemSurface
                    val rowFg = when {
                        !enabled -> scheme.onSurface.copy(alpha = .38f)
                        selected -> scheme.onSecondaryContainer
                        else -> scheme.onSurface
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 6.dp, end = 6.dp, top = outerTop, bottom = outerBottom)
                            .defaultMinSize(minHeight = 46.dp)
                            .clip(itemShape)
                            .background(rowBg)
                            .clickable(enabled = enabled) { onItemClick(entryIndex, itemIndex) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item.icon?.let { iconContent ->
                            iconContent(Modifier.padding(end = 10.dp))
                        }
                        Text(
                            text = item.text,
                            color = rowFg,
                            fontSize = 14.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Spacer(Modifier.width(12.dp))
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(scheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Canvas(Modifier.size(12.dp)) {
                                    val stroke = 2.dp.toPx()
                                    drawLine(scheme.onPrimary, Offset(size.width * .18f, size.height * .52f), Offset(size.width * .42f, size.height * .76f), strokeWidth = stroke, cap = StrokeCap.Round)
                                    drawLine(scheme.onPrimary, Offset(size.width * .42f, size.height * .76f), Offset(size.width * .84f, size.height * .26f), strokeWidth = stroke, cap = StrokeCap.Round)
                                }
                            }
                        }
                    }
                }
                if (entryIndex != entries.lastIndex) {
                    Spacer(Modifier.padding(vertical = 3.dp))
                }
            }
        }
        return
    }
    ListPopupColumn {
        entries.forEachIndexed { entryIndex, entry ->
            entry.items.forEachIndexed { itemIndex, item ->
                DropdownImpl(
                    item = item,
                    optionSize = entry.items.size,
                    isSelected = item.selected,
                    index = itemIndex,
                    dropdownColors = colors,
                    enabled = entry.enabled && item.enabled,
                    isFirst = entryIndex == 0 && itemIndex == 0,
                    isLast = entryIndex == entries.lastIndex && itemIndex == entry.items.lastIndex,
                    onSelectedIndexChange = { onItemClick(entryIndex, it) },
                )
            }
            if (entryIndex != entries.lastIndex) {
                HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), thickness = 1.5.dp)
            }
        }
    }
}
