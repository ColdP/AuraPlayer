// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.auraplayer.ui.navigation.DampedDragAnimation
import btm.m.auraplayer.ui.navigation.HostTab
import btm.m.auraplayer.ui.navigation.NavigationScaleSettings
import btm.m.auraplayer.ui.player.PlayerExpressiveColors
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeExpressive
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import kotlin.math.roundToInt

enum class AppUiStyle { HYPER, MATERIAL, ONE }

val LocalAppUiStyle = staticCompositionLocalOf { AppUiStyle.HYPER }

@Immutable
internal data class AuraExpressiveScheme(
    val dark: Boolean,
    val surface: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val outline: Color,
    val outlineVariant: Color,
) {
    fun toPlayerExpressiveColors(): PlayerExpressiveColors = PlayerExpressiveColors(
        dark = dark,
        surface = surface,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        onSurface = onSurface,
        onSurfaceVariant = onSurfaceVariant,
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        outlineVariant = outlineVariant,
        activeInk = onSurface,
        secondaryInk = lerp(onSurfaceVariant, primary, if (dark) .40f else .32f),
        timeInk = lerp(onSurfaceVariant, primary, .24f),
        iconInk = lerp(onSurfaceVariant, secondary, .28f),
        dragHandleColor = lerp(onSurfaceVariant, primary, .35f).copy(alpha = if (dark) .70f else .56f),
    )
}

internal val LocalAuraExpressiveScheme = staticCompositionLocalOf<AuraExpressiveScheme?> { null }

internal fun <T> m3ExpressiveSpatialSpring() = spring<T>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

internal fun <T> m3ExpressiveEffectsSpring() = spring<T>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

internal const val DEFAULT_MATERIAL_EXPRESSIVE_SEED: Int = 0xFF6750A4.toInt()

