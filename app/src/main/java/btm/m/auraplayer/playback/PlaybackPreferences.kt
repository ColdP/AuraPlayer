package btm.m.auraplayer.playback

import android.content.Context

enum class StreamQuality(val level: String) { STANDARD("standard"), HIGHER("higher"), HIGH("exhigh"), LOSSLESS("lossless"), HIRES("hires"), SPATIAL("jyeffect"), SURROUND("sky"), DOLBY("dolby"), VIVID("vivid"), MASTER("jymaster") }

/** Kept separate from appearance/library preferences; shared with the playback service. */
class PlaybackPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("aura.playback", Context.MODE_PRIVATE)
    private val bootCount = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
    var quality: StreamQuality
        get() = StreamQuality.entries.firstOrNull { it.level == prefs.getString("quality", "standard") } ?: StreamQuality.STANDARD
        set(value) { prefs.edit().putString("quality", value.level).apply() }
    var translation: Boolean
        get() = prefs.getBoolean("translation", true)
        set(value) { prefs.edit().putBoolean("translation", value).apply() }
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


data class StreamInfo(val level: String, val codec: String, val bitrate: Int, val sampleRate: Int, val bits: Int = 0)
data class CloudQuality(val quality: StreamQuality, val audio: AudioDetails)
data class DownloadSource(val url: String, val codec: String, val quality: StreamQuality)
object PlaybackStreams {
    val records = java.util.concurrent.ConcurrentHashMap<Long, StreamInfo>()
}
