// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

enum class NeteaseChartGroup {
    FEATURED,
    GENRE,
    GLOBAL,
    LANGUAGE,
}

enum class NeteaseChartSource {
    PLAYLIST,
    STYLE_TAG,
}

data class NeteaseChartTrackPreview(
    val title: String,
    val artist: String,
)

data class NeteaseChartSpec(
    val id: Long,
    val group: NeteaseChartGroup,
    val source: NeteaseChartSource,
    val titleKey: String,
    val defaultTitle: String,
    val defaultFrequency: String,
    val accentHex: Long,
    val styleBadge: String = "",
)

data class NeteaseChartItem(
    val spec: NeteaseChartSpec,
    val artwork: String = "",
    val updateFrequency: String = spec.defaultFrequency,
    val tracks: List<NeteaseChartTrackPreview> = emptyList(),
) {
    val stableKey: String
        get() = "${spec.group.name}:${spec.source.name}:${spec.id}"
}

internal data class NeteaseToplistMeta(
    val id: Long,
    val name: String,
    val artwork: String,
    val updateFrequency: String,
    val tracks: List<NeteaseChartTrackPreview>,
)

object NeteaseChartCatalog {
    val featuredSpecs: List<NeteaseChartSpec> = listOf(
        NeteaseChartSpec(19723756L, NeteaseChartGroup.FEATURED, NeteaseChartSource.PLAYLIST, "chart_soaring", "飙升榜", "每天更新", 0xFF4F7CFF),
        NeteaseChartSpec(3779629L, NeteaseChartGroup.FEATURED, NeteaseChartSource.PLAYLIST, "chart_new_songs", "新歌榜", "每天更新", 0xFF2FA89A),
        NeteaseChartSpec(3778678L, NeteaseChartGroup.FEATURED, NeteaseChartSource.PLAYLIST, "chart_hot_songs", "热歌榜", "每天更新", 0xFFE05A47),
        NeteaseChartSpec(2884035L, NeteaseChartGroup.FEATURED, NeteaseChartSource.PLAYLIST, "chart_original", "原创榜", "每周四更新", 0xFF6A6EE8),
        NeteaseChartSpec(7785066739L, NeteaseChartGroup.FEATURED, NeteaseChartSource.PLAYLIST, "chart_vip_hot", "VIP 热歌榜", "每天更新", 0xFFC89B54),
        NeteaseChartSpec(7785123708L, NeteaseChartGroup.FEATURED, NeteaseChartSource.PLAYLIST, "chart_vip_new", "VIP 新歌榜", "每天更新", 0xFFB98B48),
        NeteaseChartSpec(8246775932L, NeteaseChartGroup.FEATURED, NeteaseChartSource.PLAYLIST, "chart_realtime_heat", "实时热度榜", "实时更新", 0xFFE5694B),
        NeteaseChartSpec(6723173524L, NeteaseChartGroup.FEATURED, NeteaseChartSource.PLAYLIST, "chart_viral_hot", "网络热歌榜", "每周五更新", 0xFFD85B88),
        NeteaseChartSpec(13372522766L, NeteaseChartGroup.FEATURED, NeteaseChartSource.PLAYLIST, "chart_trend", "潮流风向榜", "每天更新", 0xFF7B61FF),
        NeteaseChartSpec(18176153161L, NeteaseChartGroup.FEATURED, NeteaseChartSource.PLAYLIST, "chart_realtime_share", "实时分享榜", "实时更新", 0xFF3A9BD5),
    )

    val genreSpecs: List<NeteaseChartSpec> = listOf(
        NeteaseChartSpec(1000L, NeteaseChartGroup.GENRE, NeteaseChartSource.STYLE_TAG, "chart_pop", "流行榜", "实时热门", 0xFFE86C7E, styleBadge = "POP"),
        NeteaseChartSpec(1020L, NeteaseChartGroup.GENRE, NeteaseChartSource.STYLE_TAG, "chart_cpop", "华语流行榜", "实时热门", 0xFFE06D53, styleBadge = "C-POP"),
        NeteaseChartSpec(1009L, NeteaseChartGroup.GENRE, NeteaseChartSource.STYLE_TAG, "chart_cantopop", "粤语流行榜", "实时热门", 0xFFD28B46, styleBadge = "CANTO"),
        NeteaseChartSpec(1032L, NeteaseChartGroup.GENRE, NeteaseChartSource.STYLE_TAG, "chart_western_pop", "欧美流行榜", "实时热门", 0xFF4C80D8, styleBadge = "WEST"),
        NeteaseChartSpec(1180L, NeteaseChartGroup.GENRE, NeteaseChartSource.STYLE_TAG, "chart_kpop", "K-Pop 榜", "实时热门", 0xFFC566D6, styleBadge = "K-POP"),
        NeteaseChartSpec(1034L, NeteaseChartGroup.GENRE, NeteaseChartSource.STYLE_TAG, "chart_jpop", "J-Pop 榜", "实时热门", 0xFF3E9DC7, styleBadge = "J-POP"),
        NeteaseChartSpec(12225155968L, NeteaseChartGroup.GENRE, NeteaseChartSource.PLAYLIST, "chart_western_rnb", "欧美 R&B 榜", "每周五更新", 0xFF8C63D6),
        NeteaseChartSpec(14028249541L, NeteaseChartGroup.GENRE, NeteaseChartSource.PLAYLIST, "chart_global_rap", "全球说唱榜", "每周五更新", 0xFFD67C3B),
        NeteaseChartSpec(71385702L, NeteaseChartGroup.GENRE, NeteaseChartSource.PLAYLIST, "chart_acg", "ACG 榜", "每周四更新", 0xFFDF5C9C),
        NeteaseChartSpec(3001890046L, NeteaseChartGroup.GENRE, NeteaseChartSource.PLAYLIST, "chart_vocaloid", "VOCALOID 榜", "每天更新", 0xFF39C5BB),
        NeteaseChartSpec(5059633707L, NeteaseChartGroup.GENRE, NeteaseChartSource.PLAYLIST, "chart_rock", "摇滚榜", "每周五更新", 0xFFD14B4B),
        NeteaseChartSpec(5059661515L, NeteaseChartGroup.GENRE, NeteaseChartSource.PLAYLIST, "chart_folk", "民谣榜", "每周五更新", 0xFF4F9D79),
        NeteaseChartSpec(71384707L, NeteaseChartGroup.GENRE, NeteaseChartSource.PLAYLIST, "chart_classical", "古典榜", "每周四更新", 0xFF8E7B64),
        NeteaseChartSpec(5059642708L, NeteaseChartGroup.GENRE, NeteaseChartSource.PLAYLIST, "chart_guofeng", "国风榜", "每周五更新", 0xFFB85450),
    )

