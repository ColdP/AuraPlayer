package btm.m.auraplayer.lyrics

import org.junit.Assert.*
import org.junit.Test

class LyricDepthCacheTest {
    private fun lines() = List(20) { i -> LyricLine("Row $i", null, i * 1000, (i + 1) * 1000, false, null, null, null) }

    @Test fun firstViewportPreparesCorrectDepthsAtNonzeroPlayhead() {
        val plan = initialLyricMaskDepths(lines(), FloatArray(20) { 50f }, 5500, 300f, 20f, 40f)
        assertEquals(listOf(4 to 2, 6 to 1, 7 to 2, 8 to 3, 9 to 4), plan)
        assertFalse(plan.any { it.first == 5 }) // Singing row is already sharp.
    }

    @Test fun preparationRespectsMultilineRowsAndOverlappingSingers() {
        val heights = FloatArray(20) { 50f }.apply { this[6] = 500f }
        assertEquals(listOf(4 to 2, 6 to 1), initialLyricMaskDepths(lines(), heights, 5500, 300f, 20f, 40f))
        val overlap = lines().toMutableList().apply { this[4] = LyricLine("Harmony", null, 4000, 7000, false, null, null, null) }
        val plan = initialLyricMaskDepths(overlap, FloatArray(20) { 50f }, 5500, 300f, 20f, 40f)
        assertFalse(plan.any { it.first == 4 || it.first == 5 })
        assertTrue(initialLyricMaskDepths(emptyList(), floatArrayOf(), 0, 300f, 20f, 40f).isEmpty())
    }

    @Test fun preparedMasksAreStableFromFirstFrameAndLateMasksNeverJumpIn() {
        val blend = LyricMaskBlend()
        blend.reset(setOf("prepared"))
        assertEquals(1f, blend.amount("prepared", true, 1f / 60), 0f)
        assertEquals(0f, blend.amount("late", false, 1f / 60), 0f)
        var amount = blend.amount("late", true, 1f / 60)
        assertTrue(amount > 0f && amount < .2f)
        repeat(90) {
            val next = blend.amount("late", true, 1f / 60)
            assertTrue(next >= amount && next <= 1f)
            amount = next
        }
        assertEquals(1f, amount, 0f)
        assertEquals(0f, blend.amount("late", false, 1f / 60), 0f)
        assertTrue(blend.amount("late", true, 1f / 60) < .2f) // Rebuilt after LRU eviction.
    }

    @Test fun allRowsHaveStableInitialOpacityButActualLineChangesStillAnimate() {
        val lines = lines()
        val base = FloatArray(20) { it * 70f }; val heights = FloatArray(20) { 50f }
        val focus = LyricFocus().apply { update(lines, 5500, 4, 5); layout(base, heights, 20f) }
        val transition = LyricTransition().apply { reset(base) }
        transition.update(focus, base, -1, .6f, 1f, 1f / 60, true)
        val first = FloatArray(20) { transition.alpha(it) }
        repeat(10) { transition.update(focus, base, -1, .6f, 1f, 1f / 60, false) }
        assertArrayEquals(first, FloatArray(20) { transition.alpha(it) }, 0f)
        assertEquals(.6f, first[4], 0f)
        assertEquals(1f, first[5], 0f)
        focus.update(lines, 6500, 4, 6); focus.layout(base, heights, 20f)
        transition.update(focus, base, -1, .6f, 1f, 1f / 60, false)
        assertTrue(transition.alpha(5) > .6f && transition.alpha(5) < 1f)
    }
}
