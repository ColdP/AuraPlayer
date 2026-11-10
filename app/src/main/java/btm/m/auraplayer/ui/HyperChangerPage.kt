// SPDX-License-Identifier: AGPL-3.0-or-later
// Ported directly from HyperChanger MainActivity.kt; Xposed restart actions excluded.
package btm.m.auraplayer.ui
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import btm.m.auraplayer.ui.navigation.*
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.*
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.blur.LayerBackdrop as MiuixLayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop as miuixLayerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop as rememberMiuixLayerBackdrop
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
@Composable
internal fun AppPage(
    title: String,
    onBack: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    compactTopBar: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    floatingToolbarPosition: ToolbarPosition = ToolbarPosition.BottomCenter,
    floatingToolbar: @Composable () -> Unit = {},
    showLargeTitle: Boolean = true,
    onLargeTitleClick: (() -> Unit)? = null,
    pageBackground: (@Composable BoxScope.() -> Unit)? = null,
    header: @Composable () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    coveredBlur: Dp = 0.dp,
    content: @Composable (PaddingValues, ScrollBehavior) -> Unit,
) {
    val scroll = MiuixScrollBehavior()
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop { drawRect(surface); drawContent() }
    val progressiveBackdrop = rememberMiuixLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    val collapsedFraction = scroll.state.collapsedFraction.coerceIn(0f, 1f)
    val topBarMaterialProgress by animateFloatAsState(
        targetValue = collapsedFraction,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "topBarMaterialProgress",
    )
    CompositionLocalProvider(
        LocalToolbarBackdrop provides backdrop,
        LocalToolbarCollapsed provides collapsedFraction,
        LocalDialogBackdrop provides backdrop,
    ) {
    Box(Modifier.fillMaxSize().background(surface)) {
    pageBackground?.let {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { it() }
    }
    Scaffold(
        modifier = Modifier.blur(coveredBlur),
        containerColor = ComposeColor.Transparent,
        floatingToolbar = floatingToolbar,
        floatingToolbarPosition = floatingToolbarPosition,
        topBar = {
            ProgressiveTopBarMaterial(
                backdrop = progressiveBackdrop,
                collapsedFraction = topBarMaterialProgress,
                legacyBackdrop = pageBackground?.let { backdrop },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    if (compactTopBar) {
                        SmallTopAppBar(
                            title = title,
                            color = ComposeColor.Transparent,
                            navigationIcon = {
                                navigationIcon?.invoke() ?: onBack?.let { GlassBackButton(backdrop, 1f, it) }
                            },
                            actions = {
                                actions()
                            },
                        )
                    } else {
                        Box(Modifier.fillMaxWidth()) {
                            TopAppBar(
                                title = "",
                                largeTitle = if (showLargeTitle) title else "",
                                color = ComposeColor.Transparent,
                                scrollBehavior = scroll,
                                navigationIcon = {
                                    navigationIcon?.invoke() ?: onBack?.let { GlassBackButton(backdrop, collapsedFraction, it) }
                                },
                                actions = {
                                actions()
                                },
                            )
                            // Overlay the collapsed title without contributing another row's height.
                            Box(Modifier.matchParentSize()) {
                                if (showLargeTitle && onLargeTitleClick != null && collapsedFraction < 1f / 3f) {
                                    // Restrict taps to the expanded title area, below navigation/actions.
                                    Box(
                                        Modifier.matchParentSize()
                                            .windowInsetsPadding(WindowInsets.statusBars)
                                            .padding(top = TopAppBarDefaults.CollapsedHeight)
                                            .padding(horizontal = TopAppBarDefaults.TitlePadding)
                                            .clickable(
                                                interactionSource = null,
                                                indication = null,
                                                onClickLabel = title,
                                                onClick = onLargeTitleClick,
                                            ),
                                    )
                                }
                                Box(
                                    Modifier.windowInsetsPadding(WindowInsets.statusBars).height(52.dp).fillMaxWidth()
                                        .graphicsLayer { alpha = scroll.state.collapsedFraction }
                                        .padding(horizontal = 64.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(title, style = MiuixTheme.textStyles.title3, maxLines = 1)
                                }
                            }
                        }
                    }
                    header()
                }
            }
        }
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().layerBackdrop(backdrop)
        Box(
            if (pageBackground == null) contentModifier.miuixLayerBackdrop(progressiveBackdrop) else contentModifier
        ) {
            CompositionLocalProvider(LocalPageScroll provides scroll) { content(padding, scroll) }
        }
    }
    overlay()
    }
    }
}

