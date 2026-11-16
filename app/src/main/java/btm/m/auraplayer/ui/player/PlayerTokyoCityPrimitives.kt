// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class TokyoRenderScratch {
    val sx = FloatArray(32)
    val sy = FloatArray(32)
    val sz = FloatArray(32)

    // Pre-allocated camera-space & near-plane clipping buffers (zero per-frame GC churn).
    val inX = FloatArray(8)
    val inY = FloatArray(8)
    val inZ = FloatArray(8)
    val outX = FloatArray(12)
    val outY = FloatArray(12)
    val outZ = FloatArray(12)
    val polySx = FloatArray(12)
    val polySy = FloatArray(12)

    val quadPath = Path()
    val strokePath = Path()
}

internal data class Camera3D(
    val x: Float,
    val y: Float,
    val z: Float,
    val sinYaw: Float,
    val cosYaw: Float,
    val sinPitch: Float,
    val cosPitch: Float,
    val focal: Float,
    val halfW: Float,
    val horizonY: Float,
)

internal enum class TokyoBuildingStyle {
    GLASS_TOWER,
    ZAKKYO_COMMERCIAL,
    MACHIYA_TRADITIONAL,
}

private const val NEAR_CLIP_Z = 2.2f

internal fun Camera3D.toViewSpace(
    wx: Float,
    wy: Float,
    wz: Float,
    idx: Int,
    outX: FloatArray,
    outY: FloatArray,
    outZ: FloatArray,
) {
    val dx = wx - x
    val dy = wy - y
    val dz = wz - z
    val rx = dx * cosYaw - dz * sinYaw
    val rz0 = dx * sinYaw + dz * cosYaw
    val ry = dy * cosPitch - rz0 * sinPitch
    val rz = dy * sinPitch + rz0 * cosPitch
    outX[idx] = rx
    outY[idx] = ry
    outZ[idx] = rz
}

internal fun Camera3D.projectPoint(
    wx: Float,
    wy: Float,
    wz: Float,
    idx: Int,
    scratch: TokyoRenderScratch,
): Boolean {
    val dx = wx - x
    val dy = wy - y
    val dz = wz - z
    val rx = dx * cosYaw - dz * sinYaw
    val rz0 = dx * sinYaw + dz * cosYaw
    val ry = dy * cosPitch - rz0 * sinPitch
    val rz = dy * sinPitch + rz0 * cosPitch
    if (rz <= NEAR_CLIP_Z) return false
    scratch.sx[idx] = halfW + (rx / rz) * focal
    scratch.sy[idx] = horizonY - (ry / rz) * focal
    scratch.sz[idx] = rz
    return true
}

/**
 * Clips a 3D quad in camera view space against [NEAR_CLIP_Z] so close foreground
 * architecture slides smoothly off-screen instead of abruptly popping.
 */
internal fun Camera3D.clipAndBuildQuad(
    wx0: Float, wy0: Float, wz0: Float,
    wx1: Float, wy1: Float, wz1: Float,
    wx2: Float, wy2: Float, wz2: Float,
    wx3: Float, wy3: Float, wz3: Float,
    scratch: TokyoRenderScratch,
): Int {
    toViewSpace(wx0, wy0, wz0, 0, scratch.inX, scratch.inY, scratch.inZ)
    toViewSpace(wx1, wy1, wz1, 1, scratch.inX, scratch.inY, scratch.inZ)
    toViewSpace(wx2, wy2, wz2, 2, scratch.inX, scratch.inY, scratch.inZ)
    toViewSpace(wx3, wy3, wz3, 3, scratch.inX, scratch.inY, scratch.inZ)

    val z0 = scratch.inZ[0]
    val z1 = scratch.inZ[1]
    val z2 = scratch.inZ[2]
    val z3 = scratch.inZ[3]
    if (z0 <= NEAR_CLIP_Z && z1 <= NEAR_CLIP_Z && z2 <= NEAR_CLIP_Z && z3 <= NEAR_CLIP_Z) {
        return 0
    }

    var outCount = 0
    if (z0 > NEAR_CLIP_Z && z1 > NEAR_CLIP_Z && z2 > NEAR_CLIP_Z && z3 > NEAR_CLIP_Z) {
        for (i in 0 until 4) {
            val inv = focal / scratch.inZ[i]
            scratch.polySx[i] = halfW + scratch.inX[i] * inv
            scratch.polySy[i] = horizonY - scratch.inY[i] * inv
        }
        return 4
    }

    var prevIdx = 3
    var prevZ = scratch.inZ[prevIdx]
    var prevIn = prevZ >= NEAR_CLIP_Z
    for (currIdx in 0 until 4) {
        val currZ = scratch.inZ[currIdx]
        val currIn = currZ >= NEAR_CLIP_Z
        if (currIn != prevIn) {
            val denom = currZ - prevZ
            val t = if (abs(denom) > 1e-5f) (NEAR_CLIP_Z - prevZ) / denom else 0f
            scratch.outX[outCount] = lerpF(scratch.inX[prevIdx], scratch.inX[currIdx], t)
            scratch.outY[outCount] = lerpF(scratch.inY[prevIdx], scratch.inY[currIdx], t)
            scratch.outZ[outCount] = NEAR_CLIP_Z
            outCount++
        }
        if (currIn) {
            scratch.outX[outCount] = scratch.inX[currIdx]
            scratch.outY[outCount] = scratch.inY[currIdx]
            scratch.outZ[outCount] = currZ
            outCount++
        }
        prevIdx = currIdx
        prevZ = currZ
        prevIn = currIn
    }
    if (outCount < 3) return 0
    for (i in 0 until outCount) {
        val inv = focal / scratch.outZ[i]
        scratch.polySx[i] = halfW + scratch.outX[i] * inv
        scratch.polySy[i] = horizonY - scratch.outY[i] * inv
    }
    return outCount
}

