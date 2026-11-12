package btm.m.auraplayer.audio

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider

/** Media3 reserves PCM capacity for MAX_PLAYBACK_SPEED (8x) when using AudioTrack
 * speed control, turning its default 250–750 ms into 2–6 seconds of stale DSP audio.
 * Keep a 100–150 ms PCM budget instead, still honoring AudioTrack's platform minimum
 * and frame alignment. At 3x this provides ~33–50 ms of scheduling reserve. */
@UnstableApi
internal class EffectsAudioBufferSizeProvider : DefaultAudioTrackBufferSizeProvider(
    Builder().setMinPcmBufferDurationUs(100_000).setMaxPcmBufferDurationUs(150_000)
        .setPcmBufferMultiplicationFactor(2),
) {
    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int, encoding: Int, outputMode: Int, pcmFrameSize: Int,
        sampleRate: Int, bitrate: Int, maxAudioTrackPlaybackSpeed: Double,
    ): Int = super.getBufferSizeInBytes(minBufferSizeInBytes, encoding, outputMode, pcmFrameSize, sampleRate, bitrate,
        if (outputMode == DefaultAudioSink.OUTPUT_MODE_PCM) 1.0 else maxAudioTrackPlaybackSpeed)
}
