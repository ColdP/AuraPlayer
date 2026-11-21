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
internal val LOCAL_BANNER_HEIGHT_DP_RANGE = 80..360
internal val HOME_BACKGROUND_BLUR_DP_RANGE = 8..120

enum class LocalBannerStyle {
    NONE,
    ROUNDED_CARD,
    IMMERSIVE,
    IMMERSIVE_GLASS,
    GLASS_POSTER,
    AURORA_AMBIENT,
}

enum class HomeBackgroundStyle {
    DEFAULT,
    COLOR,
    COLOR_BLUR,
}

enum class ColorExtractionMode(val prefValue: String) {
    OFF("off"),
    OKLAB("oklab"),
    MONET("monet");

    companion object {
        fun fromPref(raw: String?, legacyMonet: Boolean = false): ColorExtractionMode = when (raw) {
            "off" -> OFF
            "oklab" -> OKLAB
            "monet" -> MONET
            else -> if (legacyMonet) MONET else OFF
        }
    }
}

enum class BannerMotionPlayMode {
    OFF,
    ONCE,
    REVERSE,
    LOOP,
}

enum class HomeBannerTarget(val imagePrefix: String) {
    LOCAL("banner"),
    NETEASE("netease-banner"),
    QQ("qq-banner"),
}

data class HomeBannerConfig(
    val style: LocalBannerStyle = LocalBannerStyle.NONE,
    val imagePath: String = "",
    val motionPlayMode: BannerMotionPlayMode = BannerMotionPlayMode.LOOP,
    val styleConfigs: Map<LocalBannerStyle, LocalBannerStyleConfig> = emptyMap(),
) {
    fun styleConfig(targetStyle: LocalBannerStyle = style): LocalBannerStyleConfig =
        (styleConfigs[targetStyle] ?: defaultLocalBannerStyleConfig(targetStyle)).normalized()

    fun withStyleConfig(
        targetStyle: LocalBannerStyle = style,
        transform: (LocalBannerStyleConfig) -> LocalBannerStyleConfig,
    ): HomeBannerConfig {
        val updated = transform(styleConfig(targetStyle)).normalized()
        return copy(styleConfigs = styleConfigs + (targetStyle to updated))
    }
}

data class LocalBannerStyleConfig(
    val heightDp: Int = 180,
    val glassRefraction: Int = 100,
    val glassBlur: Int = 100,
    val glassOpacity: Int = 100,
    val glassHighlight: Int = 100,
    val glassCorner: Int = 0,
    val glassDispersion: Boolean = true,
    val backgroundStyle: HomeBackgroundStyle = HomeBackgroundStyle.DEFAULT,
    val backgroundBlurDp: Int = 36,
) {
    fun normalized(): LocalBannerStyleConfig = copy(
        heightDp = heightDp.coerceIn(LOCAL_BANNER_HEIGHT_DP_RANGE),
        glassRefraction = glassRefraction.coerceIn(0, 200),
        glassBlur = glassBlur.coerceIn(0, 300),
        glassOpacity = glassOpacity.coerceIn(0, 250),
        glassHighlight = glassHighlight.coerceIn(0, 200),
        glassCorner = glassCorner.coerceIn(-64, 96),
        backgroundBlurDp = backgroundBlurDp.coerceIn(HOME_BACKGROUND_BLUR_DP_RANGE),
    )
}

internal fun defaultLocalBannerHeightDp(style: LocalBannerStyle): Int = when (style) {
    LocalBannerStyle.NONE -> LOCAL_BANNER_HEIGHT_DP_RANGE.first
    LocalBannerStyle.ROUNDED_CARD -> 180
    LocalBannerStyle.IMMERSIVE -> 210
    LocalBannerStyle.IMMERSIVE_GLASS -> 220
    LocalBannerStyle.GLASS_POSTER -> 206
    LocalBannerStyle.AURORA_AMBIENT -> 96
}

