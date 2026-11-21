// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class ListenReportPeriod {
    DAY,
    WEEK,
    MONTH,
    YEAR,
}

enum class ListenTimeSlot {
    NONE,
    MORNING,    // 05:00 - 11:59
    AFTERNOON,  // 12:00 - 17:59
    EVENING,    // 18:00 - 22:59
    LATE_NIGHT, // 23:00 - 04:59
}

data class ReportSongStat(
    val song: Song,
    val playCount: Int,
    val durationSec: Long,
)

data class ReportArtistStat(
    val artistId: Long,
    val name: String,
    val playCount: Int,
    val share: Float,
)

data class ReportRhythmBucket(
    val key: String,
    val count: Int,
    val durationSec: Long,
    val isPeak: Boolean = false,
)

data class NeteaseListenReportData(
    val period: ListenReportPeriod,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val totalDurationSec: Long,
    val totalPlayCount: Int,
    val uniqueSongCount: Int,
    val peakTimeSlot: ListenTimeSlot,
    val topSongs: List<ReportSongStat>,
    val topArtists: List<ReportArtistStat>,
    val rhythmBuckets: List<ReportRhythmBucket>,
    val rhythmKind: RhythmKind,
    val cumulativeDurationSec: Long = 0L,
    val cumulativePlayCount: Int = 0,
    val auraPlayCount: Int = 0,
    val auraSyncedCount: Int = 0,
    val officialConnected: Boolean = false,
) {
    enum class RhythmKind {
        TIME_OF_DAY,
        WEEKDAYS,
        WEEKS_OF_MONTH,
        QUARTERS,
    }

    val topSong: ReportSongStat? get() = topSongs.firstOrNull()
    val isEmpty: Boolean get() = totalPlayCount <= 0 && totalDurationSec <= 0L && topSongs.isEmpty()
}

internal object NeteaseListenReportBuilder {
    suspend fun load(
        api: NeteaseApi,
        store: NeteaseListenStore,
        userId: Long,
        period: ListenReportPeriod,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): NeteaseListenReportData = withContext(Dispatchers.IO) {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val startDate = when (period) {
            ListenReportPeriod.DAY -> today
            ListenReportPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            ListenReportPeriod.MONTH -> today.withDayOfMonth(1)
            ListenReportPeriod.YEAR -> today.withDayOfYear(1)
        }
        val startMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val localRecords = store.recordsInRange(userId, startMs, nowMs)

        val fetched = coroutineScope {
            val recentJob = async { cloudSection { api.recentPlayEntries(limit = 100) } }
            val todayJob = async {
                if (period == ListenReportPeriod.DAY) cloudSection { api.listenDataToday() } else null
            }
            val realtimeJob = async {
                when (period) {
                    ListenReportPeriod.WEEK -> cloudSection { api.listenDataRealtimeReport("week") }
                    ListenReportPeriod.MONTH -> cloudSection { api.listenDataRealtimeReport("month") }
                    else -> null
                }
            }
            val reportJob = async {
                when (period) {
                    ListenReportPeriod.WEEK -> cloudSection { api.listenDataReport("week") }
                    ListenReportPeriod.MONTH -> cloudSection { api.listenDataReport("month") }
                    ListenReportPeriod.YEAR -> cloudSection { api.listenDataReport("year") }
                    ListenReportPeriod.DAY -> null
                }
            }
            val songRankJob = async {
                when (period) {
                    ListenReportPeriod.WEEK -> cloudSection { api.listenDataSongPlayRank("week") }
                    ListenReportPeriod.MONTH -> cloudSection { api.listenDataSongPlayRank("month") }
                    else -> null
                }
            }
            val yearJob = async {
                if (period == ListenReportPeriod.YEAR) cloudSection { api.listenDataYearReport() } else null
            }
            val totalJob = async {
                if (period == ListenReportPeriod.YEAR) cloudSection { api.listenDataTotal() } else null
            }
            val userRecordJob = async {
                when (period) {
                    ListenReportPeriod.WEEK -> cloudSection { api.listeningRank(userId, type = 1) }
                    ListenReportPeriod.YEAR -> cloudSection { api.listeningRank(userId, type = 0) }
                    ListenReportPeriod.DAY, ListenReportPeriod.MONTH -> null
                }
            }
            OfficialSnapshot(
                recentEntries = recentJob.await(),
                todayJson = todayJob.await(),
                realtimeJson = realtimeJob.await(),
                reportJson = reportJob.await(),
                songRankJson = songRankJob.await(),
                yearJson = yearJob.await(),
                totalJson = totalJob.await(),
                userRankSongs = userRecordJob.await(),
            )
        }

        buildReport(
            api = api,
            period = period,
            startDate = startDate,
            endDate = today,
            startMs = startMs,
            endMs = nowMs,
            zone = zone,
            localRecords = localRecords,
            official = fetched,
        )
    }

