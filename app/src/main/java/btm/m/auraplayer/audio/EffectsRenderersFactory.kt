package btm.m.auraplayer.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.*

/** Force decoding through the PCM path even on HDMI/Bluetooth offload capable routes. */
@UnstableApi
class EffectsRenderersFactory(context: Context, private val store: EffectsStore) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(true)
            .setEnableAudioOutputPlaybackParameters(true)
            .setAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context)
                .setAudioTrackBufferSizeProvider(EffectsAudioBufferSizeProvider()).build())
            .build()
        return ProcessingAudioSink(
            sink = sink,
            waveform = store.outputWaveform,
            smartStyle = { store.activeSmartStyle },
            trackGeneration = { store.trackGeneration },
            onSmartConfig = { store.liveSmartConfig = it },
        ) { store.realtimeConfig }
    }
}
