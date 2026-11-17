/*
 * Adapted from decent-player (https://github.com/Ma145/decent-player)
 * Copyright (c) 2026 Marcelo Silva — MIT License
 * Modified for Aura Player (btm.m.auraplayer).
 */

package btm.m.auraplayer.audio.usb

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

internal class UsbStreamingThread(
    private val usbStream: UsbAudioStream,
    val backpressureThreshold: Int = 10,
) {

    private sealed class AudioBuffer {
        class FloatBuffer(val data: FloatArray) : AudioBuffer()
        class RawBuffer(val data: ByteArray, val bitDepth: Int) : AudioBuffer()
    }

    private val audioQueue = ArrayBlockingQueue<AudioBuffer>(QUEUE_CAPACITY)

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({
            while (running) {
                if (paused) {
                    runCatching { Thread.sleep(20) }
                    continue
                }
                when (val buf = audioQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    is AudioBuffer.FloatBuffer -> usbStream.write(buf.data)
                    is AudioBuffer.RawBuffer -> usbStream.writeRaw(buf.data, buf.bitDepth)
                    null -> Unit
                }
            }
        }, "AuraUsbStreaming").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun enqueueFloat(floatBuf: FloatArray) {
        val buf = AudioBuffer.FloatBuffer(floatBuf)
        if (!audioQueue.offer(buf)) {
            audioQueue.poll()
            audioQueue.offer(buf)
        }
    }

    fun enqueueRaw(rawBytes: ByteArray, bitDepth: Int) {
        val buf = AudioBuffer.RawBuffer(rawBytes, bitDepth)
        if (!audioQueue.offer(buf)) {
            audioQueue.poll()
            audioQueue.offer(buf)
        }
    }

    fun pauseStreaming() {
        paused = true
    }

    fun resumeStreaming() {
        paused = false
    }

    fun hasPendingData(): Boolean = !audioQueue.isEmpty()

    fun queueSize(): Int = audioQueue.size

    fun flush() {
        audioQueue.clear()
    }

    fun stop() {
        running = false
        audioQueue.clear()
        runCatching { thread?.join(1500) }
        thread = null
        Log.d(TAG, "USB streaming thread stopped")
    }

    companion object {
        private const val TAG = "UsbStreamingThread"
        private const val QUEUE_CAPACITY = 128
        private const val POLL_TIMEOUT_MS = 50L
    }
}
