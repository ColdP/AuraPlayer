// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.lyrics.rememberSpatialGyroTilt
import btm.m.auraplayer.lyrics.scaleSpatialParallaxTilt
import btm.m.auraplayer.sing.AuraSingModel
import btm.m.auraplayer.sing.AuraSingQualcommNpu
import btm.m.auraplayer.spatialcover.ImportFailureReason
import btm.m.auraplayer.spatialcover.SpatialCoverAssets
import btm.m.auraplayer.spatialcover.SpatialCoverCacheEntry
import btm.m.auraplayer.spatialcover.SpatialCoverManager
import btm.m.auraplayer.spatialcover.SpatialCoverRenderer
import btm.m.auraplayer.spatialcover.SpatialDepthModel
import btm.m.auraplayer.spatialcover.SpatialDomesticMirror
import btm.m.auraplayer.spatialcover.SpatialMattingModel
import btm.m.auraplayer.spatialcover.SpatialModelDownloadManager
import btm.m.auraplayer.spatialcover.SpatialModelDownloadState
import btm.m.auraplayer.spatialcover.SpatialModelImportResult
import btm.m.auraplayer.spatialcover.SpatialModelImporter
import btm.m.auraplayer.spatialcover.SpatialModelResource
import btm.m.auraplayer.spatialcover.SpatialModelStore
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

internal fun formatSpatialBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

internal fun resolveSpatialResourceDisplayName(resource: SpatialModelResource): String {
    if (resource.id == SpatialModelStore.RUNTIME_RESOURCE_ID) {
        val abi = SpatialModelStore.runtimeAbi()?.abi ?: "arm64-v8a"
        return tr("spatial_runtime_title", "ONNX Runtime 推理引擎（%s）").format(abi)
    }
    if (resource.id == AuraSingQualcommNpu.QNN_RESOURCE_ID) {
        return tr("aura_sing_qnn_runtime_title", "Qualcomm QNN NPU 运行库（骁龙 8 Elite+）")
    }
    SpatialDepthModel.entries.firstOrNull { it.id == resource.id }?.let { return it.displayName }
    SpatialMattingModel.downloadableEntries.firstOrNull { it.id == resource.id }?.let { return it.displayName }
    AuraSingModel.entries.firstOrNull { it.resource.id == resource.id }?.let {
        return tr(it.labelKey, it.fallbackLabel)
    }
    return resource.fileName
}

internal fun formatImportFailureMessage(failure: SpatialModelImportResult.Failure): String = when (failure.reason) {
    ImportFailureReason.CANNOT_OPEN ->
        tr("model_import_fail_open", "无法读取所选文件「%s」，请确认文件访问权限后重试。").format(failure.fileName)
    ImportFailureReason.EMPTY_FILE ->
        tr("model_import_fail_empty", "所选文件「%s」为空文件，无法导入。").format(failure.fileName)
    ImportFailureReason.ARCHIVE_CORRUPTED_OR_INCOMPLETE -> {
        val candidateName = failure.candidateResource?.let { resolveSpatialResourceDisplayName(it) }
        if (candidateName != null) {
            tr(
                "model_import_fail_archive_candidate",
                "文件「%s」匹配到「%s」，但压缩包损坏、缺少必要组件或校验失败，无法导入。",
            ).format(failure.fileName, candidateName)
        } else {
            tr(
                "model_import_fail_archive",
                "压缩包「%s」损坏或未包含完整匹配的模型/运行库文件，无法导入。",
            ).format(failure.fileName)
        }
    }
    ImportFailureReason.WRITE_FAILED -> {
        val reasonText = failure.detail.ifBlank { tr("unknown_error", "未知错误") }
        tr("model_import_fail_write", "保存模型文件「%s」失败：%s").format(failure.fileName, reasonText)
    }
    ImportFailureReason.NOT_A_KNOWN_MODEL -> {
        val sizeText = formatSpatialBytes(failure.fileSize)
        val candidateName = failure.candidateResource?.let { resolveSpatialResourceDisplayName(it) }
        if (candidateName != null) {
            tr(
                "model_import_fail_mismatch_candidate",
                "文件「%s」（%s）与现有模型「%s」的特征/校验值不匹配，可能文件已损坏、不完整或并非当前支持的版本。",
            ).format(failure.fileName, sizeText, candidateName)
        } else {
            tr(
                "model_import_fail_unknown",
                "文件「%s」（%s）不是现有的 Aura Sing 或空间封面模型/运行库，未检测到匹配的特征信息，导入失败。",
            ).format(failure.fileName, sizeText)
        }
    }
}

