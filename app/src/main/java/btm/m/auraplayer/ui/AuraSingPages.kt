package btm.m.auraplayer.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.audio.OutputWaveform
import btm.m.auraplayer.sing.*
import btm.m.auraplayer.spatialcover.SpatialModelDownloadManager
import btm.m.auraplayer.spatialcover.SpatialModelDownloadState
import btm.m.auraplayer.spatialcover.SpatialModelResource
import btm.m.auraplayer.spatialcover.SpatialModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
fun AuraSingPage(padding: PaddingValues, isActive: Boolean, open: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AuraApplication
    val engine = app.auraSing
    val uiState by engine.uiState.collectAsState()

    var cacheTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(isActive, uiState.trackState) {
        if (isActive) {
            SpatialModelDownloadManager.syncFromDisk(context)
            engine.refreshReadiness()
            cacheTick++
        }
    }
    val cacheSummaryText by produceState("", cacheTick, isActive) {
        value = withContext(Dispatchers.IO) {
            val entries = engine.cache.listEntries()
            val bytes = engine.cache.totalCacheBytes()
            if (entries.isEmpty()) {
                tr("aura_sing_cache_empty_summary", "暂无已分离音轨缓存")
            } else {
                tr("aura_sing_cache_count_summary", "已缓存 %d 首音轨 · %s")
                    .format(entries.size, formatSpatialBytes(bytes))
            }
        }
    }

    val modelReady = remember(uiState.model, isActive, SpatialModelDownloadManager.states.toMap()) {
        SpatialModelStore.isRuntimeReady(context) && SpatialModelStore.isReady(context, uiState.model.resource)
    }
    val qnnReady = remember(isActive, SpatialModelDownloadManager.states.toMap()) {
        AuraSingQualcommNpu.isReady(context)
    }
    val isEliteSoc = remember { AuraSingQualcommNpu.isSnapdragon8EliteOrAbove() }

    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            EffectsCard {
                SwitchPreference(
                    title = tr("aura_sing_title", "Aura Sing"),
                    summary = tr(
                        "aura_sing_switch_summary",
                        "开启后在正在播放界面显示伴唱按钮，基于本地 AI 分离并独立调节人声音量",
                    ),
                    checked = uiState.enabled,
                ) { engine.setSettingEnabled(it) }
            }
        }
        item {
            BlurAnimatedVisibility(uiState.enabled, label = "aura_sing_details") {
                EffectsCard {
                    DualStemSpectrumPreview(
                        engine = engine,
                        uiState = uiState,
                        isActive = isActive,
                        onOpenDownloads = { open("aura_sing_downloads") },
                    )
                    GlassDropdownPreference(
                        title = tr("aura_sing_model_label", "使用模型"),
                        items = AuraSingModel.entries.map { tr(it.labelKey, it.fallbackLabel) },
                        selectedIndex = uiState.model.ordinal,
                        backdrop = LocalToolbarBackdrop.current,
                    ) { index ->
                        engine.setModel(AuraSingModel.entries[index])
                    }
                    SwitchPreference(
                        title = tr("hardware_acceleration", "硬件加速"),
                        summary = tr(
                            "aura_sing_hw_accel_summary",
                            "优先调用高通骁龙 NPU / GPU 与 XNNPACK 多核加速音轨分离（关闭则使用兼容 CPU 推理）",
                        ),
                        checked = uiState.hardwareAcceleration,
                    ) { engine.setHardwareAcceleration(it) }
                    Preference(
                        title = tr("spatial_model_downloads", "模型下载"),
                        summary = when {
                            modelReady && qnnReady ->
                                tr("aura_sing_models_qnn_ready_summary", "ONNX Runtime + 骁龙 NPU (QNN) 与所选模型已就绪")
                            modelReady && isEliteSoc ->
                                tr("aura_sing_models_ready_elite_summary", "基础引擎与模型已就绪（可下载骁龙 8 Elite+ NPU 运行库）")
                            modelReady ->
                                tr("aura_sing_models_ready_summary", "ONNX Runtime 与所选分离模型已就绪")
                            else ->
                                tr("aura_sing_models_missing_summary", "下载分离模型与运行库（复用已安装的 ONNX Runtime）")
                        },
                    ) { open("aura_sing_downloads") }
                    Preference(
                        title = tr("aura_sing_cache_title", "伴奏与人声分离缓存"),
                        summary = cacheSummaryText,
                    ) { open("aura_sing_cache") }
                }
            }
        }
    }
}

