package btm.m.auraplayer.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import btm.m.auraplayer.audio.usb.UsbAudioDevice
import btm.m.auraplayer.audio.usb.UsbExclusiveStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

data class EffectsState(
    val selected: String = "reference",
    val style: SoundStyle = SoundStyle.ORIGINAL,
    val custom: DspConfig = DspConfig(bands = listOf(60.0, 250.0, 1000.0, 4000.0, 12000.0).map { EqBand(it, 0.0) }),
    val imported: List<DspPreset> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val recent: Map<String, Long> = emptyMap(),
    val fade: Boolean = false,
    val fadeMs: Int = 600,
    val alwaysPlay: Boolean = false,
    val nativePreviousBehavior: Boolean = true,
    val usbExclusive: Boolean = false,
    val usbBufferMs: Int = 100,
    val usbLockFixedFormat: Boolean = false,
    val usbVolume: Float = 0.30f,
    val usbVolumeCollapseOnRepeat: Boolean = true,
    val usbVolumePosition: String = "right",
    val usbVolumeOffsetY: Int = 0,
    val usbVolumeOffsetX: Int = 0,
    val usbVolumeHeightScale: Int = 0,
    val usbVolumeWidthScale: Int = 0,
    val draftDirty: Boolean = false,
    val draftBase: String = "reference",
    val draftOriginal: DspConfig? = null,
    val pendingSelection: String? = null,
) {
    val presets get() = BuiltInPresets.all + BuiltInPresets.smart + DspPreset("custom", "Custom", "", custom) + imported
    val current get() = presets.firstOrNull { it.id == selected } ?: BuiltInPresets.all.first()
    val config get() = BuiltInPresets.styled(current.config, style)
    val featured get() = featuredPresets(presets, selected, counts, recent)
    val editableConfig get() = (if (selected == "custom") custom else current.config).let {
        if (it.bands.isEmpty()) it.copy(bands = listOf(60.0, 250.0, 1000.0, 4000.0, 12000.0).map { hz -> EqBand(hz, 0.0) }) else it
    }
    fun edited(c: DspConfig): EffectsState {
        val original = if (selected == "custom") draftOriginal ?: custom else current.config
        return copy(custom = c.validate(), selected = "custom", draftOriginal = original,
            draftBase = if (selected == "custom") draftBase else selected, draftDirty = c != original)
    }
    fun requestPreset(id: String, now: Long): EffectsState {
        require(id == "__defaults__" || presets.any { it.id == id })
        if (id == "custom") return copy(selected = "custom")
        return if (draftDirty && id != "custom") copy(pendingSelection = id) else applyPreset(id, now)
    }
    fun applyPreset(id: String, now: Long): EffectsState {
        if (id == "__defaults__") return copy(selected = "reference", style = SoundStyle.ORIGINAL, custom = EffectsState().custom,
            draftDirty = false, draftBase = "reference", draftOriginal = null, pendingSelection = null)
        return copy(selected = id, draftDirty = false, draftOriginal = null, pendingSelection = null,
            counts = counts + (id to ((counts[id] ?: 0) + 1).coerceAtMost(1000000)), recent = recent + (id to now))
    }
    fun savedDraft(name: String, now: Long): EffectsState {
        require(name.isNotBlank() && name.trim().length <= 80 && imported.size < 100)
        val p = DspPreset("imported-${java.util.UUID.randomUUID()}", name.trim(), "", custom.validate())
        return copy(imported = imported + p, selected = p.id, draftDirty = false, draftOriginal = null,
            counts = counts + (p.id to 1), recent = recent + (p.id to now))
    }
}

data class UsbVolumeHudEvent(
    val seq: Long = 0L,
    val isBoundary: Boolean = false,
    val isUpper: Boolean = true,
    val isPreview: Boolean = false,
)

