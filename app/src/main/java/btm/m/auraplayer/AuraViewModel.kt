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

internal const val HEARTBEAT_PLAYBACK_MODE = 3
internal const val ROAMING_PLAYBACK_MODE = 4

class AuraViewModel(application: Application) : AndroidViewModel(application) {
    private data class HeartbeatQueue(
        val items: List<MediaItem>, val index: Int, val positionMs: Long, val content: CloudPlaylistContent?,
    )

    private companion object {
        var originalHeartbeatQueue: HeartbeatQueue? = null
        var roamingModeActive: Boolean = false
        var lastExitedRoamingAtMs: Long = 0L
    }

    val app = application as AuraApplication
    val settings get() = app.settings
    val history get() = app.history
    val songSort = SongSortSettings(application)
    var sortedSongs by mutableStateOf<List<Song>>(emptyList()); private set
    var roamingPreviewSong by mutableStateOf<Song?>(null); private set
    private var roamingBatch: List<Song> = emptyList()
    private var roamingRefreshJob: Job? = null
    private var roamingLoadMoreJob: Job? = null
    private var sortJob: Job? = null
    fun sortSongs(order: SongSort) { songSort.update(order); updateSortedSongs() }
    private fun updateSortedSongs() {
        sortJob?.cancel()
        val source = songs
        val order = songSort.order
        sortJob = viewModelScope.launch { sortedSongs = withContext(Dispatchers.Default) { order.apply(source) } }
    }
    var songs by mutableStateOf<List<Song>>(emptyList()); private set
    private val blockedSongsStore = BlockedSongsStore(application)
    var blockedSongs by mutableStateOf(blockedSongsStore.load()); private set
    var manualImportCount by mutableIntStateOf(ManualImportStore(application).count()); private set
    var scanning by mutableStateOf(false); private set
    var hasPermission by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var account by mutableStateOf<CloudAccount?>(null); private set
    var savedAccounts by mutableStateOf<List<StoredCloudAccount>>(emptyList()); private set
    var cloudPlaylists by mutableStateOf<List<CloudPlaylist>>(emptyList()); private set
    var cloudPlaylistsLoading by mutableStateOf(false); private set
    var cloudPlaylistsFailed by mutableStateOf(false); private set
    var cloudPlaylistsHasMore by mutableStateOf(false); private set
    var cloudHomeItems by mutableStateOf<List<CloudHomeItem>>(emptyList()); private set
    var cloudDiscoverAlbums by mutableStateOf<List<CloudHomeItem>>(emptyList()); private set
    var cloudHomeCharts by mutableStateOf<List<NeteaseChartItem>>(NeteaseChartCatalog.defaultHomeItems()); private set
    var cloudChartsByGroup by mutableStateOf<Map<NeteaseChartGroup, List<NeteaseChartItem>>>(NeteaseChartCatalog.defaultGroupedItems()); private set
    var cloudChartsLoading by mutableStateOf(false); private set
    var cloudChartsFailed by mutableStateOf(false); private set
    var cloudRecommendedPlaylists by mutableStateOf<List<CloudHomeItem>>(emptyList()); private set
    var cloudFollowedAlbums by mutableStateOf<List<CloudHomeItem>>(emptyList()); private set
    var cloudHomeLoading by mutableStateOf(false); private set
    var cloudHomeFailed by mutableStateOf(false); private set
    var cloudRecentSongs by mutableStateOf<List<Song>>(emptyList()); private set
    var cloudRankPreviewSongs by mutableStateOf<List<Song>>(emptyList()); private set
    var cloudDailySongs by mutableStateOf<List<Song>>(emptyList()); private set
    var cloudPublicBanners by mutableStateOf<List<CloudHomeItem>>(emptyList()); private set
    var heartbeatLoading by mutableStateOf(false); private set
    var listeningRankWeek by mutableStateOf(false); private set
    private var cloudHomeJob: Job? = null
    private var cloudChartsJob: Job? = null
    private var cloudChartsHydrated = false
    private var cloudHomePlayJob: Job? = null
    private var dailySongsJob: Deferred<List<Song>?>? = null
    private var dailySongsUserId: Long? = null
    private var cloudHomeUserId: Long? = null
    private var rankPreviewJob: Job? = null
    private var heartbeatJob: Job? = null
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
    var lyricTranslationSource by mutableStateOf(playbackPreferences.translationSource); private set
    var hasDualTranslationSources by mutableStateOf(false); private set
    private var resolvedLyricsVariant: btm.m.auraplayer.lyrics.ResolvedLyrics? = null
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
    var lyricShareInitialIndex by mutableIntStateOf(-1); private set
    fun prepareLyricShare(index: Int) { lyricShareInitialIndex = index }
    var lyricsLoading by mutableStateOf(false); private set
    private val lyricRepository = btm.m.auraplayer.lyrics.LyricsRepository(
        context = application,
        llmConfigProvider = { app.llmTranslation.current },
        llmApiKeyProvider = { app.llmTranslation.apiKeyFor() },
    )
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
    private var searchRevision = 0
    var cloudLikedIds by mutableStateOf<Set<Long>>(emptySet()); private set
    var cloudLikesReady by mutableStateOf(false); private set
    private var likesJob: Job? = null
    private val liking = mutableSetOf<Long>()
    var followedArtistStates by mutableStateOf<Map<Long, Boolean>>(emptyMap()); private set
    private val followingArtists = mutableSetOf<Long>()
    var subscribedAlbums by mutableStateOf<List<CatalogAlbum>>(emptyList()); private set
    var subscribedAlbumsLoading by mutableStateOf(false); private set
    var subscribedAlbumsFailed by mutableStateOf(false); private set
    var subscribedAlbumsHasMore by mutableStateOf(false); private set
    private var subscribedAlbumOffset = 0
    private var subscribedAlbumJob: Job? = null
    private val albumTracksCache = object : LinkedHashMap<Long, List<Song>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Song>>?) = size > 256
    }
    private val attemptedAlbumTrackIds = mutableSetOf<Long>()
    private var cloudGeneration = 0
    val cloudEnabled get() = !settings.value.cloudDisabled
    var qqAccount by mutableStateOf<CloudAccount?>(null); private set
    var savedQqAccounts by mutableStateOf<List<StoredCloudAccount>>(emptyList()); private set
    var qqPlaylists by mutableStateOf<List<CloudPlaylist>>(emptyList()); private set
    var qqRecommended by mutableStateOf<List<CloudPlaylist>>(emptyList()); private set
    var qqLoading by mutableStateOf(false); private set
    var qqBusy by mutableStateOf(false); private set
    var qqFailed by mutableStateOf(false); private set
    var qqMessage by mutableStateOf(""); private set
    private var qqJob: Job? = null
    private var qqAccountJob: Job? = null
    private var qqGeneration = 0
    fun refreshQq() {
        if (!cloudEnabled || qqLoading) return
        val generation = qqGeneration
        val api = qqApiSnapshot()
        qqLoading = true; qqFailed = false
        qqJob = viewModelScope.launch {
            try {
                val hasUser = qqAccount != null || app.qq.userUin().isNotBlank() || app.qqCookie.isNotBlank()
                val mine = async { if (hasUser) runCatching { api.playlists().items }.getOrNull() else emptyList() }
                val rec = async { runCatching { api.recommendedPlaylists() }.getOrNull() }
                val mineList = mine.await(); val recList = rec.await()
                ensureActive()
                if (generation != qqGeneration) return@launch
                if (mineList == null && recList == null) { qqFailed = true; qqMessage = tr("qq_request_failed", "QQ音乐请求失败，请检查网络或服务地址") }
                else { qqPlaylists = mineList.orEmpty(); qqRecommended = recList.orEmpty() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == qqGeneration) qqFailed = true }
            finally { if (generation == qqGeneration) qqLoading = false }
        }
    }
    fun completeQqOfficialLogin(sessionCookie: String, onSuccess: () -> Unit = {}) {
        if (!cloudEnabled || qqBusy) return
        qqAccountJob?.cancel(); qqJob?.cancel(); qqLoading = false
        val generation = ++qqGeneration
        qqBusy = true
        qqAccountJob = viewModelScope.launch {
            try {
                val user = QqMusicApi({ "" }, { "" }, { sessionCookie }).account()
                ensureActive()
                if (generation != qqGeneration) return@launch
                clearQqAccountContent()
                if (app.qqEndpoint.isNotBlank()) app.useQqOfficialService()
                app.saveQqAccount(user, sessionCookie)
                syncSavedQqAccounts()
                qqAccount = user
                qqMessage = tr("login_success", "登录成功")
                refreshQq()
                onSuccess()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation != qqGeneration) return@launch
                qqMessage = if (e is CloudApiException && e.status == 301) tr("session_expired", "登录已过期，请重新登录")
                else tr("qq_login_failed", "QQ音乐登录验证失败，请重试")
            } finally { if (generation == qqGeneration) qqBusy = false }
        }
    }
    fun logoutQq() {
        resetQqAccountWork()
        app.clearQqSession()
        syncSavedQqAccounts()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        qqAccount = null; qqPlaylists = emptyList()
        qqMessage = ""
    }
    fun useQqOfficialService() {
        val changed = app.qqEndpoint.isNotBlank()
        if (changed) resetQqAccountWork()
        app.useQqOfficialService()
        syncSavedQqAccounts()
        if (changed) { qqAccount = null; qqPlaylists = emptyList() }
        qqMessage = tr("qq_official_mode", "已切换为QQ音乐官方连接")
        refreshQq()
    }
    fun configureQq(endpoint: String, uin: String, cookie: String): Boolean = runCatching {
        val normalized = endpoint.trim().takeIf { it.isNotEmpty() }?.let { NeteaseApi.normalizeEndpoint(it) }.orEmpty()
        resetQqAccountWork()
        app.saveQq(normalized, uin, cookie)
        syncSavedQqAccounts()
        qqAccount = null
        qqMessage = tr("api_saved", "服务地址已保存")
        if (cookie.isNotBlank() || uin.isNotBlank()) validateQqAccount()
        refreshQq()
    }.onFailure { qqMessage = tr("invalid_api", "请输入 HTTPS 地址；自建局域网服务可使用 HTTP") }.isSuccess

    private fun syncSavedQqAccounts() { savedQqAccounts = app.savedQqAccounts() }

    private fun qqApiSnapshot(): QqMusicApi {
        val endpoint = app.qqEndpoint; val uin = app.qqUin; val cookie = app.qqCookie
        return QqMusicApi({ endpoint }, { uin }, { cookie })
    }

    private fun validateQqAccount() {
        if (!cloudEnabled) return
        qqAccountJob?.cancel()
        val generation = qqGeneration
        val cookie = app.qqCookie
        val api = qqApiSnapshot()
        qqAccountJob = viewModelScope.launch {
            try {
                val user = api.account()
                ensureActive()
                if (generation != qqGeneration) return@launch
                if (cookie.isNotBlank()) app.saveQqAccount(user, cookie)
                syncSavedQqAccounts()
                qqAccount = user
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == qqGeneration && e is CloudApiException && e.status == 301) {
                    resetQqAccountWork()
                    app.clearQqSession()
                    syncSavedQqAccounts()
                    qqAccount = null
                    qqMessage = tr("session_expired", "登录已过期，请重新登录")
                }
            }
        }
    }

    fun switchQqCloudAccount(userId: Long) {
        if (!cloudEnabled || savedQqAccounts.none { it.userId == userId } ||
            qqAccount?.userId == userId && app.qqCookie.isNotBlank()) return
        resetQqAccountWork()
        qqAccount = app.switchQqAccount(userId)?.toAccount()
        syncSavedQqAccounts()
        qqMessage = ""
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        refreshQq()
        validateQqAccount()
    }

    fun deleteQqCloudAccount(userId: Long) {
        val wasActive = app.activeStoredQqAccount()?.userId == userId
        if (wasActive) resetQqAccountWork()
        val next = app.removeQqAccount(userId)
        syncSavedQqAccounts()
        if (wasActive) {
            qqAccount = next?.toAccount()
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            qqMessage = if (next == null) tr("logged_out", "已退出登录") else ""
            refreshQq()
            if (next != null) validateQqAccount()
        }
    }

    private fun resetQqAccountWork() {
        qqGeneration++
        qqAccountJob?.cancel(); qqJob?.cancel()
        qqBusy = false; qqLoading = false; qqFailed = false
        clearQqAccountContent()
    }

    private fun clearQqAccountContent() {
        qqPlaylists = emptyList(); qqRecommended = emptyList()
        val ids = collectionDestinations.filterValues { it.cloud?.playlist?.provider == PROVIDER_QQ }.keys.toSet()
        ids.forEach { collectionDestinations.remove(it)?.cloud?.job?.cancel() }
        cloudContents.filterValues { it.playlist.provider == PROVIDER_QQ }.keys.toList().forEach { cloudContents.remove(it)?.job?.cancel() }
        if (cloudQueue?.playlist?.provider == PROVIDER_QQ) { cloudQueue?.job?.cancel(); cloudQueue = null }
        if (activeCollectionId in ids) {
            val previous = collectionDestinations.maxByOrNull { it.key }?.value
            collection = previous?.collection; cloudCollection = previous?.cloud; activeCollectionId = previous?.id ?: 0L
        }
        controller?.let { player ->
            if (player.currentMediaItem?.mediaId?.startsWith("cloud:qq:") == true) player.pause()
            for (index in player.mediaItemCount - 1 downTo 0) {
                if (player.getMediaItemAt(index).mediaId.startsWith("cloud:qq:")) player.removeMediaItem(index)
            }
        }
        if (searchCloud && settings.value.cloudSearchSource == "qq") submitSearch()
    }
    /** Pure local mode: stop every cloud job, drop cloud queue entries and forget in-memory cloud state. */
    private fun shutdownCloud() {
        leaveHeartbeat()
        cloudGeneration++
        cloudJob?.cancel(); qqGeneration++; qqAccountJob?.cancel(); qqJob?.cancel(); qqLoading = false; qqBusy = false
        queries.value.takeIf { it.cloud }?.let { searchCloud = false; submitSearch() }
        controller?.let { player ->
            val keep = (0 until player.mediaItemCount).filter { !songFor(player.getMediaItemAt(it)).cloud }
            if (keep.size != player.mediaItemCount) {
                if (current?.cloud == true) player.stop()
                for (index in (0 until player.mediaItemCount).reversed()) if (index !in keep) player.removeMediaItem(index)
            }
        }
        resetCloudPaging()
        cloudPlaylists = emptyList(); qqPlaylists = emptyList(); qqRecommended = emptyList(); cloudBusy = false
    }
    private var controller: MediaController? = null
    private val knownSongs = mutableMapOf<String, Song>()
    private val artistArtworkCache = mutableStateMapOf<Long, String>()
    private val songArtistCache = mutableMapOf<String, List<SongArtist>>()
    private val cloudCreditsSongSeeds = mutableMapOf<Long, Song>()
    private val songCreditsCache = mutableMapOf<Long, SongCreditsData>()
    private val prefetchedArtists = mutableSetOf<Long>()
    private var lastCurrentKey: String? = null
    private val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            current = player.currentMediaItem?.let(::songFor)
            current?.takeIf { it.key != lastCurrentKey }?.let { song ->
                lastCurrentKey = song.key
                if (song.netease) viewModelScope.launch { resolveSongArtists(song) }
            }
            queue = (0 until player.mediaItemCount).map { songFor(player.getMediaItemAt(it)) }
            queueIndex = player.currentMediaItemIndex
            playbackMode = when {
                originalHeartbeatQueue != null -> HEARTBEAT_PLAYBACK_MODE
                roamingModeActive -> ROAMING_PLAYBACK_MODE
                player.repeatMode == Player.REPEAT_MODE_ONE -> 2
                player.shuffleModeEnabled -> 1
                else -> 0
            }
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
            cloudQueue?.takeIf { originalHeartbeatQueue == null && !roamingModeActive && !it.failed && player.currentMediaItemIndex >= player.mediaItemCount - 5 }
                ?.let { loadCloudSongs(it) }
            if (roamingModeActive && player.currentMediaItemIndex >= player.mediaItemCount - 2) {
                loadMoreRoamingSongs(player)
            }
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
                    streamInfo = current?.takeIf { it.netease }?.let { btm.m.auraplayer.playback.PlaybackStreams.records[it.id] }
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
            app.llmTranslation.revision.drop(1).collect {
                lyricRepository.clear()
                btm.m.auraplayer.lyrics.LlmTranslator.clearMemoryCache()
                current?.let { activeSong ->
                    lyricKey = null
                    loadLyrics(activeSong)
                }
            }
        }
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            queries.debounce(300).collectLatest { request ->
                searchJob?.cancel()
                searching = request.query.isNotBlank()
                try {
                    if (request.query.isBlank()) { searchHits = emptyList(); searchResults = emptyList(); searchHasMore = false }
                    else {
                        val page = if (request.cloud) cloudSearch(request.query, request.category, 0, request.source) else localSearch(request)
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
    fun localSong(uri: String): Song = songs.firstOrNull { it.uri == uri }
        ?: queue.firstOrNull { !it.cloud && it.uri == uri }
        ?: current?.takeIf { !it.cloud && it.uri == uri }
        ?: Song(0, "", "", "", uri = uri)

    fun localTagsSaved(song: Song, tags: btm.m.auraplayer.tags.AudioTagDocument) {
        val freshVersion = System.currentTimeMillis()
        val updatedArtwork = if (!song.cloud) {
            LocalAudioArtwork.bumpVersion(song.artwork, song.uri, freshVersion)
        } else {
            song.artwork
        }
        val updated = song.copy(title = tags[btm.m.auraplayer.tags.AudioTagField.TITLE],
            artist = tags[btm.m.auraplayer.tags.AudioTagField.ARTIST], album = tags[btm.m.auraplayer.tags.AudioTagField.ALBUM],
            composer = tags[btm.m.auraplayer.tags.AudioTagField.COMPOSER], albumArtist = tags[btm.m.auraplayer.tags.AudioTagField.ALBUM_ARTIST],
            year = tags[btm.m.auraplayer.tags.AudioTagField.YEAR].take(4).toIntOrNull() ?: 0,
            track = tags[btm.m.auraplayer.tags.AudioTagField.TRACK].toIntOrNull() ?: 0,
            artwork = updatedArtwork,
            modified = freshVersion / 1000L,
            artists = emptyList())
        knownSongs[song.key] = updated
        songs = songs.map { if (it.key == song.key) updated else it }
        queue = queue.map { if (it.key == song.key) updated else it }
        searchResults = searchResults.map { if (it.key == song.key) updated else it }
        songArtistCache.remove(song.key)
        lyricRepository.invalidate(song.key)
        if (current?.key == song.key) { current = updated; lyricKey = null; loadLyrics(updated) }
        controller?.let { player ->
            val playingNow = player.isPlaying
            val positionNow = player.currentPosition.coerceAtLeast(0)
            val currentWasEdited = player.currentMediaItem?.mediaId == song.key
            val currentIndex = player.currentMediaItemIndex
            // Tag sizes can change the byte offsets of the current audio stream. Reopen it
            // after writing, retaining its position and playback intent.
            if (currentWasEdited) player.stop()
            for (index in 0 until player.mediaItemCount) if (player.getMediaItemAt(index).mediaId == song.key) {
                player.replaceMediaItem(index, mediaItem(updated))
            }
            if (currentWasEdited) {
                player.prepare()
                player.seekTo(currentIndex, positionNow)
                if (playingNow) player.play()
            }
        }
        updateSortedSongs()
        refreshLibrary()
    }
    fun updateSettings(transform: (Appearance) -> Appearance) {
        val before = settings.value
        settings.update(transform)
        val after = settings.value
        if (before.heartbeatEnabled && !after.heartbeatEnabled) leaveHeartbeat()
        if (!before.cloudDisabled && after.cloudDisabled) shutdownCloud()
        else if (before.cloudDisabled && !after.cloudDisabled) { restoreAccount(); refreshQq() }
        if (before.cloudSearchSource != after.cloudSearchSource && searchCloud) {
            if (after.cloudSearchSource == "qq" && searchCategory !in listOf(SearchCategory.ALL, SearchCategory.SONG)) searchCategory = SearchCategory.ALL
            submitSearch()
        }
        if (before.whitelist != after.whitelist || before.blacklist != after.blacklist || before.filenameBlockRules != after.filenameBlockRules ||
            before.durationFilter != after.durationFilter || before.minimumSeconds != after.minimumSeconds) refreshLibrary(debounce = true)
    }
    fun refreshLibrary(debounce: Boolean = false, onComplete: ((Int) -> Unit)? = null) {
        hasPermission = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            if (debounce) delay(300)
            scanning = true
            try {
                val blockedUris = withContext(Dispatchers.IO) { blockedSongsStore.blockedUris() }
                val scanned = if (hasPermission) MediaLibrary(app).scan(settings.value, blockedUris) else emptyList()
                val manual = withContext(Dispatchers.IO) { ManualImportStore(app).load() }
                manualImportCount = manual.size
                val merged = mergeSongLists(scanned, manual).filterNot { it.uri in blockedUris }
                songs = merged
                updateSortedSongs()
                if (error == tr("scan_error", "读取音乐失败，请重新扫描")) error = null
                knownSongs.putAll(merged.associateBy { it.key })
                onComplete?.invoke(merged.size)
            } catch (e: CancellationException) { throw e }
            catch (_: SecurityException) {
                hasPermission = false
                val blockedUris = withContext(Dispatchers.IO) { blockedSongsStore.blockedUris() }
                val manual = withContext(Dispatchers.IO) { ManualImportStore(app).load() }.filterNot { it.uri in blockedUris }
                manualImportCount = manual.size
                songs = manual
                updateSortedSongs()
                knownSongs.putAll(manual.associateBy { it.key })
                onComplete?.invoke(manual.size)
            }
            catch (e: Exception) { android.util.Log.e("AuraMediaLibrary", "MediaStore scan failed", e); error = tr("scan_error", "读取音乐失败，请重新扫描") }
            finally { scanning = false }
        }
    }

    fun importManualSongs(drafts: List<ManualAudioDraft>, onComplete: (Int) -> Unit = {}) {
        if (drafts.isEmpty()) {
            onComplete(0)
            return
        }
        viewModelScope.launch {
            val (addedCount, manualSongs, blockedUris) = withContext(Dispatchers.IO) {
                val store = ManualImportStore(app)
                val count = store.addAll(drafts)
                Triple(count, store.load(), blockedSongsStore.blockedUris())
            }
            manualImportCount = manualSongs.size
            val merged = mergeSongLists(songs, manualSongs).filterNot { it.uri in blockedUris }
            songs = merged
            updateSortedSongs()
            knownSongs.putAll(merged.associateBy { it.key })
            onComplete(addedCount)
        }
    }

    fun scanFolder(treeUri: android.net.Uri, onComplete: (Int) -> Unit = {}) {
        hasPermission = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanning = true
            try {
                val blockedUris = withContext(Dispatchers.IO) { blockedSongsStore.blockedUris() }
                val result = MediaLibrary(app).scanTree(treeUri, settings.value, blockedUris)
                val manual = withContext(Dispatchers.IO) { ManualImportStore(app).load() }
                manualImportCount = manual.size
                val folderUris = result.folderSongs.mapTo(hashSetOf()) { it.uri }
                val retained = songs.filterNot { existing ->
                    if (existing.cloud || existing.uri in folderUris || existing.uri in blockedUris) return@filterNot true
                    if (result.volume.isNotBlank() && result.relativePrefix.isNotBlank()) {
                        val sameVolume = existing.volume.equals(result.volume, ignoreCase = true)
                        val normPath = LibraryFilter.normalize(existing.relativePath)
                        sameVolume && normPath.startsWith(result.relativePrefix, ignoreCase = true)
                    } else if (result.volume.isNotBlank() && result.relativePrefix.isEmpty()) {
                        existing.volume.equals(result.volume, ignoreCase = true)
                    } else {
                        false
                    }
                }
                val merged = mergeSongLists(retained + result.folderSongs, manual).filterNot { it.uri in blockedUris }
                songs = merged
                updateSortedSongs()
                if (error == tr("scan_error", "读取音乐失败，请重新扫描")) error = null
                knownSongs.putAll(merged.associateBy { it.key })
                onComplete(result.folderSongs.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("AuraMediaLibrary", "Partial folder scan failed", e)
                error = tr("scan_error", "读取音乐失败，请重新扫描")
            } finally {
                scanning = false
            }
        }
    }

    fun blockSong(song: Song) {
        if (song.cloud || song.uri.isBlank()) return
        songs = songs.filterNot { it.uri == song.uri }
        updateSortedSongs()
        searchResults = searchResults.filterNot { !it.cloud && it.uri == song.uri }
        searchHits = searchHits.filterNot { !it.cloud && it.song?.uri == song.uri }
        controller?.let { player ->
            if (player.currentMediaItem?.mediaId == song.key) player.pause()
            for (index in player.mediaItemCount - 1 downTo 0) {
                if (player.getMediaItemAt(index).mediaId == song.key) player.removeMediaItem(index)
            }
        }
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                val fileName = blockedSongsStore.resolveFileName(song)
                blockedSongsStore.add(song, fileName)
            }
            blockedSongs = updated
        }
    }

    fun unblockSong(uri: String) {
        if (uri.isBlank()) return
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                blockedSongsStore.remove(uri)
            }
            blockedSongs = updated
            refreshLibrary(debounce = false)
        }
    }

    fun resolveBlockedSongFileName(uri: String) {
        if (uri.isBlank()) return
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                val resolved = blockedSongsStore.resolveFileNameFromUri(uri)
                if (resolved.isNotBlank()) blockedSongsStore.updateFileName(uri, resolved) else null
            }
            if (updated != null) blockedSongs = updated
        }
    }

    private fun mergeSongLists(primary: List<Song>, secondary: List<Song>): List<Song> {
        val byUri = linkedMapOf<String, Song>()
        for (song in primary) byUri[song.uri] = song
        for (song in secondary) byUri.putIfAbsent(song.uri, song)
        val collator = java.text.Collator.getInstance()
        return byUri.values.sortedWith { first, second -> collator.compare(first.title, second.title) }
    }
    fun play(queue: List<Song>, start: Int = 0, shuffle: Boolean = false, sourceId: Long = 0L) {
        if (queue.isEmpty()) return
        val player = controller ?: run { error = tr("player_connecting", "播放服务正在连接，请稍后重试"); return }
        cancelHeartbeat()
        originalHeartbeatQueue = null
        roamingModeActive = false
        lastExitedRoamingAtMs = 0L
        roamingRefreshJob?.cancel()
        roamingLoadMoreJob?.cancel()
        if (playbackMode == HEARTBEAT_PLAYBACK_MODE || playbackMode == ROAMING_PLAYBACK_MODE) playbackMode = 0
        cloudQueue = null
        knownSongs.putAll(queue.associateBy { it.key })
        val items = queue.map { mediaItem(it, sourceId) }
        val startIndex = if (shuffle) items.indices.random() else start.coerceIn(0, items.lastIndex)
        if (shuffle) {
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.shuffleModeEnabled = true
            playbackMode = 1
        } else {
            setListRepeat(player)
            playbackMode = 0
        }
        player.setMediaItems(items, startIndex, 0)
        player.prepare()
        player.play()
    }
    private fun mediaItem(song: Song): MediaItem = mediaItem(song, 0L)
    private fun mediaItem(song: Song, sourceId: Long) = MediaItem.Builder().setMediaId(song.key).setUri(if (song.cloud) android.net.Uri.parse(song.uri).buildUpon().appendQueryParameter("quality", quality.level).build().toString() else song.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
            .setExtras(android.os.Bundle().apply {
                putLong("albumId", song.albumId); putLong("artistId", song.artistId)
                putLong("sourceId", if (sourceId > 0L) sourceId else song.albumId)
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
        lyricKey = song?.key; lyricJob?.cancel(); lyrics = emptyList(); resolvedLyricsVariant = null; hasDualTranslationSources = false; lyricsLoading = song != null
        if (song == null) return
        lyricJob = viewModelScope.launch {
            try {
                lyricRepository.resolveLyrics(song).collect { resolved ->
                    resolvedLyricsVariant = resolved
                    hasDualTranslationSources = resolved.hasDualTranslationSources
                    lyrics = resolved.select(lyricTranslationSource)
                    if (lyrics.isNotEmpty()) lyricsLoading = false
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
    fun changeLyricTranslationSource(source: btm.m.auraplayer.playback.LyricTranslationSource) {
        lyricTranslationSource = source
        playbackPreferences.translationSource = source
        resolvedLyricsVariant?.let { lyrics = it.select(source) }
    }
    fun notifyLlmTranslationCacheChanged(songKey: String? = null) {
        btm.m.auraplayer.lyrics.LlmTranslator.clearMemoryCache()
        if (songKey == null) lyricRepository.clear() else lyricRepository.invalidate(songKey)
        current?.let { activeSong ->
            if (songKey == null || activeSong.key == songKey) {
                lyricKey = null
                loadLyrics(activeSong)
            }
        }
    }
    fun seekTo(value: Long) { controller?.seekTo(value.coerceIn(0L, duration.coerceAtLeast(0))); position = value.coerceIn(0L, duration.coerceAtLeast(0)) }
    fun queueTo(index: Int) { controller?.let { if (index in 0 until it.mediaItemCount) { it.seekToDefaultPosition(index); it.play() } } }
    fun removeQueue(index: Int) { controller?.let { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) } }
    fun moveQueue(from: Int, to: Int) { controller?.let {
        if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount && from != to) it.moveMediaItem(from, to)
    } }
    fun cyclePlaybackMode() {
        val player = controller ?: return
        when {
            originalHeartbeatQueue != null -> leaveHeartbeat()
            heartbeatLoading -> cancelHeartbeat().also { setListRepeat(player) }
            roamingModeActive -> {
                roamingModeActive = false
                lastExitedRoamingAtMs = android.os.SystemClock.elapsedRealtime()
                playbackMode = 0
                setListRepeat(player)
            }
            playbackMode == 0 -> { player.repeatMode = Player.REPEAT_MODE_ALL; player.shuffleModeEnabled = true }
            playbackMode == 1 -> { player.shuffleModeEnabled = false; player.repeatMode = Player.REPEAT_MODE_ONE }
            lastExitedRoamingAtMs > 0L && android.os.SystemClock.elapsedRealtime() - lastExitedRoamingAtMs <= 10_000L -> {
                lastExitedRoamingAtMs = 0L
                roamingModeActive = true
                playbackMode = ROAMING_PLAYBACK_MODE
                setListRepeat(player)
            }
            cloudEnabled && settings.value.heartbeatEnabled && account != null && current?.netease == true -> {
                lastExitedRoamingAtMs = 0L
                enterHeartbeat(player)
            }
            else -> {
                lastExitedRoamingAtMs = 0L
                setListRepeat(player)
            }
        }
    }

    private fun setListRepeat(player: Player) {
        player.shuffleModeEnabled = false
        player.repeatMode = Player.REPEAT_MODE_ALL
    }
    fun refreshQualities() {
        val song = current?.takeIf { it.netease } ?: return
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
    fun previous() { controller?.seekToPrevious() }
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
        if (!song.netease || downloadBusy) { if (song.isQq) complete(false); return }
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
    fun rememberSongForCredits(song: Song) {
        if (song.cloud && song.id > 0L) cloudCreditsSongSeeds[song.id] = song
    }
    fun findCloudSongForCredits(songId: Long): Song? {
        if (songId <= 0L) return null
        return cloudCreditsSongSeeds[songId]
            ?: current?.takeIf { it.cloud && it.id == songId }
            ?: knownSongs["cloud:$songId"]
            ?: queue.firstOrNull { it.cloud && it.id == songId }
            ?: collection?.songs?.firstOrNull { it.cloud && it.id == songId }
            ?: cloudCollection?.songs?.firstOrNull { it.cloud && it.id == songId }
            ?: cloudRecentSongs.firstOrNull { it.cloud && it.id == songId }
            ?: cloudRankPreviewSongs.firstOrNull { it.cloud && it.id == songId }
    }
    suspend fun loadSongCredits(songId: Long, forceRefresh: Boolean = false): SongCreditsData {
        if (!forceRefresh) songCreditsCache[songId]?.let { return it }
        if (findCloudSongForCredits(songId)?.isQq == true) throw java.io.IOException("UNSUPPORTED")
        val data = app.api.songCredits(songId, findCloudSongForCredits(songId))
        songCreditsCache[songId] = data
        return data
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
        val cloud = removed?.cloud
        if (cloud != null && collectionDestinations.values.none { it.cloud === cloud }) {
            cloud.albumsJob?.cancel()
            cloud.albumTracksJob?.cancel()
            if (cloud !== cloudQueue && cloud.source != "playlist") cloud.job?.cancel()
        }
        val previous = collectionDestinations.maxByOrNull { it.key }?.value
        collection = previous?.collection; cloudCollection = previous?.cloud; activeCollectionId = previous?.id ?: 0L
    }
    fun openDirectory(value: MusicDirectory) { directory = value }
    fun closeDirectory() { directory = null }
    private fun contentKey(playlist: CloudPlaylist) = if (playlist.provider == PROVIDER_QQ) -playlist.id else playlist.id
    fun openCloudPlaylist(playlist: CloudPlaylist) {
        if (!cloudEnabled) return
        val contentKey = contentKey(playlist)
        cloudContents[contentKey]?.let { old ->
            if (!old.loading && (old.playlist.count != playlist.count ||
                    old.loadedAt > 0 && System.currentTimeMillis() - old.loadedAt > CloudMetadataCache.FRESH_MS)) {
                cloudContents.remove(contentKey)
            }
        }
        val content = cloudContents.getOrPut(contentKey) { CloudPlaylistContent(playlist) }
        // Keep only a few visited playlists in RAM. Their pages remain in the disk cache.
        while (cloudContents.size > 8) {
            val old = cloudContents.entries.firstOrNull { it.value !== content && it.value !== cloudQueue } ?: break
            old.value.job?.cancel(); cloudContents.remove(old.key)
        }
        pushCollection(MusicCollection(if (playlist.provider == PROVIDER_QQ) "cloud:qq:${playlist.id}" else "cloud:${playlist.id}", playlist.name, "", content.songs, playlist.artwork), content)
        if (content.songs.isEmpty() && !content.failed) loadCloudSongs(content)
    }

    fun openCloudHomeItem(item: CloudHomeItem) {
        if (item.actionId <= 0L) return
        when (item.actionType.ifBlank { item.kind }) {
            "playlist" -> openCloudPlaylist(CloudPlaylist(item.actionId, item.title, 0, item.artwork))
            "album" -> openSongAlbum(Song(item.actionId, "", "", item.title, albumId = item.actionId, uri = "", artwork = item.artwork, cloud = true))
            "artist" -> openSongArtist(Song(item.actionId, "", item.title, "", artistId = item.actionId, uri = "", artwork = item.artwork, cloud = true))
            "song" -> viewModelScope.launch { runCatching { app.api.songDetail(item.actionId) }.getOrNull()?.let { play(listOf(it)) } }
        }
    }
    fun playCloudHomeItem(item: CloudHomeItem) {
        if (!cloudEnabled) return
        when (item.actionType.ifBlank { item.kind }) {
            "playlist" -> {
                val playlist = CloudPlaylist(item.actionId, item.title, 0, item.artwork)
                val key = contentKey(playlist)
                val cached = cloudContents[key]?.takeIf {
                    it.songs.isNotEmpty() && !(it.loadedAt > 0 && System.currentTimeMillis() - it.loadedAt > CloudMetadataCache.FRESH_MS)
                }
                if (cached != null) {
                    playCollection(cached.songs, 0, cached)
                    return
                }
                val content = CloudPlaylistContent(playlist)
                cloudContents[key] = content
                val generation = cloudGeneration
                cloudHomePlayJob?.cancel()
                cloudHomePlayJob = viewModelScope.launch {
                    try {
                        val page = app.api.playlistSongs(playlist.id)
                        ensureActive()
                        if (generation != cloudGeneration) return@launch
                        content.songs = page.items
                        content.offset = page.nextOffset
                        content.loadedAt = System.currentTimeMillis()
                        content.hasMore = page.hasMore
                        page.total?.let { content.total = it }
                        if (page.total == null) content.total = maxOf(content.total, content.songs.size)
                        knownSongs.putAll(page.items.associateBy { it.key })
                        if (page.items.isNotEmpty()) playCollection(page.items, 0, content)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (generation == cloudGeneration) cloudFailure(e) }
                }
            }
            "album" -> {
                val generation = cloudGeneration
                cloudHomePlayJob?.cancel()
                cloudHomePlayJob = viewModelScope.launch {
                    try {
                        val catalog = app.api.albumPage(item.actionId)
                        ensureActive()
                        if (generation == cloudGeneration && catalog.page.items.isNotEmpty()) play(catalog.page.items, 0)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (generation == cloudGeneration) cloudFailure(e) }
                }
            }
            "song" -> {
                val generation = cloudGeneration
                cloudHomePlayJob?.cancel()
                cloudHomePlayJob = viewModelScope.launch {
                    runCatching { app.api.songDetail(item.actionId) }.getOrNull()?.let {
                        if (generation == cloudGeneration) play(listOf(it))
                    }
                }
            }
        }
    }
    fun openCloudChart(chart: NeteaseChartItem, title: String) {
        if (!cloudEnabled) return
        val resolvedTitle = title.ifBlank { chart.spec.defaultTitle }
        when (chart.spec.source) {
            NeteaseChartSource.PLAYLIST -> {
                openCloudPlaylist(CloudPlaylist(chart.spec.id, resolvedTitle, 0, chart.artwork))
            }
            NeteaseChartSource.STYLE_TAG -> {
                val playlist = CloudPlaylist(chart.spec.id, resolvedTitle, 0, chart.artwork)
                val cacheKey = -(1_000_000_000_000L + chart.spec.id)
                cloudContents[cacheKey]?.let { old ->
                    if (!old.loading && old.loadedAt > 0 && System.currentTimeMillis() - old.loadedAt > CloudMetadataCache.FRESH_MS) {
                        cloudContents.remove(cacheKey)
                    }
                }
                val content = cloudContents.getOrPut(cacheKey) { CloudPlaylistContent(playlist, source = "style_chart") }
                while (cloudContents.size > 8) {
                    val old = cloudContents.entries.firstOrNull { it.value !== content && it.value !== cloudQueue } ?: break
                    old.value.job?.cancel(); cloudContents.remove(old.key)
                }
                pushCollection(
                    MusicCollection("cloud:style:${chart.spec.id}", resolvedTitle, "", content.songs, chart.artwork),
                    content,
                )
                if (content.songs.isEmpty() && !content.failed) loadCloudSongs(content)
            }
        }
    }

    fun playCloudChart(chart: NeteaseChartItem, title: String) {
        if (!cloudEnabled) return
        val resolvedTitle = title.ifBlank { chart.spec.defaultTitle }
        when (chart.spec.source) {
            NeteaseChartSource.PLAYLIST -> {
                playCloudHomeItem(
                    CloudHomeItem(
                        id = chart.spec.id,
                        title = resolvedTitle,
                        artwork = chart.artwork,
                        kind = "playlist",
                        actionType = "playlist",
                        actionId = chart.spec.id,
                    ),
                )
            }
            NeteaseChartSource.STYLE_TAG -> {
                val playlist = CloudPlaylist(chart.spec.id, resolvedTitle, 0, chart.artwork)
                val cacheKey = -(1_000_000_000_000L + chart.spec.id)
                val cached = cloudContents[cacheKey]?.takeIf {
                    it.songs.isNotEmpty() && !(it.loadedAt > 0 && System.currentTimeMillis() - it.loadedAt > CloudMetadataCache.FRESH_MS)
                }
                if (cached != null) {
                    playCollection(cached.songs, 0, cached)
                    return
                }
                val content = CloudPlaylistContent(playlist, source = "style_chart")
                cloudContents[cacheKey] = content
                val generation = cloudGeneration
                cloudHomePlayJob?.cancel()
                cloudHomePlayJob = viewModelScope.launch {
                    try {
                        val page = app.api.styleChartSongs(chart.spec.id)
                        ensureActive()
                        if (generation != cloudGeneration) return@launch
                        content.songs = page.items
                        content.offset = page.nextOffset
                        content.loadedAt = System.currentTimeMillis()
                        content.hasMore = page.hasMore
                        page.total?.let { content.total = it }
                        if (page.total == null) content.total = maxOf(content.total, content.songs.size)
                        knownSongs.putAll(page.items.associateBy { it.key })
                        if (page.items.isNotEmpty()) playCollection(page.items, 0, content)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (generation == cloudGeneration) cloudFailure(e) }
                }
            }
        }
    }

    fun loadMoreCloudSongs() { cloudCollection?.let { loadCloudSongs(it) } }
    fun loadMoreCloudSongsFor(content: CloudPlaylistContent) { loadCloudSongs(content) }
    fun refreshCloudSongs() {
        val old = cloudCollection ?: return
        if (old.loading) return
        val content = CloudPlaylistContent(old.playlist, old.source)
        if (old.source == "playlist") cloudContents[contentKey(old.playlist)] = content
        else if (old.source == "style_chart") cloudContents[-(1_000_000_000_000L + old.playlist.id)] = content
        cloudCollection = content
        collectionDestinations[activeCollectionId]?.cloud = content
        loadCloudSongs(content, refresh = true)
    }
    private fun loadCloudSongs(content: CloudPlaylistContent, refresh: Boolean = false) {
        if (!cloudEnabled || content.loading || !content.hasMore) return
        val generation = cloudGeneration
        val rankType = if (listeningRankWeek) 1 else 0
        content.loading = true; content.failed = false
        content.job = viewModelScope.launch {
            try {
                val catalog = when (content.source) {
                    "album" -> app.api.albumPage(content.playlist.id)
                    "artist" -> app.api.artistPage(content.playlist.id, content.offset)
                    else -> null
                }
                val page = catalog?.page ?: when (content.source) {
                    "rank" -> app.api.listeningRank(content.playlist.id, rankType).let { CloudPage(it, it.size, false, it.size) }
                    "radio" -> app.api.radioPrograms(content.playlist.id, content.offset)
                    "style_chart" -> app.api.styleChartSongs(content.playlist.id, content.offset, refresh = refresh)
                    else -> if (content.playlist.provider == PROVIDER_QQ) app.qq.playlistSongs(content.playlist.id)
                        else app.api.playlistSongs(content.playlist.id, content.offset, refresh = refresh)
                }
                ensureActive()
                if (generation != cloudGeneration) return@launch
                if (content.source == "rank" && rankType == 0) cloudRankPreviewSongs = page.items
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
                if (content.source == "album" && page.items.isNotEmpty()) {
                    albumTracksCache[content.playlist.id] = page.items
                    val update = mapOf(content.playlist.id to page.items)
                    collectionDestinations.values.mapNotNull { it.cloud }
                        .filter { it.source == "artist" }
                        .distinct()
                        .forEach { applyAlbumTracksBatch(it, update) }
                } else if (content.source == "artist" && catalog?.info != null) {
                    hydrateArtistAlbumTracks(content)
                }
                if (originalHeartbeatQueue == null && cloudQueue === content && newSongs.isNotEmpty()) {
                    val srcId = content.playlist.id.takeIf { content.source in setOf("playlist", "album") } ?: 0L
                    controller?.addMediaItems(newSongs.map { mediaItem(it, srcId) })
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
    fun playCollection(
        tracks: List<Song>,
        index: Int = 0,
        content: CloudPlaylistContent? = cloudCollection,
        shuffle: Boolean = false,
    ) {
        val sourceId = content?.playlist?.id?.takeIf { content.source in setOf("playlist", "album") } ?: 0L
        play(tracks, index, shuffle = shuffle, sourceId = sourceId)
        if (controller != null && tracks.isNotEmpty() && content != null) {
            cloudQueue = content
            val activeIndex = controller?.currentMediaItemIndex ?: index
            if (shuffle || activeIndex >= tracks.size - 5) loadCloudSongs(content)
        }
    }
    /** Cloud search uses its own provider preference, independent of the Cloud tab's display. */
    private suspend fun cloudSearch(query: String, category: SearchCategory, offset: Int, source: String): CloudPage<MusicSearchHit> {
        if (!cloudEnabled) return CloudPage(emptyList(), 0, false)
        if (source != "qq") return app.api.searchCategory(query, category, offset)
        if (category != SearchCategory.ALL && category != SearchCategory.SONG) return CloudPage(emptyList(), 0, false)
        return qqApiSnapshot().search(query, offset).let { page ->
            CloudPage(page.items.map { MusicSearchHit(it.id, SearchCategory.SONG, it.title, it.artist, it.artwork, it) }, page.nextOffset, page.hasMore, page.total)
        }
    }
    private suspend fun localSearch(request: MusicSearchRequest): CloudPage<MusicSearchHit> = withContext(Dispatchers.Default) {
        localMusicSearch(songs, request)
    }

    fun search(query: String) { if (query != searchQuery) { searchQuery = query; submitSearch() } }
    fun changeSearchScope(cloud: Boolean) {
        searchCloud = cloud
        if (cloud && settings.value.cloudSearchSource == "qq" && searchCategory !in listOf(SearchCategory.ALL, SearchCategory.SONG)) searchCategory = SearchCategory.ALL
        if (!cloud && searchCategory.apiType > 100) searchCategory = SearchCategory.ALL
        submitSearch()
    }
    fun changeSearchCategory(category: SearchCategory) { searchCategory = category; submitSearch() }
    private fun submitSearch() {
        searchJob?.cancel(); searchHits = emptyList(); searchResults = emptyList(); searchHasMore = false
        searching = searchQuery.isNotBlank()
        queries.value = MusicSearchRequest(searchQuery, searchCloud, searchCategory, settings.value.cloudSearchSource, ++searchRevision)
    }
    fun loadMoreSearch() {
        if (searching || !searchHasMore || !searchCloud) return
        val request = queries.value
        searchJob = viewModelScope.launch {
            searching = true
            try {
                val page = cloudSearch(request.query, request.category, cloudSearchOffset, request.source)
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
    fun isFavorite(song: Song): Boolean = if (song.netease) song.id in cloudLikedIds else song.key in history.favorites()
    fun toggleFavorite(song: Song) {
        if (!song.netease) { history.toggle(song); return }
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
    fun isArtistFollowed(artistId: Long): Boolean? = followedArtistStates[artistId]
    suspend fun refreshArtistFollowed(artistId: Long): Boolean? {
        val user = account ?: return null
        if (!cloudEnabled || artistId <= 0L) return null
        val generation = cloudGeneration
        return try {
            val followed = app.api.artistFollowed(artistId)
            if (generation == cloudGeneration && account?.userId == user.userId) {
                followedArtistStates = followedArtistStates + (artistId to followed)
                followed
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    fun toggleArtistFollow(artistId: Long, targetFollow: Boolean, onResult: (Boolean) -> Unit = {}) {
        val user = account ?: run { onResult(false); return }
        if (!cloudEnabled || artistId <= 0L) { onResult(false); return }
        if (!followingArtists.add(artistId)) return
        val generation = cloudGeneration
        viewModelScope.launch {
            try {
                app.api.followArtist(artistId, targetFollow)
                if (generation != cloudGeneration || account?.userId != user.userId) {
                    onResult(false)
                    return@launch
                }
                followedArtistStates = followedArtistStates + (artistId to targetFollow)
                onResult(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == cloudGeneration) cloudFailure(e)
                onResult(false)
            } finally {
                followingArtists.remove(artistId)
            }
        }
    }
    fun addSongToCloudPlaylist(playlist: CloudPlaylist, song: Song, complete: (Boolean) -> Unit = {}) {
        val user = account ?: run { complete(false); return }
        if (!song.netease || playlist.creatorId != user.userId || playlist.specialType == 5) { complete(false); return }
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
        listeningRankWeek = false
        val content = CloudPlaylistContent(CloudPlaylist(user.userId, tr("listening_rank", "听歌排行"), 0, ""), "rank")
        pushCollection(MusicCollection("cloud:rank:${user.userId}", content.playlist.name, "", emptyList(), content.playlist.artwork), content)
        loadCloudSongs(content)
    }
    fun changeListeningRankPeriod(week: Boolean) {
        if (listeningRankWeek == week) return
        val old = cloudCollection?.takeIf { it.source == "rank" } ?: return
        listeningRankWeek = week
        old.job?.cancel()
        val content = CloudPlaylistContent(old.playlist, old.source)
        cloudCollection = content
        collectionDestinations[activeCollectionId]?.cloud = content
        loadCloudSongs(content)
    }
    private fun cancelHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        heartbeatLoading = false
    }
    private fun enterHeartbeat(player: MediaController) {
        val user = account ?: return
        val startingItem = player.currentMediaItem ?: return
        val generation = cloudGeneration
        heartbeatLoading = true
        heartbeatJob = viewModelScope.launch {
            try {
                var liked = cloudPlaylists.firstOrNull { it.specialType == 5 && it.creatorId == user.userId }
                if (liked == null) liked = app.api.playlists(user.userId).items.firstOrNull { it.specialType == 5 && it.creatorId == user.userId }
                val likedIds = if (liked != null) app.api.likedSongIds(user.userId) else emptySet()
                val seed = current?.id?.takeIf { it in likedIds } ?: likedIds.randomOrNull()
                if (liked == null || seed == null) {
                    error = tr("heartbeat_needs_likes", "先收藏几首喜欢的音乐，再试试心动模式")
                    setListRepeat(player)
                    return@launch
                }
                val tracks = app.api.heartbeatSongs(liked.id, seed)
                ensureActive()
                if (generation != cloudGeneration || originalHeartbeatQueue != null || !settings.value.heartbeatEnabled ||
                    player.currentMediaItem != startingItem || player.repeatMode != Player.REPEAT_MODE_ONE) return@launch
                if (tracks.isEmpty()) {
                    error = tr("heartbeat_empty", "暂时没有心动推荐")
                    setListRepeat(player)
                    return@launch
                }
                originalHeartbeatQueue = HeartbeatQueue(List(player.mediaItemCount, player::getMediaItemAt),
                    player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0), cloudQueue)
                cloudQueue = null
                playbackMode = HEARTBEAT_PLAYBACK_MODE
                setListRepeat(player)
                knownSongs.putAll(tracks.associateBy { it.key })
                player.setMediaItems(tracks.map(::mediaItem), 0, 0L)
                player.prepare()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == cloudGeneration) { setListRepeat(player); cloudFailure(e) }
            }
            finally {
                if (generation == cloudGeneration && isActive) { heartbeatJob = null; heartbeatLoading = false }
            }
        }
    }
    private fun leaveHeartbeat() {
        cancelHeartbeat()
        val original = originalHeartbeatQueue ?: return
        val player = controller ?: return
        originalHeartbeatQueue = null
        val content = original.content ?: cloudQueue
        cloudQueue = content
        playbackMode = 0
        setListRepeat(player)
        val savedIds = original.items.mapTo(hashSetOf()) { it.mediaId }
        val loadedWhileAway = content?.songs.orEmpty().filter { it.key !in savedIds }.map(::mediaItem)
        player.setMediaItems(original.items + loadedWhileAway, original.index, original.positionMs)
        player.prepare()
    }
    fun refreshRoamingPreview() {
        if (!cloudEnabled || account == null) return
        val generation = cloudGeneration
        viewModelScope.launch {
            val songs = cloudSection { app.api.personalFmSongs() }.orEmpty()
            if (generation != cloudGeneration || songs.isEmpty()) return@launch
            roamingBatch = songs
            roamingPreviewSong = songs.firstOrNull()
        }
    }
    fun startRoamingMode() {
        val player = controller ?: run { error = tr("player_connecting", "播放服务正在连接，请稍后重试"); return }
        if (!cloudEnabled || account == null) return
        val generation = cloudGeneration
        roamingRefreshJob?.cancel()
        roamingLoadMoreJob?.cancel()
        viewModelScope.launch {
            val tracks = roamingBatch.ifEmpty { cloudSection { app.api.personalFmSongs() }.orEmpty() }
            if (generation != cloudGeneration) return@launch
            if (tracks.isEmpty()) {
                error = tr("roaming_empty", "暂时没有漫游推荐")
                return@launch
            }
            cancelHeartbeat()
            originalHeartbeatQueue = null
            cloudQueue = null
            roamingModeActive = true
            lastExitedRoamingAtMs = 0L
            playbackMode = ROAMING_PLAYBACK_MODE
            setListRepeat(player)
            knownSongs.putAll(tracks.associateBy { it.key })
            player.setMediaItems(tracks.map(::mediaItem), 0, 0L)
            player.prepare()
            player.play()
            roamingBatch = emptyList()
            roamingRefreshJob = viewModelScope.launch {
                delay(30_000L)
                if (generation == cloudGeneration) {
                    refreshRoamingPreview()
                }
            }
        }
    }
    private fun loadMoreRoamingSongs(player: Player) {
        if (roamingLoadMoreJob?.isActive == true || !cloudEnabled || account == null) return
        val generation = cloudGeneration
        roamingLoadMoreJob = viewModelScope.launch {
            try {
                val tracks = cloudSection { app.api.personalFmSongs() }.orEmpty()
                if (generation != cloudGeneration || !roamingModeActive || tracks.isEmpty()) return@launch
                val existingIds = (0 until player.mediaItemCount).mapTo(hashSetOf()) { player.getMediaItemAt(it).mediaId }
                val fresh = tracks.filter { it.key !in existingIds }
                if (fresh.isNotEmpty()) {
                    knownSongs.putAll(fresh.associateBy { it.key })
                    player.addMediaItems(fresh.map(::mediaItem))
                }
            } finally {
                if (generation == cloudGeneration) roamingLoadMoreJob = null
            }
        }
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
                content.albums = applyCachedTracksToAlbums((content.albums + page.items).distinctBy { it.id })
                content.albumsOffset = page.nextOffset; content.albumsHasMore = page.hasMore
                hydrateArtistAlbumTracks(content)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { content.albumsFailed = true; cloudFailure(e) }
            finally { content.albumsLoading = false }
        }
    }

    private fun applyCachedTracksToAlbums(albums: List<CatalogAlbum>): List<CatalogAlbum> = albums.map { album ->
        if (album.tracks.isEmpty()) {
            albumTracksCache[album.id]?.let { tracks ->
                album.copy(tracks = tracks, count = album.count.takeIf { it > 0 } ?: tracks.size)
            } ?: album
        } else {
            album
        }
    }

    private fun applyAlbumTracksBatch(content: CloudPlaylistContent, batch: Map<Long, List<Song>>) {
        if (batch.isEmpty()) return
        fun enrich(albums: List<CatalogAlbum>): List<CatalogAlbum>? {
            var changed = false
            val updated = albums.map { album ->
                val tracks = batch[album.id]
                if (tracks != null && tracks.isNotEmpty() && album.tracks != tracks) {
                    changed = true
                    album.copy(tracks = tracks, count = album.count.takeIf { it > 0 } ?: tracks.size)
                } else {
                    album
                }
            }
            return if (changed) updated else null
        }
        content.catalog?.let { info -> enrich(info.albums)?.let { content.catalog = info.copy(albums = it) } }
        if (content.albums.isNotEmpty()) enrich(content.albums)?.let { content.albums = it }
    }

    private fun hydrateArtistAlbumTracks(content: CloudPlaylistContent) {
        if (!cloudEnabled || content.source != "artist") return
        content.catalog?.let { info ->
            val enriched = applyCachedTracksToAlbums(info.albums)
            if (enriched != info.albums) content.catalog = info.copy(albums = enriched)
        }
        if (content.albums.isNotEmpty()) {
            val enriched = applyCachedTracksToAlbums(content.albums)
            if (enriched != content.albums) content.albums = enriched
        }
        val combined = (content.albums + content.catalog?.albums.orEmpty()).distinctBy { it.id }
        val indexById = combined.withIndex().associate { it.value.id to it.index }
        val pending = combined.filter {
            it.id > 0L &&
                it.tracks.isEmpty() &&
                !albumTracksCache.containsKey(it.id) &&
                it.id !in attemptedAlbumTrackIds
        }
        if (pending.isEmpty()) return
        val prioritized = pending.sortedWith(
            compareBy<CatalogAlbum>(
                { it.count != 0 && it.count !in 1..12 },
                { indexById[it.id] ?: Int.MAX_VALUE },
            )
        )
        content.albumTracksJob?.cancel()
        val generation = cloudGeneration
        content.albumTracksJob = viewModelScope.launch {
            for (chunk in prioritized.chunked(4)) {
                ensureActive()
                if (generation != cloudGeneration) return@launch
                val results = chunk.map { album ->
                    async(Dispatchers.IO) {
                        val fetched = try {
                            if (album.provider == PROVIDER_QQ) app.qq.albumTracks(album.id.toString())
                            else app.api.albumTracks(album.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptyList()
                        }
                        album.id to fetched
                    }
                }.awaitAll()
                ensureActive()
                if (generation != cloudGeneration) return@launch
                val batch = mutableMapOf<Long, List<Song>>()
                for ((albumId, tracks) in results) {
                    attemptedAlbumTrackIds.add(albumId)
                    if (tracks.isNotEmpty()) {
                        albumTracksCache[albumId] = tracks
                        batch[albumId] = tracks
                    }
                }
                if (batch.isNotEmpty()) {
                    applyAlbumTracksBatch(content, batch)
                }
            }
        }
    }
    fun configureApi(value: String): Boolean = runCatching {
        cloudGeneration++
        cloudJob?.cancel()
        val changed = app.endpoint != NeteaseApi.normalizeEndpoint(value)
        if (changed) clearCloudQueue()
        app.saveEndpoint(value)
        syncSavedAccounts()
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
            app.clearCookie(); syncSavedAccounts(); account = null; cloudPlaylists = emptyList()
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
        cloudHomeJob?.cancel(); cloudChartsJob?.cancel(); cloudHomePlayJob?.cancel(); dailySongsJob?.cancel(); rankPreviewJob?.cancel(); leaveHeartbeat()
        dailySongsJob = null; dailySongsUserId = null
        cloudHomeLoading = false
        cloudChartsLoading = false; cloudChartsFailed = false; cloudChartsHydrated = false
        cloudLikedIds = emptySet(); cloudLikesReady = false; liking.clear()
        followedArtistStates = emptyMap(); followingArtists.clear()
        cloudHomeItems = emptyList(); cloudDiscoverAlbums = emptyList(); cloudHomeCharts = NeteaseChartCatalog.defaultHomeItems(); cloudChartsByGroup = NeteaseChartCatalog.defaultGroupedItems(); cloudRecommendedPlaylists = emptyList(); cloudFollowedAlbums = emptyList(); cloudRecentSongs = emptyList(); cloudRankPreviewSongs = emptyList(); cloudDailySongs = emptyList(); cloudPublicBanners = emptyList(); cloudHomeFailed = false
        roamingPreviewSong = null; roamingBatch = emptyList(); roamingRefreshJob?.cancel(); roamingLoadMoreJob?.cancel()
        subscribedAlbums = emptyList(); subscribedAlbumOffset = 0; subscribedAlbumsLoading = false
        subscribedAlbumsHasMore = false; subscribedAlbumsFailed = false
        collectionDestinations.values.forEach {
            it.cloud?.albumsJob?.cancel()
            it.cloud?.albumTracksJob?.cancel()
        }
        albumTracksCache.clear()
        attemptedAlbumTrackIds.clear()
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
    private fun syncSavedAccounts() {
        savedAccounts = app.savedAccounts()
    }
    private fun restoreAccount() {
        syncSavedAccounts()
        syncSavedQqAccounts()
        if (!cloudEnabled) return
        app.activeStoredAccount()?.let { account = it.toAccount() }
        if (app.cookie.isNotBlank()) cloudAction {
            val generation = cloudGeneration
            val user = app.api.account()
            currentCoroutineContext().ensureActive()
            if (generation != cloudGeneration) return@cloudAction
            app.saveAccountSession(user, app.cookie)
            syncSavedAccounts()
            account = user
            loadCloudPlaylists(reset = true)
        }
        qqAccount = app.savedQqAccount()
        if (app.qqCookie.isNotBlank() || (app.qqEndpoint.isNotBlank() && app.qqUin.isNotBlank())) {
            validateQqAccount()
        }
    }
    fun refreshCloud() {
        if (!cloudEnabled) return
        loadCloudPlaylists(reset = true, refresh = true); refreshQq()
    }
    private fun applyToplistMeta(
        specs: List<NeteaseChartSpec>,
        metaMap: Map<Long, NeteaseToplistMeta>,
        existing: Map<String, NeteaseChartItem> = emptyMap(),
    ): List<NeteaseChartItem> = specs.map { spec ->
        val key = "${spec.group.name}:${spec.source.name}:${spec.id}"
        val prev = existing[key]
        if (spec.source == NeteaseChartSource.PLAYLIST) {
            val meta = metaMap[spec.id]
            NeteaseChartItem(
                spec = spec,
                artwork = meta?.artwork?.takeIf { it.isNotBlank() } ?: prev?.artwork.orEmpty(),
                updateFrequency = meta?.updateFrequency?.takeIf { it.isNotBlank() }
                    ?: prev?.updateFrequency?.takeIf { it.isNotBlank() }
                    ?: spec.defaultFrequency,
                tracks = meta?.tracks?.takeIf { it.isNotEmpty() } ?: prev?.tracks.orEmpty(),
            )
        } else {
            prev ?: NeteaseChartItem(spec)
        }
    }

    fun refreshCloudHome() {
        if (!cloudEnabled) return
        val user = account
        if (cloudHomeLoading && cloudHomeUserId == user?.userId) return
        cloudHomeUserId = user?.userId
        cloudHomeLoading = true; cloudHomeFailed = false
        val generation = cloudGeneration
        cloudHomeJob?.cancel()
        cloudHomeJob = viewModelScope.launch {
            try {
                val home = async { cloudSection { app.api.homeItems() } }
                val recommended = async { cloudSection { app.api.recommendedPlaylists(personalized = user != null) } }
                val daily = async { if (user != null) fetchCloudDailySongs() else emptyList() }
                val recent = async { if (user != null) cloudSection { app.api.recentSongs(limit = 50) } else null }
                val followed = async { if (user != null) cloudSection { app.api.followedArtistAlbums() } else null }
                val banners = async { cloudSection { app.api.publicBanners() } }
                val discoverAlbumsDeferred = async { cloudSection { app.api.discoverAlbums(limit = 16) } }
                val toplistMetaDeferred = async { cloudSection { app.api.toplistMetaMap() } }
                val homeItems = home.await()
                val recommendations = recommended.await()
                daily.await()
                val recentTracks = recent.await()
                val discoveredAlbums = discoverAlbumsDeferred.await()
                val toplistMeta = toplistMetaDeferred.await().orEmpty()
                ensureActive()
                if (generation == cloudGeneration && account?.userId == user?.userId) {
                    cloudHomeItems = homeItems.orEmpty()
                    val combinedAlbums = (discoveredAlbums.orEmpty() + homeItems.orEmpty().filter { it.kind == "album" })
                        .distinctBy { it.id }
                    if (combinedAlbums.isNotEmpty()) cloudDiscoverAlbums = combinedAlbums
                    if (toplistMeta.isNotEmpty()) {
                        val existingHome = cloudHomeCharts.associateBy { it.stableKey }
                        val existingAll = cloudChartsByGroup.values.flatten().associateBy { it.stableKey }
                        cloudHomeCharts = applyToplistMeta(NeteaseChartCatalog.homeSpecs, toplistMeta, existingHome)
                        cloudChartsByGroup = NeteaseChartCatalog.specsByGroup.mapValues { (_, specs) ->
                            applyToplistMeta(specs, toplistMeta, existingAll)
                        }
                    }
                    val playlists = (recommendations.orEmpty() + homeItems.orEmpty().filter { it.kind == "playlist" })
                        .sortedBy { it.section == "recommended" }.distinctBy { it.id }
                    cloudRecommendedPlaylists = playlists
                    if (user == null) cloudRecentSongs = emptyList()
                    else recentTracks?.let { cloudRecentSongs = it }
                    cloudPublicBanners = banners.await()
                        .orEmpty()
                    cloudFollowedAlbums = followed.await().orEmpty()
                    cloudHomeFailed = homeItems == null && recommendations == null && discoveredAlbums == null && toplistMeta.isEmpty()
                    val covers = playlists.filter { it.section == "radar" }.take(6).map { radar ->
                        async { radar.id to cloudSection { app.api.playlistSongs(radar.id, limit = 1).items.firstOrNull()?.artwork } }
                    }.awaitAll().toMap()
                    val homeChartPreviews = cloudHomeCharts.filter { it.tracks.isEmpty() }.map { chart ->
                        async {
                            chart.spec.id to cloudSection {
                                app.api.playlistSongs(chart.spec.id, limit = 3).items
                            }
                        }
                    }.awaitAll().toMap()
                    ensureActive()
                    cloudRecommendedPlaylists = playlists.map { item ->
                        covers[item.id]?.takeIf(String::isNotBlank)?.let { item.copy(artwork = it) } ?: item
                    }
                    if (homeChartPreviews.isNotEmpty()) {
                        cloudHomeCharts = cloudHomeCharts.map { chart ->
                            val previewSongs = homeChartPreviews[chart.spec.id].orEmpty()
                            if (previewSongs.isEmpty()) chart
                            else chart.copy(
                                artwork = chart.artwork.ifBlank { previewSongs.firstOrNull()?.artwork.orEmpty() },
                                tracks = previewSongs.take(3).map { NeteaseChartTrackPreview(it.title, it.artist) },
                            )
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == cloudGeneration) cloudHomeFailed = true }
            finally { if (generation == cloudGeneration && isActive) cloudHomeLoading = false }
        }
    }

    fun refreshCloudCharts(force: Boolean = false) {
        if (!cloudEnabled) return
        if (cloudChartsLoading && !force) return
        if (!force && cloudChartsHydrated && cloudChartsByGroup.values.flatten().any { it.artwork.isNotBlank() }) return
        val generation = cloudGeneration
        cloudChartsLoading = true
        cloudChartsFailed = false
        cloudChartsJob?.cancel()
        cloudChartsJob = viewModelScope.launch {
            try {
                val meta = cloudSection { app.api.toplistMetaMap(refresh = force) }.orEmpty()
                ensureActive()
                if (generation != cloudGeneration) return@launch
                if (meta.isNotEmpty()) {
                    val existingAll = cloudChartsByGroup.values.flatten().associateBy { it.stableKey }
                    val existingHome = cloudHomeCharts.associateBy { it.stableKey }
                    cloudChartsByGroup = NeteaseChartCatalog.specsByGroup.mapValues { (_, specs) ->
                        applyToplistMeta(specs, meta, existingAll)
                    }
                    cloudHomeCharts = applyToplistMeta(NeteaseChartCatalog.homeSpecs, meta, existingHome)
                }
                val styleHydration = NeteaseChartCatalog.styleSpecs.map { spec ->
                    async {
                        spec.id to cloudSection { app.api.styleChartSongs(spec.id, cursor = 0, size = 3, refresh = force).items }
                    }
                }.awaitAll().toMap()
                ensureActive()
                if (generation != cloudGeneration) return@launch
                val updatedGenre = cloudChartsByGroup[NeteaseChartGroup.GENRE].orEmpty().map { item ->
                    if (item.spec.source != NeteaseChartSource.STYLE_TAG) item
                    else {
                        val songs = styleHydration[item.spec.id].orEmpty()
                        if (songs.isEmpty()) item
                        else item.copy(
                            artwork = songs.firstOrNull { it.artwork.isNotBlank() }?.artwork
                                ?.replaceFirst("http://", "https://")
                                ?: item.artwork,
                            tracks = songs.take(3).map { NeteaseChartTrackPreview(it.title, it.artist) },
                        )
                    }
                }
                cloudChartsByGroup = cloudChartsByGroup + (NeteaseChartGroup.GENRE to updatedGenre)
                val anyHydrated = meta.isNotEmpty() || styleHydration.values.any { !it.isNullOrEmpty() }
                cloudChartsHydrated = anyHydrated
                cloudChartsFailed = !anyHydrated
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == cloudGeneration) cloudChartsFailed = true
            } finally {
                if (generation == cloudGeneration && isActive) cloudChartsLoading = false
            }
        }
    }
    suspend fun fetchCloudDailySongs(refresh: Boolean = false): List<Song> {
        val user = account ?: return emptyList()
        if (!cloudEnabled) return emptyList()
        if (!refresh && dailySongsUserId == user.userId && cloudDailySongs.isNotEmpty()) return cloudDailySongs
        val inFlight = if (!refresh && dailySongsUserId == user.userId) dailySongsJob?.takeIf { it.isActive } else null
        if (inFlight != null) return inFlight.await().orEmpty()
        val generation = cloudGeneration
        dailySongsUserId = user.userId
        val job = viewModelScope.async { cloudSection { app.api.dailySongs(refresh) } }
        dailySongsJob = job
        val tracks = job.await()
        if (generation == cloudGeneration && account?.userId == user.userId && tracks != null) {
            cloudDailySongs = tracks
        }
        return tracks.orEmpty()
    }
    fun refreshListeningRankPreview() {
        val user = account ?: return
        if (!cloudEnabled || cloudRankPreviewSongs.isNotEmpty() || rankPreviewJob?.isActive == true) return
        val generation = cloudGeneration
        rankPreviewJob = viewModelScope.launch {
            val tracks = cloudSection { app.api.listeningRank(user.userId) }
            ensureActive()
            if (generation == cloudGeneration && tracks != null) cloudRankPreviewSongs = tracks
        }
    }
    suspend fun loadNeteaseListenReport(period: ListenReportPeriod, periodOffset: Int = 0): NeteaseListenReportData? {
        val user = account ?: return null
        if (!cloudEnabled) return null
        return NeteaseListenReportBuilder.load(app.api, app.neteaseListenStore, user.userId, period, periodOffset)
    }
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
        if (!cloudEnabled || cloudPlaylistsLoading || !reset && !cloudPlaylistsHasMore) return
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
    fun login(phone: String, country: String, captcha: String, onSuccess: () -> Unit = {}) = cloudAction {
        val generation = cloudGeneration
        val (cookie, user) = app.api.login(phone.trim(), country.trim().removePrefix("+"), captcha.trim())
        currentCoroutineContext().ensureActive()
        if (generation != cloudGeneration) return@cloudAction
        val previousUserId = account?.userId
        if (previousUserId != null && previousUserId != user.userId) clearCloudQueue() else resetCloudPaging()
        cloudPlaylists = emptyList()
        app.saveAccountSession(user, cookie)
        syncSavedAccounts()
        account = user
        loginMessage = tr("login_success", "登录成功")
        loadCloudPlaylists(reset = true)
        if (settings.value.homeContent == "netease") refreshCloudHome()
        onSuccess()
    }
    fun completeOfficialLogin(sessionCookie: String, onSuccess: () -> Unit = {}) {
        if (cloudBusy || app.endpoint.isNotBlank()) return
        cloudAction {
            val generation = cloudGeneration
            val user = withContext(Dispatchers.IO) { NeteaseApi({ "" }, { sessionCookie }).account() }
            currentCoroutineContext().ensureActive()
            if (generation != cloudGeneration) return@cloudAction
            val previousUserId = account?.userId
            if (previousUserId != null && previousUserId != user.userId) clearCloudQueue() else resetCloudPaging()
            cloudPlaylists = emptyList()
            app.saveAccountSession(user, sessionCookie)
            syncSavedAccounts()
            account = user
            loginMessage = tr("login_success", "登录成功")
            loadCloudPlaylists(reset = true)
            if (settings.value.homeContent == "netease") refreshCloudHome()
            onSuccess()
        }
    }
    fun switchCloudAccount(userId: Long) {
        if (account?.userId == userId && app.cookie.isNotBlank()) return
        cloudGeneration++
        cloudJob?.cancel()
        clearCloudQueue()
        val selected = app.switchAccount(userId)
        syncSavedAccounts()
        cloudPlaylists = emptyList()
        account = selected?.toAccount()
        if (selected == null) return
        loadCloudPlaylists(reset = true)
        if (settings.value.homeContent == "netease") refreshCloudHome()
        cloudAction {
            val generation = cloudGeneration
            val refreshed = app.api.account()
            currentCoroutineContext().ensureActive()
            if (generation == cloudGeneration && refreshed.userId == userId) {
                app.saveAccountSession(refreshed, app.cookie)
                syncSavedAccounts()
                account = refreshed
            }
        }
    }
    fun deleteCloudAccount(userId: Long) {
        val wasActive = account?.userId == userId || app.activeStoredAccount()?.userId == userId
        val nextActive = app.removeAccount(userId)
        syncSavedAccounts()
        if (wasActive) {
            cloudGeneration++
            cloudJob?.cancel()
            clearCloudQueue()
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            cloudPlaylists = emptyList()
            cloudBusy = false
            account = nextActive?.toAccount()
            if (nextActive != null) {
                loadCloudPlaylists(reset = true)
                if (settings.value.homeContent == "netease") refreshCloudHome()
            } else {
                loginMessage = tr("logged_out", "已退出登录")
            }
        }
    }
    fun useOfficialService() {
        cloudGeneration++
        cloudJob?.cancel()
        clearCloudQueue()
        app.useOfficialService()
        syncSavedAccounts()
        account = null; cloudPlaylists = emptyList(); cloudBusy = false; loginMessage = ""
    }
    fun logout() {
        cloudGeneration++
        cloudJob?.cancel()
        clearCloudQueue()
        app.clearCookie()
        syncSavedAccounts()
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
