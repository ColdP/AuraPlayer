package btm.m.auraplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.*
import coil3.compose.AsyncImage
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Calendar
import java.util.UUID

@Composable
fun AuraTabIcon(index: Int, color: Color) {
    val icon = when (index) {
        0 -> MiuixIcons.Regular.Home
        1 -> MiuixIcons.Regular.Playlist
        2 -> MiuixIcons.Regular.Music
        else -> MiuixIcons.Regular.Backup
    }
    androidx.compose.foundation.Image(icon, null, Modifier.size(28.dp), colorFilter = ColorFilter.tint(color))
}

@Composable
fun Artwork(uri: String, modifier: Modifier = Modifier, circular: Boolean = false) {
    Box(modifier.clip(if (circular) CircleShape else RoundedCornerShape(16.dp))
        .background(Brush.linearGradient(listOf(MiuixTheme.colorScheme.primaryContainer, MiuixTheme.colorScheme.surfaceContainer))), contentAlignment = Alignment.Center) {
        Text("♫", fontSize = 28.sp, color = MiuixTheme.colorScheme.primary.copy(alpha = .7f))
        if (uri.isNotBlank()) AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

private fun durationText(ms: Long): String = "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"

@Composable
fun SongRow(song: Song, model: AuraViewModel, onClick: () -> Unit, onLongAction: (() -> Unit)? = null) {
    val revision = model.history.revision
    val favorite = remember(song.key, revision) { song.key in model.history.favorites() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 9.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(song.artwork, Modifier.size(54.dp))
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(song.title.ifBlank { tr("unknown_title", "未命名歌曲") }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (model.current?.key == song.key) FontWeight.Bold else FontWeight.Medium,
                color = if (model.current?.key == song.key) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
            Text("${song.artist} · ${song.album}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(durationText(song.duration), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            IconButton(onClick = { if (onLongAction != null) onLongAction() else model.history.toggle(song) }, modifier = Modifier.size(38.dp)) {
                Text(if (onLongAction != null) "−" else if (favorite) "♥" else "♡", color = MiuixTheme.colorScheme.primary, fontSize = 22.sp)
            }
        }
    }
}

@Composable
fun SectionTitle(title: String) { Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold) }

@Composable
internal fun PermissionCard(onClick: () -> Unit) {
    SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(18.dp)) {
        Text(tr("permission_title", "让 Aura 找到你的音乐"), fontWeight = FontWeight.Bold)
        Text(tr("permission_summary", "授权读取音频后，歌曲、专辑和艺术家会自动出现在这里"), style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(vertical = 10.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        GlassDialogButton(onClick, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("grant_audio", "授权读取音乐")) }
    }
}

@Composable
fun SongsPage(model: AuraViewModel, padding: PaddingValues, requestPermission: () -> Unit) {
    LazyColumn(pageContentModifier(), contentPadding = padding) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (model.scanning) tr("scanning", "正在扫描…") else "${model.songs.size} ${tr("song_count", "首歌曲")}", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                GlassDialogButton({ model.play(model.songs, shuffle = true) }, enabled = model.songs.isNotEmpty()) { Text(tr("shuffle", "随机播放")) }
            }
        }
        if (!model.hasPermission) item { PermissionCard(requestPermission) }
        else if (model.songs.isEmpty()) item { EmptyState(tr("no_songs", "没有找到歌曲"), tr("check_filters", "请检查音乐权限、文件夹与筛选设置"), tr("rescan", "重新扫描"), { model.refreshLibrary() }) }
        items(model.songs, key = { it.key }) { song -> SongRow(song, model, { model.play(model.songs, model.songs.indexOf(song)) }) }
    }
}

