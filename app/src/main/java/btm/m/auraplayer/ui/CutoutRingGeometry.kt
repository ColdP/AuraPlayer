// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.view.DisplayCutout
import btm.m.auraplayer.data.CUTOUT_RING_HEIGHT_MAX_DP
import btm.m.auraplayer.data.CUTOUT_RING_HEIGHT_MIN_DP
import btm.m.auraplayer.data.CUTOUT_RING_HORIZONTAL_OFFSET_MAX_DP
import btm.m.auraplayer.data.CUTOUT_RING_HORIZONTAL_OFFSET_MIN_DP
import btm.m.auraplayer.data.CUTOUT_RING_VERTICAL_OFFSET_MAX_DP
import btm.m.auraplayer.data.CUTOUT_RING_VERTICAL_OFFSET_MIN_DP
import btm.m.auraplayer.data.CUTOUT_RING_WIDTH_MAX_DP
import btm.m.auraplayer.data.CUTOUT_RING_WIDTH_MIN_DP
import btm.m.auraplayer.data.CutoutRingAlignment
import btm.m.auraplayer.data.CutoutRingConfig
import btm.m.auraplayer.data.CutoutRingDirection
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

private const val BASE_SIDE_MARGIN_DP = 12f
private const val BASE_TOP_MARGIN_DP = 8f
private const val CIRCLE_BEZIER_KAPPA = 0.55228475f
private const val SQUIRCLE_MAX_KAPPA = 0.80f

internal data class CutoutRingLayout(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float,
    val strokePx: Float,
    val glowPadPx: Float,
) {
    val windowLeftPx: Int get() = kotlin.math.floor(leftPx - glowPadPx).toInt()
    val windowTopPx: Int get() = kotlin.math.floor(topPx - glowPadPx).toInt()
    val windowWidthPx: Int get() = ceil(widthPx + glowPadPx * 2f).toInt().coerceAtLeast(1)
    val windowHeightPx: Int get() = ceil(heightPx + glowPadPx * 2f).toInt().coerceAtLeast(1)
}

internal data class CutoutCalibrationResult(
    val alignment: CutoutRingAlignment,
    val widthDp: Float,
    val heightDp: Float,
    val verticalOffsetDp: Float,
    val horizontalOffsetDp: Float,
)

internal fun computeCutoutRingLayout(
    config: CutoutRingConfig,
    screenWidthPx: Int,
    density: Float,
): CutoutRingLayout {
    val safeDensity = density.coerceAtLeast(0.5f)
    val heightPx = config.heightDp * safeDensity
    val widthPx = (config.heightDp + config.widthDp) * safeDensity
    val strokePx = config.strokeWidthDp * safeDensity
    val sideMarginPx = BASE_SIDE_MARGIN_DP * safeDensity
    val baseLeftPx = when (config.alignment) {
        CutoutRingAlignment.CENTER -> (screenWidthPx - widthPx) / 2f
        CutoutRingAlignment.LEFT -> sideMarginPx
        CutoutRingAlignment.RIGHT -> screenWidthPx - widthPx - sideMarginPx
    }
    val leftPx = baseLeftPx + config.horizontalOffsetDp * safeDensity
    val topPx = (BASE_TOP_MARGIN_DP + config.verticalOffsetDp) * safeDensity
    val glowRadiusPx = if (config.glowEnabled) strokePx * 1.9f else 0f
    val glowPadPx = strokePx + glowRadiusPx + 4f * safeDensity
    return CutoutRingLayout(
        leftPx = leftPx,
        topPx = topPx,
        widthPx = widthPx,
        heightPx = heightPx,
        strokePx = strokePx,
        glowPadPx = glowPadPx,
    )
}