    private data class OfficialSnapshot(
        val recentEntries: List<NeteaseApi.RecentPlayEntry>?,
        val todayJson: JSONObject?,
        val realtimeJson: JSONObject?,
        val reportJson: JSONObject?,
        val songRankJson: JSONObject?,
        val yearJson: JSONObject?,
        val totalJson: JSONObject?,
        val userRankSongs: List<Song>?,
    )

    private data class OfficialParsedSummary(
        val durationSec: Long = 0L,
        val playCount: Int = 0,
        val uniqueSongs: Int = 0,
        val songs: List<ReportSongStat> = emptyList(),
        val artists: List<Pair<Long, Pair<String, Int>>> = emptyList(),
    )

    private data class SongAccumulator(
        var song: Song,
        var officialCount: Int = 0,
        var officialDurationSec: Long = 0L,
        var auraSyncedCount: Int = 0,
        var auraUnsyncedCount: Int = 0,
        var auraSyncedDurationSec: Long = 0L,
        var auraUnsyncedDurationSec: Long = 0L,
    )

    private fun buildReport(
        api: NeteaseApi,
        period: ListenReportPeriod,
        startDate: LocalDate,
        endDate: LocalDate,
        startMs: Long,
        endMs: Long,
        zone: ZoneId,
        localRecords: List<NeteaseListenRecord>,
        official: OfficialSnapshot,
    ): NeteaseListenReportData {
        val officialConnected = official.recentEntries != null ||
            official.todayJson != null ||
            official.realtimeJson != null ||
            official.reportJson != null ||
            official.songRankJson != null ||
            official.yearJson != null ||
            official.totalJson != null ||
            official.userRankSongs != null

        val officialRecentInWindow = official.recentEntries.orEmpty().filter { it.playTimeMs in startMs..endMs }
        val officialSummaries = listOfNotNull(
            official.todayJson?.let { parseOfficialSummary(api, it) },
            official.realtimeJson?.let { parseOfficialSummary(api, it) },
            official.reportJson?.let { parseOfficialSummary(api, it) },
            official.songRankJson?.let { parseOfficialSummary(api, it) },
            official.yearJson?.let { parseOfficialSummary(api, it) },
        )
        val totalSummary = official.totalJson?.let { parseOfficialSummary(api, it) }

        val songMap = linkedMapOf<Long, SongAccumulator>()
        fun upsertOfficialSong(song: Song, count: Int, durationSec: Long) {
            if (song.id <= 0L) return
            val existing = songMap[song.id]
            if (existing == null) {
                songMap[song.id] = SongAccumulator(
                    song = song,
                    officialCount = count.coerceAtLeast(1),
                    officialDurationSec = durationSec.coerceAtLeast(0L),
                )
            } else {
                existing.song = preferRicherSong(existing.song, song)
                existing.officialCount = maxOf(existing.officialCount, count.coerceAtLeast(1))
                existing.officialDurationSec = maxOf(existing.officialDurationSec, durationSec.coerceAtLeast(0L))
            }
        }

        for (summary in officialSummaries) {
            for (stat in summary.songs) {
                upsertOfficialSong(stat.song, stat.playCount, stat.durationSec)
            }
        }
        for (song in official.userRankSongs.orEmpty()) {
            val count = song.playCount.coerceAtLeast(1)
            val estDur = ((song.duration.coerceAtLeast(0L) / 1000L) * count)
            upsertOfficialSong(song, count, estDur)
        }
        for (entry in officialRecentInWindow) {
            val songDurSec = (entry.song.duration.coerceAtLeast(0L) / 1000L).takeIf { it > 0L } ?: 180L
            upsertOfficialSong(entry.song, 1, songDurSec)
        }

        for (record in localRecords) {
            val song = Song(
                id = record.songId,
                title = record.title,
                artist = record.artist,
                album = record.album,
                albumId = record.albumId,
                artistId = record.artistId,
                duration = record.durationSec * 1000L,
                uri = "aura://netease/${record.songId}",
                artwork = record.artwork,
                cloud = true,
            )
            val acc = songMap.getOrPut(record.songId) { SongAccumulator(song = song) }
            acc.song = preferRicherSong(acc.song, song)
            if (record.synced) {
                acc.auraSyncedCount += 1
                acc.auraSyncedDurationSec += record.playedSec
            } else {
                acc.auraUnsyncedCount += 1
                acc.auraUnsyncedDurationSec += record.playedSec
            }
        }

        val mergedSongs = songMap.values.map { acc ->
            val mergedCount = if (acc.officialCount > 0) {
                maxOf(acc.officialCount, acc.auraSyncedCount) + acc.auraUnsyncedCount
            } else {
                acc.auraSyncedCount + acc.auraUnsyncedCount
            }.coerceAtLeast(1)
            val fallbackUnitSec = (acc.song.duration / 1000L).takeIf { it > 0L } ?: 180L
            val mergedDuration = if (acc.officialDurationSec > 0L) {
                maxOf(acc.officialDurationSec, acc.auraSyncedDurationSec) + acc.auraUnsyncedDurationSec
            } else {
                val auraDur = acc.auraSyncedDurationSec + acc.auraUnsyncedDurationSec
                maxOf(auraDur, mergedCount * fallbackUnitSec)
            }
            ReportSongStat(
                song = acc.song.copy(playCount = mergedCount),
                playCount = mergedCount,
                durationSec = mergedDuration,
            )
        }.sortedWith(compareByDescending<ReportSongStat> { it.playCount }.thenByDescending { it.durationSec })

        val officialReportDurationSec = officialSummaries.maxOfOrNull { it.durationSec } ?: 0L
        val officialRecentEstDurationSec = officialRecentInWindow.sumOf {
            (it.song.duration / 1000L).takeIf { sec -> sec > 0L } ?: 180L
        }
        val officialRankEstDurationSec = if (period == ListenReportPeriod.WEEK) {
            official.userRankSongs.orEmpty().sumOf { song ->
                val count = song.playCount.coerceAtLeast(0)
                val unit = (song.duration / 1000L).takeIf { it > 0L } ?: 180L
                count * unit
            }
        } else 0L
        val officialBaseDurationSec = maxOf(officialReportDurationSec, officialRecentEstDurationSec, officialRankEstDurationSec)

        val auraSyncedDurationSec = localRecords.filter { it.synced }.sumOf { it.playedSec.toLong() }
        val auraUnsyncedDurationSec = localRecords.filterNot { it.synced }.sumOf { it.playedSec.toLong() }
        val mergedDurationSec = maxOf(
            maxOf(officialBaseDurationSec, auraSyncedDurationSec) + auraUnsyncedDurationSec,
            if (period == ListenReportPeriod.DAY) mergedSongs.sumOf { it.durationSec } else 0L,
        )

        val officialReportPlays = officialSummaries.maxOfOrNull { it.playCount } ?: 0
        val mergedSongPlaysSum = mergedSongs.sumOf { it.playCount }
        val auraSyncedPlays = localRecords.count { it.synced }
        val auraUnsyncedPlays = localRecords.count { !it.synced }
        val mergedPlayCount = maxOf(
            maxOf(officialReportPlays, auraSyncedPlays) + auraUnsyncedPlays,
            mergedSongPlaysSum,
        )

        val officialUniqueSongs = officialSummaries.maxOfOrNull { it.uniqueSongs } ?: 0
        val mergedUniqueSongs = maxOf(officialUniqueSongs, mergedSongs.size)

        // Top artists aggregated from merged songs (and supplemented by official artist summaries if any).
        val artistCounts = linkedMapOf<String, MutableArtistStat>()
        for (stat in mergedSongs) {
            val artists = stat.song.artists.ifEmpty { stat.song.creditedArtists() }
            if (artists.isNotEmpty()) {
                for (artist in artists) {
                    val key = artist.name.trim().lowercase()
                    if (key.isEmpty()) continue
                    val entry = artistCounts.getOrPut(key) { MutableArtistStat(artist.id, artist.name.trim(), 0) }
                    if (entry.artistId <= 0L && artist.id > 0L) entry.artistId = artist.id
                    entry.playCount += stat.playCount
                }
            } else if (stat.song.artist.isNotBlank()) {
                val name = stat.song.artist.trim()
                val entry = artistCounts.getOrPut(name.lowercase()) { MutableArtistStat(stat.song.artistId, name, 0) }
                entry.playCount += stat.playCount
            }
        }
        for (summary in officialSummaries) {
            for ((artistId, nameCount) in summary.artists) {
                val name = nameCount.first.trim()
                if (name.isEmpty()) continue
                val entry = artistCounts.getOrPut(name.lowercase()) { MutableArtistStat(artistId, name, 0) }
                if (entry.artistId <= 0L && artistId > 0L) entry.artistId = artistId
                entry.playCount = maxOf(entry.playCount, nameCount.second)
            }
        }
        val sortedArtists = artistCounts.values.sortedByDescending { it.playCount }.take(5)
        val maxArtistCount = sortedArtists.firstOrNull()?.playCount?.coerceAtLeast(1) ?: 1
        val topArtists = sortedArtists.map {
            ReportArtistStat(
                artistId = it.artistId,
                name = it.name,
                playCount = it.playCount,
                share = (it.playCount.toFloat() / maxArtistCount.toFloat()).coerceIn(0.08f, 1f),
            )
        }

        // Timestamped listening events (deduplicated within 90s for same songId so synced Aura + official recent aren't double-counted).
        val timestampedEvents = buildDeduplicatedTimeline(officialRecentInWindow, localRecords)
        val slotCounts = mutableMapOf(
            ListenTimeSlot.MORNING to 0,
            ListenTimeSlot.AFTERNOON to 0,
            ListenTimeSlot.EVENING to 0,
            ListenTimeSlot.LATE_NIGHT to 0,
        )
        val slotDurations = mutableMapOf(
            ListenTimeSlot.MORNING to 0L,
            ListenTimeSlot.AFTERNOON to 0L,
            ListenTimeSlot.EVENING to 0L,
            ListenTimeSlot.LATE_NIGHT to 0L,
        )
        for (ev in timestampedEvents) {
            val hour = Instant.ofEpochMilli(ev.timestampMs).atZone(zone).hour
            val slot = slotForHour(hour)
            slotCounts[slot] = (slotCounts[slot] ?: 0) + 1
            slotDurations[slot] = (slotDurations[slot] ?: 0L) + ev.durationSec
        }
        val peakSlot = slotCounts.entries.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ListenTimeSlot.NONE

        val (rhythmKind, rhythmBuckets) = buildRhythmBuckets(
            period = period,
            endDate = endDate,
            events = timestampedEvents,
            slotCounts = slotCounts,
            slotDurations = slotDurations,
            zone = zone,
        )

        return NeteaseListenReportData(
            period = period,
            startDate = startDate,
            endDate = endDate,
            totalDurationSec = mergedDurationSec,
            totalPlayCount = mergedPlayCount,
            uniqueSongCount = mergedUniqueSongs,
            peakTimeSlot = peakSlot,
            topSongs = mergedSongs.take(10),
            topArtists = topArtists,
            rhythmBuckets = rhythmBuckets,
            rhythmKind = rhythmKind,
            cumulativeDurationSec = totalSummary?.durationSec ?: 0L,
            cumulativePlayCount = totalSummary?.playCount ?: 0,
            auraPlayCount = localRecords.size,
            auraSyncedCount = auraSyncedPlays,
            officialConnected = officialConnected,
        )
    }

