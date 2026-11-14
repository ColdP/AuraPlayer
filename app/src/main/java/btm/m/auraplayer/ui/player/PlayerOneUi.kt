// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import android.media.AudioManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.R
import btm.m.auraplayer.ui.tr
import dev.pascal.oneuikit.MonetTones
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import kotlin.math.roundToInt

private fun <T> onePlayerSpring() = spring<T>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

@Immutable
internal data class PlayerOneUiColors(
    val dark: Boolean,
    val background: Color,
    val card: Color,
    val cardAlt: Color,
    val text: Color,
    val subText: Color,
    val divider: Color,
    val accent: Color,
    val accentDeep: Color,
    val onAccent: Color,
    val chip: Color,
    val onChip: Color,
    val track: Color,
)

@Composable
internal fun rememberPlayerOneUiColors(seed: Int, isDark: Boolean): PlayerOneUiColors {
    val target = remember(seed, isDark) {
        val monet = MonetTones.fromSeed(seed, isDark)
        PlayerOneUiColors(
            dark = isDark,
            background = Color(monet.background),
            card = Color(monet.card),
            cardAlt = Color(monet.cardAlt),
            text = Color(monet.text),
            subText = Color(monet.subText),
            divider = Color(monet.divider),
            accent = Color(monet.accent),
            accentDeep = Color(monet.accentDeep),
            onAccent = Color(monet.onAccent),
            chip = Color(monet.chip),
            onChip = Color(monet.onChip),
            track = Color(monet.track),
        )
    }
    val animSpec = tween<Color>(700, easing = FastOutSlowInEasing)
    return PlayerOneUiColors(
        dark = target.dark,
        background = animateColorAsState(target.background, animSpec, label = "oneBg").value,
        card = animateColorAsState(target.card, animSpec, label = "oneCard").value,
        cardAlt = animateColorAsState(target.cardAlt, animSpec, label = "oneCardAlt").value,
        text = animateColorAsState(target.text, animSpec, label = "oneText").value,
        subText = animateColorAsState(target.subText, animSpec, label = "oneSubText").value,
        divider = animateColorAsState(target.divider, animSpec, label = "oneDivider").value,
        accent = animateColorAsState(target.accent, animSpec, label = "oneAccent").value,
        accentDeep = animateColorAsState(target.accentDeep, animSpec, label = "oneAccentDeep").value,
        onAccent = animateColorAsState(target.onAccent, animSpec, label = "oneOnAccent").value,
        chip = animateColorAsState(target.chip, animSpec, label = "oneChip").value,
        onChip = animateColorAsState(target.onChip, animSpec, label = "oneOnChip").value,
        track = animateColorAsState(target.track, animSpec, label = "oneTrack").value,
    )
}

