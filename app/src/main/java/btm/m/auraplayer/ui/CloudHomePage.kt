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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.R
import btm.m.auraplayer.data.CatalogAlbum
import btm.m.auraplayer.data.CloudPlaylist
import btm.m.auraplayer.data.CloudHomeItem
import btm.m.auraplayer.data.HomeBackgroundStyle
import btm.m.auraplayer.data.HomeBannerTarget
import btm.m.auraplayer.data.LocalBannerStyle
import btm.m.auraplayer.data.MusicCollection
import btm.m.auraplayer.data.Song
import btm.m.auraplayer.data.cloudSection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** NetEase home arranged as lightweight, horizontally scrolling sections. */
@Composable
internal fun CloudHomePage(
    model: AuraViewModel,
    padding: PaddingValues,
    login: () -> Unit,
    onRecommended: () -> Unit,
    immersiveTopPadding: Dp = 0.dp,
    onReport: () -> Unit = {},
) {
    val user = model.account
    val settings = model.settings.value
    val bannerConfig = settings.bannerConfig(HomeBannerTarget.NETEASE)
    val styleConfig = bannerConfig.styleConfig(bannerConfig.style)
    val immersiveBanner = (bannerConfig.style == LocalBannerStyle.IMMERSIVE ||
        bannerConfig.style == LocalBannerStyle.IMMERSIVE_GLASS) && immersiveTopPadding > 0.dp
    val pageMargin = LocalAuraLayout.current.pageMargin
    val itemHorizontalPadding = if (immersiveBanner) pageMargin else 0.dp
    val rowContentPadding = if (immersiveBanner) PaddingValues(horizontal = pageMargin) else PaddingValues(0.dp)
    val coloredHomeBg = immersiveBanner && styleConfig.backgroundStyle != HomeBackgroundStyle.DEFAULT
    val isBlurBg = styleConfig.backgroundStyle == HomeBackgroundStyle.COLOR_BLUR
    val activeBannerTheme = if (coloredHomeBg) rememberHomeBannerTheme(bannerConfig.imagePath) else null
    var homeRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(user?.userId, homeRefresh) { model.refreshCloudHome() }
    val recentSongs = model.cloudRecentSongs
    val playlists = model.cloudRecommendedPlaylists.filter { it.section == "recommended" }
    val feature = model.cloudRecommendedPlaylists.filter { it.section == "daily" || it.section == "feature" }
    val radar = model.cloudRecommendedPlaylists.filter { it.section == "radar" }
    val albums = model.cloudHomeItems.filter { it.kind == "album" }
    val singles = key(user?.userId) {
        rememberCloudSingles { refresh ->
            val daily = if (user != null) model.fetchCloudDailySongs(refresh) else emptyList()
            val discovery = daily + if (refresh || daily.size < CloudSingleCount) cloudSection { model.app.api.discoverySongs() }.orEmpty() else emptyList()
            if (discovery.distinctBy { it.key }.size >= CloudSingleCount) discovery
            else {
                val fallback = playlists.ifEmpty { cloudSection { model.app.api.recommendedPlaylists(personalized = user != null) }.orEmpty() }
                discovery + coroutineScope {
                    (if (refresh) fallback.shuffled() else fallback).take(2).map { playlist ->
                        async { cloudSection { model.app.api.playlistSongs(playlist.id, limit = CloudSingleCount).items }.orEmpty() }
                    }.awaitAll().flatten()
                }
            }
        }
    }
    LaunchedEffect(user?.userId) {
        if (user != null && model.roamingPreviewSong == null) {
            model.refreshRoamingPreview()
        }
    }
    val recommendedKeys = remember(singles.tracks) { singles.tracks.mapTo(hashSetOf()) { it.key } }
    val recentDisplaySongs = remember(recentSongs, recommendedKeys) {
        recentSongs.filterNot { it.key in recommendedKeys }.take(CloudRecentCount)
    }
    val spotlightCards = rememberSpotlightCards(model, user != null, feature, radar)
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item("welcome") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeProfileHeader(
                    avatarUri = user?.avatar.orEmpty(),
                    displayName = user?.nickname ?: tr("netease", "网易云音乐"),
                    signature = if (user == null) tr("account_manage", "管理账号与连接") else "",
                    bannerStyle = bannerConfig.style,
                    bannerUri = bannerConfig.imagePath,
                    styleConfig = styleConfig,
                    immersiveTopPadding = immersiveTopPadding,
                    pageMargin = pageMargin,
                    motionPlayMode = bannerConfig.motionPlayMode,
                    onClick = login,
                )
                if (user == null) {
                    Box(Modifier.padding(horizontal = itemHorizontalPadding)) {
                        Preference(tr("login_required", "请先登录网易云音乐"), tr("account_manage", "管理账号与连接"), onClick = login)
                    }
                }
            }
        }
        if (spotlightCards.isNotEmpty()) item("daily_radar") {
            LazyRow(contentPadding = rowContentPadding, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(spotlightCards, key = { it.key }) { card ->
                    CloudSpotlightTile(card, bannerTheme = activeBannerTheme, isBlurBg = isBlurBg)
                }
            }
        }
        item("singles") { CloudSinglesSection(model, singles, horizontalPadding = itemHorizontalPadding, accentOverride = activeBannerTheme?.takeIf { it.isCustom }?.accent) }
        if (model.cloudHomeFailed) item("load_error") {
            Box(Modifier.padding(horizontal = itemHorizontalPadding)) { CloudPagingFooter(false, true) { homeRefresh++ } }
        }
        if (recentDisplaySongs.isNotEmpty()) item("recent") {
            val sectionAccent = activeBannerTheme?.takeIf { it.isCustom }?.accent ?: MiuixTheme.colorScheme.primary
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().padding(horizontal = itemHorizontalPadding)) {
                    CloudHomeHeader(
                        title = tr("recently_listened", "最近常听"),
                        trailing = if (user != null) {
                            {
                                ListeningReportCapsuleButton(
                                    accent = sectionAccent,
                                    bannerTheme = activeBannerTheme,
                                    isBlurBg = isBlurBg,
                                    onClick = onReport,
                                )
                            }
                        } else null,
                    )
                }
                PopularSongStrip(
                    songs = recentDisplaySongs,
                    model = model,
                    margin = itemHorizontalPadding,
                    accent = sectionAccent,
                    subtitle = { it.artist },
                    rowsPerColumn = 3,
                    columnMajor = true,
                ) { index ->
                    model.play(recentDisplaySongs, index)
                }
            }
        }
        if (playlists.isNotEmpty()) item("recommend_title") {
            Box(Modifier.padding(horizontal = itemHorizontalPadding)) {
                CloudHomeHeader(tr("recommended_playlists", "推荐歌单"), arrow = true, accentOverride = activeBannerTheme?.takeIf { it.isCustom }?.accent, onClick = onRecommended)
            }
        }
        if (playlists.isNotEmpty()) item("recommend") { CloudHomeItemRow(playlists.take(6), model, contentPadding = rowContentPadding) }
        if (model.cloudFollowedAlbums.isNotEmpty()) item("follow_title") {
            Box(Modifier.padding(horizontal = itemHorizontalPadding)) {
                CloudHomeHeader(tr("followed_artist_updates", "你关注的艺人的新动向"))
            }
        }
        if (model.cloudFollowedAlbums.isNotEmpty()) item("follow") { CloudHomeItemRow(model.cloudFollowedAlbums.take(8), model, contentPadding = rowContentPadding) }
        if (albums.isNotEmpty()) item("discover_albums") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.padding(horizontal = itemHorizontalPadding)) { CloudHomeHeader(tr("discover_albums", "发现专辑")) }
                CloudHomeItemRow(albums.take(8), model, contentPadding = rowContentPadding)
            }
        }
        if (model.cloudPublicBanners.isNotEmpty()) item("banners_title") {
            Box(Modifier.padding(horizontal = itemHorizontalPadding)) { CloudHomeHeader(tr("netease_banners", "网易云音乐推荐")) }
        }
        if (model.cloudPublicBanners.isNotEmpty()) item("banners") {
            Box(Modifier.padding(horizontal = itemHorizontalPadding)) { CloudHomeBannerStack(model.cloudPublicBanners, model) }
        }
    }
}

