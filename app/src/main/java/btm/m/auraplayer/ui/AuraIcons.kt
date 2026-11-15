package btm.m.auraplayer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Shared horizontal three-dot overflow icon matching Apple Music / MIUIX action triggers.
 */
internal val AuraMoreHorizontalIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "AuraMoreHorizontal",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        val brush = SolidColor(Color.Black)
        val dotXPositions = floatArrayOf(5f, 12f, 19f)
        val dotY = 12f
        val radius = 1.85f
        for (cx in dotXPositions) {
            path(fill = brush) {
                moveTo(cx - radius, dotY)
                arcTo(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = false, cx + radius, dotY)
                arcTo(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = false, cx - radius, dotY)
                close()
            }
        }
    }.build()
}
