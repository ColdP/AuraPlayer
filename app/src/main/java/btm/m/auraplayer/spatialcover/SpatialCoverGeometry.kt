// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.spatialcover

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Relative inverse depth normalization and HyperExtreme displacement field shaping. */
internal class SpatialDisplacementFields(
    val backgroundK: FloatArray,
    val foregroundK: FloatArray,
)

internal object SpatialDepthGeometry {
    private const val K_MIN = 0.15f
    private const val BG_WARP_GAIN = 0.62f
    private const val LIM_SLOPE = 0.45f
    private const val PAN_UV = 0.058f
    private const val FG_WARP_SPAN_PX = 6.5f
    private const val DEPTH_STD_REF = 0.32f
    private const val GREY_CLOSE_R = 2
    private const val EDGE_SMOOTH_R = 12
    private const val EDGE_RING_IN = 1f
    private const val EDGE_RING_OUT = 14f

    fun normalize(values: FloatArray, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0 && values.size == width * height)
        val sorted = values.filter { it.isFinite() }.sorted()
        require(sorted.isNotEmpty()) { "Depth model returned no finite values" }
        val low = sorted[(sorted.size * .01f).toInt().coerceAtMost(sorted.lastIndex)]
        val high = sorted[(sorted.size * .99f).toInt().coerceAtMost(sorted.lastIndex)]
        val range = high - low
        if (range <= 1e-6f) return FloatArray(values.size) { .5f }
        var result = FloatArray(values.size) { i ->
            if (values[i].isFinite()) ((values[i] - low) / range).coerceIn(0f, 1f) else .5f
        }
        result = median3(result, width, height)
        repeat(2) { result = smooth(result, width, height) }
        return result
    }

    fun median3(values: FloatArray, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0 && values.size == width * height)
        val dst = FloatArray(values.size)
        val win = FloatArray(9)
        for (y in 0 until height) {
            val y0 = max(0, y - 1)
            val y1 = min(height - 1, y + 1)
            val row = y * width
            for (x in 0 until width) {
                val x0 = max(0, x - 1)
                val x1 = min(width - 1, x + 1)
                var count = 0
                for (yy in y0..y1) {
                    val r = yy * width
                    for (xx in x0..x1) win[count++] = values[r + xx]
                }
                for (i in 1 until count) {
                    val v = win[i]
                    var j = i - 1
                    while (j >= 0 && win[j] > v) {
                        win[j + 1] = win[j]
                        j--
                    }
                    win[j + 1] = v
                }
                dst[row + x] = win[count / 2]
            }
        }
        return dst
    }

    private fun smooth(values: FloatArray, width: Int, height: Int): FloatArray =
        FloatArray(values.size) { i ->
            val x = i % width
            val y = i / width
            val center = values[i]
            var sum = center * 4f
            var weight = 4f
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val value = values[ny * width + nx]
                val delta = abs(value - center) / .05f
                val w = 1f / (1f + delta * delta)
                sum += value * w
                weight += w
            }
            sum / weight
        }

    fun greyClosing(values: FloatArray, width: Int, height: Int, radius: Int = GREY_CLOSE_R): FloatArray {
        require(width > 0 && height > 0 && values.size == width * height)
        if (radius <= 0) return values.copyOf()
        val dilated = grayMorph(values, width, height, radius, dilate = true)
        return grayMorph(dilated, width, height, radius, dilate = false)
    }

    private fun grayMorph(src: FloatArray, w: Int, h: Int, r: Int, dilate: Boolean): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var best = if (dilate) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
                for (nx in max(0, x - r)..min(w - 1, x + r)) {
                    val v = src[row + nx]
                    best = if (dilate) max(best, v) else min(best, v)
                }
                tmp[row + x] = best
            }
        }
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                var best = if (dilate) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
                for (ny in y0..y1) {
                    val v = tmp[ny * w + x]
                    best = if (dilate) max(best, v) else min(best, v)
                }
                out[y * w + x] = best
            }
        }
        return out
    }

    fun limitSlope(
        field: FloatArray,
        width: Int,
        height: Int,
        limX: Float,
        limY: Float,
        mask: BooleanArray? = null,
    ) {
        for (y in 0 until height) {
            val row = y * width
            val up = if (y > 0) row - width else -1
            for (x in 0 until width) {
                val i = row + x
                if (mask != null && !mask[i]) continue
                var v = field[i]
                if (x > 0 && (mask == null || mask[i - 1])) v = min(v, field[i - 1] + limX)
                if (up >= 0 && (mask == null || mask[up + x])) v = min(v, field[up + x] + limY)
                field[i] = v
            }
        }
        for (y in height - 1 downTo 0) {
            val row = y * width
            val down = if (y < height - 1) row + width else -1
            for (x in width - 1 downTo 0) {
                val i = row + x
                if (mask != null && !mask[i]) continue
                var v = field[i]
                if (x < width - 1 && (mask == null || mask[i + 1])) v = min(v, field[i + 1] + limX)
                if (down >= 0 && (mask == null || mask[down + x])) v = min(v, field[down + x] + limY)
                field[i] = v
            }
        }
    }

    fun extendField(
        field: FloatArray,
        mask: BooleanArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        if (radius <= 0) return
        val n = width * height
        val dist = FloatArray(n)
        val near = IntArray(n)
        chamferNearest(mask, width, height, dist, near)
        val r = radius.toFloat()
        for (i in 0 until n) {
            if (!mask[i] && dist[i] <= r && near[i] >= 0) field[i] = field[near[i]]
        }
    }

    fun harmonicDepthFill(
        depth: FloatArray,
        hole: BooleanArray,
        width: Int,
        height: Int,
        checkpoint: () -> Unit = {},
    ): FloatArray {
        val n = width * height
        val seed = BooleanArray(n) { !hole[it] }
        val dist = FloatArray(n)
        val near = IntArray(n)
        chamferNearest(seed, width, height, dist, near)
        val result = depth.copyOf()
        for (i in 0 until n) {
            if (hole[i] && near[i] >= 0) result[i] = depth[near[i]]
        }
        if (seed.none { it } || hole.none { it }) return result
        solveLaplaceMultigrid(result, hole, width, height, checkpoint)
        return result
    }

    private fun solveLaplaceMultigrid(
        hint: FloatArray,
        hole: BooleanArray,
        w: Int,
        h: Int,
        checkpoint: () -> Unit,
    ) {
        val lvW = ArrayList<Int>()
        val lvH = ArrayList<Int>()
        val lvD = ArrayList<FloatArray>()
        val lvM = ArrayList<BooleanArray>()
        lvW.add(w); lvH.add(h); lvD.add(hint); lvM.add(hole)
        while (true) {
            val pw = lvW.last()
            val ph = lvH.last()
            if (pw <= 16 || ph <= 16) break
            val nw = (pw + 1) / 2
            val nh = (ph + 1) / 2
            val pd = lvD.last()
            val pm = lvM.last()
            val nd = FloatArray(nw * nh)
            val nm = BooleanArray(nw * nh)
            var anyFree = false
            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    var sum = 0f
                    var cnt = 0
                    for (dy in 0..1) {
                        val sy = y * 2 + dy
                        if (sy >= ph) continue
                        for (dx in 0..1) {
                            val sx = x * 2 + dx
                            if (sx >= pw) continue
                            val j = sy * pw + sx
                            if (!pm[j]) { sum += pd[j]; cnt++ }
                        }
                    }
                    val idx = y * nw + x
                    if (cnt > 0) {
                        nd[idx] = sum / cnt
                        nm[idx] = false
                        anyFree = true
                    } else {
                        nm[idx] = true
                    }
                }
            }
            if (!anyFree) break
            lvW.add(nw); lvH.add(nh); lvD.add(nd); lvM.add(nm)
        }
        for (lv in lvD.size - 1 downTo 0) {
            checkpoint()
            val fw = lvW[lv]
            val fh = lvH[lv]
            val fd = lvD[lv]
            val fm = lvM[lv]
            if (lv < lvD.size - 1) {
                val cd = lvD[lv + 1]
                val cw = lvW[lv + 1]
                val ch = lvH[lv + 1]
                for (y in 0 until fh) {
                    val gy = ((y + 0.5f) * ch / fh - 0.5f).coerceIn(0f, (ch - 1).toFloat())
                    val y0 = gy.toInt().coerceIn(0, ch - 1)
                    val y1 = min(y0 + 1, ch - 1)
                    val fy = gy - y0
                    val row = y * fw
                    for (x in 0 until fw) {
                        val idx = row + x
                        if (!fm[idx]) continue
                        val gx = ((x + 0.5f) * cw / fw - 0.5f).coerceIn(0f, (cw - 1).toFloat())
                        val x0 = gx.toInt().coerceIn(0, cw - 1)
                        val x1 = min(x0 + 1, cw - 1)
                        val fx = gx - x0
                        val v00 = cd[y0 * cw + x0]
                        val v01 = cd[y0 * cw + x1]
                        val v10 = cd[y1 * cw + x0]
                        val v11 = cd[y1 * cw + x1]
                        fd[idx] = (v00 * (1f - fx) + v01 * fx) * (1f - fy) +
                            (v10 * (1f - fx) + v11 * fx) * fy
                    }
                }
            }
            val iters = if (lv == 0) 10 else 8
            repeat(iters) {
                for (y in 0 until fh) {
                    val row = y * fw
                    for (x in 0 until fw) {
                        val idx = row + x
                        if (!fm[idx]) continue
                        var s = 0f; var c = 0
                        if (x > 0) { s += fd[idx - 1]; c++ }
                        if (x < fw - 1) { s += fd[idx + 1]; c++ }
                        if (y > 0) { s += fd[idx - fw]; c++ }
                        if (y < fh - 1) { s += fd[idx + fw]; c++ }
                        if (c > 0) fd[idx] = s / c
                    }
                }
                for (y in fh - 1 downTo 0) {
                    val row = y * fw
                    for (x in fw - 1 downTo 0) {
                        val idx = row + x
                        if (!fm[idx]) continue
                        var s = 0f; var c = 0
                        if (x > 0) { s += fd[idx - 1]; c++ }
                        if (x < fw - 1) { s += fd[idx + 1]; c++ }
                        if (y > 0) { s += fd[idx - fw]; c++ }
                        if (y < fh - 1) { s += fd[idx + fw]; c++ }
                        if (c > 0) fd[idx] = s / c
                    }
                }
            }
        }
    }

    private fun chamferNearest(
        seed: BooleanArray,
        w: Int,
        h: Int,
        dist: FloatArray,
        near: IntArray,
    ) {
        val n = w * h
        for (i in 0 until n) {
            if (seed[i]) { dist[i] = 0f; near[i] = i } else { dist[i] = Float.MAX_VALUE; near[i] = -1 }
        }
        val d2 = 1.41421356f
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (dist[i] == 0f) continue
                var bd = dist[i]; var bs = near[i]
                if (x > 0 && dist[i - 1] + 1f < bd) { bd = dist[i - 1] + 1f; bs = near[i - 1] }
                if (y > 0) {
                    val up = i - w
                    if (dist[up] + 1f < bd) { bd = dist[up] + 1f; bs = near[up] }
                    if (x > 0 && dist[up - 1] + d2 < bd) { bd = dist[up - 1] + d2; bs = near[up - 1] }
                    if (x < w - 1 && dist[up + 1] + d2 < bd) { bd = dist[up + 1] + d2; bs = near[up + 1] }
                }
                dist[i] = bd; near[i] = bs
            }
        }
        for (y in h - 1 downTo 0) {
            val row = y * w
            for (x in w - 1 downTo 0) {
                val i = row + x
                if (dist[i] == 0f) continue
                var bd = dist[i]; var bs = near[i]
                if (x < w - 1 && dist[i + 1] + 1f < bd) { bd = dist[i + 1] + 1f; bs = near[i + 1] }
                if (y < h - 1) {
                    val dn = i + w
                    if (dist[dn] + 1f < bd) { bd = dist[dn] + 1f; bs = near[dn] }
                    if (x < w - 1 && dist[dn + 1] + d2 < bd) { bd = dist[dn + 1] + d2; bs = near[dn + 1] }
                    if (x > 0 && dist[dn - 1] + d2 < bd) { bd = dist[dn - 1] + d2; bs = near[dn - 1] }
                }
                dist[i] = bd; near[i] = bs
            }
        }
    }

    private fun percentile(values: FloatArray, q: Float, mask: BooleanArray? = null): Float {
        val sample = ArrayList<Float>()
        val stride = max(1, values.size / 65536)
        var i = 0
        while (i < values.size) {
            if (mask == null || mask[i]) {
                val v = values[i]
                if (v.isFinite()) sample.add(v)
            }
            i += stride
        }
        if (sample.isEmpty()) return 0.5f
        sample.sort()
        val idx = (q.coerceIn(0f, 1f) * (sample.size - 1)).roundToInt().coerceIn(0, sample.lastIndex)
        return sample[idx]
    }

    private fun dilateBool(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return src.copyOf()
        val tmp = BooleanArray(src.size)
        val out = BooleanArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var hit = false
                for (nx in max(0, x - r)..min(w - 1, x + r)) {
                    if (src[row + nx]) { hit = true; break }
                }
                tmp[row + x] = hit
            }
        }
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                var hit = false
                for (ny in y0..y1) {
                    if (tmp[ny * w + x]) { hit = true; break }
                }
                out[y * w + x] = hit
            }
        }
        return out
    }

    private fun boxBlur3(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return src.copyOf()
        val tmp = FloatArray(src.size)
        val dst = src.copyOf()
        repeat(3) {
            boxBlurH(dst, tmp, w, h, r)
            boxBlurV(tmp, dst, w, h, r)
        }
        return dst
    }

    private fun boxBlurH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (j in -r..r) sum += src[row + j.coerceIn(0, w - 1)]
            dst[row] = sum * norm
            for (x in 1 until w) {
                sum += src[row + (x + r).coerceAtMost(w - 1)]
                sum -= src[row + (x - r - 1).coerceAtLeast(0)]
                dst[row + x] = sum * norm
            }
        }
    }

    private fun boxBlurV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        for (x in 0 until w) {
            var sum = 0f
            for (j in -r..r) sum += src[j.coerceIn(0, h - 1) * w + x]
            dst[x] = sum * norm
            for (y in 1 until h) {
                sum += src[(y + r).coerceAtMost(h - 1) * w + x]
                sum -= src[(y - r - 1).coerceAtLeast(0) * w + x]
                dst[y * w + x] = sum * norm
            }
        }
    }

    fun buildDisplacementFields(
        rawDepth: FloatArray,
        mask: FloatArray,
        fillRegion: BooleanArray,
        width: Int,
        height: Int,
        checkpoint: () -> Unit = {},
    ): SpatialDisplacementFields {
        val n = width * height
        val depthMed = median3(rawDepth, width, height)
        checkpoint()
        var sum = 0.0
        var sqSum = 0.0
        for (i in 0 until n) {
            val v = depthMed[i]
            sum += v
            sqSum += v.toDouble() * v
        }
        val mean = sum / n.coerceAtLeast(1)
        val std = kotlin.math.sqrt((sqSum / n.coerceAtLeast(1) - mean * mean).coerceAtLeast(0.0)).toFloat()
        val depthScale = (std / DEPTH_STD_REF).coerceIn(0.32f, 1.0f)

        val depthBg = harmonicDepthFill(depthMed, fillRegion, width, height, checkpoint)
        val dAnchorBg = percentile(depthBg, 0.95f).coerceAtLeast(0.45f)
        val kBgRaw = FloatArray(n) { i ->
            (K_MIN + (1f - K_MIN) * BG_WARP_GAIN * depthScale *
                (1f - (depthBg[i] / dAnchorBg).coerceIn(0f, 1f))).coerceIn(0f, 1f)
        }
        val kBg = greyClosing(kBgRaw, width, height, GREY_CLOSE_R)
        val limX = LIM_SLOPE / PAN_UV / width.coerceAtLeast(1)
        val limY = LIM_SLOPE / PAN_UV / height.coerceAtLeast(1)
        limitSlope(kBg, width, height, limX, limY)
        checkpoint()

        val bodyMask = BooleanArray(n) { mask[it] > 1e-3f }
        val bodyCount = bodyMask.count { it }
        val kFg = if (bodyCount > 64) {
            val dAnchorFg = percentile(depthMed, 0.95f, bodyMask).coerceAtLeast(0.05f)
            val sFg = FloatArray(n) { i -> 1f - (depthMed[i] / dAnchorFg).coerceIn(0f, 1f) }
            val sLo = percentile(sFg, 0.01f, bodyMask)
            val sHi = percentile(sFg, 0.99f, bodyMask)
            val targetSpanPx = FG_WARP_SPAN_PX * (min(width, height) / 960f).coerceIn(0.45f, 1.25f)
            val rawSpanPx = ((sHi - sLo) * (1f - K_MIN) * depthScale) * PAN_UV * width
            val fgWarp = if (rawSpanPx > 0.05f) (targetSpanPx / rawSpanPx).coerceIn(0.02f, 1f) else 1f
            val field = FloatArray(n) { i ->
                (K_MIN + (1f - K_MIN) * depthScale * fgWarp * sFg[i]).coerceIn(0f, 1f)
            }
            limitSlope(field, width, height, limX, limY, dilateBool(bodyMask, width, height, 6))
            checkpoint()

            val solid = BooleanArray(n) { mask[it] >= 0.9f }
            val dtEdge = FloatArray(n) { if (solid[it]) Float.MAX_VALUE else 0f }
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    val i = row + x
                    var v = dtEdge[i]
                    if (x > 0) v = min(v, dtEdge[i - 1] + 1f)
                    if (y > 0) v = min(v, dtEdge[i - width] + 1f)
                    dtEdge[i] = v
                }
            }
            for (y in height - 1 downTo 0) {
                val row = y * width
                for (x in width - 1 downTo 0) {
                    val i = row + x
                    var v = dtEdge[i]
                    if (x < width - 1) v = min(v, dtEdge[i + 1] + 1f)
                    if (y < height - 1) v = min(v, dtEdge[i + width] + 1f)
                    dtEdge[i] = v
                }
            }
            val smoothRadius = max(4, (EDGE_SMOOTH_R * min(width, height) / 960f).roundToInt())
            val numIn = FloatArray(n) { i -> if (mask[i] > 0.05f) field[i] else 0f }
            val denIn = FloatArray(n) { i -> if (mask[i] > 0.05f) 1f else 0f }
            val numOut = boxBlur3(numIn, width, height, smoothRadius)
            val denOut = boxBlur3(denIn, width, height, smoothRadius)
            for (i in 0 until n) {
                if (mask[i] <= 0.05f) continue
                val t = ((EDGE_RING_OUT - dtEdge[i]) / (EDGE_RING_OUT - EDGE_RING_IN)).coerceIn(0f, 1f)
                if (t <= 0f || denOut[i] <= 1e-3f) continue
                val blurred = numOut[i] / denOut[i]
                field[i] = (field[i] * (1f - t) + blurred * t).coerceIn(0f, 1f)
            }
            val extendRadius = max(12, (36f * min(width, height) / 960f).roundToInt())
            extendField(field, bodyMask, width, height, extendRadius)
            field
        } else {
            kBg.copyOf()
        }
        return SpatialDisplacementFields(kBg, kFg)
    }

    fun subjectRelief(depth: FloatArray, mask: FloatArray, width: Int, height: Int, anchor: Float): FloatArray {
        require(width > 0 && height > 0 && depth.size == width * height && mask.size == depth.size)
        val distance = FloatArray(mask.size) { i ->
            if (mask[i] < .985f || i % width == 0 || i % width == width - 1 ||
                i / width == 0 || i / width == height - 1) 0f else (width + height).toFloat()
        }
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            if (x > 0) distance[i] = min(distance[i], distance[i - 1] + 1f)
            if (y > 0) distance[i] = min(distance[i], distance[i - width] + 1f)
        }
        for (y in height - 1 downTo 0) for (x in width - 1 downTo 0) {
            val i = y * width + x
            if (x + 1 < width) distance[i] = min(distance[i], distance[i + 1] + 1f)
            if (y + 1 < height) distance[i] = min(distance[i], distance[i + width] + 1f)
        }
        val radius = (max(width, height) * .012f).coerceIn(4f, 24f)
        val plane = anchor.coerceIn(0f, 1f)
        return FloatArray(depth.size) { i ->
            val t = (distance[i] / radius).coerceIn(0f, 1f)
            val support = t * t * (3f - 2f * t)
            val delta = if (depth[i].isFinite()) (depth[i] - plane).coerceIn(-.24f, .24f) else 0f
            (plane + delta * support).coerceIn(0f, 1f)
        }
    }
}

