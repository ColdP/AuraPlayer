package btm.m.auraplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.MainActivity
import btm.m.auraplayer.auramix.AuraMixManager
import okhttp3.OkHttpClient
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private val state by lazy { getSharedPreferences("aura.playback.queue", MODE_PRIVATE) }
    private var auraMix: AuraMixManager? = null
    override fun onCreate() {
        super.onCreate()
        val app = application as AuraApplication
        val http = OkHttpDataSource.Factory(OkHttpClient()).setDefaultRequestProperties(mapOf("Referer" to "https://music.163.com/"))
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme == "aura" && spec.uri.host == "netease") {
                val id = spec.uri.lastPathSegment?.toLongOrNull() ?: throw java.io.IOException("INVALID_SONG")
                spec.withUri(Uri.parse(app.api.resolveBlocking(id, spec.uri.getQueryParameter("quality") ?: PlaybackPreferences(this).quality.level)))
            } else spec
        }
        val source = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(PlaybackAudioCache.get(this))
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
            .setUpstreamDataSourceFactory(resolving)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaSourceFactory = DefaultMediaSourceFactory(source)
        val player = ExoPlayer.Builder(this, btm.m.auraplayer.audio.EffectsRenderersFactory(this, app.effects)).setMediaSourceFactory(mediaSourceFactory).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), !app.effects.state.value.alwaysPlay)
            setHandleAudioBecomingNoisy(true)
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            setPlaybackSpeed(PlaybackPreferences(this@PlaybackService).speed)
        }
        restoreState(player)
        app.effects.notifyTrackChanged(trackKey(player.currentMediaItem))
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
            override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                if (events.contains(androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    app.effects.notifyTrackChanged(trackKey(player.currentMediaItem))
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
                    )) saveState(player)
            }
        })
        val activity = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_MEDIA_PLAYER)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val transport = btm.m.auraplayer.audio.FadingPlayer(player, app.effects, serviceScope)
        session = MediaSession.Builder(this, transport).setSessionActivity(activity).build()
        serviceScope.launch {
            var previousAlways = app.effects.state.value.alwaysPlay
            app.effects.state.collect { settings ->
                if (settings.alwaysPlay != previousAlways) {
                    previousAlways = settings.alwaysPlay
                    player.setAudioAttributes(player.audioAttributes, !settings.alwaysPlay)
                }
            }
        }
        serviceScope.launch {
            val prefs = PlaybackPreferences(this@PlaybackService)
            while (true) {
                val deadline = prefs.sleepDeadline
                if (deadline > 0 && android.os.SystemClock.elapsedRealtime() >= deadline) {
                    prefs.sleepDeadline = 0L
                    transport.pause()
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.let(::saveState)
        if (session?.player?.playWhenReady != true) stopSelf()
    }
    override fun onDestroy() {
        serviceScope.cancel()
        auraMix?.close(); auraMix = null
        (application as AuraApplication).effects.audioSessionId = 0
        session?.run { saveState(player); player.release(); release() }
        session = null
        super.onDestroy()
    }

    private fun trackKey(item: MediaItem?): String =
        item?.let { it.mediaId.ifBlank { it.localConfiguration?.uri?.toString().orEmpty() } }.orEmpty()

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
        player.playWhenReady = state.getBoolean("playing", false)
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
}