@Composable
internal fun rememberAuraExpressiveScheme(seed: Int, isDark: Boolean): AuraExpressiveScheme {
    val target = remember(seed, isDark) {
        val scheme = SchemeExpressive(Hct.fromInt(seed), isDark, 0.0, ColorSpec.SpecVersion.SPEC_2025)
        AuraExpressiveScheme(
            dark = isDark,
            surface = Color(scheme.surface),
            surfaceContainerLowest = Color(scheme.surfaceContainerLowest),
            surfaceContainerLow = Color(scheme.surfaceContainerLow),
            surfaceContainer = Color(scheme.surfaceContainer),
            surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
            surfaceContainerHighest = Color(scheme.surfaceContainerHighest),
            onSurface = Color(scheme.onSurface),
            onSurfaceVariant = Color(scheme.onSurfaceVariant),
            primary = Color(scheme.primary),
            onPrimary = Color(scheme.onPrimary),
            primaryContainer = Color(scheme.primaryContainer),
            onPrimaryContainer = Color(scheme.onPrimaryContainer),
            secondary = Color(scheme.secondary),
            onSecondary = Color(scheme.onSecondary),
            secondaryContainer = Color(scheme.secondaryContainer),
            onSecondaryContainer = Color(scheme.onSecondaryContainer),
            tertiary = Color(scheme.tertiary),
            onTertiary = Color(scheme.onTertiary),
            tertiaryContainer = Color(scheme.tertiaryContainer),
            onTertiaryContainer = Color(scheme.onTertiaryContainer),
            error = Color(scheme.error),
            onError = Color(scheme.onError),
            errorContainer = Color(scheme.errorContainer),
            onErrorContainer = Color(scheme.onErrorContainer),
            outline = Color(scheme.outline),
            outlineVariant = Color(scheme.outlineVariant),
        )
    }
    val spec = tween<Color>(380, easing = FastOutSlowInEasing)
    return AuraExpressiveScheme(
        dark = target.dark,
        surface = animateColorAsState(target.surface, spec, label = "m3eSurface").value,
        surfaceContainerLowest = animateColorAsState(target.surfaceContainerLowest, spec, label = "m3eSurfaceLowest").value,
        surfaceContainerLow = animateColorAsState(target.surfaceContainerLow, spec, label = "m3eSurfaceLow").value,
        surfaceContainer = animateColorAsState(target.surfaceContainer, spec, label = "m3eSurfaceCont").value,
        surfaceContainerHigh = animateColorAsState(target.surfaceContainerHigh, spec, label = "m3eSurfaceHigh").value,
        surfaceContainerHighest = animateColorAsState(target.surfaceContainerHighest, spec, label = "m3eSurfaceHighest").value,
        onSurface = animateColorAsState(target.onSurface, spec, label = "m3eOnSurface").value,
        onSurfaceVariant = animateColorAsState(target.onSurfaceVariant, spec, label = "m3eOnSurfaceVar").value,
        primary = animateColorAsState(target.primary, spec, label = "m3ePrimary").value,
        onPrimary = animateColorAsState(target.onPrimary, spec, label = "m3eOnPrimary").value,
        primaryContainer = animateColorAsState(target.primaryContainer, spec, label = "m3ePrimaryCont").value,
        onPrimaryContainer = animateColorAsState(target.onPrimaryContainer, spec, label = "m3eOnPrimaryCont").value,
        secondary = animateColorAsState(target.secondary, spec, label = "m3eSecondary").value,
        onSecondary = animateColorAsState(target.onSecondary, spec, label = "m3eOnSecondary").value,
        secondaryContainer = animateColorAsState(target.secondaryContainer, spec, label = "m3eSecondaryCont").value,
        onSecondaryContainer = animateColorAsState(target.onSecondaryContainer, spec, label = "m3eOnSecondaryCont").value,
        tertiary = animateColorAsState(target.tertiary, spec, label = "m3eTertiary").value,
        onTertiary = animateColorAsState(target.onTertiary, spec, label = "m3eOnTertiary").value,
        tertiaryContainer = animateColorAsState(target.tertiaryContainer, spec, label = "m3eTertiaryCont").value,
        onTertiaryContainer = animateColorAsState(target.onTertiaryContainer, spec, label = "m3eOnTertiaryCont").value,
        error = animateColorAsState(target.error, spec, label = "m3eError").value,
        onError = animateColorAsState(target.onError, spec, label = "m3eOnError").value,
        errorContainer = animateColorAsState(target.errorContainer, spec, label = "m3eErrorCont").value,
        onErrorContainer = animateColorAsState(target.onErrorContainer, spec, label = "m3eOnErrorCont").value,
        outline = animateColorAsState(target.outline, spec, label = "m3eOutline").value,
        outlineVariant = animateColorAsState(target.outlineVariant, spec, label = "m3eOutlineVar").value,
    )
}

internal fun Colors.withExpressiveColors(e: AuraExpressiveScheme): Colors = copy(
    primary = e.primary,
    onPrimary = e.onPrimary,
    primaryVariant = e.primaryContainer,
    onPrimaryVariant = e.onPrimaryContainer,
    error = e.error,
    onError = e.onError,
    errorContainer = e.errorContainer,
    onErrorContainer = e.onErrorContainer,
    disabledPrimary = e.onSurface.copy(alpha = 0.12f),
    disabledOnPrimary = e.onSurface.copy(alpha = 0.38f),
    disabledPrimaryButton = e.onSurface.copy(alpha = 0.12f),
    disabledOnPrimaryButton = e.onSurface.copy(alpha = 0.38f),
    disabledPrimarySlider = e.onSurface.copy(alpha = 0.38f),
    primaryContainer = e.primaryContainer,
    onPrimaryContainer = e.onPrimaryContainer,
    secondary = e.secondary,
    onSecondary = e.onSecondary,
    secondaryVariant = e.secondaryContainer,
    onSecondaryVariant = e.onSecondaryContainer,
    secondaryContainer = e.secondaryContainer,
    onSecondaryContainer = e.onSecondaryContainer,
    secondaryContainerVariant = e.secondaryContainer,
    onSecondaryContainerVariant = e.onSecondaryContainer,
    tertiaryContainer = e.tertiaryContainer,
    onTertiaryContainer = e.onTertiaryContainer,
    tertiaryContainerVariant = e.tertiaryContainer,
    background = e.surface,
    onBackground = e.onSurface,
    onBackgroundVariant = e.onSurfaceVariant,
    surface = e.surface,
    onSurface = e.onSurface,
    surfaceVariant = e.surfaceContainerHigh,
    onSurfaceSecondary = e.onSurfaceVariant,
    onSurfaceVariantSummary = e.onSurfaceVariant,
    onSurfaceVariantActions = e.onSurfaceVariant,
    disabledOnSurface = e.onSurface.copy(alpha = 0.38f),
    surfaceContainer = e.surfaceContainer,
    onSurfaceContainer = e.onSurface,
    onSurfaceContainerVariant = e.onSurfaceVariant,
    surfaceContainerHigh = e.surfaceContainerHigh,
    onSurfaceContainerHigh = e.onSurface,
    surfaceContainerHighest = e.surfaceContainerHighest,
    onSurfaceContainerHighest = e.onSurface,
    outline = e.outline,
    dividerLine = e.outlineVariant.copy(alpha = 0.55f),
    sliderKeyPoint = e.primary.copy(alpha = 0.35f),
    sliderKeyPointForeground = e.primary,
    sliderBackground = e.secondaryContainer,
)

