package btm.m.auraplayer.lyrics

import android.content.Context

enum class LyricSize(val scale: Float) {
    EXTRA_SMALL(.65f), SMALL(.8f), REGULAR(.9f), LARGE(1f), EXTRA_LARGE(1.2f);
}

/** Renderer options are stored independently from appearance and library settings. */
class LyricPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("aura.lyrics", Context.MODE_PRIVATE)
    var size: LyricSize
        get() = LyricSize.entries.firstOrNull { it.name == prefs.getString("size", null) } ?: LyricSize.LARGE
        set(value) { prefs.edit().putString("size", value.name).apply() }
}
