// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.CloudPlaylistContent
import btm.m.auraplayer.data.Song
import btm.m.auraplayer.data.SongSort
import btm.m.auraplayer.data.netease
import btm.m.auraplayer.download.MusicDownloads
import btm.m.auraplayer.playback.CloudQuality
import btm.m.auraplayer.playback.StreamQuality
import btm.m.auraplayer.ui.player.SheetActionRow
import btm.m.auraplayer.ui.player.SheetItemPosition
import btm.m.auraplayer.ui.player.cloudDownloadText
import btm.m.auraplayer.ui.player.neutralSheetMaterial
import btm.m.auraplayer.ui.player.qualityName
import btm.m.auraplayer.ui.player.sheetForeground
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.AddCircle
import top.yukonga.miuix.kmp.icon.extended.AddFolder
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.ConvertFile
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SongMultiSelectFloatingToolbar(
    visible: Boolean,
    hasTracks: Boolean,
    selectedCount: Int,
    showDownload: Boolean,
    downloadEnabled: Boolean,
    showDelete: Boolean,
    deleteAsHide: Boolean = false,
    busy: Boolean,
    bottomPadding: Dp,
    onSelectAll: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.padding(bottom = bottomPadding)) {
        AnimatedVisibility(
            visible = visible,
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
                        enabled = hasTracks && !busy,
                        onClick = onSelectAll,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            MiuixIcons.Regular.SelectAll,
                            tr("select_all", "全选"),
                            Modifier.size(24.dp),
                        )
                    }
                    if (showDownload) {
                        IconButton(
                            enabled = downloadEnabled && !busy,
                            onClick = onDownload,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                MiuixIcons.Regular.Download,
                                tr("download", "下载"),
                                Modifier.size(24.dp),
                            )
                        }
                    }
                    if (showDelete) {
                        IconButton(
                            enabled = selectedCount > 0 && !busy,
                            onClick = onDelete,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                if (deleteAsHide) MiuixIcons.Regular.Hide else MiuixIcons.Regular.Delete,
                                if (deleteAsHide) tr("block_song", "屏蔽该歌曲") else tr("delete", "删除"),
                                Modifier.size(24.dp),
                                tint = if (selectedCount > 0 && !busy) {
                                    Color(0xFFD32F2F)
                                } else {
                                    Color(0xFFD32F2F).copy(alpha = 0.38f)
                                },
                            )
                        }
                    }
                    IconButton(
                        enabled = selectedCount > 0 && !busy,
                        onClick = onAddToQueue,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            MiuixIcons.Regular.AddCircle,
                            tr("add_to_queue", "添加到播放队列"),
                            Modifier.size(24.dp),
                        )
                    }
                    IconButton(
                        enabled = selectedCount > 0 && !busy,
                        onClick = onAddToPlaylist,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            MiuixIcons.Regular.AddFolder,
                            tr("add_to_playlist", "收藏到歌单"),
                            Modifier.size(24.dp),
                        )
                    }
                    IconButton(
                        enabled = !busy,
                        onClick = onClose,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            MiuixIcons.Regular.Close,
                            tr("close", "关闭"),
                            Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun BatchDeleteConfirmDialog(
    show: Boolean,
    title: String,
    summary: String,
    confirmLabel: String,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = title,
        summary = summary,
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { if (!busy) onDismiss() },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassDialogButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                enabled = !busy,
            ) {
                Text(tr("cancel", "取消"))
            }
            GlassDialogButton(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = !busy,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(if (busy) tr("saving", "正在保存…") else confirmLabel)
            }
        }
    }
}

