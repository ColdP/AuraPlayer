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
                GlassDropdownPreference(
                    tr("player_color_theme", "颜色主题"),
                    listOf(
                        tr("player_color_theme_follow_app", "跟随应用"),
                        tr("player_color_theme_light", "浅色主题"),
                        tr("player_color_theme_dark", "深色主题"),
                    ),
                    state.colorThemeFor().ordinal,
                    backdrop = LocalToolbarBackdrop.current,
                ) { index ->
                    store.update { it.withColorTheme(PlayerColorTheme.entries[index]) }
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
                GlassDropdownPreference(tr("player_special_effect", "特殊效果"),
                    playerWeatherLabels(),
                    state.weather.ordinal, backdrop = LocalToolbarBackdrop.current) { index -> store.update { it.copy(weather = PlayerWeather.entries[index]) } }
                GlassDropdownPreference(tr("player_cover_animation", "切歌时的封面动画"),
                    listOf(tr("default", "默认"), tr("player_cover_card", "卡片"), tr("player_theme_blur", "模糊"), tr("player_cover_cover", "覆盖"),
                        tr("player_cover_page", "翻页"), tr("player_cover_stack", "堆叠"), tr("player_cover_vertical_flip", "垂直翻转"), tr("player_cover_horizontal_flip", "水平翻转")),
                    state.coverAnimation.ordinal, backdrop = LocalToolbarBackdrop.current) { index -> store.update { it.copy(coverAnimation = CoverAnimation.entries[index]) } }
                GlassDropdownPreference(
                    tr("player_cover_theme", "封面主题"),
                    listOf(
                        tr("player_cover_theme_square", "方形"),
                        tr("player_cover_theme_coverflow", "Coverflow"),
                        tr("player_cover_theme_tiled", "平铺"),
                    ),
                    state.coverTheme.ordinal,
                    backdrop = LocalToolbarBackdrop.current,
                ) { index ->
                    store.update { it.copy(coverTheme = CoverTheme.entries[index]) }
                }
                BlurAnimatedVisibility(state.coverTheme == CoverTheme.COVERFLOW, label = "player-coverflow-parallax-setting") {
                    Column {
                        SwitchPreference(
                            title = tr("player_coverflow_parallax", "Coverflow 3D 体感视差"),
                            summary = tr("player_coverflow_parallax_summary", "跟随手机倾斜产生 Coverflow 分层立体视差与 3D 倾角，无需加载空间模型"),
                            checked = state.coverflowParallax,
                        ) { value -> store.update { it.copy(coverflowParallax = value) } }
                        ParallaxSensitivitySlider(
                            visible = state.coverflowParallax,
                            label = "player-coverflow-parallax-sensitivity-setting",
                            title = tr("player_parallax_tilt_sensitivity", "倾斜灵敏度"),
                            value = state.coverflowParallaxSensitivity,
                        ) { snapped ->
                            store.update { it.copy(coverflowParallaxSensitivity = snapped) }
                        }
                    }
                }
                BlurAnimatedVisibility(state.coverTheme == CoverTheme.TILED, label = "player-tiled-dwell-setting") {
                    Column {
                        SliderPreference(
                            title = tr("player_tiled_switch_dwell", "停留播放时长"),
                            value = state.tiledSwitchDwellSeconds,
                            range = 0.1f..5.0f,
                            valueText = "${"%.1f".format(state.tiledSwitchDwellSeconds)} s",
                            steps = 48,
                            defaultValue = 2.0f,
                        ) { value ->
                            val snapped = ((value * 10f).roundToInt().coerceIn(1, 50)) / 10f
                            store.update { it.copy(tiledSwitchDwellSeconds = snapped) }
                        }
                        SwitchPreference(
                            title = tr("player_tiled_parallax", "平铺 3D 体感视差"),
                            summary = tr("player_tiled_parallax_summary", "跟随手机倾斜让中心主封面与平铺封面墙产生悬浮景深与 3D 倾角，无需加载空间模型"),
                            checked = state.tiledParallax,
                        ) { value -> store.update { it.copy(tiledParallax = value) } }
                        ParallaxSensitivitySlider(
                            visible = state.tiledParallax,
                            label = "player-tiled-parallax-sensitivity-setting",
                            title = tr("player_parallax_tilt_sensitivity", "倾斜灵敏度"),
                            value = state.tiledParallaxSensitivity,
                        ) { snapped ->
                            store.update { it.copy(tiledParallaxSensitivity = snapped) }
                        }
                    }
                }
                BlurAnimatedVisibility(state.coverTheme == CoverTheme.SQUARE, label = "player-immersive-cover-setting") {
                    SwitchPreference(
                        tr("player_immersive_cover", "沉浸式封面"),
                        checked = state.immersiveCover,
                        enabled = state.coverTheme == CoverTheme.SQUARE,
                    ) { value ->
                        if (state.coverTheme == CoverTheme.SQUARE) {
                            store.update { it.copy(immersiveCover = value) }
                        }
                    }
                }
                SwitchPreference(
                    title = tr("player_apple_music_animated_cover", "Apple Music 动态封面"),
                    summary = tr("player_apple_music_animated_cover_summary", "获取到动态封面时强制使用沉浸式封面"),
                    checked = state.appleMusicAnimatedCover,
                ) { value -> store.update { it.copy(appleMusicAnimatedCover = value) } }
                SwitchPreference(
                    title = tr("player_landscape_coverflow", "横屏自动进入 Coverflow"),
                    summary = tr("player_landscape_coverflow_summary", "横屏展开正在播放界面时自动切换为 3D Coverflow 视图"),
                    checked = state.landscapeAutoCoverflow,
                ) { value -> store.update { it.copy(landscapeAutoCoverflow = value) } }
                SwitchPreference(tr("player_show_volume", "显示音量控件"), checked = state.showVolume) { value -> store.update { it.copy(showVolume = value) } }
                GlassDropdownPreference(
                    title = tr("player_hide_status_bar", "隐藏状态栏"),
                    items = listOf(
                        tr("status_bar_hide_never", "不隐藏"),
                        tr("status_bar_hide_always", "全局隐藏"),
                        tr("status_bar_hide_now_playing", "仅正在播放界面隐藏"),
                        tr("status_bar_hide_landscape", "仅横屏模式隐藏"),
                    ),
                    selectedIndex = state.statusBarHideMode.ordinal,
                    backdrop = LocalToolbarBackdrop.current,
                ) { index ->
                    store.update { it.copy(statusBarHideMode = StatusBarHideMode.entries[index]) }
                }
            }
        }
        item {
            Group(tr("player_functions", "功能")) {
                SwitchPreference(tr("player_open_notification", "从媒体通知进入时显示正在播放界面"), checked = state.openFromNotification) { value -> store.update { it.copy(openFromNotification = value) } }
                SwitchPreference(tr("player_show_audio_info", "正在播放界面显示音频信息"), checked = state.showAudioInfo) { value -> store.update { it.copy(showAudioInfo = value) } }
                SwitchPreference(tr("player_swipe_tracks", "左右滑动切歌"), checked = state.swipeTracks) { value -> store.update { it.copy(swipeTracks = value) } }
            }
        }
        item {
            Group(tr("lyrics", "歌词")) {
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
                BlurAnimatedVisibility(
                    visible = state.lyricTheme == LyricTheme.TOKYO_CITYSCAPE,
                    label = "player-tokyo-scene-time-setting",
                ) {
                    GlassDropdownPreference(
                        tr("lyric_theme_tokyo_time", "东京景色时段"),
                        tokyoSceneTimeLabels(),
                        state.tokyoSceneTime.ordinal,
                        backdrop = LocalToolbarBackdrop.current,
                    ) { index ->
                        store.update { it.copy(tokyoSceneTime = TokyoSceneTime.entries[index]) }
                    }
                }
                BlurAnimatedVisibility(
                    visible = state.lyricTheme == LyricTheme.TOKYO_CITYSCAPE,
                    label = "player-tokyo-scene-landmark-setting",
                ) {
                    GlassDropdownPreference(
                        tr("lyric_theme_tokyo_landmark", "东京景色"),
                        tokyoSceneLandmarkLabels(),
                        state.tokyoSceneLandmark.ordinal,
                        backdrop = LocalToolbarBackdrop.current,
                    ) { index ->
                        store.update { it.copy(tokyoSceneLandmark = TokyoSceneLandmark.entries[index]) }
                    }
                }
                SwitchPreference(tr("player_lyrics_awake", "显示歌词时屏幕常亮"), checked = state.keepLyricsAwake) { value -> store.update { it.copy(keepLyricsAwake = value) } }
                SwitchPreference(tr("player_lyrics_immersive", "歌词自动沉浸"), checked = state.immersiveLyrics) { value -> store.update { it.copy(immersiveLyrics = value) } }
                immersion.AnimatedVisibility(visible = { it }, enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(220)) + shrinkVertically(tween(220))) {
                    Box(Modifier.blur(immersionBlur.dp)) {
                        SliderPreference(tr("player_immersive_delay", "沉浸等待时间"), state.immersiveSeconds.toFloat(), 0f..30f,
                            "${state.immersiveSeconds} s", steps = 29, defaultValue = 5f) { value -> store.update { it.copy(immersiveSeconds = value.roundToInt()) } }
                    }
                }
                SliderPreference(
                    title = tr("player_main_lyric_scale", "主歌词缩放"),
                    value = state.mainLyricScalePercent,
                    range = LYRIC_SCALE_MIN_PERCENT..LYRIC_SCALE_MAX_PERCENT,
                    valueText = formatLyricScalePercent(state.mainLyricScalePercent),
                    defaultValue = LYRIC_SCALE_DEFAULT_PERCENT,
                    centerValue = LYRIC_SCALE_DEFAULT_PERCENT,
                ) { value ->
                    store.update { it.copy(mainLyricScalePercent = clampLyricScalePercent(value)) }
                }
                SliderPreference(
                    title = tr("player_bg_lyric_scale", "翻译与发音缩放"),
                    value = state.bgLyricScalePercent,
                    range = LYRIC_SCALE_MIN_PERCENT..LYRIC_SCALE_MAX_PERCENT,
                    valueText = formatLyricScalePercent(state.bgLyricScalePercent),
                    defaultValue = LYRIC_SCALE_DEFAULT_PERCENT,
                    centerValue = LYRIC_SCALE_DEFAULT_PERCENT,
                ) { value ->
                    store.update { it.copy(bgLyricScalePercent = clampLyricScalePercent(value)) }
                }
                GlassDropdownPreference(
                    title = tr("player_lyric_copy_mode", "歌词复制方式"),
                    items = lyricCopyModeLabels(),
                    selectedIndex = state.lyricCopyMode.ordinal,
                    backdrop = LocalToolbarBackdrop.current,
                ) { index ->
                    store.update { it.copy(lyricCopyMode = LyricCopyMode.entries[index]) }
                }
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
                ParallaxSensitivitySlider(
                    visible = state.spatialLyrics,
                    label = "player-spatial-lyrics-sensitivity-setting",
                    title = tr("player_parallax_tilt_sensitivity", "倾斜灵敏度"),
                    value = state.spatialLyricsSensitivity,
                ) { snapped ->
                    store.update { it.copy(spatialLyricsSensitivity = snapped) }
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
                        TiltSensitivitySlider(
                            title = tr("player_parallax_tilt_sensitivity", "倾斜灵敏度"),
                            value = state.spatialCoverSensitivity,
                        ) { snapped ->
                            store.update { it.copy(spatialCoverSensitivity = snapped) }
                        }
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
                        SwitchPreference(
                            title = tr("hardware_acceleration", "硬件加速"),
                            summary = tr(
                                "spatial_hw_accel_summary",
                                "优先调用高通骁龙 NPU / GPU 与 XNNPACK 多核加速空间计算（关闭则使用兼容 CPU 推理）",
                            ),
                            checked = state.spatialCoverHardwareAcceleration,
                        ) { enabled ->
                            store.update { it.copy(spatialCoverHardwareAcceleration = enabled) }
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
    tr("lyric_theme_tokyo_cityscape", "3D 东京"),
)

internal fun tokyoSceneTimeLabels(): List<String> = listOf(
    tr("tokyo_time_follow_system", "跟随系统时间"),
    tr("tokyo_time_dawn", "清晨"),
    tr("tokyo_time_morning", "上午"),
    tr("tokyo_time_noon", "正午"),
    tr("tokyo_time_dusk", "黄昏"),
    tr("tokyo_time_night", "夜晚"),
)

internal fun tokyoSceneLandmarkLabels(): List<String> = listOf(
    tr("tokyo_landmark_city_walk", "城市漫步"),
    tr("tokyo_landmark_shibuya", "涩谷十字路口"),
    tr("tokyo_landmark_tokyo_tower", "东京塔"),
    tr("tokyo_landmark_shinjuku", "新宿不夜街"),
    tr("tokyo_landmark_asakusa", "浅草仲见世"),
    tr("tokyo_landmark_odaiba", "台场彩虹湾"),
)

internal fun playerWeatherLabels(): List<String> = listOf(
    tr("player_effect_none", "不显示"),
    tr("player_effect_snow", "降雪"),
    tr("player_effect_rain", "降雨"),
)

internal fun lyricCopyModeLabels(): List<String> = listOf(
    tr("lyric_copy_mode_ask", "每次询问"),
    tr("lyric_copy_mode_original_only", "仅原文"),
    tr("lyric_copy_mode_original_translation", "原文与翻译"),
    tr("lyric_copy_mode_original_pronunciation", "原文与发音"),
    tr("lyric_copy_mode_original_pronunciation_translation", "原文与发音与翻译"),
    tr("lyric_copy_mode_pronunciation_translation", "发音与翻译"),
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

@Composable
private fun TiltSensitivitySlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    SliderPreference(
        title = title,
        value = value,
        range = PARALLAX_SENSITIVITY_MIN..PARALLAX_SENSITIVITY_MAX,
        valueText = "${"%.1f".format(value)}x",
        steps = 22,
        defaultValue = PARALLAX_SENSITIVITY_DEFAULT,
        onChange = { raw -> onValueChange(clampParallaxSensitivity(raw)) },
    )
}

@Composable
private fun ParallaxSensitivitySlider(
    visible: Boolean,
    label: String,
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    BlurAnimatedVisibility(visible = visible, label = label) {
        TiltSensitivitySlider(title = title, value = value, onValueChange = onValueChange)
    }
}
