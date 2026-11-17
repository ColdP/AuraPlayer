package btm.m.auraplayer.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.*
import btm.m.auraplayer.audio.usb.UsbExclusiveAudioSink
import btm.m.auraplayer.sing.AuraSingEngine

/** Force decoding through the PCM path even on HDMI/Bluetooth offload capable routes. */
@UnstableApi
class EffectsRenderersFactory(
    context: Context,
    private val store: EffectsStore,
    private val auraSing: AuraSingEngine? = null,
    private val onUsbSinkCreated: (UsbExclusiveAudioSink) -> Unit = {},
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        val defaultSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(true)
            .setEnableAudioOutputPlaybackParameters(true)
            .setAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context)
                .setAudioTrackBufferSizeProvider(EffectsAudioBufferSizeProvider()).build())
            .build()
        val usbSink = UsbExclusiveAudioSink(
            delegate = defaultSink,
            context = context,
            isExclusiveEnabled = { store.usbExclusiveEnabled },
            bufferMsProvider = { store.usbBufferDurationMs },
            lockFixedFormatProvider = { store.usbLockFixedFormatEnabled },
            usbVolumeProvider = { store.usbVolumeLevel },
            onStatusChanged = { store.updateUsbStatus(it) },
        )
        onUsbSinkCreated(usbSink)
        return ProcessingAudioSink(
            sink = usbSink,
            waveform = store.outputWaveform,
            auraSing = auraSing,
            smartStyle = { store.activeSmartStyle },
            trackGeneration = { store.trackGeneration },
            onSmartConfig = { store.liveSmartConfig = it },
        ) { store.realtimeConfig }
    }
}
