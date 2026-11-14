package btm.m.auraplayer.audio

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class OutputWaveformTest {
    @Test fun readsAllPcmEncodingsWithoutChangingBufferOrByteOrder() {
        for (encoding in AuraAudioProcessor.encodings) {
            val bigEndian = encoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN
            val pcm = ByteBuffer.allocate(4000 * 4).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
            repeat(2000) {
                for (sign in listOf(-1, 1)) when (encoding) {
                    C.ENCODING_PCM_8BIT -> pcm.put((128 + sign * 64).toByte())
                    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> pcm.putShort((sign * 16384).toShort())
                    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                        val value = sign * 4194304
                        if (bigEndian) { pcm.put((value shr 16).toByte()); pcm.put((value shr 8).toByte()); pcm.put(value.toByte()) }
                        else { pcm.put(value.toByte()); pcm.put((value shr 8).toByte()); pcm.put((value shr 16).toByte()) }
                    }
                    C.ENCODING_PCM_FLOAT -> pcm.putFloat(sign * .5f)
                    else -> pcm.putInt(sign * 1073741824)
                }
            }
            pcm.flip()
            val original = pcm.array().copyOf()
            val order = pcm.order(); val limit = pcm.limit()
            val waveform = OutputWaveform().apply { enabled = true }
            waveform.offer(pcm, encoding, 2, 48000)
            val peaks = waveform.snapshot!!.peaks
            assertEquals(-.5f, peaks.min(), 0f)
            assertEquals(.5f, peaks.max(), 0f)
            assertEquals(0, pcm.position()); assertEquals(limit, pcm.limit()); assertEquals(order, pcm.order())
            assertArrayEquals(original, pcm.array())
        }
    }

    @Test fun captureIsOptInAndClearsWhenDisabledOrReset() {
        val pcm = ByteBuffer.allocate(9600).order(ByteOrder.LITTLE_ENDIAN)
        repeat(2400) { pcm.putFloat(.5f) }; pcm.flip()
        val waveform = OutputWaveform()
        waveform.offer(pcm, C.ENCODING_PCM_FLOAT, 1, 48000)
        assertNull(waveform.snapshot)
        waveform.enabled = true
        waveform.offer(pcm, C.ENCODING_PCM_FLOAT, 1, 48000)
        assertNotNull(waveform.snapshot)
        waveform.enabled = false
        waveform.offer(pcm, C.ENCODING_PCM_FLOAT, 1, 48000)
        assertNull(waveform.snapshot)
        waveform.enabled = true
        waveform.offer(pcm, C.ENCODING_PCM_FLOAT, 1, 48000)
        waveform.reset()
        assertNull(waveform.snapshot)
    }

    @Test fun snapshotsAreImmutableAndStereoDoesNotCancelOppositePhase() {
        val pcm = ByteBuffer.allocate(19200).order(ByteOrder.LITTLE_ENDIAN)
        repeat(2400) { pcm.putFloat(-.6f); pcm.putFloat(.6f) }; pcm.flip()
        val waveform = OutputWaveform().apply { enabled = true }
        waveform.offer(pcm, C.ENCODING_PCM_FLOAT, 2, 48000)
        val first = waveform.snapshot!!
        val saved = first.peaks.copyOf()
        val silence = ByteBuffer.allocate(19200)
        waveform.offer(silence, C.ENCODING_PCM_FLOAT, 2, 48000)
        assertArrayEquals(saved, first.peaks, 0f)
        assertEquals(-.6f, first.peaks.min(), 0f)
        assertEquals(.6f, first.peaks.max(), 0f)
        assertNotSame(first, waveform.snapshot)
    }

    @Test fun spectrumSeparatesLowAndHighFrequenciesAndClearsWhenInactive() {
        val sampleRate = 48000
        val frames = 2400
        fun sinePcm(freqHz: Double): ByteBuffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 0 until frames) {
                putFloat((0.6 * kotlin.math.sin(2.0 * kotlin.math.PI * freqHz * i / sampleRate)).toFloat())
            }
            flip()
        }

        val waveform = OutputWaveform().apply { spectrumEnabled = true }
        waveform.offer(sinePcm(80.0), C.ENCODING_PCM_FLOAT, 1, sampleRate)
        val bassSpectrum = waveform.snapshot!!.spectrum
        assertEquals(OutputWaveform.SPECTRUM_BANDS, bassSpectrum.size)
        assertTrue(bassSpectrum[0] + bassSpectrum[1] > bassSpectrum[6] + bassSpectrum[7] + 0.15f)

        waveform.offer(sinePcm(6000.0), C.ENCODING_PCM_FLOAT, 1, sampleRate)
        val trebleSpectrum = waveform.snapshot!!.spectrum
        assertTrue(trebleSpectrum[6] + trebleSpectrum[7] > trebleSpectrum[0] + trebleSpectrum[1] + 0.15f)

        waveform.spectrumEnabled = false
        waveform.offer(sinePcm(80.0), C.ENCODING_PCM_FLOAT, 1, sampleRate)
        assertNull(waveform.snapshot)
    }
}
