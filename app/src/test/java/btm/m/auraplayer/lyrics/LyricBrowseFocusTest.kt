package btm.m.auraplayer.lyrics

import org.junit.Assert.*
import org.junit.Test

class LyricBrowseFocusTest {
    @Test fun focusFollowsVisualCenterWhenBrowsingAheadAndBack() {
        val centers = floatArrayOf(40f, 110f, 205f, 320f, 450f)
        fun selected(offset: Float) = nearestLyricRow(centers.size, 150f) { centers[it] + offset }
        assertEquals(1, selected(0f))
        assertEquals(3, selected(-170f))
        assertEquals(4, selected(-300f))
        assertEquals(0, selected(100f))
        assertEquals(-1, nearestLyricRow(0, 150f) { error("No rows") })
    }

    @Test fun futureCenteredLyricsLightUpAndDefocusTransitionsGradually() {
        val emphasis = floatArrayOf(0f); val lit = floatArrayOf(0f)
        val scale = floatArrayOf(.97f); val blur = floatArrayOf(4f)
        fun frame(focused: Boolean) = LyricMotion.appearance(0, emphasis, lit, scale, blur,
            focused, true, 1000, 90000, true, 1f / 60f, 1, 60f, 500f, if (focused) 0f else 4f)
        frame(true)
        assertTrue(blur[0] in 0f..4f && blur[0] != 0f && blur[0] != 4f)
        assertTrue(lit[0] > 0f) // Future timestamp does not prevent manual-browse readability.
        repeat(120) { frame(true) }
        assertEquals(0f, blur[0], .05f); assertEquals(1f, lit[0], .01f)
        frame(false)
        assertTrue(blur[0] > 0f && blur[0] < 4f)
        repeat(120) { frame(false) }
        assertEquals(4f, blur[0], .05f)
    }
}
