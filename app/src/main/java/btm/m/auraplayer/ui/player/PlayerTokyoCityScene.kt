// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import btm.m.auraplayer.playback.TokyoSceneLandmark
import btm.m.auraplayer.playback.TokyoSceneTime
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal data class TokyoLightingState(
    val zenithColor: Color,
    val horizonColor: Color,
    val sunGlowColor: Color,
    val facadeFrontDay: Color,
    val facadeSideDay: Color,
    val facadeNight: Color,
    val celShadowColor: Color,
    val rimHighlightColor: Color,
    val inkOutlineColor: Color,
    val roadBaseColor: Color,
    val towerOrangeColor: Color,
    val towerWhiteColor: Color,
    val towerGlowColor: Color,
    val windowWarmColor: Color,
    val windowCoolColor: Color,
    val billboardAccentA: Color,
    val billboardAccentB: Color,
    val daylight: Float,
    val nightness: Float,
    val cityLights: Float,
    val towerGlow: Float,
    val sunDirX: Float = -0.45f,
    val sunDirY: Float = 0.72f,
    val sunDirZ: Float = 0.52f,
)

internal data class TokyoWalkBlend(
    val shibuya: Float = 0f,
    val tokyoTower: Float = 0f,
    val shinjuku: Float = 0f,
    val asakusa: Float = 0f,
    val odaiba: Float = 0f,
    val dominantLandmark: TokyoSceneLandmark = TokyoSceneLandmark.SHIBUYA_CROSSING,
) {
    fun weightOf(landmark: TokyoSceneLandmark): Float = when (landmark) {
        TokyoSceneLandmark.SHIBUYA_CROSSING -> shibuya
        TokyoSceneLandmark.TOKYO_TOWER -> tokyoTower
        TokyoSceneLandmark.SHINJUKU_NEON -> shinjuku
        TokyoSceneLandmark.ASAKUSA_LANTERN -> asakusa
        TokyoSceneLandmark.ODAIBA_BAY -> odaiba
        TokyoSceneLandmark.CITY_WALK -> 1f
    }

    companion object {
        fun single(landmark: TokyoSceneLandmark): TokyoWalkBlend = when (landmark) {
            TokyoSceneLandmark.SHIBUYA_CROSSING -> TokyoWalkBlend(shibuya = 1f, dominantLandmark = TokyoSceneLandmark.SHIBUYA_CROSSING)
            TokyoSceneLandmark.TOKYO_TOWER -> TokyoWalkBlend(tokyoTower = 1f, dominantLandmark = TokyoSceneLandmark.TOKYO_TOWER)
            TokyoSceneLandmark.SHINJUKU_NEON -> TokyoWalkBlend(shinjuku = 1f, dominantLandmark = TokyoSceneLandmark.SHINJUKU_NEON)
            TokyoSceneLandmark.ASAKUSA_LANTERN -> TokyoWalkBlend(asakusa = 1f, dominantLandmark = TokyoSceneLandmark.ASAKUSA_LANTERN)
            TokyoSceneLandmark.ODAIBA_BAY -> TokyoWalkBlend(odaiba = 1f, dominantLandmark = TokyoSceneLandmark.ODAIBA_BAY)
            TokyoSceneLandmark.CITY_WALK -> TokyoWalkBlend(shibuya = 1f, dominantLandmark = TokyoSceneLandmark.SHIBUYA_CROSSING)
        }
    }
}

internal data class CityWalkBillboardStyle(
    val districtTag: String,
    val wallTiltYDeg: Float,
    val wallTiltXDeg: Float,
    val pitchBiasDeg: Float,
    val accentArgb: Int,
    val secondaryAccentArgb: Int,
    val strideCadence: Float,
)

private val CITY_WALK_TOUR_ORDER = listOf(
    TokyoSceneLandmark.SHIBUYA_CROSSING,
    TokyoSceneLandmark.SHINJUKU_NEON,
    TokyoSceneLandmark.TOKYO_TOWER,
    TokyoSceneLandmark.ASAKUSA_LANTERN,
    TokyoSceneLandmark.ODAIBA_BAY,
)

private const val CITY_WALK_SEGMENT_SECONDS = 11.5f
private const val CITY_WALK_MORPH_SECONDS = 3.2f

internal fun resolveTokyoWalkBlend(
    landmark: TokyoSceneLandmark,
    timeSec: Float,
): TokyoWalkBlend {
    if (landmark != TokyoSceneLandmark.CITY_WALK) {
        return TokyoWalkBlend.single(landmark)
    }
    val totalStops = CITY_WALK_TOUR_ORDER.size
    val totalCycle = totalStops * CITY_WALK_SEGMENT_SECONDS
    val wrappedTime = ((timeSec % totalCycle) + totalCycle) % totalCycle
    val idx = (wrappedTime / CITY_WALK_SEGMENT_SECONDS).toInt().coerceIn(0, totalStops - 1)
    val localTime = wrappedTime - idx * CITY_WALK_SEGMENT_SECONDS
    val curr = CITY_WALK_TOUR_ORDER[idx]
    val next = CITY_WALK_TOUR_ORDER[(idx + 1) % totalStops]
    val morphStart = CITY_WALK_SEGMENT_SECONDS - CITY_WALK_MORPH_SECONDS
    val morphT = if (localTime > morphStart) {
        val raw = ((localTime - morphStart) / CITY_WALK_MORPH_SECONDS).coerceIn(0f, 1f)
        raw * raw * (3f - 2f * raw)
    } else {
        0f
    }
    val wCurr = 1f - morphT
    val wNext = morphT

    fun wFor(target: TokyoSceneLandmark): Float =
        (if (curr == target) wCurr else 0f) + (if (next == target) wNext else 0f)

    return TokyoWalkBlend(
        shibuya = wFor(TokyoSceneLandmark.SHIBUYA_CROSSING),
        tokyoTower = wFor(TokyoSceneLandmark.TOKYO_TOWER),
        shinjuku = wFor(TokyoSceneLandmark.SHINJUKU_NEON),
        asakusa = wFor(TokyoSceneLandmark.ASAKUSA_LANTERN),
        odaiba = wFor(TokyoSceneLandmark.ODAIBA_BAY),
        dominantLandmark = if (morphT >= 0.5f) next else curr,
    )
}

internal fun lerpWalkBlend(a: TokyoWalkBlend, b: TokyoWalkBlend, t: Float): TokyoWalkBlend {
    val c = t.coerceIn(0f, 1f)
    val s = lerpF(a.shibuya, b.shibuya, c)
    val tt = lerpF(a.tokyoTower, b.tokyoTower, c)
    val sj = lerpF(a.shinjuku, b.shinjuku, c)
    val ak = lerpF(a.asakusa, b.asakusa, c)
    val od = lerpF(a.odaiba, b.odaiba, c)
    val sum = (s + tt + sj + ak + od).coerceAtLeast(0.001f)
    val dom = when (maxOf(s, tt, sj, ak, od)) {
        tt -> TokyoSceneLandmark.TOKYO_TOWER
        sj -> TokyoSceneLandmark.SHINJUKU_NEON
        ak -> TokyoSceneLandmark.ASAKUSA_LANTERN
        od -> TokyoSceneLandmark.ODAIBA_BAY
        else -> TokyoSceneLandmark.SHIBUYA_CROSSING
    }
    return TokyoWalkBlend(
        shibuya = s / sum,
        tokyoTower = tt / sum,
        shinjuku = sj / sum,
        asakusa = ak / sum,
        odaiba = od / sum,
        dominantLandmark = dom,
    )
}

internal fun resolveCityWalkBillboardStyle(
    landmark: TokyoSceneLandmark,
    timeSec: Float,
): CityWalkBillboardStyle {
    val blend = resolveTokyoWalkBlend(landmark, timeSec)
    val tiltY = blend.shibuya * 21f +
        blend.shinjuku * 29f +
        blend.tokyoTower * 15f +
        blend.asakusa * 18f +
        blend.odaiba * 13f
    val tiltX = blend.shibuya * 8f +
        blend.shinjuku * 12f +
        blend.tokyoTower * 7f +
        blend.asakusa * 6f +
        blend.odaiba * 5f
    val pitchBias = blend.shibuya * 4.5f +
        blend.shinjuku * 7.5f +
        blend.tokyoTower * 5.0f +
        blend.asakusa * 3.5f +
        blend.odaiba * 2.5f

    val accentColor = Color(
        red = (blend.shibuya * 0.36f + blend.shinjuku * 1.0f + blend.tokyoTower * 1.0f + blend.asakusa * 0.96f + blend.odaiba * 0.42f).coerceIn(0f, 1f),
        green = (blend.shibuya * 0.86f + blend.shinjuku * 0.42f + blend.tokyoTower * 0.62f + blend.asakusa * 0.40f + blend.odaiba * 0.82f).coerceIn(0f, 1f),
        blue = (blend.shibuya * 1.0f + blend.shinjuku * 0.68f + blend.tokyoTower * 0.34f + blend.asakusa * 0.32f + blend.odaiba * 1.0f).coerceIn(0f, 1f),
    )
    val secColor = Color(
        red = (blend.shibuya * 1.0f + blend.shinjuku * 0.38f + blend.tokyoTower * 1.0f + blend.asakusa * 1.0f + blend.odaiba * 1.0f).coerceIn(0f, 1f),
        green = (blend.shibuya * 0.56f + blend.shinjuku * 0.94f + blend.tokyoTower * 0.86f + blend.asakusa * 0.80f + blend.odaiba * 0.66f).coerceIn(0f, 1f),
        blue = (blend.shibuya * 0.74f + blend.shinjuku * 1.0f + blend.tokyoTower * 0.58f + blend.asakusa * 0.48f + blend.odaiba * 0.86f).coerceIn(0f, 1f),
    )

    val tag = when (blend.dominantLandmark) {
        TokyoSceneLandmark.SHIBUYA_CROSSING -> "SHIBUYA · SCRAMBLE"
        TokyoSceneLandmark.SHINJUKU_NEON -> "SHINJUKU · KABUKICHO"
        TokyoSceneLandmark.TOKYO_TOWER -> "MINATO · TOKYO TOWER"
        TokyoSceneLandmark.ASAKUSA_LANTERN -> "ASAKUSA · NAKAMISE"
        TokyoSceneLandmark.ODAIBA_BAY -> "ODAIBA · RAINBOW BAY"
        TokyoSceneLandmark.CITY_WALK -> "TOKYO · WALK"
    }

    return CityWalkBillboardStyle(
        districtTag = tag,
        wallTiltYDeg = tiltY,
        wallTiltXDeg = tiltX,
        pitchBiasDeg = pitchBias,
        accentArgb = accentColor.toArgb(),
        secondaryAccentArgb = secColor.toArgb(),
        strideCadence = timeSec * 1.48f,
    )
}

