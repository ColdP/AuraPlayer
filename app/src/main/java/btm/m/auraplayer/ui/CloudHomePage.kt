// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.CatalogAlbum
import btm.m.auraplayer.data.CloudPlaylist
import btm.m.auraplayer.data.CloudHomeItem
import btm.m.auraplayer.data.Song
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** NetEase home arranged as lightweight, horizontally scrolling sections. */
@Composable
internal fun CloudHomePage(model: AuraViewModel, padding: PaddingValues, login: () -> Unit, onRecommended: () -> Unit) {
    val user = model.account
    LaunchedEffect(user?.userId) { if (user != null) model.refreshCloudHome() }
    val recentSongs = model.cloudRecentSongs
    val playlists = model.cloudRecommendedPlaylists.filter { it.section == "recommended" }
    val feature = model.cloudRecommendedPlaylists.filter { it.section == "daily" || it.section == "feature" }
    val radar = model.cloudRecommendedPlaylists.filter { it.section == "radar" }
    val daily = feature
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item("welcome") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).background(MiuixTheme.colorScheme.surfaceContainer)) {
                    if (user?.avatar?.isNotBlank() == true) Artwork(user.avatar, Modifier.fillMaxSize(), circular = true)
                    else Image(MiuixIcons.Regular.Music, null, Modifier.align(Alignment.Center).size(25.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(tr("welcome", "欢迎，"), style = MiuixTheme.textStyles.body2)
                    Text(user?.nickname ?: tr("netease", "网易云音乐"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                }
            }
            if (user == null) Preference(tr("login_required", "请先登录网易云音乐"), tr("account_manage", "管理账号与连接"), onClick = login)
        }
        if (daily.isNotEmpty()) item("daily") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                daily.take(4).forEach { card -> item { CloudFeatureCard(card.title, card.artwork, Modifier.width(142.dp)) { model.openCloudHomeItem(card) } } }
            }
        }
        if (recentSongs.isNotEmpty()) item("recent_title") { CloudHomeHeader(tr("recently_listened", "最近常听")) }
        if (recentSongs.isNotEmpty()) item("recent") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recentSongs.take(7), key = { it.key }) { song ->
                    CloudRecentCard(song, model)
                }
            }
        }
        if (playlists.isNotEmpty()) item("recommend_title") { CloudHomeHeader(tr("recommended_playlists", "推荐歌单"), arrow = true, onClick = onRecommended) }
        if (playlists.isNotEmpty()) item("recommend") { CloudHomeItemRow(playlists.take(6), model) }
        if (radar.isNotEmpty()) item("radar_title") { CloudHomeHeader(user?.let { tr("user_radar", "${it.nickname}的雷达歌单") } ?: tr("user_radar_generic", "我的雷达歌单")) }
        if (radar.isNotEmpty()) item("radar") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                radar.take(5).forEach { card -> item { CloudFeatureCard(card.title, card.artwork, Modifier.width(142.dp)) { model.openCloudHomeItem(card) } } }
            }
        }
        if (model.cloudFollowedAlbums.isNotEmpty()) item("follow_title") { CloudHomeHeader(tr("followed_artist_updates", "你关注的艺人的新动向")) }
        if (model.cloudFollowedAlbums.isNotEmpty()) item("follow") { CloudHomeItemRow(model.cloudFollowedAlbums.take(8), model) }
        if (model.cloudPublicBanners.isNotEmpty()) item("banners_title") { CloudHomeHeader(tr("netease_banners", "网易云音乐推荐")) }
        if (model.cloudPublicBanners.isNotEmpty()) item("banners") { CloudHomeBannerStack(model.cloudPublicBanners, model) }
    }
}

