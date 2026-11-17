/*
 * Adapted from decent-player (https://github.com/Ma145/decent-player)
 * Copyright (c) 2026 Marcelo Silva — MIT License
 * Modified for Aura Player (btm.m.auraplayer).
 */

package btm.m.auraplayer.audio.usb

import android.util.Log

/**
 * JNI handle for direct USB audio isochronous output via Linux usbdevfs.
 */
class UsbAudioStream(
    fd: Int,
    interfaceId: Int,
    endpointOut: Int,
    endpointFeedback: Int,
    sampleRate: Int,
    channelCount: Int,
    bitDepth: Int,
    maxPacketSize: Int,
    packetsPerSecond: Int,
    bufferMs: Int = 100,
) {
    private var nativeHandle: Long = 0L

    init {
        if (nativeAvailable) {
            nativeHandle = nativeUsbAudioCreate(
                fd,
                interfaceId,
                endpointOut,
                endpointFeedback,
                sampleRate,
                channelCount,
                bitDepth,
                maxPacketSize,
                packetsPerSecond,
                bufferMs,
            )
            if (nativeHandle == 0L) {
                Log.e(TAG, "nativeUsbAudioCreate returned 0")
            }
        }
    }

    val isReady: Boolean
        get() = nativeHandle != 0L

    val isAlive: Boolean
        get() = nativeHandle != 0L && nativeIsRunning(nativeHandle)

    val framesWritten: Long
        get() = if (nativeHandle != 0L) nativeGetFramesWritten(nativeHandle) else 0L

    fun setSoftwareGain(gain: Float) {
        if (nativeHandle != 0L) nativeSetSoftwareGain(nativeHandle, gain.coerceIn(0f, 1f))
    }

    fun start(): Boolean = nativeHandle != 0L && nativeUsbAudioStart(nativeHandle)

    fun write(pcmBuffer: FloatArray) {
        if (nativeHandle != 0L) nativeUsbAudioWrite(nativeHandle, pcmBuffer)
    }

    fun writeRaw(pcmBytes: ByteArray, inputBitDepth: Int) {
        if (nativeHandle != 0L) nativeUsbAudioWriteRaw(nativeHandle, pcmBytes, inputBitDepth)
    }

    fun stop() {
        if (nativeHandle != 0L) nativeUsbAudioStop(nativeHandle)
    }

    fun flush() {
        if (nativeHandle != 0L) nativeFlush(nativeHandle)
    }

    fun drainUrbs(): Int = if (nativeHandle != 0L) nativeDrainUrbs(nativeHandle) else 0

    fun release() {
        if (nativeHandle != 0L) {
            nativeUsbAudioDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeUsbAudioCreate(
        fd: Int,
        interfaceId: Int,
        endpointOut: Int,
        endpointFeedback: Int,
        sampleRate: Int,
        channelCount: Int,
        bitDepth: Int,
        maxPacketSize: Int,
        packetsPerSec: Int,
        bufferMs: Int,
    ): Long

    private external fun nativeSetSoftwareGain(handle: Long, gain: Float)
    private external fun nativeUsbAudioStart(handle: Long): Boolean
    private external fun nativeUsbAudioWrite(handle: Long, pcmBuffer: FloatArray)
    private external fun nativeUsbAudioWriteRaw(handle: Long, pcmBytes: ByteArray, inputBitDepth: Int)
    private external fun nativeUsbAudioStop(handle: Long)
    private external fun nativeFlush(handle: Long)
    private external fun nativeDrainUrbs(handle: Long): Int
    private external fun nativeUsbAudioDestroy(handle: Long)
    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeGetFramesWritten(handle: Long): Long

    companion object {
        private const val TAG = "UsbAudioStream"

        val nativeAvailable: Boolean by lazy {
            runCatching {
                System.loadLibrary("aura_usb_audio")
                true
            }.onFailure {
                Log.e(TAG, "Failed to load libaura_usb_audio.so: ${it.message}")
            }.getOrDefault(false)
        }
    }
}
