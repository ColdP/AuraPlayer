// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.R
import btm.m.auraplayer.data.ListenReportPeriod
import btm.m.auraplayer.data.ListenTimeSlot
import btm.m.auraplayer.data.NeteaseListenReportData
import btm.m.auraplayer.data.ReportArtistStat
import btm.m.auraplayer.data.ReportRhythmBucket
import btm.m.auraplayer.data.ReportSongStat
import btm.m.auraplayer.data.Song
import btm.m.auraplayer.data.SongArtist
import btm.m.auraplayer.data.resolveListenReportWindow
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val REPORT_HISTORY_DAYS = 365
private const val REPORT_HISTORY_WEEKS = 60
private const val REPORT_HISTORY_MONTHS = 18
private const val REPORT_HISTORY_YEARS = 5

private data class ReportCacheKey(
    val period: ListenReportPeriod,
    val offset: Int,
)

private fun historyPageCount(period: ListenReportPeriod): Int = when (period) {
    ListenReportPeriod.DAY -> REPORT_HISTORY_DAYS
    ListenReportPeriod.WEEK -> REPORT_HISTORY_WEEKS
    ListenReportPeriod.MONTH -> REPORT_HISTORY_MONTHS
    ListenReportPeriod.YEAR -> REPORT_HISTORY_YEARS
}

