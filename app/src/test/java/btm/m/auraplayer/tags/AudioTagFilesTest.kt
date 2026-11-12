package btm.m.auraplayer.tags

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class AudioTagFilesTest {
    @get:Rule val temp = TemporaryFolder(File("build/tmp/tag-editor-tests").apply { mkdirs() })
    private val payload = ByteArray(4096) { ((it * 17) % 251).toByte() }
    private fun flac(): File {
        val stream = ByteArray(34)
        stream[0] = 0x10; stream[2] = 0x10
        val packed = (44100L shl 44) or (1L shl 41) or (15L shl 36) or 44100L
        for (i in 0..7) stream[10 + i] = (packed ushr (56 - i * 8)).toByte()
        return temp.newFile("sample-${System.nanoTime()}.flac").apply {
            writeBytes("fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + stream + payload)
        }
    }

    private fun encodedAudio(file: File): ByteArray = RandomAccessFile(file, "r").use { source ->
        assertEquals(0x664c6143, source.readInt())
        do {
            val flag = source.readUnsignedByte()
            val size = (source.readUnsignedByte() shl 16) or (source.readUnsignedByte() shl 8) or source.readUnsignedByte()
            source.skipBytes(size)
        } while (flag and 128 == 0)
        ByteArray((source.length() - source.filePointer).toInt()).also(source::readFully)
    }

    @Test fun writesRealFlacFieldsRetainingUneditedTagsAndEncodedPayload() {
        val file = flac()
        val initial = AudioFileIO.read(file)
        initial.tagOrCreateAndSetDefault.apply { setField(FieldKey.GENRE, "Keep genre"); setField(FieldKey.ARTIST, "Keep artist") }
        initial.commit()
        val original = AudioTagFiles.read(file)
        val draft = original.edited(AudioTagField.TITLE, "歌曲タイトル")
            .edited(AudioTagField.COMPOSER, "Composer").edited(AudioTagField.LYRICIST, "Lyricist")
            .edited(AudioTagField.YEAR, "2026").edited(AudioTagField.RELEASE_DATE, "2026-11-12")
            .edited(AudioTagField.TRACK, "12").edited(AudioTagField.ALBUM_ARTIST, "Various Artists")
            .edited(AudioTagField.COMMENT, "first\nsecond").edited(AudioTagField.LYRICS, "[00:00.00]歌词\n[00:01.00]Lyrics")
        AudioTagFiles.edit(file, original, draft)
        val saved = AudioTagFiles.read(file)
        assertEquals(draft.values, saved.values)
        assertEquals("Keep genre", AudioFileIO.read(file).tag.getFirst(FieldKey.GENRE))
        assertArrayEquals(payload, encodedAudio(file))
        AudioTagFiles.edit(file, saved, saved.edited(AudioTagField.COMMENT, ""))
        assertEquals("", AudioTagFiles.read(file)[AudioTagField.COMMENT])
        assertArrayEquals(payload, encodedAudio(file))
    }

    @Test fun nonSquareArtworkDraftUsesStableValueEquality() {
        val original = TagCover(byteArrayOf(1, 2, 3), "image/png", 2, 1)
        val same = TagCover(byteArrayOf(1, 2, 3), "image/png", 2, 1)
        val changed = TagCover(byteArrayOf(1, 2, 4), "image/png", 2, 1)
        assertEquals(original, same)
        assertNotEquals(original, changed)
    }

    @Test fun writesAndClearsMp3TagsWithoutChangingMpegFrames() {
        val frames = java.io.ByteArrayOutputStream().apply {
            repeat(24) { write(byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0)); write(ByteArray(413)) }
        }.toByteArray()
        val file = temp.newFile("track.mp3").apply { writeBytes(frames) }
        val original = AudioTagFiles.read(file)
        val draft = original.edited(AudioTagField.TITLE, "中文 title").edited(AudioTagField.TRACK, "7")
            .edited(AudioTagField.LYRICS, "[00:01]第一行\n[00:03]第二行")
            .edited(AudioTagField.LYRICIST, "Writer").edited(AudioTagField.COMPOSER, "Composer")
        AudioTagFiles.edit(file, original, draft)
        assertEquals(draft.values, AudioTagFiles.read(file).values)
        assertArrayEquals(frames, file.readBytes().takeLast(frames.size).toByteArray())
    }
}
