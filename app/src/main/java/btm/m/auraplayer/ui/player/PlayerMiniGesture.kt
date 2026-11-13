// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

internal enum class MiniPlayerDragAxis { TRACK, EXPAND }

/** Gesture-only values. Ownership transfers to the expansion animation before reset. */
@Stable
internal class MiniPlayerDragState {
    var axis by mutableStateOf<MiniPlayerDragAxis?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var horizontalOffset by mutableFloatStateOf(0f)
        private set
    private var origin = 0f
    private var distanceY = 0f

    fun begin(axis: MiniPlayerDragAxis, expansion: Float) {
        reset()
        this.axis = axis
        origin = expansion.coerceIn(0f, 1f)
        progress = origin
    }

    fun update(delta: Offset, range: Float, width: Float) {
        when (axis) {
            MiniPlayerDragAxis.EXPAND -> {
                distanceY += delta.y
                progress = (origin - distanceY / range.coerceAtLeast(1f)).coerceIn(0f, 1f)
            }
            MiniPlayerDragAxis.TRACK -> {
                val limit = width.coerceAtLeast(1f)
                horizontalOffset = (horizontalOffset + delta.x).coerceIn(-limit, limit)
            }
            null -> Unit
        }
    }

    fun reset() {
        axis = null
        progress = 0f
        horizontalOffset = 0f
        origin = 0f
        distanceY = 0f
    }
}

/** Lives on the morphing container, not on controls removed partway through expansion. */
@Composable
internal fun Modifier.playerMiniDragGesture(
    enabled: Boolean,
    allowTrackSwipe: Boolean,
    onStart: (MiniPlayerDragAxis) -> Unit,
    onDelta: (Offset) -> Unit,
    onEnd: (Boolean) -> Unit,
): Modifier {
    val canStart by rememberUpdatedState(enabled)
    val canSwipeTracks by rememberUpdatedState(allowTrackSwipe)
    val startCallback by rememberUpdatedState(onStart)
    val deltaCallback by rememberUpdatedState(onDelta)
    val endCallback by rememberUpdatedState(onEnd)
    // No open/size keys: changing the morph must not cancel the gesture driving it.
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!canStart) return@awaitEachGesture
            var axis: MiniPlayerDragAxis? = null
            var initialDelta = Offset.Zero
            val start = awaitTouchSlopOrCancellation(down.id) { change, delta ->
                axis = when {
                    abs(delta.x) > abs(delta.y) && canSwipeTracks -> MiniPlayerDragAxis.TRACK
                    abs(delta.y) >= abs(delta.x) && delta.y < 0f -> MiniPlayerDragAxis.EXPAND
                    else -> null
                }
                if (axis != null) {
                    initialDelta = delta
                    change.consume()
                }
            } ?: return@awaitEachGesture
            var completed = false
            try {
                startCallback(axis ?: return@awaitEachGesture)
                deltaCallback(initialDelta)
                val released = drag(start.id) { change ->
                    deltaCallback(change.position - change.previousPosition)
                    change.consume()
                }
                completed = true
                endCallback(released)
            } finally {
                // Includes pointer coroutine cancellation when the view leaves composition.
                if (!completed) endCallback(false)
            }
        }
    }
}
