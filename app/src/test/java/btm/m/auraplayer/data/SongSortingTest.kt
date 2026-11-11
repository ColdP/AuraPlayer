package btm.m.auraplayer.data

import org.junit.Assert.*
import org.junit.Test

class SongSortingTest {
    private val a = Song(1, "Alpha", "Zed", "Beta", uri = "local:1", added = 30, year = 2020, modified = 10, duration = 1000, composer = "Z", albumArtist = "A")
    private val b = Song(2, "Beta", "Alpha", "Zed", uri = "local:2", added = 10, year = 2024, modified = 30, duration = 3000, composer = "A", albumArtist = "Z")
    @Test fun sortsEveryRetroFieldWithCorrectDirection() {
        val expectedFirst = mapOf(SongSort.DEFAULT to a, SongSort.TITLE_ASC to a, SongSort.TITLE_DESC to b,
            SongSort.ARTIST to b, SongSort.ALBUM to a, SongSort.YEAR to b, SongSort.DATE_ADDED to a,
            SongSort.DATE_MODIFIED to b, SongSort.COMPOSER to b, SongSort.ALBUM_ARTIST to a, SongSort.DURATION to b)
        expectedFirst.forEach { (sort, first) -> assertEquals(sort.key, first, sort.apply(listOf(b, a)).first()) }
    }
    @Test fun breaksFieldTiesWithTitleAndKeepsMissingMetadata() {
        val missing = b.copy(title = "Alpha", year = 0, albumArtist = "")
        assertEquals(listOf(missing, b.copy(year = 0)), SongSort.YEAR.apply(listOf(b.copy(year = 0), missing)))
        assertEquals(2, SongSort.ALBUM_ARTIST.apply(listOf(a, missing)).size)
        assertEquals(SongSort.DEFAULT, SongSort.fromKey("removed-option"))
    }
}
