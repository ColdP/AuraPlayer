package btm.m.auraplayer.auramix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class SilenceDetectorTest {
    @Test fun detectsLeadingAndTrailingSilence() {
        val rate = 1000
        val samples = FloatArray(rate * 4) { i ->
            when {
                i < rate -> 0f
                i >= rate * 3 -> 0f
                else -> (sin(i * .2) * .3).toFloat()
            }
        }
        val result = SilenceDetector.analyze(samples, rate, 1)
        assertTrue(result.leadingSilenceMs in 900..1100)
        assertTrue(result.trailingSilenceMs in 900..1100)
        assertTrue(result.confidence > .7)
    }

    @Test fun quietAudioIsNotDroppedWhenItHasNoSilence() {
        val result = SilenceDetector.analyze(FloatArray(2000) { .01f }, 1000, 1)
        assertEquals(0L, result.leadingSilenceMs)
        assertEquals(0L, result.trailingSilenceMs)
        assertTrue(result.rmsDb < -35.0)
    }

    @Test fun veryShortAudioStillReturnsFiniteFeatures() {
        val result = SilenceDetector.analyze(floatArrayOf(0f, .2f, 0f), 1000, 1)
        assertTrue(result.durationMs >= 0)
        assertTrue(result.rmsDb.isFinite())
        assertTrue(result.peakDb.isFinite())
    }

    @Test fun allSilenceHasNoActiveContent() {
        val result = SilenceDetector.analyze(FloatArray(2000), 1000, 1)
        assertTrue(result.leadingSilenceMs > 0)
        assertEquals(result.durationMs, result.contentStartMs)
        assertEquals(0L, result.contentEndMs)
    }
}