private data class TokyoLightingKeyframe(
    val hour: Float,
    val state: TokyoLightingState,
)

// Luminous anime Cel-Shading keyframes (clean warm-ivory concrete, lavender-indigo shadows, crisp ink).
private val KEYFRAME_NIGHT = TokyoLightingState(
    zenithColor = Color(0xFF0A122C),
    horizonColor = Color(0xFF23305A),
    sunGlowColor = Color(0xFF7B98DC),
    facadeFrontDay = Color(0xFF293458),
    facadeSideDay = Color(0xFF1A2340),
    facadeNight = Color(0xFF1F294A),
    celShadowColor = Color(0xFF111832),
    rimHighlightColor = Color(0xFF9AC0FF),
    inkOutlineColor = Color(0xFF0D1226),
    roadBaseColor = Color(0xFF13192D),
    towerOrangeColor = Color(0xFFC45238),
    towerWhiteColor = Color(0xFF6B7BA0),
    towerGlowColor = Color(0xFFFFA85C),
    windowWarmColor = Color(0xFFFFDF9E),
    windowCoolColor = Color(0xFF8BE4FF),
    billboardAccentA = Color(0xFF5AE6FF),
    billboardAccentB = Color(0xFFFF72AA),
    daylight = 0.0f,
    nightness = 1.0f,
    cityLights = 1.0f,
    towerGlow = 1.0f,
    sunDirX = 0.45f,
    sunDirY = 0.64f,
    sunDirZ = 0.62f,
)

private val KEYFRAME_DAWN = TokyoLightingState(
    zenithColor = Color(0xFF283A6E),
    horizonColor = Color(0xFFEA9C8E),
    sunGlowColor = Color(0xFFFFC99C),
    facadeFrontDay = Color(0xFF9E8CA8),
    facadeSideDay = Color(0xFF565178),
    facadeNight = Color(0xFF5D577E),
    celShadowColor = Color(0xFF3E3A64),
    rimHighlightColor = Color(0xFFFFD6B8),
    inkOutlineColor = Color(0xFF282246),
    roadBaseColor = Color(0xFF343550),
    towerOrangeColor = Color(0xFFF07352),
    towerWhiteColor = Color(0xFFF8ECEF),
    towerGlowColor = Color(0xFFFFBA7A),
    windowWarmColor = Color(0xFFFFDC9C),
    windowCoolColor = Color(0xFFAFE2FF),
    billboardAccentA = Color(0xFF7AE0FF),
    billboardAccentB = Color(0xFFFF96B6),
    daylight = 0.34f,
    nightness = 0.30f,
    cityLights = 0.45f,
    towerGlow = 0.38f,
    sunDirX = -0.62f,
    sunDirY = 0.36f,
    sunDirZ = 0.70f,
)

private val KEYFRAME_MORNING = TokyoLightingState(
    zenithColor = Color(0xFF2B6BB0),
    horizonColor = Color(0xFF92C2E6),
    sunGlowColor = Color(0xFFFFF0CA),
    facadeFrontDay = Color(0xFFE0E5EE),
    facadeSideDay = Color(0xFF6B85AE),
    facadeNight = Color(0xFF7D95BC),
    celShadowColor = Color(0xFF506B98),
    rimHighlightColor = Color(0xFFFFFAEA),
    inkOutlineColor = Color(0xFF223357),
    roadBaseColor = Color(0xFF4B5870),
    towerOrangeColor = Color(0xFFF26A44),
    towerWhiteColor = Color(0xFFF6F9FF),
    towerGlowColor = Color(0xFFFFC48A),
    windowWarmColor = Color(0xFFFFE8BE),
    windowCoolColor = Color(0xFFC4EAFF),
    billboardAccentA = Color(0xFF6CD6FF),
    billboardAccentB = Color(0xFFFF98B8),
    daylight = 0.84f,
    nightness = 0.0f,
    cityLights = 0.14f,
    towerGlow = 0.0f,
    sunDirX = -0.52f,
    sunDirY = 0.70f,
    sunDirZ = 0.49f,
)

private val KEYFRAME_NOON = TokyoLightingState(
    zenithColor = Color(0xFF2366AC),
    horizonColor = Color(0xFF8CC0E6),
    sunGlowColor = Color(0xFFFFF8E4),
    facadeFrontDay = Color(0xFFECEAE6),
    facadeSideDay = Color(0xFF6D88B3),
    facadeNight = Color(0xFF849DC2),
    celShadowColor = Color(0xFF516C9A),
    rimHighlightColor = Color(0xFFFFFFFF),
    inkOutlineColor = Color(0xFF213154),
    roadBaseColor = Color(0xFF525F78),
    towerOrangeColor = Color(0xFFF5653D),
    towerWhiteColor = Color(0xFFFAFCFF),
    towerGlowColor = Color(0xFFFFCD9A),
    windowWarmColor = Color(0xFFFFEDC8),
    windowCoolColor = Color(0xFFD6F0FF),
    billboardAccentA = Color(0xFF78DEFF),
    billboardAccentB = Color(0xFFFFA0BE),
    daylight = 1.0f,
    nightness = 0.0f,
    cityLights = 0.10f,
    towerGlow = 0.0f,
    sunDirX = -0.35f,
    sunDirY = 0.86f,
    sunDirZ = 0.37f,
)

private val KEYFRAME_DUSK = TokyoLightingState(
    zenithColor = Color(0xFF2B2259),
    horizonColor = Color(0xFFEE7B60),
    sunGlowColor = Color(0xFFFFA166),
    facadeFrontDay = Color(0xFF9A7B92),
    facadeSideDay = Color(0xFF42355D),
    facadeNight = Color(0xFF4B3C68),
    celShadowColor = Color(0xFF2F254D),
    rimHighlightColor = Color(0xFFFFB87A),
    inkOutlineColor = Color(0xFF23193D),
    roadBaseColor = Color(0xFF2C2542),
    towerOrangeColor = Color(0xFFEC6444),
    towerWhiteColor = Color(0xFFDDC9DA),
    towerGlowColor = Color(0xFFFF9B52),
    windowWarmColor = Color(0xFFFFD88E),
    windowCoolColor = Color(0xFF8CE0FF),
    billboardAccentA = Color(0xFF5AE6FF),
    billboardAccentB = Color(0xFFFF78A2),
    daylight = 0.24f,
    nightness = 0.45f,
    cityLights = 0.88f,
    towerGlow = 0.92f,
    sunDirX = -0.74f,
    sunDirY = 0.26f,
    sunDirZ = 0.62f,
)

private val TOKYO_CIRCADIAN_KEYFRAMES = listOf(
    TokyoLightingKeyframe(0.0f, KEYFRAME_NIGHT),
    TokyoLightingKeyframe(4.5f, KEYFRAME_NIGHT),
    TokyoLightingKeyframe(5.75f, KEYFRAME_DAWN),
    TokyoLightingKeyframe(8.5f, KEYFRAME_MORNING),
    TokyoLightingKeyframe(12.5f, KEYFRAME_NOON),
    TokyoLightingKeyframe(16.25f, KEYFRAME_MORNING),
    TokyoLightingKeyframe(17.85f, KEYFRAME_DUSK),
    TokyoLightingKeyframe(19.3f, KEYFRAME_NIGHT),
    TokyoLightingKeyframe(24.0f, KEYFRAME_NIGHT),
)