internal fun drawClippedPoly(
    scope: DrawScope,
    scratch: TokyoRenderScratch,
    count: Int,
    fillColor: Color,
    outlineColor: Color? = null,
    outlineWidth: Float = 1.2f,
) {
    if (count < 3) return
    val path = scratch.quadPath
    path.reset()
    path.moveTo(scratch.polySx[0], scratch.polySy[0])
    for (i in 1 until count) {
        path.lineTo(scratch.polySx[i], scratch.polySy[i])
    }
    path.close()
    scope.drawPath(path = path, color = fillColor, style = Fill)
    if (outlineColor != null && outlineColor.alpha > 0.01f && outlineWidth > 0.2f) {
        scope.drawPath(
            path = path,
            color = outlineColor,
            style = Stroke(width = outlineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

internal fun drawClippedQuad3D(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    wx0: Float, wy0: Float, wz0: Float,
    wx1: Float, wy1: Float, wz1: Float,
    wx2: Float, wy2: Float, wz2: Float,
    wx3: Float, wy3: Float, wz3: Float,
    fillColor: Color,
    outlineColor: Color? = null,
    outlineWidth: Float = 1.15f,
): Boolean {
    val count = cam.clipAndBuildQuad(
        wx0, wy0, wz0,
        wx1, wy1, wz1,
        wx2, wy2, wz2,
        wx3, wy3, wz3,
        scratch,
    )
    if (count < 3) return false
    drawClippedPoly(scope, scratch, count, fillColor, outlineColor, outlineWidth)
    return true
}

internal fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

internal fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun fillQuad(
    scope: DrawScope,
    scratch: TokyoRenderScratch,
    i0: Int,
    i1: Int,
    i2: Int,
    i3: Int,
    color: Color,
) {
    val path = scratch.quadPath
    path.reset()
    path.moveTo(scratch.sx[i0], scratch.sy[i0])
    path.lineTo(scratch.sx[i1], scratch.sy[i1])
    path.lineTo(scratch.sx[i2], scratch.sy[i2])
    path.lineTo(scratch.sx[i3], scratch.sy[i3])
    path.close()
    scope.drawPath(path = path, color = color, style = Fill)
}

internal fun fillQuad(
    scope: DrawScope,
    scratch: TokyoRenderScratch,
    i0: Int,
    i1: Int,
    i2: Int,
    i3: Int,
    brush: Brush,
) {
    val path = scratch.quadPath
    path.reset()
    path.moveTo(scratch.sx[i0], scratch.sy[i0])
    path.lineTo(scratch.sx[i1], scratch.sy[i1])
    path.lineTo(scratch.sx[i2], scratch.sy[i2])
    path.lineTo(scratch.sx[i3], scratch.sy[i3])
    path.close()
    scope.drawPath(path = path, brush = brush, style = Fill)
}

internal fun strokeQuad(
    scope: DrawScope,
    scratch: TokyoRenderScratch,
    i0: Int,
    i1: Int,
    i2: Int,
    i3: Int,
    color: Color,
    width: Float,
) {
    val path = scratch.strokePath
    path.reset()
    path.moveTo(scratch.sx[i0], scratch.sy[i0])
    path.lineTo(scratch.sx[i1], scratch.sy[i1])
    path.lineTo(scratch.sx[i2], scratch.sy[i2])
    path.lineTo(scratch.sx[i3], scratch.sy[i3])
    path.close()
    scope.drawPath(
        path = path,
        color = color,
        style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** Quantized 3-step anime cel shading for a face normal against directional sunlight. */
internal fun shadeCelSurface(
    baseColor: Color,
    lighting: TokyoLightingState,
    nx: Float,
    ny: Float,
    nz: Float,
    fogT: Float,
): Color {
    val ndotl = nx * lighting.sunDirX + ny * lighting.sunDirY + nz * lighting.sunDirZ
    val midStep = smoothStep(-0.22f, 0.04f, ndotl)
    val litStep = smoothStep(0.30f, 0.56f, ndotl)
    val celShade = lerp(
        lighting.celShadowColor,
        lerp(baseColor, lighting.facadeNight, lighting.nightness * 0.72f),
        0.38f + 0.62f * midStep,
    )
    val sunlit = lerp(
        celShade,
        lerp(baseColor, lighting.sunGlowColor, 0.24f * (0.4f + 0.6f * lighting.daylight)),
        litStep * (0.35f + 0.65f * lighting.daylight),
    )
    val upFill = if (ny > 0.5f) lerp(sunlit, lighting.zenithColor, 0.22f * lighting.daylight) else sunlit
    return lerp(upFill, lighting.horizonColor, fogT)
}

/** Renders all camera-visible faces of an axis-aligned 3D cel prism with clean ink contour lines. */
internal fun drawCelBox3D(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    lighting: TokyoLightingState,
    xL: Float,
    xR: Float,
    yB: Float,
    yT: Float,
    zF: Float,
    zB: Float,
    baseColor: Color,
    fogT: Float,
    outlineWidth: Float,
    blend: Float,
) {
    val ink = lerp(lighting.inkOutlineColor, lighting.horizonColor, fogT * 0.62f)
        .copy(alpha = 0.86f * blend)

    // Back-to-front visibility: far side faces -> top/bottom -> nearest front face.
    if (cam.x < xL) {
        val c = shadeCelSurface(baseColor, lighting, -1f, 0f, 0f, fogT).copy(alpha = 0.98f * blend)
        drawClippedQuad3D(scope, cam, scratch, xL, yB, zB, xL, yB, zF, xL, yT, zF, xL, yT, zB, c, ink, outlineWidth)
    }
    if (cam.x > xR) {
        val c = shadeCelSurface(baseColor, lighting, 1f, 0f, 0f, fogT).copy(alpha = 0.98f * blend)
        drawClippedQuad3D(scope, cam, scratch, xR, yB, zF, xR, yB, zB, xR, yT, zB, xR, yT, zF, c, ink, outlineWidth)
    }
    if (cam.y > yT) {
        val c = shadeCelSurface(baseColor, lighting, 0f, 1f, 0f, fogT).copy(alpha = 0.98f * blend)
        drawClippedQuad3D(scope, cam, scratch, xL, yT, zF, xR, yT, zF, xR, yT, zB, xL, yT, zB, c, ink, outlineWidth)
    } else if (cam.y < yB) {
        val c = lerp(lighting.celShadowColor, lighting.horizonColor, fogT * 0.85f).copy(alpha = 0.96f * blend)
        drawClippedQuad3D(scope, cam, scratch, xL, yB, zB, xR, yB, zB, xR, yB, zF, xL, yB, zF, c, ink, outlineWidth)
    }
    if (cam.z < zF) {
        val c = shadeCelSurface(baseColor, lighting, 0f, 0f, -1f, fogT).copy(alpha = 0.99f * blend)
        drawClippedQuad3D(scope, cam, scratch, xL, yB, zF, xR, yB, zF, xR, yT, zF, xL, yT, zF, c, ink, outlineWidth)
    }
}

internal fun drawCelBuilding3D(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    lighting: TokyoLightingState,
    cx: Float,
    cz: Float,
    w: Float,
    d: Float,
    h: Float,
    isRightWall: Boolean,
    style: TokyoBuildingStyle,
    hasMegaScreen: Boolean,
    hasBalconies: Boolean,
    phase: Float,
    blend: Float,
    seed: Int,
) {
    if (blend <= 0.01f || cz + d * 0.5f <= NEAR_CLIP_Z) return
    val hw = w * 0.5f
    val hd = d * 0.5f
    val xL = cx - hw
    val xR = cx + hw
    val zF = cz - hd
    val zB = cz + hd
    // Inner street-facing wall points toward x = 0; isRightWall == true sits on the left side of the avenue.
    val sideX = if (isRightWall) xR else xL
    val sideNormalX = if (isRightWall) 1f else -1f

    val fogT = ((cz - 14f) / 170f).coerceIn(0f, 1f) * 0.44f
    val distRef = (cz - cam.z).coerceAtLeast(16f)
    val ow = (34f / distRef).coerceIn(0.85f, 1.85f)
    val inkCol = lerp(lighting.inkOutlineColor, lighting.horizonColor, fogT * 0.62f).copy(alpha = 0.88f * blend)
    val shadowBandCol = lerp(lighting.celShadowColor, lighting.horizonColor, fogT * 0.78f).copy(alpha = 0.34f * blend)
    val rimCol = lighting.rimHighlightColor.copy(alpha = lerpF(0.30f, 0.72f, lighting.daylight) * blend)

    // Soft pastel anime architectural facade palette varied deterministically per plot
    val paletteVariant = when (style) {
        TokyoBuildingStyle.GLASS_TOWER -> when (seed % 3) {
            0 -> lerp(lighting.facadeFrontDay, Color(0xFFD6E5F5), 0.28f)
            1 -> lerp(lighting.facadeFrontDay, Color(0xFFE7ECF4), 0.35f)
            else -> lerp(lighting.facadeFrontDay, Color(0xFFCDE0EE), 0.25f)
        }
        TokyoBuildingStyle.ZAKKYO_COMMERCIAL -> when (seed % 3) {
            0 -> lerp(lighting.facadeFrontDay, Color(0xFFECE2D6), 0.30f)
            1 -> lerp(lighting.facadeFrontDay, Color(0xFFDAD4DC), 0.26f)
            else -> lerp(lighting.facadeFrontDay, Color(0xFFC8D5DC), 0.24f)
        }
        TokyoBuildingStyle.MACHIYA_TRADITIONAL -> lerp(Color(0xFFF0E8DC), lighting.facadeFrontDay, 0.38f)
    }

    // 1. Directional ground shadow cast onto street / sidewalk along sun shadow direction
    val groundShadowLen = (h * 0.24f).coerceIn(3.5f, 13f) * (0.35f + 0.65f * lighting.daylight)
    val shX0 = sideX
    val shX1 = sideX + sideNormalX * groundShadowLen
    val shCol = lighting.celShadowColor.copy(alpha = lerpF(0.18f, 0.34f, lighting.daylight) * blend)
    drawClippedQuad3D(
        scope, cam, scratch,
        shX0, 0.05f, zF,
        shX1, 0.05f, zF + groundShadowLen * 0.35f,
        shX1, 0.05f, zB + groundShadowLen * 0.35f,
        shX0, 0.05f, zB,
        shCol,
    )

    // 2. Main cel-shaded architectural mass (all visible walls + roof slab)
    drawCelBox3D(scope, cam, scratch, lighting, xL, xR, 0f, h, zF, zB, paletteVariant, fogT, ow, blend)

    // Side-wall & front-face base colors for consistent architectural detailing
    val sideShadedCol = shadeCelSurface(paletteVariant, lighting, sideNormalX, 0f, 0f, fogT).copy(alpha = 0.98f * blend)
    val frontShadedCol = shadeCelSurface(paletteVariant, lighting, 0f, 0f, -1f, fogT).copy(alpha = 0.99f * blend)

    // Parapet cornice shadow band under roofline on front & street side
    if (cam.z < zF) {
        drawClippedQuad3D(
            scope, cam, scratch,
            xL, h * 0.92f, zF - 0.03f,
            xR, h * 0.92f, zF - 0.03f,
            xR, h, zF - 0.03f,
            xL, h, zF - 0.03f,
            shadowBandCol,
        )
        // Bright anime key-light rim along sunlit vertical corner & roof edge
        if (cam.projectPoint(xL, h, zF, 0, scratch) && cam.projectPoint(xR, h, zF, 1, scratch)) {
            scope.drawLine(rimCol, Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), ow * 1.1f, StrokeCap.Round)
        }
        val cornerX = if (lighting.sunDirX * sideNormalX > 0f) sideX else (if (isRightWall) xL else xR)
        if (cam.projectPoint(cornerX, 0f, zF, 0, scratch) && cam.projectPoint(cornerX, h, zF, 1, scratch)) {
            scope.drawLine(rimCol.copy(alpha = rimCol.alpha * 0.72f), Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), ow * 0.95f)
        }
    }

    // 3. Rooftop architecture (sloped Kawara roof for Machiya OR stepped penthouse + tanks/rails for modern)
    if (style == TokyoBuildingStyle.MACHIYA_TRADITIONAL) {
        val eaveXInner = sideX + sideNormalX * 1.45f
        val eaveXOuter = if (isRightWall) xL - 0.5f else xR + 0.5f
        val tileDark = lerp(Color(0xFF3A435E), lighting.horizonColor, fogT).copy(alpha = 0.97f * blend)
        val tileLit = lerp(Color(0xFF566384), lighting.rimHighlightColor, 0.25f * lighting.daylight).copy(alpha = 0.97f * blend)
        val ridgeY = h + 4.4f
        val ridgeX = cx
        // Pitched tiled gable slope facing street + front pediment triangle + cast eave shadow
        drawClippedQuad3D(
            scope, cam, scratch,
            eaveXInner, h - 0.2f, zF - 0.6f,
            eaveXInner, h - 0.2f, zB + 0.6f,
            ridgeX, ridgeY, zB + 0.6f,
            ridgeX, ridgeY, zF - 0.6f,
            if (isRightWall == (lighting.sunDirX > 0f)) tileLit else tileDark,
            inkCol,
            ow * 0.9f,
        )
        drawClippedQuad3D(
            scope, cam, scratch,
            eaveXOuter, h - 0.2f, zF - 0.6f,
            eaveXInner, h - 0.2f, zF - 0.6f,
            ridgeX, ridgeY, zF - 0.6f,
            ridgeX, ridgeY, zF - 0.6f,
            sideShadedCol,
            inkCol,
            ow * 0.85f,
        )
        // Eave cast shadow strip under Machiya overhang
        drawClippedQuad3D(
            scope, cam, scratch,
            sideX + sideNormalX * 0.04f, h - 1.5f, zF,
            sideX + sideNormalX * 0.04f, h - 1.5f, zB,
            sideX + sideNormalX * 0.04f, h, zB,
            sideX + sideNormalX * 0.04f, h, zF,
            shadowBandCol,
        )
    } else {
        // Stepped mechanical penthouse + rooftop railing + water tank / comm mast
        val phW = w * 0.48f
        val phD = d * 0.48f
        val phH = if (style == TokyoBuildingStyle.GLASS_TOWER) 5.2f + (seed % 3) * 2.2f else 3.6f + (seed % 2) * 1.6f
        val phXL = cx - phW * 0.5f
        val phXR = cx + phW * 0.5f
        val phZF = cz - phD * 0.5f
        val phZB = cz + phD * 0.5f
        drawCelBox3D(
            scope, cam, scratch, lighting,
            phXL, phXR, h, h + phH, phZF, phZB,
            lerp(paletteVariant, lighting.facadeSideDay, 0.28f),
            fogT,
            ow * 0.85f,
            blend,
        )
        // Roof antenna + blinking aircraft warning beacon
        if (seed % 2 == 0 &&
            cam.projectPoint(cx, h + phH, cz, 0, scratch) &&
            cam.projectPoint(cx, h + phH + 7.5f, cz, 1, scratch)
        ) {
            scope.drawLine(inkCol, Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), ow * 0.9f, StrokeCap.Round)
            if (lighting.cityLights > 0.22f) {
                val p = (0.42f + 0.58f * sin(phase * 3.1f + seed)).coerceIn(0f, 1f)
                scope.drawCircle(Color(0xFFFF5E6C).copy(alpha = p * lighting.cityLights * blend), 2.2f, Offset(scratch.sx[1], scratch.sy[1]))
            }
        }
    }

    // 4. Street-facing ground-floor commercial podium + storefront glass & warm interior glow
    val podiumH = if (style == TokyoBuildingStyle.MACHIYA_TRADITIONAL) 5.2f else (h * 0.17f).coerceIn(5.0f, 8.8f)
    val podiumInset = 0.05f * sideNormalX
    val shopGlass = if (lighting.cityLights > 0.25f) {
        lerp(lighting.windowWarmColor, Color(0xFFFFE0A3), 0.45f).copy(alpha = (0.55f + 0.35f * lighting.cityLights) * blend)
    } else {
        lerp(Color(0xFF4F7CA3), lighting.horizonColor, 0.36f + fogT * 0.4f).copy(alpha = 0.84f * blend)
    }
    drawClippedQuad3D(
        scope, cam, scratch,
        sideX + podiumInset, 0.45f, zF + 1.2f,
        sideX + podiumInset, 0.45f, zB - 1.2f,
        sideX + podiumInset, podiumH - 1.2f, zB - 1.2f,
        sideX + podiumInset, podiumH - 1.2f, zF + 1.2f,
        shopGlass,
        inkCol.copy(alpha = inkCol.alpha * 0.72f),
        ow * 0.72f,
    )
    // Projecting retail awning / canopy casting a clean underside shadow
    val awnOut = sideX + sideNormalX * 1.55f
    val awnCol = if (style == TokyoBuildingStyle.MACHIYA_TRADITIONAL) {
        lerp(Color(0xFF2F4878), lighting.facadeSideDay, 0.3f).copy(alpha = 0.94f * blend)
    } else {
        lerp(lighting.billboardAccentA, paletteVariant, 0.58f).copy(alpha = 0.92f * blend)
    }
    drawClippedQuad3D(
        scope, cam, scratch,
        sideX, podiumH - 0.1f, zF + 0.8f,
        sideX, podiumH - 0.1f, zB - 0.8f,
        awnOut, podiumH - 1.15f, zB - 0.8f,
        awnOut, podiumH - 1.15f, zF + 0.8f,
        awnCol,
        inkCol,
        ow * 0.75f,
    )

    // 5. Detailed upper-story windows along both the street-facing wall AND front facade
    val floors = ((h - podiumH - 1.5f) / 5.4f).toInt().coerceIn(1, 10)
    val panesPerWall = if (style == TokyoBuildingStyle.GLASS_TOWER) 3 else 2
    val wallOffset = 0.04f * sideNormalX
    for (fl in 0 until floors) {
        val wy0 = podiumH + 1.1f + fl * 5.2f
        val wy1 = (wy0 + 2.85f).coerceAtMost(h - 1.6f)
        if (wy1 <= wy0 + 0.6f) continue

        for (pane in 0 until panesPerWall) {
            val stepU = 1f / panesPerWall
            val u0 = pane * stepU + 0.04f
            val u1 = (pane + 1) * stepU - 0.04f
            val wz0 = lerpF(zF + 1.2f, zB - 1.2f, u0)
            val wz1 = lerpF(zF + 1.2f, zB - 1.2f, u1)
            val roomHash = (seed * 37 + fl * 19 + pane * 13) and 15
            val litThreshold = if (lighting.cityLights > 0.3f) 10 else 3
            val isLitNight = roomHash < litThreshold
            val winCol = when {
                lighting.cityLights > 0.32f && isLitNight -> {
                    val warmOrCool = if (roomHash % 3 == 0) lighting.windowCoolColor else lighting.windowWarmColor
                    lerp(warmOrCool, lighting.horizonColor, fogT * 0.45f).copy(alpha = (0.58f + 0.32f * lighting.cityLights) * blend)
                }
                style == TokyoBuildingStyle.GLASS_TOWER -> {
                    val skyReflect = lerp(Color(0xFF6096C4), lighting.zenithColor, 0.35f)
                    lerp(skyReflect, lighting.horizonColor, 0.30f + fogT * 0.5f).copy(alpha = 0.88f * blend)
                }
                else -> {
                    lerp(Color(0xFF456D96), lighting.horizonColor, 0.42f + fogT * 0.4f).copy(alpha = 0.82f * blend)
                }
            }
            if (drawClippedQuad3D(
                    scope, cam, scratch,
                    sideX + wallOffset, wy0, wz0,
                    sideX + wallOffset, wy0, wz1,
                    sideX + wallOffset, wy1, wz1,
                    sideX + wallOffset, wy1, wz0,
                    winCol,
                    inkCol.copy(alpha = inkCol.alpha * 0.56f),
                    ow * 0.62f,
                )
            ) {
                // Classic Makoto Shinkai diagonal specular window glint
                if ((!isLitNight || lighting.daylight > 0.3f) &&
                    cam.projectPoint(sideX + wallOffset, wy1, wz0, 0, scratch) &&
                    cam.projectPoint(sideX + wallOffset, lerpF(wy1, wy0, 0.68f), lerpF(wz0, wz1, 0.58f), 1, scratch)
                ) {
                    scope.drawLine(
                        Color.White.copy(alpha = lerpF(0.10f, 0.36f, lighting.daylight) * blend),
                        Offset(scratch.sx[0], scratch.sy[0]),
                        Offset(scratch.sx[1], scratch.sy[1]),
                        ow * 0.85f,
                    )
                }
            }
        }

        // Front-facing facade windows (`z = zF`) so front faces aren't blank monoliths!
        if (style != TokyoBuildingStyle.MACHIYA_TRADITIONAL && cam.z < zF && fl < 9) {
            for (fp in 0..1) {
                val fx0 = lerpF(xL + 1.8f, xR - 1.8f, fp * 0.52f)
                val fx1 = lerpF(xL + 1.8f, xR - 1.8f, fp * 0.52f + 0.42f)
                val fHash = (seed * 23 + fl * 11 + fp * 7) and 15
                val fLit = lighting.cityLights > 0.32f && fHash < 9
                val fWinCol = if (fLit) {
                    lerp(lighting.windowWarmColor, lighting.horizonColor, fogT * 0.45f).copy(alpha = 0.72f * blend)
                } else {
                    lerp(Color(0xFF4D77A2), lighting.horizonColor, 0.40f + fogT * 0.45f).copy(alpha = 0.78f * blend)
                }
                drawClippedQuad3D(
                    scope, cam, scratch,
                    fx0, wy0, zF - 0.04f,
                    fx1, wy0, zF - 0.04f,
                    fx1, wy1, zF - 0.04f,
                    fx0, wy1, zF - 0.04f,
                    fWinCol,
                    inkCol.copy(alpha = inkCol.alpha * 0.48f),
                    ow * 0.55f,
                )
            }
        }

        // Cantilevered balcony slab + handrail + shadow for Zakkyo/apartment walls
        if (hasBalconies && style == TokyoBuildingStyle.ZAKKYO_COMMERCIAL && fl % 2 == 0) {
            val balOut = sideX + sideNormalX * 1.15f
            val balColor = lerp(frontShadedCol, sideShadedCol, 0.35f).copy(alpha = 0.94f * blend)
            drawClippedQuad3D(
                scope, cam, scratch,
                balOut, wy0 - 0.5f, zF + 1.0f,
                balOut, wy0 - 0.5f, zB - 1.0f,
                balOut, wy0 + 0.9f, zB - 1.0f,
                balOut, wy0 + 0.9f, zF + 1.0f,
                balColor,
                inkCol.copy(alpha = 0.74f * blend),
                ow * 0.68f,
            )
        }
    }

    // 6. Optional Shibuya architectural wrap LED billboard on mid facade
    if (hasMegaScreen &&
        cam.projectPoint(sideX + wallOffset, h * 0.25f, zF + 1.8f, 6, scratch) &&
        cam.projectPoint(sideX + wallOffset, h * 0.25f, zB - 1.8f, 7, scratch) &&
        cam.projectPoint(sideX + wallOffset, h * 0.58f, zB - 1.8f, 8, scratch) &&
        cam.projectPoint(sideX + wallOffset, h * 0.58f, zF + 1.8f, 9, scratch)
    ) {
        val shift = (0.5f + 0.5f * sin(phase * 1.1f + seed)).coerceIn(0f, 1f)
        val tintA = lerp(lighting.billboardAccentA, lighting.billboardAccentB, shift)
        val tintB = lerp(lighting.billboardAccentB, Color(0xFFFFDE82), 1f - shift)
        val scrAlpha = lerpF(0.68f, 0.94f, lighting.cityLights) * blend
        fillQuad(
            scope, scratch, 6, 7, 8, 9,
            Brush.linearGradient(
                listOf(tintA.copy(alpha = scrAlpha), tintB.copy(alpha = scrAlpha)),
                start = Offset(scratch.sx[9], scratch.sy[9]),
                end = Offset(scratch.sx[7], scratch.sy[7]),
            ),
        )
        // Stylized anime graphic horizontal equalizer bars & glossy diagonal screen highlight
        for (bar in 0..2) {
            val frac = 0.25f + bar * 0.22f
            val bx0 = lerpF(scratch.sx[9], scratch.sx[6], frac)
            val by0 = lerpF(scratch.sy[9], scratch.sy[6], frac)
            val endFrac = (0.42f + 0.45f * abs(sin(phase * 1.6f + seed + bar))).coerceIn(0.25f, 0.88f)
            val bx1 = lerpF(bx0, lerpF(scratch.sx[8], scratch.sx[7], frac), endFrac)
            val by1 = lerpF(by0, lerpF(scratch.sy[8], scratch.sy[7], frac), endFrac)
            scope.drawLine(Color.White.copy(alpha = 0.48f * blend), Offset(bx0, by0), Offset(bx1, by1), ow * 1.35f, StrokeCap.Round)
        }
        strokeQuad(scope, scratch, 6, 7, 8, 9, inkCol, ow * 1.05f)
    }
}

internal fun drawStreetTree3D(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    wx: Float,
    wz: Float,
    height: Float,
    lighting: TokyoLightingState,
    phase: Float,
    blend: Float,
    seed: Int,
) {
    if (wz <= NEAR_CLIP_Z) return
    val crownY = height * 0.68f
    if (!cam.projectPoint(wx, 0f, wz, 0, scratch) ||
        !cam.projectPoint(wx, crownY, wz, 1, scratch) ||
        !cam.projectPoint(wx + 2.8f, crownY, wz, 2, scratch)
    ) return

    val ink = lighting.inkOutlineColor.copy(alpha = 0.82f * blend)
    val ow = (26f / scratch.sz[0].coerceAtLeast(12f)).coerceIn(0.9f, 1.8f)

    // Tree ground shadow ellipse
    val sway = sin(phase * 1.4f + seed) * 0.22f
    if (cam.projectPoint(wx + lighting.sunDirX * 2.4f, 0.05f, wz + 1.2f, 3, scratch)) {
        val sr = abs(scratch.sx[2] - scratch.sx[1]).coerceIn(4f, 26f) * 0.92f
        scope.drawCircle(
            lighting.celShadowColor.copy(alpha = (0.18f + 0.16f * lighting.daylight) * blend),
            sr * 0.85f,
            Offset(scratch.sx[3], scratch.sy[3]),
        )
    }

    // Trunk with clean outline
    val trunkCol = lerp(Color(0xFF6B4C3E), lighting.celShadowColor, lighting.nightness * 0.55f).copy(alpha = 0.95f * blend)
    scope.drawLine(ink, Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), ow * 2.1f, StrokeCap.Round)
    scope.drawLine(trunkCol, Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), ow * 1.3f, StrokeCap.Round)

    // Two-tone anime foliage puff clusters (deep shadow base + sunlit top canopy + ink outline)
    val c = Offset(scratch.sx[1] + sway, scratch.sy[1])
    val r = abs(scratch.sx[2] - scratch.sx[1]).coerceIn(5f, 30f)
    val leafShadow = lerp(Color(0xFF2D594B), lighting.celShadowColor, 0.35f + lighting.nightness * 0.45f).copy(alpha = 0.96f * blend)
    val leafLit = lerp(Color(0xFF6BB37D), lighting.sunGlowColor, 0.18f * lighting.daylight).copy(alpha = 0.96f * blend)

    for (lobe in 0..2) {
        val lx = c.x + (lobe - 1) * r * 0.52f
        val ly = c.y + (if (lobe == 1) -r * 0.28f else r * 0.08f)
        val lr = r * (if (lobe == 1) 0.86f else 0.68f)
        scope.drawCircle(leafShadow, lr, Offset(lx, ly))
        scope.drawCircle(leafLit, lr * 0.82f, Offset(lx + lighting.sunDirX * lr * 0.16f, ly - lr * 0.14f))
        scope.drawCircle(ink, lr, Offset(lx, ly), style = Stroke(width = ow * 0.9f))
    }
}

internal fun drawVendingMachine3D(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    x: Float,
    z: Float,
    facingSign: Float,
    lighting: TokyoLightingState,
    blend: Float,
    seed: Int,
) {
    if (z <= NEAR_CLIP_Z) return
    val halfW = 0.9f
    val halfD = 0.7f
    val h = 2.6f
    val bodyCol = if (seed % 2 == 0) Color(0xFFEBF2F9) else Color(0xFFD94B4B)
    drawCelBox3D(scope, cam, scratch, lighting, x - halfW, x + halfW, 0f, h, z - halfD, z + halfD, bodyCol, 0.08f, 1.0f, blend)
    // Glowing drink display showcase facing the street
    val faceX = if (facingSign > 0f) x + halfW + 0.04f else x - halfW - 0.04f
    val displayGlow = lerp(Color(0xFFCDEEFF), Color(0xFFEAF9FF), lighting.cityLights)
        .copy(alpha = (0.72f + 0.24f * lighting.cityLights) * blend)
    drawClippedQuad3D(
        scope, cam, scratch,
        faceX, h * 0.34f, z - halfD * 0.78f,
        faceX, h * 0.34f, z + halfD * 0.78f,
        faceX, h * 0.88f, z + halfD * 0.78f,
        faceX, h * 0.88f, z - halfD * 0.78f,
        displayGlow,
        lighting.inkOutlineColor.copy(alpha = 0.75f * blend),
        0.85f,
    )
}

internal fun drawVerticalBracketSign3D(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    xWall: Float,
    protrude: Float,
    z: Float,
    yBot: Float,
    yTop: Float,
    side: Int,
    lighting: TokyoLightingState,
    phase: Float,
    blend: Float,
    seed: Int,
) {
    if (z <= NEAR_CLIP_Z) return
    val xInner = if (side < 0) xWall + 0.5f else xWall - 0.5f
    val xTip = if (side < 0) xInner + protrude else xInner - protrude
    val ok = cam.projectPoint(xInner, yBot, z, 0, scratch) &&
        cam.projectPoint(xTip, yBot, z, 1, scratch) &&
        cam.projectPoint(xTip, yTop, z, 2, scratch) &&
        cam.projectPoint(xInner, yTop, z, 3, scratch)
    if (!ok) return

    val ink = lighting.inkOutlineColor.copy(alpha = 0.90f * blend)
    val ow = (28f / scratch.sz[0].coerceAtLeast(12f)).coerceIn(0.9f, 1.65f)

    // Twin steel mounting brackets connecting sign panel to building wall
    for (frac in listOf(0.22f, 0.78f)) {
        val by = lerpF(yBot, yTop, frac)
        if (cam.projectPoint(xWall, by, z, 4, scratch) &&
            cam.projectPoint(xInner, by, z, 5, scratch)
        ) {
            scope.drawLine(ink, Offset(scratch.sx[4], scratch.sy[4]), Offset(scratch.sx[5], scratch.sy[5]), ow * 1.1f)
        }
    }

    val panelBg = when (seed % 4) {
        0 -> lighting.billboardAccentA
        1 -> lighting.billboardAccentB
        2 -> Color(0xFFFFCA68)
        else -> Color(0xFFF6F8FC)
    }
    val pulse = 0.90f + 0.10f * sin(phase * 3.4f + seed)
    val panelAlpha = lerpF(0.80f, 0.96f, lighting.cityLights) * pulse * blend
    fillQuad(scope, scratch, 0, 1, 2, 3, panelBg.copy(alpha = panelAlpha))
    strokeQuad(scope, scratch, 0, 1, 2, 3, ink, ow)

    // Stylized vertical Kanji-like motif bars inside the signboard
    val cx = (scratch.sx[0] + scratch.sx[1]) * 0.5f
    val glyphCol = if (seed % 4 == 3) Color(0xFFE03E4E) else lerp(Color(0xFF1A223A), Color.White, if (seed % 2 == 0) 0.92f else 0.15f)
    val finalGlyph = glyphCol.copy(alpha = 0.88f * blend)
    for (g in 0..3) {
        val t0 = 0.18f + g * 0.20f
        val t1 = t0 + 0.11f
        val gy0 = lerpF(scratch.sy[3], scratch.sy[0], t0)
        val gy1 = lerpF(scratch.sy[3], scratch.sy[0], t1)
        scope.drawLine(finalGlyph, Offset(cx, gy0), Offset(cx, gy1), (ow * 1.85f).coerceAtLeast(1.4f), StrokeCap.Round)
    }
}

internal fun drawCatenaryWireSpan(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    x0: Float,
    y0: Float,
    z0: Float,
    x1: Float,
    y1: Float,
    z1: Float,
    sagMeters: Float,
    color: Color,
    strokeW: Float,
) {
    val segs = 7
    val path = scratch.strokePath
    path.reset()
    var started = false
    for (s in 0..segs) {
        val u = s / segs.toFloat()
        val wx = lerpF(x0, x1, u)
        val wz = lerpF(z0, z1, u)
        val sag = 4f * u * (1f - u) * sagMeters
        val wy = lerpF(y0, y1, u) - sag
        if (!cam.projectPoint(wx, wy, wz, 0, scratch)) continue
        if (!started) {
            path.moveTo(scratch.sx[0], scratch.sy[0])
            started = true
        } else {
            path.lineTo(scratch.sx[0], scratch.sy[0])
        }
    }
    if (started) {
        scope.drawPath(path, color = color, style = Stroke(width = strokeW, cap = StrokeCap.Round))
    }
}

internal fun drawUtilityPole3D(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    x: Float,
    z: Float,
    height: Float,
    lighting: TokyoLightingState,
    blend: Float,
) {
    if (z <= NEAR_CLIP_Z) return
    val ok = cam.projectPoint(x, 0f, z, 0, scratch) &&
        cam.projectPoint(x, height, z, 1, scratch)
    if (!ok) return
    val ink = lighting.inkOutlineColor.copy(alpha = 0.86f * blend)
    val ow = (26f / scratch.sz[0].coerceAtLeast(12f)).coerceIn(1.0f, 2.2f)
    val poleFill = lerp(lighting.facadeSideDay, lighting.inkOutlineColor, 0.35f).copy(alpha = 0.94f * blend)
    scope.drawLine(poleFill, Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), ow * 1.55f)
    val armDir = if (x < 0f) 1f else -1f
    if (cam.projectPoint(x - armDir * 0.4f, height * 0.92f, z, 2, scratch) &&
        cam.projectPoint(x + armDir * 2.2f, height * 0.92f, z, 3, scratch)
    ) {
        scope.drawLine(ink, Offset(scratch.sx[2], scratch.sy[2]), Offset(scratch.sx[3], scratch.sy[3]), ow)
    }
    if (cam.projectPoint(x + armDir * 0.6f, height * 0.72f, z, 4, scratch) &&
        cam.projectPoint(x + armDir * 0.6f, height * 0.81f, z, 5, scratch)
    ) {
        scope.drawLine(ink, Offset(scratch.sx[4], scratch.sy[4]), Offset(scratch.sx[5], scratch.sy[5]), ow * 2.5f, StrokeCap.Butt)
    }
}

