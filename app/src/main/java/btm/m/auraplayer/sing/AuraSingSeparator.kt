package btm.m.auraplayer.sing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.RunOptions
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.playback.PlaybackAudioCache
import btm.m.auraplayer.playback.PlaybackPreferences
import btm.m.auraplayer.spatialcover.SpatialModelStore
import btm.m.auraplayer.spatialcover.SpatialRuntimeLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit

object AuraSingSeparator {
    private const val TAG = "AuraSingSeparator"
    private const val DECODER_TIMEOUT_US = 2_000L
    private const val ENCODING_PCM_24BIT_PACKED = 21
    private const val ENCODING_PCM_32BIT = 22

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun separateTrack(
        app: AuraApplication,
        cache: AuraSingCache,
        model: AuraSingModel,
        songKey: String,
        uriString: String,
        title: String,
        artist: String,
        album: String,
        onBackendSelected: (String) -> Unit = {},
        onProgress: (Float) -> Unit,
    ): AuraSingCacheEntry = withContext(Dispatchers.IO) {
        if (!SpatialModelStore.isRuntimeReady(app)) error("ONNX Runtime 尚未下载")
        if (!SpatialModelStore.isReady(app, model.resource)) error("${model.fallbackLabel} 模型尚未下载")

        cache.findEntry(songKey, model.id)?.let {
            onProgress(1f)
            return@withContext it
        }

        val workDir = app.cacheDir.resolve("aura_sing_tmp").apply { mkdirs() }
        var tempAudioFile: File? = null
        val decodedStereoFile = File.createTempFile("decoded_44k_", ".f32", workDir)
        val vocalsTemp = File.createTempFile("vocals_", ".pcm", workDir)
        val instTemp = File.createTempFile("inst_", ".pcm", workDir)

        try {
            onProgress(0.02f)
            val sourceUri = resolvePlayableSource(app, uriString, workDir) { downloadedFile ->
                tempAudioFile = downloadedFile
            }
            currentCoroutineContext().ensureActive()

            onProgress(0.08f)
            val totalFrames = decodeAudioToStereo44100File(
                context = app,
                uri = sourceUri,
                outFile = decodedStereoFile,
                onDecodeProgress = { frac ->
                    onProgress((0.08f + frac * 0.10f).coerceIn(0.08f, 0.18f))
                },
            )
            if (totalFrames <= 0) error("无法解码音频轨道")
            currentCoroutineContext().ensureActive()

            val modelFile = SpatialModelStore.file(app, model.resource)
            val hwAccel = app.auraSing.settings.value.hardwareAcceleration
            val sessionHandle = AuraSingQualcommNpu.createAcceleratedSession(
                context = app,
                modelPath = modelFile.absolutePath,
                cacheTag = model.id,
                isInt8Quantized = model == AuraSingModel.HTDEMUCS_INT8,
                preferQnn = hwAccel && model != AuraSingModel.HTDEMUCS_INT8,
                useXnnpack = hwAccel,
                qnnCacheSubdir = "aura_sing_qnn_cache",
            )
            onBackendSelected(sessionHandle.backendLabel)
            Log.i(TAG, "Separating \"$title\" using ${model.id} on backend=${sessionHandle.backendLabel}")
            val session = sessionHandle.session
            val runOptions = sessionHandle.runOptions
            val inferStartMs = SystemClock.elapsedRealtime()

            val vocalsWriteBuf = Pcm16BufferedFileWriter(vocalsTemp)
            val instWriteBuf = Pcm16BufferedFileWriter(instTemp)

            try {
                val env = OrtEnvironment.getEnvironment()
                when (model.arch) {
                    AuraSingArch.MDX_NET -> separateWithMdxNet(
                        env = env,
                        session = session,
                        runOptions = runOptions,
                        model = model,
                        decodedStereoFile = decodedStereoFile,
                        totalFrames = totalFrames,
                        vocalsWriteBuf = vocalsWriteBuf,
                        instWriteBuf = instWriteBuf,
                        onProgress = onProgress,
                    )
                    AuraSingArch.HTDEMUCS -> separateWithHtDemucs(
                        env = env,
                        session = session,
                        runOptions = runOptions,
                        decodedStereoFile = decodedStereoFile,
                        totalFrames = totalFrames,
                        vocalsWriteBuf = vocalsWriteBuf,
                        instWriteBuf = instWriteBuf,
                        onProgress = onProgress,
                    )
                }
            } finally {
                vocalsWriteBuf.close()
                instWriteBuf.close()
                sessionHandle.close()
            }
            Log.i(
                TAG,
                "Finished separating \"$title\" (${model.id}) on ${sessionHandle.backendLabel} in ${SystemClock.elapsedRealtime() - inferStartMs}ms",
            )

            currentCoroutineContext().ensureActive()
            onProgress(0.98f)

            val durationMs = totalFrames.toLong() * 1000L / AuraSingMath.HTDEMUCS_SAMPLE_RATE
            val committed = cache.commitEntry(
                songKey = songKey,
                title = title,
                artist = artist,
                album = album,
                modelId = model.id,
                totalFrames = totalFrames,
                durationMs = durationMs,
                vocalsTemp = vocalsTemp,
                instTemp = instTemp,
            )
            onProgress(1f)
            committed
        } catch (cancel: CancellationException) {
            vocalsTemp.delete()
            instTemp.delete()
            throw cancel
        } catch (t: Throwable) {
            vocalsTemp.delete()
            instTemp.delete()
            Log.e(TAG, "Stem separation failed for $title (${model.id})", t)
            throw t
        } finally {
            decodedStereoFile.delete()
            tempAudioFile?.delete()
        }
    }

