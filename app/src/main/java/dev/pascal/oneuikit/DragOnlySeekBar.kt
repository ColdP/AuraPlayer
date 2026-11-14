/*
 * Derived from OneUI_Kit - MIT License
 * Copyright (c) 2026 The One UI Kit Authors
 *
 * Adapted for standalone Android SeekBar superclass to avoid SESL/Jetpack dependency conflicts,
 * per OneUI_Kit docs/components.md guidance.
 */
package dev.pascal.oneuikit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.SeekBar
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 仅响应横向拖拽的 One UI 9 形态滑杆。
 *
 * 系统 `SeekBar` 采用绝对定位，按下时数值即跳转至触点位置；本控件按位移增量调整进度，
 * 并将纵向手势交由父容器处理，避免可滚动界面中的误操作。绘制规格参见 `docs/components.md`。
 */
class DragOnlySeekBar(context: Context) : SeekBar(context, null, 0) {

    /** 拖拽过程中的连续回调，参数为当前进度。 */
    var onLive: ((Int) -> Unit)? = null

    /** 拖拽结束后的提交回调，参数为最终进度。 */
    var onCommit: ((Int) -> Unit)? = null

    /** 分级跨度，默认为 1。 */
    var step: Int = 1

    /** 未完成段轨道颜色。 */
    var trackColor: Int = 0

    /** 已完成段轨道颜色。 */
    var fillColor: Int = 0

    /** 拇指圆环颜色。 */
    var ringColor: Int = 0

    /** 圆环内部遮罩颜色，应与控件所在的表面色一致。 */
    var maskColor: Int = 0

    /** 当前是否处于用户横向拖拽态，供 Compose 等宿主避让外部回写。 */
    val isUserDragging: Boolean get() = dragging

    private val trackHeight = 16f * resources.displayMetrics.density
    private val ringOuter = 21f * resources.displayMetrics.density
    private val ringStroke = 3.5f * resources.displayMetrics.density

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val thumbWidth = 24f * resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    private var startProgress = 0
    private var dragging = false

    init {
        background = null
        thumb = null
        progressDrawable = null
        splitTrack = false
        setPadding(0, 0, 0, 0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                startProgress = progress
                dragging = false
                // 消费 DOWN 事件但不修改数值，以接收后续 MOVE 事件
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging) {
                    // 纵向手势交由父容器拦截，本控件随后收到 CANCEL
                    if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                    if (abs(dx) <= slop) return true
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    isPressed = true
                    // 以触发拖动时的位置为基准，避免进度跳转至触点位置
                    downX = event.x
                    startProgress = progress
                    return true
                }
                val usable = (width - paddingLeft - paddingRight - thumbWidth).coerceAtLeast(1f)
                val delta = ((event.x - downX) / usable * max).roundToInt()
                applyProgress(startProgress + delta)
                return true
            }

            MotionEvent.ACTION_UP -> {
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    dragging = false
                    onCommit?.invoke(progress)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                return true
            }
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // 控件高度包含触摸余量，轨道绘制于垂直居中区域
        setMeasuredDimension(w, (40f * resources.displayMetrics.density).toInt())
    }

    /** 拇指中心横坐标；轨道两端各内缩一个半径，端点进度时圆环与轨道端点对齐。 */
    private fun thumbCenterX(): Float {
        val left = paddingLeft + ringOuter / 2f
        val right = width - paddingRight - ringOuter / 2f
        if (right <= left) return left
        val fraction = if (max <= 0) 0f else progress.toFloat() / max
        return left + (right - left) * fraction
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        if (right <= left) return
        val cy = height / 2f
        val half = trackHeight / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val disabledFactor = if (isEnabled) 255 else 110
        val cx = thumbCenterX()

        // 未完成段轨道
        paint.color = trackColor
        paint.alpha = disabledFactor
        canvas.drawRoundRect(left, cy - half, right, cy + half, half, half, paint)

        // 已完成段裁剪至拇指中心，避免颜色绘制到圆环内部
        if (cx > left) {
            paint.color = fillColor
            paint.alpha = disabledFactor
            canvas.save()
            canvas.clipRect(left, cy - half, cx, cy + half)
            canvas.drawRoundRect(left, cy - half, right, cy + half, half, half, paint)
            canvas.restore()
        }

        // 环内先以表面色填充，再描边，使拇指保持空心环形态
        paint.color = maskColor
        paint.alpha = 255
        canvas.drawCircle(cx, cy, ringOuter / 2f, paint)
        paint.color = ringColor
        paint.alpha = disabledFactor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ringStroke
        canvas.drawCircle(cx, cy, ringOuter / 2f - ringStroke / 2f, paint)
        paint.style = Paint.Style.FILL
    }

    /** 按 [step] 吸附进度，跨级时触发触觉反馈并派发 [onLive]。 */
    private fun applyProgress(raw: Int) {
        val clamped = raw.coerceIn(0, max)
        val snapped = if (step <= 1) clamped else ((clamped + step / 2) / step) * step
        if (snapped == progress) return
        progress = snapped.coerceIn(0, max)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onLive?.invoke(this.progress)
    }
}