internal fun defaultLocalBannerStyleConfig(style: LocalBannerStyle): LocalBannerStyleConfig =
    LocalBannerStyleConfig(heightDp = defaultLocalBannerHeightDp(style)).normalized()

data class Appearance(
    val navigation: String = "hyper_os_floating",
    val navigationOffsetDp: Int = 0,
    val labels: String = "icon_and_text",
    val predictive: Boolean = true,
    val maxBackProgress: Float = .9f,
    val theme: String = "system",
    val uiStyle: String = "hyper",
    val colorExtractionMode: ColorExtractionMode = ColorExtractionMode.OFF,
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
    val neteaseScrobbleEnabled: Boolean = true,
    val localAvatarPath: String = "",
    val localUserName: String = "",
    val localHomeSignature: String = "",
    val localBannerStyle: LocalBannerStyle = LocalBannerStyle.NONE,
    val localBannerPath: String = "",
    val localBannerMotionPlayMode: BannerMotionPlayMode = BannerMotionPlayMode.LOOP,
    val localBannerGlassRefraction: Int = 100,
    val localBannerGlassBlur: Int = 100,
    val localBannerGlassOpacity: Int = 100,
    val localBannerGlassHighlight: Int = 100,
    val localBannerGlassCorner: Int = 0,
    val localBannerGlassDispersion: Boolean = true,
    val localBannerStyleConfigs: Map<LocalBannerStyle, LocalBannerStyleConfig> = emptyMap(),
    val neteaseBannerConfig: HomeBannerConfig = HomeBannerConfig(),
    val qqBannerConfig: HomeBannerConfig = HomeBannerConfig(),
) {
    val monet: Boolean get() = colorExtractionMode == ColorExtractionMode.MONET
    val isOklabColor: Boolean get() = colorExtractionMode == ColorExtractionMode.OKLAB
    val dynamicColorEnabled: Boolean get() = colorExtractionMode != ColorExtractionMode.OFF

    fun bannerStyleConfig(style: LocalBannerStyle): LocalBannerStyleConfig =
        (localBannerStyleConfigs[style] ?: defaultLocalBannerStyleConfig(style)).normalized()

    fun withBannerStyleConfig(
        style: LocalBannerStyle,
        transform: (LocalBannerStyleConfig) -> LocalBannerStyleConfig,
    ): Appearance {
        val updated = transform(bannerStyleConfig(style)).normalized()
        return copy(localBannerStyleConfigs = localBannerStyleConfigs + (style to updated))
    }

    fun bannerConfig(target: HomeBannerTarget): HomeBannerConfig = when (target) {
        HomeBannerTarget.LOCAL -> HomeBannerConfig(
            style = localBannerStyle,
            imagePath = localBannerPath,
            motionPlayMode = localBannerMotionPlayMode,
            styleConfigs = localBannerStyleConfigs,
        )
        HomeBannerTarget.NETEASE -> neteaseBannerConfig
        HomeBannerTarget.QQ -> qqBannerConfig
    }

    fun withBannerConfig(
        target: HomeBannerTarget,
        transform: (HomeBannerConfig) -> HomeBannerConfig,
    ): Appearance = when (target) {
        HomeBannerTarget.LOCAL -> {
            val next = transform(bannerConfig(HomeBannerTarget.LOCAL))
            copy(
                localBannerStyle = next.style,
                localBannerPath = next.imagePath,
                localBannerMotionPlayMode = next.motionPlayMode,
                localBannerStyleConfigs = next.styleConfigs.mapValues { (_, cfg) -> cfg.normalized() },
            )
        }
        HomeBannerTarget.NETEASE -> {
            val next = transform(neteaseBannerConfig)
            copy(neteaseBannerConfig = next.copy(styleConfigs = next.styleConfigs.mapValues { (_, cfg) -> cfg.normalized() }))
        }
        HomeBannerTarget.QQ -> {
            val next = transform(qqBannerConfig)
            copy(qqBannerConfig = next.copy(styleConfigs = next.styleConfigs.mapValues { (_, cfg) -> cfg.normalized() }))
        }
    }
}

