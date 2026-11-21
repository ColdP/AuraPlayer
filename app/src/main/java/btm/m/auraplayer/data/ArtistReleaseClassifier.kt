package btm.m.auraplayer.data

internal enum class ArtistReleaseCategory {
    ALBUM,
    SINGLE_OR_EP,
}

internal enum class ArtistReleaseSubcategory {
    ALBUM,
    EP,
    SINGLE,
}

internal data class ArtistReleaseClassification(
    val category: ArtistReleaseCategory,
    val subcategory: ArtistReleaseSubcategory,
    val totalTracks: Int,
    val coreTracks: Int,
    val totalDurationMs: Long,
    val reason: String,
)

private const val ALBUM_MIN_DURATION_MS = 30L * 60_000L
private const val EP_SINGLE_LONG_TRACK_MS = 10L * 60_000L
private const val SINGLE_MAX_CORE_TRACKS = 3
private const val EP_MAX_CORE_TRACKS = 6
private const val ALBUM_MIN_CORE_TRACKS = 7

private val TrailingBracketRegex = Regex("""\s*(?:\(([^()]*)\)|（([^（）]*)）|\[([^\[\]]*)]|【([^【】]*)】)\s*$""")
private val TrailingDashRegex = Regex("""\s+[-—–]\s+(.+?)\s*$""")
private val WhitespaceRegex = Regex("""\s+""")
private val SpeedFactorRegex = Regex("""\b\d+(?:\.\d+)?x\b""")

private val VariantTagKeywords = listOf(
    "伴奏",
    "和声伴奏",
    "纯音乐",
    "纯享",
    "无人声",
    "清唱",
    "卡拉ok",
    "消音",
    "完整版",
    "试听版",
    "片段",
    "小样",
    "现场",
    "混音",
    "重制",
    "吉他版",
    "钢琴版",
    "弦乐版",
    "管弦乐版",
    "剧情版",
    "念白",
    "加速",
    "降速",
    "中文版",
    "英文版",
    "日文版",
    "粤语版",
    "国语版",
    "主题曲",
    "片尾曲",
    "片头曲",
    "插曲",
    "推广曲",
    "instrumental",
    "inst.",
    "off vocal",
    "off-vocal",
    "less vocal",
    "no vocal",
    "backing track",
    "karaoke",
    "acapella",
    "a cappella",
    "remix",
    "rework",
    "vip",
    "dub",
    "bootleg",
    "mashup",
    "extended",
    "radio edit",
    "radio ver",
    "club mix",
    "single ver",
    "album ver",
    "short ver",
    "tv size",
    "tv ver",
    "acoustic",
    "unplugged",
    "live",
    "demo",
    "sped up",
    "speed up",
    "slowed",
    "reverb",
    "nightcore",
    "remaster",
    "feat.",
    "feat ",
    "ft.",
    "ft ",
    "with ",
    "dj",
    "ver.",
    "version",
)

private val RemixOrBackingKeywords = listOf(
    "伴奏",
    "和声伴奏",
    "卡拉ok",
    "消音",
    "无人声",
    "清唱",
    "小样",
    "加速",
    "降速",
    "混音",
    "instrumental",
    "inst.",
    "off vocal",
    "off-vocal",
    "less vocal",
    "no vocal",
    "backing track",
    "karaoke",
    "acapella",
    "a cappella",
    "remix",
    "rework",
    "vip",
    "dub",
    "bootleg",
    "club mix",
    "extended",
    "radio edit",
    "sped up",
    "speed up",
    "slowed",
    "nightcore",
    "demo",
    "dj",
)

private fun matchesVariantTag(tag: String): Boolean {
    val normalized = tag.trim().lowercase()
    if (normalized.isEmpty()) return false
    if (normalized == "inst" || normalized == "ver" || normalized == "edit" || normalized == "mix") return true
    if (normalized.endsWith("版") && normalized.length <= 16) return true
    if (SpeedFactorRegex.containsMatchIn(normalized)) return true
    return VariantTagKeywords.any { normalized.contains(it) }
}

/**
 * Strips derivative version markers (e.g. `(伴奏)`, `(Remix)`, `(Live)`, `(Instrumental)`)
 * while preserving structural titles such as `(Movement I)` or `(Part 2)`.
 */
