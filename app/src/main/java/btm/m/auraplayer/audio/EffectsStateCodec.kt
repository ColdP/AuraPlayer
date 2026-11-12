package btm.m.auraplayer.audio

import org.json.JSONArray
import org.json.JSONObject

/** Library is stored separately so dragging a slider does not reserialize 100 presets. */
internal object EffectsStateCodec {
    fun encode(s: EffectsState): String = JSONObject().apply {
        put("selected", s.selected); put("style", s.style.name); put("custom", PresetCodec.configJson(s.custom))
        put("counts", JSONObject(s.counts)); put("recent", JSONObject(s.recent))
        put("fade", s.fade); put("fadeMs", s.fadeMs); put("alwaysPlay", s.alwaysPlay)
        put("draftDirty", s.draftDirty); put("draftBase", s.draftBase)
        s.draftOriginal?.let { put("draftOriginal", PresetCodec.configJson(it)) }
        // Pending confirmation belongs to the live UI, never resume an action after process death.
    }.toString()
    fun encodeLibrary(list: List<DspPreset>): String = JSONArray().apply {
        list.forEach { p -> put(JSONObject().put("id", p.id).put("preset", JSONObject(PresetCodec.encode(p)))) }
    }.toString()
    fun decode(raw: String, library: String? = null): EffectsState = runCatching {
        val j = JSONObject(raw)
        val defaults = EffectsState()
        val imported = library?.let(::JSONArray) ?: j.optJSONArray("imported") ?: JSONArray()
        fun longs(key: String): Map<String, Long> = j.optJSONObject(key)?.let { obj -> obj.keys().asSequence().associateWith { obj.getLong(it).coerceAtLeast(0) } } ?: emptyMap()
        defaults.copy(selected = j.optString("selected", "reference"), style = SoundStyle.valueOf(j.optString("style", "ORIGINAL")),
            custom = j.optJSONObject("custom")?.let(PresetCodec::readConfig) ?: defaults.custom,
            imported = List(imported.length().coerceAtMost(100)) { i ->
                val row = imported.getJSONObject(i)
                val id = row.getString("id"); require(id.startsWith("imported-") && id.length <= 80)
                PresetCodec.decode(row.getJSONObject("preset").toString()).copy(id = id)
            }.distinctBy { it.id }, counts = longs("counts").mapValues { it.value.coerceAtMost(1000000).toInt() }, recent = longs("recent"),
            fade = j.optBoolean("fade"), fadeMs = j.optInt("fadeMs", 600).coerceIn(100, 3000), alwaysPlay = j.optBoolean("alwaysPlay"),
            draftDirty = j.optBoolean("draftDirty"), draftBase = j.optString("draftBase", "reference"), draftOriginal = j.optJSONObject("draftOriginal")?.let(PresetCodec::readConfig))
            .let { s -> if (s.presets.any { it.id == s.selected }) s.copy(draftDirty = s.draftDirty && s.selected == "custom") else s.copy(selected = "reference", draftDirty = false) }
    }.getOrDefault(EffectsState())
}
