// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class NeteaseListenRecord(
    val id: String,
    val userId: Long,
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val artistId: Long,
    val artwork: String,
    val durationSec: Int,
    val playedSec: Int,
    val timestampMs: Long,
    val synced: Boolean,
)

/**
 * Persists NetEase tracks listened inside Aura Player so listening reports immediately
 * combine official NetEase client history with Aura Player playback without waiting for
 * server aggregation delays.
 */
class NeteaseListenStore(private val storageFile: File) {
    constructor(context: Context) : this(File(context.filesDir, "netease-listen-log.json"))

    companion object {
        private const val RETENTION_MS = 400L * 24L * 60L * 60L * 1000L
        private const val MAX_RECORDS = 10_000
    }

    private val lock = Any()
    private var loaded = false
    private val entries = mutableListOf<NeteaseListenRecord>()

    var revision by mutableIntStateOf(0)
        private set

    fun record(
        userId: Long,
        songId: Long,
        title: String,
        artist: String,
        album: String,
        albumId: Long,
        artistId: Long,
        artwork: String,
        durationSec: Int,
        playedSec: Int,
        timestampMs: Long = System.currentTimeMillis(),
        synced: Boolean = false,
    ): String {
        val id = UUID.randomUUID().toString()
        if (songId <= 0L || playedSec <= 0) return id
        synchronized(lock) {
            ensureLoadedLocked()
            entries += NeteaseListenRecord(
                id = id,
                userId = userId,
                songId = songId,
                title = title,
                artist = artist,
                album = album,
                albumId = albumId,
                artistId = artistId,
                artwork = artwork,
                durationSec = durationSec.coerceAtLeast(playedSec),
                playedSec = playedSec,
                timestampMs = timestampMs,
                synced = synced,
            )
            pruneLocked(timestampMs)
            persistLocked()
        }
        revision++
        return id
    }

    fun markSynced(recordId: String) {
        if (recordId.isBlank()) return
        var changed = false
        synchronized(lock) {
            ensureLoadedLocked()
            val index = entries.indexOfLast { it.id == recordId }
            if (index >= 0 && !entries[index].synced) {
                entries[index] = entries[index].copy(synced = true)
                persistLocked()
                changed = true
            }
        }
        if (changed) revision++
    }

    fun recordsInRange(userId: Long, startMs: Long, endMs: Long): List<NeteaseListenRecord> = synchronized(lock) {
        ensureLoadedLocked()
        entries.filter { entry ->
            (entry.userId == userId || entry.userId == 0L) &&
                entry.timestampMs in startMs..endMs
        }
    }

    fun allForUser(userId: Long): List<NeteaseListenRecord> = synchronized(lock) {
        ensureLoadedLocked()
        entries.filter { it.userId == userId || it.userId == 0L }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (!storageFile.isFile) return
        runCatching {
            val array = JSONArray(storageFile.readText(Charsets.UTF_8))
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val songId = o.optLong("songId", 0L)
                val playedSec = o.optInt("playedSec", 0)
                if (songId <= 0L || playedSec <= 0) continue
                entries += NeteaseListenRecord(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    userId = o.optLong("userId", 0L),
                    songId = songId,
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    albumId = o.optLong("albumId", 0L),
                    artistId = o.optLong("artistId", 0L),
                    artwork = o.optString("artwork"),
                    durationSec = o.optInt("durationSec", playedSec).coerceAtLeast(playedSec),
                    playedSec = playedSec,
                    timestampMs = o.optLong("timestampMs", 0L),
                    synced = o.optBoolean("synced", false),
                )
            }
        }
    }

    private fun pruneLocked(nowMs: Long) {
        val cutoff = nowMs - RETENTION_MS
        entries.removeAll { it.timestampMs in 1 until cutoff }
        if (entries.size > MAX_RECORDS) {
            entries.subList(0, entries.size - MAX_RECORDS).clear()
        }
    }

    private fun persistLocked() {
        runCatching {
            storageFile.parentFile?.mkdirs()
            val array = JSONArray()
            for (item in entries) {
                array.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("userId", item.userId)
                        .put("songId", item.songId)
                        .put("title", item.title)
                        .put("artist", item.artist)
                        .put("album", item.album)
                        .put("albumId", item.albumId)
                        .put("artistId", item.artistId)
                        .put("artwork", item.artwork)
                        .put("durationSec", item.durationSec)
                        .put("playedSec", item.playedSec)
                        .put("timestampMs", item.timestampMs)
                        .put("synced", item.synced),
                )
            }
            val tmp = File(storageFile.parentFile, "${storageFile.name}.tmp")
            tmp.writeText(array.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(storageFile)) {
                storageFile.writeText(array.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }
}
