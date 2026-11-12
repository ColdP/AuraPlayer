package btm.m.auraplayer.audio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EffectsState(
    val selected: String = "reference",
    val style: SoundStyle = SoundStyle.ORIGINAL,
    val custom: DspConfig = DspConfig(bands = listOf(60.0, 250.0, 1000.0, 4000.0, 12000.0).map { EqBand(it, 0.0) }),
    val imported: List<DspPreset> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val recent: Map<String, Long> = emptyMap(),
    val fade: Boolean = false,
    val fadeMs: Int = 600,
    val alwaysPlay: Boolean = false,
    val draftDirty: Boolean = false,
    val draftBase: String = "reference",
    val draftOriginal: DspConfig? = null,
    val pendingSelection: String? = null,
) {
    val presets get() = BuiltInPresets.all + DspPreset("custom", "Custom", "", custom) + imported
    val current get() = presets.firstOrNull { it.id == selected } ?: BuiltInPresets.all.first()
    val config get() = BuiltInPresets.styled(current.config, style)
    val featured get() = featuredPresets(presets, selected, counts, recent)
    val editableConfig get() = (if (selected == "custom") custom else current.config).let {
        if (it.bands.isEmpty()) it.copy(bands = listOf(60.0, 250.0, 1000.0, 4000.0, 12000.0).map { hz -> EqBand(hz, 0.0) }) else it
    }
    fun edited(c: DspConfig): EffectsState {
        val original = if (selected == "custom") draftOriginal ?: custom else current.config
        return copy(custom = c.validate(), selected = "custom", draftOriginal = original,
            draftBase = if (selected == "custom") draftBase else selected, draftDirty = c != original)
    }
    fun requestPreset(id: String, now: Long): EffectsState {
        require(id == "__defaults__" || presets.any { it.id == id })
        if (id == "custom") return copy(selected = "custom")
        return if (draftDirty && id != "custom") copy(pendingSelection = id) else applyPreset(id, now)
    }
    fun applyPreset(id: String, now: Long): EffectsState {
        if (id == "__defaults__") return copy(selected = "reference", style = SoundStyle.ORIGINAL, custom = EffectsState().custom,
            draftDirty = false, draftBase = "reference", draftOriginal = null, pendingSelection = null)
        return copy(selected = id, draftDirty = false, draftOriginal = null, pendingSelection = null,
            counts = counts + (id to ((counts[id] ?: 0) + 1).coerceAtMost(1000000)), recent = recent + (id to now))
    }
    fun savedDraft(name: String, now: Long): EffectsState {
        require(name.isNotBlank() && name.trim().length <= 80 && imported.size < 100)
        val p = DspPreset("imported-${java.util.UUID.randomUUID()}", name.trim(), "", custom.validate())
        return copy(imported = imported + p, selected = p.id, draftDirty = false, draftOriginal = null,
            counts = counts + (p.id to 1), recent = recent + (p.id to now))
    }
}

/** Application-owned immutable snapshots. No SharedPreferences access on ExoPlayer's audio thread. */
class EffectsStore(context: Context) {
    val outputWaveform = OutputWaveform()
    @Volatile var audioSessionId: Int = 0
    private val prefs = context.getSharedPreferences("aura.audio.effects.v1", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(EffectsStateCodec.decode(prefs.getString("state", "{}")!!, prefs.getString("library", null)))
    val state = mutable.asStateFlow()
    @Volatile var realtimeConfig: DspConfig = mutable.value.config; private set

    fun select(id: String) = update { it.requestPreset(id, System.currentTimeMillis()) }
    fun restoreDefaults() = select("__defaults__")
    fun cancelPending() = update { it.copy(pendingSelection = null) }
    fun finishPending() = update { s -> s.pendingSelection?.let { s.applyPreset(it, System.currentTimeMillis()) } ?: s }
    fun style(style: SoundStyle) = update { it.copy(style = style) }
    fun custom(c: DspConfig) = update { it.edited(c) }
    fun saveDraft(name: String) = update { it.savedDraft(name, System.currentTimeMillis()) }
    fun importPreset(p: DspPreset) = update {
        require(it.imported.size < 100) { "LIBRARY_FULL" }; p.config.validate()
        it.copy(imported = it.imported + p).requestPreset(p.id, System.currentTimeMillis())
    }
    fun delete(id: String) = update { it.copy(imported = it.imported.filterNot { p -> p.id == id }, selected = if (it.selected == id) "reference" else it.selected,
        pendingSelection = if (it.pendingSelection == id) null else it.pendingSelection, counts = it.counts - id, recent = it.recent - id) }
    fun audio(fade: Boolean = state.value.fade, ms: Int = state.value.fadeMs, always: Boolean = state.value.alwaysPlay) =
        update { it.copy(fade = fade, fadeMs = ms.coerceIn(100, 3000), alwaysPlay = always) }

    @Synchronized private fun update(block: (EffectsState) -> EffectsState) {
        val previous = mutable.value
        val next = block(previous)
        realtimeConfig = next.config
        mutable.value = next
        prefs.edit().putString("state", EffectsStateCodec.encode(next)).apply {
            if (!prefs.contains("library") || previous.imported != next.imported) putString("library", EffectsStateCodec.encodeLibrary(next.imported))
        }.apply()
    }
}
