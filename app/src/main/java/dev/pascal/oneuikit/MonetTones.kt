/*
 * Derived from OneUI_Kit (https://github.com/...) - MIT License
 * Copyright (c) 2026 The One UI Kit Authors
 */
package dev.pascal.oneuikit

import android.content.Context
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * One UI 语义色生成。
 *
 * 强调色取自 `Settings.Secure` 中 `theme_customization_overlay_packages` 的
 * `android.theme.customization.accent_color`，该值与系统设置中开关、滑杆的颜色一致。
 *
 * 中性表面色不读取 `system_neutral*` 色位。三星中性色的编号方向与 AOSP 相反
 * （`neutral1_10` 接近白色），按 AOSP 惯例取色会得到整体偏白的表面。本类改为使用
 * 种子色色相与显式 CIELAB 明度生成表面色，明度参数依据系统设置的实测值标定。
 * 生成规则参见 `docs/palette.md`。
 */
class MonetTones(
    val background: Int,
    val card: Int,
    val cardAlt: Int,
    val text: Int,
    val subText: Int,
    val divider: Int,
    val accent: Int,
    /** 强调色深调，用于浅色模式下的文字与实心按钮，保证与白色文字的对比度。 */
    val accentDeep: Int,
    val onAccent: Int,
    val chip: Int,
    val onChip: Int,
    val track: Int,
    val segmentTrack: Int,
    val segmentThumb: Int,
) {
    companion object {

        /** 非三星设备（Android 12 及以上）的取色入口：AOSP Monet 强调色。 */
        private fun aospSeed(context: Context): Int {
            val id = context.resources.getIdentifier("system_accent1_500", "color", "android")
            if (id == 0) return 0
            return runCatching { context.getColor(id) }.getOrDefault(0)
        }

        /**
         * 读取种子色。取值优先级：
         * 1. 三星调色板的 `accent_color`；
         * 2. AOSP Monet 的 `system_accent1_500`；
         * 3. 0，表示取色链路全部失败，由调用方使用固定配色。
         *
         * 解析异常统一进入回落链，不抛出异常。
         */
        fun seedOf(context: Context): Int {
            val raw = rawSetting(context) ?: return aospSeed(context)
            val hex = runCatching {
                org.json.JSONObject(raw)
                    .optString("android.theme.customization.accent_color")
            }.getOrNull()
            if (hex.isNullOrBlank()) return aospSeed(context)
            val v = hex.replace("#", "").trim()
            return runCatching {
                when (v.length) {
                    8 -> v.toLong(16).toInt()
                    6 -> (0xFF000000L or v.toLong(16)).toInt()
                    else -> 0
                }
            }.getOrDefault(aospSeed(context))
        }

        /** 返回原始 JSON 字符串；其中 `_applied_timestamp` 可用于判断调色板是否变更。 */
        fun rawSetting(context: Context): String? = runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                "theme_customization_overlay_packages",
            )
        }.getOrNull()

        private fun red(c: Int): Int = (c ushr 16) and 0xFF
        private fun green(c: Int): Int = (c ushr 8) and 0xFF
        private fun blue(c: Int): Int = c and 0xFF
        private fun rgb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

        // CIELAB 色空间转换

        private fun toLinear(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }

        private fun toSrgb(v: Double): Double {
            val c = v.coerceIn(0.0, 1.0)
            return if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
        }

        private fun labF(t: Double): Double =
            if (t > 0.008856) t.pow(1.0 / 3.0) else 7.787 * t + 16.0 / 116.0

        private fun labInv(t: Double): Double {
            val t3 = t * t * t
            return if (t3 > 0.008856) t3 else (t - 16.0 / 116.0) / 7.787
        }

        /** 种子色的 LAB 色相角（弧度），用于生成带少量色调的中性色。 */
        private fun hueOf(color: Int): Double {
            val r = toLinear(red(color))
            val g = toLinear(green(color))
            val b = toLinear(blue(color))
            val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
            val y = r * 0.2126 + g * 0.7152 + b * 0.0722
            val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
            val a = 500.0 * (labF(x) - labF(y))
            val bb = 200.0 * (labF(y) - labF(z))
            return atan2(bb, a)
        }

        /** 种子色的彩度 C*，用于判断其是否适合直接作为强调色。 */
        private fun chromaOf(color: Int): Double {
            val r = toLinear(red(color))
            val g = toLinear(green(color))
            val b = toLinear(blue(color))
            val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
            val y = r * 0.2126 + g * 0.7152 + b * 0.0722
            val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
            val a = 500.0 * (labF(x) - labF(y))
            val bb = 200.0 * (labF(y) - labF(z))
            return hypot(a, bb)
        }

        /** 将 LAB 分量转换为不透明 ARGB 颜色。 */
        private fun labToColor(l: Double, a: Double, b: Double): Int {
            val fy = (l + 16.0) / 116.0
            val fx = fy + a / 500.0
            val fz = fy - b / 200.0
            val x = 0.95047 * labInv(fx)
            val y = labInv(fy)
            val z = 1.08883 * labInv(fz)
            val r = x * 3.2406 + y * -1.5372 + z * -0.4986
            val g = x * -0.9689 + y * 1.8758 + z * 0.0415
            val bb = x * 0.0557 + y * -0.2040 + z * 1.0570
            return rgb(
                (toSrgb(r) * 255.0).toInt().coerceIn(0, 255),
                (toSrgb(g) * 255.0).toInt().coerceIn(0, 255),
                (toSrgb(bb) * 255.0).toInt().coerceIn(0, 255),
            )
        }

        /** 以指定明度与彩度沿种子色相生成颜色；彩度较低时得到带少量色调的中性色。 */
        private fun tone(seed: Int, lightness: Double, chroma: Double): Int {
            val h = hueOf(seed)
            val c = chroma.coerceAtLeast(0.0)
            return labToColor(lightness, c * cos(h), c * sin(h))
        }

        /** 按 [ratio] 混合两个颜色；仅作为通用工具，不参与语义色板生成。 */
        fun mix(base: Int, over: Int, ratio: Float): Int {
            val f = ratio.coerceIn(0f, 1f)
            fun channel(a: Int, b: Int) = (a + (b - a) * f).toInt().coerceIn(0, 255)
            return rgb(
                channel(red(base), red(over)),
                channel(green(base), green(over)),
                channel(blue(base), blue(over)),
            )
        }

        /** 按 Rec.709 权值计算相对亮度，取值区间 0 至 1。 */
        fun luma(color: Int): Double =
            0.2126 * red(color) / 255.0 +
                0.7152 * green(color) / 255.0 +
                0.0722 * blue(color) / 255.0

        /**
         * 依据显式传入的种子色生成指定深浅模式的 One UI 语义色板。
         */
        fun fromSeed(seed: Int, dark: Boolean): MonetTones {
            val seedChroma = chromaOf(seed)
            return if (dark) {
                // 深色模式：页面底接近纯黑（系统实测 #010101），卡片提升至 L*12 并保留少量色调
                val card = tone(seed, 12.0, 3.0)
                val accentDeep = if (luma(seed) < 0.45) {
                    tone(seed, 72.0, seedChroma.coerceAtMost(28.0))
                } else {
                    seed
                }
                MonetTones(
                    background = tone(seed, 0.8, 0.8),
                    card = card,
                    cardAlt = tone(seed, 17.0, 3.2),
                    text = tone(seed, 95.0, 1.0),
                    subText = tone(seed, 72.0, 4.0),
                    divider = tone(seed, 24.0, 2.4),
                    accent = seed,
                    accentDeep = accentDeep,
                    onAccent = if (luma(accentDeep) > 0.5) tone(seed, 10.0, 1.0) else 0xFFFFFFFF.toInt(),
                    chip = tone(seed, 26.0, 14.0),
                    onChip = tone(seed, 88.0, 24.0),
                    track = tone(seed, 28.0, 2.6),
                    segmentTrack = tone(seed, 18.0, 2.0),
                    segmentThumb = tone(seed, 29.0, 2.6),
                )
            } else {
                // 浅色模式：实测页面底 #F4F0EF、卡片 #FCFCFC、开启态开关 #D2A07B（种子原值）
                val accentDeep = tone(seed, 40.0, seedChroma.coerceIn(14.0, 22.0))
                MonetTones(
                    background = tone(seed, 95.6, 1.4),
                    card = tone(seed, 99.0, 0.4),
                    cardAlt = tone(seed, 92.0, 1.6),
                    text = tone(seed, 9.0, 1.2),
                    subText = tone(seed, 47.0, 2.6),
                    divider = tone(seed, 88.0, 1.2),
                    accent = seed,
                    accentDeep = accentDeep,
                    onAccent = if (luma(accentDeep) > 0.5) tone(seed, 10.0, 1.0) else 0xFFFFFFFF.toInt(),
                    chip = tone(seed, 91.0, 10.0),
                    onChip = tone(seed, 34.0, 20.0),
                    track = tone(seed, 86.0, 1.6),
                    segmentTrack = tone(seed, 92.5, 1.2),
                    segmentThumb = tone(seed, 99.5, 0.2),
                )
            }
        }

        /**
         * 依据当前系统种子色生成指定深浅模式的语义色板。
         *
         * @return 语义色集合；取色链路全部失败时返回 `null`，由调用方使用固定配色。
         */
        fun from(context: Context, dark: Boolean): MonetTones? {
            val seed = seedOf(context)
            if (seed == 0) return null
            return fromSeed(seed, dark)
        }
    }
}
