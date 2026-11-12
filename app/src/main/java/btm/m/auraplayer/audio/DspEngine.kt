package btm.m.auraplayer.audio

import kotlin.math.*

/** RBJ cookbook filters, transposed direct form II. Independent state for every channel. */
internal class Biquad(b: EqBand, sampleRate: Int, channels: Int) {
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)
    private val b0: Double; private val b1: Double; private val b2: Double
    private val a1: Double; private val a2: Double
    init {
        val w = 2 * PI * b.frequency.coerceAtMost(sampleRate * .45) / sampleRate
        val cs = cos(w); val alpha = sin(w) / (2 * b.q); val a = 10.0.pow(b.gainDb / 40)
        val root = 2 * sqrt(a) * alpha
        val c = when (b.type) {
            FilterType.PEAK -> doubleArrayOf(1 + alpha * a, -2 * cs, 1 - alpha * a, 1 + alpha / a, -2 * cs, 1 - alpha / a)
            FilterType.LOW_SHELF -> doubleArrayOf(a * ((a + 1) - (a - 1) * cs + root), 2 * a * ((a - 1) - (a + 1) * cs), a * ((a + 1) - (a - 1) * cs - root), (a + 1) + (a - 1) * cs + root, -2 * ((a - 1) + (a + 1) * cs), (a + 1) + (a - 1) * cs - root)
            FilterType.HIGH_SHELF -> doubleArrayOf(a * ((a + 1) + (a - 1) * cs + root), -2 * a * ((a - 1) + (a + 1) * cs), a * ((a + 1) + (a - 1) * cs - root), (a + 1) - (a - 1) * cs + root, 2 * ((a - 1) - (a + 1) * cs), (a + 1) - (a - 1) * cs - root)
        }
        b0 = c[0] / c[3]; b1 = c[1] / c[3]; b2 = c[2] / c[3]; a1 = c[4] / c[3]; a2 = c[5] / c[3]
    }
    fun process(x: Double, channel: Int): Double {
        val y = b0 * x + z1[channel]
        z1[channel] = (b1 * x - a1 * y + z2[channel]).let { if (abs(it) < 1e-24) 0.0 else it }
        z2[channel] = (b2 * x - a2 * y).let { if (abs(it) < 1e-24) 0.0 else it }
        return y
    }

    /** Squared magnitude of this exact, sample-rate-clamped filter, without changing state. */
    fun magnitudeSquared(cosW: Double, sinW: Double): Double {
        val cos2W = 2 * cosW * cosW - 1
        val sin2W = 2 * sinW * cosW
        val nr = b0 + b1 * cosW + b2 * cos2W
        val ni = b1 * sinW + b2 * sin2W
        val dr = 1 + a1 * cosW + a2 * cos2W
        val di = a1 * sinW + a2 * sin2W
        return (nr * nr + ni * ni) / max(1e-30, dr * dr + di * di)
    }
}

/** No sample-rate conversion, delay lines or allocations in the sample loop. Stereo operations
 * only apply to actual stereo, never arbitrarily to surround channel pairs. */
class DspEngine(val config: DspConfig, private val sampleRate: Int, private val channels: Int) {
    private val filters: Array<Biquad>
    val headroomDb: Double
    private val gain: Double
    private val safetyGain: Double
    private val attack: Double
    private val release: Double
    private val limiterRelease: Double
    private val crossPole: Double
    private var lowL = 0.0
    private var lowR = 0.0
    private var envelope = 0.0
    private var limiterGain = 1.0
    init {
        config.validate(); require(sampleRate in 8000..384000 && channels in 1..32)
        filters = config.bands.map { Biquad(it, sampleRate, channels) }.toTypedArray()
        // Reserve for the combined response, rather than adding boosts at unrelated frequencies.
        // A dense response grid + band centers + 0.1 dB margin is an estimate, not a peak guarantee;
        // the final linked limiter still guards transients and high-Q custom responses.
        val eqPeakDb = estimateEqPeakDb()
        headroomDb = if (config.bypass) 0.0 else config.preampDb - eqPeakDb -
            20 * log10(max(1.0, if (channels == 2) config.width else 1.0)) - 1.0
        gain = 10.0.pow(headroomDb / 20)
        safetyGain = 10.0.pow((headroomDb - config.preampDb) / 20)
        attack = exp(-1 / (sampleRate * config.attackMs / 1000))
        release = exp(-1 / (sampleRate * config.releaseMs / 1000))
        limiterRelease = exp(-1 / (sampleRate * .08))
        crossPole = exp(-2 * PI * 700 / sampleRate)
    }

    private fun estimateEqPeakDb(): Double {
        if (config.bands.none { it.gainDb != 0.0 }) return 0.0
        fun responseDb(hz: Double): Double {
            val w = 2 * PI * hz / sampleRate
            val cs = cos(w); val sn = sin(w)
            return filters.sumOf { 10 * log10(max(1e-30, it.magnitudeSquared(cs, sn))) }
        }
        var peak = max(0.0, max(responseDb(0.0), responseDb(sampleRate * .5)))
        val octaves = ln(sampleRate * .5 / 10.0)
        for (i in 0..1024) peak = max(peak, responseDb(10 * exp(octaves * i / 1024)))
        for (band in config.bands) peak = max(peak, responseDb(band.frequency.coerceAtMost(sampleRate * .45)))
        return peak + .1
    }
    fun processFrame(input: DoubleArray, output: DoubleArray) {
        if (config.bypass) { input.copyInto(output, endIndex = channels); return }
        for (ch in 0 until channels) {
            var x = input[ch] * gain
            for (filter in filters) x = filter.process(x, ch)
            if (config.saturation > 0) {
                val drive = 1 + 4 * config.saturation
                // Safety attenuation must not starve the nonlinear stage. The explicit user
                // preamp still changes its drive; reapply safety gain before the output stage.
                val driven = x / safetyGain
                x = (1 - config.saturation) * x + config.saturation * safetyGain * tanh(driven * drive) / drive
            }
            output[ch] = x
        }
        if (channels == 2) {
            val l = output[0]; val r = output[1]
            lowL = (1 - crossPole) * l + crossPole * lowL
            lowR = (1 - crossPole) * r + crossPole * lowR
            val cf = config.crossfeed
            val left = (l + cf * lowR) / (1 + cf)
            val right = (r + cf * lowL) / (1 + cf)
            val mid = (left + right) * .5
            val side = (left - right) * .5 * config.width
            output[0] = mid + side; output[1] = mid - side
        }
        var peak = 0.0
        for (ch in 0 until channels) peak = max(peak, abs(output[ch]))
        val compressorGain = if (config.ratio > 1.0) {
            val pole = if (peak > envelope) attack else release
            envelope = peak + pole * (envelope - peak)
            // Threshold is measured before automatic safety attenuation, after user preamp/EQ.
            // Otherwise adding an EQ band or widening stereo silently disables compression.
            val levelDb = 20 * log10(max(1e-12, envelope / safetyGain))
            val reduction = max(0.0, levelDb - config.thresholdDb) * (1 - 1 / config.ratio)
            10.0.pow(-reduction / 20)
        } else 1.0
        val required = min(1.0, .98 / max(1e-12, peak * compressorGain))
        limiterGain = if (required < limiterGain) required else required + limiterRelease * (limiterGain - required)
        for (ch in 0 until channels) output[ch] = (output[ch] * compressorGain * limiterGain).let {
            if (it.isFinite()) it.coerceIn(-.98, .98) else 0.0
        }
    }
}
