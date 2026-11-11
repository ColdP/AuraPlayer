// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.min

/** Distance and velocity are expressed in dp so dismissal feels the same across densities. */
internal object PlayerDismissGesture {
    fun shouldDismiss(distance: Float, velocity: Float, height: Float): Boolean =
        distance >= min(112f, height * .16f) || (distance >= 16f && velocity >= 1000f)
}

@Composable
internal fun Modifier.playerDismissGesture(
    enabled: Boolean,
    // In lyrics mode, the body belongs to the native lyric scroller.
    headerOnly: Boolean,
    headerBottom: Float,
    onStart: () -> Unit,
    onDelta: (Float) -> Unit,
    onRelease: (Float) -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val startCallback by rememberUpdatedState(onStart)
    val deltaCallback by rememberUpdatedState(onDelta)
    val releaseCallback by rememberUpdatedState(onRelease)
    val cancelCallback by rememberUpdatedState(onCancel)
    return pointerInput(enabled, headerOnly, headerBottom) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (headerOnly && down.position.y > headerBottom) return@awaitEachGesture
            var firstDelta = 0f
            val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (overSlop > 0f) {
                    firstDelta = overSlop
                    change.consume()
                }
            } ?: return@awaitEachGesture
            val velocity = VelocityTracker()
            var trackedY = start.position.y - down.position.y
            velocity.addPosition(down.uptimeMillis, Offset.Zero)
            velocity.addPosition(start.uptimeMillis, Offset(0f, trackedY))
            var completed = false
            startCallback()
            try {
                deltaCallback(firstDelta)
                val released = verticalDrag(start.id) { change ->
                    val delta = change.position.y - change.previousPosition.y
                    trackedY += delta
                    velocity.addPosition(change.uptimeMillis, Offset(0f, trackedY))
                    deltaCallback(delta)
                    change.consume()
                }
                velocity.addPosition(android.os.SystemClock.uptimeMillis(), Offset(0f, trackedY))
                completed = true
                if (released) releaseCallback(velocity.calculateVelocity().y) else cancelCallback()
            } finally {
                if (!completed) cancelCallback()
            }
        }
    }
}
