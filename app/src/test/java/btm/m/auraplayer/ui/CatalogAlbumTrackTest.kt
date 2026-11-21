package btm.m.auraplayer.ui

import androidx.compose.ui.unit.dp
import btm.m.auraplayer.data.CatalogAlbum
import btm.m.auraplayer.data.CatalogInfo
import btm.m.auraplayer.data.CloudPlaylist
import btm.m.auraplayer.data.CloudPlaylistContent
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

        assertEquals(
            "℗ 2026 Sony Music Label Inc.",
            formatAlbumReleaseRecordLabel(
                appleMusicEnabled = true,
                appleMusicRecordLabel = "℗ 2026 Sony Music Label Inc.",
                fallbackCompany = "Sony Music",
                recordLabelPrefix = "发行公司",
            ),
        )
        assertEquals(
            "A Polydor Records release; ℗ 2026 UNIVERSAL MUSIC LLC",
            formatAlbumReleaseRecordLabel(
                appleMusicEnabled = true,
                appleMusicRecordLabel = "A Polydor Records release; ℗ 2026 UNIVERSAL MUSIC LLC",
                fallbackCompany = null,
                recordLabelPrefix = "发行公司",
            ),
        )
        assertEquals(
            "发行公司: Sony Music",
            formatAlbumReleaseRecordLabel(
                appleMusicEnabled = true,
                appleMusicRecordLabel = null,
                fallbackCompany = "Sony Music",
                recordLabelPrefix = "发行公司",
            ),
        )
        assertEquals(
            "发行公司: Sony Music",
            formatAlbumReleaseRecordLabel(
                appleMusicEnabled = false,
                appleMusicRecordLabel = "℗ 2026 Sony Music Label Inc.",
                fallbackCompany = "Sony Music",
                recordLabelPrefix = "发行公司",
            ),
        )
        assertNull(
            formatAlbumReleaseRecordLabel(
                appleMusicEnabled = false,
                appleMusicRecordLabel = null,
                fallbackCompany = " ",
                recordLabelPrefix = "发行公司",
            ),
        )
    }

    @Test
    fun albumAnimatedCoverHelpersSelectMetadataAndForceImmersiveOnlyWithVideo() {
        val collection = btm.m.auraplayer.data.MusicCollection(
            key = "album:100",
            title = "Immersive Album",
            subtitle = "Artist Name",
            artwork = "art://album",
            songs = emptyList(),
        )
        val track = Song(1, "Track 1", "Guest Artist", "", uri = "local:1")
        val resolved = representativeAlbumMotionSong(collection, listOf(track), "Artist Name")
        assertEquals("Immersive Album", resolved?.album)
        assertEquals("Artist Name", resolved?.albumArtist)
        assertTrue(shouldShowImmersiveAlbumHero(isArtist = false, albumAnimatedCoverEnabled = true, motionVideoPath = "/tmp/cover.mp4"))
        assertFalse(shouldShowImmersiveAlbumHero(isArtist = true, albumAnimatedCoverEnabled = true, motionVideoPath = "/tmp/cover.mp4"))
        assertFalse(shouldShowImmersiveAlbumHero(isArtist = false, albumAnimatedCoverEnabled = false, motionVideoPath = "/tmp/cover.mp4"))
        assertFalse(shouldShowImmersiveAlbumHero(isArtist = false, albumAnimatedCoverEnabled = true, motionVideoPath = null))
    }

    @Test
    fun artistTopSongsWidthAdaptsToMeasuredViewportAndFormatsSubtitle() {
        val margin = 20.dp
        val singleColumn = artistTopSongCardWidth(viewportWidth = 390.dp, margin = margin, columnCount = 1)
        assertEquals(350f, singleColumn.value, .01f)

        val phoneMultiColumn = artistTopSongCardWidth(viewportWidth = 390.dp, margin = margin, columnCount = 3)
        assertEquals(330f, phoneMultiColumn.value, .01f)

        val landscapeMultiColumn = artistTopSongCardWidth(viewportWidth = 840.dp, margin = margin, columnCount = 3)
        assertEquals(394f, landscapeMultiColumn.value, .01f)

        val withAlbumAndYear = Song(1, "Cruel Summer", "Taylor Swift", "Lover", year = 2019, uri = "local:1")
        val withAlbumOnly = Song(2, "Love Story", "Taylor Swift", "Fearless", year = 0, uri = "local:2")
        val withYearOnly = Song(3, "Single", "Taylor Swift", "", year = 2024, uri = "cloud:3", cloud = true)
        assertEquals("Lover · 2019", formatArtistSongAlbumYear(withAlbumAndYear))
        assertEquals("Fearless", formatArtistSongAlbumYear(withAlbumOnly))
        assertEquals("2024", formatArtistSongAlbumYear(withYearOnly))
    }

    @Test
    fun horizontalMoreIconHasThreeDots() {
        assertEquals(24f, AuraMoreHorizontalIcon.viewportWidth, .001f)
        assertEquals(24f, AuraMoreHorizontalIcon.viewportHeight, .001f)
        assertEquals(3, AuraMoreHorizontalIcon.root.size)
    }

    @Test
    fun artistInfoHelpersResolveSubtitleCountsAndSafeFileNames() {
        assertEquals(
            "Jay Chou · 周杰倫",
            resolveArtistSubtitle(listOf("Jay Chou", " 周杰倫 ", "Jay Chou"), "华语歌手", "艺术家"),
        )
        assertEquals(
            "华语歌手",
            resolveArtistSubtitle(emptyList(), " 华语歌手 ", "艺术家"),
        )
        assertEquals(
            "艺术家",
            resolveArtistSubtitle(emptyList(), "", "艺术家"),
        )

        val localTracks = listOf(
            Song(1, "A", "Artist", "Album 1", albumId = 10, uri = "local:1"),
            Song(2, "B", "Artist", "Album 1", albumId = 10, uri = "local:2"),
            Song(3, "C", "Artist", "Album 2", albumId = 20, uri = "local:3"),
        )
        assertEquals(3 to 2, resolveArtistCounts(info = null, cloud = null, tracks = localTracks))

        val cloudContent = CloudPlaylistContent(CloudPlaylist(5, "Artist", 60, ""), source = "artist")
        val cloudInfo = CatalogInfo(
            artistId = 5,
            artist = "Artist",
            artistSongCount = 128,
            artistAlbumCount = 14,
            albums = listOf(CatalogAlbum(1, "Album", "", 5, "Artist")),
        )
        assertEquals(128 to 14, resolveArtistCounts(cloudInfo, cloudContent, localTracks))

        assertEquals("A_B_C", sanitizeArtworkFileName("A/B:C", "artist"))
        assertEquals("artist", sanitizeArtworkFileName("   ", "artist"))
    }

    @Test
    fun isArtistSingleOrEpSeparatesAlbumsFromSinglesAndEps() {
        val album = CatalogAlbum(1, "LP", "", 10, "Artist", type = "专辑")
        val compilation = CatalogAlbum(2, "Best Of", "", 10, "Artist", type = "精选集")
        val unknown = CatalogAlbum(3, "Local", "", 10, "Artist", type = "")
        val singleEp = CatalogAlbum(4, "Lead", "", 10, "Artist", type = "EP/Single")
        val single = CatalogAlbum(5, "Track", "", 10, "Artist", type = "Single")
        val ep = CatalogAlbum(6, "Mini", "", 10, "Artist", type = "EP")
        val zhSingle = CatalogAlbum(7, "单曲", "", 10, "Artist", type = "单曲")

        assertFalse(isArtistSingleOrEp(album))
        assertFalse(isArtistSingleOrEp(compilation))
        assertFalse(isArtistSingleOrEp(unknown))
        assertTrue(isArtistSingleOrEp(singleEp))
        assertTrue(isArtistSingleOrEp(single))
        assertTrue(isArtistSingleOrEp(ep))
        assertTrue(isArtistSingleOrEp(zhSingle))
    }
}