@Composable
fun CollectionTile(title: String, subtitle: String, art: String, modifier: Modifier = Modifier, circular: Boolean = false, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(2.dp)) {
        Artwork(art, Modifier.fillMaxWidth().aspectRatio(1f), circular)
        Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(subtitle, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
fun AlbumsArtistsPage(model: AuraViewModel, padding: PaddingValues, tab: Int) {
    val collections = remember(model.songs, tab) {
        if (tab == 0) model.songs.groupBy { "${it.volume}:${it.albumId}:${it.album}" }.map { (key, tracks) ->
            MusicCollection("album:$key", tracks.first().album, tracks.first().artist, tracks.sortedBy { it.track }, tracks.first().artwork)
        }.sortedBy { it.title }
        else model.songs.groupBy { "${it.volume}:${it.artistId}:${it.artist}" }.map { (key, tracks) ->
            MusicCollection("artist:$key", tracks.first().artist, "${tracks.size} ${tr("song_count", "首歌曲")}", tracks, tracks.first().artwork)
        }.sortedBy { it.title }
    }
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (collections.isEmpty()) item { EmptyState(tr("no_collections", "暂无内容"), tr("library_content_hint", "本地音乐会自动归类到这里")) }
        items(collections.chunked(2)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                pair.forEach { collection -> CollectionTile(collection.title, collection.subtitle, collection.artwork, Modifier.weight(1f), tab == 1) { model.openCollection(collection) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun CollectionPage(model: AuraViewModel, padding: PaddingValues) {
    val collection = model.collection ?: return
    var addToPlaylist by remember { mutableStateOf(false) }
    val revision = model.history.revision
    val localPlaylist = remember(collection.key, revision) { model.history.playlists().find { "playlist:${it.id}" == collection.key } }
    val visible = remember(model.songs) { model.songs.map { it.key }.toSet() }
    val tracks = if (localPlaylist != null) model.songs.filter { it.key in localPlaylist.songKeys }
        else collection.songs.filter { it.cloud || it.key in visible }
    LazyColumn(pageContentModifier(), contentPadding = padding) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(collection.artwork.ifBlank { tracks.firstOrNull()?.artwork.orEmpty() }, Modifier.size(90.dp))
                Column(Modifier.padding(start = 18.dp)) {
                    Text("${tracks.size} ${tr("song_count", "首歌曲")}", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                        GlassDialogButton({ model.play(tracks) }, enabled = tracks.isNotEmpty(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("play_all", "播放全部")) }
                        if (localPlaylist != null) GlassDialogButton({ addToPlaylist = true }) { Text(tr("add", "添加")) }
                    }
                }
            }
        }
        if (tracks.isEmpty()) item { EmptyState(tr("no_songs", "没有找到歌曲")) }
        items(tracks, key = { it.key }) { song ->
            SongRow(song, model, { model.play(tracks, tracks.indexOf(song)) }, onLongAction = localPlaylist?.let { playlist -> ({
                model.history.savePlaylists(model.history.playlists().map { if (it.id == playlist.id) it.copy(songKeys = it.songKeys - song.key) else it })
            }) })
        }
    }
    if (addToPlaylist && localPlaylist != null) PlaylistSongPicker(model, localPlaylist) { addToPlaylist = false }
}

@Composable
fun PlaylistsPage(model: AuraViewModel, padding: PaddingValues, tab: Int, login: () -> Unit) {
    val revision = model.history.revision
    val playlists = remember(revision) { model.history.playlists() }
    var creating by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<LocalPlaylist?>(null) }
    var deletion by remember { mutableStateOf<LocalPlaylist?>(null) }
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (tab == 0) {
            item { GlassDialogButton({ creating = true; name = "" }, Modifier.fillMaxWidth()) { Text(tr("new_playlist", "新建歌单")) } }
            items(playlists, key = { it.id }) { playlist ->
                val tracks = model.songs.filter { it.key in playlist.songKeys }
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    Row(Modifier.fillMaxWidth().clickable { model.openCollection(MusicCollection("playlist:${playlist.id}", playlist.name, "", tracks)) }
                        .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(tracks.firstOrNull()?.artwork.orEmpty(), Modifier.size(60.dp))
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(playlist.name, fontWeight = FontWeight.SemiBold)
                            Text("${tracks.size} ${tr("song_count", "首歌曲")}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        GlassIconDropdownMenu(DropdownEntry(listOf(
                            DropdownItem(tr("add_songs", "添加歌曲"), onClick = { editing = playlist }),
                            DropdownItem(tr("delete", "删除"), onClick = { deletion = playlist }),
                        )), LocalToolbarBackdrop.current) { Text("···", fontSize = 24.sp) }
                    }
                }
            }
            if (playlists.isEmpty()) item { EmptyState(tr("playlist_empty", "创建第一张歌单"), tr("playlist_empty_summary", "把喜欢的本地音乐收在一起")) }
        } else {
            item {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    Preference(model.account?.nickname ?: tr("netease_login", "网易云音乐登录"),
                        if (model.cloudBusy) tr("loading", "正在加载…") else if (model.account == null) tr("login_to_playlists", "登录后查看你的网易云歌单") else tr("account_manage", "管理账号与连接"), onClick = login)
                }
            }
            items(model.cloudPlaylists, key = { it.id }) { playlist ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled = !model.cloudBusy) { model.openCloudPlaylist(playlist) }
                    .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(playlist.artwork, Modifier.size(68.dp))
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(playlist.name, fontWeight = FontWeight.SemiBold)
                        Text("${playlist.count} ${tr("song_count", "首歌曲")}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            if (model.account != null && model.cloudPlaylists.isEmpty() && !model.cloudBusy) item { EmptyState(tr("no_cloud_playlists", "暂无网易云歌单"), action = tr("refresh", "刷新"), onAction = model::refreshCloud) }
        }
    }
    WindowDialog(creating, title = tr("new_playlist", "新建歌单"), onDismissRequest = { creating = false }) {
        TextField(name, { name = it }, label = tr("playlist_name", "歌单名称"), singleLine = true, modifier = Modifier.fillMaxWidth())
        GlassDialogButton({
            model.history.savePlaylists(playlists + LocalPlaylist(UUID.randomUUID().toString(), name.trim(), emptyList()))
            creating = false
        }, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("create", "创建")) }
    }
    WindowDialog(deletion != null, title = tr("delete_playlist", "删除歌单？"), summary = tr("delete_playlist_summary", "只会删除歌单，不会删除音乐文件"), onDismissRequest = { deletion = null }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ deletion = null }, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
            GlassDialogButton({ model.history.savePlaylists(playlists.filterNot { it.id == deletion?.id }); deletion = null }, Modifier.weight(1f)) { Text(tr("delete", "删除")) }
        }
    }
    editing?.let { PlaylistSongPicker(model, it) { editing = null } }
}

@Composable
private fun PlaylistSongPicker(model: AuraViewModel, playlist: LocalPlaylist, dismiss: () -> Unit) {
    var selected by remember(playlist.id) { mutableStateOf(playlist.songKeys.toSet()) }
    WindowDialog(true, title = tr("add_songs", "添加歌曲"), onDismissRequest = dismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(model.songs, key = { it.key }) { song ->
                BasicComponent(title = song.title, summary = song.artist, onClick = { selected = if (song.key in selected) selected - song.key else selected + song.key },
                    endActions = { Checkbox(state = androidx.compose.ui.state.ToggleableState(song.key in selected), onClick = { selected = if (song.key in selected) selected - song.key else selected + song.key }) })
            }
        }
        GlassDialogButton({
            model.history.savePlaylists(model.history.playlists().map { if (it.id == playlist.id) it.copy(songKeys = selected.toList()) else it })
            dismiss()
        }, Modifier.fillMaxWidth().padding(top = 12.dp), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("save", "保存")) }
    }
}