    private data class MutableArtistStat(var artistId: Long, val name: String, var playCount: Int)
    private data class TimelineEvent(val songId: Long, val timestampMs: Long, val durationSec: Long)

    private fun buildDeduplicatedTimeline(
        officialRecent: List<NeteaseApi.RecentPlayEntry>,
        localRecords: List<NeteaseListenRecord>,
    ): List<TimelineEvent> {
        val result = mutableListOf<TimelineEvent>()
        for (record in localRecords) {
            if (record.timestampMs > 0L) {
                result += TimelineEvent(record.songId, record.timestampMs, record.playedSec.toLong())
            }
        }
        for (entry in officialRecent) {
            if (entry.playTimeMs <= 0L) continue
            val duplicated = result.any {
                it.songId == entry.song.id && kotlin.math.abs(it.timestampMs - entry.playTimeMs) <= 180_000L
            }
            if (!duplicated) {
                val sec = (entry.song.duration / 1000L).takeIf { it > 0L } ?: 180L
                result += TimelineEvent(entry.song.id, entry.playTimeMs, sec)
            }
        }
        return result
    }

    private fun buildRhythmBuckets(
        period: ListenReportPeriod,
        endDate: LocalDate,
        events: List<TimelineEvent>,
        slotCounts: Map<ListenTimeSlot, Int>,
        slotDurations: Map<ListenTimeSlot, Long>,
        zone: ZoneId,
    ): Pair<NeteaseListenReportData.RhythmKind, List<ReportRhythmBucket>> {
        if (events.isEmpty()) {
            return NeteaseListenReportData.RhythmKind.TIME_OF_DAY to emptyList()
        }
        return when (period) {
            ListenReportPeriod.DAY -> {
                val ordered = listOf(
                    ListenTimeSlot.MORNING to "morning",
                    ListenTimeSlot.AFTERNOON to "afternoon",
                    ListenTimeSlot.EVENING to "evening",
                    ListenTimeSlot.LATE_NIGHT to "late_night",
                )
                val maxVal = ordered.maxOf { slotCounts[it.first] ?: 0 }
                NeteaseListenReportData.RhythmKind.TIME_OF_DAY to ordered.map { (slot, key) ->
                    val count = slotCounts[slot] ?: 0
                    ReportRhythmBucket(key, count, slotDurations[slot] ?: 0L, isPeak = maxVal > 0 && count == maxVal)
                }
            }
            ListenReportPeriod.WEEK -> {
                val counts = IntArray(7)
                val durs = LongArray(7)
                for (ev in events) {
                    val idx = Instant.ofEpochMilli(ev.timestampMs).atZone(zone).dayOfWeek.value - 1
                    counts[idx]++
                    durs[idx] += ev.durationSec
                }
                val maxVal = counts.maxOrNull() ?: 0
                val keys = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
                NeteaseListenReportData.RhythmKind.WEEKDAYS to keys.mapIndexed { idx, key ->
                    ReportRhythmBucket(key, counts[idx], durs[idx], isPeak = maxVal > 0 && counts[idx] == maxVal)
                }
            }
            ListenReportPeriod.MONTH -> {
                val weekCount = ((endDate.lengthOfMonth() - 1) / 7) + 1
                val counts = IntArray(weekCount)
                val durs = LongArray(weekCount)
                for (ev in events) {
                    val day = Instant.ofEpochMilli(ev.timestampMs).atZone(zone).dayOfMonth
                    val idx = ((day - 1) / 7).coerceIn(0, weekCount - 1)
                    counts[idx]++
                    durs[idx] += ev.durationSec
                }
                val nonZeroWeeks = counts.count { it > 0 }
                if (nonZeroWeeks >= 2) {
                    val maxVal = counts.maxOrNull() ?: 0
                    NeteaseListenReportData.RhythmKind.WEEKS_OF_MONTH to List(weekCount) { idx ->
                        ReportRhythmBucket("w${idx + 1}", counts[idx], durs[idx], isPeak = maxVal > 0 && counts[idx] == maxVal)
                    }
                } else {
                    val ordered = listOf(
                        ListenTimeSlot.MORNING to "morning",
                        ListenTimeSlot.AFTERNOON to "afternoon",
                        ListenTimeSlot.EVENING to "evening",
                        ListenTimeSlot.LATE_NIGHT to "late_night",
                    )
                    val maxVal = ordered.maxOf { slotCounts[it.first] ?: 0 }
                    NeteaseListenReportData.RhythmKind.TIME_OF_DAY to ordered.map { (slot, key) ->
                        val count = slotCounts[slot] ?: 0
                        ReportRhythmBucket(key, count, slotDurations[slot] ?: 0L, isPeak = maxVal > 0 && count == maxVal)
                    }
                }
            }
            ListenReportPeriod.YEAR -> {
                val qCounts = IntArray(4)
                val qDurs = LongArray(4)
                for (ev in events) {
                    val month = Instant.ofEpochMilli(ev.timestampMs).atZone(zone).monthValue
                    val q = ((month - 1) / 3).coerceIn(0, 3)
                    qCounts[q]++
                    qDurs[q] += ev.durationSec
                }
                val nonZeroQuarters = qCounts.count { it > 0 }
                if (nonZeroQuarters >= 2) {
                    val maxVal = qCounts.maxOrNull() ?: 0
                    val keys = listOf("q1", "q2", "q3", "q4")
                    NeteaseListenReportData.RhythmKind.QUARTERS to keys.mapIndexed { idx, key ->
                        ReportRhythmBucket(key, qCounts[idx], qDurs[idx], isPeak = maxVal > 0 && qCounts[idx] == maxVal)
                    }
                } else {
                    val ordered = listOf(
                        ListenTimeSlot.MORNING to "morning",
                        ListenTimeSlot.AFTERNOON to "afternoon",
                        ListenTimeSlot.EVENING to "evening",
                        ListenTimeSlot.LATE_NIGHT to "late_night",
                    )
                    val maxVal = ordered.maxOf { slotCounts[it.first] ?: 0 }
                    NeteaseListenReportData.RhythmKind.TIME_OF_DAY to ordered.map { (slot, key) ->
                        val count = slotCounts[slot] ?: 0
                        ReportRhythmBucket(key, count, slotDurations[slot] ?: 0L, isPeak = maxVal > 0 && count == maxVal)
                    }
                }
            }
        }
    }

