// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) Hemanth Savarala and Retro Music contributors.
// HomeFragment, abs_playlists, item_suggestions and HomeAdapter ported to MIUIX Compose.
package btm.m.auraplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Calendar

@Composable
fun HomePage(model: AuraViewModel, padding: PaddingValues, requestPermission: () -> Unit) {
    val songs = model.songs
    val revision = model.history.revision
    val library by produceState(HomeLibrary(), songs, revision) {
        value = withContext(Dispatchers.IO) {
        val favorites = model.history.favorites()
        val lastPlayed = songs.associate { it.key to model.history.recent(it) }
        val counts = songs.associate { it.key to model.history.count(it) }
        val month = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
            HomeLibrary.from(songs, favorites, lastPlayed, counts, month / 1000L, month)
        }
    }
    var suggestionSeed by remember { mutableIntStateOf(0) }
    val suggestions = remember(library.suggestionPool, suggestionSeed) {
        library.suggestionPool.takeIf { it.size > 9 }?.shuffled()?.take(8).orEmpty()
    }
    fun openTracks(key: String, title: String, tracks: List<Song>) {
        model.openCollection(MusicCollection(key, title, "", tracks, tracks.firstOrNull()?.artwork.orEmpty()))
    }
    val historyTitle = tr("history", "最近播放")
    val addedTitle = tr("last_added", "最近添加")
    val mostTitle = tr("most_played", "最常播放")
    val favoriteTitle = tr("favorites", "我的收藏")
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("user") {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(MiuixTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                    if (model.account?.avatar?.isNotBlank() == true) Artwork(model.account!!.avatar, Modifier.fillMaxSize(), circular = true)
                    else Image(MiuixIcons.Regular.Music, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(tr("welcome", "欢迎，"), style = MiuixTheme.textStyles.body2)
                    model.account?.nickname?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (!model.hasPermission) item("permission") { PermissionCard(requestPermission) }
        item("shortcuts") {
            Column(Modifier.padding(top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HomeAction(historyTitle, MiuixIcons.Regular.Recent, Modifier.weight(1f)) { openTracks("recent", historyTitle, library.history) }
                    HomeAction(addedTitle, MiuixIcons.Regular.Add, Modifier.weight(1f)) { openTracks("added", addedTitle, library.added) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HomeAction(mostTitle, MiuixIcons.Regular.TopDownloads, Modifier.weight(1f)) { openTracks("most", mostTitle, library.mostPlayed) }
                    HomeAction(tr("shuffle", "随机播放"), MiuixIcons.Regular.ConvertFile, Modifier.weight(1f)) { model.play(songs, shuffle = true) }
                }
            }
        }
        if (suggestions.size == 8) item("suggestions") {
            Column {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("suggestion_songs", "建议"), Modifier.weight(1f), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { suggestionSeed++ }, modifier = Modifier.size(44.dp)) {
                        Image(MiuixIcons.Regular.Refresh, tr("refresh", "刷新"), Modifier.size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
                    }
                }
                // Original 2:1:2 mosaic: a large mix tile, two small covers, one large cover, five bottom covers.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                val small = (maxWidth - 16.dp) / 5
                val large = small * 2 + 4.dp
                Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val uiStyle = LocalAppUiStyle.current
                    val scheme = if (uiStyle == AppUiStyle.MATERIAL) rememberActiveExpressiveScheme() else null
                    val onePalette = if (uiStyle == AppUiStyle.ONE) rememberActiveOneUiPalette() else null
                    val tileCorner = when (uiStyle) {
                        AppUiStyle.ONE -> 26.dp
                        AppUiStyle.MATERIAL -> 22.dp
                        AppUiStyle.HYPER -> 16.dp
                    }
                    val tileBg = when (uiStyle) {
                        AppUiStyle.ONE -> Color(onePalette!!.chip)
                        AppUiStyle.MATERIAL -> scheme!!.primaryContainer
                        AppUiStyle.HYPER -> MiuixTheme.colorScheme.primary.copy(alpha = .12f)
                    }
                    val tileFg = when (uiStyle) {
                        AppUiStyle.ONE -> Color(onePalette!!.onChip)
                        AppUiStyle.MATERIAL -> scheme!!.onPrimaryContainer
                        AppUiStyle.HYPER -> MiuixTheme.colorScheme.primary
                    }
                    Box(Modifier.size(large).clip(RoundedCornerShape(tileCorner))
                        .background(tileBg)
                        .clickable { model.playNext(suggestions) }.padding(if (uiStyle != AppUiStyle.HYPER) 12.dp else 8.dp), contentAlignment = Alignment.CenterStart) {
                        Text(tr("new_music_mix", "新歌混烧"), color = tileFg,
                            fontWeight = FontWeight.Bold, fontSize = if (uiStyle != AppUiStyle.HYPER) 22.sp else 24.sp)
                    }
                    Column(Modifier.width(small), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SuggestionCover(suggestions[0], model, Modifier.fillMaxWidth().aspectRatio(1f))
                        SuggestionCover(suggestions[2], model, Modifier.fillMaxWidth().aspectRatio(1f))
                    }
                    SuggestionCover(suggestions[1], model, Modifier.size(large))
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    suggestions.drop(3).forEach { song -> SuggestionCover(song, model, Modifier.size(small)) }
                }
                }
                }
            }
        }
        // Original Repository.homeSections order. Empty sections are omitted.
        homeCollections(tr("top_artists", "热门艺术家"), library.topArtists, artists = true, model = model)
        homeCollections(tr("top_albums", "热门专辑"), library.topAlbums, artists = false, model = model)
        homeCollections(tr("recent_artists", "最近艺术家"), library.recentArtists, artists = true, model = model)
        homeCollections(tr("recent_albums", "最近的专辑"), library.recentAlbums, artists = false, model = model)
        if (library.favorites.isNotEmpty()) {
            item("favorites_title") {
                HomeSectionHeader(favoriteTitle) { openTracks("favorites", favoriteTitle, library.favorites) }
            }
            item("favorites_content") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(library.favorites, key = { it.key }) { song ->
                        Column(Modifier.width(LocalAuraLayout.current.homeTileWidth).clip(RoundedCornerShape(14.dp)).clickable { model.play(library.favorites, library.favorites.indexOf(song)) }) {
                            Artwork(song.artwork, Modifier.fillMaxWidth().aspectRatio(.82f))
                            Text(song.title, Modifier.padding(12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body1)
                        }
                    }
                }
            }
        }
        if (model.scanning) item("scanning") { Text(tr("scanning", "正在扫描…"), style = MiuixTheme.textStyles.body2) }
        else if (model.hasPermission && songs.isEmpty()) item("empty") {
            EmptyState(tr("empty_library", "音乐库还是空的"), tr("empty_library_summary", "将音乐放入手机后，点击右上角重新扫描"), tr("rescan", "重新扫描"), { model.refreshLibrary() })
        }
    }
}

