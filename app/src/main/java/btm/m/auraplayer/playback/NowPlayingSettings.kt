package btm.m.auraplayer.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

enum class PlayerTheme { AURORA, AURORA_BLUR, BLUR, COLOR, FULLSCREEN, MATERIAL, ONE }
enum class GlowProgressStyle { OFF, HYPER, ONE }
enum class MaterialPlayerBackground { MATERIAL, BLUR, AURORA, AURORA_BLUR }
enum class PlayerWeather { NONE, SNOW, RAIN }
enum class CoverAnimation { DEFAULT, CARD, BLUR, COVER, PAGE, STACK, VERTICAL_FLIP, HORIZONTAL_FLIP }
enum class CoverTheme { SQUARE, COVERFLOW, TILED }
enum class LyricTheme {
    BLUR,
    DEEP_STAR_SEA,
    AFTERGLOW_SHORE,
    STREAMING_SHADOWS,
    LIGHT_YEAR_DRIFT,
    NEON_CITY,
    WIND_LETTER,
    STAR_MOON,
    LIME_SODA,
    MONET_GARDEN,
    FADED_FILM,
    TOKYO_CITYSCAPE,
}

enum class TokyoSceneTime {
    FOLLOW_SYSTEM,
    DAWN,
    MORNING,
    NOON,
    DUSK,
    NIGHT,
}

enum class TokyoSceneLandmark {
    CITY_WALK,
    SHIBUYA_CROSSING,
    TOKYO_TOWER,
    SHINJUKU_NEON,
    ASAKUSA_LANTERN,
    ODAIBA_BAY,
}

internal const val PARALLAX_SENSITIVITY_MIN = 0.2f
internal const val PARALLAX_SENSITIVITY_MAX = 2.5f
internal const val PARALLAX_SENSITIVITY_DEFAULT = 1.0f
internal const val LYRIC_SCALE_MIN_PERCENT = -100f
internal const val LYRIC_SCALE_MAX_PERCENT = 200f
internal const val LYRIC_SCALE_DEFAULT_PERCENT = 0f

internal fun clampParallaxSensitivity(value: Float): Float {
    if (!value.isFinite()) return PARALLAX_SENSITIVITY_DEFAULT
    return ((value.coerceIn(PARALLAX_SENSITIVITY_MIN, PARALLAX_SENSITIVITY_MAX) * 10f).roundToInt() / 10f)
}

internal fun clampLyricScalePercent(value: Float): Float {
    if (!value.isFinite()) return LYRIC_SCALE_DEFAULT_PERCENT
    val rounded = (value.coerceIn(LYRIC_SCALE_MIN_PERCENT, LYRIC_SCALE_MAX_PERCENT) * 10f).roundToInt() / 10f
    return if (rounded == -0.0f) 0f else rounded
}

internal fun lyricScaleMultiplier(percent: Float): Float =
    (1f + clampLyricScalePercent(percent) / 100f).coerceAtLeast(0f)

internal fun formatLyricScalePercent(percent: Float): String =
    String.format(Locale.US, "%.1f%%", clampLyricScalePercent(percent))

internal fun shouldPromptLyricThemePowerNotice(
    target: LyricTheme,
    dismissed: Boolean,
): Boolean = target != LyricTheme.BLUR && !dismissed

internal fun shouldPromptSpatialLyricsPowerNotice(
    enabling: Boolean,
    dismissed: Boolean,
): Boolean = enabling && !dismissed

internal fun shouldPromptSpatialCoverPowerNotice(
    enabling: Boolean,
    dismissed: Boolean,
): Boolean = enabling && !dismissed

