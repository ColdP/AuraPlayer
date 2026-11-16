// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.lyrics.LlmTranslationCache
import btm.m.auraplayer.lyrics.LlmTranslationCacheEntry
import btm.m.auraplayer.lyrics.LlmTranslationExportFormat
import btm.m.auraplayer.lyrics.LlmTranslationExporter
import btm.m.auraplayer.lyrics.LlmTranslationImporter
import btm.m.auraplayer.lyrics.LlmTranslator
import btm.m.auraplayer.lyrics.formatLlmCacheBytes
import btm.m.auraplayer.ui.navigation.InteractiveHighlight
import btm.m.auraplayer.ui.player.PlayerNavigationGeometry
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.ToolbarPosition
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun llmTranslationEditRoute(cacheKey: String): String =
    "llm_translation_edit:${Uri.encode(cacheKey)}"

@Composable
fun LlmTranslationCachePage(
    model: AuraViewModel,
    back: () -> Unit,
    open: (String) -> Unit = {},
    isActive: Boolean = true,
) {
    val context = LocalContext.current
    val app = context.applicationContext as AuraApplication
    val store = app.llmTranslation
    val config by store.state.collectAsState()
    val scope = rememberCoroutineScope()

    var refreshTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(isActive) {
        if (isActive) refreshTick++
    }
    val entries by produceState(emptyList<LlmTranslationCacheEntry>(), refreshTick) {
        value = withContext(Dispatchers.IO) { LlmTranslationCache.listEntries(context.cacheDir) }
    }
    val totalBytes = remember(entries) { entries.sumOf { it.sizeBytes } }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var pendingZipExport by remember { mutableStateOf<List<LlmTranslationCacheEntry>>(emptyList()) }

    var confirmClearAll by remember { mutableStateOf(false) }
    var selectedEntry by remember { mutableStateOf<LlmTranslationCacheEntry?>(null) }
    var pendingDeleteEntry by remember { mutableStateOf<LlmTranslationCacheEntry?>(null) }
    var pendingImportEntries by remember { mutableStateOf<List<LlmTranslationCacheEntry>?>(null) }
    var exportTarget by remember { mutableStateOf<LlmTranslationCacheEntry?>(null) }
    var pendingExportFormat by remember { mutableStateOf<LlmTranslationExportFormat?>(null) }
    var pendingExportEntry by remember { mutableStateOf<LlmTranslationCacheEntry?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun closeSelection() {
        selectionMode = false
        selectedKeys = emptySet()
    }

    fun toggleSelection(cacheKey: String) {
        selectedKeys = if (cacheKey in selectedKeys) selectedKeys - cacheKey else selectedKeys + cacheKey
    }

    LaunchedEffect(entries) {
        if (entries.isEmpty()) {
            closeSelection()
        } else {
            val valid = entries.mapTo(HashSet(), LlmTranslationCacheEntry::cacheKey)
            val pruned = selectedKeys.filterTo(LinkedHashSet()) { it in valid }
            if (pruned.size != selectedKeys.size) {
                selectedKeys = pruned
                if (pruned.isEmpty()) selectionMode = false
            }
        }
    }

    fun launchExport(
        entry: LlmTranslationCacheEntry,
        format: LlmTranslationExportFormat,
        launcher: androidx.activity.result.ActivityResultLauncher<String>,
    ) {
        pendingExportEntry = entry
        pendingExportFormat = format
        launcher.launch(LlmTranslationExporter.suggestedFileName(entry, format))
    }

    val handleExportResult: (Uri?) -> Unit = { uri ->
        val entry = pendingExportEntry
        val format = pendingExportFormat
        pendingExportEntry = null
        pendingExportFormat = null
        if (uri != null && entry != null && format != null) {
            scope.launch {
                val payload = LlmTranslationExporter.export(entry, format)
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(payload.toByteArray(Charsets.UTF_8))
                        } ?: error("Cannot open output stream")
                    }.isSuccess
                }
                Toast.makeText(
                    context,
                    if (ok) tr("llm_cache_exported", "翻译已导出") else tr("llm_cache_export_failed", "导出失败，请重试"),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val jsonExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LlmTranslationExportFormat.JSON.mimeType),
        handleExportResult,
    )
    val mdExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LlmTranslationExportFormat.MARKDOWN.mimeType),
        handleExportResult,
    )
    val txtExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LlmTranslationExportFormat.TXT.mimeType),
        handleExportResult,
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
                        LlmTranslationExporter.writeZip(selected, out)
                    } ?: error("unable to create zip")
                }.isSuccess
            }
            busy = false
            if (ok) {
                Toast.makeText(context, tr("llm_cache_exported", "翻译已导出"), Toast.LENGTH_SHORT).show()
                closeSelection()
            } else {
                Toast.makeText(context, tr("llm_cache_export_failed", "导出失败，请重试"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val parsedAll = withContext(Dispatchers.IO) {
                val collected = ArrayList<LlmTranslationCacheEntry>()
                for (uri in uris) {
                    val fileName = queryImportDisplayName(context, uri)
                    val parsed = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            LlmTranslationImporter.importStream(fileName, stream, config.provider)
                        }.orEmpty()
                    }.getOrDefault(emptyList())
                    collected += parsed
                }
                collected
            }
            busy = false
            if (parsedAll.isNotEmpty()) {
                pendingImportEntries = parsedAll
            } else {
                Toast.makeText(
                    context,
                    tr("llm_cache_import_failed", "导入失败，请选择有效的 JSON / MD / TXT / ZIP 文件"),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    BackHandler(enabled = selectionMode) { closeSelection() }

    val playerClearance = if (model.current != null) {
        PlayerNavigationGeometry.miniHeight + PlayerNavigationGeometry.bottomSpacing + 14.dp
    } else {
        0.dp
    }

    AppPage(
        title = tr("llm_translation_cache", "翻译缓存"),
        onBack = back,
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
                                enabled = entries.isNotEmpty() && !busy,
                                onClick = {
                                    selectedKeys = if (selectedKeys.size == entries.size) {
                                        emptySet()
                                    } else {
                                        entries.mapTo(linkedSetOf(), LlmTranslationCacheEntry::cacheKey)
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
                                enabled = selectedKeys.isNotEmpty() && !busy,
                                onClick = {
                                    pendingZipExport = entries.filter { it.cacheKey in selectedKeys }
                                    val stamp = SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(Date())
                                    zipExporter.launch("AuraPlayer_Translations_$stamp.zip")
                                },
                            ) {
                                Icon(
                                    MiuixIcons.Regular.Download,
                                    tr("export", "导出"),
                                    Modifier.size(26.4.dp),
                                )
                            }
                            IconButton(
                                enabled = selectedKeys.isNotEmpty() && !busy,
                                onClick = { confirmBatchDelete = true },
                            ) {
                                Icon(
                                    MiuixIcons.Regular.Delete,
                                    tr("delete", "删除"),
                                    Modifier.size(26.4.dp),
                                    tint = if (selectedKeys.isNotEmpty() && !busy) {
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
            // Card 1: No SmallTitle. Displays total cache footprint and prompts confirmation before clearing all.
            item("llm_cache_usage_card") {
                SeamlessCard(
                    Modifier.fillMaxWidth(),
                    cornerRadius = 22.5.dp,
                    insideMargin = PaddingValues(0.dp),
                ) {
                    Preference(
                        title = tr("llm_cache_usage", "翻译缓存占用"),
                        summary = "${formatLlmCacheBytes(totalBytes)} · ${tr("llm_cache_tap_clear", "点击清除全部缓存")}",
                        enabled = !busy && !selectionMode,
                    ) {
                        confirmClearAll = true
                    }
                    Preference(
                        title = tr("llm_cache_import_action", "导入翻译"),
                        summary = tr("llm_cache_import_summary", "支持导入 JSON / MD / TXT / ZIP 格式的歌词翻译"),
                        enabled = !busy && !selectionMode,
                    ) {
                        importer.launch(arrayOf("*/*"))
                    }
                }
            }

            if (entries.isEmpty()) {
                item("llm_cache_empty_card") {
                    SeamlessCard(
                        Modifier.fillMaxWidth(),
                        cornerRadius = 22.5.dp,
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        Text(
                            tr("llm_cache_empty", "暂无大模型翻译缓存"),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                        )
                    }
                }
            } else {
                items(entries, key = LlmTranslationCacheEntry::cacheKey) { entry ->
                    val title = entry.songTitle.ifBlank { tr("unknown_title", "未命名歌曲") }
                    val summary = listOf(entry.modelName, entry.artist, entry.albumTitle)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    SeamlessCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 22.5.dp,
                        insideMargin = PaddingValues(16.dp),
                        showIndication = true,
                        onClick = {
                            if (busy) return@SeamlessCard
                            if (selectionMode) {
                                toggleSelection(entry.cacheKey)
                            } else {
                                selectedEntry = entry
                            }
                        },
                        onLongPress = {
                            if (busy) return@SeamlessCard
                            selectionMode = true
                            selectedKeys = selectedKeys + entry.cacheKey
                        },
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    style = MiuixTheme.textStyles.body1,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (summary.isNotEmpty()) {
                                    Text(
                                        text = summary,
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                            AnimatedVisibility(
                                visible = selectionMode,
                                enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 2 },
                                exit = fadeOut(tween(120)) + slideOutHorizontally(tween(160)) { it / 2 },
                            ) {
                                Checkbox(
                                    state = if (entry.cacheKey in selectedKeys) ToggleableState.On else ToggleableState.Off,
                                    onClick = { toggleSelection(entry.cacheKey) },
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
        title = tr("llm_cache_clear_confirm_title", "清除全部缓存"),
        summary = tr(
            "llm_cache_clear_confirm_summary",
            "确定清除全部 %d 首歌曲的翻译缓存（%s）吗？此操作无法撤销。",
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
                        withContext(Dispatchers.IO) { LlmTranslator.clearCache(context.cacheDir) }
                        model.notifyLlmTranslationCacheChanged(null)
                        refreshTick++
                        busy = false
                        Toast.makeText(context, tr("llm_cache_cleared", "已清除全部翻译缓存"), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("clear", "清除")) }
        }
    }

    WindowDialog(
        show = confirmBatchDelete,
        title = tr("llm_cache_batch_delete_title", "删除所选翻译？"),
        summary = tr(
            "llm_cache_batch_delete_summary",
            "确定删除所选 %d 首歌曲的翻译缓存吗？此操作无法撤销。",
        ).format(selectedKeys.size),
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
                    val keysToDelete = selectedKeys.toSet()
                    confirmBatchDelete = false
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            LlmTranslationCache.deleteEntries(context.cacheDir, keysToDelete)
                        }
                        model.notifyLlmTranslationCacheChanged(null)
                        refreshTick++
                        busy = false
                        closeSelection()
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(tr("delete", "删除"), color = Color(0xFFD32F2F)) }
        }
    }

    selectedEntry?.let { entry ->
        val dialogTitle = listOf(
            entry.songTitle.ifBlank { tr("unknown_title", "未命名歌曲") },
            entry.artist,
        ).filter { it.isNotBlank() }.joinToString(" · ")
        val dialogSummary = listOf(entry.modelName, entry.albumTitle)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

        WindowDialog(
            show = true,
            title = dialogTitle,
            summary = dialogSummary,
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { if (!busy) selectedEntry = null },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton(
                        onClick = {
                            val currentKey = store.apiKeyFor()
                            if (!config.enabled || currentKey.isBlank() || config.activeModel().isBlank()) {
                                Toast.makeText(
                                    context,
                                    tr("llm_regenerate_unavailable", "请先启用大模型翻译并完成接口与模型配置"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@GlassDialogButton
                            }
                            busy = true
                            scope.launch {
                                val updated = LlmTranslator.regenerateCachedEntry(
                                    entry = entry,
                                    config = config,
                                    apiKey = currentKey,
                                    cacheRoot = context.cacheDir,
                                )
                                busy = false
                                if (updated != null) {
                                    selectedEntry = updated
                                    model.notifyLlmTranslationCacheChanged(null)
                                    refreshTick++
                                    Toast.makeText(context, tr("llm_regenerate_success", "已重新生成翻译"), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, tr("llm_regenerate_failed", "重新生成失败，请检查配置或语种设置"), Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(if (busy) tr("llm_regenerating", "正在生成…") else tr("llm_regenerate", "重新生成"))
                    }
                    GlassDialogButton(
                        onClick = {
                            pendingDeleteEntry = entry
                            selectedEntry = null
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) {
                        Text(tr("delete", "删除"))
                    }
                }
                GlassDialogButton(
                    onClick = {
                        val targetKey = entry.cacheKey
                        selectedEntry = null
                        open(llmTranslationEditRoute(targetKey))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text(tr("llm_edit_lines", "编辑译文"))
                }
                GlassDialogButton(
                    onClick = {
                        exportTarget = entry
                        selectedEntry = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text(tr("export", "导出"))
                }
                GlassDialogButton(
                    onClick = { selectedEntry = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text(tr("cancel", "取消"))
                }
            }
        }
    }

    pendingDeleteEntry?.let { entry ->
        val songLabel = entry.songTitle.ifBlank { tr("unknown_title", "未命名歌曲") }
        WindowDialog(
            show = true,
            title = tr("confirm_delete", "确认删除"),
            summary = tr("llm_cache_delete_confirm", "确定删除「%s」的翻译缓存吗？").format(songLabel),
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { pendingDeleteEntry = null },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(
                    onClick = { pendingDeleteEntry = null },
                    modifier = Modifier.weight(1f),
                ) { Text(tr("cancel", "取消")) }
                GlassDialogButton(
                    onClick = {
                        val targetKey = entry.cacheKey
                        pendingDeleteEntry = null
                        scope.launch {
                            withContext(Dispatchers.IO) { LlmTranslationCache.deleteEntry(context.cacheDir, targetKey) }
                            model.notifyLlmTranslationCacheChanged(null)
                            refreshTick++
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("delete", "删除")) }
            }
        }
    }

    exportTarget?.let { entry ->
        WindowDialog(
            show = true,
            title = tr("llm_export_title", "导出翻译"),
            summary = listOf(
                entry.songTitle.ifBlank { tr("unknown_title", "未命名歌曲") },
                entry.artist,
            ).filter { it.isNotBlank() }.joinToString(" · "),
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { exportTarget = null },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(
                    onClick = {
                        exportTarget = null
                        launchExport(entry, LlmTranslationExportFormat.JSON, jsonExporter)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("llm_export_json", "导出为 JSON")) }
                GlassDialogButton(
                    onClick = {
                        exportTarget = null
                        launchExport(entry, LlmTranslationExportFormat.MARKDOWN, mdExporter)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("llm_export_markdown", "导出为 Markdown")) }
                GlassDialogButton(
                    onClick = {
                        exportTarget = null
                        launchExport(entry, LlmTranslationExportFormat.TXT, txtExporter)
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

    pendingImportEntries?.let { candidates ->
        val previewTitles = candidates.take(5).joinToString("、") {
            it.songTitle.ifBlank { tr("unknown_title", "未命名歌曲") }
        }
        val titleListText = if (candidates.size > 5) "$previewTitles …" else previewTitles
        WindowDialog(
            show = true,
            title = tr("llm_cache_import_confirm_title", "确认导入翻译"),
            summary = tr(
                "llm_cache_import_confirm_summary",
                "已从所选文件中解析出 %d 首歌曲的歌词翻译（%s），确定导入到翻译缓存吗？",
            ).format(candidates.size, titleListText),
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { if (!busy) pendingImportEntries = null },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(
                    onClick = { pendingImportEntries = null },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) { Text(tr("cancel", "取消")) }
                GlassDialogButton(
                    onClick = {
                        val toWrite = candidates
                        pendingImportEntries = null
                        busy = true
                        scope.launch {
                            val importedCount = withContext(Dispatchers.IO) {
                                LlmTranslationCache.writeAll(context.cacheDir, toWrite)
                            }
                            busy = false
                            if (importedCount > 0) {
                                model.notifyLlmTranslationCacheChanged(null)
                                refreshTick++
                                Toast.makeText(
                                    context,
                                    tr("llm_cache_imported_count", "已导入 %d 首歌词翻译").format(importedCount),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    tr("llm_cache_import_failed", "导入失败，请选择有效的 JSON / MD / TXT / ZIP 文件"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("import_action", "导入")) }
            }
        }
    }
}

/** Copied from HyperChanger's GlassLyricFloatingToolbar with Backdrop vibrancy + blur + lens. */
@Composable
internal fun GlassLyricFloatingToolbar(
    content: @Composable () -> Unit,
) {
    val backdrop = LocalToolbarBackdrop.current
    val animationScope = rememberCoroutineScope()
    val touchHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope,
            radiusMultiplier = .4f,
            surfaceAlpha = 0f,
            falloffMultiplier = .25f,
        ) { size, offset ->
            Offset(
                offset.x.coerceIn(0f, size.width),
                offset.y.coerceIn(0f, size.height),
            )
        }
    }
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val isLightTheme = surface.luminance() > .5f
    val tint = (if (isLightTheme) Color(0xFFF8F8F8) else surface).copy(alpha = .80f)
    val shadowColor = if (isLightTheme) Color.Gray else Color.Black
    val shape = RoundedCornerShape(50.dp)
    val glassModifier = if (backdrop != null && LocalAdvancedMaterialEnabled.current && isRuntimeShaderSupported()) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(3.dp.toPx())
                lens(12.dp.toPx(), 32.dp.toPx(), chromaticAberration = true)
            },
            highlight = { Highlight.Default.copy(alpha = .70f) },
            shadow = {
                Shadow.Default.copy(
                    radius = 12.dp,
                    color = shadowColor,
                    alpha = .35f,
                )
            },
            innerShadow = null,
            onDrawSurface = { drawRect(tint) },
        )
    } else {
        Modifier.clip(shape).background(tint)
    }
    FloatingToolbar(
        modifier = glassModifier
            .then(touchHighlight.gestureModifier)
            .then(touchHighlight.modifier),
        color = Color.Transparent,
        cornerRadius = 50.dp,
        shadowElevation = 0.dp,
        showDivider = false,
        content = content,
    )
}

private fun queryImportDisplayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

@Composable
internal fun LlmTranslationLineEditorRoute(
    cacheKey: String,
    model: AuraViewModel,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableIntStateOf(0) }
    val loadedEntry by produceState<LlmTranslationCacheEntry?>(null, cacheKey, refreshTick) {
        value = withContext(Dispatchers.IO) { LlmTranslationCache.read(context.cacheDir, cacheKey) }
    }
    var editedEntry by remember(cacheKey) { mutableStateOf<LlmTranslationCacheEntry?>(null) }
    val entry = editedEntry ?: loadedEntry

    var editingLineIndex by remember { mutableStateOf<Int?>(null) }
    var lineDraft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    BackHandler(enabled = saving) {}

    val pageTitle = entry?.songTitle?.ifBlank { tr("unknown_title", "未命名歌曲") }
        ?: tr("llm_edit_lines", "编辑译文")
    val subtitleMeta = entry?.let {
        listOf(it.artist, it.modelName).filter { part -> part.isNotBlank() }.joinToString(" · ")
    }.orEmpty()

    AppPage(
        title = pageTitle,
        onBack = { if (!saving) back() },
    ) { padding, scroll ->
        val bottomExtra = 28.dp + (if (model.current != null) 76.dp else 0.dp)
        val contentPadding = PaddingValues(
            start = LocalAuraLayout.current.pageMargin,
            top = padding.calculateTopPadding() + 4.dp,
            end = LocalAuraLayout.current.pageMargin,
            bottom = padding.calculateBottomPadding() + bottomExtra,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (entry == null) {
                item("line_editor_empty") {
                    SeamlessCard(
                        Modifier.fillMaxWidth(),
                        cornerRadius = 22.5.dp,
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        Text(
                            text = tr("loading", "加载中…"),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            } else {
                item("line_editor_header") {
                    SeamlessCard(
                        Modifier.fillMaxWidth(),
                        cornerRadius = 22.5.dp,
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        if (subtitleMeta.isNotEmpty()) {
                            Text(
                                text = subtitleMeta,
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = tr("llm_line_editor_hint", "点击任意歌词行即可单独修改该行翻译"),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = if (subtitleMeta.isNotEmpty()) 4.dp else 0.dp),
                        )
                    }
                }

                itemsIndexed(entry.lines, key = { idx, line -> "${idx}_${line.startMs}" }) { index, line ->
                val timestamp = LlmTranslationExporter.formatTimestamp(line.startMs)
                val originalText = line.text.ifBlank { tr("llm_line_empty_original", "（空行 / 间奏）") }
                val translationText = line.translation?.takeIf { it.isNotBlank() }
                SeamlessCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    showIndication = true,
                    onClick = {
                        if (saving) return@SeamlessCard
                        editingLineIndex = index
                        lineDraft = line.translation.orEmpty()
                    },
                ) {
                    Text(
                        text = "[$timestamp]",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = originalText,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = translationText ?: tr("llm_line_no_translation", "暂无译文 · 点击添加"),
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = if (translationText != null) FontWeight.Medium else FontWeight.Normal,
                        color = if (translationText != null) {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.72f)
                        },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            }
        }
    }

    val activeEntry = entry ?: return
    editingLineIndex?.let { index ->
        val targetLine = activeEntry.lines.getOrNull(index) ?: return@let
        val timestamp = LlmTranslationExporter.formatTimestamp(targetLine.startMs)
        val originalSummary = targetLine.text.ifBlank { tr("llm_line_empty_original", "（空行 / 间奏）") }
        WindowDialog(
            show = true,
            title = "[$timestamp] · ${tr("llm_edit_line_title", "编辑该行翻译")}",
            summary = originalSummary,
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { if (!saving) editingLineIndex = null },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AuraTextField(
                    value = lineDraft,
                    onValueChange = { lineDraft = it },
                    label = tr("llm_line_translation_label", "译文（留空则清除该行翻译）"),
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 200.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton(
                        onClick = { editingLineIndex = null },
                        modifier = Modifier.weight(1f),
                        enabled = !saving,
                    ) { Text(tr("cancel", "取消")) }
                    GlassDialogButton(
                        onClick = {
                            saving = true
                            scope.launch {
                                    val updated = withContext(Dispatchers.IO) {
                                        LlmTranslationCache.updateLineTranslation(
                                            cacheRoot = context.cacheDir,
                                            entry = activeEntry,
                                            lineIndex = index,
                                            newTranslation = lineDraft,
                                        )
                                    }
                                    saving = false
                                    if (updated != null) {
                                        editingLineIndex = null
                                        editedEntry = updated
                                        refreshTick++
                                        model.notifyLlmTranslationCacheChanged(null)
                                    Toast.makeText(
                                        context,
                                        tr("llm_line_saved", "已保存该行翻译"),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        tr("llm_line_save_failed", "保存失败，请重试"),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !saving,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(tr("save", "保存")) }
                }
            }
        }
    }
}
