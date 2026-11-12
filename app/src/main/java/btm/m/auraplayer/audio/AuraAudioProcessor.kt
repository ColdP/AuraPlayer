package btm.m.auraplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Kept active even in bypass, so presets can change without re-preparing a track. */
@UnstableApi
class AuraAudioProcessor(private val config: () -> DspConfig) : BaseAudioProcessor() {
    companion object {
        val encodings = setOf(C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN)
    }
    private var engine: DspEngine? = null
    private var previous: DspEngine? = null
    private var applied: DspConfig? = null
    private var input = DoubleArray(0)
    private var output = DoubleArray(0)
    private var oldOutput = DoubleArray(0)
    private var remaining = 0
    private var transitionFrames = 1

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding !in encodings || inputAudioFormat.channelCount !in 1..32 || inputAudioFormat.sampleRate !in 8000..384000)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        applied = null; engine = null; previous = null; remaining = 0
        input = DoubleArray(inputAudioFormat.channelCount.coerceAtLeast(0))
        output = DoubleArray(input.size); oldOutput = DoubleArray(input.size)
        transitionFrames = (inputAudioFormat.sampleRate * .03).toInt().coerceAtLeast(1)
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val desired = config()
        if (desired != applied) {
            previous = engine
            engine = DspEngine(desired, inputAudioFormat.sampleRate, input.size)
            applied = desired
            remaining = if (previous == null) 0 else transitionFrames
        }
        val bytes = inputBuffer.remaining()
        val encoding = inputAudioFormat.encoding
        val bigEndian = encoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val sampleBytes = when (encoding) { C.ENCODING_PCM_8BIT -> 1; C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2; C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3; else -> 4 }
        require(bytes % (input.size * sampleBytes) == 0) { "PCM_FRAME_ALIGNMENT" }
        val buffer = replaceOutputBuffer(bytes).order(order)
        if (desired.bypass && remaining == 0) {
            // Preserve every PCM byte, including -32768. No float conversion, gain or limiter.
            buffer.put(inputBuffer)
        } else {
            inputBuffer.order(order)
            while (inputBuffer.hasRemaining()) {
                for (ch in input.indices) input[ch] = when (inputAudioFormat.encoding) {
                    C.ENCODING_PCM_8BIT -> ((inputBuffer.get().toInt() and 255) - 128).toDouble() / 128
                    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> inputBuffer.short.toDouble() / 32768
                    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                        val first = inputBuffer.get().toInt(); val second = inputBuffer.get().toInt(); val third = inputBuffer.get().toInt()
                        val value = if (bigEndian) (third and 255) or ((second and 255) shl 8) or (first shl 16)
                            else (first and 255) or ((second and 255) shl 8) or (third shl 16)
                        value.toDouble() / 8388608
                    }
                    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> inputBuffer.int.toDouble() / 2147483648.0
                    else -> inputBuffer.float.toDouble().let { if (it.isFinite()) it.coerceIn(-1.0, 1.0) else 0.0 }
                }
                engine!!.processFrame(input, output)
                if (remaining > 0) {
                    previous!!.processFrame(input, oldOutput)
                    val mix = 1.0 - remaining.toDouble() / transitionFrames
                    for (ch in input.indices) output[ch] = oldOutput[ch] * (1 - mix) + output[ch] * mix
                    remaining--
                    if (remaining == 0) previous = null
                }
                for (ch in input.indices) when (inputAudioFormat.encoding) {
                    C.ENCODING_PCM_8BIT -> buffer.put(((output[ch] * 128).roundToInt() + 128).coerceIn(0, 255).toByte())
                    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> buffer.putShort((output[ch] * 32768).roundToInt().coerceIn(-32768, 32767).toShort())
                    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                        val value = (output[ch] * 8388608).roundToInt().coerceIn(-8388608, 8388607)
                        if (bigEndian) { buffer.put((value shr 16).toByte()); buffer.put((value shr 8).toByte()); buffer.put(value.toByte()) }
                        else { buffer.put(value.toByte()); buffer.put((value shr 8).toByte()); buffer.put((value shr 16).toByte()) }
                    }
                    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> buffer.putInt(kotlin.math.round(output[ch] * 2147483648.0).toLong().coerceIn(-2147483648L, 2147483647L).toInt())
                    else -> buffer.putFloat(output[ch].toFloat())
                }
            }
        }
        buffer.flip()
    }
    override fun onReset() { engine = null; previous = null; applied = null; input = DoubleArray(0); output = input; oldOutput = input }
}
