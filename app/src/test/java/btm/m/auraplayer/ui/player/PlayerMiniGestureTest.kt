package btm.m.auraplayer.ui.player

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class PlayerMiniGestureTest {
    @Test fun draggingPastControlFadeThresholdDoesNotLoseProgress() {
        val drag = MiniPlayerDragState()
        drag.begin(MiniPlayerDragAxis.EXPAND, 0f)
        drag.update(Offset(0f, -280f), 700f, 360f)
        assertEquals(.4f, drag.progress, .0001f)
        drag.update(Offset(0f, -210f), 700f, 360f)
        assertEquals(.7f, drag.progress, .0001f)
        assertEquals(MiniPlayerDragAxis.EXPAND, drag.axis)
    }

    @Test fun finishedOrCancelledDragCannotKeepCollapsedPlayerPartiallyExpanded() {
        val drag = MiniPlayerDragState()
        for (height in listOf(56f, 60f)) {
            repeat(10) {
                drag.begin(MiniPlayerDragAxis.EXPAND, 0f)
                drag.update(Offset(0f, -350f), 700f, 360f)
                assertEquals(.5f, drag.progress, .0001f)
                // The animation has taken ownership, then the gesture is released or cancelled.
                drag.reset()
                assertNull(drag.axis)
                assertEquals(0f, drag.progress, 0f)
                val collapsedExpansion = if (drag.axis == MiniPlayerDragAxis.EXPAND) drag.progress else 0f
                assertEquals(height, height + (800f - height) * collapsedExpansion, 0f)
            }
        }
    }

    @Test fun nextGestureStartsCleanAfterVerticalOrHorizontalCancellation() {
        val drag = MiniPlayerDragState()
        drag.begin(MiniPlayerDragAxis.TRACK, 0f)
        drag.update(Offset(-100f, -300f), 700f, 360f)
        assertEquals(-100f, drag.horizontalOffset, 0f)
        assertEquals(0f, drag.progress, 0f)
        drag.begin(MiniPlayerDragAxis.EXPAND, .1f)
        assertEquals(0f, drag.horizontalOffset, 0f)
        drag.update(Offset(0f, -70f), 700f, 360f)
        assertEquals(.2f, drag.progress, .0001f)
        drag.reset()
        drag.begin(MiniPlayerDragAxis.EXPAND, 0f)
        assertEquals(0f, drag.progress, 0f)
    }

    @Test fun reversingDragReturnsToOriginalSizeWithoutResidualFloor() {
        val drag = MiniPlayerDragState()
        drag.begin(MiniPlayerDragAxis.EXPAND, 0f)
        drag.update(Offset(0f, -1000f), 700f, 360f)
        assertEquals(1f, drag.progress, 0f)
        drag.update(Offset(0f, 1000f), 700f, 360f)
        assertEquals(0f, drag.progress, 0f)
    }
}
