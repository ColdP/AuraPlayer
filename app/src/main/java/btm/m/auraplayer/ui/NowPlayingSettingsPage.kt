package btm.m.auraplayer.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.playback.*
import kotlin.math.roundToInt

@Composable
fun NowPlayingSettingsPage(padding: PaddingValues) {
    val store = (LocalContext.current.applicationContext as AuraApplication).nowPlaying
    val state by store.state.collectAsState()
    val transition = updateTransition(state.hasBlurControl, label = "player-blur-setting")
    val blur by transition.animateFloat(transitionSpec = { tween(220) }, label = "blur") { if (it) 0f else 8f }
    val immersion = updateTransition(state.immersiveLyrics, label = "player-immersive-setting")
    val immersionBlur by immersion.animateFloat(transitionSpec = { tween(220) }, label = "blur") { if (it) 0f else 8f }
    val fullScreen = updateTransition(state.theme == PlayerTheme.FULLSCREEN, label = "player-glass-setting")
    val glassBlur by fullScreen.animateFloat(transitionSpec = { tween(220) }, label = "blur") { if (it) 0f else 8f }
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Group(tr("appearance", "外观")) {
                GlassDropdownPreference(tr("player_interface_theme", "界面主题"),
                    listOf(tr("player_theme_aurora", "流光"), tr("player_theme_aurora_blur", "流光模糊"), tr("player_theme_blur", "模糊"),
                        tr("player_theme_color", "颜色"), tr("player_theme_fullscreen", "全屏")), state.theme.ordinal, backdrop = LocalToolbarBackdrop.current) { index ->
                    store.update { it.copy(theme = PlayerTheme.entries[index]) }
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
                GlassDropdownPreference(tr("player_special_effect", "特殊效果"),
                    listOf(tr("player_effect_none", "不显示"), tr("player_effect_snow", "降雪"), tr("player_effect_rain", "降雨")),
                    state.weather.ordinal, backdrop = LocalToolbarBackdrop.current) { index -> store.update { it.copy(weather = PlayerWeather.entries[index]) } }
                GlassDropdownPreference(tr("player_cover_animation", "切歌时的封面动画"),
                    listOf(tr("default", "默认"), tr("player_cover_card", "卡片"), tr("player_theme_blur", "模糊"), tr("player_cover_cover", "覆盖"),
                        tr("player_cover_page", "翻页"), tr("player_cover_stack", "堆叠"), tr("player_cover_vertical_flip", "垂直翻转"), tr("player_cover_horizontal_flip", "水平翻转")),
                    state.coverAnimation.ordinal, backdrop = LocalToolbarBackdrop.current) { index -> store.update { it.copy(coverAnimation = CoverAnimation.entries[index]) } }
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
    }
}
