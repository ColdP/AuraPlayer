// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.auraplayer.BuildConfig
import btm.m.auraplayer.ui.navigation.DampedDragAnimation
import btm.m.auraplayer.ui.navigation.InteractiveHighlight
import btm.m.auraplayer.update.AppUpdateManager
import btm.m.auraplayer.update.UpdateChannel
import btm.m.auraplayer.update.UpdateInfo
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

private sealed interface UpdateState {
    data object Checking : UpdateState
    data object Error : UpdateState
    data class Ready(val info: UpdateInfo, val note: String) : UpdateState
}

@Composable
internal fun UpdateAvailableDialog(
    show: Boolean,
    currentVersion: String,
    latestVersion: String,
    onDismiss: () -> Unit,
    onViewUpdate: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = tr("update_available_title", "检测到新版本"),
        titleTextAlign = TextAlign.Start,
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "${tr("update_current_version", "当前版本")}：$currentVersion",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "${tr("update_latest_version", "最新版本")}：$latestVersion",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) {
                    Text(tr("cancel", "取消"))
                }
                GlassDialogButton(
                    onClick = onViewUpdate,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(tr("update_view_action", "查看更新"))
                }
            }
        }
    }
}

@Composable
fun SoftwareUpdatePage(
    padding: PaddingValues,
    open: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backdrop = LocalToolbarBackdrop.current
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloaded by remember { mutableStateOf(false) }
    var rootAvailable by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    val installPreferences = remember { context.getSharedPreferences("software_update", Context.MODE_PRIVATE) }
    var installMode by remember { mutableStateOf(installPreferences.getString("install_mode", "manual") ?: "manual") }
    var updateChannel by remember { mutableStateOf(AppUpdateManager.getUpdateChannel(context)) }
    var showRedirectDialog by remember { mutableStateOf(false) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    val updateInfo = (state as? UpdateState.Ready)?.info
    val hasUpdate = updateInfo?.versionCode?.let { it > BuildConfig.VERSION_CODE.toLong() } == true
    val useThemePrimary = LocalAuraColorStyle.current.isDynamicEnabled ||
        LocalAppUiStyle.current != AppUiStyle.HYPER ||
        LocalAuraOklabScheme.current != null

    fun checkUpdate(channel: UpdateChannel = updateChannel) {
        checkJob?.cancel()
        state = UpdateState.Checking
        downloaded = false
        checkJob = scope.launch {
            val nextState = withContext(Dispatchers.IO) {
                runCatching {
                    val info = AppUpdateManager.fetchUpdateInfo(channel)
                    val note = AppUpdateManager.fetchReleaseNote(info.releaseNoteUrl)
                    UpdateState.Ready(info, note)
                }.getOrElse { UpdateState.Error }
            }
            if (channel == updateChannel) {
                state = nextState
            }
        }
    }

    LaunchedEffect(Unit) {
        checkUpdate()
    }
    LaunchedEffect(Unit) {
        while (true) {
            rootAvailable = withContext(Dispatchers.IO) { AppUpdateManager.hasRootAccess() }
            delay(10_000)
        }
    }
    LaunchedEffect(rootAvailable) {
        if (rootAvailable) {
            installMode = installPreferences.getString("install_mode", "manual") ?: "manual"
        }
    }
    LaunchedEffect(downloadId) {
        val id = downloadId ?: return@LaunchedEffect
        val manager = context.getSystemService(DownloadManager::class.java) ?: return@LaunchedEffect
        while (true) {
            val cursor = manager.query(DownloadManager.Query().setFilterById(id))
            val active = cursor?.use {
                if (!it.moveToFirst()) return@use false
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val current = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                if (total > 0) downloadProgress = ((current * 100L) / total).toInt().coerceIn(0, 100)
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        downloaded = true
                        false
                    }
                    DownloadManager.STATUS_FAILED -> {
                        downloadId = null
                        false
                    }
                    else -> true
                }
            } ?: false
            if (!active) break
            delay(500)
        }
    }

    fun startDownload() {
        val info = updateInfo ?: return
        if (downloaded) {
            if (installMode == "silent" && rootAvailable) {
                installing = true
                scope.launch {
                    val success = withContext(Dispatchers.IO) {
                        AppUpdateManager.installDownloadedApk(context, downloadId!!)
                    }
                    installing = false
                    if (!success) {
                        Toast.makeText(
                            context,
                            tr("update_silent_install_failed", "静默安装失败，请改用手动安装"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                return
            }
            val uri = context.getSystemService(DownloadManager::class.java)
                ?.getUriForDownloadedFile(downloadId ?: return)
            if (uri != null) {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            }
            return
        }
        if (downloadId != null) {
            context.getSystemService(DownloadManager::class.java)?.remove(downloadId!!)
            downloadId = null
            downloadProgress = 0
            downloaded = false
            return
        }
        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("Aura Player v${info.versionName}")
            setDescription(tr("update_downloading_description", "下载更新"))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "AuraPlayer-v${info.versionName.replace(' ', '-')}.apk",
            )
            setMimeType("application/vnd.android.package-archive")
        }
        downloadId = context.getSystemService(DownloadManager::class.java)?.enqueue(request)
    }

    LazyColumn(
        pageContentModifier(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                GlassDropdownPreference(
                    title = tr("update_channel", "更新通道"),
                    items = listOf(
                        tr("update_channel_github", "GitHub"),
                        tr("update_channel_cloud_disk", "网盘"),
                    ),
                    selectedIndex = if (updateChannel == UpdateChannel.CLOUD_DISK) 1 else 0,
                    backdrop = backdrop,
                ) { index ->
                    val nextChannel = if (index == 1) UpdateChannel.CLOUD_DISK else UpdateChannel.GITHUB
                    if (nextChannel != updateChannel) {
                        updateChannel = nextChannel
                        AppUpdateManager.setUpdateChannel(context, nextChannel)
                        showRedirectDialog = false
                        downloadId?.let { id ->
                            context.getSystemService(DownloadManager::class.java)?.remove(id)
                        }
                        downloadId = null
                        downloadProgress = 0
                        downloaded = false
                        checkUpdate(nextChannel)
                    }
                }
                GlassDropdownPreference(
                    title = tr("update_install_mode", "安装方式"),
                    items = listOf(
                        tr("update_install_manual", "手动安装"),
                        tr("update_install_silent", "静默安装"),
                    ),
                    selectedIndex = if (installMode == "silent" && rootAvailable) 1 else 0,
                    enabled = rootAvailable,
                    backdrop = backdrop,
                ) { index ->
                    installMode = if (index == 1) "silent" else "manual"
                    installPreferences.edit().putString("install_mode", installMode).apply()
                }
            }
        }
        item {
            SeamlessCard(
                Modifier.fillMaxWidth(),
                cornerRadius = 22.5.dp,
                insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 11.4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Aura Player v${if (hasUpdate) updateInfo?.versionName else BuildConfig.VERSION_NAME}",
                            style = MiuixTheme.textStyles.body1.copy(fontSize = MiuixTheme.textStyles.body1.fontSize * 1.2f),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            when (state) {
                                UpdateState.Checking -> tr("update_checking", "检查更新中")
                                UpdateState.Error -> tr("update_check_failed", "检查更新失败")
                                is UpdateState.Ready -> if (hasUpdate) {
                                    "${tr("update_available_title", "检测到新版本")} · ${AppUpdateManager.formatApkSize(updateInfo?.apkSize ?: 0L)}"
                                } else {
                                    tr("update_latest_already", "已是最新版本")
                                }
                            },
                            style = MiuixTheme.textStyles.body2.copy(fontSize = MiuixTheme.textStyles.body2.fontSize * 1.2f),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                when (val current = state) {
                    UpdateState.Checking -> Text(
                        tr("update_checking_dots", "检查更新中......"),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .6f),
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    UpdateState.Error -> Text(
                        tr("update_unavailable", "暂时无法获取更新信息"),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .padding(top = 18.dp)
                            .clickable { checkUpdate() },
                    )
                    is UpdateState.Ready -> {
                        Text(
                            AppUpdateManager.stripMarkdown(current.note).lines().take(6).joinToString("\n"),
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.padding(top = 18.dp, bottom = 0.dp),
                        )
                        Text(
                            tr("update_learn_more", "了解更多..."),
                            color = if (useThemePrimary) MiuixTheme.colorScheme.primary else Color(0xFF1976D2),
                            style = MiuixTheme.textStyles.body1,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .clickable { open("update_log") },
                        )
                        if (hasUpdate) {
                            HorizontalDivider(modifier = Modifier.padding(top = 14.dp, bottom = 12.dp))
                            val buttonBackdrop = rememberLayerBackdrop()
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .layerBackdrop(buttonBackdrop)
                                        .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = .12f)),
                                )
                                val isCloudDisk = updateChannel == UpdateChannel.CLOUD_DISK
                                UpdateToolbarButton(
                                    onClick = {
                                        if (isCloudDisk) {
                                            showRedirectDialog = true
                                        } else {
                                            startDownload()
                                        }
                                    },
                                    text = when {
                                        isCloudDisk -> tr("update_redirect", "跳转")
                                        installing -> tr("update_installing", "安装中")
                                        downloaded -> tr("update_install_now", "立即安装")
                                        downloadId != null -> "${tr("update_downloading", "下载中")} $downloadProgress%"
                                        else -> tr("update_download_now", "立即下载")
                                    },
                                    primary = true,
                                    enabled = isCloudDisk || !installing,
                                    modifier = Modifier.fillMaxWidth(),
                                    backdrop = buttonBackdrop,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    WindowDialog(
        show = showRedirectDialog && updateInfo != null,
        title = tr("update_redirect_dialog_title", "前往网盘下载"),
        summary = tr("update_redirect_dialog_message", "将跳转到网盘页面下载更新安装包，也可以复制链接后在浏览器中打开。"),
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { showRedirectDialog = false },
    ) {
        Column(Modifier.fillMaxWidth()) {
            GlassDialogButton(
                onClick = {
                    val url = updateInfo?.apkUrl ?: return@GlassDialogButton
                    showRedirectDialog = false
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        Toast.makeText(
                            context,
                            tr("update_open_link_failed", "无法打开浏览器，请复制链接后手动访问"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(tr("update_redirect_now", "立即跳转"))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(
                    onClick = {
                        val url = updateInfo?.apkUrl ?: return@GlassDialogButton
                        copyToClipboard(context, tr("update_channel_cloud_disk", "网盘"), url)
                        Toast.makeText(context, tr("copied", "已复制"), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(tr("copy", "复制"))
                }
                GlassDialogButton(
                    onClick = { showRedirectDialog = false },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(tr("cancel", "取消"))
                }
            }
        }
    }
}

@Composable
fun UpdateLogPage(padding: PaddingValues) {
    val context = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val channel = AppUpdateManager.getUpdateChannel(context)
        val loaded = withContext(Dispatchers.IO) {
            runCatching { AppUpdateManager.fetchReleaseNote(AppUpdateManager.fetchUpdateInfo(channel).releaseNoteUrl) }.getOrNull()
        }
        note = loaded
        failed = loaded == null
    }
    LazyColumn(
        pageContentModifier(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
                Text(
                    when {
                        note != null -> markdownAnnotatedString(note!!)
                        failed -> AnnotatedString(tr("update_unavailable", "暂时无法获取更新信息"))
                        else -> AnnotatedString(tr("loading", "加载中…"))
                    },
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }
    }
}

@Composable
internal fun UpdateToolbarButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    backdrop: LayerBackdrop? = LocalToolbarBackdrop.current as? LayerBackdrop,
    shape: Shape = RoundedCornerShape(50.dp),
    surfaceColor: Color? = null,
    primaryContentColor: Color? = null,
    blurRadius: Dp = TOOLBAR_GLASS_BLUR_RADIUS,
    leadingIcon: (@Composable () -> Unit)? = null,
    allowDrag: Boolean = true,
) {
    val animationScope = rememberCoroutineScope()
    val drag = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = 0f,
            valueRange = -1f..1f,
            visibilityThreshold = .001f,
            initialScale = 1f,
            pressedScale = 1.035f,
            onDragStopped = { animateToValue(0f) },
            onDrag = { size, amount ->
                updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f))
            },
        )
    }
    val highlight = remember(animationScope) {
        InteractiveHighlight(animationScope) { size, offset ->
            Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height))
        }
    }
    val colorStyle = LocalAuraColorStyle.current
    val useThemePrimary = colorStyle.isDynamicEnabled ||
        LocalAppUiStyle.current != AppUiStyle.HYPER ||
        LocalAuraOklabScheme.current != null
    val tint = surfaceColor ?: if (primary) {
        (if (useThemePrimary) MiuixTheme.colorScheme.primary else Color(0xFF0088FF)).copy(alpha = .92f)
    } else {
        MiuixTheme.colorScheme.surfaceContainer.copy(alpha = .82f)
    }
    val advancedMaterial = LocalAdvancedMaterialEnabled.current && isRuntimeShaderSupported()
    Box(
        modifier
            .height(48.dp)
            .then(if (enabled) highlight.gestureModifier else Modifier)
            .then(if (enabled && allowDrag) drag.modifier else Modifier)
            .clip(shape)
            .then(
                if (backdrop != null && advancedMaterial) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            vibrancy()
                            if (blurRadius > 0.dp) blur(blurRadius.toPx())
                            toolbarGlassLens(drag.pressProgress)
                        },
                        highlight = { toolbarGlassHighlight(drag.pressProgress) },
                        shadow = { Shadow.Default.copy(radius = 5.dp, color = Color.Black, alpha = .24f) },
                        innerShadow = null,
                        onDrawSurface = { drawRoundRect(tint) },
                    )
                } else {
                    Modifier.background(tint)
                },
            )
            .graphicsLayer {
                alpha = if (enabled) 1f else .42f
                scaleX = drag.scaleX
                scaleY = drag.scaleY
                translationX = drag.value * 4.dp.toPx()
            }
            .then(if (enabled) highlight.modifier else Modifier)
            .clickable(enabled = enabled, interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            leadingIcon?.let {
                it()
                Spacer(Modifier.width(9.dp))
            }
            Text(
                text,
                color = if (primary) {
                    primaryContentColor ?: if (useThemePrimary) MiuixTheme.colorScheme.onPrimary else Color.White
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun markdownAnnotatedString(markdown: String): AnnotatedString = buildAnnotatedString {
    markdown.lines().forEachIndexed { index, line ->
        val heading = Regex("^\\s*#{1,6}\\s+(.*)$").find(line)
        val content = heading?.groupValues?.get(1) ?: line
        val headingStyle = if (heading != null) {
            SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)
        } else {
            SpanStyle()
        }
        withStyle(headingStyle) {
            var cursor = 0
            Regex("(\\*\\*|__)(.+?)\\1|`(.+?)`|\\[([^]]+)]\\(([^)]+)\\)").findAll(content).forEach { match ->
                append(content.substring(cursor, match.range.first))
                when {
                    match.groupValues[2].isNotEmpty() -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[2]) }
                    }
                    match.groupValues[3].isNotEmpty() -> {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[3]) }
                    }
                    else -> append(match.groupValues[4])
                }
                cursor = match.range.last + 1
            }
            append(content.substring(cursor))
        }
        if (index < markdown.lines().lastIndex) append("\n")
    }
}
