package btm.m.auraplayer.lyrics

import kotlin.math.abs

/** Choose the visually centered row, independent of song timing or unequal translation heights. */
internal inline fun nearestLyricRow(count: Int, viewportCenter: Float, rowCenter: (Int) -> Float): Int {
    var nearest = -1
    var distance = Float.POSITIVE_INFINITY
    for (i in 0 until count) {
        val candidate = abs(rowCenter(i) - viewportCenter)
        if (candidate < distance) { nearest = i; distance = candidate }
    }
    return nearest
}
