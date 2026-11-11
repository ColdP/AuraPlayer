package btm.m.auraplayer.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlayerDismissGestureTest {
    @Test fun smallAccidentalDragDoesNotDismiss() {
        assertFalse(PlayerDismissGesture.shouldDismiss(12f, 1800f, 800f))
        assertFalse(PlayerDismissGesture.shouldDismiss(70f, 0f, 800f))
    }

    @Test fun intentionalDownwardFlingDismisses() {
        assertTrue(PlayerDismissGesture.shouldDismiss(35f, 1400f, 800f))
        assertFalse(PlayerDismissGesture.shouldDismiss(35f, -1400f, 800f))
    }

    @Test fun slowDragWorksWithoutVelocityAndAdaptsToShortScreens() {
        assertTrue(PlayerDismissGesture.shouldDismiss(115f, 0f, 900f))
        assertTrue(PlayerDismissGesture.shouldDismiss(100f, 0f, 600f))
        assertFalse(PlayerDismissGesture.shouldDismiss(90f, 0f, 600f))
    }
}
