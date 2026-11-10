package btm.m.auraplayer.data

import org.junit.Assert.*
import org.junit.Test

class LibraryFilterTest {
    private fun accepts(path: String, duration: Long = 180000, white: Boolean = false, blocked: Set<String> = emptySet(), enabled: Boolean = false, minimum: Int = 0) =
        LibraryFilter.accepts(path, duration, white, blocked, enabled, minimum)

    @Test fun blacklistIncludesChildrenButNotSiblingPrefixes() {
        val blocked = setOf("Music/Voice")
        assertFalse(accepts("Music/Voice/", blocked = blocked))
        assertFalse(accepts("Music/Voice/Archive/", blocked = blocked))
        assertTrue(accepts("Music/Voices/", blocked = blocked))
    }
    @Test fun whitelistOverridesBlacklistAndExcludesMusicLikeFolders() {
        assertTrue(accepts("Music/Albums/", white = true, blocked = setOf("Music/")))
        assertFalse(accepts("MusicBackup/", white = true))
        assertFalse(accepts("Download/Music/", white = true))
        assertFalse(accepts("", white = true))
    }
    @Test fun durationThresholdIncludesBoundaryAndCanBeDisabled() {
        assertFalse(accepts("Music/", 29999, enabled = true, minimum = 30))
        assertTrue(accepts("Music/", 30000, enabled = true, minimum = 30))
        assertTrue(accepts("Music/", 1, enabled = false, minimum = 60))
        assertTrue(accepts("Music/", 0, enabled = true, minimum = 0))
    }
    @Test fun normalizesPickerPathsWithoutBlockingEverythingForEmptyEntry() {
        assertFalse(accepts("Music/Voice", blocked = setOf("/Music\\Voice/")))
        assertTrue(accepts("Music/", blocked = setOf("")))
    }
    @Test fun selectedFolderDoesNotHideSamePathOnAnotherStorageVolume() {
        val blocked = setOf("external_primary:Music/Voice/")
        assertFalse(LibraryFilter.accepts("Music/Voice/", 1000, false, blocked, false, 0, "external_primary"))
        assertTrue(LibraryFilter.accepts("Music/Voice/", 1000, false, blocked, false, 0, "abcd-1234"))
    }
    @Test fun songsOnDifferentStorageVolumesHaveDifferentIdentities() {
        val internal = Song(1, "A", "B", "C", uri = "content://media/external_primary/audio/media/1")
        val sd = internal.copy(uri = "content://media/abcd-1234/audio/media/1")
        assertNotEquals(internal.key, sd.key)
    }
}
