package btm.m.auraplayer.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class Appearance(
    val navigation: String = "hyper_os_floating",
    val labels: String = "icon_and_text",
    val predictive: Boolean = true,
    val maxBackProgress: Float = .9f,
    val theme: String = "system",
    val uiStyle: String = "hyper",
    val monet: Boolean = false,
    val color: String = "system",
    val customColor: Int = 0xFF7962D9.toInt(),
    val palette: Int = 0,
    val colorSpec: Int = 0,
    val language: String = "zh",
    val homeContent: String = "local",
    val whitelist: Boolean = false,
    val durationFilter: Boolean = false,
    val minimumSeconds: Int = 0,
    val blacklist: Set<String> = emptySet(),
)

class AuraSettings(context: Context) {
    var previewColor by mutableStateOf<Int?>(null)
    private val prefs = context.getSharedPreferences("aura.settings", Context.MODE_PRIVATE)
    var value by mutableStateOf(read()); private set
    private fun read() = Appearance(
        navigation = prefs.getString("navigation", "hyper_os_floating")!!,
        labels = prefs.getString("labels", "icon_and_text")!!,
        predictive = prefs.getBoolean("predictive", true),
        maxBackProgress = prefs.getFloat("backMaximum", .9f).coerceIn(.1f, 1f),
        theme = prefs.getString("theme", "system")!!,
        uiStyle = normalizeUiStyle(prefs.getString("uiStyle", "hyper")),
        monet = prefs.getBoolean("monet", false), color = prefs.getString("color", "system")!!,
        customColor = prefs.getInt("customColor", 0xFF7962D9.toInt()),
        palette = prefs.getInt("palette", 0), colorSpec = prefs.getInt("colorSpec", 0),
        language = prefs.getString("language", "zh")!!,
        homeContent = prefs.getString("homeContent", "local")!!,
        whitelist = prefs.getBoolean("whitelist", false),
        durationFilter = prefs.getBoolean("durationFilter", false),
        minimumSeconds = prefs.getInt("minimumSeconds", 0).coerceIn(0, 60),
        blacklist = prefs.getStringSet("blacklist", emptySet())!!.toSet(),
    )
    fun update(transform: (Appearance) -> Appearance) {
        val v = transform(value).let { it.copy(uiStyle = normalizeUiStyle(it.uiStyle)) }
        prefs.edit().putString("navigation", v.navigation).putString("labels", v.labels)
            .putBoolean("predictive", v.predictive).putFloat("backMaximum", v.maxBackProgress)
            .putString("theme", v.theme).putString("uiStyle", v.uiStyle).putBoolean("monet", v.monet).putString("color", v.color)
            .putInt("customColor", v.customColor).putInt("palette", v.palette).putInt("colorSpec", v.colorSpec)
            .putString("language", v.language).putBoolean("whitelist", v.whitelist)
            .putString("homeContent", v.homeContent)
            .putBoolean("durationFilter", v.durationFilter).putInt("minimumSeconds", v.minimumSeconds)
            .putStringSet("blacklist", v.blacklist).apply()
        value = v
    }

    companion object {
        /** 将持久化或外部传入的界面风格值规范化为已支持的选项。 */
        @JvmStatic
        fun normalizeUiStyle(raw: String?): String = when (raw) {
            "material" -> "material"
            "one" -> "one"
            else -> "hyper"
        }
    }
}

class LibraryHistory(context: Context) {
    private val prefs = context.getSharedPreferences("aura.library", Context.MODE_PRIVATE)
    var revision by mutableStateOf(0); private set
    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet())!!.toSet()
    fun toggle(song: Song) {
        val old = favorites()
        prefs.edit().putStringSet("favorites", if (song.key in old) old - song.key else old + song.key).apply()
        revision++
    }
    fun record(song: Song) {
        prefs.edit().putLong("recent.${song.key}", System.currentTimeMillis())
            .putInt("count.${song.key}", count(song) + 1).apply()
        revision++
    }
    fun recent(song: Song) = prefs.getLong("recent.${song.key}", 0)
    fun count(song: Song) = prefs.getInt("count.${song.key}", 0)
    fun playlists(): List<LocalPlaylist> = runCatching {
        val array = JSONArray(prefs.getString("playlists", "[]"))
        List(array.length()) { i -> array.getJSONObject(i).let { o ->
            val ids = o.getJSONArray("songs")
            LocalPlaylist(o.getString("id"), o.getString("name"), List(ids.length()) { ids.getString(it) })
        } }
    }.getOrDefault(emptyList())
    fun savePlaylists(playlists: List<LocalPlaylist>) {
        val data = JSONArray()
        playlists.forEach { data.put(JSONObject().put("id", it.id).put("name", it.name).put("songs", JSONArray(it.songKeys))) }
        prefs.edit().putString("playlists", data.toString()).apply()
        revision++
    }
}