@Composable
private fun DualStemSpectrumPreview(
    engine: AuraSingEngine,
    uiState: AuraSingUiState,
    isActive: Boolean,
    onOpenDownloads: () -> Unit,
) {
    val activeSpectrum = isActive && uiState.enabled && uiState.activeInPlayer
    val bands = OutputWaveform.SPECTRUM_BANDS
    val smoothVocal = remember { FloatArray(bands) }
    val smoothInst = remember { FloatArray(bands) }
    var renderTick by remember { mutableIntStateOf(0) }
    var signalVisibility by remember { mutableFloatStateOf(0f) }

    LifecycleResumeEffect(activeSpectrum, engine) {
        engine.vocalWaveform.enabled = activeSpectrum
        engine.vocalWaveform.spectrumEnabled = activeSpectrum
        engine.instrumentWaveform.enabled = activeSpectrum
        engine.instrumentWaveform.spectrumEnabled = activeSpectrum
        onPauseOrDispose {
            engine.vocalWaveform.enabled = false
            engine.vocalWaveform.spectrumEnabled = false
            engine.instrumentWaveform.enabled = false
            engine.instrumentWaveform.spectrumEnabled = false
        }
    }

    LaunchedEffect(activeSpectrum, uiState.isLiveSeparatingAudio, engine) {
        if (!activeSpectrum || !uiState.isLiveSeparatingAudio) {
            smoothVocal.fill(0f)
            smoothInst.fill(0f)
            signalVisibility = 0f
            renderTick++
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos {
                val now = System.nanoTime()
                val vSnap = engine.vocalWaveform.snapshot
                val iSnap = engine.instrumentWaveform.snapshot
                val vFresh = vSnap != null && (now - vSnap.updatedNanos) < 240_000_000L
                val iFresh = iSnap != null && (now - iSnap.updatedNanos) < 240_000_000L
                for (b in 0 until bands) {
                    val vTarget = if (vFresh && b < vSnap.spectrum.size) vSnap.spectrum[b] else 0f
                    val iTarget = if (iFresh && b < iSnap.spectrum.size) iSnap.spectrum[b] else 0f
                    val vAlpha = if (vTarget > smoothVocal[b]) 0.42f else 0.16f
                    val iAlpha = if (iTarget > smoothInst[b]) 0.42f else 0.16f
                    smoothVocal[b] += (vTarget - smoothVocal[b]) * vAlpha
                    smoothInst[b] += (iTarget - smoothInst[b]) * iAlpha
                }
                val targetVis = if (vFresh || iFresh) 1f else 0.25f
                signalVisibility += (targetVis - signalVisibility) * 0.18f
                renderTick++
            }
        }
    }

    val primary = MiuixTheme.colorScheme.primary
    val instColor = MiuixTheme.colorScheme.secondary
    val surfaceColor = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(10.5.dp))
            .background(primary.copy(alpha = 0.065f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                tr("aura_sing_dual_spectrum", "人声 / 伴奏实时频谱"),
                style = MiuixTheme.textStyles.body2,
                color = surfaceColor,
            )
            val badgeText = listOfNotNull(
                uiState.activeBackend.takeIf { it.isNotBlank() && uiState.activeInPlayer },
                uiState.trackTitle.takeIf { it.isNotBlank() && uiState.activeInPlayer },
            ).joinToString(" · ")
            if (badgeText.isNotBlank()) {
                Text(
                    badgeText,
                    style = MiuixTheme.textStyles.body2,
                    color = primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }

        if (!uiState.activeInPlayer) {
            Text(
                tr(
                    "aura_sing_spectrum_wait_player",
                    "在正在播放界面点击麦克风按钮开启 Aura Sing 后，将在这里分开实时输出当前歌曲的 Vocal 和 Instrument 音轨频谱。",
                ),
                style = MiuixTheme.textStyles.body2,
                color = surfaceColor.copy(alpha = 0.8f),
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            when (val ts = uiState.trackState) {
                is AuraSingTrackState.MissingModels -> {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            tr("aura_sing_missing_models_hint", "尚未下载 ONNX Runtime 或当前所选 HTDemucs 模型。"),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.error,
                        )
                        GlassDialogButton(
                            onClick = onOpenDownloads,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(tr("aura_sing_go_download", "前往模型下载"))
                        }
                    }
                }
                is AuraSingTrackState.Separating -> {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            tr("aura_sing_separating_progress", "正在使用 AI 模型分离当前歌曲人声与伴奏… %d%%")
                                .format((ts.progress * 100f).roundToInt().coerceIn(0, 100)),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MiuixTheme.textStyles.body2,
                            color = primary,
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MiuixTheme.colorScheme.surfaceVariant),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(ts.progress.coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .background(primary),
                            )
                        }
                    }
                }
                is AuraSingTrackState.Failed -> {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            tr("aura_sing_sep_failed", "分离失败：%s").format(ts.message),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.error,
                        )
                        GlassDialogButton(
                            onClick = { engine.retrySeparation() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(tr("retry", "重试"))
                        }
                    }
                }
                is AuraSingTrackState.Idle,
                is AuraSingTrackState.Ready -> {
                    @Suppress("UNUSED_EXPRESSION")
                    renderTick
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SpectrumTrackColumn(
                            label = tr("aura_sing_vocal_track", "Vocal 人声 (%d%%)")
                                .format((uiState.vocalVolume * 100f).roundToInt()),
                            levels = smoothVocal,
                            visibility = signalVisibility,
                            barColor = primary,
                            modifier = Modifier.weight(1f),
                        )
                        SpectrumTrackColumn(
                            label = tr("aura_sing_instrument_track", "Instrument 伴奏"),
                            levels = smoothInst,
                            visibility = signalVisibility,
                            barColor = instColor,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpectrumTrackColumn(
    label: String,
    levels: FloatArray,
    visibility: Float,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp),
        ) {
            val count = levels.size.coerceAtLeast(1)
            val gap = 4.dp.toPx()
            val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(2.dp.toPx())
            val maxHeight = size.height
            drawLine(
                color = barColor.copy(alpha = 0.16f),
                start = Offset(0f, maxHeight - 1.dp.toPx()),
                end = Offset(size.width, maxHeight - 1.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
            for (i in 0 until count) {
                val amp = (levels[i] * visibility).coerceIn(0.05f, 1f)
                val barHeight = (maxHeight * amp).coerceAtLeast(4.dp.toPx())
                val left = i * (barWidth + gap)
                drawRoundRect(
                    color = barColor.copy(alpha = 0.88f),
                    topLeft = Offset(left, maxHeight - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }
    }
}

@Composable
fun AuraSingDownloadsPage(padding: PaddingValues) {
    val context = LocalContext.current
    val app = context.applicationContext as AuraApplication
    LaunchedEffect(Unit) {
        SpatialModelDownloadManager.syncFromDisk(context)
    }
    val states = SpatialModelDownloadManager.states
    val stateSignature = states.entries.joinToString { "${it.key}:${it.value::class.simpleName}" }
    LaunchedEffect(stateSignature) {
        app.auraSing.refreshReadiness()
    }

    val runtimeResource = remember { SpatialModelStore.runtimeResource() }
    val runtimeAbi = remember { SpatialModelStore.runtimeAbi() }
    val qnnResource = remember { AuraSingQualcommNpu.runtimeResource() }
    val isEliteSoc = remember { AuraSingQualcommNpu.isSnapdragon8EliteOrAbove() }
    val isQtiSoc = remember { AuraSingQualcommNpu.isQualcommSoC() }
    val socLabel = remember { AuraSingQualcommNpu.socModelName().ifBlank { "Snapdragon" } }
    var pendingDelete by remember { mutableStateOf<Pair<String, SpatialModelResource>?>(null) }

    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
                Text(
                    tr(
                        "aura_sing_downloads_intro",
                        "Aura Sing 复用空间封面的 ONNX Runtime 原生推理引擎（无需重复下载）。推荐选择轻量高速的 KUIELab / MDX-Net 系列模型以获得极快分离速度。",
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        item(key = "aura_sing_shared_onnxruntime") {
            if (runtimeResource != null && runtimeAbi != null) {
                val title = tr("aura_sing_runtime_shared_title", "ONNX Runtime 推理引擎（%s · 与空间封面共用）")
                    .format(runtimeAbi.abi)
                SpatialResourceCard(
                    title = title,
                    summary = tr(
                        "aura_sing_runtime_shared_summary",
                        "与空间封面共用同一份 1.31.0 原生运行库，若此前已下载则自动就绪。",
                    ),
                    sizeText = formatSpatialBytes(runtimeResource.sizeBytes),
                    state = states[runtimeResource.id] ?: SpatialModelDownloadState.Idle,
                    downloadLabel = tr("spatial_download_runtime", "下载运行库"),
                    onDownload = { SpatialModelDownloadManager.start(context, runtimeResource) },
                    onCancel = { SpatialModelDownloadManager.cancel(runtimeResource.id) },
                    onDelete = { pendingDelete = title to runtimeResource },
                )
            } else {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
                    Text(
                        tr("spatial_runtime_unsupported_abi", "当前设备架构不支持（仅适配 arm64-v8a）。"),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
        if (qnnResource != null) {
            item(key = AuraSingQualcommNpu.QNN_RESOURCE_ID) {
                val qnnTitle = tr("aura_sing_qnn_runtime_title", "Qualcomm QNN NPU 运行库（骁龙 8 Elite+）")
                val qnnSummary = when {
                    isEliteSoc -> tr(
                        "aura_sing_qnn_runtime_summary_elite",
                        "已检测到骁龙旗舰芯片（%s）。按需下载后可启用 Hexagon HTP NPU（v79/v81）与 Adreno GPU 硬件加速（约 53 MB）。",
                    ).format(socLabel)
                    isQtiSoc -> tr(
                        "aura_sing_qnn_runtime_summary_qcom",
                        "当前检测到高通平台（%s）。按需下载以尝试启用 QNN HTP / Adreno GPU 加速（主要针对 8 Elite 及以上优化）。",
                    ).format(socLabel)
                    else -> tr(
                        "aura_sing_qnn_runtime_summary_other",
                        "高通骁龙 8 Elite 及以上专属 Hexagon NPU (v79/v81) 与 Adreno GPU 加速运行库（按需下载，约 53 MB）。",
                    )
                }
                SpatialResourceCard(
                    title = qnnTitle,
                    summary = qnnSummary,
                    sizeText = formatSpatialBytes(qnnResource.sizeBytes),
                    state = states[qnnResource.id] ?: SpatialModelDownloadState.Idle,
                    downloadLabel = tr("aura_sing_download_qnn_runtime", "下载 NPU 运行库"),
                    onDownload = { SpatialModelDownloadManager.start(context, qnnResource) },
                    onCancel = { SpatialModelDownloadManager.cancel(qnnResource.id) },
                    onDelete = { pendingDelete = qnnTitle to qnnResource },
                )
            }
        }
        items(AuraSingModel.entries.toList(), key = { it.id }) { model ->
            val resource = model.resource
            val displayName = tr(model.labelKey, model.fallbackLabel)
            SpatialResourceCard(
                title = displayName,
                summary = tr(model.summaryKey, model.fallbackSummary),
                sizeText = formatSpatialBytes(resource.sizeBytes),
                state = states[resource.id] ?: SpatialModelDownloadState.Idle,
                downloadLabel = tr("depth_download_model", "下载模型"),
                onDownload = { SpatialModelDownloadManager.start(context, resource) },
                onCancel = { SpatialModelDownloadManager.cancel(resource.id) },
                onDelete = { pendingDelete = displayName to resource },
            )
        }
    }

    pendingDelete?.let { (name, resource) ->
        WindowDialog(
            show = true,
            title = tr("confirm_delete", "确认删除"),
            summary = tr("aura_sing_delete_model_confirm", "确定删除 %s 吗？删除后需要重新下载才能继续使用 Aura Sing。")
                .format(name),
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { pendingDelete = null },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onClick = { pendingDelete = null }, modifier = Modifier.weight(1f)) {
                    Text(tr("cancel", "取消"))
                }
                GlassDialogButton(
                    onClick = {
                        SpatialModelDownloadManager.delete(context, resource)
                        app.auraSing.refreshReadiness()
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
}

@Composable
fun AuraSingCachePage(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = (context.applicationContext as AuraApplication).auraSing
    var refreshTick by remember { mutableIntStateOf(0) }
    val entries by produceState(emptyList<AuraSingCacheEntry>(), refreshTick) {
        value = withContext(Dispatchers.IO) { engine.cache.listEntries() }
    }
    var selectedEntry by remember { mutableStateOf<AuraSingCacheEntry?>(null) }
    var pendingDeleteEntry by remember { mutableStateOf<AuraSingCacheEntry?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    // HyperChanger-style multi-select export dialog state
    var exportDialogInitialEntryIds by remember { mutableStateOf<Set<String>?>(null) }
    var pendingExportTargets by remember { mutableStateOf<List<AuraSingExportTarget>>(emptyList()) }
    var exporting by remember { mutableStateOf(false) }

    fun handleExportUri(uri: android.net.Uri?) {
        val targets = pendingExportTargets
        pendingExportTargets = emptyList()
        if (uri == null || targets.isEmpty()) return
        exporting = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        AuraSingExport.exportTargetsToStream(engine.cache, targets, out)
                    } ?: error("无法打开输出文件")
                }
            }
            exporting = false
            result.onSuccess {
                Toast.makeText(
                    context,
                    tr("aura_sing_export_success", "已成功导出 %d 个音轨文件").format(targets.size),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    tr("aura_sing_export_failed", "导出失败：%s").format(err.message ?: ""),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val wavExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav"),
        onResult = ::handleExportUri,
    )
    val zipExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = ::handleExportUri,
    )

    val totalBytes = remember(entries) { entries.sumOf { it.totalBytes } }

    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        tr("aura_sing_cache_overview", "已缓存 %d 首分离音轨 · 共占用 %s")
                            .format(entries.size, formatSpatialBytes(totalBytes)),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (entries.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassDialogButton(
                                onClick = { confirmClearAll = true },
                                enabled = !exporting,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(tr("aura_sing_clear_all_cache", "清空全部缓存"))
                            }
                            GlassDialogButton(
                                onClick = { exportDialogInitialEntryIds = entries.map { it.id }.toSet() },
                                enabled = !exporting,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColorsPrimary(),
                            ) {
                                Text(
                                    if (exporting) tr("aura_sing_exporting", "正在导出…")
                                    else tr("aura_sing_export_combo", "自由组合导出"),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(24.dp)) {
                    Text(
                        tr(
                            "aura_sing_cache_empty",
                            "暂无伴奏与人声分离缓存。开启 Aura Sing 并在正在播放界面启用麦克风后，分离完成的人声与伴奏音轨会自动保存在本地。",
                        ),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            items(entries, key = { it.id }) { entry ->
                val modelLabel = AuraSingModel.byId(entry.modelId).let { tr(it.labelKey, it.fallbackLabel) }
                SeamlessCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.5.dp))
                        .clickable { selectedEntry = entry },
                    cornerRadius = 22.5.dp,
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            entry.title.ifBlank { entry.songKey },
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val artistAlbum = listOf(entry.artist, entry.album).filter { it.isNotBlank() }.joinToString(" · ")
                        if (artistAlbum.isNotBlank()) {
                            Text(
                                artistAlbum,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "$modelLabel · ${formatDurationMs(entry.durationMs)} · ${formatSpatialBytes(entry.totalBytes)}",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    selectedEntry?.let { entry ->
        val modelLabel = AuraSingModel.byId(entry.modelId).let { tr(it.labelKey, it.fallbackLabel) }
        WindowDialog(
            show = true,
            title = entry.title.ifBlank { tr("aura_sing_cache_item_title", "分离音轨详情") },
            summary = listOfNotNull(
                entry.artist.takeIf { it.isNotBlank() },
                modelLabel,
            ).joinToString(" · "),
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { selectedEntry = null },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr(
                        "aura_sing_cache_entry_stems",
                        "人声音轨：%s · 伴奏音轨：%s\n采样率：44.1 kHz 立体声",
                    ).format(
                        formatSpatialBytes(entry.vocalsBytes),
                        formatSpatialBytes(entry.instrumentalBytes),
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                GlassDialogButton(
                    onClick = {
                        exportDialogInitialEntryIds = setOf(entry.id)
                        selectedEntry = null
                    },
                    enabled = !exporting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(tr("aura_sing_export_entry_action", "导出分离音频"))
                }
                GlassDialogButton(
                    onClick = {
                        pendingDeleteEntry = entry
                        selectedEntry = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr("delete", "删除"))
                }
                GlassDialogButton(
                    onClick = { selectedEntry = null },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr("cancel", "取消"))
                }
            }
        }
    }

    pendingDeleteEntry?.let { entry ->
        WindowDialog(
            show = true,
            title = tr("confirm_delete", "确认删除"),
            summary = tr(
                "aura_sing_delete_cache_confirm",
                "确定删除「%s」的伴奏与人声分离缓存（%s）吗？",
            ).format(entry.title.ifBlank { entry.songKey }, formatSpatialBytes(entry.totalBytes)),
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { pendingDeleteEntry = null },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onClick = { pendingDeleteEntry = null }, modifier = Modifier.weight(1f)) {
                    Text(tr("cancel", "取消"))
                }
                GlassDialogButton(
                    onClick = {
                        engine.cache.deleteEntry(entry.id)
                        engine.onCacheEntryDeleted(entry.id)
                        pendingDeleteEntry = null
                        refreshTick++
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(tr("delete", "删除"))
                }
            }
        }
    }

    if (confirmClearAll) {
        WindowDialog(
            show = true,
            title = tr("confirm_delete", "确认删除"),
            summary = tr(
                "aura_sing_clear_all_confirm",
                "确定清空全部 %d 首歌曲的伴奏与人声分离缓存（%s）吗？",
            ).format(entries.size, formatSpatialBytes(totalBytes)),
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { confirmClearAll = false },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onClick = { confirmClearAll = false }, modifier = Modifier.weight(1f)) {
                    Text(tr("cancel", "取消"))
                }
                GlassDialogButton(
                    onClick = {
                        engine.cache.clearAll()
                        engine.onCacheCleared()
                        confirmClearAll = false
                        refreshTick++
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(tr("delete", "删除"))
                }
            }
        }
    }

    exportDialogInitialEntryIds?.let { initialIds ->
        SingMultiSelectExportDialog(
            entries = entries,
            initialSelectedEntryIds = initialIds,
            busy = exporting,
            onDismiss = { if (!exporting) exportDialogInitialEntryIds = null },
            onConfirmExport = { selectedTargets ->
                exportDialogInitialEntryIds = null
                pendingExportTargets = selectedTargets
                val fileName = AuraSingExport.suggestedFileName(selectedTargets)
                if (selectedTargets.size == 1) {
                    wavExportLauncher.launch(fileName)
                } else {
                    zipExportLauncher.launch(fileName)
                }
            },
        )
    }
}

/**
 * Multi-select export dialog inspired by HyperChanger's checkbox selection dialog:
 * lets users freely combine stems (Vocals / Instrumental) and cached songs, exporting either a single
 * lossless WAV or a multi-track ZIP archive.
 */
@Composable
private fun SingMultiSelectExportDialog(
    entries: List<AuraSingCacheEntry>,
    initialSelectedEntryIds: Set<String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirmExport: (List<AuraSingExportTarget>) -> Unit,
) {
    var selectedStems by remember(initialSelectedEntryIds) {
        mutableStateOf(setOf(AuraSingExportStem.VOCALS, AuraSingExportStem.INSTRUMENTAL))
    }
    var selectedEntryIds by remember(initialSelectedEntryIds, entries) {
        val valid = initialSelectedEntryIds.filter { id -> entries.any { it.id == id } }.toSet()
        mutableStateOf(valid)
    }

    val selectedTargets = remember(entries, selectedEntryIds, selectedStems) {
        AuraSingExport.buildTargets(entries, selectedEntryIds, selectedStems)
    }
    val estimatedBytes = remember(selectedTargets) {
        selectedTargets.sumOf { target ->
            val raw = when (target.stem) {
                AuraSingExportStem.VOCALS -> target.entry.vocalsBytes
                AuraSingExportStem.INSTRUMENTAL -> target.entry.instrumentalBytes
            }
            raw + AuraSingExport.WAV_HEADER_SIZE
        }
    }

    WindowDialog(
        show = true,
        onDismissRequest = { if (!busy) onDismiss() },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                tr("aura_sing_export_dialog_title", "选择导出的分离音频"),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
            )
            Text(
                when {
                    selectedTargets.isEmpty() ->
                        tr("aura_sing_export_none_selected", "请至少选择一种音轨与一首歌曲")
                    selectedTargets.size == 1 ->
                        tr("aura_sing_export_single_summary", "将导出 1 个 WAV 无损音频文件（%s）")
                            .format(formatSpatialBytes(estimatedBytes))
                    else ->
                        tr("aura_sing_export_zip_summary", "已选 %d 个音轨文件（%s），将打包导出为 ZIP")
                            .format(selectedTargets.size, formatSpatialBytes(estimatedBytes))
                },
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Spacer(Modifier.height(2.dp))
            Text(
                tr("aura_sing_export_stems_section", "音轨组合"),
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.primary,
            )
            SingMultiSelectCheckboxRow(
                title = tr("aura_sing_export_stem_vocal", "人声音轨 (Vocal · WAV)"),
                subtitle = null,
                checked = AuraSingExportStem.VOCALS in selectedStems,
                enabled = !busy,
            ) {
                selectedStems = if (AuraSingExportStem.VOCALS in selectedStems) {
                    selectedStems - AuraSingExportStem.VOCALS
                } else {
                    selectedStems + AuraSingExportStem.VOCALS
                }
            }
            SingMultiSelectCheckboxRow(
                title = tr("aura_sing_export_stem_inst", "伴奏音轨 (Instrumental · WAV)"),
                subtitle = null,
                checked = AuraSingExportStem.INSTRUMENTAL in selectedStems,
                enabled = !busy,
            ) {
                selectedStems = if (AuraSingExportStem.INSTRUMENTAL in selectedStems) {
                    selectedStems - AuraSingExportStem.INSTRUMENTAL
                } else {
                    selectedStems + AuraSingExportStem.INSTRUMENTAL
                }
            }

            if (entries.size > 1) {
                Spacer(Modifier.height(2.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        tr("aura_sing_export_songs_section", "缓存歌曲（%d/%d）")
                            .format(selectedEntryIds.size, entries.size),
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    val allSelected = selectedEntryIds.size == entries.size
                    Text(
                        if (allSelected) tr("aura_sing_deselect_all", "取消全选")
                        else tr("aura_sing_select_all", "全选"),
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !busy) {
                                selectedEntryIds = if (allSelected) emptySet() else entries.map { it.id }.toSet()
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 210.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    entries.forEach { entry ->
                        val checked = entry.id in selectedEntryIds
                        val stemBytes = (if (AuraSingExportStem.VOCALS in selectedStems) entry.vocalsBytes else 0L) +
                            (if (AuraSingExportStem.INSTRUMENTAL in selectedStems) entry.instrumentalBytes else 0L)
                        SingMultiSelectCheckboxRow(
                            title = entry.title.ifBlank { entry.songKey },
                            subtitle = listOfNotNull(
                                entry.artist.takeIf { it.isNotBlank() },
                                formatDurationMs(entry.durationMs),
                                stemBytes.takeIf { it > 0L }?.let(::formatSpatialBytes),
                            ).joinToString(" · "),
                            checked = checked,
                            enabled = !busy,
                        ) {
                            selectedEntryIds = if (checked) {
                                selectedEntryIds - entry.id
                            } else {
                                selectedEntryIds + entry.id
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) {
                    Text(tr("cancel", "取消"))
                }
                GlassDialogButton(
                    onClick = { onConfirmExport(selectedTargets) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy && selectedTargets.isNotEmpty(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(
                        if (selectedTargets.size <= 1) {
                            tr("aura_sing_export_wav_btn", "导出 WAV")
                        } else {
                            tr("aura_sing_export_zip_btn", "打包导出 (%d)").format(selectedTargets.size)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SingMultiSelectCheckboxRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuraCheckbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { if (enabled) onToggle() },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MiuixTheme.textStyles.body1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatDurationMs(durationMs: Long): String {
    val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%02d:%02d".format(min, sec)
}