internal fun drawChochinLantern3D(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    wx: Float,
    wy: Float,
    wz: Float,
    lighting: TokyoLightingState,
    blend: Float,
    scaleMul: Float = 1f,
) {
    if (wz <= NEAR_CLIP_Z || !cam.projectPoint(wx, wy, wz, 0, scratch)) return
    val c = Offset(scratch.sx[0], scratch.sy[0])
    val scale = (390f * scaleMul / scratch.sz[0].coerceAtLeast(10f)).coerceIn(4.5f, 34f)
    val rx = scale * 0.78f
    val ry = scale * 1.15f
    val ink = lighting.inkOutlineColor.copy(alpha = 0.88f * blend)

    // Soft warm glow aura around lantern
    scope.drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFFFA558).copy(alpha = (0.30f + 0.40f * lighting.cityLights) * blend), Color.Transparent),
            center = c,
            radius = ry * 2.3f,
        ),
        radius = ry * 2.3f,
        center = c,
    )

    val bodyPath = scratch.quadPath
    bodyPath.reset()
    val ovalSteps = 16
    for (k in 0 until ovalSteps) {
        val ang = (k / ovalSteps.toFloat()) * 2f * PI.toFloat()
        val px = c.x + cos(ang) * rx
        val py = c.y + sin(ang) * ry
        if (k == 0) bodyPath.moveTo(px, py) else bodyPath.lineTo(px, py)
    }
    bodyPath.close()
    scope.drawPath(
        path = bodyPath,
        brush = Brush.linearGradient(
            listOf(Color(0xFFFF724A).copy(alpha = 0.96f * blend), Color(0xFFBA241E).copy(alpha = 0.96f * blend)),
            start = Offset(c.x - rx, c.y - ry * 0.5f),
            end = Offset(c.x + rx, c.y + ry * 0.8f),
        ),
    )
    for (rOff in listOf(-0.48f, 0f, 0.48f)) {
        val yy = c.y + ry * rOff
        val hw = rx * sqrt((1f - rOff * rOff).coerceAtLeast(0f))
        scope.drawLine(ink.copy(alpha = 0.46f * blend), Offset(c.x - hw, yy), Offset(c.x + hw, yy), 0.85f)
    }
    scope.drawPath(bodyPath, ink, style = Stroke(width = 1.25f))
    val capW = rx * 0.56f
    val capH = ry * 0.16f
    scope.drawRect(Color(0xFF1F1824).copy(alpha = 0.95f * blend), Offset(c.x - capW, c.y - ry - capH * 0.5f), Size(capW * 2f, capH))
    scope.drawRect(Color(0xFF1F1824).copy(alpha = 0.95f * blend), Offset(c.x - capW, c.y + ry - capH * 0.5f), Size(capW * 2f, capH))
}

