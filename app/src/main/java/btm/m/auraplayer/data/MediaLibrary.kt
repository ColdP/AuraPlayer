package btm.m.auraplayer.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media as Audio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.Collator
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

data class PartialScanResult(
    val volume: String,
    val relativePrefix: String,
    val folderSongs: List<Song>,
)

class MediaLibrary(private val context: Context) {
    private val audioExtensions = setOf("mp3", "flac", "m4a", "mp4", "ogg", "opus", "wav", "aac", "wma", "aif", "aiff")

    suspend fun scan(settings: Appearance): List<Song> = withContext(Dispatchers.IO) {
        val songs = queryMediaStore(settings = settings)
        val collator = Collator.getInstance()
        songs.sortedWith { first, second -> collator.compare(first.title, second.title) }
    }

    /**
     * Scans a SAF-picked folder: triggers MediaScanner indexing for new audio files in that tree,
     * re-queries MediaStore for that folder prefix, and falls back to reading SAF audio files directly
     * if MediaStore has not indexed them yet.
     */
    suspend fun scanTree(treeUri: Uri, settings: Appearance): PartialScanResult = withContext(Dispatchers.IO) {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        val isExternalStorageDoc = treeUri.authority == "com.android.externalstorage.documents" && treeId.contains(':')
        val volume = if (isExternalStorageDoc) {
            treeId.substringBefore(':').let { if (it == "primary") MediaStore.VOLUME_EXTERNAL_PRIMARY else it.lowercase() }
        } else {
            ""
        }
        val relativePrefix = if (isExternalStorageDoc) {
            LibraryFilter.normalize(treeId.substringAfter(':', ""))
        } else {
            ""
        }

        val filenameMatcher = settings.filenameBlockRules.compile()
        val discoveredDocs = if (treeId.isNotBlank()) {
            collectSafAudioDocs(treeUri, treeId, relativePrefix).filterNot { filenameMatcher.blocks(it.displayName) }
        } else emptyList()
        if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
            val scanPaths = discoveredDocs.mapNotNull { doc ->
                doc.externalRelativePath.takeIf { it.isNotBlank() }?.let { rel ->
                    File(Environment.getExternalStorageDirectory(), rel).takeIf { it.isFile }?.absolutePath
                }
            }.distinct()
            if (scanPaths.isNotEmpty()) {
                triggerMediaScanner(scanPaths)
            }
        }

        val mediaStoreMatches = if (volume.isNotEmpty()) {
            queryMediaStore(
                settings = settings,
                filenameMatcher = filenameMatcher,
                targetVolume = volume,
                relativePrefix = relativePrefix,
            )
        } else {
            emptyList()
        }

        val indexedKeys = mediaStoreMatches.mapTo(hashSetOf()) { "${LibraryFilter.normalize(it.relativePath)}|${it.title}|${it.duration}" }
        val indexedPaths = mediaStoreMatches.mapTo(hashSetOf()) { "${LibraryFilter.normalize(it.relativePath)}${it.title}" }
        val safExtras = mutableListOf<Song>()
        val nowSec = System.currentTimeMillis() / 1000L
        for (doc in discoveredDocs) {
            currentCoroutineContext().ensureActive()
            val normFolder = LibraryFilter.normalize(doc.folderRelativePath)
            val fallbackTitle = doc.displayName.substringBeforeLast('.').ifBlank { doc.displayName }
            if (isExternalStorageDoc && !LibraryFilter.accepts(normFolder, 60_000L, settings.whitelist, settings.blacklist, false, 0, volume)) {
                continue
            }
            val alreadyInMediaStore = mediaStoreMatches.any { ms ->
                LibraryFilter.normalize(ms.relativePath) == normFolder &&
                    (ms.title.equals(fallbackTitle, ignoreCase = true) || ms.uri == doc.uri.toString())
            }
            if (alreadyInMediaStore) continue

            val extracted = extractSafAudioSong(doc, normFolder, volume.ifBlank { MediaStore.VOLUME_EXTERNAL_PRIMARY }, nowSec)
                ?: continue
            if (!LibraryFilter.acceptsDuration(extracted.duration, settings.durationFilter, settings.minimumSeconds)) {
                continue
            }
            val sig = "$normFolder|${extracted.title}|${extracted.duration}"
            if (sig !in indexedKeys && "$normFolder${extracted.title}" !in indexedPaths) {
                safExtras += extracted
            }
        }