data class NowPlayingConfig(
    val theme: PlayerTheme = PlayerTheme.AURORA,
    val blur: Int = 0,
    val weather: PlayerWeather = PlayerWeather.NONE,
    val coverAnimation: CoverAnimation = CoverAnimation.DEFAULT,
    val immersiveCover: Boolean = false,
    val appleMusicAnimatedCover: Boolean = false,
    val albumAnimatedCover: Boolean = false,
    val appleMusicArtistArtwork: Boolean = false,
    val artistAnimatedCover: Boolean = false,
    val artistWordmarkLogo: Boolean = true,
    val coverTheme: CoverTheme = CoverTheme.SQUARE,
    val tiledSwitchDwellSeconds: Float = 2.0f,
    val coverflowParallax: Boolean = false,
    val coverflowParallaxSensitivity: Float = PARALLAX_SENSITIVITY_DEFAULT,
    val tiledParallax: Boolean = false,
    val tiledParallaxSensitivity: Float = PARALLAX_SENSITIVITY_DEFAULT,
    val landscapeAutoCoverflow: Boolean = false,
    val showVolume: Boolean = true,
    val openFromNotification: Boolean = true,
    val keepLyricsAwake: Boolean = false,
    val showAudioInfo: Boolean = true,
    val swipeTracks: Boolean = true,
    val immersiveLyrics: Boolean = false,
    val immersiveSeconds: Int = 5,
    val fullscreenGlass: Boolean = true,
    val materialBackground: MaterialPlayerBackground = MaterialPlayerBackground.MATERIAL,
    val materialColorMix: Int = 50,
    val glowProgress: GlowProgressStyle = GlowProgressStyle.OFF,
    val lyricTheme: LyricTheme = LyricTheme.BLUR,
    val tokyoSceneTime: TokyoSceneTime = TokyoSceneTime.FOLLOW_SYSTEM,
    val tokyoSceneLandmark: TokyoSceneLandmark = TokyoSceneLandmark.CITY_WALK,
    val lyricThemePowerNoticeDismissed: Boolean = false,
    val spatialLyrics: Boolean = false,
    val spatialLyricsSensitivity: Float = PARALLAX_SENSITIVITY_DEFAULT,
    val spatialLyricsPowerNoticeDismissed: Boolean = false,
    val spatialCover: Boolean = false,
    val spatialCoverSensitivity: Float = PARALLAX_SENSITIVITY_DEFAULT,
    val spatialCoverPowerNoticeDismissed: Boolean = false,
    val spatialCoverDepthModel: String = "depth_anything_v2_small",
    val spatialCoverMattingModel: String = "matting_u2netp",
    val spatialCoverViewActive: Boolean = true,
    val mainLyricScalePercent: Float = LYRIC_SCALE_DEFAULT_PERCENT,
    val bgLyricScalePercent: Float = LYRIC_SCALE_DEFAULT_PERCENT,
) {
    val usesArtworkBackground: Boolean
        get() = theme == PlayerTheme.AURORA_BLUR ||
            theme == PlayerTheme.BLUR ||
            theme == PlayerTheme.FULLSCREEN ||
            (theme == PlayerTheme.MATERIAL && (materialBackground == MaterialPlayerBackground.BLUR || materialBackground == MaterialPlayerBackground.AURORA_BLUR))

    val hasBlurControl: Boolean
        get() = theme == PlayerTheme.BLUR ||
            theme == PlayerTheme.AURORA_BLUR ||
            (theme == PlayerTheme.MATERIAL && (materialBackground == MaterialPlayerBackground.BLUR || materialBackground == MaterialPlayerBackground.AURORA_BLUR))
    // 0 keeps the existing default. Negative values approach clear, positive values add blur.
    val blurRadius get() = if (blur < 0) 48f * (blur.coerceAtLeast(-64) + 64) / 64 else 48f + blur.coerceAtMost(96)
}

