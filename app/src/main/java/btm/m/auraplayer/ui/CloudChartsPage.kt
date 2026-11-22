// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.R
import btm.m.auraplayer.data.NeteaseChartCatalog
import btm.m.auraplayer.data.NeteaseChartGroup
import btm.m.auraplayer.data.NeteaseChartItem
import btm.m.auraplayer.data.NeteaseChartSource
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun chartGroupTitle(group: NeteaseChartGroup): String = when (group) {
    NeteaseChartGroup.FEATURED -> tr("charts_group_featured", "精选榜")
    NeteaseChartGroup.GENRE -> tr("charts_group_genre", "曲风榜")
    NeteaseChartGroup.GLOBAL -> tr("charts_group_global", "全球榜")
    NeteaseChartGroup.LANGUAGE -> tr("charts_group_language", "语种榜")
}

@Composable
internal fun chartItemTitle(item: NeteaseChartItem): String =
    tr(item.spec.titleKey, item.spec.defaultTitle)

@Composable
private fun chartFrequencyText(raw: String): String = when (raw.trim()) {
    "实时热门" -> tr("chart_freq_realtime_hot", "实时热门")
    "实时更新" -> tr("chart_freq_realtime", "实时更新")
    else -> raw.trim()
}

/** Horizontally scrolling 4-chart strip displayed on the NetEase home page. */
@Composable
internal fun CloudHomeChartsRow(
    charts: List<NeteaseChartItem>,
    model: AuraViewModel,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bannerTheme: HomeBannerTheme? = null,
    isBlurBg: Boolean = false,
) {
    LazyRow(
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(charts, key = { it.stableKey }) { chart ->
            CloudHomeChartCard(
                chart = chart,
                model = model,
                bannerTheme = bannerTheme,
                isBlurBg = isBlurBg,
            )
        }
    }
}