@Composable
internal fun ModelDownloadToolsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fetchingDomesticUrl by remember { mutableStateOf(false) }
    var importingModel by remember { mutableStateOf(false) }
    var domesticError by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var importFailure by remember { mutableStateOf<SpatialModelImportResult.Failure?>(null) }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || importingModel) return@rememberLauncherForActivityResult
        importingModel = true
        scope.launch {
            val result = SpatialModelImporter.importFromUri(context, uri)
            importingModel = false
            when (result) {
                is SpatialModelImportResult.Success -> {
                    (context.applicationContext as? AuraApplication)?.auraSing?.refreshReadiness()
                    val name = resolveSpatialResourceDisplayName(result.resource)
                    Toast.makeText(
                        context,
                        tr("model_import_success", "已成功导入：%s").format(name),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                is SpatialModelImportResult.Failure -> {
                    importFailure = result
                }
            }
        }
    }

    EffectsCard {
        Preference(
            title = tr("model_domestic_download", "国内用户下载"),
            summary = if (fetchingDomesticUrl) {
                tr("model_domestic_fetching", "正在获取国内下载链接…")
            } else {
                tr("model_domestic_download_summary", "获取国内网盘下载链接并跳转浏览器")
            },
            enabled = !fetchingDomesticUrl,
        ) {
            fetchingDomesticUrl = true
            scope.launch {
                val result = runCatching { SpatialDomesticMirror.fetchDownloadUrl() }
                fetchingDomesticUrl = false
                result.onSuccess { url ->
                    val opened = runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.isSuccess
                    if (!opened) {
                        domesticError = tr("model_domestic_open_failed", "未能打开系统浏览器或对应应用，请复制以下链接手动访问：\n%s").format(url) to url
                    }
                }.onFailure { err ->
                    val msg = err.message?.takeIf { it.isNotBlank() } ?: tr("unknown_error", "未知错误")
                    domesticError = tr("model_domestic_fetch_failed", "获取国内下载链接失败：%s").format(msg) to null
                }
            }
        }
        Preference(
            title = tr("model_import_local", "导入模型"),
            summary = if (importingModel) {
                tr("model_importing", "正在校验并导入模型…")
            } else {
                tr("model_import_local_summary", "从本地选择已下载的模型或运行库文件，自动识别特征并校验导入")
            },
            enabled = !importingModel,
        ) {
            modelPicker.launch(arrayOf("*/*"))
        }
    }

    domesticError?.let { (message, copyableUrl) ->
        WindowDialog(
            show = true,
            title = tr("model_domestic_download", "国内用户下载"),
            summary = message,
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { domesticError = null },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!copyableUrl.isNullOrBlank()) {
                    GlassDialogButton(
                        onClick = {
                            copyToClipboard(context, "download_url", copyableUrl)
                            Toast.makeText(context, tr("copied", "已复制"), Toast.LENGTH_SHORT).show()
                            domesticError = null
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(tr("copy_link", "复制链接"))
                    }
                }
                GlassDialogButton(
                    onClick = { domesticError = null },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(tr("confirm", "确定"))
                }
            }
        }
    }

    importFailure?.let { failure ->
        WindowDialog(
            show = true,
            title = tr("model_import_failed_title", "导入模型失败"),
            summary = formatImportFailureMessage(failure),
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { importFailure = null },
        ) {
            GlassDialogButton(
                onClick = { importFailure = null },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(tr("confirm", "确定"))
            }
        }
    }
}