    private suspend fun separateWithMdxNet(
        env: OrtEnvironment,
        session: OrtSession,
        runOptions: RunOptions?,
        model: AuraSingModel,
        decodedStereoFile: File,
        totalFrames: Int,
        vocalsWriteBuf: Pcm16BufferedFileWriter,
        instWriteBuf: Pcm16BufferedFileWriter,
        onProgress: (Float) -> Unit,
    ) {
        val stft = MdxStftProcessor(
            nFft = model.nFft,
            hopLength = model.hopLength,
            dimF = model.dimF,
            dimT = model.dimT,
        )
        val chunkSize = stft.chunkSize
        val trim = stft.trim
        val genSize = stft.genSize.coerceAtLeast(1)
        val totalChunks = ((totalFrames + genSize - 1) / genSize).coerceAtLeast(1)
        val leftMixChunk = FloatArray(chunkSize)
        val rightMixChunk = FloatArray(chunkSize)
        val predLeft = FloatArray(chunkSize)
        val predRight = FloatArray(chunkSize)
        val tensorSize = 4 * model.dimF * model.dimT
        val inputBuffer = FloatBuffer.allocate(tensorSize)
        val inputShape = longArrayOf(1L, 4L, model.dimF.toLong(), model.dimT.toLong())
        val outputScratch = FloatArray(tensorSize)
        val byteScratch = ByteArray(chunkSize * 8)
        val inputName = session.inputNames.firstOrNull() ?: "input"
        val comp = model.compensate
        val vocalsIsPrimary = model.primaryStem == AuraSingPrimaryStem.VOCALS

        RandomAccessFile(decodedStereoFile, "r").use { raf ->
            for (chunkIdx in 0 until totalChunks) {
                currentCoroutineContext().ensureActive()
                val songStart = chunkIdx * genSize
                val emitCount = minOf(genSize, totalFrames - songStart)
                if (emitCount <= 0) break

                readStereoWindowZeroPadded(
                    raf = raf,
                    windowStartFrame = songStart - trim,
                    windowFrames = chunkSize,
                    totalSongFrames = totalFrames,
                    byteScratch = byteScratch,
                    leftOut = leftMixChunk,
                    rightOut = rightMixChunk,
                )

                inputBuffer.clear()
                stft.forwardStereo(leftMixChunk, rightMixChunk, inputBuffer.array(), zeroLowBins = true)
                inputBuffer.rewind()

                OnnxTensor.createTensor(env, inputBuffer, inputShape).use { tensor ->
                    val results = if (runOptions != null) {
                        session.run(mapOf(inputName to tensor), runOptions)
                    } else {
                        session.run(mapOf(inputName to tensor))
                    }
                    results.use { r ->
                        val outTensor = r[0] as OnnxTensor
                        val outBuf = outTensor.floatBuffer
                        val count = minOf(outBuf.remaining(), outputScratch.size)
                        outBuf.get(outputScratch, 0, count)
                    }
                }

                stft.inverseStereo(outputScratch, predLeft, predRight)

                for (i in 0 until emitCount) {
                    val cIdx = trim + i
                    val primL = predLeft[cIdx] * comp
                    val primR = predRight[cIdx] * comp
                    val secL = leftMixChunk[cIdx] - primL
                    val secR = rightMixChunk[cIdx] - primR
                    val vL: Float
                    val vR: Float
                    val iL: Float
                    val iR: Float
                    if (vocalsIsPrimary) {
                        vL = primL; vR = primR
                        iL = secL; iR = secR
                    } else {
                        iL = primL; iR = primR
                        vL = secL; vR = secR
                    }
                    vocalsWriteBuf.writeStereoFrame(AuraSingMath.floatToPcm16(vL), AuraSingMath.floatToPcm16(vR))
                    instWriteBuf.writeStereoFrame(AuraSingMath.floatToPcm16(iL), AuraSingMath.floatToPcm16(iR))
                }

                val pct = 0.18f + 0.78f * ((chunkIdx + 1).toFloat() / totalChunks.toFloat())
                onProgress(pct.coerceIn(0.18f, 0.96f))
            }
        }
    }

