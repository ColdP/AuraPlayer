package btm.m.auraplayer.audio

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class EffectsAudioBufferSizeProviderTest {
    @Test fun pcmBudgetDoesNotGrowToSecondsWhenPlatformSpeedControlReservesEightTimesCapacity() {
        val provider = EffectsAudioBufferSizeProvider()
        val default = DefaultAudioTrackBufferSizeProvider.Builder().build()
        val rate = 48000; val frameSize = 8; val minBytes = 9600
        val optimized = provider.getBufferSizeInBytes(minBytes, C.ENCODING_PCM_FLOAT, DefaultAudioSink.OUTPUT_MODE_PCM, frameSize, rate, -1, 8.0)
        val before = default.getBufferSizeInBytes(minBytes, C.ENCODING_PCM_FLOAT, DefaultAudioSink.OUTPUT_MODE_PCM, frameSize, rate, -1, 8.0)
        assertEquals(rate * frameSize / 10, optimized)
        assertEquals(rate * frameSize * 2, before)
        assertTrue(optimized < before / 10)
        assertEquals(optimized, provider.getBufferSizeInBytes(minBytes, C.ENCODING_PCM_FLOAT, DefaultAudioSink.OUTPUT_MODE_PCM, frameSize, rate, -1, 1.0))
    }
    @Test fun platformMinimumAndFrameAlignmentAreAlwaysHonored() {
        val provider = EffectsAudioBufferSizeProvider()
        for (rate in listOf(8000, 44100, 48000, 96000, 192000)) for (frameSize in listOf(2, 4, 6, 8, 24)) for (minBytes in listOf(100, 10001, 200003)) {
            val bytes = provider.getBufferSizeInBytes(minBytes, C.ENCODING_PCM_16BIT, DefaultAudioSink.OUTPUT_MODE_PCM, frameSize, rate, -1, 8.0)
            assertTrue(bytes >= minBytes)
            assertEquals(0, bytes % frameSize)
            // 150 ms budget can be exceeded only to satisfy the device's minimum buffer size.
            val bound = maxOf(minBytes, (rate * .15 * frameSize).toInt()) + frameSize
            assertTrue("$rate/$frameSize/$minBytes: $bytes > $bound", bytes <= bound)
        }
    }
}
