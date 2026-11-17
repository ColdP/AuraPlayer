package btm.m.auraplayer.audio

import org.json.JSONArray
import org.json.JSONObject

/** Library is stored separately so dragging a slider does not reserialize 100 presets. */
internal object EffectsStateCodec {
    fun encode(s: EffectsState): String = JSONObject().apply {
        put("selected", s.selected); put("style", s.style.name); put("custom", PresetCodec.configJson(s.custom))
        put("counts", JSONObject(s.counts)); put("recent", JSONObject(s.recent))
        put("fade", s.fade); put("fadeMs", s.fadeMs); put("alwaysPlay", s.alwaysPlay)
        put("nativePreviousBehavior", s.nativePreviousBehavior)
        put("usbExclusive", s.usbExclusive)
        put("usbBufferMs", EffectsStore.normalizeUsbBufferMs(s.usbBufferMs))
        put("usbLockFixedFormat", s.usbLockFixedFormat)
        put("usbVolume", s.usbVolume.coerceIn(0f, 1f).toDouble())
        put("usbVolumeCollapseOnRepeat", s.usbVolumeCollapseOnRepeat)
        put("usbVolumePosition", EffectsStore.normalizeUsbVolumePosition(s.usbVolumePosition))
        put("usbVolumeOffsetY", s.usbVolumeOffsetY.coerceIn(-64, 96))
        put("usbVolumeOffsetX", s.usbVolumeOffsetX.coerceIn(-32, 64))
        put("usbVolumeHeightScale", s.usbVolumeHeightScale.coerceIn(-100, 100))
        put("usbVolumeWidthScale", s.usbVolumeWidthScale.coerceIn(-100, 100))
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
            nativePreviousBehavior = j.optBoolean("nativePreviousBehavior", defaults.nativePreviousBehavior),
            usbExclusive = j.optBoolean("usbExclusive", defaults.usbExclusive),
            usbBufferMs = EffectsStore.normalizeUsbBufferMs(j.optInt("usbBufferMs", defaults.usbBufferMs)),
            usbLockFixedFormat = j.optBoolean("usbLockFixedFormat", defaults.usbLockFixedFormat),
            usbVolume = j.optDouble("usbVolume", defaults.usbVolume.toDouble()).toFloat().let { if (it.isFinite()) it.coerceIn(0f, 1f) else defaults.usbVolume },
            usbVolumeCollapseOnRepeat = j.optBoolean("usbVolumeCollapseOnRepeat", defaults.usbVolumeCollapseOnRepeat),
            usbVolumePosition = EffectsStore.normalizeUsbVolumePosition(j.optString("usbVolumePosition", defaults.usbVolumePosition)),
            usbVolumeOffsetY = j.optInt("usbVolumeOffsetY", defaults.usbVolumeOffsetY).coerceIn(-64, 96),
            usbVolumeOffsetX = j.optInt("usbVolumeOffsetX", defaults.usbVolumeOffsetX).coerceIn(-32, 64),
            usbVolumeHeightScale = j.optInt("usbVolumeHeightScale", defaults.usbVolumeHeightScale).coerceIn(-100, 100),
            usbVolumeWidthScale = j.optInt("usbVolumeWidthScale", defaults.usbVolumeWidthScale).coerceIn(-100, 100),
            draftDirty = j.optBoolean("draftDirty"), draftBase = j.optString("draftBase", "reference"), draftOriginal = j.optJSONObject("draftOriginal")?.let(PresetCodec::readConfig))
            .let { s -> if (s.presets.any { it.id == s.selected }) s.copy(draftDirty = s.draftDirty && s.selected == "custom") else s.copy(selected = "reference", draftDirty = false) }
    }.getOrDefault(EffectsState())
}