@Composable
private fun HomeAction(title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    if (LocalAppUiStyle.current == AppUiStyle.ONE) {
        val palette = rememberActiveOneUiPalette()
        Row(
            modifier = modifier
                .heightIn(min = 58.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(palette.card))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Color(palette.chip)),
                contentAlignment = Alignment.Center,
            ) {
                Image(icon, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color(palette.onChip)))
            }
            Text(
                text = title,
                color = Color(palette.text),
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        return
    }
    if (LocalAppUiStyle.current == AppUiStyle.MATERIAL) {
        val scheme = rememberActiveExpressiveScheme()
        Row(
            modifier = modifier
                .heightIn(min = 58.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(scheme.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape).background(scheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Image(icon, null, Modifier.size(19.dp), colorFilter = ColorFilter.tint(scheme.onSecondaryContainer))
            }
            Text(
                text = title,
                color = scheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        return
    }
    Row(modifier.heightIn(min = 50.dp).clip(RoundedCornerShape(16.dp))
        .background(MiuixTheme.colorScheme.surfaceContainer).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(icon, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
        Text(title, Modifier.padding(start = 12.dp), style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SuggestionCover(song: Song, model: AuraViewModel, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).clickable { model.playNext(listOf(song)) }) {
        Artwork(song.artwork, Modifier.fillMaxSize())
    }
}

private fun LazyListScope.homeCollections(title: String, collections: List<MusicCollection>, artists: Boolean, model: AuraViewModel) {
    if (collections.isEmpty()) return
    item("${title}_title") { HomeSectionHeader(title) { model.openDirectory(MusicDirectory(title, collections, artists)) } }
    item("${title}_content") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(collections.take(5), key = { it.key }) { collection -> HomeCollection(collection, artists) { model.openCollection(collection) } }
        }
    }
}

@Composable
private fun HomeSectionHeader(title: String, onClick: () -> Unit) {
    if (LocalAppUiStyle.current == AppUiStyle.ONE) {
        val palette = rememberActiveOneUiPalette()
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), color = Color(palette.text), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Image(MiuixIcons.Regular.ChevronForward, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color(palette.accentDeep)))
        }
        return
    }
    if (LocalAppUiStyle.current == AppUiStyle.MATERIAL) {
        val scheme = rememberActiveExpressiveScheme()
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), color = scheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Box(
                modifier = Modifier.size(30.dp).clip(CircleShape).background(scheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Image(MiuixIcons.Regular.ChevronForward, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(scheme.onSecondaryContainer))
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
        Image(MiuixIcons.Regular.ChevronForward, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
    }
}

@Composable
private fun HomeCollection(collection: MusicCollection, artists: Boolean, onClick: () -> Unit) {
    val tile = LocalAuraLayout.current.homeTileWidth
    Column(Modifier.width(tile).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Artwork(collection.artwork, Modifier.size(tile), circular = artists)
            Text(collection.title, Modifier.fillMaxWidth().padding(top = 9.dp, start = if (artists) 0.dp else 9.dp, end = if (artists) 0.dp else 9.dp, bottom = 6.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = if (artists) TextAlign.Center else TextAlign.Start, style = MiuixTheme.textStyles.body1)
    }
}

@Composable
fun DirectoryPage(model: AuraViewModel, padding: PaddingValues) {
    val directory = model.directory ?: return
    val layout = LocalAuraLayout.current
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(directory.collections.chunked(layout.gridColumns)) { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                pair.forEach { collection -> HomeCollection(collection, directory.artists) { model.openCollection(collection) } }
                repeat(layout.gridColumns - pair.size) { Spacer(Modifier.width(layout.homeTileWidth)) }
            }
        }
    }
}
