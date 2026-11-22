// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlin.math.roundToInt

enum class CutoutRingContent {
    SONG_PROGRESS,
    VOLUME,
    AUDIO_LEVEL,
    SLEEP_TIMER,
}

enum class CutoutRingColorMode {
    FOLLOW_THEME,
    FOLLOW_COVER,
    CUSTOM,
}

enum class CutoutRingAlignment {
    CENTER,
    LEFT,
    RIGHT,
}

enum class CutoutRingDirection {
    CLOCKWISE,
    COUNTER_CLOCKWISE,
}

internal const val CUTOUT_RING_WIDTH_MIN_DP = 0f
internal const val CUTOUT_RING_WIDTH_MAX_DP = 160f
internal const val CUTOUT_RING_HEIGHT_MIN_DP = 10f
internal const val CUTOUT_RING_HEIGHT_MAX_DP = 80f
internal const val CUTOUT_RING_STROKE_MIN_DP = 1.0f
internal const val CUTOUT_RING_STROKE_MAX_DP = 8.0f
internal const val CUTOUT_RING_VERTICAL_OFFSET_MIN_DP = -24f
internal const val CUTOUT_RING_VERTICAL_OFFSET_MAX_DP = 80f
internal const val CUTOUT_RING_HORIZONTAL_OFFSET_MIN_DP = -120f
internal const val CUTOUT_RING_HORIZONTAL_OFFSET_MAX_DP = 120f
internal const val CUTOUT_RING_OPACITY_MIN = 20
internal const val CUTOUT_RING_OPACITY_MAX = 100
internal const val CUTOUT_RING_TRACK_OPACITY_MIN = 5
internal const val CUTOUT_RING_TRACK_OPACITY_MAX = 60
internal const val CUTOUT_RING_DEFAULT_CUSTOM_COLOR = 0xFF6E92D8.toInt()

data class CutoutRingConfig(
    val enabled: Boolean = false,
    val content: CutoutRingContent = CutoutRingContent.SONG_PROGRESS,
    val temporarilyShowVolume: Boolean = true,
    val showOutsideApp: Boolean = false,
    val onlyWhenPlaying: Boolean = false,
    val hideInLandscape: Boolean = true,
    val colorMode: CutoutRingColorMode = CutoutRingColorMode.FOLLOW_THEME,
    val customColor: Int = CUTOUT_RING_DEFAULT_CUSTOM_COLOR,
    val strokeWidthDp: Float = 2.6f,
    val opacityPercent: Int = 100,
    val showTrack: Boolean = true,
    val trackOpacityPercent: Int = 22,
    val direction: CutoutRingDirection = CutoutRingDirection.CLOCKWISE,
    val roundCap: Boolean = true,
    val glowEnabled: Boolean = false,
    val alignment: CutoutRingAlignment = CutoutRingAlignment.CENTER,
    val widthDp: Float = 0f,
    val heightDp: Float = 26f,
    val cornerSmoothness: Int = 0,
    val verticalOffsetDp: Float = 0f,
    val horizontalOffsetDp: Float = 0f,
) {
    fun normalized(): CutoutRingConfig = copy(
        customColor = customColor or 0xFF000000.toInt(),
        strokeWidthDp = snapHalfTenth(strokeWidthDp, 5f).coerceIn(CUTOUT_RING_STROKE_MIN_DP, CUTOUT_RING_STROKE_MAX_DP),
        opacityPercent = opacityPercent.coerceIn(CUTOUT_RING_OPACITY_MIN, CUTOUT_RING_OPACITY_MAX),
        trackOpacityPercent = trackOpacityPercent.coerceIn(CUTOUT_RING_TRACK_OPACITY_MIN, CUTOUT_RING_TRACK_OPACITY_MAX),
        widthDp = snapHalf(widthDp).coerceIn(CUTOUT_RING_WIDTH_MIN_DP, CUTOUT_RING_WIDTH_MAX_DP),
        heightDp = snapHalf(heightDp).coerceIn(CUTOUT_RING_HEIGHT_MIN_DP, CUTOUT_RING_HEIGHT_MAX_DP),
        cornerSmoothness = cornerSmoothness.coerceIn(0, 100),
        verticalOffsetDp = snapHalf(verticalOffsetDp).coerceIn(CUTOUT_RING_VERTICAL_OFFSET_MIN_DP, CUTOUT_RING_VERTICAL_OFFSET_MAX_DP),
        horizontalOffsetDp = snapHalf(horizontalOffsetDp).coerceIn(CUTOUT_RING_HORIZONTAL_OFFSET_MIN_DP, CUTOUT_RING_HORIZONTAL_OFFSET_MAX_DP),
    )

    fun resetGeometry(): CutoutRingConfig {
        val defaults = CutoutRingConfig()
        return copy(
            alignment = defaults.alignment,
            widthDp = defaults.widthDp,
            heightDp = defaults.heightDp,
            cornerSmoothness = defaults.cornerSmoothness,
            verticalOffsetDp = defaults.verticalOffsetDp,
            horizontalOffsetDp = defaults.horizontalOffsetDp,
        )
    }

    private fun snapHalf(value: Float): Float =
        if (value.isFinite()) (value * 2f).roundToInt() / 2f else 0f

    private fun snapHalfTenth(value: Float, factor: Float): Float =
        if (value.isFinite()) (value * factor).roundToInt() / factor else 2.6f
}

