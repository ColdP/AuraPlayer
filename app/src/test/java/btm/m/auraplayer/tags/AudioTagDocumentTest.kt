package btm.m.auraplayer.tags

import org.junit.Assert.*
import org.junit.Test

class AudioTagDocumentTest {
    @Test fun fieldDraftsPreserveUntouchedValuesAndAllowMultilineOnlyForLyricsAndComments() {
        val original = AudioTagDocument(AudioTagField.entries.associateWith { "old-${it.name}" }, null)
        val edited = original.edited(AudioTagField.TITLE, "New title")
            .edited(AudioTagField.LYRICS, "first line\nsecond line")
        assertEquals("New title", edited[AudioTagField.TITLE])
        assertEquals("first line\nsecond line", edited[AudioTagField.LYRICS])
        assertEquals(original[AudioTagField.ARTIST], edited[AudioTagField.ARTIST])
        assertFalse(AudioTagField.TITLE.multiline)
        assertTrue(AudioTagField.COMMENT.multiline && AudioTagField.LYRICS.multiline)
    }

    @Test fun trackNumberAcceptsOnlyDigitsAndEmptyRemainsValid() {
        val original = AudioTagDocument(emptyMap(), null)
        assertEquals("12", original.edited(AudioTagField.TRACK, "12")[AudioTagField.TRACK])
        assertEquals("", original.edited(AudioTagField.TRACK, "")[AudioTagField.TRACK])
        for (invalid in listOf("-1", "1.5", "12a", "１")) {
            assertFalse(AudioTagField.TRACK.accepts(invalid))
            try { original.edited(AudioTagField.TRACK, invalid); fail("Accepted $invalid") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun coverEqualityUsesImageBytesRatherThanArrayIdentity() {
        assertEquals(TagCover(byteArrayOf(1, 2, 3), "image/png", 2, 1), TagCover(byteArrayOf(1, 2, 3), "image/png", 2, 1))
        assertNotEquals(TagCover(byteArrayOf(1, 2), "image/png"), TagCover(byteArrayOf(1, 3), "image/png"))
    }
}
