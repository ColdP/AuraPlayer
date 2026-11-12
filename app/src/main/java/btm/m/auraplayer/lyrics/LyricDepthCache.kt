package btm.m.auraplayer.lyrics

/** Build the initial viewport's masks before publishing a new lyric layout. Includes overlap
 * groups and multiline rows, using exactly the renderer's initial scroll anchor. */
internal fun initialLyricMaskDepths(
    lines: List<LyricLine>, heights: FloatArray, positionMs: Int,
    viewportHeight: Float, gap: Float, overscan: Float,
): List<Pair<Int, Int>> {
    if (lines.isEmpty() || heights.size != lines.size || viewportHeight <= 0f) return emptyList()
    val focus = LyricFocus()
    val fallback = lines.indexOfLast { it.start <= positionMs }.coerceAtLeast(0)
    focus.update(lines, positionMs, LyricFocus.DEFAULT_LIMIT, fallback)
    var top = 0f
    val base = FloatArray(lines.size) { i -> top.also { top += heights[i] + gap } }
    focus.layout(base, heights, gap)
    val scroll = focus.position(focus.last, base)
    val anchor = viewportHeight * .15f
    return lines.indices.mapNotNull { i ->
        val y = anchor + focus.position(i, base) - scroll
        if (focus.contains(i) || y + heights[i] < -overscan || y > viewportHeight + overscan) null
        else i to focus.distance(i).coerceIn(1, 4)
    }
}

/** Masks arriving after the first frame blend in instead of replacing sharp glyphs at once. */
internal class LyricMaskBlend {
    private val amounts = mutableMapOf<String, Float>()

    fun reset(prepared: Set<String>) {
        amounts.clear()
        prepared.forEach { amounts[it] = 1f }
    }

    fun amount(key: String, ready: Boolean, dt: Float): Float {
        if (!ready) { amounts.remove(key); return 0f }
        val previous = amounts[key] ?: 0f
        val next = LyricMotion.approach(previous, 1f, dt.coerceIn(0f, .05f), .10f)
        return (if (next > .995f) 1f else next).also { amounts[key] = it }
    }
}
