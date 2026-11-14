/*
 * Derived from OneUI_Kit - MIT License
 * Copyright (c) 2026 The One UI Kit Authors
 */
package dev.pascal.oneuikit

import android.content.Context

/**
 * 控件工厂。
 *
 * 将 [OneUiPalette] 的色值注入控件并绑定回调，调用方按界面持有一个实例即可。
 * 色板变更后需重新构造工厂与控件。
 *
 * @param context 上下文。
 * @param palette 当前生效的语义色板。
 */
class OneUiWidgets(
    private val context: Context,
    val palette: OneUiPalette,
) {

    /**
     * 构造开关，并绑定状态变化监听。
     *
     * @param checked 初始选中状态。
     * @param onChange 选中状态变化回调。
     * @return 已注入色板的 [BoSwitchView]。
     */
    fun switchView(checked: Boolean, onChange: (Boolean) -> Unit): BoSwitchView =
        BoSwitchView(
            context = context,
            checked = checked,
            trackOn = palette.accent,
            trackOff = palette.track,
            thumbColor = if (palette.dark) palette.cardAlt else 0xFFFFFFFF.toInt(),
        ).apply {
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    /**
     * 将当前色板同步至已构造的 [BoSwitchView]。
     */
    fun syncSwitch(view: BoSwitchView) {
        view.updateColors(
            trackOn = palette.accent,
            trackOff = palette.track,
            thumbColor = if (palette.dark) palette.cardAlt else 0xFFFFFFFF.toInt(),
        )
    }

    /**
     * 构造带分级的滑杆，绑定拖动与提交回调。
     *
     * @param value 初始进度。
     * @param max 量程上限。
     * @param onLive 拖动过程中的连续回调，用于刷新界面显示。
     * @param onCommit 拖拽结束后的提交回调，用于持久化配置。
     * @return 已注入色板的 [DragOnlySeekBar]。
     */
    fun sliderView(
        value: Int,
        max: Int,
        onLive: (Int) -> Unit = {},
        onCommit: (Int) -> Unit = {},
    ): DragOnlySeekBar = DragOnlySeekBar(context).apply {
        this.max = max
        this.progress = value.coerceIn(0, max)
        this.step = stepFor(max)
        this.onLive = onLive
        this.onCommit = onCommit
        trackColor = palette.track
        fillColor = palette.accent
        ringColor = palette.accent
        maskColor = palette.card
    }

    /**
     * 将当前色板与掩码色同步至已构造的 [DragOnlySeekBar]。
     */
    fun syncSlider(view: DragOnlySeekBar, surfaceColor: Int = palette.card) {
        var changed = false
        if (view.trackColor != palette.track) {
            view.trackColor = palette.track
            changed = true
        }
        if (view.fillColor != palette.accent) {
            view.fillColor = palette.accent
            changed = true
        }
        if (view.ringColor != palette.accent) {
            view.ringColor = palette.accent
            changed = true
        }
        if (view.maskColor != surfaceColor) {
            view.maskColor = surfaceColor
            changed = true
        }
        if (changed) view.invalidate()
    }

    companion object {
        /**
         * 按量程计算分级跨度：量程不小于 40 时返回 5，不小于 12 时返回 2，其余返回 1。
         * 较大量程采用较粗的分级，避免进度被拆分为过密的档位。
         */
        fun stepFor(max: Int): Int = when {
            max >= 40 -> 5
            max >= 12 -> 2
            else -> 1
        }
    }
}
