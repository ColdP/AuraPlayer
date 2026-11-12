package btm.m.auraplayer.audio

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.*

/** Intercepts transport commands from UI, notification and headsets through MediaSession.
 * Fade-out completes before pausing; resume starts the ramp only when audio is ready. */
@UnstableApi
class FadingPlayer(private val delegate: Player, private val store: EffectsStore, private val scope: CoroutineScope) : ForwardingPlayer(delegate) {
    private var ramp: Job? = null
    private var requested = delegate.playWhenReady
    private var userVolume = delegate.volume
    private var fadingOut = false
    init {
        delegate.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && !fadingOut) { requested = false; ramp?.cancel(); delegate.volume = userVolume }
            }
        })
    }
    override fun play() = setPlayWhenReady(true)
    override fun pause() = setPlayWhenReady(false)
    override fun setVolume(volume: Float) { userVolume = volume.coerceIn(0f, 1f); if (ramp?.isActive != true) delegate.volume = userVolume }
    override fun getVolume(): Float = userVolume
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (requested == playWhenReady && (delegate.playWhenReady == playWhenReady || ramp?.isActive == true)) return
        requested = playWhenReady
        ramp?.cancel(); fadingOut = false
        val settings = store.state.value
        if (!settings.fade || (!playWhenReady && !delegate.isPlaying)) {
            delegate.volume = userVolume; delegate.playWhenReady = playWhenReady; return
        }
        val initial = if (playWhenReady && !delegate.playWhenReady) 0f else delegate.volume
        if (playWhenReady) { delegate.volume = initial; delegate.playWhenReady = true }
        fadingOut = !playWhenReady
        ramp = scope.launch {
            if (playWhenReady) {
                while (!delegate.isPlaying && delegate.playWhenReady && delegate.playbackState != Player.STATE_ENDED) delay(16)
                if (!delegate.playWhenReady || delegate.playbackState == Player.STATE_ENDED) return@launch
            }
            val start = android.os.SystemClock.elapsedRealtime()
            while (true) {
                val fraction = ((android.os.SystemClock.elapsedRealtime() - start).toFloat() / settings.fadeMs).coerceIn(0f, 1f)
                val eased = .5f - .5f * kotlin.math.cos(fraction * kotlin.math.PI.toFloat())
                delegate.volume = initial + ((if (playWhenReady) userVolume else 0f) - initial) * eased
                if (fraction >= 1) break
                delay(16)
            }
            if (!playWhenReady) { delegate.pause(); fadingOut = false; delegate.volume = userVolume }
        }
    }
}
