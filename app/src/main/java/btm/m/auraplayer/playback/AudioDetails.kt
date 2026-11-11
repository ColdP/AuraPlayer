package btm.m.auraplayer.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import btm.m.auraplayer.data.Song

/** Container/encoded audio properties; bit depth is never inferred from bitrate or Hi-Res labels. */
data class AudioDetails(val codec: String = "", val sampleRate: Int = 0, val bits: Int = 0, val bitrate: Int = 0) {
    fun withFormat(format: Format?): AudioDetails {
        if (format == null) return this
        return copy(codec = codecFor(format.sampleMimeType).ifBlank { codec },
            sampleRate = format.sampleRate.takeIf { it > 0 } ?: sampleRate,
            bits = pcmBits(format.pcmEncoding).takeIf { it > 0 }
                ?: (if (format.sampleMimeType == "audio/flac") format.initializationData.firstNotNullOfOrNull { flacBits(it).takeIf { depth -> depth > 0 } } else null) ?: bits,
            bitrate = format.averageBitrate.takeIf { it > 0 } ?: bitrate)
    }
    companion object {
        fun flacBits(bytes: ByteArray): Int {
            val offset = if (bytes.size >= 42 && bytes.take(4).toByteArray().contentEquals(byteArrayOf(102, 76, 97, 67))) 8
                else if (bytes.size == 34) 0 else return 0
            return (((bytes[offset + 12].toInt() and 1) shl 4) or ((bytes[offset + 13].toInt() and 0xF0) shr 4)) + 1
        }
        fun pcmBits(encoding: Int) = when (encoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }
        fun codecFor(mime: String?) = when (mime) {
            "audio/flac" -> "FLAC"; "audio/mpeg" -> "MP3"; "audio/mp4a-latm" -> "AAC"
            "audio/alac" -> "ALAC"; "audio/raw" -> "PCM"; "audio/opus" -> "Opus"; "audio/vorbis" -> "Vorbis"
            "audio/ac3" -> "AC-3"; "audio/eac3", "audio/eac3-joc" -> "E-AC-3"
            else -> mime?.substringAfter('/')?.uppercase().orEmpty()
        }
        fun inspect(context: Context, song: Song): AudioDetails {
            val extractor = MediaExtractor()
            var result = AudioDetails()
            try {
                extractor.setDataSource(context, Uri.parse(song.uri), null)
                val f = (0 until extractor.trackCount).map(extractor::getTrackFormat).firstOrNull {
                    it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                if (f != null) {
                    fun number(key: String) = if (f.containsKey(key)) runCatching { f.getInteger(key) }.getOrDefault(0) else 0
                    var bits = number("bits-per-sample").takeIf { it > 0 } ?: pcmBits(number(MediaFormat.KEY_PCM_ENCODING))
                    f.getByteBuffer("csd-0")?.duplicate()?.let { buffer ->
                        val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                        if (bits == 0 && f.getString(MediaFormat.KEY_MIME) == "audio/flac") bits = flacBits(bytes)
                    }
                    result = AudioDetails(codecFor(f.getString(MediaFormat.KEY_MIME)), number(MediaFormat.KEY_SAMPLE_RATE), bits, number(MediaFormat.KEY_BIT_RATE))
                }
            } catch (_: Exception) { } finally { extractor.release() }
            if (result.bitrate <= 0) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(song.uri))
                    result = result.copy(bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0)
                } catch (_: Exception) { } finally { retriever.release() }
            }
            return result
        }
    }
}
