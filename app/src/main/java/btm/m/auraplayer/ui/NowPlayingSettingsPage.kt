package btm.m.auraplayer.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.playback.*
import btm.m.auraplayer.spatialcover.SpatialDepthModel
import btm.m.auraplayer.spatialcover.SpatialMattingModel
import btm.m.auraplayer.spatialcover.SpatialModelStore
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.roundToInt

@Composable
fun NowPlayingSettingsPage(padding: PaddingValues, open: (String) -> Unit = {}) {
    val context = LocalContext.current
    val store = (context.applicationContext as AuraApplication).nowPlaying
    val state by store.state.collectAsState()
    val transition = updateTransition(state.hasBlurControl, label = "player-blur-setting")
    val blur by transition.animateFloat(transitionSpec = { tween(220) }, label = "blur") { if (it) 0f else 8f }
    val immersion = updateTransition(state.immersiveLyrics, label = "player-immersive-setting")
    val immersionBlur by immersion.animateFloat(transitionSpec = { tween(220) }, label = "blur") { if (it) 0f else 8f }
    val fullScreen = updateTransition(state.theme == PlayerTheme.FULLSCREEN, label = "player-glass-setting")
    val glassBlur by fullScreen.animateFloat(transitionSpec = { tween(220) }, label = "blur") { if (it) 0f else 8f }
    val materialSetting = updateTransition(state.theme == PlayerTheme.MATERIAL, label = "player-material-setting")
    val materialBlur by materialSetting.animateFloat(transitionSpec = { tween(220) }, label = "blur") { if (it) 0f else 8f }
    var pendingLyricTheme by remember { mutableStateOf<LyricTheme?>(null) }
    var pendingSpatialNotice by remember { mutableStateOf(false) }
    var pendingSpatialCoverNotice by remember { mutableStateOf(false) }
    val lyricThemeItems = lyricThemeLabels()
    val selectedDepthModel = SpatialDepthModel.byId(state.spatialCoverDepthModel)
    val selectedMattingModel = SpatialMattingModel.byId(state.spatialCoverMattingModel)
    val spatialModelsReady = remember(context, selectedDepthModel, selectedMattingModel, state.spatialCover) {
        SpatialModelStore.readySelectionSummary(context, selectedDepthModel, selectedMattingModel)
    }
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Group(tr("appearance", "外观")) {
                GlassDropdownPreference(tr("player_interface_theme", "界面主题"),
                    listOf(
                        tr("player_theme_aurora", "流光"),
                        tr("player_theme_aurora_blur", "流光模糊"),
                        tr("player_theme_blur", "模糊"),
                        tr("player_theme_color", "颜色"),
                        tr("player_theme_fullscreen", "全屏"),
                        tr("player_theme_material", "Material"),
                        tr("player_theme_one", "One"),
                    ), state.theme.ordinal, backdrop = LocalToolbarBackdrop.current) { index ->
                    store.update { it.copy(theme = PlayerTheme.entries[index]) }
                }
                materialSetting.AnimatedVisibility(visible = { it }, enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(220)) + shrinkVertically(tween(220))) {
                    Box(Modifier.blur(materialBlur.dp)) {
                        Column {
                            GlassDropdownPreference(
                                tr("player_material_background", "页面背景"),
                                listOf(
                                    tr("player_theme_material", "Material"),
                                    tr("player_theme_blur", "模糊"),
                                    tr("player_theme_aurora", "流光"),
                                    tr("player_theme_aurora_blur", "流光模糊"),
                                ),
                                state.materialBackground.ordinal,
                                backdrop = LocalToolbarBackdrop.current,
                            ) { index ->
                                store.update { it.copy(materialBackground = MaterialPlayerBackground.entries[index]) }
                            }
                            SliderPreference(
                                tr("player_material_color_mix", "Material 混色强度"),
                                state.materialColorMix.toFloat(),
                                0f..100f,
                                "${state.materialColorMix}%",
                                steps = 99,
                                defaultValue = 50f,
                            ) { value -> store.update { it.copy(materialColorMix = value.roundToInt()) } }
                        }
                    }
                }
                fullScreen.AnimatedVisibility(visible = { it }, enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(220)) + shrinkVertically(tween(220))) {
                    Box(Modifier.blur(glassBlur.dp)) {
                        SwitchPreference(tr("player_glass_effect", "玻璃效果"), checked = state.fullscreenGlass) { value -> store.update { it.copy(fullscreenGlass = value) } }
                    }
                }
                transition.AnimatedVisibility(visible = { it }, enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(220)) + shrinkVertically(tween(220))) {
                    Box(Modifier.blur(blur.dp)) {
                        SliderPreference(tr("player_blur_amount", "模糊程度"), state.blur.toFloat(), -64f..96f,
                            state.blur.toString(), steps = 159, defaultValue = 0f) { value -> store.update { it.copy(blur = value.roundToInt()) } }
                    }
                }
                GlassDropdownPreference(
                    tr("player_glow_progress", "炫光进度条"),
                    listOf(
                        tr("player_glow_off", "不开启"),
                        tr("player_glow_hyper", "Hyper"),
                        tr("player_glow_one", "One"),
                    ),
                    state.glowProgress.ordinal,
                    backdrop = LocalToolbarBackdrop.current,
                ) { index ->
                    store.update { it.copy(glowProgress = GlowProgressStyle.entries[index]) }
                }
                GlassDropdownPreference(
                    tr("player_lyric_theme", "歌词主题"),
                    lyricThemeItems,
                    state.lyricTheme.ordinal,
                    backdrop = LocalToolbarBackdrop.current,
                ) { index ->
                    val picked = LyricTheme.entries[index]
                    if (shouldPromptLyricThemePowerNotice(picked, state.lyricThemePowerNoticeDismissed)) {
                        pendingLyricTheme = picked
                    } else {
                        store.update { it.copy(lyricTheme = picked) }
                    }
                }
                GlassDropdownPreference(tr("player_special_effect", "特殊效果"),
                    listOf(tr("player_effect_none", "不显示"), tr("player_effect_snow", "降雪"), tr("player_effect_rain", "降雨")),
                    state.weather.ordinal, backdrop = LocalToolbarBackdrop.current) { index -> store.update { it.copy(weather = PlayerWeather.entries[index]) } }
                GlassDropdownPreference(tr("player_cover_animation", "切歌时的封面动画"),
                    listOf(tr("default", "默认"), tr("player_cover_card", "卡片"), tr("player_theme_blur", "模糊"), tr("player_cover_cover", "覆盖"),
                        tr("player_cover_page", "翻页"), tr("player_cover_stack", "堆叠"), tr("player_cover_vertical_flip", "垂直翻转"), tr("player_cover_horizontal_flip", "水平翻转")),
                    state.coverAnimation.ordinal, backdrop = LocalToolbarBackdrop.current) { index -> store.update { it.copy(coverAnimation = CoverAnimation.entries[index]) } }
                SwitchPreference(tr("player_immersive_cover", "沉浸式封面"), checked = state.immersiveCover) { value -> store.update { it.copy(immersiveCover = value) } }
                SwitchPreference(
                    title = tr("player_apple_music_animated_cover", "Apple Music 动态封面"),
                    summary = tr("player_apple_music_animated_cover_summary", "获取到动态封面时强制使用沉浸式封面"),
                    checked = state.appleMusicAnimatedCover,
                ) { value -> store.update { it.copy(appleMusicAnimatedCover = value) } }
                SwitchPreference(tr("player_show_volume", "显示音量控件"), checked = state.showVolume) { value -> store.update { it.copy(showVolume = value) } }
            }
        }
        item {
            Group(tr("player_functions", "功能")) {
                SwitchPreference(tr("player_open_notification", "从媒体通知进入时显示正在播放界面"), checked = state.openFromNotification) { value -> store.update { it.copy(openFromNotification = value) } }
                SwitchPreference(tr("player_lyrics_awake", "显示歌词时屏幕常亮"), checked = state.keepLyricsAwake) { value -> store.update { it.copy(keepLyricsAwake = value) } }
                SwitchPreference(tr("player_lyrics_immersive", "歌词自动沉浸"), checked = state.immersiveLyrics) { value -> store.update { it.copy(immersiveLyrics = value) } }
                immersion.AnimatedVisibility(visible = { it }, enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(220)) + shrinkVertically(tween(220))) {
                    Box(Modifier.blur(immersionBlur.dp)) {
                        SliderPreference(tr("player_immersive_delay", "沉浸等待时间"), state.immersiveSeconds.toFloat(), 0f..30f,
                            "${state.immersiveSeconds} s", steps = 29, defaultValue = 5f) { value -> store.update { it.copy(immersiveSeconds = value.roundToInt()) } }
                    }
                }
                SwitchPreference(tr("player_show_audio_info", "正在播放界面显示音频信息"), checked = state.showAudioInfo) { value -> store.update { it.copy(showAudioInfo = value) } }
                SwitchPreference(tr("player_swipe_tracks", "左右滑动切歌"), checked = state.swipeTracks) { value -> store.update { it.copy(swipeTracks = value) } }
            }
        }
        item {
            Group(tr("experimental_features", "实验性功能")) {
                SwitchPreference(
                    title = tr("player_spatial_lyrics", "空间歌词效果"),
                    checked = state.spatialLyrics,
                ) { value ->
                    if (shouldPromptSpatialLyricsPowerNotice(value, state.spatialLyricsPowerNoticeDismissed)) {
                        pendingSpatialNotice = true
                    } else {
                        store.update { it.copy(spatialLyrics = value) }
                    }
                }
                SwitchPreference(
                    title = tr("player_spatial_cover", "空间封面效果"),
                    checked = state.spatialCover,
                ) { value ->
                    if (shouldPromptSpatialCoverPowerNotice(value, state.spatialCoverPowerNoticeDismissed)) {
                        pendingSpatialCoverNotice = true
                    } else {
                        store.update { it.copy(spatialCover = value) }
                    }
                }
                BlurAnimatedVisibility(
                    visible = state.spatialCover,
                    label = "player-spatial-cover-setting",
                ) {
                    Column {
                        GlassDropdownPreference(
                            tr("player_spatial_depth_model", "空间渲染模型"),
                            SpatialDepthModel.entries.map { it.displayName },
                            SpatialDepthModel.entries.indexOf(selectedDepthModel).coerceAtLeast(0),
                            backdrop = LocalToolbarBackdrop.current,
                        ) { index ->
                            store.update { it.copy(spatialCoverDepthModel = SpatialDepthModel.entries[index].id) }
                        }
                        GlassDropdownPreference(
                            tr("player_spatial_matting_model", "抠图模型"),
                            SpatialMattingModel.entries.map { it.displayName },
                            SpatialMattingModel.entries.indexOf(selectedMattingModel).coerceAtLeast(0),
                            backdrop = LocalToolbarBackdrop.current,
                        ) { index ->
                            store.update { it.copy(spatialCoverMattingModel = SpatialMattingModel.entries[index].id) }
                        }
                        Preference(
                            title = tr("spatial_model_downloads", "模型下载"),
                            summary = if (spatialModelsReady) {
                                tr("spatial_models_ready_summary", "运行库与当前所选模型已下载就绪")
                            } else {
                                tr("spatial_models_missing_summary", "运行库或所选模型尚未下载，点击前往下载")
                            },
                        ) { open("spatial_cover_downloads") }
                        Preference(
                            title = tr("spatial_cover_cache_title", "空间封面数据缓存"),
                            summary = tr("spatial_cover_cache_page_summary", "查看、全屏预览或管理本地已生成的空间封面缓存"),
                        ) { open("spatial_cover_cache") }
                    }
                }
            }
        }
    }
    LyricThemePowerWarningDialog(
        pendingTheme = pendingLyricTheme,
        onDismiss = { pendingLyricTheme = null },
        onConfirm = { chosen, dontShowAgain ->
            store.update {
                it.copy(
                    lyricTheme = chosen,
                    lyricThemePowerNoticeDismissed = it.lyricThemePowerNoticeDismissed || dontShowAgain,
                )
            }
            pendingLyricTheme = null
        },
    )
    SpatialLyricsPowerWarningDialog(
        show = pendingSpatialNotice,
        onDismiss = { pendingSpatialNotice = false },
        onConfirm = { dontShowAgain ->
            store.update {
                it.copy(
                    spatialLyrics = true,
                    spatialLyricsPowerNoticeDismissed = it.spatialLyricsPowerNoticeDismissed || dontShowAgain,
                )
            }
            pendingSpatialNotice = false
        },
    )
    SpatialCoverPowerWarningDialog(
        show = pendingSpatialCoverNotice,
        onDismiss = { pendingSpatialCoverNotice = false },
        onConfirm = { dontShowAgain ->
            store.update {
                it.copy(
                    spatialCover = true,
                    spatialCoverPowerNoticeDismissed = it.spatialCoverPowerNoticeDismissed || dontShowAgain,
                )
            }
            pendingSpatialCoverNotice = false
        },
    )
}

