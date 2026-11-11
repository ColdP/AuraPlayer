package btm.m.auraplayer.lyrics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PlayerLyricsTest {
    @Test fun pairsTranslationsByTimestampRatherThanIndex() {
        val lines = LyricParse.parse("[00:00.00]作曲：Nobody\n[00:10.00]The moon is full\n[00:20.00]Shadows grow", "[00:10.00]月满之时\n[00:20.00]暗影漫延")
        assertEquals(3, lines.size)
        assertNull(lines[0].translation)
        assertEquals("月满之时", lines[1].translation)
        assertEquals("暗影漫延", lines[2].translation)
    }
    @Test fun preservesPronunciationSeparatelyFromTranslation() {
        val lines = LyricParse.parse("[00:01.00]君が好き\n[00:03.00]夜の中", "[00:01.00]我喜欢你\n[00:03.00]夜晚之中", "[00:01.00]kimi ga suki\n[00:03.00]yoru no naka")
        assertEquals("kimi ga suki", lines[0].roma)
        assertEquals("我喜欢你", lines[0].under(1))
        assertEquals("kimi ga suki", lines[0].under(2))
        assertEquals("kimi ga suki\n我喜欢你", lines[0].under(3))
        assertNull(lines[0].under(0))
    }
    @Test fun preservesWordTimingAndFractionalFill() {
        val lines = LyricParse.parse("[1000,2000](1000,1000,0)Hello (2000,1000,0)world\n[4000,1000](4000,1000,0)Again")
        assertTrue(lines.first().hasWords())
        assertEquals(1000, lines.first().start)
        assertTrue(lines.first().sungChars(1500) > 0)
        assertTrue(lines.first().sungChars(1500) < lines.first().text.length)
    }
    @Test fun rejectsWrongSongAndKeepsOriginalTimingWhenBorrowing() {
        val own = LyricParse.parse("[00:01.00]君が好き\n[00:03.00]夜の中\n[00:05.00]星が光る")
        val translated = LyricParse.parse("[00:01.10]君が好き\n[00:03.10]夜の中\n[00:05.10]星が光る", "[00:01.10]我喜欢你\n[00:03.10]夜晚之中\n[00:05.10]星光闪耀")
        val result = LyricParse.borrow(own, translated, true, true)
        assertTrue(result.matched)
        assertEquals(own.map { it.start }, result.lines.map { it.start })
        assertEquals("我喜欢你", result.lines.first().translation)
        val unrelated = LyricParse.parse("[00:01.00]Completely different\n[00:03.00]Another recording", "[00:01.00]不同\n[00:03.00]另一首歌")
        assertEquals(0, LyricParse.borrow(own, unrelated, true, true).translations)
    }
    @Test fun delayedSpringStartsInOrderAndConvergesWithoutOvershooting() {
        val scroll = floatArrayOf(0f); val aim = floatArrayOf(0f); val next = floatArrayOf(100f)
        val velocity = floatArrayOf(0f); val start = longArrayOf(100)
        LyricMotion.step(0, scroll, aim, next, velocity, start, 50, 1f / 60, false, 170f, 28.7f, 8000f)
        assertEquals(0f, scroll[0], .001f)
        repeat(240) { LyricMotion.step(0, scroll, aim, next, velocity, start, 100L + it * 16, 1f / 60, false, 170f, 28.7f, 8000f) }
        assertTrue(abs(scroll[0] - 100f) < .5f)
        assertTrue(scroll[0] <= 100.5f)
    }
    @Test fun overlapFocusLightsBothSingers() {
        val lines = listOf(LyricLine("A", null, 0, 3000, false, null, null, null), LyricLine("B", null, 1000, 4000, true, null, null, null))
        val focus = LyricFocus()
        focus.update(lines, 1500, 4, 1)
        assertTrue(focus.contains(0)); assertTrue(focus.contains(1)); assertEquals(2, focus.count)
    }
}
