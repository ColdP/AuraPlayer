// SPDX-License-Identifier: AGPL-3.0-or-later
// Ported from HyperChanger MainActivity.kt; renderer retained.
package btm.m.auraplayer.ui
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import btm.m.auraplayer.ui.navigation.NavigationMaterial
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.*
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported

internal data class SoftGlassHeaderTabsColors(
    val surfaceTint: Color,
    val selectedContainer: Color,
    val selectedContent: Color,
    val unselectedContent: Color,
)

internal fun resolveSoftGlassHeaderTabsColors(
    surface: Color,
    surfaceContainer: Color,
    primary: Color,
    primaryContainer: Color,
    onSurface: Color,
    selectionOpacity: Int = 100,
): SoftGlassHeaderTabsColors {
    val dark = surface.luminance() < .5f
    val baseSelectedAlpha = if (dark) .46f else .38f
    return SoftGlassHeaderTabsColors(
        surfaceTint = lerp(surfaceContainer, primaryContainer, if (dark) .22f else .16f),
        selectedContainer = lerp(primaryContainer, primary, if (dark) .28f else .16f)
            .copy(alpha = (baseSelectedAlpha * selectionOpacity / 100f).coerceIn(0f, .88f)),
        selectedContent = primary,
        unselectedContent = onSurface.copy(alpha = .72f),
    )
}

@Composable
internal fun SoftGlassHeaderTabs(
    tabs: List<String>,
    selected: Int,
    navigationStyle: String = "",
    material: NavigationMaterial? = null,
    onSelected: (Int) -> Unit,
) = SoftGlassHeaderTabs(tabs, selected, 1f, navigationStyle = navigationStyle, material = material, onSelected = onSelected)

@Composable
internal fun SoftGlassHeaderTabs(
    tabs: List<String>,
    selected: Int,
    widthScale: Float,
    finalWidthScale: Float = 1f,
    scrollable: Boolean = false,
    navigationStyle: String = "",
    material: NavigationMaterial? = null,
    onSelected: (Int) -> Unit,
) {
    val backdrop = LocalToolbarBackdrop.current
    val scheme = MiuixTheme.colorScheme
    val dark = scheme.surface.luminance() < .5f
    val colorStyle = LocalAppInterfaceStyle.current == AppInterfaceStyle.COLOR
    val glassSpec = material?.let {
        resolveFloatingGlassMaterialSpec(navigationStyle, it, dark, colorStyle)
    }
    val colors = resolveSoftGlassHeaderTabsColors(
        surface = scheme.surface,
        surfaceContainer = scheme.surfaceContainer,
        primary = scheme.primary,
        primaryContainer = scheme.primaryContainer,
        onSurface = scheme.onSurface,
        selectionOpacity = material?.selectionOpacity ?: 100,
    )
    val surfaceTint by animateColorAsState(colors.surfaceTint, tween(320, easing = FastOutSlowInEasing), label = "tabsSurfaceTint")
    val shape = remember { Capsule() }
    val glass = if (backdrop != null && isRuntimeShaderSupported()) Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            if (glassSpec != null) {
                blur(glassSpec.blurRadius.toPx())
                lens(
                    glassSpec.refractionHeight.toPx(),
                    glassSpec.refractionAmount.toPx(),
                    depthEffect = glassSpec.depthEffect,
                    chromaticAberration = glassSpec.chromaticAberration,
                )
            } else {
                blur(3.dp.toPx())
                lens(10.dp.toPx(), 24.dp.toPx(), depthEffect = true, chromaticAberration = true)
            }
        },
        highlight = { Highlight.Default.copy(alpha = glassSpec?.highlightAlpha ?: .62f) },
        onDrawSurface = { drawRect(surfaceTint.copy(alpha = glassSpec?.surfaceAlpha ?: .6f)) },
    ) else Modifier.background(surfaceTint, shape)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tabsWidth = ((maxWidth - LocalAuraLayout.current.pageMargin * 2).coerceAtLeast(0.dp) * widthScale).coerceAtMost(maxWidth) * finalWidthScale
        BoxWithConstraints(Modifier.align(Alignment.Center).width(tabsWidth).padding(vertical = 8.dp)) {
        Box(
            Modifier.matchParentSize()
                .clip(shape)
                .then(glass),
        )
        Row(
            Modifier.fillMaxWidth().height(53.dp * 0.93f * 0.97f * 0.92f).padding(4.dp).then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            tabs.forEachIndexed { index, label ->
                val active = index == selected
                val tabBackground by animateColorAsState(
                    if (active) colors.selectedContainer else Color.Transparent,
                    tween(220, easing = FastOutSlowInEasing),
                    label = "tabContainer$index",
                )
                val tabTextColor by animateColorAsState(
                    if (active) colors.selectedContent else colors.unselectedContent,
                    tween(220, easing = FastOutSlowInEasing),
                    label = "tabText$index",
                )
                Box(
                    (if (scrollable) Modifier.width(80.dp) else Modifier.weight(1f))
                        .fillMaxHeight()
                        .clip(shape)
                        .background(tabBackground)
                        .clickable { onSelected(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = tabTextColor,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
    }
}