    private suspend fun separateWithHtDemucs(
        env: OrtEnvironment,
        session: OrtSession,
        runOptions: RunOptions?,
        decodedStereoFile: File,
        totalFrames: Int,
        vocalsWriteBuf: Pcm16BufferedFileWriter,
        instWriteBuf: Pcm16BufferedFileWriter,
        onProgress: (Float) -> Unit,
    ) {
        val seg = AuraSingMath.HTDEMUCS_SEGMENT_SAMPLES
        val offsets = AuraSingMath.planChunkOffsets(totalFrames)
        val overlapWriter = AuraSingMath.RollingStemOverlapAddWriter(
            segmentSamples = seg,
            strideSamples = AuraSingMath.HTDEMUCS_STRIDE_SAMPLES,
        )
        val inputBuffer = FloatBuffer.allocate(AuraSingMath.HTDEMUCS_CHANNELS * seg)
        val inputShape = longArrayOf(
            1L,
            AuraSingMath.HTDEMUCS_CHANNELS.toLong(),
            seg.toLong(),
        )
        val chunkBytesScratch = ByteArray(seg * 8)
        val flatOutputScratch = FloatArray(4 * 2 * seg)
        val inputName = session.inputNames.firstOrNull() ?: "mix"

        RandomAccessFile(decodedStereoFile, "r").use { raf ->
            for (chunkIndex in offsets.indices) {
                currentCoroutineContext().ensureActive()
                val offset = offsets[chunkIndex]
                val valid = (totalFrames - offset).coerceIn(0, seg)

                readChunkToPlanarBuffer(
                    raf = raf,
                    offsetFrame = offset,
                    validFrames = valid,
                    segFrames = seg,
                    byteScratch = chunkBytesScratch,
                    planarDst = inputBuffer,
                )

                OnnxTensor.createTensor(env, inputBuffer, inputShape).use { tensor ->
                    val results = if (runOptions != null) {
                        session.run(mapOf(inputName to tensor), runOptions)
                    } else {
                        session.run(mapOf(inputName to tensor))
                    }
                    results.use { r ->
                        val outTensor = r[0] as OnnxTensor
                        val outBuf = outTensor.floatBuffer
                        val count = minOf(outBuf.remaining(), flatOutputScratch.size)
                        outBuf.get(flatOutputScratch, 0, count)
                        overlapWriter.accumulateAndDrain(
                            stemsFlat = flatOutputScratch,
                            validFramesInChunk = valid,
                            isFirst = chunkIndex == 0,
                            isLast = chunkIndex == offsets.lastIndex,
                        ) { vL, vR, iL, iR ->
                            vocalsWriteBuf.writeStereoFrame(vL, vR)
                            instWriteBuf.writeStereoFrame(iL, iR)
                        }
                    }
                }

                val pct = 0.18f + 0.78f * ((chunkIndex + 1).toFloat() / offsets.size.toFloat())
                onProgress(pct.coerceIn(0.18f, 0.96f))
            }
        }
    }

