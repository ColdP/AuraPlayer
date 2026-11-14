// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.auraplayer.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import btm.m.auraplayer.ui.navigation.DampedDragAnimation
import btm.m.auraplayer.ui.navigation.HostTab
import btm.m.auraplayer.ui.player.PlayerExpressiveColors
import dev.pascal.oneuikit.BoSwitchView
import dev.pascal.oneuikit.DragOnlySeekBar
import dev.pascal.oneuikit.OneUiPalette
import dev.pascal.oneuikit.OneUiWidgets
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

internal val LocalAuraOneUiPalette = staticCompositionLocalOf<OneUiPalette?> { null }
internal val LocalAuraOneUiWidgets = staticCompositionLocalOf<OneUiWidgets?> { null }

internal val OneUiCardRadius = 26.dp

private fun <T> oneUiSpring() = spring<T>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

@Composable
internal fun rememberOneUiPalette(
    isDark: Boolean,
    useMonet: Boolean,
    customSeed: Int?,
): OneUiPalette {
    val context = LocalContext.current
    return remember(context, isDark, useMonet, customSeed) {
        OneUiPalette(
            context = context,
            forceDark = isDark,
            useMonet = useMonet,
            customSeed = customSeed,
        )
    }
}

@Composable
internal fun rememberActiveOneUiPalette(): OneUiPalette {
    LocalAuraOneUiPalette.current?.let { return it }
    val context = LocalContext.current
    val isDark = MiuixTheme.colorScheme.surface.luminance() < .5f
    return remember(context, isDark) {
        OneUiPalette(context = context, forceDark = isDark, useMonet = false)
    }
}

@Composable
internal fun rememberActiveOneUiWidgets(): OneUiWidgets {
    LocalAuraOneUiWidgets.current?.let { return it }
    val context = LocalContext.current
    val palette = rememberActiveOneUiPalette()
    return remember(context, palette) { OneUiWidgets(context, palette) }
}

internal fun OneUiPalette.toPlayerExpressiveColors(): PlayerExpressiveColors {
    val accentColor = Color(accentDeep)
    val surface = Color(background)
    val textColor = Color(text)
    val subTextColor = Color(subText)
    return PlayerExpressiveColors(
        dark = dark,
        surface = surface,
        surfaceContainerLow = Color(card),
        surfaceContainer = Color(card),
        surfaceContainerHigh = Color(cardAlt),
        surfaceContainerHighest = Color(segmentTrack),
        onSurface = textColor,
        onSurfaceVariant = subTextColor,
        primary = accentColor,
        onPrimary = Color(onAccent),
        primaryContainer = Color(chip),
        onPrimaryContainer = Color(onChip),
        secondary = Color(accent),
        secondaryContainer = Color(chip),
        onSecondaryContainer = Color(onChip),
        tertiaryContainer = Color(cardAlt),
        onTertiaryContainer = textColor,
        outlineVariant = Color(divider),
        activeInk = textColor,
        secondaryInk = subTextColor,
        timeInk = subTextColor,
        iconInk = subTextColor,
        dragHandleColor = Color(track),
    )
}

