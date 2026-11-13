// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import android.media.AudioManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.R
import btm.m.auraplayer.ui.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

private fun <T> expressiveSpatialSpring() = spring<T>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow
)

private fun <T> expressiveEffectsSpring() = spring<T>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium
)

@Composable
internal fun MaterialWavyProgressSlider(
    value: Float,
    playing: Boolean,
    label: String,
    activeColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    enabled: Boolean = true
) {
    val onChangeState by rememberUpdatedState(onChange)
    val onFinishedState by rememberUpdatedState(onFinished)
    val valueState by rememberUpdatedState(value.coerceIn(0f, 1f))
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(playing, dragging, enabled) {
        if (!playing || dragging || !enabled) return@LaunchedEffect
        var lastNanos = 0L
        while (isActive) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    val dt = ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    phase = (phase + dt * 4.2f) % (2f * PI.toFloat())
                }
                lastNanos = nanos
            }
        }
    }

    val waveAmplitude by animateDpAsState(
        targetValue = if (playing && !dragging && enabled) 2.5.dp else 0.dp,
        animationSpec = expressiveEffectsSpring(),
        label = "m3eWaveAmp"
    )
    val thumbHeight by animateDpAsState(
        targetValue = if (dragging) 22.dp else 18.dp,
        animationSpec = expressiveSpatialSpring(),
        label = "m3eThumbH"
    )

    val displayed = if (dragging) dragFraction else valueState

    Canvas(
        modifier = modifier
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(displayed, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        val clamped = target.coerceIn(0f, 1f)
                        onChangeState(clamped)
                        onFinishedState()
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragging = true
                    dragFraction = (down.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onChangeState(dragFraction)
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        pointer.consume()
                        dragFraction = (pointer.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        onChangeState(dragFraction)
                        pressed = pointer.pressed
                    }
                    dragging = false
                    onFinishedState()
                }
            }
    ) {
        val strokePx = 4.dp.toPx()
        val thumbW = 4.dp.toPx()
        val thumbHPx = thumbHeight.toPx().coerceAtMost(size.height)
        val gapPx = 6.dp.toPx()
        val stopDotRadius = 2.dp.toPx()
        val cy = size.height / 2f

        val minX = thumbW / 2f
        val maxX = (size.width - thumbW / 2f).coerceAtLeast(minX)
        val thumbX = minX + (maxX - minX) * displayed

        val inactiveStart = (thumbX + thumbW / 2f + gapPx).coerceAtMost(maxX)
        if (inactiveStart < maxX - strokePx / 2f) {
            drawLine(
                color = trackColor,
                start = Offset(inactiveStart, cy),
                end = Offset(maxX, cy),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
        }
        drawCircle(
            color = activeColor.copy(alpha = 0.72f),
            radius = stopDotRadius,
            center = Offset(maxX - stopDotRadius, cy)
        )

        val activeEnd = (thumbX - thumbW / 2f - gapPx).coerceAtLeast(minX)
        if (activeEnd > minX + 1f) {
            val ampPx = waveAmplitude.toPx()
            if (ampPx <= 0.15f) {
                drawLine(
                    color = activeColor,
                    start = Offset(minX, cy),
                    end = Offset(activeEnd, cy),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
            } else {
                val wavelengthPx = 22.dp.toPx()
                val stepPx = 2.dp.toPx()
                val span = activeEnd - minX
                val steps = ceil(span / stepPx).toInt().coerceAtLeast(2)
                val path = Path()
                for (i in 0..steps) {
                    val x = minX + span * (i.toFloat() / steps)
                    val edgeFade = ((x - minX) / 8.dp.toPx()).coerceIn(0f, 1f) *
                        ((activeEnd - x) / 8.dp.toPx()).coerceIn(0f, 1f)
                    val angle = ((x - minX) / wavelengthPx) * (2f * PI.toFloat()) - phase
                    val y = cy + sin(angle) * ampPx * edgeFade
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = activeColor,
                    style = Stroke(
                        width = strokePx,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        drawRoundRect(
            color = activeColor,
            topLeft = Offset(thumbX - thumbW / 2f, cy - thumbHPx / 2f),
            size = Size(thumbW, thumbHPx),
            cornerRadius = CornerRadius(thumbW / 2f, thumbW / 2f)
        )
    }
}

@Composable
internal fun MaterialExpressivePlayPauseButton(
    playing: Boolean,
    label: String,
    colors: PlayerExpressiveColors,
    touchHeight: Dp,
    pausedWidth: Dp,
    playingWidth: Dp,
    iconSize: Dp,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val containerColor by animateColorAsState(
        targetValue = if (playing) colors.primary else colors.primaryContainer,
        animationSpec = expressiveEffectsSpring(),
        label = "m3ePlayContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (playing) colors.onPrimary else colors.onPrimaryContainer,
        animationSpec = expressiveEffectsSpring(),
        label = "m3ePlayContent"
    )
    val width by animateDpAsState(
        targetValue = if (playing) playingWidth else pausedWidth,
        animationSpec = expressiveSpatialSpring(),
        label = "m3ePlayWidth"
    )
    val corner by animateDpAsState(
        targetValue = when {
            pressed -> 16.dp
            playing -> touchHeight / 2f
            else -> 22.dp
        },
        animationSpec = expressiveSpatialSpring(),
        label = "m3ePlayCorner"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = expressiveSpatialSpring(),
        label = "m3ePlayScale"
    )

    Box(
        modifier = Modifier
            .size(width = width, height = touchHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(corner))
            .background(containerColor)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play),
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(contentColor)
        )
    }
}

@Composable
internal fun MaterialExpressiveTransportButton(
    icon: Int,
    label: String,
    containerColor: Color,
    contentColor: Color,
    width: Dp,
    height: Dp,
    iconSize: Dp,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) 14.dp else 22.dp,
        animationSpec = expressiveSpatialSpring(),
        label = "m3eTransportCorner"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = expressiveSpatialSpring(),
        label = "m3eTransportScale"
    )

    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(corner))
            .background(containerColor)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(contentColor)
        )
    }
}

