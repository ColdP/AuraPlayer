package btm.m.auraplayer.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.*
import btm.m.auraplayer.ui.*
import com.kyant.backdrop.Backdrop
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import java.util.UUID

internal data class SongActionRequest(val song: Song, val backdrop: Backdrop?, val remove: (() -> Unit)? = null)
internal val LocalSongActions = staticCompositionLocalOf<(SongActionRequest) -> Unit> { {} }
internal val LocalOpenTagEditor = staticCompositionLocalOf<(Song) -> Unit> { {} }

@Composable
internal fun SongActionsSheet(model: AuraViewModel, request: SongActionRequest, show: Boolean,
                              dismiss: () -> Unit, onDismissFinished: () -> Unit) {
    val song = request.song
    val editTags = LocalOpenTagEditor.current
    val foreground = sheetForeground()
    val expressive = if (LocalAppUiStyle.current == AppUiStyle.MATERIAL) {
        LocalAuraExpressiveScheme.current?.toPlayerExpressiveColors()
    } else null
    var artists by remember(song.key) { mutableStateOf(song.creditedArtists()) }
    var playlistOpen by remember(song.key) { mutableStateOf(false) }
    var playlistMounted by remember(song.key) { mutableStateOf(false) }
    var audioInfoOpen by remember(song.key) { mutableStateOf(false) }
    LaunchedEffect(show) { if (!show) audioInfoOpen = false }
    LaunchedEffect(song.key) { artists = model.resolveSongArtists(song) }
    val historyRevision = model.history.revision
    val favorite = model.isFavorite(song)
    val isOneUi = expressive == null && LocalAppUiStyle.current == AppUiStyle.ONE
    @Composable fun action(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, summary: String? = null,
                            enabled: Boolean = true, position: SheetItemPosition = SheetItemPosition.SINGLE, onClick: () -> Unit) {
        if (expressive != null || isOneUi) {
            SheetActionRow(icon, title, summary, enabled, expressive = expressive, position = position, onClick = onClick)
        } else {
            ArrowPreference(title, summary = summary,
                titleColor = BasicComponentColors(foreground, foreground.copy(alpha = .35f)),
                summaryColor = BasicComponentColors(foreground.copy(alpha = .68f), foreground.copy(alpha = .3f)),
                startAction = { Image(icon, null, Modifier.padding(end = 14.dp).size(24.dp), colorFilter = ColorFilter.tint(foreground)) },
                insideMargin = PaddingValues(horizontal = 20.dp, vertical = 12.dp), enabled = enabled, onClick = onClick)
        }
    }
    @Composable fun divider() {
        when {
            isOneUi -> HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), thickness = 0.75.dp, color = androidx.compose.ui.graphics.Color(rememberActiveOneUiPalette().divider))
            expressive == null -> HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = foreground.copy(alpha = .14f))
            else -> Spacer(Modifier.height(10.dp))
        }
    }
    val sheetModifier = if (expressive != null) {
        val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Modifier.clip(sheetShape).background(expressive.surfaceContainerLow, sheetShape)
    } else {
        Modifier.neutralSheetMaterial(request.backdrop)
    }
    OverlayBottomSheet(show = show, onDismissRequest = dismiss, onDismissFinished = onDismissFinished,
        modifier = sheetModifier, backgroundColor = androidx.compose.ui.graphics.Color.Transparent,
        insideMargin = DpSize(0.dp, 0.dp), dragHandleColor = expressive?.dragHandleColor ?: foreground.copy(alpha = .45f), allowDismiss = !model.downloadBusy) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            if (expressive != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(expressive.surfaceContainerHigh)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(song.artwork, Modifier.size(56.dp), cornerRadius = 16.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(song.title, color = expressive.onSurface, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(song.artist, song.album).filter(String::isNotBlank).joinToString(" · "), color = expressive.secondaryInk,
                            fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { model.toggleFavorite(song) }, modifier = Modifier.size(44.dp)) {
                        Image(if (favorite) MiuixIcons.Regular.FavoritesFill else MiuixIcons.Regular.Favorites,
                            if (favorite) tr("unfavorite", "取消收藏") else tr("favorite", "收藏"), Modifier.size(24.dp),
                            colorFilter = ColorFilter.tint(if (favorite) expressive.primary else expressive.onSurface))
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(song.artwork, Modifier.size(58.dp), cornerRadius = ListArtworkCornerRadius)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(song.title, color = foreground, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(song.artist, song.album).filter(String::isNotBlank).joinToString(" · "), color = foreground.copy(alpha = .68f),
                            fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { model.toggleFavorite(song) }, modifier = Modifier.size(48.dp)) {
                        Image(if (favorite) MiuixIcons.Regular.FavoritesFill else MiuixIcons.Regular.Favorites,
                            if (favorite) tr("unfavorite", "取消收藏") else tr("favorite", "收藏"), Modifier.size(26.dp), colorFilter = ColorFilter.tint(foreground))
                    }
                }
            }
            divider()
            val groupModifier = if (expressive != null) Modifier.fillMaxWidth().padding(horizontal = 16.dp) else Modifier.fillMaxWidth()
            Column(groupModifier, verticalArrangement = Arrangement.spacedBy(if (expressive != null) 2.dp else 0.dp)) {
                if (song.cloud) SongDownloadPreference(model, song, show, request.backdrop, expressive = expressive, position = SheetItemPosition.TOP)
                action(MiuixIcons.Regular.Layers, tr("play_next", "下一首播放"),
                    position = if (song.cloud) SheetItemPosition.MIDDLE else SheetItemPosition.TOP) { model.enqueueNext(song); dismiss() }
                action(MiuixIcons.Regular.Playlist, tr("add_to_queue", "添加到播放队列"), position = SheetItemPosition.MIDDLE) { model.enqueue(song); dismiss() }
                action(MiuixIcons.Regular.AddFolder, tr("add_to_playlist", "收藏到歌单"),
                    position = if (request.remove != null) SheetItemPosition.MIDDLE else SheetItemPosition.BOTTOM) { playlistMounted = true; playlistOpen = true }
                request.remove?.let { remove ->
                    action(MiuixIcons.Regular.Delete, tr("remove_from_playlist", "从歌单移除"), position = SheetItemPosition.BOTTOM) { remove(); dismiss() }
                }
            }
            divider()
            Column(groupModifier, verticalArrangement = Arrangement.spacedBy(if (expressive != null) 2.dp else 0.dp)) {
                action(MiuixIcons.Regular.Album, tr("album", "专辑"), song.album, song.albumId > 0 || !song.cloud,
                    position = SheetItemPosition.TOP) { model.openSongAlbum(song); dismiss() }
                val artistPosition = if (!song.cloud) SheetItemPosition.MIDDLE else SheetItemPosition.BOTTOM
                if (artists.size > 1) SheetDropdown(MiuixIcons.Regular.ContactsCircle, tr("artist", "艺术家"), song.artist, request.backdrop,
                    DropdownEntry(artists.map { artist -> DropdownItem(artist.name, enabled = !song.cloud || artist.id > 0,
                        icon = { Artwork(artist.artwork, it.size(30.dp), circular = true) },
                        onClick = { model.openSongArtist(song, artist); dismiss() }) }), expressive = expressive, position = artistPosition)
                else action(MiuixIcons.Regular.ContactsCircle, tr("artist", "艺术家"), song.artist, song.artistId > 0 || !song.cloud,
                    position = artistPosition) { model.openSongArtist(song); dismiss() }
                if (!song.cloud) {
                    action(MiuixIcons.Regular.File, tr("tag_editor", "标签编辑器"), position = SheetItemPosition.MIDDLE) { dismiss(); editTags(song) }
                    action(MiuixIcons.Regular.Info, tr("audio_info", "音频信息"), position = SheetItemPosition.BOTTOM) { audioInfoOpen = true }
                }
            }
        }
    }
    if (playlistMounted) SongPlaylistPicker(model, song, playlistOpen, request.backdrop,
        dismiss = { playlistOpen = false }, onDismissFinished = { playlistMounted = false })
    if (!song.cloud) {
        AudioInfoDialog(
            song = song,
            show = audioInfoOpen,
            playingFormat = model.audioFormat.takeIf { model.current?.key == song.key },
            onDismissRequest = { audioInfoOpen = false },
        )
    }
}

