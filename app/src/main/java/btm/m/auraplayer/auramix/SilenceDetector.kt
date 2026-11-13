package btm.m.auraplayer.auramix

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class AudioWindow(val rms: Double, val peak: Double)

/** Streaming silence detector. It stores only ~50 windows per second, never the PCM stream. */
class SilenceDetector(private val sampleRate: Int, private val channels: Int, windowMs: Int = 20) {
    private val frameWindow = (sampleRate * windowMs / 1000).coerceAtLeast(1)
    private var frameCount = 0
    private var sumSquares = 0.0
    private var peak = 0.0
    private val windows = ArrayList<AudioWindow>()

    fun accept(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        val end = (offset + length).coerceIn(offset, samples.size)
        var i = offset
        while (i < end) {
            val value = samples[i].toDouble().coerceIn(-1.0, 1.0)
            sumSquares += value * value
            peak = max(peak, kotlin.math.abs(value))
            frameCount++
            if (frameCount >= frameWindow) flushWindow()
            i++
        }
    }

    fun finish(totalFrames: Long = 0L): AudioFeatures {
        if (frameCount > 0) flushWindow()
        if (windows.isEmpty()) return AudioFeatures(0, 0, 0, 0, 0, -120.0, -120.0, 0.0)
        val floor = windows.map { it.rms }.sorted()[((windows.size - 1) * .2).toInt()]
        val maximumRms = windows.maxOf { it.rms }
        val windowMs = frameWindow * 1000L / sampleRate.coerceAtLeast(1)
        val durationMs = if (totalFrames > 0) totalFrames * 1000L / sampleRate else windows.size * windowMs
        if (maximumRms < 1e-6) return AudioFeatures(durationMs, durationMs, 0, durationMs, durationMs, -120.0, -120.0, .9)
        // A uniformly quiet recording is still music. Only call it silence when the window
        // distribution provides a separable quiet floor and active level.
        val threshold = if (maximumRms <= floor * 3.2) floor * .55 else max(.0015, floor * 3.2)
        val active = windows.indices.filter { windows[it].rms >= threshold || windows[it].peak >= threshold * 2.0 }
        if (active.isEmpty()) return AudioFeatures(durationMs, durationMs, 0, durationMs, durationMs, rmsDb(windows), peakDb(windows), .35)
        val first = active.first()
        val last = active.last()
        val start = (first * windowMs).coerceIn(0, durationMs)
        val end = ((last + 1) * windowMs).coerceIn(start, durationMs)
        val separation = (maximumRms / threshold.coerceAtLeast(1e-6)).coerceIn(1.0, 8.0)
        val confidence = (.55 + separation / 16.0).coerceIn(.55, 1.0)
        return AudioFeatures(durationMs, start, end, start, (durationMs - end).coerceAtLeast(0), rmsDb(windows), peakDb(windows), confidence)
    }

    private fun flushWindow() {
        windows += AudioWindow(sqrt(sumSquares / frameCount.coerceAtLeast(1)), peak)
        frameCount = 0; sumSquares = 0.0; peak = 0.0
    }

    private fun rmsDb(values: List<AudioWindow>) = 20.0 * log10(values.map { it.rms }.average().coerceAtLeast(1e-6))
    private fun peakDb(values: List<AudioWindow>) = 20.0 * log10(values.maxOf { it.peak }.coerceAtLeast(1e-6))

    companion object {
        fun analyze(samples: FloatArray, sampleRate: Int, channels: Int): AudioFeatures {
            require(sampleRate > 0 && channels > 0)
            val detector = SilenceDetector(sampleRate, channels)
            detector.accept(samples)
            return detector.finish((samples.size / channels).toLong())
        }
    }
}