internal fun resolveTokyoLighting(
    setting: TokyoSceneTime,
    hour24: Int,
    minute: Int = 0,
): TokyoLightingState {
    val targetHour = when (setting) {
        TokyoSceneTime.DAWN -> 5.75f
        TokyoSceneTime.MORNING -> 8.5f
        TokyoSceneTime.NOON -> 12.5f
        TokyoSceneTime.DUSK -> 17.85f
        TokyoSceneTime.NIGHT -> 22.0f
        TokyoSceneTime.FOLLOW_SYSTEM -> {
            val wrappedHour = ((hour24 % 24) + 24) % 24
            wrappedHour + minute.coerceIn(0, 59) / 60f
        }
    }
    for (i in 0 until TOKYO_CIRCADIAN_KEYFRAMES.lastIndex) {
        val a = TOKYO_CIRCADIAN_KEYFRAMES[i]
        val b = TOKYO_CIRCADIAN_KEYFRAMES[i + 1]
        if (targetHour <= b.hour || i == TOKYO_CIRCADIAN_KEYFRAMES.lastIndex - 1) {
            val span = (b.hour - a.hour).coerceAtLeast(0.001f)
            val rawT = ((targetHour - a.hour) / span).coerceIn(0f, 1f)
            val smoothT = rawT * rawT * (3f - 2f * rawT)
            return lerpLighting(a.state, b.state, smoothT)
        }
    }
    return KEYFRAME_NIGHT
}

internal fun resolveCurrentTokyoLighting(setting: TokyoSceneTime): TokyoLightingState {
    if (setting != TokyoSceneTime.FOLLOW_SYSTEM) {
        return resolveTokyoLighting(setting, 12, 0)
    }
    val cal = Calendar.getInstance()
    return resolveTokyoLighting(
        setting = setting,
        hour24 = cal.get(Calendar.HOUR_OF_DAY),
        minute = cal.get(Calendar.MINUTE),
    )
}

private fun lerpLighting(a: TokyoLightingState, b: TokyoLightingState, t: Float): TokyoLightingState {
    val sx = lerpF(a.sunDirX, b.sunDirX, t)
    val sy = lerpF(a.sunDirY, b.sunDirY, t)
    val sz = lerpF(a.sunDirZ, b.sunDirZ, t)
    val len = sqrt(sx * sx + sy * sy + sz * sz).coerceAtLeast(0.001f)
    return TokyoLightingState(
        zenithColor = lerp(a.zenithColor, b.zenithColor, t),
        horizonColor = lerp(a.horizonColor, b.horizonColor, t),
        sunGlowColor = lerp(a.sunGlowColor, b.sunGlowColor, t),
        facadeFrontDay = lerp(a.facadeFrontDay, b.facadeFrontDay, t),
        facadeSideDay = lerp(a.facadeSideDay, b.facadeSideDay, t),
        facadeNight = lerp(a.facadeNight, b.facadeNight, t),
        celShadowColor = lerp(a.celShadowColor, b.celShadowColor, t),
        rimHighlightColor = lerp(a.rimHighlightColor, b.rimHighlightColor, t),
        inkOutlineColor = lerp(a.inkOutlineColor, b.inkOutlineColor, t),
        roadBaseColor = lerp(a.roadBaseColor, b.roadBaseColor, t),
        towerOrangeColor = lerp(a.towerOrangeColor, b.towerOrangeColor, t),
        towerWhiteColor = lerp(a.towerWhiteColor, b.towerWhiteColor, t),
        towerGlowColor = lerp(a.towerGlowColor, b.towerGlowColor, t),
        windowWarmColor = lerp(a.windowWarmColor, b.windowWarmColor, t),
        windowCoolColor = lerp(a.windowCoolColor, b.windowCoolColor, t),
        billboardAccentA = lerp(a.billboardAccentA, b.billboardAccentA, t),
        billboardAccentB = lerp(a.billboardAccentB, b.billboardAccentB, t),
        daylight = lerpF(a.daylight, b.daylight, t),
        nightness = lerpF(a.nightness, b.nightness, t),
        cityLights = lerpF(a.cityLights, b.cityLights, t),
        towerGlow = lerpF(a.towerGlow, b.towerGlow, t),
        sunDirX = sx / len,
        sunDirY = sy / len,
        sunDirZ = sz / len,
    )
}

internal data class TokyoFarTowerSpec(
    val x: Float,
    val z: Float,
    val width: Float,
    val height: Float,
    val stepTier: Boolean,
    val beaconPhase: Float,
)

internal class TokyoCityBlueprint {
    val farTowers: List<TokyoFarTowerSpec> = listOf(
        TokyoFarTowerSpec(-215f, 315f, 34f, 88f, stepTier = true, 0.7f),
        TokyoFarTowerSpec(-162f, 295f, 32f, 114f, stepTier = true, 0.2f),
        TokyoFarTowerSpec(-112f, 282f, 28f, 140f, stepTier = false, 1.4f),
        TokyoFarTowerSpec(-66f, 304f, 34f, 98f, stepTier = true, 2.7f),
        TokyoFarTowerSpec(68f, 290f, 32f, 128f, stepTier = true, 3.6f),
        TokyoFarTowerSpec(116f, 276f, 34f, 148f, stepTier = false, 4.9f),
        TokyoFarTowerSpec(168f, 302f, 34f, 112f, stepTier = true, 5.5f),
        TokyoFarTowerSpec(222f, 316f, 36f, 92f, stepTier = false, 1.9f),
    )
}

internal fun drawTokyoCityscapeScene(
    scope: DrawScope,
    lighting: TokyoLightingState,
    phase: Float,
    spatialTilt: Offset,
    blend: Float,
    walkBlend: TokyoWalkBlend,
    drawFallbackSky: Boolean,
    blueprint: TokyoCityBlueprint,
    scratch: TokyoRenderScratch,
) {
    if (blend <= 0.01f) return
    val w = scope.size.width
    val h = scope.size.height
    if (w <= 1f || h <= 1f) return

    // Adapt camera framing seamlessly between tall portrait phones (9:19.5 / 9:16)
    // and wide landscape / split-pane viewports (16:9 / 19.5:9).
    val aspect = (w / h).coerceIn(0.40f, 2.60f)
    val landscapeMix = ((aspect - 0.85f) / 0.85f).coerceIn(0f, 1f)

    val strideRad = phase * 1.48f * (2f * PI.toFloat())
    val walkBobY = (abs(sin(strideRad)) - 0.5f) * 0.30f
    val walkSwayX = cos(strideRad * 0.5f) * 0.36f

    val camHeight = walkBlend.shibuya * 7.2f +
        walkBlend.shinjuku * 4.5f +
        walkBlend.tokyoTower * 5.2f +
        walkBlend.asakusa * 3.9f +
        walkBlend.odaiba * 6.2f + walkBobY

    val yawBias = walkBlend.shinjuku * 0.024f - walkBlend.tokyoTower * 0.018f + walkBlend.odaiba * 0.022f
    val horizonFrac = lerpF(0.47f, 0.495f, landscapeMix) + walkBlend.shinjuku * 0.015f
    val horizonY = h * horizonFrac + spatialTilt.y * h * 0.042f + walkBobY * 3.8f

    val yaw = -spatialTilt.x * 0.068f + yawBias
    val pitch = spatialTilt.y * 0.036f - 0.014f
    val baseFocal = lerpF(w * 1.12f, h * 1.32f, landscapeMix)
    val focal = baseFocal * (1f + walkBlend.shinjuku * 0.05f - walkBlend.odaiba * 0.04f)

    val cam = Camera3D(
        x = spatialTilt.x * 7.5f + walkSwayX,
        y = camHeight,
        z = -18f,
        sinYaw = sin(yaw),
        cosYaw = cos(yaw),
        sinPitch = sin(pitch),
        cosPitch = cos(pitch),
        focal = focal,
        halfW = w * 0.5f,
        horizonY = horizonY,
    )

    if (drawFallbackSky) {
        drawSkyFallback(scope, lighting, horizonY, spatialTilt, blend)
    }

    drawTokyoFarSkyline(scope, cam, scratch, lighting, phase, blend, blueprint.farTowers)

    if (walkBlend.odaiba > 0.01f) {
        drawOdaibaDistrict(scope, cam, scratch, lighting, phase, landscapeMix, blend * walkBlend.odaiba)
    }
    if (walkBlend.tokyoTower > 0.01f) {
        drawTokyoTowerDistrict(scope, cam, scratch, lighting, phase, landscapeMix, blend * walkBlend.tokyoTower)
    }
    if (walkBlend.asakusa > 0.01f) {
        drawAsakusaDistrict(scope, cam, scratch, lighting, phase, landscapeMix, blend * walkBlend.asakusa)
    }
    if (walkBlend.shinjuku > 0.01f) {
        drawShinjukuDistrict(scope, cam, scratch, lighting, phase, landscapeMix, blend * walkBlend.shinjuku)
    }
    if (walkBlend.shibuya > 0.01f) {
        drawShibuyaDistrict(scope, cam, scratch, lighting, phase, landscapeMix, blend * walkBlend.shibuya)
    }

    drawAnimePetalsAndLightMotes(scope, lighting, phase, spatialTilt, blend)
    drawTokyoAtmosphereVeil(scope, lighting, horizonY, blend)
}

