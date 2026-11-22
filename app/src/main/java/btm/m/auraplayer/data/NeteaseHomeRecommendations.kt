// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal data class NeteaseTasteProfile(
    val userId: Long,
    val frequentSongs: List<Song>,
    val frequentArtists: List<SongArtist>,
    val frequentAlbums: List<CloudHomeItem>,
    val recentSongs: List<Song>,
    val likedIds: Set<Long>,
    val likedPlaylistId: Long?,
    val loadedAtMs: Long,
) {
    val hasHistory: Boolean
        get() = frequentSongs.isNotEmpty() || frequentArtists.isNotEmpty() || frequentAlbums.isNotEmpty()
}

internal object NeteaseHomeRecommendations {
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    private class SongAccumulator(var song: Song, var score: Double)
    private class ArtistAccumulator(val id: Long, var name: String, var artwork: String, var score: Double)
    private class AlbumAccumulator(
        val id: Long,
        var title: String,
        var subtitle: String,
        var artwork: String,
        var score: Double,
        val trackIds: HashSet<Long> = hashSetOf(),
    )

    private fun String.httpsUrl(): String = replaceFirst("http://", "https://")

    private fun Song.normalizedArtwork(): Song {
        val https = artwork.httpsUrl()
        return if (https == artwork) this else copy(artwork = https)
    }

    private fun CatalogAlbum.toDiscoverHomeItem(): CloudHomeItem? {
        val httpsArt = artwork.httpsUrl()
        if (id <= 0L || title.isBlank() || httpsArt.isBlank()) return null
        return CloudHomeItem(
            id = id,
            title = title,
            subtitle = artist,
            artwork = httpsArt,
            kind = "album",
            sourceId = id,
            section = "discover_album",
            actionType = "album",
            actionId = id,
        )
    }

    private fun <T, K> interleaveUnique(
        streams: List<List<T>>,
        keySelector: (T) -> K,
    ): List<T> {
        if (streams.isEmpty()) return emptyList()
        val seen = HashSet<K>()
        val result = ArrayList<T>()
        val maxLen = streams.maxOfOrNull { it.size } ?: 0
        for (index in 0 until maxLen) {
            for (stream in streams) {
                val item = stream.getOrNull(index) ?: continue
                if (seen.add(keySelector(item))) {
                    result += item
                }
            }
        }
        return result
    }