    val globalSpecs: List<NeteaseChartSpec> = listOf(
        NeteaseChartSpec(60198L, NeteaseChartGroup.GLOBAL, NeteaseChartSource.PLAYLIST, "chart_billboard", "美国公告牌榜", "每周三更新", 0xFF3A7BD5),
        NeteaseChartSpec(180106L, NeteaseChartGroup.GLOBAL, NeteaseChartSource.PLAYLIST, "chart_uk", "英国 UK 榜", "每周一更新", 0xFF5368C9),
        NeteaseChartSpec(60131L, NeteaseChartGroup.GLOBAL, NeteaseChartSource.PLAYLIST, "chart_oricon", "日本公信榜", "每周四更新", 0xFFD95B7A),
        NeteaseChartSpec(27135204L, NeteaseChartGroup.GLOBAL, NeteaseChartSource.PLAYLIST, "chart_france_nrj", "法国 NRJ 榜", "每周五更新", 0xFF4C72B8),
        NeteaseChartSpec(6939992364L, NeteaseChartGroup.GLOBAL, NeteaseChartSource.PLAYLIST, "chart_russia_pop", "俄罗斯流行榜", "每周五更新", 0xFF5E77A8),
        NeteaseChartSpec(3812895L, NeteaseChartGroup.GLOBAL, NeteaseChartSource.PLAYLIST, "chart_beatport", "全球电子舞曲榜", "每周三更新", 0xFF2CA58D),
    )

    val languageSpecs: List<NeteaseChartSpec> = listOf(
        NeteaseChartSpec(2809513713L, NeteaseChartGroup.LANGUAGE, NeteaseChartSource.PLAYLIST, "chart_western_hot", "欧美热歌榜", "每周四更新", 0xFF4D79C8),
        NeteaseChartSpec(2809577409L, NeteaseChartGroup.LANGUAGE, NeteaseChartSource.PLAYLIST, "chart_western_new", "欧美新歌榜", "每天更新", 0xFF3D96B8),
        NeteaseChartSpec(5059644681L, NeteaseChartGroup.LANGUAGE, NeteaseChartSource.PLAYLIST, "chart_japanese", "日语榜", "每周二更新", 0xFFE36B88),
        NeteaseChartSpec(745956260L, NeteaseChartGroup.LANGUAGE, NeteaseChartSource.PLAYLIST, "chart_korean", "韩语榜", "每周四更新", 0xFF9C6BDE),
        NeteaseChartSpec(6732051320L, NeteaseChartGroup.LANGUAGE, NeteaseChartSource.PLAYLIST, "chart_russian", "俄语榜", "每周四更新", 0xFF597BB5),
        NeteaseChartSpec(7095271308L, NeteaseChartGroup.LANGUAGE, NeteaseChartSource.PLAYLIST, "chart_thai", "泰语榜", "每周四更新", 0xFFD98743),
        NeteaseChartSpec(6732014811L, NeteaseChartGroup.LANGUAGE, NeteaseChartSource.PLAYLIST, "chart_vietnamese", "越南语榜", "每周四更新", 0xFF479B74),
    )

    val specsByGroup: Map<NeteaseChartGroup, List<NeteaseChartSpec>> = linkedMapOf(
        NeteaseChartGroup.FEATURED to featuredSpecs,
        NeteaseChartGroup.GENRE to genreSpecs,
        NeteaseChartGroup.GLOBAL to globalSpecs,
        NeteaseChartGroup.LANGUAGE to languageSpecs,
    )

    /** Fixed 4 charts displayed on the NetEase home page. */
    val homeSpecs: List<NeteaseChartSpec> = listOf(
        featuredSpecs[0], // 飙升榜
        featuredSpecs[1], // 新歌榜
        languageSpecs[2], // 日语榜
        genreSpecs[8], // ACG 榜
    )

    val styleSpecs: List<NeteaseChartSpec> = genreSpecs.filter { it.source == NeteaseChartSource.STYLE_TAG }

    fun defaultHomeItems(): List<NeteaseChartItem> = homeSpecs.map { NeteaseChartItem(it) }

    fun defaultGroupedItems(): Map<NeteaseChartGroup, List<NeteaseChartItem>> =
        specsByGroup.mapValues { (_, specs) -> specs.map { NeteaseChartItem(it) } }
}