internal object SpatialMattingEdges {
    fun probabilities(values: FloatArray, normalize: Boolean): FloatArray {
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        for (value in values) if (value.isFinite()) {
            low = min(low, value)
            high = max(high, value)
        }
        val range = high - low
        return FloatArray(values.size) { index ->
            val value = values[index]
            when {
                !value.isFinite() -> 0f
                normalize && range > 1e-6f -> ((value - low) / range).coerceIn(0f, 1f)
                else -> value.coerceIn(0f, 1f)
            }
        }
    }

    fun refine(mask: FloatArray, pixels: IntArray, width: Int, height: Int, radius: Int, checkpoint: () -> Unit = {}): FloatArray {
        require(width > 0 && height > 0 && radius >= 0)
        require(mask.size == width * height && pixels.size == mask.size)
        val guide = FloatArray(mask.size) { i ->
            val color = pixels[i]
            (((color ushr 16) and 255) * .2126f + ((color ushr 8) and 255) * .7152f +
                (color and 255) * .0722f) / 255f
        }
        val scratch = FloatArray(mask.size)
        val meanI = FloatArray(mask.size)
        val meanP = FloatArray(mask.size)
        val a = FloatArray(mask.size) { guide[it] * guide[it] }
        val b = FloatArray(mask.size) { guide[it] * mask[it] }
        mean(guide, meanI, scratch, width, height, radius, checkpoint)
        mean(mask, meanP, scratch, width, height, radius, checkpoint)
        mean(a, a, scratch, width, height, radius, checkpoint)
        mean(b, b, scratch, width, height, radius, checkpoint)
        for (i in mask.indices) {
            val variance = (a[i] - meanI[i] * meanI[i]).coerceAtLeast(0f)
            a[i] = (b[i] - meanI[i] * meanP[i]) / (variance + .001f)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        mean(a, a, scratch, width, height, radius, checkpoint)
        mean(b, b, scratch, width, height, radius, checkpoint)
        for (i in mask.indices) {
            meanP[i] = if (mask[i] <= .005f || mask[i] >= .995f) {
                mask[i]
            } else {
                (.75f * (a[i] * guide[i] + b[i]) + .25f * mask[i]).coerceIn(0f, 1f)
            }
        }
        return meanP
    }

    fun backgroundRemovalMask(mask: FloatArray, width: Int, height: Int, radius: Int, checkpoint: () -> Unit = {}): FloatArray {
        require(width > 0 && height > 0 && radius >= 0 && mask.size == width * height)
        val horizontal = FloatArray(mask.size)
        val result = FloatArray(mask.size)
        for (y in 0 until height) for (x in 0 until width) {
            if (x == 0 && (y and 15) == 0) checkpoint()
            var value = 0f
            for (nx in max(0, x - radius)..min(width - 1, x + radius)) {
                value = max(value, mask[y * width + nx])
            }
            horizontal[y * width + x] = value
        }
        for (y in 0 until height) for (x in 0 until width) {
            if (x == 0 && (y and 15) == 0) checkpoint()
            var value = 0f
            for (ny in max(0, y - radius)..min(height - 1, y + radius)) {
                value = max(value, horizontal[ny * width + x])
            }
            result[y * width + x] = ((value - .005f) / .025f).coerceIn(0f, 1f)
        }
        return result
    }

    /** Fill closed interior holes of [bodyMask], excluding enclosed background gaps deeper than [gap]. */
    fun fillHolesExcludingFarGaps(
        bodyMask: BooleanArray,
        depth: FloatArray,
        width: Int,
        height: Int,
        gap: Float = 0.15f,
    ): BooleanArray {
        val n = width * height
        val fillRegion = BooleanArray(n) { true }
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        fun push(idx: Int) {
            if (bodyMask[idx] || !fillRegion[idx]) return
            fillRegion[idx] = false
            queue[tail++] = idx
        }
        for (x in 0 until width) {
            push(x)
            push((height - 1) * width + x)
        }
        for (y in 1 until height - 1) {
            push(y * width)
            push(y * width + width - 1)
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % width
            val y = i / width
            if (x > 0) push(i - 1)
            if (x < width - 1) push(i + 1)
            if (y > 0) push(i - width)
            if (y < height - 1) push(i + width)
        }
        val visited = BooleanArray(n)
        val compQueue = IntArray(n)
        for (start in 0 until n) {
            if (visited[start] || !fillRegion[start] || bodyMask[start]) continue
            var cHead = 0
            var cTail = 0
            visited[start] = true
            compQueue[cTail++] = start
            var sumIn = 0.0
            var minX = width; var maxX = -1
            var minY = height; var maxY = -1
            while (cHead < cTail) {
                val i = compQueue[cHead++]
                val x = i % width
                val y = i / width
                sumIn += depth[i]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                fun visitNeighbor(nIdx: Int) {
                    if (!visited[nIdx] && fillRegion[nIdx] && !bodyMask[nIdx]) {
                        visited[nIdx] = true
                        compQueue[cTail++] = nIdx
                    }
                }
                if (x > 0) visitNeighbor(i - 1)
                if (x < width - 1) visitNeighbor(i + 1)
                if (y > 0) visitNeighbor(i - width)
                if (y < height - 1) visitNeighbor(i + width)
            }
            val meanIn = (sumIn / cTail.coerceAtLeast(1)).toFloat()
            val pad = 8
            val rx0 = max(0, minX - pad)
            val rx1 = min(width - 1, maxX + pad)
            val ry0 = max(0, minY - pad)
            val ry1 = min(height - 1, maxY + pad)
            var sumOut = 0.0
            var cntOut = 0
            for (yy in ry0..ry1) {
                val r = yy * width
                for (xx in rx0..rx1) {
                    val q = r + xx
                    if (bodyMask[q]) { sumOut += depth[q]; cntOut++ }
                }
            }
            if (cntOut >= 32 && meanIn < sumOut / cntOut - gap) {
                for (j in 0 until cTail) fillRegion[compQueue[j]] = false
            }
        }
        return fillRegion
    }

    private fun mean(
        values: FloatArray,
        result: FloatArray,
        temp: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        checkpoint: () -> Unit,
    ) {
        for (y in 0 until height) {
            if ((y and 15) == 0) checkpoint()
            val row = y * width
            var sum = 0.0
            for (x in 0..min(radius, width - 1)) sum += values[row + x]
            for (x in 0 until width) {
                temp[row + x] = (sum / (min(width - 1, x + radius) - max(0, x - radius) + 1)).toFloat()
                if (x - radius >= 0) sum -= values[row + x - radius]
                if (x + radius + 1 < width) sum += values[row + x + radius + 1]
            }
        }
        for (x in 0 until width) {
            if ((x and 15) == 0) checkpoint()
            var sum = 0.0
            for (y in 0..min(radius, height - 1)) sum += temp[y * width + x]
            for (y in 0 until height) {
                result[y * width + x] = (sum / (min(height - 1, y + radius) - max(0, y - radius) + 1)).toFloat()
                if (y - radius >= 0) sum -= temp[(y - radius) * width + x]
                if (y + radius + 1 < height) sum += temp[(y + radius + 1) * width + x]
            }
        }
    }
}

internal class SpatialInpaintedBackground(val pixels: IntArray, val depth: FloatArray)

internal object SpatialBackgroundInpaint {
    fun fill(pixels: IntArray, depth: FloatArray, removal: FloatArray, width: Int, height: Int, checkpoint: () -> Unit = {}): SpatialInpaintedBackground {
        require(width > 0 && height > 0 && pixels.size == width * height)
        require(depth.size == pixels.size && removal.size == pixels.size)
        val step = max(1, ceil(max(width, height) / 384.0).toInt())
        val smallWidth = (width + step - 1) / step
        val smallHeight = (height + step - 1) / step
        val channels = Array(4) { FloatArray(smallWidth * smallHeight) }
        val weights = FloatArray(smallWidth * smallHeight)
        var fallbackR = 0.0
        var fallbackG = 0.0
        var fallbackB = 0.0
        var fallbackWeight = 0.0
        for (y in 0 until height) for (x in 0 until width) {
            if (x == 0 && (y and 15) == 0) checkpoint()
            val i = y * width + x
            val color = pixels[i]
            val alpha = (color ushr 24) / 255f
            val r = ((color ushr 16) and 255) / 255f
            val g = ((color ushr 8) and 255) / 255f
            val b = (color and 255) / 255f
            fallbackR += r * alpha
            fallbackG += g * alpha
            fallbackB += b * alpha
            fallbackWeight += alpha
            if (removal[i] > .001f || alpha < .99f) continue
            val j = (y / step) * smallWidth + x / step
            weights[j] += 1f
            channels[0][j] += r
            channels[1][j] += g
            channels[2][j] += b
            channels[3][j] += depth[i]
        }
        val known = BooleanArray(weights.size) { weights[it] > 0f }
        for (i in weights.indices) if (known[i]) for (channel in channels) channel[i] /= weights[i]
        val fallback = if (fallbackWeight > 0.0) {
            floatArrayOf((fallbackR / fallbackWeight).toFloat(), (fallbackG / fallbackWeight).toFloat(), (fallbackB / fallbackWeight).toFloat(), .35f)
        } else floatArrayOf(.2f, .2f, .2f, .35f)
        solve(channels, known, smallWidth, smallHeight, fallback, checkpoint)
        val output = IntArray(pixels.size)
        val outputDepth = FloatArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            if (x == 0 && (y and 15) == 0) checkpoint()
            val i = y * width + x
            val sx = (x + .5f) / step - .5f
            val sy = (y + .5f) / step - .5f
            val t = max(removal[i], 1f - (pixels[i] ushr 24) / 255f).coerceIn(0f, 1f)
            fun color(channel: Int, shift: Int): Int {
                val original = ((pixels[i] ushr shift) and 255) / 255f
                return ((original + (sample(channels[channel], smallWidth, smallHeight, sx, sy) - original) * t) * 255f)
                    .roundToInt().coerceIn(0, 255)
            }
            output[i] = (0xff shl 24) or (color(0, 16) shl 16) or (color(1, 8) shl 8) or color(2, 0)
            outputDepth[i] = (depth[i] + (sample(channels[3], smallWidth, smallHeight, sx, sy) - depth[i]) * t).coerceIn(0f, 1f)
        }
        return SpatialInpaintedBackground(output, outputDepth)
    }

