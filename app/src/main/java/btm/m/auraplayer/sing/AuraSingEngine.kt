package btm.m.auraplayer.sing

import androidx.media3.common.C
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.audio.OutputWaveform
import btm.m.auraplayer.spatialcover.SpatialModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

sealed interface AuraSingTrackState {
    data object Idle : AuraSingTrackState
    data class MissingModels(val missingRuntime: Boolean, val missingModel: Boolean) : AuraSingTrackState
    data class Separating(val progress: Float) : AuraSingTrackState
    data class Ready(val entry: AuraSingCacheEntry) : AuraSingTrackState
    data class Failed(val message: String) : AuraSingTrackState
}

data class AuraSingUiState(
    val enabled: Boolean = false,
    val model: AuraSingModel = AuraSingModel.DEFAULT,
    val hardwareAcceleration: Boolean = true,
    val activeInPlayer: Boolean = false,
    val vocalVolume: Float = AuraSingMath.DEFAULT_ACTIVE_VOCAL_VOLUME,
    val trackKey: String = "",
    val trackTitle: String = "",
    val trackArtist: String = "",
    val activeBackend: String = "",
    val trackState: AuraSingTrackState = AuraSingTrackState.Idle,
) {
    val isLiveSeparatingAudio: Boolean
        get() = enabled && activeInPlayer && trackState is AuraSingTrackState.Ready
}

/**
 * Coordinates Aura Sing state, HTDemucs stem separation jobs, real-time PCM vocal/instrumental mixing,
 * and dual OutputWaveform taps for real-time separated Vocal and Instrument spectrum rendering.
 */
class AuraSingEngine(private val app: AuraApplication) {
    val settings = AuraSingSettings(app)
    val cache = AuraSingCache(app)

    val vocalWaveform = OutputWaveform()
    val instrumentWaveform = OutputWaveform()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val separatorMutex = Mutex()
    private var currentJob: Job? = null

    private data class TrackDescriptor(
        val songKey: String,
        val uriString: String,
        val title: String,
        val artist: String,
        val album: String,
    )

    private var currentTrack: TrackDescriptor? = null

    private val mutableUi = MutableStateFlow(
        AuraSingUiState(
            enabled = settings.value.enabled,
            model = settings.value.model,
            hardwareAcceleration = settings.value.hardwareAcceleration,
        ),
    )
    val uiState = mutableUi.asStateFlow()

    // ---- Audio-thread bridge state ----
    @Volatile private var activeModelVolume: Float = 1f
    @Volatile private var activeSingAudio: Boolean = false
    val isAudioMixingActive: Boolean get() = activeSingAudio
    @Volatile private var pendingAnchorSongUs: Long = 0L
    @Volatile private var anchorRevision: Long = 0L

    private val readerLock = Any()
    private var activeEntryId: String = ""
    private var activeVocalsFile: File? = null
    private var activeInstFile: File? = null
    private var activeTotalFrames: Long = 0L

    // Audio-thread-exclusive fields (plus readerLock when switching entry files)
    private var lastAnchorRevision = -1L
    private var anchoredSinkPtsUs = Long.MIN_VALUE
    private var anchoredSongUs = 0L
    private var lastChunkEndPtsUs = Long.MIN_VALUE
    private var currentStemMixWeight = 0f

    private var blockReader: DualStemBlockReader? = null
    private var vocalScratch = ShortArray(0)
    private var instScratch = ShortArray(0)
    private var vocalByteScratch = ByteArray(0)
    private var instByteScratch = ByteArray(0)

    init {
        scope.launch {
            settings.state.collect { cfg ->
                val cur = mutableUi.value
                val nextActive = if (cfg.enabled) cur.activeInPlayer else false
                mutableUi.value = cur.copy(
                    enabled = cfg.enabled,
                    model = cfg.model,
                    hardwareAcceleration = cfg.hardwareAcceleration,
                    activeInPlayer = nextActive,
                )
                syncAudioThreadFlags()
                refreshTrackStems(autoSeparateIfActive = true)
            }
        }
    }

    fun setSettingEnabled(enabled: Boolean) {
        settings.setEnabled(enabled)
        if (!enabled) {
            currentJob?.cancel()
            deactivateInPlayer()
        }
    }