internal fun Colors.withOneUiColors(p: OneUiPalette): Colors = copy(
    primary = Color(p.accentDeep),
    onPrimary = Color(p.onAccent),
    primaryVariant = Color(p.chip),
    onPrimaryVariant = Color(p.onChip),
    disabledPrimary = Color(p.text).copy(alpha = 0.12f),
    disabledOnPrimary = Color(p.text).copy(alpha = 0.38f),
    disabledPrimaryButton = Color(p.text).copy(alpha = 0.12f),
    disabledOnPrimaryButton = Color(p.text).copy(alpha = 0.38f),
    disabledPrimarySlider = Color(p.text).copy(alpha = 0.38f),
    primaryContainer = Color(p.chip),
    onPrimaryContainer = Color(p.onChip),
    secondary = Color(p.accent),
    onSecondary = Color(p.onAccent),
    secondaryVariant = Color(p.chip),
    onSecondaryVariant = Color(p.onChip),
    secondaryContainer = Color(p.chip),
    onSecondaryContainer = Color(p.onChip),
    secondaryContainerVariant = Color(p.chip),
    onSecondaryContainerVariant = Color(p.onChip),
    tertiaryContainer = Color(p.cardAlt),
    onTertiaryContainer = Color(p.text),
    tertiaryContainerVariant = Color(p.cardAlt),
    background = Color(p.background),
    onBackground = Color(p.text),
    onBackgroundVariant = Color(p.subText),
    surface = Color(p.background),
    onSurface = Color(p.text),
    surfaceVariant = Color(p.cardAlt),
    onSurfaceSecondary = Color(p.subText),
    onSurfaceVariantSummary = Color(p.subText),
    onSurfaceVariantActions = Color(p.subText),
    disabledOnSurface = Color(p.text).copy(alpha = 0.38f),
    surfaceContainer = Color(p.card),
    onSurfaceContainer = Color(p.text),
    onSurfaceContainerVariant = Color(p.subText),
    surfaceContainerHigh = Color(p.cardAlt),
    onSurfaceContainerHigh = Color(p.text),
    surfaceContainerHighest = Color(p.segmentTrack),
    onSurfaceContainerHighest = Color(p.text),
    outline = Color(p.divider),
    dividerLine = Color(p.divider),
    sliderKeyPoint = Color(p.accent).copy(alpha = 0.35f),
    sliderKeyPointForeground = Color(p.accent),
    sliderBackground = Color(p.track),
)

private object OneUiColumnScope : ColumnScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier = this
    override fun Modifier.align(alignment: Alignment.Horizontal): Modifier = this
    override fun Modifier.alignBy(alignmentLine: VerticalAlignmentLine): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = this
}

/**
 * One UI 9 卡片容器：26dp 连续圆角实色卡片，内部仅在相邻可见行之间绘制左缩进 20dp 的极细分割线。
 */
@Composable
internal fun OneUiCardColumn(
    modifier: Modifier = Modifier,
    containerColor: Color = Color(rememberActiveOneUiPalette().card),
    dividerColor: Color = Color(rememberActiveOneUiPalette().divider),
    dividerInset: Dp = 20.dp,
    cornerRadius: Dp = OneUiCardRadius,
    content: @Composable ColumnScope.() -> Unit,
) {
    val insetPx = with(LocalDensity.current) { dividerInset.toPx() }
    val dividerStrokePx = with(LocalDensity.current) { 0.75.dp.toPx().coerceAtLeast(1f) }
    val dividerOffsets = remember { ArrayList<Float>() }
    val shape = RoundedCornerShape(cornerRadius)
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor, shape)
            .drawWithContent {
                drawContent()
                val startX = insetPx
                val endX = (size.width - insetPx).coerceAtLeast(startX)
                for (y in dividerOffsets) {
                    drawLine(
                        color = dividerColor,
                        start = Offset(startX, y),
                        end = Offset(endX, y),
                        strokeWidth = dividerStrokePx,
                    )
                }
            },
        content = { OneUiColumnScope.content() },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val visible = ArrayList<Placeable>(placeables.size)
        for (p in placeables) {
            if (p.height > 0) visible.add(p)
        }
        dividerOffsets.clear()
        val totalHeight = visible.sumOf { it.height }
        val width = (placeables.maxOfOrNull { it.width } ?: constraints.minWidth)
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        var y = 0
        val positions = IntArray(visible.size)
        visible.forEachIndexed { idx, p ->
            positions[idx] = y
            y += p.height
            if (idx < visible.lastIndex) {
                dividerOffsets.add(y.toFloat())
            }
        }
        layout(width, totalHeight) {
            visible.forEachIndexed { idx, p ->
                p.placeRelative(0, positions[idx])
            }
        }
    }
}

