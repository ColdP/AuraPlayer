package btm.m.auraplayer.auramix

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import btm.m.auraplayer.audio.EffectsRenderersFactory
import btm.m.auraplayer.audio.EffectsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap

/**
 * Plans smart transitions on the existing Media3 queue. AuraMix deliberately keeps one audible
 * renderer: two independent ExoPlayer AudioTracks cannot share a sample clock and the handoff
 * caused a stall after the progress bar moved to the next item.
 */
@UnstableApi
class AuraMixManager(
    private val context: Context,
    private val main: ExoPlayer,
    private val sourceFactory: DefaultMediaSourceFactory,
    private val effects: EffectsStore,
    val settings: AuraMixSettings,
    private val scope: CoroutineScope,
) : Player.Listener {
    private val features = ConcurrentHashMap<String, AudioFeatures>()
    private var watcher: Job? = null
    private var transition: ActiveTransition? = null
    private var plannedKey: String? = null
    private var plannedPlan: TransitionPlan? = null
    private var synchronizing = false

    private data class ActiveTransition(
        val currentId: String,
        val nextId: String,
        val plan: TransitionPlan,
        val baseVolume: Float,
        val nextIndex: Int,
    )

    init {
        main.addListener(this)
        watcher = scope.launch(Dispatchers.Main.immediate) { watch() }
    }

    private suspend fun watch() {
        while (kotlin.coroutines.coroutineContext.isActive) {
            val config = settings.state.value
            if (!config.enabled || config.mode == AuraMixMode.OFF || !main.isPlaying) {
                if (transition != null) cancelTransition() else stopSecondary()
                delay(90)
                continue
            }
            val current = main.currentMediaItem
            val nextIndex = main.nextMediaItemIndex
            val next = if (nextIndex != C.INDEX_UNSET && nextIndex in 0 until main.mediaItemCount) main.getMediaItemAt(nextIndex) else null
            val duration = main.duration
            if (current == null || next == null || duration <= 0L) { delay(120); continue }
            val key = "${current.mediaId}->${next.mediaId}:${config.mode}:${config.autoCalculate}:${config.durationMs}"
            if (plannedKey != key) {
                plannedKey = key
                plannedPlan = buildPlan(config, current, next)
            }
            val plan = plannedPlan
            if (plan?.enabled == true && plan.fadeStartMs != null && main.currentPosition >= plan.fadeStartMs - 80L) {
                begin(plan, next)
            }
            delay(75)
        }
    }

    private suspend fun buildPlan(config: AuraMixConfig, current: MediaItem, next: MediaItem): TransitionPlan {
        val mode = if (config.autoCalculate) AuraMixMode.SMART else config.mode
        if (mode == AuraMixMode.TRADITIONAL) {
            val currentDuration = current.mediaMetadata.durationMs ?: 0L
            val nextDuration = next.mediaMetadata.durationMs ?: currentDuration
            return TransitionPlanner.plan(mode, config.durationMs,
                AudioFeatures(currentDuration, 0, currentDuration, 0, 0, -18.0, -1.0, .5),
                AudioFeatures(nextDuration, 0, nextDuration, 0, 0, -18.0, -1.0, .5))
        }
        val currentFeatures = feature(current, AudioEdge.TAIL)
        val nextFeatures = feature(next, AudioEdge.HEAD)
        val planned = TransitionPlanner.plan(mode, config.durationMs, currentFeatures, nextFeatures)
        // A local item can fail codec inspection (unsupported container, protected URI, or a
        // decoder that does not expose PCM). Keep the smart switch useful by falling back to a
        // bounded dynamic fade instead of silently disabling AuraMix.
        if (planned.enabled || mode != AuraMixMode.SMART) return planned
        val duration = currentFeatures?.durationMs ?: current.mediaMetadata.durationMs ?: 0L
        return if (duration > 1_500L && isLocal(current) && isLocal(next)) {
            TransitionPlanner.plan(AuraMixMode.TRADITIONAL, config.durationMs.coerceAtMost(duration / 3),
                AudioFeatures(duration, 0, duration, 0, 0, -18.0, -1.0, .5),
                AudioFeatures(next.mediaMetadata.durationMs ?: duration, 0, next.mediaMetadata.durationMs ?: duration, 0, 0, -18.0, -1.0, .5))
        } else TransitionPlan.CUT
    }

    private fun isLocal(item: MediaItem): Boolean {
        val scheme = item.localConfiguration?.uri?.scheme.orEmpty()
        return scheme != "aura" && scheme != "http" && scheme != "https"
    }

    private suspend fun feature(item: MediaItem, edge: AudioEdge): AudioFeatures? {
        val key = "${item.mediaId}:${edge.name}"
        features[key]?.let { return it }
        val uri = item.localConfiguration?.uri ?: return null
        if (uri.scheme == "aura" || uri.scheme == "http" || uri.scheme == "https") return null
        val value = AudioAnalyzer.analyze(context, uri, edge) ?: return null
        features[key] = value
        return value
    }

    private suspend fun begin(plan: TransitionPlan, next: MediaItem) {
        if (transition != null || !main.isPlaying) return
        val current = main.currentMediaItem ?: return
        runCatching {
            val base = main.volume.coerceIn(0f, 1f)
            val nextIndex = main.nextMediaItemIndex
            if (!isQueuedNext(nextIndex, next.mediaId)) {
                return
            }
            transition = ActiveTransition(current.mediaId, next.mediaId, plan, base, nextIndex)
            settings.publishVisual(AuraMixVisualTransition(
                current.mediaMetadata.artworkUri?.toString().orEmpty(), current.mediaMetadata.title?.toString().orEmpty(), current.mediaMetadata.artist?.toString().orEmpty(),
                next.mediaMetadata.artworkUri?.toString().orEmpty(), next.mediaMetadata.title?.toString().orEmpty(), next.mediaMetadata.artist?.toString().orEmpty(), 0f))
            val start = android.os.SystemClock.elapsedRealtime()
            while (transition != null && main.currentMediaItem?.mediaId == current.mediaId && main.isPlaying) {
                val start = plan.fadeStartMs ?: (main.duration - plan.overlapMs)
                val end = plan.fadeEndMs ?: main.duration
                val progress = ((main.currentPosition - start).toDouble() / (end - start).coerceAtLeast(1L)).coerceIn(0.0, 1.0)
                val (out, into) = CrossfadeCurve.gains(progress)
                main.volume = (base * out).toFloat()
                settings.publishVisual(settings.visual.value?.copy(progress = progress.toFloat()))
                if (progress >= 1.0) break
                delay(12)
            }
            // Advance the existing Media3 player once, atomically selecting the queue item and
            // its analyzed content start. There is no second renderer to synchronize and no
            // follow-up seek after the transition.
            if (transition != null && main.currentMediaItem?.mediaId == current.mediaId && main.isPlaying) {
                val index = main.nextMediaItemIndex
                if (isQueuedNext(index, next.mediaId)) {
                    main.seekTo(index, plan.nextStartMs.coerceAtLeast(0L))
                } else {
                    cancelTransition()
                }
            }
            if (android.os.SystemClock.elapsedRealtime() - start > plan.overlapMs + 2_000L) cancelTransition()
        }.onFailure { if (it !is CancellationException) cancelTransition() }
    }

    private fun isQueuedNext(index: Int, mediaId: String): Boolean =
        index != C.INDEX_UNSET && index in 0 until main.mediaItemCount && main.getMediaItemAt(index).mediaId == mediaId

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val active = transition ?: return
        if (synchronizing) {
            return
        }
        if (mediaItem?.mediaId != active.nextId) { cancelTransition(); return }
        synchronizing = true
        scope.launch(Dispatchers.Main.immediate) {
            try {
                // Keep the normal Media3 renderer alive through the item transition. Waiting for
                // READY or crossfading a second AudioTrack here adds the exact audible stall we
                // are avoiding.
                main.volume = active.baseVolume
            } finally {
                transition = null; synchronizing = false; plannedKey = null; plannedPlan = null
                settings.publishVisual(null)
            }
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // Media3 reports seeks, repeat and automatic transitions as discontinuities. The item
        // transition callback is the authoritative handoff point; cancelling here races it.
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) cancelTransition()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) cancelTransition()
    }

    fun close() {
        watcher?.cancel(); watcher = null
        cancelTransition()
        main.removeListener(this)
    }

    private fun stopSecondary() {
    }

    private fun cancelTransition() {
        val active = transition
        transition = null
        plannedPlan = null
        if (!synchronizing && active != null) main.volume = active.baseVolume
        stopSecondary()
        settings.publishVisual(null)
    }
}
