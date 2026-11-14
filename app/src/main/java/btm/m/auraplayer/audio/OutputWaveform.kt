package btm.m.auraplayer.audio

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Read-only tap of post-DSP PCM. Active while the effects preview or spectrum progress bar is visible.
 * Audio-thread-owned accumulation; immutable snapshots cross to Compose at <=30 Hz.
 * This is the app's PCM output, before Android mixing/device volume, not microphone capture. */
@UnstableApi
class OutputWaveform {
    data class Snapshot(
        val peaks: FloatArray,
        val updatedNanos: Long,
        val spectrum: FloatArray = FloatArray(SPECTRUM_BANDS),
    )

    @Volatile var enabled = false
    @Volatile var spectrumEnabled = false
    @Volatile var snapshot: Snapshot? = null; private set

    private val ring = FloatArray(256)
    private var position = 0
    private var frames = 0
    private var publishFrames = 0
    private var low = 0f
    private var high = 0f
    private var lastRate = 0

    private val lpAlphas = FloatArray(SPECTRUM_BANDS - 1)
    private val lpStates = FloatArray(SPECTRUM_BANDS - 1)
    private val bandEnergySq = DoubleArray(SPECTRUM_BANDS)
    private var spectrumFrames = 0

    fun reset() {
        ring.fill(0f); position = 0; frames = 0; publishFrames = 0
        low = 0f; high = 0f; lastRate = 0; snapshot = null
        lpStates.fill(0f); bandEnergySq.fill(0.0); spectrumFrames = 0
    }

    private fun configureSampleRate(sampleRate: Int) {
        val sr = sampleRate.coerceAtLeast(1)
        for (i in BAND_CUTOFFS_HZ.indices) {
            val w = 2.0 * PI * BAND_CUTOFFS_HZ[i] / sr
            lpAlphas[i] = (1.0 - exp(-w)).toFloat().coerceIn(0f, 1f)
        }
    }

    fun offer(pcm: ByteBuffer, encoding: Int, channels: Int, sampleRate: Int) {
        if (!enabled && !spectrumEnabled) { if (lastRate != 0) reset(); return }
        if (lastRate != sampleRate) {
            reset()
            lastRate = sampleRate
            configureSampleRate(sampleRate)
        }
        val bytes = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            else -> 4
        }
        val bigEndian = encoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN
        val bucketFrames = (sampleRate / 250).coerceAtLeast(1) // ~512 ms scrolling history.
        val publishInterval = (sampleRate / 30).coerceAtLeast(1)
        val channelCount = channels.coerceAtLeast(1)
        val analyzeSpectrum = spectrumEnabled
        var offset = pcm.position()
        while (offset + bytes * channelCount <= pcm.limit()) {
            var monoSum = 0f
            repeat(channelCount) { channel ->
                val index = offset + channel * bytes
                var value = 0
                if (bytes > 1) for (b in 0 until bytes) {
                    val shift = 8 * (if (bigEndian) bytes - 1 - b else b)
                    value = value or ((pcm.get(index + b).toInt() and 255) shl shift)
                }
                val sample = when (encoding) {
                    C.ENCODING_PCM_8BIT -> ((pcm.get(index).toInt() and 255) - 128) / 128f
                    C.ENCODING_PCM_FLOAT -> Float.fromBits(value)
                    else -> ((value shl (32 - bytes * 8)) shr (32 - bytes * 8)).toDouble().div(1L shl (bytes * 8 - 1)).toFloat()
                }.let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
                low = min(low, sample); high = max(high, sample)
                monoSum += sample
            }
            if (analyzeSpectrum) {
                var residual = monoSum / channelCount
                for (stage in BAND_CUTOFFS_HZ.indices) {
                    val lp = lpStates[stage] + lpAlphas[stage] * (residual - lpStates[stage])
                    lpStates[stage] = lp
                    val highBand = residual - lp
                    val bandIndex = SPECTRUM_BANDS - 1 - stage
                    bandEnergySq[bandIndex] += (highBand * highBand).toDouble()
                    residual = lp
                }
                bandEnergySq[0] += (residual * residual).toDouble()
                spectrumFrames++
            }
            offset += bytes * channelCount
            if (++frames >= bucketFrames) {
                ring[position] = low; ring[position + 1] = high
                position = (position + 2) % ring.size
                frames = 0; low = 0f; high = 0f
            }
            if (++publishFrames >= publishInterval) {
                val ordered = FloatArray(ring.size) { ring[(position + it) % ring.size] }
                val spectrum = FloatArray(SPECTRUM_BANDS)
                if (analyzeSpectrum && spectrumFrames > 0) {
                    val frameCount = spectrumFrames.toDouble()
                    val logNorm = ln(19f)
                    for (b in 0 until SPECTRUM_BANDS) {
                        val rms = sqrt(bandEnergySq[b] / frameCount).toFloat()
                        val tilt = 1f + b * 0.12f
                        spectrum[b] = (ln(1f + rms * tilt * 18f) / logNorm).coerceIn(0f, 1f)
                    }
                    bandEnergySq.fill(0.0)
                    spectrumFrames = 0
                }
                snapshot = Snapshot(ordered, System.nanoTime(), spectrum)
                publishFrames = 0
            }
        }
    }

    companion object {
        const val SPECTRUM_BANDS = 8
        // Cascaded descending low-pass cutoffs split audio into 8 logarithmic frequency bands.
        private val BAND_CUTOFFS_HZ = floatArrayOf(7000f, 3500f, 1760f, 880f, 440f, 220f, 110f)
    }
}
