package btm.m.auraplayer.data

import androidx.compose.runtime.*
import kotlinx.coroutines.Job

data class CloudPage<T>(val items: List<T>, val nextOffset: Int, val hasMore: Boolean, val total: Int? = null)

/** Shared by the visible collection and its playback queue, including after the page is closed. */
class CloudPlaylistContent(val playlist: CloudPlaylist, val source: String = "playlist") {
    var songs by mutableStateOf<List<Song>>(emptyList()); internal set
    var loading by mutableStateOf(false); internal set
    var failed by mutableStateOf(false); internal set
    var hasMore by mutableStateOf(true); internal set
    var total by mutableIntStateOf(playlist.count); internal set
    var catalog by mutableStateOf<CatalogInfo?>(null); internal set
    var albums by mutableStateOf<List<CatalogAlbum>>(emptyList()); internal set
    var albumsLoading by mutableStateOf(false); internal set
    var albumsFailed by mutableStateOf(false); internal set
    var albumsHasMore by mutableStateOf(true); internal set
    internal var albumsOffset = 0
    internal var albumsJob: Job? = null
    internal var offset = 0
    internal var loadedAt = 0L
    internal var job: Job? = null
}

data class CatalogAlbum(val id: Long, val title: String, val artwork: String, val artistId: Long,
                        val artist: String, val published: Long = 0, val count: Int = 0)
data class CatalogInfo(val artistId: Long = 0, val artist: String = "", val published: Long = 0,
                       val company: String = "", val description: String = "", val albums: List<CatalogAlbum> = emptyList())
data class CloudCatalogPage(val title: String, val artwork: String, val page: CloudPage<Song>, val info: CatalogInfo? = null)

/** Each detail route retains its own content and scroll composition while another route covers it. */
class CollectionDestination(val id: Long, initial: MusicCollection, initialCloud: CloudPlaylistContent?) {
    var collection by mutableStateOf(initial); internal set
    var cloud by mutableStateOf(initialCloud); internal set
}
