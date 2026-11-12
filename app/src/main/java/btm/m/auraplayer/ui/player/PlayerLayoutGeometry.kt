// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Compute once for both the cover position and the controls' additional inset. */
internal fun playerCoverInset(width: Dp, coverSize: Dp, outerMargin: Dp): Dp =
    ((width - coverSize) / 2 - outerMargin).coerceAtLeast(0.dp)

internal data class FullscreenPlayerCard(val left: Dp, val top: Dp, val width: Dp, val height: Dp)

/** Landscape 4:3, bottom-centered, with a readable cover area above and bounded tablet width. */
internal fun fullscreenPlayerCard(width: Dp, height: Dp, status: Dp, navigation: Dp): FullscreenPlayerCard {
    val availableHeight = (height - status - navigation - 64.dp).coerceAtLeast(1.dp)
    val cardWidth = minOf((width - 28.dp).coerceAtLeast(1.dp), 520.dp, availableHeight * (4f / 3f))
    val cardHeight = cardWidth * .75f
    return FullscreenPlayerCard((width - cardWidth) / 2, height - navigation - 14.dp - cardHeight, cardWidth, cardHeight)
}