private fun drawSkyFallback(
    scope: DrawScope,
    lighting: TokyoLightingState,
    horizonY: Float,
    tilt: Offset,
    blend: Float,
) {
    val w = scope.size.width
    val h = scope.size.height
    scope.drawRect(
        brush = Brush.verticalGradient(
            0.0f to lighting.zenithColor.copy(alpha = blend),
            (horizonY / h).coerceIn(0.25f, 0.75f) to lighting.horizonColor.copy(alpha = blend),
            1.0f to lighting.roadBaseColor.copy(alpha = blend),
        ),
    )
    val orbCenter = Offset(
        x = w * (0.26f - tilt.x * 0.05f),
        y = horizonY * lerpF(0.86f, 0.36f, lighting.daylight),
    )
    scope.drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(lighting.sunGlowColor.copy(alpha = 0.36f * blend), Color.Transparent),
            center = orbCenter,
            radius = min(w, h) * 0.42f,
        ),
        radius = min(w, h) * 0.42f,
        center = orbCenter,
    )
}

private fun drawTokyoFarSkyline(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    lighting: TokyoLightingState,
    phase: Float,
    blend: Float,
    farTowers: List<TokyoFarTowerSpec>,
) {
    // Aerial-perspective pastel silhouettes with subtle cel step split & atmospheric outline.
    val celBody = lerp(
        lighting.horizonColor,
        lerp(Color(0xFF42547A), Color(0xFF1A2240), lighting.nightness),
        0.52f + 0.34f * lighting.nightness,
    ).copy(alpha = 0.76f * blend)
    val celLitStrip = lerp(celBody, lighting.sunGlowColor, 0.18f * (0.3f + 0.7f * lighting.daylight))
        .copy(alpha = 0.74f * blend)
    val inkCol = lerp(lighting.inkOutlineColor, lighting.horizonColor, 0.56f).copy(alpha = 0.52f * blend)

    for (tower in farTowers) {
        val hw = tower.width * 0.5f
        val ok = cam.projectPoint(tower.x - hw, 0f, tower.z, 0, scratch) &&
            cam.projectPoint(tower.x + hw, 0f, tower.z, 1, scratch) &&
            cam.projectPoint(tower.x + hw, tower.height, tower.z, 2, scratch) &&
            cam.projectPoint(tower.x - hw, tower.height, tower.z, 3, scratch)
        if (!ok) continue
        fillQuad(scope, scratch, 0, 1, 2, 3, celBody)
        // Lit side rim sliver facing sun
        if (cam.projectPoint(tower.x - hw, 0f, tower.z, 4, scratch) &&
            cam.projectPoint(tower.x - hw + tower.width * 0.24f, 0f, tower.z, 5, scratch) &&
            cam.projectPoint(tower.x - hw + tower.width * 0.24f, tower.height, tower.z, 6, scratch) &&
            cam.projectPoint(tower.x - hw, tower.height, tower.z, 7, scratch)
        ) {
            fillQuad(scope, scratch, 4, 5, 6, 7, celLitStrip)
        }
        strokeQuad(scope, scratch, 0, 1, 2, 3, inkCol, 0.85f)

        if (tower.stepTier &&
            cam.projectPoint(tower.x - hw * 0.55f, tower.height, tower.z, 4, scratch) &&
            cam.projectPoint(tower.x + hw * 0.55f, tower.height, tower.z, 5, scratch) &&
            cam.projectPoint(tower.x + hw * 0.55f, tower.height + 12f, tower.z, 6, scratch) &&
            cam.projectPoint(tower.x - hw * 0.55f, tower.height + 12f, tower.z, 7, scratch)
        ) {
            fillQuad(scope, scratch, 4, 5, 6, 7, celBody)
            strokeQuad(scope, scratch, 4, 5, 6, 7, inkCol, 0.8f)
        }

        if (lighting.cityLights > 0.12f) {
            val pulse = (0.45f + 0.55f * sin(phase * PI.toFloat() * 2f + tower.beaconPhase)).coerceIn(0f, 1f)
            val topY = if (tower.stepTier) tower.height + 14.5f else tower.height + 2.5f
            if (cam.projectPoint(tower.x, topY, tower.z, 8, scratch)) {
                scope.drawCircle(
                    color = Color(0xFFFF5264).copy(alpha = pulse * lighting.cityLights * 0.82f * blend),
                    radius = 2.4f,
                    center = Offset(scratch.sx[8], scratch.sy[8]),
                )
            }
        }
    }
}

private fun drawWalkingRoadBed(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    lighting: TokyoLightingState,
    roadHalfW: Float,
    laneAccentColor: Color,
    phase: Float,
    blend: Float,
    pedestrianPromenade: Boolean = false,
) {
    if (blend <= 0.01f) return
    val roadNearCol = lighting.roadBaseColor.copy(alpha = 0.96f * blend)
    val roadFarCol = lerp(lighting.roadBaseColor, lighting.horizonColor, 0.30f).copy(alpha = 0.90f * blend)
    drawClippedQuad3D(
        scope, cam, scratch,
        -190f, 0f, -14f,
        190f, 0f, -14f,
        240f, 0f, 225f,
        -240f, 0f, 225f,
        lerp(roadNearCol, roadFarCol, 0.38f),
    )

    // Raised Japanese sidewalks with 3D vertical curb riser & tactile yellow Braille blocks
    val sidewalkCol = lerp(lighting.facadeSideDay, lighting.roadBaseColor, 0.38f).copy(alpha = 0.93f * blend)
    val curbRiserCol = lerp(lighting.celShadowColor, lighting.inkOutlineColor, 0.35f).copy(alpha = 0.94f * blend)
    val outerSpan = 160f
    drawGroundRect(scope, cam, scratch, -outerSpan, -roadHalfW, -14f, 185f, sidewalkCol, y = 0.22f)
    drawGroundRect(scope, cam, scratch, roadHalfW, outerSpan, -14f, 185f, sidewalkCol, y = 0.22f)

    val ink = lighting.inkOutlineColor.copy(alpha = 0.84f * blend)
    drawClippedQuad3D(scope, cam, scratch, -roadHalfW, 0f, -14f, -roadHalfW, 0.22f, -14f, -roadHalfW, 0.22f, 185f, -roadHalfW, 0f, 185f, curbRiserCol, ink, 1.15f)
    drawClippedQuad3D(scope, cam, scratch, roadHalfW, 0f, -14f, roadHalfW, 0.22f, -14f, roadHalfW, 0.22f, 185f, roadHalfW, 0f, 185f, curbRiserCol, ink, 1.15f)

    // Iconic Japanese yellow tactile paving strips along both sidewalks
    val tactileCol = lerp(Color(0xFFE5BA42), lighting.horizonColor, 0.16f + 0.36f * lighting.nightness)
        .copy(alpha = lerpF(0.65f, 0.88f, lighting.daylight) * blend)
    drawGroundRect(scope, cam, scratch, -roadHalfW - 1.55f, -roadHalfW - 0.95f, -10f, 155f, tactileCol, y = 0.24f)
    drawGroundRect(scope, cam, scratch, roadHalfW + 0.95f, roadHalfW + 1.55f, -10f, 155f, tactileCol, y = 0.24f)

    val strideSpacing = 20f
    val travelZ = (phase * 7.8f) % strideSpacing
    if (!pedestrianPromenade) {
        // Center road dashed divider lines
        val laneDashCol = lerp(Color(0xFFD5DFED), lighting.horizonColor, 0.25f)
            .copy(alpha = lerpF(0.45f, 0.72f, lighting.daylight) * blend)
        for (slot in 0..8) {
            val z0 = slot * strideSpacing - travelZ
            if (z0 < -10f || z0 > 165f) continue
            drawGroundRect(scope, cam, scratch, -0.22f, 0.22f, z0, z0 + 7.2f, laneDashCol, y = 0.05f)
        }
    }

    val curbGlow = laneAccentColor.copy(alpha = (0.20f + 0.42f * lighting.cityLights) * blend)
    for (slot in 0..7) {
        val z0 = slot * strideSpacing - travelZ
        if (z0 < -8f || z0 > 155f) continue
        val z1 = z0 + 6.5f
        drawClippedQuad3D(scope, cam, scratch, -roadHalfW - 0.18f, 0.24f, z0, -roadHalfW, 0.24f, z0, -roadHalfW, 0.24f, z1, -roadHalfW - 0.18f, 0.24f, z1, curbGlow)
        drawClippedQuad3D(scope, cam, scratch, roadHalfW, 0.24f, z0, roadHalfW + 0.18f, 0.24f, z0, roadHalfW + 0.18f, 0.24f, z1, roadHalfW, 0.24f, z1, curbGlow)
    }
}