@Composable
internal fun AuraUiTheme(
    themeMode: String,
    uiStyle: String,
    monet: Boolean,
    seed: Int?,
    useSystemSeed: Boolean = false,
    controller: ThemeController,
    textStyles: TextStyles,
    content: @Composable () -> Unit,
) {
    val style = when (uiStyle) {
        "material" -> AppUiStyle.MATERIAL
        "one" -> AppUiStyle.ONE
        else -> AppUiStyle.HYPER
    }
    val isDark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val expressiveSeed = if (monet && seed != null) seed else DEFAULT_MATERIAL_EXPRESSIVE_SEED
    val expressiveScheme = rememberAuraExpressiveScheme(expressiveSeed, isDark)
    when (style) {
        AppUiStyle.MATERIAL -> {
            val base = if (isDark) darkColorScheme() else lightColorScheme()
            val mapped = base.withExpressiveColors(expressiveScheme)
            MiuixTheme(colors = mapped, textStyles = textStyles) {
                CompositionLocalProvider(
                    LocalAppUiStyle provides AppUiStyle.MATERIAL,
                    LocalAuraExpressiveScheme provides expressiveScheme,
                    content = content,
                )
            }
        }
        AppUiStyle.ONE -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            val onePalette = rememberOneUiPalette(
                isDark = isDark,
                useMonet = monet,
                customSeed = if (useSystemSeed) null else seed,
            )
            val oneWidgets = remember(context, onePalette) {
                dev.pascal.oneuikit.OneUiWidgets(context, onePalette)
            }
            val base = if (isDark) darkColorScheme() else lightColorScheme()
            val mapped = base.withOneUiColors(onePalette)
            MiuixTheme(colors = mapped, textStyles = textStyles) {
                CompositionLocalProvider(
                    LocalAppUiStyle provides AppUiStyle.ONE,
                    LocalAuraOneUiPalette provides onePalette,
                    LocalAuraOneUiWidgets provides oneWidgets,
                    LocalAuraExpressiveScheme provides onePalette.toPlayerExpressiveColors().let { p ->
                        expressiveScheme.copy(
                            dark = p.dark,
                            surface = p.surface,
                            surfaceContainerLowest = p.surfaceContainerLow,
                            surfaceContainerLow = p.surfaceContainerLow,
                            surfaceContainer = p.surfaceContainer,
                            surfaceContainerHigh = p.surfaceContainerHigh,
                            surfaceContainerHighest = p.surfaceContainerHighest,
                            onSurface = p.onSurface,
                            onSurfaceVariant = p.onSurfaceVariant,
                            primary = p.primary,
                            onPrimary = p.onPrimary,
                            primaryContainer = p.primaryContainer,
                            onPrimaryContainer = p.onPrimaryContainer,
                            secondary = p.secondary,
                            secondaryContainer = p.secondaryContainer,
                            onSecondaryContainer = p.onSecondaryContainer,
                            tertiaryContainer = p.tertiaryContainer,
                            onTertiaryContainer = p.onTertiaryContainer,
                            outlineVariant = p.outlineVariant,
                        )
                    },
                    content = content,
                )
            }
        }
        AppUiStyle.HYPER -> {
            MiuixTheme(controller = controller, textStyles = textStyles) {
                CompositionLocalProvider(
                    LocalAppUiStyle provides AppUiStyle.HYPER,
                    LocalAuraExpressiveScheme provides expressiveScheme,
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun rememberActiveExpressiveScheme(): AuraExpressiveScheme {
    LocalAuraExpressiveScheme.current?.let { return it }
    val isDark = MiuixTheme.colorScheme.surface.luminance() < .5f
    return rememberAuraExpressiveScheme(DEFAULT_MATERIAL_EXPRESSIVE_SEED, isDark)
}

private object ExpressiveColumnScope : ColumnScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier = this
    override fun Modifier.align(alignment: Alignment.Horizontal): Modifier = this
    override fun Modifier.alignBy(alignmentLine: VerticalAlignmentLine): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = this
}

@Composable
internal fun MaterialExpressiveSegmentedColumn(
    modifier: Modifier = Modifier,
    containerColor: Color = rememberActiveExpressiveScheme().surfaceContainer,
    gap: Dp = 2.5.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val gapPx = with(LocalDensity.current) { gap.roundToPx() }
    val itemRecords = remember { ArrayList<Triple<Int, Int, RoundedCornerShape>>() }
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val w = size.width
                for ((y, h, shape) in itemRecords) {
                    val outline = shape.createOutline(Size(w, h.toFloat()), layoutDirection, this)
                    withTransform({ translate(top = y.toFloat()) }) {
                        drawOutline(outline, color = containerColor)
                    }
                }
            },
        content = { ExpressiveColumnScope.content() },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val visibleIndices = ArrayList<Int>(placeables.size)
        for (i in placeables.indices) {
            if (placeables[i].height > 0) visibleIndices.add(i)
        }
        itemRecords.clear()
        val totalHeight = if (visibleIndices.isEmpty()) 0 else {
            visibleIndices.sumOf { placeables[it].height } + gapPx * (visibleIndices.size - 1)
        }
        val width = (placeables.maxOfOrNull { it.width } ?: constraints.minWidth)
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        var y = 0
        val planned = ArrayList<Pair<Placeable, Pair<Int, RoundedCornerShape>>>(visibleIndices.size)
        visibleIndices.forEachIndexed { vIndex, idx ->
            val p = placeables[idx]
            val shape = when {
                visibleIndices.size == 1 -> RoundedCornerShape(22.dp)
                vIndex == 0 -> RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
                vIndex == visibleIndices.lastIndex -> RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 22.dp, bottomEnd = 22.dp)
                else -> RoundedCornerShape(6.dp)
            }
            itemRecords.add(Triple(y, p.height, shape))
            planned.add(p to (y to shape))
            y += p.height + gapPx
        }
        layout(width, totalHeight) {
            for ((p, placement) in planned) {
                val (itemY, shape) = placement
                p.placeRelativeWithLayer(0, itemY) {
                    clip = true
                    this.shape = shape
                }
            }
        }
    }
}

