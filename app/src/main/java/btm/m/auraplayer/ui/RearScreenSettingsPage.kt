// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.RearPlayerActivity
import btm.m.auraplayer.lyrics.LyricSize
import btm.m.auraplayer.playback.LyricTheme
import btm.m.auraplayer.playback.PlayerTheme
import btm.m.auraplayer.playback.PlayerWeather
import btm.m.auraplayer.playback.TokyoSceneLandmark
import btm.m.auraplayer.playback.TokyoSceneTime
import btm.m.auraplayer.playback.shouldPromptLyricThemePowerNotice
import btm.m.auraplayer.rear.RearColorMode
import btm.m.auraplayer.rear.RearLyricSpacing
import btm.m.auraplayer.rear.RearPrivilegeState
import btm.m.auraplayer.rear.RearScreenDevice
import btm.m.auraplayer.rear.RearScreenLauncher
import btm.m.auraplayer.ui.player.lyricSizeLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

private const val KEEP_AWAKE_CONFIRM_SECONDS = 5

@Composable
fun RearScreenSettingsPage(padding: PaddingValues) {
    val context = LocalContext.current
    val app = context.applicationContext as AuraApplication
    val store = app.rearScreen
    val privilegeManager = app.rearPrivilege
    val backdrop = LocalToolbarBackdrop.current
    val config by store.state.collectAsState()
    val mainConfig by app.nowPlaying.state.collectAsState()
    val privilege by privilegeManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    var casting by remember { mutableStateOf(false) }
    var castStatus by remember { mutableStateOf<String?>(null) }
    var showKeepAwakeDialog by remember { mutableStateOf(false) }
    var pendingLyricTheme by remember { mutableStateOf<LyricTheme?>(null) }
    val lyricThemeItems = lyricThemeLabels()
    val tokyoSceneTimeItems = tokyoSceneTimeLabels()
    val tokyoSceneLandmarkItems = tokyoSceneLandmarkLabels()

    LaunchedEffect(Unit) {
        privilegeManager.refresh(forceRootProbe = true)
    }

    val isAuthorized = privilege.isAuthorized
    val privilegeSummary = when (privilege) {
        RearPrivilegeState.ROOT -> tr("rear_screen_perm_root", "已授予超级用户权限")
        RearPrivilegeState.SHIZUKU -> tr("rear_screen_perm_shizuku", "已连接到 Shizuku")
        RearPrivilegeState.UNAUTHORIZED -> tr("rear_screen_perm_unauthorized", "未授权")
    }
    val currentDeviceSummary = remember { RearScreenDevice.formatDeviceLabel() }

    val themeLabels = listOf(
        tr("player_theme_aurora", "流光"),
        tr("player_theme_aurora_blur", "流光模糊"),
        tr("player_theme_blur", "模糊"),
        tr("player_theme_color", "颜色"),
        tr("player_theme_fullscreen", "全屏"),
        tr("player_theme_material", "Material"),
        tr("player_theme_one", "One"),
    )
    val lyricSizeLabels = LyricSize.entries.map { lyricSizeLabel(it) }
    val lyricSpacingLabels = listOf(
        tr("rear_lyric_spacing_compact", "紧凑"),
        tr("rear_lyric_spacing_normal", "标准"),
        tr("rear_lyric_spacing_relaxed", "宽松"),
        tr("rear_lyric_spacing_wide", "极宽"),
    )
    val translationOption = tr("show_translation", "显示翻译")
    val pronunciationOption = tr("show_pronunciation", "显示发音")
    val selectedLyricOptions = buildSet {
        if (config.showTranslation) add(0)
        if (config.showPronunciation) add(1)
    }
    val lyricOptionsSummary = buildList {
        if (config.showTranslation) add(translationOption)
        if (config.showPronunciation) add(pronunciationOption)
    }.ifEmpty { listOf(tr("rear_lyric_options_none", "不显示")) }.joinToString("、")
    val weatherLabels = listOf(
        tr("player_effect_none", "不显示"),
        tr("player_effect_snow", "降雪"),
        tr("player_effect_rain", "降雨"),
    )

    LazyColumn(
        pageContentModifier(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Card 1: No SmallTitle
        item {
            SeamlessCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.5.dp,
                insideMargin = PaddingValues(0.dp),
            ) {
                Preference(
                    title = tr("rear_screen_current_device", "当前机型"),
                    summary = currentDeviceSummary,
                ) {}

                Preference(
                    title = tr("rear_screen_permission_status", "权限连接状态"),
                    summary = privilegeSummary,
                ) {
                    scope.launch {
                        val outcome = privilegeManager.requestOrRefresh()
                        if (!outcome.state.isAuthorized && !outcome.requestedShizukuDialog) {
                            Toast.makeText(
                                context,
                                tr("rear_screen_perm_guide", "请授予 Root 权限，或先启动并连接 Shizuku 后点击重试"),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        }

        // Card 2: Cast
        item {
            Group(tr("rear_screen_cast_group", "投放")) {
                SwitchPreference(
                    title = tr("rear_screen_restart_before_cast", "投放前重启背屏应用"),
                    summary = tr(
                        "rear_screen_restart_before_cast_summary",
                        "重启系统背屏服务可提升首次投放成功率",
                    ),
                    checked = config.restartSubscreenBeforeCast,
                    enabled = isAuthorized && !casting,
                ) { checked ->
                    store.update { it.copy(restartSubscreenBeforeCast = checked) }
                }

                Preference(
                    title = tr("rear_screen_cast_try", "尝试投放到背屏"),
                    summary = castStatus,
                    enabled = isAuthorized && !casting,
                ) {
                    scope.launch {
                        casting = true
                        castStatus = tr("rear_screen_casting", "正在尝试投放到背屏…")
                        try {
                            val result = RearScreenLauncher.castToRearDisplay(
                                context = context,
                                privilegeManager = privilegeManager,
                                activityClassName = RearPlayerActivity::class.java.name,
                                restartSubscreenBeforeCast = config.restartSubscreenBeforeCast,
                            )
                            val msg = tr(result.summaryKey, result.summaryFallback)
                            castStatus = msg
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        } finally {
                            casting = false
                        }
                    }
                }
            }
        }

        // Card 3: Theme & Rear Behavior
        item {
            Group(tr("theme", "主题")) {
                SwitchPreference(
                    title = tr("rear_screen_follow_main_theme", "主题跟随主屏幕"),
                    checked = config.followMainTheme,
                    enabled = isAuthorized,
                ) { follow ->
                    store.update { it.copy(followMainTheme = follow) }
                }

                BlurAnimatedVisibility(
                    visible = !config.followMainTheme,
                    label = "rearScreenTheme",
                ) {
                    GlassDropdownPreference(
                        title = tr("player_interface_theme", "界面主题"),
                        items = themeLabels,
                        selectedIndex = PlayerTheme.entries.indexOf(config.theme).coerceAtLeast(0),
                        enabled = isAuthorized,
                        backdrop = backdrop,
                        onSelectedIndexChange = { index ->
                            store.update { it.copy(theme = PlayerTheme.entries[index]) }
                        },
                    )
                }

                SwitchPreference(
                    title = tr("rear_screen_amoled_black", "AMOLED 纯黑背景"),
                    checked = config.amoledBlack,
                    enabled = isAuthorized,
                ) { checked ->
                    store.update { it.copy(amoledBlack = checked) }
                }

                val colorModeLabels = listOf(
                    tr("follow_system", "跟随系统"),
                    tr("light_mode", "浅色模式"),
                    tr("dark_mode", "深色模式"),
                )
                GlassDropdownPreference(
                    title = tr("rear_screen_color_mode", "颜色模式"),
                    items = colorModeLabels,
                    selectedIndex = RearColorMode.entries.indexOf(config.colorMode).coerceAtLeast(0),
                    enabled = isAuthorized,
                    backdrop = backdrop,
                    onSelectedIndexChange = { index ->
                        store.update { it.copy(colorMode = RearColorMode.entries[index]) }
                    },
                )

                GlassDropdownPreference(
                    title = tr("lyrics_size", "歌词大小"),
                    items = lyricSizeLabels,
                    selectedIndex = LyricSize.entries.indexOf(config.lyricSize).coerceAtLeast(0),
                    enabled = isAuthorized,
                    backdrop = backdrop,
                    onSelectedIndexChange = { index ->
                        store.update { it.copy(lyricSize = LyricSize.entries[index]) }
                    },
                )

                GlassDropdownPreference(
                    title = tr("rear_lyric_spacing", "歌词行距"),
                    items = lyricSpacingLabels,
                    selectedIndex = RearLyricSpacing.entries.indexOf(config.lyricSpacing).coerceAtLeast(0),
                    enabled = isAuthorized,
                    backdrop = backdrop,
                    onSelectedIndexChange = { index ->
                        store.update { it.copy(lyricSpacing = RearLyricSpacing.entries[index]) }
                    },
                )

                GlassMultiSelectDropdownPreference(
                    title = tr("lyrics_options", "歌词选项"),
                    items = listOf(translationOption, pronunciationOption),
                    selectedIndices = selectedLyricOptions,
                    summaryText = lyricOptionsSummary,
                    enabled = isAuthorized,
                    backdrop = backdrop,
                    onToggleIndex = { index ->
                        store.update { current ->
                            when (index) {
                                0 -> current.copy(showTranslation = !current.showTranslation)
                                1 -> current.copy(showPronunciation = !current.showPronunciation)
                                else -> current
                            }
                        }
                    },
                )

                SwitchPreference(
                    title = tr("player_show_volume", "显示音量控件"),
                    checked = config.showVolume,
                    enabled = isAuthorized,
                ) { checked ->
                    store.update { it.copy(showVolume = checked) }
                }

                SwitchPreference(
                    title = tr("player_swipe_tracks", "左右滑动切歌"),
                    checked = config.swipeTracks,
                    enabled = isAuthorized,
                ) { checked ->
                    store.update { it.copy(swipeTracks = checked) }
                }

                SwitchPreference(
                    title = tr("rear_screen_keep_lyrics_awake", "显示歌词时背屏常亮"),
                    checked = config.keepLyricsAwake,
                    enabled = isAuthorized,
                ) { checked ->
                    if (checked) {
                        showKeepAwakeDialog = true
                    } else {
                        store.update { it.copy(keepLyricsAwake = false) }
                    }
                }

                BlurAnimatedVisibility(
                    visible = !config.followMainTheme,
                    label = "rearScreenLyricTheme",
                ) {
                    GlassDropdownPreference(
                        title = tr("player_lyric_theme", "歌词主题"),
                        items = lyricThemeItems,
                        selectedIndex = LyricTheme.entries.indexOf(config.lyricTheme).coerceAtLeast(0),
                        enabled = isAuthorized,
                        backdrop = backdrop,
                        onSelectedIndexChange = { index ->
                            val picked = LyricTheme.entries[index]
                            if (shouldPromptLyricThemePowerNotice(picked, mainConfig.lyricThemePowerNoticeDismissed)) {
                                pendingLyricTheme = picked
                            } else {
                                store.update { it.copy(lyricTheme = picked) }
                            }
                        },
                    )
                }

                BlurAnimatedVisibility(
                    visible = !config.followMainTheme && config.lyricTheme == LyricTheme.TOKYO_CITYSCAPE,
                    label = "rearScreenTokyoSceneTime",
                ) {
                    GlassDropdownPreference(
                        title = tr("lyric_theme_tokyo_time", "东京景色时段"),
                        items = tokyoSceneTimeItems,
                        selectedIndex = TokyoSceneTime.entries.indexOf(config.tokyoSceneTime).coerceAtLeast(0),
                        enabled = isAuthorized,
                        backdrop = backdrop,
                        onSelectedIndexChange = { index ->
                            store.update { it.copy(tokyoSceneTime = TokyoSceneTime.entries[index]) }
                        },
                    )
                }

                BlurAnimatedVisibility(
                    visible = !config.followMainTheme && config.lyricTheme == LyricTheme.TOKYO_CITYSCAPE,
                    label = "rearScreenTokyoSceneLandmark",
                ) {
                    GlassDropdownPreference(
                        title = tr("lyric_theme_tokyo_landmark", "东京景色"),
                        items = tokyoSceneLandmarkItems,
                        selectedIndex = TokyoSceneLandmark.entries.indexOf(config.tokyoSceneLandmark).coerceAtLeast(0),
                        enabled = isAuthorized,
                        backdrop = backdrop,
                        onSelectedIndexChange = { index ->
                            store.update { it.copy(tokyoSceneLandmark = TokyoSceneLandmark.entries[index]) }
                        },
                    )
                }

                BlurAnimatedVisibility(
                    visible = !config.followMainTheme,
                    label = "rearScreenWeather",
                ) {
                    GlassDropdownPreference(
                        title = tr("player_special_effect", "特殊效果"),
                        items = weatherLabels,
                        selectedIndex = PlayerWeather.entries.indexOf(config.weather).coerceAtLeast(0),
                        enabled = isAuthorized,
                        backdrop = backdrop,
                        onSelectedIndexChange = { index ->
                            store.update { it.copy(weather = PlayerWeather.entries[index]) }
                        },
                    )
                }
            }
        }
    }

    RearKeepAwakeConfirmDialog(
        show = showKeepAwakeDialog,
        onDismiss = { showKeepAwakeDialog = false },
        onConfirm = {
            showKeepAwakeDialog = false
            store.update { it.copy(keepLyricsAwake = true) }
        },
    )

    LyricThemePowerWarningDialog(
        pendingTheme = pendingLyricTheme,
        onDismiss = { pendingLyricTheme = null },
        onConfirm = { chosen, dontShowAgain ->
            store.update { it.copy(lyricTheme = chosen) }
            if (dontShowAgain) {
                app.nowPlaying.update { it.copy(lyricThemePowerNoticeDismissed = true) }
            }
            pendingLyricTheme = null
        },
    )
}

@Composable
private fun RearKeepAwakeConfirmDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var remainingSeconds by remember { mutableIntStateOf(KEEP_AWAKE_CONFIRM_SECONDS) }

    LaunchedEffect(show) {
        if (show) {
            remainingSeconds = KEEP_AWAKE_CONFIRM_SECONDS
            while (remainingSeconds > 0) {
                delay(1000L)
                remainingSeconds--
            }
        }
    }

    WindowDialog(
        show = show,
        title = tr("rear_screen_keep_awake_dialog_title", "实验性功能提示"),
        summary = tr(
            "rear_screen_keep_awake_dialog_summary",
            "「显示歌词时背屏常亮」为实验性功能，开启后在背屏显示歌词期间将保持屏幕常亮，可能会增加功耗并加快电量消耗。",
        ),
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassDialogButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            ) {
                Text(tr("cancel", "取消"))
            }
            GlassDialogButton(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = remainingSeconds == 0,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                val baseLabel = tr("confirm", "确认")
                Text(if (remainingSeconds > 0) "$baseLabel (${remainingSeconds}s)" else baseLabel)
            }
        }
    }
}