private fun drawShibuyaDistrict(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    lighting: TokyoLightingState,
    phase: Float,
    landscapeMix: Float,
    blend: Float,
) {
    drawWalkingRoadBed(scope, cam, scratch, lighting, roadHalfW = 19f, laneAccentColor = lighting.billboardAccentA, phase = phase, blend = blend)

    // Elevated Yamanote viaduct + animated commuter train across Shibuya!
    drawElevatedTrainViaduct(scope, cam, scratch, lighting, zBridge = 96f, deckY = 17.5f, phase = phase, blend = blend)

    // 4-way Shibuya Scramble Zebra crosswalks
    val stripeColor = lerp(Color(0xFFC8D2E0), Color(0xFFF3F7FC), lighting.daylight * 0.5f + 0.28f)
        .copy(alpha = lerpF(0.62f, 0.86f, lighting.daylight) * blend)
    val loopLen = 58f
    val scrollZ = (phase * 6.5f) % loopLen
    for (rep in 0..2) {
        val plazaZ = 34f + rep * loopLen - scrollZ
        if (plazaZ < -12f || plazaZ > 145f) continue
        for (step in -5..5) {
            val sx0 = step * 3.1f
            drawGroundRect(scope, cam, scratch, sx0 - 0.8f, sx0 + 0.8f, plazaZ - 14f, plazaZ - 10f, stripeColor, y = 0.06f)
            drawGroundRect(scope, cam, scratch, sx0 - 0.8f, sx0 + 0.8f, plazaZ + 10f, plazaZ + 14f, stripeColor, y = 0.06f)
            val u = step * 3.0f
            drawDiagonalStripe(scope, cam, scratch, u, plazaZ + u * 0.62f, 1.5f, 1.15f, -0.62f, stripeColor)
            drawDiagonalStripe(scope, cam, scratch, u, plazaZ - u * 0.62f, 1.5f, 1.15f, 0.62f, stripeColor)
        }
    }

    val blockSpacing = 36f
    val bScroll = (phase * 7.8f) % blockSpacing
    for (slot in 4 downTo 0) {
        val zCenter = 22f + slot * blockSpacing - bScroll
        if (zCenter < -10f || zCenter > 175f) continue

        // Secondary background urban block tier so wide landscape (16:9 / 19.5:9) is richly populated
        if (landscapeMix > 0.04f) {
            drawCelBuildingPair(
                scope = scope, cam = cam, scratch = scratch, lighting = lighting,
                xInner = 54f, width = 28f, depth = 24f, zCenter = zCenter + 8f,
                leftHeight = 64f + ((slot + 1) % 3) * 14f,
                rightHeight = 68f + (slot % 3) * 12f,
                style = TokyoBuildingStyle.GLASS_TOWER,
                hasMegaScreen = false, hasBalconies = false, phase = phase,
                blend = blend * (0.65f + 0.30f * landscapeMix),
                seed = 700 + slot * 17,
            )
        }

        drawCelBuildingPair(
            scope = scope,
            cam = cam,
            scratch = scratch,
            lighting = lighting,
            xInner = 20.5f,
            width = 24f,
            depth = 22f,
            zCenter = zCenter,
            leftHeight = 46f + (slot % 2) * 14f,
            rightHeight = 54f + ((slot + 1) % 3) * 12f,
            style = TokyoBuildingStyle.GLASS_TOWER,
            hasMegaScreen = true,
            hasBalconies = false,
            phase = phase,
            blend = blend,
            seed = 50 + slot * 13,
        )

        // Roadside street trees & glowing Japanese vending machines on sidewalks
        if (slot % 2 == 0) {
            drawStreetTree3D(scope, cam, scratch, -17.6f, zCenter - 11f, 9.2f, lighting, phase, blend, slot * 5)
            drawStreetTree3D(scope, cam, scratch, 17.6f, zCenter + 11f, 9.2f, lighting, phase, blend, slot * 5 + 1)
        } else {
            drawVendingMachine3D(scope, cam, scratch, -19.2f, zCenter - 11f, facingSign = 1f, lighting = lighting, blend = blend, seed = slot)
            drawVendingMachine3D(scope, cam, scratch, 19.2f, zCenter + 11f, facingSign = -1f, lighting = lighting, blend = blend, seed = slot + 1)
        }
    }
}

private fun drawShinjukuDistrict(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    lighting: TokyoLightingState,
    phase: Float,
    landscapeMix: Float,
    blend: Float,
) {
    drawWalkingRoadBed(scope, cam, scratch, lighting, roadHalfW = 11.5f, laneAccentColor = lighting.billboardAccentB, phase = phase, blend = blend)

    // Soft pastel anime rain-mirror sheen across central Kabukicho street
    if (lighting.cityLights > 0.1f &&
        cam.projectPoint(-8.5f, 0.06f, 8f, 0, scratch) &&
        cam.projectPoint(8.5f, 0.06f, 8f, 1, scratch) &&
        cam.projectPoint(8.5f, 0.06f, 125f, 2, scratch) &&
        cam.projectPoint(-8.5f, 0.06f, 125f, 3, scratch)
    ) {
        fillQuad(
            scope, scratch, 0, 1, 2, 3,
            Brush.horizontalGradient(
                0.0f to lighting.billboardAccentB.copy(alpha = 0.22f * lighting.cityLights * blend),
                0.5f to lighting.billboardAccentA.copy(alpha = 0.16f * lighting.cityLights * blend),
                1.0f to Color(0xFFFFB25C).copy(alpha = 0.18f * lighting.cityLights * blend),
                startX = scratch.sx[0].coerceAtMost(scratch.sx[1]),
                endX = scratch.sx[0].coerceAtLeast(scratch.sx[1]),
            ),
        )
    }

    // Iconic crimson Kabukicho Ichibangai Entrance Archway spanning overhead (`z = 54m`)
    drawKabukichoGateArch(scope, cam, scratch, zGate = 54f, halfSpan = 11.5f, lighting = lighting, blend = blend)

    // Dense cel-shaded zakkyo buildings, flanking skyline in landscape, bracket signs & catenary wires!
    val spacing = 22f
    val bScroll = (phase * 7.8f) % spacing
    var prevPoleZ: Float? = null
    for (slot in 6 downTo 0) {
        val zCenter = 12f + slot * spacing - bScroll
        if (zCenter < -8f || zCenter > 160f) continue

        if (landscapeMix > 0.04f) {
            drawCelBuildingPair(
                scope = scope, cam = cam, scratch = scratch, lighting = lighting,
                xInner = 34f, width = 24f, depth = 20f, zCenter = zCenter + 4f,
                leftHeight = 56f + (slot % 3) * 12f,
                rightHeight = 60f + ((slot + 1) % 3) * 14f,
                style = TokyoBuildingStyle.GLASS_TOWER,
                hasMegaScreen = false, hasBalconies = false, phase = phase,
                blend = blend * (0.68f + 0.26f * landscapeMix),
                seed = 820 + slot * 13,
            )
        }

        drawCelBuildingPair(
            scope = scope,
            cam = cam,
            scratch = scratch,
            lighting = lighting,
            xInner = 12.2f,
            width = 18f,
            depth = 15f,
            zCenter = zCenter,
            leftHeight = 40f + (slot % 3) * 10f,
            rightHeight = 44f + ((slot + 1) % 3) * 12f,
            style = TokyoBuildingStyle.ZAKKYO_COMMERCIAL,
            hasMegaScreen = false,
            hasBalconies = true,
            phase = phase,
            blend = blend,
            seed = 120 + slot * 19,
        )
        drawVerticalBracketSign3D(scope, cam, scratch, -12.0f, 3.3f, zCenter - 2.5f, 8.5f, 25.5f, -1, lighting, phase, blend, slot * 7)
        drawVerticalBracketSign3D(scope, cam, scratch, 12.0f, 3.3f, zCenter + 2.5f, 10f, 28.5f, 1, lighting, phase, blend, slot * 11 + 3)
        if (slot % 2 == 1) {
            drawVendingMachine3D(scope, cam, scratch, -11.6f, zCenter + 6.5f, facingSign = 1f, lighting = lighting, blend = blend, seed = slot + 2)
        }

        drawUtilityPole3D(scope, cam, scratch, -10.8f, zCenter, 19f, lighting, blend)
        drawUtilityPole3D(scope, cam, scratch, 10.8f, zCenter + 4f, 19f, lighting, blend)
        if (slot % 2 == 0) {
            drawCatenaryWireSpan(scope, cam, scratch, -10.8f, 17.8f, zCenter, 10.8f, 17.8f, zCenter + 4f, 1.4f, lighting.inkOutlineColor.copy(alpha = 0.68f * blend), 1.0f)
        }
        prevPoleZ?.let { pz ->
            drawCatenaryWireSpan(scope, cam, scratch, -10.8f, 17.5f, pz, -10.8f, 17.5f, zCenter, 1.2f, lighting.inkOutlineColor.copy(alpha = 0.68f * blend), 1.0f)
            drawCatenaryWireSpan(scope, cam, scratch, 10.8f, 17.5f, pz + 4f, 10.8f, 17.5f, zCenter + 4f, 1.2f, lighting.inkOutlineColor.copy(alpha = 0.68f * blend), 1.0f)
        }
        prevPoleZ = zCenter
    }
}

private fun drawKabukichoGateArch(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    zGate: Float,
    halfSpan: Float,
    lighting: TokyoLightingState,
    blend: Float,
) {
    val ow = (26f / (zGate - cam.z).coerceAtLeast(16f)).coerceIn(1.1f, 2.0f)
    val archRed = lerp(Color(0xFFD63838), Color(0xFFFF4A4A), 0.35f + 0.55f * lighting.cityLights)
    for (side in listOf(-1f, 1f)) {
        val xCol = side * halfSpan
        drawCelBox3D(scope, cam, scratch, lighting, xCol - 0.65f, xCol + 0.65f, 0f, 12.8f, zGate - 0.6f, zGate + 0.6f, archRed, 0.08f, ow, blend)
    }
    drawCelBox3D(scope, cam, scratch, lighting, -halfSpan - 0.9f, halfSpan + 0.9f, 11.4f, 15.2f, zGate - 0.7f, zGate + 0.7f, archRed, 0.08f, ow, blend)
    if (cam.projectPoint(-halfSpan * 0.62f, 13.3f, zGate - 0.72f, 0, scratch) &&
        cam.projectPoint(halfSpan * 0.62f, 13.3f, zGate - 0.72f, 1, scratch)
    ) {
        scope.drawLine(
            color = Color(0xFFFFF2DF).copy(alpha = (0.48f + 0.48f * lighting.cityLights) * blend),
            start = Offset(scratch.sx[0], scratch.sy[0]),
            end = Offset(scratch.sx[1], scratch.sy[1]),
            strokeWidth = (ow * 2.2f).coerceAtLeast(2.0f),
            cap = StrokeCap.Round,
        )
    }
}

