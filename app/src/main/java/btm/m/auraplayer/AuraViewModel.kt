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
    var songs by mutableStateOf<List<Song>>(emptyList()); private set
    var scanning by mutableStateOf(false); private set
    var hasPermission by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var account by mutableStateOf<CloudAccount?>(null); private set
    var cloudPlaylists by mutableStateOf<List<CloudPlaylist>>(emptyList()); private set
    var cloudBusy by mutableStateOf(false); private set
    var loginMessage by mutableStateOf(""); private set
    var smsRemaining by mutableIntStateOf(0); private set
    var current by mutableStateOf<Song?>(null); private set
    var playing by mutableStateOf(false); private set
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
            current = knownSongs[player.currentMediaItem?.mediaId]
            playing = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
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
            runCatching { future.get() }.onSuccess { controller = it; it.addListener(listener) }
                .onFailure { error = tr("player_connect_error", "无法连接播放服务") }
        }, ContextCompat.getMainExecutor(app))
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
                    val cloud = if (query.isNotBlank() && app.endpoint.isNotBlank()) {
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
        if (!hasPermission) { songs = emptyList(); scanning = false; return }
        scanJob = viewModelScope.launch {
            if (debounce) delay(300)
            scanning = true
            try {
                songs = MediaLibrary(app).scan(settings.value)
                if (error == tr("scan_error", "读取音乐失败，请重新扫描")) error = null
                knownSongs.putAll(songs.associateBy { it.key })
            } catch (e: CancellationException) { throw e }
            catch (_: SecurityException) { hasPermission = false; songs = emptyList() }
            catch (e: Exception) { android.util.Log.e("AuraMediaLibrary", "MediaStore scan failed", e); error = tr("scan_error", "读取音乐失败，请重新扫描") }
            finally { scanning = false }
        }
    }
    fun play(queue: List<Song>, start: Int = 0, shuffle: Boolean = false) {
        if (queue.isEmpty()) return
        val player = controller ?: run { error = tr("player_connecting", "播放服务正在连接，请稍后重试"); return }
        val ordered = if (shuffle) queue.shuffled() else queue
        knownSongs.putAll(ordered.associateBy { it.key })
        val items = ordered.map(::mediaItem)
        player.setMediaItems(items, if (shuffle) 0 else start.coerceIn(0, items.lastIndex), 0)
        player.prepare()
        player.play()
    }
    private fun mediaItem(song: Song) = MediaItem.Builder().setMediaId(song.key).setUri(song.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
            .setArtworkUri(song.artwork.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)).build()).build()

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
    fun togglePlayback() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }
    fun openCollection(value: MusicCollection) { collection = value }
    fun closeCollection() { collection = null }
    fun openDirectory(value: MusicDirectory) { directory = value }
    fun closeDirectory() { directory = null }
    fun openCloudPlaylist(playlist: CloudPlaylist) = cloudAction {
        val tracks = app.api.playlistSongs(playlist.id)
        knownSongs.putAll(tracks.associateBy { it.key })
        collection = MusicCollection("cloud:${playlist.id}", playlist.name, "", tracks, playlist.artwork)
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
            catch (e: Exception) {
                if (e is CloudApiException && e.status == 301) { app.clearCookie(); account = null; cloudPlaylists = emptyList() }
                val message = when {
                    app.endpoint.isBlank() -> tr("configure_api", "请先配置网易云 API 服务地址")
                    e is CloudApiException && e.status == 301 -> tr("session_expired", "登录已过期，请重新登录")
                    e is CloudApiException -> tr("cloud_request_failed", "网易云请求失败") + " (${e.status})"
                    else -> tr("cloud_network_error", "连接失败，请检查服务地址和网络")
                }
                loginMessage = message
                error = message
            } finally { cloudBusy = false }
        }
    }
    private fun restoreAccount() {
        if (app.cookie.isNotBlank() && app.endpoint.isNotBlank()) cloudAction {
            account = app.api.account()
            cloudPlaylists = app.api.playlists(account!!.userId)
        }
    }
    fun refreshCloud() {
        val user = account ?: return
        cloudAction { cloudPlaylists = app.api.playlists(user.userId) }
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
        account = user
        loginMessage = tr("login_success", "登录成功")
        cloudPlaylists = app.api.playlists(user.userId)
    }
    fun logout() {
        cloudGeneration++
        cloudJob?.cancel()
        clearCloudQueue()
        app.clearCookie()
        account = null
        cloudPlaylists = emptyList()
        if (collection?.key?.startsWith("cloud:") == true) collection = null
        cloudBusy = false
        loginMessage = tr("logged_out", "已退出登录")
    }
    private fun clearCloudQueue() {
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