@Composable
internal fun OneUiGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = rememberActiveOneUiPalette()
    Column(Modifier.fillMaxWidth()) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                color = Color(palette.subText),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.05.sp,
                modifier = Modifier.padding(start = 26.dp, top = 12.dp, end = 22.dp, bottom = 8.dp),
            )
        }
        OneUiCardColumn(
            containerColor = Color(palette.card),
            dividerColor = Color(palette.divider),
            content = content,
        )
    }
}

@Composable
internal fun OneUiPreferenceRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    value: String? = null,
    onClick: () -> Unit,
) {
    val palette = rememberActiveOneUiPalette()
    val alpha = if (enabled) 1f else 0.42f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color(palette.text),
            )
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    fontSize = 12.5.sp,
                    color = Color(palette.subText),
                    lineHeight = 16.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = Color(palette.accentDeep),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            imageVector = MiuixIcons.Regular.ChevronForward,
            contentDescription = null,
            tint = Color(palette.subText).copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 封装 OneUI_Kit 原生 [BoSwitchView] 供 Compose 界面直接使用。
 */
@Composable
internal fun OneUiSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val widgets = rememberActiveOneUiWidgets()
    val latestOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val latestEnabled by rememberUpdatedState(enabled)
    AndroidView(
        modifier = modifier,
        factory = {
            widgets.switchView(checked) { value ->
                if (latestEnabled) {
                    latestOnCheckedChange(value)
                }
            }.apply {
                isEnabled = enabled
            }
        },
        update = { view ->
            widgets.syncSwitch(view)
            view.isEnabled = enabled
            view.setCheckedProgrammatic(checked)
        },
    )
}

@Composable
internal fun OneUiSwitchPreference(
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = rememberActiveOneUiPalette()
    val alpha = if (enabled) 1f else 0.42f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp)
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color(palette.text),
            )
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    fontSize = 12.5.sp,
                    color = Color(palette.subText),
                    lineHeight = 16.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        OneUiSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * 封装 OneUI_Kit 原生 [DragOnlySeekBar]（横向位移增量拖动，避免可滚动页面误触）。
 */
@Composable
internal fun OneUiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    maskColor: Color = Color(rememberActiveOneUiPalette().card),
    modifier: Modifier = Modifier,
) {
    val widgets = rememberActiveOneUiWidgets()
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val intMax = if (steps > 0) (steps + 1) else 1000
    val stepSize = if (steps > 0) 1 else OneUiWidgets.stepFor(intMax)
    val toIntProgress = { v: Float ->
        (((v - valueRange.start) / span).coerceIn(0f, 1f) * intMax).roundToInt().coerceIn(0, intMax)
    }
    val toFloatValue = { progress: Int ->
        (valueRange.start + (progress.toFloat() / intMax.coerceAtLeast(1)) * span)
            .coerceIn(valueRange.start, valueRange.endInclusive)
    }
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestOnFinished by rememberUpdatedState(onValueChangeFinished)
    val latestToFloat by rememberUpdatedState(toFloatValue)
    val surfaceArgb = maskColor.toArgb()

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        factory = {
            widgets.sliderView(
                value = toIntProgress(value),
                max = intMax,
                onLive = { progress -> latestOnChange(latestToFloat(progress)) },
                onCommit = { progress ->
                    latestOnChange(latestToFloat(progress))
                    latestOnFinished()
                },
            ).apply {
                this.isEnabled = enabled
                this.step = stepSize
                this.maskColor = surfaceArgb
            }
        },
        update = { view ->
            widgets.syncSlider(view, surfaceArgb)
            view.isEnabled = enabled
            if (view.max != intMax) view.max = intMax
            view.step = stepSize
            view.onLive = { progress -> latestOnChange(latestToFloat(progress)) }
            view.onCommit = { progress ->
                latestOnChange(latestToFloat(progress))
                latestOnFinished()
            }
            if (!view.isUserDragging) {
                val target = toIntProgress(value)
                if (view.progress != target) {
                    view.progress = target
                }
            }
        },
    )
}

