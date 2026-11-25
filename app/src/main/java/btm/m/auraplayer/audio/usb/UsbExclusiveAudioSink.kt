/*
 * Adapted from decent-player (https://github.com/Ma145/decent-player)
 * Copyright (c) 2026 Marcelo Silva — MIT License
 * Modified for Aura Player (btm.m.auraplayer):
 *   - Feature Unit (0x06) hardware gain + safe software fallback gain.
 *   - Configurable USB buffer depth (40ms / 100ms / 200ms).
 *   - Compatibility mode: lock fixed 48 kHz / 16-bit with stateful resampler.
 *   - Live toggle, hot-plug detach recovery, mono->stereo expansion, SonicAudioProcessor speed support.
 */

package btm.m.auraplayer.audio.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

@UnstableApi
class UsbExclusiveAudioSink(
    private val delegate: DefaultAudioSink,
    private val context: Context,
    private val isExclusiveEnabled: () -> Boolean,
    private val bufferMsProvider: () -> Int = { 100 },
    private val lockFixedFormatProvider: () -> Boolean = { false },
    private val usbVolumeProvider: () -> Float = { 0.30f },
    private val onStatusChanged: (UsbExclusiveStatus) -> Unit = {},
) : ForwardingAudioSink(delegate) {

    private val usbAudioDevice = UsbAudioDevice.getInstance(context)
    private val usbLock = Any()
    private val sonicProcessor = SonicAudioProcessor()
    private val fixedRateResampler = UsbStereoResampler()

    @Volatile
    private var usbAudioStream: UsbAudioStream? = null

    @Volatile
    private var usbStreamingThread: UsbStreamingThread? = null

    private var activeUsbSampleRate: Int = 0
    private var activeUsbChannels: Int = 0
    private var activeUsbInputChannels: Int = 0
    private var activeUsbWireBits: Int = 0
    private var activeUsbBufferMs: Int = 100
    private var activeUsbLockFixed: Boolean = false
    private var hardwareVolumeActive: Boolean = false

    private var currentEncoding: Int = C.ENCODING_PCM_16BIT
    private var currentInputSampleRate: Int = 0
    private var currentInputChannelCount: Int = 0
    private var currentPlaybackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    private var sonicConfigured: Boolean = false

    @Volatile
    private var usbStartMediaTimeUs: Long = 0L

    @Volatile
    private var usbStartMediaTimeNeedsInit: Boolean = true

    @Volatile
    private var isPlaying: Boolean = false

    @Volatile
    private var needsReconfigure: Boolean = false

    private var pendingVolume: Float = 1f
    private var delegateMuted: Boolean = false
    private var lastFailedAttemptKey: String? = null

    private val supportedEncodings = setOf(
        C.ENCODING_PCM_16BIT,
        C.ENCODING_PCM_24BIT,
        C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_FLOAT,
    )

    override fun getFormatSupport(format: Format): Int {
        if (isExclusiveEnabled() &&
            UsbAudioStream.nativeAvailable &&
            format.sampleMimeType == MimeTypes.AUDIO_RAW &&
            format.pcmEncoding in supportedEncodings &&
            format.channelCount in 1..2 &&
            format.sampleRate in 8000..384000
        ) {
            val device = usbAudioDevice.findUsbAudioDevice()
            if (device != null && usbAudioDevice.hasPermission(device)) {
                val base = super.getFormatSupport(format)
                if (base != AudioSink.SINK_FORMAT_UNSUPPORTED) return base
                if (format.sampleRate > 192000) {
                    val clamped = super.getFormatSupport(format.buildUpon().setSampleRate(192000).build())
                    if (clamped != AudioSink.SINK_FORMAT_UNSUPPORTED) return clamped
                }
            }
        }
        return super.getFormatSupport(format)
    }

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val enc = inputFormat.pcmEncoding.takeIf { it != Format.NO_VALUE } ?: C.ENCODING_PCM_16BIT
        currentEncoding = enc
        currentInputSampleRate = inputFormat.sampleRate
        currentInputChannelCount = inputFormat.channelCount
        lastFailedAttemptKey = null
        needsReconfigure = false
        fixedRateResampler.reset()
        configureSonic(currentInputSampleRate, currentInputChannelCount, enc)

        val delegateFormat = if (super.getFormatSupport(inputFormat) == AudioSink.SINK_FORMAT_UNSUPPORTED &&
            inputFormat.sampleRate > 192000
        ) {
            inputFormat.buildUpon().setSampleRate(192000).build()
        } else {
            inputFormat
        }
        super.configure(delegateFormat, specifiedBufferSize, outputChannels)

        if (isExclusiveEnabled() &&
            currentInputSampleRate in 8000..384000 &&
            currentInputChannelCount in 1..2 &&
            enc in supportedEncodings
        ) {
            configureUsbExclusive(currentInputSampleRate, currentInputChannelCount, enc)
        } else {
            releaseUsbStream(closeDevice = true)
            refreshStatus()
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (!buffer.hasRemaining()) return true

        if (!isExclusiveEnabled()) {
            if (usbAudioStream != null) {
                releaseUsbStream(closeDevice = true)
            }
            unmuteDelegateIfNeeded()
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        if ((usbAudioStream == null || needsReconfigure) &&
            currentInputSampleRate in 8000..384000 &&
            currentInputChannelCount in 1..2 &&
            currentEncoding in supportedEncodings
        ) {
            val device = usbAudioDevice.findUsbAudioDevice()
            if (device != null && usbAudioDevice.hasPermission(device)) {
                val attemptKey = buildAttemptKey(device.deviceId, currentInputSampleRate, currentInputChannelCount, currentEncoding)
                if (needsReconfigure || attemptKey != lastFailedAttemptKey) {
                    needsReconfigure = false
                    configureUsbExclusive(currentInputSampleRate, currentInputChannelCount, currentEncoding)
                }
            }
        }

        val stream = usbAudioStream
        val thread = usbStreamingThread
        if (stream == null || !stream.isAlive || thread == null) {
            if (stream != null && !stream.isAlive) {
                val failedDevice = usbAudioDevice.findUsbAudioDevice()
                if (failedDevice != null) {
                    lastFailedAttemptKey = buildAttemptKey(failedDevice.deviceId, currentInputSampleRate, currentInputChannelCount, currentEncoding)
                }
                releaseUsbStream(closeDevice = true)
                refreshStatus()
            }
            unmuteDelegateIfNeeded()
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        muteDelegateIfNeeded()

        if (usbStartMediaTimeNeedsInit) {
            usbStartMediaTimeUs = maxOf(0L, presentationTimeUs)
            usbStartMediaTimeNeedsInit = false
        }

        if (thread.queueSize() >= thread.backpressureThreshold) {
            return false
        }

        val pcmSource: ByteBuffer = if (isSonicActive() && sonicConfigured) {
            val duplicate = buffer.duplicate()
            sonicProcessor.queueInput(duplicate)
            val out = sonicProcessor.output
            if (!out.hasRemaining()) {
                buffer.position(buffer.limit())
                return true
            }
            out
        } else {
            buffer.slice().order(buffer.order())
        }

        if (activeUsbSampleRate != currentInputSampleRate && currentInputSampleRate > 0 && activeUsbSampleRate > 0) {
            val resampled = fixedRateResampler.process(
                pcm = pcmSource,
                encoding = currentEncoding,
                inputChannels = currentInputChannelCount,
                inputRate = currentInputSampleRate,
                outputRate = activeUsbSampleRate,
            )
            if (resampled.isNotEmpty()) {
                thread.enqueueFloat(resampled)
            }
        } else {
            enqueueToUsbThread(thread, pcmSource, currentEncoding, currentInputChannelCount, activeUsbChannels)
        }

        buffer.position(buffer.limit())
        return true
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val stream = usbAudioStream
        if (isExclusiveEnabled() && stream?.isAlive == true && activeUsbSampleRate > 0) {
            if (usbStartMediaTimeNeedsInit) return AudioSink.CURRENT_POSITION_NOT_SET
            val frames = stream.framesWritten
            val elapsedUs = frames * C.MICROS_PER_SECOND / activeUsbSampleRate
            val speed = currentPlaybackParameters.speed.takeIf { it > 0f } ?: 1f
            return usbStartMediaTimeUs + (elapsedUs * speed).toLong()
        }
        return super.getCurrentPositionUs(sourceEnded)
    }

    override fun hasPendingData(): Boolean {
        val stream = usbAudioStream
        if (isExclusiveEnabled() && stream?.isAlive == true && usbStreamingThread?.hasPendingData() == true) {
            return true
        }
        return super.hasPendingData()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        currentPlaybackParameters = playbackParameters
        if (sonicConfigured) {
            sonicProcessor.setSpeed(playbackParameters.speed)
            sonicProcessor.setPitch(playbackParameters.pitch)
        }
        super.setPlaybackParameters(playbackParameters)
    }

    override fun play() {
        super.play()
        isPlaying = true
        usbStreamingThread?.resumeStreaming()
    }

    override fun pause() {
        isPlaying = false
        usbStreamingThread?.pauseStreaming()
        super.pause()
    }

    override fun setVolume(volume: Float) {
        pendingVolume = volume
        if (isExclusiveEnabled() && usbAudioStream?.isAlive == true) {
            muteDelegateIfNeeded()
        } else {
            unmuteDelegateIfNeeded()
        }
    }

    override fun flush() {
        super.flush()
        if (sonicConfigured) {
            sonicProcessor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        fixedRateResampler.reset()
        usbStreamingThread?.flush()
        usbAudioStream?.flush()
        usbStartMediaTimeNeedsInit = true
    }

    override fun reset() {
        releaseUsbStream(closeDevice = false)
        if (sonicConfigured) {
            sonicProcessor.reset()
            sonicConfigured = false
        }
        fixedRateResampler.reset()
        usbStartMediaTimeNeedsInit = true
        super.reset()
    }

    override fun release() {
        releaseUsbStream(closeDevice = true)
        sonicProcessor.reset()
        sonicConfigured = false
        super.release()
    }

    fun onExclusiveToggled(enabled: Boolean) {
        lastFailedAttemptKey = null
        needsReconfigure = false
        if (!enabled) {
            releaseUsbStream(closeDevice = true)
            unmuteDelegateIfNeeded()
            onStatusChanged(UsbExclusiveStatus.Disabled)
        } else {
            refreshStatus()
        }
    }

    fun onUsbTuningChanged() {
        lastFailedAttemptKey = null
        if (isExclusiveEnabled() && usbAudioStream != null) {
            needsReconfigure = true
        }
    }

    fun onUsbVolumeChanged(volume: Float) {
        synchronized(usbLock) {
            val stream = usbAudioStream ?: return
            val info = usbAudioDevice.currentDeviceInfo() ?: return
            applyVolumeLocked(info, stream, volume)
        }
    }

    fun onUsbAttached() {
        lastFailedAttemptKey = null
        needsReconfigure = false
        refreshStatus()
    }

    fun onUsbDetached(device: UsbDevice?) {
        synchronized(usbLock) {
            val invalidated = usbAudioDevice.invalidateIfDetached(device)
            if (invalidated || usbAudioDevice.findUsbAudioDevice() == null) {
                releaseUsbStreamLocked(closeDevice = true)
            }
        }
        lastFailedAttemptKey = null
        needsReconfigure = false
        refreshStatus()
    }

    fun refreshStatus() {
        if (!isExclusiveEnabled()) {
            onStatusChanged(UsbExclusiveStatus.Disabled)
            return
        }
        val stream = usbAudioStream
        val device = usbAudioDevice.findUsbAudioDevice()
        if (device == null) {
            onStatusChanged(UsbExclusiveStatus.NoDevice)
            return
        }
        val name = device.productName?.takeIf { it.isNotBlank() } ?: "USB DAC"
        if (!usbAudioDevice.hasPermission(device)) {
            onStatusChanged(UsbExclusiveStatus.NeedsPermission(name))
            return
        }
        if (stream?.isAlive == true && activeUsbSampleRate > 0) {
            onStatusChanged(UsbExclusiveStatus.Active(name, activeUsbSampleRate, activeUsbWireBits, hardwareVolumeActive))
        } else {
            onStatusChanged(UsbExclusiveStatus.Ready(name))
        }
    }

    private fun buildAttemptKey(deviceId: Int, sampleRate: Int, channels: Int, encoding: Int): String =
        "$deviceId:$sampleRate:$channels:$encoding:${bufferMsProvider()}:${lockFixedFormatProvider()}"

    private fun configureUsbExclusive(sampleRate: Int, inputChannels: Int, encoding: Int) {
        synchronized(usbLock) {
            if (!UsbAudioStream.nativeAvailable) {
                onStatusChanged(UsbExclusiveStatus.Fallback("USB DAC", "native_unavailable"))
                return
            }
            val device = usbAudioDevice.findUsbAudioDevice() ?: run {
                releaseUsbStreamLocked(closeDevice = true)
                onStatusChanged(UsbExclusiveStatus.NoDevice)
                return
            }
            val deviceName = device.productName?.takeIf { it.isNotBlank() } ?: "USB DAC"
            if (!usbAudioDevice.hasPermission(device)) {
                releaseUsbStreamLocked(closeDevice = false)
                onStatusChanged(UsbExclusiveStatus.NeedsPermission(deviceName))
                return
            }

            val lockFixed = lockFixedFormatProvider()
            val bufferMs = bufferMsProvider()
            val targetSampleRate = if (lockFixed) FIXED_COMPAT_SAMPLE_RATE else sampleRate
            val preferredBits = if (lockFixed) FIXED_COMPAT_BIT_DEPTH else encodingBitDepth(encoding)

            val existing = usbAudioStream
            if (existing?.isAlive == true &&
                activeUsbSampleRate == targetSampleRate &&
                activeUsbBufferMs == bufferMs &&
                activeUsbLockFixed == lockFixed &&
                (lockFixed || (activeUsbInputChannels == inputChannels && activeUsbWireBits >= preferredBits))
            ) {
                activeUsbInputChannels = inputChannels
                forceMediaToSpeaker()
                muteDelegateIfNeeded()
                onStatusChanged(UsbExclusiveStatus.Active(deviceName, activeUsbSampleRate, activeUsbWireBits, hardwareVolumeActive))
                return
            }

            releaseUsbStreamLocked(closeDevice = false)
            val attemptKey = buildAttemptKey(device.deviceId, sampleRate, inputChannels, encoding)

            var info = usbAudioDevice.openDevice(device)
            if (info == null) {
                lastFailedAttemptKey = attemptKey
                onStatusChanged(UsbExclusiveStatus.Fallback(deviceName, "open_failed"))
                return
            }

            val targetChannels = if (lockFixed || (inputChannels == 1 && info.altFormats.none { it.channels == 1 })) 2 else inputChannels
            val chosenAlt = usbAudioDevice.selectAltForFormat(info, preferredBits, targetChannels)
            val wireBits = chosenAlt.wireBitDepth
            val eps = usbAudioDevice.resolveEndpointsForAlt(chosenAlt.altSetting)
            val outEp = eps?.first ?: info.endpointOutAddress
            val fbEp = eps?.second ?: info.endpointFeedbackAddress
            val maxPkt = usbAudioDevice.resolveMaxPacketForAlt(chosenAlt.altSetting) ?: info.maxPacketSize

            val bytesPerFrame = (wireBits / 8) * chosenAlt.channels
            val bytesPerSec = targetSampleRate.toLong() * bytesPerFrame
            val packetsPerSec = if (info.isUac1 && maxPkt > 0 && bytesPerSec / 1000.0 <= maxPkt * 1.05) 1000 else 8000
            val nominalPacketBytes = bytesPerSec.toDouble() / packetsPerSec

            if (maxPkt > 0 && nominalPacketBytes > maxPkt) {
                Log.w(TAG, "Rate $targetSampleRate Hz exceeds endpoint maxPacketSize=$maxPkt; falling back")
                lastFailedAttemptKey = attemptKey
                usbAudioDevice.closeDevice()
                onStatusChanged(UsbExclusiveStatus.Fallback(info.deviceName, "rate_exceeds_endpoint"))
                return
            }

            if (!usbAudioDevice.setAltSetting(info.interfaceId, 0)) {
                usbAudioDevice.closeDevice()
                info = usbAudioDevice.openDevice(device)
                if (info == null) {
                    lastFailedAttemptKey = attemptKey
                    onStatusChanged(UsbExclusiveStatus.Fallback(deviceName, "reopen_failed"))
                    return
                }
                usbAudioDevice.setAltSetting(info.interfaceId, 0)
            }

            usbAudioDevice.setSampleRate(info, targetSampleRate)
            usbAudioDevice.readClockValid(info)
            usbAudioDevice.setAltSetting(info.interfaceId, 0)
            val altSetOk = usbAudioDevice.setAltSetting(info.interfaceId, chosenAlt.altSetting)
            Thread.sleep(50)

            val stream = UsbAudioStream(
                fd = info.fd,
                interfaceId = info.interfaceId,
                endpointOut = outEp,
                endpointFeedback = fbEp,
                sampleRate = targetSampleRate,
                channelCount = chosenAlt.channels,
                bitDepth = wireBits,
                maxPacketSize = maxPkt,
                packetsPerSecond = packetsPerSec,
                bufferMs = bufferMs,
            )

            if (!altSetOk || !stream.isReady) {
                Log.w(TAG, "USB stream prepare failed (altSetOk=$altSetOk ready=${stream.isReady})")
                stream.release()
                usbAudioDevice.closeDevice()
                lastFailedAttemptKey = attemptKey
                onStatusChanged(UsbExclusiveStatus.Fallback(info.deviceName, "stream_start_failed"))
                return
            }

            // Set safe hardware/fallback volume BEFORE starting ISO transfers to prevent ear-blast.
            applyVolumeLocked(info, stream, usbVolumeProvider())

            if (!stream.start()) {
                Log.w(TAG, "USB stream start failed")
                stream.release()
                usbAudioDevice.closeDevice()
                lastFailedAttemptKey = attemptKey
                onStatusChanged(UsbExclusiveStatus.Fallback(info.deviceName, "stream_start_failed"))
                return
            }

            usbAudioStream = stream
            activeUsbSampleRate = targetSampleRate
            activeUsbChannels = chosenAlt.channels
            activeUsbInputChannels = inputChannels
            activeUsbWireBits = wireBits
            activeUsbBufferMs = bufferMs
            activeUsbLockFixed = lockFixed
            usbStartMediaTimeNeedsInit = true
            fixedRateResampler.reset()
            val backpressure = when (bufferMs) {
                40 -> 4
                200 -> 20
                else -> 10
            }
            usbStreamingThread = UsbStreamingThread(stream, backpressureThreshold = backpressure).apply {
                if (!isPlaying) pauseStreaming()
                start()
            }

            forceMediaToSpeaker()
            muteDelegateIfNeeded()
            onStatusChanged(UsbExclusiveStatus.Active(info.deviceName, targetSampleRate, wireBits, hardwareVolumeActive))
            Log.i(
                TAG,
                "USB exclusive active: ${info.deviceName} ${targetSampleRate}Hz ${wireBits}bit " +
                    "${chosenAlt.channels}ch buf=${bufferMs}ms lockFixed=$lockFixed hwVol=$hardwareVolumeActive",
            )
        }
    }

    private fun applyVolumeLocked(info: UsbAudioDeviceInfo, stream: UsbAudioStream, normalizedVolume: Float) {
        val vol = normalizedVolume.coerceIn(0f, 1f)
        val hwOk = usbAudioDevice.setHardwareVolume(info, vol)
        hardwareVolumeActive = hwOk
        stream.setSoftwareGain(if (hwOk) 1.0f else perceptualSoftwareGain(vol))
    }

    private fun perceptualSoftwareGain(normalized: Float): Float {
        val v = normalized.coerceIn(0f, 1f)
        if (v >= 0.999f) return 1.0f
        if (v <= 0.001f) return 0.0f
        return v.toDouble().pow(3.0).toFloat()
    }

    private fun releaseUsbStream(closeDevice: Boolean) {
        synchronized(usbLock) {
            releaseUsbStreamLocked(closeDevice)
        }
    }

    private fun releaseUsbStreamLocked(closeDevice: Boolean) {
        val stream = usbAudioStream
        usbAudioStream = null
        stream?.stop()
        usbStreamingThread?.stop()
        usbStreamingThread = null
        stream?.drainUrbs()
        stream?.release()
        if (closeDevice) {
            usbAudioDevice.closeDevice()
        }
        activeUsbSampleRate = 0
        activeUsbChannels = 0
        activeUsbInputChannels = 0
        activeUsbWireBits = 0
        hardwareVolumeActive = false
        fixedRateResampler.reset()
        clearForcedRouting()
        unmuteDelegateIfNeeded()
    }

    private fun configureSonic(sampleRate: Int, channels: Int, encoding: Int) {
        sonicConfigured = runCatching {
            if (sampleRate <= 0 || channels <= 0) {
                sonicProcessor.reset()
                false
            } else {
                sonicProcessor.configure(AudioProcessor.AudioFormat(sampleRate, channels, encoding))
                sonicProcessor.setSpeed(currentPlaybackParameters.speed)
                sonicProcessor.setPitch(currentPlaybackParameters.pitch)
                sonicProcessor.flush(AudioProcessor.StreamMetadata.DEFAULT)
                true
            }
        }.getOrDefault(false)
    }

    private fun isSonicActive(): Boolean =
        currentPlaybackParameters.speed != 1f || currentPlaybackParameters.pitch != 1f

    private fun enqueueToUsbThread(
        thread: UsbStreamingThread,
        pcm: ByteBuffer,
        encoding: Int,
        inChannels: Int,
        outChannels: Int,
    ) {
        val slice = pcm.slice().order(ByteOrder.LITTLE_ENDIAN)
        if (encoding == C.ENCODING_PCM_FLOAT) {
            val totalSamples = slice.remaining() / 4
            if (totalSamples <= 0) return
            if (inChannels == 1 && outChannels == 2) {
                val out = FloatArray(totalSamples * 2)
                val fb = slice.asFloatBuffer()
                for (i in 0 until totalSamples) {
                    val s = fb.get(i)
                    out[i * 2] = s
                    out[i * 2 + 1] = s
                }
                thread.enqueueFloat(out)
            } else {
                val out = FloatArray(totalSamples)
                slice.asFloatBuffer().get(out)
                thread.enqueueFloat(out)
            }
            return
        }

        val bitDepth = encodingBitDepth(encoding)
        val bps = bitDepth / 8
        val remaining = slice.remaining()
        if (remaining <= 0) return
        if (inChannels == 1 && outChannels == 2) {
            val raw = ByteArray(remaining)
            slice.get(raw)
            val expanded = ByteArray(remaining * 2)
            var dst = 0
            var src = 0
            while (src + bps <= raw.size) {
                System.arraycopy(raw, src, expanded, dst, bps)
                System.arraycopy(raw, src, expanded, dst + bps, bps)
                src += bps
                dst += bps * 2
            }
            thread.enqueueRaw(expanded, bitDepth)
        } else {
            val raw = ByteArray(remaining)
            slice.get(raw)
            thread.enqueueRaw(raw, bitDepth)
        }
    }

    private fun encodingBitDepth(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
        else -> 16
    }

    private fun muteDelegateIfNeeded() {
        if (!delegateMuted) {
            super.setVolume(0f)
            delegateMuted = true
        }
    }

    private fun unmuteDelegateIfNeeded() {
        if (delegateMuted) {
            super.setVolume(pendingVolume)
            delegateMuted = false
        }
    }

    private fun forceMediaToSpeaker() {
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                delegate.setPreferredDevice(speaker)
            }
        }
    }

    private fun clearForcedRouting() {
        runCatching { delegate.setPreferredDevice(null) }
    }

    companion object {
        private const val TAG = "UsbExclusiveAudioSink"
        private const val FIXED_COMPAT_SAMPLE_RATE = 48000
        private const val FIXED_COMPAT_BIT_DEPTH = 16
    }
}

/**
 * Stateful Catmull-Rom stereo resampler with downsampling lowpass pre-filter for fixed-rate (48 kHz) USB compatibility mode.
 */
internal class UsbStereoResampler {
    private var prev2L = 0f
    private var prev2R = 0f
    private var prev1L = 0f
    private var prev1R = 0f
    private var lpPrevL = 0f
    private var lpPrevR = 0f
    private var hasPrev = false
    private var phase = 0.0

    fun reset() {
        prev2L = 0f
        prev2R = 0f
        prev1L = 0f
        prev1R = 0f
        lpPrevL = 0f
        lpPrevR = 0f
        hasPrev = false
        phase = 0.0
    }

    fun process(
        pcm: ByteBuffer,
        encoding: Int,
        inputChannels: Int,
        inputRate: Int,
        outputRate: Int,
    ): FloatArray {
        val slice = pcm.slice().order(ByteOrder.LITTLE_ENDIAN)
        val bps = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val frameBytes = bps * inputChannels.coerceAtLeast(1)
        val inFrames = slice.remaining() / frameBytes
        if (inFrames <= 0 || inputRate <= 0 || outputRate <= 0) return FloatArray(0)

        val inL = FloatArray(inFrames)
        val inR = FloatArray(inFrames)
        for (i in 0 until inFrames) {
            val l = readNormalizedSample(slice, encoding)
            val r = if (inputChannels >= 2) readNormalizedSample(slice, encoding) else l
            inL[i] = l
            inR[i] = r
            for (extra in 2 until inputChannels) {
                readNormalizedSample(slice, encoding)
            }
        }

        if (inputRate > outputRate && inFrames > 1) {
            // Lightweight 3-tap [0.25, 0.5, 0.25] zero-phase-ish anti-alias pre-filter before decimation.
            var prevL = if (hasPrev) lpPrevL else inL[0]
            var prevR = if (hasPrev) lpPrevR else inR[0]
            lpPrevL = inL[inFrames - 1]
            lpPrevR = inR[inFrames - 1]
            for (i in 0 until inFrames) {
                val curL = inL[i]
                val curR = inR[i]
                val nextL = if (i + 1 < inFrames) inL[i + 1] else curL
                val nextR = if (i + 1 < inFrames) inR[i + 1] else curR
                inL[i] = 0.25f * prevL + 0.5f * curL + 0.25f * nextL
                inR[i] = 0.25f * prevR + 0.5f * curR + 0.25f * nextR
                prevL = curL
                prevR = curR
            }
        } else {
            lpPrevL = inL[inFrames - 1]
            lpPrevR = inR[inFrames - 1]
        }

        val step = inputRate.toDouble() / outputRate.toDouble()
        val estOut = ((inFrames.toDouble() / step) + 4).toInt().coerceAtLeast(1)
        val out = FloatArray(estOut * 2)
        var outCount = 0

        if (!hasPrev) {
            prev2L = inL[0]
            prev2R = inR[0]
            prev1L = inL[0]
            prev1R = inR[0]
            hasPrev = true
            phase = 0.0
        }

        fun sampleAt(arr: FloatArray, p2: Float, p1: Float, idx: Int): Float = when {
            idx <= -2 -> p2
            idx == -1 -> p1
            idx >= inFrames -> arr[inFrames - 1]
            else -> arr[idx]
        }

        while (phase < inFrames - 1) {
            val idx = kotlin.math.floor(phase).toInt()
            val t = (phase - idx).toFloat()
            if (outCount * 2 + 1 >= out.size) break
            out[outCount * 2] = cubicHermite(
                sampleAt(inL, prev2L, prev1L, idx - 1),
                sampleAt(inL, prev2L, prev1L, idx),
                sampleAt(inL, prev2L, prev1L, idx + 1),
                sampleAt(inL, prev2L, prev1L, idx + 2),
                t,
            )
            out[outCount * 2 + 1] = cubicHermite(
                sampleAt(inR, prev2R, prev1R, idx - 1),
                sampleAt(inR, prev2R, prev1R, idx),
                sampleAt(inR, prev2R, prev1R, idx + 1),
                sampleAt(inR, prev2R, prev1R, idx + 2),
                t,
            )
            outCount++
            phase += step
        }

        phase -= inFrames
        prev2L = if (inFrames >= 2) inL[inFrames - 2] else prev1L
        prev2R = if (inFrames >= 2) inR[inFrames - 2] else prev1R
        prev1L = inL[inFrames - 1]
        prev1R = inR[inFrames - 1]
        return if (outCount * 2 == out.size) out else out.copyOf(outCount * 2)
    }

    private fun cubicHermite(y0: Float, y1: Float, y2: Float, y3: Float, t: Float): Float {
        val a0 = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3
        val a1 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3
        val a2 = -0.5f * y0 + 0.5f * y2
        return (((a0 * t + a1) * t + a2) * t + y1).coerceIn(-1.0f, 1.0f)
    }

    private fun readNormalizedSample(buf: ByteBuffer, encoding: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> buf.short / 32768f
        C.ENCODING_PCM_24BIT -> {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt()
            val s = b0 or (b1 shl 8) or (b2 shl 16)
            s / 8388608f
        }
        C.ENCODING_PCM_32BIT -> (buf.int.toDouble() / 2147483648.0).toFloat()
        C.ENCODING_PCM_FLOAT -> buf.float
        else -> 0f
    }
}