private const val CloudSingleCount = 25
private const val CloudRecentCount = 9

private class CloudSinglesState {
    var tracks by mutableStateOf<List<Song>>(emptyList())
    var loading by mutableStateOf(true)
    var refresh by mutableIntStateOf(0)
    val scrollState = androidx.compose.foundation.lazy.LazyListState()
}

@Composable
private fun rememberCloudSingles(load: suspend (Boolean) -> List<Song>): CloudSinglesState {
    val state = remember { CloudSinglesState() }
    val currentLoad by rememberUpdatedState(load)
    LaunchedEffect(state.refresh) {
        val refresh = state.refresh
        state.loading = true
        try {
            val previous = state.tracks.mapTo(hashSetOf()) { it.key }
            val candidates = currentLoad(refresh > 0).distinctBy { it.key }
            if (candidates.isNotEmpty()) {
                state.tracks = (if (refresh == 0) candidates else candidates.shuffled().sortedBy { it.key in previous }).take(CloudSingleCount)
                state.scrollState.requestScrollToItem(0)
            }
        } finally { state.loading = false }
    }
    return state
}

@Composable
private fun CloudSinglesSection(
    model: AuraViewModel,
    state: CloudSinglesState,
    horizontalPadding: Dp = 0.dp,
    accentOverride: Color? = null,
) {
    val tracks = state.tracks
    val loading = state.loading
    val refreshTint = accentOverride ?: MiuixTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = horizontalPadding), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { CloudHomeHeader(tr("recommended_singles", "为你推荐的单曲")) }
            IconButton(onClick = { if (!loading) state.refresh++ }, modifier = Modifier.size(44.dp)) {
                Image(MiuixIcons.Regular.Refresh, tr("refresh", "刷新"), Modifier.size(22.dp),
                    colorFilter = ColorFilter.tint(refreshTint.copy(alpha = if (loading) .45f else 1f)))
            }
        }
        if (tracks.isNotEmpty()) PopularSongStrip(tracks, model, margin = horizontalPadding, accent = refreshTint, subtitle = { it.artist }, scrollState = state.scrollState) { model.play(tracks, it) }
        else Text(if (loading) tr("loading", "加载中…") else tr("no_songs", "暂无歌曲"), Modifier.padding(horizontal = horizontalPadding), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
internal fun QqHomePage(
    model: AuraViewModel,
    padding: PaddingValues,
    login: () -> Unit,
    immersiveTopPadding: Dp = 0.dp,
) {
    val user = model.qqAccount
    val settings = model.settings.value
    val bannerConfig = settings.bannerConfig(HomeBannerTarget.QQ)
    val styleConfig = bannerConfig.styleConfig(bannerConfig.style)
    val immersiveBanner = (bannerConfig.style == LocalBannerStyle.IMMERSIVE ||
        bannerConfig.style == LocalBannerStyle.IMMERSIVE_GLASS) && immersiveTopPadding > 0.dp
    val pageMargin = LocalAuraLayout.current.pageMargin
    val itemHorizontalPadding = if (immersiveBanner) pageMargin else 0.dp
    val rowContentPadding = if (immersiveBanner) PaddingValues(horizontal = pageMargin) else PaddingValues(0.dp)
    val coloredQqBg = immersiveBanner && styleConfig.backgroundStyle != HomeBackgroundStyle.DEFAULT
    val activeBannerTheme = if (coloredQqBg) rememberHomeBannerTheme(bannerConfig.imagePath) else null
    val recommended = model.qqRecommended
    LaunchedEffect(model.app.qqEndpoint, user?.userId) { if (recommended.isEmpty()) model.refreshQq() }
    val singles = if (recommended.isNotEmpty()) key(user?.userId, recommended) {
        rememberCloudSingles { refresh ->
            val playlists = (if (refresh) recommended.shuffled() else recommended).take(3)
            coroutineScope {
                playlists.map { playlist ->
                    async { cloudSection { model.app.qq.playlistSongs(playlist.id).items }.orEmpty().take(15) }
                }.awaitAll().flatten()
            }
        }
    } else null
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item("qq_welcome") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeProfileHeader(
                    avatarUri = qqAccountAvatar(user),
                    displayName = user?.nickname ?: tr("qq_music", "QQ音乐"),
                    signature = if (user != null && user.userId > 0L) "QQ ${user.userId}" else tr("account_manage", "管理账号与连接"),
                    bannerStyle = bannerConfig.style,
                    bannerUri = bannerConfig.imagePath,
                    styleConfig = styleConfig,
                    immersiveTopPadding = immersiveTopPadding,
                    pageMargin = pageMargin,
                    motionPlayMode = bannerConfig.motionPlayMode,
                    onClick = login,
                )
                if (user == null) {
                    Box(Modifier.padding(horizontal = itemHorizontalPadding)) {
                        Preference(tr("qq_login_required", "请先登录 QQ音乐"), tr("account_manage", "管理账号与连接"), onClick = login)
                    }
                }
            }
        }
        if (singles != null) {
            item("qq_singles") { CloudSinglesSection(model, singles, horizontalPadding = itemHorizontalPadding, accentOverride = activeBannerTheme?.takeIf { it.isCustom }?.accent) }
            item("qq_playlists") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.padding(horizontal = itemHorizontalPadding)) {
                        CloudHomeHeader(tr("recommended_playlists", "推荐歌单"))
                    }
                    LazyRow(contentPadding = rowContentPadding, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(recommended.take(12), key = { it.id }) { playlist ->
                            CloudFeatureCard(playlist.name, playlist.artwork, Modifier.width(142.dp)) { model.openCloudPlaylist(playlist) }
                        }
                    }
                }
            }
        } else if (model.qqLoading || model.qqFailed) item("qq_loading") {
            Box(Modifier.padding(horizontal = itemHorizontalPadding)) {
                CloudPagingFooter(model.qqLoading, model.qqFailed, model::refreshQq)
            }
        }
    }
}