private fun drawTokyoTowerDistrict(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    lighting: TokyoLightingState,
    phase: Float,
    landscapeMix: Float,
    blend: Float,
) {
    drawWalkingRoadBed(scope, cam, scratch, lighting, roadHalfW = 16f, laneAccentColor = lighting.towerGlowColor, phase = phase, blend = blend)

    val spacing = 24f
    val bScroll = (phase * 7.8f) % spacing
    for (slot in 5 downTo 0) {
        val zPos = 14f + slot * spacing - bScroll
        if (zPos < -8f || zPos > 145f) continue

        if (landscapeMix > 0.04f) {
            drawCelBuildingPair(
                scope = scope, cam = cam, scratch = scratch, lighting = lighting,
                xInner = 48f, width = 26f, depth = 20f, zCenter = zPos + 5f,
                leftHeight = 52f + (slot % 2) * 14f,
                rightHeight = 56f + ((slot + 1) % 3) * 12f,
                style = TokyoBuildingStyle.GLASS_TOWER,
                hasMegaScreen = false, hasBalconies = false, phase = phase,
                blend = blend * (0.65f + 0.30f * landscapeMix),
                seed = 900 + slot * 11,
            )
        }

        drawCelBuildingPair(
            scope = scope,
            cam = cam,
            scratch = scratch,
            lighting = lighting,
            xInner = 23f,
            width = 22f,
            depth = 18f,
            zCenter = zPos,
            leftHeight = 42f + (slot % 3) * 8f,
            rightHeight = 46f + ((slot + 1) % 3) * 9f,
            style = TokyoBuildingStyle.ZAKKYO_COMMERCIAL,
            hasMegaScreen = false,
            hasBalconies = true,
            phase = phase,
            blend = blend * 0.94f,
            seed = 210 + slot * 17,
        )

        // Roadside trees & streetlamps
        drawStreetTree3D(scope, cam, scratch, -18.5f, zPos - 7f, 8.8f, lighting, phase, blend, slot * 7)
        drawStreetTree3D(scope, cam, scratch, 18.5f, zPos + 7f, 8.8f, lighting, phase, blend, slot * 7 + 1)
        for (sideX in listOf(-15.2f, 15.2f)) {
            if (cam.projectPoint(sideX, 0f, zPos, 0, scratch) &&
                cam.projectPoint(sideX, 8.6f, zPos, 1, scratch)
            ) {
                scope.drawLine(lighting.inkOutlineColor.copy(alpha = 0.85f * blend), Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), 2.0f)
                scope.drawCircle(
                    brush = Brush.radialGradient(
                        listOf(lighting.towerGlowColor.copy(alpha = (0.36f + 0.54f * lighting.cityLights) * blend), Color.Transparent),
                        center = Offset(scratch.sx[1], scratch.sy[1]),
                        radius = 12f,
                    ),
                    radius = 12f,
                    center = Offset(scratch.sx[1], scratch.sy[1]),
                )
            }
        }
    }

    drawTokyoTowerStructure(scope, cam, scratch, lighting, towerX = 4f, towerZ = 104f, totalHeight = 126f, phase = phase, blend = blend)
}

private fun drawAsakusaDistrict(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    lighting: TokyoLightingState,
    phase: Float,
    landscapeMix: Float,
    blend: Float,
) {
    drawWalkingRoadBed(scope, cam, scratch, lighting, roadHalfW = 13f, laneAccentColor = Color(0xFFFF724A), phase = phase, blend = blend, pedestrianPromenade = true)

    // Stone Nakamise flagstones (石畳) pattern down center approach
    val stoneCol = lerp(Color(0xFF9EA6B8), lighting.celShadowColor, 0.28f + 0.48f * lighting.nightness)
        .copy(alpha = lerpF(0.45f, 0.72f, lighting.daylight) * blend)
    val flagStep = 14f
    val fScroll = (phase * 7.8f) % flagStep
    for (i in 0..9) {
        val fz = i * flagStep - fScroll
        if (fz < -8f || fz > 145f) continue
        drawGroundRect(scope, cam, scratch, -9.5f, 9.5f, fz, fz + 0.55f, stoneCol, y = 0.05f)
    }

    // Distant Tokyo Skytree truss spire rendered with clean cel contour
    if (cam.projectPoint(32f, 0f, 230f, 0, scratch) &&
        cam.projectPoint(32f, 155f, 230f, 1, scratch)
    ) {
        val skyColor = lerp(Color(0xFF7D97B8), Color(0xFF4CD8FF), lighting.cityLights * 0.6f).copy(alpha = 0.66f * blend)
        scope.drawLine(lighting.inkOutlineColor.copy(alpha = 0.58f * blend), Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), 4.6f, StrokeCap.Round)
        scope.drawLine(skyColor, Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), 2.8f, StrokeCap.Round)
    }

    val archSpacing = 24f
    val aScroll = (phase * 7.8f) % archSpacing
    for (slot in 5 downTo 0) {
        val zArch = 11f + slot * archSpacing - aScroll
        if (zArch < -8f || zArch > 155f) continue

        if (landscapeMix > 0.04f) {
            drawCelBuildingPair(
                scope = scope, cam = cam, scratch = scratch, lighting = lighting,
                xInner = 38f, width = 22f, depth = 18f, zCenter = zArch + 6f,
                leftHeight = 36f + (slot % 3) * 10f,
                rightHeight = 40f + ((slot + 1) % 3) * 10f,
                style = TokyoBuildingStyle.ZAKKYO_COMMERCIAL,
                hasMegaScreen = false, hasBalconies = false, phase = phase,
                blend = blend * (0.62f + 0.30f * landscapeMix),
                seed = 640 + slot * 19,
            )
        }

        // Authentic sloped Kawara-roof Machiya souvenir stalls flanking Nakamise-dori
        drawCelBuildingPair(
            scope = scope,
            cam = cam,
            scratch = scratch,
            lighting = lighting,
            xInner = 16f,
            width = 18f,
            depth = 18f,
            zCenter = zArch,
            leftHeight = 11.5f,
            rightHeight = 11.5f,
            style = TokyoBuildingStyle.MACHIYA_TRADITIONAL,
            hasMegaScreen = false,
            hasBalconies = false,
            phase = phase,
            blend = blend * 0.94f,
            seed = 310 + slot,
        )

        val sway = sin(phase * 1.9f + slot * 0.8f) * 0.40f
        drawKawaraArchGate3D(
            scope = scope,
            cam = cam,
            scratch = scratch,
            z = zArch,
            halfSpan = 11.4f,
            pillarHeight = 13.4f,
            lighting = lighting,
            swayOffset = sway,
            blend = blend,
            isMainKaminarimon = slot == 2,
        )
    }
}

