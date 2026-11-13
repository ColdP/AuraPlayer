// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from HyperChanger NavigationMaterialPage; Aura owns its local settings and preview.
package btm.m.auraplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.R
import btm.m.auraplayer.data.Song
import btm.m.auraplayer.ui.player.PlayerNavigationGeometry
import btm.m.auraplayer.ui.player.miniPlayerMaterial
import btm.m.auraplayer.ui.navigation.NavigationMaterialSettings
import btm.m.auraplayer.ui.navigation.NavigationStyle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
internal fun NavigationMaterialPage(model: AuraViewModel, padding: PaddingValues, style: NavigationStyle) {
    val store = model.app.navigationMaterial
    val materials by store.state.collectAsState()
    val material = materials[style] ?: NavigationMaterialSettings.defaults(style)
    val defaults = NavigationMaterialSettings.defaults(style)
    val cover = rememberRandomLocalCover(model)
    var confirmReset by remember { mutableStateOf(false) }
    DisposableEffect(store) { onDispose { store.save() } }

    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "preview") {
            NavigationMaterialPreview(model, style, cover)
        }
        item(key = style.preferenceValue) {
            Group(tr("nav_material_parameters", "参数调整")) {
                MaterialPercent(tr("nav_material_blur", "模糊度"), material.blur, 300, defaults.blur,
                    { store.update(style) { value -> value.copy(blur = it) } }, store::save)
                MaterialPercent(tr("nav_material_opacity", "背景不透明度"), material.opacity, 250, defaults.opacity,
                    { store.update(style) { value -> value.copy(opacity = it) } }, store::save)
                MaterialPercent(tr("nav_material_refraction", "折射强度"), material.refraction, 200, defaults.refraction,
                    { store.update(style) { value -> value.copy(refraction = it) } }, store::save)
                MaterialPercent(tr("nav_material_thickness", "折射厚度"), material.thickness, 200, defaults.thickness,
                    { store.update(style) { value -> value.copy(thickness = it) } }, store::save)
                MaterialPercent(tr("nav_material_highlight", "高光强度"), material.highlight, 200, defaults.highlight,
                    { store.update(style) { value -> value.copy(highlight = it) } }, store::save)
                if (style == NavigationStyle.HYPER_OS_FLOATING) {
                    MaterialPercent(tr("nav_material_selection", "选中胶囊不透明度"), material.selectionOpacity, 300, defaults.selectionOpacity,
                        { store.update(style) { value -> value.copy(selectionOpacity = it) } }, store::save)
                } else {
                    MaterialPercent(tr("nav_material_press", "按压折射增强"), material.pressBoost, 200, defaults.pressBoost,
                        { store.update(style) { value -> value.copy(pressBoost = it) } }, store::save)
                }
                SwitchPreference(tr("nav_material_dispersion", "色散"), checked = material.dispersion ?: (style == NavigationStyle.LIQUID_GLASS),
                    onCheckedChange = { checked -> store.update(style) { it.copy(dispersion = checked) }; store.save() })
                Preference(tr("nav_material_reset", "恢复默认参数")) { confirmReset = true }
            }
        }
    }
    WindowDialog(confirmReset, title = tr("nav_material_reset", "恢复默认参数"),
        summary = tr("nav_material_reset_confirm", "确定恢复当前样式的默认材质参数吗？"),
        titleTextAlign = TextAlign.Start, summaryTextAlign = TextAlign.Start,
        onDismissRequest = { confirmReset = false }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ confirmReset = false }, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
            GlassDialogButton({ store.reset(style); confirmReset = false }, Modifier.weight(1f)) { Text(tr("nav_material_reset", "恢复默认参数")) }
        }
    }
}

@Composable
private fun MaterialPercent(title: String, value: Int, max: Int, default: Int, change: (Int) -> Unit, save: () -> Unit) {
    EditableSliderPreference(title = title, value = value.toFloat(), valueText = tr("nav_material_percent", "%d%%").format(value),
        valueRange = 0f..max.toFloat(), steps = max - 1, defaultValue = default.toFloat(),
        onValueChange = { change(it.roundToInt()) }, onValueChangeFinished = save)
}