@Composable
internal fun BatchDownloadQualitySheet(
    model: AuraViewModel,
    songs: List<Song>,
    show: Boolean,
    backdrop: Backdrop?,
    dismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onComplete: (succeeded: Int, failed: Int) -> Unit,
) {
    val context = LocalContext.current
    val foreground = sheetForeground()
    val expressive = if (LocalAppUiStyle.current == AppUiStyle.MATERIAL) {
        LocalAuraExpressiveScheme.current?.toPlayerExpressiveColors()
    } else null
    val neteaseSongs = remember(songs) { songs.filter { it.netease } }
    val singleSong = neteaseSongs.singleOrNull()
    var singleLevels by remember(singleSong?.key) { mutableStateOf<List<CloudQuality>>(emptyList()) }
    var loading by remember(singleSong?.key) { mutableStateOf(singleSong != null) }
    var failed by remember(singleSong?.key) { mutableStateOf(false) }
    var retry by remember(singleSong?.key) { mutableIntStateOf(0) }

    LaunchedEffect(show, singleSong?.key, retry) {
        val target = singleSong ?: run {
            loading = false
            failed = false
            return@LaunchedEffect
        }
        if (!show) return@LaunchedEffect
        loading = true
        failed = false
        try {
            singleLevels = model.app.api.qualityDetails(target.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    val sheetModifier = if (expressive != null) {
        val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Modifier.clip(sheetShape).background(expressive.surfaceContainerLow, sheetShape)
    } else {
        Modifier.neutralSheetMaterial(backdrop)
    }

    OverlayBottomSheet(
        show = show,
        title = tr("select_quality", "选择音质"),
        onDismissRequest = { if (!model.downloadBusy) dismiss() },
        onDismissFinished = onDismissFinished,
        modifier = sheetModifier,
        backgroundColor = Color.Transparent,
        insideMargin = DpSize(0.dp, 0.dp),
        dragHandleColor = expressive?.dragHandleColor ?: foreground.copy(alpha = .45f),
        allowDismiss = !model.downloadBusy,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            val subtitle = if (neteaseSongs.size <= 1 && singleSong != null) {
                singleSong.title
            } else {
                tr("batch_download_count", "已选择 %d 首可下载歌曲").format(neteaseSongs.size)
            }
            Text(
                text = subtitle,
                color = expressive?.secondaryInk ?: foreground.copy(alpha = .68f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
            if (neteaseSongs.isEmpty()) {
                Text(
                    text = tr("batch_download_unavailable", "所选歌曲暂无支持下载的云端音源"),
                    color = foreground.copy(alpha = .68f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            } else if (singleSong != null) {
                when {
                    loading && singleLevels.isEmpty() -> {
                        Text(
                            text = tr("loading", "正在加载…"),
                            color = foreground.copy(alpha = .68f),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                    failed && singleLevels.isEmpty() -> {
                        SheetActionRow(
                            icon = MiuixIcons.Regular.Refresh,
                            title = tr("quality_retry", "获取音质失败，点击重试"),
                            enabled = !model.downloadBusy,
                            expressive = expressive,
                            position = SheetItemPosition.SINGLE,
                            onClick = { retry++ },
                        )
                    }
                    else -> {
                        val options = singleLevels.ifEmpty {
                            StreamQuality.entries.map { CloudQuality(it, btm.m.auraplayer.playback.AudioDetails()) }
                        }
                        val groupModifier = if (expressive != null) Modifier.fillMaxWidth().padding(horizontal = 16.dp) else Modifier.fillMaxWidth()
                        Column(groupModifier, verticalArrangement = Arrangement.spacedBy(if (expressive != null) 2.dp else 0.dp)) {
                            options.forEachIndexed { index, option ->
                                val downloaded = MusicDownloads.isDownloaded(context, singleSong, option.quality)
                                val label = (if (downloaded) tr("downloaded", "已下载") + " · " else "") + cloudDownloadText(option, singleSong)
                                val position = when {
                                    options.size == 1 -> SheetItemPosition.SINGLE
                                    index == 0 -> SheetItemPosition.TOP
                                    index == options.lastIndex -> SheetItemPosition.BOTTOM
                                    else -> SheetItemPosition.MIDDLE
                                }
                                BatchQualityOptionRow(
                                    title = label,
                                    selected = option.quality == model.neteaseQuality,
                                    enabled = !model.downloadBusy,
                                    expressive = expressive,
                                    foreground = foreground,
                                    position = position,
                                ) {
                                    model.downloadSongs(neteaseSongs, option.quality) { ok, fail ->
                                        onComplete(ok, fail)
                                        dismiss()
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val qualities = StreamQuality.entries
                val groupModifier = if (expressive != null) Modifier.fillMaxWidth().padding(horizontal = 16.dp) else Modifier.fillMaxWidth()
                Column(groupModifier, verticalArrangement = Arrangement.spacedBy(if (expressive != null) 2.dp else 0.dp)) {
                    qualities.forEachIndexed { index, quality ->
                        val position = when {
                            qualities.size == 1 -> SheetItemPosition.SINGLE
                            index == 0 -> SheetItemPosition.TOP
                            index == qualities.lastIndex -> SheetItemPosition.BOTTOM
                            else -> SheetItemPosition.MIDDLE
                        }
                        BatchQualityOptionRow(
                            title = qualityName(quality),
                            selected = quality == model.neteaseQuality,
                            enabled = !model.downloadBusy,
                            expressive = expressive,
                            foreground = foreground,
                            position = position,
                        ) {
                            model.downloadSongs(neteaseSongs, quality) { ok, fail ->
                                onComplete(ok, fail)
                                dismiss()
                            }
                        }
                    }
                }
            }
            if (model.downloadBusy) {
                Text(
                    text = tr("preparing_download", "正在准备…"),
                    color = expressive?.secondaryInk ?: foreground.copy(alpha = .72f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun BatchQualityOptionRow(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    expressive: btm.m.auraplayer.ui.player.PlayerExpressiveColors?,
    foreground: Color,
    position: SheetItemPosition,
    onClick: () -> Unit,
) {
    if (expressive != null || LocalAppUiStyle.current == AppUiStyle.ONE) {
        SheetActionRow(
            icon = MiuixIcons.Regular.Download,
            title = title,
            summary = if (selected) tr("current_default_quality", "当前默认音质") else null,
            enabled = enabled,
            expressive = expressive,
            position = position,
            onClick = onClick,
        )
    } else {
        BasicComponent(
            title = title,
            summary = if (selected) tr("current_default_quality", "当前默认音质") else null,
            titleColor = BasicComponentColors(foreground, foreground.copy(alpha = .35f)),
            summaryColor = BasicComponentColors(foreground.copy(alpha = .68f), foreground.copy(alpha = .3f)),
            startAction = {
                Image(
                    MiuixIcons.Regular.Download,
                    null,
                    Modifier.padding(end = 14.dp).size(22.dp),
                    colorFilter = ColorFilter.tint(foreground),
                )
            },
            insideMargin = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            enabled = enabled,
            onClick = onClick,
        )
    }
}

@Composable
internal fun BatchPlaylistPickerSheet(
    model: AuraViewModel,
    songs: List<Song>,
    show: Boolean,
    backdrop: Backdrop?,
    dismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onCompleted: () -> Unit,
) {
    val context = LocalContext.current
    val foreground = sheetForeground()
    val revision = model.history.revision
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    val localSongs = remember(songs) { songs.filter { !it.cloud } }
    val cloudSongs = remember(songs) { songs.filter { it.cloud } }
    val neteaseCloudSongs = remember(cloudSongs) { cloudSongs.filter { it.netease && it.id > 0L } }
    val isCloudMode = cloudSongs.isNotEmpty() && localSongs.isEmpty()
    val ownedCloudPlaylists = model.cloudPlaylists.filter { it.creatorId == model.account?.userId && it.specialType != 5 }

    val expressive = if (LocalAppUiStyle.current == AppUiStyle.MATERIAL) {
        LocalAuraExpressiveScheme.current?.toPlayerExpressiveColors()
    } else null
    val sheetModifier = if (expressive != null) {
        val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Modifier.clip(sheetShape).background(expressive.surfaceContainerLow, sheetShape)
    } else {
        Modifier.neutralSheetMaterial(backdrop)
    }

    OverlayBottomSheet(
        show = show,
        title = if (isCloudMode) tr("add_to_cloud_playlist", "收藏到云端歌单") else tr("add_to_local_playlist", "添加到本地歌单"),
        onDismissRequest = { if (!busy) dismiss() },
        onDismissFinished = onDismissFinished,
        modifier = sheetModifier,
        backgroundColor = Color.Transparent,
        insideMargin = DpSize(0.dp, 0.dp),
        allowDismiss = !busy,
        dragHandleColor = expressive?.dragHandleColor ?: foreground.copy(alpha = .45f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            if (isCloudMode) {
                when {
                    model.account == null -> {
                        Text(
                            tr("login_required", "请先登录网易云音乐"),
                            Modifier.padding(20.dp),
                            color = foreground,
                        )
                    }
                    neteaseCloudSongs.isEmpty() -> {
                        Text(
                            tr("cloud_playlist_add_unsupported", "当前仅支持将网易云音乐收藏到云端歌单"),
                            Modifier.padding(20.dp),
                            color = foreground.copy(alpha = .68f),
                        )
                    }
                    else -> {
                        ownedCloudPlaylists.forEach { playlist ->
                            BasicComponent(
                                title = playlist.name,
                                summary = tr("cloud_playlist", "云端歌单"),
                                titleColor = BasicComponentColors(foreground, foreground.copy(alpha = .35f)),
                                summaryColor = BasicComponentColors(foreground.copy(alpha = .68f), foreground.copy(alpha = .3f)),
                                enabled = !busy,
                                startAction = {
                                    Artwork(
                                        playlist.artwork,
                                        Modifier.padding(end = 12.dp).size(44.dp),
                                        cornerRadius = ListArtworkCornerRadius,
                                    )
                                },
                                onClick = {
                                    busy = true
                                    failed = false
                                    model.addSongsToCloudPlaylist(playlist, neteaseCloudSongs) { success ->
                                        busy = false
                                        failed = !success
                                        if (success) {
                                            Toast.makeText(
                                                context,
                                                tr("added_to_playlist_count", "已添加到歌单（%d 首）").format(neteaseCloudSongs.size),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            onCompleted()
                                            dismiss()
                                        }
                                    }
                                },
                            )
                        }
                        if (model.cloudPlaylistsHasMore || model.cloudPlaylistsLoading || model.cloudPlaylistsFailed) {
                            CloudPagingFooter(model.cloudPlaylistsLoading, model.cloudPlaylistsFailed, model::loadMoreCloudPlaylists)
                        }
                        if (ownedCloudPlaylists.isEmpty() && !model.cloudPlaylistsLoading) {
                            Text(
                                tr("no_playlists", "暂无可用歌单"),
                                Modifier.padding(20.dp),
                                color = foreground.copy(alpha = .68f),
                            )
                        }
                    }
                }
            } else {
                val localPlaylists = remember(revision) { model.history.playlists() }
                localPlaylists.forEach { playlist ->
                    val tracks = model.songs.filter { it.key in playlist.songKeys }
                    BasicComponent(
                        title = playlist.name,
                        summary = "${tracks.size} ${tr("song_count", "首歌曲")}",
                        titleColor = BasicComponentColors(foreground, foreground.copy(alpha = .35f)),
                        summaryColor = BasicComponentColors(foreground.copy(alpha = .68f), foreground.copy(alpha = .3f)),
                        startAction = {
                            Artwork(
                                tracks.firstOrNull()?.artwork.orEmpty(),
                                Modifier.padding(end = 12.dp).size(44.dp),
                                cornerRadius = ListArtworkCornerRadius,
                            )
                        },
                        onClick = {
                            model.addSongsToLocalPlaylist(playlist.id, localSongs)
                            Toast.makeText(
                                context,
                                tr("added_to_playlist_count", "已添加到歌单（%d 首）").format(localSongs.size),
                                Toast.LENGTH_SHORT,
                            ).show()
                            onCompleted()
                            dismiss()
                        },
                    )
                }
                if (localPlaylists.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = foreground.copy(alpha = .12f))
                }
                BasicComponent(
                    title = tr("new_playlist", "新建歌单"),
                    titleColor = BasicComponentColors(foreground, foreground.copy(alpha = .35f)),
                    startAction = {
                        Image(
                            MiuixIcons.Regular.Add,
                            null,
                            Modifier.padding(end = 12.dp).size(22.dp),
                            colorFilter = ColorFilter.tint(foreground),
                        )
                    },
                    onClick = {
                        newPlaylistName = ""
                        createOpen = true
                    },
                )
            }
            if (busy) Text(tr("saving", "正在保存…"), Modifier.padding(20.dp), color = foreground)
            if (failed) Text(tr("playlist_add_failed", "添加失败，请点击歌单重试"), Modifier.padding(20.dp), color = foreground)
        }
    }

    WindowDialog(
        createOpen,
        title = tr("new_playlist", "新建歌单"),
        titleTextAlign = TextAlign.Start,
        onDismissRequest = { createOpen = false },
    ) {
        AuraTextField(
            newPlaylistName,
            { newPlaylistName = it },
            singleLine = true,
            label = tr("playlist_name", "歌单名称"),
            modifier = Modifier.fillMaxWidth(),
        )
        GlassDialogButton(
            onClick = {
                model.createLocalPlaylistWithSongs(newPlaylistName, localSongs)
                createOpen = false
                Toast.makeText(
                    context,
                    tr("added_to_playlist_count", "已添加到歌单（%d 首）").format(localSongs.size),
                    Toast.LENGTH_SHORT,
                ).show()
                onCompleted()
                dismiss()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            enabled = newPlaylistName.isNotBlank(),
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) {
            Text(tr("create", "创建"))
        }
    }
}

@Composable
internal fun PlaylistToolbarMenu(
    model: AuraViewModel,
    isLocalUserPlaylist: Boolean,
    cloud: CloudPlaylistContent?,
    isDailyCollection: Boolean,
    tracks: List<Song>,
    sortOrder: SongSort,
    onAddSongs: () -> Unit,
    onEnterMultiSelect: () -> Unit,
    onSelectSort: (SongSort) -> Unit,
) {
    val iconColor = MiuixTheme.colorScheme.onSurface
    var menuOpen by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val colors = glassDropdownColors()
    fun dismiss() {
        sortExpanded = false
        menuOpen = false
    }
    fun select(action: () -> Unit) {
        if (!menuOpen || pendingAction != null) return
        pendingAction = action
        dismiss()
    }
    val items = listOf(
        if (isLocalUserPlaylist) {
            DropdownItem(
                tr("add_songs", "添加歌曲"),
                icon = { modifier -> Image(MiuixIcons.Regular.Add, null, modifier.size(22.dp), colorFilter = ColorFilter.tint(iconColor)) },
            )
        } else {
            DropdownItem(
                tr("refresh", "刷新"),
                icon = { modifier -> Image(MiuixIcons.Regular.Refresh, null, modifier.size(22.dp), colorFilter = ColorFilter.tint(iconColor)) },
            )
        },
        DropdownItem(
            tr("shuffle", "随机播放"),
            icon = { modifier -> Image(MiuixIcons.Regular.ConvertFile, null, modifier.size(22.dp), colorFilter = ColorFilter.tint(iconColor)) },
        ),
        DropdownItem(
            tr("multi_select", "多选"),
            icon = { modifier -> Image(MiuixIcons.Regular.SelectAll, null, modifier.size(22.dp), colorFilter = ColorFilter.tint(iconColor)) },
        ),
        DropdownItem(
            tr("sort", "排序"),
            icon = { modifier -> Image(MiuixIcons.Regular.Sort, null, modifier.size(22.dp), colorFilter = ColorFilter.tint(iconColor)) },
        ),
    )
    Box {
        GlassToolbarIconButton(AuraMoreHorizontalIcon, tr("more", "更多")) {
            if (pendingAction == null) {
                if (menuOpen) dismiss() else menuOpen = true
            }
        }
        GlassDropdownPopup(
            entries = emptyList(),
            show = menuOpen,
            onDismiss = ::dismiss,
            onBack = { if (sortExpanded) sortExpanded = false else dismiss() },
            onDismissFinished = {
                val action = pendingAction
                pendingAction = null
                action?.invoke()
            },
            dropdownColors = colors,
            backdrop = LocalToolbarBackdrop.current,
            maxHeight = 440.dp,
            horizontalOffset = 0.dp,
            trailingScreenInset = 12.dp,
            growFromAnchor = true,
        ) {
            Column(Modifier.width(200.dp).verticalScroll(rememberScrollState())) {
                items.forEachIndexed { index, item ->
                    Box(Modifier.fillMaxWidth(), propagateMinConstraints = true) {
                        DropdownImpl(
                            item = item,
                            optionSize = items.size,
                            index = index,
                            isSelected = false,
                            dropdownColors = colors,
                            enabled = menuOpen,
                            hasSubmenu = false,
                            isFirst = index == 0,
                            isLast = index == items.lastIndex && !sortExpanded,
                            onSelectedIndexChange = {
                                when (index) {
                                    0 -> select {
                                        when {
                                            isLocalUserPlaylist -> onAddSongs()
                                            cloud != null -> if (!cloud.loading) model.refreshCloudSongs()
                                            isDailyCollection -> model.refreshCloudDailyCollection()
                                            else -> model.refreshLibrary()
                                        }
                                    }
                                    1 -> select {
                                        if (tracks.isNotEmpty()) {
                                            model.playCollection(tracks, shuffle = true, content = cloud)
                                        }
                                    }
                                    2 -> select(onEnterMultiSelect)
                                    else -> sortExpanded = !sortExpanded
                                }
                            },
                        )
                    }
                }
                AnimatedVisibility(
                    visible = sortExpanded,
                    modifier = Modifier.fillMaxWidth(),
                    enter = expandVertically(tween(300, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
                        fadeIn(tween(240)) + scaleIn(tween(300), initialScale = .9f, transformOrigin = TransformOrigin(.5f, 0f)),
                    exit = shrinkVertically(tween(260, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) +
                        fadeOut(tween(200)) + scaleOut(tween(260), targetScale = .9f, transformOrigin = TransformOrigin(.5f, 0f)),
                ) {
                    val blur by transition.animateFloat(transitionSpec = { tween(240) }, label = "playlistSortOptionsBlur") { state ->
                        if (state == EnterExitState.Visible) 0f else 10f
                    }
                    Column(
                        Modifier.fillMaxWidth().graphicsLayer {
                            val radius = blur.dp.toPx()
                            renderEffect = if (radius > .01f) BlurEffect(radius, radius, TileMode.Decal) else null
                        },
                    ) {
                        SongSort.entries.forEachIndexed { index, sort ->
                            key(sort) {
                                Box(Modifier.fillMaxWidth(), propagateMinConstraints = true) {
                                    DropdownImpl(
                                        item = DropdownItem(songSortLabel(sort)),
                                        optionSize = SongSort.entries.size,
                                        index = index,
                                        isSelected = sortOrder == sort,
                                        dropdownColors = colors,
                                        enabled = menuOpen && sortExpanded,
                                        isFirst = false,
                                        isLast = index == SongSort.entries.lastIndex,
                                        onSelectedIndexChange = { select { onSelectSort(sort) } },
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
