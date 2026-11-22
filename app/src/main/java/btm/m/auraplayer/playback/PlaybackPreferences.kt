package btm.m.auraplayer.playback

import android.content.Context

enum class StreamQuality(val level: String) { STANDARD("standard"), HIGHER("higher"), HIGH("exhigh"), LOSSLESS("lossless"), HIRES("hires"), SPATIAL("jyeffect"), SURROUND("sky"), DOLBY("dolby"), VIVID("vivid"), MASTER("jymaster") }
enum class LyricTranslationSource { LLM, NETWORK }

/** Kept separate from appearance/library preferences; shared with the playback service. */
class PlaybackPreferences(context: Context) {
    companion object {
        const val CELLULAR_QUALITY_FOLLOW = "follow"
        const val DEFAULT_AUDIO_CACHE_MAX_MB = 2048
        val AUDIO_CACHE_LIMIT_OPTIONS_MB = listOf(512, 1024, 2048, 4096, 8192)
    }

    private val prefs = context.getSharedPreferences("aura.playback", Context.MODE_PRIVATE)
    private val bootCount = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
    var quality: StreamQuality
        get() = StreamQuality.entries.firstOrNull { it.level == prefs.getString("quality", "standard") } ?: StreamQuality.STANDARD
        set(value) { prefs.edit().putString("quality", value.level).apply() }
    /** Maximum stream quality allowed on metered (cellular) networks; null means follow [quality]. */
    var cellularQuality: StreamQuality?
        get() {
            val raw = prefs.getString("cellular_quality", StreamQuality.STANDARD.level)
            if (raw == CELLULAR_QUALITY_FOLLOW) return null
            return StreamQuality.entries.firstOrNull { it.level == raw } ?: StreamQuality.STANDARD
        }
        set(value) {
            prefs.edit().putString("cellular_quality", value?.level ?: CELLULAR_QUALITY_FOLLOW).apply()
        }
    var wifiOnlyMotionCover: Boolean
        get() = prefs.getBoolean("wifi_only_motion_cover", true)
        set(value) { prefs.edit().putBoolean("wifi_only_motion_cover", value).apply() }
    var audioCacheMaxMb: Int
        get() = prefs.getInt("audio_cache_max_mb", DEFAULT_AUDIO_CACHE_MAX_MB).coerceIn(512, 8192)
        set(value) { prefs.edit().putInt("audio_cache_max_mb", value.coerceIn(512, 8192)).apply() }

    fun effectiveQuality(requested: StreamQuality, metered: Boolean): StreamQuality {
        if (!metered) return requested
        val cap = cellularQuality ?: return requested
        return if (requested.ordinal <= cap.ordinal) requested else cap
    }
    var translation: Boolean
        get() = prefs.getBoolean("translation", true)
        set(value) { prefs.edit().putBoolean("translation", value).apply() }
    var translationSource: LyricTranslationSource
        get() = LyricTranslationSource.entries.firstOrNull { it.name == prefs.getString("translation_source", "LLM") }
            ?: LyricTranslationSource.LLM
        set(value) { prefs.edit().putString("translation_source", value.name).apply() }
    var pronunciation: Boolean
        get() = prefs.getBoolean("pronunciation", true)
        set(value) { prefs.edit().putBoolean("pronunciation", value).apply() }
    var speed: Float
        get() = prefs.getFloat("speed", 1f).coerceIn(.5f, 3f)
        set(value) { prefs.edit().putFloat("speed", value.coerceIn(.5f, 3f)).apply() }
    var sleepDeadline: Long
        get() = if (prefs.getInt("sleepBootCount", -2) == bootCount) prefs.getLong("sleepDeadline", 0L) else 0L
        set(value) { prefs.edit().putLong("sleepDeadline", value).putInt("sleepBootCount", bootCount).apply() }
    var sleepMinutes: Int
        get() = prefs.getInt("sleepMinutes", 0)
        set(value) { prefs.edit().putInt("sleepMinutes", value).apply() }
}


data class StreamInfo(val level: String, val codec: String, val bitrate: Int, val sampleRate: Int, val bits: Int = 0, val isTrial: Boolean = false)
data class CloudQuality(val quality: StreamQuality, val audio: AudioDetails)
data class DownloadSource(val url: String, val codec: String, val quality: StreamQuality)
object PlaybackStreams {
    val records = java.util.concurrent.ConcurrentHashMap<Long, StreamInfo>()
}