    private fun slotForHour(hour: Int): ListenTimeSlot = when (hour) {
        in 5..11 -> ListenTimeSlot.MORNING
        in 12..17 -> ListenTimeSlot.AFTERNOON
        in 18..22 -> ListenTimeSlot.EVENING
        else -> ListenTimeSlot.LATE_NIGHT
    }

    private fun preferRicherSong(first: Song, second: Song): Song = Song(
        id = first.id,
        title = first.title.ifBlank { second.title },
        artist = first.artist.ifBlank { second.artist },
        album = first.album.ifBlank { second.album },
        albumId = if (first.albumId > 0L) first.albumId else second.albumId,
        artistId = if (first.artistId > 0L) first.artistId else second.artistId,
        duration = if (first.duration > 0L) first.duration else second.duration,
        uri = first.uri.ifBlank { second.uri },
        artwork = first.artwork.ifBlank { second.artwork },
        cloud = true,
        artists = first.artists.ifEmpty { second.artists },
        playCount = maxOf(first.playCount, second.playCount),
    )

    private fun parseOfficialSummary(api: NeteaseApi, root: JSONObject): OfficialParsedSummary {
        val data = root.optJSONObject("data") ?: root
        val nestedCandidates = listOfNotNull(
            data,
            data.optJSONObject("report"),
            data.optJSONObject("realtimeReport"),
            data.optJSONObject("listenData"),
            data.optJSONObject("summary"),
            data.optJSONObject("total"),
            data.optJSONObject("yearReport"),
        )
        var bestDurationSec = 0L
        var bestPlayCount = 0
        var bestUniqueSongs = 0
        val parsedSongs = mutableListOf<ReportSongStat>()
        val parsedArtists = mutableListOf<Pair<Long, Pair<String, Int>>>()

        for (obj in nestedCandidates) {
            bestDurationSec = maxOf(bestDurationSec, extractDurationSec(obj))
            bestPlayCount = maxOf(bestPlayCount, extractPlayCount(obj))
            bestUniqueSongs = maxOf(bestUniqueSongs, extractUniqueSongs(obj))
            parsedSongs += extractSongStats(api, obj)
            parsedArtists += extractArtistStats(obj)
        }
        return OfficialParsedSummary(
            durationSec = bestDurationSec,
            playCount = bestPlayCount,
            uniqueSongs = bestUniqueSongs,
            songs = parsedSongs,
            artists = parsedArtists,
        )
    }