    suspend fun loadTasteProfile(
        api: NeteaseApi,
        store: NeteaseListenStore,
        userId: Long,
        cachedLikedIds: Set<Long>? = null,
        cachedLikedPlaylistId: Long? = null,
        cachedRecentSongs: List<Song>? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): NeteaseTasteProfile = withContext(Dispatchers.IO) {
        coroutineScope {
            val localDeferred = async { runCatching { store.allForUser(userId) }.getOrDefault(emptyList()) }
            val weekRankDeferred = async { cloudSection { api.listeningRank(userId, type = 1) }.orEmpty() }
            val allRankDeferred = async { cloudSection { api.listeningRank(userId, type = 0) }.orEmpty() }
            val recentDeferred = async {
                cachedRecentSongs?.takeIf { it.isNotEmpty() }
                    ?: cloudSection { api.recentSongs(limit = 50) }.orEmpty()
            }
            val likedIdsDeferred = async {
                cachedLikedIds ?: cloudSection { api.likedSongIds(userId) }.orEmpty()
            }
            val likedPlaylistIdDeferred = async {
                cachedLikedPlaylistId ?: cloudSection {
                    api.playlists(userId).items.firstOrNull { it.specialType == 5 && it.creatorId == userId }?.id
                }
            }
            val recentAlbumsDeferred = async { cloudSection { api.recentAlbumEntries(limit = 30) }.orEmpty() }

            val localRecords = localDeferred.await()
            val weekRank = weekRankDeferred.await()
            val allRank = allRankDeferred.await()
            val recentSongs = recentDeferred.await()
            val likedIds = likedIdsDeferred.await()
            val likedPlaylistId = likedPlaylistIdDeferred.await()
            val recentAlbumEntries = recentAlbumsDeferred.await()

            val songScores = linkedMapOf<Long, SongAccumulator>()
            fun addSong(song: Song, weight: Double, preferOverwrite: Boolean) {
                if (song.id <= 0L || song.title.isBlank()) return
                val normalized = song.normalizedArtwork()
                val existing = songScores[song.id]
                if (existing == null) {
                    songScores[song.id] = SongAccumulator(normalized, weight)
                } else {
                    existing.score += weight
                    if (preferOverwrite || (existing.song.artists.isEmpty() && normalized.artists.isNotEmpty())) {
                        existing.song = normalized
                    } else if (existing.song.artwork.isBlank() && normalized.artwork.isNotBlank()) {
                        existing.song = existing.song.copy(artwork = normalized.artwork)
                    }
                }
            }

            // Local Aura Player playback records capture listening habits immediately.
            for (record in localRecords) {
                if (record.songId <= 0L || record.title.isBlank()) continue
                val ageMs = (nowMs - record.timestampMs).coerceAtLeast(0L)
                val recencyFactor = when {
                    ageMs <= 7L * DAY_MS -> 2.0
                    ageMs <= 30L * DAY_MS -> 1.3
                    else -> 0.8
                }
                val durationFactor = 3.0 + (record.playedSec.coerceAtMost(300) / 120.0)
                val fallbackSong = Song(
                    id = record.songId,
                    title = record.title,
                    artist = record.artist,
                    album = record.album,
                    albumId = record.albumId,
                    artistId = record.artistId,
                    duration = record.durationSec * 1000L,
                    uri = "aura://netease/${record.songId}",
                    artwork = record.artwork.httpsUrl(),
                    cloud = true,
                )
                addSong(fallbackSong, durationFactor * recencyFactor, preferOverwrite = false)
            }

            // NetEase official weekly and all-time listening ranks.
            val weekSize = weekRank.size.coerceAtLeast(1)
            weekRank.forEachIndexed { index, song ->
                val countWeight = song.playCount.coerceAtLeast(0) * 1.5
                val rankWeight = ((weekSize - index).toDouble() / weekSize) * 20.0 + 4.0
                addSong(song, countWeight + rankWeight, preferOverwrite = true)
            }

            val allSize = allRank.size.coerceAtLeast(1)
            allRank.forEachIndexed { index, song ->
                val countWeight = song.playCount.coerceAtLeast(0) * 0.6
                val rankWeight = ((allSize - index).toDouble() / allSize) * 12.0 + 2.0
                addSong(song, countWeight + rankWeight, preferOverwrite = true)
            }

            val recentSize = recentSongs.size.coerceAtLeast(1)
            recentSongs.forEachIndexed { index, song ->
                val rankWeight = ((recentSize - index).toDouble() / recentSize) * 8.0 + 1.5
                addSong(song, rankWeight, preferOverwrite = true)
            }

            for ((id, acc) in songScores) {
                if (id in likedIds) acc.score *= 1.15
            }

            val sortedSongs = songScores.values.sortedByDescending { it.score }
            val frequentSongs = sortedSongs.map { it.song }

            val artistScores = linkedMapOf<Long, ArtistAccumulator>()
            for (acc in sortedSongs) {
                val song = acc.song
                val artists = song.artists.ifEmpty {
                    if (song.artistId > 0L && song.artist.isNotBlank()) listOf(SongArtist(song.artistId, song.artist))
                    else emptyList()
                }
                artists.forEachIndexed { artistIndex, artist ->
                    if (artist.id <= 0L || artist.name.isBlank()) return@forEachIndexed
                    val weight = acc.score * if (artistIndex == 0) 1.0 else 0.7
                    val existing = artistScores[artist.id]
                    if (existing == null) {
                        artistScores[artist.id] = ArtistAccumulator(artist.id, artist.name, artist.artwork.httpsUrl(), weight)
                    } else {
                        existing.score += weight
                        if (existing.artwork.isBlank() && artist.artwork.isNotBlank()) {
                            existing.artwork = artist.artwork.httpsUrl()
                        }
                    }
                }
            }
            val frequentArtists = artistScores.values
                .sortedByDescending { it.score }
                .map { SongArtist(it.id, it.name, it.artwork) }

            val albumScores = linkedMapOf<Long, AlbumAccumulator>()
            for (acc in sortedSongs) {
                val song = acc.song
                val httpsArt = song.artwork.httpsUrl()
                if (song.albumId <= 0L || song.album.isBlank() || httpsArt.isBlank()) continue
                val existing = albumScores[song.albumId]
                if (existing == null) {
                    albumScores[song.albumId] = AlbumAccumulator(
                        id = song.albumId,
                        title = song.album,
                        subtitle = song.artist,
                        artwork = httpsArt,
                        score = acc.score,
                        trackIds = hashSetOf(song.id),
                    )
                } else {
                    existing.score += acc.score
                    if (existing.trackIds.add(song.id)) {
                        existing.score += 2.0
                    }
                    if (existing.subtitle.isBlank() && song.artist.isNotBlank()) {
                        existing.subtitle = song.artist
                    }
                }
            }

            val recentAlbumSize = recentAlbumEntries.size.coerceAtLeast(1)
            recentAlbumEntries.forEachIndexed { index, entry ->
                val item = entry.album.toDiscoverHomeItem() ?: return@forEachIndexed
                val weight = entry.playCount.coerceAtLeast(0) * 2.0 +
                    ((recentAlbumSize - index).toDouble() / recentAlbumSize) * 10.0 + 3.0
                val existing = albumScores[item.id]
                if (existing == null) {
                    albumScores[item.id] = AlbumAccumulator(item.id, item.title, item.subtitle, item.artwork, weight)
                } else {
                    existing.score += weight
                    if (existing.artwork.isBlank()) existing.artwork = item.artwork
                    if (existing.subtitle.isBlank()) existing.subtitle = item.subtitle
                }
            }

            val frequentAlbums = albumScores.values
                .sortedByDescending { it.score }
                .map { acc ->
                    CloudHomeItem(
                        id = acc.id,
                        title = acc.title,
                        subtitle = acc.subtitle,
                        artwork = acc.artwork,
                        kind = "album",
                        sourceId = acc.id,
                        section = "discover_album",
                        actionType = "album",
                        actionId = acc.id,
                    )
                }

            NeteaseTasteProfile(
                userId = userId,
                frequentSongs = frequentSongs,
                frequentArtists = frequentArtists,
                frequentAlbums = frequentAlbums,
                recentSongs = recentSongs.map { it.normalizedArtwork() },
                likedIds = likedIds,
                likedPlaylistId = likedPlaylistId,
                loadedAtMs = nowMs,
            )
        }
    }

