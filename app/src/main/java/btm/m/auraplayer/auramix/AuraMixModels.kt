package btm.m.auraplayer.auramix

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI

/** The transition modes exposed by AuraMix. OFF preserves Media3's normal item transition. */
enum class AuraMixMode { OFF, TRADITIONAL, SMART }

data class AudioFeatures(
    val durationMs: Long,
    val contentStartMs: Long,
    val contentEndMs: Long,
    val leadingSilenceMs: Long,
    val trailingSilenceMs: Long,
    val rmsDb: Double,
    val peakDb: Double,
    val confidence: Double,
) {
    val analyzable: Boolean get() = confidence >= .45 && durationMs > 0
}

enum class TransitionStrategy { CUT, CROSSFADE }

data class TransitionPlan(
    val strategy: TransitionStrategy,
    val overlapMs: Long,
    val fadeOutMs: Long,
    val fadeInMs: Long,
    val reason: String,
    val fadeStartMs: Long? = null,
    val fadeEndMs: Long? = null,
    val nextStartMs: Long = 0L,
) {
    val enabled get() = strategy == TransitionStrategy.CROSSFADE && overlapMs > 0
    companion object { val CUT = TransitionPlan(TransitionStrategy.CUT, 0, 0, 0, "cut") }
}

/** Equal-power gains keep perceived loudness steadier than a linear ramp. */
object CrossfadeCurve {
    fun gains(progress: Double): Pair<Double, Double> {
        val p = progress.coerceIn(0.0, 1.0)
        val fadeOut = cos(p * PI * .5)
        val fadeIn = cos((1.0 - p) * PI * .5)
        return fadeOut to fadeIn
    }
}

object TransitionPlanner {
    fun plan(mode: AuraMixMode, configuredMs: Long, current: AudioFeatures?, next: AudioFeatures?): TransitionPlan {
        if (mode == AuraMixMode.OFF) return TransitionPlan.CUT
        val configured = configuredMs.coerceIn(120L, 12_000L)
        if (mode == AuraMixMode.TRADITIONAL) {
            val start = (current?.durationMs?.minus(configured) ?: 0L).coerceAtLeast(0L)
            return TransitionPlan(TransitionStrategy.CROSSFADE, configured, configured, configured, "traditional", start,
                current?.durationMs, 0L)
        }
        if (current == null || next == null || !current.analyzable || !next.analyzable) return TransitionPlan.CUT
        if (current.durationMs < 2_000 || next.durationMs < 1_500) return TransitionPlan.CUT

        // Use real content boundaries. Silent tails/heads are excluded from the overlap so a
        // quiet ending does not get mixed with a quiet beginning and create a perceived dropout.
        val availableCurrent = current.contentEndMs.coerceAtLeast(0L)
        val availableNext = (next.durationMs - next.contentStartMs).coerceAtLeast(0L)
        val silenceWindow = (current.trailingSilenceMs + next.leadingSilenceMs).coerceAtMost(3_200L)
        val effectiveConfigured = max(configured, silenceWindow)
        val overlap = min(effectiveConfigured, min(availableCurrent, availableNext))
        if (overlap < 240L) return TransitionPlan.CUT
        val fadeOut = min(overlap, max(240L, overlap))
        val fadeIn = min(overlap, max(240L, overlap))
        val fadeEnd = current.contentEndMs.coerceIn(0L, current.durationMs)
        val fadeStart = (fadeEnd - fadeOut).coerceAtLeast(current.contentStartMs)
        val actualFade = (fadeEnd - fadeStart).coerceAtLeast(0L)
        if (actualFade < 240L) return TransitionPlan.CUT
        // The next decoder starts at the first active frame. Starting earlier would reintroduce
        // the detected head silence into the overlap and make the transition sound delayed.
        val nextStart = next.contentStartMs.coerceIn(0L, next.durationMs)
        return TransitionPlan(TransitionStrategy.CROSSFADE, actualFade, actualFade, fadeIn,
            "content-boundaries", fadeStart, fadeEnd, nextStart)
    }
}
