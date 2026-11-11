// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Compute once for both the cover position and the controls' additional inset. */
internal fun playerCoverInset(width: Dp, coverSize: Dp, outerMargin: Dp): Dp =
    ((width - coverSize) / 2 - outerMargin).coerceAtLeast(0.dp)
