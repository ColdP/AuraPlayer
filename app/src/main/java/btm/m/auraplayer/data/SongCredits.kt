// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

enum class SongCreditRole {
    LYRICIST,
    COMPOSER,
    ARRANGER,
    PRODUCER,
}

data class SongCreditPerson(
    val name: String,
    val roles: List<SongCreditRole>,
    val avatarUrl: String = "",
    val artistId: Long = 0L,
)

data class SongCreditsData(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val artwork: String,
    val releaseYear: String,
    val songwriting: List<SongCreditPerson>,
    val production: List<SongCreditPerson>,
)

internal object SongCreditsParser {
    private val orpheusArtistIdRegex = Regex("""[?&]id=(\d+)""")
    private val plainCreditRegex = Regex(
        """^(?:\[\d{1,3}:\d{2}(?:\.\d{1,3})?]\s*)*(作词|词|作曲|曲|编曲|编曲人|制作人|音乐制作人|联合制作人|Lyricist|Lyrics|Composer|Arranger|Producer)\s*[:：]\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val plainSplitRegex = Regex("""\s*(?:/|,|，|、|&)\s*""")

    private data class RawCreditEntry(
        val role: SongCreditRole,
        val name: String,
        val avatarUrl: String,
        val artistId: Long,
    )

    private class MutablePerson(
        val name: String,
        var avatarUrl: String,
        var artistId: Long,
    ) {
        val roles = LinkedHashSet<SongCreditRole>()
    }

    fun parse(
        songId: Long,
        lyricJson: JSONObject,
        detailJson: JSONObject? = null,
        fallbackSong: Song? = null,
        albumPublishTimeMs: Long = 0L,
    ): SongCreditsData {
        val detailSongObj = detailJson?.optJSONArray("songs")?.optJSONObject(0)
        val detailSong = detailJson?.optJSONArray("songs")?.let { arr ->
            if (arr.length() > 0) parseSongsList(arr).firstOrNull() else null
        }

        val title = detailSong?.title?.takeIf { it.isNotBlank() } ?: fallbackSong?.title.orEmpty()
        val artist = detailSong?.artist?.takeIf { it.isNotBlank() } ?: fallbackSong?.artist.orEmpty()
        val album = detailSong?.album?.takeIf { it.isNotBlank() } ?: fallbackSong?.album.orEmpty()
        val artwork = (detailSong?.artwork?.takeIf { it.isNotBlank() } ?: fallbackSong?.artwork.orEmpty())
            .replaceFirst("http://", "https://")

        val publishTimeMs = detailSongObj?.optLong("publishTime", 0L)?.takeIf { it > 0L }
            ?: albumPublishTimeMs.takeIf { it > 0L }
            ?: 0L
        val releaseYear = formatReleaseYear(publishTimeMs, fallbackSong?.year ?: 0)

        val rawEntries = ArrayList<RawCreditEntry>()
        val seenTextLines = HashSet<String>()
        val lyricSources = listOf(
            lyricJson.optJSONObject("lrc")?.optString("lyric").orEmpty(),
            lyricJson.optJSONObject("yrc")?.optString("lyric").orEmpty(),
        )
        for (body in lyricSources) {
            if (body.isBlank()) continue
            for (rawLine in body.lineSequence()) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty() || !seenTextLines.add(trimmed)) continue
                if (trimmed.startsWith("{")) {
                    parseJsonCreditLine(trimmed, rawEntries)
                } else {
                    parsePlainCreditLine(trimmed, rawEntries)
                }
            }
        }

        val songwriting = groupByBucket(
            rawEntries.filter { it.role == SongCreditRole.LYRICIST || it.role == SongCreditRole.COMPOSER },
        )
        val production = groupByBucket(
            rawEntries.filter { it.role == SongCreditRole.ARRANGER || it.role == SongCreditRole.PRODUCER },
        )

        return SongCreditsData(
            songId = songId,
            title = title,
            artist = artist,
            album = album,
            artwork = artwork,
            releaseYear = releaseYear,
            songwriting = songwriting,
            production = production,
        )
    }

    internal fun formatReleaseYear(publishTimeMs: Long, fallbackYear: Int = 0): String {
        if (publishTimeMs > 0L) {
            val year = runCatching {
                Instant.ofEpochMilli(publishTimeMs).atZone(ZoneId.systemDefault()).year
            }.getOrDefault(0)
            if (year > 1900) return year.toString()
        }
        return if (fallbackYear > 1900) fallbackYear.toString() else ""
    }

    private fun parseJsonCreditLine(line: String, out: MutableList<RawCreditEntry>) {
        val obj = runCatching { JSONObject(line) }.getOrNull() ?: return
        val segments = obj.optJSONArray("c") ?: return
        if (segments.length() < 2) return

        val firstSeg = segments.optJSONObject(0) ?: return
        val firstText = firstSeg.optString("tx").trim()
        val roleText = firstText.trimEnd(':', '：', ' ')
        val role = mapRole(roleText) ?: return

        for (i in 1 until segments.length()) {
            val seg = segments.optJSONObject(i) ?: continue
            val tx = seg.optString("tx").trim()
            if (tx.isEmpty() || isSeparatorOnly(tx)) continue

            val li = seg.optString("li")
                .trim()
                .takeUnless { it == "null" }
                .orEmpty()
                .replaceFirst("http://", "https://")
            val orUri = seg.optString("or").trim()
            val artistId = parseArtistId(orUri)

            if (li.isBlank() && artistId <= 0L && plainSplitRegex.containsMatchIn(tx)) {
                for (part in tx.split(plainSplitRegex)) {
                    val clean = part.trim()
                    if (clean.isNotEmpty() && !isSeparatorOnly(clean)) {
                        out += RawCreditEntry(role, clean, "", 0L)
                    }
                }
            } else {
                out += RawCreditEntry(role, tx, li, artistId)
            }
        }
    }

    private fun parsePlainCreditLine(line: String, out: MutableList<RawCreditEntry>) {
        val match = plainCreditRegex.matchEntire(line) ?: return
        val role = mapRole(match.groupValues[1]) ?: return
        val peopleRaw = match.groupValues[2].trim()
        if (peopleRaw.isEmpty()) return
        for (part in peopleRaw.split(plainSplitRegex)) {
            val clean = part.trim()
            if (clean.isNotEmpty() && !isSeparatorOnly(clean)) {
                out += RawCreditEntry(role, clean, "", 0L)
            }
        }
    }

    private fun mapRole(raw: String): SongCreditRole? {
        val key = raw.trim().lowercase()
        return when {
            key == "作词" || key == "词" || key == "lyricist" || key == "lyrics" -> SongCreditRole.LYRICIST
            key == "作曲" || key == "曲" || key == "composer" -> SongCreditRole.COMPOSER
            key == "编曲" || key == "编曲人" || key == "arranger" -> SongCreditRole.ARRANGER
            key == "制作人" || key == "音乐制作人" || key == "联合制作人" || key == "producer" || key == "co-producer" ->
                SongCreditRole.PRODUCER
            else -> null
        }
    }

    private fun isSeparatorOnly(text: String): Boolean =
        text.all { it == '/' || it == ',' || it == '，' || it == '、' || it == '&' || it == ':' || it == '：' || it.isWhitespace() }

    private fun parseArtistId(orUri: String): Long =
        orpheusArtistIdRegex.find(orUri)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L

    private fun groupByBucket(entries: List<RawCreditEntry>): List<SongCreditPerson> {
        if (entries.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutablePerson>()
        val keyByName = HashMap<String, String>()

        for (entry in entries) {
            val normName = entry.name.trim().lowercase()
            val lookupKey = when {
                entry.artistId > 0L -> "id:${entry.artistId}".also { idKey ->
                    // Merge if the same name appeared earlier without artistId
                    val prevNameKey = keyByName[normName]
                    if (prevNameKey != null && prevNameKey != idKey && prevNameKey.startsWith("name:")) {
                        val migrated = grouped.remove(prevNameKey)
                        if (migrated != null) {
                            migrated.artistId = entry.artistId
                            if (migrated.avatarUrl.isBlank()) migrated.avatarUrl = entry.avatarUrl
                            grouped[idKey] = migrated
                            keyByName[normName] = idKey
                        }
                    } else if (prevNameKey == null) {
                        keyByName[normName] = idKey
                    }
                }
                else -> keyByName[normName] ?: "name:$normName".also { keyByName[normName] = it }
            }

            val person = grouped.getOrPut(lookupKey) {
                MutablePerson(entry.name.trim(), entry.avatarUrl, entry.artistId)
            }
            if (person.avatarUrl.isBlank() && entry.avatarUrl.isNotBlank()) {
                person.avatarUrl = entry.avatarUrl
            }
            if (person.artistId <= 0L && entry.artistId > 0L) {
                person.artistId = entry.artistId
            }
            person.roles += entry.role
        }

        return grouped.values.map {
            SongCreditPerson(
                name = it.name,
                roles = it.roles.toList(),
                avatarUrl = it.avatarUrl,
                artistId = it.artistId,
            )
        }
    }

    private fun parseSongsList(array: org.json.JSONArray): List<Song> = List(array.length()) { i ->
        val o = array.getJSONObject(i)
        val artistData = o.optJSONArray("ar") ?: o.optJSONArray("artists") ?: org.json.JSONArray()
        val artists = List(artistData.length()) { index ->
            artistData.getJSONObject(index).let {
                SongArtist(
                    it.optLong("id"),
                    it.optString("name"),
                    it.optString("picUrl").takeUnless { url -> url == "null" }.orEmpty(),
                )
            }
        }.filter { it.name.isNotBlank() }
        val album = o.optJSONObject("al") ?: o.optJSONObject("album") ?: JSONObject()
        val id = o.getLong("id")
        Song(
            id = id,
            title = o.optString("name"),
            artist = artists.joinToString(" / ") { it.name },
            album = album.optString("name"),
            albumId = album.optLong("id"),
            artistId = artists.firstOrNull()?.id ?: 0,
            duration = o.optLong("dt", o.optLong("duration")),
            uri = "aura://netease/$id",
            artwork = album.optString("picUrl"),
            cloud = true,
            artists = artists,
        )
    }
}