internal fun detectCutoutCalibration(
    cutout: DisplayCutout?,
    screenWidthPx: Int,
    density: Float,
): CutoutCalibrationResult? {
    if (cutout == null || screenWidthPx <= 0 || density <= 0f) return null
    val topRect = cutout.boundingRectTop
    if (topRect.isEmpty || topRect.width() <= 0 || topRect.height() <= 0) return null

    // Give a small uniform clearance around the camera lens so the ring frames it cleanly.
    val clearanceDp = 3f
    val cutoutWidthDp = topRect.width() / density
    val cutoutHeightDp = topRect.height() / density
    val ringHeightDp = snapHalf(cutoutHeightDp + clearanceDp * 2f)
        .coerceIn(CUTOUT_RING_HEIGHT_MIN_DP, CUTOUT_RING_HEIGHT_MAX_DP)
    val extraWidthDp = snapHalf((cutoutWidthDp - cutoutHeightDp).coerceAtLeast(0f))
        .coerceIn(CUTOUT_RING_WIDTH_MIN_DP, CUTOUT_RING_WIDTH_MAX_DP)
    val ringWidthDp = ringHeightDp + extraWidthDp

    val centerX = topRect.exactCenterX()
    val alignment = when {
        centerX < screenWidthPx * 0.35f -> CutoutRingAlignment.LEFT
        centerX > screenWidthPx * 0.65f -> CutoutRingAlignment.RIGHT
        else -> CutoutRingAlignment.CENTER
    }
    val desiredLeftDp = (centerX / density) - ringWidthDp / 2f
    val baseLeftDp = when (alignment) {
        CutoutRingAlignment.CENTER -> (screenWidthPx / density - ringWidthDp) / 2f
        CutoutRingAlignment.LEFT -> BASE_SIDE_MARGIN_DP
        CutoutRingAlignment.RIGHT -> (screenWidthPx / density) - ringWidthDp - BASE_SIDE_MARGIN_DP
    }
    val horizontalOffsetDp = snapHalf(desiredLeftDp - baseLeftDp)
        .coerceIn(CUTOUT_RING_HORIZONTAL_OFFSET_MIN_DP, CUTOUT_RING_HORIZONTAL_OFFSET_MAX_DP)

    val desiredTopDp = (topRect.exactCenterY() / density) - ringHeightDp / 2f
    val verticalOffsetDp = snapHalf(desiredTopDp - BASE_TOP_MARGIN_DP)
        .coerceIn(CUTOUT_RING_VERTICAL_OFFSET_MIN_DP, CUTOUT_RING_VERTICAL_OFFSET_MAX_DP)

    return CutoutCalibrationResult(
        alignment = alignment,
        widthDp = extraWidthDp,
        heightDp = ringHeightDp,
        verticalOffsetDp = verticalOffsetDp,
        horizontalOffsetDp = horizontalOffsetDp,
    )
}

private fun snapHalf(value: Float): Float = (value * 2f).roundToInt() / 2f

/**
 * Reusable hardware-friendly renderer used by both the system overlay View and in-app Compose Canvas
 * so geometry, smoothness, direction, and glow remain pixel-identical across modes.
 */
internal class CutoutRingDrawer {
    private val fullPath = Path()
    private val progressPath = Path()
    private val pathMeasure = PathMeasure()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private var lastGlowRadius = -1f

    fun draw(
        canvas: Canvas,
        config: CutoutRingConfig,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        strokePx: Float,
        progress: Float,
        accentArgb: Int,
        alphaMultiplier: Float,
    ) {
        val clampedAlpha = alphaMultiplier.coerceIn(0f, 1f)
        if (clampedAlpha <= 0.004f || width <= 1f || height <= 1f || strokePx <= 0.25f) return

        val inset = strokePx * 0.5f
        val l = left + inset
        val t = top + inset
        val r = (left + width - inset).coerceAtLeast(l + 1f)
        val b = (top + height - inset).coerceAtLeast(t + 1f)

        buildCutoutRingPath(
            outPath = fullPath,
            left = l,
            top = t,
            right = r,
            bottom = b,
            cornerSmoothnessPercent = config.cornerSmoothness,
            clockwise = config.direction == CutoutRingDirection.CLOCKWISE,
        )

        val cap = if (config.roundCap) Paint.Cap.ROUND else Paint.Cap.BUTT
        val baseOpacity = (config.opacityPercent / 100f).coerceIn(0.05f, 1f) * clampedAlpha
        val rgb = accentArgb and 0x00FFFFFF

        if (config.showTrack) {
            val trackAlpha = ((config.trackOpacityPercent / 100f) * baseOpacity * 255f)
                .roundToInt().coerceIn(0, 255)
            if (trackAlpha > 0) {
                trackPaint.strokeWidth = strokePx
                trackPaint.strokeCap = cap
                trackPaint.color = (trackAlpha shl 24) or rgb
                canvas.drawPath(fullPath, trackPaint)
            }
        }

        val clampedProgress = progress.coerceIn(0f, 1f)
        if (clampedProgress <= 0.001f) return

        val arcAlpha = (baseOpacity * 255f).roundToInt().coerceIn(0, 255)
        if (arcAlpha <= 0) return

        progressPaint.strokeWidth = strokePx
        progressPaint.strokeCap = cap
        progressPaint.color = (arcAlpha shl 24) or rgb

        val targetPath: Path = if (clampedProgress >= 0.999f) {
            fullPath
        } else {
            pathMeasure.setPath(fullPath, false)
            val totalLen = pathMeasure.length
            progressPath.rewind()
            if (totalLen > 0f) {
                pathMeasure.getSegment(0f, totalLen * clampedProgress, progressPath, true)
            }
            progressPath
        }

        if (config.glowEnabled) {
            val glowRadius = (strokePx * 1.45f).coerceAtLeast(1.5f)
            if (glowRadius != lastGlowRadius) {
                glowPaint.maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
                lastGlowRadius = glowRadius
            }
            val glowAlpha = (baseOpacity * 0.62f * 255f).roundToInt().coerceIn(0, 255)
            glowPaint.strokeWidth = strokePx * 1.35f
            glowPaint.strokeCap = cap
            glowPaint.color = (glowAlpha shl 24) or rgb
            canvas.drawPath(targetPath, glowPaint)
        }

        canvas.drawPath(targetPath, progressPaint)
    }
}

