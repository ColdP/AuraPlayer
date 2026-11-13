package btm.m.auraplayer.auramix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuraMixModelsTest {
    private fun feature(duration: Long, head: Long = 0, tail: Long = 0, confidence: Double = 1.0) =
        AudioFeatures(duration, head, duration - tail, head, tail, -14.0, -1.0, confidence)

    @Test fun smartUsesContentBoundaries() {
        val plan = TransitionPlanner.plan(AuraMixMode.SMART, 1200, feature(120000, tail = 400), feature(180000, head = 250))
        assertTrue(plan.enabled)
        assertTrue(plan.overlapMs in 240..1200)
    }

    @Test fun unknownOrShortTracksCut() {
        assertEquals(TransitionStrategy.CUT, TransitionPlanner.plan(AuraMixMode.SMART, 1000, null, feature(20000)).strategy)
        assertEquals(TransitionStrategy.CUT, TransitionPlanner.plan(AuraMixMode.SMART, 1000, feature(1000), feature(20000)).strategy)
    }

    @Test fun traditionalKeepsRequestedDuration() {
        val plan = TransitionPlanner.plan(AuraMixMode.TRADITIONAL, 900, null, null)
        assertEquals(900, plan.overlapMs)
    }

    @Test fun equalPowerEndpointsAreStable() {
        val start = CrossfadeCurve.gains(0.0)
        val end = CrossfadeCurve.gains(1.0)
        assertEquals(1.0, start.first, .0001)
        assertEquals(0.0, start.second, .0001)
        assertEquals(0.0, end.first, .0001)
        assertEquals(1.0, end.second, .0001)
    }
}