    private fun readStereoWindowZeroPadded(
        raf: RandomAccessFile,
        windowStartFrame: Int,
        windowFrames: Int,
        totalSongFrames: Int,
        byteScratch: ByteArray,
        leftOut: FloatArray,
        rightOut: FloatArray,
    ) {
        val windowEndFrame = windowStartFrame + windowFrames
        val validStart = windowStartFrame.coerceAtLeast(0)
        val validEnd = windowEndFrame.coerceAtMost(totalSongFrames)
        if (validStart >= validEnd) {
            leftOut.fill(0f, 0, windowFrames)
            rightOut.fill(0f, 0, windowFrames)
            return
        }
        val dstOffset = validStart - windowStartFrame
        val validCount = validEnd - validStart
        if (dstOffset > 0) {
            leftOut.fill(0f, 0, dstOffset)
            rightOut.fill(0f, 0, dstOffset)
        }
        val byteLen = validCount * 8
        raf.seek(validStart.toLong() * 8L)
        var totalRead = 0
        while (totalRead < byteLen) {
            val n = raf.read(byteScratch, totalRead, byteLen - totalRead)
            if (n < 0) break
            totalRead += n
        }
        val fb = ByteBuffer.wrap(byteScratch, 0, byteLen).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        for (i in 0 until validCount) {
            leftOut[dstOffset + i] = fb.get(i * 2)
            rightOut[dstOffset + i] = fb.get(i * 2 + 1)
        }
        val tailStart = dstOffset + validCount
        if (tailStart < windowFrames) {
            leftOut.fill(0f, tailStart, windowFrames)
            rightOut.fill(0f, tailStart, windowFrames)
        }
    }

    private fun readChunkToPlanarBuffer(
        raf: RandomAccessFile,
        offsetFrame: Int,
        validFrames: Int,
        segFrames: Int,
        byteScratch: ByteArray,
        planarDst: FloatBuffer,
    ) {
        val byteLen = validFrames * 8
        if (byteLen > 0) {
            raf.seek(offsetFrame.toLong() * 8L)
            var totalRead = 0
            while (totalRead < byteLen) {
                val n = raf.read(byteScratch, totalRead, byteLen - totalRead)
                if (n < 0) break
                totalRead += n
            }
        }
        val fb = ByteBuffer.wrap(byteScratch, 0, byteLen).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val arr = planarDst.array()
        for (i in 0 until validFrames) {
            arr[i] = fb.get(i * 2)
            arr[segFrames + i] = fb.get(i * 2 + 1)
        }
        if (validFrames < segFrames) {
            arr.fill(0f, validFrames, segFrames)
            arr.fill(0f, segFrames + validFrames, segFrames * 2)
        }
        planarDst.clear()
    }

    private class Pcm16BufferedFileWriter(file: File) : AutoCloseable {
        private val stream = BufferedOutputStream(FileOutputStream(file), 65_536)
        private val raw = ByteArray(65_536)
        private var pos = 0

        fun writeStereoFrame(left: Short, right: Short) {
            if (pos + 4 > raw.size) flushBuffer()
            val l = left.toInt()
            val r = right.toInt()
            raw[pos] = (l and 0xFF).toByte()
            raw[pos + 1] = ((l ushr 8) and 0xFF).toByte()
            raw[pos + 2] = (r and 0xFF).toByte()
            raw[pos + 3] = ((r ushr 8) and 0xFF).toByte()
            pos += 4
        }

        private fun flushBuffer() {
            if (pos > 0) {
                stream.write(raw, 0, pos)
                pos = 0
            }
        }

        override fun close() {
            flushBuffer()
            stream.flush()
            stream.close()
        }
    }

    private class FloatStereoFileSink(file: File) : AuraSingMath.PrimitiveStereoSink, AutoCloseable {
        private val stream = BufferedOutputStream(FileOutputStream(file), 65_536)
        private val raw = ByteArray(65_536)
        private val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        private var pos = 0
        var totalFrames: Int = 0
            private set

        override fun push(left: Float, right: Float) {
            if (pos + 8 > raw.size) flushBuffer()
            bb.putFloat(pos, left)
            bb.putFloat(pos + 4, right)
            pos += 8
            totalFrames++
        }

        private fun flushBuffer() {
            if (pos > 0) {
                stream.write(raw, 0, pos)
                pos = 0
            }
        }

        override fun close() {
            flushBuffer()
            stream.flush()
            stream.close()
        }
    }

