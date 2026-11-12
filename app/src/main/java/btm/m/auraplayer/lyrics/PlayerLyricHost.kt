package btm.m.auraplayer.lyrics

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.FrameLayout

/** A bounded attached HDR panel isolates extended-range lyrics from the SDR glass UI.
 * Ordinary devices and failed panel creation retain the same inline renderer. No overlay permission. */
class PlayerLyricHost(context: Context) : FrameLayout(context) {
    val lyrics = PlayerLyricView(context)
    private val manager = context.getSystemService(WindowManager::class.java)
    private var panel: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var requested = false
    private var failed = false
    private var hdrOn = false
    private val coordinates = IntArray(2)
    private val preDraw = ViewTreeObserver.OnPreDrawListener { synchronizePanel(); true }

    init { addView(lyrics, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

    fun configure(hdr: Boolean, keepAwake: Boolean) {
        requested = hdr
        keepScreenOn = keepAwake
        if (!hdr) releasePanel()
        if (isAttachedToWindow) synchronizePanel()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnPreDrawListener(preDraw) }
    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDraw)
        requested = false; releasePanel(); super.onDetachedFromWindow()
    }

    private fun synchronizePanel() {
        if (!requested || failed || !isAttachedToWindow || width <= 0 || height <= 0 || !isShown) return
        if (display?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() != true) return
        getLocationOnScreen(coordinates)
        if (panel == null) {
            val holder = FrameLayout(context)
            val lp = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT).apply {
                token = windowToken; gravity = Gravity.TOP or Gravity.LEFT
                x = coordinates[0]; y = coordinates[1]
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                title = "AuraLyrics"
            }
            try {
                removeView(lyrics)
                holder.addView(lyrics, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                manager.addView(holder, lp)
                panel = holder; params = lp
            } catch (_: RuntimeException) {
                (lyrics.parent as? android.view.ViewGroup)?.removeView(lyrics)
                runCatching { manager.removeView(holder) }
                addView(lyrics); failed = true; return
            }
        }
        val lp = params ?: return
        val wantHdr = lyrics.hdrHighlightWanted()
        val changed = lp.x != coordinates[0] || lp.y != coordinates[1] || lp.width != width || lp.height != height || wantHdr != hdrOn
        if (changed) {
            lp.x = coordinates[0]; lp.y = coordinates[1]; lp.width = width; lp.height = height
            lp.colorMode = if (wantHdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
            if (Build.VERSION.SDK_INT >= 35) lp.desiredHdrHeadroom = if (wantHdr) 3f else 1f
            try { manager.updateViewLayout(panel, lp); hdrOn = wantHdr }
            catch (_: RuntimeException) { failed = true; releasePanel(); return }
        }
        lyrics.hdrEnabled = hdrOn
    }

    // The panel is non-touchable: route the inline host's local events to the same renderer.
    override fun onTouchEvent(event: MotionEvent): Boolean = if (panel != null) lyrics.dispatchTouchEvent(event) else super.onTouchEvent(event)

    private fun releasePanel() {
        val holder = panel ?: return
        panel = null; params = null; hdrOn = false; lyrics.hdrEnabled = false
        holder.removeView(lyrics)
        runCatching { manager.removeView(holder) }
        addView(lyrics, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}
