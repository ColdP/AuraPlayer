package btm.m.auraplayer.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.math.pow

@UnstableApi
class ProcessingAudioSinkTest {
    private class FakeSink(val maxWrite: Int) {
        val written = ByteArrayOutputStream()
        var flushes = 0
        var calls = 0
        var blocked = false
        var onWrite: (() -> Unit)? = null
        val timestamps = mutableListOf<Long>()
        val requestedBytes = mutableListOf<Int>()
        val sink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "handleBuffer" -> {
                    calls++
                    val buffer = args!![0] as ByteBuffer
                    timestamps += args[1] as Long
                    requestedBytes += buffer.remaining()
                    val bytes = ByteArray(if (blocked) 0 else minOf(maxWrite, buffer.remaining()))
                    buffer.get(bytes); written.write(bytes)
                    onWrite?.invoke()
                    !buffer.hasRemaining()
                }
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                "supportsFormat" -> true
                "flush" -> { flushes++; null }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as AudioSink
    }
    private fun format(encoding: Int = C.ENCODING_PCM_24BIT) = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setSampleRate(96000).setChannelCount(2).setPcmEncoding(encoding).build()

    @Test fun waveformTapsProcessedAudioOnceAcrossPartialWrites() {
        val partial = FakeSink(120); val full = FakeSink(Int.MAX_VALUE)
        val first = OutputWaveform().apply { enabled = true }
        val second = OutputWaveform().apply { enabled = true }
        val config = DspConfig(preampDb = -12.0)
        val a = ProcessingAudioSink(partial.sink, first) { config }
        val b = ProcessingAudioSink(full.sink, second) { config }
        a.configure(format(C.ENCODING_PCM_FLOAT), 0, null)
        b.configure(format(C.ENCODING_PCM_FLOAT), 0, null)
        val pcm = ByteBuffer.allocate(9600 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(9600) { pcm.putFloat(-.8f); pcm.putFloat(.8f) }; pcm.flip()
        val whole = pcm.duplicate()
        while (!a.handleBuffer(pcm, 0, 1)) { /* Simulate backpressure. */ }
        assertTrue(b.handleBuffer(whole, 0, 1))
        assertArrayEquals(first.snapshot!!.peaks, second.snapshot!!.peaks, 0f)
        val expected = (.8 * 10.0.pow(-13.0 / 20)).toFloat()
        assertEquals(expected, first.snapshot!!.peaks.max(), 1e-6f)
        assertEquals(-expected, first.snapshot!!.peaks.min(), 1e-6f)
        assertArrayEquals(full.written.toByteArray(), partial.written.toByteArray())
    }

    @Test fun partialWritesDoNotReprocessOrDuplicatePcmAndPreserveOriginalBufferUntilDone() {
        val fake = FakeSink(114)
        val sink = ProcessingAudioSink(fake.sink) { DspConfig(bypass = true) }
        sink.configure(format(), 0, null)
        val bytes = Random(16).nextBytes(1200)
        val input = ByteBuffer.wrap(bytes)
        while (!sink.handleBuffer(input, 1000, 1)) assertEquals(0, input.position())
        assertEquals(input.limit(), input.position())
        assertArrayEquals(bytes, fake.written.toByteArray())
        assertTrue(fake.calls > 1)
    }
    @Test fun activeEffectsAreAppliedOnlyOnceEvenWhenAudioTrackStalls() {
        val config = BuiltInPresets.all[1].config
        val partial = FakeSink(120); val whole = FakeSink(Int.MAX_VALUE)
        val a = ProcessingAudioSink(partial.sink) { config }; val b = ProcessingAudioSink(whole.sink) { config }
        a.configure(format(), 0, null); b.configure(format(), 0, null)
        val bytes = Random(2).nextBytes(2400)
        val inputA = ByteBuffer.wrap(bytes.copyOf()); val inputB = ByteBuffer.wrap(bytes.copyOf())
        while (!a.handleBuffer(inputA, 0, 1)) { /* AudioTrack backpressure. */ }
        assertTrue(b.handleBuffer(inputB, 0, 1))
        assertArrayEquals(whole.written.toByteArray(), partial.written.toByteArray())
        assertFalse(bytes.contentEquals(whole.written.toByteArray()))
    }
    @Test fun flushDiscardsPendingAudioAndFilterState() {
        val fake = FakeSink(120)
        val sink = ProcessingAudioSink(fake.sink) { BuiltInPresets.all[1].config }
        sink.configure(format(), 0, null)
        assertFalse(sink.handleBuffer(ByteBuffer.wrap(Random(1).nextBytes(1200)), 0, 1))
        sink.flush()
        fake.written.reset()
        val silence = ByteBuffer.wrap(ByteArray(1200))
        while (!sink.handleBuffer(silence, 20000, 1)) { }
        assertEquals(1, fake.flushes)
        assertArrayEquals(ByteArray(1200), fake.written.toByteArray())
    }
    @Test fun encodedPassthroughAndOffloadCannotSkipEffects() {
        val sink = ProcessingAudioSink(FakeSink(120).sink) { DspConfig() }
        val encoded = format().buildUpon().setSampleMimeType(MimeTypes.AUDIO_AC3).build()
        assertFalse(sink.supportsFormat(encoded))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(encoded))
        assertFalse(sink.getFormatOffloadSupport(encoded).isFormatSupported)
        assertTrue(sink.supportsFormat(format()))
    }
    @Test fun aLargeDecoderBufferCanUseNewParametersWithoutWaitingForItsOldAudioToDrain() {
        var config = DspConfig(bypass = true, preampDb = 0.0)
        val fake = FakeSink(Int.MAX_VALUE)
        val sink = ProcessingAudioSink(fake.sink) { config }
        sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
        val frames = 96000
        val input = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames * 2) { putShort(16384.toShort()) }; flip()
        }
        fake.onWrite = { fake.blocked = true }
        assertFalse(sink.handleBuffer(input, 1_000_000, 1))
        assertEquals(0, input.position())
        assertEquals(960 * 4, fake.written.size())
        config = DspConfig(preampDb = -12.0)
        fake.blocked = false; fake.onWrite = null
        assertTrue(sink.handleBuffer(input, 1_000_000, 1))
        val output = ByteBuffer.wrap(fake.written.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(input.limit(), output.remaining())
        // At most one old pending chunk (10 ms) remains, then a 30 ms click-free transition.
        assertEquals(16384, output.getShort(0).toInt())
        assertEquals((.5 * 10.0.pow(-13.0 / 20) * 32768).toInt().toDouble(), output.getShort(9600 * 4).toDouble(), 1.0)
        assertTrue(fake.requestedBytes.all { it <= 960 * 4 })
        assertEquals(1_000_000L, fake.timestamps[0])
        assertEquals(1_010_000L, fake.timestamps[1])
        assertEquals(fake.timestamps[1], fake.timestamps[2])
        assertEquals(1_990_000L, fake.timestamps.last())
    }
    @Test fun chunkingPreservesPcmBytesTimestampsAndBufferPositionAcrossAllFrameSizes() {
        for ((encoding, frameSize) in listOf(C.ENCODING_PCM_16BIT to 4, C.ENCODING_PCM_24BIT to 6, C.ENCODING_PCM_FLOAT to 8)) {
            val fake = FakeSink(77)
            val sink = ProcessingAudioSink(fake.sink) { DspConfig(bypass = true) }
            sink.configure(format(encoding).buildUpon().setSampleRate(44100).build(), 0, null)
            val bytes = Random(2).nextBytes(5000 * frameSize)
            val input = ByteBuffer.wrap(ByteArray(12) + bytes).apply { position(12) }
            while (!sink.handleBuffer(input, 50_000, 1)) assertEquals(12, input.position())
            assertEquals(input.limit(), input.position())
            assertArrayEquals(bytes, fake.written.toByteArray())
            assertTrue(fake.requestedBytes.all { it <= 441 * frameSize })
            assertTrue(fake.timestamps.zipWithNext().all { (a, b) -> a <= b })
            assertEquals(50_000L + 4851L * 1_000_000 / 44100, fake.timestamps.last())
        }
    }
}
