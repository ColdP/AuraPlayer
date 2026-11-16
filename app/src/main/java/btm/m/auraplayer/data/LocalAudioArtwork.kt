// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import btm.m.auraplayer.tags.AudioTagFiles
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Local audio artwork URI builder & parser.
 *
 * Points Aura Player's image loader to read full-resolution embedded cover art
 * directly from the audio file (falling back to MediaStore's album art URI)
 * instead of relying solely on MediaStore's downscaled album-art thumbnail cache,
 * and embeds a version token so tag edits bust Coil caches immediately.
 */
object LocalAudioArtwork {
    const val SCHEME = "aura-art"
    const val HOST = "local"
    private const val ARTWORK_BYTES_CACHE_BYTES = 24 * 1024 * 1024

    // Keeps extracted embedded album art bytes in memory across list thumbnails,
    // album pages, album info sheets, Now Playing covers, and Monet quantization
    // so switching screens never re-runs MediaMetadataRetriever for the same track.
    private val extractedBytesCache = LinkedHashMap<String, ByteArray>(64, 0.75f, true)
    private var extractedBytesTotal = 0

    internal fun cachedBytes(rawUri: String): ByteArray? =
        if (rawUri.isBlank()) null else synchronized(extractedBytesCache) { extractedBytesCache[rawUri] }

    internal fun cacheBytes(rawUri: String, bytes: ByteArray) {
        if (rawUri.isBlank() || bytes.isEmpty() || bytes.size > ARTWORK_BYTES_CACHE_BYTES / 3) return
        synchronized(extractedBytesCache) {
            extractedBytesCache.remove(rawUri)?.let { extractedBytesTotal -= it.size }
            extractedBytesCache[rawUri] = bytes
            extractedBytesTotal += bytes.size
            val iter = extractedBytesCache.entries.iterator()
            while (extractedBytesTotal > ARTWORK_BYTES_CACHE_BYTES && iter.hasNext()) {
                val eldest = iter.next()
                extractedBytesTotal -= eldest.value.size
                iter.remove()
            }
        }
    }

    internal fun sameAlbumArtUri(firstUri: String, secondUri: String): Boolean {
        val firstAlbum = parse(firstUri)?.albumArtUri?.takeIf { it.isNotBlank() } ?: return false
        val secondAlbum = parse(secondUri)?.albumArtUri?.takeIf { it.isNotBlank() } ?: return false
        return firstAlbum == secondAlbum
    }

    data class ParsedUri(
        val songUri: String,
        val albumArtUri: String,
        val version: Long,
    )

    fun uriFor(songUri: String, albumArtUri: String = "", version: Long = 0L): String {
        val encodedSong = encodeParam(songUri)
        val encodedAlbum = encodeParam(albumArtUri)
        return "$SCHEME://$HOST?uri=$encodedSong&albumArt=$encodedAlbum&v=$version"
    }

    fun bumpVersion(existingArtwork: String, songUri: String, version: Long): String {
        val parsed = parse(existingArtwork)
        val resolvedSongUri = parsed?.songUri?.takeIf { it.isNotBlank() } ?: songUri
        val fallbackAlbumArt = parsed?.albumArtUri
            ?: existingArtwork.takeIf { it.startsWith("content://", ignoreCase = true) }.orEmpty()
        return uriFor(songUri = resolvedSongUri, albumArtUri = fallbackAlbumArt, version = version)
    }

    fun parse(raw: String): ParsedUri? {
        if (!raw.startsWith("$SCHEME://", ignoreCase = true)) return null
        val query = raw.substringAfter('?', "")
        if (query.isEmpty()) return null
        var songUri = ""
        var albumArtUri = ""
        var version = 0L
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=')
            val value = decodeParam(pair.substringAfter('=', ""))
            when (key) {
                "uri" -> songUri = value
                "albumArt" -> albumArtUri = value
                "v" -> version = value.toLongOrNull() ?: 0L
            }
        }
        if (songUri.isBlank() && albumArtUri.isBlank()) return null
        return ParsedUri(songUri = songUri, albumArtUri = albumArtUri, version = version)
    }

    private fun encodeParam(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decodeParam(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}

/**
 * Coil fetcher that extracts full-resolution embedded album art from local audio files
 * before falling back to MediaStore's album-art content URI.
 */
internal class LocalAudioArtworkFetcher(
    private val context: Context,
    private val rawUri: String,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val parsed = LocalAudioArtwork.parse(rawUri) ?: return null
        val bytes = LocalAudioArtwork.cachedBytes(rawUri)
            ?: (readEmbeddedArt(parsed.songUri) ?: readAlbumArtFallback(parsed.albumArtUri))
                ?.also { LocalAudioArtwork.cacheBytes(rawUri, it) }
            ?: return null
        if (bytes.isEmpty()) return null
        val buffer = Buffer().write(bytes)
        return SourceFetchResult(
            source = ImageSource(source = buffer, fileSystem = options.fileSystem),
            mimeType = null,
            dataSource = DataSource.MEMORY,
        )
    }

    private fun readEmbeddedArt(songUriString: String): ByteArray? {
        if (songUriString.isBlank()) return null
        val uri = Uri.parse(songUriString)
        val retriever = MediaMetadataRetriever()
        val retrieved = runCatching {
            if (uri.scheme == "file") {
                uri.path?.let { retriever.setDataSource(it) }
            } else {
                retriever.setDataSource(context, uri)
            }
            retriever.embeddedPicture?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        runCatching { retriever.release() }
        if (retrieved != null) return retrieved

        // Fallback to jaudiotagger if a local path is directly readable (e.g. some exotic containers).
        val directFile = resolveReadableFile(uri)
        if (directFile != null) {
            val tagBytes = runCatching { AudioTagFiles.read(directFile).cover?.bytes }.getOrNull()
            if (tagBytes != null && tagBytes.isNotEmpty()) return tagBytes
        }
        return null
    }

    private fun resolveReadableFile(uri: Uri): File? {
        if (uri.scheme == "file") {
            return uri.path?.let(::File)?.takeIf { it.isFile && it.canRead() }
        }
        val path = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return path?.let(::File)?.takeIf { it.isFile && it.canRead() }
    }

    private fun readAlbumArtFallback(albumArtUriString: String): ByteArray? {
        if (albumArtUriString.isBlank()) return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(albumArtUriString))?.use { it.readBytes() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    class Factory(private val context: Context) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != LocalAudioArtwork.SCHEME) return null
            return LocalAudioArtworkFetcher(context.applicationContext, data.toString(), options)
        }
    }
}
