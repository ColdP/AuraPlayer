package btm.m.auraplayer.ui.player

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
internal fun Modifier.playerTrackSwipe(enabled: Boolean, next: () -> Unit, previous: () -> Unit): Modifier {
    val onNext by rememberUpdatedState(next); val onPrevious by rememberUpdatedState(previous)
    return pointerInput(enabled) {
        if (!enabled) return@pointerInput
        var delta = 0f
        detectHorizontalDragGestures(onDragStart = { delta = 0f }, onDragCancel = { delta = 0f },
            onDragEnd = {
                if (abs(delta) >= 56.dp.toPx()) { if (delta < 0f) onNext() else onPrevious() }
                delta = 0f
            }) { change, amount -> change.consume(); delta += amount }
    }
}
