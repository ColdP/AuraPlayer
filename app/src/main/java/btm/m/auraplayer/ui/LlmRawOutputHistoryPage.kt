// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.lyrics.LlmRawOutputExportFormat
import btm.m.auraplayer.lyrics.LlmRawOutputExporter
import btm.m.auraplayer.lyrics.LlmRawOutputHistoryStore
import btm.m.auraplayer.lyrics.LlmRawOutputRecord
import btm.m.auraplayer.lyrics.formatLlmCacheBytes
import btm.m.auraplayer.ui.player.PlayerNavigationGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.ToolbarPosition
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LlmRawOutputHistoryPage(
    model: AuraViewModel,
    back: () -> Unit,
    isActive: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var refreshTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(isActive) {
        if (isActive) refreshTick++
    }
    val entries by produceState(emptyList<LlmRawOutputRecord>(), refreshTick) {
        value = withContext(Dispatchers.IO) { LlmRawOutputHistoryStore.list(context.cacheDir) }
    }
    val totalBytes = remember(entries) { entries.sumOf { it.sizeBytes } }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val filteredEntries = remember(entries, searchQuery) {
        LlmRawOutputHistoryStore.filter(entries, searchQuery)
    }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    var selectedRecord by remember { mutableStateOf<LlmRawOutputRecord?>(null) }
    var inspectRecord by remember { mutableStateOf<LlmRawOutputRecord?>(null) }
    var inspectShowFullHttp by remember { mutableStateOf(false) }
    var pendingDeleteRecord by remember { mutableStateOf<LlmRawOutputRecord?>(null) }
    var exportTarget by remember { mutableStateOf<LlmRawOutputRecord?>(null) }
    var pendingExportRecord by remember { mutableStateOf<LlmRawOutputRecord?>(null) }
    var pendingExportFormat by remember { mutableStateOf<LlmRawOutputExportFormat?>(null) }
    var pendingZipExport by remember { mutableStateOf<List<LlmRawOutputRecord>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    fun closeSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun closeSearch() {
        searchExpanded = false
        searchQuery = ""
        keyboard?.hide()
    }

    fun toggleSelection(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    LaunchedEffect(entries) {
        if (entries.isEmpty()) {
            closeSelection()
        } else {
            val valid = entries.mapTo(HashSet(), LlmRawOutputRecord::id)
            val pruned = selectedIds.filterTo(LinkedHashSet()) { it in valid }
            if (pruned.size != selectedIds.size) {
                selectedIds = pruned
                if (pruned.isEmpty()) selectionMode = false
            }
        }
    }

    fun launchSingleExport(
        record: LlmRawOutputRecord,
        format: LlmRawOutputExportFormat,
        launcher: androidx.activity.result.ActivityResultLauncher<String>,
    ) {
        pendingExportRecord = record
        pendingExportFormat = format
        launcher.launch(LlmRawOutputExporter.suggestedFileName(record, format))
    }

    val handleSingleExportResult: (Uri?) -> Unit = { uri ->
        val record = pendingExportRecord
        val format = pendingExportFormat
        pendingExportRecord = null
        pendingExportFormat = null
        if (uri != null && record != null && format != null) {
            scope.launch {
                val payload = LlmRawOutputExporter.export(record, format)
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(payload.toByteArray(Charsets.UTF_8))
                        } ?: error("Cannot open output stream")
                    }.isSuccess
                }
                Toast.makeText(
                    context,
                    if (ok) tr("llm_raw_history_exported", "历史输出已导出")
                    else tr("llm_cache_export_failed", "导出失败，请重试"),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val jsonExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LlmRawOutputExportFormat.JSON.mimeType),
        handleSingleExportResult,
    )
    val txtExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LlmRawOutputExportFormat.TXT.mimeType),
        handleSingleExportResult,
    )
    val zipExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val selected = pendingZipExport
        pendingZipExport = emptyList()
        if (uri == null || selected.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        LlmRawOutputExporter.writeZip(selected, out)
                    } ?: error("unable to create zip")
                }.isSuccess
            }
            busy = false
            if (ok) {
                Toast.makeText(context, tr("llm_raw_history_exported", "历史输出已导出"), Toast.LENGTH_SHORT).show()
                if (selectionMode) closeSelection()
            } else {
                Toast.makeText(context, tr("llm_cache_export_failed", "导出失败，请重试"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(enabled = selectionMode || searchExpanded || searchQuery.isNotEmpty()) {
        if (selectionMode) {
            closeSelection()
        } else {
            closeSearch()
        }
    }

    val playerClearance = if (model.current != null) {
        PlayerNavigationGeometry.miniHeight + PlayerNavigationGeometry.bottomSpacing + 14.dp
    } else {
        0.dp
    }

    AppPage(
        title = tr("llm_raw_history_title", "历史输出"),
        onBack = back,
        actions = {
            AnimatedVisibility(
                visible = !selectionMode,
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .9f),
                exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .9f),
            ) {
                Row {
                    GlassToolbarIconButton(
                        icon = MiuixIcons.Regular.Download,
                        description = tr("llm_raw_history_export_zip", "导出为 ZIP"),
                        onClick = {
                            if (busy) return@GlassToolbarIconButton
                            if (filteredEntries.isEmpty()) {
                                Toast.makeText(
                                    context,
                                    tr("llm_raw_history_export_empty", "暂无可导出的历史输出"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                pendingZipExport = filteredEntries
                                val stamp = SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(Date())
                                zipExporter.launch("AuraPlayer_RawOutputs_$stamp.zip")
                            }
                        },
                    )
                }
            }
        },
        header = {
            PlaylistHeaderSearchBar(
                query = searchQuery,
                expanded = searchExpanded,
                onQueryChange = { searchQuery = it },
                onExpandedChange = { expanded ->
                    searchExpanded = expanded
                    if (!expanded) searchQuery = ""
                },
                onSearch = { keyboard?.hide() },
                placeholder = tr("llm_raw_history_search_hint", "搜索歌曲、模型、语言或原始内容"),
            )
        },
        floatingToolbarPosition = ToolbarPosition.BottomCenter,
        floatingToolbar = {
            Box(Modifier.padding(bottom = playerClearance)) {
                AnimatedVisibility(
                    visible = selectionMode,
                    enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = .86f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = .9f),
                ) {
                    GlassLyricFloatingToolbar {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                enabled = filteredEntries.isNotEmpty() && !busy,
                                onClick = {
                                    val visibleIds = filteredEntries.mapTo(linkedSetOf(), LlmRawOutputRecord::id)
                                    val allVisibleSelected = visibleIds.isNotEmpty() && visibleIds.all { it in selectedIds }
                                    selectedIds = if (allVisibleSelected) {
                                        selectedIds - visibleIds
                                    } else {
                                        selectedIds + visibleIds
                                    }
                                },
                            ) {
                                Icon(
                                    MiuixIcons.Regular.SelectAll,
                                    tr("llm_cache_select_all", "全选"),
                                    Modifier.size(26.4.dp),
                                )
                            }
                            IconButton(
                                enabled = selectedIds.isNotEmpty() && !busy,
                                onClick = {
                                    pendingZipExport = entries.filter { it.id in selectedIds }
                                    val stamp = SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(Date())
                                    zipExporter.launch("AuraPlayer_RawOutputs_$stamp.zip")
                                },
                            ) {
                                Icon(
                                    MiuixIcons.Regular.Download,
                                    tr("export", "导出"),
                                    Modifier.size(26.4.dp),
                                )
                            }
                            IconButton(
                                enabled = selectedIds.isNotEmpty() && !busy,
                                onClick = { confirmBatchDelete = true },
                            ) {
                                Icon(
                                    MiuixIcons.Regular.Delete,
                                    tr("delete", "删除"),
                                    Modifier.size(26.4.dp),
                                    tint = if (selectedIds.isNotEmpty() && !busy) {
                                        Color(0xFFD32F2F)
                                    } else {
                                        Color(0xFFD32F2F).copy(alpha = 0.38f)
                                    },
                                )
                            }
                            IconButton(onClick = ::closeSelection) {
                                Icon(
                                    MiuixIcons.Regular.Close,
                                    tr("close", "关闭"),
                                    Modifier.size(26.4.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding, scroll ->
        val bottomExtra = (if (selectionMode) 112.dp else 28.dp) + (if (model.current != null) 76.dp else 0.dp)
        val contentPadding = PaddingValues(
            start = LocalAuraLayout.current.pageMargin,
            top = padding.calculateTopPadding() + 4.dp,
            end = LocalAuraLayout.current.pageMargin,
            bottom = padding.calculateBottomPadding() + bottomExtra,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("llm_raw_history_usage_card") {
                SeamlessCard(
                    Modifier.fillMaxWidth(),
                    cornerRadius = 22.5.dp,
                    insideMargin = PaddingValues(0.dp),
                ) {
                    val countSummary = tr("llm_raw_history_summary", "已记录 %d 次 · %s")
                        .format(entries.size, formatLlmCacheBytes(totalBytes))
                    Preference(
                        title = tr("llm_raw_history_usage", "历史输出记录"),
                        summary = if (entries.isNotEmpty()) {
                            "$countSummary · ${tr("llm_raw_history_tap_clear", "点击清除全部记录")}"
                        } else {
                            countSummary
                        },
                        enabled = !busy && !selectionMode && entries.isNotEmpty(),
                    ) {
                        confirmClearAll = true
                    }
                }
            }

            if (filteredEntries.isEmpty()) {
                item("llm_raw_history_empty_card") {
                    SeamlessCard(
                        Modifier.fillMaxWidth(),
                        cornerRadius = 22.5.dp,
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank() && entries.isNotEmpty()) {
                                tr("llm_raw_history_no_match", "没有匹配的历史输出")
                            } else {
                                tr("llm_raw_history_empty", "暂无 API 原始输出记录")
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                        )
                    }
                }
            } else {
                items(filteredEntries, key = LlmRawOutputRecord::id) { record ->
                    val baseTitle = record.songTitle.ifBlank { tr("unknown_title", "未命名歌曲") }
                    val cardTitle = if (record.totalChunks > 1) {
                        "$baseTitle · #${record.chunkIndex + 1}/${record.totalChunks}"
                    } else {
                        baseTitle
                    }
                    val meta = buildRawHistoryMeta(record)
                    val preview = record.primaryOutput
                        .replace('\r', ' ')
                        .replace('\n', ' ')
                        .trim()
                        .ifBlank { tr("llm_raw_history_empty_body", "（空响应）") }
                    SeamlessCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 22.5.dp,
                        insideMargin = PaddingValues(16.dp),
                        showIndication = true,
                        onClick = {
                            if (busy) return@SeamlessCard
                            if (selectionMode) {
                                toggleSelection(record.id)
                            } else {
                                selectedRecord = record
                            }
                        },
                        onLongPress = {
                            if (busy) return@SeamlessCard
                            selectionMode = true
                            selectedIds = selectedIds + record.id
                        },
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = cardTitle,
                                    style = MiuixTheme.textStyles.body1,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = meta,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = if (record.succeeded) {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    } else {
                                        Color(0xFFD32F2F)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = preview,
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            AnimatedVisibility(
                                visible = selectionMode,
                                enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 2 },
                                exit = fadeOut(tween(120)) + slideOutHorizontally(tween(160)) { it / 2 },
                            ) {
                                Checkbox(
                                    state = if (record.id in selectedIds) ToggleableState.On else ToggleableState.Off,
                                    onClick = { toggleSelection(record.id) },
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    WindowDialog(
        show = confirmClearAll,
        title = tr("llm_raw_history_clear_title", "清除全部历史输出"),
        summary = tr(
            "llm_raw_history_clear_summary",
            "确定清除全部 %d 条历史输出记录（%s）吗？此操作无法撤销。",
        ).format(entries.size, formatLlmCacheBytes(totalBytes)),
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { confirmClearAll = false },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassDialogButton(
                onClick = { confirmClearAll = false },
                modifier = Modifier.weight(1f),
            ) { Text(tr("cancel", "取消")) }
            GlassDialogButton(
                onClick = {
                    confirmClearAll = false
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { LlmRawOutputHistoryStore.clear(context.cacheDir) }
                        refreshTick++
                        busy = false
                        Toast.makeText(
                            context,
                            tr("llm_raw_history_cleared", "已清除全部历史输出"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("clear", "清除")) }
        }
    }

    WindowDialog(
        show = confirmBatchDelete,
        title = tr("llm_raw_history_batch_delete_title", "删除所选记录？"),
        summary = tr(
            "llm_raw_history_batch_delete_summary",
            "确定删除所选 %d 条 API 历史输出记录吗？此操作无法撤销。",
        ).format(selectedIds.size),
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { confirmBatchDelete = false },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassDialogButton(
                onClick = { confirmBatchDelete = false },
                modifier = Modifier.weight(1f),
            ) { Text(tr("cancel", "取消")) }
            GlassDialogButton(
                onClick = {
                    val toDelete = selectedIds.toSet()
                    confirmBatchDelete = false
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            LlmRawOutputHistoryStore.deleteEntries(context.cacheDir, toDelete)
                        }
                        refreshTick++
                        busy = false
                        closeSelection()
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(tr("delete", "删除"), color = Color(0xFFD32F2F)) }
        }
    }

    selectedRecord?.let { record ->
        val dialogTitle = listOf(
            record.songTitle.ifBlank { tr("unknown_title", "未命名歌曲") },
            record.artist,
        ).filter { it.isNotBlank() }.joinToString(" · ")
        val dialogSummary = buildRawHistoryMeta(record)

        WindowDialog(
            show = true,
            title = dialogTitle,
            summary = dialogSummary,
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { if (!busy) selectedRecord = null },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton(
                        onClick = {
                            selectedRecord = null
                            inspectShowFullHttp = false
                            inspectRecord = record
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(tr("view", "查看")) }
                    GlassDialogButton(
                        onClick = {
                            copyToClipboard(
                                context,
                                tr("llm_raw_history_title", "历史输出"),
                                record.primaryOutput,
                            )
                            Toast.makeText(context, tr("copied", "已复制"), Toast.LENGTH_SHORT).show()
                            selectedRecord = null
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("copy", "复制")) }
                }
                GlassDialogButton(
                    onClick = {
                        exportTarget = record
                        selectedRecord = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("export", "导出")) }
                GlassDialogButton(
                    onClick = {
                        pendingDeleteRecord = record
                        selectedRecord = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("delete", "删除"), color = Color(0xFFD32F2F)) }
                GlassDialogButton(
                    onClick = { selectedRecord = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("cancel", "取消")) }
            }
        }
    }

    inspectRecord?.let { record ->
        val hasDistinctHttp = record.rawResponseBody.isNotBlank() &&
            record.rawResponseBody != record.primaryOutput
        val displayedText = if (inspectShowFullHttp && hasDistinctHttp) {
            record.rawResponseBody
        } else {
            record.primaryOutput.ifBlank { tr("llm_raw_history_empty_body", "（空响应）") }
        }
        val title = listOf(
            record.songTitle.ifBlank { tr("unknown_title", "未命名歌曲") },
            record.modelName,
        ).filter { it.isNotBlank() }.joinToString(" · ")
        val summary = buildRawHistoryMeta(record)

        WindowDialog(
            show = true,
            title = title,
            summary = summary,
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { inspectRecord = null },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasDistinctHttp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassDialogButton(
                            onClick = { inspectShowFullHttp = false },
                            modifier = Modifier.weight(1f),
                            colors = if (!inspectShowFullHttp) {
                                ButtonDefaults.buttonColorsPrimary()
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                        ) { Text(tr("llm_raw_history_tab_content", "模型输出")) }
                        GlassDialogButton(
                            onClick = { inspectShowFullHttp = true },
                            modifier = Modifier.weight(1f),
                            colors = if (inspectShowFullHttp) {
                                ButtonDefaults.buttonColorsPrimary()
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                        ) { Text(tr("llm_raw_history_tab_http", "HTTP 原文")) }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 300.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = displayedText,
                            style = MiuixTheme.textStyles.body2.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            ),
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton(
                        onClick = {
                            copyToClipboard(
                                context,
                                tr("llm_raw_history_title", "历史输出"),
                                displayedText,
                            )
                            Toast.makeText(context, tr("copied", "已复制"), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("copy", "复制")) }
                    GlassDialogButton(
                        onClick = { inspectRecord = null },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(tr("close", "关闭")) }
                }
            }
        }
    }

    pendingDeleteRecord?.let { record ->
        val label = record.songTitle.ifBlank { tr("unknown_title", "未命名歌曲") }
        WindowDialog(
            show = true,
            title = tr("confirm_delete", "确认删除"),
            summary = tr("llm_raw_history_delete_confirm", "确定删除「%s」的这条历史输出记录吗？").format(label),
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { pendingDeleteRecord = null },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(
                    onClick = { pendingDeleteRecord = null },
                    modifier = Modifier.weight(1f),
                ) { Text(tr("cancel", "取消")) }
                GlassDialogButton(
                    onClick = {
                        val targetId = record.id
                        pendingDeleteRecord = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LlmRawOutputHistoryStore.deleteEntries(context.cacheDir, listOf(targetId))
                            }
                            refreshTick++
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("delete", "删除")) }
            }
        }
    }

    exportTarget?.let { record ->
        WindowDialog(
            show = true,
            title = tr("llm_raw_history_export_title", "导出原始输出"),
            summary = buildRawHistoryMeta(record),
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { exportTarget = null },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(
                    onClick = {
                        exportTarget = null
                        launchSingleExport(record, LlmRawOutputExportFormat.JSON, jsonExporter)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("llm_export_json", "导出为 JSON")) }
                GlassDialogButton(
                    onClick = {
                        exportTarget = null
                        launchSingleExport(record, LlmRawOutputExportFormat.TXT, txtExporter)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("llm_export_txt", "导出为 TXT")) }
                GlassDialogButton(
                    onClick = { exportTarget = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("cancel", "取消")) }
            }
        }
    }
}

private fun buildRawHistoryMeta(record: LlmRawOutputRecord): String {
    val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(record.createdAtMs))
    val langPair = listOf(record.sourceLanguage, record.targetLanguage)
        .filter { it.isNotBlank() }
        .joinToString("→")
    val status = if (record.httpStatus > 0) "HTTP ${record.httpStatus}" else tr("failed", "失败")
    return listOf(stamp, record.modelName, langPair, status)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}
