package btm.m.auraplayer.data

import org.junit.Assert.*
import org.junit.Test

class HomeLibraryTest {
    private fun song(id: Long, album: Long, artist: Long, added: Long = 2000, volume: String = "external_primary") =
        Song(id, "Track $id", "Artist $artist", "Album $album", albumId = album, artistId = artist,
            uri = "content://media/$volume/audio/media/$id", added = added, track = id.toInt(), volume = volume)

    @Test fun recentSectionsUseAddedDateAndIncludeTheFullAlbum() {
        val old = song(1, 10, 20, 500)
        val new = song(2, 10, 20, 2500)
        val latest = song(3, 11, 21, 3000)
        val home = HomeLibrary.from(listOf(old, new, latest), emptySet(), emptyMap(), emptyMap(), 1000, 100000)
        assertEquals(listOf(latest, new), home.added)
        assertEquals(listOf("Album 11", "Album 10"), home.recentAlbums.map { it.title })
        assertEquals(listOf(old, new), home.recentAlbums[1].songs)
        assertTrue(home.topAlbums.isEmpty())
    }

    @Test fun topSectionsUseRealPlayCountsAndSuggestionsExcludeRecentlyPlayedSongs() {
        val first = song(1, 10, 20)
        val second = song(2, 11, 21)
        val third = song(3, 12, 22)
        val home = HomeLibrary.from(listOf(first, second, third), setOf(second.key),
            mapOf(first.key to 90000L, second.key to 150000L), mapOf(first.key to 2, second.key to 8), 0, 100000)
        assertEquals(listOf(second, first), home.history)
        assertEquals(listOf(second, first), home.mostPlayed)
        assertEquals(listOf("Album 11", "Album 10"), home.topAlbums.map { it.title })
        assertEquals(listOf(first, third), home.suggestionPool)
        assertEquals(listOf(second), home.favorites)
    }

    @Test fun albumAndArtistIdsFromDifferentVolumesAreNotMerged() {
        val first = song(1, 10, 20)
        val second = song(1, 10, 20, volume = "abcd-1234")
        val home = HomeLibrary.from(listOf(first, second), emptySet(), emptyMap(), emptyMap(), 0, 100000)
        assertEquals(2, home.recentAlbums.size)
        assertEquals(2, home.recentArtists.size)
        assertEquals(listOf(first), home.recentAlbums.first().songs)
    }
}