    suspend fun loadRecommendedSingles(
        api: NeteaseApi,
        profile: NeteaseTasteProfile?,
        targetCount: Int = 25,
        refresh: Boolean = false,
        previousKeys: Set<String> = emptySet(),
        fallbackPlaylists: List<CloudHomeItem> = emptyList(),
        dailySongsProvider: suspend (Boolean) -> List<Song> = { emptyList() },
    ): List<Song> = withContext(Dispatchers.IO) {
        if (profile != null && profile.frequentSongs.isNotEmpty()) {
            val seedPool = profile.frequentSongs.take(15)
            val simiSeeds = if (refresh) seedPool.shuffled().take(3) else seedPool.take(3)
            val likedFrequent = profile.frequentSongs.filter { it.id in profile.likedIds }
            val heartbeatSeedId = if (profile.likedPlaylistId != null && profile.likedIds.isNotEmpty()) {
                if (refresh) {
                    likedFrequent.shuffled().firstOrNull()?.id ?: profile.likedIds.randomOrNull()
                } else {
                    likedFrequent.firstOrNull()?.id ?: profile.likedIds.firstOrNull()
                }
            } else null

            val relatedInitial = coroutineScope {
                val simiJobs = simiSeeds.map { seed ->
                    async { cloudSection { api.similarSongs(seed.id, limit = 20) }.orEmpty() }
                }
                val heartbeatJob = if (profile.likedPlaylistId != null && heartbeatSeedId != null) {
                    async { cloudSection { api.heartbeatSongs(profile.likedPlaylistId, heartbeatSeedId) }.orEmpty() }
                } else null
                val streams = simiJobs.awaitAll() + listOfNotNull(heartbeatJob?.await())
                interleaveUnique(streams) { it.id }.filter { it.id > 0L && it.title.isNotBlank() }
            }

            val relatedPool = if (relatedInitial.size < 18) {
                val extraSeeds = seedPool.filterNot { candidate -> simiSeeds.any { it.id == candidate.id } }.take(2)
                val extras = coroutineScope {
                    val fmJob = async { cloudSection { api.personalFmSongs() }.orEmpty() }
                    val extraSimiJobs = extraSeeds.map { seed ->
                        async { cloudSection { api.similarSongs(seed.id, limit = 20) }.orEmpty() }
                    }
                    listOf(fmJob.await()) + extraSimiJobs.awaitAll()
                }.flatten()
                (relatedInitial + extras).filter { it.id > 0L && it.title.isNotBlank() }.distinctBy { it.id }
            } else {
                relatedInitial
            }

            val frequentCandidates = if (refresh) {
                val head = profile.frequentSongs.take(25).shuffled()
                val tail = profile.frequentSongs.drop(25).take(15)
                head + tail
            } else {
                profile.frequentSongs.take(25)
            }

            // Blend ~3 similar/heartbeat songs with 1 user's direct frequent song.
            val blended = blendRelatedAndFrequentSongs(relatedPool, frequentCandidates)
            val ordered = if (refresh && previousKeys.isNotEmpty()) {
                blended.sortedBy { it.key in previousKeys }
            } else {
                blended
            }
            if (ordered.size >= targetCount) return@withContext ordered.take(targetCount)

            val supplemental = buildList {
                addAll(ordered)
                addAll(dailySongsProvider(refresh))
                if (size < targetCount) {
                    addAll(cloudSection { api.discoverySongs() }.orEmpty())
                }
            }.distinctBy { it.id }
            return@withContext supplemental.take(targetCount)
        }

        // Logged in with no listening history yet: try Heartbeat + Personal FM before daily/discovery fallback.
        if (profile != null && profile.likedPlaylistId != null && profile.likedIds.isNotEmpty()) {
            val seedId = profile.likedIds.random()
            val (heartbeat, fm) = coroutineScope {
                val h = async { cloudSection { api.heartbeatSongs(profile.likedPlaylistId, seedId) }.orEmpty() }
                val f = async { cloudSection { api.personalFmSongs() }.orEmpty() }
                h.await() to f.await()
            }
            val combined = (heartbeat + fm).distinctBy { it.id }
            if (combined.size >= targetCount) return@withContext combined.take(targetCount)
            val daily = dailySongsProvider(refresh)
            val filled = (combined + daily).distinctBy { it.id }
            if (filled.size >= targetCount) return@withContext filled.take(targetCount)
        }

        val daily = if (profile != null) dailySongsProvider(refresh) else emptyList()
        val discovery = (daily + if (refresh || daily.size < targetCount) {
            cloudSection { api.discoverySongs() }.orEmpty()
        } else {
            emptyList()
        }).distinctBy { it.id }
        if (discovery.size >= targetCount) return@withContext discovery.take(targetCount)

        val playlists = fallbackPlaylists.ifEmpty {
            cloudSection { api.recommendedPlaylists(personalized = profile != null) }.orEmpty()
        }
        val playlistTracks = coroutineScope {
            (if (refresh) playlists.shuffled() else playlists).take(2).map { playlist ->
                async { cloudSection { api.playlistSongs(playlist.id, limit = targetCount).items }.orEmpty() }
            }.awaitAll().flatten()
        }
        (discovery + playlistTracks).distinctBy { it.id }.take(targetCount)
    }

