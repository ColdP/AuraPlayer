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
    var directory by mutableStateOf<MusicDirectory?>(null); private set
    var searchQuery by mutableStateOf(""); private set
    var searchResults by mutableStateOf<List<Song>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    var searchHasMore by mutableStateOf(false); private set
    private var cloudSearchOffset = 0
    private var searchJob: Job? = null
    private var cloudJob: Job? = null
    private var scanJob: Job? = null
    private val queries = MutableStateFlow("")
    private var cloudGeneration = 0
    private var controller: MediaController? = null
    private val knownSongs = mutableMapOf<String, Song>()
    private val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            current = player.currentMediaItem?.let(::songFor)
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
                }
                delay(100)
            }
        }
        refreshLibrary()
        restoreAccount()
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            queries.debounce(300).collectLatest { query ->
                searchJob?.cancel()
                searching = true
                try {
                    val local = withContext(Dispatchers.Default) {
                        songs.filter { "${it.title} ${it.artist} ${it.album}".contains(query, true) }
                    }
                    cloudSearchOffset = 0
                    val cloud = if (query.isNotBlank()) {
                        try { app.api.search(query) } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = tr("cloud_search_error", "网易云搜索失败，本地结果仍可使用"); emptyList() }
                    } else emptyList()
                    searchHasMore = cloud.size == 50
                    searchResults = if (query.isBlank()) emptyList() else local + cloud
                    knownSongs.putAll(searchResults.associateBy { it.key })
                } finally { searching = false }
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
            duration = item.mediaMetadata.durationMs ?: 0,
            uri = if (cloud) "aura://netease/${item.mediaId.substringAfter(':')}" else item.mediaId.removePrefix("local:"),
            artwork = md.artworkUri?.toString().orEmpty(), cloud = cloud)
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
            qualityLoading = true; qualityError = false; availableQualities = emptyList()
            try { val levels = app.api.availableQualities(song.id); if (current?.key == song.key) availableQualities = levels }
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
    fun openCollection(value: MusicCollection) { cloudCollection = null; collection = value }
    fun closeCollection() { collection = null; cloudCollection = null }
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
        cloudCollection = content
        collection = MusicCollection("cloud:${playlist.id}", playlist.name, "", content.songs, playlist.artwork)
        if (content.songs.isEmpty() && !content.failed) loadCloudSongs(content)
    }
    fun loadMoreCloudSongs() { cloudCollection?.let { loadCloudSongs(it) } }
    fun refreshCloudSongs() {
        val old = cloudCollection ?: return
        if (old.loading) return
        val content = CloudPlaylistContent(old.playlist)
        cloudContents[old.playlist.id] = content
        cloudCollection = content
        loadCloudSongs(content, refresh = true)
    }
    private fun loadCloudSongs(content: CloudPlaylistContent, refresh: Boolean = false) {
        if (content.loading || !content.hasMore || account == null) return
        val generation = cloudGeneration
        content.loading = true; content.failed = false
        content.job = viewModelScope.launch {
            try {
                val page = app.api.playlistSongs(content.playlist.id, content.offset, refresh = refresh)
                ensureActive()
                if (generation != cloudGeneration) return@launch
                val existing = content.songs.mapTo(hashSetOf()) { it.key }
                val newSongs = page.items.filterNot { it.key in existing }
                content.songs = content.songs + newSongs
                content.offset = page.nextOffset
                content.loadedAt = System.currentTimeMillis()
                content.hasMore = page.hasMore
                page.total?.let { content.total = it }
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
    fun playCollection(tracks: List<Song>, index: Int = 0) {
        val content = cloudCollection
        play(tracks, index)
        if (controller != null && tracks.isNotEmpty() && content != null) {
            cloudQueue = content
            if (index >= tracks.size - 5) loadCloudSongs(content)
        }
    }
    fun search(query: String) {
        if (query == searchQuery) return
        searchQuery = query
        searchResults = emptyList()
        searchHasMore = false
        searching = query.isNotBlank()
        queries.value = query
    }
    fun loadMoreSearch() {
        if (searching || !searchHasMore) return
        val query = searchQuery
        searchJob = viewModelScope.launch {
            searching = true
            try {
                val nextOffset = cloudSearchOffset + 50
                val more = app.api.search(query, nextOffset)
                if (query == searchQuery) {
                    cloudSearchOffset = nextOffset
                    searchHasMore = more.size == 50
                    searchResults = (searchResults + more).distinctBy { it.key }
                    knownSongs.putAll(more.associateBy { it.key })
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = tr("cloud_search_error", "网易云搜索失败，本地结果仍可使用") }
            finally { searching = false }
        }
    }
    fun configureApi(value: String): Boolean = runCatching {
        cloudGeneration++
        cloudJob?.cancel()
        val changed = app.endpoint != NeteaseApi.normalizeEndpoint(value)
        if (changed) clearCloudQueue()
        app.saveEndpoint(value)
        if (changed) { account = null; cloudPlaylists = emptyList(); collection = null }
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
        cloudPlaylistJob?.cancel()
        cloudContents.values.forEach { it.job?.cancel() }
        cloudCollection?.job?.cancel()
        cloudQueue?.job?.cancel()
        cloudQueue = null; cloudCollection = null; cloudContents.clear()
        cloudPlaylistOffset = 0; cloudPlaylistsLoading = false
        cloudPlaylistRetryReset = false; cloudPlaylistRetryRefresh = false
        cloudPlaylistsFailed = false; cloudPlaylistsHasMore = false
        if (collection?.key?.startsWith("cloud:") == true) collection = null
    }
    private fun restoreAccount() {
        if (app.cookie.isNotBlank()) cloudAction {
            account = app.api.account()
            loadCloudPlaylists(reset = true)
        }
    }
    fun refreshCloud() { loadCloudPlaylists(reset = true, refresh = true) }
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
        val generation = cloudGeneration
        val offset = if (reset) 0 else cloudPlaylistOffset
        cloudPlaylistRetryReset = reset; cloudPlaylistRetryRefresh = refresh
        cloudPlaylistsLoading = true; cloudPlaylistsFailed = false
        cloudPlaylistJob = viewModelScope.launch {
            try {
                val page = app.api.playlists(user.userId, offset, refresh = refresh)
                ensureActive()
                if (generation != cloudGeneration || account?.userId != user.userId) return@launch
                cloudPlaylists = ((if (reset) emptyList() else cloudPlaylists) + page.items).distinctBy { it.id }
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
        if (collection?.key?.startsWith("cloud:") == true) collection = null
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
        app.contentResolver.unregisterContentObserver(observer)
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
        super.onCleared()
    }
}
