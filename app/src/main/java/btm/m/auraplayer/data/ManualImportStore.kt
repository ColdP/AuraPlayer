// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

const val MAX_MANUAL_IMPORT_COUNT = 20

data class ManualAudioDraft(
    val uri: String,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val artwork: String,
    val track: Int = 0,
    val year: Int = 0,
)

/**
 * Merges newly picked audio files into the staged import list, deduplicating by URI
 * and enforcing [maxCount]. Returns the merged list and the number of skipped items.
 */
fun mergeManualAudioSelection(
    existing: List<ManualAudioDraft>,
    incoming: List<ManualAudioDraft>,
    maxCount: Int = MAX_MANUAL_IMPORT_COUNT,
): Pair<List<ManualAudioDraft>, Int> {
    if (incoming.isEmpty()) return existing.take(maxCount) to 0
    val seen = existing.mapTo(linkedSetOf()) { it.uri }
    val result = existing.toMutableList()
    var skipped = 0
    for (item in incoming) {
        if (!seen.add(item.uri)) continue
        if (result.size >= maxCount) {
            skipped++
        } else {
            result += item
        }
    }
    return result to skipped
}

fun removeManualAudioSelection(
    existing: List<ManualAudioDraft>,
    uri: String,
): List<ManualAudioDraft> = existing.filterNot { it.uri == uri }

fun stableManualSongId(uri: String): Long {
    val hash = (uri.hashCode().toLong() and 0x7FFFFFFFL).coerceAtLeast(1L)
    return -hash
}

class ManualImportStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("aura.manual_imports", Context.MODE_PRIVATE)

    fun count(): Int = decodeEntries(prefs.getString("items", null)).size

    suspend fun inspectPickedUris(uris: List<Uri>): List<ManualAudioDraft> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        uris.distinct().mapNotNull { uri ->
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            extractDraft(uri, now)
        }
    }

    fun addAll(drafts: List<ManualAudioDraft>): Int {
        if (drafts.isEmpty()) return 0
        val existing = decodeEntries(prefs.getString("items", null)).associateByTo(linkedMapOf()) { it.uri }
        val now = System.currentTimeMillis()
        for (draft in drafts) {
            existing[draft.uri] = StoredManualSong(
                uri = draft.uri,
                fileName = draft.fileName,
                title = draft.title,
                artist = draft.artist,
                album = draft.album,
                duration = draft.duration,
                track = draft.track,
                year = draft.year,
                added = now / 1000L,
                modified = now / 1000L,
            )
        }
        saveEntries(existing.values.toList())
        return drafts.distinctBy { it.uri }.size
    }

    fun load(): List<Song> {
        val entries = decodeEntries(prefs.getString("items", null))
        if (entries.isEmpty()) return emptyList()
        return entries.mapNotNull { entry ->
            if (!isReadableUri(entry.uri)) return@mapNotNull null
            entry.toSong()
        }
    }

    private fun extractDraft(uri: Uri, nowMs: Long): ManualAudioDraft? {
        val fileName = resolveFileName(uri)
        val retriever = MediaMetadataRetriever()
        var title = ""
        var artist = ""
        var album = ""
        var duration = 0L
        var track = 0
        var year = 0
        val opened = runCatching {
            if (uri.scheme == "file") {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(appContext, uri)
            }
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim()
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim()
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')
                ?.toIntOrNull() ?: 0
            year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.take(4)
                ?.toIntOrNull() ?: 0
            true
        }.getOrDefault(false)
        runCatching { retriever.release() }
        if (!opened && !isReadableUri(uri.toString())) return null
        val fallbackTitle = fileName.substringBeforeLast('.').ifBlank { fileName }
        return ManualAudioDraft(
            uri = uri.toString(),
            fileName = fileName,
            title = title.ifBlank { fallbackTitle },
            artist = artist,
            album = album,
            duration = duration,
            artwork = LocalAudioArtwork.uriFor(songUri = uri.toString(), version = nowMs),
            track = track,
            year = year,
        )
    }

    private fun resolveFileName(uri: Uri): String {
        if (uri.scheme == "file") {
            return uri.path?.let(::File)?.name.orEmpty().ifBlank { "audio" }
        }
        val queried = runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull().orEmpty()
        return queried.ifBlank { uri.lastPathSegment?.substringAfterLast('/').orEmpty() }.ifBlank { "audio" }
    }

    private fun isReadableUri(raw: String): Boolean {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        if (uri.scheme == "file") {
            return uri.path?.let(::File)?.let { it.isFile && it.canRead() } == true
        }
        return runCatching {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private data class StoredManualSong(
        val uri: String,
        val fileName: String,
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val track: Int,
        val year: Int,
        val added: Long,
        val modified: Long,
    ) {
        fun toSong(): Song = Song(
            id = stableManualSongId(uri),
            title = title.ifBlank { fileName.substringBeforeLast('.').ifBlank { fileName } },
            artist = artist,
            album = album,
            duration = duration,
            uri = uri,
            artwork = LocalAudioArtwork.uriFor(songUri = uri, version = modified),
            relativePath = "",
            added = added,
            track = track,
            modified = modified,
            fileName = fileName,
        )
    }

    private fun decodeEntries(raw: String?): List<StoredManualSong> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val obj = array.getJSONObject(index)
                StoredManualSong(
                    uri = obj.getString("uri"),
                    fileName = obj.optString("fileName"),
                    title = obj.optString("title"),
                    artist = obj.optString("artist"),
                    album = obj.optString("album"),
                    duration = obj.optLong("duration", 0L),
                    track = obj.optInt("track", 0),
                    year = obj.optInt("year", 0),
                    added = obj.optLong("added", 0L),
                    modified = obj.optLong("modified", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveEntries(entries: List<StoredManualSong>) {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put("uri", entry.uri)
                    .put("fileName", entry.fileName)
                    .put("title", entry.title)
                    .put("artist", entry.artist)
                    .put("album", entry.album)
                    .put("duration", entry.duration)
                    .put("track", entry.track)
                    .put("year", entry.year)
                    .put("added", entry.added)
                    .put("modified", entry.modified),
            )
        }
        prefs.edit().putString("items", array.toString()).apply()
    }
}