        val combined = (mediaStoreMatches + safExtras)
        val collator = Collator.getInstance()
        PartialScanResult(
            volume = volume,
            relativePrefix = relativePrefix,
            folderSongs = combined.sortedWith { first, second -> collator.compare(first.title, second.title) },
        )
    }

    private suspend fun queryMediaStore(
        settings: Appearance,
        filenameMatcher: FilenameBlockMatcher = settings.filenameBlockRules.compile(),
        targetVolume: String? = null,
        relativePrefix: String? = null,
    ): List<Song> {
        val columns = arrayOf(
            Audio._ID, Audio.TITLE, Audio.ARTIST, Audio.ALBUM, Audio.ALBUM_ID, Audio.ARTIST_ID,
            Audio.DURATION, Audio.RELATIVE_PATH, Audio.DATE_ADDED, Audio.TRACK, Audio.VOLUME_NAME,
            Audio.YEAR, Audio.DATE_MODIFIED, Audio.COMPOSER, Audio.ALBUM_ARTIST, Audio.DISPLAY_NAME,
        )
        val songs = mutableListOf<Song>()
        val collection = Audio.getContentUri(MediaStore.VOLUME_EXTERNAL)
        context.contentResolver.query(
            collection,
            columns,
            "${Audio.IS_MUSIC} != 0 AND ${Audio.IS_PENDING} = 0 AND ${Audio.IS_TRASHED} = 0",
            null,
            null,
        )?.use { cursor ->
            val ix = columns.map(cursor::getColumnIndexOrThrow)
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val volume = cursor.getString(ix[10]) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                if (targetVolume != null && !volume.equals(targetVolume, ignoreCase = true)) continue
                val path = cursor.getString(ix[7]).orEmpty()
                val normalizedPath = LibraryFilter.normalize(path)
                if (!relativePrefix.isNullOrEmpty() && !normalizedPath.startsWith(relativePrefix, ignoreCase = true)) {
                    continue
                }
                val duration = cursor.getLong(ix[6])
                if (!LibraryFilter.accepts(path, duration, settings.whitelist, settings.blacklist, settings.durationFilter, settings.minimumSeconds, volume)) {
                    continue
                }
                if (filenameMatcher.blocks(cursor.getString(ix[15]).orEmpty())) continue
                val id = cursor.getLong(ix[0])
                val albumId = cursor.getLong(ix[4])
                val modified = cursor.getLong(ix[12])
                val songCollection = Audio.getContentUri(volume)
                val songUri = ContentUris.withAppendedId(songCollection, id).toString()
                val albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/$volume/audio/albumart"), albumId).toString()
                songs += Song(
                    id = id,
                    title = cursor.getString(ix[1]).orEmpty(),
                    artist = cursor.getString(ix[2]).orEmpty(),
                    album = cursor.getString(ix[3]).orEmpty(),
                    albumId = albumId,
                    artistId = cursor.getLong(ix[5]),
                    duration = duration,
                    uri = songUri,
                    artwork = LocalAudioArtwork.uriFor(songUri = songUri, albumArtUri = albumArtUri, version = modified),
                    relativePath = normalizedPath,
                    added = cursor.getLong(ix[8]),
                    track = cursor.getInt(ix[9]),
                    volume = volume,
                    year = cursor.getInt(ix[11]),
                    modified = modified,
                    composer = cursor.getString(ix[13]).orEmpty(),
                    albumArtist = cursor.getString(ix[14]).orEmpty(),
                )
            }
        }
        return songs
    }

    private data class SafAudioDoc(
        val uri: Uri,
        val displayName: String,
        val folderRelativePath: String,
        val externalRelativePath: String,
    )

    private suspend fun collectSafAudioDocs(
        treeUri: Uri,
        rootDocId: String,
        rootRelPath: String,
    ): List<SafAudioDoc> {
        val docs = mutableListOf<SafAudioDoc>()
        val queue = ArrayDeque<Pair<String, String>>()
        val visited = hashSetOf<String>()
        queue.addLast(rootDocId to rootRelPath)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        while (queue.isNotEmpty() && docs.size < 4000) {
            currentCoroutineContext().ensureActive()
            val (parentDocId, parentRel) = queue.removeFirst()
            if (!visited.add(parentDocId)) continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            runCatching {
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext() && docs.size < 4000) {
                        val childId = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else ""
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                        val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                        if (childId.isBlank()) continue
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            val subRel = if (name.isNotBlank()) "${parentRel}${name}/" else parentRel
                            queue.addLast(childId to subRel)
                        } else if (isAudioMimeOrExt(mime, name)) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            val relFilePath = if (childId.contains(':')) {
                                childId.substringAfter(':').trimStart('/')
                            } else {
                                "${parentRel}${name}".trimStart('/')
                            }
                            docs += SafAudioDoc(
                                uri = docUri,
                                displayName = name.ifBlank { childId.substringAfterLast('/') },
                                folderRelativePath = parentRel,
                                externalRelativePath = relFilePath,
                            )
                        }
                    }
                }
            }
        }
        return docs
    }

    private fun isAudioMimeOrExt(mime: String, name: String): Boolean {
        if (mime.startsWith("audio/", ignoreCase = true) || mime.equals("application/ogg", ignoreCase = true)) {
            return true
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in audioExtensions
    }

    private fun extractSafAudioSong(
        doc: SafAudioDoc,
        normalizedFolder: String,
        volume: String,
        nowSec: Long,
    ): Song? {
        val retriever = MediaMetadataRetriever()
        var title = ""
        var artist = ""
        var album = ""
        var duration = 0L
        var track = 0
        var year = 0
        val ok = runCatching {
            retriever.setDataSource(context, doc.uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim()
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim()
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')
                ?.toIntOrNull() ?: 0
            year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull() ?: 0
            true
        }.getOrDefault(false)
        runCatching { retriever.release() }
        if (!ok) return null
        val songUri = doc.uri.toString()
        val fallbackTitle = doc.displayName.substringBeforeLast('.').ifBlank { doc.displayName }
        return Song(
            id = stableManualSongId(songUri),
            title = title.ifBlank { fallbackTitle },
            artist = artist,
            album = album,
            duration = duration,
            uri = songUri,
            artwork = LocalAudioArtwork.uriFor(songUri = songUri, version = nowSec),
            relativePath = normalizedFolder,
            added = nowSec,
            track = track,
            volume = volume,
            year = year,
            modified = nowSec,
        )
    }

    private suspend fun triggerMediaScanner(paths: List<String>) {
        if (paths.isEmpty()) return
        withTimeoutOrNull(4_500L) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val remaining = AtomicInteger(paths.size)
                MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                    if (remaining.decrementAndGet() <= 0 && continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }
        }
    }
}
