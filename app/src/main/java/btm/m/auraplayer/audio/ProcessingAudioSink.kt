package btm.m.auraplayer.audio

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.*
import java.nio.ByteBuffer

@UnstableApi
internal class ProcessingAudioSink(
    sink: AudioSink,
    private val waveform: OutputWaveform? = null,
    smartStyle: () -> SoundStyle? = { null },
    trackGeneration: () -> Long = { 0L },
    onSmartConfig: (DspConfig) -> Unit = {},
    config: () -> DspConfig,
) : ForwardingAudioSink(sink) {
    // Process before DefaultAudioSink's format conversion, so 24/32-bit sources are not
    // needlessly reduced to 16-bit to enter Media3's integer-only processor chain.
    private val processor = AuraAudioProcessor(config, smartStyle, trackGeneration, onSmartConfig)
    private var pendingInput: ByteBuffer? = null
    private var pendingOutput: ByteBuffer? = null
    private var readPosition = 0
    private var startPosition = 0
    private var frameSize = 1
    private var chunkBytes = 1
    private var sampleRate = 1
    private var encoding = 0
    private var channels = 1
    private var chunkTimeUs = 0L
    private var configured = false
    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW && format.pcmEncoding in AuraAudioProcessor.encodings && format.channelCount in 1..32 && format.sampleRate in 8000..384000)
            super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.Builder().build()
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        try { processor.configure(AudioProcessor.AudioFormat(inputFormat)) }
        catch (error: AudioProcessor.UnhandledAudioFormatException) { throw AudioSink.ConfigurationException(error, inputFormat) }
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        frameSize = AudioProcessor.AudioFormat(inputFormat).bytesPerFrame
        sampleRate = inputFormat.sampleRate
        encoding = inputFormat.pcmEncoding
        channels = inputFormat.channelCount
        waveform?.reset()
        chunkBytes = (sampleRate / 100).coerceAtLeast(1) * frameSize
        configured = true
        pendingInput = null; pendingOutput = null
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (pendingInput == null) {
            if (!buffer.hasRemaining()) return true
            require(buffer.remaining() % frameSize == 0) { "PCM_FRAME_ALIGNMENT" }
            pendingInput = buffer
            readPosition = buffer.position()
            startPosition = readPosition
        }
        check(pendingInput === buffer) { "AudioSink input changed before consumption" }
        while (readPosition < buffer.limit() || pendingOutput != null) {
            if (pendingOutput == null) {
                // Only process what AudioTrack is about to receive. A decoder may return seconds
                // of PCM in one buffer; pending backpressure must not freeze all of it to old EQ.
                val end = readPosition + minOf(chunkBytes, buffer.limit() - readPosition)
                val chunk = buffer.duplicate().apply { position(readPosition); limit(end) }
                chunkTimeUs = presentationTimeUs + (readPosition - startPosition).toLong() / frameSize * 1_000_000 / sampleRate
                processor.queueInput(chunk)
                pendingOutput = processor.output
                waveform?.offer(pendingOutput!!, encoding, channels, sampleRate)
                readPosition = end
            }
            // Preserve the same processed chunk and timestamp across partial AudioTrack writes.
            if (!super.handleBuffer(pendingOutput!!, chunkTimeUs, encodedAccessUnitCount)) return false
            pendingOutput = null
        }
        buffer.position(buffer.limit())
        pendingInput = null; pendingOutput = null
        return true
    }
    override fun flush() {
        waveform?.reset()
        pendingInput = null; pendingOutput = null
        if (configured) processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        super.flush()
    }
    override fun reset() {
        waveform?.reset()
        pendingInput = null; pendingOutput = null; configured = false
        processor.reset(); super.reset()
    }
}
