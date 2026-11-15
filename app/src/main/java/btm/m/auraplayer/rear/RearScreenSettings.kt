// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.rear

import android.content.Context
import btm.m.auraplayer.lyrics.LyricSize
import btm.m.auraplayer.playback.CoverAnimation
import btm.m.auraplayer.playback.LyricTheme
import btm.m.auraplayer.playback.MaterialPlayerBackground
import btm.m.auraplayer.playback.NowPlayingConfig
import btm.m.auraplayer.playback.PlayerTheme
import btm.m.auraplayer.playback.PlayerWeather
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class RearLyricSpacing(val multiplier: Float) {
    COMPACT(0.60f),
    NORMAL(0.95f),
    RELAXED(1.30f),
    WIDE(1.70f),
}

enum class RearColorMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class RearScreenConfig(
    val followMainTheme: Boolean = false,
    val theme: PlayerTheme = PlayerTheme.AURORA,
    val colorMode: RearColorMode = RearColorMode.SYSTEM,
    val amoledBlack: Boolean = false,
    val restartSubscreenBeforeCast: Boolean = true,
    val showVolume: Boolean = true,
    val swipeTracks: Boolean = true,
    val keepLyricsAwake: Boolean = false,
    val lyricSize: LyricSize = LyricSize.EXTRA_SMALL,
    val lyricSpacing: RearLyricSpacing = RearLyricSpacing.NORMAL,
    val showTranslation: Boolean = true,
    val showPronunciation: Boolean = false,
    val weather: PlayerWeather = PlayerWeather.NONE,
    val lyricTheme: LyricTheme = LyricTheme.BLUR,
)

data class RearScreenResolvedAppearance(
    val theme: PlayerTheme,
    val weather: PlayerWeather,
    val lyricTheme: LyricTheme,
    val blur: Int,
    val materialBackground: MaterialPlayerBackground,
    val materialColorMix: Int,
    val coverAnimation: CoverAnimation,
    val fullscreenGlass: Boolean,
) {
    fun toNowPlayingConfig(base: NowPlayingConfig, rear: RearScreenConfig): NowPlayingConfig =
        base.copy(
            theme = theme,
            weather = weather,
            lyricTheme = lyricTheme,
            blur = blur,
            materialBackground = materialBackground,
            materialColorMix = materialColorMix,
            coverAnimation = coverAnimation,
            fullscreenGlass = fullscreenGlass,
            showVolume = rear.showVolume,
            swipeTracks = rear.swipeTracks,
            keepLyricsAwake = rear.keepLyricsAwake,
        )
}

fun RearScreenConfig.resolveAppearance(main: NowPlayingConfig): RearScreenResolvedAppearance {
    val activeTheme = if (followMainTheme) main.theme else theme
    val activeWeather = if (followMainTheme) main.weather else weather
    val activeLyricTheme = if (followMainTheme) main.lyricTheme else lyricTheme
    return RearScreenResolvedAppearance(
        theme = activeTheme,
        weather = activeWeather,
        lyricTheme = activeLyricTheme,
        blur = main.blur,
        materialBackground = main.materialBackground,
        materialColorMix = main.materialColorMix,
        coverAnimation = main.coverAnimation,
        fullscreenGlass = main.fullscreenGlass,
    )
}

object RearScreenCodec {
    fun encode(config: RearScreenConfig): String = JSONObject()
        .put("followMainTheme", config.followMainTheme)
        .put("theme", config.theme.name)
        .put("colorMode", config.colorMode.name)
        .put("amoledBlack", config.amoledBlack)
        .put("restartSubscreenBeforeCast", config.restartSubscreenBeforeCast)
        .put("showVolume", config.showVolume)
        .put("swipeTracks", config.swipeTracks)
        .put("keepLyricsAwake", config.keepLyricsAwake)
        .put("lyricSize", config.lyricSize.name)
        .put("lyricSpacing", config.lyricSpacing.name)
        .put("showTranslation", config.showTranslation)
        .put("showPronunciation", config.showPronunciation)
        .put("weather", config.weather.name)
        .put("lyricTheme", config.lyricTheme.name)
        .toString()

    fun decode(raw: String?): RearScreenConfig {
        if (raw.isNullOrBlank()) return RearScreenConfig()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return RearScreenConfig()
        val defaults = RearScreenConfig()
        return RearScreenConfig(
            followMainTheme = json.optBoolean("followMainTheme", defaults.followMainTheme),
            theme = json.optString("theme").let { name ->
                PlayerTheme.entries.firstOrNull { it.name == name } ?: defaults.theme
            },
            colorMode = json.optString("colorMode").let { name ->
                RearColorMode.entries.firstOrNull { it.name == name } ?: defaults.colorMode
            },
            amoledBlack = json.optBoolean("amoledBlack", defaults.amoledBlack),
            restartSubscreenBeforeCast = json.optBoolean("restartSubscreenBeforeCast", defaults.restartSubscreenBeforeCast),
            showVolume = json.optBoolean("showVolume", defaults.showVolume),
            swipeTracks = json.optBoolean("swipeTracks", defaults.swipeTracks),
            keepLyricsAwake = json.optBoolean("keepLyricsAwake", defaults.keepLyricsAwake),
            lyricSize = json.optString("lyricSize").let { name ->
                LyricSize.entries.firstOrNull { it.name == name } ?: defaults.lyricSize
            },
            lyricSpacing = json.optString("lyricSpacing").let { name ->
                RearLyricSpacing.entries.firstOrNull { it.name == name } ?: defaults.lyricSpacing
            },
            showTranslation = json.optBoolean("showTranslation", defaults.showTranslation),
            showPronunciation = json.optBoolean("showPronunciation", defaults.showPronunciation),
            weather = json.optString("weather").let { name ->
                PlayerWeather.entries.firstOrNull { it.name == name } ?: defaults.weather
            },
            lyricTheme = json.optString("lyricTheme").let { name ->
                LyricTheme.entries.firstOrNull { it.name == name } ?: defaults.lyricTheme
            },
        )
    }
}

class RearScreenSettings(context: Context) {
    private val prefs = context.getSharedPreferences("rear_screen", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(RearScreenCodec.decode(prefs.getString("config", null)))
    val state: StateFlow<RearScreenConfig> = mutable.asStateFlow()

    fun update(transform: (RearScreenConfig) -> RearScreenConfig) {
        val next = transform(mutable.value)
        mutable.value = next
        prefs.edit().putString("config", RearScreenCodec.encode(next)).apply()
    }
}
