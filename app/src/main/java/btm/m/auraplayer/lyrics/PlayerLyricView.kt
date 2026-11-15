// SPDX-License-Identifier: AGPL-3.0-or-later
// Renderer adapter for HyperChanger's HyperMusicCover next/lyrics implementation.
package btm.m.auraplayer.lyrics

import android.content.Context
import android.graphics.*
import android.graphics.text.LineBreaker
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import btm.m.auraplayer.playback.LyricTheme
import btm.m.auraplayer.ui.player.LyricPvEffect
import btm.m.auraplayer.ui.player.lyricThemeSpec
import kotlinx.coroutines.*
import kotlin.math.*

internal fun computePvRowShiftX(
    boxWidth: Float,
    contentWidth: Float,
    staggerRatio: Float,
    opposite: Boolean,
): Float {
    val freeRoom = max(0f, boxWidth - contentWidth)
    if (freeRoom <= 0f) return 0f
    val clamped = staggerRatio.coerceIn(0f, 1f)
    return if (opposite) -(1f - clamped) * freeRoom else clamped * freeRoom
}

internal sealed interface TapSeekAction {
    data object None : TapSeekAction
    data object CancelledDoubleTap : TapSeekAction
    data class SeekNow(val targetMs: Long) : TapSeekAction
    data class ScheduleSeek(val targetMs: Long, val delayMs: Long, val generation: Int) : TapSeekAction
}

/**
 * Delays tap-to-seek by [delayMs] so a second tap within the window cancels seeking
 * instead of colliding with the rear display's double-tap-to-sleep gesture.
 */
internal class LyricTapSeekGuard {
    var delayMs: Long = 0L
    private var pendingTargetMs: Long? = null
    private var lastTapUpAtMs: Long = -1L
    private var suppressCurrentTouch = false
    private var generation = 0

    val hasPendingSeek: Boolean
        get() = pendingTargetMs != null

    fun onTouchDown(atMs: Long): Boolean {
        if (delayMs <= 0L) {
            suppressCurrentTouch = false
            return false
        }
        val withinWindow = lastTapUpAtMs >= 0L && (atMs - lastTapUpAtMs) in 0L..delayMs
        return if (pendingTargetMs != null || withinWindow) {
            suppressCurrentTouch = true
            lastTapUpAtMs = -1L
            val hadPending = pendingTargetMs != null
            pendingTargetMs = null
            generation++
            hadPending
        } else {
            suppressCurrentTouch = false
            false
        }
    }

    fun onTouchTapUp(targetMs: Long?, atMs: Long): TapSeekAction {
        if (delayMs <= 0L) {
            suppressCurrentTouch = false
            return if (targetMs != null) TapSeekAction.SeekNow(targetMs) else TapSeekAction.None
        }
        if (suppressCurrentTouch) {
            suppressCurrentTouch = false
            lastTapUpAtMs = -1L
            return TapSeekAction.CancelledDoubleTap
        }
        lastTapUpAtMs = atMs
        if (targetMs != null) {
            pendingTargetMs = targetMs
            generation++
            return TapSeekAction.ScheduleSeek(targetMs, delayMs, generation)
        }
        return TapSeekAction.None
    }

    fun onTouchDragOrCancel() {
        suppressCurrentTouch = false
    }

    fun consumeScheduled(gen: Int): Long? {
        if (gen != generation) return null
        val target = pendingTargetMs ?: return null
        pendingTargetMs = null
        lastTapUpAtMs = -1L
        return target
    }

    fun cancelPending(): Boolean {
        val hadPending = pendingTargetMs != null
        pendingTargetMs = null
        lastTapUpAtMs = -1L
        suppressCurrentTouch = false
        generation++
        return hadPending
    }
}