@Composable
private fun ProgressiveTopBarMaterial(
    backdrop: MiuixLayerBackdrop,
    collapsedFraction: Float,
    legacyBackdrop: LayerBackdrop? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val minimumContentHeightPx = with(density) { PROGRESSIVE_TOP_BAR_MINIMUM_CONTENT_HEIGHT.dp.roundToPx() }
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val foreground = subcompose("progressive-top-bar-foreground") {
            Box(Modifier.fillMaxWidth(), content = content)
        }.map { measurable -> measurable.measure(constraints.copy(minHeight = 0)) }
        val width = (foreground.maxOfOrNull { it.width } ?: constraints.minWidth)
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (foreground.maxOfOrNull { it.height } ?: constraints.minHeight)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        // Extend only the Miuix material; keep the title-bar layout height unchanged.
        val baseMaterialHeight = height.coerceAtLeast(minimumContentHeightPx)
        val materialHeight = if (legacyBackdrop == null) {
            (baseMaterialHeight * PROGRESSIVE_TOP_BAR_HEIGHT_FACTOR).toInt()
        } else {
            baseMaterialHeight
        }
        val background = subcompose("progressive-top-bar-background") {
            if (legacyBackdrop != null) {
                LegacyProgressiveTopBarBackground(
                    backdrop = legacyBackdrop,
                    progress = collapsedFraction,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ProgressiveTopBarBackground(
                    backdrop = backdrop,
                    progress = collapsedFraction,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }.map { measurable -> measurable.measure(Constraints.fixed(width, materialHeight)) }
        layout(width, height) {
            background.forEach { it.placeRelative(0, 0) }
            foreground.forEach { it.placeRelative(0, 0) }
        }
    }
}

@Composable
private fun LegacyProgressiveTopBarBackground(
    backdrop: LayerBackdrop,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    if (progress <= 0f) return
    Box(modifier.graphicsLayer { alpha = progress }) {
        Box(
            Modifier.matchParentSize()
                .expandDrawHeight(TOP_BAR_MATERIAL_HEIGHT_FACTOR)
                .drawPlainBackdrop(
                    backdrop = backdrop,
                    shape = { androidx.compose.ui.graphics.RectangleShape },
                    effects = {
                        blur(8.dp.toPx())
                        runtimeShaderEffect(
                            key = "about-progressive-blur-alpha-mask",
                            shaderString = PROGRESSIVE_BLUR_ALPHA_MASK_SHADER,
                            uniformShaderName = "content",
                        ) { setFloatUniform("size", size.width, size.height) }
                    },
                )
        )
    }
}

@Composable
private fun ProgressiveTopBarBackground(
    backdrop: MiuixLayerBackdrop,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    if (progress <= 0f) return
    val surface = MiuixTheme.colorScheme.surface
    Box(modifier.graphicsLayer { alpha = progress }) {
        Box(
            Modifier.matchParentSize().progressiveTextureBlur(
                backdrop = backdrop,
                shape = androidx.compose.ui.graphics.RectangleShape,
                blurRadius = PROGRESSIVE_TOP_BAR_BLUR_RADIUS,
                gradient = ProgressiveBlur.Top.copy(startFraction = PROGRESSIVE_TOP_BAR_BLUR_FADE_START),
            )
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to surface.copy(alpha = PROGRESSIVE_TOP_BAR_OVERLAY_ALPHA),
                    PROGRESSIVE_TOP_BAR_OVERLAY_END to ComposeColor.Transparent,
                    1f to ComposeColor.Transparent,
                )
            )
        )
    }
}

private const val PROGRESSIVE_TOP_BAR_BLUR_RADIUS = 3.6f
private const val PROGRESSIVE_TOP_BAR_BLUR_FADE_START = 0.60f
private const val PROGRESSIVE_TOP_BAR_OVERLAY_ALPHA = 0.94f
private const val PROGRESSIVE_TOP_BAR_OVERLAY_END = 0.80f
private const val PROGRESSIVE_TOP_BAR_MINIMUM_CONTENT_HEIGHT = 56f
private const val PROGRESSIVE_TOP_BAR_HEIGHT_FACTOR = 1.22f
private const val TOP_BAR_MATERIAL_HEIGHT_FACTOR = 1.4926275f
private const val TOOLBAR_GLASS_SURFACE_ALPHA = .80f
internal val TOOLBAR_GLASS_BLUR_RADIUS = 2.dp
private val TOOLBAR_GLASS_LENS_HEIGHT = 16.dp
private val TOOLBAR_GLASS_LENS_AMOUNT = 32.dp