@Composable
private fun CloudHomeChartCard(
    chart: NeteaseChartItem,
    model: AuraViewModel,
    bannerTheme: HomeBannerTheme? = null,
    isBlurBg: Boolean = false,
) {
    val title = chartItemTitle(chart)
    val frequency = chartFrequencyText(chart.updateFrequency.ifBlank { chart.spec.defaultFrequency })
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val customTheme = bannerTheme?.takeIf { it.isCustom }
    val artworkTheme = if (customTheme == null && chart.artwork.isNotBlank()) {
        rememberHomeBannerTheme(chart.artwork).takeIf { it.isCustom }
    } else null
    val accentColor = customTheme?.accent
        ?: artworkTheme?.accent
        ?: Color(chart.spec.accentHex)
    val baseSurface = when {
        customTheme != null -> if (isBlurBg) {
            customTheme.cardContainer.copy(alpha = if (dark) 0.72f else 0.78f)
        } else {
            customTheme.cardContainer
        }
        LocalAppUiStyle.current == AppUiStyle.MATERIAL -> rememberActiveExpressiveScheme().surfaceContainerLow
        LocalAppUiStyle.current == AppUiStyle.ONE -> Color(rememberActiveOneUiPalette().card)
        else -> MiuixTheme.colorScheme.surfaceContainer
    }
    val tintedSurface = if (customTheme != null) {
        baseSurface
    } else {
        lerp(baseSurface, accentColor, if (dark) 0.09f else 0.055f)
    }
    val playBg = customTheme?.accent ?: accentColor
    val playFg = customTheme?.onAccent ?: if (playBg.luminance() > 0.56f) Color.Black else Color.White
    val summaryColor = customTheme?.mutedInk ?: MiuixTheme.colorScheme.onSurfaceVariantSummary
    val rankMuted = summaryColor.copy(alpha = 0.72f)

    SeamlessCard(
        modifier = Modifier.width(264.dp),
        cornerRadius = 22.dp,
        insideMargin = PaddingValues(13.dp),
        colors = CardDefaults.defaultColors(color = tintedSurface),
        showIndication = true,
        onClick = { model.openCloudChart(chart, title) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            CloudChartArtwork(
                chart = chart,
                title = title,
                artworkUri = chart.artwork,
                modifier = Modifier.size(56.dp),
                cornerRadius = 14.dp,
                showEditorialOverlay = false,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (frequency.isNotBlank()) {
                    Text(
                        text = frequency,
                        color = summaryColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(30.dp)
                    .clip(CircleShape)
                    .background(playBg)
                    .clickable { model.playCloudChart(chart, title) },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_player_play),
                    contentDescription = tr("play", "播放"),
                    modifier = Modifier.size(12.dp),
                    colorFilter = ColorFilter.tint(playFg),
                )
            }
        }
        Spacer(Modifier.height(11.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (chart.tracks.isNotEmpty()) {
                chart.tracks.take(3).forEachIndexed { idx, track ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${idx + 1}",
                            modifier = Modifier.width(14.dp),
                            color = if (idx == 0) accentColor else rankMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = track.title,
                            modifier = Modifier.weight(1f, fill = false),
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (track.artist.isNotBlank()) {
                            Text(
                                text = "· ${track.artist}",
                                color = summaryColor,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                repeat(3) { idx ->
                    val barFraction = when (idx) {
                        0 -> 0.86f
                        1 -> 0.72f
                        else -> 0.60f
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${idx + 1}",
                            modifier = Modifier.width(14.dp),
                            color = if (idx == 0) accentColor.copy(alpha = 0.75f) else rankMuted.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Box(
                            Modifier
                                .fillMaxWidth(barFraction)
                                .height(10.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.09f else 0.065f)),
                        )
                    }
                }
            }
        }
    }
}

/** Secondary full rankings page displaying all 4 categorized NetEase chart sections. */
@Composable
internal fun CloudChartsPage(
    model: AuraViewModel,
    padding: PaddingValues,
    selectedTab: Int,
) {
    LaunchedEffect(model.account?.userId) {
        model.refreshCloudCharts()
    }
    val pullToRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) return@LaunchedEffect
        val start = System.currentTimeMillis()
        try {
            model.refreshCloudCharts(force = true)
            val elapsed = System.currentTimeMillis() - start
            if (elapsed < 320L) delay(320L - elapsed)
        } finally {
            isRefreshing = false
        }
    }

    val visibleGroups = remember(selectedTab) {
        when (selectedTab) {
            1 -> listOf(NeteaseChartGroup.FEATURED)
            2 -> listOf(NeteaseChartGroup.GENRE)
            3 -> listOf(NeteaseChartGroup.GLOBAL)
            4 -> listOf(NeteaseChartGroup.LANGUAGE)
            else -> listOf(
                NeteaseChartGroup.FEATURED,
                NeteaseChartGroup.GENRE,
                NeteaseChartGroup.GLOBAL,
                NeteaseChartGroup.LANGUAGE,
            )
        }
    }
    val chartsByGroup = model.cloudChartsByGroup

    PullToRefresh(
        isRefreshing = isRefreshing,
        onRefresh = { isRefreshing = true },
        modifier = Modifier.fillMaxSize(),
        pullToRefreshState = pullToRefreshState,
        contentPadding = padding,
        topAppBarScrollBehavior = LocalPageScroll.current,
        color = MiuixTheme.colorScheme.primary,
        refreshTexts = listOf(
            tr("pull_to_refresh", "下拉刷新"),
            tr("release_to_refresh", "松开刷新"),
            tr("refreshing", "正在刷新…"),
            tr("refresh_completed", "刷新完成"),
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = if (maxWidth >= 640.dp) 3 else 2
            LazyColumn(
                modifier = pageContentModifier(),
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (model.cloudChartsFailed && chartsByGroup.values.flatten().none { it.artwork.isNotBlank() }) {
                    item("charts_error") {
                        CloudPagingFooter(model.cloudChartsLoading, true) {
                            model.refreshCloudCharts(force = true)
                        }
                    }
                }
                visibleGroups.forEachIndexed { groupIndex, group ->
                    val charts = chartsByGroup[group]
                        ?: NeteaseChartCatalog.specsByGroup[group].orEmpty().map { NeteaseChartItem(it) }
                    item("header_${group.name}") {
                        CloudChartGroupHeader(
                            title = chartGroupTitle(group),
                            count = charts.size,
                            topSpacing = if (groupIndex == 0) 2.dp else 8.dp,
                        )
                    }
                    if (group == NeteaseChartGroup.FEATURED) {
                        val flagship = charts.take(4)
                        val secondary = charts.drop(4)
                        items(flagship, key = { "flagship_${it.stableKey}" }) { chart ->
                            CloudChartFeaturedCard(chart = chart, model = model)
                        }
                        if (secondary.isNotEmpty()) {
                            val rows = secondary.chunked(columns)
                            items(rows.indices.toList(), key = { "featured_grid_$it" }) { rowIndex ->
                                CloudChartGridRow(
                                    items = rows[rowIndex],
                                    columns = columns,
                                    model = model,
                                )
                            }
                        }
                    } else {
                        val rows = charts.chunked(columns)
                        items(rows.indices.toList(), key = { "${group.name}_grid_$it" }) { rowIndex ->
                            CloudChartGridRow(
                                items = rows[rowIndex],
                                columns = columns,
                                model = model,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudChartGroupHeader(
    title: String,
    count: Int,
    topSpacing: Dp = 0.dp,
) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val pillBg = when (LocalAppUiStyle.current) {
        AppUiStyle.MATERIAL -> rememberActiveExpressiveScheme().secondaryContainer
        AppUiStyle.ONE -> Color(rememberActiveOneUiPalette().chip)
        else -> MiuixTheme.colorScheme.primary.copy(alpha = if (dark) 0.18f else 0.11f)
    }
    val pillFg = when (LocalAppUiStyle.current) {
        AppUiStyle.MATERIAL -> rememberActiveExpressiveScheme().onSecondaryContainer
        AppUiStyle.ONE -> Color(rememberActiveOneUiPalette().onChip)
        else -> MiuixTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topSpacing, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(pillBg)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$count",
                color = pillFg,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CloudChartFeaturedCard(
    chart: NeteaseChartItem,
    model: AuraViewModel,
) {
    val title = chartItemTitle(chart)
    val frequency = chartFrequencyText(chart.updateFrequency.ifBlank { chart.spec.defaultFrequency })
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val artworkTheme = if (chart.artwork.isNotBlank()) rememberHomeBannerTheme(chart.artwork).takeIf { it.isCustom } else null
    val accentColor = artworkTheme?.accent ?: Color(chart.spec.accentHex)
    val baseSurface = when (LocalAppUiStyle.current) {
        AppUiStyle.MATERIAL -> rememberActiveExpressiveScheme().surfaceContainerLow
        AppUiStyle.ONE -> Color(rememberActiveOneUiPalette().card)
        else -> MiuixTheme.colorScheme.surfaceContainer
    }
    val cardColor = lerp(baseSurface, accentColor, if (dark) 0.09f else 0.05f)
    val summaryColor = MiuixTheme.colorScheme.onSurfaceVariantSummary

    SeamlessCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        insideMargin = PaddingValues(13.dp),
        colors = CardDefaults.defaultColors(color = cardColor),
        showIndication = true,
        onClick = { model.openCloudChart(chart, title) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            val thumbBackdrop = rememberLayerBackdrop {
                drawRect(cardColor)
                drawContent()
            }
            Box(Modifier.size(84.dp)) {
                Box(Modifier.fillMaxSize().layerBackdrop(thumbBackdrop)) {
                    CloudChartArtwork(
                        chart = chart,
                        title = title,
                        artworkUri = chart.artwork,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 16.dp,
                        showEditorialOverlay = false,
                    )
                }
                val playShape = RoundedCornerShape(10.dp)
                val playBg = if (LocalAdvancedMaterialEnabled.current) {
                    Modifier.drawPlainBackdrop(
                        backdrop = thumbBackdrop,
                        shape = { playShape },
                        effects = {
                            vibrancy()
                            backdropBlur(12.dp.toPx())
                        },
                        onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.24f)) },
                    )
                } else {
                    Modifier.background(Color.Black.copy(alpha = 0.50f), playShape)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(28.dp)
                        .clip(playShape)
                        .then(playBg)
                        .clickable { model.playCloudChart(chart, title) },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_player_play),
                        contentDescription = tr("play", "播放"),
                        modifier = Modifier.size(11.dp),
                        colorFilter = ColorFilter.tint(Color.White),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f, fill = false),
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (frequency.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = if (dark) 0.22f else 0.13f))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = frequency,
                                color = if (dark) lerp(accentColor, Color.White, 0.35f) else accentColor,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (chart.tracks.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        chart.tracks.take(3).forEachIndexed { idx, track ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "${idx + 1}",
                                    modifier = Modifier.width(12.dp),
                                    color = if (idx == 0) accentColor else summaryColor.copy(alpha = 0.72f),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = if (track.artist.isNotBlank()) "${track.title} - ${track.artist}" else track.title,
                                    color = if (idx == 0) MiuixTheme.colorScheme.onSurface else summaryColor,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (idx == 0) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = tr("charts_open_hint", "点击查看完整榜单"),
                        color = summaryColor,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                    )
                }
            }
            Image(
                imageVector = MiuixIcons.Regular.ChevronForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(summaryColor.copy(alpha = 0.65f)),
            )
        }
    }
}

@Composable
private fun CloudChartGridRow(
    items: List<NeteaseChartItem>,
    columns: Int,
    model: AuraViewModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.forEach { chart ->
            CloudChartGridCard(
                chart = chart,
                model = model,
                modifier = Modifier.weight(1f),
            )
        }
        repeat((columns - items.size).coerceAtLeast(0)) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CloudChartGridCard(
    chart: NeteaseChartItem,
    model: AuraViewModel,
    modifier: Modifier = Modifier,
) {
    val title = chartItemTitle(chart)
    val frequency = chartFrequencyText(chart.updateFrequency.ifBlank { chart.spec.defaultFrequency })
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val accentColor = Color(chart.spec.accentHex)
    val baseSurface = when (LocalAppUiStyle.current) {
        AppUiStyle.MATERIAL -> rememberActiveExpressiveScheme().surfaceContainerLow
        AppUiStyle.ONE -> Color(rememberActiveOneUiPalette().card)
        else -> MiuixTheme.colorScheme.surfaceContainer
    }
    val cardColor = lerp(baseSurface, accentColor, if (dark) 0.06f else 0.035f)
    val summaryColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val usesTopSongCover = NeteaseChartCatalog.usesTopSongCoverCard(chart.spec)
    val coverUri = if (usesTopSongCover) {
        chart.topSongArtwork.ifBlank { chart.artwork }
    } else {
        chart.artwork
    }
    val firstTrack = chart.tracks.firstOrNull()
    val subtitle = when {
        firstTrack != null && firstTrack.artist.isNotBlank() -> "#1 ${firstTrack.title} · ${firstTrack.artist}"
        firstTrack != null -> "#1 ${firstTrack.title}"
        else -> frequency
    }

    SeamlessCard(
        modifier = modifier,
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(10.dp),
        colors = CardDefaults.defaultColors(color = cardColor),
        showIndication = true,
        onClick = { model.openCloudChart(chart, title) },
    ) {
        val coverShape = RoundedCornerShape(14.dp)
        val tagBackdrop = rememberLayerBackdrop {
            drawRect(cardColor)
            drawContent()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(coverShape),
        ) {
            Box(Modifier.fillMaxSize().layerBackdrop(tagBackdrop)) {
                CloudChartArtwork(
                    chart = chart,
                    title = title,
                    artworkUri = coverUri,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 14.dp,
                    showEditorialOverlay = usesTopSongCover,
                )
            }
            if (frequency.isNotBlank()) {
                val pillShape = RoundedCornerShape(999.dp)
                val pillBg = if (LocalAdvancedMaterialEnabled.current) {
                    Modifier.drawPlainBackdrop(
                        backdrop = tagBackdrop,
                        shape = { pillShape },
                        effects = {
                            vibrancy()
                            backdropBlur(12.dp.toPx())
                        },
                        onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.24f)) },
                    )
                } else {
                    Modifier.background(Color.Black.copy(alpha = 0.46f), pillShape)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(pillShape)
                        .then(pillBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = frequency,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
            val playShape = RoundedCornerShape(12.dp)
            val playBg = if (LocalAdvancedMaterialEnabled.current) {
                Modifier.drawPlainBackdrop(
                    backdrop = tagBackdrop,
                    shape = { playShape },
                    effects = {
                        vibrancy()
                        backdropBlur(12.dp.toPx())
                    },
                    onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.24f)) },
                )
            } else {
                Modifier.background(Color.Black.copy(alpha = 0.48f), playShape)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(34.dp)
                    .clip(playShape)
                    .then(playBg)
                    .clickable { model.playCloudChart(chart, title) },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_player_play),
                    contentDescription = tr("play", "播放"),
                    modifier = Modifier.size(12.dp),
                    colorFilter = ColorFilter.tint(Color.White),
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Column(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = summaryColor,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CloudChartArtwork(
    chart: NeteaseChartItem,
    title: String,
    artworkUri: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    showEditorialOverlay: Boolean = false,
) {
    val accent = Color(chart.spec.accentHex)
    val deep = lerp(accent, Color(0xFF0E1118), 0.52f)
    val bright = lerp(accent, Color.White, 0.16f)
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(bright, accent, deep))),
    ) {
        if (artworkUri.isNotBlank()) {
            Artwork(
                uri = artworkUri,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = cornerRadius,
                adaptIrregular = false,
            )
            if (showEditorialOverlay) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    accent.copy(alpha = 0.20f),
                                    deep.copy(alpha = 0.78f),
                                ),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 10.dp, end = 48.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    if (chart.spec.styleBadge.isNotBlank()) {
                        Text(
                            text = chart.spec.styleBadge,
                            color = Color.White.copy(alpha = 0.84f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (chart.spec.styleBadge.isNotBlank()) {
                    Text(
                        text = chart.spec.styleBadge,
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        maxLines = 1,
                    )
                }
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
