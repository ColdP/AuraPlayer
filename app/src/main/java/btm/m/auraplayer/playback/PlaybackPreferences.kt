package btm.m.auraplayer.playback

import android.content.Context

enum class StreamQuality(val level: String) { STANDARD("standard"), HIGHER("higher"), HIGH("exhigh"), LOSSLESS("lossless"), HIRES("hires"), SPATIAL("jyeffect"), SURROUND("sky"), DOLBY("dolby"), VIVID("vivid"), MASTER("jymaster") }

/** Kept separate from appearance/library preferences; shared with the playback service. */
class PlaybackPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("aura.playback", Context.MODE_PRIVATE)
    var quality: StreamQuality
        get() = StreamQuality.entries.firstOrNull { it.level == prefs.getString("quality", "standard") } ?: StreamQuality.STANDARD
        set(value) { prefs.edit().putString("quality", value.level).apply() }
    var translation: Boolean
        get() = prefs.getBoolean("translation", true)
        set(value) { prefs.edit().putBoolean("translation", value).apply() }
    var pronunciation: Boolean
        get() = prefs.getBoolean("pronunciation", true)
        set(value) { prefs.edit().putBoolean("pronunciation", value).apply() }
}


data class StreamInfo(val level: String, val codec: String, val bitrate: Int, val sampleRate: Int)
object PlaybackStreams {
    val records = java.util.concurrent.ConcurrentHashMap<Long, StreamInfo>()
}
