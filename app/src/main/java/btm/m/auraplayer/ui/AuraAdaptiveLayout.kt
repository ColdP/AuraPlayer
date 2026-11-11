// SPDX-License-Identifier: GPL-3.0-or-later
// Width/orientation breakpoints and column counts follow Retro Music's resource qualifiers.
package btm.m.auraplayer.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.theme.defaultTextStyles

internal data class AuraLayoutInfo(val width: Float = 360f, val height: Float = 800f) {
    val tablet get() = minOf(width, height) >= 600f
    val landscape get() = width > height
    val gridColumns get() = if (tablet) { if (landscape) 6 else 4 } else if (landscape) 4 else 2
    val listColumns get() = if (tablet) { if (landscape) 3 else 2 } else if (landscape) 2 else 1
    val pageMargin get() = if (tablet) 64.dp else 16.dp
    val homeTileWidth get() = ((width - pageMargin.value * 2 - 24) / if (tablet || landscape) 4.3f else 2.3f).coerceIn(112f, 156f).dp
}

internal val LocalAuraLayout = staticCompositionLocalOf { AuraLayoutInfo() }
internal val CompactPreferencePadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

@Composable
internal fun AuraAdaptiveLayout(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = remember(maxWidth, maxHeight) { AuraLayoutInfo(maxWidth.value, maxHeight.value) }
        CompositionLocalProvider(LocalAuraLayout provides layout, content = content)
    }
}

// Native sp retains the system font scale, including nonlinear accessibility scaling.
internal fun auraTextStyles() = defaultTextStyles(
    main = TextStyle(fontSize = 15.sp), paragraph = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    body1 = TextStyle(fontSize = 15.sp), body2 = TextStyle(fontSize = 13.sp),
    button = TextStyle(fontSize = 15.sp), headline1 = TextStyle(fontSize = 15.sp), headline2 = TextStyle(fontSize = 14.sp),
    title1 = TextStyle(fontSize = 28.sp), title2 = TextStyle(fontSize = 22.sp),
    title3 = TextStyle(fontSize = 18.sp), title4 = TextStyle(fontSize = 16.sp),
    footnote1 = TextStyle(fontSize = 12.sp), footnote2 = TextStyle(fontSize = 10.sp),
)