private fun drawOdaibaDistrict(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    lighting: TokyoLightingState,
    phase: Float,
    landscapeMix: Float,
    blend: Float,
) {
    // 1. Anime Tokyo Bay water with crisp multi-band cel reflection highlights
    val waterTop = lerp(Color(0xFF33689E), Color(0xFF0C1732), 0.25f + 0.70f * lighting.nightness)
    val waterBot = lerp(Color(0xFF1F4975), Color(0xFF081024), 0.25f + 0.70f * lighting.nightness)
    if (cam.projectPoint(-240f, 0f, 34f, 0, scratch) &&
        cam.projectPoint(240f, 0f, 34f, 1, scratch) &&
        cam.projectPoint(280f, 0f, 225f, 2, scratch) &&
        cam.projectPoint(-280f, 0f, 225f, 3, scratch)
    ) {
        fillQuad(
            scope, scratch, 0, 1, 2, 3,
            Brush.verticalGradient(
                0f to waterTop.copy(alpha = 0.94f * blend),
                1f to waterBot.copy(alpha = 0.96f * blend),
                startY = scratch.sy[2].coerceAtMost(scope.size.height),
                endY = scratch.sy[0].coerceAtLeast(cam.horizonY + 12f),
            ),
        )
    } else {
        drawClippedQuad3D(scope, cam, scratch, -240f, 0f, 34f, 240f, 0f, 34f, 280f, 0f, 225f, -280f, 0f, 225f, lerp(waterTop, waterBot, 0.5f).copy(alpha = 0.95f * blend))
    }

    // Cel horizontal wave glints + inverted tower reflections on Tokyo Bay surface
    val shimmerColor = lerp(lighting.rimHighlightColor, lighting.billboardAccentA, lighting.nightness * 0.55f)
    for (r in 0..14) {
        val rz = 42f + r * 10.5f
        val rx = sin(phase * 1.2f + r * 1.4f) * (24f + landscapeMix * 16f)
        val halfSpan = 8f + (r % 3) * 5.5f
        if (cam.projectPoint(rx - halfSpan, 0.1f, rz, 0, scratch) &&
            cam.projectPoint(rx + halfSpan, 0.1f, rz, 1, scratch)
        ) {
            val alpha = (0.24f + 0.30f * abs(sin(phase * 2.0f + r))) * blend
            scope.drawLine(shimmerColor.copy(alpha = alpha), Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), 1.9f, StrokeCap.Round)
        }
    }

    // Timber coastal boardwalk promenade + cel-outlined glass balustrade + seaside trees
    val boardColor = lerp(Color(0xFF68584E), Color(0xFF22283C), lighting.nightness * 0.72f).copy(alpha = 0.95f * blend)
    drawGroundRect(scope, cam, scratch, -160f, 160f, -14f, 34f, boardColor, y = 0.04f)
    if (cam.projectPoint(-130f, 2.2f, 34f, 0, scratch) &&
        cam.projectPoint(130f, 2.2f, 34f, 1, scratch)
    ) {
        scope.drawLine(lighting.rimHighlightColor.copy(alpha = 0.76f * blend), Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), 2.2f)
    }
    for (post in -4..4) {
        val px = post * 14f
        if (cam.projectPoint(px, 0f, 34f, 0, scratch) &&
            cam.projectPoint(px, 2.3f, 34f, 1, scratch)
        ) {
            scope.drawLine(lighting.inkOutlineColor.copy(alpha = 0.88f * blend), Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), 2.0f)
        }
    }
    drawStreetTree3D(scope, cam, scratch, -24f, 24f, 9.5f, lighting, phase, blend, 91)
    drawStreetTree3D(scope, cam, scratch, 28f, 26f, 8.8f, lighting, phase, blend, 92)

    // Waterfront Odaiba towers & Fuji-TV inspired spherical truss landmark across bay
    drawCelBuilding3D(
        scope, cam, scratch, lighting, -78f, 168f, 30f, 24f, 64f,
        isRightWall = true, style = TokyoBuildingStyle.GLASS_TOWER,
        hasMegaScreen = true, hasBalconies = false, phase = phase, blend = blend * 0.88f, seed = 512,
    )

    // 2. Iconic Rainbow Bridge with double-tier H-frame cel pylons & suspension cables
    val bz = 136f
    val deckY = 18f
    val pylonH = 54f
    val towerCol = lerp(Color(0xFFE6EEF8), Color(0xFF94A5C4), lighting.nightness * 0.55f).copy(alpha = 0.96f * blend)
    val ink = lighting.inkOutlineColor.copy(alpha = 0.88f * blend)
    val bridgeLight = lerp(Color(0xFFFFDEA8), lighting.billboardAccentA, lighting.nightness * 0.5f).copy(alpha = (0.48f + 0.48f * lighting.cityLights) * blend)

    for (px in listOf(-38f, 38f)) {
        for (legOff in listOf(-2.4f, 2.4f)) {
            drawCelBox3D(scope, cam, scratch, lighting, px + legOff - 0.8f, px + legOff + 0.8f, 0f, pylonH, bz - 0.8f, bz + 0.8f, towerCol, 0.22f, 1.1f, blend)
        }
        if (cam.projectPoint(px - 2.4f, pylonH - 8f, bz, 4, scratch) &&
            cam.projectPoint(px + 2.4f, pylonH - 8f, bz, 5, scratch) &&
            cam.projectPoint(px - 2.4f, deckY + 12f, bz, 6, scratch) &&
            cam.projectPoint(px + 2.4f, deckY + 12f, bz, 7, scratch)
        ) {
            scope.drawLine(towerCol, Offset(scratch.sx[4], scratch.sy[4]), Offset(scratch.sx[5], scratch.sy[5]), 3.0f)
            scope.drawLine(towerCol, Offset(scratch.sx[6], scratch.sy[6]), Offset(scratch.sx[7], scratch.sy[7]), 2.6f)
        }
    }

    drawCelBox3D(scope, cam, scratch, lighting, -125f, 125f, deckY, deckY + 3.0f, bz - 1.2f, bz + 1.2f, towerCol, 0.22f, 1.1f, blend)
    if (cam.projectPoint(-125f, deckY + 3.0f, bz - 1.2f, 0, scratch) &&
        cam.projectPoint(125f, deckY + 3.0f, bz - 1.2f, 1, scratch)
    ) {
        scope.drawLine(bridgeLight, Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), 2.4f)
    }

    val cableSegments = 14
    for (seg in 0 until cableSegments) {
        val u0 = seg / cableSegments.toFloat()
        val u1 = (seg + 1) / cableSegments.toFloat()
        val x0 = lerpF(-38f, 38f, u0)
        val x1 = lerpF(-38f, 38f, u1)
        val sag0 = 4f * u0 * (1f - u0)
        val sag1 = 4f * u1 * (1f - u1)
        val y0 = lerpF(pylonH, deckY + 5f, sag0)
        val y1 = lerpF(pylonH, deckY + 5f, sag1)
        if (cam.projectPoint(x0, y0, bz, 4, scratch) &&
            cam.projectPoint(x1, y1, bz, 5, scratch) &&
            cam.projectPoint(x0, deckY + 3.0f, bz, 6, scratch)
        ) {
            scope.drawLine(bridgeLight, Offset(scratch.sx[4], scratch.sy[4]), Offset(scratch.sx[5], scratch.sy[5]), 2.2f)
            if (seg > 0) {
                scope.drawLine(towerCol.copy(alpha = 0.56f * blend), Offset(scratch.sx[4], scratch.sy[4]), Offset(scratch.sx[6], scratch.sy[6]), 1.0f)
            }
        }
    }

    for (anchor in listOf(Pair(-38f, -110f), Pair(38f, 110f))) {
        if (cam.projectPoint(anchor.first, pylonH, bz, 4, scratch) &&
            cam.projectPoint(anchor.second, deckY + 3f, bz, 5, scratch)
        ) {
            scope.drawLine(bridgeLight, Offset(scratch.sx[4], scratch.sy[4]), Offset(scratch.sx[5], scratch.sy[5]), 1.9f)
        }
    }

    // 3. Detailed spoked Daikanransha Ferris Wheel with rotating passenger cabins
    drawSpokedFerrisWheel3D(
        scope = scope,
        cam = cam,
        scratch = scratch,
        wx = 74f,
        wy = 42f,
        wz = 172f,
        radiusWorld = 24f,
        phase = phase,
        lighting = lighting,
        blend = blend,
    )
    @Suppress("UNUSED_VARIABLE")
    val _inkRef = ink
}

private fun drawCelBuildingPair(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    lighting: TokyoLightingState,
    xInner: Float,
    width: Float,
    depth: Float,
    zCenter: Float,
    leftHeight: Float,
    rightHeight: Float,
    style: TokyoBuildingStyle,
    hasMegaScreen: Boolean,
    hasBalconies: Boolean,
    phase: Float,
    blend: Float,
    seed: Int,
) {
    drawCelBuilding3D(scope, cam, scratch, lighting, -(xInner + width * 0.5f), zCenter, width, depth, leftHeight, isRightWall = true, style = style, hasMegaScreen = hasMegaScreen, hasBalconies = hasBalconies, phase = phase, blend = blend, seed = seed)
    drawCelBuilding3D(scope, cam, scratch, lighting, (xInner + width * 0.5f), zCenter, width, depth, rightHeight, isRightWall = false, style = style, hasMegaScreen = hasMegaScreen, hasBalconies = hasBalconies, phase = phase, blend = blend, seed = seed + 7)
}

