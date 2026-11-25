package btm.m.auraplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class AuraExtractorsTest {
    private class ByteArrayExtractorInput(private val data: ByteArray) : ExtractorInput {
        private var pos = 0
        private var peekPos = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, buffer, offset, n)
            pos += n
            peekPos = pos
            return n
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > data.size) {
                if (allowEndOfInput && pos == data.size) return false
                throw java.io.EOFException()
            }
            System.arraycopy(data, pos, target, offset, length)
            pos += length
            peekPos = pos
            return true
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            readFully(target, offset, length, false)
        }

        override fun skip(length: Int): Int {
            val n = minOf(length, data.size - pos)
            pos += n
            peekPos = pos
            return n
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > data.size) {
                if (allowEndOfInput && pos == data.size) return false
                throw java.io.EOFException()
            }
            pos += length
            peekPos = pos
            return true
        }

        override fun skipFully(length: Int) {
            skipFully(length, false)
        }

        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            if (peekPos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - peekPos)
            System.arraycopy(data, peekPos, target, offset, n)
            peekPos += n
            return n
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (peekPos + length > data.size) {
                if (allowEndOfInput && peekPos == data.size) return false
                throw java.io.EOFException()
            }
            System.arraycopy(data, peekPos, target, offset, length)
            peekPos += length
            return true
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            peekFully(target, offset, length, false)
        }

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (peekPos + length > data.size) {
                if (allowEndOfInput) return false
                throw java.io.EOFException()
            }
            peekPos += length
            return true
        }

        override fun advancePeekPosition(length: Int) {
            advancePeekPosition(length, false)
        }

        override fun resetPeekPosition() {
            peekPos = pos
        }

        override fun getPeekPosition(): Long = peekPos.toLong()
        override fun getPosition(): Long = pos.toLong()
        override fun getLength(): Long = data.size.toLong()
        override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
            pos = position.toInt()
            peekPos = pos
            throw e
        }
    }

    private class RecordingOutput : ExtractorOutput, TrackOutput {
        var format: Format? = null
        var seekMap: SeekMap? = null
        val samples = ByteArrayOutputStream()

        override fun track(id: Int, type: Int): TrackOutput = this
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }

        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = 0
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val buf = ByteArray(length)
            data.readBytes(buf, 0, length)
            samples.write(buf)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
    }

    @Test
    fun aiffExtractorSniffsAndReadsPcm16Be() {
        val pcm = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x01, 0x02, 0x03, 0x04)
        val out = ByteArrayOutputStream()
        // FORM + size + AIFF
        out.write("FORM".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(4 + 8 + 18 + 8 + 8 + pcm.size).array())
        out.write("AIFF".toByteArray(Charsets.US_ASCII))
        // COMM chunk (18 bytes): 2 ch, 2 frames, 16-bit, 44100 Hz (0x400E AC44 0000 0000 0000)
        out.write("COMM".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(18).array())
        out.write(byteArrayOf(0, 2, 0, 0, 0, 2, 0, 16, 0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0))
        // SSND chunk
        out.write("SSND".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(8 + pcm.size).array())
        out.write(ByteArray(8))
        out.write(pcm)

        val bytes = out.toByteArray()
        val extractor = AiffExtractor()
        assertTrue(extractor.sniff(ByteArrayExtractorInput(bytes)))
        assertFalse(extractor.sniff(ByteArrayExtractorInput(ByteArray(16))))

        val rec = RecordingOutput()
        extractor.init(rec)
        val input = ByteArrayExtractorInput(bytes)
        val holder = PositionHolder()
        while (extractor.read(input, holder) == Extractor.RESULT_CONTINUE) Unit
        assertEquals(MimeTypes.AUDIO_RAW, rec.format?.sampleMimeType)
        assertEquals(C.ENCODING_PCM_16BIT_BIG_ENDIAN, rec.format?.pcmEncoding)
        assertEquals(44100, rec.format?.sampleRate)
        assertEquals(2, rec.format?.channelCount)
        assertEquals(pcm.size, rec.samples.size())
        assertNotNull(rec.seekMap)
    }

    @Test
    fun cafExtractorSniffsAndReadsAlacWithCookie() {
        val cookie = ByteArray(24).apply { this[5] = 24 }
        val pkt = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val out = ByteArrayOutputStream()
        out.write("caff".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 1, 0, 0))
        // desc chunk (32 bytes)
        out.write("desc".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(32L).array())
        val desc = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        desc.putDouble(48000.0)
        desc.put("alac".toByteArray(Charsets.US_ASCII))
        desc.putInt(0)
        desc.putInt(0) // VBR
        desc.putInt(4096)
        desc.putInt(2)
        desc.putInt(0)
        out.write(desc.array())
        // kuki chunk
        out.write("kuki".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(cookie.size.toLong()).array())
        out.write(cookie)
        // pakt chunk (1 packet, size=4)
        out.write("pakt".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(25L).array())
        val pakt = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        pakt.putLong(1L).putLong(4096L).putInt(0).putInt(0)
        out.write(pakt.array())
        out.write(byteArrayOf(4))
        // data chunk (4 bytes mEditCount + pkt)
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(4L + pkt.size).array())
        out.write(ByteArray(4))
        out.write(pkt)

        val bytes = out.toByteArray()
        val extractor = CafExtractor()
        assertTrue(extractor.sniff(ByteArrayExtractorInput(bytes)))
        val rec = RecordingOutput()
        extractor.init(rec)
        val input = ByteArrayExtractorInput(bytes)
        val holder = PositionHolder()
        while (extractor.read(input, holder) == Extractor.RESULT_CONTINUE) Unit
        assertEquals(MimeTypes.AUDIO_ALAC, rec.format?.sampleMimeType)
        assertEquals(48000, rec.format?.sampleRate)
        assertEquals(2, rec.format?.channelCount)
        assertEquals(24, AudioDetails.alacBits(rec.format!!.initializationData.first()))
        assertEquals(pkt.size, rec.samples.size())
    }

    @Test
    fun dsfExtractorSniffsAndDecimatesDsd64ToFloatPcm() {
        val blockPerCh = 4096
        val channels = 2
        val totalFileSize = 28L + 52L + 12L + blockPerCh * channels
        val out = ByteArrayOutputStream()
        // DSD chunk (28 bytes)
        out.write("DSD ".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(28L).array())
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(totalFileSize).array())
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).array())
        // fmt chunk (52 bytes)
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        val fmt = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        fmt.putLong(52L)
        fmt.putInt(1).putInt(0).putInt(2).putInt(channels)
        fmt.putInt(2_822_400).putInt(1)
        fmt.putLong(blockPerCh.toLong() * 8L)
        fmt.putInt(blockPerCh).putInt(0)
        out.write(fmt.array())
        // data chunk
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(12L + blockPerCh * channels).array())
        out.write(ByteArray(blockPerCh * channels) { 0x55 }) // DSD zero-DC alternating bit pattern

        val bytes = out.toByteArray()
        val extractor = DsfExtractor()
        assertTrue(extractor.sniff(ByteArrayExtractorInput(bytes)))
        val rec = RecordingOutput()
        extractor.init(rec)
        val input = ByteArrayExtractorInput(bytes)
        val holder = PositionHolder()
        while (extractor.read(input, holder) == Extractor.RESULT_CONTINUE) Unit
        assertEquals(MimeTypes.AUDIO_RAW, rec.format?.sampleMimeType)
        assertEquals(C.ENCODING_PCM_FLOAT, rec.format?.pcmEncoding)
        assertEquals(88_200, rec.format?.sampleRate)
        assertEquals(channels, rec.format?.channelCount)
        assertEquals((blockPerCh / 4) * channels * 4, rec.samples.size())
    }
}
