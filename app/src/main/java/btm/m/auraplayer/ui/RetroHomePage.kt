// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) Hemanth Savarala and Retro Music contributors.
// HomeFragment, abs_playlists, item_suggestions and HomeAdapter ported to MIUIX Compose.
package btm.m.auraplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.*
import coil3.compose.AsyncImage
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop as miuixLayerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop as rememberMiuixLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Calendar

@Composable
fun HomePage(
    model: AuraViewModel,
    padding: PaddingValues,
    requestPermission: () -> Unit,
    immersiveTopPadding: Dp = 0.dp,
) {
    val songs = model.songs
    val settings = model.settings.value
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
    val immersiveBanner = (settings.localBannerStyle == LocalBannerStyle.IMMERSIVE ||
        settings.localBannerStyle == LocalBannerStyle.IMMERSIVE_GLASS) && immersiveTopPadding > 0.dp
    val pageMargin = LocalAuraLayout.current.pageMargin
    val itemHorizontalPadding = if (immersiveBanner) pageMargin else 0.dp
    val rowContentPadding = if (immersiveBanner) PaddingValues(horizontal = pageMargin) else PaddingValues(0.dp)
    val avatarUri = settings.localAvatarPath.ifBlank { if (model.cloudEnabled) model.account?.avatar.orEmpty() else "" }
    val displayName = settings.localUserName.ifBlank { if (model.cloudEnabled) model.account?.nickname.orEmpty() else "" }
    val styleConfig = settings.bannerStyleConfig(settings.localBannerStyle)
    val darkSurface = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val coloredHomeBg = immersiveBanner && styleConfig.backgroundStyle != HomeBackgroundStyle.DEFAULT
    val isBlurBg = styleConfig.backgroundStyle == HomeBackgroundStyle.COLOR_BLUR
    val activeHomeTheme = if (coloredHomeBg) rememberHomeBannerTheme(settings.localBannerPath).takeIf { it.isCustom } else null
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("user") {
            HomeProfileHeader(
                avatarUri = avatarUri,
                displayName = displayName,
                signature = settings.localHomeSignature,
                bannerStyle = settings.localBannerStyle,
                bannerUri = settings.localBannerPath,
                styleConfig = styleConfig,
                motionPlayMode = settings.localBannerMotionPlayMode,
                immersiveTopPadding = immersiveTopPadding,
                pageMargin = pageMargin,
            )
        }
        if (!model.hasPermission) item("permission") {
            Box(Modifier.padding(horizontal = itemHorizontalPadding)) { PermissionCard(requestPermission) }
        }
        item("shortcuts") {
            Column(Modifier.padding(horizontal = itemHorizontalPadding).padding(top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HomeAction(historyTitle, MiuixIcons.Regular.Recent, Modifier.weight(1f), activeHomeTheme, isBlurBg) { openTracks("recent", historyTitle, library.history) }
                    HomeAction(addedTitle, MiuixIcons.Regular.Add, Modifier.weight(1f), activeHomeTheme, isBlurBg) { openTracks("added", addedTitle, library.added) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HomeAction(mostTitle, MiuixIcons.Regular.TopDownloads, Modifier.weight(1f), activeHomeTheme, isBlurBg) { openTracks("most", mostTitle, library.mostPlayed) }
                    HomeAction(tr("shuffle", "随机播放"), MiuixIcons.Regular.ConvertFile, Modifier.weight(1f), activeHomeTheme, isBlurBg) { model.play(songs, shuffle = true) }
                }
            }
        }
        if (suggestions.size == 8) item("suggestions") {
            Column(Modifier.padding(horizontal = itemHorizontalPadding)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("suggestion_songs", "建议"), Modifier.weight(1f), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { suggestionSeed++ }, modifier = Modifier.size(44.dp)) {
                        Image(MiuixIcons.Regular.Refresh, tr("refresh", "刷新"), Modifier.size(22.dp), colorFilter = ColorFilter.tint(activeHomeTheme?.accent ?: MiuixTheme.colorScheme.primary))
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
                    val tileBg = when {
                        activeHomeTheme != null -> if (isBlurBg) {
                            activeHomeTheme.cardContainer.copy(alpha = if (darkSurface) 0.72f else 0.78f)
                        } else {
                            activeHomeTheme.cardContainer
                        }
                        uiStyle == AppUiStyle.ONE -> Color(onePalette!!.chip)
                        uiStyle == AppUiStyle.MATERIAL -> scheme!!.primaryContainer
                        else -> MiuixTheme.colorScheme.primary.copy(alpha = .12f)
                    }
                    val tileFg = when {
                        activeHomeTheme != null -> activeHomeTheme.accent
                        uiStyle == AppUiStyle.ONE -> Color(onePalette!!.onChip)
                        uiStyle == AppUiStyle.MATERIAL -> scheme!!.onPrimaryContainer
                        else -> MiuixTheme.colorScheme.primary
                    }
                    Box(Modifier.size(large).clip(RoundedCornerShape(tileCorner))
                        .background(tileBg)
                        .clickable { model.playNext(suggestions) }.padding(if (uiStyle != AppUiStyle.HYPER) 12.dp else 8.dp), contentAlignment = Alignment.CenterStart) {
                        Text(tr("new_music_mix", "新歌混烧"), color = tileFg,
                            fontWeight = FontWeight.Bold, fontSize = if (uiStyle != AppUiStyle.HYPER) 22.sp else 24.sp)
                    }
                    Column(Modifier.width(small), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SuggestionCover(suggestions[0], { model.play(suggestions, 0) }, Modifier.fillMaxWidth().aspectRatio(1f))
                        SuggestionCover(suggestions[2], { model.play(suggestions, 2) }, Modifier.fillMaxWidth().aspectRatio(1f))
                    }
                    SuggestionCover(suggestions[1], { model.play(suggestions, 1) }, Modifier.size(large))
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    suggestions.drop(3).forEachIndexed { offset, song ->
                        SuggestionCover(song, { model.play(suggestions, 3 + offset) }, Modifier.size(small))
                    }
                }
                }
                }
            }
        }
        // Original Repository.homeSections order. Empty sections are omitted.
        homeCollections(tr("top_artists", "热门艺术家"), library.topArtists, artists = true, model = model, horizontalPadding = itemHorizontalPadding, rowContentPadding = rowContentPadding)
        homeCollections(tr("top_albums", "热门专辑"), library.topAlbums, artists = false, model = model, horizontalPadding = itemHorizontalPadding, rowContentPadding = rowContentPadding)
        homeCollections(tr("recent_artists", "最近艺术家"), library.recentArtists, artists = true, model = model, horizontalPadding = itemHorizontalPadding, rowContentPadding = rowContentPadding)
        homeCollections(tr("recent_albums", "最近的专辑"), library.recentAlbums, artists = false, model = model, horizontalPadding = itemHorizontalPadding, rowContentPadding = rowContentPadding)
        if (library.favorites.isNotEmpty()) {
            item("favorites_title") {
                Box(Modifier.padding(horizontal = itemHorizontalPadding)) {
                    HomeSectionHeader(favoriteTitle) { openTracks("favorites", favoriteTitle, library.favorites) }
                }
            }
            item("favorites_content") {
                LazyRow(contentPadding = rowContentPadding, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(library.favorites, key = { it.key }) { song ->
                        Column(Modifier.width(LocalAuraLayout.current.homeTileWidth).clip(RoundedCornerShape(14.dp)).clickable { model.play(library.favorites, library.favorites.indexOf(song)) }) {
                            Artwork(song.artwork, Modifier.fillMaxWidth().aspectRatio(.82f))
                            Text(song.title, Modifier.padding(12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body1)
                        }
                    }
                }
            }
        }
        if (model.scanning) item("scanning") { Text(tr("scanning", "正在扫描…"), Modifier.padding(horizontal = itemHorizontalPadding), style = MiuixTheme.textStyles.body2) }
        else if (model.hasPermission && songs.isEmpty()) item("empty") {
            Box(Modifier.padding(horizontal = itemHorizontalPadding)) {
                EmptyState(tr("empty_library", "音乐库还是空的"), tr("empty_library_summary", "将音乐放入手机后，点击右上角重新扫描"), tr("rescan", "重新扫描"), { model.refreshLibrary() })
            }
        }
    }
}

