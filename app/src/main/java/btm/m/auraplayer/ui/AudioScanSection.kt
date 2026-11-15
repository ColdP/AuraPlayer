// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.MAX_MANUAL_IMPORT_COUNT
import btm.m.auraplayer.data.ManualAudioDraft
import btm.m.auraplayer.data.ManualImportStore
import btm.m.auraplayer.data.mergeManualAudioSelection
import btm.m.auraplayer.data.removeManualAudioSelection
import kotlin.math.round
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AudioScanGroup(model: AuraViewModel) {
    val context = LocalContext.current
    val settings = model.settings.value
    val scanning = model.scanning
    val manualCount = model.manualImportCount

    var showBlacklist by remember { mutableStateOf(false) }
    var showManualImport by remember { mutableStateOf(false) }
    var showRescanDialog by remember { mutableStateOf(false) }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        Toast.makeText(
            context,
            tr("partial_scan_started", "正在扫描所选文件夹…"),
            Toast.LENGTH_SHORT,
        ).show()
        model.scanFolder(uri) { count ->
            Toast.makeText(
                context,
                tr("scan_complete_count", "扫描完成，共 %d 首歌曲").format(count),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val manualSummary = if (manualCount > 0) {
        tr("manual_import_count_summary", "已导入 %d 首手动选择的歌曲").format(manualCount)
    } else {
        tr("manual_import_summary", "从文件选择器手动导入音频文件")
    }

    Group(tr("scan", "扫描")) {
        Preference(
            title = tr("blacklist", "黑名单"),
            summary = tr("blacklist_summary", "黑名单中的文件夹不会在音乐库中显示"),
        ) { showBlacklist = true }
        SwitchPreference(
            title = tr("whitelist", "白名单"),
            summary = tr("whitelist_summary", "只显示 Music 目录中的音乐"),
            checked = settings.whitelist,
        ) {
            model.updateSettings { current -> current.copy(whitelist = it) }
            model.refreshLibrary(debounce = false)
        }
        SwitchPreference(
            title = tr("filter_duration", "筛选歌曲时长"),
            summary = tr("filter_duration_summary", "隐藏低于最短时长的音频文件"),
            checked = settings.durationFilter,
        ) {
            model.updateSettings { current -> current.copy(durationFilter = it) }
            model.refreshLibrary(debounce = false)
        }
        BlurAnimatedVisibility(settings.durationFilter, label = "duration_filter") {
            EditableSliderPreference(
                value = settings.minimumSeconds.toFloat(),
                onValueChange = { value ->
                    val seconds = round(value).toInt().coerceIn(5, 120)
                    model.updateSettings { current -> current.copy(minimumSeconds = seconds) }
                },
                onValueChangeFinished = { model.refreshLibrary(debounce = false) },
                title = tr("minimum_duration", "最短歌曲时长"),
                valueText = tr("seconds_value", "%d 秒").format(settings.minimumSeconds),
                valueRange = 5f..120f,
                steps = 114,
                defaultValue = 15f,
            )
        }
        Preference(
            title = tr("manual_import_songs", "手动导入歌曲"),
            summary = manualSummary,
            enabled = !scanning,
        ) { showManualImport = true }
        Preference(
            title = tr("rescan", "重新扫描"),
            summary = tr("rescan_summary", "选择全盘扫描或部分扫描"),
            enabled = !scanning,
        ) { showRescanDialog = true }
    }

    if (showBlacklist) {
        BlacklistDialog(model) { showBlacklist = false }
    }
    ManualImportSongsDialog(
        show = showManualImport,
        model = model,
        onDismiss = { showManualImport = false },
    )
    RescanModeDialog(
        show = showRescanDialog,
        onFullScan = {
            showRescanDialog = false
            model.refreshLibrary(debounce = false) { count ->
                Toast.makeText(
                    context,
                    tr("scan_complete_count", "扫描完成，共 %d 首歌曲").format(count),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        onPartialScan = {
            showRescanDialog = false
            treePicker.launch(null)
        },
        onDismiss = { showRescanDialog = false },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ManualImportSongsDialog(
    show: Boolean,
    model: AuraViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importStore = remember(context) { ManualImportStore(context) }

    var selectedDrafts by remember { mutableStateOf<List<ManualAudioDraft>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var readingSelection by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ManualAudioDraft?>(null) }

    val multiAudioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        readingSelection = true
        scope.launch {
            val inspected = importStore.inspectPickedUris(uris)
            val (merged, skipped) = mergeManualAudioSelection(
                existing = selectedDrafts,
                incoming = inspected,
                maxCount = MAX_MANUAL_IMPORT_COUNT,
            )
            selectedDrafts = merged
            readingSelection = false
            if (skipped > 0) {
                Toast.makeText(
                    context,
                    tr(
                        "manual_import_limit_reached",
                        "最多只能选择 20 个文件，已忽略多出的 %d 个文件",
                    ).format(skipped),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    WindowDialog(
        show = show,
        title = tr("manual_import_songs", "手动导入歌曲"),
        titleTextAlign = TextAlign.Start,
        onDismissRequest = {
            if (!importing) {
                expanded = false
                onDismiss()
            }
        },
    ) {
        val surfaceTint = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        val previewModifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(surfaceTint)
            .let { base ->
                if (selectedDrafts.isNotEmpty()) {
                    base.clickable { expanded = !expanded }
                } else {
                    base
                }
            }
            .padding(12.dp)

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Box(
                modifier = previewModifier,
                contentAlignment = Alignment.CenterStart,
            ) {
                when {
                    selectedDrafts.isEmpty() -> {
                        Text(
                            text = if (readingSelection) {
                                tr("reading_audio_files", "正在读取音频信息…")
                            } else {
                                tr("not_imported", "未导入")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }

                    else -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                selectedDrafts.forEach { item ->
                                    Artwork(
                                        uri = item.artwork,
                                        modifier = Modifier.size(48.dp),
                                        cornerRadius = 10.dp,
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = expanded,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically(),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp),
                                    horizontalAlignment = Alignment.Start,
                                ) {
                                    HorizontalDivider(
                                        color = MiuixTheme.colorScheme.dividerLine,
                                    )
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 220.dp)
                                            .verticalScroll(rememberScrollState())
                                            .padding(top = 6.dp),
                                        horizontalAlignment = Alignment.Start,
                                    ) {
                                        selectedDrafts.forEachIndexed { index, item ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .combinedClickable(
                                                        onClick = { expanded = false },
                                                        onLongClick = { pendingDelete = item },
                                                    )
                                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                Artwork(
                                                    uri = item.artwork,
                                                    modifier = Modifier.size(32.dp),
                                                    cornerRadius = 7.dp,
                                                )
                                                Text(
                                                    text = item.fileName,
                                                    modifier = Modifier.weight(1f),
                                                    textAlign = TextAlign.Start,
                                                    style = MiuixTheme.textStyles.body2,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MiuixTheme.colorScheme.onSurface,
                                                )
                                            }
                                            if (index < selectedDrafts.lastIndex) {
                                                HorizontalDivider(
                                                    color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.45f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = tr(
                    "manual_import_hint",
                    "点击下方按钮选择音频文件导入，允许多选，最多选择20个文件",
                ),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassDialogButton(
                    onClick = {
                        multiAudioPicker.launch(arrayOf("audio/*", "application/ogg"))
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !readingSelection && !importing && selectedDrafts.size < MAX_MANUAL_IMPORT_COUNT,
                ) {
                    Text(tr("select_files", "选择文件"))
                }
                GlassDialogButton(
                    onClick = { showClearConfirm = true },
                    modifier = Modifier.weight(1f),
                    enabled = selectedDrafts.isNotEmpty() && !importing,
                ) {
                    Text(tr("clear", "清除"))
                }
            }

            Spacer(Modifier.height(10.dp))

            GlassDialogButton(
                onClick = {
                    val drafts = selectedDrafts
                    if (drafts.isEmpty()) return@GlassDialogButton
                    importing = true
                    model.importManualSongs(drafts) { importedCount ->
                        importing = false
                        selectedDrafts = emptyList()
                        expanded = false
                        Toast.makeText(
                            context,
                            tr("manual_import_done", "已导入 %d 首歌曲").format(importedCount),
                            Toast.LENGTH_SHORT,
                        ).show()
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedDrafts.isNotEmpty() && !importing && !readingSelection,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(
                    if (importing) {
                        tr("importing", "正在导入…")
                    } else {
                        tr("import_action", "导入")
                    },
                )
            }

            Spacer(Modifier.height(10.dp))

            GlassDialogButton(
                onClick = {
                    if (!importing) {
                        expanded = false
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !importing,
            ) {
                Text(tr("cancel", "取消"))
            }
        }
    }

    WindowDialog(
        show = showClearConfirm,
        title = tr("clear_selected_files_title", "确认清除"),
        summary = tr(
            "clear_selected_files_summary",
            "确定要清除当前已选择的 %d 个音频文件吗？",
        ).format(selectedDrafts.size),
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { showClearConfirm = false },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = tr("clear_selected_files_note", "此操作仅清空本次待导入选择列表，不会删除本地音频文件。"),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassDialogButton(
                    onClick = { showClearConfirm = false },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(tr("cancel", "取消"))
                }
                GlassDialogButton(
                    onClick = {
                        selectedDrafts = emptyList()
                        expanded = false
                        showClearConfirm = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(tr("clear", "清除"))
                }
            }
        }
    }

    val targetDelete = pendingDelete
    WindowDialog(
        show = targetDelete != null,
        title = tr("remove_selected_audio_title", "删除已选文件"),
        summary = tr(
            "remove_selected_audio_summary",
            "确定要从待导入列表中移除「%s」吗？",
        ).format(targetDelete?.fileName.orEmpty()),
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { pendingDelete = null },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassDialogButton(
                onClick = { pendingDelete = null },
                modifier = Modifier.weight(1f),
            ) {
                Text(tr("cancel", "取消"))
            }
            GlassDialogButton(
                onClick = {
                    val toRemove = targetDelete
                    if (toRemove != null) {
                        val updated = removeManualAudioSelection(selectedDrafts, toRemove.uri)
                        selectedDrafts = updated
                        if (updated.isEmpty()) expanded = false
                    }
                    pendingDelete = null
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(tr("delete", "删除"))
            }
        }
    }
}

@Composable
private fun RescanModeDialog(
    show: Boolean,
    onFullScan: () -> Unit,
    onPartialScan: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = tr("select_scan_mode", "选择扫描方式"),
        titleTextAlign = TextAlign.Start,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            GlassDialogButton(
                onClick = onFullScan,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(tr("full_scan", "全盘扫描"))
            }
            Spacer(Modifier.height(10.dp))
            GlassDialogButton(
                onClick = onPartialScan,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr("partial_scan", "部分扫描"))
            }
            Spacer(Modifier.height(10.dp))
            GlassDialogButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr("cancel", "取消"))
            }
        }
    }
}
