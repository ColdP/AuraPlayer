package btm.m.auraplayer.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.sing.AuraSingMath
import btm.m.auraplayer.sing.AuraSingTrackState
import btm.m.auraplayer.ui.tr
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * Right-side Aura Sing microphone trigger matching [LyricOptionsMenu]'s glass material, 44.dp size,
 * and spring expansion animation. Expands vertically upward into a capsule slider controlling
 * separated Vocal track volume; dragging vocal volume to full (>= 99%) exits Aura Sing.
 */
@Composable
internal fun AuraSingControl(
    backdrop: Backdrop,
    monetTint: Color,
    anchorX: Dp,
    anchorY: Dp,
    topInset: Dp,
    paneLeftX: Dp = 16.dp,
    modifier: Modifier = Modifier,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    val engine = (LocalContext.current.applicationContext as AuraApplication).auraSing
    val uiState by engine.uiState.collectAsState()
    if (!uiState.enabled) return

    var expanded by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val tint by animateColorAsState(monetTint, tween(1000), label = "auraSingMonet")

    LaunchedEffect(expanded) {
        onExpandedChange(expanded)
        progress.animateTo(
            if (expanded) 1f else 0f,
            spring(.88f, if (expanded) 360f else 430f, visibilityThreshold = .0005f),
        )
    }
    LaunchedEffect(uiState.activeInPlayer) {
        if (!uiState.activeInPlayer && expanded) {
            expanded = false
        }
    }
    DisposableEffect(Unit) { onDispose { onExpandedChange(false) } }
    BackHandler(expanded) { expanded = false }

    val p = progress.value.coerceIn(0f, 1f)
    val buttonSize = 44.dp
    val maxExpandedHeight = minOf(154.dp, (anchorY + buttonSize - topInset).coerceAtLeast(buttonSize))
    val height = buttonSize + (maxExpandedHeight - buttonSize) * p
    val radius = 22.dp
    val shape = RoundedCornerShape(radius)

    val density = LocalDensity.current
    var dragVolume by remember { mutableFloatStateOf(uiState.vocalVolume) }
    var dragging by remember { mutableStateOf(false) }
    val displayedVolume = if (dragging) dragVolume else uiState.vocalVolume

    Box(Modifier.fillMaxSize().then(modifier)) {
        if (expanded || p > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = false },
            )
        }

        // Status hint pill when Aura Sing is active & missing models or actively separating.
        val statusHint = when {
            !uiState.activeInPlayer -> null
            uiState.trackState is AuraSingTrackState.MissingModels ->
                tr("aura_sing_player_missing_model", "请先在 设置-音频-Aura Sing 下载模型")
            uiState.trackState is AuraSingTrackState.Separating -> {
                val pct = ((uiState.trackState as AuraSingTrackState.Separating).progress * 100f).roundToInt().coerceIn(0, 100)
                tr("aura_sing_player_separating", "音轨分离中 %d%%").format(pct)
            }
            uiState.trackState is AuraSingTrackState.Failed ->
                tr("aura_sing_player_failed", "音轨分离失败")
            else -> null
        }

        if (statusHint != null && (expanded || uiState.trackState is AuraSingTrackState.Separating)) {
            val statusStartX = paneLeftX + buttonSize + 10.dp
            val statusWidth = (anchorX - 10.dp - statusStartX).coerceAtLeast(100.dp)
            Box(
                modifier = Modifier
                    .offset(x = statusStartX, y = anchorY)
                    .width(statusWidth)
                    .height(buttonSize),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .playerMenuMaterial(backdrop, tint, 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        statusHint,
                        color = Color.White.copy(alpha = 0.92f),
                        style = MiuixTheme.textStyles.footnote1,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }

        val currentHeightPx = with(density) { maxExpandedHeight.toPx().coerceAtLeast(1f) }
        val pillInteractionModifier = if (expanded) {
            Modifier
                .pointerInput(maxExpandedHeight) {
                    detectTapGestures { offset ->
                        val nextVol = (1f - (offset.y / currentHeightPx)).coerceIn(0f, 1f)
                        if (AuraSingMath.shouldExitAuraSing(nextVol)) {
                            engine.deactivateInPlayer()
                            expanded = false
                        } else {
                            engine.setVocalVolume(nextVol)
                        }
                    }
                }
                .pointerInput(maxExpandedHeight) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            val nextVol = (1f - (offset.y / currentHeightPx)).coerceIn(0f, 1f)
                            dragVolume = nextVol
                            if (!AuraSingMath.shouldExitAuraSing(nextVol)) {
                                engine.setVocalVolume(nextVol)
                            }
                        },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            val nextVol = (1f - (change.position.y / currentHeightPx)).coerceIn(0f, 1f)
                            dragVolume = nextVol
                            if (!AuraSingMath.shouldExitAuraSing(nextVol)) {
                                engine.setVocalVolume(nextVol)
                            }
                        },
                        onDragEnd = {
                            val finalVol = dragVolume
                            dragging = false
                            if (AuraSingMath.shouldExitAuraSing(finalVol)) {
                                engine.deactivateInPlayer()
                                expanded = false
                            } else {
                                engine.setVocalVolume(finalVol)
                            }
                        },
                        onDragCancel = {
                            dragging = false
                        },
                    )
                }
        } else {
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (!uiState.activeInPlayer) {
                    engine.activateInPlayer()
                }
                expanded = true
            }
        }

        Box(
            Modifier
                .offset(anchorX, anchorY + buttonSize - height)
                .size(buttonSize, height)
                .playerMenuMaterial(backdrop, tint, radius)
                .clip(shape)
                .then(pillInteractionModifier)
                .semantics { contentDescription = tr("aura_sing_title", "Aura Sing") },
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (p > 0.01f) {
                // Expanded capsule fill rising upward proportionally to vocalVolume (0f..1f).
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = ((p - .12f) / .88f).coerceIn(0f, 1f) }
                        .blur(((1f - p) * 10f).dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(displayedVolume.coerceIn(0f, 1f))
                            .background(Color.White.copy(alpha = 0.78f)),
                    )
                }
            }

            if (uiState.activeInPlayer && uiState.trackState is AuraSingTrackState.Separating && p < 0.25f) {
                val sepProgress = (uiState.trackState as AuraSingTrackState.Separating).progress.coerceIn(0f, 1f)
                Canvas(
                    modifier = Modifier
                        .size(buttonSize)
                        .padding(3.dp)
                        .alpha(1f - p * 4f),
                ) {
                    val strokePx = 2.dp.toPx()
                    drawArc(
                        color = Color.White.copy(alpha = 0.22f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(strokePx, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = Color.White.copy(alpha = 0.92f),
                        startAngle = -90f,
                        sweepAngle = (360f * sepProgress).coerceAtLeast(18f),
                        useCenter = false,
                        style = Stroke(strokePx, cap = StrokeCap.Round),
                    )
                }
            }

            // Mic glyph anchored inside the bottom 44dp, matching screenshot contrast over white fill.
            val iconOverBrightFill = (p > 0.45f && displayedVolume > 0.20f) || (uiState.activeInPlayer && p < 0.05f)
            val iconColor = when {
                p > 0.35f && displayedVolume > 0.22f -> Color(0xFF1B2028).copy(alpha = 0.88f)
                uiState.activeInPlayer && p <= 0.35f -> Color.White
                else -> Color.White.copy(alpha = 0.90f)
            }
            Box(
                Modifier
                    .size(buttonSize)
                    .align(Alignment.BottomCenter)
                    .let { base ->
                        if (uiState.activeInPlayer && p < 0.05f) {
                            base.background(Color.White.copy(alpha = 0.20f), shape)
                        } else base
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Mic,
                    contentDescription = tr("aura_sing_title", "Aura Sing"),
                    tint = iconColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
