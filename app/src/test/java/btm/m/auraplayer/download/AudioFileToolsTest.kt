package btm.m.auraplayer.download

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class AudioFileToolsTest {
    @get:Rule val temp = TemporaryFolder(File("build/tmp/audio-file-tests").apply { mkdirs() })
    private val tags = MusicTags("歌曲 title", "艺术家", "专辑", 2, 2026)
    private fun encrypt(value: ByteArray, key: String) = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES")); doFinal(value)
    }
    private fun ncm(audio: ByteArray): File {
        val key = "fixture-key".toByteArray()
        // Fixed fixture generated with the format's key schedule, including non-aligned streaming length.
        val box = IntArray(256) { it }; var previous = 0
        for (i in 0..255) {
            val next = (box[i] + previous + key[i % key.size].toInt()) and 255
            val swap = box[i]; box[i] = box[next]; box[next] = swap; previous = next
        }
        val encrypted = audio.mapIndexed { index, byte ->
            val i = (index + 1) and 255
            (byte.toInt() xor box[(box[i] + box[(box[i] + i) and 255]) and 255]).toByte()
        }.toByteArray()
        val core = encrypt("neteasecloudmusic".toByteArray() + key, "hzHRAmso5kInbaxW").map { (it.toInt() xor 0x64).toByte() }.toByteArray()
        val json = """music:{"musicName":"NCM title","artist":[["Singer",1]],"album":"Album","format":"mp3"}""".toByteArray()
        val metadata = ("163 key(Don't modify):".toByteArray() + Base64.getEncoder().encode(encrypt(json, "#14ljk_!\\]&0U<'(")))
            .map { (it.toInt() xor 0x63).toByte() }.toByteArray()
        val file = temp.newFile()
        DataOutputStream(file.outputStream()).use { output ->
            fun le(value: Int) { output.writeInt(Integer.reverseBytes(value)) }
            output.write("CTENFDAM".toByteArray()); output.writeShort(0)
            le(core.size); output.write(core); le(metadata.size); output.write(metadata)
            output.write(ByteArray(9)); le(0); output.write(encrypted)
        }
        return file
    }
    @Test fun decryptsNcmInChunksWithoutChangingEncodedAudioAndReadsTags() {
        val original = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0) + ByteArray(150001) { (it % 251).toByte() }
        val output = temp.newFile()
        val decoded = AudioFileTools.decode(ncm(original), output, tags)
        assertEquals("mp3", decoded.codec)
        assertArrayEquals(original, decoded.file.readBytes())
        assertEquals("NCM title", decoded.tags.title); assertEquals("Singer", decoded.tags.artist)
    }
    @Test fun writesUtf8Id3AndKeepsMp3FramesByteForByte() {
        val input = temp.newFile().apply { writeBytes(byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0) + ByteArray(400) { 7 }) }
        val output = temp.newFile()
        AudioFileTools.writeTags(DecodedAudio(input, "mp3", tags), output)
        val bytes = output.readBytes()
        assertEquals("ID3", bytes.take(3).toByteArray().toString(Charsets.US_ASCII))
        val size = (6..9).fold(0) { n, i -> (n shl 7) or (bytes[i].toInt() and 127) }
        assertTrue(bytes.toString(Charsets.UTF_8).contains("歌曲 title"))
        assertArrayEquals(input.readBytes(), bytes.copyOfRange(size + 10, bytes.size))
        val second = temp.newFile()
        AudioFileTools.writeTags(DecodedAudio(output, "mp3", tags), second)
        assertArrayEquals(bytes, second.readBytes())
    }
    @Test fun flacTagsPreserveStreamInfoAndEncodedFrames() {
        val streamInfo = ByteArray(34) { (it + 1).toByte() }
        val frames = ByteArray(270) { (it % 256).toByte() }
        val input = temp.newFile().apply { writeBytes("fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + streamInfo + frames) }
        val output = temp.newFile()
        AudioFileTools.writeTags(DecodedAudio(input, "flac", tags), output)
        RandomAccessFile(output, "r").use { source ->
            assertEquals(0x664c6143, source.readInt())
            assertEquals(0, source.readUnsignedByte()); assertEquals(0x000022, (source.readUnsignedByte() shl 16) or (source.readUnsignedByte() shl 8) or source.readUnsignedByte())
            assertArrayEquals(streamInfo, ByteArray(34).also(source::readFully))
            assertEquals(132, source.readUnsignedByte())
            val size = (source.readUnsignedByte() shl 16) or (source.readUnsignedByte() shl 8) or source.readUnsignedByte()
            val comments = ByteArray(size).also(source::readFully).toString(Charsets.UTF_8)
            assertTrue(comments.contains("TITLE=歌曲 title")); assertTrue(comments.contains("ARTIST=艺术家")); assertTrue(comments.contains("ALBUM=专辑"))
            assertArrayEquals(frames, ByteArray(frames.size).also(source::readFully))
        }
    }
    @Test fun rejectsTruncatedOrMalformedInput() {
        val file = temp.newFile().apply { writeBytes("CTENFDAM".toByteArray() + ByteArray(2) + byteArrayOf(-1, -1, -1, 127)) }
        try { AudioFileTools.decode(file, temp.newFile(), tags); fail("Expected validation") } catch (_: IllegalArgumentException) { }
    }
}
