package btm.m.auraplayer.audio

import kotlin.math.*

/**
 * Real-time open-loop spectral & dynamic analyzer for song-adaptive tuning ("Smart Tuning").
 *
 * Uses a bank of 6 constant-Q RBJ bandpass filters (0 dB peak gain), active-frame gating,
 * linear-power averaging, and target-slope matching inspired by commercial LTAS studies
 * (Pestana, Ma & Reiss, AES 2013) and smooth spectral balancing (Ma, Reiss & Black, AES 2013).
 *
 * Thread-confined to the audio processing thread; zero allocations per sample/frame.
 */
internal class SmartTuningAnalyzer(private val sampleRate: Int, private val channels: Int) {
    companion object {
        val BAND_FREQS = doubleArrayOf(65.0, 175.0, 440.0, 1100.0, 2800.0, 7500.0)
        // Commercial LTAS target offset relative to constant-Q equal-energy-per-octave (pink noise),
        // anchored at 1100 Hz = 0 dB.
        private val TARGET_OFFSET_DB = doubleArrayOf(1.0, 1.2, 0.2, 0.0, -1.6, -3.2)
        private const val ANALYSIS_Q = 1.1
        private const val ACTIVE_RMS_THRESHOLD = 0.0056 // ~ -45 dBFS
        private const val INITIAL_LOCK_WINDOWS = 12     // ~ 240 ms of active audio
        private const val EVAL_INTERVAL_WINDOWS = 50    // ~ 1.0 s cadence after lock
        private const val DAMPING = 0.55
        private const val MAX_BAND_DB = 4.0

        fun neutralConfig(): DspConfig = DspConfig(
            bands = listOf(
                EqBand(65.0, 0.0, 0.707, FilterType.LOW_SHELF),
                EqBand(175.0, 0.0, 0.9, FilterType.PEAK),
                EqBand(440.0, 0.0, 0.9, FilterType.PEAK),
                EqBand(1100.0, 0.0, 0.85, FilterType.PEAK),
                EqBand(2800.0, 0.0, 0.9, FilterType.PEAK),
                EqBand(7500.0, 0.0, 0.707, FilterType.HIGH_SHELF),
            ),
            preampDb = -1.0,
        )
    }

    private val bandCount = BAND_FREQS.size
    private val bandActive = BooleanArray(bandCount) { BAND_FREQS[it] < sampleRate * 0.42 }
    private val b0 = DoubleArray(bandCount)
    private val a1 = DoubleArray(bandCount)
    private val a2 = DoubleArray(bandCount)
    private val z1 = DoubleArray(bandCount)
    private val z2 = DoubleArray(bandCount)

    private val windowFrames = (sampleRate / 50).coerceAtLeast(1) // 20 ms windows
    private val bandSqSum = DoubleArray(bandCount)
    private val avgBandEnergy = DoubleArray(bandCount)
    private val rawDiff = DoubleArray(bandCount)
    private val smoothedDiff = DoubleArray(bandCount)

    private var windowCount = 0
    private var monoSqSum = 0.0
    private var midSqSum = 0.0
    private var sideSqSum = 0.0
    private var windowPeak = 0.0

    private var activeWindows = 0
    private var windowsSinceEval = 0
    private var avgCrestDb = 12.0
    private var avgSideFraction = 0.25
    private var hasLocked = false

    var currentConfig: DspConfig = neutralConfig()
        private set

