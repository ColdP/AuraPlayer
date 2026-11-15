package btm.m.auraplayer.ui.player

import androidx.compose.ui.geometry.Offset
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

internal class TokyoRenderScratch {
    val sx = FloatArray(32)
    val sy = FloatArray(32)
    val sz = FloatArray(32)
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
    if (rz <= 4.5f) return false
    scratch.sx[idx] = halfW + (rx / rz) * focal
    scratch.sy[idx] = horizonY - (ry / rz) * focal
    scratch.sz[idx] = rz
    return true
}

internal fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

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
    hasMegaScreen: Boolean,
    hasBalconies: Boolean,
    phase: Float,
    blend: Float,
    seed: Int,
) {
    val hw = w * 0.5f
    val hd = d * 0.5f
    val xL = cx - hw
    val xR = cx + hw
    val zF = cz - hd
    val zB = cz + hd
    val sideX = if (isRightWall) xR else xL
    val outerX = if (isRightWall) xL else xR

    val ok = cam.projectPoint(xL, 0f, zF, 0, scratch) &&
        cam.projectPoint(xR, 0f, zF, 1, scratch) &&
        cam.projectPoint(xR, h, zF, 2, scratch) &&
        cam.projectPoint(xL, h, zF, 3, scratch) &&
        cam.projectPoint(sideX, 0f, zB, 4, scratch) &&
        cam.projectPoint(sideX, h, zB, 5, scratch)
    if (!ok) return

    // Soft anime aerial perspective fog blending far buildings gently into pastel sky
    val fogT = ((cz - 12f) / 145f).coerceIn(0f, 1f) * 0.42f
    val wallVariant = ((seed % 3) - 1) * 0.08f
    val sunLitFace = lerp(
        lerp(lighting.facadeFrontDay, lighting.facadeNight, lighting.nightness * 0.84f),
        lighting.sunGlowColor,
        (0.08f + wallVariant).coerceIn(0f, 0.25f) * lighting.daylight,
    )
    val frontCol = lerp(sunLitFace, lighting.horizonColor, fogT).copy(alpha = 0.98f * blend)
    val shadowFace = lerp(
        lerp(lighting.facadeSideDay, lighting.facadeNight, lighting.nightness * 0.90f),
        lighting.celShadowColor,
        0.35f,
    )
    val sideCol = lerp(shadowFace, lighting.horizonColor, fogT * 0.92f).copy(alpha = 0.98f * blend)
    val inkCol = lerp(lighting.inkOutlineColor, lighting.horizonColor, fogT * 0.65f).copy(alpha = 0.88f * blend)
    val rimCol = lighting.rimHighlightColor.copy(alpha = lerpF(0.32f, 0.68f, lighting.daylight) * blend)
    val outlineW = (32f / scratch.sz[0].coerceAtLeast(14f)).coerceIn(0.95f, 1.85f)

    // 1. Side street-facing cel wall + crisp silhouette ink outline
    if (isRightWall) {
        fillQuad(scope, scratch, 1, 4, 5, 2, sideCol)
        strokeQuad(scope, scratch, 1, 4, 5, 2, inkCol, outlineW)
    } else {
        fillQuad(scope, scratch, 4, 0, 3, 5, sideCol)
        strokeQuad(scope, scratch, 4, 0, 3, 5, inkCol, outlineW)
    }

    // 2. Front cel-shaded facade + cast shadow band under top parapet cornice
    fillQuad(scope, scratch, 0, 1, 2, 3, frontCol)
    if (cam.projectPoint(xL, h * 0.91f, zF, 6, scratch) &&
        cam.projectPoint(xR, h * 0.91f, zF, 7, scratch)
    ) {
        fillQuad(scope, scratch, 6, 7, 2, 3, lighting.celShadowColor.copy(alpha = 0.26f * blend))
    }
    strokeQuad(scope, scratch, 0, 1, 2, 3, inkCol, outlineW)

    // Key-light sunlit rim highlight along top roof parapet and sunlit corner arris
    val rimCornerBot = if (isRightWall) 1 else 0
    val rimCornerTop = if (isRightWall) 2 else 3
    scope.drawLine(rimCol, Offset(scratch.sx[3], scratch.sy[3]), Offset(scratch.sx[2], scratch.sy[2]), outlineW * 1.15f, StrokeCap.Round)
    scope.drawLine(rimCol.copy(alpha = rimCol.alpha * 0.8f), Offset(scratch.sx[rimCornerBot], scratch.sy[rimCornerBot]), Offset(scratch.sx[rimCornerTop], scratch.sy[rimCornerTop]), outlineW * 0.9f, StrokeCap.Round)

    // 3. Rooftop setback penthouse & communication mast (breaks boxy silhouette cleanly!)
    val phH = 5.5f + (seed % 3) * 2.2f
    val phW = w * 0.44f
    val phD = d * 0.44f
    val phSideX = if (isRightWall) cx + phW * 0.5f else cx - phW * 0.5f
    if (cam.projectPoint(cx - phW * 0.5f, h, cz - phD * 0.5f, 6, scratch) &&
        cam.projectPoint(cx + phW * 0.5f, h, cz - phD * 0.5f, 7, scratch) &&
        cam.projectPoint(cx + phW * 0.5f, h + phH, cz - phD * 0.5f, 8, scratch) &&
        cam.projectPoint(cx - phW * 0.5f, h + phH, cz - phD * 0.5f, 9, scratch) &&
        cam.projectPoint(phSideX, h, cz + phD * 0.5f, 10, scratch) &&
        cam.projectPoint(phSideX, h + phH, cz + phD * 0.5f, 11, scratch)
    ) {
        fillQuad(scope, scratch, 6, 7, 8, 9, frontCol)
        strokeQuad(scope, scratch, 6, 7, 8, 9, inkCol, outlineW * 0.85f)
        if (isRightWall) {
            fillQuad(scope, scratch, 7, 10, 11, 8, sideCol)
        } else {
            fillQuad(scope, scratch, 10, 6, 9, 11, sideCol)
        }
        // Subtle rooftop antenna / lightning spire
        if (seed % 2 == 0 &&
            cam.projectPoint(cx, h + phH, cz, 12, scratch) &&
            cam.projectPoint(cx, h + phH + 7.5f, cz, 13, scratch)
        ) {
            scope.drawLine(inkCol, Offset(scratch.sx[12], scratch.sy[12]), Offset(scratch.sx[13], scratch.sy[13]), outlineW * 0.85f, StrokeCap.Round)
            if (lighting.cityLights > 0.25f) {
                val p = (0.45f + 0.55f * sin(phase * 3f + seed)).coerceIn(0f, 1f)
                scope.drawCircle(Color(0xFFFF5E6C).copy(alpha = p * lighting.cityLights * blend), 2.1f, Offset(scratch.sx[13], scratch.sy[13]))
            }
        }
    }

    // 4. Ground-floor sloped retail awning / overhang + cast shadow band
    val podiumH = (h * 0.19f).coerceIn(5.5f, 9.5f)
    val awnOut = 1.6f
    val awnSideOuter = if (isRightWall) sideX + awnOut else sideX - awnOut
    if (cam.projectPoint(sideX, podiumH, zF + 0.8f, 6, scratch) &&
        cam.projectPoint(sideX, podiumH, zB - 0.8f, 7, scratch) &&
        cam.projectPoint(awnSideOuter, podiumH - 1.5f, zB - 0.8f, 8, scratch) &&
        cam.projectPoint(awnSideOuter, podiumH - 1.5f, zF + 0.8f, 9, scratch)
    ) {
        val awnColor = lerp(lighting.billboardAccentA, lighting.facadeFrontDay, 0.55f).copy(alpha = 0.92f * blend)
        fillQuad(scope, scratch, 6, 7, 8, 9, awnColor)
        strokeQuad(scope, scratch, 6, 7, 8, 9, inkCol, outlineW * 0.8f)
    }

    // 5. Crisp cel-shaded window bays along street-facing wall with mullions & anime sky reflection slashes!
    val floors = ((h - podiumH) / 7.0f).toInt().coerceIn(2, 8)
    for (fl in 0 until floors) {
        val wy0 = podiumH + 1.8f + fl * 6.5f
        val wy1 = (wy0 + 3.2f).coerceAtMost(h - 2.2f)
        if (wy1 <= wy0 + 0.8f) continue

        // Window glass band partitioned cleanly into 2 architectural panes
        for (pane in 0..1) {
            val z0 = lerpF(zF + 1.8f, zB - 1.8f, pane * 0.52f)
            val z1 = lerpF(zF + 1.8f, zB - 1.8f, pane * 0.52f + 0.44f)
            if (cam.projectPoint(sideX, wy0, z0, 6, scratch) &&
                cam.projectPoint(sideX, wy0, z1, 7, scratch) &&
                cam.projectPoint(sideX, wy1, z1, 8, scratch) &&
                cam.projectPoint(sideX, wy1, z0, 9, scratch)
            ) {
                val roomHash = (seed * 37 + fl * 19 + pane * 13) and 15
                val litThreshold = if (lighting.cityLights > 0.3f) 10 else 3
                val isLitNight = roomHash < litThreshold
                val basePaneCol = if (lighting.cityLights > 0.35f && isLitNight) {
                    val c = if (roomHash % 3 == 0) lighting.windowCoolColor else lighting.windowWarmColor
                    c.copy(alpha = (0.48f + 0.36f * lighting.cityLights) * blend)
                } else {
                    lerp(Color(0xFF426E9C), lighting.horizonColor, 0.45f).copy(alpha = 0.78f * blend)
                }
                fillQuad(scope, scratch, 6, 7, 8, 9, basePaneCol)
                // Classic anime diagonal glass reflection glint across day panes
                if (!isLitNight || lighting.daylight > 0.35f) {
                    val mx = lerpF(scratch.sx[9], scratch.sx[7], 0.55f)
                    val my = lerpF(scratch.sy[9], scratch.sy[7], 0.55f)
                    scope.drawLine(
                        color = Color.White.copy(alpha = lerpF(0.10f, 0.34f, lighting.daylight) * blend),
                        start = Offset(scratch.sx[9], scratch.sy[9]),
                        end = Offset(mx, my),
                        strokeWidth = outlineW * 0.9f,
                    )
                }
                strokeQuad(scope, scratch, 6, 7, 8, 9, inkCol.copy(alpha = inkCol.alpha * 0.55f), outlineW * 0.65f)
            }
        }

        // Optional cantilevered balcony ledge & handrail along apartment walls
        if (hasBalconies && fl % 2 == 0) {
            val balOut = if (isRightWall) sideX + 1.15f else sideX - 1.15f
            if (cam.projectPoint(balOut, wy0 - 0.5f, zF + 1.4f, 10, scratch) &&
                cam.projectPoint(balOut, wy0 - 0.5f, zB - 1.4f, 11, scratch) &&
                cam.projectPoint(balOut, wy0 + 1.1f, zB - 1.4f, 12, scratch) &&
                cam.projectPoint(balOut, wy0 + 1.1f, zF + 1.4f, 13, scratch)
            ) {
                fillQuad(scope, scratch, 10, 11, 12, 13, lerp(frontCol, sideCol, 0.4f).copy(alpha = 0.9f * blend))
                strokeQuad(scope, scratch, 10, 11, 12, 13, inkCol.copy(alpha = 0.7f * blend), outlineW * 0.7f)
            }
        }
    }

    // 6. Optional framed Shibuya-style architectural wrap LED billboard on mid façade
    if (hasMegaScreen &&
        cam.projectPoint(sideX, h * 0.24f, zF + 2.0f, 6, scratch) &&
        cam.projectPoint(sideX, h * 0.24f, zB - 2.0f, 7, scratch) &&
        cam.projectPoint(sideX, h * 0.58f, zB - 2.0f, 8, scratch) &&
        cam.projectPoint(sideX, h * 0.58f, zF + 2.0f, 9, scratch)
    ) {
        val shift = (0.5f + 0.5f * sin(phase * 1.1f + seed)).coerceIn(0f, 1f)
        val tintA = lerp(lighting.billboardAccentA, lighting.billboardAccentB, shift)
        val tintB = lerp(lighting.billboardAccentB, Color(0xFFFFDE82), 1f - shift)
        val scrAlpha = lerpF(0.62f, 0.92f, lighting.cityLights) * blend
        fillQuad(
            scope, scratch, 6, 7, 8, 9,
            Brush.linearGradient(
                listOf(tintA.copy(alpha = scrAlpha), tintB.copy(alpha = scrAlpha)),
                start = Offset(scratch.sx[9], scratch.sy[9]),
                end = Offset(scratch.sx[7], scratch.sy[7]),
            ),
        )
        // Stylized anime billboard stripe bars & glass glare inside the screen
        val barY0 = lerpF(scratch.sy[9], scratch.sy[6], 0.35f)
        val barY1 = lerpF(scratch.sy[8], scratch.sy[7], 0.35f)
        scope.drawLine(Color.White.copy(alpha = 0.42f * blend), Offset(scratch.sx[9], barY0), Offset(lerpF(scratch.sx[9], scratch.sx[8], 0.72f), lerpF(barY0, barY1, 0.72f)), outlineW * 1.6f)
        strokeQuad(scope, scratch, 6, 7, 8, 9, inkCol, outlineW * 1.05f)
    }

    // Back-edge anchor reference keeps outerX usage clean
    @Suppress("UNUSED_VARIABLE")
    val _outerAnchor = outerX
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
    val xInner = if (side < 0) xWall + 0.5f else xWall - 0.5f
    val xTip = if (side < 0) xInner + protrude else xInner - protrude
    val ok = cam.projectPoint(xInner, yBot, z, 0, scratch) &&
        cam.projectPoint(xTip, yBot, z, 1, scratch) &&
        cam.projectPoint(xTip, yTop, z, 2, scratch) &&
        cam.projectPoint(xInner, yTop, z, 3, scratch)
    if (!ok) return

    val ink = lighting.inkOutlineColor.copy(alpha = 0.90f * blend)
    val ow = (28f / scratch.sz[0].coerceAtLeast(12f)).coerceIn(0.9f, 1.6f)

    // Twin steel mounting brackets connecting sign panel to building wall
    for (frac in listOf(0.22f, 0.78f)) {
        val by = lerpF(yBot, yTop, frac)
        if (cam.projectPoint(xWall, by, z, 4, scratch) &&
            cam.projectPoint(xInner, by, z, 5, scratch)
        ) {
            scope.drawLine(ink, Offset(scratch.sx[4], scratch.sy[4]), Offset(scratch.sx[5], scratch.sy[5]), ow * 1.1f)
        }
    }

    val panelBg = when (seed % 3) {
        0 -> lighting.billboardAccentA
        1 -> lighting.billboardAccentB
        else -> Color(0xFFFFCA68)
    }
    val pulse = 0.88f + 0.12f * sin(phase * 3.8f + seed)
    val panelAlpha = lerpF(0.76f, 0.95f, lighting.cityLights) * pulse * blend
    fillQuad(scope, scratch, 0, 1, 2, 3, panelBg.copy(alpha = panelAlpha))
    strokeQuad(scope, scratch, 0, 1, 2, 3, ink, ow)

    // Stylized vertical Kanji-like motif bars inside the signboard
    val cx = (scratch.sx[0] + scratch.sx[1]) * 0.5f
    val glyphCol = lerp(Color(0xFF1A223A), Color.White, if (seed % 2 == 0) 0.92f else 0.15f).copy(alpha = 0.85f * blend)
    for (g in 0..3) {
        val t0 = 0.18f + g * 0.20f
        val t1 = t0 + 0.11f
        val gy0 = lerpF(scratch.sy[3], scratch.sy[0], t0)
        val gy1 = lerpF(scratch.sy[3], scratch.sy[0], t1)
        scope.drawLine(glyphCol, Offset(cx, gy0), Offset(cx, gy1), (ow * 1.8f).coerceAtLeast(1.4f), StrokeCap.Round)
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
    val segs = 6
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
    val ok = cam.projectPoint(x, 0f, z, 0, scratch) &&
        cam.projectPoint(x, height, z, 1, scratch)
    if (!ok) return
    val ink = lighting.inkOutlineColor.copy(alpha = 0.85f * blend)
    val ow = (26f / scratch.sz[0].coerceAtLeast(12f)).coerceIn(1.1f, 2.2f)
    val poleFill = lerp(lighting.facadeSideDay, lighting.inkOutlineColor, 0.35f).copy(alpha = 0.92f * blend)
    scope.drawLine(poleFill, Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), ow * 1.6f)
    // Cross-arm insulators near pole top
    val armDir = if (x < 0f) 1f else -1f
    if (cam.projectPoint(x - armDir * 0.4f, height * 0.92f, z, 2, scratch) &&
        cam.projectPoint(x + armDir * 2.2f, height * 0.92f, z, 3, scratch)
    ) {
        scope.drawLine(ink, Offset(scratch.sx[2], scratch.sy[2]), Offset(scratch.sx[3], scratch.sy[3]), ow)
    }
    // Pole-mounted cylindrical transformer drum
    if (cam.projectPoint(x + armDir * 0.6f, height * 0.72f, z, 4, scratch) &&
        cam.projectPoint(x + armDir * 0.6f, height * 0.81f, z, 5, scratch)
    ) {
        scope.drawLine(ink, Offset(scratch.sx[4], scratch.sy[4]), Offset(scratch.sx[5], scratch.sy[5]), ow * 2.6f, StrokeCap.Butt)
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
) {
    if (!cam.projectPoint(wx, wy, wz, 0, scratch)) return
    val c = Offset(scratch.sx[0], scratch.sy[0])
    val scale = (380f / scratch.sz[0].coerceAtLeast(10f)).coerceIn(4.5f, 22f)
    val rx = scale * 0.78f
    val ry = scale * 1.15f
    val ink = lighting.inkOutlineColor.copy(alpha = 0.88f * blend)

    // Soft warm glow aura around lantern
    scope.drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFFFA558).copy(alpha = (0.28f + 0.36f * lighting.cityLights) * blend), Color.Transparent),
            center = c,
            radius = ry * 2.2f,
        ),
        radius = ry * 2.2f,
        center = c,
    )

    // Two-tone cel-shaded oval paper lantern body
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
            listOf(Color(0xFFFF6E48).copy(alpha = 0.96f * blend), Color(0xFFB8241C).copy(alpha = 0.96f * blend)),
            start = Offset(c.x - rx, c.y - ry * 0.5f),
            end = Offset(c.x + rx, c.y + ry * 0.8f),
        ),
    )
    // Bamboo horizontal paper ribs
    for (rOff in listOf(-0.48f, 0f, 0.48f)) {
        val yy = c.y + ry * rOff
        val hw = rx * kotlin.math.sqrt((1f - rOff * rOff).coerceAtLeast(0f))
        scope.drawLine(ink.copy(alpha = 0.45f * blend), Offset(c.x - hw, yy), Offset(c.x + hw, yy), 0.85f)
    }
    // Outer ink contour & top/bottom lacquered black wooden caps
    scope.drawPath(bodyPath, ink, style = Stroke(width = 1.2f))
    val capW = rx * 0.55f
    val capH = ry * 0.16f
    scope.drawRect(Color(0xFF1F1824).copy(alpha = 0.95f * blend), Offset(c.x - capW, c.y - ry - capH * 0.5f), androidx.compose.ui.geometry.Size(capW * 2f, capH))
    scope.drawRect(Color(0xFF1F1824).copy(alpha = 0.95f * blend), Offset(c.x - capW, c.y + ry - capH * 0.5f), androidx.compose.ui.geometry.Size(capW * 2f, capH))
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
) {
    val ink = lighting.inkOutlineColor.copy(alpha = 0.90f * blend)
    val ow = (26f / scratch.sz[0].coerceAtLeast(14f)).coerceIn(1.1f, 2.2f)
    val vermilionLit = lerp(Color(0xFFD63B2D), Color(0xFFF05543), lighting.daylight * 0.6f).copy(alpha = 0.96f * blend)
    val vermilionShade = Color(0xFF8A2220).copy(alpha = 0.96f * blend)
    val tileLit = lerp(Color(0xFF343B54), Color(0xFF4C5574), lighting.daylight * 0.5f).copy(alpha = 0.96f * blend)
    val plasterCol = lerp(Color(0xFFDFD8D0), Color(0xFFF4EFE8), lighting.daylight * 0.65f).copy(alpha = 0.94f * blend)

    // Twin thick vermilion gate columns with 2-tone cel strip & ink outline
    for (side in listOf(-1f, 1f)) {
        val xMid = side * halfSpan
        if (cam.projectPoint(xMid - 0.7f, 0f, z, 0, scratch) &&
            cam.projectPoint(xMid + 0.7f, 0f, z, 1, scratch) &&
            cam.projectPoint(xMid + 0.65f, pillarHeight, z, 2, scratch) &&
            cam.projectPoint(xMid - 0.65f, pillarHeight, z, 3, scratch)
        ) {
            fillQuad(scope, scratch, 0, 1, 2, 3, if (side < 0f) vermilionLit else vermilionShade)
            strokeQuad(scope, scratch, 0, 1, 2, 3, ink, ow)
        }
    }

    // Horizontal red lintel beam + white plastered upper frieze band
    if (cam.projectPoint(-halfSpan - 1.2f, pillarHeight - 1.2f, z, 0, scratch) &&
        cam.projectPoint(halfSpan + 1.2f, pillarHeight - 1.2f, z, 1, scratch) &&
        cam.projectPoint(halfSpan + 1.2f, pillarHeight + 0.6f, z, 2, scratch) &&
        cam.projectPoint(-halfSpan - 1.2f, pillarHeight + 0.6f, z, 3, scratch)
    ) {
        fillQuad(scope, scratch, 0, 1, 2, 3, plasterCol)
        strokeQuad(scope, scratch, 0, 1, 2, 3, ink, ow)
    }

    // Sweeping curved Kawara tiled pagoda roof (*sori* upturned eaves!)
    val eaveSpan = halfSpan + 3.6f
    val ridgeSpan = halfSpan + 1.4f
    val roofBaseY = pillarHeight + 0.6f
    val ridgeY = pillarHeight + 3.8f
    if (cam.projectPoint(-eaveSpan, roofBaseY + 0.5f, z, 0, scratch) &&
        cam.projectPoint(eaveSpan, roofBaseY + 0.5f, z, 1, scratch) &&
        cam.projectPoint(ridgeSpan, ridgeY, z + 0.8f, 2, scratch) &&
        cam.projectPoint(-ridgeSpan, ridgeY, z + 0.8f, 3, scratch)
    ) {
        fillQuad(scope, scratch, 0, 1, 2, 3, tileLit)
        // Lit cel highlight rib along the roof ridge
        scope.drawLine(
            color = lighting.rimHighlightColor.copy(alpha = 0.52f * blend),
            start = Offset(scratch.sx[3], scratch.sy[3]),
            end = Offset(scratch.sx[2], scratch.sy[2]),
            strokeWidth = ow * 1.2f,
        )
        strokeQuad(scope, scratch, 0, 1, 2, 3, ink, ow)
    }

    // Hanging detailed Chochin paper lantern under center crossbeam
    drawChochinLantern3D(scope, cam, scratch, swayOffset, pillarHeight - 2.6f, z, lighting, blend)
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
    if (!cam.projectPoint(wx, wy, wz, 0, scratch) ||
        !cam.projectPoint(wx + radiusWorld, wy, wz, 1, scratch)
    ) return

    val center = Offset(scratch.sx[0], scratch.sy[0])
    val rPx = abs(scratch.sx[1] - scratch.sx[0]).coerceIn(14f, 62f)
    val wheelRim = lerp(Color(0xFFFF7DB8), lighting.billboardAccentA, 0.5f + 0.5f * sin(phase * 0.4f))
        .copy(alpha = lerpF(0.65f, 0.94f, lighting.cityLights) * blend)
    val ink = lighting.inkOutlineColor.copy(alpha = 0.82f * blend)

    // Steel A-frame support legs down to bay ground
    if (cam.projectPoint(wx - radiusWorld * 0.48f, 0f, wz, 2, scratch) &&
        cam.projectPoint(wx + radiusWorld * 0.48f, 0f, wz, 3, scratch)
    ) {
        scope.drawLine(ink, center, Offset(scratch.sx[2], scratch.sy[2]), 1.6f)
        scope.drawLine(ink, center, Offset(scratch.sx[3], scratch.sy[3]), 1.6f)
    }

    // Dual structural rings + rotating 14 spokes + gondola pods
    scope.drawCircle(wheelRim, rPx, center, style = Stroke(width = 2.0f))
    scope.drawCircle(lighting.billboardAccentA.copy(alpha = wheelRim.alpha * 0.78f), rPx * 0.74f, center, style = Stroke(width = 1.2f))
    val spokeCount = 14
    val rotOffset = phase * 0.16f
    for (s in 0 until spokeCount) {
        val ang = rotOffset + (s / spokeCount.toFloat()) * 2f * PI.toFloat()
        val gx = center.x + cos(ang) * rPx
        val gy = center.y + sin(ang) * rPx
        scope.drawLine(Color.White.copy(alpha = 0.28f * blend), center, Offset(gx, gy), 0.9f)
        // Small rectangular passenger gondola hanging vertically under rim node
        scope.drawRect(
            color = wheelRim,
            topLeft = Offset(gx - 1.8f, gy),
            size = androidx.compose.ui.geometry.Size(3.6f, 4.2f),
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
    val ok = cam.projectPoint(-160f, deckY, zBridge, 0, scratch) &&
        cam.projectPoint(160f, deckY, zBridge, 1, scratch) &&
        cam.projectPoint(160f, deckY + 3.4f, zBridge, 2, scratch) &&
        cam.projectPoint(-160f, deckY + 3.4f, zBridge, 3, scratch)
    if (!ok) return

    val ink = lighting.inkOutlineColor.copy(alpha = 0.85f * blend)
    val girderCol = lerp(lighting.facadeSideDay, lighting.horizonColor, 0.35f).copy(alpha = 0.94f * blend)
    fillQuad(scope, scratch, 0, 1, 2, 3, girderCol)
    strokeQuad(scope, scratch, 0, 1, 2, 3, ink, 1.15f)

    // Concrete bridge piers supporting viaduct
    for (px in listOf(-68f, -24f, 24f, 68f)) {
        if (cam.projectPoint(px - 1.6f, 0f, zBridge, 4, scratch) &&
            cam.projectPoint(px + 1.6f, 0f, zBridge, 5, scratch) &&
            cam.projectPoint(px + 1.4f, deckY, zBridge, 6, scratch) &&
            cam.projectPoint(px - 1.4f, deckY, zBridge, 7, scratch)
        ) {
            fillQuad(scope, scratch, 4, 5, 6, 7, girderCol)
            strokeQuad(scope, scratch, 4, 5, 6, 7, ink, 1.0f)
        }
    }

    // Gliding Japanese commuter train (e.g., Yamanote green-silver stripe cars!) across viaduct!
    val travel = ((phase * 0.085f) % 1f)
    val trainStartX = lerpF(-140f, 110f, travel)
    val carCount = 4
    val carLen = 13.5f
    val carGap = 1.1f
    val bodyCol = lerp(Color(0xFFD6E0EA), Color(0xFF7B8CA8), lighting.nightness * 0.55f).copy(alpha = 0.96f * blend)
    val stripeCol = Color(0xFF7AD645).copy(alpha = 0.92f * blend)
    val winGlow = lerp(Color(0xFF426E9C), lighting.windowWarmColor, 0.25f + 0.75f * lighting.cityLights)
        .copy(alpha = 0.92f * blend)

    for (c in 0 until carCount) {
        val x0 = trainStartX + c * (carLen + carGap)
        val x1 = x0 + carLen
        if (cam.projectPoint(x0, deckY + 3.4f, zBridge - 0.6f, 8, scratch) &&
            cam.projectPoint(x1, deckY + 3.4f, zBridge - 0.6f, 9, scratch) &&
            cam.projectPoint(x1, deckY + 7.2f, zBridge - 0.6f, 10, scratch) &&
            cam.projectPoint(x0, deckY + 7.2f, zBridge - 0.6f, 11, scratch)
        ) {
            fillQuad(scope, scratch, 8, 9, 10, 11, bodyCol)
            strokeQuad(scope, scratch, 8, 9, 10, 11, ink, 1.0f)
            val sY0 = lerpF(scratch.sy[8], scratch.sy[11], 0.25f)
            val sY1 = lerpF(scratch.sy[9], scratch.sy[10], 0.25f)
            scope.drawLine(stripeCol, Offset(scratch.sx[8], sY0), Offset(scratch.sx[9], sY1), 2.0f)
            // Warm glowing train windows across carriage
            for (wIdx in 0..3) {
                val u0 = 0.14f + wIdx * 0.21f
                val u1 = u0 + 0.14f
                val wx0 = lerpF(scratch.sx[11], scratch.sx[10], u0)
                val wx1 = lerpF(scratch.sx[11], scratch.sx[10], u1)
                val wy = lerpF(scratch.sy[8], scratch.sy[11], 0.58f)
                scope.drawLine(winGlow, Offset(wx0, wy), Offset(wx1, wy), 2.8f)
            }
        }
    }
}