/**
 * Builds a closed circle / squircle / capsule path starting at exact 12 o'clock (top center).
 * - When width == height and smoothness == 0, produces an exact circular path.
 * - When width > height, expands symmetrically into a capsule.
 * - Corner smoothness (0..100) continuously softens corners into a G2-style superellipse/squircle.
 */
internal fun buildCutoutRingPath(
    outPath: Path,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    cornerSmoothnessPercent: Int,
    clockwise: Boolean,
) {
    outPath.rewind()
    val w = (right - left).coerceAtLeast(1f)
    val h = (bottom - top).coerceAtLeast(1f)
    val cx = (left + right) * 0.5f
    val cy = (top + bottom) * 0.5f
    val r = min(w, h) * 0.5f
    val halfFlat = ((w - h) * 0.5f).coerceAtLeast(0f)
    val s = (cornerSmoothnessPercent / 100f).coerceIn(0f, 1f)

    // Smooth shoulder extension into the horizontal straight edges (for capsules).
    val extX = min(halfFlat, r * 0.45f * s)
    val xRightStart = (right - r - extX).coerceAtLeast(cx)
    val xLeftEnd = (left + r + extX).coerceAtMost(cx)

    val kappa = CIRCLE_BEZIER_KAPPA + (SQUIRCLE_MAX_KAPPA - CIRCLE_BEZIER_KAPPA) * s
    val spanX = (right - xRightStart).coerceAtLeast(r)
    val c1 = (spanX * kappa).coerceAtMost(spanX)
    val c2 = (r * kappa).coerceAtMost(r)

    outPath.moveTo(cx, top)
    if (clockwise) {
        if (xRightStart > cx + 0.01f) outPath.lineTo(xRightStart, top)
        outPath.cubicTo(xRightStart + c1, top, right, cy - c2, right, cy)
        outPath.cubicTo(right, cy + c2, xRightStart + c1, bottom, xRightStart, bottom)
        if (xLeftEnd < xRightStart - 0.01f) outPath.lineTo(xLeftEnd, bottom)
        outPath.cubicTo(xLeftEnd - c1, bottom, left, cy + c2, left, cy)
        outPath.cubicTo(left, cy - c2, xLeftEnd - c1, top, xLeftEnd, top)
        if (xLeftEnd < cx - 0.01f) outPath.lineTo(cx, top)
    } else {
        if (xLeftEnd < cx - 0.01f) outPath.lineTo(xLeftEnd, top)
        outPath.cubicTo(xLeftEnd - c1, top, left, cy - c2, left, cy)
        outPath.cubicTo(left, cy + c2, xLeftEnd - c1, bottom, xLeftEnd, bottom)
        if (xRightStart > xLeftEnd + 0.01f) outPath.lineTo(xRightStart, bottom)
        outPath.cubicTo(xRightStart + c1, bottom, right, cy + c2, right, cy)
        outPath.cubicTo(right, cy - c2, xRightStart + c1, top, xRightStart, top)
        if (xRightStart > cx + 0.01f) outPath.lineTo(cx, top)
    }
    outPath.close()
}
