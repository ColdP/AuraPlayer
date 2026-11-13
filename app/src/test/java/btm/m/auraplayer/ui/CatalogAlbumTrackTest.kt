package btm.m.auraplayer.ui

import androidx.compose.ui.unit.dp
import btm.m.auraplayer.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAlbumTrackTest {
    @Test
    fun albumTrackRowHeightIsNinetyThreePercentOfPreviousHeight() {
        val previous = 74.dp * .88f * .92f
        assertEquals(previous.value * .93f, AlbumTrackRowHeight.value, .001f)
        assertTrue(AlbumTrackRowVerticalPadding > 0.dp)
        assertEquals(AlbumTrackRowHeight.value, 44f + AlbumTrackRowVerticalPadding.value * 2f, .001f)
    }

    @Test
    fun resolveAlbumArtistPrefersCatalogAndTrackAlbumArtistBeforeFallbacks() {
        val localTrackWithTag = Song(1, "Track", "Singer A", "Album", uri = "local:1", albumArtist = "Various Artists")
        val localTrackWithoutTag = Song(2, "Track 2", "Singer B", "Album", uri = "local:2")
        assertEquals(
            "Cloud Album Artist",
            resolveAlbumArtist("Cloud Album Artist", "Sub", listOf(localTrackWithTag)),
        )
        assertEquals(
            "Various Artists",
            resolveAlbumArtist(null, "Singer A", listOf(localTrackWithTag, localTrackWithoutTag)),
        )
        assertEquals(
            "Collection Subtitle",
            resolveAlbumArtist("", "Collection Subtitle", listOf(localTrackWithoutTag)),
        )
        assertEquals(
            "Singer B",
            resolveAlbumArtist(null, "", listOf(localTrackWithoutTag)),
        )
    }

    @Test
    fun shouldShowAlbumTrackArtistOnlyWhenArtistDiffersFromAlbumArtist() {
        val same = Song(1, "Same", "Jay Chou", "Album", uri = "cloud:1", cloud = true)
        val sameCaseAndSpace = Song(2, "Same Case", " jay chou ", "Album", uri = "cloud:2", cloud = true)
        val collab = Song(3, "Collab", "Jay Chou / Fei Yuqing", "Album", uri = "cloud:3", cloud = true)
        val explicitTagDiff = Song(4, "OST", "Singer A", "OST", uri = "local:4", albumArtist = "Various Artists")
        val blankArtist = Song(5, "Unknown", "", "Album", uri = "local:5")

        assertFalse(shouldShowAlbumTrackArtist(same, "Jay Chou"))
        assertFalse(shouldShowAlbumTrackArtist(sameCaseAndSpace, "Jay Chou"))
        assertTrue(shouldShowAlbumTrackArtist(collab, "Jay Chou"))
        assertTrue(shouldShowAlbumTrackArtist(explicitTagDiff, "Singer A"))
        assertFalse(shouldShowAlbumTrackArtist(blankArtist, "Jay Chou"))
        assertFalse(shouldShowAlbumTrackArtist(same, ""))
    }

    @Test
    fun formatAlbumTotalDurationAndReleaseSummaryMatchExpectedFormats() {
        val trackA = Song(1, "A", "Artist", "Album", duration = 20 * 60_000L + 10_000L, uri = "local:1")
        val trackB = Song(2, "B", "Artist", "Album", duration = 18 * 60_000L + 50_000L, uri = "local:2")
        val trackLong = Song(3, "C", "Artist", "Album", duration = 72 * 60_000L, uri = "local:3")

        assertNull(formatAlbumTotalDuration(emptyList()))
        assertEquals("39分钟", formatAlbumTotalDuration(listOf(trackA, trackB), language = "zh", hourUnit = "小时", minuteUnit = "分钟"))
        assertEquals("39 min", formatAlbumTotalDuration(listOf(trackA, trackB), language = "en", hourUnit = "hr", minuteUnit = "min"))
        assertEquals("1小时12分钟", formatAlbumTotalDuration(listOf(trackLong), language = "zh", hourUnit = "小时", minuteUnit = "分钟"))
        assertEquals("2022年3月9日, 39分钟", formatAlbumReleaseSummary("2022年3月9日", "39分钟"))
        assertEquals("39分钟", formatAlbumReleaseSummary(null, "39分钟"))
        assertNull(formatAlbumReleaseSummary("", null))
    }
}