internal fun coreTrackTitle(rawTitle: String): String {
    var current = rawTitle.trim().lowercase()
    if (current.isEmpty()) return ""
    while (true) {
        val bracketMatch = TrailingBracketRegex.find(current)
        if (bracketMatch != null) {
            val inner = bracketMatch.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
            if (matchesVariantTag(inner)) {
                val stripped = current.substring(0, bracketMatch.range.first).trim()
                if (stripped.isNotEmpty()) {
                    current = stripped
                    continue
                }
            }
        }
        val dashMatch = TrailingDashRegex.find(current)
        if (dashMatch != null) {
            val suffix = dashMatch.groupValues[1]
            if (matchesVariantTag(suffix)) {
                val stripped = current.substring(0, dashMatch.range.first).trim()
                if (stripped.isNotEmpty()) {
                    current = stripped
                    continue
                }
            }
        }
        break
    }
    return current.replace(WhitespaceRegex, " ").trim()
}

internal fun isRemixOrBackingTrack(rawTitle: String): Boolean {
    val normalized = rawTitle.trim().lowercase()
    if (normalized.isEmpty()) return false
    if (SpeedFactorRegex.containsMatchIn(normalized)) return true
    return RemixOrBackingKeywords.any { normalized.contains(it) }
}

/**
 * Classifies an artist's release into Album vs Single/EP using track list information first
 * (track count, distinct core songs after deduplicating instrumentals/remixes/edits, and total
 * running time), falling back to declared track count and provider type metadata only when no
 * track list is available.
 */
