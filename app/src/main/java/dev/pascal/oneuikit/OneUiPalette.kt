/*
 * Derived from OneUI_Kit - MIT License
 * Copyright (c) 2026 The One UI Kit Authors
 */
package dev.pascal.oneuikit

import android.content.Context
import android.content.res.Configuration

/**
 * One UI 语义色板。
 *
 * 由种子色派生页面底、卡片、文字与强调色等一整套颜色。控件仅依赖本类型提供的字段，
 * 因此切换调色板或深浅模式时只需重新构造色板并重建视图，无需修改控件实现。
 *
 * @param context 上下文。
 * @param forceDark 显式指定深浅模式；为 `null` 时跟随系统夜间模式。
 * @param useMonet 是否启用动态取色。三星设备读取 `Settings.Secure`，其他设备回落至
 * AOSP Monet；取色链路失败或设为 `false` 时使用内置固定配色。
 * @param customSeed 可选外部种子色；非空且非 0 时优先按该种子色生成调色板。
 */
class OneUiPalette(
    context: Context,
    forceDark: Boolean? = null,
    useMonet: Boolean = true,
    customSeed: Int? = null,
) {
    /** 当前色板是否为深色模式。显式指定优先，否则取自系统夜间模式。 */
    val dark: Boolean = forceDark
        ?: ((context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)

    /** 页面底色。 */
    val background: Int

    /** 卡片表面色。 */
    val card: Int

    /** 次表面色，用于输入框等次级容器。 */
    val cardAlt: Int

    /** 主文字色。 */
    val text: Int

    /** 次文字色。 */
    val subText: Int

    /** 强调色，取种子色原值。 */
    val accent: Int

    /** 强调色深调，用于浅色模式下的文字与实心按钮。 */
    val accentDeep: Int

    /** 强调色表面上的前景色。 */
    val onAccent: Int

    /** 关闭态轨道色。 */
    val track: Int

    /** 分割线色。 */
    val divider: Int

    /** 图标底板色。 */
    val chip: Int

    /** 图标底板上的前景色。 */
    val onChip: Int

    /** 分段控件轨道色。 */
    val segmentTrack: Int

    /** 分段控件选中块色。 */
    val segmentThumb: Int

    init {
        val monet = when {
            !useMonet -> null
            customSeed != null && customSeed != 0 -> MonetTones.fromSeed(customSeed, dark)
            else -> MonetTones.from(context, dark)
        }
        if (monet != null) {
            background = monet.background
            card = monet.card
            cardAlt = monet.cardAlt
            text = monet.text
            subText = monet.subText
            accent = monet.accent
            accentDeep = monet.accentDeep
            onAccent = monet.onAccent
            track = monet.track
            divider = monet.divider
            chip = monet.chip
            onChip = monet.onChip
            segmentTrack = monet.segmentTrack
            segmentThumb = monet.segmentThumb
        } else if (dark) {
            // 固定配色：无法取得种子色或关闭动态取色时使用
            background = 0xFF000000.toInt()
            card = 0xFF1B1B1B.toInt()
            cardAlt = 0xFF242424.toInt()
            text = 0xFFF2F2F2.toInt()
            subText = 0xFF9A9A9A.toInt()
            accent = 0xFF4C9DFF.toInt()
            accentDeep = 0xFF4C9DFF.toInt()
            onAccent = 0xFF06233F.toInt()
            track = 0xFF2E2E2E.toInt()
            divider = 0xFF2A2A2A.toInt()
            chip = 0xFF243A55.toInt()
            onChip = 0xFF9CC6FF.toInt()
            segmentTrack = 0xFF292929.toInt()
            segmentThumb = 0xFF3C3C3C.toInt()
        } else {
            background = 0xFFF2F2F2.toInt()
            card = 0xFFFFFFFF.toInt()
            cardAlt = 0xFFF7F7F7.toInt()
            text = 0xFF101010.toInt()
            subText = 0xFF6E6E6E.toInt()
            accent = 0xFF0381FE.toInt()
            accentDeep = 0xFF0B63C7.toInt()
            onAccent = 0xFFFFFFFF.toInt()
            track = 0xFFE6E6E6.toInt()
            divider = 0xFFEDEDED.toInt()
            chip = 0xFFE6F0FF.toInt()
            onChip = 0xFF0A5FCC.toInt()
            segmentTrack = 0xFFE9E9E9.toInt()
            segmentThumb = 0xFFFFFFFF.toInt()
        }
    }
}
