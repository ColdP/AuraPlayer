package btm.m.auraplayer.playback

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.media3.common.Format
import btm.m.auraplayer.data.Song
import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** File and audio stream properties for a local music track. */
data class LocalAudioFileInfo(
    val filePath: String = "",
    val fileName: String = "",
    val fileSizeBytes: Long = 0L,
    val lastModifiedMs: Long = 0L,
    val format: String = "",
    val bits: Int = 0,
    val bitrate: Int = 0,
    val sampleRate: Int = 0,
    val codec: String = "",
) {
    companion object {
        fun inspect(context: Context, song: Song, playingFormat: Format? = null): LocalAudioFileInfo {
            val uri = Uri.parse(song.uri)
            val fileFromUri = if (uri.scheme == "file") uri.path?.let(::File) else null
            var displayName = ""
            var dataPath = ""
            var relativePath = ""
            var queriedSize = 0L
            var queriedModifiedSec = 0L
            var queriedMime = ""

            if (uri.scheme != "file") {
                val fullProjection = arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                    MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                )
                val cursor = runCatching {
                    context.contentResolver.query(uri, fullProjection, null, null, null)
                }.getOrNull() ?: runCatching {
                    context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )
                }.getOrNull()
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        fun str(col: String): String {
                            val idx = c.getColumnIndex(col)
                            return if (idx >= 0) c.getString(idx).orEmpty() else ""
                        }
                        fun lng(col: String): Long {
                            val idx = c.getColumnIndex(col)
                            return if (idx >= 0) c.getLong(idx) else 0L
                        }
                        displayName = str(OpenableColumns.DISPLAY_NAME)
                        queriedSize = lng(OpenableColumns.SIZE)
                        dataPath = str(MediaStore.Audio.Media.DATA)
                        queriedModifiedSec = lng(MediaStore.Audio.Media.DATE_MODIFIED)
                        queriedMime = str(MediaStore.Audio.Media.MIME_TYPE)
                        relativePath = str(MediaStore.Audio.Media.RELATIVE_PATH)
                    }
                }
            }

            val directFile = (fileFromUri ?: dataPath.takeIf { it.isNotBlank() }?.let(::File))
                ?.takeIf { it.isFile && it.canRead() }
            val resolvedName = displayName
                .ifBlank { fileFromUri?.name.orEmpty() }
                .ifBlank { dataPath.substringAfterLast('/', "") }
                .ifBlank { song.title }
            val resolvedPath = fileFromUri?.absolutePath
                ?: dataPath.ifBlank {
                    val rel = relativePath.ifBlank { song.relativePath }
                    if (rel.isNotBlank() && resolvedName.isNotBlank()) {
                        val normalized = if (rel.endsWith('/')) rel else "$rel/"
                        "$normalized$resolvedName"
                    } else {
                        song.uri
                    }
                }
            val resolvedSize = queriedSize.takeIf { it > 0L }
                ?: directFile?.length()?.takeIf { it > 0L }
                ?: runCatching {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
                }.getOrDefault(0L).coerceAtLeast(0L)
            val resolvedModifiedMs = queriedModifiedSec.takeIf { it > 0L }?.times(1000L)
                ?: song.modified.takeIf { it > 0L }?.times(1000L)
                ?: directFile?.lastModified()?.takeIf { it > 0L }
                ?: 0L

            var details = AudioDetails.inspect(context, song).withFormat(playingFormat)
            var retrieverMime = ""
            if (details.bits <= 0 || details.sampleRate <= 0 || details.bitrate <= 0 || details.codec.isBlank()) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val bits = if (details.bits > 0) details.bits
                    else retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                        ?.toIntOrNull()?.takeIf { it > 0 } ?: 0
                    val sampleRate = if (details.sampleRate > 0) details.sampleRate
                    else retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                        ?.toIntOrNull()?.takeIf { it > 0 } ?: 0
                    val bitrate = if (details.bitrate > 0) details.bitrate
                    else retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull()?.takeIf { it > 0 } ?: 0
                    retrieverMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
                    val codec = details.codec.ifBlank { AudioDetails.codecFor(retrieverMime).removePrefix("X-") }
                    details = details.copy(codec = codec, sampleRate = sampleRate, bits = bits, bitrate = bitrate)
                } catch (_: Exception) {
                } finally {
                    retriever.release()
                }
            }
            if (directFile != null && (details.bits <= 0 || details.sampleRate <= 0 || details.bitrate <= 0 || details.codec.isBlank())) {
                runCatching { AudioFileIO.read(directFile).audioHeader }.getOrNull()?.let { header ->
                    val bits = if (details.bits > 0) details.bits else header.bitsPerSample.takeIf { it > 0 } ?: 0
                    val sampleRate = if (details.sampleRate > 0) details.sampleRate else header.sampleRateAsNumber.takeIf { it > 0 } ?: 0
                    val bitrate = if (details.bitrate > 0) details.bitrate
                    else header.bitRateAsNumber.takeIf { it > 0L }?.times(1000L)?.toInt() ?: 0
                    val codec = details.codec.ifBlank { header.encodingType.orEmpty().trim() }
                    details = details.copy(codec = codec, sampleRate = sampleRate, bits = bits, bitrate = bitrate)
                }
            }

            val mime = queriedMime.ifBlank { retrieverMime }.ifBlank {
                runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
            }
            val resolvedFormat = formatFromExtensionOrMime(resolvedName, resolvedPath, mime, details.codec)

            return LocalAudioFileInfo(
                filePath = resolvedPath,
                fileName = resolvedName,
                fileSizeBytes = resolvedSize,
                lastModifiedMs = resolvedModifiedMs,
                format = resolvedFormat,
                bits = details.bits,
                bitrate = details.bitrate,
                sampleRate = details.sampleRate,
                codec = details.codec,
            )
        }

        internal fun formatFromExtensionOrMime(fileName: String, filePath: String, mime: String, codec: String): String {
            val ext = extensionOf(fileName).ifBlank { extensionOf(filePath) }
            if (ext.isNotBlank()) return ext.uppercase(Locale.ROOT)
            val normalizedMime = mime.lowercase(Locale.ROOT).substringBefore(';').trim()
            val fromMime = when (normalizedMime) {
                "audio/flac", "audio/x-flac" -> "FLAC"
                "audio/mpeg", "audio/mp3" -> "MP3"
                "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/mp4a-latm" -> "M4A"
                "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "WAV"
                "audio/ogg", "application/ogg" -> "OGG"
                "audio/opus" -> "OPUS"
                "audio/aac", "audio/x-aac" -> "AAC"
                "audio/aiff", "audio/x-aiff" -> "AIFF"
                "audio/x-ms-wma" -> "WMA"
                "audio/ape", "audio/x-ape" -> "APE"
                "audio/dsf", "audio/x-dsf" -> "DSF"
                "audio/dff", "audio/x-dff" -> "DFF"
                else -> normalizedMime.substringAfter('/', "").removePrefix("x-").uppercase(Locale.ROOT)
            }
            return fromMime.ifBlank { codec }
        }

        private fun extensionOf(nameOrPath: String): String {
            val leaf = nameOrPath.substringAfterLast('/', nameOrPath)
            if ('.' !in leaf) return ""
            val ext = leaf.substringAfterLast('.', "").trim()
            return if (ext.length in 2..6 && ext.all { it.isLetterOrDigit() }) ext else ""
        }

        internal fun formatTimestamp(ms: Long, locale: Locale = Locale.getDefault()): String {
            if (ms <= 0L) return "—"
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale).format(Date(ms))
        }

        internal fun formatSampleRate(sampleRate: Int, unit: String): String {
            if (sampleRate <= 0) return "—"
            return "${DecimalFormat("0.###").format(sampleRate / 1000.0)} $unit"
        }

        internal fun formatBitrate(bitrate: Int, unit: String): String {
            if (bitrate <= 0) return "—"
            return "${(bitrate / 1000.0).roundToInt()} $unit"
        }

        internal fun formatBitDepth(bits: Int, unit: String): String {
            if (bits <= 0) return "—"
            return "$bits-$unit"
        }
    }
}
