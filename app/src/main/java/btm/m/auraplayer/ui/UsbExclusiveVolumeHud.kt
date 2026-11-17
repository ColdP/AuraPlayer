// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import btm.m.auraplayer.R
import btm.m.auraplayer.audio.EffectsStore
import btm.m.auraplayer.audio.usb.UsbExclusiveStatus
import btm.m.auraplayer.ui.navigation.NavigationStyle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

@Composable
internal fun IosUsbVolumeHud(
    effects: EffectsStore,
    backdrop: Backdrop,
    navigationStylePreference: String,
    onMountedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val hudEvent by effects.usbVolumeHudEvent.collectAsState()
    val state by effects.state.collectAsState()
    val usbStatus by effects.usbStatus.collectAsState()
    val usbActive = usbStatus is UsbExclusiveStatus.Active

    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(mounted, usbActive) {
        onMountedChange(mounted && usbActive)
    }
    var collapsed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var pressCountInSession by remember { mutableIntStateOf(0) }
    var lastInteractionSeq by remember { mutableLongStateOf(0L) }
    var handledEventSeq by remember { mutableLongStateOf(0L) }

    val appearAnim = remember { Animatable(0f) }
    val stretchAnim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(hudEvent.seq, usbActive) {
        if (!usbActive) {
            mounted = false
            collapsed = false
            pressCountInSession = 0
            appearAnim.snapTo(0f)
            stretchAnim.snapTo(0f)
            return@LaunchedEffect
        }
        if (hudEvent.seq > handledEventSeq) {
            handledEventSeq = hudEvent.seq
            if (hudEvent.isPreview) {
                pressCountInSession = 1
                collapsed = false
                mounted = true
            } else {
                val wasShown = mounted && appearAnim.value > 0.15f
                if (!wasShown) {
                    pressCountInSession = 1
                    collapsed = false
                    mounted = true
                } else {
                    pressCountInSession++
                    if (state.usbVolumeCollapseOnRepeat && pressCountInSession >= 2 && !dragging) {
                        collapsed = true
                    }
                }
            }
            lastInteractionSeq++
            if (hudEvent.isBoundary) {
                scope.launch {
                    val target = if (hudEvent.isUpper) 1f else -1f
                    stretchAnim.animateTo(target, tween(75, easing = FastOutSlowInEasing))
                    stretchAnim.animateTo(0f, spring(dampingRatio = 0.46f, stiffness = 460f))
                }
            }
        }
    }

    LaunchedEffect(mounted, lastInteractionSeq, dragging, usbActive) {
        if (!mounted || !usbActive) return@LaunchedEffect
        if (appearAnim.targetValue < 1f) {
            appearAnim.animateTo(
                1f,
                spring(dampingRatio = 0.78f, stiffness = 420f),
            )
        }
        if (dragging) return@LaunchedEffect
        delay(2000L)
        appearAnim.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
        mounted = false
        collapsed = false
        pressCountInSession = 0
    }

    if (!mounted || !usbActive) return

    val collapseProgress by animateFloatAsState(
        targetValue = if (collapsed && state.usbVolumeCollapseOnRepeat) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 460f),
        label = "usbVolumeCollapse",
    )
    val animatedFill by animateFloatAsState(
        targetValue = state.usbVolume.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 620f),
        label = "usbVolumeFill",
    )

    val style = NavigationStyle.fromPreference(navigationStylePreference)
    val material = rememberModuleNavigationMaterial(navigationStylePreference)
    val advancedMaterial = LocalAdvancedMaterialEnabled.current
    val isDarkTheme = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val useEffects = advancedMaterial && isRuntimeShaderSupported()
    val liquid = style == NavigationStyle.LIQUID_GLASS
    val harmony = style == NavigationStyle.HARMONY_OS_FLOATING || style == NavigationStyle.HYPER_OS_FLOATING
    val floating = style == NavigationStyle.HYPER_OS_FLOATING || harmony
    val blurRadius = if (liquid) 3 else 18

    val surfaceColor = when (style) {
        NavigationStyle.LIQUID_GLASS -> if (isDarkTheme) Color(0xFF121212).copy(alpha = 0.40f) else Color(0xFFFAFAFA).copy(alpha = 0.40f)
        NavigationStyle.HYPER_OS_FLOATING -> if (isDarkTheme) Color.Black.copy(alpha = 0.30f) else Color(0xFFF5F5F5).copy(alpha = 0.42f)
        NavigationStyle.HARMONY_OS_FLOATING -> if (isDarkTheme) Color.Black.copy(alpha = 0.36f) else Color(0xFFF5F5F5).copy(alpha = 0.48f)
        NavigationStyle.HYPER_OS -> if (isDarkTheme) Color(0xFF242424).copy(alpha = 0.88f) else Color(0xFFF5F5F5).copy(alpha = 0.82f)
        NavigationStyle.MATERIAL -> if (isDarkTheme) Color(0xFF282430) else Color(0xFFECE6F0)
        NavigationStyle.ONE -> if (isDarkTheme) Color(0xFF1B1B1B) else Color(0xFFFFFFFF)
    }
    val density = LocalDensity.current
    val accentColor = MiuixTheme.colorScheme.primary

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthFactor = (1f + state.usbVolumeWidthScale / 100f).coerceIn(0.35f, 2.0f)
        val heightFactor = (1f + state.usbVolumeHeightScale / 100f).coerceIn(0.35f, 2.0f)

        val expandedWidth = 52.dp * widthFactor
        val thinWidth = (8.dp * widthFactor).coerceAtLeast(5.dp)
        val expandedHeight = 160.dp * heightFactor
        val thinHeight = 152.dp * heightFactor

        val pillWidth = lerp(expandedWidth, thinWidth, collapseProgress)
        val pillHeight = lerp(expandedHeight, thinHeight, collapseProgress)

        val baseEndInset = lerp(16.dp, 6.dp, collapseProgress) + state.usbVolumeOffsetX.dp
        val baseTopY = maxHeight / 3f - pillHeight / 2f + state.usbVolumeOffsetY.dp
        val isLeft = state.usbVolumePosition == "left"

        val appear = appearAnim.value.coerceIn(0f, 1f)
        val appearScale = lerp(0.24f, 1f, appear)
        val appearBlurDp = (1f - appear) * 14f
        val appearSlideX = with(density) {
            val slide = (1f - appear) * 22.dp.toPx()
            if (isLeft) -slide else slide
        }
        val stretch = stretchAnim.value
        val pillHeightPx = with(density) { pillHeight.toPx().coerceAtLeast(1f) }

        val backdropModifier = if (useEffects) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = {
                    vibrancy()
                    val r = blurRadius.coerceIn(0, 40)
                    blur(
                        with(density) {
                            when {
                                liquid -> r * 8f / 18f
                                floating -> r * if (harmony) 0.29f else 0.24f
                                else -> r.toFloat()
                            }.dp.toPx() * material.blur / 100f
                        },
                    )
                    if (liquid) {
                        lens(
                            24.dp.toPx() * material.thickness / 100f,
                            24.dp.toPx() * material.refraction / 100f,
                            chromaticAberration = material.dispersion ?: false,
                        )
                    } else if (floating) {
                        lens(
                            16.dp.toPx() * material.thickness / 100f,
                            32.dp.toPx() * material.refraction / 100f,
                            depthEffect = true,
                            chromaticAberration = material.dispersion ?: false,
                        )
                    }
                },
                highlight = {
                    Highlight.Default.copy(
                        alpha = material.light(
                            when {
                                liquid -> 1f
                                isDarkTheme -> 0.78f
                                else -> 0.96f
                            },
                        ),
                    )
                },
                shadow = {
                    Shadow(
                        radius = 16.dp,
                        color = Color.Black.copy(alpha = if (isDarkTheme) 0.36f else 0.16f),
                    )
                },
                onDrawSurface = {
                    drawRect(surfaceColor.copy(alpha = material.alpha(surfaceColor.alpha)))
                },
            )
        } else {
            val solidColor = if (isDarkTheme) Color(0xFF242424) else Color(0xFFF5F5F5)
            Modifier.background(solidColor.copy(alpha = 0.94f), CircleShape)
        }

        val iconAlpha = (1f - collapseProgress * 2.4f).coerceIn(0f, 1f)
        val fillColor = if (isDarkTheme) Color(0xFFEAEAEA).copy(alpha = 0.92f) else Color.White.copy(alpha = 0.94f)
        val isMuted = state.usbVolume <= 0.001f

        Box(
            modifier = Modifier
                .align(if (isLeft) Alignment.TopStart else Alignment.TopEnd)
                .offset(x = if (isLeft) baseEndInset else -baseEndInset, y = baseTopY)
                .width(pillWidth)
                .height(pillHeight)
                .graphicsLayer {
                    alpha = appear
                    scaleX = appearScale * (1f - abs(stretch) * 0.06f)
                    scaleY = appearScale * (1f + abs(stretch) * 0.11f)
                    translationX = appearSlideX
                    translationY = -stretch * 4.dp.toPx()
                    transformOrigin = TransformOrigin(0.5f, if (stretch >= 0f) 1f else 0f)
                }
                .then(if (appearBlurDp > 0.1f) Modifier.blur(appearBlurDp.dp) else Modifier)
                .clip(CircleShape)
                .then(backdropModifier)
                .pointerInput(pillHeightPx) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            collapsed = false
                            pressCountInSession = 1
                            lastInteractionSeq++
                        },
                        onDragEnd = {
                            dragging = false
                            lastInteractionSeq++
                            scope.launch {
                                stretchAnim.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 460f))
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            lastInteractionSeq++
                            scope.launch {
                                stretchAnim.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 460f))
                            }
                        },
                        onVerticalDrag = { _, dragAmount ->
                            val delta = -dragAmount / pillHeightPx
                            val raw = state.usbVolume + delta
                            if (raw > 1f) {
                                effects.setUsbVolume(1f, showHud = false)
                                scope.launch {
                                    stretchAnim.snapTo(((raw - 1f) * 3f).coerceIn(0f, 1f))
                                }
                            } else if (raw < 0f) {
                                effects.setUsbVolume(0f, showHud = false)
                                scope.launch {
                                    stretchAnim.snapTo((raw * 3f).coerceIn(-1f, 0f))
                                }
                            } else {
                                effects.setUsbVolume(raw, showHud = false)
                                if (stretchAnim.value != 0f) {
                                    scope.launch { stretchAnim.snapTo(0f) }
                                }
                            }
                            lastInteractionSeq++
                        },
                    )
                },
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(animatedFill)
                    .background(fillColor),
            )
            if (iconAlpha > 0.01f) {
                Image(
                    painter = painterResource(
                        if (isMuted) R.drawable.ic_player_volume_muted else R.drawable.ic_player_volume_full,
                    ),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(accentColor),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp * heightFactor.coerceIn(0.6f, 1.2f))
                        .size(22.dp * widthFactor.coerceIn(0.75f, 1.25f))
                        .graphicsLayer {
                            alpha = iconAlpha
                            val iconScale = lerp(1f, 0.4f, collapseProgress)
                            scaleX = iconScale
                            scaleY = iconScale
                        },
                )
            }
        }
    }
}
