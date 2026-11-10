package btm.m.auraplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import btm.m.auraplayer.ui.navigation.NavigationMaterialSettings
import btm.m.auraplayer.ui.navigation.NavigationStyle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.*
import com.kyant.backdrop.highlight.Highlight
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

val LocalToolbarBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
val LocalDialogBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalToolbarCollapsed = compositionLocalOf { 0f }
internal val LocalPageScroll = compositionLocalOf<ScrollBehavior?> { null }

@Composable
internal fun pageContentModifier(): Modifier {
    val scroll = LocalPageScroll.current
    return Modifier.fillMaxSize().then(if (scroll != null) Modifier.nestedScroll(scroll.nestedScrollConnection) else Modifier)
}
// Compatibility with imported MIUIX components. Aura always uses the MIUIX branch.
enum class AppInterfaceStyle { MIUIX, COLOR }
val LocalAppInterfaceStyle = staticCompositionLocalOf { AppInterfaceStyle.MIUIX }

@Composable
internal fun rememberModuleNavigationMaterial(style: String) = remember(style) {
    NavigationMaterialSettings.defaults(NavigationStyle.fromPreference(style))
}

@Composable
fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SmallTitle(title, insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
        SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp), content = content)
    }
}

@Composable
fun Preference(title: String, summary: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    top.yukonga.miuix.kmp.preference.ArrowPreference(title = title, summary = summary, enabled = enabled, onClick = onClick)
}

@Composable
fun SwitchPreference(title: String, summary: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    top.yukonga.miuix.kmp.preference.SwitchPreference(checked = checked, onCheckedChange = onCheckedChange, title = title, summary = summary)
}

@Composable
fun SliderPreference(title: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, enabled: Boolean = true, steps: Int = 0, defaultValue: Float = range.start, onChange: (Float) -> Unit) {
    EditableSliderPreference(value = value, onValueChange = onChange, onValueChangeFinished = {},
        title = title, valueText = valueText, valueRange = range, enabled = enabled, steps = steps, defaultValue = defaultValue)
}

@Composable
fun EmptyState(title: String, summary: String = "", action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("♫", fontSize = 48.sp, color = MiuixTheme.colorScheme.primary)
        Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.SemiBold)
        if (summary.isNotBlank()) Text(summary, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2)
        if (action != null) GlassDialogButton(onClick = onAction, colors = ButtonDefaults.buttonColorsPrimary()) { Text(action) }
    }
}