@Composable
internal fun OneUiButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    colors: ButtonColors? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = rememberActiveOneUiPalette()
    val resolvedColors = colors ?: if (primary) {
        ButtonDefaults.buttonColors(
            color = Color(palette.accentDeep),
            disabledColor = Color(palette.track),
            contentColor = Color(palette.onAccent),
            disabledContentColor = Color(palette.subText),
        )
    } else {
        ButtonDefaults.buttonColors(
            color = Color(palette.cardAlt),
            disabledColor = Color(palette.track),
            contentColor = Color(palette.text),
            disabledContentColor = Color(palette.subText),
        )
    }
    top.yukonga.miuix.kmp.basic.Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        cornerRadius = 24.dp,
        colors = resolvedColors,
        content = content,
    )
}

@Composable
internal fun OneUiIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tonal: Boolean = true,
    tint: Color? = null,
) {
    val palette = rememberActiveOneUiPalette()
    val container = if (tonal) Color(palette.cardAlt) else Color.Transparent
    val iconColor = tint ?: Color(palette.text)
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(container, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(21.dp),
            colorFilter = ColorFilter.tint(iconColor),
        )
    }
}

/**
 * One UI 9 分段切换器：胶囊轨道 (`segmentTrack`) + 轻阴影浮起选中块 (`segmentThumb`)。
 */