class AuraSettings(context: Context) {
    private val appContext = context.applicationContext
    var previewColor by mutableStateOf<Int?>(null)
    private val prefs = context.getSharedPreferences("aura.settings", Context.MODE_PRIVATE)
    var value by mutableStateOf(read()); private set
    private fun read(): Appearance {
        val legacyRefraction = prefs.getInt("localBannerGlassRefraction", 100).coerceIn(0, 200)
        val legacyBlur = prefs.getInt("localBannerGlassBlur", 100).coerceIn(0, 300)
        val legacyOpacity = prefs.getInt("localBannerGlassOpacity", 100).coerceIn(0, 250)
        val legacyHighlight = prefs.getInt("localBannerGlassHighlight", 100).coerceIn(0, 200)
        val legacyCorner = prefs.getInt("localBannerGlassCorner", 0).coerceIn(-64, 96)
        val legacyDispersion = prefs.getBoolean("localBannerGlassDispersion", true)
        val hasLegacyGlassOverrides = prefs.contains("localBannerGlassRefraction") ||
            prefs.contains("localBannerGlassBlur") ||
            prefs.contains("localBannerGlassOpacity") ||
            prefs.contains("localBannerGlassHighlight") ||
            prefs.contains("localBannerGlassCorner") ||
            prefs.contains("localBannerGlassDispersion")
        val legacyGlassTemplate = LocalBannerStyleConfig(
            glassRefraction = legacyRefraction,
            glassBlur = legacyBlur,
            glassOpacity = legacyOpacity,
            glassHighlight = legacyHighlight,
            glassCorner = legacyCorner,
            glassDispersion = legacyDispersion,
        )
        val styleConfigs = decodeBannerStyleConfigs(
            raw = prefs.getString("localBannerStyleConfigs", null),
            legacyGlassTemplate = if (hasLegacyGlassOverrides) legacyGlassTemplate else null,
        )
        return Appearance(
            navigation = prefs.getString("navigation", "hyper_os_floating")!!,
            navigationOffsetDp = prefs.getInt("navigationOffsetDp", 0).coerceIn(NAVIGATION_OFFSET_DP_RANGE),
            labels = prefs.getString("labels", "icon_and_text")!!,
            predictive = prefs.getBoolean("predictive", true),
            maxBackProgress = prefs.getFloat("backMaximum", .9f).coerceIn(.1f, 1f),
            theme = prefs.getString("theme", "system")!!,
            uiStyle = normalizeUiStyle(prefs.getString("uiStyle", "hyper")),
            colorExtractionMode = ColorExtractionMode.fromPref(
                raw = prefs.getString("colorExtractionMode", null),
                legacyMonet = prefs.getBoolean("monet", false),
            ),
            color = prefs.getString("color", "system")!!,
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
            neteaseScrobbleEnabled = prefs.getBoolean("neteaseScrobbleEnabled", true),
            localAvatarPath = prefs.getString("localAvatarPath", "").orEmpty(),
            localUserName = prefs.getString("localUserName", "").orEmpty(),
            localHomeSignature = prefs.getString("localHomeSignature", "").orEmpty(),
            localBannerStyle = LocalBannerStyle.entries.firstOrNull { it.name == prefs.getString("localBannerStyle", null) }
                ?: LocalBannerStyle.NONE,
            localBannerPath = prefs.getString("localBannerPath", "").orEmpty(),
            localBannerMotionPlayMode = BannerMotionPlayMode.entries.firstOrNull {
                it.name == prefs.getString("localBannerMotionPlayMode", null)
            } ?: BannerMotionPlayMode.LOOP,
            localBannerGlassRefraction = legacyRefraction,
            localBannerGlassBlur = legacyBlur,
            localBannerGlassOpacity = legacyOpacity,
            localBannerGlassHighlight = legacyHighlight,
            localBannerGlassCorner = legacyCorner,
            localBannerGlassDispersion = legacyDispersion,
            localBannerStyleConfigs = styleConfigs,
            neteaseBannerConfig = decodeHomeBannerConfig(prefs.getString("neteaseBannerConfig", null)),
            qqBannerConfig = decodeHomeBannerConfig(prefs.getString("qqBannerConfig", null)),
        )
    }
    fun update(transform: (Appearance) -> Appearance) {
        val v = transform(value).let {
            it.copy(uiStyle = normalizeUiStyle(it.uiStyle), cloudSearchSource = normalizeCloudSearchSource(it.cloudSearchSource),
                navigationOffsetDp = it.navigationOffsetDp.coerceIn(NAVIGATION_OFFSET_DP_RANGE),
                minimumSeconds = it.minimumSeconds.coerceIn(SCAN_MINIMUM_SECONDS_RANGE),
                localBannerGlassRefraction = it.localBannerGlassRefraction.coerceIn(0, 200),
                localBannerGlassBlur = it.localBannerGlassBlur.coerceIn(0, 300),
                localBannerGlassOpacity = it.localBannerGlassOpacity.coerceIn(0, 250),
                localBannerGlassHighlight = it.localBannerGlassHighlight.coerceIn(0, 200),
                localBannerGlassCorner = it.localBannerGlassCorner.coerceIn(-64, 96),
                localBannerStyleConfigs = it.localBannerStyleConfigs.mapValues { (_, cfg) -> cfg.normalized() },
                neteaseBannerConfig = it.neteaseBannerConfig.copy(
                    styleConfigs = it.neteaseBannerConfig.styleConfigs.mapValues { (_, cfg) -> cfg.normalized() },
                ),
                qqBannerConfig = it.qqBannerConfig.copy(
                    styleConfigs = it.qqBannerConfig.styleConfigs.mapValues { (_, cfg) -> cfg.normalized() },
                ))
        }
        prefs.edit().putString("navigation", v.navigation).putString("labels", v.labels)
            .putInt("navigationOffsetDp", v.navigationOffsetDp)
            .putBoolean("predictive", v.predictive).putFloat("backMaximum", v.maxBackProgress)
            .putString("theme", v.theme).putString("uiStyle", v.uiStyle)
            .putString("colorExtractionMode", v.colorExtractionMode.prefValue)
            .putBoolean("monet", v.colorExtractionMode == ColorExtractionMode.MONET)
            .putString("color", v.color)
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
            .putBoolean("cloudDisabled", v.cloudDisabled).putBoolean("heartbeatEnabled", v.heartbeatEnabled)
            .putBoolean("neteaseScrobbleEnabled", v.neteaseScrobbleEnabled)
            .putString("localAvatarPath", v.localAvatarPath)
            .putString("localUserName", v.localUserName)
            .putString("localHomeSignature", v.localHomeSignature)
            .putString("localBannerStyle", v.localBannerStyle.name)
            .putString("localBannerPath", v.localBannerPath)
            .putString("localBannerMotionPlayMode", v.localBannerMotionPlayMode.name)
            .putInt("localBannerGlassRefraction", v.localBannerGlassRefraction)
            .putInt("localBannerGlassBlur", v.localBannerGlassBlur)
            .putInt("localBannerGlassOpacity", v.localBannerGlassOpacity)
            .putInt("localBannerGlassHighlight", v.localBannerGlassHighlight)
            .putInt("localBannerGlassCorner", v.localBannerGlassCorner)
            .putBoolean("localBannerGlassDispersion", v.localBannerGlassDispersion)
            .putString("localBannerStyleConfigs", encodeBannerStyleConfigs(v.localBannerStyleConfigs))
            .putString("neteaseBannerConfig", encodeHomeBannerConfig(v.neteaseBannerConfig))
            .putString("qqBannerConfig", encodeHomeBannerConfig(v.qqBannerConfig))
            .apply()
        value = v
        btm.m.auraplayer.widget.WidgetUpdateCoordinator.requestUpdate(appContext)
    }

