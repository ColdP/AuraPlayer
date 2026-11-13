package btm.m.auraplayer.ui.player

import org.junit.Assert.*
import org.junit.Test

class LyricShareTemplatesTest {
    @Test fun definesTwelveDistinctTemplates() {
        val templates = lyricShareTemplates()
        assertEquals(12, LyricShareTemplateId.entries.size)
        assertEquals(12, templates.size)
        assertEquals(12, templates.map { it.id }.distinct().size)
        assertTrue(templates.all { it.title.isNotBlank() })
    }

    @Test fun selectionToggleRespectsSixteenLineLimit() {
        var selected = (0 until 16).toSet()
        // Cannot add a 17th line once at the cap.
        selected = toggleLyricShareIndex(selected, 16)
        assertEquals(16, selected.size)
        assertFalse(16 in selected)
        // Deselecting an existing line frees a slot for another line.
        selected = toggleLyricShareIndex(selected, 3)
        assertEquals(15, selected.size)
        assertFalse(3 in selected)
        selected = toggleLyricShareIndex(selected, 16)
        assertEquals(16, selected.size)
        assertTrue(16 in selected)
    }

    @Test fun validatesSupportedFontHeaders() {
        assertTrue(isSupportedFontHeader(byteArrayOf(0x00, 0x01, 0x00, 0x00)))
        assertTrue(isSupportedFontHeader("OTTO".toByteArray(Charsets.US_ASCII)))
        assertTrue(isSupportedFontHeader("ttcf".toByteArray(Charsets.US_ASCII)))
        assertFalse(isSupportedFontHeader("<!ht".toByteArray(Charsets.US_ASCII)))
        assertFalse(isSupportedFontHeader(byteArrayOf(0x00, 0x01)))
    }

    @Test fun computesExportDimensionsRespectingQualityAndCap() {
        assertEquals(400 to 600, computeExportDimensions(1000, 1500, LyricShareImageQuality.VERY_LOW))
        assertEquals(650 to 975, computeExportDimensions(1000, 1500, LyricShareImageQuality.LOW))
        assertEquals(1000 to 1500, computeExportDimensions(1000, 1500, LyricShareImageQuality.NORMAL))
        assertEquals(1500 to 2250, computeExportDimensions(1000, 1500, LyricShareImageQuality.HIGH))
        assertEquals(2000 to 3000, computeExportDimensions(1000, 1500, LyricShareImageQuality.ULTRA))
        // Oversized inputs cap longest edge cleanly while keeping aspect ratio.
        val (w, h) = computeExportDimensions(2000, 3000, LyricShareImageQuality.ULTRA, maxEdge = 4096)
        assertEquals(4096, h)
        assertEquals(2731, w)
    }

    @Test fun includesRequestedFiltersAndLeicaModes() {
        val filters = LyricShareFilter.entries
        assertTrue(LyricShareFilter.DEFAULT in filters)
        assertTrue(LyricShareFilter.LEICA_M3 in filters)
        assertTrue(LyricShareFilter.LEICA_M9 in filters)
        assertTrue(LyricShareFilter.LEICA_M10R in filters)
        assertTrue(LyricShareFilter.MILLENNIUM in filters)
        assertTrue(LyricShareFilter.ABAO in filters)
        assertTrue(filters.size >= 10)
    }

    @Test fun fontFolderHelpersHandleExtensionsAndDuplicateStems() {
        assertTrue(isSupportedFontFileName("LXGW.ttf"))
        assertTrue(isSupportedFontFileName("SourceHan.OTF"))
        assertTrue(isSupportedFontFileName("Noto.ttc"))
        assertFalse(isSupportedFontFileName("notes.txt"))
        assertEquals("My_Font.ttf", sanitizeFontFileName("My/Font.ttf"))
        assertEquals("FontName.ttf", sanitizeFontFileName("FontName"))
        val names = listOf("WenKai.ttf", "Song.ttf", "Song.otf")
        assertEquals("WenKai", fontMenuDisplayName("WenKai.ttf", names))
        assertEquals("Song.ttf", fontMenuDisplayName("Song.ttf", names))
        assertEquals("Song.otf", fontMenuDisplayName("Song.otf", names))
    }

    @Test fun parsesSavedShareOptionEnumsWithFallback() {
        assertEquals(LyricShareFilter.LEICA_M9, parseShareEnumOrDefault("LEICA_M9", LyricShareFilter.DEFAULT))
        assertEquals(LyricShareFilter.DEFAULT, parseShareEnumOrDefault("UNKNOWN_FILTER", LyricShareFilter.DEFAULT))
        assertEquals(LyricShareImageQuality.HIGH, parseShareEnumOrDefault("HIGH", LyricShareImageQuality.NORMAL))
        assertEquals(LyricShareLineSpacing.LOOSE, parseShareEnumOrDefault("LOOSE", LyricShareLineSpacing.REGULAR))
        assertEquals(LyricShareFont.ROUNDED, parseShareEnumOrDefault(null, LyricShareFont.ROUNDED))
    }

    @Test fun lightPaperFlagMatchesExpectedThreeTemplates() {
        val lightPaperIds = lyricShareTemplates().filter { it.lightPaper }.map { it.id }.toSet()
        assertEquals(
            setOf(
                LyricShareTemplateId.EDITORIAL_PAPER,
                LyricShareTemplateId.SWISS_GRID,
                LyricShareTemplateId.POLAROID,
            ),
            lightPaperIds,
        )
    }
}
