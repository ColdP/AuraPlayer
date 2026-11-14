/*
 * Derived from OneUI_Kit - MIT License
 * Copyright (c) 2026 The One UI Kit Authors
 */
package dev.pascal.oneuikit

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.animation.DecelerateInterpolator
import android.widget.CompoundButton

/**
 * One UI 9 形态开关：胶囊轨道与内嵌圆钮，全部绘制在 `onDraw()` 中完成。
 *
 * 继承 [CompoundButton] 以复用选中状态、切换语义与无障碍支持。颜色由构造参数注入，
 * 控件本身不读取系统设置。
 *
 * @param context 上下文。
 * @param checked 初始选中状态。
 * @param trackOn 开启态轨道颜色。
 * @param trackOff 关闭态轨道颜色。
 * @param thumbColor 圆钮颜色。
 */
class BoSwitchView(
    context: Context,
    checked: Boolean,
    private var trackOn: Int,
    private var trackOff: Int,
    private var thumbColor: Int,
) : CompoundButton(context) {

    private val density = resources.displayMetrics.density
    private var animator: ValueAnimator? = null
    private var position = if (checked) 1f else 0f
    private var userListener: OnCheckedChangeListener? = null
    private var suppressListener = false

    init {
        buttonDrawable = null
        // 代码方式创建的 CompoundButton 默认不可点击，需显式开启以接收点击事件
        isClickable = true
        isFocusable = true
        // 上下各留 8dp 内边距以扩展触摸区域，绘制区域仍为 24dp
        setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
        // 初始值直接写入父类字段，避免首次渲染触发状态补间
        super.setChecked(checked)
    }

    private fun dp(value: Float) = value * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            dp(40f).toInt() + paddingLeft + paddingRight,
            dp(24f).toInt() + paddingTop + paddingBottom,
        )
    }

    override fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) {
        userListener = listener
        super.setOnCheckedChangeListener { buttonView, isChecked ->
            if (!suppressListener) {
                userListener?.onCheckedChanged(buttonView, isChecked)
            }
        }
    }

    override fun setChecked(checked: Boolean) {
        if (isChecked == checked) return
        super.setChecked(checked)
        animateTo(if (checked) 1f else 0f)
    }

    /** 程序同步外部状态时更新勾选状态并触发动画，而不回调 [OnCheckedChangeListener]。 */
    fun setCheckedProgrammatic(checked: Boolean) {
        if (isChecked == checked) return
        suppressListener = true
        setChecked(checked)
        suppressListener = false
    }

    /** 在主题或色板变更时原地刷新开关色值。 */
    fun updateColors(trackOn: Int, trackOff: Int, thumbColor: Int) {
        if (this.trackOn == trackOn && this.trackOff == trackOff && this.thumbColor == thumbColor) return
        this.trackOn = trackOn
        this.trackOff = trackOff
        this.thumbColor = thumbColor
        invalidate()
    }

    /** 点击时触发一次确认触觉反馈。 */
    override fun performClick(): Boolean {
        val handled = super.performClick()
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        return handled
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(position, target).apply {
            duration = 160L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        if (w <= 0f || h <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val alpha = if (isEnabled) 255 else 90

        paint.color = blend(trackOff, trackOn, position)
        paint.alpha = alpha
        val radius = h / 2f
        canvas.drawRoundRect(left, top, left + w, top + h, radius, radius, paint)

        val inset = dp(3f)
        val diameter = h - inset * 2f
        val travel = w - diameter - inset * 2f
        val cx = left + inset + diameter / 2f + travel * position
        paint.color = thumbColor
        paint.alpha = alpha
        canvas.drawCircle(cx, top + h / 2f, diameter / 2f, paint)
    }

    /** 按 [t] 在 [from] 与 [to] 之间插值，返回不透明的 ARGB 颜色。 */
    private fun blend(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun channel(a: Int, b: Int) = (a + (b - a) * f).toInt().coerceIn(0, 255)
        return Color.argb(
            255,
            channel(Color.red(from), Color.red(to)),
            channel(Color.green(from), Color.green(to)),
            channel(Color.blue(from), Color.blue(to)),
        )
    }
}
