package btm.m.auraplayer.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

internal val homeContentSources = listOf("local", "netease", PROVIDER_QQ)

internal fun normalizeHomeContent(value: String?) = when (value) {
    "cloud", "netease" -> "netease"
    PROVIDER_QQ -> PROVIDER_QQ
    else -> "local"
}

internal val NAVIGATION_OFFSET_DP_RANGE = -64..96

data class Appearance(
    val navigation: String = "hyper_os_floating",
    val navigationOffsetDp: Int = 0,
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
    val filenameBlockRules: FilenameBlockRules = FilenameBlockRules(),
    val disableAdvancedMaterial: Boolean = false,
    /** Which cloud provider(s) the Cloud tab shows: "netease", "qq" or "all". */
    val cloudSource: String = "all",
    /** Cloud search provider, independent of the providers displayed in the Cloud tab. */
    val cloudSearchSource: String = "netease",
    /** Pure local player mode: hides and disables every cloud feature. */
    val cloudDisabled: Boolean = false,
    val heartbeatEnabled: Boolean = true,
)

class AuraSettings(context: Context) {
    private val appContext = context.applicationContext
    var previewColor by mutableStateOf<Int?>(null)
    private val prefs = context.getSharedPreferences("aura.settings", Context.MODE_PRIVATE)
    var value by mutableStateOf(read()); private set
    private fun read() = Appearance(
        navigation = prefs.getString("navigation", "hyper_os_floating")!!,
        navigationOffsetDp = prefs.getInt("navigationOffsetDp", 0).coerceIn(NAVIGATION_OFFSET_DP_RANGE),
        labels = prefs.getString("labels", "icon_and_text")!!,
        predictive = prefs.getBoolean("predictive", true),
        maxBackProgress = prefs.getFloat("backMaximum", .9f).coerceIn(.1f, 1f),
        theme = prefs.getString("theme", "system")!!,
        uiStyle = normalizeUiStyle(prefs.getString("uiStyle", "hyper")),
        monet = prefs.getBoolean("monet", false), color = prefs.getString("color", "system")!!,
        customColor = prefs.getInt("customColor", 0xFF7962D9.toInt()),
        palette = prefs.getInt("palette", 0), colorSpec = prefs.getInt("colorSpec", 0),
        language = prefs.getString("language", "zh")!!,
        homeContent = normalizeHomeContent(prefs.getString("homeContent", null)),
        whitelist = prefs.getBoolean("whitelist", false),
        durationFilter = prefs.getBoolean("durationFilter", false),
        minimumSeconds = prefs.getInt("minimumSeconds", 0).coerceIn(SCAN_MINIMUM_SECONDS_RANGE),
        blacklist = prefs.getStringSet("blacklist", emptySet())!!.toSet(),
        filenameBlockRules = FilenameBlockRules(
            enabled = prefs.getBoolean("filenameBlockEnabled", true),
            keywords = prefs.getString("filenameBlockKeywords", "").orEmpty(),
            patterns = prefs.getString("filenameBlockPatterns", "").orEmpty(),
        ),
        disableAdvancedMaterial = prefs.getBoolean("disableAdvancedMaterial", false),
        cloudSource = prefs.getString("cloudSource", "all")!!.takeIf { it in setOf("netease", "qq", "all") } ?: "all",
        cloudSearchSource = normalizeCloudSearchSource(prefs.getString("cloudSearchSource", null), prefs.getString("cloudSource", "all")),
        cloudDisabled = prefs.getBoolean("cloudDisabled", false),
        heartbeatEnabled = prefs.getBoolean("heartbeatEnabled", true),
    )
    fun update(transform: (Appearance) -> Appearance) {
        val v = transform(value).let {
            it.copy(uiStyle = normalizeUiStyle(it.uiStyle), cloudSearchSource = normalizeCloudSearchSource(it.cloudSearchSource),
                navigationOffsetDp = it.navigationOffsetDp.coerceIn(NAVIGATION_OFFSET_DP_RANGE),
                minimumSeconds = it.minimumSeconds.coerceIn(SCAN_MINIMUM_SECONDS_RANGE))
        }
        prefs.edit().putString("navigation", v.navigation).putString("labels", v.labels)
            .putInt("navigationOffsetDp", v.navigationOffsetDp)
            .putBoolean("predictive", v.predictive).putFloat("backMaximum", v.maxBackProgress)
            .putString("theme", v.theme).putString("uiStyle", v.uiStyle).putBoolean("monet", v.monet).putString("color", v.color)
            .putInt("customColor", v.customColor).putInt("palette", v.palette).putInt("colorSpec", v.colorSpec)
            .putString("language", v.language).putBoolean("whitelist", v.whitelist)
            .putString("homeContent", v.homeContent)
            .putBoolean("durationFilter", v.durationFilter).putInt("minimumSeconds", v.minimumSeconds)
            .putStringSet("blacklist", v.blacklist)
            .putBoolean("filenameBlockEnabled", v.filenameBlockRules.enabled)
            .putString("filenameBlockKeywords", v.filenameBlockRules.keywords)
            .putString("filenameBlockPatterns", v.filenameBlockRules.patterns)
            .putBoolean("disableAdvancedMaterial", v.disableAdvancedMaterial)
            .putString("cloudSource", v.cloudSource).putString("cloudSearchSource", v.cloudSearchSource)
            .putBoolean("cloudDisabled", v.cloudDisabled).putBoolean("heartbeatEnabled", v.heartbeatEnabled).apply()
        value = v
        btm.m.auraplayer.widget.WidgetUpdateCoordinator.requestUpdate(appContext)
    }

    companion object {
        fun normalizeCloudSearchSource(raw: String?, previousDisplaySource: String? = null): String = when (raw) {
            "qq" -> "qq"
            "netease" -> "netease"
            null -> if (previousDisplaySource == "qq") "qq" else "netease"
            else -> "netease"
        }

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