private data class LocalCoverPreview(val song: Song, val image: ImageBitmap)

@Composable
private fun rememberRandomLocalCover(model: AuraViewModel): LocalCoverPreview? {
    val context = LocalContext.current
    // Randomize once per visit, never when a slider changes. Retry missing artwork
    // with other local albums, using Coil's existing bounded decoding/cache pipeline.
    val candidates = remember(model.songs) {
        model.songs.asSequence().filter { !it.cloud && it.artwork.isNotBlank() }
            .toList().shuffled().distinctBy { it.artwork }.take(32)
    }
    val cover by produceState<LocalCoverPreview?>(null, candidates, context) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            val loader = SingletonImageLoader.get(context)
            for (song in candidates) {
                currentCoroutineContext().ensureActive()
                val result = loader.execute(ImageRequest.Builder(context).data(song.artwork).size(960).allowHardware(false).build())
                if (result is SuccessResult) return@withContext LocalCoverPreview(song, result.image.toBitmap().asImageBitmap())
            }
            null
        }
    }
    return cover
}

@Composable
private fun NavigationMaterialPreview(model: AuraViewModel, style: NavigationStyle, cover: LocalCoverPreview?) {
    val backdrop = rememberLayerBackdrop()
    val selected = remember { mutableIntStateOf(0) }
    var searchPressed by remember { mutableStateOf(false) }
    val surface = MiuixTheme.colorScheme.surfaceContainer
    SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
        Box(Modifier.fillMaxWidth().height(310.dp).clip(RoundedCornerShape(22.5.dp))) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(surface)) {
                val image = cover
                if (image != null) Image(image.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(tr("nav_material_no_cover", "暂无可用的本地音乐封面"),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (searchPressed) Text(tr("nav_material_search_preview", "搜索按钮已响应"),
                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).background(surface, RoundedCornerShape(12.dp)).padding(12.dp))
            if (cover != null) {
                val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    PlayerNavigationGeometry.bottomSpacing + PlayerNavigationGeometry.barHeight + PlayerNavigationGeometry.miniGap
                MaterialMiniPlayerPreview(cover, backdrop, style.preferenceValue,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = bottom).padding(horizontal = PlayerNavigationGeometry.outerInset))
            }
            AuraBottomBar(selected, model, backdrop, Modifier.align(Alignment.BottomCenter),
                searchMounted = false, searchProgress = 0f, secondaryProgress = 0f,
                onSearch = { searchPressed = !searchPressed }, onPositioned = {}, navigationStyle = style.preferenceValue)
        }
    }
}

@Composable
private fun MaterialMiniPlayerPreview(cover: LocalCoverPreview, backdrop: com.kyant.backdrop.Backdrop, style: String, modifier: Modifier) {
    val color = if (MiuixTheme.colorScheme.surface.luminance() < .5f) Color.White else Color.Black
    val shape = RoundedCornerShape(PlayerNavigationGeometry.miniHeight / 2)
    Row(modifier.fillMaxWidth().height(PlayerNavigationGeometry.miniHeight)
        .miniPlayerMaterial(backdrop, style, shape).clip(shape).padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Image(cover.image, null, Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(cover.song.title, color = color, fontSize = 14.sp, lineHeight = 17.5.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(cover.song.artist, color = color.copy(alpha = .8f), fontSize = 11.2.sp, lineHeight = 14.7.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Decorative controls: the preview never changes the real playback session.
        Spacer(Modifier.width(10.dp))
        for (icon in listOf(R.drawable.ic_player_play, R.drawable.ic_player_next)) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Image(painterResource(icon), null, Modifier.size(38.dp * .8f * .92f * .85f), colorFilter = ColorFilter.tint(color))
            }
            if (icon == R.drawable.ic_player_play) Spacer(Modifier.width(10.dp))
        }
    }
}