@Composable
private fun SongPlaylistPicker(model: AuraViewModel, song: Song, show: Boolean, backdrop: Backdrop?,
                               dismiss: () -> Unit, onDismissFinished: () -> Unit) {
    val foreground = sheetForeground()
    val revision = model.history.revision
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var createOpen by remember { mutableStateOf(false) }
    val cloud = model.cloudPlaylists.filter { it.creatorId == model.account?.userId && it.specialType != 5 }
    OverlayBottomSheet(show, title = tr("add_to_playlist", "收藏到歌单"), onDismissRequest = { if (!busy) dismiss() },
        onDismissFinished = onDismissFinished, modifier = Modifier.neutralSheetMaterial(backdrop),
        backgroundColor = androidx.compose.ui.graphics.Color.Transparent, insideMargin = DpSize(0.dp, 0.dp), allowDismiss = !busy,
        dragHandleColor = foreground.copy(alpha = .45f)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            if (song.cloud) {
                if (model.account == null) Text(tr("login_required", "请先登录网易云音乐"), Modifier.padding(20.dp), color = foreground)
                cloud.forEach { playlist ->
                    BasicComponent(title = playlist.name, summary = tr("cloud_playlist", "云端歌单"), enabled = !busy,
                        startAction = { Artwork(playlist.artwork, Modifier.padding(end = 12.dp).size(44.dp), cornerRadius = ListArtworkCornerRadius) },
                        onClick = { busy = true; failed = false; model.addSongToCloudPlaylist(playlist, song) { success ->
                            busy = false; failed = !success; if (success) dismiss()
                        } })
                }
                if (model.cloudPlaylistsHasMore || model.cloudPlaylistsLoading || model.cloudPlaylistsFailed)
                    CloudPagingFooter(model.cloudPlaylistsLoading, model.cloudPlaylistsFailed, model::loadMoreCloudPlaylists)
                if (model.account != null && cloud.isEmpty() && !model.cloudPlaylistsLoading)
                    Text(tr("no_playlists", "暂无可用歌单"), Modifier.padding(20.dp), color = foreground.copy(alpha = .68f))
            } else {
                model.history.playlists().forEach { playlist ->
                    BasicComponent(title = playlist.name, summary = tr("local_playlist", "本地歌单"), onClick = {
                        model.history.savePlaylists(model.history.playlists().map {
                            if (it.id == playlist.id) it.copy(songKeys = (it.songKeys + song.key).distinct()) else it
                        }); dismiss()
                    })
                }
                BasicComponent(title = tr("new_playlist", "新建歌单"), onClick = { createOpen = true })
            }
            if (busy) Text(tr("saving", "正在保存…"), Modifier.padding(20.dp), color = foreground)
            if (failed) Text(tr("playlist_add_failed", "添加失败，请点击歌单重试"), Modifier.padding(20.dp), color = foreground)
        }
    }
    WindowDialog(createOpen, title = tr("new_playlist", "新建歌单"), titleTextAlign = androidx.compose.ui.text.style.TextAlign.Start,
        onDismissRequest = { createOpen = false }) {
        AuraTextField(name, { name = it }, singleLine = true, label = tr("playlist_name", "歌单名称"), modifier = Modifier.fillMaxWidth())
        GlassDialogButton({ model.history.savePlaylists(model.history.playlists() + LocalPlaylist(UUID.randomUUID().toString(), name.trim(), listOf(song.key)))
            createOpen = false; dismiss()
        }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = name.isNotBlank()) { Text(tr("create", "创建")) }
    }
}
