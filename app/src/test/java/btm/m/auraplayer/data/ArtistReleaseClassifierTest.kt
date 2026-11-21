package btm.m.auraplayer.data

import org.junit.Assert.*
import org.junit.Test

class ArtistReleaseClassifierTest {
    private fun song(id: Long, title: String, durationMs: Long = 210_000L): Song =
        Song(id = id, title = title, artist = "Artist", album = "Release", duration = durationMs, uri = "aura://netease/$id", cloud = true)

    private fun album(
        id: Long = 1L,
        title: String = "Release",
        count: Int = 0,
        type: String = "",
        subType: String = "",
        tracks: List<Song> = emptyList(),
        provider: String = "",
    ): CatalogAlbum = CatalogAlbum(
        id = id,
        title = title,
        artwork = "",
        artistId = 10L,
        artist = "Artist",
        count = count,
        type = type,
        subType = subType,
        tracks = tracks,
        provider = provider,
    )

    @Test
    fun singleMislabeledAsAlbumByApiIsClassifiedAsSingleOrEpUsingTrackList() {
        val single = album(
            title = "Mojito",
            count = 1,
            type = "专辑",
            tracks = listOf(song(1, "Mojito", 185_000L)),
        )
        val result = classifyArtistRelease(single)
        assertEquals(ArtistReleaseCategory.SINGLE_OR_EP, result.category)
        assertEquals(ArtistReleaseSubcategory.SINGLE, result.subcategory)
    }

    @Test
    fun remixAndInstrumentalBundleOfOneToThreeCoreSongsIsSingleOrEpEvenWithSevenTracks() {
        val remixBundle = album(
            title = "Hit Song",
            count = 7,
            type = "专辑",
            tracks = listOf(
                song(1, "Hit Song", 200_000L),
                song(2, "Hit Song (伴奏)", 200_000L),
                song(3, "Hit Song (和声伴奏)", 200_000L),
                song(4, "Hit Song (DJ版)", 210_000L),
                song(5, "Hit Song (Remix)", 240_000L),
                song(6, "Hit Song (Live)", 220_000L),
                song(7, "Hit Song (0.8x)", 230_000L),
            ),
        )
        val result = classifyArtistRelease(remixBundle)
        assertEquals(ArtistReleaseCategory.SINGLE_OR_EP, result.category)
        assertEquals(1, result.coreTracks)
        assertEquals(7, result.totalTracks)
    }

    @Test
    fun maxiSingleWithBackingTracksIsSingleOrEp() {
        val maxi = album(
            title = "Maxi",
            count = 4,
            type = "Single",
            tracks = listOf(
                song(1, "Track A", 240_000L),
                song(2, "Track B", 230_000L),
                song(3, "Track A (off vocal ver.)", 240_000L),
                song(4, "Track B (Instrumental)", 230_000L),
            ),
        )
        val result = classifyArtistRelease(maxi)
        assertEquals(ArtistReleaseCategory.SINGLE_OR_EP, result.category)
        assertEquals(2, result.coreTracks)
    }

    @Test
    fun fourToSixTrackReleaseSplitsByThirtyMinuteDurationThreshold() {
        val miniEp = album(
            title = "Mini Album",
            count = 5,
            type = "专辑",
            tracks = List(5) { idx -> song(idx + 1L, "Track ${idx + 1}", 200_000L) }, // 16.6 min
        )
        val epResult = classifyArtistRelease(miniEp)
        assertEquals(ArtistReleaseCategory.SINGLE_OR_EP, epResult.category)
        assertEquals(ArtistReleaseSubcategory.EP, epResult.subcategory)

        val progAlbum = album(
            title = "Animals",
            count = 5,
            type = "EP/Single",
            tracks = List(5) { idx -> song(idx + 10L, "Movement ${idx + 1}", 500_000L) }, // 41.6 min
        )
        val albumResult = classifyArtistRelease(progAlbum)
        assertEquals(ArtistReleaseCategory.ALBUM, albumResult.category)
        assertEquals(ArtistReleaseSubcategory.ALBUM, albumResult.subcategory)
    }

    @Test
    fun classicalMultiMovementTracksStayDistinctWhenNotVersionTags() {
        assertEquals("symphony no. 5 (movement i)", coreTrackTitle("Symphony No. 5 (Movement I)"))
        assertEquals("symphony no. 5 (movement ii)", coreTrackTitle("Symphony No. 5 (Movement II)"))
        assertEquals("song title", coreTrackTitle("Song Title (伴奏) (Live)"))
        assertEquals("song title", coreTrackTitle("Song Title - Instrumental"))
        assertEquals("track 1 - the beginning", coreTrackTitle("Track 1 - The Beginning"))
    }

    @Test
    fun sevenDistinctTracksIsClassifiedAsAlbumEvenIfShortOrLive() {
        val shortAlbum = album(
            title = "Short LP",
            count = 7,
            type = "EP/Single",
            tracks = List(7) { idx -> song(idx + 1L, "Song ${idx + 1}", 190_000L) }, // ~22 min
        )
        assertEquals(ArtistReleaseCategory.ALBUM, classifyArtistRelease(shortAlbum).category)

        val liveAlbum = album(
            title = "Live Tour",
            count = 8,
            type = "Live",
            tracks = List(8) { idx -> song(idx + 20L, "Song ${idx + 1} (Live)", 240_000L) },
        )
        val liveResult = classifyArtistRelease(liveAlbum)
        assertEquals(ArtistReleaseCategory.ALBUM, liveResult.category)
        assertEquals(8, liveResult.coreTracks)
    }

    @Test
    fun singleTrackOverThirtyMinutesAndTenToThirtyMinutesFollowDurationRules() {
        val longFormAlbum = album(
            title = "Continuous Suite",
            count = 1,
            tracks = listOf(song(1, "Part I-IV", 40L * 60_000L)),
        )
        assertEquals(ArtistReleaseCategory.ALBUM, classifyArtistRelease(longFormAlbum).category)

        val longSingleEp = album(
            title = "Extended Suite",
            count = 1,
            tracks = listOf(song(2, "Long Track", 15L * 60_000L)),
        )
        val longEpResult = classifyArtistRelease(longSingleEp)
        assertEquals(ArtistReleaseCategory.SINGLE_OR_EP, longEpResult.category)
        assertEquals(ArtistReleaseSubcategory.EP, longEpResult.subcategory)
    }

    @Test
    fun declaredCountOverridesMisleadingApiTypeBeforeTrackListArrives() {
        val neteaseSingle = album(title = "Lead", count = 2, type = "专辑")
        assertEquals(ArtistReleaseCategory.SINGLE_OR_EP, classifyArtistRelease(neteaseSingle).category)

        val qqAlbum = album(title = "Full LP", count = 11, type = "Single", provider = PROVIDER_QQ)
        assertEquals(ArtistReleaseCategory.ALBUM, classifyArtistRelease(qqAlbum).category)
    }
}
