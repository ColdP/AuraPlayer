package btm.m.auraplayer.audio

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class FilterType { PEAK, LOW_SHELF, HIGH_SHELF }
data class EqBand(val frequency: Double, val gainDb: Double, val q: Double = .707, val type: FilterType = FilterType.PEAK)
data class DspConfig(
    val bypass: Boolean = false,
    val bands: List<EqBand> = emptyList(),
    val preampDb: Double = -2.0,
    val width: Double = 1.0,
    val crossfeed: Double = 0.0,
    val saturation: Double = 0.0,
    val thresholdDb: Double = -12.0,
    val ratio: Double = 1.0,
    val attackMs: Double = 20.0,
    val releaseMs: Double = 160.0,
) {
    fun validate(): DspConfig = apply {
        fun range(v: Double, lo: Double, hi: Double) = require(v.isFinite() && v in lo..hi) { "PARAMETER_RANGE" }
        require(bands.size <= 8) { "TOO_MANY_BANDS" }
        bands.forEach { range(it.frequency, 20.0, 20000.0); range(it.gainDb, -9.0, 6.0); range(it.q, .3, 3.0) }
        range(preampDb, -18.0, 0.0); range(width, .5, 1.4); range(crossfeed, 0.0, .3); range(saturation, 0.0, .25)
        range(thresholdDb, -30.0, -3.0); range(ratio, 1.0, 4.0); range(attackMs, 1.0, 100.0); range(releaseMs, 30.0, 1000.0)
    }
}

data class DspPreset(val id: String, val name: String, val description: String, val config: DspConfig, val core: Boolean = false)
enum class SoundStyle { CLASSIC, BALANCED, ORIGINAL }

/** Names are localized at the UI boundary; DSP data never depends on the current language. */
object BuiltInPresets {
    private fun peak(hz: Double, db: Double, q: Double = .8) = EqBand(hz, db, q)
    private fun low(hz: Double, db: Double) = EqBand(hz, db, type = FilterType.LOW_SHELF)
    private fun high(hz: Double, db: Double) = EqBand(hz, db, type = FilterType.HIGH_SHELF)
    val all = listOf(
        DspPreset("reference", "Reference", "", DspConfig(bypass = true, preampDb = 0.0), true),
        // Deliberately different tonal/dynamic profiles; boosts stay within +5 dB per band.
        DspPreset("warmth", "Warmth", "", DspConfig(bands = listOf(low(180.0, 3.2), peak(600.0, 1.0), high(4200.0, -2.8)), preampDb = -1.0, width = .94, crossfeed = .10, saturation = .14), true),
        DspPreset("clarity", "Clarity", "", DspConfig(bands = listOf(low(120.0, -1.0), peak(320.0, -2.8), peak(2800.0, 3.0), high(9000.0, 2.0)), preampDb = -1.0, width = 1.04), true),
        DspPreset("deep_bass", "Deep Bass", "", DspConfig(bands = listOf(low(65.0, 5.0), peak(180.0, 1.2), peak(400.0, -2.0), high(6500.0, -1.2)), preampDb = -1.5, thresholdDb = -18.0, ratio = 1.6, attackMs = 30.0, releaseMs = 180.0), true),
        DspPreset("wide_stage", "Wide Stage", "", DspConfig(bands = listOf(low(150.0, .8), peak(700.0, -1.4), high(6500.0, 1.8)), preampDb = -1.0, width = 1.4), true),
        DspPreset("j_pop", "J-Pop", "", DspConfig(bands = listOf(low(110.0, 2.2), peak(450.0, -2.0), peak(2200.0, 2.7), peak(6500.0, -.8), high(10000.0, 1.0)), preampDb = -1.0, width = 1.10, thresholdDb = -18.0, ratio = 1.35, attackMs = 18.0, releaseMs = 130.0)),
        DspPreset("vocaloid", "Vocaloid", "", DspConfig(bands = listOf(low(75.0, 2.6), peak(700.0, -2.2), peak(1800.0, 1.2), peak(3800.0, -3.0, 1.2), high(10000.0, 2.4)), preampDb = -1.0, width = 1.24, saturation = .08, thresholdDb = -18.0, ratio = 1.25, attackMs = 12.0, releaseMs = 100.0)),
        DspPreset("anime", "Anime Soundtrack", "", DspConfig(bands = listOf(low(80.0, 3.6), peak(400.0, -.8), peak(1600.0, 1.9), high(7500.0, 1.6)), preampDb = -1.0, width = 1.30)),
        DspPreset("electronic", "Electronic", "", DspConfig(bands = listOf(low(60.0, 4.4), peak(140.0, 1.5), peak(400.0, -2.8), high(7500.0, 2.4)), preampDb = -1.5, width = 1.22, ratio = 1.8, thresholdDb = -18.0, attackMs = 28.0, releaseMs = 120.0)),
        DspPreset("rock", "Rock", "", DspConfig(bands = listOf(low(110.0, 2.0), peak(350.0, 1.0), peak(1700.0, 2.8), peak(4500.0, -2.4), high(10000.0, -1.2)), preampDb = -1.0, width = 1.05, saturation = .18, ratio = 1.65, thresholdDb = -17.0, attackMs = 10.0, releaseMs = 110.0)),
        DspPreset("acoustic", "Acoustic", "", DspConfig(bands = listOf(low(100.0, -1.2), peak(250.0, -2.2), peak(2400.0, 2.4), high(8500.0, 1.2)), preampDb = -1.0, width = .96, crossfeed = .12)),
        DspPreset("classical", "Classical", "", DspConfig(bands = listOf(low(70.0, 1.0), peak(450.0, -.8), high(6000.0, .4)), preampDb = -1.0, width = 1.20, crossfeed = .04)),
        DspPreset("jazz", "Jazz Lounge", "", DspConfig(bands = listOf(low(180.0, 2.2), peak(1000.0, 2.4), peak(3200.0, -1.0), high(6000.0, -2.8)), preampDb = -1.0, width = .90, crossfeed = .20, saturation = .10)),
        DspPreset("piano", "Piano Solo", "", DspConfig(bands = listOf(low(90.0, -1.2), peak(320.0, -1.5), peak(1000.0, 1.6), peak(3000.0, -1.0), high(9000.0, 1.4)), preampDb = -1.0, width = 1.04, crossfeed = .08)),
        DspPreset("live", "Live Recording", "", DspConfig(bands = listOf(low(90.0, 1.4), peak(350.0, -2.4), peak(2200.0, 2.1), high(8000.0, -2.4)), preampDb = -1.0, width = 1.36, crossfeed = .02)),
    ).also { list -> list.forEach { it.config.validate() } }