    private fun blendRelatedAndFrequentSongs(
        related: List<Song>,
        frequent: List<Song>,
    ): List<Song> {
        if (related.isEmpty()) return frequent.distinctBy { it.id }
        if (frequent.isEmpty()) return related.distinctBy { it.id }
        val seen = HashSet<Long>()
        val result = ArrayList<Song>(related.size + frequent.size)
        var rIdx = 0
        var fIdx = 0
        while (rIdx < related.size || fIdx < frequent.size) {
            var addedRelated = 0
            while (rIdx < related.size && addedRelated < 3) {
                val candidate = related[rIdx++]
                if (seen.add(candidate.id)) {
                    result += candidate
                    addedRelated++
                }
            }
            while (fIdx < frequent.size) {
                val candidate = frequent[fIdx++]
                if (seen.add(candidate.id)) {
                    result += candidate
                    break
                }
            }
        }
        return result
    }

    suspend fun loadDiscoverAlbums(
        api: NeteaseApi,
        profile: NeteaseTasteProfile?,
        limit: Int = 16,
        refresh: Boolean = false,
        previousIds: Set<Long> = emptySet(),
        homeAlbumItems: List<CloudHomeItem> = emptyList(),
    ): List<CloudHomeItem> = withContext(Dispatchers.IO) {
        if (profile != null && profile.hasHistory) {
            val frequentAlbumIds = profile.frequentAlbums.mapTo(hashSetOf()) { it.id }
            val topFrequentArtistIds = profile.frequentArtists.take(3).mapTo(hashSetOf()) { it.id }

            val artistPool = profile.frequentArtists.take(8)
            val artistSeeds = if (refresh) artistPool.shuffled().take(3) else artistPool.take(3)
            val simiArtistSeedList = artistSeeds.take(2)

            val songPool = profile.frequentSongs.take(10)
            val songSeeds = if (refresh) songPool.shuffled().take(2) else songPool.take(2)

            val (frequentArtistAlbumLists, similarArtistLists, similarSongLists) = coroutineScope {
                val artistAlbumsJob = async {
                    artistSeeds.map { artist ->
                        async { cloudSection { api.artistAlbums(artist.id, offset = 0).items }.orEmpty() }
                    }.awaitAll()
                }
                val simiArtistsJob = async {
                    simiArtistSeedList.map { artist ->
                        async { cloudSection { api.similarArtists(artist.id) }.orEmpty() }
                    }.awaitAll()
                }
                val simiSongsJob = async {
                    songSeeds.map { song ->
                        async { cloudSection { api.similarSongs(song.id, limit = 20) }.orEmpty() }
                    }.awaitAll()
                }
                Triple(artistAlbumsJob.await(), simiArtistsJob.await(), simiSongsJob.await())
            }

            // 1. Other albums by the user's frequently listened artists.
            val frequentArtistStreams = frequentArtistAlbumLists.map { albums ->
                val items = albums.mapNotNull { it.toDiscoverHomeItem() }
                val unheardFirst = items.sortedBy { it.id in frequentAlbumIds }
                if (refresh) unheardFirst.take(6).shuffled() + unheardFirst.drop(6) else unheardFirst
            }
            val frequentArtistOtherAlbums = interleaveUnique(frequentArtistStreams) { it.id }

            // 2. Albums by artists similar to the user's frequently listened artists.
            val candidateSimiArtists = interleaveUnique(similarArtistLists) { it.id }
                .filterNot { it.id in topFrequentArtistIds }
                .ifEmpty { interleaveUnique(similarArtistLists) { it.id } }
            val pickedSimiArtists = if (refresh) candidateSimiArtists.take(8).shuffled().take(4) else candidateSimiArtists.take(4)
            val simiArtistStreams = coroutineScope {
                pickedSimiArtists.map { artist ->
                    async {
                        cloudSection { api.artistAlbums(artist.id, offset = 0).items }.orEmpty()
                            .mapNotNull { it.toDiscoverHomeItem() }
                            .let { if (refresh) it.take(5).shuffled() else it }
                            .take(3)
                    }
                }.awaitAll()
            }
            val similarArtistAlbums = interleaveUnique(simiArtistStreams) { it.id }

            // 3. Albums derived from songs similar to the user's frequently listened songs.
            val similarSongAlbumStreams = similarSongLists.map { songs ->
                songs.mapNotNull { song ->
                    val httpsArt = song.artwork.httpsUrl()
                    if (song.albumId <= 0L || song.album.isBlank() || httpsArt.isBlank()) return@mapNotNull null
                    CloudHomeItem(
                        id = song.albumId,
                        title = song.album,
                        subtitle = song.artist,
                        artwork = httpsArt,
                        kind = "album",
                        sourceId = song.albumId,
                        section = "discover_album",
                        actionType = "album",
                        actionId = song.albumId,
                    )
                }.distinctBy { it.id }
                    .sortedBy { it.id in frequentAlbumIds }
                    .let { if (refresh) it.take(8).shuffled() else it }
            }
            val similarSongAlbums = interleaveUnique(similarSongAlbumStreams) { it.id }

            // 4. User's directly frequently listened albums.
            val directFrequentAlbums = if (refresh) {
                profile.frequentAlbums.take(12).shuffled() + profile.frequentAlbums.drop(12)
            } else {
                profile.frequentAlbums
            }

            val interleaved = interleaveUnique(
                listOf(similarArtistAlbums, frequentArtistOtherAlbums, similarSongAlbums, directFrequentAlbums),
            ) { it.id }

            val ordered = if (refresh && previousIds.isNotEmpty()) {
                interleaved.sortedBy { it.id in previousIds }
            } else {
                interleaved
            }

            if (ordered.size >= 8) {
                return@withContext ordered.take(limit)
            }
            val fallback = (cloudSection { api.discoverAlbums(limit) }.orEmpty() + homeAlbumItems)
                .distinctBy { it.id }
            return@withContext (ordered + fallback).distinctBy { it.id }.take(limit)
        }

        val generic = cloudSection { api.discoverAlbums(limit) }.orEmpty()
        (generic + homeAlbumItems).distinctBy { it.id }.take(limit)
    }
}
