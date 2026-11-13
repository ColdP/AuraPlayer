// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.R
import btm.m.auraplayer.lyrics.LyricLine
import btm.m.auraplayer.ui.Artwork
import btm.m.auraplayer.ui.GlassDialogButton
import btm.m.auraplayer.ui.GlassIconDropdownMenu
import btm.m.auraplayer.ui.WindowDialog
import btm.m.auraplayer.ui.tr
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.*
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.vibrancy
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class SharePreset(val id: String, val title: String, val cover: Boolean, val colors: List<Color>)

private fun sharePresets() = listOf(
    SharePreset("aurora", tr("share_preset_aurora", "极光"), true, listOf(Color(0xff182848), Color(0xff4b6cb7))),
    SharePreset("night", tr("share_preset_night", "深夜"), true, listOf(Color(0xff090909), Color(0xff343434))),
    SharePreset("sunset", tr("share_preset_sunset", "日落"), true, listOf(Color(0xff7b4397), Color(0xffdc2430))),
    SharePreset("ocean", tr("share_preset_ocean", "深海"), true, listOf(Color(0xff005c97), Color(0xff363795))),
    SharePreset("forest", tr("share_preset_forest", "森语"), true, listOf(Color(0xff134e5e), Color(0xff71b280))),
    SharePreset("paper", tr("share_preset_paper", "纸页"), true, listOf(Color(0xff434343), Color(0xff000000))),
    SharePreset("rose", tr("share_preset_rose", "玫瑰"), true, listOf(Color(0xff42275a), Color(0xff734b6d))),
    SharePreset("aurora2", tr("share_preset_mist", "薄雾"), true, listOf(Color(0xff3a6073), Color(0xff16222a))),
    SharePreset("mono", tr("share_preset_mono", "留白"), false, listOf(Color(0xff202020), Color(0xff202020))),
    SharePreset("ink", tr("share_preset_ink", "墨色"), false, listOf(Color(0xff101010), Color(0xff282828))),
    SharePreset("cream", tr("share_preset_cream", "奶油"), false, listOf(Color(0xff4b3a2f), Color(0xff1d1612))),
    SharePreset("glass", tr("share_preset_glass", "玻璃"), false, listOf(Color(0xff283048), Color(0xff859398))),
)

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun LyricSharePage(
    model: AuraViewModel,
    lines: List<LyricLine>,
    selected: Set<Int>,
    generating: Boolean,
    backdrop: Backdrop,
    onToggle: (Int) -> Unit,
    onGenerate: () -> Unit,
    onBack: () -> Unit,
) {
    var preset by remember { mutableStateOf(sharePresets().first()) }
    var translation by remember { mutableStateOf(model.showTranslation) }
    var pronunciation by remember { mutableStateOf(model.showPronunciation) }
    var size by remember { mutableStateOf(model.lyricSize) }
    var optionsOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .12f))) {
        AnimatedContent(generating, transitionSpec = { (fadeIn() + scaleIn(initialScale = .96f)).togetherWith(fadeOut()) }, modifier = Modifier.fillMaxSize(), label = "lyricSharePage") { output ->
            if (!output) {
                Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Image(MiuixIcons.Regular.Close, tr("cancel", "取消"), Modifier.size(24.dp).clickable(onClick = onBack), colorFilter = ColorFilter.tint(Color.White))
                        Text(tr("select_lyrics_to_share", "选择歌词"), Modifier.weight(1f).padding(start = 14.dp), color = Color.White, fontSize = 20.sp)
                        Text("${selected.size}/16", color = Color.White.copy(alpha = .65f), fontSize = 13.sp)
                    }
                    LazyColumn(Modifier.weight(1f).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 100.dp)) {
                        items(lines.indices.toList(), key = { it }) { index ->
                            val checked = index in selected
                            val selectedProgress by androidx.compose.animation.core.animateFloatAsState(if (checked) 1f else 0f, androidx.compose.animation.core.tween(260), label = "lyricSelection")
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).blur(((1f - selectedProgress) * 3f).dp).background(Color.White.copy(alpha = .07f + selectedProgress * .15f))
                                .clickable(enabled = checked || selected.size < 16) { onToggle(index) }.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(lines[index].text, Modifier.weight(1f), color = Color.White.copy(alpha = if (checked) 1f else .78f), fontSize = 16.sp)
                                if (checked) Image(MiuixIcons.Basic.Check, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White))
                            }
                        }
                    }
                    ShareGlassButton(enabled = selected.isNotEmpty(), onClick = onGenerate) { Text(tr("generate_share_image", "生成分享图片"), color = Color.White, fontSize = 16.sp) }
                }
            } else {
                val selectedLines = selected.sorted().mapNotNull { lines.getOrNull(it) }
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Image(MiuixIcons.Regular.Close, tr("cancel", "取消"), Modifier.size(24.dp).clickable(onClick = onBack), colorFilter = ColorFilter.tint(Color.White))
                        Text(tr("share_lyrics", "分享歌词"), Modifier.weight(1f).padding(start = 14.dp), color = Color.White, fontSize = 20.sp)
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)).clickable { exportOpen = true }, contentAlignment = Alignment.Center) {
                            Image(MiuixIcons.Basic.Check, tr("done", "完成"), Modifier.size(21.dp), colorFilter = ColorFilter.tint(Color.White))
                        }
                    }
                    ShareImagePreview(model, selectedLines, preset, translation, pronunciation, size, Modifier.fillMaxWidth().weight(1f).padding(vertical = 18.dp))
                    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                        items(sharePresets(), key = { it.id }) { item ->
                            Column(Modifier.width(92.dp).clickable { preset = item }, horizontalAlignment = Alignment.CenterHorizontally) {
                                ShareImagePreview(model, selectedLines.take(2), item, translation, pronunciation, size, Modifier.size(92.dp).clip(RoundedCornerShape(14.dp)).graphicsLayer { alpha = if (item.id == preset.id) 1f else .62f })
                                Text(item.title, color = Color.White.copy(alpha = if (item.id == preset.id) 1f else .65f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                    val menuItems = listOf(
                        DropdownItem(tr("show_translation", "显示翻译"), selected = translation, onClick = { translation = !translation }),
                        DropdownItem(tr("show_pronunciation", "显示发音"), selected = pronunciation, onClick = { pronunciation = !pronunciation }),
                        DropdownItem(tr("lyrics_size", "歌词大小"), enabled = false),
                    ) + btm.m.auraplayer.lyrics.LyricSize.entries.map { value -> DropdownItem(lyricSizeLabel(value), selected = size == value, onClick = { size = value }) }
                    GlassIconDropdownMenu(DropdownEntry(menuItems), backdrop = null, onExpandedChange = { optionsOpen = it }, trigger = { toggle, enabled ->
                        Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)).clickable(enabled = enabled) { toggle() }, contentAlignment = Alignment.Center) {
                            Image(painterResource(R.drawable.ic_player_translate), tr("lyrics_options", "歌词选项"), Modifier.size(23.dp), colorFilter = ColorFilter.tint(Color.White))
                        }
                    }, content = {})
                }
            }
        }
    }
    WindowDialog(exportOpen, title = tr("export_share_image", "导出分享图片"), onDismissRequest = { exportOpen = false }) {
        ShareImagePreview(model, selected.sorted().mapNotNull { lines.getOrNull(it) }, preset, translation, pronunciation, size, Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(18.dp)))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ exportOpen = false }, Modifier.weight(1f)) { Text(tr("export", "导出")) }
            GlassDialogButton({ exportOpen = false }, Modifier.weight(1f)) { Text(tr("share", "分享")) }
        }
        GlassDialogButton({ exportOpen = false }, Modifier.fillMaxWidth().padding(top = 10.dp)) { Text(tr("cancel", "取消")) }
    }
}