/** Dedicated appearance/interaction storage, shared by settings, activity and player. */
class NowPlayingSettings(context: Context) {
    private val prefs = context.getSharedPreferences("aura.now-playing.v1", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(NowPlayingCodec.decode(prefs.getString("config", "{}")!!))
    val state = mutable.asStateFlow()
    fun update(change: (NowPlayingConfig) -> NowPlayingConfig) {
        val next = change(mutable.value).let {
            it.copy(
                blur = it.blur.coerceIn(-64, 96),
                immersiveSeconds = it.immersiveSeconds.coerceIn(0, 30),
                materialColorMix = it.materialColorMix.coerceIn(0, 100),
                tiledSwitchDwellSeconds = ((it.tiledSwitchDwellSeconds.coerceIn(0.1f, 5.0f) * 10f).roundToInt() / 10f),
                coverflowParallaxSensitivity = clampParallaxSensitivity(it.coverflowParallaxSensitivity),
                tiledParallaxSensitivity = clampParallaxSensitivity(it.tiledParallaxSensitivity),
                spatialLyricsSensitivity = clampParallaxSensitivity(it.spatialLyricsSensitivity),
                spatialCoverSensitivity = clampParallaxSensitivity(it.spatialCoverSensitivity),
                mainLyricScalePercent = clampLyricScalePercent(it.mainLyricScalePercent),
                bgLyricScalePercent = clampLyricScalePercent(it.bgLyricScalePercent),
            )
        }
        mutable.value = next
        prefs.edit().putString("config", NowPlayingCodec.encode(next)).apply()
    }
}

internal object NowPlayingCodec {
    fun encode(c: NowPlayingConfig): String = JSONObject().apply {
        put("theme", c.theme.name); put("blur", c.blur); put("weather", c.weather.name)
        put("coverAnimation", c.coverAnimation.name); put("immersiveCover", c.immersiveCover); put("showVolume", c.showVolume)
        put("openFromNotification", c.openFromNotification); put("keepLyricsAwake", c.keepLyricsAwake)
        put("showAudioInfo", c.showAudioInfo); put("swipeTracks", c.swipeTracks)
        put("immersiveLyrics", c.immersiveLyrics); put("immersiveSeconds", c.immersiveSeconds)
        put("fullscreenGlass", c.fullscreenGlass); put("materialBackground", c.materialBackground.name)
        put("materialColorMix", c.materialColorMix); put("glowProgress", c.glowProgress.name)
        put("lyricTheme", c.lyricTheme.name)
        put("tokyoSceneTime", c.tokyoSceneTime.name)
        put("tokyoSceneLandmark", c.tokyoSceneLandmark.name)
        put("lyricThemePowerNoticeDismissed", c.lyricThemePowerNoticeDismissed)
        put("spatialLyrics", c.spatialLyrics)
        put("spatialLyricsSensitivity", c.spatialLyricsSensitivity.toDouble())
        put("spatialLyricsPowerNoticeDismissed", c.spatialLyricsPowerNoticeDismissed)
        put("spatialCover", c.spatialCover)
        put("spatialCoverSensitivity", c.spatialCoverSensitivity.toDouble())
        put("spatialCoverPowerNoticeDismissed", c.spatialCoverPowerNoticeDismissed)
        put("spatialCoverDepthModel", c.spatialCoverDepthModel)
        put("spatialCoverMattingModel", c.spatialCoverMattingModel)
        put("spatialCoverViewActive", c.spatialCoverViewActive)
        put("appleMusicAnimatedCover", c.appleMusicAnimatedCover)
        put("albumAnimatedCover", c.albumAnimatedCover)
        put("appleMusicArtistArtwork", c.appleMusicArtistArtwork)
        put("artistAnimatedCover", c.artistAnimatedCover)
        put("artistWordmarkLogo", c.artistWordmarkLogo)
        put("coverTheme", c.coverTheme.name)
        put("tiledSwitchDwellSeconds", c.tiledSwitchDwellSeconds.toDouble())
        put("coverflowParallax", c.coverflowParallax)
        put("coverflowParallaxSensitivity", c.coverflowParallaxSensitivity.toDouble())
        put("tiledParallax", c.tiledParallax)
        put("tiledParallaxSensitivity", c.tiledParallaxSensitivity.toDouble())
        put("landscapeAutoCoverflow", c.landscapeAutoCoverflow)
        put("mainLyricScalePercent", c.mainLyricScalePercent.toDouble())
        put("bgLyricScalePercent", c.bgLyricScalePercent.toDouble())
    }.toString()
    fun decode(raw: String): NowPlayingConfig = runCatching {
        val j = JSONObject(raw); val defaults = NowPlayingConfig()
        defaults.copy(
            theme = PlayerTheme.entries.firstOrNull { it.name == j.optString("theme") } ?: defaults.theme,
            blur = j.optInt("blur", 0).coerceIn(-64, 96),
            weather = PlayerWeather.entries.firstOrNull { it.name == j.optString("weather") } ?: defaults.weather,
            coverAnimation = CoverAnimation.entries.firstOrNull { it.name == j.optString("coverAnimation") } ?: defaults.coverAnimation,
            immersiveCover = j.optBoolean("immersiveCover", defaults.immersiveCover),
            showVolume = j.optBoolean("showVolume", defaults.showVolume),
            openFromNotification = j.optBoolean("openFromNotification", defaults.openFromNotification),
            keepLyricsAwake = j.optBoolean("keepLyricsAwake", defaults.keepLyricsAwake),
            showAudioInfo = j.optBoolean("showAudioInfo", defaults.showAudioInfo),
            swipeTracks = j.optBoolean("swipeTracks", defaults.swipeTracks),
            immersiveLyrics = j.optBoolean("immersiveLyrics", defaults.immersiveLyrics),
            immersiveSeconds = j.optInt("immersiveSeconds", defaults.immersiveSeconds).coerceIn(0, 30),
            fullscreenGlass = j.optBoolean("fullscreenGlass", defaults.fullscreenGlass),
            materialBackground = MaterialPlayerBackground.entries.firstOrNull { it.name == j.optString("materialBackground") } ?: defaults.materialBackground,
            materialColorMix = j.optInt("materialColorMix", defaults.materialColorMix).coerceIn(0, 100),
            glowProgress = GlowProgressStyle.entries.firstOrNull { it.name == j.optString("glowProgress") } ?: defaults.glowProgress,
            lyricTheme = LyricTheme.entries.firstOrNull { it.name == j.optString("lyricTheme") } ?: defaults.lyricTheme,
            tokyoSceneTime = TokyoSceneTime.entries.firstOrNull { it.name == j.optString("tokyoSceneTime") } ?: defaults.tokyoSceneTime,
            tokyoSceneLandmark = TokyoSceneLandmark.entries.firstOrNull { it.name == j.optString("tokyoSceneLandmark") } ?: defaults.tokyoSceneLandmark,
            lyricThemePowerNoticeDismissed = j.optBoolean("lyricThemePowerNoticeDismissed", defaults.lyricThemePowerNoticeDismissed),
            spatialLyrics = j.optBoolean("spatialLyrics", defaults.spatialLyrics),
            spatialLyricsSensitivity = j.optDouble(
                "spatialLyricsSensitivity",
                defaults.spatialLyricsSensitivity.toDouble(),
            ).toFloat().let(::clampParallaxSensitivity),
            spatialLyricsPowerNoticeDismissed = j.optBoolean("spatialLyricsPowerNoticeDismissed", defaults.spatialLyricsPowerNoticeDismissed),
            spatialCover = j.optBoolean("spatialCover", defaults.spatialCover),
            spatialCoverSensitivity = j.optDouble(
                "spatialCoverSensitivity",
                defaults.spatialCoverSensitivity.toDouble(),
            ).toFloat().let(::clampParallaxSensitivity),
            spatialCoverPowerNoticeDismissed = j.optBoolean("spatialCoverPowerNoticeDismissed", defaults.spatialCoverPowerNoticeDismissed),
            spatialCoverDepthModel = j.optString("spatialCoverDepthModel", defaults.spatialCoverDepthModel).ifBlank { defaults.spatialCoverDepthModel },
            spatialCoverMattingModel = j.optString("spatialCoverMattingModel", defaults.spatialCoverMattingModel).ifBlank { defaults.spatialCoverMattingModel },
            spatialCoverViewActive = j.optBoolean("spatialCoverViewActive", defaults.spatialCoverViewActive),
            appleMusicAnimatedCover = j.optBoolean("appleMusicAnimatedCover", defaults.appleMusicAnimatedCover),
            albumAnimatedCover = j.optBoolean("albumAnimatedCover", defaults.albumAnimatedCover),
            appleMusicArtistArtwork = j.optBoolean("appleMusicArtistArtwork", defaults.appleMusicArtistArtwork),
            artistAnimatedCover = j.optBoolean("artistAnimatedCover", defaults.artistAnimatedCover),
            artistWordmarkLogo = j.optBoolean("artistWordmarkLogo", defaults.artistWordmarkLogo),
            coverTheme = CoverTheme.entries.firstOrNull { it.name == j.optString("coverTheme") } ?: defaults.coverTheme,
            tiledSwitchDwellSeconds = ((j.optDouble("tiledSwitchDwellSeconds", defaults.tiledSwitchDwellSeconds.toDouble()).toFloat().coerceIn(0.1f, 5.0f) * 10f).roundToInt() / 10f),
            coverflowParallax = j.optBoolean("coverflowParallax", defaults.coverflowParallax),
            coverflowParallaxSensitivity = j.optDouble(
                "coverflowParallaxSensitivity",
                defaults.coverflowParallaxSensitivity.toDouble(),
            ).toFloat().let(::clampParallaxSensitivity),
            tiledParallax = j.optBoolean("tiledParallax", defaults.tiledParallax),
            tiledParallaxSensitivity = j.optDouble(
                "tiledParallaxSensitivity",
                defaults.tiledParallaxSensitivity.toDouble(),
            ).toFloat().let(::clampParallaxSensitivity),
            landscapeAutoCoverflow = j.optBoolean("landscapeAutoCoverflow", defaults.landscapeAutoCoverflow),
            mainLyricScalePercent = j.optDouble(
                "mainLyricScalePercent",
                defaults.mainLyricScalePercent.toDouble(),
            ).toFloat().let(::clampLyricScalePercent),
            bgLyricScalePercent = j.optDouble(
                "bgLyricScalePercent",
                defaults.bgLyricScalePercent.toDouble(),
            ).toFloat().let(::clampLyricScalePercent),
        )
    }.getOrDefault(NowPlayingConfig())
}