internal fun drawKawaraArchGate3D(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    z: Float,
    halfSpan: Float,
    pillarHeight: Float,
    lighting: TokyoLightingState,
    swayOffset: Float,
    blend: Float,
    isMainKaminarimon: Boolean = false,
) {
    if (z <= NEAR_CLIP_Z) return
    val distRef = (z - cam.z).coerceAtLeast(14f)
    val ow = (30f / distRef).coerceIn(1.0f, 2.2f)
    val ink = lighting.inkOutlineColor.copy(alpha = 0.90f * blend)
    val vermilionLit = lerp(Color(0xFFD63B2D), Color(0xFFF45C48), lighting.daylight * 0.6f).copy(alpha = 0.97f * blend)
    val vermilionShade = Color(0xFF8A2220).copy(alpha = 0.97f * blend)
    val tileLit = lerp(Color(0xFF39415D), Color(0xFF556082), lighting.daylight * 0.5f).copy(alpha = 0.97f * blend)
    val plasterCol = lerp(Color(0xFFE6DED4), Color(0xFFF6F1E9), lighting.daylight * 0.65f).copy(alpha = 0.95f * blend)

    // Twin 3D vermilion pillars with thickness & depth
    for (side in listOf(-1f, 1f)) {
        val xMid = side * halfSpan
        val col = if (side < 0f == (lighting.sunDirX < 0f)) vermilionLit else vermilionShade
        drawCelBox3D(scope, cam, scratch, lighting, xMid - 0.72f, xMid + 0.72f, 0f, pillarHeight, z - 0.7f, z + 0.7f, col, 0.08f, ow, blend)
    }

    // Cast stone beam & white plastered frieze lintel
    drawCelBox3D(
        scope, cam, scratch, lighting,
        -halfSpan - 1.4f, halfSpan + 1.4f,
        pillarHeight - 1.3f, pillarHeight + 0.7f,
        z - 0.75f, z + 0.75f,
        plasterCol,
        0.08f,
        ow,
        blend,
    )

    // Sweeping upturned *sori* Kawara ceramic tiled roof
    val eaveSpan = halfSpan + (if (isMainKaminarimon) 4.6f else 3.6f)
    val ridgeSpan = halfSpan + 1.4f
    val roofBaseY = pillarHeight + 0.7f
    val ridgeY = pillarHeight + (if (isMainKaminarimon) 5.2f else 3.9f)
    drawClippedQuad3D(
        scope, cam, scratch,
        -eaveSpan, roofBaseY + 0.6f, z - 1.1f,
        eaveSpan, roofBaseY + 0.6f, z - 1.1f,
        ridgeSpan, ridgeY, z + 0.7f,
        -ridgeSpan, ridgeY, z + 0.7f,
        tileLit,
        ink,
        ow,
    )
    if (cam.projectPoint(-ridgeSpan, ridgeY, z + 0.7f, 0, scratch) &&
        cam.projectPoint(ridgeSpan, ridgeY, z + 0.7f, 1, scratch)
    ) {
        scope.drawLine(lighting.rimHighlightColor.copy(alpha = 0.56f * blend), Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), ow * 1.25f)
    }

    // Hanging Chochin lantern (extra grand on Kaminarimon!)
    drawChochinLantern3D(
        scope, cam, scratch, swayOffset, pillarHeight - (if (isMainKaminarimon) 3.4f else 2.5f), z,
        lighting, blend, scaleMul = if (isMainKaminarimon) 1.65f else 1.0f,
    )
}

