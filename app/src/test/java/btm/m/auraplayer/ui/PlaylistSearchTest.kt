package btm.m.auraplayer.ui

import btm.m.auraplayer.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSearchTest {
    private val sampleSongs = listOf(
        Song(1L, "晴天", "周杰伦", "叶惠美", uri = "local://1"),
        Song(2L, "Blue Bird", "Ikimonogakari", "My Song Your Song", albumArtist = "いきものがかり", uri = "local://2"),
        Song(3L, "七里香", "周杰伦", "七里香", uri = "local://3"),
    )

    @Test
    fun blankQueryReturnsAllPlaylistTracks() {
        assertEquals(sampleSongs, filterPlaylistSongs(sampleSongs, ""))
        assertEquals(sampleSongs, filterPlaylistSongs(sampleSongs, "   "))
    }

    @Test
    fun queryMatchesTitleArtistAlbumAndAlbumArtistCaseInsensitively() {
        assertEquals(listOf(sampleSongs[0]), filterPlaylistSongs(sampleSongs, "晴天"))
        assertEquals(listOf(sampleSongs[0], sampleSongs[2]), filterPlaylistSongs(sampleSongs, "周杰伦"))
        assertEquals(listOf(sampleSongs[1]), filterPlaylistSongs(sampleSongs, "my song"))
        assertEquals(listOf(sampleSongs[1]), filterPlaylistSongs(sampleSongs, "いきものがかり"))
        assertEquals(listOf(sampleSongs[2]), filterPlaylistSongs(sampleSongs, "周杰伦 七里香"))
        assertTrue(filterPlaylistSongs(sampleSongs, "不存在").isEmpty())
    }

    @Test
    fun autoLoadRemainingPlaylistTracksOnlyRunsWhenActiveAndHasMore() {
        assertTrue(shouldAutoLoadRemainingPlaylistTracks(searchActive = true, hasMore = true, loading = false, failed = false))
        assertFalse(shouldAutoLoadRemainingPlaylistTracks(searchActive = false, hasMore = true, loading = false, failed = false))
        assertFalse(shouldAutoLoadRemainingPlaylistTracks(searchActive = true, hasMore = false, loading = false, failed = false))
        assertFalse(shouldAutoLoadRemainingPlaylistTracks(searchActive = true, hasMore = true, loading = true, failed = false))
        assertFalse(shouldAutoLoadRemainingPlaylistTracks(searchActive = true, hasMore = true, loading = false, failed = true))
        assertFalse(
            shouldAutoLoadRemainingPlaylistTracks(
                searchActive = true,
                hasMore = true,
                loading = false,
                failed = false,
                accountRequiredMissing = true,
            )
        )
    }
}