@Composable
fun SpatialCoverDownloadsPage(padding: PaddingValues) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        SpatialModelDownloadManager.syncFromDisk(context)
    }
    val states = SpatialModelDownloadManager.states
    val runtimeResource = remember { SpatialModelStore.runtimeResource() }
    val runtimeAbi = remember { SpatialModelStore.runtimeAbi() }
    val qnnResource = remember { btm.m.auraplayer.sing.AuraSingQualcommNpu.runtimeResource() }
    val isEliteSoc = remember { btm.m.auraplayer.sing.AuraSingQualcommNpu.isSnapdragon8EliteOrAbove() }
    val isQtiSoc = remember { btm.m.auraplayer.sing.AuraSingQualcommNpu.isQualcommSoC() }
    val socLabel = remember { btm.m.auraplayer.sing.AuraSingQualcommNpu.socModelName().ifBlank { "Snapdragon" } }
    var pendingDelete by remember { mutableStateOf<Pair<String, SpatialModelResource>?>(null) }

    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
                Text(
                    tr(
                        "spatial_downloads_intro",
                        "ONNX Runtime 运行库与空间渲染、抠图模型均按需下载，不占用安装包体积。下载完成并通过 SHA-256 校验后即可在本地离线计算空间封面。",
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        item(key = "spatial_model_download_tools") {
            ModelDownloadToolsSection()
        }
        item(key = "onnxruntime_runtime") {
            if (runtimeResource != null && runtimeAbi != null) {
                val title = tr("spatial_runtime_title", "ONNX Runtime 推理引擎（%s）").format(runtimeAbi.abi)
                SpatialResourceCard(
                    title = title,
                    summary = tr(
                        "spatial_runtime_summary",
                        "空间深度计算与抠图必需的本地原生运行库（1.31.0，仅 arm64-v8a），下载后自动校验解压。",
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
            item(key = btm.m.auraplayer.sing.AuraSingQualcommNpu.QNN_RESOURCE_ID) {
                val qnnTitle = tr("aura_sing_qnn_runtime_title", "Qualcomm QNN NPU 运行库（骁龙 8 Elite+）")
                val qnnSummary = when {
                    isEliteSoc -> tr(
                        "spatial_qnn_runtime_summary_elite",
                        "已检测到骁龙旗舰芯片（%s）。与 Aura Sing 共用，按需下载后空间封面也将启用 Hexagon HTP NPU 与 Adreno GPU 硬件加速（约 53 MB）。",
                    ).format(socLabel)
                    isQtiSoc -> tr(
                        "spatial_qnn_runtime_summary_qcom",
                        "当前检测到高通平台（%s）。与 Aura Sing 共用，按需下载以尝试启用 QNN HTP / Adreno GPU 加速。",
                    ).format(socLabel)
                    else -> tr(
                        "spatial_qnn_runtime_summary_other",
                        "与 Aura Sing 共用，高通骁龙 8 Elite 及以上专属 Hexagon NPU (v79/v81) & Adreno GPU 加速运行库（约 53 MB）。",
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
        items(SpatialDepthModel.entries.toList(), key = { it.id }) { model ->
            val resource = model.resource
            SpatialResourceCard(
                title = model.displayName,
                summary = when (model) {
                    SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL ->
                        tr("depth_model_da2_summary", "细节与边缘更稳，适合人物、插画与复杂封面。")
                    SpatialDepthModel.MIDAS_MOBILE ->
                        tr("depth_model_midas_summary", "体积适中、推理较快，适合快速生成。")
                    SpatialDepthModel.DEPTH_ANYTHING_V3_BASE ->
                        tr("depth_model_da3_summary", "景深层次最佳，模型体积与生成耗时明显更高，建议在 Wi-Fi 下下载。")
                },
                sizeText = formatSpatialBytes(resource.sizeBytes),
                state = states[resource.id] ?: SpatialModelDownloadState.Idle,
                downloadLabel = tr("depth_download_model", "下载模型"),
                onDownload = { SpatialModelDownloadManager.start(context, resource) },
                onCancel = { SpatialModelDownloadManager.cancel(resource.id) },
                onDelete = { pendingDelete = model.displayName to resource },
            )
        }
        items(SpatialMattingModel.downloadableEntries, key = { it.id }) { matting ->
            val resource = matting.resource ?: return@items
            SpatialResourceCard(
                title = matting.displayName,
                summary = when (matting) {
                    SpatialMattingModel.U2NETP ->
                        tr("matting_u2netp_summary", "轻量抠图模型，适合快速分离封面主体与背景。")
                    SpatialMattingModel.ISNET_INT8 ->
                        tr("matting_isnet_summary", "1024 分辨率，兼顾主体边缘精细度与生成速度。")
                    SpatialMattingModel.BIREFNET_LITE_INT8 ->
                        tr("matting_birefnet_summary", "适合发丝与复杂轮廓，内存占用与耗时较高。")
                    SpatialMattingModel.NONE -> ""
                },
                sizeText = formatSpatialBytes(resource.sizeBytes),
                state = states[resource.id] ?: SpatialModelDownloadState.Idle,
                downloadLabel = tr("depth_download_resource", "下载"),
                onDownload = { SpatialModelDownloadManager.start(context, resource) },
                onCancel = { SpatialModelDownloadManager.cancel(resource.id) },
                onDelete = { pendingDelete = matting.displayName to resource },
            )
        }
    }

    pendingDelete?.let { (name, resource) ->
        WindowDialog(
            show = true,
            title = tr("confirm_delete", "确认删除"),
            summary = tr("spatial_delete_model_confirm", "确定删除 %s 吗？删除后需要重新下载才能继续生成。").format(name),
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
internal fun SpatialResourceCard(
    title: String,
    summary: String,
    sizeText: String,
    state: SpatialModelDownloadState,
    downloadLabel: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MiuixTheme.textStyles.title4)
                    Text(
                        summary,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    sizeText,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            when (state) {
                is SpatialModelDownloadState.Downloading -> {
                    val fraction = if (state.total > 0) (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                    Box(
                        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(fraction).fillMaxHeight()
                                .background(MiuixTheme.colorScheme.primary),
                        )
                    }
                    Text(
                        tr("spatial_downloading", "正在下载 %s / %s · %s/s").format(
                            formatSpatialBytes(state.downloaded),
                            formatSpatialBytes(state.total),
                            formatSpatialBytes(state.speed),
                        ),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Button(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 50.dp,
                    ) {
                        Text(tr("cancel_download", "取消下载"), fontSize = 15.sp)
                    }
                }
                SpatialModelDownloadState.Done -> {
                    Text(
                        tr("spatial_downloaded", "已下载并通过校验"),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Button(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 50.dp,
                    ) {
                        Text(tr("delete", "删除"), fontSize = 15.sp)
                    }
                }
                is SpatialModelDownloadState.Failed -> {
                    Text(
                        tr("spatial_download_failed", "下载失败：%s").format(state.message),
                        style = MiuixTheme.textStyles.body2,
                        color = Color(0xFFE05353),
                    )
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 50.dp,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(tr("retry_download", "重试下载"), fontSize = 15.sp)
                    }
                }
                SpatialModelDownloadState.Idle -> {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 50.dp,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(downloadLabel, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SpatialCoverCachePage(padding: PaddingValues, open: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableIntStateOf(0) }
    val entries by produceState(emptyList<SpatialCoverCacheEntry>(), refreshTick) {
        value = SpatialCoverManager.listCacheEntries(context)
    }
    var selectedEntry by remember { mutableStateOf<SpatialCoverCacheEntry?>(null) }
    var pendingDeleteEntry by remember { mutableStateOf<SpatialCoverCacheEntry?>(null) }

    val totalBytes = remember(entries) { entries.sumOf { it.sizeBytes } }

    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
                Text(
                    tr("spatial_cache_summary", "已缓存 %d 个空间封面 · 共占用 %s")
                        .format(entries.size, formatSpatialBytes(totalBytes)),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (entries.isEmpty()) {
            item {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(24.dp)) {
                    Text(
                        tr("spatial_cache_empty", "暂无空间封面缓存，开启空间封面效果并播放歌曲后会自动生成并缓存。"),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            items(entries, key = { it.key }) { entry ->
                val thumb by produceState<ImageBitmap?>(null, entry.sourcePath) {
                    value = withContext(Dispatchers.IO) {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                        BitmapFactory.decodeFile(entry.sourcePath, opts)?.asImageBitmap()
                    }
                }
                SeamlessCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.5.dp))
                        .clickable { selectedEntry = entry },
                    cornerRadius = 22.5.dp,
                    insideMargin = PaddingValues(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MiuixTheme.colorScheme.surfaceVariant)
                                .clickable { open("spatial_cover_preview:${entry.key}") },
                        ) {
                            thumb?.let {
                                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                entry.album.ifBlank { entry.title }.ifBlank { tr("spatial_cover_item_untitled", "歌曲封面") },
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${entry.depthModel.displayName} · ${entry.mattingModel.displayName} · ${formatSpatialBytes(entry.sizeBytes)}",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    selectedEntry?.let { entry ->
        val dialogThumb by produceState<ImageBitmap?>(null, entry.sourcePath) {
            value = withContext(Dispatchers.IO) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(entry.sourcePath, opts)?.asImageBitmap()
            }
        }
        WindowDialog(
            show = true,
            title = null,
            summary = null,
            onDismissRequest = { selectedEntry = null },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MiuixTheme.colorScheme.surfaceVariant),
                ) {
                    dialogThumb?.let {
                        Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Text(
                    "${entry.depthModel.displayName} · ${entry.mattingModel.displayName} · ${formatSpatialBytes(entry.sizeBytes)}",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                GlassDialogButton(
                    onClick = {
                        val key = entry.key
                        selectedEntry = null
                        open("spatial_cover_preview:$key")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(tr("spatial_preview_effect", "预览空间效果"))
                }
                GlassDialogButton(
                    onClick = {
                        pendingDeleteEntry = entry
                        selectedEntry = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr("spatial_delete_cache", "删除空间缓存"))
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
                "spatial_delete_cache_confirm",
                "确定删除该封面的空间数据缓存（%s）吗？下次启用空间封面效果时将重新计算。",
            ).format(formatSpatialBytes(entry.sizeBytes)),
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
                        val key = entry.key
                        pendingDeleteEntry = null
                        scope.launch {
                            SpatialCoverManager.deleteCacheEntry(context, key)
                            refreshTick++
                        }
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
fun SpatialCoverPreviewPage(cacheKey: String, back: () -> Unit) {
    val context = LocalContext.current
    val nowPlayingConfig by (context.applicationContext as AuraApplication).nowPlaying.state.collectAsState()
    var assets by remember { mutableStateOf<SpatialCoverAssets?>(null) }
    var failed by remember { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(true) }
    val gyroTilt by rememberSpatialGyroTilt(enabled = true)
    var dragTilt by remember(cacheKey) { mutableStateOf(Offset.Zero) }
    val backdrop = rememberLayerBackdrop()

    LaunchedEffect(cacheKey) {
        val loaded = SpatialCoverManager.loadCacheEntryAssets(context, cacheKey)
        assets = loaded
        failed = loaded == null
    }
    LaunchedEffect(Unit) {
        delay(3600L)
        hintVisible = false
    }

    val currentAssets = assets
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .pointerInput(cacheKey) {
                    detectDragGestures { _, dragAmount ->
                        val next = Offset(
                            dragTilt.x + dragAmount.x / size.width.coerceAtLeast(1) * 2.4f,
                            dragTilt.y + dragAmount.y / size.height.coerceAtLeast(1) * 2.4f,
                        )
                        dragTilt = Offset(next.x.coerceIn(-1f, 1f), next.y.coerceIn(-1f, 1f))
                    }
                }
                .pointerInput(cacheKey) {
                    detectTapGestures(onDoubleTap = { dragTilt = Offset.Zero })
                },
        ) {
            if (currentAssets != null) {
                Image(
                    bitmap = currentAssets.source.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(60.dp),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.28f)),
                )
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val coverSide = minOf(maxWidth - 44.dp, maxHeight - 160.dp, 380.dp).coerceAtLeast(140.dp)
                    Box(
                        modifier = Modifier
                            .size(coverSide)
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color.Black.copy(alpha = 0.2f)),
                    ) {
                        SpatialCoverRenderer(
                            assets = currentAssets,
                            tiltProvider = {
                                val scaledGyro = scaleSpatialParallaxTilt(gyroTilt, nowPlayingConfig.spatialCoverSensitivity)
                                Offset(
                                    (scaledGyro.x + dragTilt.x).coerceIn(-1.2f, 1.2f),
                                    (scaledGyro.y + dragTilt.y).coerceIn(-1.2f, 1.2f),
                                )
                            },
                            strength = 1.0f,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            } else if (failed) {
                Text(
                    tr("spatial_cache_load_failed", "该空间封面缓存已损坏或已被删除"),
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                    style = MiuixTheme.textStyles.body1,
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 10.dp, start = 14.dp),
        ) {
            SpatialLiquidGlassBackButton(backdrop, back)
        }

        AnimatedVisibility(
            visible = hintVisible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(500)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text(
                    tr("spatial_preview_hint", "转动手机或拖动屏幕体验空间效果 · 双击重置"),
                    color = Color.White.copy(alpha = 0.92f),
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }
    }
}

@Composable
private fun SpatialLiquidGlassBackButton(backdrop: com.kyant.backdrop.Backdrop, onClick: () -> Unit) {
    val surface = MiuixTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    val advancedMaterial = LocalAdvancedMaterialEnabled.current && top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported()
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .then(
                if (advancedMaterial) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { CircleShape },
                        effects = {
                            vibrancy()
                            blur(10.dp.toPx())
                            lens(18.dp.toPx(), 44.dp.toPx(), depthEffect = true, chromaticAberration = true)
                        },
                        highlight = { Highlight.Default.copy(alpha = .62f) },
                        shadow = { Shadow.Default.copy(radius = 6.dp, color = Color.Black, alpha = .3f) },
                        innerShadow = null,
                        onDrawSurface = {
                            drawCircle((if (dark) Color.Black else surface).copy(alpha = .45f))
                        },
                    )
                } else {
                    Modifier.background(if (dark) Color(0xFF242424) else surface)
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            MiuixIcons.Regular.ChevronBackward,
            tr("back", "返回"),
            Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(Color.White),
        )
    }
}