@Composable
internal fun MaterialExpressiveGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = rememberActiveExpressiveScheme()
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = scheme.primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 8.dp),
        )
        MaterialExpressiveSegmentedColumn(
            containerColor = scheme.surfaceContainer,
            content = content,
        )
    }
}

@Composable
internal fun MaterialExpressivePreferenceRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = {
        val scheme = rememberActiveExpressiveScheme()
        Image(
            imageVector = MiuixIcons.Basic.ArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(scheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f)),
        )
    },
) {
    val scheme = rememberActiveExpressiveScheme()
    val alpha = if (enabled) 1f else .38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = scheme.onSurface.copy(alpha = alpha),
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    color = scheme.onSurfaceVariant.copy(alpha = alpha),
                    fontSize = 13.sp,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
internal fun MaterialExpressiveSwitch(
    checked: Boolean,
    enabled: Boolean = true,
    pressed: Boolean = false,
) {
    val scheme = rememberActiveExpressiveScheme()
    val alpha = if (enabled) 1f else .38f
    val trackColor by animateColorAsState(
        targetValue = if (checked) scheme.primary else scheme.surfaceContainerHighest,
        animationSpec = m3ExpressiveEffectsSpring(),
        label = "m3eSwitchTrack",
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) Color.Transparent else scheme.outline,
        animationSpec = m3ExpressiveEffectsSpring(),
        label = "m3eSwitchBorder",
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) scheme.onPrimary else scheme.outline,
        animationSpec = m3ExpressiveEffectsSpring(),
        label = "m3eSwitchThumbColor",
    )
    val thumbProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = m3ExpressiveSpatialSpring(),
        label = "m3eSwitchProgress",
    )
    val thumbSize by animateDpAsState(
        targetValue = when {
            pressed -> 28.dp
            checked -> 24.dp
            else -> 16.dp
        },
        animationSpec = m3ExpressiveSpatialSpring(),
        label = "m3eSwitchThumbSize",
    )
    val trackWidth = 52.dp
    val trackHeight = 32.dp
    Box(
        modifier = Modifier
            .size(trackWidth, trackHeight)
            .alpha(alpha)
            .clip(CircleShape)
            .background(trackColor)
            .then(
                if (checked) Modifier else Modifier.border(2.dp, borderColor, CircleShape)
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val minOffsetPx = with(density) { (16.dp - thumbSize / 2).toPx() }
        val maxOffsetPx = with(density) { (trackWidth - 16.dp - thumbSize / 2).toPx() }
        val offsetPx = minOffsetPx + (maxOffsetPx - minOffsetPx) * thumbProgress
        Box(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(offsetPx.roundToInt(), 0) }
                .size(thumbSize)
                .clip(CircleShape)
                .background(thumbColor),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Canvas(Modifier.size(14.dp)) {
                    val stroke = 2.dp.toPx()
                    drawLine(
                        color = scheme.primary,
                        start = Offset(size.width * .18f, size.height * .52f),
                        end = Offset(size.width * .42f, size.height * .76f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = scheme.primary,
                        start = Offset(size.width * .42f, size.height * .76f),
                        end = Offset(size.width * .84f, size.height * .26f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
internal fun MaterialExpressiveSwitchPreference(
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val scheme = rememberActiveExpressiveScheme()
    val alpha = if (enabled) 1f else .38f
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.foundation.LocalIndication.current,
                enabled = enabled,
                role = Role.Switch,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCheckedChange(!checked)
                },
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = scheme.onSurface.copy(alpha = alpha),
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    color = scheme.onSurfaceVariant.copy(alpha = alpha),
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        MaterialExpressiveSwitch(checked = checked, enabled = enabled, pressed = pressed)
    }
}

@Composable
internal fun MaterialExpressiveSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val scheme = rememberActiveExpressiveScheme()
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val normalized = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(normalized) }
    val onChangeState by rememberUpdatedState(onValueChange)
    val onFinishedState by rememberUpdatedState(onValueChangeFinished)
    val displayed = if (dragging) dragFraction else normalized
    val animatedHandleWidth by animateDpAsState(
        targetValue = if (dragging) 2.5.dp else 4.dp,
        animationSpec = m3ExpressiveSpatialSpring(),
        label = "m3eSliderHandle",
    )

    fun mapFraction(fraction: Float): Float {
        val clamped = fraction.coerceIn(0f, 1f)
        val raw = valueRange.start + clamped * span
        if (steps <= 0) return raw
        val interval = span / (steps + 1)
        if (interval <= 0f) return raw
        return (valueRange.start + ((raw - valueRange.start) / interval).roundToInt() * interval)
            .coerceIn(valueRange.start, valueRange.endInclusive)
    }

    val activeColor = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = .38f)
    val inactiveColor = if (enabled) scheme.secondaryContainer else scheme.onSurface.copy(alpha = .12f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics {
                if (!enabled) disabled()
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                setProgress { target ->
                    onChangeState(target.coerceIn(valueRange.start, valueRange.endInclusive))
                    onFinishedState()
                    true
                }
            }
            .pointerInput(enabled, valueRange, steps) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    dragging = true
                    dragFraction = (down.position.x / w).coerceIn(0f, 1f)
                    onChangeState(mapFraction(dragFraction))
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        change.consume()
                        dragFraction = (change.position.x / w).coerceIn(0f, 1f)
                        onChangeState(mapFraction(dragFraction))
                    }
                    dragging = false
                    onFinishedState()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val trackHeight = 14.dp.toPx()
            val thumbW = animatedHandleWidth.toPx()
            val thumbH = 34.dp.toPx()
            val gap = 5.dp.toPx()
            val thumbX = (displayed * size.width).coerceIn(thumbW / 2f, (size.width - thumbW / 2f).coerceAtLeast(thumbW / 2f))
            val activeEnd = (thumbX - thumbW / 2f - gap).coerceAtLeast(0f)
            val inactiveStart = (thumbX + thumbW / 2f + gap).coerceAtMost(size.width)
            if (activeEnd > 2f) {
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(0f, cy - trackHeight / 2f),
                    size = Size(activeEnd, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f),
                )
            }
            val inactiveW = (size.width - inactiveStart).coerceAtLeast(0f)
            if (inactiveW > 2f) {
                drawRoundRect(
                    color = inactiveColor,
                    topLeft = Offset(inactiveStart, cy - trackHeight / 2f),
                    size = Size(inactiveW, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f),
                )
                // Expressive track end stop indicator dot
                if (inactiveW > trackHeight) {
                    drawCircle(
                        color = activeColor,
                        radius = 2.dp.toPx(),
                        center = Offset(size.width - trackHeight / 2f, cy),
                    )
                }
            }
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(thumbX - thumbW / 2f, cy - thumbH / 2f),
                size = Size(thumbW, thumbH),
                cornerRadius = CornerRadius(thumbW / 2f),
            )
        }
    }
}

