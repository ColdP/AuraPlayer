package btm.m.auraplayer.ui.navigation
object NavigationScaleSettings {
    const val MIN = -10000
    const val MAX = 10000
    data class Settings(
        val widthEnabled: Boolean = false,
        val widthHundredths: Int = 0,
        val heightEnabled: Boolean = false,
        val heightHundredths: Int = 0,
    ) {
        val widthFactor: Float get() = factor(widthEnabled, widthHundredths)
        val heightFactor: Float get() = factor(heightEnabled, heightHundredths)
        fun normalized() = copy(widthHundredths = widthHundredths.coerceIn(MIN, MAX),
            heightHundredths = heightHundredths.coerceIn(MIN, MAX))
    }


    private fun factor(enabled: Boolean, value: Int) = if (enabled) 1f + value.coerceIn(MIN, MAX) / 10000f else 1f
    fun supports(style: NavigationStyle) = style != NavigationStyle.HYPER_OS
}
object NavigationCornerSettings {
    data class Settings(val enabled: Boolean = false, val value: Int = 0) {
        val radiusFactor: Float get() = if (enabled) 1f + value.coerceIn(-96, 0) / 96f else 1f
    }
    fun supports(style: NavigationStyle) = NavigationScaleSettings.supports(style)
}
