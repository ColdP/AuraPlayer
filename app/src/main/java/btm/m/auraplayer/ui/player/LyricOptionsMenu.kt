// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.R
import btm.m.auraplayer.lyrics.LyricSize
import btm.m.auraplayer.ui.tr
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop

internal fun lyricSizeLabel(size: LyricSize): String = when (size) {
    LyricSize.EXTRA_SMALL -> tr("lyrics_size_extra_small", "极小")
    LyricSize.SMALL -> tr("lyrics_size_small", "小")
    LyricSize.REGULAR -> tr("lyrics_size_regular", "常规")
    LyricSize.LARGE -> tr("lyrics_size_large", "大")
    LyricSize.EXTRA_LARGE -> tr("lyrics_size_extra_large", "极大")
}

/** A single glass surface expands up from the button; its bottom-left corner stays anchored. */
@Composable
internal fun LyricOptionsMenu(
    model: AuraViewModel, backdrop: Backdrop, monetTint: Color,
    anchorX: Dp, anchorY: Dp, screenWidth: Dp, topInset: Dp,
    modifier: Modifier = Modifier,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val tint by animateColorAsState(monetTint, tween(1000), label = "lyricMenuMonet")
    LaunchedEffect(expanded) {
        onExpandedChange(expanded)
        progress.animateTo(if (expanded) 1f else 0f,
            spring(.88f, if (expanded) 360f else 430f, visibilityThreshold = .0005f))
    }
    DisposableEffect(Unit) { onDispose { onExpandedChange(false) } }
    BackHandler(expanded) { expanded = false }
    val p = progress.value.coerceIn(0f, 1f)
    val buttonSize = 44.dp
    val menuWidth = (minOf(260.dp, (screenWidth - anchorX - 16.dp).coerceAtLeast(buttonSize)) * .9f).coerceAtLeast(buttonSize)
    // Seven 44dp rows plus the divider and size label fit in this compact surface. Keeping the
    // cap close to the measured content prevents a large empty tail below “极大”.
    val menuHeight = minOf(348.dp, (anchorY + buttonSize - topInset).coerceAtLeast(buttonSize))
    val width = buttonSize + (menuWidth - buttonSize) * p
    val height = buttonSize + (menuHeight - buttonSize) * p
    val shape = RoundedCornerShape(22.dp + 4.dp * p)
    Box(Modifier.fillMaxSize().then(modifier)) {
        if (expanded || p > 0f) Box(Modifier.fillMaxSize()
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null) { expanded = false })
        Box(
            Modifier.offset(anchorX, anchorY + buttonSize - height).size(width, height)
                .playerMenuMaterial(backdrop, tint, 22.dp + 4.dp * p)
                .clip(shape)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null, enabled = !expanded, onClick = { expanded = true }),
            contentAlignment = Alignment.Center,
        ) {
            // Keep the menu measured at its final size while the enclosing glass changes shape.
            Box(Modifier.matchParentSize().clip(shape)) {
                if (p > 0f) Column(Modifier.width(menuWidth).heightIn(max = menuHeight)
                    .graphicsLayer { alpha = ((p - .2f) / .8f).coerceIn(0f, 1f) }
                    .blur(((1f - p) * 12).dp)
                    .verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 0.dp)) {
                    OptionRow(
                        if (model.showPronunciation) tr("hide_pronunciation", "隐藏发音") else tr("show_pronunciation", "显示发音"),
                        model.showPronunciation, enabled = expanded,
                    ) { model.lyricsOptions(model.showTranslation, !model.showPronunciation) }
                    OptionRow(
                        if (model.showTranslation) tr("hide_translation", "隐藏翻译") else tr("show_translation", "显示翻译"),
                        model.showTranslation, enabled = expanded,
                    ) { model.lyricsOptions(!model.showTranslation, model.showPronunciation) }
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 7.dp).fillMaxWidth()
                        .height(1.dp).background(Color.White.copy(alpha = .16f)))
                    Text(tr("lyrics_size", "歌词大小"), color = Color.White.copy(alpha = .65f), fontSize = 12.sp,
                        modifier = Modifier.padding(start = 18.dp, bottom = 4.dp))
                    LyricSize.entries.forEach { size ->
                        OptionRow(lyricSizeLabel(size), model.lyricSize == size, enabled = expanded) {
                            model.changeLyricSize(size)
                        }
                    }
                }
                val iconAlpha = (1f - p * 2f).coerceIn(0f, 1f)
                if (iconAlpha > 0f) Image(painterResource(R.drawable.ic_player_translate), tr("lyrics_options", "歌词选项"),
                    Modifier.align(Alignment.BottomStart).size(buttonSize).padding(10.dp)
                        .graphicsLayer { alpha = iconAlpha }.blur((p * 16).dp),
                    colorFilter = ColorFilter.tint(Color.White))
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).clickable(enabled = enabled, onClick = onClick)
        .semantics { selected = checked }.padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f))
        if (checked) Image(painterResource(R.drawable.ic_player_check), null,
            Modifier.size(19.dp), colorFilter = ColorFilter.tint(Color.White))
    }
}