@Composable
private fun ListeningReportCapsuleButton(
    accent: Color,
    bannerTheme: HomeBannerTheme?,
    isBlurBg: Boolean,
    onClick: () -> Unit,
) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val customTheme = bannerTheme?.takeIf { it.isCustom }
    val pillBg = when {
        customTheme != null -> if (isBlurBg) {
            customTheme.cardContainer.copy(alpha = if (dark) 0.78f else 0.84f)
        } else {
            customTheme.cardContainer
        }
        LocalAppUiStyle.current == AppUiStyle.MATERIAL -> rememberActiveExpressiveScheme().secondaryContainer
        LocalAppUiStyle.current == AppUiStyle.ONE -> Color(rememberActiveOneUiPalette().chip)
        else -> accent.copy(alpha = if (dark) 0.18f else 0.12f)
    }
    val pillFg = when {
        customTheme != null -> customTheme.accent
        LocalAppUiStyle.current == AppUiStyle.MATERIAL -> rememberActiveExpressiveScheme().onSecondaryContainer
        LocalAppUiStyle.current == AppUiStyle.ONE -> Color(rememberActiveOneUiPalette().onChip)
        else -> accent
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(pillBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Image(
            imageVector = MiuixIcons.Regular.Notes,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            colorFilter = ColorFilter.tint(pillFg),
        )
        Text(
            text = tr("report_short", "报告"),
            color = pillFg,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun CloudHomeHeader(
    title: String,
    arrow: Boolean = false,
    accentOverride: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    if (LocalAppUiStyle.current == AppUiStyle.ONE) {
        val palette = rememberActiveOneUiPalette()
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = arrow, onClick = onClick).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, Modifier.weight(1f), color = Color(palette.text), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            trailing?.invoke()
            if (arrow) Image(MiuixIcons.Regular.ChevronForward, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(accentOverride ?: Color(palette.accentDeep)))
        }
        return
    }
    if (LocalAppUiStyle.current == AppUiStyle.MATERIAL) {
        val scheme = rememberActiveExpressiveScheme()
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = arrow, onClick = onClick).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, Modifier.weight(1f), color = scheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            trailing?.invoke()
            if (arrow) Box(
                modifier = Modifier.size(30.dp).clip(CircleShape).background(scheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Image(MiuixIcons.Regular.ChevronForward, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(accentOverride ?: scheme.onSecondaryContainer))
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(enabled = arrow, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, Modifier.weight(1f), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
        trailing?.invoke()
        if (arrow) Image(MiuixIcons.Regular.ChevronForward, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(accentOverride ?: MiuixTheme.colorScheme.primary))
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

private data class CloudSpotlightCard(
    val key: String,
    val eyebrow: String,
    val title: String,
    val artwork: String,
    val onOpen: () -> Unit,
    val onPlay: () -> Unit,
)

@Composable
private fun rememberSpotlightCards(
    model: AuraViewModel,
    loggedIn: Boolean,
    feature: List<CloudHomeItem>,
    radar: List<CloudHomeItem>,
): List<CloudSpotlightCard> {
    val dailyLabel = tr("daily_recommendation", "每日推荐")
    val radarLabel = tr("radar_playlist", "雷达歌单")
    val featuredLabel = tr("featured_playlist", "精选歌单")
    val todayLabel = tr("today_for_you", "今日精选")
    val neteaseLabel = tr("netease", "网易云音乐")
    val dailySongs = model.cloudDailySongs
    val hasDailyFeature = feature.any { it.section == "daily" }

    return buildList {
        if (!hasDailyFeature && loggedIn && dailySongs.isNotEmpty()) {
            val artwork = dailySongs.firstOrNull { it.artwork.isNotBlank() }?.artwork.orEmpty()
            add(
                CloudSpotlightCard(
                    key = "daily-songs",
                    eyebrow = todayLabel,
                    title = dailyLabel,
                    artwork = artwork,
                    onOpen = { model.openCollection(MusicCollection("cloud:daily", dailyLabel, "", dailySongs, artwork)) },
                    onPlay = { model.play(dailySongs, 0) },
                )
            )
        }
        feature.filter { it.section == "daily" }.take(2).forEach { card ->
            val normalizedTitle = if (card.title.contains("每日推荐") || card.title.equals("Daily 30", ignoreCase = true)) {
                dailyLabel
            } else {
                card.title
            }
            add(
                CloudSpotlightCard(
                    key = "daily-${card.id}",
                    eyebrow = todayLabel,
                    title = normalizedTitle,
                    artwork = card.artwork,
                    onOpen = { model.openCloudHomeItem(card) },
                    onPlay = { model.playCloudHomeItem(card) },
                )
            )
        }
        val roamingSong = model.roamingPreviewSong
        if (loggedIn && roamingSong != null) {
            add(
                CloudSpotlightCard(
                    key = "roaming-mode",
                    eyebrow = tr("roaming_mode", "漫游模式"),
                    title = roamingSong.title,
                    artwork = roamingSong.artwork,
                    onOpen = { model.startRoamingMode() },
                    onPlay = { model.startRoamingMode() },
                )
            )
        }
        radar.take(6).forEach { card ->
            add(
                CloudSpotlightCard(
                    key = "radar-${card.id}",
                    eyebrow = if (card.title == radarLabel) neteaseLabel else radarLabel,
                    title = card.title,
                    artwork = card.artwork,
                    onOpen = { model.openCloudHomeItem(card) },
                    onPlay = { model.playCloudHomeItem(card) },
                )
            )
        }
        feature.filter { it.section != "daily" }.take(2).forEach { card ->
            add(
                CloudSpotlightCard(
                    key = "feature-${card.id}",
                    eyebrow = if (card.title == featuredLabel) neteaseLabel else featuredLabel,
                    title = card.title,
                    artwork = card.artwork,
                    onOpen = { model.openCloudHomeItem(card) },
                    onPlay = { model.playCloudHomeItem(card) },
                )
            )
        }
    }
}

@Composable
private fun CloudSpotlightTile(
    card: CloudSpotlightCard,
    bannerTheme: HomeBannerTheme? = null,
    isBlurBg: Boolean = false,
) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val resolvedTheme = when {
        bannerTheme == null -> null
        bannerTheme.isCustom -> bannerTheme
        else -> rememberHomeBannerTheme(card.artwork).takeIf { it.isCustom }
    }
    val cardColors = if (resolvedTheme != null) {
        val fill = if (isBlurBg) {
            resolvedTheme.cardContainer.copy(alpha = if (dark) 0.72f else 0.78f)
        } else {
            resolvedTheme.cardContainer
        }
        top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = fill)
    } else {
        top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors()
    }
    val eyebrowColor = resolvedTheme?.mutedInk ?: MiuixTheme.colorScheme.onSurfaceVariantSummary
    val playBgColor = resolvedTheme?.accent ?: MiuixTheme.colorScheme.primary
    val playFgColor = resolvedTheme?.onAccent ?: MiuixTheme.colorScheme.onPrimary

    SeamlessCard(
        modifier = Modifier.width(178.dp),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(10.dp),
        colors = cardColors,
        showIndication = true,
        onClick = card.onOpen,
    ) {
        Artwork(
            uri = card.artwork,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            cornerRadius = 13.dp,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = card.eyebrow,
                    color = eyebrowColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = card.title,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(playBgColor)
                    .clickable(onClick = card.onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_player_play),
                    contentDescription = tr("play", "播放"),
                    modifier = Modifier.size(13.dp),
                    colorFilter = ColorFilter.tint(playFgColor),
                )
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
private fun CloudHomeItemRow(
    items: List<CloudHomeItem>,
    model: AuraViewModel,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyRow(contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
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
