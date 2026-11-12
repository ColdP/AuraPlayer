package btm.m.auraplayer.audio

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/** Read-only tap of post-DSP PCM. Only active while the effects preview is visible.
 * Audio-thread-owned accumulation; immutable snapshots cross to Compose at <=25 Hz.
 * This is the app's PCM output, before Android mixing/device volume, not microphone capture. */
@UnstableApi
class OutputWaveform {
    data class Snapshot(val peaks: FloatArray, val updatedNanos: Long)
    @Volatile var enabled = false
    @Volatile var snapshot: Snapshot? = null; private set
    private val ring = FloatArray(256)
    private var position = 0
    private var frames = 0
    private var publishFrames = 0
    private var low = 0f
    private var high = 0f
    private var lastRate = 0

    fun reset() {
        ring.fill(0f); position = 0; frames = 0; publishFrames = 0
        low = 0f; high = 0f; lastRate = 0; snapshot = null
    }

    fun offer(pcm: ByteBuffer, encoding: Int, channels: Int, sampleRate: Int) {
        if (!enabled) { if (lastRate != 0) reset(); return }
        if (lastRate != sampleRate) { reset(); lastRate = sampleRate }
        val bytes = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            else -> 4
        }
        val bigEndian = encoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN
        val bucketFrames = (sampleRate / 250).coerceAtLeast(1) // ~512 ms scrolling history.
        var offset = pcm.position()
        while (offset + bytes * channels <= pcm.limit()) {
            repeat(channels) { channel ->
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
            }
            offset += bytes * channels
            if (++frames >= bucketFrames) {
                ring[position] = low; ring[position + 1] = high
                position = (position + 2) % ring.size
                frames = 0; low = 0f; high = 0f
            }
            if (++publishFrames >= sampleRate / 25) {
                val ordered = FloatArray(ring.size) { ring[(position + it) % ring.size] }
                snapshot = Snapshot(ordered, System.nanoTime())
                publishFrames = 0
            }
        }
    }
}