internal fun drawSpokedFerrisWheel3D(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    wx: Float,
    wy: Float,
    wz: Float,
    radiusWorld: Float,
    phase: Float,
    lighting: TokyoLightingState,
    blend: Float,
) {
    if (wz <= NEAR_CLIP_Z ||
        !cam.projectPoint(wx, wy, wz, 0, scratch) ||
        !cam.projectPoint(wx + radiusWorld, wy, wz, 1, scratch)
    ) return

    val center = Offset(scratch.sx[0], scratch.sy[0])
    val rPx = abs(scratch.sx[1] - scratch.sx[0]).coerceIn(14f, 72f)
    val wheelRim = lerp(Color(0xFFFF7DB8), lighting.billboardAccentA, 0.5f + 0.5f * sin(phase * 0.4f))
        .copy(alpha = lerpF(0.68f, 0.94f, lighting.cityLights) * blend)
    val ink = lighting.inkOutlineColor.copy(alpha = 0.84f * blend)

    if (cam.projectPoint(wx - radiusWorld * 0.48f, 0f, wz, 2, scratch) &&
        cam.projectPoint(wx + radiusWorld * 0.48f, 0f, wz, 3, scratch)
    ) {
        scope.drawLine(ink, center, Offset(scratch.sx[2], scratch.sy[2]), 1.7f)
        scope.drawLine(ink, center, Offset(scratch.sx[3], scratch.sy[3]), 1.7f)
    }

    scope.drawCircle(wheelRim, rPx, center, style = Stroke(width = 2.1f))
    scope.drawCircle(lighting.billboardAccentA.copy(alpha = wheelRim.alpha * 0.78f), rPx * 0.74f, center, style = Stroke(width = 1.25f))
    val spokeCount = 16
    val rotOffset = phase * 0.16f
    for (s in 0 until spokeCount) {
        val ang = rotOffset + (s / spokeCount.toFloat()) * 2f * PI.toFloat()
        val gx = center.x + cos(ang) * rPx
        val gy = center.y + sin(ang) * rPx
        scope.drawLine(Color.White.copy(alpha = 0.30f * blend), center, Offset(gx, gy), 0.9f)
        scope.drawRect(
            color = wheelRim,
            topLeft = Offset(gx - 1.9f, gy),
            size = Size(3.8f, 4.4f),
        )
    }
}