internal fun lyricThemeLabels(): List<String> = listOf(
    tr("player_theme_blur", "模糊"),
    tr("lyric_theme_deep_star_sea", "深潜星海"),
    tr("lyric_theme_afterglow_shore", "余晖漫岸"),
    tr("lyric_theme_streaming_shadows", "流光碎影"),
    tr("lyric_theme_light_year_drift", "光年漫溯"),
    tr("lyric_theme_neon_city", "霓虹城市"),
    tr("lyric_theme_wind_letter", "风起信笺"),
    tr("lyric_theme_star_moon", "碎星揽月"),
    tr("lyric_theme_lime_soda", "青柠苏打"),
    tr("lyric_theme_monet_garden", "莫奈花园"),
    tr("lyric_theme_faded_film", "褪色胶片"),
)

@Composable
internal fun LyricThemePowerWarningDialog(
    pendingTheme: LyricTheme?,
    onDismiss: () -> Unit,
    onConfirm: (theme: LyricTheme, dontShowAgain: Boolean) -> Unit,
) {
    CountdownPowerWarningDialog(
        show = pendingTheme != null,
        title = tr("player_lyric_theme_power_title", "功耗提示"),
        summary = tr(
            "player_lyric_theme_power_summary",
            "该歌词主题包含动态光影、特殊字体渲染或 3D 视效，可能会提高设备功耗并增加耗电与发热，是否确认切换？",
        ),
        onDismiss = onDismiss,
        onConfirm = { dontShowAgain ->
            val chosen = pendingTheme
            if (chosen != null) onConfirm(chosen, dontShowAgain) else onDismiss()
        },
    )
}