@Composable
internal fun OneUiSegmentedTabs(
    tabs: List<String>,
    selected: Int,
    scrollable: Boolean = false,
    onSelected: (Int) -> Unit,
) {
    if (tabs.isEmpty()) return
    val palette = rememberActiveOneUiPalette()
    val haptics = LocalHapticFeedback.current
    val currentIndex = selected.coerceIn(0, tabs.lastIndex)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (scrollable) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(palette.segmentTrack), CircleShape)
                    .horizontalScroll(rememberScrollState())
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                tabs.forEachIndexed { index, title ->
                    val isSelected = index == currentIndex
                    val bg by animateColorAsState(
                        targetValue = if (isSelected) Color(palette.segmentThumb) else Color.Transparent,
                        animationSpec = tween(180),
                        label = "oneTabItemBg",
                    )
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .then(
                                if (isSelected) Modifier.shadow(1.dp, CircleShape, clip = false) else Modifier
                            )
                            .clip(CircleShape)
                            .background(bg, CircleShape)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelected(index)
                            }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = title,
                            color = Color(if (isSelected) palette.text else palette.subText),
                            fontSize = 13.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            val density = LocalDensity.current
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(Color(palette.segmentTrack), CircleShape)
                    .padding(3.dp),
            ) {
                val slotWidth = maxWidth / tabs.size
                val slotPx = with(density) { slotWidth.toPx() }
                val animatedOffset by animateDpAsState(
                    targetValue = slotWidth * currentIndex,
                    animationSpec = oneUiSpring(),
                    label = "oneSegmentThumbX",
                )
                Box(
                    Modifier
                        .offset { IntOffset(animatedOffset.roundToPx(), 0) }
                        .width(slotWidth)
                        .fillMaxHeight()
                        .shadow(1.dp, CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(Color(palette.segmentThumb), CircleShape)
                )
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEachIndexed { index, title ->
                        val isSelected = index == currentIndex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelected(index)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = title,
                                color = Color(if (isSelected) palette.text else palette.subText),
                                fontSize = 13.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One UI 9 底部浮动胶囊导航栏：使用 [OneUiPalette] 的 `card` 容器与 `chip`/`accentDeep` 选中高亮。
 */
@Composable
internal fun OneUiNavigationBar(
    tabs: List<HostTab>,
    selectedIndex: MutableIntState,
    showIcons: Boolean,
    showLabels: Boolean,
    isDarkTheme: Boolean,
    tabImageVector: ((Int) -> ImageVector)?,
    tabIconContent: (@Composable (Int, Color) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val view = LocalView.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalAuraOneUiPalette.current ?: remember(context, isDarkTheme) {
        OneUiPalette(context = context, forceDark = isDarkTheme, useMonet = false)
    }
    val tabCount = tabs.size.coerceAtLeast(1)
    val currentSelected = selectedIndex.intValue.coerceIn(0, tabCount - 1)
    val latestTabs by rememberUpdatedState(tabs)
    val latestSelected by rememberUpdatedState(currentSelected)
    val dragAnimation = remember(tabCount) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = currentSelected.toFloat(),
            valueRange = 0f..(tabCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 0.97f,
            onDragStopped = {
                val target = targetValue.roundToInt().coerceIn(0, tabCount - 1)
                animateToValue(target.toFloat())
                if (target != latestSelected) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    latestTabs[target].onClick()
                }
            },
            onDrag = { size, amount ->
                val slot = size.width.toFloat() / tabCount
                if (slot > 0f) updateValue((targetValue + amount.x / slot).coerceIn(0f, (tabCount - 1).toFloat()))
            },
            awaitTargetBeforeRelease = false,
            followFingerImmediately = true,
            onDragStarted = { size, pos ->
                val slot = size.width.toFloat() / tabCount
                if (slot > 0f) updateValue((pos.x / slot - .5f).coerceIn(0f, (tabCount - 1).toFloat()))
            },
            onTap = { size, pos ->
                val slot = size.width.toFloat() / tabCount
                if (slot > 0f) {
                    val target = (pos.x / slot).toInt().coerceIn(0, tabCount - 1)
                    animateToValue(target.toFloat())
                    if (target != latestSelected) {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        latestTabs[target].onClick()
                    }
                }
            },
            onDragCancelled = { animateToValue(latestSelected.toFloat()) },
        )
    }
    LaunchedEffect(dragAnimation, currentSelected) {
        if (!dragAnimation.isDragging) dragAnimation.animateToValue(currentSelected.toFloat())
    }
    val containerColor = Color(palette.card)
    val pillColor = Color(palette.chip)
    val activeContent = Color(palette.accentDeep)
    val inactiveContent = Color(palette.subText)

    BoxWithConstraints(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (palette.dark) .52f else .14f),
                spotColor = Color.Black.copy(alpha = if (palette.dark) .52f else .14f),
            )
            .clip(CircleShape)
            .background(containerColor, CircleShape)
            .border(BorderStroke(0.75.dp, Color(palette.divider)), CircleShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        val innerPadding = 4.dp
        val innerWidthPx = with(density) { (maxWidth - innerPadding * 2).toPx().coerceAtLeast(0f) }
        val tabWidthPx = innerWidthPx / tabCount
        val tabWidth = with(density) { tabWidthPx.toDp() }
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Box(
                Modifier
                    .width(tabWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = dragAnimation.value * tabWidthPx
                        scaleX = dragAnimation.scaleX
                        scaleY = dragAnimation.scaleY
                    }
                    .clip(CircleShape)
                    .background(pillColor, CircleShape)
            )
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == currentSelected
                    val contentColor = if (selected) activeContent else inactiveContent
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(interactionSource = null, indication = null, role = Role.Tab) {
                                if (index != latestSelected) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    dragAnimation.animateToValue(index.toFloat())
                                    latestTabs[index].onClick()
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (showIcons || tab.iconOnly) {
                            when {
                                tabImageVector != null -> Image(
                                    imageVector = tabImageVector(index),
                                    contentDescription = tab.label,
                                    colorFilter = ColorFilter.tint(contentColor),
                                    modifier = Modifier.size(if (showLabels && !tab.iconOnly) 21.dp else 24.dp),
                                )
                                tabIconContent != null -> tabIconContent(index, contentColor)
                            }
                        }
                        if (showLabels && !tab.iconOnly) {
                            Spacer(Modifier.height(1.dp))
                            BasicText(
                                text = tab.label,
                                maxLines = 1,
                                style = TextStyle(
                                    color = contentColor,
                                    fontSize = if (showIcons) 11.sp else 13.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                ),
                            )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxSize().then(dragAnimation.modifier))
        }
    }
}

@Composable
internal fun OneUiSearchButton(
    size: Dp,
    primary: Boolean,
    closeProgress: Float,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    motionAlpha: Float = 1f,
    onClick: () -> Unit,
) {
    val palette = rememberActiveOneUiPalette()
    val containerColor = if (primary) Color(palette.accentDeep) else Color(palette.card)
    val contentColor = if (primary) Color(palette.onAccent) else Color(palette.text)
    Box(
        modifier = modifier
            .requiredSize(size)
            .graphicsLayer { alpha = motionAlpha }
            .shadow(
                elevation = 6.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (palette.dark) .46f else .14f),
                spotColor = Color.Black.copy(alpha = if (palette.dark) .46f else .14f),
            )
            .clip(CircleShape)
            .background(containerColor, CircleShape)
            .then(
                if (!primary) Modifier.border(0.75.dp, Color(palette.divider), CircleShape) else Modifier
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon ?: MiuixIcons.Regular.Search,
            if (closeProgress < .5f) tr("category_search_open", "搜索音乐") else null,
            Modifier.size(22.dp).graphicsLayer { alpha = 1f - closeProgress },
            tint = contentColor,
        )
        Icon(
            MiuixIcons.Regular.Close,
            if (closeProgress >= .5f) tr("category_search_close", "关闭搜索") else null,
            Modifier.size(22.dp).graphicsLayer { alpha = closeProgress },
            tint = contentColor,
        )
    }
}

