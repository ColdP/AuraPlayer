// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.rear

import android.os.Build

data class RearScreenProfile(
    val deviceCode: String,
    val marketName: String,
    val diagonalInches: Float,
    val widthPx: Int,
    val heightPx: Int,
)

data class RearCutoutRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class RearSafeInsetsPx(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

object RearScreenDevice {
    const val REQUIRED_BRAND = "xiaomi"

    val supportedProfiles: List<RearScreenProfile> = listOf(
        RearScreenProfile(
            deviceCode = "pandora",
            marketName = "Xiaomi 17 Pro",
            diagonalInches = 2.7f,
            widthPx = 904,
            heightPx = 572,
        ),
        RearScreenProfile(
            deviceCode = "popsicle",
            marketName = "Xiaomi 17 Pro Max",
            diagonalInches = 2.9f,
            widthPx = 976,
            heightPx = 596,
        ),
        RearScreenProfile(
            deviceCode = "hongkong",
            marketName = "Xiaomi 18 Pro",
            diagonalInches = 2.7f,
            widthPx = 912,
            heightPx = 596,
        ),
        RearScreenProfile(
            deviceCode = "madrid",
            marketName = "Xiaomi 18 Pro Max",
            diagonalInches = 2.9f,
            widthPx = 976,
            heightPx = 596,
        ),
    )

    private val profileByCode: Map<String, RearScreenProfile> =
        supportedProfiles.associateBy { it.deviceCode }

    fun findProfile(
        brand: String = Build.BRAND,
        device: String = Build.DEVICE,
    ): RearScreenProfile? {
        if (!brand.trim().equals(REQUIRED_BRAND, ignoreCase = true)) return null
        return profileByCode[device.trim().lowercase()]
    }

    fun isSupported(
        brand: String = Build.BRAND,
        device: String = Build.DEVICE,
    ): Boolean = findProfile(brand, device) != null

    fun formatDeviceLabel(
        brand: String = Build.BRAND,
        device: String = Build.DEVICE,
        model: String = Build.MODEL,
    ): String {
        val code = device.trim()
        val profile = findProfile(brand, code)
        val displayName = profile?.marketName
            ?: model.trim().ifEmpty { brand.trim().ifEmpty { "Xiaomi" } }
        return if (code.isNotEmpty()) "$displayName ($code)" else displayName
    }

    /**
     * Resolves safe content insets around rear camera cutouts.
     *
     * Background layers and visual effects still render edge-to-edge under the cutout;
     * interactive and textual content respects [RearSafeInsetsPx].
     */
    fun computeSafeInsetsPx(
        screenWidthPx: Int,
        screenHeightPx: Int,
        safeLeft: Int,
        safeTop: Int,
        safeRight: Int,
        safeBottom: Int,
        boundingRects: List<RearCutoutRect>,
        extraMarginPx: Int = 0,
    ): RearSafeInsetsPx {
        var left = safeLeft.coerceAtLeast(0)
        var top = safeTop.coerceAtLeast(0)
        var right = safeRight.coerceAtLeast(0)
        var bottom = safeBottom.coerceAtLeast(0)

        val edgeTolerance = 6
        val halfW = screenWidthPx / 2
        val halfH = screenHeightPx / 2
        if (screenWidthPx > 0 && screenHeightPx > 0) {
            for (rect in boundingRects) {
                val width = (rect.right - rect.left).coerceAtLeast(0)
                val height = (rect.bottom - rect.top).coerceAtLeast(0)
                if (width == 0 || height == 0) continue

                val touchesLeft = rect.left <= edgeTolerance && rect.right < halfW
                val touchesRight = rect.right >= screenWidthPx - edgeTolerance && rect.left > halfW
                val touchesTop = rect.top <= edgeTolerance && rect.bottom < halfH
                val touchesBottom = rect.bottom >= screenHeightPx - edgeTolerance && rect.top > halfH

                when {
                    touchesLeft && height >= width -> left = maxOf(left, rect.right.coerceAtLeast(0))
                    touchesRight && height >= width -> right = maxOf(right, (screenWidthPx - rect.left).coerceAtLeast(0))
                    touchesTop && left == 0 && right == 0 -> {
                        if (rect.right <= halfW && height > rect.bottom * 0.4f) {
                            left = maxOf(left, rect.right.coerceAtLeast(0))
                        } else if (rect.left >= halfW && height > rect.bottom * 0.4f) {
                            right = maxOf(right, (screenWidthPx - rect.left).coerceAtLeast(0))
                        } else {
                            top = maxOf(top, rect.bottom.coerceAtLeast(0))
                        }
                    }
                    touchesBottom && left == 0 && right == 0 -> bottom = maxOf(bottom, (screenHeightPx - rect.top).coerceAtLeast(0))
                }
            }
        }

        val pad = extraMarginPx.coerceAtLeast(0)
        return RearSafeInsetsPx(
            left = left + pad,
            top = top + pad,
            right = right + pad,
            bottom = bottom + pad,
        )
    }
}
