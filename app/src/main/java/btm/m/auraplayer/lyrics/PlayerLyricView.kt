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
import kotlinx.coroutines.*
import kotlin.math.*

/** Uses the original per-row springs, stagger, emphasis, depth blur and syllable renderer. */
class PlayerLyricView(context: Context) : View(context) {
    private data class Row(val line: LyricLine, val main: StaticLayout, val below: StaticLayout?,
                           val paint: TextPaint, val underPaint: TextPaint, val xs: FloatArray,
                           val glow: LyricGlowMask?, val bg: StaticLayout?, val bgPaint: TextPaint,
                           val bgXs: FloatArray, val bgGlow: LyricGlowMask?, val height: Float)
    private var scope: CoroutineScope? = null
    private var buildJob: Job? = null
    private var rows = emptyList<Row>()
    private var source = emptyList<LyricLine>()
    private var requestedSource = emptyList<LyricLine>()
    private var below = 3
    private var lyricScale = 1f
    private var generation = 0
    private var builtWidth = 0
    private var builtMode = -1
    private var builtScale = 0f
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
    private val colors = object : LyricWordRenderer.Colors {
        override fun ink(alpha: Float, emphasis: Float, backing: Boolean) = Color.pack(1f, 1f, 1f, alpha.coerceIn(0f, 1f))
        override fun white(alpha: Float, gain: Float) = Color.pack(1f, 1f, 1f, alpha.coerceIn(0f, 1f))
        override fun glowGain(glow: Float) = 1f
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); rebuild(); invalidate() }
    override fun onDetachedFromWindow() { longPressHandler.removeCallbacks(longPressAction); keepScreenOn = false; scope?.cancel(); scope = null; pending.clear(); super.onDetachedFromWindow() }
    fun submit(lines: List<LyricLine>, translation: Boolean, pronunciation: Boolean, textScale: Float = 1f) {
        val mode = (if (translation) 1 else 0) or (if (pronunciation) 2 else 0)
        val scale = textScale.coerceIn(.5f, 1.5f)
        if (requestedSource === lines && below == mode && lyricScale == scale) return
        requestedSource = lines; below = mode; lyricScale = scale; rebuild()
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
    private fun paint(size: Float, weight: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * sd; color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, weight, false)
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
        // Retain the layout and appearance when the same content is attached again unchanged.
        if (source === lines && builtWidth == width && builtMode == mode && builtScale == scale) return
        val gen = ++generation
        val targetWidth = width
        val viewportHeight = height.toFloat()
        val positionMs = (playhead + if (playing) SystemClock.uptimeMillis() - sampledAt else 0L)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val textWidth = max(1, width - (52 * d).toInt())
        buildJob?.cancel()
        buildJob = owner.launch {
            val (built, prepared) = withContext(Dispatchers.Default) {
                val built = lines.map { line ->
                    ensureActive()
                    val p = paint(30f * scale, 700); val tp = paint(17f * scale, 500)
                    val main = layout(line.text, p, textWidth, line.opposite)
                    val under = line.under(mode)?.let { layout(it, tp, textWidth, line.opposite) }
                    val bp = paint(17f * scale, 500)
                    val bg = line.bg?.let { layout(it.text, bp, textWidth, line.opposite) }
                    val bgXs = line.bg?.let { b -> FloatArray(b.text.length + 1) { bg!!.getPrimaryHorizontal(it) } } ?: FloatArray(0)
                    val bgGlow = line.bg?.takeIf { it.hasWords() }?.let { LyricGlowMask.build(it, bg, bgXs, TextPaint(bp), 5f * d, LyricWordRenderer.GLOW_MIN_MS) }
                    val xs = FloatArray(line.text.length + 1) { main.getPrimaryHorizontal(it) }
                    val glow = if (line.hasWords()) LyricGlowMask.build(line, main, xs, TextPaint(p), 5f * d, LyricWordRenderer.GLOW_MIN_MS) else null
                    Row(line, main, under, p, tp, xs, glow, bg, bp, bgXs, bgGlow,
                        main.height + (if (bg == null) 0f else bg.height + 3 * d) + (if (under == null) 0f else under.height + 5 * d))
                }
                val prepared = linkedMapOf<String, Bitmap>()
                for ((i, distance) in initialLyricMaskDepths(lines, FloatArray(built.size) { built[it].height },
                    positionMs, viewportHeight, 22 * d, 40 * d)) {
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
            heights = FloatArray(rows.size) { rows[it].height }
            var y = 0f
            base = FloatArray(rows.size) { i -> y.also { y += heights[i] + 22 * d } }
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
        if (primeAppearance) {
            for (i in rows.indices) {
                val selected = if (visualFocus >= 0) i == visualFocus else singing.contains(i)
                val distance = if (visualFocus >= 0) abs(i - visualFocus) else singing.distance(i)
                emphasis[i] = if (selected) 1f else 0f
                lit[i] = if (selected && (visualFocus >= 0 || ms >= rows[i].line.start)) 1f else 0f
                scales[i] = if (selected) 1f else .97f
                blurs[i] = LyricMotion.depthBlur(distance, d)
            }
            primeAppearance = false
        }
        for ((i, row) in rows.withIndex()) {
            moving = LyricMotion.step(i, scroll, aim, next, velocity, aimAt, now, dt, false, springK, c, height * LyricMotion.SEEK_SPEED_BANDS) != 0 || moving
            val focused = if (visualFocus >= 0) i == visualFocus else singing.contains(i)
            val focusDistance = if (visualFocus >= 0) abs(i - visualFocus) else singing.distance(i)
            moving = LyricMotion.appearance(i, emphasis, lit, scales, blurs, focused, visualFocus >= 0, ms, row.line.start,
                now >= aimAt[i], dt, singing.count, heights[i], height.toFloat(), LyricMotion.depthBlur(focusDistance, d)) != 0 || moving
            val y = anchor + singing.position(i, base) - scroll[i] + manual
            if (y + row.height < -40 * d || y > height + 40 * d) continue
            if (i == browseSelected && browseAlpha > 0f) {
                browsePaint.color = Color.WHITE
                browsePaint.alpha = (browseAlpha * .10f * 255).toInt()
                canvas.drawRoundRect(12 * d, y - 8 * d, width - 12 * d, y + row.height + 8 * d,
                    16 * d, 16 * d, browsePaint)
                timestampPaint.alpha = (browseAlpha * .85f * 255).toInt()
                val metrics = timestampPaint.fontMetrics
                val seconds = row.line.start.coerceAtLeast(0) / 1000
                val stamp = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                canvas.drawText(stamp, width - 26 * d, y + row.height / 2 - (metrics.ascent + metrics.descent) / 2, timestampPaint)
            }
            // The shader fades/defocuses pixels individually, including partial text rows.
            val alpha = (.4f + .6f * lit[i]) * transition.alpha(i)
            val save = canvas.save()
            canvas.translate(26 * d, y); canvas.scale(scales[i], scales[i], 0f, row.height / 2)
            val distance = focusDistance.coerceIn(1, 4)
            // Blend the cached depth mask and the sharp glyphs; no abrupt bitmap swap.
            val blurred = if (blurs[i] > .01f * d) blurred(i, distance) else null
            val readiness = maskBlend.amount("$i:$distance", blurred != null, dt)
            if (blurred != null && readiness < 1f) moving = true
            val blurMix = (blurs[i] / LyricMotion.depthBlur(distance, d)).coerceIn(0f, 1f) * readiness
            if (blurred != null) {
                imagePaint.alpha = (alpha * blurMix * 255).toInt().coerceIn(0, 255)
                val pad = 16 * d
                canvas.drawBitmap(blurred, null, RectF(-pad, -pad, row.main.width + pad, row.height + pad), imagePaint)
            }
            if (blurMix < .999f) {
                val sharpAlpha = alpha * (1f - blurMix)
                row.paint.alpha = 255
                if (row.line.hasWords() && focused && visualFocus < 0) words.draw(canvas, row.line, row.main, row.paint, row.xs,
                    emphasis[i], sharpAlpha, sharpAlpha * .4f, 1.8f * d, true, 0, ms, row.glow, colors)
                else { row.paint.color = Color.WHITE; row.paint.alpha = (sharpAlpha * 255).toInt(); row.main.draw(canvas) }
                var underY = row.main.height.toFloat()
                row.bg?.let { bg ->
                    val saveBg = canvas.save()
                    canvas.translate(0f, underY + 3 * d)
                    val backing = row.line.bg
                    if (backing.hasWords() && focused && visualFocus < 0) words.draw(canvas, backing, bg, row.bgPaint, row.bgXs,
                        emphasis[i], sharpAlpha * .8f, sharpAlpha * .32f, 1.08f * d, true, 1, ms, row.bgGlow, colors)
                    else { row.bgPaint.color = Color.WHITE; row.bgPaint.alpha = (sharpAlpha * .8f * 255).toInt(); bg.draw(canvas) }
                    canvas.restoreToCount(saveBg)
                    underY += bg.height + 3 * d
                }
                row.below?.let {
                    canvas.translate(0f, underY + 5 * d)
                    row.underPaint.color = Color.WHITE; row.underPaint.alpha = (sharpAlpha * .62f * 255).toInt()
                    it.draw(canvas)
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
        val pad = (16 * d).toInt(); val radius = LyricMotion.depthBlur(distance, d)
        val w = row.main.width + pad * 2; val h = row.height.toInt() + pad * 2
        val image = Bitmap.createBitmap(max(1, w / down), max(1, h / down), Bitmap.Config.ARGB_8888)
        val out = Canvas(image); out.scale(1f / down, 1f / down); out.translate(pad.toFloat(), pad.toFloat())
        val p = TextPaint(row.paint).apply { color = Color.WHITE; alpha = 255; maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }
        val tp = TextPaint(row.underPaint).apply { alpha = 158; maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }
        layout(row.line.text, p, row.main.width, row.line.opposite).draw(out)
        var underY = row.main.height.toFloat()
        row.line.bg?.let { bg ->
            val saved = out.save(); out.translate(0f, underY + 3 * d)
            val bp = TextPaint(row.bgPaint).apply { alpha = 204; maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }
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
        return RectF(loc[0] + 26f * d, top, loc[0] + width - 26f * d, top + row.height)
    }

    fun viewportBoundsInWindow(): RectF {
        val loc = IntArray(2)
        getLocationInWindow(loc)
        return RectF(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + width).toFloat(), (loc[1] + height).toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y; lastY = event.y; dragging = false
                longPressTriggered = false
                longPressHandler.removeCallbacks(longPressAction)
                longPressRow = rowAt(event.y)
                if (longPressRow >= 0) longPressHandler.postDelayed(longPressAction, 520L)
                if (manualUntil > SystemClock.uptimeMillis()) manualUntil = SystemClock.uptimeMillis() + 3000
                parent?.requestDisallowInterceptTouchEvent(true); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.y - downY) > 8 * d) dragging = true
                if (dragging) longPressHandler.removeCallbacks(longPressAction)
                if (dragging) { manual += event.y - lastY; manual = manual.coerceIn(-max(0f, (base.lastOrNull() ?: 0f) - (base.getOrNull(focus) ?: 0f)), base.getOrNull(focus) ?: 0f); manualUntil = SystemClock.uptimeMillis() + 3000; invalidate() }
                lastY = event.y; return true
            }
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressAction)
                if (dragging) manualUntil = SystemClock.uptimeMillis() + 3000
                else if (!longPressTriggered) {
                    val hit = rowAt(event.y)
                    if (hit >= 0) { onSeek(source[hit].start.toLong()); manual = 0f; manualUntil = 0L }
                    performClick()
                }
                longPressTriggered = false; longPressRow = -1
                dragging = false; invalidate()
                parent?.requestDisallowInterceptTouchEvent(false); return true
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressAction); longPressTriggered = false; longPressRow = -1
                if (dragging) manualUntil = SystemClock.uptimeMillis() + 3000
                dragging = false; invalidate(); parent?.requestDisallowInterceptTouchEvent(false); return true
            }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }

}
