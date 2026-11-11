package btm.m.auraplayer.data

import androidx.compose.runtime.*
import kotlinx.coroutines.Job

data class CloudPage<T>(val items: List<T>, val nextOffset: Int, val hasMore: Boolean, val total: Int? = null)

/** Shared by the visible collection and its playback queue, including after the page is closed. */
class CloudPlaylistContent(val playlist: CloudPlaylist) {
    var songs by mutableStateOf<List<Song>>(emptyList()); internal set
    var loading by mutableStateOf(false); internal set
    var failed by mutableStateOf(false); internal set
    var hasMore by mutableStateOf(true); internal set
    var total by mutableIntStateOf(playlist.count); internal set
    internal var offset = 0
    internal var loadedAt = 0L
    internal var job: Job? = null
}