@Composable
internal fun OneUiPlayerProgressSlider(
    value: Float,
    label: String,
    colors: PlayerOneUiColors,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    enabled: Boolean = true,
) {
    val onChangeState by rememberUpdatedState(onChange)
    val onFinishedState by rememberUpdatedState(onFinished)
    val valueState by rememberUpdatedState(value.coerceIn(0f, 1f))
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val trackThickness by animateDpAsState(
        targetValue = if (dragging) 11.dp else 9.dp,
        animationSpec = onePlayerSpring(),
        label = "oneSliderTrack",
    )
    val thumbOuterRadius by animateDpAsState(
        targetValue = if (dragging) 10.dp else 8.5.dp,
        animationSpec = onePlayerSpring(),
        label = "oneSliderThumb",
    )

    val displayed = if (dragging) dragFraction else valueState

    Canvas(
        modifier = modifier.playerScrubGesture(
            enabled = enabled,
            displayedValue = displayed,
            label = label,
            onDragStateChange = { isDown ->
                if (isDown && !dragging) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                dragging = isDown
            },
            onChange = { next ->
                dragFraction = next
                onChangeState(next)
            },
            onFinished = onFinishedState,
        ),
    ) {
        val trackPx = trackThickness.toPx().coerceAtMost(size.height * 0.7f)
        val radiusPx = thumbOuterRadius.toPx().coerceAtMost(size.height / 2f)
        val strokePx = 3.dp.toPx()
        val cy = size.height / 2f
        val minX = radiusPx
        val maxX = (size.width - radiusPx).coerceAtLeast(minX)
        val thumbX = minX + (maxX - minX) * displayed

        drawRoundRect(
            color = colors.track,
            topLeft = Offset(0f, cy - trackPx / 2f),
            size = Size(size.width, trackPx),
            cornerRadius = CornerRadius(trackPx / 2f, trackPx / 2f),
        )
        if (thumbX > 1f) {
            drawRoundRect(
                color = colors.accentDeep,
                topLeft = Offset(0f, cy - trackPx / 2f),
                size = Size(thumbX.coerceAtLeast(trackPx), trackPx),
                cornerRadius = CornerRadius(trackPx / 2f, trackPx / 2f),
            )
        }
        drawCircle(
            color = colors.accentDeep,
            radius = radiusPx,
            center = Offset(thumbX, cy),
        )
        drawCircle(
            color = colors.background,
            radius = (radiusPx - strokePx).coerceAtLeast(2.dp.toPx()),
            center = Offset(thumbX, cy),
        )
    }
}

@Composable
internal fun OneUiPlayerPlayPauseButton(
    playing: Boolean,
    label: String,
    colors: PlayerOneUiColors,
    touchSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) 18.dp else if (playing) touchSize / 2f else 24.dp,
        animationSpec = onePlayerSpring(),
        label = "onePlayCorner",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = onePlayerSpring(),
        label = "onePlayScale",
    )

    Box(
        modifier = Modifier
            .size(touchSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(corner))
            .background(colors.accentDeep)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play),
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(colors.onAccent),
        )
    }
}

@Composable
internal fun OneUiPlayerTransportButton(
    icon: Int,
    label: String,
    colors: PlayerOneUiColors,
    touchSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = onePlayerSpring(),
        label = "oneTransportScale",
    )

    Box(
        modifier = Modifier
            .size(touchSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(colors.cardAlt)
            .border(0.75.dp, colors.divider, CircleShape)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(colors.text),
        )
    }
}

@Composable
internal fun OneUiPlayerBottomButton(
    icon: Int,
    label: String,
    selected: Boolean,
    colors: PlayerOneUiColors,
    touchSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = onePlayerSpring(),
        label = "oneBottomScale",
    )
    val bg = if (selected) colors.chip else colors.cardAlt
    val fg = if (selected) colors.onChip else colors.subText

    Box(
        modifier = Modifier
            .size(touchSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(fg),
        )
    }
}

@Composable
internal fun OneUiPlayerMoreButton(
    label: String,
    colors: PlayerOneUiColors,
    buttonSize: Dp,
    iconSize: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = onePlayerSpring(),
        label = "oneMoreScale",
    )

    Box(
        modifier = Modifier
            .size(buttonSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(colors.cardAlt)
            .border(0.75.dp, colors.divider, CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = MiuixIcons.Regular.More,
            contentDescription = label,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(colors.text),
        )
    }
}

@Composable
internal fun OneUiPlayerVolumeSlider(
    colors: PlayerOneUiColors,
    modifier: Modifier = Modifier,
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

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(colors.cardAlt),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_player_volume_muted),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                colorFilter = ColorFilter.tint(colors.subText),
            )
        }
        Spacer(Modifier.width(8.dp))
        OneUiPlayerProgressSlider(
            value = value,
            label = tr("media_volume", "媒体音量"),
            colors = colors,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onChange = { next ->
                dragging = true
                value = next
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, (next * max).roundToInt(), 0)
            },
            onFinished = { dragging = false },
            enabled = !manager.isVolumeFixed,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(colors.cardAlt),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_player_volume_full),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                colorFilter = ColorFilter.tint(colors.accentDeep),
            )
        }
    }
}
