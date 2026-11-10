// SPDX-License-Identifier: AGPL-3.0-or-later
// Ported from HyperChanger MainActivity.kt; renderer retained.
package btm.m.auraplayer.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.*
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
@Composable
internal fun SoftGlassHeaderTabs(tabs: List<String>, selected: Int, onSelected: (Int) -> Unit) =
    SoftGlassHeaderTabs(tabs, selected, 1f, onSelected = onSelected)

@Composable
internal fun SoftGlassHeaderTabs(tabs: List<String>, selected: Int, widthScale: Float, finalWidthScale: Float = 1f, onSelected: (Int) -> Unit) {
    val backdrop = LocalToolbarBackdrop.current
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val shape = remember { Capsule() }
    val glass = if (backdrop != null && isRuntimeShaderSupported()) Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(3.dp.toPx())
            lens(10.dp.toPx(), 24.dp.toPx(), depthEffect = true, chromaticAberration = true)
        },
        highlight = { Highlight.Default.copy(alpha = .62f) },
        onDrawSurface = { drawRect(surface.copy(alpha = .6f)) },
    ) else Modifier.background(surface, shape)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tabsWidth = ((maxWidth - 56.dp).coerceAtLeast(0.dp) * widthScale).coerceAtMost(maxWidth) * finalWidthScale
        BoxWithConstraints(Modifier.align(Alignment.Center).width(tabsWidth).padding(vertical = 8.dp)) {
        Box(
            Modifier.matchParentSize()
                .clip(shape)
                .then(glass),
        )
        Row(
            Modifier.fillMaxWidth().height(53.dp * 0.93f * 0.97f).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            tabs.forEachIndexed { index, label ->
                Box(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .then(
                            if (index == selected) {
                                Modifier.background(MiuixTheme.colorScheme.onSurface.copy(alpha = .10f))
                            } else Modifier
                        )
                        .clickable { onSelected(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
    }
}
