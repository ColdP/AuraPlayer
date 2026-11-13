package btm.m.auraplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.SearchCategory
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CloudHubPage(model: AuraViewModel, padding: PaddingValues, login: () -> Unit) {
    val user = model.account
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item("account") {
            SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                ArrowPreference(title = user?.let { "${it.nickname} · ${it.userId}" } ?: tr("netease", "网易云音乐"),
                    summary = tr("account_manage", "管理账号与连接"), onClick = login,
                    startAction = { user?.avatar?.takeIf { it.isNotBlank() }?.let { Artwork(it, Modifier.padding(end = 12.dp).size(44.dp), true) } })
            }
        }
        item("rank") {
            SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                ArrowPreference(title = tr("listening_rank", "听歌排行"), onClick = { if (user == null) login() else model.openListeningRank() },
                    startAction = { Artwork(model.current?.artwork.orEmpty(), Modifier.padding(end = 12.dp).size(48.dp), cornerRadius = ListArtworkCornerRadius) })
            }
        }
        item("playlists") {
            Group(tr("playlists", "歌单")) {
                model.cloudPlaylists.forEach { playlist ->
                    val liked = playlist.specialType == 5 && playlist.creatorId == user?.userId
                    ArrowPreference(title = if (liked) tr("liked_music", "我喜欢的音乐") else playlist.name,
                        summary = "${playlist.count} ${tr("song_count", "首歌曲")}", onClick = { model.openCloudPlaylist(playlist) },
                        startAction = { Artwork(playlist.artwork, Modifier.padding(end = 12.dp).size(48.dp), cornerRadius = ListArtworkCornerRadius) })
                }
                if (user == null) Preference(tr("login_required", "请先登录网易云音乐"), onClick = login)
                else if (model.cloudPlaylists.isEmpty() && !model.cloudPlaylistsLoading) Text(tr("no_cloud_playlists", "暂无网易云歌单"), Modifier.padding(20.dp))
            }
        }
        if (user != null && (model.cloudPlaylistsHasMore || model.cloudPlaylistsLoading || model.cloudPlaylistsFailed)) item("playlist_paging") {
            CloudPagingFooter(model.cloudPlaylistsLoading, model.cloudPlaylistsFailed, model::loadMoreCloudPlaylists)
        }
        item("albums") {
            Group(tr("saved_albums", "收藏的专辑")) {
                model.subscribedAlbums.forEach { album ->
                    ArrowPreference(title = album.title, summary = album.artist, onClick = { model.openCatalogAlbum(album) },
                        startAction = { Artwork(album.artwork, Modifier.padding(end = 12.dp).size(48.dp), cornerRadius = ListArtworkCornerRadius) })
                }
                if (model.subscribedAlbums.isEmpty() && !model.subscribedAlbumsLoading) Text(tr("no_saved_albums", "暂无收藏的专辑"),
                    Modifier.padding(20.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (user != null && (model.subscribedAlbumsHasMore || model.subscribedAlbumsLoading || model.subscribedAlbumsFailed)) item("album_paging") {
            CloudPagingFooter(model.subscribedAlbumsLoading, model.subscribedAlbumsFailed) { model.loadMoreSubscribedAlbums() }
        }
    }
}

@Composable
internal fun searchCategoryLabel(category: SearchCategory): String = when (category) {
    SearchCategory.ALL -> tr("search_all", "综合")
    SearchCategory.SONG -> tr("search_song", "单曲")
    SearchCategory.ALBUM -> tr("albums", "专辑")
    SearchCategory.ARTIST -> tr("artists", "艺术家")
    SearchCategory.LYRICS -> tr("lyrics", "歌词")
    SearchCategory.VOICE -> tr("search_voice", "声音")
    SearchCategory.BLOG -> tr("search_blog", "博客")
}

@Composable
internal fun SearchFilters(model: AuraViewModel) {
    val types = if (model.searchCloud) SearchCategory.entries else SearchCategory.entries.take(4)
    val navigationStyle = model.settings.value.navigation
    val material = rememberModuleNavigationMaterial(navigationStyle)
    Column {
        SoftGlassHeaderTabs(listOf(tr("local", "本地"), tr("cloud", "云端")), if (model.searchCloud) 1 else 0,
            navigationStyle = navigationStyle, material = material) { model.changeSearchScope(it == 1) }
        SoftGlassHeaderTabs(types.map { searchCategoryLabel(it) }, types.indexOf(model.searchCategory).coerceAtLeast(0),
            widthScale = 1f, scrollable = true, navigationStyle = navigationStyle, material = material) { model.changeSearchCategory(types[it]) }
    }
}
