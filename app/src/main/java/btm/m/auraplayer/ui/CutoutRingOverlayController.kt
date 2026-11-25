// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.MainActivity
import btm.m.auraplayer.data.CUTOUT_RING_DEFAULT_CUSTOM_COLOR
import btm.m.auraplayer.data.CutoutRingColorMode
import btm.m.auraplayer.data.CutoutRingConfig
import btm.m.auraplayer.data.CutoutRingContent
import btm.m.auraplayer.data.CutoutRingSettings
import btm.m.auraplayer.playback.PlaybackPreferences
import btm.m.auraplayer.playback.PlaybackService
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.pascal.oneuikit.MonetTones
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
private const val TEMP_VOLUME_DURATION_MS = 2_200L
private const val CALIBRATION_PREVIEW_PROGRESS = 0.68f

internal data class CutoutRingPlaybackSnapshot(
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val playbackSpeed: Float,
    val artworkUri: String?,
)

internal data class CutoutRingRenderState(
    val visible: Boolean = false,
    val progress: Float = 0f,
    val accentArgb: Int = CUTOUT_RING_DEFAULT_CUSTOM_COLOR,
    val fastTransition: Boolean = false,
)

class CutoutRingOverlayController(
    private val app: AuraApplication,
    private val store: CutoutRingSettings,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackPrefs = PlaybackPreferences(app)
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val powerManager = app.getSystemService(PowerManager::class.java)

    private val mutableRenderState = MutableStateFlow(CutoutRingRenderState())
    internal val renderState: StateFlow<CutoutRingRenderState> = mutableRenderState.asStateFlow()

    private val mutableSystemOverlayActive = MutableStateFlow(false)
    internal val systemOverlayActive: StateFlow<Boolean> = mutableSystemOverlayActive.asStateFlow()

    @Volatile private var themePrimaryArgb: Int = resolveFallbackThemeAccent()
    @Volatile private var coverAccentArgb: Int? = null
    @Volatile private var coverAccentKey: String? = null
    @Volatile private var uiPlaybackFallback: CutoutRingPlaybackSnapshot? = null
    @Volatile private var calibrationPreview: Boolean = false
    @Volatile private var mainActivityForeground: Boolean = false
    @Volatile private var screenInteractive: Boolean = powerManager?.isInteractive != false
    @Volatile private var lastKnownVolumeFraction: Float = readCurrentVolumeFraction()
    @Volatile private var tempVolumeUntilMs: Long = 0L

    private var receiversRegistered = false
    private var tickJob: Job? = null
    private var coverExtractJob: Job? = null
    private var windowContext: Context? = null
    private var overlayWindowManager: WindowManager? = null
    private var overlayView: CutoutRingOverlayView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var lastAppliedLayout: CutoutRingLayout? = null
    private var lastUsbHudSeq: Long = 0L

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_VOLUME_CHANGED -> {
                    val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                    if (stream == AudioManager.STREAM_MUSIC || stream == -1) {
                        val nextVol = readCurrentVolumeFraction()
                        val changed = abs(nextVol - lastKnownVolumeFraction) > 0.001f
                        lastKnownVolumeFraction = nextVol
                        if (changed) triggerTemporaryVolumeWindow()
                        refreshRenderState()
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractive = true
                    reconcileRunningState()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    reconcileRunningState()
                }
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                mainHandler.post {
                    updateOverlayWindowGeometry(force = true)
                    refreshRenderState()
                }
            }
        }
    }

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    mainActivityForeground = true
                    reconcileRunningState()
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if (activity is MainActivity) {
                    mainActivityForeground = false
                    reconcileRunningState()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        scope.launch {
            store.state.collect {
                reconcileRunningState()
            }
        }
        scope.launch {
            store.previewColor.collect {
                refreshRenderState()
            }
        }
        scope.launch {
            app.effects.usbVolumeHudEvent.collect { event ->
                if (event.seq > lastUsbHudSeq) {
                    lastUsbHudSeq = event.seq
                    lastKnownVolumeFraction = readCurrentVolumeFraction()
                    triggerTemporaryVolumeWindow()
                    refreshRenderState()
                }
            }
        }
        scope.launch {
            app.effects.state.collect {
                if (app.effects.isUsbExclusiveActive) {
                    lastKnownVolumeFraction = readCurrentVolumeFraction()
                    refreshRenderState()
                }
            }
        }
    }

    fun onConfigurationChanged() {
        mainHandler.post {
            if (themePrimaryArgb == 0) themePrimaryArgb = resolveFallbackThemeAccent()
            updateOverlayWindowGeometry(force = true)
            refreshRenderState()
        }
    }

    fun onOverlayPermissionMayHaveChanged() {
        mainHandler.post { reconcileRunningState() }
    }

    fun updateThemePrimaryColor(argb: Int) {
        val opaque = argb or 0xFF000000.toInt()
        if (themePrimaryArgb != opaque) {
            themePrimaryArgb = opaque
            refreshRenderState()
        }
    }

    internal fun updateUiPlaybackFallback(snapshot: CutoutRingPlaybackSnapshot?) {
        uiPlaybackFallback = snapshot
        if (store.state.value.enabled) refreshRenderState()
    }

    fun setCalibrationPreview(active: Boolean) {
        if (calibrationPreview != active) {
            calibrationPreview = active
            reconcileRunningState()
        }
    }

    fun notifyPlaybackChanged() {
        if (!store.state.value.enabled) return
        mainHandler.post { reconcileRunningState() }
    }

    private fun triggerTemporaryVolumeWindow() {
        val config = store.state.value
        if (config.enabled && config.content != CutoutRingContent.VOLUME && config.temporarilyShowVolume) {
            tempVolumeUntilMs = SystemClock.elapsedRealtime() + TEMP_VOLUME_DURATION_MS
            ensureTickLoopIfNeeded()
        }
    }

    private fun reconcileRunningState() {
        val config = store.state.value
        if (!config.enabled) {
            app.effects.outputWaveform.levelEnabled = false
            stopTickLoop()
            unregisterReceivers()
            detachSystemOverlay()
            mutableRenderState.value = mutableRenderState.value.copy(visible = false)
            return
        }

        registerReceivers()
        val canDrawOutside = config.showOutsideApp && Settings.canDrawOverlays(app)
        if (canDrawOutside) {
            attachOrUpdateSystemOverlay(config)
        } else {
            detachSystemOverlay()
        }

        refreshRenderState()
        ensureTickLoopIfNeeded()
    }

    private fun ensureTickLoopIfNeeded() {
        val config = store.state.value
        val activeSurface = mutableSystemOverlayActive.value || mainActivityForeground
        val shouldPoll = config.enabled && screenInteractive && activeSurface
        val needsAudioLevel = shouldPoll && config.content == CutoutRingContent.AUDIO_LEVEL
        app.effects.outputWaveform.levelEnabled = needsAudioLevel

        if (!shouldPoll) {
            stopTickLoop()
            return
        }
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                refreshRenderState()
                val currentConfig = store.state.value
                val inTempVol = SystemClock.elapsedRealtime() < tempVolumeUntilMs
                val delayMs = when {
                    !currentConfig.enabled || !screenInteractive -> 500L
                    inTempVol -> 80L
                    currentConfig.content == CutoutRingContent.AUDIO_LEVEL -> 33L
                    currentConfig.content == CutoutRingContent.SONG_PROGRESS -> 100L
                    currentConfig.content == CutoutRingContent.SLEEP_TIMER -> 400L
                    else -> 250L
                }
                delay(delayMs)
            }
        }
    }

    private fun stopTickLoop() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_VOLUME_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching { app.registerReceiver(systemReceiver, filter, Context.RECEIVER_EXPORTED) }
        runCatching {
            app.getSystemService(DisplayManager::class.java)
                ?.registerDisplayListener(displayListener, mainHandler)
        }
        receiversRegistered = true
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        runCatching { app.unregisterReceiver(systemReceiver) }
        runCatching {
            app.getSystemService(DisplayManager::class.java)
                ?.unregisterDisplayListener(displayListener)
        }
        receiversRegistered = false
    }

    private fun attachOrUpdateSystemOverlay(config: CutoutRingConfig) {
        if (overlayView == null) {
            val dm = app.getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val ctx = if (display != null) {
                runCatching {
                    app.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                }.getOrDefault(app)
            } else app
            val wm = ctx.getSystemService(WindowManager::class.java) ?: return
            val metrics = currentScreenMetrics(wm)
            val layout = computeCutoutRingLayout(config, metrics.widthPx, metrics.density)
            val params = WindowManager.LayoutParams(
                layout.windowWidthPx,
                layout.windowHeightPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = layout.windowLeftPx
                y = layout.windowTopPx
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
                title = "AuraCutoutRing"
            }
            val view = CutoutRingOverlayView(ctx).apply {
                updateConfigAndLayout(config, layout)
            }
            val added = runCatching { wm.addView(view, params) }.isSuccess
            if (!added) {
                mutableSystemOverlayActive.value = false
                return
            }
            windowContext = ctx
            overlayWindowManager = wm
            overlayView = view
            overlayLayoutParams = params
            lastAppliedLayout = layout
            mutableSystemOverlayActive.value = true
        } else {
            updateOverlayWindowGeometry(force = false)
        }
    }

    private fun updateOverlayWindowGeometry(force: Boolean) {
        val view = overlayView ?: return
        val wm = overlayWindowManager ?: return
        val params = overlayLayoutParams ?: return
        val config = store.state.value
        val metrics = currentScreenMetrics(wm)
        val layout = computeCutoutRingLayout(config, metrics.widthPx, metrics.density)
        view.updateConfigAndLayout(config, layout)
        if (force || layout != lastAppliedLayout) {
            params.width = layout.windowWidthPx
            params.height = layout.windowHeightPx
            params.x = layout.windowLeftPx
            params.y = layout.windowTopPx
            runCatching { wm.updateViewLayout(view, params) }
            lastAppliedLayout = layout
        }
    }

    private fun detachSystemOverlay() {
        val view = overlayView
        val wm = overlayWindowManager
        overlayView = null
        overlayWindowManager = null
        overlayLayoutParams = null
        windowContext = null
        lastAppliedLayout = null
        mutableSystemOverlayActive.value = false
        if (view != null && wm != null) {
            view.stopAnimating()
            runCatching { wm.removeViewImmediate(view) }
        }
    }

    private fun refreshRenderState() {
        val config = store.state.value
        if (!config.enabled || !screenInteractive) {
            val hidden = mutableRenderState.value.copy(visible = false)
            mutableRenderState.value = hidden
            overlayView?.updateTarget(config, hidden)
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        val tempVolumeActive = config.content != CutoutRingContent.VOLUME &&
            config.temporarilyShowVolume &&
            nowMs < tempVolumeUntilMs

        val playback = PlaybackService.activeInstance?.currentCutoutRingPlayback() ?: uiPlaybackFallback
        val isPlaying = playback?.isPlaying == true

        if (config.colorMode == CutoutRingColorMode.FOLLOW_COVER) {
            ensureCoverAccentExtracted(playback?.artworkUri)
        }

        val isLandscape = isCurrentDisplayLandscape()
        val hideForLandscape = config.hideInLandscape && isLandscape && !calibrationPreview
        val hideForPause = config.onlyWhenPlaying &&
            !isPlaying &&
            !calibrationPreview &&
            !tempVolumeActive &&
            config.content != CutoutRingContent.VOLUME &&
            !(config.content == CutoutRingContent.SLEEP_TIMER && playbackPrefs.sleepDeadline > nowMs)

        val visible = !hideForLandscape && !hideForPause

        val progress = if (tempVolumeActive) {
            readCurrentVolumeFraction()
        } else {
            when (config.content) {
                CutoutRingContent.SONG_PROGRESS -> {
                    val dur = playback?.durationMs ?: 0L
                    val pos = playback?.positionMs ?: 0L
                    val raw = if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
                    if (calibrationPreview && raw < 0.02f && !isPlaying) CALIBRATION_PREVIEW_PROGRESS else raw
                }
                CutoutRingContent.VOLUME -> readCurrentVolumeFraction()
                CutoutRingContent.AUDIO_LEVEL -> {
                    if (isPlaying) {
                        app.effects.outputWaveform.level.coerceIn(0f, 1f)
                    } else if (calibrationPreview) {
                        0.45f + 0.25f * sin(nowMs / 420.0).toFloat()
                    } else {
                        0f
                    }
                }
                CutoutRingContent.SLEEP_TIMER -> {
                    val deadline = playbackPrefs.sleepDeadline
                    val totalMs = (playbackPrefs.sleepMinutes.coerceAtLeast(1) * 60_000L).toFloat()
                    val remaining = (deadline - nowMs).coerceAtLeast(0L)
                    if (deadline > nowMs && totalMs > 0f) {
                        (remaining / totalMs).coerceIn(0f, 1f)
                    } else if (calibrationPreview) {
                        CALIBRATION_PREVIEW_PROGRESS
                    } else {
                        0f
                    }
                }
            }
        }

        val accentArgb = resolveRingColor(config)
        val fastTransition = tempVolumeActive ||
            config.content == CutoutRingContent.VOLUME ||
            config.content == CutoutRingContent.AUDIO_LEVEL

        val next = CutoutRingRenderState(
            visible = visible,
            progress = progress.coerceIn(0f, 1f),
            accentArgb = accentArgb,
            fastTransition = fastTransition,
        )
        mutableRenderState.value = next
        overlayView?.updateTarget(config, next)
    }

    private fun resolveRingColor(config: CutoutRingConfig): Int {
        store.previewColor.value?.let { return it }
        return when (config.colorMode) {
            CutoutRingColorMode.CUSTOM -> config.customColor
            CutoutRingColorMode.FOLLOW_COVER -> coverAccentArgb ?: themePrimaryArgb
            CutoutRingColorMode.FOLLOW_THEME -> themePrimaryArgb
        }
    }

    private fun ensureCoverAccentExtracted(artworkUri: String?) {
        if (artworkUri.isNullOrBlank()) {
            coverAccentKey = null
            coverAccentArgb = null
            return
        }
        if (artworkUri == coverAccentKey) return
        coverAccentKey = artworkUri
        coverExtractJob?.cancel()
        coverExtractJob = scope.launch {
            val extracted = withContext(Dispatchers.IO) {
                runCatching {
                    val loader = SingletonImageLoader.get(app)
                    val request = ImageRequest.Builder(app)
                        .data(artworkUri)
                        .size(64, 64)
                        .allowHardware(false)
                        .build()
                    val result = loader.execute(request) as? SuccessResult ?: return@runCatching null
                    val bitmap = result.image.toBitmap()
                    val seed = OklabThemeEngine.extractOklabSeed(bitmap, themePrimaryArgb)
                    // Ensure vivid visibility around the dark camera punch-hole.
                    val hsv = FloatArray(3)
                    Color.colorToHSV(seed, hsv)
                    hsv[1] = hsv[1].coerceAtLeast(0.45f)
                    hsv[2] = hsv[2].coerceAtLeast(0.78f)
                    Color.HSVToColor(0xFF, hsv)
                }.getOrNull()
            }
            if (coverAccentKey == artworkUri) {
                coverAccentArgb = extracted
                refreshRenderState()
            }
        }
    }

    private fun readCurrentVolumeFraction(): Float {
        if (app.effects.isUsbExclusiveActive) {
            return app.effects.state.value.usbVolume.coerceIn(0f, 1f)
        }
        val am = audioManager ?: return 0.5f
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        return (cur.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }

    private data class ScreenMetrics(val widthPx: Int, val heightPx: Int, val density: Float)

    private fun currentScreenMetrics(wm: WindowManager? = overlayWindowManager): ScreenMetrics {
        val activeWm = wm ?: app.getSystemService(WindowManager::class.java)
        val bounds = activeWm?.currentWindowMetrics?.bounds
        val dm = app.resources.displayMetrics
        val w = bounds?.width()?.takeIf { it > 0 } ?: dm.widthPixels
        val h = bounds?.height()?.takeIf { it > 0 } ?: dm.heightPixels
        return ScreenMetrics(w, h, dm.density)
    }

    private fun isCurrentDisplayLandscape(): Boolean {
        val metrics = currentScreenMetrics()
        if (metrics.widthPx != metrics.heightPx) {
            return metrics.widthPx > metrics.heightPx
        }
        return app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun resolveFallbackThemeAccent(): Int {
        val appearance = app.settings.value
        val systemFallback = MonetTones.seedOf(app).takeIf { it != 0 }
            ?: runCatching { app.getColor(android.R.color.system_accent1_500) }
                .getOrDefault(CUTOUT_RING_DEFAULT_CUSTOM_COLOR)
        return when (appearance.color) {
            "custom" -> appearance.customColor
            "cover" -> coverAccentArgb ?: systemFallback
            "system" -> systemFallback
            else -> colorPresets.firstOrNull { it.first == appearance.color }?.third?.toInt()
                ?: CUTOUT_RING_DEFAULT_CUSTOM_COLOR
        } or 0xFF000000.toInt()
    }
}

private class CutoutRingOverlayView(context: Context) : View(context) {
    private val drawer = CutoutRingDrawer()
    private var config = CutoutRingConfig()
    private var layout = CutoutRingLayout(0f, 0f, 1f, 1f, 2f, 4f)
    private var target = CutoutRingRenderState()

    private var currentProgress = 0f
    private var currentAlpha = 0f
    private var currentColor = CUTOUT_RING_DEFAULT_CUSTOM_COLOR
    private var lastFrameNanos = 0L
    private var animating = false

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val frameRunnable = Runnable {
        animating = false
        stepAndDraw()
    }

    fun updateConfigAndLayout(nextConfig: CutoutRingConfig, nextLayout: CutoutRingLayout) {
        val glowChanged = config.glowEnabled != nextConfig.glowEnabled
        config = nextConfig
        layout = nextLayout
        if (glowChanged) {
            setLayerType(if (nextConfig.glowEnabled) LAYER_TYPE_SOFTWARE else LAYER_TYPE_HARDWARE, null)
        }
        scheduleAnimation()
    }

    fun updateTarget(nextConfig: CutoutRingConfig, nextTarget: CutoutRingRenderState) {
        if (config.glowEnabled != nextConfig.glowEnabled) {
            setLayerType(if (nextConfig.glowEnabled) LAYER_TYPE_SOFTWARE else LAYER_TYPE_HARDWARE, null)
        }
        config = nextConfig
        target = nextTarget
        if (nextTarget.visible && visibility != VISIBLE) {
            visibility = VISIBLE
        }
        scheduleAnimation()
    }

    fun stopAnimating() {
        removeCallbacks(frameRunnable)
        animating = false
    }

    private fun scheduleAnimation() {
        if (!animating) {
            animating = true
            lastFrameNanos = System.nanoTime()
            postOnAnimation(frameRunnable)
        }
        invalidate()
    }

    private fun stepAndDraw() {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastFrameNanos = now

        val targetAlpha = if (target.visible) 1f else 0f
        val alphaRate = if (target.visible) 14f else 11f
        val progressRate = if (target.fastTransition) 20f else 12f
        val colorRate = 10f

        currentAlpha = expLerp(currentAlpha, targetAlpha, alphaRate, dt)
        currentProgress = expLerp(currentProgress, target.progress, progressRate, dt)
        currentColor = lerpArgb(currentColor, target.accentArgb, 1f - exp(-colorRate * dt))

        val alphaSettled = abs(currentAlpha - targetAlpha) < 0.004f
        val progressSettled = abs(currentProgress - target.progress) < 0.0015f
        val colorSettled = currentColor == target.accentArgb

        if (alphaSettled) currentAlpha = targetAlpha
        if (progressSettled) currentProgress = target.progress
        if (colorSettled) currentColor = target.accentArgb

        if (currentAlpha <= 0.002f && !target.visible) {
            if (visibility != GONE) visibility = GONE
            return
        }

        invalidate()
        if (!alphaSettled || !progressSettled || !colorSettled) {
            animating = true
            postOnAnimation(frameRunnable)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentAlpha <= 0.003f) return
        val localLeft = layout.leftPx - layout.windowLeftPx
        val localTop = layout.topPx - layout.windowTopPx
        drawer.draw(
            canvas = canvas,
            config = config,
            left = localLeft,
            top = localTop,
            width = layout.widthPx,
            height = layout.heightPx,
            strokePx = layout.strokePx,
            progress = currentProgress,
            accentArgb = currentColor,
            alphaMultiplier = currentAlpha,
        )
    }
}

@Composable
internal fun InAppCutoutRingOverlay() {
    val context = LocalContext.current
    val app = context.applicationContext as AuraApplication
    val config by app.cutoutRing.state.collectAsState()
    val systemActive by app.cutoutRingController.systemOverlayActive.collectAsState()
    if (!config.enabled || systemActive) return

    val target by app.cutoutRingController.renderState.collectAsState()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val drawer = remember { CutoutRingDrawer() }

    var displayedAlpha by remember { mutableFloatStateOf(if (target.visible) 1f else 0f) }
    var displayedProgress by remember { mutableFloatStateOf(target.progress) }
    var displayedColor by remember { mutableIntStateOf(target.accentArgb) }

    LaunchedEffect(target) {
        var lastNanos = 0L
        while (true) {
            val targetAlpha = if (target.visible) 1f else 0f
            val alphaSettled = abs(displayedAlpha - targetAlpha) < 0.004f
            val progressSettled = abs(displayedProgress - target.progress) < 0.0015f
            val colorSettled = displayedColor == target.accentArgb
            if (alphaSettled && progressSettled && colorSettled) {
                displayedAlpha = targetAlpha
                displayedProgress = target.progress
                displayedColor = target.accentArgb
                break
            }
            withFrameNanos { frameNanos ->
                val dt = if (lastNanos == 0L) 0.016f
                else ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastNanos = frameNanos
                val alphaRate = if (target.visible) 14f else 11f
                val progressRate = if (target.fastTransition) 20f else 12f
                displayedAlpha = expLerp(displayedAlpha, targetAlpha, alphaRate, dt)
                displayedProgress = expLerp(displayedProgress, target.progress, progressRate, dt)
                displayedColor = lerpArgb(displayedColor, target.accentArgb, 1f - exp(-10f * dt))
            }
        }
    }

    if (displayedAlpha <= 0.003f) return

    ComposeCanvas(modifier = Modifier.fillMaxSize()) {
        val screenWidthPx = size.width.roundToInt().coerceAtLeast(1)
        val ringLayout = computeCutoutRingLayout(config, screenWidthPx, density)
        // Touch configuration so rotation forces layout recomputation.
        configuration.orientation
        drawIntoCanvas { composeCanvas ->
            drawer.draw(
                canvas = composeCanvas.nativeCanvas,
                config = config,
                left = ringLayout.leftPx,
                top = ringLayout.topPx,
                width = ringLayout.widthPx,
                height = ringLayout.heightPx,
                strokePx = ringLayout.strokePx,
                progress = displayedProgress,
                accentArgb = displayedColor,
                alphaMultiplier = displayedAlpha,
            )
        }
    }
}

private fun expLerp(current: Float, target: Float, rate: Float, dt: Float): Float {
    val factor = 1f - exp(-rate * dt)
    return current + (target - current) * factor
}

private fun lerpArgb(start: Int, end: Int, fraction: Float): Int {
    val t = fraction.coerceIn(0f, 1f)
    val sr = (start ushr 16) and 0xFF
    val sg = (start ushr 8) and 0xFF
    val sb = start and 0xFF
    val er = (end ushr 16) and 0xFF
    val eg = (end ushr 8) and 0xFF
    val eb = end and 0xFF
    val r = (sr + (er - sr) * t).roundToInt().coerceIn(0, 255)
    val g = (sg + (eg - sg) * t).roundToInt().coerceIn(0, 255)
    val b = (sb + (eb - sb) * t).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