    fun setModel(model: AuraSingModel) {
        settings.setModel(model)
    }

    fun setHardwareAcceleration(enabled: Boolean) {
        settings.setHardwareAcceleration(enabled)
    }

    fun activateInPlayer(initialVolume: Float = AuraSingMath.DEFAULT_ACTIVE_VOCAL_VOLUME) {
        if (!settings.value.enabled) return
        val clamped = initialVolume.coerceIn(0f, AuraSingMath.VOCAL_EXIT_THRESHOLD - 0.01f)
        mutableUi.value = mutableUi.value.copy(
            activeInPlayer = true,
            vocalVolume = clamped,
        )
        syncAudioThreadFlags()
        refreshTrackStems(autoSeparateIfActive = true)
    }

    fun setVocalVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        if (AuraSingMath.shouldExitAuraSing(clamped)) {
            deactivateInPlayer()
            return
        }
        if (!settings.value.enabled) return
        val wasInactive = !mutableUi.value.activeInPlayer
        mutableUi.value = mutableUi.value.copy(
            activeInPlayer = true,
            vocalVolume = clamped,
        )
        syncAudioThreadFlags()
        if (wasInactive) {
            refreshTrackStems(autoSeparateIfActive = true)
        }
    }

    fun deactivateInPlayer() {
        mutableUi.value = mutableUi.value.copy(
            activeInPlayer = false,
            vocalVolume = 1f,
        )
        syncAudioThreadFlags()
        vocalWaveform.reset()
        instrumentWaveform.reset()
    }

    fun retrySeparation() {
        refreshTrackStems(autoSeparateIfActive = true)
    }

    fun refreshReadiness() {
        refreshTrackStems(autoSeparateIfActive = mutableUi.value.activeInPlayer)
    }

    fun notifyTrackChanged(
        songKey: String,
        uriString: String,
        title: String,
        artist: String,
        album: String,
        positionMs: Long,
    ) {
        val prevKey = currentTrack?.songKey
        currentTrack = if (songKey.isBlank() || uriString.isBlank()) null else TrackDescriptor(
            songKey = songKey,
            uriString = uriString,
            title = title,
            artist = artist,
            album = album,
        )
        pendingAnchorSongUs = positionMs.coerceAtLeast(0L) * 1000L
        anchorRevision++
        if (prevKey != songKey) {
            vocalWaveform.reset()
            instrumentWaveform.reset()
            mutableUi.value = mutableUi.value.copy(
                trackKey = songKey,
                trackTitle = title,
                trackArtist = artist,
                trackState = AuraSingTrackState.Idle,
            )
        }
        refreshTrackStems(autoSeparateIfActive = mutableUi.value.activeInPlayer)
    }

    fun notifyPositionDiscontinuity(positionMs: Long) {
        pendingAnchorSongUs = positionMs.coerceAtLeast(0L) * 1000L
        anchorRevision++
    }

    fun onAudioSinkFlush() {
        anchoredSinkPtsUs = Long.MIN_VALUE
        lastChunkEndPtsUs = Long.MIN_VALUE
        vocalWaveform.reset()
        instrumentWaveform.reset()
    }

    fun onAudioSinkDiscontinuity() {
        anchoredSinkPtsUs = Long.MIN_VALUE
        lastChunkEndPtsUs = Long.MIN_VALUE
        vocalWaveform.reset()
        instrumentWaveform.reset()
    }

    fun onCacheCleared() {
        clearAudioEntry()
        refreshTrackStems(autoSeparateIfActive = false)
    }

    fun onCacheEntryDeleted(entryId: String) {
        if (activeEntryId == entryId) {
            clearAudioEntry()
            refreshTrackStems(autoSeparateIfActive = false)
        }
    }

    /**
     * Called inside ExoPlayer's audio thread (`ProcessingAudioSink`) before EQ/DSP processing.
     * Mutates [chunk] in-place when Aura Sing is active and separated stems are ready for the track,
     * and feeds real-time separated Vocal and Instrument PCM streams into [vocalWaveform] and [instrumentWaveform].
     */
    fun processAudioChunk(
        chunk: ByteBuffer,
        chunkTimeUs: Long,
        encoding: Int,
        channels: Int,
        sampleRate: Int,
    ) {
        val rev = anchorRevision
        if (rev != lastAnchorRevision || anchoredSinkPtsUs == Long.MIN_VALUE) {
            lastAnchorRevision = rev
            anchoredSinkPtsUs = chunkTimeUs
            anchoredSongUs = pendingAnchorSongUs
        } else if (lastChunkEndPtsUs != Long.MIN_VALUE && kotlin.math.abs(chunkTimeUs - lastChunkEndPtsUs) > 120_000L) {
            // Re-anchor smoothly if decoder jumped unexpectedly without a sink flush.
            anchoredSinkPtsUs = chunkTimeUs
            anchoredSongUs = pendingAnchorSongUs
        }

        val sampleBytes = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            else -> 4
        }
        val frameBytes = sampleBytes * channels.coerceAtLeast(1)
        val sinkFrames = chunk.remaining() / frameBytes
        lastChunkEndPtsUs = chunkTimeUs + sinkFrames.toLong() * 1_000_000L / sampleRate.coerceAtLeast(1)

        if (!activeSingAudio || sinkFrames <= 0) {
            currentStemMixWeight = 0f
            return
        }

        val songTimeUs = anchoredSongUs + (chunkTimeUs - anchoredSinkPtsUs)
        val songPosSamples44100 = songTimeUs.coerceAtLeast(0L).toDouble() * AuraSingMath.HTDEMUCS_SAMPLE_RATE / 1_000_000.0
        val stepPerSinkFrame44100 = AuraSingMath.HTDEMUCS_SAMPLE_RATE.toDouble() / sampleRate.coerceAtLeast(1).toDouble()
        val maxNeeded44100 = (songPosSamples44100 + sinkFrames * stepPerSinkFrame44100 + 2.0).roundToLong()

        val reader = ensureReader() ?: return
        val window = reader.ensureWindow(songPosSamples44100.toLong().coerceAtLeast(0L), maxNeeded44100) ?: return

        val rampStep = (sinkFrames.toFloat() / (sampleRate * 0.025f).coerceAtLeast(1f)).coerceIn(0f, 1f)
        currentStemMixWeight = (currentStemMixWeight + rampStep).coerceAtMost(1f)

        val needVocalSpectrum = vocalWaveform.enabled || vocalWaveform.spectrumEnabled
        val needInstSpectrum = instrumentWaveform.enabled || instrumentWaveform.spectrumEnabled
        val outVocalShort = if (needVocalSpectrum) ensureShortScratch(true, sinkFrames * 2) else null
        val outInstShort = if (needInstSpectrum) ensureShortScratch(false, sinkFrames * 2) else null

        AuraSingMath.mixStemsIntoPcmBuffer(
            sinkBuffer = chunk,
            vocalsStereo = window.vocals,
            instStereo = window.inst,
            stemWindowStartFrame = window.startFrame,
            songPositionSamples44100 = songPosSamples44100,
            stepPerSinkFrame44100 = stepPerSinkFrame44100,
            vocalGain = activeModelVolume,
            stemMixWeight = currentStemMixWeight,
            encoding = encoding,
            channels = channels,
            outVocalStereo = outVocalShort,
            outInstStereo = outInstShort,
        )

        if (outVocalShort != null) {
            offerShortsToWaveform(vocalWaveform, outVocalShort, sinkFrames, sampleRate, isVocal = true)
        }
        if (outInstShort != null) {
            offerShortsToWaveform(instrumentWaveform, outInstShort, sinkFrames, sampleRate, isVocal = false)
        }
    }

    private fun refreshTrackStems(autoSeparateIfActive: Boolean) {
        val cfg = settings.value
        val track = currentTrack
        if (!cfg.enabled || track == null) {
            currentJob?.cancel()
            clearAudioEntry()
            mutableUi.value = mutableUi.value.copy(trackState = AuraSingTrackState.Idle)
            syncAudioThreadFlags()
            return
        }

        val cached = cache.findEntry(track.songKey, cfg.model.id)
        if (cached != null) {
            currentJob?.cancel()
            attachAudioEntry(cached)
            mutableUi.value = mutableUi.value.copy(trackState = AuraSingTrackState.Ready(cached))
            syncAudioThreadFlags()
            return
        }

        clearAudioEntry()
        val missingRuntime = !SpatialModelStore.isRuntimeReady(app)
        val missingModel = !SpatialModelStore.isReady(app, cfg.model.resource)
        if (missingRuntime || missingModel) {
            currentJob?.cancel()
            mutableUi.value = mutableUi.value.copy(
                trackState = AuraSingTrackState.MissingModels(
                    missingRuntime = missingRuntime,
                    missingModel = missingModel,
                ),
            )
            syncAudioThreadFlags()
            return
        }

        if (!autoSeparateIfActive && !mutableUi.value.activeInPlayer) {
            mutableUi.value = mutableUi.value.copy(trackState = AuraSingTrackState.Idle)
            syncAudioThreadFlags()
            return
        }

        val jobKey = "${track.songKey}|${cfg.model.id}"
        if (currentJob?.isActive == true && lastPreparedJobKey == jobKey) return
        lastPreparedJobKey = jobKey
        currentJob?.cancel()
        mutableUi.value = mutableUi.value.copy(trackState = AuraSingTrackState.Separating(0f))
        syncAudioThreadFlags()

        currentJob = scope.launch {
            try {
                val entry = separatorMutex.withLock {
                    AuraSingSeparator.separateTrack(
                        app = app,
                        cache = cache,
                        model = cfg.model,
                        songKey = track.songKey,
                        uriString = track.uriString,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        onBackendSelected = { backend ->
                            scope.launch(Dispatchers.Main.immediate) {
                                if (currentTrack?.songKey == track.songKey && settings.value.model == cfg.model) {
                                    mutableUi.value = mutableUi.value.copy(activeBackend = backend)
                                }
                            }
                        },
                        onProgress = { progress ->
                            scope.launch(Dispatchers.Main.immediate) {
                                if (currentTrack?.songKey == track.songKey && settings.value.model == cfg.model) {
                                    mutableUi.value = mutableUi.value.copy(
                                        trackState = AuraSingTrackState.Separating(progress),
                                    )
                                }
                            }
                        },
                    )
                }
                if (currentTrack?.songKey == track.songKey && settings.value.model == cfg.model) {
                    attachAudioEntry(entry)
                    mutableUi.value = mutableUi.value.copy(trackState = AuraSingTrackState.Ready(entry))
                    syncAudioThreadFlags()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                if (currentTrack?.songKey == track.songKey) {
                    mutableUi.value = mutableUi.value.copy(
                        trackState = AuraSingTrackState.Failed(t.message ?: "分离失败"),
                    )
                    syncAudioThreadFlags()
                }
            }
        }
    }

    private var lastPreparedJobKey = ""

    private fun attachAudioEntry(entry: AuraSingCacheEntry) {
        synchronized(readerLock) {
            if (activeEntryId == entry.id) return
            blockReader?.close()
            blockReader = null
            activeEntryId = entry.id
            activeVocalsFile = cache.vocalsFile(entry.id)
            activeInstFile = cache.instrumentalFile(entry.id)
            activeTotalFrames = entry.totalFrames
        }
    }

    private fun clearAudioEntry() {
        synchronized(readerLock) {
            blockReader?.close()
            blockReader = null
            activeEntryId = ""
            activeVocalsFile = null
            activeInstFile = null
            activeTotalFrames = 0L
        }
    }

    private fun syncAudioThreadFlags() {
        val state = mutableUi.value
        activeModelVolume = state.vocalVolume
        activeSingAudio = state.enabled && state.activeInPlayer && activeEntryId.isNotEmpty()
        if (!activeSingAudio) {
            currentStemMixWeight = 0f
        }
    }

    private fun ensureReader(): DualStemBlockReader? {
        blockReader?.let { existing ->
            if (existing.entryId == activeEntryId) return existing
        }
        return synchronized(readerLock) {
            if (activeEntryId.isEmpty()) return@synchronized null
            blockReader?.let { if (it.entryId == activeEntryId) return@synchronized it }
            blockReader?.close()
            val vFile = activeVocalsFile ?: return@synchronized null
            val iFile = activeInstFile ?: return@synchronized null
            if (!vFile.isFile || !iFile.isFile) return@synchronized null
            DualStemBlockReader(activeEntryId, vFile, iFile, activeTotalFrames).also {
                blockReader = it
            }
        }
    }

    private fun ensureShortScratch(isVocal: Boolean, needed: Int): ShortArray {
        return if (isVocal) {
            if (vocalScratch.size < needed) vocalScratch = ShortArray(needed)
            vocalScratch
        } else {
            if (instScratch.size < needed) instScratch = ShortArray(needed)
            instScratch
        }
    }

    private fun offerShortsToWaveform(
        waveform: OutputWaveform,
        shorts: ShortArray,
        frames: Int,
        sampleRate: Int,
        isVocal: Boolean,
    ) {
        val sampleCount = frames * 2
        val neededBytes = sampleCount * 2
        var bytes = if (isVocal) vocalByteScratch else instByteScratch
        if (bytes.size < neededBytes) {
            bytes = ByteArray(neededBytes)
            if (isVocal) vocalByteScratch = bytes else instByteScratch = bytes
        }
        val bb = ByteBuffer.wrap(bytes, 0, neededBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(shorts, 0, sampleCount)
        bb.position(0).limit(neededBytes)
        waveform.offer(bb, C.ENCODING_PCM_16BIT, 2, sampleRate)
    }

    private class StemWindow(val startFrame: Long, val vocals: ShortArray, val inst: ShortArray)

    private class DualStemBlockReader(
        val entryId: String,
        vocalsFile: File,
        instFile: File,
        private val totalFrames: Long,
    ) {
        companion object {
            private const val BLOCK_FRAMES = 32_768L // ~740 ms of stereo 44,100 Hz PCM
        }

        private val vocalsRaf = RandomAccessFile(vocalsFile, "r")
        private val instRaf = RandomAccessFile(instFile, "r")
        private var windowStart = -1L
        private var windowFrameCount = 0
        private var vocalsBuf = ShortArray(0)
        private var instBuf = ShortArray(0)
        private var rawBytes = ByteArray(0)

        fun ensureWindow(fromFrame: Long, toFrame: Long): StemWindow? {
            if (totalFrames <= 0L) return null
            val clampedFrom = fromFrame.coerceIn(0L, totalFrames - 1L)
            val clampedTo = toFrame.coerceIn(clampedFrom + 1L, totalFrames)
            if (windowStart >= 0L && clampedFrom >= windowStart && clampedTo <= windowStart + windowFrameCount) {
                return StemWindow(windowStart, vocalsBuf, instBuf)
            }
            val start = clampedFrom
            val neededFrames = maxOf(BLOCK_FRAMES, clampedTo - start)
            val available = (totalFrames - start).coerceIn(0L, neededFrames).toInt()
            if (available <= 0) return null
            val byteLen = available * 4
            if (rawBytes.size < byteLen) rawBytes = ByteArray(byteLen)
            val sampleLen = available * 2
            if (vocalsBuf.size < sampleLen) vocalsBuf = ShortArray(sampleLen)
            if (instBuf.size < sampleLen) instBuf = ShortArray(sampleLen)

            val fileOffset = start * 4L
            vocalsRaf.seek(fileOffset)
            readFullyOrZero(vocalsRaf, rawBytes, byteLen)
            ByteBuffer.wrap(rawBytes, 0, byteLen).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(vocalsBuf, 0, sampleLen)

            instRaf.seek(fileOffset)
            readFullyOrZero(instRaf, rawBytes, byteLen)
            ByteBuffer.wrap(rawBytes, 0, byteLen).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(instBuf, 0, sampleLen)

            windowStart = start
            windowFrameCount = available
            return StemWindow(windowStart, vocalsBuf, instBuf)
        }

        private fun readFullyOrZero(raf: RandomAccessFile, dst: ByteArray, len: Int) {
            var readTotal = 0
            while (readTotal < len) {
                val n = raf.read(dst, readTotal, len - readTotal)
                if (n < 0) break
                readTotal += n
            }
            if (readTotal < len) dst.fill(0, readTotal, len)
        }

        fun close() {
            runCatching { vocalsRaf.close() }
            runCatching { instRaf.close() }
        }
    }
}
