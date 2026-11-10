// SPDX-License-Identifier: GPL-3.0-or-later
// Home data ordering ported from Retro Music Repository, LastAddedRepository and TopPlayedRepository.
package btm.m.auraplayer.data

/** All section membership comes from the visible MediaStore library and actual playback history. */
data class HomeLibrary(
    val history: List<Song> = emptyList(),
    val added: List<Song> = emptyList(),
    val mostPlayed: List<Song> = emptyList(),
    val favorites: List<Song> = emptyList(),
    val suggestionPool: List<Song> = emptyList(),
    val topArtists: List<MusicCollection> = emptyList(),
    val topAlbums: List<MusicCollection> = emptyList(),
    val recentArtists: List<MusicCollection> = emptyList(),
    val recentAlbums: List<MusicCollection> = emptyList(),
) {
    companion object {
        fun from(songs: List<Song>, favorites: Set<String>, lastPlayed: Map<String, Long>,
                 counts: Map<String, Int>, addedCutoffSeconds: Long, playedCutoffMillis: Long): HomeLibrary {
            val recent = songs.filter { (lastPlayed[it.key] ?: 0L) > 0L }.sortedByDescending { lastPlayed[it.key] }
            val added = songs.filter { it.added > addedCutoffSeconds }.sortedByDescending { it.added }
            val most = songs.filter { (counts[it.key] ?: 0) > 0 }.sortedByDescending { counts[it.key] }.take(100)
            val allAlbums = songs.groupBy { "${it.volume}:${it.albumId}" }
            val allArtists = songs.groupBy { "${it.volume}:${it.artistId}" }
            fun albums(rankedSongs: List<Song>) = rankedSongs.groupBy { "${it.volume}:${it.albumId}" }.map { (key, tracks) ->
                val fullAlbum = allAlbums.getValue(key)
                MusicCollection("album:$key", tracks.first().album, tracks.first().artist,
                    fullAlbum.sortedBy { it.track }, tracks.first().artwork)
            }
            fun artists(rankedSongs: List<Song>) = rankedSongs.groupBy { "${it.volume}:${it.artistId}" }.map { (key, tracks) ->
                MusicCollection("artist:$key", tracks.first().artist, "",
                    allArtists.getValue(key), tracks.first().artwork)
            }
            val pool = songs.filter { (lastPlayed[it.key] ?: 0L) < playedCutoffMillis }
            return HomeLibrary(recent, added, most, songs.filter { it.key in favorites }, pool,
                artists(most), albums(most), artists(added), albums(added))
        }
    }
}