@Composable
internal fun LyricShareRoute(model: AuraViewModel, padding: PaddingValues, backdrop: Backdrop?, onBack: () -> Unit) {
    var selected by rememberSaveable {
        mutableStateOf(model.lyricShareInitialIndex.takeIf { it >= 0 }?.let { setOf(it) } ?: emptySet())
    }
    var generated by rememberSaveable { mutableStateOf(false) }
    val lines = model.lyrics
    // The route is already hosted inside AppPage's recorded surface. Passing that same
    // backdrop into another drawBackdrop creates a recursive RenderNode tree on Android 17.
    // Keep the page's controls on the stable translucent MIUIX surface instead.
    LyricSharePage(model, lines, selected, generated, backdrop ?: return, { index ->
        selected = if (index in selected) selected - index else if (selected.size < 16) selected + index else selected
    }, { generated = true }, onBack)
}

@Composable
private fun ShareGlassButton(enabled: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(54.dp)
        .clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = if (enabled) .14f else .06f))
        .clickable(enabled = enabled, onClick = onClick), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun ShareImagePreview(model: AuraViewModel, lines: List<LyricLine>, preset: SharePreset, translation: Boolean, pronunciation: Boolean, size: btm.m.auraplayer.lyrics.LyricSize, modifier: Modifier) {
    Box(modifier.background(Brush.verticalGradient(preset.colors)).clip(RoundedCornerShape(18.dp)).padding(18.dp)) {
        if (preset.cover && model.current?.artwork?.isNotBlank() == true) Artwork(model.current?.artwork.orEmpty(), Modifier.fillMaxSize().blur(24.dp), cornerRadius = 18.dp)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (preset.cover) .34f else .08f)))
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            lines.forEach { line ->
                Text(line.text, color = Color.White, fontSize = (16f * size.scale).sp, modifier = Modifier.padding(vertical = 3.dp))
                if (pronunciation && !line.roma.isNullOrBlank()) Text(line.roma.orEmpty(), color = Color.White.copy(alpha = .68f), fontSize = (11f * size.scale).sp)
                if (translation && !line.translation.isNullOrBlank()) Text(line.translation.orEmpty(), color = Color.White.copy(alpha = .72f), fontSize = (11f * size.scale).sp)
            }
        }
    }
}
