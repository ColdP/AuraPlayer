package btm.m.auraplayer.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BlockedSong(
    val uri: String,
    val id: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: Long = 0L,
    val artistId: Long = 0L,
    val duration: Long = 0L,
    val artwork: String = "",
    val relativePath: String = "",
    val volume: String = "external_primary",
    val modified: Long = 0L,
    val fileName: String = "",
    val blockedAt: Long = 0L,
) {
    fun toSong(): Song = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        artistId = artistId,
        duration = duration,
        uri = uri,
        artwork = artwork,
        relativePath = relativePath,
        volume = volume,
        modified = modified,
        fileName = fileName,
    )
}

class BlockedSongsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("aura.blocked_songs", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<BlockedSong> = decodeItems(prefs.getString("items", null))
        .sortedByDescending { it.blockedAt }

    @Synchronized
    fun blockedUris(): Set<String> = decodeItems(prefs.getString("items", null))
        .mapTo(hashSetOf()) { it.uri }

    @Synchronized
    fun add(song: Song, resolvedFileName: String = song.fileName): List<BlockedSong> {
        if (song.cloud || song.uri.isBlank()) return load()
        val existing = decodeItems(prefs.getString("items", null)).associateByTo(linkedMapOf()) { it.uri }
        val fileName = resolvedFileName.ifBlank { existing[song.uri]?.fileName.orEmpty() }
        existing[song.uri] = BlockedSong(
            uri = song.uri,
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            albumId = song.albumId,
            artistId = song.artistId,
            duration = song.duration,
            artwork = song.artwork,
            relativePath = song.relativePath,
            volume = song.volume,
            modified = song.modified,
            fileName = fileName,
            blockedAt = System.currentTimeMillis(),
        )
        val sorted = existing.values.sortedByDescending { it.blockedAt }
        saveItems(sorted)
        return sorted
    }

    @Synchronized
    fun updateFileName(uri: String, fileName: String): List<BlockedSong> {
        if (uri.isBlank() || fileName.isBlank()) return load()
        val items = decodeItems(prefs.getString("items", null)).toMutableList()
        val idx = items.indexOfFirst { it.uri == uri }
        if (idx >= 0 && items[idx].fileName != fileName) {
            items[idx] = items[idx].copy(fileName = fileName)
            saveItems(items)
        }
        return items.sortedByDescending { it.blockedAt }
    }

    @Synchronized
    fun remove(uri: String): List<BlockedSong> {
        if (uri.isBlank()) return load()
        val updated = decodeItems(prefs.getString("items", null))
            .filterNot { it.uri == uri }
            .sortedByDescending { it.blockedAt }
        saveItems(updated)
        return updated
    }

    fun resolveFileName(song: Song): String {
        if (song.fileName.isNotBlank()) return song.fileName
        return resolveFileNameFromUri(song.uri, song.title)
    }

    fun resolveFileNameFromUri(rawUri: String, fallbackTitle: String = ""): String {
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return fallbackTitle
        if (uri.scheme == "file") {
            return uri.path?.let(::File)?.name.orEmpty().ifBlank { fallbackTitle }
        }
        var displayName = ""
        var dataPath = ""
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, MediaStore.Audio.Media.DATA)
        val cursor = runCatching {
            appContext.contentResolver.query(uri, projection, null, null, null)
        }.getOrNull() ?: runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        }.getOrNull()
        cursor?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) displayName = c.getString(nameIdx).orEmpty()
                val dataIdx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                if (dataIdx >= 0) dataPath = c.getString(dataIdx).orEmpty()
            }
        }
        return displayName
            .ifBlank { dataPath.substringAfterLast('/', "") }
            .ifBlank { uri.lastPathSegment?.substringAfterLast('/').orEmpty() }
            .ifBlank { fallbackTitle }
    }

    private fun decodeItems(raw: String?): List<BlockedSong> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val obj = array.getJSONObject(index)
                BlockedSong(
                    uri = obj.getString("uri"),
                    id = obj.optLong("id", 0L),
                    title = obj.optString("title"),
                    artist = obj.optString("artist"),
                    album = obj.optString("album"),
                    albumId = obj.optLong("albumId", 0L),
                    artistId = obj.optLong("artistId", 0L),
                    duration = obj.optLong("duration", 0L),
                    artwork = obj.optString("artwork"),
                    relativePath = obj.optString("relativePath"),
                    volume = obj.optString("volume", "external_primary"),
                    modified = obj.optLong("modified", 0L),
                    fileName = obj.optString("fileName"),
                    blockedAt = obj.optLong("blockedAt", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveItems(items: List<BlockedSong>) {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("uri", item.uri)
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("artist", item.artist)
                    .put("album", item.album)
                    .put("albumId", item.albumId)
                    .put("artistId", item.artistId)
                    .put("duration", item.duration)
                    .put("artwork", item.artwork)
                    .put("relativePath", item.relativePath)
                    .put("volume", item.volume)
                    .put("modified", item.modified)
                    .put("fileName", item.fileName)
                    .put("blockedAt", item.blockedAt),
            )
        }
        prefs.edit().putString("items", array.toString()).apply()
    }
}