/** Uses the original per-row springs, stagger, emphasis, depth blur and syllable renderer. */
class PlayerLyricView(context: Context) : View(context) {
    private data class Row(val line: LyricLine, val main: StaticLayout, val below: StaticLayout?,
                           val paint: TextPaint, val underPaint: TextPaint, val xs: FloatArray,
                           val glow: LyricGlowMask?, val bg: StaticLayout?, val bgPaint: TextPaint,
                           val bgXs: FloatArray, val bgGlow: LyricGlowMask?, val height: Float,
                           val contentWidth: Float)
    private var scope: CoroutineScope? = null
    private var buildJob: Job? = null
    private var rows = emptyList<Row>()
    private var source = emptyList<LyricLine>()
    private var requestedSource = emptyList<LyricLine>()
    private var below = 3
    private var lyricScale = 1f
    private var lineSpacingScale = 1f
    private var horizontalInsetDp = 26f
    private var generation = 0
    private var builtWidth = 0
    private var builtMode = -1
    private var builtScale = 0f
    private var builtLineSpacing = 0f
    private var builtHorizontalInsetDp = -1f
    private var lyricTheme: LyricTheme = LyricTheme.BLUR
    private var builtLyricTheme: LyricTheme = LyricTheme.BLUR
    private var themeSpec = lyricThemeSpec(LyricTheme.BLUR)
    private var spatialEnabled = false
    private var spatialTiltX = 0f
    private var spatialTiltY = 0f
    private val camera = android.graphics.Camera()
    private val cameraMatrix = Matrix()
    private val d = resources.displayMetrics.density
    private val sd = resources.displayMetrics.scaledDensity
    private var base = FloatArray(0); private var heights = FloatArray(0)
    private var scroll = FloatArray(0); private var aim = FloatArray(0); private var next = FloatArray(0)
    private var velocity = FloatArray(0); private var aimAt = LongArray(0)
    private var emphasis = FloatArray(0); private var lit = FloatArray(0)
    private var scales = FloatArray(0); private var blurs = FloatArray(0)
    private val singing = LyricFocus()
    private val transition = LyricTransition()
    private val words = LyricWordRenderer()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val masks = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val pending = mutableSetOf<String>()
    private val maskBlend = LyricMaskBlend()
    private var focus = -1
    private var springK = 170f
    private var lastFrame = 0L
    private var playhead = 0L
    private var sampledAt = 0L
    private var playing = false
    private var manual = 0f
    private var manualUntil = 0L
    private var downY = 0f; private var lastY = 0f; private var dragging = false
    private var browseAlpha = 0f
    private var browseSelected = -1
    private val browsePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pvPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pvStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pvTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pvPath = Path()
    private val pvRect = RectF()
    private val timestampPaint = paint(12f, 600).apply { textAlign = Paint.Align.RIGHT }
    private val timeGutter = max(44f * d, timestampPaint.measureText("000:00") + 12f * d)
    private var lastPosition = 0L
    private var jumped = false
    private var primeAppearance = true
    var onSeek: (Long) -> Unit = {}
    var onLongPress: (Int) -> Unit = {}
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false
    private var longPressRow = -1
    private val longPressAction = Runnable {
        if (longPressRow >= 0 && !dragging && isAttachedToWindow) {
            longPressTriggered = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress(longPressRow)
            invalidate()
        }
    }
    private var inkArgb: Int = Color.WHITE
    private val colors = object : LyricWordRenderer.Colors {
        override fun ink(alpha: Float, emphasis: Float, backing: Boolean): Long {
            val base = inkArgb
            val tint = themeSpec.accentArgb
            val blend = if (lyricTheme != LyricTheme.BLUR && !backing) (emphasis * 0.45f).coerceIn(0f, 1f) else 0f
            val r = (Color.red(base) + (Color.red(tint) - Color.red(base)) * blend) / 255f
            val g = (Color.green(base) + (Color.green(tint) - Color.green(base)) * blend) / 255f
            val b = (Color.blue(base) + (Color.blue(tint) - Color.blue(base)) * blend) / 255f
            return Color.pack(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))
        }
        override fun white(alpha: Float, gain: Float): Long {
            val tint = if (lyricTheme != LyricTheme.BLUR) themeSpec.accentArgb else inkArgb
            val r = (Color.red(tint) + (255 - Color.red(tint)) * 0.35f) / 255f
            val g = (Color.green(tint) + (255 - Color.green(tint)) * 0.35f) / 255f
            val b = (Color.blue(tint) + (255 - Color.blue(tint)) * 0.35f) / 255f
            return Color.pack(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))
        }
        override fun glowGain(glow: Float) = themeSpec.wordGlowBoost
    }

    fun inkColor(argb: Int) {
        if (inkArgb == argb) return
        inkArgb = argb
        timestampPaint.color = argb
        masks.evictAll()
        invalidate()
    }

    fun lyricTheme(theme: LyricTheme) {
        if (lyricTheme == theme) return
        lyricTheme = theme
        themeSpec = lyricThemeSpec(theme)
        timestampPaint.typeface = themedTypeface(themeSpec.subWeight, themeSpec.italicSub)
        masks.evictAll()
        rebuild()
        invalidate()
    }

    fun spatialLyrics(enabled: Boolean) {
        if (spatialEnabled == enabled) return
        spatialEnabled = enabled
        invalidate()
    }

    fun spatialTilt(tiltX: Float, tiltY: Float) {
        val cx = tiltX.coerceIn(-1f, 1f)
        val cy = tiltY.coerceIn(-1f, 1f)
        if (abs(cx - spatialTiltX) < 0.0008f && abs(cy - spatialTiltY) < 0.0008f) return
        spatialTiltX = cx
        spatialTiltY = cy
        if (spatialEnabled) invalidate()
    }

    private val tapSeekGuard = LyricTapSeekGuard()
    private var pendingSeekRunnable: Runnable? = null
    var tapSeekDelayMs: Long = 0L
        set(value) {
            val clamped = value.coerceAtLeast(0L)
            field = clamped
            tapSeekGuard.delayMs = clamped
            if (clamped == 0L) cancelPendingSeek()
        }

    fun cancelPendingSeek() {
        pendingSeekRunnable?.let { longPressHandler.removeCallbacks(it) }
        pendingSeekRunnable = null
        if (tapSeekGuard.cancelPending()) invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); rebuild(); invalidate() }
    override fun onDetachedFromWindow() { cancelPendingSeek(); longPressHandler.removeCallbacks(longPressAction); keepScreenOn = false; scope?.cancel(); scope = null; pending.clear(); super.onDetachedFromWindow() }
    fun submit(
        lines: List<LyricLine>,
        translation: Boolean,
        pronunciation: Boolean,
        textScale: Float = 1f,
        lineSpacingMultiplier: Float = 1f,
        horizontalPaddingDp: Float = 26f,
    ) {
        val mode = (if (translation) 1 else 0) or (if (pronunciation) 2 else 0)
        val scale = textScale.coerceIn(.5f, 1.5f)
        val spacing = lineSpacingMultiplier.coerceIn(.4f, 2.5f)
        val inset = horizontalPaddingDp.coerceIn(0f, 40f)
        if (requestedSource === lines && below == mode && lyricScale == scale &&
            lineSpacingScale == spacing && horizontalInsetDp == inset
        ) return
        if (requestedSource !== lines) cancelPendingSeek()
        requestedSource = lines
        below = mode
        lyricScale = scale
        lineSpacingScale = spacing
        horizontalInsetDp = inset
        rebuild()
    }
    fun position(ms: Long, isPlaying: Boolean) {
        val now = SystemClock.uptimeMillis()
        val expected = playhead + if (playing) now - sampledAt else 0
        if (abs(ms - expected) > 800) jumped = true
        playhead = ms; sampledAt = now; playing = isPlaying
        invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) rebuild()
    }
    private fun themedTypeface(weight: Int, italic: Boolean): Typeface {
        val base = if (themeSpec.fontFamilyName == "default") {
            Typeface.DEFAULT
        } else {
            Typeface.create(themeSpec.fontFamilyName, Typeface.NORMAL)
        }
        return Typeface.create(base, weight, italic)
    }

    private fun paint(
        size: Float,
        weight: Int,
        italic: Boolean = false,
        spacing: Float = 0f,
    ) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * sd
        color = inkArgb
        typeface = themedTypeface(weight, italic)
        if (spacing != 0f) letterSpacing = spacing
    }
    private fun layout(text: String, p: TextPaint, width: Int, opposite: Boolean) =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setIncludePad(false).setAlignment(if (opposite) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED).build()

    private fun rebuild() {
        val owner = scope ?: return
        if (width <= 0) return
        // Keep the lyric body symmetric for normal and opposite (choral) alignment. The browse
        // timestamp is an overlay and must not permanently make right-aligned rows narrower.
        val lines = requestedSource; val mode = below; val scale = lyricScale
        val spacing = lineSpacingScale; val insetDp = horizontalInsetDp
        val activeTheme = lyricTheme
        val activeSpec = themeSpec
        // Retain the layout and appearance when the same content is attached again unchanged.
        if (source === lines && builtWidth == width && builtMode == mode && builtScale == scale &&
            builtLineSpacing == spacing && builtHorizontalInsetDp == insetDp &&
            builtLyricTheme == activeTheme
        ) return
        val gen = ++generation
        val targetWidth = width
        val viewportHeight = height.toFloat()
        val positionMs = (playhead + if (playing) SystemClock.uptimeMillis() - sampledAt else 0L)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val horizontalPadPx = (insetDp * 2f * d).toInt()
        val textWidth = max(1, width - horizontalPadPx)
        val rowGapPx = 22f * d * spacing
        buildJob?.cancel()
        buildJob = owner.launch {
            val (built, prepared) = withContext(Dispatchers.Default) {
                val built = lines.map { line ->
                    ensureActive()
                    val p = paint(30f * scale, activeSpec.mainWeight, activeSpec.italicMain, activeSpec.letterSpacing)
                    val tp = paint(17f * scale, activeSpec.subWeight, activeSpec.italicSub, activeSpec.letterSpacing * 0.5f)
                    val main = layout(line.text, p, textWidth, line.opposite)
                    val under = line.under(mode)?.let { layout(it, tp, textWidth, line.opposite) }
                    val bp = paint(17f * scale, activeSpec.subWeight, activeSpec.italicSub, activeSpec.letterSpacing * 0.5f)
                    val bg = line.bg?.let { layout(it.text, bp, textWidth, line.opposite) }
                    val bgXs = line.bg?.let { b -> FloatArray(b.text.length + 1) { bg!!.getPrimaryHorizontal(it) } } ?: FloatArray(0)
                    val bgGlow = line.bg?.takeIf { it.hasWords() }?.let { LyricGlowMask.build(it, bg, bgXs, TextPaint(bp), 5f * d, LyricWordRenderer.GLOW_MIN_MS) }
                    val xs = FloatArray(line.text.length + 1) { main.getPrimaryHorizontal(it) }
                    val glow = if (line.hasWords()) LyricGlowMask.build(line, main, xs, TextPaint(p), 5f * d, LyricWordRenderer.GLOW_MIN_MS) else null
                    val contentW = maxOf(
                        main.maxLineWidth(),
                        bg?.maxLineWidth() ?: 0f,
                        under?.maxLineWidth() ?: 0f,
                    ).coerceIn(1f, textWidth.toFloat())
                    Row(line, main, under, p, tp, xs, glow, bg, bp, bgXs, bgGlow,
                        main.height + (if (bg == null) 0f else bg.height + 3 * d) + (if (under == null) 0f else under.height + 5 * d),
                        contentW)
                }
                val prepared = linkedMapOf<String, Bitmap>()
                for ((i, distance) in initialLyricMaskDepths(lines, FloatArray(built.size) { built[it].height },
                    positionMs, viewportHeight, rowGapPx, 40 * d)) {
                    ensureActive()
                    prepared["$i:$distance"] = buildDepthMask(built[i], distance, mode)
                }
                built to prepared
            }
            if (gen != generation) return@launch
            source = lines; rows = built; masks.evictAll(); pending.clear()
            prepared.forEach { (key, bitmap) -> masks.put(key, bitmap) }
            maskBlend.reset(prepared.keys)
            builtWidth = targetWidth; builtMode = mode; builtScale = scale
            builtLineSpacing = spacing; builtHorizontalInsetDp = insetDp; builtLyricTheme = activeTheme
            heights = FloatArray(rows.size) { rows[it].height }
            var y = 0f
            base = FloatArray(rows.size) { i -> y.also { y += heights[i] + rowGapPx } }
            scroll = FloatArray(rows.size); aim = scroll.clone(); next = scroll.clone(); velocity = scroll.clone()
            emphasis = scroll.clone(); lit = scroll.clone(); blurs = scroll.clone(); scales = FloatArray(rows.size) { .97f }
            aimAt = LongArray(rows.size); singing.reset(); transition.reset(base); focus = -1; lastFrame = 0; primeAppearance = true
            manual = 0f; manualUntil = 0; browseAlpha = 0f; browseSelected = -1; invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rows.isEmpty()) return
        // HyperChanger's lock-screen renderer fades the lyric band at its two edges after the
        // per-line rendering pass. Keep this as a Canvas mask: sampling the rendered text through
        // a second Compose backdrop layer magnifies and brightens glyphs on Android 15/16.
        val lyricLayer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 1f / 60f else ((now - lastFrame) / 1000f).coerceIn(0f, .05f)
        lastFrame = now
        val ms = (playhead + if (playing) now - sampledAt else 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val index = source.indexOfLast { it.start <= ms }.coerceAtLeast(0)
        val previousCount = singing.count
        val groupChanged = singing.update(source, ms, LyricFocus.DEFAULT_LIMIT, index)
        if (groupChanged || focus < 0) {
            singing.layout(base, heights, 22 * d)
            if (focus >= 0) transition.rebase(singing, base, scroll, aim, next)
        }
        val sf = if (singing.count > 0) singing.last else index
        val anchor = height * .15f
        if (sf != focus || groupChanged) {
            val target = singing.position(sf, base)
            if (focus < 0) { scroll.fill(target); aim.fill(target); next.fill(target) }
            else if (jumped) { aimAt.fill(now); next.fill(target); springK = 90f }
            else {
                springK = LyricMotion.stiffness(false, if (sf > 0) source[sf].start - source[sf - 1].start else 800)
                springK /= LyricTransition.motionWeight(max(previousCount, singing.count), singing.height(base, heights), height.toFloat())
                LyricMotion.ripple(singing, base, heights, aimAt, next, now, target, anchor, 0f, sf, previousCount)
            }
            focus = sf
            contentDescription = source[sf].text + (source[sf].under(below)?.let { "\n$it" } ?: "")
        }
        jumped = false
        val browsing = dragging || now < manualUntil
        if (!browsing) manual = LyricMotion.approach(manual, 0f, dt, .18f)
        if (abs(manual) < .2f) manual = 0f
        val browseTarget = if (browsing) 1f else 0f
        browseAlpha = LyricMotion.approach(browseAlpha, browseTarget, dt, .10f)
        if (browsing) browseSelected = nearestLyricRow(rows.size, height * .5f) {
            anchor + singing.position(it, base) - scroll[it] + manual + heights[it] / 2
        }
        else if (browseAlpha < .005f) { browseAlpha = 0f; browseSelected = -1 }
        transition.update(singing, base, -1, .6f, 1f, dt, primeAppearance)
        var moving = abs(manual) > .2f || abs(browseAlpha - browseTarget) > .005f
        val c = sqrt(springK) * LyricMotion.DAMPING_MULT
        // Browsing follows the row at the viewing anchor rather than the playback clock.
        // The same center becomes the timestamp/selection target during manual scrolling.
        val visualFocus = if (browsing && browseSelected >= 0) browseSelected else -1
        // A newly attached/rebuilt lyric viewport already represents the current playhead.
        // Start at that focus's appearance, rather than fading from an all-unselected frame.
        // Subsequent row changes keep their normal animated transitions.
        val blurScale = themeSpec.depthBlurMultiplier.coerceIn(0f, 1.5f)
        if (primeAppearance) {
            for (i in rows.indices) {
                val selected = if (visualFocus >= 0) i == visualFocus else singing.contains(i)
                val distance = if (visualFocus >= 0) abs(i - visualFocus) else singing.distance(i)
                emphasis[i] = if (selected) 1f else 0f
                lit[i] = if (selected && (visualFocus >= 0 || ms >= rows[i].line.start)) 1f else 0f
                scales[i] = if (selected) 1f else .97f
                blurs[i] = LyricMotion.depthBlur(distance, d) * blurScale
            }
            primeAppearance = false
        }
        val focusAnchorY = height * .22f
        val timeSec = now / 1000f
        val unit = min(width, height).toFloat().coerceAtLeast(1f) / 360f
        var hasPrevStar = false
        var prevStarX = 0f
        var prevStarY = 0f
        for ((i, row) in rows.withIndex()) {
            moving = LyricMotion.step(i, scroll, aim, next, velocity, aimAt, now, dt, false, springK, c, height * LyricMotion.SEEK_SPEED_BANDS) != 0 || moving
            val focused = if (visualFocus >= 0) i == visualFocus else singing.contains(i)
            val focusDistance = if (visualFocus >= 0) abs(i - visualFocus) else singing.distance(i)
            val targetBlur = LyricMotion.depthBlur(focusDistance, d) * blurScale
            moving = LyricMotion.appearance(i, emphasis, lit, scales, blurs, focused, visualFocus >= 0, ms, row.line.start,
                now >= aimAt[i], dt, singing.count, heights[i], height.toFloat(), targetBlur) != 0 || moving
            val y = anchor + singing.position(i, base) - scroll[i] + manual
            if (y + row.height < -40 * d || y > height + 40 * d) continue
            if (i == browseSelected && browseAlpha > 0f) {
                val boxHorizontalInset = kotlin.math.max(0f, horizontalInsetDp - 14f) * d
                val stampRightInset = kotlin.math.max(8f, horizontalInsetDp) * d
                browsePaint.color = inkArgb
                browsePaint.alpha = (browseAlpha * .10f * 255).toInt()
                canvas.drawRoundRect(
                    boxHorizontalInset,
                    y - 8 * d,
                    width - boxHorizontalInset,
                    y + row.height + 8 * d,
                    16 * d,
                    16 * d,
                    browsePaint,
                )
                timestampPaint.color = inkArgb
                timestampPaint.alpha = (browseAlpha * .85f * 255).toInt()
                val metrics = timestampPaint.fontMetrics
                val seconds = row.line.start.coerceAtLeast(0) / 1000
                val stamp = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                canvas.drawText(stamp, width - stampRightInset, y + row.height / 2 - (metrics.ascent + metrics.descent) / 2, timestampPaint)
            }
            // The shader fades/defocuses pixels individually, including partial text rows.
            val alpha = (.4f + .6f * lit[i]) * transition.alpha(i)
            val unfocused = (1f - emphasis[i]).coerceIn(0f, 1f)
            val normY = ((y + row.height * .5f - focusAnchorY) / max(1f, height.toFloat())).coerceIn(-1.2f, 1.2f)
            val pvWeight = if (lyricTheme != LyricTheme.BLUR) (1f - browseAlpha).coerceIn(0f, 1f) else 0f
            val staggerRatio = pvStaggerRatioFor(i)
            val staggerX = if (pvWeight > 0f) {
                computePvRowShiftX(row.main.width.toFloat(), row.contentWidth, staggerRatio, row.line.opposite) * pvWeight
            } else {
                0f
            }
            val entryVectorX = if (pvWeight > 0f && themeSpec.entrySlideDp > 0f) {
                unfocused * themeSpec.entrySlideDp * d * pvWeight * (if (i % 2 == 0) 1f else -1f)
            } else {
                0f
            }
            val swayX = if (lyricTheme != LyricTheme.BLUR && themeSpec.swayAmplitudeDp > 0f) {
                sin(timeSec * 1.35f + i * 0.58f) * themeSpec.swayAmplitudeDp * d * (0.35f + 0.65f * emphasis[i])
            } else {
                0f
            }
            val arcX = if (lyricTheme != LyricTheme.BLUR && themeSpec.arcShiftDp > 0f) {
                val sign = if (row.line.opposite) -1f else 1f
                -abs(normY) * themeSpec.arcShiftDp * d * unfocused * sign
            } else {
                0f
            }
            val buoyancyY = if (themeSpec.pvEffect == LyricPvEffect.CAUSTIC_WAVE && pvWeight > 0f) {
                cos(timeSec * 1.6f + i * 0.9f) * 2.4f * d * pvWeight
            } else {
                0f
            }
            val spatialActive = spatialEnabled && (abs(spatialTiltX) > 0.001f || abs(spatialTiltY) > 0.001f)
            val spatialMix = if (spatialEnabled) (1f - browseAlpha * 0.65f).coerceIn(0f, 1f) else 0f
            val rowDepthZ = emphasis[i] * 1.15f - focusDistance.coerceAtMost(4) * 0.24f
            val spatialRowDx = if (spatialActive) spatialTiltX * 10.5f * d * rowDepthZ * spatialMix else 0f
            val spatialRowDy = if (spatialActive) spatialTiltY * 7.5f * d * rowDepthZ * spatialMix else 0f
            val baseRowX = horizontalInsetDp * d + staggerX + entryVectorX + swayX + arcX + spatialRowDx
            val baseRowY = y + buoyancyY + spatialRowDy
            val contentStartX = if (row.line.opposite) (row.main.width - row.contentWidth).coerceAtLeast(0f) else 0f
            val contentEndX = contentStartX + row.contentWidth
            val localPivotX = if (row.line.opposite) {
                contentStartX + row.contentWidth * (1f - staggerRatio)
            } else {
                row.contentWidth * staggerRatio
            }
            val localPivotY = row.height * 0.5f

            if (themeSpec.pvEffect == LyricPvEffect.CONSTELLATION && pvWeight > 0.05f) {
                val starX = (baseRowX + contentStartX + row.contentWidth * (if (i % 2 == 0) 0f else 1f)).coerceIn(8f * d, width - 8f * d)
                val starY = baseRowY + row.height * 0.45f
                if (hasPrevStar) {
                    pvStrokePaint.color = themeSpec.accentArgb
                    pvStrokePaint.strokeWidth = max(0.8f, 1.05f * unit)
                    pvStrokePaint.alpha = (pvWeight * alpha * 0.28f * 255).toInt().coerceIn(0, 255)
                    canvas.drawLine(prevStarX, prevStarY, starX, starY, pvStrokePaint)
                }
                drawPvConstellationNode(canvas, starX, starY, timeSec, i, emphasis[i], pvWeight * alpha, unit)
                prevStarX = starX
                prevStarY = starY
                hasPrevStar = true
            }

            if (themeSpec.pvEffect == LyricPvEffect.VERTICAL_CALLIGRAPHY &&
                focused && emphasis[i] > 0.22f && pvWeight > 0.05f && visualFocus < 0
            ) {
                val stripShiftX = if (spatialActive) spatialTiltX * 5.5f * d * spatialMix else 0f
                val stripShiftY = if (spatialActive) spatialTiltY * 4.5f * d * spatialMix else 0f
                drawPvVerticalCalligraphyStrip(canvas, row, baseRowY + stripShiftY, emphasis[i] * pvWeight * alpha, unit, stripShiftX)
            }

            val rowTiltDeg = if (pvWeight > 0f) pvTiltDegFor(i) * pvWeight else 0f
            val spatialHeroPop = if (spatialEnabled) 1f + 0.03f * emphasis[i] * spatialMix else 1f
            val rowScale = scales[i] * (1f + (themeSpec.heroScale - 1f) * emphasis[i] * pvWeight) * spatialHeroPop
            val save = canvas.save()
            canvas.translate(baseRowX, baseRowY)
            canvas.scale(rowScale, rowScale, localPivotX, localPivotY)
            if (abs(rowTiltDeg) > 0.01f) {
                canvas.rotate(rowTiltDeg, localPivotX, localPivotY)
            }
            val themeCamX = if (lyricTheme != LyricTheme.BLUR && unfocused > 0.01f) -normY * themeSpec.cameraTiltXDeg * unfocused else 0f
            val themeCamY = if (lyricTheme != LyricTheme.BLUR && unfocused > 0.01f) themeSpec.cameraTiltYDeg * unfocused * (if (row.line.opposite) -1f else 1f) else 0f
            val themeCamZ = if (lyricTheme != LyricTheme.BLUR && unfocused > 0.01f) abs(normY) * themeSpec.cameraDepthZ * unfocused * d else 0f
            val spatialCamX = if (spatialActive) -spatialTiltY * 10.5f * spatialMix else 0f
            val spatialCamY = if (spatialActive) spatialTiltX * 13.5f * spatialMix else 0f
            val spatialCamZ = if (spatialActive) (-emphasis[i] * 26f + focusDistance.coerceAtMost(4) * 14f) * d * spatialMix else 0f
            val totalCamX = themeCamX + spatialCamX
            val totalCamY = themeCamY + spatialCamY
            val totalCamZ = themeCamZ + spatialCamZ
            if (abs(totalCamX) > 0.02f || abs(totalCamY) > 0.02f || abs(totalCamZ) > 0.05f) {
                camera.save()
                camera.setLocation(0f, 0f, -12f)
                camera.rotateX(totalCamX)
                camera.rotateY(totalCamY)
                camera.translate(0f, 0f, totalCamZ)
                camera.getMatrix(cameraMatrix)
                camera.restore()
                val pivotX = if (row.line.opposite) row.main.width.toFloat() else row.main.width * 0.42f
                val pivotY = row.height * 0.5f
                cameraMatrix.preTranslate(-pivotX, -pivotY)
                cameraMatrix.postTranslate(pivotX, pivotY)
                canvas.concat(cameraMatrix)
            }
            if (lyricTheme != LyricTheme.BLUR && themeSpec.watermarkAlpha > 0f && emphasis[i] > 0.08f && pvWeight > 0.05f) {
                val wmSave = canvas.save()
                if (spatialActive) {
                    canvas.translate(-spatialTiltX * 13f * d * spatialMix, -spatialTiltY * 9f * d * spatialMix)
                }
                drawPvWatermark(canvas, row, localPivotX, alpha * emphasis[i] * pvWeight * themeSpec.watermarkAlpha, timeSec, i)
                canvas.restoreToCount(wmSave)
            }
            if (lyricTheme != LyricTheme.BLUR && pvWeight > 0.05f) {
                val backSave = canvas.save()
                if (spatialActive) {
                    canvas.translate(-spatialTiltX * 3.8f * d * spatialMix, -spatialTiltY * 2.8f * d * spatialMix)
                }
                drawPvBackOrnament(
                    canvas = canvas,
                    row = row,
                    contentStartX = contentStartX,
                    contentEndX = contentEndX,
                    emphasis = emphasis[i],
                    pvAlpha = pvWeight * alpha,
                    timeSec = timeSec,
                    ms = ms,
                    index = i,
                    unit = unit,
                )
                canvas.restoreToCount(backSave)
            }
            val distance = focusDistance.coerceIn(1, 4)
            val maxBlur = LyricMotion.depthBlur(distance, d) * blurScale
            // Blend the cached depth mask and the sharp glyphs; no abrupt bitmap swap.
            val blurred = if (blurs[i] > .01f * d && maxBlur > .01f * d) blurred(i, distance) else null
            val readiness = maskBlend.amount("$i:$distance", blurred != null, dt)
            if (blurred != null && readiness < 1f) moving = true
            val blurMix = if (maxBlur > .01f * d) (blurs[i] / maxBlur).coerceIn(0f, 1f) * readiness else 0f
            if (blurred != null) {
                imagePaint.alpha = (alpha * blurMix * 255).toInt().coerceIn(0, 255)
                val pad = 16 * d
                canvas.drawBitmap(blurred, null, RectF(-pad, -pad, row.main.width + pad, row.height + pad), imagePaint)
            }
            if (blurMix < .999f) {
                val sharpAlpha = alpha * (1f - blurMix)
                if (themeSpec.pvEffect == LyricPvEffect.CYBER_GLITCH && emphasis[i] > 0.14f && pvWeight > 0.05f) {
                    drawPvGlitchEcho(canvas, row, emphasis[i] * pvWeight * sharpAlpha, timeSec, i)
                }
                row.paint.alpha = 255
                val glowA = if (lyricTheme != LyricTheme.BLUR) (emphasis[i] * Color.alpha(themeSpec.glowArgb)).toInt().coerceIn(0, 255) else 0
                if (themeSpec.glowRadiusDp > 0f && glowA > 2) {
                    val glowColor = Color.argb(
                        glowA,
                        Color.red(themeSpec.glowArgb),
                        Color.green(themeSpec.glowArgb),
                        Color.blue(themeSpec.glowArgb),
                    )
                    val sDx = if (spatialActive) -spatialTiltX * 3.5f * d * spatialMix else 0f
                    val sDy = if (spatialActive) -spatialTiltY * 3.0f * d * spatialMix else 0f
                    row.paint.setShadowLayer(themeSpec.glowRadiusDp * d, sDx, sDy, glowColor)
                } else if (spatialActive && emphasis[i] > 0.25f) {
                    val shadowColor = Color.argb((emphasis[i] * 92).toInt().coerceIn(0, 255), 0, 0, 0)
                    row.paint.setShadowLayer(7f * d, -spatialTiltX * 4f * d * spatialMix, (2f - spatialTiltY * 3.5f) * d * spatialMix, shadowColor)
                } else {
                    row.paint.clearShadowLayer()
                }
                if (row.line.hasWords() && focused && visualFocus < 0) words.draw(canvas, row.line, row.main, row.paint, row.xs,
                    emphasis[i], sharpAlpha, sharpAlpha * .4f, 1.8f * d, true, 0, ms, row.glow, colors)
                else { row.paint.color = inkArgb; row.paint.alpha = (sharpAlpha * 255).toInt(); row.main.draw(canvas) }
                row.paint.clearShadowLayer()
                var underY = row.main.height.toFloat()
                row.bg?.let { bg ->
                    val saveBg = canvas.save()
                    canvas.translate(0f, underY + 3 * d)
                    val backing = row.line.bg
                    if (backing.hasWords() && focused && visualFocus < 0) words.draw(canvas, backing, bg, row.bgPaint, row.bgXs,
                        emphasis[i], sharpAlpha * .8f, sharpAlpha * .32f, 1.08f * d, true, 1, ms, row.bgGlow, colors)
                    else { row.bgPaint.color = inkArgb; row.bgPaint.alpha = (sharpAlpha * .8f * 255).toInt(); bg.draw(canvas) }
                    canvas.restoreToCount(saveBg)
                    underY += bg.height + 3 * d
                }
                row.below?.let {
                    canvas.translate(0f, underY + 5 * d)
                    row.underPaint.color = inkArgb; row.underPaint.alpha = (sharpAlpha * .62f * 255).toInt()
                    it.draw(canvas)
                }
                if (themeSpec.pvEffect == LyricPvEffect.WATER_MIRROR && emphasis[i] > 0.1f && pvWeight > 0.05f) {
                    drawPvWaterReflection(canvas, row, contentStartX, contentEndX, emphasis[i] * pvWeight * sharpAlpha, timeSec, unit)
                }
            }
            canvas.restoreToCount(save)
        }
        val feather = min(26f * d, height * .24f)
        if (feather > 0f) {
            edgeMaskPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(
                    0x00000000,
                    0xFFFFFFFF.toInt(),
                    0xFFFFFFFF.toInt(),
                    0x00000000,
                ),
                floatArrayOf(0f, feather / height, 1f - feather / height, 1f),
                Shader.TileMode.CLAMP,
            )
            edgeMaskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), edgeMaskPaint)
            edgeMaskPaint.xfermode = null
            edgeMaskPaint.shader = null
        }
        canvas.restoreToCount(lyricLayer)
        lastPosition = ms.toLong()
        if (playing || moving || pending.isNotEmpty()) postInvalidateOnAnimation()
        else if (browsing && !dragging) postInvalidateDelayed((manualUntil - now + 1).coerceAtLeast(1))
    }

    /** Same downsampled, per-distance BlurMaskFilter cache as HyperChanger LyricView.makeBlurred. */
    private fun blurred(index: Int, distance: Int): Bitmap? {
        val key = "$index:$distance"
        masks.get(key)?.let { return it }
        if (!pending.add(key)) return null
        val gen = generation; val row = rows[index]; val owner = scope ?: return null
        // Clone on the UI thread. The live TextPaint alpha/shader is changed during onDraw;
        // reading it from a worker can produce different mask brightness on adjacent frames.
        val snapshot = row.copy(paint = TextPaint(row.paint), underPaint = TextPaint(row.underPaint), bgPaint = TextPaint(row.bgPaint))
        val mode = builtMode
        owner.launch {
            val bitmap = withContext(Dispatchers.Default) {
                buildDepthMask(snapshot, distance, mode)
            }
            if (gen == generation) { pending.remove(key); masks.put(key, bitmap); invalidate() }
        }
        return null
    }

    /** Worker-owned paints for both first-viewport preparation and later cache misses. */
    private fun buildDepthMask(row: Row, distance: Int, mode: Int): Bitmap {
        val down = if (distance >= 3) 4 else 2
        val pad = (16 * d).toInt()
        val radius = (LyricMotion.depthBlur(distance, d) * themeSpec.depthBlurMultiplier).coerceAtLeast(0.5f)
        val w = row.main.width + pad * 2; val h = row.height.toInt() + pad * 2
        val image = Bitmap.createBitmap(max(1, w / down), max(1, h / down), Bitmap.Config.ARGB_8888)
        val out = Canvas(image); out.scale(1f / down, 1f / down); out.translate(pad.toFloat(), pad.toFloat())
        val p = TextPaint(row.paint).apply { color = inkArgb; alpha = 255; maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }
        val tp = TextPaint(row.underPaint).apply { color = inkArgb; alpha = 158; maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }
        layout(row.line.text, p, row.main.width, row.line.opposite).draw(out)
        var underY = row.main.height.toFloat()
        row.line.bg?.let { bg ->
            val saved = out.save(); out.translate(0f, underY + 3 * d)
            val bp = TextPaint(row.bgPaint).apply { color = inkArgb; alpha = 204; maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }
            val bl = layout(bg.text, bp, row.main.width, row.line.opposite); bl.draw(out)
            out.restoreToCount(saved); underY += bl.height + 3 * d
        }
        row.line.under(mode)?.let { out.translate(0f, underY + 5 * d); layout(it, tp, row.main.width, row.line.opposite).draw(out) }
        return image
    }
    private fun rowAt(yPos: Float): Int = rows.indices.firstOrNull { i ->
        val y = height * .15f + singing.position(i, base) - (scroll.getOrNull(i) ?: 0f) + manual
        yPos in (y - 6f * d)..(y + rows[i].height + 6f * d)
    } ?: -1

    fun rowBoundsInWindow(index: Int): RectF? {
        val row = rows.getOrNull(index) ?: return null
        val loc = IntArray(2)
        getLocationInWindow(loc)
        val top = loc[1] + height * .15f + singing.position(index, base) - (scroll.getOrNull(index) ?: 0f) + manual
        val pvWeight = if (lyricTheme != LyricTheme.BLUR) (1f - browseAlpha).coerceIn(0f, 1f) else 0f
        val staggerX = if (pvWeight > 0f) {
            computePvRowShiftX(row.main.width.toFloat(), row.contentWidth, pvStaggerRatioFor(index), row.line.opposite) * pvWeight
        } else 0f
        val startX = loc[0] + horizontalInsetDp * d + staggerX +
            if (row.line.opposite) (row.main.width - row.contentWidth).coerceAtLeast(0f) else 0f
        val left = if (pvWeight > 0.1f) startX.coerceIn(loc[0].toFloat(), loc[0] + width - 24f * d) else loc[0] + horizontalInsetDp * d
        val right = if (pvWeight > 0.1f) (startX + row.contentWidth).coerceIn(left + 24f * d, (loc[0] + width).toFloat()) else loc[0] + width - horizontalInsetDp * d
        return RectF(left, top, right, top + row.height)
    }

    fun viewportBoundsInWindow(): RectF {
        val loc = IntArray(2)
        getLocationInWindow(loc)
        return RectF(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + width).toFloat(), (loc[1] + height).toFloat())
    }

    var allowParentHorizontalSwipe = false
    private var downX = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = SystemClock.uptimeMillis()
                if (tapSeekGuard.onTouchDown(now)) {
                    pendingSeekRunnable?.let { longPressHandler.removeCallbacks(it) }
                    pendingSeekRunnable = null
                    invalidate()
                }
                downX = event.x; downY = event.y; lastY = event.y; dragging = false
                longPressTriggered = false
                longPressHandler.removeCallbacks(longPressAction)
                longPressRow = rowAt(event.y)
                if (longPressRow >= 0) longPressHandler.postDelayed(longPressAction, 520L)
                if (manualUntil > now) manualUntil = now + 3000
                parent?.requestDisallowInterceptTouchEvent(true); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (!dragging && allowParentHorizontalSwipe && dx > 10 * d && dx > dy * 1.25f) {
                    longPressHandler.removeCallbacks(longPressAction)
                    longPressTriggered = false
                    longPressRow = -1
                    tapSeekGuard.onTouchDragOrCancel()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
                if (dy > 8 * d) dragging = true
                if (dragging) longPressHandler.removeCallbacks(longPressAction)
                if (dragging) { manual += event.y - lastY; manual = manual.coerceIn(-max(0f, (base.lastOrNull() ?: 0f) - (base.getOrNull(focus) ?: 0f)), base.getOrNull(focus) ?: 0f); manualUntil = SystemClock.uptimeMillis() + 3000; invalidate() }
                lastY = event.y; return true
            }
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressAction)
                val now = SystemClock.uptimeMillis()
                if (dragging) {
                    manualUntil = now + 3000
                    tapSeekGuard.onTouchDragOrCancel()
                } else if (!longPressTriggered) {
                    val hit = rowAt(event.y)
                    val targetMs = if (hit >= 0) source[hit].start.toLong() else null
                    when (val action = tapSeekGuard.onTouchTapUp(targetMs, now)) {
                        is TapSeekAction.SeekNow -> {
                            onSeek(action.targetMs)
                            manual = 0f
                            manualUntil = 0L
                        }
                        is TapSeekAction.ScheduleSeek -> {
                            if (manual != 0f || manualUntil > now) {
                                manualUntil = now + action.delayMs + 500L
                            }
                            pendingSeekRunnable?.let { longPressHandler.removeCallbacks(it) }
                            val runnable = Runnable {
                                pendingSeekRunnable = null
                                val resolved = tapSeekGuard.consumeScheduled(action.generation) ?: return@Runnable
                                onSeek(resolved)
                                manual = 0f
                                manualUntil = 0L
                                invalidate()
                            }
                            pendingSeekRunnable = runnable
                            longPressHandler.postDelayed(runnable, action.delayMs)
                        }
                        TapSeekAction.CancelledDoubleTap -> {
                            if (manual == 0f) manualUntil = 0L
                        }
                        TapSeekAction.None -> Unit
                    }
                    performClick()
                } else {
                    tapSeekGuard.onTouchDragOrCancel()
                }
                longPressTriggered = false; longPressRow = -1
                dragging = false; invalidate()
                parent?.requestDisallowInterceptTouchEvent(false); return true
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressAction); longPressTriggered = false; longPressRow = -1
                tapSeekGuard.onTouchDragOrCancel()
                if (dragging) manualUntil = SystemClock.uptimeMillis() + 3000
                dragging = false; invalidate(); parent?.requestDisallowInterceptTouchEvent(false); return true
            }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    private fun pvStaggerRatioFor(index: Int): Float {
        val pattern = themeSpec.staggerPattern
        return if (pattern.isNotEmpty()) pattern[index % pattern.size].coerceIn(0f, 1f) else 0f
    }

    private fun pvTiltDegFor(index: Int): Float {
        val pattern = themeSpec.tiltPatternDeg
        return if (pattern.isNotEmpty()) pattern[index % pattern.size] else 0f
    }

    private fun StaticLayout.maxLineWidth(): Float {
        var maxW = 0f
        for (lineIndex in 0 until lineCount) {
            maxW = max(maxW, getLineWidth(lineIndex))
        }
        return maxW
    }

    private fun drawPvWatermark(
        canvas: Canvas,
        row: Row,
        localPivotX: Float,
        alpha: Float,
        timeSec: Float,
        index: Int,
    ) {
        if (alpha <= 0.005f || row.line.text.isBlank()) return
        val firstLineEnd = row.main.getLineEnd(0).coerceIn(0, row.line.text.length)
        val label = row.line.text.substring(0, firstLineEnd).trim()
        if (label.isEmpty()) return
        pvTextPaint.set(row.paint)
        pvTextPaint.clearShadowLayer()
        pvTextPaint.shader = null
        pvTextPaint.textSize = row.paint.textSize * 1.88f
        pvTextPaint.color = themeSpec.secondaryAccentArgb
        pvTextPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        pvTextPaint.textAlign = if (row.line.opposite) Paint.Align.RIGHT else Paint.Align.LEFT
        val driftX = sin(timeSec * 1.25f + index * 0.7f) * 8f * d
        val baseX = if (row.line.opposite) row.main.width.toFloat() else 0f
        val save = canvas.save()
        canvas.rotate(-pvTiltDegFor(index) * 0.65f, localPivotX, row.height * 0.5f)
        canvas.drawText(label, baseX + driftX, row.main.height * 0.72f, pvTextPaint)
        canvas.restoreToCount(save)
    }

    private fun drawPvBackOrnament(
        canvas: Canvas,
        row: Row,
        contentStartX: Float,
        contentEndX: Float,
        emphasis: Float,
        pvAlpha: Float,
        timeSec: Float,
        ms: Int,
        index: Int,
        unit: Float,
    ) {
        if (emphasis <= 0.04f || pvAlpha <= 0.02f) return
        val w = max(24f * unit, contentEndX - contentStartX)
        val h = row.height
        val padX = 10f * unit
        val padY = 5f * unit
        when (themeSpec.pvEffect) {
            LyricPvEffect.NONE -> Unit

            LyricPvEffect.CAUSTIC_WAVE -> {
                val baseY = row.main.height + 3f * unit
                pvPath.reset()
                val steps = 16
                for (step in 0..steps) {
                    val t = step.toFloat() / steps
                    val x = contentStartX + w * t
                    val wave = sin(t * 7.5f - timeSec * 2.8f + index) * 3.2f * unit * emphasis
                    if (step == 0) pvPath.moveTo(x, baseY + wave) else pvPath.lineTo(x, baseY + wave)
                }
                pvStrokePaint.color = themeSpec.accentArgb
                pvStrokePaint.strokeWidth = max(1f, 1.5f * unit)
                pvStrokePaint.alpha = (emphasis * pvAlpha * 0.55f * 255).toInt().coerceIn(0, 255)
                canvas.drawPath(pvPath, pvStrokePaint)
                pvPaint.color = themeSpec.secondaryAccentArgb
                pvPaint.alpha = (emphasis * pvAlpha * 0.45f * 255).toInt().coerceIn(0, 255)
                val bx = contentEndX + 8f * unit + sin(timeSec * 1.9f + index) * 3f * unit
                val by = baseY - 12f * unit - abs(cos(timeSec * 1.4f + index)) * 6f * unit
                canvas.drawCircle(bx, by, 2.4f * unit, pvPaint)
            }

            LyricPvEffect.WATER_MIRROR -> {
                val horizonY = row.main.height + 2f * unit
                pvStrokePaint.color = themeSpec.accentArgb
                pvStrokePaint.strokeWidth = max(1f, 1.1f * unit)
                pvStrokePaint.alpha = (emphasis * pvAlpha * 0.50f * 255).toInt().coerceIn(0, 255)
                canvas.drawLine(contentStartX, horizonY, contentEndX, horizonY, pvStrokePaint)
            }

            LyricPvEffect.PRISM_SLASH -> {
                val sweep = ((ms % 2600) / 2600f)
                val slant = 12f * unit
                pvPath.reset()
                pvPath.moveTo(contentStartX - padX + slant, -padY * 0.6f)
                pvPath.lineTo(contentEndX + padX + slant, -padY * 0.6f)
                pvPath.lineTo(contentEndX + padX - slant, row.main.height + padY * 0.5f)
                pvPath.lineTo(contentStartX - padX - slant, row.main.height + padY * 0.5f)
                pvPath.close()
                pvPaint.color = themeSpec.accentArgb
                pvPaint.alpha = (emphasis * pvAlpha * 0.14f * 255).toInt().coerceIn(0, 255)
                canvas.drawPath(pvPath, pvPaint)
                pvStrokePaint.color = themeSpec.secondaryAccentArgb
                pvStrokePaint.strokeWidth = max(1f, 1.3f * unit)
                pvStrokePaint.alpha = (emphasis * pvAlpha * 0.48f * 255).toInt().coerceIn(0, 255)
                val slashX = contentStartX + w * sweep
                canvas.drawLine(slashX - 10f * unit, h + 4f * unit, slashX + 18f * unit, -6f * unit, pvStrokePaint)
            }

            LyricPvEffect.SPACE_WARP -> {
                val centerY = row.main.height * 0.5f
                pvStrokePaint.color = themeSpec.accentArgb
                pvStrokePaint.strokeWidth = max(0.9f, 1.1f * unit)
                pvStrokePaint.alpha = (emphasis * pvAlpha * 0.34f * 255).toInt().coerceIn(0, 255)
                canvas.drawLine(contentStartX - 28f * unit, centerY, contentStartX - 6f * unit, centerY, pvStrokePaint)
                canvas.drawLine(contentEndX + 6f * unit, centerY, contentEndX + 34f * unit, centerY, pvStrokePaint)
                pvStrokePaint.color = themeSpec.secondaryAccentArgb
                pvStrokePaint.alpha = (emphasis * pvAlpha * 0.28f * 255).toInt().coerceIn(0, 255)
                pvRect.set(contentStartX - 14f * unit, -6f * unit, contentEndX + 14f * unit, row.main.height + 6f * unit)
                canvas.drawArc(pvRect, 155f, 50f, false, pvStrokePaint)
                canvas.drawArc(pvRect, -25f, 50f, false, pvStrokePaint)
            }

            LyricPvEffect.CYBER_GLITCH -> {
                val left = contentStartX - padX
                val right = contentEndX + padX
                val top = -padY * 0.4f
                val bottom = row.main.height + padY * 0.4f
                val corner = 9f * unit
                pvPaint.color = 0xFF0A0F24.toInt()
                pvPaint.alpha = (emphasis * pvAlpha * 0.36f * 255).toInt().coerceIn(0, 255)
                canvas.drawRect(left, top, right, bottom, pvPaint)

                pvStrokePaint.strokeWidth = max(1.1f, 1.6f * unit)
                pvStrokePaint.color = themeSpec.secondaryAccentArgb
                pvStrokePaint.alpha = (emphasis * pvAlpha * 0.85f * 255).toInt().coerceIn(0, 255)
                canvas.drawLine(left, top + corner, left, top, pvStrokePaint)
                canvas.drawLine(left, top, left + corner, top, pvStrokePaint)
                canvas.drawLine(left, bottom - corner, left, bottom, pvStrokePaint)
                canvas.drawLine(left, bottom, left + corner, bottom, pvStrokePaint)

                pvStrokePaint.color = themeSpec.accentArgb
                canvas.drawLine(right - corner, top, right, top, pvStrokePaint)
                canvas.drawLine(right, top, right, top + corner, pvStrokePaint)
                canvas.drawLine(right - corner, bottom, right, bottom, pvStrokePaint)
                canvas.drawLine(right, bottom - corner, right, bottom, pvStrokePaint)

                pvTextPaint.set(row.underPaint)
                pvTextPaint.clearShadowLayer()
                pvTextPaint.textSize = max(8f * unit, 9.5f * d)
                pvTextPaint.color = themeSpec.secondaryAccentArgb
                pvTextPaint.alpha = (emphasis * pvAlpha * 0.78f * 255).toInt().coerceIn(0, 255)
                pvTextPaint.textAlign = Paint.Align.LEFT
                val tag = "${(index + 1).toString().padStart(2, '0')} // PV"
                canvas.drawText(tag, left + 4f * unit, top - 2.5f * unit, pvTextPaint)
            }

            LyricPvEffect.VERTICAL_CALLIGRAPHY -> {
                val lineY = row.main.height + 2.5f * unit
                val wipeEnd = contentStartX + w * emphasis.coerceIn(0.15f, 1f)
                pvStrokePaint.color = themeSpec.secondaryAccentArgb
                pvStrokePaint.strokeWidth = max(1.2f, 1.8f * unit)
                pvStrokePaint.alpha = (emphasis * pvAlpha * 0.65f * 255).toInt().coerceIn(0, 255)
                canvas.drawLine(contentStartX, lineY, wipeEnd, lineY, pvStrokePaint)
            }

            LyricPvEffect.CONSTELLATION -> {
                pvStrokePaint.color = themeSpec.accentArgb
                pvStrokePaint.strokeWidth = max(0.8f, 1f * unit)
                pvStrokePaint.alpha = (emphasis * pvAlpha * 0.35f * 255).toInt().coerceIn(0, 255)
                canvas.drawLine(contentStartX, row.main.height + 2f * unit, contentStartX + w * 0.6f, row.main.height + 2f * unit, pvStrokePaint)
            }

            LyricPvEffect.POP_STICKER -> {
                val left = contentStartX - padX
                val right = contentEndX + padX
                val top = -padY * 0.5f
                val bottom = row.main.height + padY * 0.5f
                val corner = 8f * unit
                val shadowShift = 3.6f * unit
                pvRect.set(left + shadowShift, top + shadowShift, right + shadowShift, bottom + shadowShift)
                pvPaint.color = 0xFF07140E.toInt()
                pvPaint.alpha = (emphasis * pvAlpha * 0.78f * 255).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(pvRect, corner, corner, pvPaint)

                pvRect.set(left, top, right, bottom)
                pvPaint.color = 0xFF163D2E.toInt()
                pvPaint.alpha = (emphasis * pvAlpha * 0.86f * 255).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(pvRect, corner, corner, pvPaint)

                pvStrokePaint.color = themeSpec.accentArgb
                pvStrokePaint.strokeWidth = max(1.2f, 1.8f * unit)
                pvStrokePaint.alpha = (emphasis * pvAlpha * 0.92f * 255).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(pvRect, corner, corner, pvStrokePaint)

                pvStrokePaint.color = themeSpec.secondaryAccentArgb
                val plusX = right + 6f * unit
                val plusY = top + 2f * unit
                val arm = 3.5f * unit
                canvas.drawLine(plusX - arm, plusY, plusX + arm, plusY, pvStrokePaint)
                canvas.drawLine(plusX, plusY - arm, plusX, plusY + arm, pvStrokePaint)
            }

            LyricPvEffect.PAINTERLY_BLOOM -> {
                val midY = row.main.height * 0.5f
                pvPaint.color = themeSpec.accentArgb
                pvPaint.alpha = (emphasis * pvAlpha * 0.16f * 255).toInt().coerceIn(0, 255)
                pvRect.set(contentStartX - 12f * unit, midY - 16f * unit, contentStartX + w * 0.65f, midY + 14f * unit)
                canvas.drawOval(pvRect, pvPaint)
                pvPaint.color = themeSpec.secondaryAccentArgb
                pvPaint.alpha = (emphasis * pvAlpha * 0.14f * 255).toInt().coerceIn(0, 255)
                pvRect.set(contentStartX + w * 0.3f, midY - 12f * unit, contentEndX + 14f * unit, midY + 18f * unit)
                canvas.drawOval(pvRect, pvPaint)
            }

            LyricPvEffect.CINEMA_GATE -> {
                val left = contentStartX - padX
                val right = contentEndX + padX
                val top = -padY * 0.45f
                val bottom = row.main.height + padY * 0.45f
                val mark = 7f * unit
                pvStrokePaint.color = themeSpec.accentArgb
                pvStrokePaint.strokeWidth = max(0.9f, 1.2f * unit)
                pvStrokePaint.alpha = (emphasis * pvAlpha * 0.70f * 255).toInt().coerceIn(0, 255)
                canvas.drawLine(left, top + mark, left, top, pvStrokePaint)
                canvas.drawLine(left, top, left + mark, top, pvStrokePaint)
                canvas.drawLine(right - mark, top, right, top, pvStrokePaint)
                canvas.drawLine(right, top, right, top + mark, pvStrokePaint)
                canvas.drawLine(left, bottom - mark, left, bottom, pvStrokePaint)
                canvas.drawLine(left, bottom, left + mark, bottom, pvStrokePaint)
                canvas.drawLine(right - mark, bottom, right, bottom, pvStrokePaint)
                canvas.drawLine(right, bottom - mark, right, bottom, pvStrokePaint)

                val sec = row.line.start.coerceAtLeast(0) / 1000
                val frame = (row.line.start.coerceAtLeast(0) % 1000) / 42
                val smpte = "${(sec / 60).toString().padStart(2, '0')}:${(sec % 60).toString().padStart(2, '0')}:${frame.toString().padStart(2, '0')}"
                pvTextPaint.set(row.underPaint)
                pvTextPaint.clearShadowLayer()
                pvTextPaint.textSize = max(7.5f * unit, 9f * d)
                pvTextPaint.color = themeSpec.accentArgb
                pvTextPaint.alpha = (emphasis * pvAlpha * 0.65f * 255).toInt().coerceIn(0, 255)
                pvTextPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(smpte, right, top - 2.5f * unit, pvTextPaint)
            }
        }
    }

    private fun drawPvGlitchEcho(
        canvas: Canvas,
        row: Row,
        alpha: Float,
        timeSec: Float,
        index: Int,
    ) {
        if (alpha <= 0.02f) return
        val pulse = max(0f, sin(timeSec * 3.1f + index * 1.3f))
        val jitter = (1.1f + pulse * 1.8f) * d
        val prevColor = row.paint.color
        val prevAlpha = row.paint.alpha
        val prevShader = row.paint.shader
        row.paint.clearShadowLayer()
        row.paint.shader = null
        val splitAlpha = (alpha * (0.28f + 0.18f * pulse) * 255).toInt().coerceIn(0, 255)

        val saveCyan = canvas.save()
        canvas.translate(-jitter, -0.5f * d)
        row.paint.color = themeSpec.secondaryAccentArgb
        row.paint.alpha = splitAlpha
        row.main.draw(canvas)
        canvas.restoreToCount(saveCyan)

        val savePink = canvas.save()
        canvas.translate(jitter, 0.5f * d)
        row.paint.color = themeSpec.accentArgb
        row.paint.alpha = splitAlpha
        row.main.draw(canvas)
        canvas.restoreToCount(savePink)

        row.paint.color = prevColor
        row.paint.alpha = prevAlpha
        row.paint.shader = prevShader
    }

    private fun drawPvWaterReflection(
        canvas: Canvas,
        row: Row,
        contentStartX: Float,
        contentEndX: Float,
        alpha: Float,
        timeSec: Float,
        unit: Float,
    ) {
        if (alpha <= 0.04f || row.line.text.isBlank()) return
        val firstLineEnd = row.main.getLineEnd(0).coerceIn(0, row.line.text.length)
        val label = row.line.text.substring(0, firstLineEnd).trim()
        if (label.isEmpty()) return
        val save = canvas.save()
        val horizonY = row.main.height + 3f * unit
        canvas.translate(sin(timeSec * 2.4f) * 2f * unit, horizonY)
        canvas.scale(1f, -0.52f, (contentStartX + contentEndX) * 0.5f, 0f)
        pvTextPaint.set(row.paint)
        pvTextPaint.clearShadowLayer()
        pvTextPaint.shader = null
        pvTextPaint.color = themeSpec.secondaryAccentArgb
        pvTextPaint.alpha = (alpha * 0.24f * 255).toInt().coerceIn(0, 255)
        pvTextPaint.textAlign = if (row.line.opposite) Paint.Align.RIGHT else Paint.Align.LEFT
        val baseX = if (row.line.opposite) row.main.width.toFloat() else 0f
        canvas.drawText(label, baseX, -row.paint.fontMetrics.descent, pvTextPaint)
        canvas.restoreToCount(save)
    }

    private fun drawPvConstellationNode(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        timeSec: Float,
        index: Int,
        emphasis: Float,
        pvAlpha: Float,
        unit: Float,
    ) {
        val radius = (3.4f + emphasis * 4.4f) * unit
        val angle = timeSec * 28f + index * 37f
        val save = canvas.save()
        canvas.rotate(angle, cx, cy)
        pvPath.reset()
        pvPath.moveTo(cx, cy - radius)
        pvPath.lineTo(cx + radius * 0.28f, cy - radius * 0.28f)
        pvPath.lineTo(cx + radius, cy)
        pvPath.lineTo(cx + radius * 0.28f, cy + radius * 0.28f)
        pvPath.lineTo(cx, cy + radius)
        pvPath.lineTo(cx - radius * 0.28f, cy + radius * 0.28f)
        pvPath.lineTo(cx - radius, cy)
        pvPath.lineTo(cx - radius * 0.28f, cy - radius * 0.28f)
        pvPath.close()
        pvPaint.color = themeSpec.accentArgb
        pvPaint.alpha = ((0.30f + 0.65f * emphasis) * pvAlpha * 255).toInt().coerceIn(0, 255)
        canvas.drawPath(pvPath, pvPaint)
        canvas.restoreToCount(save)
    }

    private fun drawPvVerticalCalligraphyStrip(
        canvas: Canvas,
        row: Row,
        rowTopY: Float,
        alpha: Float,
        unit: Float,
        stripShiftX: Float = 0f,
    ) {
        val clean = row.line.text.filter { !it.isWhitespace() }.take(8)
        if (clean.isEmpty() || alpha <= 0.05f) return
        val stripX = width - max(14f * unit, horizontalInsetDp * 0.45f * d) + stripShiftX
        val charSize = max(10f * unit, 11.5f * d)
        val stepY = charSize * 1.24f
        val topY = rowTopY.coerceIn(18f * unit, max(20f * unit, height - (clean.length + 2) * stepY))
        pvStrokePaint.color = themeSpec.accentArgb
        pvStrokePaint.strokeWidth = max(0.7f, 0.9f * unit)
        pvStrokePaint.alpha = (alpha * 0.26f * 255).toInt().coerceIn(0, 255)
        canvas.drawLine(stripX - charSize * 0.65f, topY - 4f * unit, stripX - charSize * 0.65f, topY + clean.length * stepY + 12f * unit, pvStrokePaint)

        pvTextPaint.set(row.paint)
        pvTextPaint.clearShadowLayer()
        pvTextPaint.shader = null
        pvTextPaint.textSize = charSize
        pvTextPaint.textAlign = Paint.Align.CENTER
        pvTextPaint.color = themeSpec.accentArgb
        pvTextPaint.alpha = (alpha * 0.72f * 255).toInt().coerceIn(0, 255)
        var cy = topY + charSize
        for (ch in clean) {
            canvas.drawText(ch.toString(), stripX, cy, pvTextPaint)
            cy += stepY
        }
        val sealTop = cy + 2f * unit
        val sealSize = max(9f * unit, 10f * d)
        pvRect.set(stripX - sealSize * 0.5f, sealTop, stripX + sealSize * 0.5f, sealTop + sealSize)
        pvPaint.color = themeSpec.secondaryAccentArgb
        pvPaint.alpha = (alpha * 0.78f * 255).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(pvRect, 1.5f * unit, 1.5f * unit, pvPaint)
        pvTextPaint.textSize = sealSize * 0.72f
        pvTextPaint.color = 0xFFFDF3E3.toInt()
        pvTextPaint.alpha = (alpha * 0.95f * 255).toInt().coerceIn(0, 255)
        canvas.drawText("詞", stripX, sealTop + sealSize * 0.78f, pvTextPaint)
    }

}