@Composable
private fun CloudHomeHeader(title: String, arrow: Boolean = false, onClick: () -> Unit = {}) {
    if (LocalAppUiStyle.current == AppUiStyle.ONE) {
        val palette = rememberActiveOneUiPalette()
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = arrow, onClick = onClick).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), color = Color(palette.text), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            if (arrow) Image(MiuixIcons.Regular.ChevronForward, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color(palette.accentDeep)))
        }
        return
    }
    if (LocalAppUiStyle.current == AppUiStyle.MATERIAL) {
        val scheme = rememberActiveExpressiveScheme()
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = arrow, onClick = onClick).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), color = scheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (arrow) Box(
                modifier = Modifier.size(30.dp).clip(CircleShape).background(scheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Image(MiuixIcons.Regular.ChevronForward, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(scheme.onSecondaryContainer))
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
        if (arrow) Image(MiuixIcons.Regular.ChevronForward, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
    }
}

@Composable
internal fun CloudRecommendedPage(model: AuraViewModel, padding: PaddingValues) {
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(tr("recommended_playlists", "推荐歌单"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold) }
        items(model.cloudRecommendedPlaylists.filter { it.section == "recommended" }, key = { "recommended-${it.id}" }) { item ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer).clickable { model.openCloudHomeItem(item) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(item.artwork, Modifier.size(64.dp), cornerRadius = 12.dp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    if (item.subtitle.isNotBlank()) Text(item.subtitle, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
                }
                Image(MiuixIcons.Regular.ChevronForward, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
private fun CloudFeatureCard(title: String, artwork: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(174.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)) {
        Artwork(artwork, Modifier.fillMaxSize(), cornerRadius = 18.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = .78f)))))
        Text(title, Modifier.align(Alignment.BottomStart).padding(13.dp), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CloudRecentCard(song: Song, model: AuraViewModel) {
    Column(Modifier.width(124.dp).clip(RoundedCornerShape(16.dp)).clickable { model.play(listOf(song)) }.padding(8.dp)) {
        Artwork(song.artwork, Modifier.fillMaxWidth().aspectRatio(1f), cornerRadius = 12.dp)
        Text(song.title, Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body2)
        Text(song.artist, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CloudHomeItemRow(items: List<CloudHomeItem>, model: AuraViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        items(items, key = { "home-${it.kind}-${it.id}" }) { item ->
            Column(Modifier.width(124.dp).clickable { model.openCloudHomeItem(item) }) {
                Artwork(item.artwork, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(item.title, Modifier.padding(top = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body2)
                if (item.subtitle.isNotBlank()) Text(item.subtitle, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CloudHomeBannerStack(items: List<CloudHomeItem>, model: AuraViewModel) {
    if (items.isEmpty()) return
    val scope = rememberCoroutineScope()
    val drag = remember { androidx.compose.animation.core.Animatable(0f) }
    var index by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(146.dp)) {
        val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() * 3f }
        Box(Modifier.fillMaxSize().pointerInput(items) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { _, delta -> scope.launch { drag.snapTo((drag.value + delta).coerceIn(-widthPx, widthPx)) } },
                onDragEnd = {
                    scope.launch {
                        val direction = if (drag.value < -56f) 1 else if (drag.value > 56f) -1 else 0
                        if (direction != 0) {
                            drag.animateTo(direction * widthPx, tween(180))
                            index = (index + direction + items.size) % items.size
                        }
                        drag.animateTo(0f, tween(220))
                    }
                },
            )
        }) {
            val next = items[(index + 1) % items.size]
            val current = items[index]
            CloudHomeBannerCard(next, Modifier.align(Alignment.Center).fillMaxWidth().height(146.dp).graphicsLayer { alpha = .68f; scaleX = .94f; scaleY = .94f }.blur(8.dp))
            CloudHomeBannerCard(current, Modifier.align(Alignment.Center).fillMaxWidth().height(146.dp).clickable { model.openCloudHomeItem(current) }.graphicsLayer {
                translationX = drag.value
                alpha = 1f - (kotlin.math.abs(drag.value) / widthPx).coerceIn(0f, 1f) * .18f
            }.blur((kotlin.math.abs(drag.value) / widthPx * 8f).dp))
        }
    }
}

@Composable
private fun CloudHomeBannerCard(item: CloudHomeItem, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(18.dp))) {
        Artwork(item.artwork, Modifier.fillMaxSize(), cornerRadius = 18.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f)))))
        Text(item.title, Modifier.align(Alignment.BottomStart).padding(14.dp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CloudPlaylistRow(playlists: List<CloudPlaylist>, model: AuraViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        items(playlists, key = { it.id }) { playlist ->
            Column(Modifier.width(124.dp).clickable { model.openCloudPlaylist(playlist) }) {
                Artwork(playlist.artwork, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(playlist.name, Modifier.padding(top = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body2)
            }
        }
    }
}

@Composable
private fun CloudAlbumRow(albums: List<CatalogAlbum>, model: AuraViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        items(albums, key = { it.id }) { album ->
            Column(Modifier.width(124.dp).clickable { model.openCatalogAlbum(album) }) {
                Artwork(album.artwork, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(album.title, Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body2)
                Text(album.artist, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