internal fun classifyArtistRelease(
    album: CatalogAlbum,
    tracks: List<Song> = album.tracks,
): ArtistReleaseClassification {
    if (tracks.isNotEmpty()) {
        val trackCount = tracks.size
        val coreTitles = tracks.map { coreTrackTitle(it.title) }.filter { it.isNotEmpty() }
        val coreCount = if (coreTitles.isNotEmpty()) coreTitles.distinct().size else trackCount
        val knownDurations = tracks.map { it.duration }.filter { it > 0L }
        val totalDurationMs = knownDurations.sum()
        val maxTrackDurationMs = knownDurations.maxOrNull() ?: 0L
        val hasReliableDurations = knownDurations.isNotEmpty() && knownDurations.size * 4 >= trackCount * 3
        val exceedsAlbumDuration = totalDurationMs > ALBUM_MIN_DURATION_MS

        if (coreCount <= SINGLE_MAX_CORE_TRACKS) {
            if (trackCount == 1) {
                return when {
                    exceedsAlbumDuration -> ArtistReleaseClassification(
                        category = ArtistReleaseCategory.ALBUM,
                        subcategory = ArtistReleaseSubcategory.ALBUM,
                        totalTracks = trackCount,
                        coreTracks = coreCount,
                        totalDurationMs = totalDurationMs,
                        reason = "single_track_over_30m",
                    )
                    maxTrackDurationMs >= EP_SINGLE_LONG_TRACK_MS -> ArtistReleaseClassification(
                        category = ArtistReleaseCategory.SINGLE_OR_EP,
                        subcategory = ArtistReleaseSubcategory.EP,
                        totalTracks = trackCount,
                        coreTracks = coreCount,
                        totalDurationMs = totalDurationMs,
                        reason = "single_track_10m_to_30m",
                    )
                    else -> ArtistReleaseClassification(
                        category = ArtistReleaseCategory.SINGLE_OR_EP,
                        subcategory = ArtistReleaseSubcategory.SINGLE,
                        totalTracks = trackCount,
                        coreTracks = coreCount,
                        totalDurationMs = totalDurationMs,
                        reason = "single_track",
                    )
                }
            }
            val isLongFormAlbum = trackCount <= SINGLE_MAX_CORE_TRACKS &&
                coreCount >= 2 &&
                exceedsAlbumDuration &&
                tracks.none { isRemixOrBackingTrack(it.title) }
            if (isLongFormAlbum) {
                return ArtistReleaseClassification(
                    category = ArtistReleaseCategory.ALBUM,
                    subcategory = ArtistReleaseSubcategory.ALBUM,
                    totalTracks = trackCount,
                    coreTracks = coreCount,
                    totalDurationMs = totalDurationMs,
                    reason = "few_long_tracks_over_30m",
                )
            }
            val subcategory = if (trackCount >= 4 || coreCount == SINGLE_MAX_CORE_TRACKS || maxTrackDurationMs >= EP_SINGLE_LONG_TRACK_MS) {
                ArtistReleaseSubcategory.EP
            } else {
                ArtistReleaseSubcategory.SINGLE
            }
            return ArtistReleaseClassification(
                category = ArtistReleaseCategory.SINGLE_OR_EP,
                subcategory = subcategory,
                totalTracks = trackCount,
                coreTracks = coreCount,
                totalDurationMs = totalDurationMs,
                reason = "up_to_3_core_tracks",
            )
        }

        if (coreCount <= EP_MAX_CORE_TRACKS) {
            if (exceedsAlbumDuration) {
                return ArtistReleaseClassification(
                    category = ArtistReleaseCategory.ALBUM,
                    subcategory = ArtistReleaseSubcategory.ALBUM,
                    totalTracks = trackCount,
                    coreTracks = coreCount,
                    totalDurationMs = totalDurationMs,
                    reason = "4_to_6_core_tracks_over_30m",
                )
            }
            if (trackCount >= ALBUM_MIN_CORE_TRACKS && coreCount == EP_MAX_CORE_TRACKS && !hasReliableDurations) {
                return ArtistReleaseClassification(
                    category = ArtistReleaseCategory.ALBUM,
                    subcategory = ArtistReleaseSubcategory.ALBUM,
                    totalTracks = trackCount,
                    coreTracks = coreCount,
                    totalDurationMs = totalDurationMs,
                    reason = "7_plus_tracks_unknown_duration",
                )
            }
            return ArtistReleaseClassification(
                category = ArtistReleaseCategory.SINGLE_OR_EP,
                subcategory = ArtistReleaseSubcategory.EP,
                totalTracks = trackCount,
                coreTracks = coreCount,
                totalDurationMs = totalDurationMs,
                reason = "4_to_6_core_tracks_within_30m",
            )
        }

        return ArtistReleaseClassification(
            category = ArtistReleaseCategory.ALBUM,
            subcategory = ArtistReleaseSubcategory.ALBUM,
            totalTracks = trackCount,
            coreTracks = coreCount,
            totalDurationMs = totalDurationMs,
            reason = "7_or_more_core_tracks",
        )
    }

    val declaredCount = album.count.coerceAtLeast(0)
    if (declaredCount > 0) {
        return when {
            declaredCount <= SINGLE_MAX_CORE_TRACKS -> ArtistReleaseClassification(
                category = ArtistReleaseCategory.SINGLE_OR_EP,
                subcategory = ArtistReleaseSubcategory.SINGLE,
                totalTracks = declaredCount,
                coreTracks = declaredCount,
                totalDurationMs = 0L,
                reason = "declared_count_1_to_3",
            )
            declaredCount <= EP_MAX_CORE_TRACKS -> ArtistReleaseClassification(
                category = ArtistReleaseCategory.SINGLE_OR_EP,
                subcategory = ArtistReleaseSubcategory.EP,
                totalTracks = declaredCount,
                coreTracks = declaredCount,
                totalDurationMs = 0L,
                reason = "declared_count_4_to_6",
            )
            else -> ArtistReleaseClassification(
                category = ArtistReleaseCategory.ALBUM,
                subcategory = ArtistReleaseSubcategory.ALBUM,
                totalTracks = declaredCount,
                coreTracks = declaredCount,
                totalDurationMs = 0L,
                reason = "declared_count_7_plus",
            )
        }
    }

    val normalizedType = "${album.type} ${album.subType}".trim().lowercase()
    val looksLikeSingle = normalizedType.contains("single") || normalizedType.contains("单曲")
    val looksLikeEp = normalizedType.contains("ep") ||
        normalizedType.contains("迷你专辑") ||
        normalizedType.contains("mini album") ||
        normalizedType.contains("mini-album")
    if (looksLikeSingle || looksLikeEp) {
        return ArtistReleaseClassification(
            category = ArtistReleaseCategory.SINGLE_OR_EP,
            subcategory = if (looksLikeEp) ArtistReleaseSubcategory.EP else ArtistReleaseSubcategory.SINGLE,
            totalTracks = 0,
            coreTracks = 0,
            totalDurationMs = 0L,
            reason = "fallback_api_type",
        )
    }

    return ArtistReleaseClassification(
        category = ArtistReleaseCategory.ALBUM,
        subcategory = ArtistReleaseSubcategory.ALBUM,
        totalTracks = 0,
        coreTracks = 0,
        totalDurationMs = 0L,
        reason = "fallback_default_album",
    )
}