    fun styled(config: DspConfig, style: SoundStyle): DspConfig {
        if (config.bypass) return config
        return when (style) {
            SoundStyle.ORIGINAL -> config
            SoundStyle.BALANCED -> config.copy(bands = config.bands.map { it.copy(gainDb = it.gainDb * .75) }, width = 1 + (config.width - 1) * .75)
            SoundStyle.CLASSIC -> config.copy(bands = if (config.bands.size < 8) config.bands + low(140.0, .6) else config.bands, saturation = (config.saturation + .02).coerceAtMost(.25))
        }
    }
}

/** One preset per document/QR. No URLs, paths, executable content, or persisted usage data. */
object PresetCodec {
    const val MAX_BYTES = 32768
    fun encode(p: DspPreset): String = JSONObject().apply {
        put("format", "aura-dsp"); put("version", 1); put("name", p.name); put("description", p.description)
        put("dsp", configJson(p.config))
    }.toString()
    fun configJson(c: DspConfig): JSONObject = JSONObject().apply {
        c.validate()
        put("bypass", c.bypass); put("preampDb", c.preampDb); put("width", c.width); put("crossfeed", c.crossfeed)
        put("saturation", c.saturation); put("thresholdDb", c.thresholdDb); put("ratio", c.ratio)
        put("attackMs", c.attackMs); put("releaseMs", c.releaseMs)
        put("bands", JSONArray().apply { c.bands.forEach { b -> put(JSONObject().apply {
            put("frequency", b.frequency); put("gainDb", b.gainDb); put("q", b.q); put("type", b.type.name)
        }) } })
    }
    fun decode(text: String): DspPreset {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "PRESET_TOO_LARGE" }
        val j = JSONObject(text)
        require(j.getString("format") == "aura-dsp" && j.getInt("version") == 1) { "UNSUPPORTED_PRESET" }
        val name = j.getString("name").trim(); require(name.isNotBlank() && name.length <= 80)
        val description = j.optString("description"); require(description.length <= 240)
        return DspPreset("imported-${UUID.randomUUID()}", name, description, readConfig(j.getJSONObject("dsp")))
    }
    fun readConfig(j: JSONObject): DspConfig {
        val array = j.getJSONArray("bands"); require(array.length() <= 8)
        return DspConfig(j.getBoolean("bypass"), List(array.length()) { i ->
            val b = array.getJSONObject(i)
            EqBand(b.getDouble("frequency"), b.getDouble("gainDb"), b.getDouble("q"), FilterType.valueOf(b.getString("type")))
        }, j.getDouble("preampDb"), j.getDouble("width"), j.getDouble("crossfeed"), j.getDouble("saturation"),
            j.getDouble("thresholdDb"), j.getDouble("ratio"), j.getDouble("attackMs"), j.getDouble("releaseMs")).validate()
    }
}

fun featuredPresets(all: List<DspPreset>, selected: String, counts: Map<String, Int>, recent: Map<String, Long>): List<DspPreset> =
    all.sortedWith(compareByDescending<DspPreset> { it.id == selected }
        .thenByDescending { counts[it.id] ?: 0 }.thenByDescending { recent[it.id] ?: 0 }
        .thenByDescending { it.core }.thenBy { all.indexOf(it) }).take(5)
