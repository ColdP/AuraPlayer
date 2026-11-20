package btm.m.auraplayer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.MainActivity
import btm.m.auraplayer.R
import btm.m.auraplayer.auramix.AuraMixManager
import btm.m.auraplayer.data.LocalAudioArtwork
import btm.m.auraplayer.widget.WidgetActionReceiver
import btm.m.auraplayer.widget.WidgetPlaybackSnapshot
import btm.m.auraplayer.widget.WidgetPlaybackState
import btm.m.auraplayer.widget.WidgetUpdateCoordinator
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import okhttp3.OkHttpClient
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

@UnstableApi
class PlaybackService : MediaSessionService() {
    companion object {
        @Volatile
        internal var activeInstance: PlaybackService? = null
            private set
        private const val WIDGET_FALLBACK_CHANNEL_ID = "aura.widget.fallback"
        private const val WIDGET_FALLBACK_NOTIFICATION_ID = 9001
    }

    private var session: MediaSession? = null
    private var playerInstance: ExoPlayer? = null
    private var transportInstance: btm.m.auraplayer.audio.FadingPlayer? = null
    private var usbAudioSink: btm.m.auraplayer.audio.usb.UsbExclusiveAudioSink? = null
    private var usbReceiver: BroadcastReceiver? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private val state by lazy { getSharedPreferences("aura.playback.queue", MODE_PRIVATE) }
    private var auraMix: AuraMixManager? = null
    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        val app = application as AuraApplication
        val http = OkHttpDataSource.Factory(OkHttpClient()).setDefaultRequestProperties(mapOf("Referer" to "https://music.163.com/"))
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.scheme == "aura" && spec.uri.host == "netease") {
                val id = spec.uri.lastPathSegment?.toLongOrNull() ?: throw java.io.IOException("INVALID_SONG")
                spec.withUri(Uri.parse(app.api.resolveBlocking(id, spec.uri.getQueryParameter("quality") ?: PlaybackPreferences(this).quality.level)))
            } else if (spec.uri.scheme == "aura" && spec.uri.host == "qqmusic") {
                val mid = spec.uri.lastPathSegment ?: throw java.io.IOException("INVALID_SONG")
                spec.withUri(Uri.parse(app.qq.resolveBlocking(mid)))
            } else spec
        }
        val cacheSource = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(PlaybackAudioCache.get(this))
            .setUpstreamDataSourceFactory(resolving)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val cloudSource = ResolvingDataSource.Factory(cacheSource) { spec ->
            val uri = spec.uri
            val key = if (uri.scheme == "aura") uri.lastPathSegment?.let { trackId ->
                playbackAudioCacheKey(uri.host.orEmpty(), trackId,
                    uri.getQueryParameter("quality") ?: PlaybackPreferences(this).quality.level)
            } else null
            if (key == null) spec else spec.buildUpon().setKey(key).setFlags(playbackAudioCacheFlags(spec.flags)).build()
        }
        val source = DefaultDataSource.Factory(this, cloudSource)
        val mediaSourceFactory = DefaultMediaSourceFactory(source)
        val renderersFactory = btm.m.auraplayer.audio.EffectsRenderersFactory(
            context = this,
            store = app.effects,
            auraSing = app.auraSing,
            onUsbSinkCreated = { usbAudioSink = it },
        )
        val player = ExoPlayer.Builder(this, renderersFactory).setMediaSourceFactory(mediaSourceFactory).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), !app.effects.state.value.alwaysPlay)
            setHandleAudioBecomingNoisy(true)
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            setPlaybackSpeed(PlaybackPreferences(this@PlaybackService).speed)
        }
        restoreState(player)
        app.effects.notifyTrackChanged(trackKey(player.currentMediaItem))
        notifyAuraSingTrack(app, player)
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioSessionIdChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioSessionId: Int) {
                app.effects.audioSessionId = audioSessionId
            }
        })
        player.repeatMode = state.getInt("repeatMode", androidx.media3.common.Player.REPEAT_MODE_ALL)
            .coerceIn(androidx.media3.common.Player.REPEAT_MODE_OFF, androidx.media3.common.Player.REPEAT_MODE_ALL)
        player.shuffleModeEnabled = state.getBoolean("shuffle", false)
        auraMix = AuraMixManager(this, player, mediaSourceFactory, app.effects, app.auraMix, serviceScope)
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo,
                reason: Int,
            ) {
                if (reason != androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    app.auraSing.notifyPositionDiscontinuity(newPosition.positionMs.coerceAtLeast(0L))
                }
            }

            override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                if (events.contains(androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    app.effects.notifyTrackChanged(trackKey(player.currentMediaItem))
                    notifyAuraSingTrack(app, player)
                }
                // Volume ramps must not serialize the entire queue on every animation frame.
                if (events.containsAny(
                        androidx.media3.common.Player.EVENT_TIMELINE_CHANGED,
                        androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION,
                        androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY,
                        androidx.media3.common.Player.EVENT_PLAYBACK_STATE_CHANGED,
                        androidx.media3.common.Player.EVENT_PLAY_WHEN_READY_CHANGED,
                        androidx.media3.common.Player.EVENT_MEDIA_METADATA_CHANGED,
                        androidx.media3.common.Player.EVENT_REPEAT_MODE_CHANGED,
                        androidx.media3.common.Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    )) {
                    saveState(player)
                    WidgetUpdateCoordinator.requestUpdate(this@PlaybackService)
                }
            }
        })
        val activity = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_MEDIA_PLAYER)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val transport = btm.m.auraplayer.audio.FadingPlayer(player, app.effects, serviceScope)
        playerInstance = player
        transportInstance = transport
        val bitmapLoader = CacheBitmapLoader(AuraMediaBitmapLoader(this, DataSourceBitmapLoader(this)))
        session = MediaSession.Builder(this, transport)
            .setSessionActivity(activity)
            .setBitmapLoader(bitmapLoader)
            .build()
        WidgetUpdateCoordinator.requestUpdate(this)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        if (app.effects.usbExclusiveEnabled) {
                            val usbDev = btm.m.auraplayer.audio.usb.UsbAudioDevice.getInstance(this@PlaybackService)
                            val audioDev = usbDev.findUsbAudioDevice()
                            if (audioDev != null && !usbDev.hasPermission(audioDev)) {
                                usbDev.requestPermission(audioDev) {
                                    usbAudioSink?.onUsbAttached() ?: app.effects.refreshUsbStatus()
                                }
                            } else {
                                usbAudioSink?.onUsbAttached() ?: app.effects.refreshUsbStatus()
                            }
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val detached = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        usbAudioSink?.onUsbDetached(detached) ?: app.effects.refreshUsbStatus()
                    }
                }
            }
        }
        usbReceiver = receiver
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            Context.RECEIVER_EXPORTED,
        )
        val audioManager = getSystemService(AudioManager::class.java)
        val deviceCb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val hasUsb = addedDevices.any {
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                if (hasUsb && app.effects.usbExclusiveEnabled) {
                    val usbDev = btm.m.auraplayer.audio.usb.UsbAudioDevice.getInstance(this@PlaybackService)
                    val audioDev = usbDev.findUsbAudioDevice()
                    if (audioDev != null && !usbDev.hasPermission(audioDev)) {
                        usbDev.requestPermission(audioDev) {
                            usbAudioSink?.onUsbAttached() ?: app.effects.refreshUsbStatus()
                        }
                    } else {
                        usbAudioSink?.onUsbAttached() ?: app.effects.refreshUsbStatus()
                    }
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val hasUsb = removedDevices.any {
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                if (hasUsb && btm.m.auraplayer.audio.usb.UsbAudioDevice.getInstance(this@PlaybackService).findUsbAudioDevice() == null) {
                    // Only treat as physical removal when UsbManager also reports no USB audio device.
                    usbAudioSink?.onUsbDetached(null) ?: app.effects.refreshUsbStatus()
                }
            }
        }
        audioDeviceCallback = deviceCb
        audioManager?.registerAudioDeviceCallback(deviceCb, android.os.Handler(android.os.Looper.getMainLooper()))
        serviceScope.launch {
            var previousAlways = app.effects.state.value.alwaysPlay
            var previousUsbExclusive = app.effects.state.value.usbExclusive
            var previousUsbBufferMs = app.effects.state.value.usbBufferMs
            var previousUsbLockFixed = app.effects.state.value.usbLockFixedFormat
            var previousUsbVolume = app.effects.state.value.usbVolume
            app.effects.state.collect { settings ->
                if (settings.alwaysPlay != previousAlways) {
                    previousAlways = settings.alwaysPlay
                    player.setAudioAttributes(player.audioAttributes, !settings.alwaysPlay)
                }
                if (settings.usbExclusive != previousUsbExclusive) {
                    previousUsbExclusive = settings.usbExclusive
                    usbAudioSink?.onExclusiveToggled(settings.usbExclusive)
                }
                if (settings.usbBufferMs != previousUsbBufferMs || settings.usbLockFixedFormat != previousUsbLockFixed) {
                    previousUsbBufferMs = settings.usbBufferMs
                    previousUsbLockFixed = settings.usbLockFixedFormat
                    usbAudioSink?.onUsbTuningChanged()
                }
                if (settings.usbVolume != previousUsbVolume) {
                    previousUsbVolume = settings.usbVolume
                    usbAudioSink?.onUsbVolumeChanged(settings.usbVolume)
                }
            }
        }
        serviceScope.launch {
            val prefs = PlaybackPreferences(this@PlaybackService)
            var lastSavedPosition = -1L
            var elapsedTicks = 0
            while (true) {
                val deadline = prefs.sleepDeadline
                if (deadline > 0 && android.os.SystemClock.elapsedRealtime() >= deadline) {
                    prefs.sleepDeadline = 0L
                    transport.pause()
                }
                elapsedTicks++
                if (elapsedTicks >= 4 && player.isPlaying) {
                    elapsedTicks = 0
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    if (kotlin.math.abs(pos - lastSavedPosition) >= 1500L) {
                        lastSavedPosition = pos
                        state.edit().putLong("position", pos).apply()
                    }
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (action in WidgetActionReceiver.SUPPORTED_ACTIONS) {
            if ((playerInstance?.mediaItemCount ?: 0) <= 0) {
                ensureFallbackForegroundAndStop()
            } else {
                handleWidgetAction(intent, forcePlayOnColdStart = true)
            }
        }
        return result
    }

    internal fun currentWidgetSnapshot(): WidgetPlaybackSnapshot? {
        val player = playerInstance ?: return null
        val playRequested = transportInstance?.isPlayRequested ?: player.playWhenReady
        return WidgetPlaybackState.fromLivePlayer(player, playRequested)
    }

    internal fun handleWidgetAction(intent: Intent?, forcePlayOnColdStart: Boolean = false) {
        val player = playerInstance ?: return
        val transport = transportInstance ?: return
        when (intent?.action) {
            WidgetActionReceiver.ACTION_PLAY_PAUSE -> {
                if (!forcePlayOnColdStart && transport.isPlayRequested) {
                    transport.pause()
                } else {
                    if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                        player.prepare()
                    }
                    transport.play()
                }
            }
            WidgetActionReceiver.ACTION_NEXT -> {
                if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                    player.prepare()
                }
                transport.seekToNextMediaItem()
                if (forcePlayOnColdStart) transport.play()
            }
            WidgetActionReceiver.ACTION_PREV -> {
                if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                    player.prepare()
                }
                transport.seekToPrevious()
                if (forcePlayOnColdStart) transport.play()
            }
            WidgetActionReceiver.ACTION_PLAY_QUEUE_INDEX -> {
                val index = intent.getIntExtra(WidgetActionReceiver.EXTRA_QUEUE_INDEX, -1)
                if (index in 0 until player.mediaItemCount) {
                    player.seekToDefaultPosition(index)
                    player.prepare()
                    transport.play()
                }
            }
        }
        WidgetUpdateCoordinator.requestUpdate(this)
    }

    private fun ensureFallbackForegroundAndStop() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(WIDGET_FALLBACK_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    WIDGET_FALLBACK_CHANNEL_ID,
                    "Aura Player Playback",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, WIDGET_FALLBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aura)
            .setContentTitle("Aura Player")
            .setSilent(true)
            .build()
        startForeground(WIDGET_FALLBACK_NOTIFICATION_ID, notification)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.let(::saveState)
        if (session?.player?.playWhenReady != true) stopSelf()
    }
    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        usbReceiver?.let { runCatching { unregisterReceiver(it) } }
        usbReceiver = null
        audioDeviceCallback?.let { cb ->
            runCatching { getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(cb) }
        }
        audioDeviceCallback = null
        serviceScope.cancel()
        auraMix?.close(); auraMix = null
        (application as AuraApplication).effects.audioSessionId = 0
        playerInstance = null
        transportInstance = null
        session?.run { saveState(player); player.release(); release() }
        session = null
        WidgetUpdateCoordinator.requestUpdate(this)
        super.onDestroy()
    }

    private fun trackKey(item: MediaItem?): String =
        item?.let { it.mediaId.ifBlank { it.localConfiguration?.uri?.toString().orEmpty() } }.orEmpty()

    private fun notifyAuraSingTrack(app: AuraApplication, player: androidx.media3.common.Player) {
        val item = player.currentMediaItem
        val key = trackKey(item)
        val uri = item?.localConfiguration?.uri?.toString().orEmpty()
        val md = item?.mediaMetadata
        app.auraSing.notifyTrackChanged(
            songKey = key,
            uriString = uri,
            title = md?.title?.toString().orEmpty(),
            artist = md?.artist?.toString().orEmpty(),
            album = md?.albumTitle?.toString().orEmpty(),
            positionMs = player.currentPosition.coerceAtLeast(0L),
        )
    }

    private fun restoreState(player: androidx.media3.exoplayer.ExoPlayer) {
        val count = state.getInt("count", 0)
        if (count <= 0) return
        val items = buildList<MediaItem> {
            repeat(count) { index ->
                val raw = state.getString("item.$index", null) ?: return@repeat
                runCatching {
                    val item = JSONObject(raw)
                    val uri = item.optString("uri").takeIf(String::isNotBlank) ?: return@runCatching
                    add(MediaItem.Builder().setMediaId(item.optString("id"))
                        .setUri(uri)
                        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(item.optString("title"))
                            .setArtist(item.optString("artist"))
                            .setAlbumTitle(item.optString("album"))
                            .setExtras(android.os.Bundle().apply {
                                putLong("albumId", item.optLong("albumId")); putLong("artistId", item.optLong("artistId"))
                                putString("artists", item.optString("artists", "[]"))
                            })
                            .setDurationMs(item.optLong("duration", 0L))
                            .setArtworkUri(item.optString("artwork").takeIf(String::isNotBlank)?.let(Uri::parse))
                            .build())
                        .build())
                }
            }
        }
        if (items.isEmpty()) return
        val index = state.getInt("index", 0).coerceIn(items.indices)
        val position = state.getLong("position", 0L).coerceAtLeast(0L)
        player.setMediaItems(items, index, position)
        player.prepare()
        // Never resume auto-play on cold relaunch after exiting while playing; keep restored track and progress.
        player.playWhenReady = false
    }

    private fun saveState(player: androidx.media3.common.Player) {
        val editor = state.edit().clear().putInt("count", player.mediaItemCount)
            .putInt("index", player.currentMediaItemIndex)
            .putLong("position", player.currentPosition.coerceAtLeast(0L))
            .putBoolean("playing", player.playWhenReady && player.playbackState != androidx.media3.common.Player.STATE_ENDED)
            .putInt("repeatMode", player.repeatMode)
            .putBoolean("shuffle", player.shuffleModeEnabled)
        repeat(player.mediaItemCount) { index ->
            val item = player.getMediaItemAt(index)
            val md = item.mediaMetadata
            editor.putString("item.$index", JSONObject().apply {
                put("id", item.mediaId)
                put("uri", item.localConfiguration?.uri?.toString().orEmpty())
                put("title", md.title?.toString().orEmpty())
                put("artist", md.artist?.toString().orEmpty())
                put("album", md.albumTitle?.toString().orEmpty())
                put("albumId", md.extras?.getLong("albumId") ?: 0L)
                put("artistId", md.extras?.getLong("artistId") ?: 0L)
                put("artists", md.extras?.getString("artists") ?: "[]")
                put("duration", md.durationMs ?: 0L)
                put("artwork", md.artworkUri?.toString().orEmpty())
            }.toString())
        }
        editor.apply()
    }

    private inner class AuraMediaBitmapLoader(
        private val context: android.content.Context,
        private val fallback: DataSourceBitmapLoader,
    ) : BitmapLoader {
        override fun supportsMimeType(mimeType: String): Boolean = fallback.supportsMimeType(mimeType)

        override fun decodeBitmap(data: ByteArray): ListenableFuture<android.graphics.Bitmap> =
            fallback.decodeBitmap(data)

        override fun loadBitmap(uri: Uri): ListenableFuture<android.graphics.Bitmap> {
            if (uri.scheme == LocalAudioArtwork.SCHEME) {
                val future = SettableFuture.create<android.graphics.Bitmap>()
                serviceScope.launch(Dispatchers.IO) {
                    val bitmap = runCatching {
                        val result = SingletonImageLoader.get(context).execute(
                            ImageRequest.Builder(context)
                                .data(uri.toString())
                                .size(1024)
                                .allowHardware(false)
                                .build(),
                        ) as? SuccessResult
                        result?.image?.toBitmap()
                    }.getOrNull()
                    if (bitmap != null) {
                        future.set(bitmap)
                    } else {
                        val parsedAlbumArt = LocalAudioArtwork.parse(uri.toString())?.albumArtUri
                            ?.takeIf { it.isNotBlank() }
                            ?.let(Uri::parse)
                        if (parsedAlbumArt != null) {
                            runCatching { fallback.loadBitmap(parsedAlbumArt).get() }
                                .onSuccess(future::set)
                                .onFailure(future::setException)
                        } else {
                            future.setException(java.io.IOException("ARTWORK_UNAVAILABLE"))
                        }
                    }
                }
                return future
            }
            return fallback.loadBitmap(uri)
        }
    }
}