@Composable
internal fun MaterialExpressiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    colors: ButtonColors? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = rememberActiveExpressiveScheme()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) 12.dp else 28.dp,
        animationSpec = m3ExpressiveSpatialSpring(),
        label = "m3eBtnCorner",
    )
    val defaultColors = colors ?: if (primary) {
        ButtonDefaults.buttonColors(
            color = scheme.primary,
            disabledColor = scheme.onSurface.copy(alpha = 0.12f),
            contentColor = scheme.onPrimary,
            disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
        )
    } else {
        ButtonDefaults.buttonColors(
            color = scheme.secondaryContainer,
            disabledColor = scheme.onSurface.copy(alpha = 0.12f),
            contentColor = scheme.onSecondaryContainer,
            disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
        )
    }
    top.yukonga.miuix.kmp.basic.Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        cornerRadius = corner,
        colors = defaultColors,
        content = content,
    )
}

@Composable
internal fun MaterialExpressiveIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tonal: Boolean = true,
    tint: Color? = null,
) {
    val scheme = rememberActiveExpressiveScheme()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) 12.dp else 21.dp,
        animationSpec = m3ExpressiveSpatialSpring(),
        label = "m3eIconBtnCorner",
    )
    val shape = RoundedCornerShape(corner)
    val container = if (tonal) scheme.surfaceContainerHigh else Color.Transparent
    val iconColor = tint ?: scheme.onSurface
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(shape)
            .background(container, shape)
            .clickable(interactionSource = interactionSource, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick),
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

