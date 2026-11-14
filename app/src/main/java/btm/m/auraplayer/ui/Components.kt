package btm.m.auraplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.graphics.luminance
import btm.m.auraplayer.ui.navigation.NavigationMaterial
import btm.m.auraplayer.ui.navigation.NavigationMaterialSettings
import btm.m.auraplayer.ui.navigation.NavigationStyle
import btm.m.auraplayer.ui.player.playerMiniBlurRadius
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.*
import com.kyant.backdrop.highlight.Highlight
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

val LocalToolbarBackdrop = staticCompositionLocalOf<Backdrop?> { null }
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
internal fun rememberModuleNavigationMaterial(style: String): btm.m.auraplayer.ui.navigation.NavigationMaterial {
    val store = (LocalContext.current.applicationContext as btm.m.auraplayer.AuraApplication).navigationMaterial
    val materials by store.state.collectAsState()
    val selected = NavigationStyle.fromPreference(style)
    return materials[selected] ?: NavigationMaterialSettings.defaults(selected)
}

internal data class FloatingGlassMaterialSpec(
    val blurRadius: Dp,
    val refractionHeight: Dp,
    val refractionAmount: Dp,
    val depthEffect: Boolean,
    val chromaticAberration: Boolean,
    val highlightAlpha: Float,
    val surfaceAlpha: Float,
)

internal fun resolveFloatingGlassMaterialSpec(
    navigationStyle: String,
    material: NavigationMaterial,
    dark: Boolean,
    colorStyle: Boolean = false,
): FloatingGlassMaterialSpec {
    val liquid = navigationStyle == "liquid_glass"
    val harmony = navigationStyle == "harmony_os_floating"
    val baseBlur = playerMiniBlurRadius(navigationStyle, material.blur)
    val baseSurfaceAlpha = when {
        colorStyle -> .58f
        liquid -> .40f
        harmony -> if (dark) .36f else .48f
        else -> if (dark) .30f else .42f
    }
    val baseHighlightAlpha = when {
        colorStyle -> .78f
        liquid -> Highlight.Default.alpha
        else -> (if (dark) .78f else .96f) * (if (harmony) .72f else 1f)
    }
    return FloatingGlassMaterialSpec(
        blurRadius = if (colorStyle) 14.dp else baseBlur,
        refractionHeight = (if (liquid) 24.dp else 16.dp) * material.thickness / 100f,
        refractionAmount = if (colorStyle) 0.dp else (if (liquid) 24.dp else 32.dp) * material.refraction / 100f,
        depthEffect = !liquid,
        chromaticAberration = material.dispersion ?: liquid,
        highlightAlpha = if (colorStyle) baseHighlightAlpha else material.light(baseHighlightAlpha),
        surfaceAlpha = material.alpha(baseSurfaceAlpha),
    )
}

@Composable
fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    when (LocalAppUiStyle.current) {
        AppUiStyle.MATERIAL -> {
            MaterialExpressiveGroup(title, content)
            return
        }
        AppUiStyle.ONE -> {
            OneUiGroup(title, content)
            return
        }
        AppUiStyle.HYPER -> Unit
    }
    Column(Modifier.fillMaxWidth()) {
        SmallTitle(title, insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
        SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp), content = content)
    }
}

@Composable
fun Preference(title: String, summary: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    when (LocalAppUiStyle.current) {
        AppUiStyle.MATERIAL -> {
            MaterialExpressivePreferenceRow(title = title, summary = summary, enabled = enabled, onClick = onClick)
            return
        }
        AppUiStyle.ONE -> {
            OneUiPreferenceRow(title = title, summary = summary, enabled = enabled, onClick = onClick)
            return
        }
        AppUiStyle.HYPER -> Unit
    }
    top.yukonga.miuix.kmp.preference.ArrowPreference(title = title, summary = summary, enabled = enabled, onClick = onClick, insideMargin = CompactPreferencePadding)
}

@Composable
fun SwitchPreference(title: String, summary: String? = null, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    when (LocalAppUiStyle.current) {
        AppUiStyle.MATERIAL -> {
            MaterialExpressiveSwitchPreference(title = title, summary = summary, checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
            return
        }
        AppUiStyle.ONE -> {
            OneUiSwitchPreference(title = title, summary = summary, checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
            return
        }
        AppUiStyle.HYPER -> Unit
    }
    top.yukonga.miuix.kmp.preference.SwitchPreference(checked = checked, onCheckedChange = onCheckedChange, title = title, summary = summary, enabled = enabled, insideMargin = CompactPreferencePadding)
}

@Composable
fun BlurAnimatedVisibility(visible: Boolean, label: String, content: @Composable () -> Unit) {
    val transition = updateTransition(visible, label = label)
    val blur by transition.animateFloat(transitionSpec = { tween(220) }, label = "blur") { if (it) 0f else 8f }
    transition.AnimatedVisibility(
        visible = { it },
        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
        exit = fadeOut(tween(220)) + shrinkVertically(tween(220)),
    ) {
        Box(Modifier.blur(blur.dp)) {
            content()
        }
    }
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