object CutoutRingCodec {
    fun encode(config: CutoutRingConfig): String {
        val safe = config.normalized()
        return JSONObject()
            .put("enabled", safe.enabled)
            .put("content", safe.content.name)
            .put("temporarilyShowVolume", safe.temporarilyShowVolume)
            .put("showOutsideApp", safe.showOutsideApp)
            .put("onlyWhenPlaying", safe.onlyWhenPlaying)
            .put("hideInLandscape", safe.hideInLandscape)
            .put("colorMode", safe.colorMode.name)
            .put("customColor", safe.customColor)
            .put("strokeWidthDp", safe.strokeWidthDp.toDouble())
            .put("opacityPercent", safe.opacityPercent)
            .put("showTrack", safe.showTrack)
            .put("trackOpacityPercent", safe.trackOpacityPercent)
            .put("direction", safe.direction.name)
            .put("roundCap", safe.roundCap)
            .put("glowEnabled", safe.glowEnabled)
            .put("alignment", safe.alignment.name)
            .put("widthDp", safe.widthDp.toDouble())
            .put("heightDp", safe.heightDp.toDouble())
            .put("cornerSmoothness", safe.cornerSmoothness)
            .put("verticalOffsetDp", safe.verticalOffsetDp.toDouble())
            .put("horizontalOffsetDp", safe.horizontalOffsetDp.toDouble())
            .toString()
    }

    fun decode(raw: String?): CutoutRingConfig {
        if (raw.isNullOrBlank()) return CutoutRingConfig()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return CutoutRingConfig()
        val defaults = CutoutRingConfig()
        return CutoutRingConfig(
            enabled = json.optBoolean("enabled", defaults.enabled),
            content = json.optString("content").let { name ->
                CutoutRingContent.entries.firstOrNull { it.name == name } ?: defaults.content
            },
            temporarilyShowVolume = json.optBoolean("temporarilyShowVolume", defaults.temporarilyShowVolume),
            showOutsideApp = json.optBoolean("showOutsideApp", defaults.showOutsideApp),
            onlyWhenPlaying = json.optBoolean("onlyWhenPlaying", defaults.onlyWhenPlaying),
            hideInLandscape = json.optBoolean("hideInLandscape", defaults.hideInLandscape),
            colorMode = json.optString("colorMode").let { name ->
                CutoutRingColorMode.entries.firstOrNull { it.name == name } ?: defaults.colorMode
            },
            customColor = json.optInt("customColor", defaults.customColor),
            strokeWidthDp = json.optDouble("strokeWidthDp", defaults.strokeWidthDp.toDouble()).toFloat(),
            opacityPercent = json.optInt("opacityPercent", defaults.opacityPercent),
            showTrack = json.optBoolean("showTrack", defaults.showTrack),
            trackOpacityPercent = json.optInt("trackOpacityPercent", defaults.trackOpacityPercent),
            direction = json.optString("direction").let { name ->
                CutoutRingDirection.entries.firstOrNull { it.name == name } ?: defaults.direction
            },
            roundCap = json.optBoolean("roundCap", defaults.roundCap),
            glowEnabled = json.optBoolean("glowEnabled", defaults.glowEnabled),
            alignment = json.optString("alignment").let { name ->
                CutoutRingAlignment.entries.firstOrNull { it.name == name } ?: defaults.alignment
            },
            widthDp = json.optDouble("widthDp", defaults.widthDp.toDouble()).toFloat(),
            heightDp = json.optDouble("heightDp", defaults.heightDp.toDouble()).toFloat(),
            cornerSmoothness = json.optInt("cornerSmoothness", defaults.cornerSmoothness),
            verticalOffsetDp = json.optDouble("verticalOffsetDp", defaults.verticalOffsetDp.toDouble()).toFloat(),
            horizontalOffsetDp = json.optDouble("horizontalOffsetDp", defaults.horizontalOffsetDp.toDouble()).toFloat(),
        ).normalized()
    }
}

class CutoutRingSettings(context: Context) {
    private val prefs = context.getSharedPreferences("aura.cutout_ring", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(CutoutRingCodec.decode(prefs.getString("config", null)))
    val state: StateFlow<CutoutRingConfig> = mutable.asStateFlow()

    private val mutablePreviewColor = MutableStateFlow<Int?>(null)
    val previewColor: StateFlow<Int?> = mutablePreviewColor.asStateFlow()

    fun setPreviewColor(color: Int?) {
        mutablePreviewColor.value = color?.let { it or 0xFF000000.toInt() }
    }

    fun update(transform: (CutoutRingConfig) -> CutoutRingConfig) {
        val next = transform(mutable.value).normalized()
        mutable.value = next
        prefs.edit().putString("config", CutoutRingCodec.encode(next)).apply()
    }
}