@Composable
internal fun MaterialExpressiveSegmentedTabs(
    tabs: List<String>,
    selected: Int,
    scrollable: Boolean = false,
    onSelected: (Int) -> Unit,
) {
    if (tabs.isEmpty()) return
    val scheme = rememberActiveExpressiveScheme()
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val scrollModifier = if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()
        Row(
            modifier = Modifier
                .then(scrollModifier)
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, label ->
                val isSelected = index == selected
                val interactionSource = remember { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) scheme.primary else Color.Transparent,
                    animationSpec = m3ExpressiveEffectsSpring(),
                    label = "m3eTabBg",
                )
                val fgColor by animateColorAsState(
                    targetValue = if (isSelected) scheme.onPrimary else scheme.onSurfaceVariant,
                    animationSpec = m3ExpressiveEffectsSpring(),
                    label = "m3eTabFg",
                )
                val itemCorner by animateDpAsState(
                    targetValue = if (pressed) 12.dp else 999.dp,
                    animationSpec = m3ExpressiveSpatialSpring(),
                    label = "m3eTabCorner",
                )
                Box(
                    modifier = (if (scrollable) Modifier else Modifier.weight(1f))
                        .clip(RoundedCornerShape(itemCorner))
                        .background(bgColor)
                        .clickable(interactionSource = interactionSource, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Tab) {
                            if (!isSelected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelected(index)
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = fgColor,
                        fontSize = 13.5.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun MaterialExpressiveNavigationBar(
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
    val scope = rememberCoroutineScope()
    val scheme = rememberAuraExpressiveScheme(DEFAULT_MATERIAL_EXPRESSIVE_SEED, isDarkTheme).let {
        LocalAuraExpressiveScheme.current ?: it
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
            pressedScale = 0.96f,
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
    val containerColor by animateColorAsState(scheme.surfaceContainerHigh, m3ExpressiveEffectsSpring(), label = "m3eNavBg")
    val pillColor by animateColorAsState(scheme.secondaryContainer, m3ExpressiveEffectsSpring(), label = "m3eNavPill")
    val activeContent by animateColorAsState(scheme.onSecondaryContainer, m3ExpressiveEffectsSpring(), label = "m3eNavActive")
    val inactiveContent by animateColorAsState(scheme.onSurfaceVariant, m3ExpressiveEffectsSpring(), label = "m3eNavInactive")

    BoxWithConstraints(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (isDarkTheme) .45f else .18f),
                spotColor = Color.Black.copy(alpha = if (isDarkTheme) .45f else .18f),
            )
            .clip(CircleShape)
            .background(containerColor, CircleShape)
            .border(BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = if (isDarkTheme) .35f else .55f)), CircleShape),
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
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1.08f else 0.96f,
                        animationSpec = m3ExpressiveSpatialSpring(),
                        label = "m3eNavIconScale",
                    )
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
                            Box(Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale }) {
                                when {
                                    tabImageVector != null -> Image(
                                        imageVector = tabImageVector(index),
                                        contentDescription = tab.label,
                                        colorFilter = ColorFilter.tint(contentColor),
                                        modifier = Modifier.size(if (showLabels && !tab.iconOnly) 22.dp else 25.dp),
                                    )
                                    tabIconContent != null -> tabIconContent(index, contentColor)
                                }
                            }
                        }
                        if (showLabels && !tab.iconOnly) {
                            Spacer(Modifier.height(1.dp))
                            BasicText(
                                text = tab.label,
                                maxLines = 1,
                                style = TextStyle(
                                    color = contentColor,
                                    fontSize = if (showIcons) 11.sp else 13.5.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
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
internal fun MaterialExpressiveSearchButton(
    size: Dp,
    primary: Boolean,
    closeProgress: Float,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    motionAlpha: Float = 1f,
    onClick: () -> Unit,
) {
    val isDark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val scheme = LocalAuraExpressiveScheme.current ?: rememberAuraExpressiveScheme(DEFAULT_MATERIAL_EXPRESSIVE_SEED, isDark)
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) 16.dp else size / 2f,
        animationSpec = m3ExpressiveSpatialSpring(),
        label = "m3eSearchFabCorner",
    )
    val containerColor = if (primary) scheme.primary else scheme.primaryContainer
    val contentColor = if (primary) scheme.onPrimary else scheme.onPrimaryContainer
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .requiredSize(size)
            .graphicsLayer { alpha = motionAlpha }
            .shadow(
                elevation = 6.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (isDark) .42f else .18f),
                spotColor = Color.Black.copy(alpha = if (isDark) .42f else .18f),
            )
            .clip(shape)
            .background(containerColor, shape)
            .clickable(enabled = enabled, interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon ?: MiuixIcons.Regular.Search,
            if (closeProgress < .5f) tr("category_search_open", "搜索音乐") else null,
            Modifier.size(23.dp).graphicsLayer { alpha = 1f - closeProgress },
            tint = contentColor,
        )
        Icon(
            MiuixIcons.Regular.Close,
            if (closeProgress >= .5f) tr("category_search_close", "关闭搜索") else null,
            Modifier.size(23.dp).graphicsLayer { alpha = closeProgress },
            tint = contentColor,
        )
    }
}