@Composable
internal fun SpatialLyricsPowerWarningDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (dontShowAgain: Boolean) -> Unit,
) {
    CountdownPowerWarningDialog(
        show = show,
        title = tr("player_spatial_lyrics_power_title", "实验性功能与功耗提示"),
        summary = tr(
            "player_spatial_lyrics_power_summary",
            "空间歌词效果将持续读取陀螺仪传感器并实时计算多层 3D 景深视差，可能会明显增加设备功耗、耗电与发热。\n\n请注意：该功能为实验性功能，在未来的版本更新中随时可能调整或删除，是否确认开启？",
        ),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
internal fun SpatialCoverPowerWarningDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (dontShowAgain: Boolean) -> Unit,
) {
    CountdownPowerWarningDialog(
        show = show,
        title = tr("player_spatial_cover_power_title", "实验性功能与功耗提示"),
        summary = tr(
            "player_spatial_cover_power_summary",
            "空间封面效果需要在本地运行深度估计与主体抠图神经网络模型，并结合陀螺仪实时执行三维视差着色计算，可能会显著增加功耗、发热与耗电，同时占用一定本地缓存空间。\n\n请注意：该功能为实验性功能，在未来的版本更新中随时可能调整或删除，是否确认开启？",
        ),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
private fun CountdownPowerWarningDialog(
    show: Boolean,
    title: String,
    summary: String,
    onDismiss: () -> Unit,
    onConfirm: (dontShowAgain: Boolean) -> Unit,
) {
    var noticeCountdown by remember { mutableIntStateOf(3) }
    LaunchedEffect(show) {
        if (!show) return@LaunchedEffect
        noticeCountdown = 3
        while (noticeCountdown > 0) {
            delay(1000L)
            noticeCountdown--
        }
    }
    WindowDialog(
        show = show,
        title = title,
        summary = summary,
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val suffix = if (noticeCountdown > 0) " (${noticeCountdown}s)" else ""
            GlassDialogButton(
                onClick = { onConfirm(true) },
                modifier = Modifier.fillMaxWidth(),
                enabled = noticeCountdown == 0,
            ) {
                Text(tr("dont_show_again", "不再提示") + suffix)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassDialogButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(tr("cancel", "取消"))
                }
                GlassDialogButton(
                    onClick = { onConfirm(false) },
                    modifier = Modifier.weight(1f),
                    enabled = noticeCountdown == 0,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(tr("got_it", "了解") + suffix)
                }
            }
        }
    }
}
