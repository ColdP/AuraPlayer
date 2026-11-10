package btm.m.auraplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        val app = application as AuraApplication
        val http = OkHttpDataSource.Factory(OkHttpClient()).setDefaultRequestProperties(mapOf("Referer" to "https://music.163.com/"))
        val source = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme == "aura" && spec.uri.host == "netease") {
                val id = spec.uri.lastPathSegment?.toLongOrNull() ?: throw java.io.IOException("INVALID_SONG")
                spec.withUri(Uri.parse(app.api.resolveBlocking(id)))
            } else spec
        }
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) { if (session?.player?.playWhenReady != true) stopSelf() }
    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
