package btm.m.auraplayer.download

import org.json.JSONObject
import java.io.*
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class MusicTags(val title: String, val artist: String, val album: String, val track: Int = 0, val year: Int = 0)
data class DecodedAudio(val file: File, val codec: String, val tags: MusicTags, val cover: ByteArray = byteArrayOf())

/** Streaming port of the supplied ncm2flac.py container algorithm; audio is never re-encoded. */
object AudioFileTools {
    private fun RandomAccessFile.leInt(): Int = Integer.reverseBytes(readInt())
    private fun RandomAccessFile.block(max: Int): ByteArray {
        val length = leInt()
        require(length in 0..max && length.toLong() <= length() - filePointer) { "INVALID_NCM_LENGTH" }
        return ByteArray(length).also(::readFully)
    }
    private fun aes(data: ByteArray, key: String) = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(UTF_8), "AES")); doFinal(data)
    }
    fun decode(input: File, output: File, fallback: MusicTags): DecodedAudio {
        RandomAccessFile(input, "r").use { source ->
            require(source.length() >= 4) { "EMPTY_AUDIO" }
            val signature = ByteArray(minOf(8L, source.length()).toInt()).also(source::readFully)
            if (!signature.contentEquals("CTENFDAM".toByteArray())) {
                val codec = detectCodec(input)
                require(codec in setOf("flac", "mp3")) { "UNSUPPORTED_AUDIO" }
                return DecodedAudio(input, codec, fallback)
            }
            source.skipBytes(2)
            val encryptedKey = source.block(1024 * 1024).map { (it.toInt() xor 0x64).toByte() }.toByteArray()
            val key = aes(encryptedKey, "hzHRAmso5kInbaxW").drop(17).toByteArray()
            require(key.isNotEmpty()) { "INVALID_NCM_KEY" }
            val box = IntArray(256) { it }
            var last = 0
            for (i in 0..255) {
                val swap = box[i]
                val c = (swap + last + (key[i % key.size].toInt() and 255)) and 255
                box[i] = box[c]; box[c] = swap; last = c
            }
            val metadata = source.block(16 * 1024 * 1024).map { (it.toInt() xor 0x63).toByte() }.toByteArray()
            val info = if (metadata.isNotEmpty()) {
                require(metadata.size > 22)
                val decoded = aes(Base64.getDecoder().decode(metadata.copyOfRange(22, metadata.size)), "#14ljk_!\\]&0U<'(")
                JSONObject(decoded.toString(UTF_8).substringAfter(':'))
            } else JSONObject()
            source.skipBytes(4) // CRC
            source.skipBytes(5) // gap + image allocation field
            val cover = source.block(32 * 1024 * 1024)
            val tags = MusicTags(info.optString("musicName").ifBlank { fallback.title },
                info.optJSONArray("artist")?.let { artists -> (0 until artists.length()).mapNotNull {
                    artists.optJSONArray(it)?.optString(0)
                }.joinToString(" / ") }?.takeIf { it.isNotBlank() } ?: fallback.artist,
                info.optString("album").ifBlank { fallback.album }, info.optInt("track", fallback.track), fallback.year)
            output.outputStream().buffered().use { sink ->
                val bytes = ByteArray(64 * 1024)
                var position = 0L
                while (true) {
                    val count = source.read(bytes); if (count < 0) break
                    for (n in 0 until count) {
                        val i = ((position + n + 1) and 255L).toInt()
                        val mask = box[(box[i] + box[(box[i] + i) and 255]) and 255]
                        bytes[n] = (bytes[n].toInt() xor mask).toByte()
                    }
                    sink.write(bytes, 0, count); position += count
                }
            }
            val codec = detectCodec(output)
            require(codec in setOf("flac", "mp3")) { "INVALID_DECODED_AUDIO" }
            return DecodedAudio(output, codec, tags, cover)
        }
    }
    fun detectCodec(file: File): String = file.inputStream().use { input ->
        val b = ByteArray(4); input.read(b)
        when {
            b.contentEquals("fLaC".toByteArray()) -> "flac"
            b.take(3).toByteArray().contentEquals("ID3".toByteArray()) || (b[0].toInt() and 255 == 255 && b[1].toInt() and 224 == 224) -> "mp3"
            else -> ""
        }
    }
    fun writeTags(audio: DecodedAudio, destination: File, cover: ByteArray = audio.cover) {
        if (audio.codec == "flac") writeFlac(audio.file, destination, audio.tags, cover)
        else writeMp3(audio.file, destination, audio.tags, cover)
    }
    private fun syncSafe(size: Int) = byteArrayOf(((size shr 21) and 127).toByte(), ((size shr 14) and 127).toByte(), ((size shr 7) and 127).toByte(), (size and 127).toByte())
    private fun writeMp3(input: File, output: File, tags: MusicTags, cover: ByteArray) {
        val frames = ByteArrayOutputStream()
        fun frame(id: String, bytes: ByteArray) {
            frames.write(id.toByteArray()); frames.write(syncSafe(bytes.size)); frames.write(byteArrayOf(0, 0)); frames.write(bytes)
        }
        listOf("TIT2" to tags.title, "TPE1" to tags.artist, "TALB" to tags.album,
            "TRCK" to tags.track.takeIf { it > 0 }?.toString().orEmpty(), "TDRC" to tags.year.takeIf { it > 0 }?.toString().orEmpty())
            .filter { it.second.isNotBlank() }.forEach { (id, text) -> frame(id, byteArrayOf(3) + text.toByteArray(UTF_8)) }
        if (cover.isNotEmpty()) frame("APIC", byteArrayOf(3) + coverMime(cover).toByteArray() + byteArrayOf(0, 3, 0) + cover)
        RandomAccessFile(input, "r").use { source ->
            val header = ByteArray(10)
            if (source.length() >= 10) source.readFully(header)
            if (header.take(3).toByteArray().contentEquals("ID3".toByteArray())) {
                val size = (6..9).fold(0) { n, i -> (n shl 7) or (header[i].toInt() and 127) }
                val footer = if (header[3].toInt() == 4 && header[5].toInt() and 16 != 0) 10 else 0
                require(size + 10L + footer <= source.length())
                source.seek(size + 10L + footer)
            } else source.seek(0)
            output.outputStream().buffered().use { sink ->
                sink.write("ID3".toByteArray()); sink.write(byteArrayOf(4, 0, 0)); sink.write(syncSafe(frames.size())); frames.writeTo(sink)
                copy(source, sink)
            }
        }
    }
    private fun coverMime(bytes: ByteArray) = if (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) "image/png" else "image/jpeg"
    private fun writeFlac(input: File, output: File, tags: MusicTags, cover: ByteArray) {
        RandomAccessFile(input, "r").use { source ->
            require(source.readInt() == 0x664c6143)
            val blocks = mutableListOf<Pair<Int, ByteArray>>()
            var last: Boolean
            do {
                val header = source.readUnsignedByte(); last = header and 128 != 0
                val type = header and 127
                val size = (source.readUnsignedByte() shl 16) or (source.readUnsignedByte() shl 8) or source.readUnsignedByte()
                require(size.toLong() <= source.length() - source.filePointer)
                val bytes = ByteArray(size).also(source::readFully)
                if (type != 1 && type != 4 && (type != 6 || cover.isEmpty())) blocks += type to bytes
            } while (!last)
            require(blocks.firstOrNull()?.first == 0)
            val comment = ByteArrayOutputStream()
            fun le(value: Int) { comment.write(byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())) }
            fun text(value: String) { val bytes = value.toByteArray(UTF_8); le(bytes.size); comment.write(bytes) }
            text("Aura Player")
            val values = listOf("TITLE=${tags.title}", "ARTIST=${tags.artist}", "ALBUM=${tags.album}") +
                listOfNotNull(tags.track.takeIf { it > 0 }?.let { "TRACKNUMBER=$it" }, tags.year.takeIf { it > 0 }?.let { "DATE=$it" })
            le(values.size); values.forEach(::text); blocks += 4 to comment.toByteArray()
            if (cover.isNotEmpty()) {
                val picture = ByteArrayOutputStream()
                DataOutputStream(picture).use { p ->
                    p.writeInt(3); val mime = coverMime(cover).toByteArray(); p.writeInt(mime.size); p.write(mime)
                    p.writeInt(0); repeat(4) { p.writeInt(0) }; p.writeInt(cover.size); p.write(cover)
                }
                blocks += 6 to picture.toByteArray()
            }
            output.outputStream().buffered().use { sink ->
                sink.write("fLaC".toByteArray())
                blocks.forEachIndexed { index, (type, bytes) ->
                    require(bytes.size <= 0xffffff)
                    sink.write(type or if (index == blocks.lastIndex) 128 else 0)
                    sink.write(byteArrayOf((bytes.size shr 16).toByte(), (bytes.size shr 8).toByte(), bytes.size.toByte())); sink.write(bytes)
                }
                copy(source, sink)
            }
        }
    }
    private fun copy(input: RandomAccessFile, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
    }
}