@Composable
internal fun OneUiCheckbox(
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberActiveOneUiPalette()
    val haptics = LocalHapticFeedback.current
    val fill by animateColorAsState(
        targetValue = if (checked) Color(palette.accentDeep) else Color.Transparent,
        animationSpec = tween(160),
        label = "oneCheckFill",
    )
    val border by animateColorAsState(
        targetValue = if (checked) Color(palette.accentDeep) else Color(palette.subText),
        animationSpec = tween(160),
        label = "oneCheckBorder",
    )
    val scale by animateFloatAsState(
        targetValue = if (checked) 1f else 0.94f,
        animationSpec = oneUiSpring(),
        label = "oneCheckScale",
    )
    val alpha = if (enabled) 1f else .38f
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Checkbox) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .alpha(alpha)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(fill)
                .border(BorderStroke(1.8.dp, border), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Canvas(Modifier.size(13.dp)) {
                    val stroke = 2.dp.toPx()
                    drawLine(
                        color = Color(palette.onAccent),
                        start = Offset(size.width * .20f, size.height * .52f),
                        end = Offset(size.width * .43f, size.height * .75f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = Color(palette.onAccent),
                        start = Offset(size.width * .43f, size.height * .75f),
                        end = Offset(size.width * .82f, size.height * .28f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
internal fun OneUiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    useLabelAsPlaceholder: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    cornerRadius: Dp = 16.dp,
) {
    val palette = rememberActiveOneUiPalette()
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(cornerRadius)
    val borderColor by animateColorAsState(
        targetValue = if (focused) Color(palette.accentDeep) else Color.Transparent,
        animationSpec = tween(160),
        label = "oneFieldBorder",
    )
    val showTopLabel = label.isNotBlank() && (!useLabelAsPlaceholder || value.isNotEmpty() || focused)
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 52.dp)
            .clip(shape)
            .background(Color(palette.cardAlt), shape)
            .border(BorderStroke(1.5.dp, borderColor), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (showTopLabel) {
            Text(
                text = label,
                color = Color(if (focused) palette.accentDeep else palette.subText),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            interactionSource = interactionSource,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            textStyle = TextStyle(
                color = if (enabled) Color(palette.text) else Color(palette.subText),
                fontSize = 15.5.sp,
            ),
            cursorBrush = SolidColor(Color(palette.accentDeep)),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && !showTopLabel && label.isNotBlank()) {
                        Text(
                            text = label,
                            color = Color(palette.subText),
                            fontSize = 15.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
    }
}