@Composable
internal fun HomeProfileHeader(
    avatarUri: String,
    displayName: String,
    signature: String,
    bannerStyle: LocalBannerStyle,
    bannerUri: String,
    styleConfig: LocalBannerStyleConfig,
    immersiveTopPadding: Dp,
    pageMargin: Dp,
    motionPlayMode: BannerMotionPlayMode = BannerMotionPlayMode.LOOP,
    onClick: (() -> Unit)? = null,
) {
    val motionInfo by produceState(MotionPhotoInfo(), bannerUri) {
        value = MotionPhotoSupport.inspectAsync(bannerUri)
    }
    val supportsMotionBackdrop = bannerStyle == LocalBannerStyle.ROUNDED_CARD ||
        bannerStyle == LocalBannerStyle.IMMERSIVE ||
        bannerStyle == LocalBannerStyle.IMMERSIVE_GLASS ||
        bannerStyle == LocalBannerStyle.GLASS_POSTER
    val canReplayMotion = supportsMotionBackdrop &&
        bannerUri.isNotBlank() &&
        motionInfo.isDynamic &&
        motionPlayMode != BannerMotionPlayMode.OFF
    var replayNonce by remember(bannerUri, motionPlayMode) { mutableIntStateOf(0) }
    val effectiveOnClick: (() -> Unit)? = if (canReplayMotion) {
        { replayNonce++ }
    } else {
        onClick
    }
    val clickModifier = if (effectiveOnClick != null) Modifier.clickable(onClick = effectiveOnClick) else Modifier
    val layout = LocalAuraLayout.current
    val bannerTheme = rememberHomeBannerTheme(bannerUri)
    val adaptiveTextColors = rememberAdaptiveBannerTextColors(
        bannerUri = bannerUri,
        bannerStyle = bannerStyle,
        styleConfig = styleConfig,
        bannerTheme = bannerTheme,
    )
    val adaptiveHeight = resolveAdaptiveBannerHeightDp(
        style = bannerStyle,
        configuredHeightDp = styleConfig.heightDp,
        viewportHeightDp = layout.height,
        isLandscape = layout.landscape,
        isTablet = layout.tablet,
        immersiveTopPaddingDp = immersiveTopPadding.value,
    )
    when (bannerStyle) {
        LocalBannerStyle.NONE -> HomeProfileRow(
            avatarUri = avatarUri,
            displayName = displayName,
            signature = signature,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp).then(clickModifier),
        )
        LocalBannerStyle.ROUNDED_CARD -> {
            val darkBanner = bannerUri.isNotBlank()
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp)
                    .heightIn(min = adaptiveHeight)
                    .clip(RoundedCornerShape(22.5.dp))
                    .then(clickModifier),
            ) {
                HomeBannerBackdrop(
                    bannerUri = bannerUri,
                    motionPlayMode = motionPlayMode,
                    replayNonce = replayNonce,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            if (darkBanner) {
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.32f to Color.Black.copy(alpha = 0.16f),
                                    1f to Color.Black.copy(alpha = 0.72f),
                                )
                            } else {
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    1f to MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                )
                            },
                        ),
                )
                HomeProfileRow(
                    avatarUri = avatarUri,
                    displayName = displayName,
                    signature = signature,
                    titleColor = adaptiveTextColors.titleColor,
                    subtitleColor = adaptiveTextColors.subtitleColor,
                    avatarRingColor = adaptiveTextColors.avatarRingColor,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),
                )
            }
        }
        LocalBannerStyle.IMMERSIVE -> {
            val topPad = if (immersiveTopPadding > 0.dp) immersiveTopPadding + 10.dp else 18.dp
            val horizontalPad = if (immersiveTopPadding > 0.dp) pageMargin else 0.dp
            Box(Modifier.fillMaxWidth().heightIn(min = adaptiveHeight).then(clickModifier)) {
                ImmersiveHomeBannerBackdrop(
                    bannerUri = bannerUri,
                    motionPlayMode = motionPlayMode,
                    replayNonce = replayNonce,
                    backgroundStyle = styleConfig.backgroundStyle,
                    modifier = Modifier.matchParentSize(),
                )
                HomeProfileRow(
                    avatarUri = avatarUri,
                    displayName = displayName,
                    signature = signature,
                    titleColor = adaptiveTextColors.titleColor,
                    subtitleColor = adaptiveTextColors.subtitleColor,
                    avatarRingColor = adaptiveTextColors.avatarRingColor,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(top = topPad, start = horizontalPad, end = horizontalPad, bottom = 26.dp),
                )
            }
        }
        LocalBannerStyle.IMMERSIVE_GLASS -> {
            val surface = MiuixTheme.colorScheme.surface
            val dark = surface.luminance() < .5f
            val glassBackdrop = rememberLayerBackdrop { drawRect(surface); drawContent() }
            val topPad = if (immersiveTopPadding > 0.dp) immersiveTopPadding + 14.dp else 18.dp
            val horizontalPad = if (immersiveTopPadding > 0.dp) pageMargin else 0.dp
            val cardShape = RoundedCornerShape(resolveLocalBannerGlassCorner(22.dp, styleConfig.glassCorner))
            Box(Modifier.fillMaxWidth().heightIn(min = adaptiveHeight).then(clickModifier)) {
                ImmersiveHomeBannerBackdrop(
                    bannerUri = bannerUri,
                    motionPlayMode = motionPlayMode,
                    replayNonce = replayNonce,
                    backgroundStyle = styleConfig.backgroundStyle,
                    modifier = Modifier.matchParentSize().layerBackdrop(glassBackdrop),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(top = topPad, start = horizontalPad, end = horizontalPad, bottom = 22.dp)
                        .clip(cardShape)
                        .then(
                            homeLiquidGlassModifier(
                                backdrop = glassBackdrop,
                                shape = cardShape,
                                surface = surface,
                                dark = dark,
                                config = styleConfig,
                            ),
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    HomeProfileRow(
                        avatarUri = avatarUri,
                        displayName = displayName,
                        signature = signature,
                        titleColor = adaptiveTextColors.titleColor,
                        subtitleColor = adaptiveTextColors.subtitleColor,
                        avatarRingColor = adaptiveTextColors.avatarRingColor,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        LocalBannerStyle.GLASS_POSTER -> {
            val surface = MiuixTheme.colorScheme.surface
            val dark = surface.luminance() < .5f
            val posterBackdrop = rememberLayerBackdrop { drawRect(surface); drawContent() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp)
                    .heightIn(min = adaptiveHeight)
                    .clip(RoundedCornerShape(24.dp))
                    .then(clickModifier),
            ) {
                Box(Modifier.matchParentSize().layerBackdrop(posterBackdrop)) {
                    HomeBannerBackdrop(
                        bannerUri = bannerUri,
                        motionPlayMode = motionPlayMode,
                        replayNonce = replayNonce,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                val dockShape = RoundedCornerShape(resolveLocalBannerGlassCorner(18.dp, styleConfig.glassCorner))
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(top = 12.dp, start = 10.dp, end = 10.dp, bottom = 10.dp)
                        .clip(dockShape)
                        .then(
                            homeLiquidGlassModifier(
                                backdrop = posterBackdrop,
                                shape = dockShape,
                                surface = surface,
                                dark = dark,
                                config = styleConfig,
                            ),
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    HomeProfileRow(
                        avatarUri = avatarUri,
                        displayName = displayName,
                        signature = signature,
                        titleColor = adaptiveTextColors.titleColor,
                        subtitleColor = adaptiveTextColors.subtitleColor,
                        avatarRingColor = adaptiveTextColors.avatarRingColor,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        LocalBannerStyle.AURORA_AMBIENT -> {
            val surface = MiuixTheme.colorScheme.surfaceContainer
            val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
            val auraStart = bannerTheme.auraStart
            val auraEnd = bannerTheme.auraEnd
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp)
                    .heightIn(min = adaptiveHeight)
                    .clip(RoundedCornerShape(22.5.dp))
                    .background(surface)
                    .then(clickModifier),
                contentAlignment = Alignment.Center,
            ) {
                if (bannerUri.isNotBlank()) {
                    AsyncImage(
                        model = bannerUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = if (dark) 0.34f else 0.28f; scaleX = 1.12f; scaleY = 1.12f }
                            .blur(28.dp),
                    )
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    auraStart.copy(alpha = 0.45f),
                                    auraEnd.copy(alpha = 0.28f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HomeProfileRow(
                        avatarUri = avatarUri,
                        displayName = displayName,
                        signature = signature,
                        titleColor = adaptiveTextColors.titleColor,
                        subtitleColor = adaptiveTextColors.subtitleColor,
                        avatarRingColor = adaptiveTextColors.avatarRingColor,
                        modifier = Modifier.weight(1f),
                    )
                    if (bannerUri.isNotBlank()) {
                        AsyncImage(
                            model = bannerUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(56.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                        )
                    }
                }
            }
        }
    }
}

internal fun resolveAdaptiveBannerHeightDp(
    style: LocalBannerStyle,
    configuredHeightDp: Int,
    viewportHeightDp: Float,
    isLandscape: Boolean,
    isTablet: Boolean,
    immersiveTopPaddingDp: Float,
): Dp {
    if (style == LocalBannerStyle.NONE) return 0.dp
    val clamped = configuredHeightDp.coerceIn(LOCAL_BANNER_HEIGHT_DP_RANGE).toFloat()
    val viewportCap = when {
        isLandscape -> (viewportHeightDp * 0.56f).coerceAtLeast(120f)
        isTablet -> (viewportHeightDp * 0.42f).coerceAtLeast(220f)
        else -> (viewportHeightDp * 0.52f).coerceAtLeast(240f)
    }
    val styleMinFloor = when (style) {
        LocalBannerStyle.NONE -> 0f
        LocalBannerStyle.IMMERSIVE -> immersiveTopPaddingDp + 76f
        LocalBannerStyle.IMMERSIVE_GLASS -> immersiveTopPaddingDp + 96f
        LocalBannerStyle.GLASS_POSTER -> 124f
        LocalBannerStyle.ROUNDED_CARD -> 100f
        LocalBannerStyle.AURORA_AMBIENT -> 80f
    }
    val effectiveMax = maxOf(viewportCap, styleMinFloor)
    val scaled = when {
        isLandscape && (style == LocalBannerStyle.IMMERSIVE || style == LocalBannerStyle.IMMERSIVE_GLASS) -> clamped * 0.85f
        isLandscape -> clamped * 0.82f
        else -> clamped
    }
    return scaled.coerceIn(styleMinFloor, effectiveMax).dp
}

@Composable
private fun ImmersiveHomeBannerBackdrop(
    bannerUri: String,
    motionPlayMode: BannerMotionPlayMode,
    replayNonce: Int = 0,
    backgroundStyle: HomeBackgroundStyle,
    modifier: Modifier = Modifier,
) {
    val bannerTheme = rememberHomeBannerTheme(bannerUri)
    ImmersiveBannerSurfaceBackdrop(
        bannerTheme = bannerTheme,
        backgroundStyle = backgroundStyle,
        modifier = modifier,
    ) {
        HomeBannerBackdrop(
            bannerUri = bannerUri,
            motionPlayMode = motionPlayMode,
            replayNonce = replayNonce,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun ImmersiveBannerSurfaceBackdrop(
    bannerTheme: HomeBannerTheme,
    backgroundStyle: HomeBackgroundStyle = HomeBackgroundStyle.COLOR,
    modifier: Modifier = Modifier,
    backdropContent: @Composable BoxScope.() -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surface
    val dark = surface.luminance() < .5f
    val accentMix = bannerTheme.heroMixTint
    val fadeTarget = when (backgroundStyle) {
        HomeBackgroundStyle.DEFAULT -> surface
        HomeBackgroundStyle.COLOR -> bannerTheme.surfaceColor
        HomeBackgroundStyle.COLOR_BLUR -> Color.Transparent
    }
    val heroBackdrop = rememberMiuixLayerBackdrop { drawContent() }
    val featheredModifier = if (backgroundStyle == HomeBackgroundStyle.COLOR_BLUR) {
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black,
                        0.42f to Color.Black,
                        0.54f to Color.Black.copy(alpha = 0.93f),
                        0.65f to Color.Black.copy(alpha = 0.78f),
                        0.76f to Color.Black.copy(alpha = 0.54f),
                        0.86f to Color.Black.copy(alpha = 0.28f),
                        0.94f to Color.Black.copy(alpha = 0.08f),
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
    } else {
        modifier
    }
    Box(featheredModifier) {
        Box(Modifier.matchParentSize().miuixLayerBackdrop(heroBackdrop), content = backdropContent)
        if (LocalAdvancedMaterialEnabled.current) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .progressiveTextureBlur(
                        backdrop = heroBackdrop,
                        shape = RectangleShape,
                        blurRadius = if (backgroundStyle == HomeBackgroundStyle.COLOR_BLUR) 7.5f else 5.8f,
                        gradient = ProgressiveBlur.Bottom.copy(startFraction = 0.22f, endFraction = 0.96f),
                    )
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.26f to Color.Transparent,
                                0.40f to Color.White.copy(alpha = 0.12f),
                                0.54f to Color.White.copy(alpha = 0.38f),
                                0.68f to Color.White.copy(alpha = 0.68f),
                                0.82f to Color.White.copy(alpha = 0.90f),
                                0.94f to Color.White.copy(alpha = 0.98f),
                                1f to Color.White,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    if (backgroundStyle == HomeBackgroundStyle.COLOR_BLUR) {
                        Brush.verticalGradient(
                            0f to surface.copy(alpha = if (dark) 0.10f else 0.16f),
                            0.14f to surface.copy(alpha = if (dark) 0.03f else 0.05f),
                            0.28f to accentMix.copy(alpha = 0f),
                            0.48f to accentMix.copy(alpha = 0.10f),
                            0.66f to lerp(accentMix, bannerTheme.surfaceColor, 0.35f).copy(alpha = 0.20f),
                            0.82f to bannerTheme.surfaceColor.copy(alpha = 0.14f),
                            0.93f to bannerTheme.surfaceColor.copy(alpha = 0.05f),
                            1f to bannerTheme.surfaceColor.copy(alpha = 0f),
                        )
                    } else {
                        Brush.verticalGradient(
                            0f to fadeTarget.copy(alpha = if (dark) 0.10f else 0.16f),
                            0.14f to fadeTarget.copy(alpha = if (dark) 0.03f else 0.05f),
                            0.26f to accentMix.copy(alpha = 0f),
                            0.40f to accentMix.copy(alpha = 0.07f),
                            0.54f to lerp(accentMix, fadeTarget, 0.28f).copy(alpha = 0.22f),
                            0.66f to lerp(accentMix, fadeTarget, 0.50f).copy(alpha = 0.44f),
                            0.78f to lerp(accentMix, fadeTarget, 0.74f).copy(alpha = 0.70f),
                            0.88f to lerp(accentMix, fadeTarget, 0.90f).copy(alpha = 0.89f),
                            0.95f to fadeTarget.copy(alpha = 0.97f),
                            1f to fadeTarget,
                        )
                    },
                ),
        )
    }
}

private fun resolveLocalBannerGlassCorner(baseRadius: Dp, cornerOffset: Int): Dp =
    if (cornerOffset < 0) {
        baseRadius * ((cornerOffset.coerceAtLeast(-64) + 64f) / 64f)
    } else {
        baseRadius + (cornerOffset.coerceAtMost(96) * 0.25f).dp
    }

@Composable
private fun homeLiquidGlassModifier(
    backdrop: Backdrop,
    shape: Shape,
    surface: Color,
    dark: Boolean,
    config: LocalBannerStyleConfig,
): Modifier {
    val advancedMaterial = LocalAdvancedMaterialEnabled.current
    if (!advancedMaterial || !isRuntimeShaderSupported()) {
        return Modifier.background(
            surface.copy(alpha = if (!advancedMaterial) 1f else (0.78f * config.glassOpacity / 100f).coerceIn(0.25f, 1f)),
            shape,
        )
    }
    val blurDp = (3f * config.glassBlur / 100f).dp
    val refractionHeightDp = (16f * config.glassRefraction / 100f).dp
    val refractionAmountDp = (28f * config.glassRefraction / 100f).dp
    val surfaceAlpha = (0.42f * config.glassOpacity / 100f).coerceIn(0f, 1f)
    val highlightAlpha = ((if (dark) 0.56f else 0.72f) * config.glassHighlight / 100f).coerceIn(0f, 1f)
    return Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            if (blurDp > 0.dp) backdropBlur(blurDp.toPx())
            if (refractionHeightDp > 0.dp && refractionAmountDp > 0.dp) {
                lens(
                    refractionHeightDp.toPx(),
                    refractionAmountDp.toPx(),
                    depthEffect = true,
                    chromaticAberration = config.glassDispersion,
                )
            }
        },
        highlight = { Highlight.Default.copy(alpha = highlightAlpha) },
        onDrawSurface = { drawRect(surface.copy(alpha = surfaceAlpha)) },
    )
}

@Composable
private fun HomeProfileRow(
    avatarUri: String,
    displayName: String,
    signature: String,
    modifier: Modifier = Modifier,
    titleColor: Color = Color.Unspecified,
    subtitleColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    avatarRingColor: Color = Color.Transparent,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .then(if (avatarRingColor != Color.Transparent) Modifier.border(1.5.dp, avatarRingColor, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUri.isNotBlank()) {
                Artwork(avatarUri, Modifier.fillMaxSize(), circular = true)
            } else {
                Image(MiuixIcons.Regular.Music, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
            }
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(tr("welcome", "欢迎，"), style = MiuixTheme.textStyles.body2, color = subtitleColor)
            displayName.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            signature.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MiuixTheme.textStyles.body2, color = subtitleColor, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun HomeAction(
    title: String,
    icon: ImageVector,
    modifier: Modifier,
    theme: HomeBannerTheme? = null,
    isBlurBg: Boolean = false,
    onClick: () -> Unit,
) {
    val darkSurface = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    if (theme != null) {
        val cardFill = if (isBlurBg) {
            theme.cardContainer.copy(alpha = if (darkSurface) 0.72f else 0.78f)
        } else {
            theme.cardContainer
        }
        Row(
            modifier.heightIn(min = 50.dp).clip(RoundedCornerShape(16.dp))
                .background(cardFill).clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(icon, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(theme.accent))
            Text(title, Modifier.padding(start = 12.dp), style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        }
        return
    }
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
private fun SuggestionCover(song: Song, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Artwork(song.artwork, Modifier.fillMaxSize())
    }
}

private fun LazyListScope.homeCollections(
    title: String,
    collections: List<MusicCollection>,
    artists: Boolean,
    model: AuraViewModel,
    horizontalPadding: Dp = 0.dp,
    rowContentPadding: PaddingValues = PaddingValues(0.dp),
) {
    if (collections.isEmpty()) return
    item("${title}_title") {
        Box(Modifier.padding(horizontal = horizontalPadding)) {
            HomeSectionHeader(title) { model.openDirectory(MusicDirectory(title, collections, artists)) }
        }
    }
    item("${title}_content") {
        LazyRow(contentPadding = rowContentPadding, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