    private fun solve(values: Array<FloatArray>, known: BooleanArray, width: Int, height: Int, fallback: FloatArray, checkpoint: () -> Unit) {
        if (known.all { it }) return
        if (known.none { it }) {
            values.forEachIndexed { c, channel -> channel.fill(fallback[c]) }
            return
        }
        if (width > 2 || height > 2) {
            val cw = (width + 1) / 2
            val ch = (height + 1) / 2
            val coarse = Array(values.size) { FloatArray(cw * ch) }
            val coarseKnown = BooleanArray(cw * ch)
            for (y in 0 until ch) for (x in 0 until cw) {
                val i = y * cw + x
                var count = 0
                for (ny in y * 2 until min(height, y * 2 + 2)) for (nx in x * 2 until min(width, x * 2 + 2)) {
                    val j = ny * width + nx
                    if (!known[j]) continue
                    count++
                    for (c in values.indices) coarse[c][i] += values[c][j]
                }
                if (count > 0) {
                    coarseKnown[i] = true
                    for (channel in coarse) channel[i] /= count.toFloat()
                }
            }
            solve(coarse, coarseKnown, cw, ch, fallback, checkpoint)
            for (y in 0 until height) for (x in 0 until width) {
                val i = y * width + x
                if (!known[i]) for (c in values.indices) {
                    values[c][i] = sample(coarse[c], cw, ch, (x + .5f) / 2f - .5f, (y + .5f) / 2f - .5f)
                }
            }
        } else {
            for (c in values.indices) {
                var sum = 0f
                var count = 0
                for (i in known.indices) if (known[i]) { sum += values[c][i]; count++ }
                for (i in known.indices) if (!known[i]) values[c][i] = sum / count
            }
        }
        repeat(64) {
            checkpoint()
            for (parity in 0..1) for (y in 0 until height) {
                for (x in ((y + parity) and 1) until width step 2) {
                    val i = y * width + x
                    if (known[i]) continue
                    for (channel in values) {
                        var sum = 0f
                        var count = 0
                        if (x > 0) { sum += channel[i - 1]; count++ }
                        if (x < width - 1) { sum += channel[i + 1]; count++ }
                        if (y > 0) { sum += channel[i - width]; count++ }
                        if (y < height - 1) { sum += channel[i + width]; count++ }
                        if (count > 0) channel[i] = sum / count
                    }
                }
            }
        }
    }

    private fun sample(values: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
        val sx = x.coerceIn(0f, width - 1f)
        val sy = y.coerceIn(0f, height - 1f)
        val x0 = sx.toInt()
        val y0 = sy.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = sx - x0
        val fy = sy - y0
        val top = values[y0 * width + x0] * (1f - fx) + values[y0 * width + x1] * fx
        val bottom = values[y1 * width + x0] * (1f - fx) + values[y1 * width + x1] * fx
        return top * (1f - fy) + bottom * fy
    }
}
