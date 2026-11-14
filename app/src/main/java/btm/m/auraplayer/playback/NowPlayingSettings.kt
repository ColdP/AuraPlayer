package btm.m.auraplayer.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class PlayerTheme { AURORA, AURORA_BLUR, BLUR, COLOR, FULLSCREEN, MATERIAL, ONE }
enum class GlowProgressStyle { OFF, HYPER, ONE }
enum class MaterialPlayerBackground { MATERIAL, BLUR, AURORA, AURORA_BLUR }
enum class PlayerWeather { NONE, SNOW, RAIN }
enum class CoverAnimation { DEFAULT, CARD, BLUR, COVER, PAGE, STACK, VERTICAL_FLIP, HORIZONTAL_FLIP }

data class NowPlayingConfig(
    val theme: PlayerTheme = PlayerTheme.AURORA,
    val blur: Int = 0,
    val weather: PlayerWeather = PlayerWeather.NONE,
    val coverAnimation: CoverAnimation = CoverAnimation.DEFAULT,
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
            )
        }
        mutable.value = next
        prefs.edit().putString("config", NowPlayingCodec.encode(next)).apply()
    }
}

internal object NowPlayingCodec {
    fun encode(c: NowPlayingConfig): String = JSONObject().apply {
        put("theme", c.theme.name); put("blur", c.blur); put("weather", c.weather.name)
        put("coverAnimation", c.coverAnimation.name); put("showVolume", c.showVolume)
        put("openFromNotification", c.openFromNotification); put("keepLyricsAwake", c.keepLyricsAwake)
        put("showAudioInfo", c.showAudioInfo); put("swipeTracks", c.swipeTracks)
        put("immersiveLyrics", c.immersiveLyrics); put("immersiveSeconds", c.immersiveSeconds)
        put("fullscreenGlass", c.fullscreenGlass); put("materialBackground", c.materialBackground.name)
        put("materialColorMix", c.materialColorMix); put("glowProgress", c.glowProgress.name)
    }.toString()
    fun decode(raw: String): NowPlayingConfig = runCatching {
        val j = JSONObject(raw); val defaults = NowPlayingConfig()
        defaults.copy(
            theme = PlayerTheme.entries.firstOrNull { it.name == j.optString("theme") } ?: defaults.theme,
            blur = j.optInt("blur", 0).coerceIn(-64, 96),
            weather = PlayerWeather.entries.firstOrNull { it.name == j.optString("weather") } ?: defaults.weather,
            coverAnimation = CoverAnimation.entries.firstOrNull { it.name == j.optString("coverAnimation") } ?: defaults.coverAnimation,
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
        )
    }.getOrDefault(NowPlayingConfig())
}