@Composable
internal fun AuraCheckbox(
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (LocalAppUiStyle.current) {
        AppUiStyle.ONE -> {
            OneUiCheckbox(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange, modifier = modifier)
            return
        }
        AppUiStyle.HYPER -> {
            Checkbox(
                state = ToggleableState(checked),
                enabled = enabled,
                onClick = onCheckedChange,
                modifier = modifier,
            )
            return
        }
        AppUiStyle.MATERIAL -> Unit
    }
    val scheme = rememberActiveExpressiveScheme()
    val haptics = LocalHapticFeedback.current
    val fill by animateColorAsState(
        targetValue = if (checked) scheme.primary else Color.Transparent,
        animationSpec = m3ExpressiveEffectsSpring(),
        label = "m3eCheckFill",
    )
    val border by animateColorAsState(
        targetValue = if (checked) scheme.primary else scheme.onSurfaceVariant,
        animationSpec = m3ExpressiveEffectsSpring(),
        label = "m3eCheckBorder",
    )
    val scale by animateFloatAsState(
        targetValue = if (checked) 1f else 0.94f,
        animationSpec = m3ExpressiveSpatialSpring(),
        label = "m3eCheckScale",
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
                .size(20.dp)
                .alpha(alpha)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(5.dp))
                .background(fill)
                .border(BorderStroke(2.dp, border), RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Canvas(Modifier.size(13.dp)) {
                    val stroke = 2.dp.toPx()
                    drawLine(
                        color = scheme.onPrimary,
                        start = Offset(size.width * .18f, size.height * .52f),
                        end = Offset(size.width * .42f, size.height * .76f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = scheme.onPrimary,
                        start = Offset(size.width * .42f, size.height * .76f),
                        end = Offset(size.width * .84f, size.height * .26f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
internal fun AuraTextField(
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
    when (LocalAppUiStyle.current) {
        AppUiStyle.ONE -> {
            OneUiTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = modifier,
                label = label,
                useLabelAsPlaceholder = useLabelAsPlaceholder,
                enabled = enabled,
                singleLine = singleLine,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                cornerRadius = cornerRadius,
            )
            return
        }
        AppUiStyle.HYPER -> {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = modifier,
                label = label,
                useLabelAsPlaceholder = useLabelAsPlaceholder,
                enabled = enabled,
                singleLine = singleLine,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                cornerRadius = cornerRadius,
            )
            return
        }
        AppUiStyle.MATERIAL -> Unit
    }
    val scheme = rememberActiveExpressiveScheme()
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(cornerRadius.coerceAtMost(28.dp))
    val borderColor by animateColorAsState(
        targetValue = if (focused) scheme.primary else scheme.outlineVariant.copy(alpha = 0.7f),
        animationSpec = m3ExpressiveEffectsSpring(),
        label = "m3eFieldBorder",
    )
    val bgColor by animateColorAsState(
        targetValue = if (focused) scheme.surfaceContainerHigh else scheme.surfaceContainerHighest,
        animationSpec = m3ExpressiveEffectsSpring(),
        label = "m3eFieldBg",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused) 2.dp else 1.dp,
        animationSpec = m3ExpressiveSpatialSpring(),
        label = "m3eFieldBorderWidth",
    )
    val showTopLabel = label.isNotBlank() && (!useLabelAsPlaceholder || value.isNotEmpty() || focused)
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 52.dp)
            .clip(shape)
            .background(bgColor, shape)
            .border(BorderStroke(borderWidth, borderColor), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (showTopLabel) {
            Text(
                text = label,
                color = if (focused) scheme.primary else scheme.onSurfaceVariant,
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
                color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f),
                fontSize = 15.5.sp,
            ),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && !showTopLabel && label.isNotBlank()) {
                        Text(
                            text = label,
                            color = scheme.onSurfaceVariant,
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