    private fun extractDurationSec(obj: JSONObject): Long {
        val secKeys = listOf("totalDuration", "listenDuration", "duration", "playDuration", "listenTime", "totalTime", "dayDuration")
        for (key in secKeys) {
            val raw = obj.optLong(key, -1L)
            if (raw > 0L) {
                // NetEase activity endpoints usually return seconds, occasionally ms (> 100_000_000 for a single period).
                return if (raw > 100_000_000L) raw / 1000L else raw
            }
        }
        val minKeys = listOf("totalMinutes", "listenMinutes", "minutes")
        for (key in minKeys) {
            val raw = obj.optLong(key, -1L)
            if (raw > 0L) return raw * 60L
        }
        return 0L
    }

    private fun extractPlayCount(obj: JSONObject): Int {
        val keys = listOf("listenCount", "playCount", "totalPlayCount", "songPlayCount", "totalListenCount", "count")
        for (key in keys) {
            val value = obj.optInt(key, -1)
            if (value > 0) return value
        }
        return 0
    }

    private fun extractUniqueSongs(obj: JSONObject): Int {
        val keys = listOf("songCount", "uniqueSongCount", "totalSongCount", "listenSongCount", "trackCount")
        for (key in keys) {
            val value = obj.optInt(key, -1)
            if (value > 0) return value
        }
        return 0
    }

