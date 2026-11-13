package btm.m.auraplayer.auramix

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class AudioEdge { HEAD, TAIL }

/** MediaCodec-backed edge analyzer for local files. Network URIs are intentionally left unknown. */
object AudioAnalyzer {
    private const val EDGE_WINDOW_US = 30_000_000L

    suspend fun analyze(context: Context, uri: Uri, edge: AudioEdge): AudioFeatures? = withContext(Dispatchers.IO) {
        runCatching { decode(context, uri, edge) }.getOrNull()
    }

    private fun decode(context: Context, uri: Uri, edge: AudioEdge): AudioFeatures {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            var index = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { index = i; format = candidate; break }
            }
            require(index >= 0 && format != null)
            val inputFormat = format!!
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("MISSING_MIME")
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val windowStartUs = if (edge == AudioEdge.TAIL) (durationUs - EDGE_WINDOW_US).coerceAtLeast(0L) else 0L
            val windowEndUs = if (edge == AudioEdge.HEAD) minOf(durationUs.takeIf { it > 0 } ?: EDGE_WINDOW_US, EDGE_WINDOW_US) else durationUs
            extractor.selectTrack(index)
            if (windowStartUs > 0) extractor.seekTo(windowStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(mime)
            val detector = SilenceDetector(sampleRate, channels)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var decodedFrames = 0L
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            try {
                while (!outputEnded) {
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex) ?: continue
                            val sampleTime = extractor.sampleTime
                            val size = if (sampleTime < 0 || (windowEndUs > 0 && sampleTime >= windowEndUs)) -1 else extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            val output = codec.getOutputBuffer(outputIndex)
                            if (output != null && info.size > 0) {
                                val data = output.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                data.position(info.offset).limit(info.offset + info.size)
                                val encoding = if (inputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) inputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else C.ENCODING_PCM_16BIT
                                decodedFrames += acceptPcm(data, detector, encoding, channels)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            } finally { codec.stop(); codec.release() }
            val measured = detector.finish(decodedFrames)
            val totalDurationMs = durationUs / 1000L
            val leading = if (edge == AudioEdge.HEAD) measured.leadingSilenceMs.coerceAtMost(totalDurationMs) else 0L
            val trailing = if (edge == AudioEdge.TAIL) measured.trailingSilenceMs.coerceAtMost(totalDurationMs) else 0L
            return AudioFeatures(totalDurationMs, leading, (totalDurationMs - trailing).coerceAtLeast(leading), leading, trailing,
                measured.rmsDb, measured.peakDb, measured.confidence)
        } finally { extractor.release() }
    }

    private fun acceptPcm(buffer: ByteBuffer, detector: SilenceDetector, encoding: Int, channels: Int): Long {
        val bytes = when (encoding) { C.ENCODING_PCM_8BIT -> 1; C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }
        val frames = buffer.remaining() / (bytes * channels)
        val samples = FloatArray(frames * channels)
        for (i in samples.indices) samples[i] = when (encoding) {
            C.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) / 128f
            C.ENCODING_PCM_16BIT -> buffer.short / 32768f
            C.ENCODING_PCM_24BIT -> {
                val value = (buffer.get().toInt() and 255) or ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() shl 16)
                value / 8388608f
            }
            else -> buffer.float
        }
        detector.accept(samples)
        return frames.toLong()
    }
}