/** Application-owned immutable snapshots. No SharedPreferences access on ExoPlayer's audio thread. */
class EffectsStore(context: Context) {
    private val appContext = context.applicationContext
    val outputWaveform = OutputWaveform()
    @Volatile var audioSessionId: Int = 0
    private val prefs = context.getSharedPreferences("aura.audio.effects.v1", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(EffectsStateCodec.decode(prefs.getString("state", "{}")!!, prefs.getString("library", null)))
    val state = mutable.asStateFlow()
    private val usbStatusMutable = MutableStateFlow<UsbExclusiveStatus>(
        if (mutable.value.usbExclusive) UsbExclusiveStatus.NoDevice else UsbExclusiveStatus.Disabled,
    )
    val usbStatus = usbStatusMutable.asStateFlow()
    private val usbVolumeHudEventMutable = MutableStateFlow(UsbVolumeHudEvent())
    val usbVolumeHudEvent = usbVolumeHudEventMutable.asStateFlow()
    private var hudEventSeq = 0L
    @Volatile var realtimeConfig: DspConfig = mutable.value.config; private set
    @Volatile var usbExclusiveEnabled: Boolean = mutable.value.usbExclusive; private set
    @Volatile var usbBufferDurationMs: Int = normalizeUsbBufferMs(mutable.value.usbBufferMs); private set
    @Volatile var usbLockFixedFormatEnabled: Boolean = mutable.value.usbLockFixedFormat; private set
    @Volatile var usbVolumeLevel: Float = mutable.value.usbVolume.coerceIn(0f, 1f); private set
    @Volatile var smartTuningEnabled: Boolean = mutable.value.selected == BuiltInPresets.SMART_ID; private set
    @Volatile private var usbMonitorRegistered = false
    @Volatile private var activeStyle: SoundStyle = mutable.value.style
    @Volatile var liveSmartConfig: DspConfig = BuiltInPresets.smart.config
    @Volatile var trackGeneration: Long = 0L; private set
    @Volatile private var currentTrackKey: String = ""

    val activeSmartStyle: SoundStyle? get() = if (smartTuningEnabled) activeStyle else null
    val activeCurrentConfig: DspConfig get() = if (state.value.selected == BuiltInPresets.SMART_ID) liveSmartConfig else state.value.current.config

    fun notifyTrackChanged(trackKey: String) {
        if (trackKey != currentTrackKey) {
            currentTrackKey = trackKey
            trackGeneration++
            liveSmartConfig = BuiltInPresets.styled(BuiltInPresets.smart.config, activeStyle)
        }
    }

    fun select(id: String) = update { it.requestPreset(id, System.currentTimeMillis()) }
    fun restoreDefaults() = select("__defaults__")
    fun cancelPending() = update { it.copy(pendingSelection = null) }
    fun finishPending() = update { s -> s.pendingSelection?.let { s.applyPreset(it, System.currentTimeMillis()) } ?: s }
    fun style(style: SoundStyle) = update { it.copy(style = style) }
    fun custom(c: DspConfig) = update { it.edited(c) }
    fun saveDraft(name: String) = update { it.savedDraft(name, System.currentTimeMillis()) }
    fun importPreset(p: DspPreset) = update {
        require(it.imported.size < 100) { "LIBRARY_FULL" }; p.config.validate()
        it.copy(imported = it.imported + p).requestPreset(p.id, System.currentTimeMillis())
    }
    fun delete(id: String) = update { it.copy(imported = it.imported.filterNot { p -> p.id == id }, selected = if (it.selected == id) "reference" else it.selected,
        pendingSelection = if (it.pendingSelection == id) null else it.pendingSelection, counts = it.counts - id, recent = it.recent - id) }
    val isUsbExclusiveActive: Boolean get() = usbStatus.value is UsbExclusiveStatus.Active

    fun audio(
        fade: Boolean = state.value.fade,
        ms: Int = state.value.fadeMs,
        always: Boolean = state.value.alwaysPlay,
        nativePrevious: Boolean = state.value.nativePreviousBehavior,
        usbExclusive: Boolean = state.value.usbExclusive,
        usbBufferMs: Int = state.value.usbBufferMs,
        usbLockFixedFormat: Boolean = state.value.usbLockFixedFormat,
        usbVolumeCollapseOnRepeat: Boolean = state.value.usbVolumeCollapseOnRepeat,
        usbVolumePosition: String = state.value.usbVolumePosition,
        usbVolumeOffsetY: Int = state.value.usbVolumeOffsetY,
        usbVolumeOffsetX: Int = state.value.usbVolumeOffsetX,
        usbVolumeHeightScale: Int = state.value.usbVolumeHeightScale,
        usbVolumeWidthScale: Int = state.value.usbVolumeWidthScale,
    ) = update {
        it.copy(
            fade = fade,
            fadeMs = ms.coerceIn(100, 3000),
            alwaysPlay = always,
            nativePreviousBehavior = nativePrevious,
            usbExclusive = usbExclusive,
            usbBufferMs = normalizeUsbBufferMs(usbBufferMs),
            usbLockFixedFormat = usbLockFixedFormat,
            usbVolumeCollapseOnRepeat = usbVolumeCollapseOnRepeat,
            usbVolumePosition = normalizeUsbVolumePosition(usbVolumePosition),
            usbVolumeOffsetY = usbVolumeOffsetY.coerceIn(-64, 96),
            usbVolumeOffsetX = usbVolumeOffsetX.coerceIn(-32, 64),
            usbVolumeHeightScale = usbVolumeHeightScale.coerceIn(-100, 100),
            usbVolumeWidthScale = usbVolumeWidthScale.coerceIn(-100, 100),
        )
    }

    fun setUsbVolume(volume: Float, showHud: Boolean = false) {
        val clamped = volume.coerceIn(0f, 1f)
        update { it.copy(usbVolume = clamped) }
        if (showHud) {
            hudEventSeq++
            usbVolumeHudEventMutable.value = UsbVolumeHudEvent(seq = hudEventSeq, isBoundary = false, isUpper = true)
        }
    }

    fun previewUsbVolumeHud() {
        if (!isUsbExclusiveActive) return
        hudEventSeq++
        usbVolumeHudEventMutable.value = UsbVolumeHudEvent(seq = hudEventSeq, isBoundary = false, isUpper = true, isPreview = true)
    }

    fun stepUsbVolume(increase: Boolean, showHud: Boolean = true): Float {
        val step = 1f / 25f
        val current = state.value.usbVolume.coerceIn(0f, 1f)
        val atBoundary = (increase && current >= 0.999f) || (!increase && current <= 0.001f)
        val rawNext = if (increase) current + step else current - step
        val snapped = ((rawNext.coerceIn(0f, 1f) * 25f).roundToInt() / 25f).coerceIn(0f, 1f)
        if (!atBoundary) {
            update { it.copy(usbVolume = snapped) }
        }
        if (showHud) {
            hudEventSeq++
            usbVolumeHudEventMutable.value = UsbVolumeHudEvent(
                seq = hudEventSeq,
                isBoundary = atBoundary,
                isUpper = increase,
            )
        }
        return snapped
    }

    fun registerProcessUsbMonitor() {
        if (usbMonitorRegistered) return
        usbMonitorRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        if (usbExclusiveEnabled) {
                            val usbDev = UsbAudioDevice.getInstance(appContext)
                            val audioDev = usbDev.findUsbAudioDevice()
                            if (audioDev != null && !usbDev.hasPermission(audioDev)) {
                                usbDev.requestPermission(audioDev) { refreshUsbStatus() }
                            }
                        }
                        refreshUsbStatus()
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val detached = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        UsbAudioDevice.getInstance(appContext).invalidateIfDetached(detached)
                        refreshUsbStatus()
                    }
                }
            }
        }
        runCatching {
            appContext.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                },
                Context.RECEIVER_EXPORTED,
            )
        }
    }

    fun updateUsbStatus(status: UsbExclusiveStatus) {
        usbStatusMutable.value = status
    }

    fun refreshUsbStatus() {
        if (!usbExclusiveEnabled) {
            usbStatusMutable.value = UsbExclusiveStatus.Disabled
            return
        }
        val usbDevice = UsbAudioDevice.getInstance(appContext)
        val device = usbDevice.findUsbAudioDevice()
        if (device == null) {
            usbStatusMutable.value = UsbExclusiveStatus.NoDevice
            return
        }
        val name = device.productName?.takeIf { it.isNotBlank() } ?: "USB DAC"
        if (!usbDevice.hasPermission(device)) {
            usbStatusMutable.value = UsbExclusiveStatus.NeedsPermission(name)
            return
        }
        if (usbStatusMutable.value !is UsbExclusiveStatus.Active) {
            usbStatusMutable.value = UsbExclusiveStatus.Ready(name)
        }
    }

    @Synchronized private fun update(block: (EffectsState) -> EffectsState) {
        val previous = mutable.value
        val next = block(previous)
        realtimeConfig = next.config
        usbExclusiveEnabled = next.usbExclusive
        usbBufferDurationMs = normalizeUsbBufferMs(next.usbBufferMs)
        usbLockFixedFormatEnabled = next.usbLockFixedFormat
        usbVolumeLevel = next.usbVolume.coerceIn(0f, 1f)
        smartTuningEnabled = next.selected == BuiltInPresets.SMART_ID
        activeStyle = next.style
        if (!smartTuningEnabled) liveSmartConfig = BuiltInPresets.smart.config
        mutable.value = next
        if (!next.usbExclusive) {
            usbStatusMutable.value = UsbExclusiveStatus.Disabled
        } else if (previous.usbExclusive != next.usbExclusive) {
            refreshUsbStatus()
        }
        prefs.edit().putString("state", EffectsStateCodec.encode(next)).apply {
            if (!prefs.contains("library") || previous.imported != next.imported) putString("library", EffectsStateCodec.encodeLibrary(next.imported))
        }.apply()
    }

    companion object {
        fun normalizeUsbBufferMs(raw: Int): Int = when (raw) {
            40 -> 40
            200 -> 200
            else -> 100
        }

        fun normalizeUsbVolumePosition(raw: String?): String =
            if (raw == "left") "left" else "right"
    }
}
