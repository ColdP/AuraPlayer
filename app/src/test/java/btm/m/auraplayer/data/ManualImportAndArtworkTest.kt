// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualImportAndArtworkTest {
    private fun draft(index: Int): ManualAudioDraft = ManualAudioDraft(
        uri = "content://com.android.providers.media.documents/document/audio%3A$index",
        fileName = "track_$index.flac",
        title = "Track $index",
        artist = "Artist $index",
        album = "Album $index",
        duration = 180_000L,
        artwork = LocalAudioArtwork.uriFor(
            songUri = "content://com.android.providers.media.documents/document/audio%3A$index",
        ),
    )

    @Test
    fun localAudioArtworkRoundTripAndVersionBumpPreserveUris() {
        val songUri = "content://media/external/audio/media/42"
        val albumArt = "content://media/external/audio/albumart/99"
        val built = LocalAudioArtwork.uriFor(songUri = songUri, albumArtUri = albumArt, version = 10L)

        val parsed = LocalAudioArtwork.parse(built)
        assertNotNull(parsed)
        assertEquals(songUri, parsed!!.songUri)
        assertEquals(albumArt, parsed.albumArtUri)
        assertEquals(10L, parsed.version)

        val bumped = LocalAudioArtwork.bumpVersion(built, songUri = songUri, version = 250L)
        val bumpedParsed = LocalAudioArtwork.parse(bumped)
        assertNotNull(bumpedParsed)
        assertEquals(songUri, bumpedParsed!!.songUri)
        assertEquals(albumArt, bumpedParsed.albumArtUri)
        assertEquals(250L, bumpedParsed.version)

        val upgraded = LocalAudioArtwork.bumpVersion(albumArt, songUri = songUri, version = 7L)
        val upgradedParsed = LocalAudioArtwork.parse(upgraded)
        assertNotNull(upgradedParsed)
        assertEquals(songUri, upgradedParsed!!.songUri)
        assertEquals(albumArt, upgradedParsed.albumArtUri)
        assertEquals(7L, upgradedParsed.version)

        assertNull(LocalAudioArtwork.parse("https://example.com/cover.jpg"))
    }

    @Test
    fun mergeManualAudioSelectionDeduplicatesAndCapsAtTwenty() {
        val firstBatch = (1..15).map(::draft)
        val (afterFirst, skippedFirst) = mergeManualAudioSelection(emptyList(), firstBatch)
        assertEquals(15, afterFirst.size)
        assertEquals(0, skippedFirst)

        // Include duplicates of existing URIs (14, 15) plus new items (16..24 -> 9 unique).
        val secondBatch = listOf(draft(14), draft(15)) + (16..24).map(::draft)
        val (merged, skippedSecond) = mergeManualAudioSelection(
            existing = afterFirst,
            incoming = secondBatch,
            maxCount = MAX_MANUAL_IMPORT_COUNT,
        )
        assertEquals(MAX_MANUAL_IMPORT_COUNT, merged.size)
        assertEquals(4, skippedSecond)
        assertEquals((1..20).map { "track_$it.flac" }, merged.map { it.fileName })
    }

    @Test
    fun removeManualAudioSelectionAndStableManualSongIdBehaveDeterministically() {
        val list = listOf(draft(1), draft(2), draft(3))
        val updated = removeManualAudioSelection(list, list[1].uri)
        assertEquals(listOf("track_1.flac", "track_3.flac"), updated.map { it.fileName })

        val id1 = stableManualSongId(list[0].uri)
        val id2 = stableManualSongId(list[2].uri)
        assertTrue(id1 < 0L)
        assertTrue(id2 < 0L)
        assertEquals(id1, stableManualSongId(list[0].uri))
        assertNotEquals(id1, id2)
    }

    @Test
    fun localAudioArtworkCachesExtractedBytesAndMatchesSameAlbumArt() {
        val song1 = LocalAudioArtwork.uriFor(
            songUri = "content://media/external/audio/media/101",
            albumArtUri = "content://media/external/audio/albumart/555",
            version = 100L,
        )
        val song2 = LocalAudioArtwork.uriFor(
            songUri = "content://media/external/audio/media/102",
            albumArtUri = "content://media/external/audio/albumart/555",
            version = 101L,
        )
        val otherAlbum = LocalAudioArtwork.uriFor(
            songUri = "content://media/external/audio/media/103",
            albumArtUri = "content://media/external/audio/albumart/999",
            version = 102L,
        )
        assertTrue(LocalAudioArtwork.sameAlbumArtUri(song1, song2))
        assertFalse(LocalAudioArtwork.sameAlbumArtUri(song1, otherAlbum))

        val payload = byteArrayOf(1, 2, 3, 4, 5)
        LocalAudioArtwork.cacheBytes(song1, payload)
        assertEquals(payload.toList(), LocalAudioArtwork.cachedBytes(song1)?.toList())
        assertNull(LocalAudioArtwork.cachedBytes(song2))
    }
}
