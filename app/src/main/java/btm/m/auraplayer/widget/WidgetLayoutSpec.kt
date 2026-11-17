// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.widget

import androidx.annotation.LayoutRes
import androidx.media3.common.C
import androidx.media3.common.Player
import btm.m.auraplayer.R

internal enum class WidgetLayoutTier {
    SIZE_2X2,
    SIZE_4X1,
    SIZE_4X2,
    SIZE_4X3,
    SIZE_4X4,
}

internal fun selectPlayerWidgetLayoutTier(widthDp: Float, heightDp: Float): WidgetLayoutTier {
    val w = widthDp.coerceAtLeast(1f)
    val h = heightDp.coerceAtLeast(1f)
    return when {
        h < 115f && w >= 210f -> WidgetLayoutTier.SIZE_4X1
        w < 210f || (w < 235f && h < 235f && (w / h) < 1.35f) -> WidgetLayoutTier.SIZE_2X2
        h < 230f -> WidgetLayoutTier.SIZE_4X2
        h < 315f -> WidgetLayoutTier.SIZE_4X3
        else -> WidgetLayoutTier.SIZE_4X4
    }
}

@LayoutRes
internal fun layoutResForTier(tier: WidgetLayoutTier): Int = when (tier) {
    WidgetLayoutTier.SIZE_2X2 -> R.layout.widget_player_2x2
    WidgetLayoutTier.SIZE_4X1 -> R.layout.widget_player_4x1
    WidgetLayoutTier.SIZE_4X2 -> R.layout.widget_player_4x2
    WidgetLayoutTier.SIZE_4X3 -> R.layout.widget_player_4x3
    WidgetLayoutTier.SIZE_4X4 -> R.layout.widget_player_4x4
}

internal fun resolveSequentialUpcomingIndices(
    currentIndex: Int,
    itemCount: Int,
    repeatMode: Int,
    limit: Int = 2,
): List<Int> {
    if (itemCount <= 1 || currentIndex !in 0 until itemCount || limit <= 0) return emptyList()
    val wrap = repeatMode != Player.REPEAT_MODE_OFF
    val result = ArrayList<Int>(limit)
    var cursor = currentIndex
    repeat(limit) {
        val next = if (cursor + 1 < itemCount) {
            cursor + 1
        } else if (wrap) {
            0
        } else {
            return result
        }
        if (next == currentIndex || next in result) return result
        result.add(next)
        cursor = next
    }
    return result
}

internal fun resolveTimelineUpcomingIndices(player: Player, limit: Int = 2): List<Int> {
    val count = player.mediaItemCount
    val current = player.currentMediaItemIndex
    val timeline = player.currentTimeline
    if (count <= 1 || current == C.INDEX_UNSET || current !in 0 until count || timeline.isEmpty || limit <= 0) {
        return emptyList()
    }
    val effectiveRepeat = if (player.repeatMode == Player.REPEAT_MODE_ONE) {
        Player.REPEAT_MODE_ALL
    } else {
        player.repeatMode
    }
    val result = ArrayList<Int>(limit)
    var cursor = current
    repeat(limit) {
        val next = timeline.getNextWindowIndex(cursor, effectiveRepeat, player.shuffleModeEnabled)
        if (next == C.INDEX_UNSET || next !in 0 until count || next == current || next in result) {
            return result
        }
        result.add(next)
        cursor = next
    }
    return result
}
