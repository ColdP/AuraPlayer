package btm.m.auraplayer

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.compose.runtime.*
import btm.m.auraplayer.data.*
import btm.m.auraplayer.playback.PlaybackService
import btm.m.auraplayer.ui.tr
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import coil3.SingletonImageLoader
import coil3.request.ImageRequest

class AuraViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as AuraApplication
    val settings get() = app.settings
    val history get() = app.history
    val songSort = SongSortSettings(application)
    var sortedSongs by mutableStateOf<List<Song>>(emptyList()); private set
    private var sortJob: Job? = null
    fun sortSongs(order: SongSort) { songSort.update(order); updateSortedSongs() }
    private fun updateSortedSongs() {
        sortJob?.cancel()
        val source = songs
        val order = songSort.order
        sortJob = viewModelScope.launch { sortedSongs = withContext(Dispatchers.Default) { order.apply(source) } }
    }
    var songs by mutableStateOf<List<Song>>(emptyList()); private set
    var scanning by mutableStateOf(false); private set
    var hasPermission by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var account by mutableStateOf<CloudAccount?>(null); private set
    var cloudPlaylists by mutableStateOf<List<CloudPlaylist>>(emptyList()); private set
    var cloudPlaylistsLoading by mutableStateOf(false); private set
    var cloudPlaylistsFailed by mutableStateOf(false); private set
    var cloudPlaylistsHasMore by mutableStateOf(false); private set
    private var cloudPlaylistOffset = 0
    private var cloudPlaylistRetryReset = false
    private var cloudPlaylistRetryRefresh = false
    private var cloudPlaylistJob: Job? = null
    var cloudCollection by mutableStateOf<CloudPlaylistContent?>(null); private set
    private val cloudContents = linkedMapOf<Long, CloudPlaylistContent>()
    private var cloudQueue: CloudPlaylistContent? = null
    var cloudBusy by mutableStateOf(false); private set
    var loginMessage by mutableStateOf(""); private set
    var smsRemaining by mutableIntStateOf(0); private set
    var current by mutableStateOf<Song?>(null); private set
    var playing by mutableStateOf(false); private set
    var playbackRequested by mutableStateOf(false); private set
    val playbackPreferences = btm.m.auraplayer.playback.PlaybackPreferences(application)
    var quality by mutableStateOf(playbackPreferences.quality); private set
    var showTranslation by mutableStateOf(playbackPreferences.translation); private set
    var showPronunciation by mutableStateOf(playbackPreferences.pronunciation); private set
    private val lyricPreferences = btm.m.auraplayer.lyrics.LyricPreferences(application)
    var lyricSize by mutableStateOf(lyricPreferences.size); private set
    fun changeLyricSize(value: btm.m.auraplayer.lyrics.LyricSize) {
        lyricSize = value; lyricPreferences.size = value
    }
    var position by mutableLongStateOf(0L); private set
    var duration by mutableLongStateOf(0L); private set
    var queue by mutableStateOf<List<Song>>(emptyList()); private set
    var queueIndex by mutableIntStateOf(-1); private set
    var playbackMode by mutableIntStateOf(0); private set
    var availableQualities by mutableStateOf<List<btm.m.auraplayer.playback.StreamQuality>>(emptyList()); private set
    var qualityLoading by mutableStateOf(false); private set
    var qualityError by mutableStateOf(false); private set
    var qualityDetails by mutableStateOf<List<btm.m.auraplayer.playback.CloudQuality>>(emptyList()); private set
    var playbackSpeed by mutableFloatStateOf(playbackPreferences.speed); private set
    var sleepRemaining by mutableLongStateOf(0L); private set
    var downloadBusy by mutableStateOf(false); private set
    var downloadedSongs by mutableStateOf<List<Song>>(emptyList()); private set
    private val downloadsPreferences = btm.m.auraplayer.download.MusicDownloads.completedPreferences(application)
    private val downloadsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshDownloadedSongs() }
    var streamInfo by mutableStateOf<btm.m.auraplayer.playback.StreamInfo?>(null); private set
    private var qualityJob: Job? = null
    var localAudioDetails by mutableStateOf<btm.m.auraplayer.playback.AudioDetails?>(null); private set
    private var localAudioBase: btm.m.auraplayer.playback.AudioDetails? = null
    private var audioJob: Job? = null
    var audioFormat by mutableStateOf<androidx.media3.common.Format?>(null); private set
    var lyrics by mutableStateOf<List<btm.m.auraplayer.lyrics.LyricLine>>(emptyList()); private set
    var lyricsLoading by mutableStateOf(false); private set
    private val lyricRepository = btm.m.auraplayer.lyrics.LyricsRepository(application)
    private var lyricJob: Job? = null
    private var lyricKey: String? = null
    var buffering by mutableStateOf(false); private set
    var collection by mutableStateOf<MusicCollection?>(null); private set
    private val collectionDestinations = mutableStateMapOf<Long, CollectionDestination>()
    private var collectionSerial = 0L
    var activeCollectionId by mutableLongStateOf(0L); private set
    val collectionDestinationIds get() = collectionDestinations.keys.toSet()
    fun collectionDestination(id: Long) = collectionDestinations[id]
    var directory by mutableStateOf<MusicDirectory?>(null); private set
    var searchQuery by mutableStateOf(""); private set
    var searchResults by mutableStateOf<List<Song>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    var searchHasMore by mutableStateOf(false); private set
    private var cloudSearchOffset = 0
    private var searchJob: Job? = null
    private var cloudJob: Job? = null
    private var scanJob: Job? = null
    var searchCloud by mutableStateOf(false); private set
    var searchCategory by mutableStateOf(SearchCategory.ALL); private set
    var searchHits by mutableStateOf<List<MusicSearchHit>>(emptyList()); private set
    private val queries = MutableStateFlow(MusicSearchRequest())
    var cloudLikedIds by mutableStateOf<Set<Long>>(emptySet()); private set
    var cloudLikesReady by mutableStateOf(false); private set
    private var likesJob: Job? = null
    private val liking = mutableSetOf<Long>()
    var subscribedAlbums by mutableStateOf<List<CatalogAlbum>>(emptyList()); private set
    var subscribedAlbumsLoading by mutableStateOf(false); private set
    var subscribedAlbumsFailed by mutableStateOf(false); private set
    var subscribedAlbumsHasMore by mutableStateOf(false); private set
    private var subscribedAlbumOffset = 0
    private var subscribedAlbumJob: Job? = null
    private var cloudGeneration = 0
    private var controller: MediaController? = null
    private val knownSongs = mutableMapOf<String, Song>()
    private val artistArtworkCache = mutableStateMapOf<Long, String>()
    private val songArtistCache = mutableMapOf<String, List<SongArtist>>()
    private val prefetchedArtists = mutableSetOf<Long>()
    private var lastCurrentKey: String? = null
    private val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            current = player.currentMediaItem?.let(::songFor)
            current?.takeIf { it.key != lastCurrentKey }?.let { song ->
                lastCurrentKey = song.key
                if (song.cloud) viewModelScope.launch { resolveSongArtists(song) }
            }
            queue = (0 until player.mediaItemCount).map { songFor(player.getMediaItemAt(it)) }
            queueIndex = player.currentMediaItemIndex
            playbackMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) 2 else if (player.shuffleModeEnabled) 1 else 0
            duration = player.duration.takeIf { it > 0 } ?: current?.duration ?: 0
            position = player.currentPosition.coerceAtLeast(0)
            audioFormat = player.currentTracks.groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.let { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
            loadLyrics(current)
            if (current?.cloud == false) localAudioDetails = (localAudioBase ?: btm.m.auraplayer.playback.AudioDetails()).withFormat(audioFormat)
            playing = player.isPlaying
            playbackRequested = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            buffering = player.playbackState == Player.STATE_BUFFERING
            playbackSpeed = player.playbackParameters.speed
            cloudQueue?.takeIf { !it.failed && player.currentMediaItemIndex >= player.mediaItemCount - 5 }
                ?.let { loadCloudSongs(it) }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            knownSongs[mediaItem?.mediaId]?.let(history::record)
        }
        override fun onPlayerError(e: PlaybackException) {
            error = tr("playback_error", "播放失败，请检查文件、网络或歌曲播放权限")
        }
    }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshLibrary(debounce = true) }
    }
    init {
        app.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        future.addListener({
            runCatching { future.get() }.onSuccess { controller = it; it.addListener(listener); listener.onEvents(it, Player.Events(androidx.media3.common.FlagSet.Builder().build())) }
                .onFailure { error = tr("player_connect_error", "无法连接播放服务") }
        }, ContextCompat.getMainExecutor(app))
        viewModelScope.launch {
            while (isActive) {
                controller?.let { player ->
                    streamInfo = current?.takeIf { it.cloud }?.let { btm.m.auraplayer.playback.PlaybackStreams.records[it.id] }
                    position = player.currentPosition.coerceAtLeast(0)
                    duration = player.duration.takeIf { it > 0 } ?: current?.duration ?: 0
                    sleepRemaining = (playbackPreferences.sleepDeadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                }
                delay(100)
            }
        }
        refreshLibrary()
        restoreAccount()
        refreshDownloadedSongs()
        downloadsPreferences.registerOnSharedPreferenceChangeListener(downloadsListener)
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            queries.debounce(300).collectLatest { request ->
                searchJob?.cancel()
                searching = request.query.isNotBlank()
                try {
                    if (request.query.isBlank()) { searchHits = emptyList(); searchResults = emptyList(); searchHasMore = false }
                    else {
                        val page = if (request.cloud) app.api.searchCategory(request.query, request.category) else localSearch(request)
                        ensureActive()
                        if (request != queries.value) return@collectLatest
                        searchHits = page.items; searchHasMore = page.hasMore; cloudSearchOffset = page.nextOffset
                        searchResults = page.items.filter { it.kind == SearchCategory.SONG || it.kind == SearchCategory.LYRICS }.mapNotNull { it.song }
                        knownSongs.putAll(searchResults.associateBy { it.key })
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = tr("cloud_search_failed", "云端搜索失败，请重试") }
                finally { if (queries.value == request) searching = false }
            }
        }
    }
    fun dismissError() { error = null }
    fun updateSettings(transform: (Appearance) -> Appearance) {
        val before = settings.value
        settings.update(transform)
        val after = settings.value
        if (before.whitelist != after.whitelist || before.blacklist != after.blacklist ||
            before.durationFilter != after.durationFilter || before.minimumSeconds != after.minimumSeconds) refreshLibrary(debounce = true)
    }
    fun refreshLibrary(debounce: Boolean = false) {
        hasPermission = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        scanJob?.cancel()
        if (!hasPermission) { songs = emptyList(); sortedSongs = emptyList(); scanning = false; return }
        scanJob = viewModelScope.launch {
            if (debounce) delay(300)
            scanning = true
            try {
                songs = MediaLibrary(app).scan(settings.value)
                updateSortedSongs()
                if (error == tr("scan_error", "读取音乐失败，请重新扫描")) error = null
                knownSongs.putAll(songs.associateBy { it.key })
            } catch (e: CancellationException) { throw e }
            catch (_: SecurityException) { hasPermission = false; songs = emptyList(); sortedSongs = emptyList() }
            catch (e: Exception) { android.util.Log.e("AuraMediaLibrary", "MediaStore scan failed", e); error = tr("scan_error", "读取音乐失败，请重新扫描") }
            finally { scanning = false }
        }
    }
    fun play(queue: List<Song>, start: Int = 0, shuffle: Boolean = false) {
        if (queue.isEmpty()) return
        val player = controller ?: run { error = tr("player_connecting", "播放服务正在连接，请稍后重试"); return }
        cloudQueue = null
        val ordered = if (shuffle) queue.shuffled() else queue
        knownSongs.putAll(ordered.associateBy { it.key })
        val items = ordered.map(::mediaItem)
        player.setMediaItems(items, if (shuffle) 0 else start.coerceIn(0, items.lastIndex), 0)
        player.prepare()
        player.play()
    }
    private fun mediaItem(song: Song) = MediaItem.Builder().setMediaId(song.key).setUri(if (song.cloud) android.net.Uri.parse(song.uri).buildUpon().appendQueryParameter("quality", quality.level).build().toString() else song.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
            .setExtras(android.os.Bundle().apply {
                putLong("albumId", song.albumId); putLong("artistId", song.artistId)
                putString("artists", encodeSongArtists(song.artists))
            })
            .setDurationMs(song.duration).setArtworkUri(song.artwork.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)).build()).build()

    /** Retro home suggestions queue immediately after the current song. */
    fun playNext(tracks: List<Song>) {
        if (tracks.isEmpty()) return
        val player = controller ?: run { error = tr("player_connecting", "播放服务正在连接，请稍后重试"); return }
        knownSongs.putAll(tracks.associateBy { it.key })
        if (player.mediaItemCount == 0) { play(tracks); return }
        val next = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
        player.addMediaItems(next, tracks.map(::mediaItem))
        if (!player.isPlaying) {
            player.seekToDefaultPosition(next)
            player.prepare()
            player.play()
        }
    }
    private fun songFor(item: MediaItem): Song = knownSongs.getOrPut(item.mediaId) {
        val md = item.mediaMetadata
        val cloud = item.mediaId.startsWith("cloud:")
        Song(item.mediaId.substringAfter(':').toLongOrNull() ?: item.mediaId.hashCode().toLong(),
            md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(), md.albumTitle?.toString().orEmpty(),
            albumId = md.extras?.getLong("albumId") ?: 0L, artistId = md.extras?.getLong("artistId") ?: 0L,
            duration = item.mediaMetadata.durationMs ?: 0,
            uri = if (cloud) "aura://netease/${item.mediaId.substringAfter(':')}" else item.mediaId.removePrefix("local:"),
            artwork = md.artworkUri?.toString().orEmpty(), cloud = cloud,
            artists = decodeSongArtists(md.extras?.getString("artists")))
    }
    private fun loadLyrics(song: Song?) {
        if (lyricKey == song?.key) return
        audioJob?.cancel(); localAudioBase = null; localAudioDetails = null
        if (song != null && !song.cloud) audioJob = viewModelScope.launch {
            val detail = withContext(Dispatchers.IO) { btm.m.auraplayer.playback.AudioDetails.inspect(app, song) }
            if (current?.key == song.key) { localAudioBase = detail; localAudioDetails = detail.withFormat(audioFormat) }
        }
        lyricKey = song?.key; lyricJob?.cancel(); lyrics = emptyList(); lyricsLoading = song != null
        if (song == null) return
        lyricJob = viewModelScope.launch {
            try {
                lyricRepository.lyrics(song).collect { rows ->
                    lyrics = rows
                    if (rows.isNotEmpty()) lyricsLoading = false
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("AuraLyrics", "Lyrics lookup failed", e) }
            finally { if (lyricKey == song.key) lyricsLoading = false }
        }
    }
    fun lyricsOptions(translation: Boolean, pronunciation: Boolean) {
        showTranslation = translation; showPronunciation = pronunciation
        playbackPreferences.translation = translation; playbackPreferences.pronunciation = pronunciation
    }
    fun seekTo(value: Long) { controller?.seekTo(value.coerceIn(0L, duration.coerceAtLeast(0))); position = value.coerceIn(0L, duration.coerceAtLeast(0)) }
    fun queueTo(index: Int) { controller?.let { if (index in 0 until it.mediaItemCount) { it.seekToDefaultPosition(index); it.play() } } }
    fun removeQueue(index: Int) { controller?.let { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) } }
    fun moveQueue(from: Int, to: Int) { controller?.let {
        if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount && from != to) it.moveMediaItem(from, to)
    } }
    fun cyclePlaybackMode() { controller?.let {
        when (playbackMode) {
            0 -> { it.repeatMode = Player.REPEAT_MODE_ALL; it.shuffleModeEnabled = true }
            1 -> { it.shuffleModeEnabled = false; it.repeatMode = Player.REPEAT_MODE_ONE }
            else -> { it.shuffleModeEnabled = false; it.repeatMode = Player.REPEAT_MODE_ALL }
        }
    } }
    fun refreshQualities() {
        val song = current?.takeIf { it.cloud } ?: return
        qualityJob?.cancel()
        qualityJob = viewModelScope.launch {
            qualityLoading = true; qualityError = false; availableQualities = emptyList(); qualityDetails = emptyList()
            try {
                val fetched = app.api.qualityDetails(song.id)
                // The stream endpoint can already have resolved a usable level while
                // music/detail returns no catalogue (this is common for guest/DRM
                // tracks). Keep that actual level selectable instead of showing an
                // empty menu.
                val levels = if (fetched.isNotEmpty()) fetched else streamInfo?.let { stream ->
                    btm.m.auraplayer.playback.StreamQuality.entries.firstOrNull { it.level == stream.level }?.let {
                        listOf(btm.m.auraplayer.playback.CloudQuality(it, btm.m.auraplayer.playback.AudioDetails(
                            codec = stream.codec, sampleRate = stream.sampleRate, bits = stream.bits, bitrate = stream.bitrate)))
                    }
                }.orEmpty()
                if (current?.key == song.key) { qualityDetails = levels; availableQualities = levels.map { it.quality } }
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { qualityError = true }
            finally { qualityLoading = false }
        }
    }
    fun selectQuality(value: btm.m.auraplayer.playback.StreamQuality) {
        if (quality == value) return
        quality = value; playbackPreferences.quality = value
        val player = controller ?: return
        val index = player.currentMediaItemIndex
        val pos = player.currentPosition
        val resume = player.playWhenReady
        // Replace cloud URIs so ExoPlayer resolves the newly requested level, preserving queue/time.
        val items = (0 until player.mediaItemCount).map { mediaItem(songFor(player.getMediaItemAt(it))) }
        if (items.isNotEmpty()) { player.setMediaItems(items, index.coerceIn(items.indices), pos); player.prepare(); player.playWhenReady = resume }
    }
    fun togglePlayback() { controller?.let { if (it.playWhenReady) it.pause() else it.play() } }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }
    fun enqueueNext(song: Song) {
        val player = controller ?: run { error = tr("player_connecting", "播放服务正在连接，请稍后重试"); return }
        if (player.mediaItemCount == 0) { play(listOf(song)); return }
        knownSongs[song.key] = song
        player.addMediaItem((player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount), mediaItem(song))
    }
    fun enqueue(song: Song) {
        val player = controller ?: run { error = tr("player_connecting", "播放服务正在连接，请稍后重试"); return }
        knownSongs[song.key] = song
        player.addMediaItem(mediaItem(song))
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }
    fun refreshDownloadedSongs() { downloadedSongs = btm.m.auraplayer.download.MusicDownloads.downloadedSongs(app) }
    fun changePlaybackSpeed(value: Float) {
        if (value !in listOf(.5f, .75f, 1f, 1.5f, 2f, 3f)) return
        controller?.setPlaybackSpeed(value)
        playbackPreferences.speed = value; playbackSpeed = value
    }
    fun setSleepTimer(minutes: Int) {
        playbackPreferences.sleepMinutes = minutes
        playbackPreferences.sleepDeadline = if (minutes > 0) android.os.SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
        sleepRemaining = if (minutes > 0) minutes * 60_000L else 0L
    }
    fun download(song: Song, quality: btm.m.auraplayer.playback.StreamQuality, complete: (Boolean) -> Unit) {
        if (!song.cloud || downloadBusy) return
        downloadBusy = true
        viewModelScope.launch {
            try {
                val source = app.api.downloadSource(song.id, quality)
                withContext(Dispatchers.IO) { btm.m.auraplayer.download.MusicDownloads.enqueue(app, song, source) }
                complete(true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { complete(false) }
            finally { downloadBusy = false }
        }
    }
    fun openSongAlbum(song: Song) = openSongCatalog(song, false)
    fun openSongArtist(song: Song) = openSongCatalog(song, true)
    fun openSongArtist(song: Song, artist: SongArtist) {
        if (song.cloud && artist.artwork.isNotBlank()) artistArtworkCache[artist.id] = artist.artwork
        openSongArtist(song.copy(artist = artist.name, artistId = artist.id, artists = listOf(artist)))
    }
    suspend fun resolveSongArtists(song: Song): List<SongArtist> = coroutineScope {
        val credits = if (song.cloud) songArtistCache[song.key] ?: song.artists.ifEmpty {
            try { app.api.songArtists(song.id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
        }.ifEmpty { song.creditedArtists() }.also { if (it.all { artist -> artist.id > 0 }) songArtistCache[song.key] = it }
        else song.creditedArtists()
        credits.map { artist -> async {
            if (!song.cloud) {
                val local = songs.firstOrNull { it.artist.equals(artist.name, ignoreCase = true) }
                artist.copy(id = local?.artistId ?: artist.id, artwork = local?.artwork.orEmpty())
            } else {
                val cached = artistArtworkCache[artist.id]
                val artwork = cached ?: artist.artwork.takeIf(String::isNotBlank) ?: if (artist.id > 0) try {
                    app.api.artistArtwork(artist.id)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { "" } else ""
                if (artwork.isNotBlank()) {
                    artistArtworkCache[artist.id] = artwork
                    if (prefetchedArtists.add(artist.id)) SingletonImageLoader.get(app).enqueue(
                        ImageRequest.Builder(app).data(artwork).size(512).build())
                }
                artist.copy(artwork = artwork)
            }
        } }.awaitAll()
    }
    private fun openSongCatalog(song: Song, artist: Boolean) {
        val id = if (artist) song.artistId else song.albumId
        val title = if (artist) song.artist else song.album
        val nextKey = "${if (song.cloud) "cloud:" else ""}${if (artist) "artist" else "album"}:$id"
        if (collection?.key == nextKey || song.cloud && id <= 0) return
        if (!song.cloud) {
            val tracks = songs.filter { if (artist) if (id > 0) it.artistId == id else it.creditedArtists().any { credit -> credit.name.equals(title, true) }
                else if (id > 0) it.albumId == id else it.album == title }
            pushCollection(MusicCollection(nextKey, title, song.artist, tracks, song.artwork), null)
        } else if (id > 0) {
            val source = if (artist) "artist" else "album"
            val artwork = if (artist) artistArtworkCache[id].orEmpty() else song.artwork
            val content = CloudPlaylistContent(CloudPlaylist(id, title, 0, artwork), source)
            pushCollection(MusicCollection(nextKey, title, song.artist, emptyList(), artwork), content)
            loadCloudSongs(content)
        }
    }
    private fun pushCollection(value: MusicCollection, content: CloudPlaylistContent?) {
        val id = ++collectionSerial
        collectionDestinations[id] = CollectionDestination(id, value, content)
        collection = value; cloudCollection = content; activeCollectionId = id
    }
    fun openCollection(value: MusicCollection) { pushCollection(value, null) }
    fun closeCollection(id: Long) {
        val removed = collectionDestinations.remove(id)
        removed?.cloud?.albumsJob?.cancel()
        if (removed?.cloud !== cloudQueue && removed?.cloud?.source != "playlist") removed?.cloud?.job?.cancel()
        val previous = collectionDestinations.maxByOrNull { it.key }?.value
        collection = previous?.collection; cloudCollection = previous?.cloud; activeCollectionId = previous?.id ?: 0L
    }
    fun openDirectory(value: MusicDirectory) { directory = value }
    fun closeDirectory() { directory = null }
    fun openCloudPlaylist(playlist: CloudPlaylist) {
        cloudContents[playlist.id]?.let { old ->
            if (!old.loading && (old.playlist.count != playlist.count ||
                    old.loadedAt > 0 && System.currentTimeMillis() - old.loadedAt > CloudMetadataCache.FRESH_MS)) {
                cloudContents.remove(playlist.id)
            }
        }
        val content = cloudContents.getOrPut(playlist.id) { CloudPlaylistContent(playlist) }
        // Keep only a few visited playlists in RAM. Their pages remain in the disk cache.
        while (cloudContents.size > 8) {
            val old = cloudContents.entries.firstOrNull { it.value !== content && it.value !== cloudQueue } ?: break
            old.value.job?.cancel(); cloudContents.remove(old.key)
        }
        pushCollection(MusicCollection("cloud:${playlist.id}", playlist.name, "", content.songs, playlist.artwork), content)
        if (content.songs.isEmpty() && !content.failed) loadCloudSongs(content)
    }
    fun loadMoreCloudSongs() { cloudCollection?.let { loadCloudSongs(it) } }
    fun loadMoreCloudSongsFor(content: CloudPlaylistContent) { loadCloudSongs(content) }
    fun refreshCloudSongs() {
        val old = cloudCollection ?: return
        if (old.loading) return
        val content = CloudPlaylistContent(old.playlist, old.source)
        if (old.source == "playlist") cloudContents[old.playlist.id] = content
        cloudCollection = content
        collectionDestinations[activeCollectionId]?.cloud = content
        loadCloudSongs(content, refresh = true)
    }
    private fun loadCloudSongs(content: CloudPlaylistContent, refresh: Boolean = false) {
        if (content.loading || !content.hasMore || content.source == "playlist" && account == null) return
        val generation = cloudGeneration
        content.loading = true; content.failed = false
        content.job = viewModelScope.launch {
            try {
                val catalog = when (content.source) {
                    "album" -> app.api.albumPage(content.playlist.id)
                    "artist" -> app.api.artistPage(content.playlist.id, content.offset)
                    else -> null
                }
                val page = catalog?.page ?: when (content.source) {
                    "rank" -> app.api.listeningRank(content.playlist.id).let { CloudPage(it, it.size, false, it.size) }
                    "radio" -> app.api.radioPrograms(content.playlist.id, content.offset)
                    else -> app.api.playlistSongs(content.playlist.id, content.offset, refresh = refresh)
                }
                ensureActive()
                if (generation != cloudGeneration) return@launch
                if (catalog != null) collectionDestinations.values.filter { it.cloud === content }.forEach { destination ->
                    destination.collection = destination.collection.copy(
                        title = catalog.title.ifBlank { destination.collection.title }, artwork = catalog.artwork.ifBlank { destination.collection.artwork })
                    if (destination.id == activeCollectionId) collection = destination.collection
                }
                catalog?.info?.let { content.catalog = it }
                val existing = content.songs.mapTo(hashSetOf()) { it.key }
                val newSongs = page.items.filterNot { it.key in existing }
                content.songs = content.songs + newSongs
                content.offset = page.nextOffset
                content.loadedAt = System.currentTimeMillis()
                content.hasMore = page.hasMore
                page.total?.let { content.total = it }
                if (page.total == null) content.total = maxOf(content.total, content.songs.size)
                knownSongs.putAll(newSongs.associateBy { it.key })
                if (cloudQueue === content && newSongs.isNotEmpty()) {
                    controller?.addMediaItems(newSongs.map(::mediaItem))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == cloudGeneration) { content.failed = true; cloudFailure(e) }
            }
            finally { content.loading = false }
            // An unavailable slice can be empty despite further IDs being available.
            if (!content.failed && content.hasMore && (content.songs.isEmpty() ||
                    cloudQueue === content && queueIndex >= queue.size - 5)) loadCloudSongs(content)
        }
    }
    fun playCollection(tracks: List<Song>, index: Int = 0, content: CloudPlaylistContent? = cloudCollection) {
        play(tracks, index)
        if (controller != null && tracks.isNotEmpty() && content != null) {
            cloudQueue = content
            if (index >= tracks.size - 5) loadCloudSongs(content)
        }
    }
    private suspend fun localSearch(request: MusicSearchRequest): CloudPage<MusicSearchHit> = withContext(Dispatchers.Default) {
        localMusicSearch(songs, request)
    }

    fun search(query: String) { if (query != searchQuery) { searchQuery = query; submitSearch() } }
    fun changeSearchScope(cloud: Boolean) {
        searchCloud = cloud
        if (!cloud && searchCategory.apiType > 100) searchCategory = SearchCategory.ALL
        submitSearch()
    }
    fun changeSearchCategory(category: SearchCategory) { searchCategory = category; submitSearch() }
    private fun submitSearch() {
        searchJob?.cancel(); searchHits = emptyList(); searchResults = emptyList(); searchHasMore = false
        searching = searchQuery.isNotBlank()
        queries.value = MusicSearchRequest(searchQuery, searchCloud, searchCategory)
    }
    fun loadMoreSearch() {
        if (searching || !searchHasMore || !searchCloud) return
        val request = queries.value
        searchJob = viewModelScope.launch {
            searching = true
            try {
                val page = app.api.searchCategory(request.query, request.category, cloudSearchOffset)
                if (request == queries.value) {
                    cloudSearchOffset = page.nextOffset; searchHasMore = page.hasMore
                    searchHits = (searchHits + page.items).distinctBy { it.key }
                    searchResults = searchHits.filter { it.kind == SearchCategory.SONG || it.kind == SearchCategory.LYRICS }.mapNotNull { it.song }
                    knownSongs.putAll(searchResults.associateBy { it.key })
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = tr("cloud_search_failed", "云端搜索失败，请重试") }
            finally { if (queries.value == request) searching = false }
        }
    }
    fun openSearchHit(hit: MusicSearchHit) {
        when (hit.kind) {
            SearchCategory.SONG, SearchCategory.LYRICS -> hit.song?.let { song -> play(searchResults, searchResults.indexOf(song)) }
            SearchCategory.ALBUM -> openSongAlbum(hit.song ?: Song(0, "", hit.subtitle, hit.title, hit.id, hit.artistId, uri = "", artwork = hit.artwork, cloud = hit.cloud))
            SearchCategory.ARTIST -> openSongArtist(hit.song ?: Song(0, "", hit.title, "", artistId = hit.id, uri = "", artwork = hit.artwork, cloud = hit.cloud))
            SearchCategory.VOICE -> viewModelScope.launch { try { app.api.voiceSong(hit.id)?.let { play(listOf(it)) } }
                catch (e: CancellationException) { throw e } catch (e: Exception) { cloudFailure(e) } }
            SearchCategory.BLOG -> {
                val content = CloudPlaylistContent(CloudPlaylist(hit.id, hit.title, 0, hit.artwork), "radio")
                pushCollection(MusicCollection("cloud:radio:${hit.id}", hit.title, hit.subtitle, emptyList(), hit.artwork), content)
                loadCloudSongs(content)
            }
            else -> Unit
        }
    }
    fun isFavorite(song: Song): Boolean = if (song.cloud) song.id in cloudLikedIds else song.key in history.favorites()
    fun toggleFavorite(song: Song) {
        if (!song.cloud) { history.toggle(song); return }
        val user = account ?: run { error = tr("login_required", "请先登录网易云音乐"); return }
        if (!liking.add(song.id)) return
        val generation = cloudGeneration
        viewModelScope.launch {
            try {
                likesJob?.join()
                if (generation != cloudGeneration || account?.userId != user.userId) return@launch
                if (!cloudLikesReady) { cloudLikedIds = app.api.likedSongIds(user.userId); cloudLikesReady = true }
                if (generation != cloudGeneration || account?.userId != user.userId) return@launch
                val like = song.id !in cloudLikedIds
                app.api.likeSong(song.id, like)
                if (generation != cloudGeneration || account?.userId != user.userId) return@launch
                cloudLikedIds = if (like) cloudLikedIds + song.id else cloudLikedIds - song.id
                val liked = cloudPlaylists.firstOrNull { it.specialType == 5 && it.creatorId == user.userId }
                if (liked != null) {
                    cloudPlaylists = cloudPlaylists.map { if (it.id == liked.id) it.copy(count = (it.count + if (like) 1 else -1).coerceAtLeast(0)) else it }
                    cloudContents.remove(liked.id)
                    collectionDestinations.values.mapNotNull { it.cloud }.filter { it.source == "playlist" && it.playlist.id == liked.id }.distinct().forEach { content ->
                        content.songs = if (like) (listOf(song) + content.songs).distinctBy { it.key } else content.songs.filterNot { it.id == song.id }
                        content.total = (content.total + if (like) 1 else -1).coerceAtLeast(0)
                    }
                    withContext(Dispatchers.IO) { app.api.invalidatePlaylist(liked.id) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == cloudGeneration) cloudFailure(e) }
            finally { liking.remove(song.id) }
        }
    }
    fun addSongToCloudPlaylist(playlist: CloudPlaylist, song: Song, complete: (Boolean) -> Unit = {}) {
        val user = account ?: run { complete(false); return }
        if (!song.cloud || playlist.creatorId != user.userId || playlist.specialType == 5) { complete(false); return }
        val generation = cloudGeneration
        viewModelScope.launch {
            try {
                app.api.addSongToPlaylist(playlist.id, song.id)
                if (generation != cloudGeneration) { complete(false); return@launch }
                cloudContents.remove(playlist.id)
                collectionDestinations.values.mapNotNull { it.cloud }.filter { it.source == "playlist" && it.playlist.id == playlist.id }.distinct().forEach {
                    if (it.songs.none { entry -> entry.key == song.key }) {
                        it.songs = listOf(song) + it.songs; it.total++
                    }
                }
                complete(true)
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { complete(false) }
        }
    }
    fun openListeningRank() {
        val user = account ?: return
        val content = CloudPlaylistContent(CloudPlaylist(user.userId, tr("listening_rank", "听歌排行"), 0, current?.artwork.orEmpty()), "rank")
        pushCollection(MusicCollection("cloud:rank:${user.userId}", content.playlist.name, "", emptyList(), content.playlist.artwork), content)
        loadCloudSongs(content)
    }
    fun openCatalogAlbum(album: CatalogAlbum) = openSongAlbum(Song(0, "", album.artist, album.title, album.id, album.artistId, uri = "", artwork = album.artwork, cloud = true))
    fun loadMoreSubscribedAlbums(reset: Boolean = false) {
        if (subscribedAlbumsLoading || !reset && !subscribedAlbumsHasMore) return
        val user = account ?: return
        val generation = cloudGeneration
        subscribedAlbumsLoading = true; subscribedAlbumsFailed = false
        subscribedAlbumJob = viewModelScope.launch {
            try {
                val page = app.api.subscribedAlbums(if (reset) 0 else subscribedAlbumOffset)
                if (generation == cloudGeneration && user.userId == account?.userId) {
                    subscribedAlbums = ((if (reset) emptyList() else subscribedAlbums) + page.items).distinctBy { it.id }
                    subscribedAlbumOffset = page.nextOffset; subscribedAlbumsHasMore = page.hasMore
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == cloudGeneration) { subscribedAlbumsFailed = true; cloudFailure(e) } }
            finally { if (generation == cloudGeneration) subscribedAlbumsLoading = false }
        }
    }
    fun loadMoreArtistAlbums(content: CloudPlaylistContent) {
        if (content.albumsLoading || !content.albumsHasMore) return
        content.albumsLoading = true; content.albumsFailed = false
        content.albumsJob = viewModelScope.launch {
            try {
                val page = app.api.artistAlbums(content.playlist.id, content.albumsOffset)
                content.albums = (content.albums + page.items).distinctBy { it.id }
                content.albumsOffset = page.nextOffset; content.albumsHasMore = page.hasMore
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { content.albumsFailed = true; cloudFailure(e) }
            finally { content.albumsLoading = false }
        }
    }
    fun configureApi(value: String): Boolean = runCatching {
        cloudGeneration++
        cloudJob?.cancel()
        val changed = app.endpoint != NeteaseApi.normalizeEndpoint(value)
        if (changed) clearCloudQueue()
        app.saveEndpoint(value)
        if (changed) { account = null; cloudPlaylists = emptyList() }
        loginMessage = tr("api_saved", "服务地址已保存")
    }.onFailure { loginMessage = tr("invalid_api", "请输入 HTTPS 地址；自建局域网服务可使用 HTTP") }.isSuccess

    private fun cloudAction(block: suspend () -> Unit) {
        cloudJob?.cancel()
        cloudJob = viewModelScope.launch {
            cloudBusy = true
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { cloudFailure(e) }
            finally { cloudBusy = false }
        }
    }
    private fun cloudFailure(e: Exception) {
        if (e is CloudApiException && e.status == 301) {
            cloudGeneration++
            resetCloudPaging()
            app.clearCookie(); account = null; cloudPlaylists = emptyList()
        }
        val message = when {
            e is CloudApiException && e.status == 301 -> tr("session_expired", "登录已过期，请重新登录")
            e is CloudApiException -> tr("cloud_request_failed", "网易云请求失败") + " (${e.status})"
            else -> tr("cloud_network_error", "连接失败，请检查网络后重试")
        }
        loginMessage = message; error = message
    }
    private fun resetCloudPaging() {
        likesJob?.cancel(); subscribedAlbumJob?.cancel()
        cloudLikedIds = emptySet(); cloudLikesReady = false; liking.clear()
        subscribedAlbums = emptyList(); subscribedAlbumOffset = 0; subscribedAlbumsLoading = false
        subscribedAlbumsHasMore = false; subscribedAlbumsFailed = false
        collectionDestinations.values.forEach { it.cloud?.albumsJob?.cancel() }
        cloudPlaylistJob?.cancel()
        cloudContents.values.forEach { it.job?.cancel() }
        val cloudIds = collectionDestinations.filterValues { it.cloud != null }.keys.toList()
        cloudIds.forEach { collectionDestinations[it]?.cloud?.job?.cancel(); collectionDestinations.remove(it) }
        cloudCollection?.job?.cancel()
        cloudQueue?.job?.cancel()
        cloudQueue = null; cloudCollection = null; cloudContents.clear()
        cloudPlaylistOffset = 0; cloudPlaylistsLoading = false
        cloudPlaylistRetryReset = false; cloudPlaylistRetryRefresh = false
        cloudPlaylistsFailed = false; cloudPlaylistsHasMore = false
        val previous = collectionDestinations.maxByOrNull { it.key }?.value
        collection = previous?.collection; cloudCollection = previous?.cloud; activeCollectionId = previous?.id ?: 0L
    }
    private fun restoreAccount() {
        if (app.cookie.isNotBlank()) cloudAction {
            account = app.api.account()
            loadCloudPlaylists(reset = true)
        }
    }
    fun refreshCloud() { loadCloudPlaylists(reset = true, refresh = true) }
    private fun refreshCloudExtras() {
        val user = account ?: return
        val generation = cloudGeneration
        likesJob?.cancel()
        likesJob = viewModelScope.launch {
            try { val ids = app.api.likedSongIds(user.userId)
                if (generation == cloudGeneration && account?.userId == user.userId) { cloudLikedIds = ids; cloudLikesReady = true }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (generation == cloudGeneration) cloudFailure(e) }
        }
        loadMoreSubscribedAlbums(reset = true)
    }
    fun loadMoreCloudPlaylists() {
        loadCloudPlaylists(reset = cloudPlaylistsFailed && cloudPlaylistRetryReset,
            refresh = cloudPlaylistsFailed && cloudPlaylistRetryRefresh)
    }
    fun forgetCloudMetadataCache(categories: Set<CloudCacheCategory>) {
        if (CloudCacheCategory.METADATA in categories) cloudContents.clear()
    }
    private fun loadCloudPlaylists(reset: Boolean = false, refresh: Boolean = false) {
        val user = account ?: return
        if (cloudPlaylistsLoading || !reset && !cloudPlaylistsHasMore) return
        if (reset) refreshCloudExtras()
        val generation = cloudGeneration
        val offset = if (reset) 0 else cloudPlaylistOffset
        cloudPlaylistRetryReset = reset; cloudPlaylistRetryRefresh = refresh
        cloudPlaylistsLoading = true; cloudPlaylistsFailed = false
        cloudPlaylistJob = viewModelScope.launch {
            try {
                val page = app.api.playlists(user.userId, offset, refresh = refresh)
                ensureActive()
                if (generation != cloudGeneration || account?.userId != user.userId) return@launch
                cloudPlaylists = ((if (reset) emptyList() else cloudPlaylists) + page.items).distinctBy { it.id }.sortedByDescending { it.specialType == 5 && it.creatorId == user.userId }
                cloudPlaylistOffset = page.nextOffset; cloudPlaylistsHasMore = page.hasMore
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == cloudGeneration) { cloudPlaylistsFailed = true; cloudFailure(e) }
            }
            finally { if (generation == cloudGeneration) cloudPlaylistsLoading = false }
        }
    }
    fun sendCaptcha(phone: String, country: String) {
        if (smsRemaining > 0 || phone.isBlank() || country.isBlank()) return
        cloudAction {
            app.api.sendCaptcha(phone.trim(), country.trim().removePrefix("+"))
            loginMessage = tr("captcha_sent", "验证码已发送")
            viewModelScope.launch { smsRemaining = 60; while (smsRemaining > 0) { delay(1000); smsRemaining-- } }
        }
    }
    fun login(phone: String, country: String, captcha: String) = cloudAction {
        val generation = cloudGeneration
        val (cookie, user) = app.api.login(phone.trim(), country.trim().removePrefix("+"), captcha.trim())
        currentCoroutineContext().ensureActive()
        if (generation != cloudGeneration) return@cloudAction
        app.saveCookie(cookie)
        resetCloudPaging(); cloudPlaylists = emptyList()
        account = user
        loginMessage = tr("login_success", "登录成功")
        loadCloudPlaylists(reset = true)
    }
    fun completeOfficialLogin(sessionCookie: String) {
        if (cloudBusy || app.endpoint.isNotBlank()) return
        cloudAction {
            val generation = cloudGeneration
            val user = withContext(Dispatchers.IO) { NeteaseApi({ "" }, { sessionCookie }).account() }
            currentCoroutineContext().ensureActive()
            if (generation != cloudGeneration) return@cloudAction
            app.saveCookie(sessionCookie)
            resetCloudPaging(); cloudPlaylists = emptyList()
            account = user
            loginMessage = tr("login_success", "登录成功")
            loadCloudPlaylists(reset = true)
        }
    }
    fun useOfficialService() {
        cloudGeneration++
        cloudJob?.cancel()
        clearCloudQueue()
        app.useOfficialService()
        account = null; cloudPlaylists = emptyList(); cloudBusy = false; loginMessage = ""
    }
    fun logout() {
        cloudGeneration++
        cloudJob?.cancel()
        clearCloudQueue()
        app.clearCookie()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        account = null
        cloudPlaylists = emptyList()
        cloudBusy = false
        loginMessage = tr("logged_out", "已退出登录")
    }
    private fun clearCloudQueue() {
        resetCloudPaging()
        controller?.let { player ->
            if (player.currentMediaItem?.mediaId?.startsWith("cloud:") == true) player.pause()
            for (index in player.mediaItemCount - 1 downTo 0) {
                if (player.getMediaItemAt(index).mediaId.startsWith("cloud:")) player.removeMediaItem(index)
            }
        }
    }
    override fun onCleared() {
        downloadsPreferences.unregisterOnSharedPreferenceChangeListener(downloadsListener)
        app.contentResolver.unregisterContentObserver(observer)
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
        super.onCleared()
    }
}
