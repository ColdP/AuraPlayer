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
import kotlin.math.roundToInt

/**
 * Media3 [Extractor] for AIFF and AIFF-C (`FORM`/`AIFF`, `FORM`/`AIFC`) audio files.
 * Supports big-endian & little-endian (`sowt`) 8/16/24/32-bit PCM, `fl32` IEEE float,
 * and G.711 `alaw` / `ulaw` streams.
 */
@UnstableApi
class AiffExtractor : Extractor {
    private var output: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var headerParsed = false

    private var channels = 0
    private var sampleRate = 0
    private var numSampleFrames = 0L
    private var bytesPerFrame = 0
    private var dataStart = -1L
    private var dataSize = 0L
    private var bytesRead = 0L

    private var signed8BitToUnsigned = false
    private var swapFloat32Endian = false
    private var scratch = ByteArray(0)
    private val parsable = ParsableByteArray()

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(12)
        input.peekFully(header, 0, 12)
        return header[0] == 'F'.code.toByte() &&
            header[1] == 'O'.code.toByte() &&
            header[2] == 'R'.code.toByte() &&
            header[3] == 'M'.code.toByte() &&
            header[8] == 'A'.code.toByte() &&
            header[9] == 'I'.code.toByte() &&
            header[10] == 'F'.code.toByte() &&
            (header[11] == 'F'.code.toByte() || header[11] == 'C'.code.toByte())
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        this.trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
    }

    override fun seek(position: Long, timeUs: Long) {
        if (!headerParsed || bytesPerFrame <= 0 || sampleRate <= 0) return
        val frameIndex = ((timeUs.coerceAtLeast(0L) * sampleRate) / C.MICROS_PER_SECOND)
            .coerceIn(0L, numSampleFrames.coerceAtLeast(0L))
        val byteOffset = (frameIndex * bytesPerFrame).coerceIn(0L, dataSize)
        bytesRead = byteOffset - (byteOffset % bytesPerFrame)
    }

    override fun release() = Unit

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headerParsed) {
            parseHeader(input)
            headerParsed = true
        }
        if (bytesRead >= dataSize) return Extractor.RESULT_END_OF_INPUT
        val desiredPos = dataStart + bytesRead
        if (input.position != desiredPos) {
            seekPosition.position = desiredPos
            return Extractor.RESULT_SEEK
        }

        val frameAlignedChunk = (4096 * bytesPerFrame).coerceAtLeast(bytesPerFrame)
        val remaining = (dataSize - bytesRead).coerceAtMost(frameAlignedChunk.toLong()).toInt()
        val alignedToRead = remaining - (remaining % bytesPerFrame)
        if (alignedToRead <= 0) return Extractor.RESULT_END_OF_INPUT

        if (scratch.size < alignedToRead) scratch = ByteArray(alignedToRead)
        val readNow = input.read(scratch, 0, alignedToRead)
        if (readNow == C.RESULT_END_OF_INPUT) return Extractor.RESULT_END_OF_INPUT

        val usable = readNow - (readNow % bytesPerFrame)
        if (usable <= 0) return Extractor.RESULT_END_OF_INPUT

        if (signed8BitToUnsigned) {
            for (i in 0 until usable) {
                scratch[i] = ((scratch[i].toInt() + 128) and 0xFF).toByte()
            }
        } else if (swapFloat32Endian) {
            var i = 0
            while (i + 3 < usable) {
                val b0 = scratch[i]
                val b1 = scratch[i + 1]
                scratch[i] = scratch[i + 3]
                scratch[i + 1] = scratch[i + 2]
                scratch[i + 2] = b1
                scratch[i + 3] = b0
                i += 4
            }
        }

        val frameIndex = bytesRead / bytesPerFrame
        val timeUs = frameIndex * C.MICROS_PER_SECOND / sampleRate.coerceAtLeast(1)
        parsable.reset(scratch, usable)
        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        track.sampleData(parsable, usable)
        track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, usable, 0, null)
        bytesRead += usable
        return if (bytesRead >= dataSize) Extractor.RESULT_END_OF_INPUT else Extractor.RESULT_CONTINUE
    }

    private fun parseHeader(input: ExtractorInput) {
        val formHeader = ByteArray(12)
        input.readFully(formHeader, 0, 12)
        val isAifc = formHeader[11] == 'C'.code.toByte()

        var commFound = false
        var sampleBits = 16
        var compression = "NONE"
        var ssndOffset = 0L

        val chunkHeader = ByteArray(8)
        while (true) {
            val ok = input.readFully(chunkHeader, 0, 8, true)
            if (!ok) break
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val chunkSize = readU32BE(chunkHeader, 4)
            when (id) {
                "COMM" -> {
                    val toRead = chunkSize.toInt().coerceAtLeast(18)
                    val comm = ByteArray(toRead)
                    input.readFully(comm, 0, toRead)
                    channels = readU16BE(comm, 0)
                    numSampleFrames = readU32BE(comm, 2)
                    sampleBits = readU16BE(comm, 6)
                    sampleRate = parseExtended80BE(comm, 8).roundToInt().coerceIn(8000, 384000)
                    if (isAifc && toRead >= 22) {
                        compression = String(comm, 18, 4, Charsets.US_ASCII)
                    }
                    if ((chunkSize and 1L) != 0L) input.skipFully(1)
                    commFound = true
                }
                "SSND" -> {
                    if (chunkSize < 8) throw ParserException.createForMalformedContainer("Invalid AIFF SSND chunk", null)
                    val ssndHead = ByteArray(8)
                    input.readFully(ssndHead, 0, 8)
                    ssndOffset = readU32BE(ssndHead, 0)
                    if (ssndOffset > 0L) input.skipFully(ssndOffset.toInt())
                    dataStart = input.position
                    val available = (chunkSize - 8L - ssndOffset).coerceAtLeast(0L)
                    dataSize = if (input.length != C.LENGTH_UNSET.toLong()) {
                        available.coerceAtMost((input.length - dataStart).coerceAtLeast(0L))
                    } else {
                        available
                    }
                    if (commFound) break
                    val skipPayload = available + (chunkSize and 1L)
                    skipLong(input, skipPayload)
                }
                else -> {
                    val skip = chunkSize + (chunkSize and 1L)
                    skipLong(input, skip)
                }
            }
            if (commFound && dataStart >= 0L) break
        }

        if (!commFound || dataStart < 0L || channels <= 0 || sampleRate <= 0) {
            throw ParserException.createForMalformedContainer("Missing AIFF COMM or SSND chunk", null)
        }

        val formatBuilder = Format.Builder()
            .setChannelCount(channels)
            .setSampleRate(sampleRate)

        when (compression.lowercase()) {
            "none", "twos" -> {
                val pcmEnc = when (sampleBits) {
                    8 -> {
                        signed8BitToUnsigned = true
                        C.ENCODING_PCM_8BIT
                    }
                    16 -> C.ENCODING_PCM_16BIT_BIG_ENDIAN
                    24 -> C.ENCODING_PCM_24BIT_BIG_ENDIAN
                    32 -> C.ENCODING_PCM_32BIT_BIG_ENDIAN
                    else -> throw ParserException.createForUnsupportedContainerFeature("Unsupported AIFF bit depth: $sampleBits")
                }
                bytesPerFrame = channels * (sampleBits / 8)
                formatBuilder.setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(pcmEnc)
            }
            "sowt" -> {
                val pcmEnc = when (sampleBits) {
                    8 -> {
                        signed8BitToUnsigned = true
                        C.ENCODING_PCM_8BIT
                    }
                    16 -> C.ENCODING_PCM_16BIT
                    24 -> C.ENCODING_PCM_24BIT
                    32 -> C.ENCODING_PCM_32BIT
                    else -> throw ParserException.createForUnsupportedContainerFeature("Unsupported AIFF-C sowt bit depth: $sampleBits")
                }
                bytesPerFrame = channels * (sampleBits / 8)
                formatBuilder.setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(pcmEnc)
            }
            "fl32" -> {
                swapFloat32Endian = true
                bytesPerFrame = channels * 4
                formatBuilder.setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_FLOAT)
            }
            "alaw" -> {
                bytesPerFrame = channels
                formatBuilder.setSampleMimeType(MimeTypes.AUDIO_ALAW)
            }
            "ulaw" -> {
                bytesPerFrame = channels
                formatBuilder.setSampleMimeType(MimeTypes.AUDIO_MLAW)
            }
            else -> throw ParserException.createForUnsupportedContainerFeature("Unsupported AIFF compression: $compression")
        }

        if (numSampleFrames <= 0L && bytesPerFrame > 0) {
            numSampleFrames = dataSize / bytesPerFrame
        }
        val durationUs = numSampleFrames * C.MICROS_PER_SECOND / sampleRate

        val out = output ?: return
        trackOutput?.format(formatBuilder.build())
        out.endTracks()
        out.seekMap(object : SeekMap {
            override fun isSeekable(): Boolean = true
            override fun getDurationUs(): Long = durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val clampedUs = timeUs.coerceIn(0L, durationUs)
                val frame = (clampedUs * sampleRate / C.MICROS_PER_SECOND).coerceIn(0L, numSampleFrames)
                val pos = dataStart + (frame * bytesPerFrame).coerceIn(0L, dataSize)
                return SeekMap.SeekPoints(SeekPoint(clampedUs, pos))
            }
        })
    }

    private fun skipLong(input: ExtractorInput, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val step = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            input.skipFully(step)
            remaining -= step
        }
    }

    private fun readU16BE(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private fun readU32BE(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or
            ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or
            (buf[off + 3].toLong() and 0xFF)

    /** Decodes 80-bit IEEE 754 extended precision float used in AIFF COMM chunks. */
    private fun parseExtended80BE(buf: ByteArray, off: Int): Double {
        val expRaw = ((buf[off].toInt() and 0x7F) shl 8) or (buf[off + 1].toInt() and 0xFF)
        var hiMant = 0L
        var loMant = 0L
        for (i in 0 until 4) hiMant = (hiMant shl 8) or (buf[off + 2 + i].toLong() and 0xFF)
        for (i in 0 until 4) loMant = (loMant shl 8) or (buf[off + 6 + i].toLong() and 0xFF)
        if (expRaw == 0 && hiMant == 0L && loMant == 0L) return 0.0
        if (expRaw == 0x7FFF) return 0.0
        val exp = expRaw - 16383
        val value = Math.scalb(hiMant.toDouble(), exp - 31) + Math.scalb(loMant.toDouble(), exp - 63)
        return if ((buf[off].toInt() and 0x80) != 0) -value else value
    }
}
