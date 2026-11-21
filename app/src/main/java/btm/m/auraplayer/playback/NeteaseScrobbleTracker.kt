// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import btm.m.auraplayer.AuraApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tracks actual listening time of NetEase tracks inside [PlaybackService], persists local
 * Aura Player listening records for the combined listening report, and submits official
 * NetEase scrobble telemetry (NCBL v3 / weblog) so plays count toward official history and
 * annual reports.
 */
internal class NeteaseScrobbleTracker(
    private val app: AuraApplication,
) {
    private data class ActiveTrack(
        val songId: Long,
        val sourceId: Long,
        val title: String,
        val artist: String,
        val album: String,
        val albumId: Long,
        val artistId: Long,
        val artwork: String,
        var durationMs: Long,
        val userId: Long,
        var accumulatedPlayMs: Long = 0L,
        var playingSinceElapsedMs: Long = 0L,
        var reported: Boolean = false,
    )

    companion object {
        private const val TAG = "NeteaseScrobble"

        internal fun isQualifiedListen(playedSec: Int, totalSec: Int, completed: Boolean): Boolean {
            if (playedSec >= 30) return true
            if (completed && playedSec >= 10) return true
            if (totalSec in 1..60 && playedSec >= (totalSec / 2).coerceAtLeast(10)) return true
            return false
        }
    }

    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active: ActiveTrack? = null

    fun syncInitial(player: Player) {
        active = extractTrack(player.currentMediaItem, player.duration, player.isPlaying)
    }

    fun onAutoTransition(player: Player) {
        flushCurrent(completed = true)
        active = extractTrack(player.currentMediaItem, player.duration, player.isPlaying)
    }

    fun onMediaItemTransition(player: Player) {
        val nextId = parseNeteaseSongId(player.currentMediaItem)
        val current = active
        if (current != null && current.songId == nextId && !current.reported) {
            updatePlayingState(player)
            return
        }
        flushCurrent(completed = false)
        active = extractTrack(player.currentMediaItem, player.duration, player.isPlaying)
    }

    fun onPlaybackStateChanged(player: Player) {
        if (player.playbackState == Player.STATE_ENDED) {
            flushCurrent(completed = true)
        } else {
            updatePlayingState(player)
        }
    }

    fun onTick(player: Player) {
        val current = active
        val itemId = parseNeteaseSongId(player.currentMediaItem)
        if (current == null && itemId != null) {
            active = extractTrack(player.currentMediaItem, player.duration, player.isPlaying)
            return
        }
        if (current != null && current.songId != itemId) {
            flushCurrent(completed = false)
            active = extractTrack(player.currentMediaItem, player.duration, player.isPlaying)
            return
        }
        updatePlayingState(player)
    }

    fun flushOnStop() {
        flushCurrent(completed = false)
    }

    private fun updatePlayingState(player: Player) {
        val session = active ?: return
        if (player.duration > 0L && session.durationMs <= 0L) {
            session.durationMs = player.duration
        }
        val now = SystemClock.elapsedRealtime()
        if (player.isPlaying) {
            if (session.playingSinceElapsedMs == 0L) {
                session.playingSinceElapsedMs = now
            }
        } else if (session.playingSinceElapsedMs > 0L) {
            session.accumulatedPlayMs += (now - session.playingSinceElapsedMs).coerceAtLeast(0L)
            session.playingSinceElapsedMs = 0L
        }
    }

    private fun flushCurrent(completed: Boolean) {
        val session = active ?: return
        if (session.reported) return
        val now = SystemClock.elapsedRealtime()
        if (session.playingSinceElapsedMs > 0L) {
            session.accumulatedPlayMs += (now - session.playingSinceElapsedMs).coerceAtLeast(0L)
            session.playingSinceElapsedMs = 0L
        }
        val rawPlayedSec = (session.accumulatedPlayMs / 1000L).toInt()
        val totalSec = (session.durationMs / 1000L).toInt().coerceAtLeast(0)
        val playedSec = if (totalSec > 0) rawPlayedSec.coerceAtMost(totalSec) else rawPlayedSec
        if (!isQualifiedListen(playedSec, totalSec, completed)) return

        session.reported = true
        val effectiveTotalSec = totalSec.coerceAtLeast(playedSec)
        val recordId = app.neteaseListenStore.record(
            userId = session.userId,
            songId = session.songId,
            title = session.title,
            artist = session.artist,
            album = session.album,
            albumId = session.albumId,
            artistId = session.artistId,
            artwork = session.artwork,
            durationSec = effectiveTotalSec,
            playedSec = playedSec,
            timestampMs = System.currentTimeMillis(),
            synced = false,
        )

        if (app.settings.value.cloudDisabled || !app.settings.value.neteaseScrobbleEnabled || app.cookie.isBlank()) {
            return
        }

        val stream = PlaybackStreams.records[session.songId]
        val bitrateKbps = stream?.bitrate?.let { if (it > 1000) it / 1000 else it }?.takeIf { it > 0 } ?: 320
        val level = stream?.level?.takeIf { it.isNotBlank() } ?: "exhigh"
        val effectiveSourceId = when {
            session.sourceId > 0L -> session.sourceId
            session.albumId > 0L -> session.albumId
            else -> session.songId
        }

        reportScope.launch {
            val ok = runCatching {
                app.api.scrobble(
                    songId = session.songId,
                    sourceId = effectiveSourceId,
                    playedSec = playedSec,
                    totalSec = effectiveTotalSec,
                    title = session.title,
                    artist = session.artist,
                    bitrateKbps = bitrateKbps,
                    level = level,
                    completed = completed,
                )
            }.onFailure { err ->
                Log.w(TAG, "NetEase scrobble failed for song ${session.songId}", err)
            }.getOrDefault(false)
            if (ok) {
                app.neteaseListenStore.markSynced(recordId)
            }
        }
    }

    private fun extractTrack(item: MediaItem?, playerDurationMs: Long, isPlaying: Boolean): ActiveTrack? {
        val songId = parseNeteaseSongId(item) ?: return null
        val md = item?.mediaMetadata ?: return null
        val extras = md.extras
        val durationMs = (md.durationMs ?: 0L).takeIf { it > 0L } ?: playerDurationMs.coerceAtLeast(0L)
        val userId = app.activeStoredAccount()?.userId ?: 0L
        return ActiveTrack(
            songId = songId,
            sourceId = extras?.getLong("sourceId") ?: 0L,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            albumId = extras?.getLong("albumId") ?: 0L,
            artistId = extras?.getLong("artistId") ?: 0L,
            artwork = md.artworkUri?.toString().orEmpty(),
            durationMs = durationMs,
            userId = userId,
            playingSinceElapsedMs = if (isPlaying) SystemClock.elapsedRealtime() else 0L,
        )
    }

    private fun parseNeteaseSongId(item: MediaItem?): Long? {
        val uri = item?.localConfiguration?.uri ?: return null
        if (uri.scheme != "aura" || uri.host != "netease") return null
        return uri.lastPathSegment?.toLongOrNull()?.takeIf { it > 0L }
    }
}
