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
 * Media3 [Extractor] for Apple Core Audio Format (`.caf`) containers.
 * Supports ALAC (`alac`), linear PCM (`lpcm`), AAC (`aac `), MP3 (`.mp3`), FLAC (`flac`),
 * Opus (`opus`), and G.711 (`ulaw`/`alaw`), including `pakt` VBR seeking.
 */
@UnstableApi
class CafExtractor : Extractor {
    private var output: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var headerParsed = false

    private var sampleRate = 0
    private var channels = 0
    private var bitsPerChannel = 0
    private var bytesPerPacket = 0
    private var framesPerPacket = 0
    private var formatId = ""
    private var formatFlags = 0
    private var kuki: ByteArray? = null

    private var dataStart = -1L
    private var dataSize = 0L
    private var packetSizes: IntArray? = null
    private var packetOffsets: LongArray? = null
    private var currentPacketIndex = 0
    private var bytesRead = 0L
    private var totalValidFrames = 0L

    private var signed8BitToUnsigned = false
    private var swapFloat32Endian = false
    private var scratch = ByteArray(0)
    private val parsable = ParsableByteArray()

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(8)
        input.peekFully(header, 0, 8)
        return header[0] == 'c'.code.toByte() &&
            header[1] == 'a'.code.toByte() &&
            header[2] == 'f'.code.toByte() &&
            header[3] == 'f'.code.toByte() &&
            header[4] == 0.toByte() &&
            header[5] == 1.toByte()
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        this.trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
    }

    override fun seek(position: Long, timeUs: Long) {
        if (!headerParsed || sampleRate <= 0) return
        val sizes = packetSizes
        val offsets = packetOffsets
        if (sizes != null && offsets != null && framesPerPacket > 0) {
            val targetFrame = (timeUs.coerceAtLeast(0L) * sampleRate) / C.MICROS_PER_SECOND
            val idx = (targetFrame / framesPerPacket).toInt().coerceIn(0, (sizes.size - 1).coerceAtLeast(0))
            currentPacketIndex = idx
            bytesRead = if (idx < offsets.size) offsets[idx] else dataSize
        } else if (bytesPerPacket > 0 && framesPerPacket > 0) {
            val targetFrame = (timeUs.coerceAtLeast(0L) * sampleRate) / C.MICROS_PER_SECOND
            val packetIdx = (targetFrame / framesPerPacket).coerceAtLeast(0L)
            val byteOffset = (packetIdx * bytesPerPacket).coerceIn(0L, dataSize)
            bytesRead = byteOffset - (byteOffset % bytesPerPacket)
        }
    }

    override fun release() = Unit

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headerParsed) {
            parseHeader(input)
            headerParsed = true
        }
        val sizes = packetSizes
        val offsets = packetOffsets
        if (sizes != null && offsets != null) {
            if (currentPacketIndex >= sizes.size) return Extractor.RESULT_END_OF_INPUT
            val pktSize = sizes[currentPacketIndex]
            val pktOffset = offsets[currentPacketIndex]
            if (pktSize <= 0 || pktOffset + pktSize > dataSize) return Extractor.RESULT_END_OF_INPUT
            val desiredPos = dataStart + pktOffset
            if (input.position != desiredPos) {
                seekPosition.position = desiredPos
                return Extractor.RESULT_SEEK
            }
            if (scratch.size < pktSize) scratch = ByteArray(pktSize)
            input.readFully(scratch, 0, pktSize)
            val timeUs = currentPacketIndex.toLong() * framesPerPacket * C.MICROS_PER_SECOND / sampleRate
            parsable.reset(scratch, pktSize)
            val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
            track.sampleData(parsable, pktSize)
            track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, pktSize, 0, null)
            currentPacketIndex++
            bytesRead = pktOffset + pktSize
            return if (currentPacketIndex >= sizes.size) Extractor.RESULT_END_OF_INPUT else Extractor.RESULT_CONTINUE
        }

        if (bytesPerPacket <= 0 || bytesRead >= dataSize) return Extractor.RESULT_END_OF_INPUT
        val desiredPos = dataStart + bytesRead
        if (input.position != desiredPos) {
            seekPosition.position = desiredPos
            return Extractor.RESULT_SEEK
        }
        val chunkLimit = (4096 * bytesPerPacket).coerceAtLeast(bytesPerPacket)
        val remaining = (dataSize - bytesRead).coerceAtMost(chunkLimit.toLong()).toInt()
        val alignedToRead = remaining - (remaining % bytesPerPacket)
        if (alignedToRead <= 0) return Extractor.RESULT_END_OF_INPUT

        if (scratch.size < alignedToRead) scratch = ByteArray(alignedToRead)
        val readNow = input.read(scratch, 0, alignedToRead)
        if (readNow == C.RESULT_END_OF_INPUT) return Extractor.RESULT_END_OF_INPUT
        val usable = readNow - (readNow % bytesPerPacket)
        if (usable <= 0) return Extractor.RESULT_END_OF_INPUT

        if (signed8BitToUnsigned) {
            for (i in 0 until usable) scratch[i] = ((scratch[i].toInt() + 128) and 0xFF).toByte()
        } else if (swapFloat32Endian) {
            var i = 0
            while (i + 3 < usable) {
                val b0 = scratch[i]; val b1 = scratch[i + 1]
                scratch[i] = scratch[i + 3]; scratch[i + 1] = scratch[i + 2]
                scratch[i + 2] = b1; scratch[i + 3] = b0
                i += 4
            }
        }

        val packetIndex = bytesRead / bytesPerPacket
        val timeUs = packetIndex * framesPerPacket * C.MICROS_PER_SECOND / sampleRate
        parsable.reset(scratch, usable)
        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        track.sampleData(parsable, usable)
        track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, usable, 0, null)
        bytesRead += usable
        return if (bytesRead >= dataSize) Extractor.RESULT_END_OF_INPUT else Extractor.RESULT_CONTINUE
    }

    private fun parseHeader(input: ExtractorInput) {
        val fileHeader = ByteArray(8)
        input.readFully(fileHeader, 0, 8)

        var descFound = false
        val chunkHead = ByteArray(12)
        while (true) {
            val ok = input.readFully(chunkHead, 0, 12, true)
            if (!ok) break
            val type = String(chunkHead, 0, 4, Charsets.US_ASCII)
            val chunkSize = readI64BE(chunkHead, 4)
            when (type) {
                "desc" -> {
                    if (chunkSize < 32) throw ParserException.createForMalformedContainer("Invalid CAF desc chunk", null)
                    val desc = ByteArray(32)
                    input.readFully(desc, 0, 32)
                    sampleRate = Double.fromBits(readI64BE(desc, 0)).roundToInt().coerceIn(8000, 384000)
                    formatId = String(desc, 8, 4, Charsets.US_ASCII)
                    formatFlags = readI32BE(desc, 12)
                    bytesPerPacket = readI32BE(desc, 16)
                    framesPerPacket = readI32BE(desc, 20)
                    channels = readI32BE(desc, 24)
                    bitsPerChannel = readI32BE(desc, 28)
                    if (chunkSize > 32L) skipLong(input, chunkSize - 32L)
                    descFound = true
                }
                "kuki" -> {
                    if (chunkSize in 1L..65536L) {
                        val cookie = ByteArray(chunkSize.toInt())
                        input.readFully(cookie, 0, cookie.size)
                        kuki = cookie
                    } else if (chunkSize > 0L) {
                        skipLong(input, chunkSize)
                    }
                }
                "pakt" -> {
                    if (chunkSize >= 24L) {
                        val paktHead = ByteArray(24)
                        input.readFully(paktHead, 0, 24)
                        val numPackets = readI64BE(paktHead, 0)
                        totalValidFrames = readI64BE(paktHead, 8)
                        val tableBytesLen = (chunkSize - 24L).coerceAtLeast(0L)
                        if (bytesPerPacket == 0 && numPackets in 1L..5_000_000L && tableBytesLen in 1L..32_000_000L) {
                            val tableBytes = ByteArray(tableBytesLen.toInt())
                            input.readFully(tableBytes, 0, tableBytes.size)
                            val count = numPackets.toInt()
                            val sizes = IntArray(count)
                            val offsets = LongArray(count)
                            var pos = 0
                            var offset = 0L
                            var parsed = 0
                            while (parsed < count && pos < tableBytes.size) {
                                var value = 0
                                var b: Int
                                do {
                                    b = tableBytes[pos++].toInt() and 0xFF
                                    value = (value shl 7) or (b and 0x7F)
                                } while ((b and 0x80) != 0 && pos < tableBytes.size)
                                sizes[parsed] = value
                                offsets[parsed] = offset
                                offset += value
                                parsed++
                            }
                            packetSizes = if (parsed == count) sizes else sizes.copyOf(parsed)
                            packetOffsets = if (parsed == count) offsets else offsets.copyOf(parsed)
                        } else if (tableBytesLen > 0L) {
                            skipLong(input, tableBytesLen)
                        }
                    } else if (chunkSize > 0L) {
                        skipLong(input, chunkSize)
                    }
                }
                "data" -> {
                    if (chunkSize >= 0L && chunkSize < 4L) throw ParserException.createForMalformedContainer("Invalid CAF data chunk", null)
                    input.skipFully(4) // mEditCount
                    dataStart = input.position
                    dataSize = if (chunkSize < 0L) {
                        if (input.length != C.LENGTH_UNSET.toLong()) (input.length - dataStart).coerceAtLeast(0L) else 0L
                    } else {
                        (chunkSize - 4L).coerceAtLeast(0L)
                    }
                    break
                }
                else -> {
                    if (chunkSize < 0L) break
                    if (chunkSize > 0L) skipLong(input, chunkSize)
                }
            }
        }

        if (!descFound || dataStart < 0L || channels <= 0 || sampleRate <= 0) {
            throw ParserException.createForMalformedContainer("Missing CAF desc or data chunk", null)
        }

        val formatBuilder = Format.Builder()
            .setChannelCount(channels)
            .setSampleRate(sampleRate)

        when (formatId) {
            "alac" -> {
                formatBuilder.setSampleMimeType(MimeTypes.AUDIO_ALAC)
                kuki?.let { formatBuilder.setInitializationData(listOf(it)) }
            }
            "aac " -> {
                formatBuilder.setSampleMimeType(MimeTypes.AUDIO_AAC)
                kuki?.let { formatBuilder.setInitializationData(listOf(it)) }
            }
            ".mp3" -> formatBuilder.setSampleMimeType(MimeTypes.AUDIO_MPEG)
            "flac" -> {
                formatBuilder.setSampleMimeType(MimeTypes.AUDIO_FLAC)
                kuki?.let { formatBuilder.setInitializationData(listOf(it)) }
            }
            "opus" -> {
                formatBuilder.setSampleMimeType(MimeTypes.AUDIO_OPUS)
                kuki?.let { formatBuilder.setInitializationData(listOf(it)) }
            }
            "ulaw" -> formatBuilder.setSampleMimeType(MimeTypes.AUDIO_MLAW)
            "alaw" -> formatBuilder.setSampleMimeType(MimeTypes.AUDIO_ALAW)
            "lpcm" -> {
                val isFloat = (formatFlags and 1) != 0
                val isLittleEndian = (formatFlags and 2) != 0
                val pcmEnc = when {
                    isFloat && bitsPerChannel == 32 -> {
                        swapFloat32Endian = !isLittleEndian
                        C.ENCODING_PCM_FLOAT
                    }
                    bitsPerChannel == 8 -> {
                        signed8BitToUnsigned = true
                        C.ENCODING_PCM_8BIT
                    }
                    bitsPerChannel == 16 -> if (isLittleEndian) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_16BIT_BIG_ENDIAN
                    bitsPerChannel == 24 -> if (isLittleEndian) C.ENCODING_PCM_24BIT else C.ENCODING_PCM_24BIT_BIG_ENDIAN
                    bitsPerChannel == 32 -> if (isLittleEndian) C.ENCODING_PCM_32BIT else C.ENCODING_PCM_32BIT_BIG_ENDIAN
                    else -> throw ParserException.createForUnsupportedContainerFeature("Unsupported CAF LPCM bit depth: $bitsPerChannel")
                }
                if (bytesPerPacket <= 0) bytesPerPacket = channels * (bitsPerChannel / 8)
                if (framesPerPacket <= 0) framesPerPacket = 1
                formatBuilder.setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(pcmEnc)
            }
            else -> throw ParserException.createForUnsupportedContainerFeature("Unsupported CAF formatId: $formatId")
        }

        val sizes = packetSizes
        val offsets = packetOffsets
        val totalFrames = when {
            totalValidFrames > 0L -> totalValidFrames
            sizes != null && framesPerPacket > 0 -> sizes.size.toLong() * framesPerPacket
            bytesPerPacket > 0 && framesPerPacket > 0 -> (dataSize / bytesPerPacket) * framesPerPacket
            else -> 0L
        }
        val durationUs = if (totalFrames > 0L) totalFrames * C.MICROS_PER_SECOND / sampleRate else C.TIME_UNSET

        val out = output ?: return
        trackOutput?.format(formatBuilder.build())
        out.endTracks()
        out.seekMap(object : SeekMap {
            override fun isSeekable(): Boolean = durationUs != C.TIME_UNSET && (sizes != null || bytesPerPacket > 0)
            override fun getDurationUs(): Long = durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                if (!isSeekable) return SeekMap.SeekPoints(SeekPoint(0L, dataStart))
                val clampedUs = timeUs.coerceIn(0L, durationUs)
                val targetFrame = clampedUs * sampleRate / C.MICROS_PER_SECOND
                val pos = if (sizes != null && offsets != null && framesPerPacket > 0 && sizes.isNotEmpty()) {
                    val idx = (targetFrame / framesPerPacket).toInt().coerceIn(0, sizes.size - 1)
                    dataStart + offsets[idx]
                } else {
                    val pktIdx = (targetFrame / framesPerPacket.coerceAtLeast(1)).coerceAtLeast(0L)
                    dataStart + (pktIdx * bytesPerPacket).coerceIn(0L, dataSize)
                }
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

    private fun readI32BE(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    private fun readI64BE(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 56) or
            ((buf[off + 1].toLong() and 0xFF) shl 48) or
            ((buf[off + 2].toLong() and 0xFF) shl 40) or
            ((buf[off + 3].toLong() and 0xFF) shl 32) or
            ((buf[off + 4].toLong() and 0xFF) shl 24) or
            ((buf[off + 5].toLong() and 0xFF) shl 16) or
            ((buf[off + 6].toLong() and 0xFF) shl 8) or
            (buf[off + 7].toLong() and 0xFF)
}