@Composable
internal fun NeteaseListenReportPage(
    model: AuraViewModel,
    padding: PaddingValues,
    selectedTab: Int,
    refreshNonce: Int,
    login: () -> Unit,
) {
    val period = when (selectedTab) {
        0 -> ListenReportPeriod.DAY
        1 -> ListenReportPeriod.WEEK
        2 -> ListenReportPeriod.MONTH
        else -> ListenReportPeriod.YEAR
    }
    val user = model.account
    val storeRevision = model.app.neteaseListenStore.revision
    val cachedReports = remember(user?.userId) { mutableStateMapOf<ReportCacheKey, NeteaseListenReportData>() }
    val loadingKeys = remember(user?.userId) { mutableStateMapOf<ReportCacheKey, Boolean>() }

    val dayPager = rememberPagerState(initialPage = REPORT_HISTORY_DAYS - 1) { REPORT_HISTORY_DAYS }
    val weekPager = rememberPagerState(initialPage = REPORT_HISTORY_WEEKS - 1) { REPORT_HISTORY_WEEKS }
    val monthPager = rememberPagerState(initialPage = REPORT_HISTORY_MONTHS - 1) { REPORT_HISTORY_MONTHS }
    val yearPager = rememberPagerState(initialPage = REPORT_HISTORY_YEARS - 1) { REPORT_HISTORY_YEARS }
    fun pagerFor(target: ListenReportPeriod): PagerState = when (target) {
        ListenReportPeriod.DAY -> dayPager
        ListenReportPeriod.WEEK -> weekPager
        ListenReportPeriod.MONTH -> monthPager
        ListenReportPeriod.YEAR -> yearPager
    }

    val pullToRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }
    var localRefreshNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) return@LaunchedEffect
        val start = System.currentTimeMillis()
        try {
            // Reset all periods to today/this-week/this-month/this-year on pull-to-refresh.
            val activePager = pagerFor(period)
            val todayPage = historyPageCount(period) - 1
            if (activePager.currentPage != todayPage) {
                activePager.animateScrollToPage(todayPage)
            }
            ListenReportPeriod.entries.filter { it != period }.forEach { other ->
                val p = pagerFor(other)
                val target = historyPageCount(other) - 1
                if (p.currentPage != target) p.scrollToPage(target)
            }
            if (user != null) {
                val loaded = model.loadNeteaseListenReport(period, 0)
                if (loaded != null) {
                    cachedReports[ReportCacheKey(period, 0)] = loaded
                }
            }
            localRefreshNonce++
            val elapsed = System.currentTimeMillis() - start
            if (elapsed < 280L) delay(280L - elapsed)
        } finally {
            isRefreshing = false
        }
    }

    LaunchedEffect(refreshNonce) {
        if (refreshNonce <= 0) return@LaunchedEffect
        val activePager = pagerFor(period)
        val todayPage = historyPageCount(period) - 1
        if (activePager.currentPage != todayPage) {
            activePager.animateScrollToPage(todayPage)
        }
        if (user != null) {
            val loaded = model.loadNeteaseListenReport(period, 0)
            if (loaded != null) {
                cachedReports[ReportCacheKey(period, 0)] = loaded
            }
        }
        localRefreshNonce++
    }

    if (user == null) {
        LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item("login_prompt") {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    Preference(
                        tr("login_required", "请先登录网易云音乐"),
                        tr("listening_report_login_hint", "登录后即可查看日 / 周 / 月 / 年合并听歌报告"),
                        onClick = login,
                    )
                }
            }
        }
        return
    }

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
        AnimatedContent(
            targetState = period,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "neteaseListenReportPeriod",
        ) { activePeriod ->
            val pageCount = historyPageCount(activePeriod)
            val todayPage = pageCount - 1
            val pagerState = pagerFor(activePeriod)

            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                key = { "${activePeriod.name}:$it" },
                modifier = Modifier.fillMaxSize(),
            ) { pageIndex ->
                val offset = pageIndex - todayPage
                val cacheKey = ReportCacheKey(activePeriod, offset)
                val report = cachedReports[cacheKey]
                val isLoading = loadingKeys[cacheKey] ?: (report == null)
                val window = remember(activePeriod, offset) {
                    resolveListenReportWindow(activePeriod, offset)
                }

                LaunchedEffect(user.userId, activePeriod, offset, localRefreshNonce, storeRevision) {
                    if (cachedReports[cacheKey] == null) {
                        loadingKeys[cacheKey] = true
                    }
                    try {
                        val loaded = model.loadNeteaseListenReport(activePeriod, offset)
                        if (loaded != null) {
                            cachedReports[cacheKey] = loaded
                        }
                    } finally {
                        loadingKeys[cacheKey] = false
                    }
                }

                LazyColumn(
                    modifier = pageContentModifier(),
                    contentPadding = padding,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (report == null && isLoading) {
                        item("loading") {
                            ReportPlaceholderCard(
                                period = activePeriod,
                                startDate = window.startDate,
                                endDate = window.endDate,
                                title = tr("loading", "加载中…"),
                                subtitle = tr("listening_report_loading", "正在汇总网易云官方与 Aura Player 收听记录"),
                            )
                        }
                        return@LazyColumn
                    }
                    if (report == null || report.isEmpty) {
                        item("empty") {
                            ReportPlaceholderCard(
                                period = activePeriod,
                                startDate = report?.startDate ?: window.startDate,
                                endDate = report?.endDate ?: window.endDate,
                                title = tr("listening_report_empty", "本期暂无听歌记录"),
                                subtitle = tr("listening_report_empty_hint", "在网易云音乐或 Aura Player 中播放歌曲后，报告会自动合并更新"),
                            )
                        }
                        item("sync_footer_empty") {
                            ReportSyncFooterCard(
                                scrobbleEnabled = model.settings.value.neteaseScrobbleEnabled,
                                auraPlayCount = report?.auraPlayCount ?: 0,
                                auraSyncedCount = report?.auraSyncedCount ?: 0,
                            )
                        }
                        return@LazyColumn
                    }

                    item("hero") {
                        ReportHeroCard(
                            report = report,
                            nickname = user.nickname,
                            avatar = user.avatar,
                        )
                    }

                    report.topSong?.let { top ->
                        item("top_song") {
                            val queue = report.topSongs.map { it.song }
                            ReportTopTrackCard(
                                period = report.period,
                                stat = top,
                                onPlay = { model.play(queue, 0) },
                            )
                        }
                    }

                    if (report.rhythmBuckets.isNotEmpty() || (report.period == ListenReportPeriod.YEAR && report.cumulativeDurationSec > 0L)) {
                        item("rhythm") {
                            ReportRhythmCard(report = report)
                        }
                    }

                    if (report.topSongs.isNotEmpty()) {
                        item("songs_section") {
                            val queue = report.topSongs.map { it.song }
                            ReportTopSongsCard(
                                songs = report.topSongs,
                                activeSongKey = model.current?.key,
                                onPlay = { index -> model.play(queue, index) },
                            )
                        }
                    }

                    if (report.topArtists.isNotEmpty()) {
                        item("artists_section") {
                            ReportTopArtistsCard(
                                artists = report.topArtists,
                                onOpenArtist = { stat ->
                                    if (stat.artistId > 0L) {
                                        model.openSongArtist(
                                            Song(
                                                id = 0L,
                                                title = "",
                                                artist = stat.name,
                                                album = "",
                                                artistId = stat.artistId,
                                                uri = "",
                                                cloud = true,
                                                artists = listOf(SongArtist(stat.artistId, stat.name)),
                                            ),
                                            SongArtist(stat.artistId, stat.name),
                                        )
                                    }
                                },
                            )
                        }
                    }

                    item("sync_footer") {
                        ReportSyncFooterCard(
                            scrobbleEnabled = model.settings.value.neteaseScrobbleEnabled,
                            auraPlayCount = report.auraPlayCount,
                            auraSyncedCount = report.auraSyncedCount,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportPlaceholderCard(
    period: ListenReportPeriod,
    startDate: LocalDate,
    endDate: LocalDate,
    title: String,
    subtitle: String,
) {
    SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportPeriodDateHeader(
                period = period,
                startDate = startDate,
                endDate = endDate,
            )
            Text(
                text = title,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
    }
}

@Composable
private fun ReportPeriodDateHeader(
    period: ListenReportPeriod,
    startDate: LocalDate,
    endDate: LocalDate,
) {
    val primary = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = periodCaption(period),
                color = primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((-3).dp),
            ) {
                Image(
                    imageVector = MiuixIcons.Regular.ChevronBackward,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    colorFilter = ColorFilter.tint(primary.copy(alpha = 0.72f)),
                )
                Image(
                    imageVector = MiuixIcons.Regular.ChevronForward,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    colorFilter = ColorFilter.tint(primary.copy(alpha = 0.72f)),
                )
            }
        }
        Text(
            text = formatPeriodRange(period, startDate, endDate),
            color = muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ReportHeroCard(
    report: NeteaseListenReportData,
    nickname: String,
    avatar: String,
) {
    val primary = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val washTop = primary.copy(alpha = if (isDark) 0.16f else 0.085f)
    val washBottom = primary.copy(alpha = if (isDark) 0.03f else 0.015f)
    val hairline = onSurface.copy(alpha = if (isDark) 0.10f else 0.08f)

    SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(washTop, washBottom)))
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ReportPeriodDateHeader(
                    period = report.period,
                    startDate = report.startDate,
                    endDate = report.endDate,
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(onSurface.copy(alpha = if (isDark) 0.07f else 0.05f))
                        .padding(start = 4.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (avatar.isNotBlank()) {
                        Artwork(avatar, Modifier.size(20.dp), circular = true)
                    }
                    Text(
                        text = nickname,
                        color = onSurface.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = tr("report_total_duration", "聆听时长"),
                    color = muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                DurationHeroReadout(report.totalDurationSec, onSurface, muted)
            }

            Box(Modifier.fillMaxWidth().height(0.5.dp).background(hairline))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroStatCell(
                    label = tr("report_play_count", "播放次数"),
                    value = report.totalPlayCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(0.5.dp).height(28.dp).background(hairline))
                HeroStatCell(
                    label = tr("report_unique_songs", "收听曲目"),
                    value = report.uniqueSongCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(0.5.dp).height(28.dp).background(hairline))
                HeroStatCell(
                    label = tr("report_peak_time", "常听时段"),
                    value = timeSlotLabel(report.peakTimeSlot),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(primary.copy(alpha = 0.85f)))
                Text(
                    text = tr("report_combined_source", "已合并网易云音乐官方与 Aura Player 收听数据"),
                    color = muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DurationHeroReadout(totalSec: Long, primaryInk: Color, mutedInk: Color) {
    val totalMinutes = ((totalSec.coerceAtLeast(0L) + 30L) / 60L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    val hourUnit = tr("hours", "小时")
    val minuteUnit = tr("minutes", "分钟")

    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (hours > 0L) {
            Text(
                text = hours.toString(),
                color = primaryInk,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.0).sp,
                lineHeight = 40.sp,
            )
            Text(
                text = hourUnit,
                color = mutedInk,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (minutes > 0L || hours == 0L) {
            val minuteText = if (totalSec in 1L..29L) "<1" else minutes.toString()
            Text(
                text = minuteText,
                color = primaryInk,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.0).sp,
                lineHeight = 40.sp,
            )
            Text(
                text = minuteUnit,
                color = mutedInk,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun HeroStatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = value,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReportTopTrackCard(
    period: ListenReportPeriod,
    stat: ReportSongStat,
    onPlay: () -> Unit,
) {
    val primary = MiuixTheme.colorScheme.primary
    val onPrimary = MiuixTheme.colorScheme.onPrimary
    val onSurface = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val eyebrow = when (period) {
        ListenReportPeriod.DAY -> tr("report_top_song_day", "今日最常聆听")
        ListenReportPeriod.WEEK -> tr("report_top_song_week", "本周最常聆听")
        ListenReportPeriod.MONTH -> tr("report_top_song_month", "本月最常聆听")
        ListenReportPeriod.YEAR -> tr("report_top_song_year", "年度单曲之最")
    }

    SeamlessCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.5.dp,
        insideMargin = PaddingValues(16.dp),
        showIndication = true,
        onClick = onPlay,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Artwork(stat.song.artwork, Modifier.size(66.dp), cornerRadius = 14.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = eyebrow,
                    color = primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stat.song.title,
                    color = onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(stat.song.artist, stat.song.album).filter { it.isNotBlank() }.joinToString(" · "),
                    color = muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tr("report_times_value", "%d 次").format(stat.playCount),
                    color = onSurface.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(primary),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_player_play),
                    contentDescription = tr("play", "播放"),
                    modifier = Modifier.size(13.dp),
                    colorFilter = ColorFilter.tint(onPrimary),
                )
            }
        }
    }
}

@Composable
private fun ReportRhythmCard(report: NeteaseListenReportData) {
    val primary = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val maxCount = report.rhythmBuckets.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1

    SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = tr("report_rhythm", "聆听节律"),
                    color = onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val subtitle = when (report.rhythmKind) {
                    NeteaseListenReportData.RhythmKind.TIME_OF_DAY -> tr("report_rhythm_day_sub", "按时段分布")
                    NeteaseListenReportData.RhythmKind.WEEKDAYS -> tr("report_rhythm_week_sub", "按星期分布")
                    NeteaseListenReportData.RhythmKind.WEEKS_OF_MONTH -> tr("report_rhythm_month_sub", "按月内周次分布")
                    NeteaseListenReportData.RhythmKind.QUARTERS -> tr("report_rhythm_year_sub", "按季度分布")
                }
                Text(text = subtitle, color = muted, fontSize = 12.sp)
            }

            if (report.rhythmBuckets.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(112.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    report.rhythmBuckets.forEach { bucket ->
                        RhythmBarColumn(
                            bucket = bucket,
                            label = rhythmBucketLabel(bucket.key),
                            fraction = if (bucket.count <= 0) 0f else (bucket.count.toFloat() / maxCount.toFloat()).coerceIn(0.14f, 1f),
                            accent = primary,
                            mutedBar = onSurface.copy(alpha = if (isDark) 0.18f else 0.13f),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (report.period == ListenReportPeriod.YEAR && (report.cumulativeDurationSec > 0L || report.cumulativePlayCount > 0)) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(onSurface.copy(alpha = 0.08f)))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tr("report_all_time_footprint", "网易云累计收听"),
                        color = muted,
                        fontSize = 12.sp,
                    )
                    val summaryParts = buildList {
                        if (report.cumulativeDurationSec > 0L) add(formatCompactDuration(report.cumulativeDurationSec))
                        if (report.cumulativePlayCount > 0) add(tr("report_times_value", "%d 次").format(report.cumulativePlayCount))
                    }
                    Text(
                        text = summaryParts.joinToString(" · "),
                        color = onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun RhythmBarColumn(
    bucket: ReportRhythmBucket,
    label: String,
    fraction: Float,
    accent: Color,
    mutedBar: Color,
    modifier: Modifier = Modifier,
) {
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "rhythmBar_${bucket.key}",
    )
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (bucket.count > 0) {
            Text(
                text = bucket.count.toString(),
                color = if (bucket.isPeak) accent else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 10.sp,
                fontWeight = if (bucket.isPeak) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val barColor = if (bucket.isPeak) accent else mutedBar
            if (bucket.count <= 0) {
                Box(
                    Modifier
                        .width(12.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(mutedBar.copy(alpha = 0.55f)),
                )
            } else {
                Box(
                    Modifier
                        .width(14.dp)
                        .fillMaxHeight(animatedFraction)
                        .clip(RoundedCornerShape(999.dp))
                        .background(barColor),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = label,
            color = if (bucket.isPeak) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            fontWeight = if (bucket.isPeak) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun ReportTopSongsCard(
    songs: List<ReportSongStat>,
    activeSongKey: String?,
    onPlay: (Int) -> Unit,
) {
    val primary = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val maxCount = songs.firstOrNull()?.playCount?.coerceAtLeast(1) ?: 1

    SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(vertical = 16.dp, horizontal = 16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = tr("report_top_songs", "高频单曲"),
                color = onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
            )
            songs.forEachIndexed { index, stat ->
                val isPlaying = activeSongKey == stat.song.key
                val ratio = (stat.playCount.toFloat() / maxCount.toFloat()).coerceIn(0.08f, 1f)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onPlay(index) }
                        .padding(horizontal = 2.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "%02d".format(index + 1),
                        color = if (index < 3) primary else muted,
                        fontSize = 12.sp,
                        fontWeight = if (index < 3) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.width(22.dp),
                    )
                    Artwork(stat.song.artwork, Modifier.size(44.dp), cornerRadius = ListArtworkCornerRadius)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stat.song.title,
                                color = if (isPlaying) primary else onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = tr("report_times_value", "%d 次").format(stat.playCount),
                                color = muted,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                        if (stat.song.artist.isNotBlank()) {
                            Text(
                                text = stat.song.artist,
                                color = muted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(onSurface.copy(alpha = 0.06f)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(ratio)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(primary.copy(alpha = if (index < 3) 0.72f else 0.36f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportTopArtistsCard(
    artists: List<ReportArtistStat>,
    onOpenArtist: (ReportArtistStat) -> Unit,
) {
    val primary = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary

    SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = tr("report_top_artists", "偏爱艺术家"),
                color = onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            artists.forEachIndexed { index, artist ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = artist.artistId > 0L) { onOpenArtist(artist) }
                        .padding(vertical = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = if (index == 0) primary else muted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(14.dp),
                            )
                            Text(
                                text = artist.name,
                                color = onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = tr("report_times_value", "%d 次").format(artist.playCount),
                                color = muted,
                                fontSize = 12.sp,
                            )
                            if (artist.artistId > 0L) {
                                Image(
                                    MiuixIcons.Regular.ChevronForward,
                                    null,
                                    Modifier.size(14.dp),
                                    colorFilter = ColorFilter.tint(muted.copy(alpha = 0.7f)),
                                )
                            }
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(onSurface.copy(alpha = 0.06f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(artist.share)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(999.dp))
                                .background(primary.copy(alpha = (0.85f - index * 0.13f).coerceAtLeast(0.32f))),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportSyncFooterCard(
    scrobbleEnabled: Boolean,
    auraPlayCount: Int,
    auraSyncedCount: Int,
) {
    val primary = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary

    SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (scrobbleEnabled) primary else muted.copy(alpha = 0.4f)),
                )
                Text(
                    text = if (scrobbleEnabled) {
                        tr("report_sync_active", "已开启网易云官方听歌数据同步")
                    } else {
                        tr("report_sync_paused", "未开启网易云官方听歌数据同步")
                    },
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                text = if (auraPlayCount > 0) {
                    tr("report_aura_contrib", "Aura Player 本期记录 %d 次播放，其中 %d 次已上报至网易云官方").format(auraPlayCount, auraSyncedCount)
                } else {
                    tr("report_aura_contrib_empty", "在 Aura Player 播放网易云歌曲时将自动累计并同步至官方年度报告")
                },
                color = muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

private fun periodCaption(period: ListenReportPeriod): String = when (period) {
    ListenReportPeriod.DAY -> tr("report_daily_title", "每日听歌报告")
    ListenReportPeriod.WEEK -> tr("report_weekly_title", "每周听歌报告")
    ListenReportPeriod.MONTH -> tr("report_monthly_title", "每月听歌报告")
    ListenReportPeriod.YEAR -> tr("report_yearly_title", "年度听歌报告")
}

private fun formatPeriodRange(period: ListenReportPeriod, start: LocalDate, end: LocalDate): String = when (period) {
    ListenReportPeriod.DAY -> end.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
    ListenReportPeriod.WEEK -> "${start.format(DateTimeFormatter.ofPattern("MM.dd"))} – ${end.format(DateTimeFormatter.ofPattern("MM.dd"))}"
    ListenReportPeriod.MONTH -> end.format(DateTimeFormatter.ofPattern("yyyy.MM"))
    ListenReportPeriod.YEAR -> "${end.year}"
}

private fun timeSlotLabel(slot: ListenTimeSlot): String = when (slot) {
    ListenTimeSlot.MORNING -> tr("report_slot_morning", "清晨")
    ListenTimeSlot.AFTERNOON -> tr("report_slot_afternoon", "午后")
    ListenTimeSlot.EVENING -> tr("report_slot_evening", "傍晚")
    ListenTimeSlot.LATE_NIGHT -> tr("report_slot_late_night", "深夜")
    ListenTimeSlot.NONE -> "—"
}

private fun rhythmBucketLabel(key: String): String = when (key) {
    "morning" -> tr("report_slot_morning", "清晨")
    "afternoon" -> tr("report_slot_afternoon", "午后")
    "evening" -> tr("report_slot_evening", "傍晚")
    "late_night" -> tr("report_slot_late_night", "深夜")
    "mon" -> tr("report_weekday_mon", "一")
    "tue" -> tr("report_weekday_tue", "二")
    "wed" -> tr("report_weekday_wed", "三")
    "thu" -> tr("report_weekday_thu", "四")
    "fri" -> tr("report_weekday_fri", "五")
    "sat" -> tr("report_weekday_sat", "六")
    "sun" -> tr("report_weekday_sun", "日")
    "w1" -> "W1"
    "w2" -> "W2"
    "w3" -> "W3"
    "w4" -> "W4"
    "w5" -> "W5"
    "q1" -> "Q1"
    "q2" -> "Q2"
    "q3" -> "Q3"
    "q4" -> "Q4"
    else -> key
}

private fun formatCompactDuration(totalSec: Long): String {
    val totalMinutes = ((totalSec.coerceAtLeast(0L) + 30L) / 60L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    val hourUnit = tr("hours", "小时")
    val minuteUnit = tr("minutes", "分钟")
    val joiner = if (I18n.language == "en") " " else ""
    return when {
        hours > 0L && minutes > 0L -> "$hours$joiner$hourUnit$joiner$minutes$joiner$minuteUnit"
        hours > 0L -> "$hours$joiner$hourUnit"
        else -> "${minutes.coerceAtLeast(1L)}$joiner$minuteUnit"
    }
}
