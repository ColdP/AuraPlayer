package btm.m.auraplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Media3 [Extractor] for Sony DSD Stream File (`.dsf`) audio.
 * Converts 1-bit DSD64 / DSD128 / DSD256 streams to 32-bit float PCM (88.2 kHz)
 * using a windowed-sinc byte-lookup FIR decimator with -6 dB SACD headroom.
 */
@UnstableApi
class DsfExtractor : Extractor {
    private var output: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var headerParsed = false

    private var channels = 0
    private var dsdSampleRate = 0
    private var pcmSampleRate = 88_200
    private var bitsPerSample = 1
    private var sampleCountPerChannel = 0L
    private var blockSizePerChannel = 4096
    private var bytesPerOutSample = 4
    private var windowBytes = 8

    private var dataStart = -1L
    private var dataSize = 0L
    private var totalBlocks = 0L
    private var currentBlock = 0L

    private var firLut: Array<FloatArray> = emptyArray()
    private var history: Array<ByteArray> = emptyArray()
    private var historyPos: IntArray = IntArray(0)
    private var blockBuffer = ByteArray(0)
    private var outBytes = ByteArray(0)
    private val parsable = ParsableByteArray()

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(32)
        input.peekFully(header, 0, 32)
        return header[0] == 'D'.code.toByte() &&
            header[1] == 'S'.code.toByte() &&
            header[2] == 'D'.code.toByte() &&
            header[3] == ' '.code.toByte() &&
            header[28] == 'f'.code.toByte() &&
            header[29] == 'm'.code.toByte() &&
            header[30] == 't'.code.toByte() &&
            header[31] == ' '.code.toByte()
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        this.trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
    }

    override fun seek(position: Long, timeUs: Long) {
        if (!headerParsed || dsdSampleRate <= 0 || blockSizePerChannel <= 0) return
        val dsdBitsPerBlock = blockSizePerChannel.toLong() * 8L
        val targetBit = (timeUs.coerceAtLeast(0L) * dsdSampleRate) / C.MICROS_PER_SECOND
        currentBlock = (targetBit / dsdBitsPerBlock).coerceIn(0L, totalBlocks)
        resetFirHistory()
    }

    override fun release() = Unit

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headerParsed) {
            parseHeader(input)
            headerParsed = true
        }
        if (currentBlock >= totalBlocks) return Extractor.RESULT_END_OF_INPUT
        val blockBytesTotal = blockSizePerChannel * channels
        val desiredPos = dataStart + currentBlock * blockBytesTotal.toLong()
        if (input.position != desiredPos) {
            seekPosition.position = desiredPos
            return Extractor.RESULT_SEEK
        }

        val remainingInFile = (dataSize - currentBlock * blockBytesTotal.toLong()).coerceAtLeast(0L)
        if (remainingInFile < blockBytesTotal) return Extractor.RESULT_END_OF_INPUT
        input.readFully(blockBuffer, 0, blockBytesTotal)

        val dsdBitsBefore = currentBlock * blockSizePerChannel.toLong() * 8L
        val remainingSamples = (sampleCountPerChannel - dsdBitsBefore).coerceAtLeast(0L)
        val validDsdBytesInBlock = ((remainingSamples + 7L) / 8L).coerceIn(0L, blockSizePerChannel.toLong()).toInt()
        val outFrames = validDsdBytesInBlock / bytesPerOutSample
        if (outFrames <= 0) {
            currentBlock = totalBlocks
            return Extractor.RESULT_END_OF_INPUT
        }

        val outByteLen = outFrames * channels * 4
        if (outBytes.size < outByteLen) outBytes = ByteArray(outByteLen)

        var dst = 0
        var byteInBlock = 0
        val winMask = windowBytes - 1
        for (f in 0 until outFrames) {
            for (step in 0 until bytesPerOutSample) {
                for (ch in 0 until channels) {
                    val buf = history[ch]
                    val pos = historyPos[ch]
                    buf[pos] = blockBuffer[ch * blockSizePerChannel + byteInBlock]
                    historyPos[ch] = (pos + 1) and winMask
                }
                byteInBlock++
            }
            for (ch in 0 until channels) {
                val buf = history[ch]
                val oldestPos = historyPos[ch]
                var acc = 0f
                for (b in 0 until windowBytes) {
                    val v = buf[(oldestPos + b) and winMask].toInt() and 0xFF
                    acc += firLut[b][v]
                }
                val bits = acc.coerceIn(-1f, 1f).toRawBits()
                outBytes[dst++] = (bits and 0xFF).toByte()
                outBytes[dst++] = ((bits ushr 8) and 0xFF).toByte()
                outBytes[dst++] = ((bits ushr 16) and 0xFF).toByte()
                outBytes[dst++] = ((bits ushr 24) and 0xFF).toByte()
            }
        }

        val outFrameStart = dsdBitsBefore / (bytesPerOutSample * 8L)
        val timeUs = outFrameStart * C.MICROS_PER_SECOND / pcmSampleRate
        parsable.reset(outBytes, outByteLen)
        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        track.sampleData(parsable, outByteLen)
        track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, outByteLen, 0, null)

        currentBlock++
        return if (currentBlock >= totalBlocks) Extractor.RESULT_END_OF_INPUT else Extractor.RESULT_CONTINUE
    }

    private fun parseHeader(input: ExtractorInput) {
        val dsdChunk = ByteArray(28)
        input.readFully(dsdChunk, 0, 28)
        val dsdChunkSize = readI64LE(dsdChunk, 4)
        if (dsdChunkSize > 28L) input.skipFully((dsdChunkSize - 28L).toInt())

        val fmtChunk = ByteArray(52)
        input.readFully(fmtChunk, 0, 52)
        val fmtSize = readI64LE(fmtChunk, 4)
        val formatId = readI32LE(fmtChunk, 16)
        if (formatId != 0) throw ParserException.createForUnsupportedContainerFeature("Compressed DSF not supported: formatId=$formatId")
        channels = readI32LE(fmtChunk, 24)
        dsdSampleRate = readI32LE(fmtChunk, 28)
        bitsPerSample = readI32LE(fmtChunk, 32)
        sampleCountPerChannel = readI64LE(fmtChunk, 36)
        blockSizePerChannel = readI32LE(fmtChunk, 44)
        if (fmtSize > 52L) input.skipFully((fmtSize - 52L).toInt())

        val dataHead = ByteArray(12)
        input.readFully(dataHead, 0, 12)
        if (dataHead[0] != 'd'.code.toByte() || dataHead[1] != 'a'.code.toByte() || dataHead[2] != 't'.code.toByte() || dataHead[3] != 'a'.code.toByte()) {
            throw ParserException.createForMalformedContainer("Missing DSF data chunk", null)
        }
        val rawDataChunkSize = readI64LE(dataHead, 4)
        dataStart = input.position
        val declaredPayload = (rawDataChunkSize - 12L).coerceAtLeast(0L)
        dataSize = if (input.length != C.LENGTH_UNSET.toLong()) {
            declaredPayload.coerceAtMost((input.length - dataStart).coerceAtLeast(0L))
        } else {
            declaredPayload
        }

        if (channels !in 1..8 || dsdSampleRate < 352_800 || blockSizePerChannel <= 0) {
            throw ParserException.createForMalformedContainer("Invalid DSF stream parameters", null)
        }

        val decimation = (dsdSampleRate.toDouble() / 88_200.0).roundToInt().coerceAtLeast(8)
        bytesPerOutSample = (decimation / 8).coerceAtLeast(1)
        pcmSampleRate = (dsdSampleRate / (bytesPerOutSample * 8)).coerceIn(44_100, 192_000)
        windowBytes = nextPowerOfTwo((bytesPerOutSample * 2).coerceAtLeast(8))

        val blockBytesTotal = blockSizePerChannel.toLong() * channels
        totalBlocks = if (blockBytesTotal > 0L) dataSize / blockBytesTotal else 0L
        blockBuffer = ByteArray(blockSizePerChannel * channels)
        buildFirLut()
        resetFirHistory()

        val durationUs = if (sampleCountPerChannel > 0L) sampleCountPerChannel * C.MICROS_PER_SECOND / dsdSampleRate else C.TIME_UNSET
        val out = output ?: return
        trackOutput?.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .setChannelCount(channels)
                .setSampleRate(pcmSampleRate)
                .build(),
        )
        out.endTracks()
        out.seekMap(object : SeekMap {
            override fun isSeekable(): Boolean = durationUs != C.TIME_UNSET && totalBlocks > 0L
            override fun getDurationUs(): Long = durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                if (!isSeekable) return SeekMap.SeekPoints(SeekPoint(0L, dataStart))
                val clampedUs = timeUs.coerceIn(0L, durationUs)
                val targetBit = clampedUs * dsdSampleRate / C.MICROS_PER_SECOND
                val blk = (targetBit / (blockSizePerChannel.toLong() * 8L)).coerceIn(0L, (totalBlocks - 1L).coerceAtLeast(0L))
                return SeekMap.SeekPoints(SeekPoint(clampedUs, dataStart + blk * blockBytesTotal))
            }
        })
    }

    private fun buildFirLut() {
        val totalTaps = windowBytes * 8
        val fc = 22_000.0 / dsdSampleRate.toDouble()
        val taps = DoubleArray(totalTaps)
        val center = (totalTaps - 1) * 0.5
        val beta = 7.0
        val i0Beta = besselI0(beta)
        var sum = 0.0
        for (i in 0 until totalTaps) {
            val n = i - center
            val sinc = if (n == 0.0) 2.0 * fc else sin(2.0 * PI * fc * n) / (PI * n)
            val norm = (2.0 * i / (totalTaps - 1)) - 1.0
            val win = besselI0(beta * sqrt((1.0 - norm * norm).coerceAtLeast(0.0))) / i0Beta
            val w = sinc * win
            taps[i] = w
            sum += w
        }
        // Normalize unity DC gain * 0.5 (-6 dB SACD reference level).
        val scale = if (sum > 0.0) 0.5 / sum else 1.0
        for (i in 0 until totalTaps) taps[i] *= scale

        val lsbFirst = bitsPerSample != 8
        firLut = Array(windowBytes) { b ->
            val baseTap = b * 8
            FloatArray(256) { byteVal ->
                var acc = 0.0
                for (bit in 0 until 8) {
                    val shift = if (lsbFirst) bit else (7 - bit)
                    val bitOn = ((byteVal ushr shift) and 1) != 0
                    acc += if (bitOn) taps[baseTap + bit] else -taps[baseTap + bit]
                }
                acc.toFloat()
            }
        }
    }

    private fun resetFirHistory() {
        history = Array(channels) { ByteArray(windowBytes) { 0x55 } } // 0x55 alternating bits = zero-DC DSD idle pattern
        historyPos = IntArray(channels)
    }

    private fun nextPowerOfTwo(v: Int): Int {
        var x = 1
        while (x < v) x = x shl 1
        return x
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val halfX = x * 0.5
        for (k in 1..20) {
            term *= (halfX / k) * (halfX / k)
            sum += term
            if (term < 1e-12 * sum) break
        }
        return sum
    }

    private fun readI32LE(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)

    private fun readI64LE(buf: ByteArray, off: Int): Long =
        (buf[off].toLong() and 0xFF) or
            ((buf[off + 1].toLong() and 0xFF) shl 8) or
            ((buf[off + 2].toLong() and 0xFF) shl 16) or
            ((buf[off + 3].toLong() and 0xFF) shl 24) or
            ((buf[off + 4].toLong() and 0xFF) shl 32) or
            ((buf[off + 5].toLong() and 0xFF) shl 40) or
            ((buf[off + 6].toLong() and 0xFF) shl 48) or
            ((buf[off + 7].toLong() and 0xFF) shl 56)
}
