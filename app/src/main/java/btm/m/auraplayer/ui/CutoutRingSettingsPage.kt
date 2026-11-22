// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.data.CUTOUT_RING_HEIGHT_MAX_DP
import btm.m.auraplayer.data.CUTOUT_RING_HEIGHT_MIN_DP
import btm.m.auraplayer.data.CUTOUT_RING_HORIZONTAL_OFFSET_MAX_DP
import btm.m.auraplayer.data.CUTOUT_RING_HORIZONTAL_OFFSET_MIN_DP
import btm.m.auraplayer.data.CUTOUT_RING_OPACITY_MAX
import btm.m.auraplayer.data.CUTOUT_RING_OPACITY_MIN
import btm.m.auraplayer.data.CUTOUT_RING_STROKE_MAX_DP
import btm.m.auraplayer.data.CUTOUT_RING_STROKE_MIN_DP
import btm.m.auraplayer.data.CUTOUT_RING_TRACK_OPACITY_MAX
import btm.m.auraplayer.data.CUTOUT_RING_TRACK_OPACITY_MIN
import btm.m.auraplayer.data.CUTOUT_RING_VERTICAL_OFFSET_MAX_DP
import btm.m.auraplayer.data.CUTOUT_RING_VERTICAL_OFFSET_MIN_DP
import btm.m.auraplayer.data.CUTOUT_RING_WIDTH_MAX_DP
import btm.m.auraplayer.data.CUTOUT_RING_WIDTH_MIN_DP
import btm.m.auraplayer.data.CutoutRingAlignment
import btm.m.auraplayer.data.CutoutRingColorMode
import btm.m.auraplayer.data.CutoutRingContent
import btm.m.auraplayer.data.CutoutRingDirection
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun CutoutRingSettingsPage(padding: PaddingValues) {
    val context = LocalContext.current
    val app = context.applicationContext as AuraApplication
    val store = app.cutoutRing
    val controller = app.cutoutRingController
    val config by store.state.collectAsState()
    val previewColor by store.previewColor.collectAsState()
    val backdrop = LocalToolbarBackdrop.current
    val view = LocalView.current
    val density = LocalDensity.current.density

    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var pendingOverlayPermissionEnable by remember { mutableStateOf(false) }
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        controller.setCalibrationPreview(true)
        onDispose {
            store.setPreviewColor(null)
            controller.setCalibrationPreview(false)
        }
    }

    LifecycleResumeEffect(Unit) {
        val granted = Settings.canDrawOverlays(context)
        canDrawOverlays = granted
        if (pendingOverlayPermissionEnable && granted) {
            pendingOverlayPermissionEnable = false
            store.update { it.copy(showOutsideApp = true) }
        } else if (!granted && config.showOutsideApp) {
            store.update { it.copy(showOutsideApp = false) }
        }
        controller.onOverlayPermissionMayHaveChanged()
        onPauseOrDispose {}
    }

    val contentLabels = listOf(
        tr("cutout_ring_content_progress", "歌曲进度"),
        tr("cutout_ring_content_volume", "媒体音量"),
        tr("cutout_ring_content_audio_level", "音频律动"),
        tr("cutout_ring_content_sleep_timer", "睡眠定时"),
    )
    val colorModeLabels = listOf(
        tr("cutout_ring_color_follow_theme", "跟随主题色"),
        tr("cutout_ring_color_follow_cover", "跟随封面取色"),
        tr("cutout_ring_color_custom", "自定义颜色"),
    )
    val alignmentLabels = listOf(
        tr("cutout_ring_align_center", "居中对齐"),
        tr("cutout_ring_align_left", "左对齐"),
        tr("cutout_ring_align_right", "右对齐"),
    )
    val directionLabels = listOf(
        tr("cutout_ring_direction_cw", "顺时针"),
        tr("cutout_ring_direction_ccw", "逆时针"),
    )

    LazyColumn(
        pageContentModifier(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Standalone top card without SmallTitle: master switch.
        item("cutout_ring_master_toggle") {
            SeamlessCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.5.dp,
                insideMargin = PaddingValues(0.dp),
            ) {
                SwitchPreference(
                    title = tr("cutout_ring_enable", "启用挖孔光环"),
                    summary = tr("cutout_ring_enable_summary", "在屏幕挖孔周围显示实时环形状态指示"),
                    checked = config.enabled,
                ) { checked ->
                    store.update { it.copy(enabled = checked) }
                }
            }
        }

        // All detailed settings collapse/expand with the app's existing BlurAnimatedVisibility.
        item("cutout_ring_settings_body") {
            BlurAnimatedVisibility(
                visible = config.enabled,
                label = "cutout-ring-options",
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Card 1: Display content & behavior
                    Group(tr("cutout_ring_group_display", "显示内容与行为")) {
                        GlassDropdownPreference(
                            title = tr("cutout_ring_content", "环形显示内容"),
                            items = contentLabels,
                            selectedIndex = config.content.ordinal,
                            backdrop = backdrop,
                        ) { index ->
                            store.update { it.copy(content = CutoutRingContent.entries[index]) }
                        }

                        BlurAnimatedVisibility(
                            visible = config.content != CutoutRingContent.VOLUME,
                            label = "cutout-ring-temp-volume",
                        ) {
                            SwitchPreference(
                                title = tr("cutout_ring_temp_volume", "调节音量时临时显示音量"),
                                summary = tr(
                                    "cutout_ring_temp_volume_summary",
                                    "调整媒体或 USB 独占音量时临时切换为音量环，片刻后自动恢复",
                                ),
                                checked = config.temporarilyShowVolume,
                            ) { checked ->
                                store.update { it.copy(temporarilyShowVolume = checked) }
                            }
                        }

                        SwitchPreference(
                            title = tr("cutout_ring_show_outside_app", "在应用外显示"),
                            summary = if (canDrawOverlays) {
                                tr("cutout_ring_show_outside_app_summary", "离开应用后通过系统悬浮窗继续在挖孔处显示光环")
                            } else {
                                tr("cutout_ring_show_outside_app_perm_summary", "需要授予悬浮窗权限，开启后离开应用仍可显示光环")
                            },
                            checked = config.showOutsideApp && canDrawOverlays,
                        ) { checked ->
                            if (!checked) {
                                pendingOverlayPermissionEnable = false
                                store.update { it.copy(showOutsideApp = false) }
                            } else if (Settings.canDrawOverlays(context)) {
                                canDrawOverlays = true
                                store.update { it.copy(showOutsideApp = true) }
                            } else {
                                showOverlayPermissionDialog = true
                            }
                        }

                        SwitchPreference(
                            title = tr("cutout_ring_only_when_playing", "仅播放时显示"),
                            summary = tr("cutout_ring_only_when_playing_summary", "暂停播放或无歌曲时自动隐藏光环"),
                            checked = config.onlyWhenPlaying,
                        ) { checked ->
                            store.update { it.copy(onlyWhenPlaying = checked) }
                        }

                        SwitchPreference(
                            title = tr("cutout_ring_hide_in_landscape", "横屏时自动隐藏"),
                            summary = tr("cutout_ring_hide_in_landscape_summary", "屏幕切换为横屏时自动隐藏光环，避免遮挡画面"),
                            checked = config.hideInLandscape,
                        ) { checked ->
                            store.update { it.copy(hideInLandscape = checked) }
                        }
                    }

                    // Card 2: Appearance & color
                    Group(tr("cutout_ring_group_appearance", "外观与颜色")) {
                        GlassDropdownPreference(
                            title = tr("cutout_ring_color", "环形颜色"),
                            items = colorModeLabels,
                            selectedIndex = config.colorMode.ordinal,
                            backdrop = backdrop,
                        ) { index ->
                            store.setPreviewColor(null)
                            store.update { it.copy(colorMode = CutoutRingColorMode.entries[index]) }
                        }

                        BlurAnimatedVisibility(
                            visible = config.colorMode == CutoutRingColorMode.CUSTOM,
                            label = "cutout-ring-custom-color",
                        ) {
                            ThemeSeedColorPreference(
                                title = tr("cutout_ring_custom_color", "自定义环形颜色"),
                                color = previewColor ?: config.customColor,
                                onPreviewColor = { store.setPreviewColor(it) },
                                onDismissPreview = { store.setPreviewColor(null) },
                                onColorChange = { color ->
                                    store.update {
                                        it.copy(
                                            colorMode = CutoutRingColorMode.CUSTOM,
                                            customColor = color,
                                        )
                                    }
                                },
                            )
                        }

                        SliderPreference(
                            title = tr("cutout_ring_stroke_width", "环形粗细"),
                            value = config.strokeWidthDp,
                            range = CUTOUT_RING_STROKE_MIN_DP..CUTOUT_RING_STROKE_MAX_DP,
                            valueText = formatDp(config.strokeWidthDp),
                            steps = 34,
                            defaultValue = 2.6f,
                        ) { value ->
                            store.update { it.copy(strokeWidthDp = value) }
                        }

                        SliderPreference(
                            title = tr("cutout_ring_opacity", "环形不透明度"),
                            value = config.opacityPercent.toFloat(),
                            range = CUTOUT_RING_OPACITY_MIN.toFloat()..CUTOUT_RING_OPACITY_MAX.toFloat(),
                            valueText = "${config.opacityPercent}%",
                            steps = CUTOUT_RING_OPACITY_MAX - CUTOUT_RING_OPACITY_MIN - 1,
                            defaultValue = 100f,
                        ) { value ->
                            store.update { it.copy(opacityPercent = value.roundToInt()) }
                        }

                        SwitchPreference(
                            title = tr("cutout_ring_show_track", "显示背景底环"),
                            summary = tr("cutout_ring_show_track_summary", "在未到达进度的区域显示半透明底环"),
                            checked = config.showTrack,
                        ) { checked ->
                            store.update { it.copy(showTrack = checked) }
                        }

                        BlurAnimatedVisibility(
                            visible = config.showTrack,
                            label = "cutout-ring-track-opacity",
                        ) {
                            SliderPreference(
                                title = tr("cutout_ring_track_opacity", "底环不透明度"),
                                value = config.trackOpacityPercent.toFloat(),
                                range = CUTOUT_RING_TRACK_OPACITY_MIN.toFloat()..CUTOUT_RING_TRACK_OPACITY_MAX.toFloat(),
                                valueText = "${config.trackOpacityPercent}%",
                                steps = CUTOUT_RING_TRACK_OPACITY_MAX - CUTOUT_RING_TRACK_OPACITY_MIN - 1,
                                defaultValue = 22f,
                            ) { value ->
                                store.update { it.copy(trackOpacityPercent = value.roundToInt()) }
                            }
                        }

                        GlassDropdownPreference(
                            title = tr("cutout_ring_direction", "进度方向"),
                            items = directionLabels,
                            selectedIndex = config.direction.ordinal,
                            backdrop = backdrop,
                        ) { index ->
                            store.update { it.copy(direction = CutoutRingDirection.entries[index]) }
                        }

                        SwitchPreference(
                            title = tr("cutout_ring_round_cap", "圆头端点"),
                            checked = config.roundCap,
                        ) { checked ->
                            store.update { it.copy(roundCap = checked) }
                        }

                        SwitchPreference(
                            title = tr("cutout_ring_glow", "柔和光晕"),
                            summary = tr("cutout_ring_glow_summary", "为进度环添加柔和外发光效果"),
                            checked = config.glowEnabled,
                        ) { checked ->
                            store.update { it.copy(glowEnabled = checked) }
                        }
                    }

                    // Card 3: Position & shape
                    Group(tr("cutout_ring_group_geometry", "位置与形状")) {
                        Preference(
                            title = tr("cutout_ring_auto_calibrate", "自动适配屏幕挖孔"),
                            summary = tr("cutout_ring_auto_calibrate_summary", "根据系统上报的挖孔区域自动匹配位置与尺寸"),
                        ) {
                            val cutout = view.rootWindowInsets?.displayCutout
                            val screenWidthPx = view.rootView?.width?.takeIf { it > 0 }
                                ?: context.resources.displayMetrics.widthPixels
                            val calibrated = detectCutoutCalibration(cutout, screenWidthPx, density)
                            if (calibrated != null) {
                                store.update {
                                    it.copy(
                                        alignment = calibrated.alignment,
                                        widthDp = calibrated.widthDp,
                                        heightDp = calibrated.heightDp,
                                        verticalOffsetDp = calibrated.verticalOffsetDp,
                                        horizontalOffsetDp = calibrated.horizontalOffsetDp,
                                    )
                                }
                                Toast.makeText(
                                    context,
                                    tr("cutout_ring_auto_calibrate_done", "已自动适配当前屏幕挖孔"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    tr("cutout_ring_auto_calibrate_missing", "未检测到顶部屏幕挖孔，请手动微调位置与尺寸"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }

                        GlassDropdownPreference(
                            title = tr("cutout_ring_alignment", "环形位置"),
                            items = alignmentLabels,
                            selectedIndex = config.alignment.ordinal,
                            backdrop = backdrop,
                        ) { index ->
                            store.update { it.copy(alignment = CutoutRingAlignment.entries[index]) }
                        }

                        SliderPreference(
                            title = tr("cutout_ring_width", "环形宽度"),
                            value = config.widthDp,
                            range = CUTOUT_RING_WIDTH_MIN_DP..CUTOUT_RING_WIDTH_MAX_DP,
                            valueText = if (config.widthDp <= 0.05f) {
                                tr("cutout_ring_width_circle_value", "0 dp（圆形）")
                            } else {
                                formatDp(config.widthDp)
                            },
                            steps = 319,
                            defaultValue = 0f,
                        ) { value ->
                            store.update { it.copy(widthDp = value) }
                        }

                        SliderPreference(
                            title = tr("cutout_ring_height", "环形高度"),
                            value = config.heightDp,
                            range = CUTOUT_RING_HEIGHT_MIN_DP..CUTOUT_RING_HEIGHT_MAX_DP,
                            valueText = formatDp(config.heightDp),
                            steps = 139,
                            defaultValue = 26f,
                        ) { value ->
                            store.update { it.copy(heightDp = value) }
                        }

                        SliderPreference(
                            title = tr("cutout_ring_corner_smoothness", "环形圆角平滑度"),
                            value = config.cornerSmoothness.toFloat(),
                            range = 0f..100f,
                            valueText = "${config.cornerSmoothness}%",
                            steps = 99,
                            defaultValue = 0f,
                        ) { value ->
                            store.update { it.copy(cornerSmoothness = value.roundToInt()) }
                        }

                        SliderPreference(
                            title = tr("cutout_ring_vertical_offset", "环形垂直偏移"),
                            value = config.verticalOffsetDp,
                            range = CUTOUT_RING_VERTICAL_OFFSET_MIN_DP..CUTOUT_RING_VERTICAL_OFFSET_MAX_DP,
                            valueText = formatSignedDp(config.verticalOffsetDp),
                            steps = 207,
                            defaultValue = 0f,
                            centerValue = 0f,
                        ) { value ->
                            store.update { it.copy(verticalOffsetDp = value) }
                        }

                        SliderPreference(
                            title = tr("cutout_ring_horizontal_offset", "环形水平偏移"),
                            value = config.horizontalOffsetDp,
                            range = CUTOUT_RING_HORIZONTAL_OFFSET_MIN_DP..CUTOUT_RING_HORIZONTAL_OFFSET_MAX_DP,
                            valueText = formatSignedDp(config.horizontalOffsetDp),
                            steps = 479,
                            defaultValue = 0f,
                            centerValue = 0f,
                        ) { value ->
                            store.update { it.copy(horizontalOffsetDp = value) }
                        }

                        Preference(
                            title = tr("cutout_ring_reset_geometry", "重置位置与形状"),
                            summary = tr("cutout_ring_reset_geometry_summary", "恢复默认对齐方式、圆形尺寸、平滑度与偏移量"),
                        ) {
                            store.update { it.resetGeometry() }
                        }
                    }
                }
            }
        }
    }

    WindowDialog(
        show = showOverlayPermissionDialog,
        title = tr("cutout_ring_overlay_perm_title", "需要悬浮窗权限"),
        summary = tr(
            "cutout_ring_overlay_perm_message",
            "在应用外显示挖孔光环需要「显示在其他应用的上层」权限。光环不会拦截点击或输入，仅用于在状态栏挖孔周围绘制指示图形。",
        ),
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { showOverlayPermissionDialog = false },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassDialogButton(
                onClick = { showOverlayPermissionDialog = false },
                modifier = Modifier.weight(1f),
            ) {
                Text(tr("cancel", "取消"))
            }
            GlassDialogButton(
                onClick = {
                    showOverlayPermissionDialog = false
                    pendingOverlayPermissionEnable = true
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(tr("cutout_ring_overlay_perm_go", "前往授权"))
            }
        }
    }
}

private fun formatDp(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    return if (rounded == rounded.roundToInt().toFloat()) {
        "${rounded.roundToInt()} dp"
    } else {
        String.format(Locale.US, "%.1f dp", rounded)
    }
}

private fun formatSignedDp(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    val prefix = if (rounded > 0f) "+" else ""
    return if (rounded == rounded.roundToInt().toFloat()) {
        "$prefix${rounded.roundToInt()} dp"
    } else {
        String.format(Locale.US, "%s%.1f dp", prefix, rounded)
    }
}