    init {
        require(sampleRate in 8000..384000 && channels in 1..32)
        for (i in 0 until bandCount) {
            if (!bandActive[i]) continue
            val w = 2.0 * PI * BAND_FREQS[i] / sampleRate
            val alpha = sin(w) / (2.0 * ANALYSIS_Q)
            val a0 = 1.0 + alpha
            b0[i] = alpha / a0
            a1[i] = -2.0 * cos(w) / a0
            a2[i] = (1.0 - alpha) / a0
        }
    }

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
        bandSqSum.fill(0.0)
        avgBandEnergy.fill(0.0)
        windowCount = 0
        monoSqSum = 0.0
        midSqSum = 0.0
        sideSqSum = 0.0
        windowPeak = 0.0
        activeWindows = 0
        windowsSinceEval = 0
        avgCrestDb = 12.0
        avgSideFraction = 0.25
        hasLocked = false
        currentConfig = neutralConfig()
    }

    /** Feed one pre-DSP normalized PCM frame. Returns true if [currentConfig] updated. */
    fun offerFrame(frame: DoubleArray): Boolean {
        var mono = 0.0
        for (ch in 0 until channels) {
            val s = frame[ch]
            mono += s
            val absS = abs(s)
            if (absS > windowPeak) windowPeak = absS
        }
        mono /= channels
        monoSqSum += mono * mono

        if (channels == 2) {
            val mid = (frame[0] + frame[1]) * 0.5
            val side = (frame[0] - frame[1]) * 0.5
            midSqSum += mid * mid
            sideSqSum += side * side
        }

        for (i in 0 until bandCount) {
            if (!bandActive[i]) continue
            val b = b0[i]
            val y = b * mono + z1[i]
            val nz1 = -a1[i] * y + z2[i]
            val nz2 = -b * mono - a2[i] * y
            z1[i] = if (abs(nz1) < 1e-24) 0.0 else nz1
            z2[i] = if (abs(nz2) < 1e-24) 0.0 else nz2
            bandSqSum[i] += y * y
        }

        if (++windowCount < windowFrames) return false
        return finishWindow()
    }

    private fun finishWindow(): Boolean {
        val n = windowCount
        val rms = sqrt(monoSqSum / n)
        val peak = windowPeak
        val midE = midSqSum / n
        val sideE = sideSqSum / n

        windowCount = 0
        monoSqSum = 0.0
        midSqSum = 0.0
        sideSqSum = 0.0
        windowPeak = 0.0

        if (rms < ACTIVE_RMS_THRESHOLD) {
            bandSqSum.fill(0.0)
            return false
        }

        val alpha = if (activeWindows < 25) 1.0 / (activeWindows + 1) else 0.008
        for (i in 0 until bandCount) {
            if (bandActive[i]) {
                val e = bandSqSum[i] / n
                avgBandEnergy[i] += alpha * (e - avgBandEnergy[i])
            }
            bandSqSum[i] = 0.0
        }

        val crestDb = (20.0 * log10(peak / rms.coerceAtLeast(1e-12))).coerceIn(1.0, 30.0)
        avgCrestDb += alpha * (crestDb - avgCrestDb)

        if (channels == 2) {
            val sideFrac = sideE / (midE + sideE + 1e-12)
            avgSideFraction += alpha * (sideFrac - avgSideFraction)
        }

        activeWindows++
        windowsSinceEval++

        val shouldEvaluate = (!hasLocked && activeWindows >= INITIAL_LOCK_WINDOWS) ||
            (hasLocked && windowsSinceEval >= EVAL_INTERVAL_WINDOWS)
        if (!shouldEvaluate) return false

        windowsSinceEval = 0
        val candidate = buildCandidateConfig()
        val changed = !hasLocked || differsSignificantly(currentConfig, candidate)
        if (changed) {
            hasLocked = true
            currentConfig = candidate
        }
        return changed
    }

    private fun buildCandidateConfig(): DspConfig {
        var sum = 0.0
        var count = 0
        for (i in 0 until bandCount) {
            if (!bandActive[i]) {
                rawDiff[i] = 0.0
                continue
            }
            val measuredDb = 10.0 * log10(avgBandEnergy[i].coerceAtLeast(1e-12))
            rawDiff[i] = TARGET_OFFSET_DB[i] - measuredDb
            sum += rawDiff[i]
            count++
        }
        val mean = if (count > 0) sum / count else 0.0
        for (i in 0 until bandCount) {
            if (bandActive[i]) rawDiff[i] -= mean
        }

        // Gentle spectral smoothing across adjacent bands, then proportional damping & clamping.
        for (i in 0 until bandCount) {
            if (!bandActive[i]) {
                smoothedDiff[i] = 0.0
                continue
            }
            val left = if (i > 0 && bandActive[i - 1]) rawDiff[i - 1] else rawDiff[i]
            val right = if (i + 1 < bandCount && bandActive[i + 1]) rawDiff[i + 1] else rawDiff[i]
            val blended = 0.15 * left + 0.70 * rawDiff[i] + 0.15 * right
            smoothedDiff[i] = (blended * DAMPING).coerceIn(-MAX_BAND_DB, MAX_BAND_DB)
        }

        val bands = listOf(
            EqBand(65.0, roundTenth(smoothedDiff[0]), 0.707, FilterType.LOW_SHELF),
            EqBand(175.0, roundTenth(smoothedDiff[1]), 0.9, FilterType.PEAK),
            EqBand(440.0, roundTenth(smoothedDiff[2]), 0.9, FilterType.PEAK),
            EqBand(1100.0, roundTenth(smoothedDiff[3]), 0.85, FilterType.PEAK),
            EqBand(2800.0, roundTenth(smoothedDiff[4]), 0.9, FilterType.PEAK),
            EqBand(7500.0, roundTenth(smoothedDiff[5]), 0.707, FilterType.HIGH_SHELF),
        )

        val width = if (channels == 2) {
            roundHundredth((1.16 - avgSideFraction * 0.40).coerceIn(0.96, 1.16))
        } else 1.0
        val crossfeed = if (channels == 2 && avgSideFraction > 0.35) {
            roundHundredth(((avgSideFraction - 0.35) * 0.20).coerceIn(0.0, 0.06))
        } else 0.0

        val (ratio, thresholdDb, saturation) = when {
            avgCrestDb < 9.5 -> Triple(1.0, -16.0, 0.0)
            avgCrestDb <= 14.5 -> Triple(1.15, -18.0, 0.03)
            else -> Triple(1.35, -20.0, 0.04)
        }

        return DspConfig(
            bypass = false,
            bands = bands,
            preampDb = -1.0,
            width = width,
            crossfeed = crossfeed,
            saturation = saturation,
            thresholdDb = thresholdDb,
            ratio = ratio,
            attackMs = 25.0,
            releaseMs = 160.0,
        ).validate()
    }

    private fun differsSignificantly(a: DspConfig, b: DspConfig): Boolean {
        for (i in a.bands.indices) {
            if (abs(a.bands[i].gainDb - b.bands[i].gainDb) >= 0.25) return true
        }
        return abs(a.width - b.width) >= 0.03 || abs(a.ratio - b.ratio) >= 0.10
    }

    private fun roundTenth(v: Double) = (v * 10.0).roundToInt() / 10.0
    private fun roundHundredth(v: Double) = (v * 100.0).roundToInt() / 100.0
}