@Composable
internal fun MaterialExpressiveBottomButton(
    icon: Int,
    label: String,
    selected: Boolean,
    colors: PlayerExpressiveColors,
    touchSize: Dp,
    iconSize: Dp,
    useTertiary: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val unselectedBg = colors.surfaceContainerHigh.copy(alpha = if (colors.dark) .82f else .94f)
    val selectedBg = if (useTertiary) colors.tertiaryContainer else colors.secondaryContainer
    val selectedFg = if (useTertiary) colors.onTertiaryContainer else colors.onSecondaryContainer
    val containerColor by animateColorAsState(
        targetValue = if (selected) selectedBg else unselectedBg,
        animationSpec = expressiveEffectsSpring(),
        label = "m3eBottomBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) selectedFg else colors.iconInk,
        animationSpec = expressiveEffectsSpring(),
        label = "m3eBottomFg"
    )
    val width by animateDpAsState(
        targetValue = if (selected) touchSize + 10.dp else touchSize,
        animationSpec = expressiveSpatialSpring(),
        label = "m3eBottomW"
    )
    val corner by animateDpAsState(
        targetValue = if (pressed) 12.dp else if (selected) 15.dp else touchSize / 2f,
        animationSpec = expressiveSpatialSpring(),
        label = "m3eBottomCorner"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = expressiveSpatialSpring(),
        label = "m3eBottomScale"
    )

    Box(
        modifier = Modifier
            .size(width = width, height = touchSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(corner))
            .background(containerColor)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(contentColor)
        )
    }
}

@Composable
internal fun MaterialExpressiveMoreButton(
    label: String,
    colors: PlayerExpressiveColors,
    buttonSize: Dp,
    iconSize: Dp,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) 11.dp else buttonSize / 2f,
        animationSpec = expressiveSpatialSpring(),
        label = "m3eMoreCorner"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = expressiveSpatialSpring(),
        label = "m3eMoreScale"
    )

    Box(
        modifier = Modifier
            .size(buttonSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(corner))
            .background(colors.secondaryContainer)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Image(
            imageVector = MiuixIcons.Regular.More,
            contentDescription = label,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(colors.onSecondaryContainer)
        )
    }
}

@Composable
internal fun MaterialExpressiveVolumeSlider(
    colors: PlayerExpressiveColors,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val max = remember(manager) { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var value by remember {
        mutableFloatStateOf(manager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat())
    }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(manager) {
        while (true) {
            if (!dragging) value = manager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
            delay(300)
        }
    }

    val badgeBg = colors.surfaceContainerHigh.copy(alpha = if (colors.dark) .80f else .92f)
    val trackBg = colors.surfaceContainerHighest.copy(alpha = if (colors.dark) .55f else .85f)

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(badgeBg),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_player_volume_muted),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                colorFilter = ColorFilter.tint(colors.iconInk)
            )
        }
        Spacer(Modifier.width(8.dp))
        MaterialWavyProgressSlider(
            value = value,
            playing = false,
            label = tr("media_volume", "媒体音量"),
            activeColor = colors.secondary,
            trackColor = trackBg,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onChange = { next ->
                dragging = true
                value = next
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, (next * max).roundToInt(), 0)
            },
            onFinished = { dragging = false },
            enabled = !manager.isVolumeFixed
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(badgeBg),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_player_volume_full),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                colorFilter = ColorFilter.tint(colors.primary)
            )
        }
    }
}
