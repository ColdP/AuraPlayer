package btm.m.auraplayer.auramix

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AuraMixConfig(
    val enabled: Boolean = false,
    val mode: AuraMixMode = AuraMixMode.SMART,
    val durationMs: Long = 600L,
    val autoCalculate: Boolean = true,
)

data class AuraMixVisualTransition(
    val fromArtwork: String,
    val fromTitle: String,
    val fromArtist: String,
    val toArtwork: String,
    val toTitle: String,
    val toArtist: String,
    val progress: Float,
)

class AuraMixSettings(context: Context) {
    private val prefs = context.getSharedPreferences("aura.mix.v1", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    private val visualMutable = MutableStateFlow<AuraMixVisualTransition?>(null)
    val visual = visualMutable.asStateFlow()

    fun update(change: (AuraMixConfig) -> AuraMixConfig) {
        val changed = change(mutable.value)
        val next = changed.copy(durationMs = changed.durationMs.coerceIn(120, 12_000))
        mutable.value = next
        prefs.edit().putBoolean("enabled", next.enabled).putString("mode", next.mode.name)
            .putLong("duration", next.durationMs).putBoolean("auto", next.autoCalculate).apply()
    }

    fun publishVisual(value: AuraMixVisualTransition?) { visualMutable.value = value }

    private fun load() = AuraMixConfig(
        enabled = prefs.getBoolean("enabled", false),
        mode = runCatching { AuraMixMode.valueOf(prefs.getString("mode", AuraMixMode.SMART.name)!!) }.getOrDefault(AuraMixMode.SMART),
        durationMs = prefs.getLong("duration", 600L).coerceIn(120, 12_000),
        autoCalculate = prefs.getBoolean("auto", true),
    )
}