private fun drawTokyoTowerStructure(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    lighting: TokyoLightingState,
    towerX: Float,
    towerZ: Float,
    totalHeight: Float,
    phase: Float,
    blend: Float,
) {
    val tiers = 9
    fun towerHalfWidth(y: Float): Float {
        val hNorm = y.coerceIn(0f, totalHeight)
        return 17.5f * exp(-hNorm * 0.0165f) + 1.5f
    }

    if (lighting.towerGlow > 0.08f && cam.projectPoint(towerX, totalHeight * 0.54f, towerZ, 0, scratch)) {
        val center = Offset(scratch.sx[0], scratch.sy[0])
        val haloRadius = scope.size.minDimension * 0.38f
        scope.drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(lighting.towerGlowColor.copy(alpha = 0.28f * lighting.towerGlow * blend), Color.Transparent),
                center = center,
                radius = haloRadius,
            ),
            radius = haloRadius,
            center = center,
        )
    }

    val inkCol = lighting.inkOutlineColor.copy(alpha = 0.80f * blend)

    for (tier in 0 until tiers) {
        val y0 = totalHeight * (tier.toFloat() / tiers)
        val y1 = totalHeight * ((tier + 1).toFloat() / tiers)
        val w0 = towerHalfWidth(y0)
        val w1 = towerHalfWidth(y1)
        val dayMetal = if (tier % 2 == 0) lighting.towerOrangeColor else lighting.towerWhiteColor
        val litMetal = lerp(dayMetal, lighting.towerGlowColor, lighting.towerGlow * 0.72f)
        val backStrut = litMetal.copy(alpha = 0.56f * blend)
        val frontStrut = litMetal.copy(alpha = 0.96f * blend)

        val projected = cam.projectPoint(towerX - w0, y0, towerZ - w0, 0, scratch) &&
            cam.projectPoint(towerX + w0, y0, towerZ - w0, 1, scratch) &&
            cam.projectPoint(towerX + w0, y0, towerZ + w0, 2, scratch) &&
            cam.projectPoint(towerX - w0, y0, towerZ + w0, 3, scratch) &&
            cam.projectPoint(towerX - w1, y1, towerZ - w1, 4, scratch) &&
            cam.projectPoint(towerX + w1, y1, towerZ - w1, 5, scratch) &&
            cam.projectPoint(towerX + w1, y1, towerZ + w1, 6, scratch) &&
            cam.projectPoint(towerX - w1, y1, towerZ + w1, 7, scratch)
        if (!projected) continue

        val legWidth = lerpF(3.6f, 1.6f, tier / tiers.toFloat())
        val braceWidth = (legWidth * 0.58f).coerceAtLeast(1.0f)

        drawTowerEdge(scope, scratch, 3, 7, backStrut, legWidth * 0.85f)
        drawTowerEdge(scope, scratch, 2, 6, backStrut, legWidth * 0.85f)
        drawTowerEdge(scope, scratch, 3, 6, backStrut, braceWidth * 0.8f)
        drawTowerEdge(scope, scratch, 2, 7, backStrut, braceWidth * 0.8f)

        drawTowerEdge(scope, scratch, 0, 4, inkCol, legWidth + 1.5f)
        drawTowerEdge(scope, scratch, 1, 5, inkCol, legWidth + 1.5f)
        drawTowerEdge(scope, scratch, 0, 4, frontStrut, legWidth)
        drawTowerEdge(scope, scratch, 1, 5, frontStrut, legWidth)
        drawTowerEdge(scope, scratch, 0, 5, frontStrut, braceWidth)
        drawTowerEdge(scope, scratch, 1, 4, frontStrut, braceWidth)
        drawTowerEdge(scope, scratch, 4, 5, frontStrut, braceWidth * 1.15f)
    }

    drawTowerDeck(scope, cam, scratch, towerX, towerZ, totalHeight * 0.54f, 7.8f, 6.8f, lighting, blend)
    drawTowerDeck(scope, cam, scratch, towerX, towerZ, totalHeight * 0.85f, 4.4f, 4.8f, lighting, blend)

    if (cam.projectPoint(towerX, totalHeight, towerZ, 0, scratch) &&
        cam.projectPoint(towerX, totalHeight + 22f, towerZ, 1, scratch) &&
        cam.projectPoint(towerX, totalHeight + 42f, towerZ, 2, scratch)
    ) {
        val spireLower = lerp(lighting.towerOrangeColor, lighting.towerGlowColor, lighting.towerGlow * 0.7f).copy(alpha = 0.96f * blend)
        val spireUpper = lerp(lighting.towerWhiteColor, Color.White, lighting.towerGlow * 0.4f).copy(alpha = 0.94f * blend)
        scope.drawLine(inkCol, Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[2], scratch.sy[2]), 3.6f, StrokeCap.Round)
        scope.drawLine(spireLower, Offset(scratch.sx[0], scratch.sy[0]), Offset(scratch.sx[1], scratch.sy[1]), 2.2f, StrokeCap.Round)
        scope.drawLine(spireUpper, Offset(scratch.sx[1], scratch.sy[1]), Offset(scratch.sx[2], scratch.sy[2]), 1.6f, StrokeCap.Round)

        val beaconPulse = (0.35f + 0.65f * sin(phase * PI.toFloat() * 2.4f)).coerceIn(0f, 1f)
        val tip = Offset(scratch.sx[2], scratch.sy[2])
        scope.drawCircle(Color(0xFFFF4646).copy(alpha = beaconPulse * 0.45f * blend), 7.5f, tip)
        scope.drawCircle(Color(0xFFFFD8C4).copy(alpha = (0.45f + 0.55f * beaconPulse) * blend), 2.3f, tip)
    }
}

private fun drawTowerDeck(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    tx: Float,
    tz: Float,
    deckY: Float,
    halfW: Float,
    deckH: Float,
    lighting: TokyoLightingState,
    blend: Float,
) {
    val hw = halfW
    val deckBody = lerp(lighting.towerWhiteColor, lighting.towerGlowColor, lighting.towerGlow * 0.45f)
    drawCelBox3D(scope, cam, scratch, lighting, tx - hw, tx + hw, deckY, deckY + deckH, tz - hw, tz + hw, deckBody, 0.16f, 1.2f, blend)

    val winY0 = deckY + deckH * 0.34f
    val winY1 = deckY + deckH * 0.72f
    val windowTint = lerp(Color(0xFF2B4D70), Color(0xFFFFDFA6), lighting.towerGlow * 0.85f).copy(alpha = 0.94f * blend)
    drawClippedQuad3D(scope, cam, scratch, tx - hw * 0.9f, winY0, tz - hw - 0.04f, tx + hw * 0.9f, winY0, tz - hw - 0.04f, tx + hw * 0.9f, winY1, tz - hw - 0.04f, tx - hw * 0.9f, winY1, tz - hw - 0.04f, windowTint)
}

private fun drawTowerEdge(
    scope: DrawScope,
    scratch: TokyoRenderScratch,
    i0: Int,
    i1: Int,
    color: Color,
    strokeWidth: Float,
) {
    scope.drawLine(
        color = color,
        start = Offset(scratch.sx[i0], scratch.sy[i0]),
        end = Offset(scratch.sx[i1], scratch.sy[i1]),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}

private fun drawGroundRect(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    x0: Float,
    x1: Float,
    z0: Float,
    z1: Float,
    color: Color,
    y: Float = 0.04f,
) {
    drawClippedQuad3D(
        scope, cam, scratch,
        x0, y, z0,
        x1, y, z0,
        x1, y, z1,
        x0, y, z1,
        color,
    )
}

private fun drawDiagonalStripe(
    scope: DrawScope,
    cam: Camera3D,
    scratch: TokyoRenderScratch,
    cx: Float,
    cz: Float,
    halfLen: Float,
    halfWid: Float,
    slope: Float,
    color: Color,
) {
    val dx = halfLen
    val dz = halfLen * slope
    val nx = -slope * halfWid * 0.7f
    val nz = halfWid * 0.7f
    drawClippedQuad3D(
        scope, cam, scratch,
        cx - dx - nx, 0.06f, cz - dz - nz,
        cx + dx - nx, 0.06f, cz + dz - nz,
        cx + dx + nx, 0.06f, cz + dz + nz,
        cx - dx + nx, 0.06f, cz - dz + nz,
        color,
    )
}

private fun drawAnimePetalsAndLightMotes(
    scope: DrawScope,
    lighting: TokyoLightingState,
    phase: Float,
    tilt: Offset,
    blend: Float,
) {
    val w = scope.size.width
    val h = scope.size.height
    val petalColor = lerp(Color(0xFFFFDAE8), Color(0xFFFFB68E), 1f - lighting.daylight)
    val ink = lighting.inkOutlineColor.copy(alpha = 0.28f * blend)

    for (p in 0..13) {
        val seed = p * 1.618f
        val xNorm = ((0.08f * p + phase * 0.025f + sin(phase * 0.9f + seed) * 0.04f - tilt.x * 0.08f) % 1f + 1f) % 1f
        val yNorm = ((0.11f * p + phase * (0.018f + (p % 3) * 0.006f) - tilt.y * 0.06f) % 1f + 1f) % 1f
        val pos = Offset(xNorm * w, yNorm * h)
        val r = 2.6f + (p % 3) * 1.4f
        val angle = phase * 1.6f + seed * 3f
        val dx = cos(angle) * r
        val dy = sin(angle) * r * 0.55f
        val alpha = (0.36f + 0.24f * sin(phase * 2.1f + seed)) * blend
        scope.drawLine(
            color = petalColor.copy(alpha = alpha),
            start = Offset(pos.x - dx, pos.y - dy),
            end = Offset(pos.x + dx, pos.y + dy),
            strokeWidth = r * 0.9f,
            cap = StrokeCap.Round,
        )
        scope.drawCircle(ink.copy(alpha = alpha * 0.35f), r * 0.55f, pos)
    }
}

private fun drawTokyoAtmosphereVeil(
    scope: DrawScope,
    lighting: TokyoLightingState,
    horizonY: Float,
    blend: Float,
) {
    val h = scope.size.height
    val w = scope.size.width
    val hFrac = (horizonY / h).coerceIn(0.25f, 0.72f)
    scope.drawRect(
        brush = Brush.verticalGradient(
            (hFrac - 0.11f).coerceAtLeast(0f) to Color.Transparent,
            hFrac to lighting.horizonColor.copy(alpha = lerpF(0.28f, 0.18f, lighting.nightness) * blend),
            (hFrac + 0.13f).coerceAtMost(1f) to Color.Transparent,
        ),
    )
    val center = Offset(w * 0.5f, h * 0.48f)
    scope.drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF060914).copy(alpha = lerpF(0.24f, 0.12f, lighting.nightness) * blend),
                Color.Transparent,
            ),
            center = center,
            radius = scope.size.maxDimension * 0.54f,
        ),
    )
}
