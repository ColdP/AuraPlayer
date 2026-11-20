package btm.m.auraplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameBlockRulesTest {
    @Test fun keywordsMatchLiteralSubstringsRegardlessOfCase() {
        val matcher = FilenameBlockRules(keywords = " recording \n\n[draft]").compile()
        assertTrue(matcher.blocks("Old RECORDING.mp3"))
        assertTrue(matcher.blocks("Mix [draft].flac"))
        assertFalse(matcher.blocks("A draft song.mp3"))
        assertFalse(matcher.blocks(""))
    }

    @Test fun examplesMatchFullFilenamesIncludingExtensions() {
        val matcher = FilenameBlockRules(patterns = "^recording\n\\.(amr|m4r)$").compile()
        assertNull(matcher.invalidPatternLine)
        assertTrue(matcher.blocks("RECORDING-01.wav"))
        assertTrue(matcher.blocks("Call.AMR"))
        assertFalse(matcher.blocks("My recording.wav"))
        assertFalse(matcher.blocks("Call.amr.mp3"))
    }

    @Test fun reportsTheFirstInvalidLineWithoutBreakingOtherStoredRules() {
        val matcher = FilenameBlockRules(patterns = "^voice\n\n[\n(").compile()
        assertEquals(3, matcher.invalidPatternLine)
        assertTrue(matcher.blocks("voice.mp3"))
        assertFalse(matcher.blocks("music.mp3"))
    }

    @Test fun pausingKeepsRulesAndSavingRemovesDuplicateLines() {
        val rules = FilenameBlockRules(enabled = false, keywords = " Voice \r\nVOICE\nDraft", patterns = "\\.amr$\n\n\\.amr$").normalized()
        assertEquals(3, rules.count)
        assertFalse(rules.compile().blocks("Voice.amr"))
        assertTrue(rules.copy(enabled = true).compile().blocks("Voice.amr"))
    }
}