    private fun extractSongStats(api: NeteaseApi, obj: JSONObject): List<ReportSongStat> {
        val arrayKeys = listOf(
            "songPlayRankList",
            "topSongs",
            "songs",
            "rankList",
            "list",
            "todaySongs",
            "playRankList",
            "mostPlayedSongs",
        )
        val result = mutableListOf<ReportSongStat>()
        for (key in arrayKeys) {
            val array = obj.optJSONArray(key) ?: continue
            result += parseSongStatArray(api, array)
        }
        // Single top song field if present
        listOf("mostPlayedSong", "topSong", "favoriteSong", "song").forEach { key ->
            obj.optJSONObject(key)?.let { item ->
                parseSingleSongStat(api, item)?.let(result::add)
            }
        }
        return result
    }

    private fun parseSongStatArray(api: NeteaseApi, array: JSONArray): List<ReportSongStat> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            parseSingleSongStat(api, item)?.let(::add)
        }
    }

    private fun parseSingleSongStat(api: NeteaseApi, item: JSONObject): ReportSongStat? {
        val nestedSong = item.optJSONObject("song")
            ?: item.optJSONObject("track")
            ?: item.optJSONObject("songInfo")
            ?: item.optJSONObject("data")
        val count = item.optInt("playCount", item.optInt("count", item.optInt("listenCount", 1))).coerceAtLeast(1)
        val rawDur = extractDurationSec(item)
        if (nestedSong != null && nestedSong.optLong("id", 0L) > 0L) {
            val song = api.parseSongs(JSONArray().put(nestedSong)).firstOrNull() ?: return null
            val durSec = if (rawDur > 0L) rawDur else ((song.duration / 1000L).takeIf { it > 0L } ?: 180L) * count
            return ReportSongStat(song.copy(playCount = count), count, durSec)
        }
        val id = item.optLong("songId", item.optLong("id", 0L))
        val name = item.optString("songName", item.optString("name"))
        if (id <= 0L || name.isBlank()) return null
        if (item.has("ar") || item.has("al") || item.has("artists")) {
            val normalized = JSONObject(item.toString()).put("id", id).put("name", name)
            val song = api.parseSongs(JSONArray().put(normalized)).firstOrNull() ?: return null
            val durSec = if (rawDur > 0L) rawDur else ((song.duration / 1000L).takeIf { it > 0L } ?: 180L) * count
            return ReportSongStat(song.copy(playCount = count), count, durSec)
        }
        val albumObj = item.optJSONObject("album") ?: item.optJSONObject("al")
        val artwork = albumObj?.optString("picUrl").orEmpty()
            .ifBlank { item.optString("picUrl", item.optString("coverUrl")) }
            .replaceFirst("http://", "https://")
        val artistName = item.optString("artistName", item.optString("singerName"))
        val durationMs = item.optLong("dt", item.optLong("duration", 0L)).let {
            if (it in 1L..3600L) it * 1000L else it
        }
        val song = Song(
            id = id,
            title = name,
            artist = artistName,
            album = albumObj?.optString("name").orEmpty().ifBlank { item.optString("albumName") },
            albumId = albumObj?.optLong("id", 0L) ?: item.optLong("albumId", 0L),
            artistId = item.optLong("artistId", 0L),
            duration = durationMs,
            uri = "aura://netease/$id",
            artwork = artwork,
            cloud = true,
            playCount = count,
        )
        val durSec = if (rawDur > 0L) rawDur else ((durationMs / 1000L).takeIf { it > 0L } ?: 180L) * count
        return ReportSongStat(song, count, durSec)
    }

    private fun extractArtistStats(obj: JSONObject): List<Pair<Long, Pair<String, Int>>> {
        val keys = listOf("topArtists", "artists", "artistRankList", "favoriteArtists")
        return buildList {
            for (key in keys) {
                val array = obj.optJSONArray(key) ?: continue
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optLong("artistId", item.optLong("id", 0L))
                    val name = item.optString("artistName", item.optString("name")).trim()
                    val count = item.optInt("playCount", item.optInt("count", 1)).coerceAtLeast(1)
                    if (name.isNotBlank()) add(id to (name to count))
                }
            }
        }
    }
}