internal fun drawElevatedTrainViaduct(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    lighting: TokyoLightingState,
    zBridge: Float,
    deckY: Float,
    phase: Float,
    blend: Float,
) {
    if (zBridge <= NEAR_CLIP_Z) return
    val ink = lighting.inkOutlineColor.copy(alpha = 0.86f * blend)
    val girderCol = lerp(lighting.facadeSideDay, lighting.horizonColor, 0.32f).copy(alpha = 0.95f * blend)

    // Cast viaduct shadow stripe onto Shibuya avenue asphalt below
    drawClippedQuad3D(
        scope, cam, scratch,
        -110f, 0.06f, zBridge - 2.2f,
        110f, 0.06f, zBridge - 2.2f,
        110f, 0.06f, zBridge + 5.5f,
        -110f, 0.06f, zBridge + 5.5f,
        lighting.celShadowColor.copy(alpha = 0.30f * blend),
    )

    // Concrete box piers
    for (px in listOf(-72f, -24f, 24f, 72f)) {
        drawCelBox3D(scope, cam, scratch, lighting, px - 1.5f, px + 1.5f, 0f, deckY, zBridge - 1.2f, zBridge + 1.2f, girderCol, 0.18f, 1.05f, blend)
    }

    // 3D viaduct girder with dark underside soffit
    drawCelBox3D(scope, cam, scratch, lighting, -165f, 165f, deckY, deckY + 3.4f, zBridge - 1.8f, zBridge + 1.8f, girderCol, 0.18f, 1.15f, blend)

    // Gliding Yamanote-style commuter train
    val travel = ((phase * 0.085f) % 1f)
    val trainStartX = lerpF(-145f, 115f, travel)
    val carCount = 4
    val carLen = 13.5f
    val carGap = 1.0f
    val bodyCol = lerp(Color(0xFFE0E8F2), Color(0xFF8192AF), lighting.nightness * 0.52f).copy(alpha = 0.97f * blend)
    val stripeCol = Color(0xFF7AD645).copy(alpha = 0.94f * blend)
    val winGlow = lerp(Color(0xFF426E9C), lighting.windowWarmColor, 0.25f + 0.75f * lighting.cityLights)
        .copy(alpha = 0.94f * blend)

    for (c in 0 until carCount) {
        val x0 = trainStartX + c * (carLen + carGap)
        val x1 = x0 + carLen
        drawCelBox3D(scope, cam, scratch, lighting, x0, x1, deckY + 3.4f, deckY + 7.2f, zBridge - 1.2f, zBridge + 0.8f, bodyCol, 0.16f, 1.0f, blend)
        if (cam.projectPoint(x0, deckY + 4.3f, zBridge - 1.22f, 8, scratch) &&
            cam.projectPoint(x1, deckY + 4.3f, zBridge - 1.22f, 9, scratch)
        ) {
            scope.drawLine(stripeCol, Offset(scratch.sx[8], scratch.sy[8]), Offset(scratch.sx[9], scratch.sy[9]), 2.1f)
            for (wIdx in 0..3) {
                val u0 = 0.14f + wIdx * 0.21f
                val u1 = u0 + 0.14f
                val wx0 = lerpF(scratch.sx[8], scratch.sx[9], u0)
                val wx1 = lerpF(scratch.sx[8], scratch.sx[9], u1)
                if (cam.projectPoint(lerpF(x0, x1, u0), deckY + 5.7f, zBridge - 1.22f, 10, scratch) &&
                    cam.projectPoint(lerpF(x0, x1, u1), deckY + 5.7f, zBridge - 1.22f, 11, scratch)
                ) {
                    scope.drawLine(winGlow, Offset(wx0, scratch.sy[10]), Offset(wx1, scratch.sy[11]), 2.9f)
                }
            }
        }
    }
    @Suppress("UNUSED_VARIABLE")
    val _inkRef = ink
}