private const val PROGRESSIVE_BLUR_ALPHA_MASK_SHADER = """
uniform shader content;
uniform float2 size;
half4 main(float2 coord) {
    float blurAlpha = smoothstep(size.y, size.y * 0.5, coord.y);
    return content.eval(coord) * blurAlpha;
}
"""

private fun Modifier.expandDrawHeight(factor: Float) = layout { measurable, constraints ->
    val expandedHeight = (constraints.maxHeight * factor).toInt()
    val placeable = measurable.measure(constraints.copy(minHeight = expandedHeight, maxHeight = expandedHeight))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(0, 0) }
}

internal fun BackdropEffectScope.toolbarGlassLens(progress: Float = 0f) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    lens(
        (TOOLBAR_GLASS_LENS_HEIGHT + 4.dp * clampedProgress).toPx(),
        (TOOLBAR_GLASS_LENS_AMOUNT + 8.dp * clampedProgress).toPx(),
        depthEffect = true,
        chromaticAberration = true,
    )
}

internal fun toolbarGlassHighlight(progress: Float = 0f): Highlight {
    val clampedProgress = progress.coerceIn(0f, 1f)
    return Highlight.Default.copy(
        width = .75.dp,
        blurRadius = .4.dp,
        alpha = .76f + clampedProgress * .24f,
    )
}

@Composable
private fun GlassBackButton(backdrop: LayerBackdrop, collapsedFraction: Float, onClick: () -> Unit) {
    GlassToolbarIconButton(
        icon = MiuixIcons.Regular.ChevronBackward,
        description = tr("\u8fd4\u56de", "\u8fd4\u56de"),
        onClick = onClick,
        backdrop = backdrop,
        collapsedFraction = collapsedFraction,
        containerPadding = PaddingValues(start = 8.dp),
        iconTranslationX = -1.15.dp,
    )
}

@Composable
internal fun GlassToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    backdrop: LayerBackdrop? = LocalToolbarBackdrop.current,
    collapsedFraction: Float = LocalToolbarCollapsed.current,
    containerPadding: PaddingValues = PaddingValues(horizontal = 2.dp),
    iconTranslationX: Dp = 0.dp,
    onClick: () -> Unit,
) {
    val animationScope = rememberCoroutineScope()
    val drag = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = 0f,
            valueRange = -1f..1f,
            visibilityThreshold = .001f,
            initialScale = 1f,
            pressedScale = 1.08f,
            onDragStopped = { animateToValue(0f) },
            onDrag = { size, amount -> updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f)) },
        )
    }
    val highlight = remember(animationScope) {
        InteractiveHighlight(animationScope) { size, offset ->
            androidx.compose.ui.geometry.Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height))
        }
    }
    val glassTint = MiuixTheme.colorScheme.surface.copy(alpha = TOOLBAR_GLASS_SURFACE_ALPHA)
    val iconColor = MiuixTheme.colorScheme.onSurface
    val glassAlpha by animateFloatAsState(collapsedFraction, tween(180), label = "toolbarButtonGlassAlpha")
    Box(
        Modifier.padding(containerPadding)
            .then(Modifier.size(46.dp))
            .then(highlight.gestureModifier)
            .then(drag.modifier)
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { alpha = glassAlpha }
                .then(if (backdrop != null) Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(TOOLBAR_GLASS_BLUR_RADIUS.toPx())
                        toolbarGlassLens(drag.pressProgress)
                    },
                    highlight = { toolbarGlassHighlight(drag.pressProgress) },
                    shadow = { Shadow.Default.copy(radius = 5.dp, color = ComposeColor.Black, alpha = .24f) },
                    innerShadow = null,
                    layerBlock = {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        val velocity = abs(drag.velocity.coerceIn(-1f, 1f))
                        scaleX *= 1f + velocity * .12f
                        scaleY /= 1f + velocity * .08f
                        translationX = drag.value * 5.dp.toPx()
                    },
                    onDrawSurface = { drawCircle(glassTint) },
                ) else Modifier.background(glassTint, CircleShape))
                .then(highlight.modifier),
        ) {
        }
        Image(
            icon,
            description,
            Modifier.size(22.dp).graphicsLayer { translationX = iconTranslationX.toPx() },
            colorFilter = ColorFilter.tint(iconColor),
        )
    }
}