    companion object {
        internal fun decodeBannerStyleConfigs(
            raw: String?,
            legacyGlassTemplate: LocalBannerStyleConfig? = null,
        ): Map<LocalBannerStyle, LocalBannerStyleConfig> {
            val json = raw?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
            return LocalBannerStyle.entries.filter { it != LocalBannerStyle.NONE }.associateWith { style ->
                val defaults = defaultLocalBannerStyleConfig(style)
                val base = if (legacyGlassTemplate != null &&
                    (style == LocalBannerStyle.GLASS_POSTER || style == LocalBannerStyle.IMMERSIVE_GLASS)
                ) {
                    legacyGlassTemplate.copy(heightDp = defaults.heightDp).normalized()
                } else {
                    defaults
                }
                val item = json?.optJSONObject(style.name) ?: return@associateWith base
                val bgStyle = HomeBackgroundStyle.entries.firstOrNull {
                    it.name == item.optString("backgroundStyle")
                } ?: base.backgroundStyle
                base.copy(
                    heightDp = item.optInt("heightDp", base.heightDp),
                    glassRefraction = item.optInt("glassRefraction", base.glassRefraction),
                    glassBlur = item.optInt("glassBlur", base.glassBlur),
                    glassOpacity = item.optInt("glassOpacity", base.glassOpacity),
                    glassHighlight = item.optInt("glassHighlight", base.glassHighlight),
                    glassCorner = item.optInt("glassCorner", base.glassCorner),
                    glassDispersion = item.optBoolean("glassDispersion", base.glassDispersion),
                    backgroundStyle = bgStyle,
                    backgroundBlurDp = item.optInt("backgroundBlurDp", base.backgroundBlurDp),
                ).normalized()
            }
        }

        internal fun encodeBannerStyleConfigs(configs: Map<LocalBannerStyle, LocalBannerStyleConfig>): String =
            encodeBannerStyleConfigsJson(configs).toString()

        private fun encodeBannerStyleConfigsJson(configs: Map<LocalBannerStyle, LocalBannerStyleConfig>): JSONObject =
            JSONObject().apply {
                LocalBannerStyle.entries.filter { it != LocalBannerStyle.NONE }.forEach { style ->
                    val cfg = (configs[style] ?: defaultLocalBannerStyleConfig(style)).normalized()
                    put(style.name, JSONObject().apply {
                        put("heightDp", cfg.heightDp)
                        put("glassRefraction", cfg.glassRefraction)
                        put("glassBlur", cfg.glassBlur)
                        put("glassOpacity", cfg.glassOpacity)
                        put("glassHighlight", cfg.glassHighlight)
                        put("glassCorner", cfg.glassCorner)
                        put("glassDispersion", cfg.glassDispersion)
                        put("backgroundStyle", cfg.backgroundStyle.name)
                        put("backgroundBlurDp", cfg.backgroundBlurDp)
                    })
                }
            }

        internal fun decodeHomeBannerConfig(raw: String?): HomeBannerConfig {
            val json = raw?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return HomeBannerConfig()
            val style = LocalBannerStyle.entries.firstOrNull { it.name == json.optString("style") }
                ?: LocalBannerStyle.NONE
            val imagePath = json.optString("imagePath", "")
            val motionPlayMode = BannerMotionPlayMode.entries.firstOrNull {
                it.name == json.optString("motionPlayMode")
            } ?: BannerMotionPlayMode.LOOP
            val styleConfigs = decodeBannerStyleConfigs(json.optJSONObject("styleConfigs")?.toString())
            return HomeBannerConfig(
                style = style,
                imagePath = imagePath,
                motionPlayMode = motionPlayMode,
                styleConfigs = styleConfigs,
            )
        }

        internal fun encodeHomeBannerConfig(config: HomeBannerConfig): String =
            JSONObject().apply {
                put("style", config.style.name)
                put("imagePath", config.imagePath)
                put("motionPlayMode", config.motionPlayMode.name)
                put("styleConfigs", encodeBannerStyleConfigsJson(config.styleConfigs))
            }.toString()
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
