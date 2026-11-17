// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.widget

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import btm.m.auraplayer.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class WidgetTrackInfo(
    val queueIndex: Int,
    val title: String,
    val artist: String,
    val artworkUri: String,
)

internal data class WidgetPlaybackSnapshot(
    val current: WidgetTrackInfo?,
    val isPlaying: Boolean,
    val upcoming: List<WidgetTrackInfo>,
)

internal object WidgetPlaybackState {
    suspend fun readSnapshot(context: Context): WidgetPlaybackSnapshot {
        val liveService = PlaybackService.activeInstance
        if (liveService != null) {
            val live = withContext(Dispatchers.Main.immediate) {
                PlaybackService.activeInstance?.currentWidgetSnapshot()
            }
            if (live != null) return live
        }
        return readPersistedSnapshot(context)
    }

    internal fun fromLivePlayer(player: Player, playRequested: Boolean): WidgetPlaybackSnapshot {
        val count = player.mediaItemCount
        val currentIdx = player.currentMediaItemIndex
        val currentItem = player.currentMediaItem
        if (count <= 0 || currentItem == null || currentIdx !in 0 until count) {
            return WidgetPlaybackSnapshot(current = null, isPlaying = false, upcoming = emptyList())
        }
        val current = trackFromMediaItem(currentIdx, currentItem)
        val upcomingIndices = resolveTimelineUpcomingIndices(player, limit = 2)
        val upcoming = upcomingIndices.mapNotNull { idx ->
            if (idx in 0 until count) trackFromMediaItem(idx, player.getMediaItemAt(idx)) else null
        }
        val playing = playRequested && player.playbackState != Player.STATE_ENDED
        return WidgetPlaybackSnapshot(current = current, isPlaying = playing, upcoming = upcoming)
    }

    internal fun readPersistedSnapshot(context: Context): WidgetPlaybackSnapshot {
        val prefs = context.getSharedPreferences("aura.playback.queue", Context.MODE_PRIVATE)
        val count = prefs.getInt("count", 0)
        if (count <= 0) {
            return WidgetPlaybackSnapshot(current = null, isPlaying = false, upcoming = emptyList())
        }
        val index = prefs.getInt("index", 0).coerceIn(0, count - 1)
        val repeatMode = prefs.getInt("repeatMode", Player.REPEAT_MODE_ALL)
            .coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL)
        val current = readPersistedTrack(prefs, index) ?: return WidgetPlaybackSnapshot(
            current = null,
            isPlaying = false,
            upcoming = emptyList(),
        )
        val upcoming = resolveSequentialUpcomingIndices(index, count, repeatMode, limit = 2)
            .mapNotNull { idx -> readPersistedTrack(prefs, idx) }
        return WidgetPlaybackSnapshot(
            current = current,
            isPlaying = false,
            upcoming = upcoming,
        )
    }

    private fun readPersistedTrack(prefs: android.content.SharedPreferences, index: Int): WidgetTrackInfo? {
        val raw = prefs.getString("item.$index", null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            WidgetTrackInfo(
                queueIndex = index,
                title = obj.optString("title").ifBlank { "" },
                artist = obj.optString("artist").ifBlank { "" },
                artworkUri = obj.optString("artwork").ifBlank { "" },
            )
        }.getOrNull()
    }

    private fun trackFromMediaItem(index: Int, item: MediaItem): WidgetTrackInfo {
        val md = item.mediaMetadata
        return WidgetTrackInfo(
            queueIndex = index,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            artworkUri = md.artworkUri?.toString().orEmpty(),
        )
    }
}
