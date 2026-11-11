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
import okhttp3.OkHttpClient
import org.json.JSONObject

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val state by lazy { getSharedPreferences("aura.playback.queue", MODE_PRIVATE) }
    override fun onCreate() {
        super.onCreate()
        val app = application as AuraApplication
        val http = OkHttpDataSource.Factory(OkHttpClient()).setDefaultRequestProperties(mapOf("Referer" to "https://music.163.com/"))
        val source = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme == "aura" && spec.uri.host == "netease") {
                val id = spec.uri.lastPathSegment?.toLongOrNull() ?: throw java.io.IOException("INVALID_SONG")
                spec.withUri(Uri.parse(app.api.resolveBlocking(id, spec.uri.getQueryParameter("quality") ?: PlaybackPreferences(this).quality.level)))
            } else spec
        }
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
        }
        restoreState(player)
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                saveState(player)
            }
        })
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.let(::saveState)
        if (session?.player?.playWhenReady != true) stopSelf()
    }
    override fun onDestroy() {
        session?.run { saveState(player); player.release(); release() }
        session = null
        super.onDestroy()
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
        repeat(player.mediaItemCount) { index ->
            val item = player.getMediaItemAt(index)
            val md = item.mediaMetadata
            editor.putString("item.$index", JSONObject().apply {
                put("id", item.mediaId)
                put("uri", item.localConfiguration?.uri?.toString().orEmpty())
                put("title", md.title?.toString().orEmpty())
                put("artist", md.artist?.toString().orEmpty())
                put("album", md.albumTitle?.toString().orEmpty())
                put("duration", md.durationMs ?: 0L)
                put("artwork", md.artworkUri?.toString().orEmpty())
            }.toString())
        }
        editor.apply()
    }
}