    private fun resolvePlayableSource(
        app: AuraApplication,
        uriString: String,
        workDir: File,
        onTempCreated: (File) -> Unit,
    ): Uri {
        val parsed = Uri.parse(uriString)
        if (parsed.scheme == "aura" && parsed.host == "netease") {
            val id = parsed.lastPathSegment?.toLongOrNull() ?: error("无效的歌曲 ID")
            val qualityName = parsed.getQueryParameter("quality") ?: PlaybackPreferences(app).quality.level
            val tmp = File.createTempFile("sing_src_", ".audio", workDir)
            onTempCreated(tmp)
            if (PlaybackAudioCache.copyIfFullyCached(app, uriString, tmp)) {
                return Uri.fromFile(tmp)
            }
            val resolvedUrl = app.api.resolveBlocking(id, qualityName)
            if (resolvedUrl.isBlank()) error("无法获取歌曲播放地址")
            val request = Request.Builder()
                .url(resolvedUrl)
                .header("Referer", "https://music.163.com/")
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("音频下载失败 (HTTP ${resp.code})")
                val body = resp.body ?: error("音频响应为空")
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }
            }
            return Uri.fromFile(tmp)
        }
        return parsed
    }

    private fun decodeAudioToStereo44100File(
        context: Context,
        uri: Uri,
        outFile: File,
        onDecodeProgress: (Float) -> Unit,
    ): Int {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val sink = FloatStereoFileSink(outFile)
        try {
            if (uri.scheme == "file") {
                extractor.setDataSource(uri.path ?: error("无效文件路径"))
            } else {
                extractor.setDataSource(context, uri, null)
            }
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) error("未找到音频轨道")
            extractor.selectTrack(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = if (trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                trackFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else AudioFormat.ENCODING_PCM_16BIT
            var resampler = AuraSingMath.StereoResampler44100(sampleRate, channels)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(trackFormat, null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var shortScratch = ShortArray(8192)
            var lastProgressMs = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(DECODER_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, DECODER_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFmt = codec.outputFormat
                        val newRate = if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else sampleRate
                        val newCh = if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else channels
                        pcmEncoding = if (newFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) newFmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        if (newRate != sampleRate || newCh != channels) {
                            sampleRate = newRate
                            channels = newCh
                            resampler = AuraSingMath.StereoResampler44100(sampleRate, channels)
                        }
                    }
                    outIdx >= 0 -> {
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.order(ByteOrder.LITTLE_ENDIAN)

                                val sampleCount = when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT, ENCODING_PCM_32BIT -> info.size / 4
                                    ENCODING_PCM_24BIT_PACKED -> info.size / 3
                                    AudioFormat.ENCODING_PCM_8BIT -> info.size
                                    else -> info.size / 2
                                }
                                if (shortScratch.size < sampleCount) shortScratch = ShortArray(sampleCount)
                                when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> {
                                        val fb = outBuf.asFloatBuffer()
                                        for (s in 0 until sampleCount) {
                                            shortScratch[s] = AuraSingMath.floatToPcm16(fb.get(s))
                                        }
                                    }
                                    ENCODING_PCM_32BIT -> {
                                        val ib = outBuf.asIntBuffer()
                                        for (s in 0 until sampleCount) {
                                            shortScratch[s] = (ib.get(s) shr 16).toShort()
                                        }
                                    }
                                    ENCODING_PCM_24BIT_PACKED -> {
                                        var p = info.offset
                                        for (s in 0 until sampleCount) {
                                            val b1 = outBuf.get(p + 1).toInt() and 0xFF
                                            val b2 = outBuf.get(p + 2).toInt()
                                            shortScratch[s] = ((b2 shl 8) or b1).toShort()
                                            p += 3
                                        }
                                    }
                                    AudioFormat.ENCODING_PCM_8BIT -> {
                                        for (s in 0 until sampleCount) {
                                            shortScratch[s] = (((outBuf.get(info.offset + s).toInt() and 0xFF) - 128) shl 8).toShort()
                                        }
                                    }
                                    else -> {
                                        outBuf.asShortBuffer().get(shortScratch, 0, sampleCount)
                                    }
                                }
                                resampler.processInterleavedShortToSink(shortScratch, sampleCount, sink)
                            }
                        }
                        if (durationUs > 0L) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgressMs >= 120L) {
                                lastProgressMs = now
                                onDecodeProgress((info.presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
            }
            resampler.finishToSink(sink)
            return sink.totalFrames
        } finally {
            runCatching { sink.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
