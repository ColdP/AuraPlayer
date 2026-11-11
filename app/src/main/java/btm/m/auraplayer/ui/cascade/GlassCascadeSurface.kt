package btm.m.auraplayer.ui.cascade

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.ui.glassMenuSurface
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.*
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils

internal val LocalCascadeBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** The exact tint, blur, lens, highlight and shadow used by HyperChanger GlassDropdownMenu. */
@Composable
internal fun Modifier.cascadeGlassSurface(shape: Shape): Modifier {
    return glassMenuSurface(LocalCascadeBackdrop.current, shape)
}

@Composable
internal fun GlassWindowCascadingListPopup(show: Boolean, entries: List<DropdownEntry>, onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit, maxHeight: Dp?, dropdownColors: DropdownColors, collapseOnSelection: Boolean,
    backdrop: Backdrop?) {
    GlassCascadingListPopupLayout(
        show = show, entries = entries, onDismissRequest = onDismissRequest, onDismissFinished = onDismissFinished,
        dropdownColors = dropdownColors, collapseOnSelection = collapseOnSelection, maxHeight = maxHeight,
        enableWindowDim = false,
        popupHost = { visible, content ->
            MiuixPopupUtils.Companion.PopupLayout(
                visible = remember { mutableStateOf(visible) }.also { it.value = visible },
                enableWindowDim = false, enableBackHandler = false, renderInRootScaffold = true,
                enterTransition = EnterTransition.None, exitTransition = ExitTransition.None,
            ) {
                // Root-scaffold popups reparent their content. Restore the captured page
                // sampling layer here, after reparenting, just like our glass dialogs.
                CompositionLocalProvider(LocalCascadeBackdrop provides backdrop) { content() }
            }
        },
    )
}
