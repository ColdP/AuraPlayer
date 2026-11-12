package btm.m.auraplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.math.pow

@UnstableApi
class AuraAudioProcessorTest {
    private fun processor(encoding: Int, channels: Int = 2, rate: Int = 48000, config: () -> DspConfig): AuraAudioProcessor = AuraAudioProcessor(config).apply {
        configure(AudioProcessor.AudioFormat(rate, channels, encoding)); flush(AudioProcessor.StreamMetadata.DEFAULT)
    }
    private fun process(p: AuraAudioProcessor, bytes: ByteArray): ByteArray {
        val b = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply { put(bytes); flip() }
        p.queueInput(b); assertFalse(b.hasRemaining())
        return p.output.let { out -> ByteArray(out.remaining()).also(out::get) }
    }
    @Test fun bypassPreservesEveryBitAcrossAllPcmPrecisions() {
        val random = Random(8)
        for ((encoding, size) in listOf(C.ENCODING_PCM_8BIT to 1, C.ENCODING_PCM_16BIT to 2, C.ENCODING_PCM_24BIT to 3, C.ENCODING_PCM_32BIT to 4, C.ENCODING_PCM_FLOAT to 4,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN to 2, C.ENCODING_PCM_24BIT_BIG_ENDIAN to 3, C.ENCODING_PCM_32BIT_BIG_ENDIAN to 4)) {
            val p = processor(encoding) { BuiltInPresets.all.first().config }
            val bytes = random.nextBytes(2000 * size * 2)
            assertArrayEquals(bytes, process(p, bytes))
            assertArrayEquals(bytes, process(p, bytes))
        }
    }
    @Test fun splitBuffersProduceSameSamplesAsWholeBuffer() {
        for ((encoding, size) in listOf(C.ENCODING_PCM_16BIT to 2, C.ENCODING_PCM_24BIT to 3, C.ENCODING_PCM_32BIT to 4)) {
            val config = BuiltInPresets.all[3].config
            val a = processor(encoding) { config }; val b = processor(encoding) { config }
            val bytes = Random(3).nextBytes(4000 * size * 2)
            val whole = process(a, bytes)
            val split = process(b, bytes.copyOfRange(0, 1400 * size * 2)) + process(b, bytes.copyOfRange(1400 * size * 2, bytes.size))
            assertArrayEquals(whole, split)
        }
    }
    @Test fun presetChangeReturnsToExactBypassAfterTransition() {
        var config = BuiltInPresets.all[1].config
        val p = processor(C.ENCODING_PCM_16BIT) { config }
        val bytes = Random(2).nextBytes(19200)
        process(p, bytes)
        config = BuiltInPresets.all.first().config
        process(p, bytes)
        assertArrayEquals(bytes, process(p, bytes))
    }
    @Test fun flushReconfiguresChannelsAndRateWithoutStaleState() {
        val p = processor(C.ENCODING_PCM_24BIT) { BuiltInPresets.all[4].config }
        process(p, Random(1).nextBytes(600))
        p.configure(AudioProcessor.AudioFormat(96000, 1, C.ENCODING_PCM_FLOAT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val output = process(p, ByteArray(4096))
        assertArrayEquals(ByteArray(4096), output)
    }
    @Test fun activePcmConversionHasCorrectSignedScaleAndEndianOrder() {
        val samples = listOf(-.5, 0.0, .5, -.5)
        for ((encoding, size, big) in listOf(
            Triple(C.ENCODING_PCM_8BIT, 1, false), Triple(C.ENCODING_PCM_16BIT, 2, false), Triple(C.ENCODING_PCM_24BIT, 3, false),
            Triple(C.ENCODING_PCM_32BIT, 4, false), Triple(C.ENCODING_PCM_FLOAT, 4, false), Triple(C.ENCODING_PCM_16BIT_BIG_ENDIAN, 2, true),
            Triple(C.ENCODING_PCM_24BIT_BIG_ENDIAN, 3, true), Triple(C.ENCODING_PCM_32BIT_BIG_ENDIAN, 4, true))) {
            val input = ByteBuffer.allocate(samples.size * size).order(if (big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
            for (x in samples) when (encoding) {
                C.ENCODING_PCM_8BIT -> input.put((x * 128 + 128).toInt().toByte())
                C.ENCODING_PCM_FLOAT -> input.putFloat(x.toFloat())
                else -> when (size) {
                    2 -> input.putShort((x * 32768).toInt().toShort())
                    3 -> {
                        val v = (x * 8388608).toInt()
                        if (big) { input.put((v shr 16).toByte()); input.put((v shr 8).toByte()); input.put(v.toByte()) }
                        else { input.put(v.toByte()); input.put((v shr 8).toByte()); input.put((v shr 16).toByte()) }
                    }
                    else -> input.putInt((x * 2147483648.0).toInt())
                }
            }
            val config = DspConfig()
            val p = processor(encoding) { config }
            val output = ByteBuffer.wrap(process(p, input.array())).order(input.order())
            for (x in samples) {
                val actual = when (encoding) {
                    C.ENCODING_PCM_8BIT -> ((output.get().toInt() and 255) - 128) / 128.0
                    C.ENCODING_PCM_FLOAT -> output.float.toDouble()
                    else -> when (size) {
                        2 -> output.short / 32768.0
                        3 -> {
                            val a = output.get().toInt(); val b = output.get().toInt(); val c = output.get().toInt()
                            (if (big) (a shl 16) or ((b and 255) shl 8) or (c and 255) else (c shl 16) or ((b and 255) shl 8) or (a and 255)) / 8388608.0
                        }
                        else -> output.int / 2147483648.0
                    }
                }
                assertEquals("encoding $encoding", x * 10.0.pow(-3.0 / 20), actual, if (size == 1) .004 else .00002)
            }
        }
    }
}
