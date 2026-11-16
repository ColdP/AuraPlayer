package btm.m.auraplayer.sing

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

object AuraSingMath {
    const val HTDEMUCS_SAMPLE_RATE = 44_100
    const val HTDEMUCS_CHANNELS = 2
    const val HTDEMUCS_SEGMENT_SAMPLES = 343_980
    const val HTDEMUCS_OVERLAP_SAMPLES = HTDEMUCS_SEGMENT_SAMPLES / 8 // 42,997 (~0.97s smooth crossfade)
    const val HTDEMUCS_STRIDE_SAMPLES = HTDEMUCS_SEGMENT_SAMPLES - HTDEMUCS_OVERLAP_SAMPLES // 300,983
    const val VOCAL_EXIT_THRESHOLD = 0.99f
    const val DEFAULT_ACTIVE_VOCAL_VOLUME = 0.25f

    /** Pull-to-full vocal volume exits Aura Sing and restores original mix. */
    fun shouldExitAuraSing(vocalVolume: Float): Boolean = vocalVolume >= VOCAL_EXIT_THRESHOLD

    fun buildSegmentWindow(
        segmentSamples: Int = HTDEMUCS_SEGMENT_SAMPLES,
        overlapSamples: Int = HTDEMUCS_OVERLAP_SAMPLES,
        fadeIn: Boolean = true,
        fadeOut: Boolean = true,
    ): FloatArray {
        val window = FloatArray(segmentSamples) { 1f }
        val effOverlap = overlapSamples.coerceIn(0, segmentSamples / 2)
        if (effOverlap <= 0) return window
        val step = (effOverlap - 1).coerceAtLeast(1)
        for (i in 0 until effOverlap) {
            val gain = i.toFloat() / step.toFloat()
            if (fadeIn) window[i] = gain
            if (fadeOut) window[segmentSamples - 1 - i] = gain
        }
        return window
    }

    fun planChunkOffsets(
        totalFrames: Int,
        segmentSamples: Int = HTDEMUCS_SEGMENT_SAMPLES,
        strideSamples: Int = HTDEMUCS_STRIDE_SAMPLES,
    ): IntArray {
        if (totalFrames <= 0) return IntArray(0)
        if (totalFrames <= segmentSamples) return intArrayOf(0)
        val offsets = mutableListOf<Int>()
        var offset = 0
        while (offset < totalFrames) {
            offsets += offset
            if (offset + segmentSamples >= totalFrames) break
            offset += strideSamples.coerceAtLeast(1)
        }
        return offsets.toIntArray()
    }

    interface PrimitiveStereoSink {
        fun push(left: Float, right: Float)
    }

    /**
     * Stateful linear resampler + channel normalizer into stereo 44,100 Hz float32 streams.
     * Supports arbitrary channel counts and sample rates without seam clicks across decoder chunks.
     */
    class StereoResampler44100(
        private val srcRate: Int,
        private val srcChannels: Int,
    ) {
        private var prevLeft = 0f
        private var prevRight = 0f
        private var hasPrev = false
        private var srcPos = 0.0
        private var chunkSize = 0
        private val step = srcRate.coerceAtLeast(1).toDouble() / HTDEMUCS_SAMPLE_RATE.toDouble()

        fun processInterleavedShort(
            pcm: ShortArray,
            sampleCount: Int,
            outLeft: MutableList<Float>,
            outRight: MutableList<Float>,
        ) {
            processInterleavedShortToSink(
                pcm,
                sampleCount,
                object : PrimitiveStereoSink {
                    override fun push(left: Float, right: Float) {
                        outLeft.add(left)
                        outRight.add(right)
                    }
                },
            )
        }

        fun processInterleavedShortToSink(
            pcm: ShortArray,
            sampleCount: Int,
            sink: PrimitiveStereoSink,
        ) {
            val ch = srcChannels.coerceAtLeast(1)
            val frames = sampleCount / ch
            if (frames <= 0) return
            if (srcRate == HTDEMUCS_SAMPLE_RATE && ch == 2) {
                var i = 0
                while (i + 1 < frames * 2) {
                    sink.push(pcm[i] / 32768f, pcm[i + 1] / 32768f)
                    i += 2
                }
                return
            }
            if (srcRate == HTDEMUCS_SAMPLE_RATE && ch == 1) {
                for (f in 0 until frames) {
                    val v = pcm[f] / 32768f
                    sink.push(v, v)
                }
                return
            }

            fun frameL(localIdx: Int): Float = when {
                localIdx < 0 -> prevLeft
                ch == 1 -> pcm[localIdx] / 32768f
                else -> pcm[localIdx * ch] / 32768f
            }

            fun frameR(localIdx: Int): Float = when {
                localIdx < 0 -> prevRight
                ch == 1 -> pcm[localIdx] / 32768f
                else -> pcm[localIdx * ch + 1] / 32768f
            }

            var pos = srcPos - chunkSize.toDouble()
            if (!hasPrev && pos < 0.0) pos = 0.0
            while (pos < frames - 1) {
                val i0 = kotlin.math.floor(pos).toInt()
                val frac = (pos - i0).toFloat()
                val i1 = i0 + 1
                val l0 = frameL(i0)
                val l1 = frameL(i1)
                val r0 = frameR(i0)
                val r1 = frameR(i1)
                sink.push(l0 + (l1 - l0) * frac, r0 + (r1 - r0) * frac)
                pos += step
            }
            prevLeft = frameL(frames - 1)
            prevRight = frameR(frames - 1)
            hasPrev = true
            srcPos = pos
            chunkSize = frames
        }

        /** Flushes the final fractional sample after the last chunk is fed. */
        fun finish(outLeft: MutableList<Float>, outRight: MutableList<Float>) {
            finishToSink(object : PrimitiveStereoSink {
                override fun push(left: Float, right: Float) {
                    outLeft.add(left)
                    outRight.add(right)
                }
            })
        }

        fun finishToSink(sink: PrimitiveStereoSink) {
            if (!hasPrev || srcRate == HTDEMUCS_SAMPLE_RATE) return
            val pos = srcPos - chunkSize.toDouble()
            if (pos in -0.999..0.0) {
                sink.push(prevLeft, prevRight)
            }
        }
    }

    /**
     * Constant-memory (~6.8 MB) streaming overlap-add accumulator for HTDemucs chunks.
     * Since HTDemucs stride (257,985) > half segment (171,990), finalized frames can be flushed
     * directly to 16-bit PCM files after each chunk, avoiding whole-song heap allocations.
     */
    class RollingStemOverlapAddWriter(
        private val segmentSamples: Int = HTDEMUCS_SEGMENT_SAMPLES,
        private val strideSamples: Int = HTDEMUCS_STRIDE_SAMPLES,
    ) {
        private val overlapSamples = (segmentSamples - strideSamples).coerceAtLeast(0)
        private val vocL = FloatArray(segmentSamples)
        private val vocR = FloatArray(segmentSamples)
        private val instL = FloatArray(segmentSamples)
        private val instR = FloatArray(segmentSamples)
        private val weight = FloatArray(segmentSamples)
        private val firstMiddleWindow = buildSegmentWindow(segmentSamples, overlapSamples, fadeIn = false, fadeOut = true)
        private val innerWindow = buildSegmentWindow(segmentSamples, overlapSamples, fadeIn = true, fadeOut = true)
        private val lastOnlyWindow = buildSegmentWindow(segmentSamples, overlapSamples, fadeIn = false, fadeOut = false)
        private val lastTailWindow = buildSegmentWindow(segmentSamples, overlapSamples, fadeIn = true, fadeOut = false)

        fun accumulateAndDrain(
            stemsFlat: FloatArray,
            validFramesInChunk: Int,
            isFirst: Boolean,
            isLast: Boolean,
            onFrame: (vocL: Short, vocR: Short, instL: Short, instR: Short) -> Unit,
        ): Int {
            val valid = validFramesInChunk.coerceIn(0, segmentSamples)
            if (valid <= 0) return 0

            val window = when {
                isFirst && isLast -> lastOnlyWindow
                isFirst -> firstMiddleWindow
                isLast -> lastTailWindow
                else -> innerWindow
            }

            val drumsL = 0
            val drumsR = segmentSamples
            val bassL = 2 * segmentSamples
            val bassR = 3 * segmentSamples
            val otherL = 4 * segmentSamples
            val otherR = 5 * segmentSamples
            val vocalsL = 6 * segmentSamples
            val vocalsR = 7 * segmentSamples

            for (i in 0 until valid) {
                val w = window[i]
                vocL[i] += stemsFlat[vocalsL + i] * w
                vocR[i] += stemsFlat[vocalsR + i] * w
                instL[i] += (stemsFlat[drumsL + i] + stemsFlat[bassL + i] + stemsFlat[otherL + i]) * w
                instR[i] += (stemsFlat[drumsR + i] + stemsFlat[bassR + i] + stemsFlat[otherR + i]) * w
                weight[i] += w
            }

            val emitCount = if (isLast) valid else minOf(valid, strideSamples)
            for (i in 0 until emitCount) {
                val w = weight[i].coerceAtLeast(1e-8f)
                onFrame(
                    floatToPcm16(vocL[i] / w),
                    floatToPcm16(vocR[i] / w),
                    floatToPcm16(instL[i] / w),
                    floatToPcm16(instR[i] / w),
                )
            }

            if (!isLast) {
                val tailCount = (segmentSamples - emitCount).coerceAtLeast(0)
                if (tailCount > 0) {
                    System.arraycopy(vocL, emitCount, vocL, 0, tailCount)
                    System.arraycopy(vocR, emitCount, vocR, 0, tailCount)
                    System.arraycopy(instL, emitCount, instL, 0, tailCount)
                    System.arraycopy(instR, emitCount, instR, 0, tailCount)
                    System.arraycopy(weight, emitCount, weight, 0, tailCount)
                }
                vocL.fill(0f, tailCount, segmentSamples)
                vocR.fill(0f, tailCount, segmentSamples)
                instL.fill(0f, tailCount, segmentSamples)
                instR.fill(0f, tailCount, segmentSamples)
                weight.fill(0f, tailCount, segmentSamples)
            }
            return emitCount
        }
    }

    /**
     * Stateful chunked overlap-add accumulator writing directly into 16-bit interleaved stereo PCM arrays.
     * Avoids holding 4x float copies for the entire song in RAM on Android.
     */
    class StemOverlapAddWriter(
        val totalFrames: Int,
        private val segmentSamples: Int = HTDEMUCS_SEGMENT_SAMPLES,
        private val overlapSamples: Int = HTDEMUCS_OVERLAP_SAMPLES,
    ) {
        private val vocAccumL = FloatArray(totalFrames)
        private val vocAccumR = FloatArray(totalFrames)
        private val instAccumL = FloatArray(totalFrames)
        private val instAccumR = FloatArray(totalFrames)
        private val weightAccum = FloatArray(totalFrames)

        /**
         * Accumulates one ONNX inference output `[1, 4, 2, segmentSamples]` flattened in C-order:
         * index = ((stem * 2 + ch) * segmentSamples) + i, stems = [drums(0), bass(1), other(2), vocals(3)].
         */
        fun accumulateChunk(
            stemsFlat: FloatArray,
            offset: Int,
            isFirst: Boolean,
            isLast: Boolean,
        ) {
            val valid = (totalFrames - offset).coerceIn(0, segmentSamples)
            if (valid <= 0) return
            val window = buildSegmentWindow(
                segmentSamples = segmentSamples,
                overlapSamples = overlapSamples,
                fadeIn = !isFirst,
                fadeOut = !isLast,
            )
            val drumsL = 0
            val drumsR = segmentSamples
            val bassL = 2 * segmentSamples
            val bassR = 3 * segmentSamples
            val otherL = 4 * segmentSamples
            val otherR = 5 * segmentSamples
            val vocalsL = 6 * segmentSamples
            val vocalsR = 7 * segmentSamples

            for (i in 0 until valid) {
                val dst = offset + i
                val w = window[i]
                val vL = stemsFlat[vocalsL + i]
                val vR = stemsFlat[vocalsR + i]
                val iL = stemsFlat[drumsL + i] + stemsFlat[bassL + i] + stemsFlat[otherL + i]
                val iR = stemsFlat[drumsR + i] + stemsFlat[bassR + i] + stemsFlat[otherR + i]
                vocAccumL[dst] += vL * w
                vocAccumR[dst] += vR * w
                instAccumL[dst] += iL * w
                instAccumR[dst] += iR * w
                weightAccum[dst] += w
            }
        }

        fun normalizeToStereoPcm16(vocalsOut: ShortArray, instrumentalOut: ShortArray) {
            for (i in 0 until totalFrames) {
                val w = weightAccum[i].coerceAtLeast(1e-8f)
                vocalsOut[i * 2] = floatToPcm16(vocAccumL[i] / w)
                vocalsOut[i * 2 + 1] = floatToPcm16(vocAccumR[i] / w)
                instrumentalOut[i * 2] = floatToPcm16(instAccumL[i] / w)
                instrumentalOut[i * 2 + 1] = floatToPcm16(instAccumR[i] / w)
            }
        }
    }

    fun floatToPcm16(sample: Float): Short {
        val clamped = if (abs(sample) > 1.0f) tanh(sample.toDouble()).toFloat() else sample
        return (clamped * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
    }

    /**
     * Mixes stereo 44,100 Hz 16-bit PCM vocals + instrumental frames into [sinkBuffer] starting at its current
     * position up to limit, writing exact target [encoding], [channels], and [sinkSampleRate].
     * Also outputs stereo 16-bit frames for vocal and instrumental spectrum monitors when provided.
     */
    fun mixStemsIntoPcmBuffer(
        sinkBuffer: ByteBuffer,
        vocalsStereo: ShortArray,
        instStereo: ShortArray,
        stemWindowStartFrame: Long,
        songPositionSamples44100: Double,
        stepPerSinkFrame44100: Double,
        vocalGain: Float,
        stemMixWeight: Float,
        encoding: Int,
        channels: Int,
        outVocalStereo: ShortArray? = null,
        outInstStereo: ShortArray? = null,
    ): Int {
        val sampleBytes = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            else -> 4
        }
        val chCount = channels.coerceAtLeast(1)
        val frameBytes = sampleBytes * chCount
        val totalFrames = sinkBuffer.remaining() / frameBytes
        if (totalFrames <= 0) return 0

        val bigEndian = encoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN ||
            encoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN ||
            encoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN
        sinkBuffer.order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)

        val maxWindowFrames = minOf(vocalsStereo.size, instStereo.size) / 2
        val basePos = sinkBuffer.position()
        val mixW = stemMixWeight.coerceIn(0f, 1f)
        val origW = 1f - mixW

        for (frame in 0 until totalFrames) {
            val absStemPos = songPositionSamples44100 + frame * stepPerSinkFrame44100
            val relPos = absStemPos - stemWindowStartFrame.toDouble()
            val idx0 = kotlin.math.floor(relPos).toInt()
            val frac = (relPos - idx0).toFloat().coerceIn(0f, 1f)

            val vL: Float
            val vR: Float
            val iL: Float
            val iR: Float
            if (maxWindowFrames <= 0 || idx0 < 0 || idx0 >= maxWindowFrames) {
                vL = 0f; vR = 0f; iL = 0f; iR = 0f
            } else {
                val idx1 = (idx0 + 1).coerceAtMost(maxWindowFrames - 1)
                val p0 = idx0 * 2
                val p1 = idx1 * 2
                vL = (vocalsStereo[p0] + (vocalsStereo[p1] - vocalsStereo[p0]) * frac) / 32768f
                vR = (vocalsStereo[p0 + 1] + (vocalsStereo[p1 + 1] - vocalsStereo[p0 + 1]) * frac) / 32768f
                iL = (instStereo[p0] + (instStereo[p1] - instStereo[p0]) * frac) / 32768f
                iR = (instStereo[p0 + 1] + (instStereo[p1 + 1] - instStereo[p0 + 1]) * frac) / 32768f
            }

            if (outVocalStereo != null && frame * 2 + 1 < outVocalStereo.size) {
                outVocalStereo[frame * 2] = floatToPcm16(vL * vocalGain)
                outVocalStereo[frame * 2 + 1] = floatToPcm16(vR * vocalGain)
            }
            if (outInstStereo != null && frame * 2 + 1 < outInstStereo.size) {
                outInstStereo[frame * 2] = floatToPcm16(iL)
                outInstStereo[frame * 2 + 1] = floatToPcm16(iR)
            }

            val stemL = softClip(iL + vL * vocalGain)
            val stemR = softClip(iR + vR * vocalGain)

            val frameOffset = basePos + frame * frameBytes
            for (ch in 0 until chCount) {
                val pos = frameOffset + ch * sampleBytes
                val stemSample = when {
                    chCount == 1 -> (stemL + stemR) * 0.5f
                    ch % 2 == 0 -> stemL
                    else -> stemR
                }
                val finalSample = if (origW > 0f) {
                    val orig = readSampleFloat(sinkBuffer, pos, encoding, bigEndian)
                    softClip(orig * origW + stemSample * mixW)
                } else {
                    stemSample
                }
                writeSampleFloat(sinkBuffer, pos, encoding, bigEndian, finalSample)
            }
        }
        return totalFrames
    }

    private fun softClip(v: Float): Float =
        if (abs(v) <= 0.98f) v else (0.98f * kotlin.math.sign(v) + 0.02f * tanh(((v - 0.98f * kotlin.math.sign(v)) / 0.02f).toDouble()).toFloat()).coerceIn(-1f, 1f)

    private fun readSampleFloat(buf: ByteBuffer, pos: Int, encoding: Int, bigEndian: Boolean): Float = when (encoding) {
        C.ENCODING_PCM_8BIT -> ((buf.get(pos).toInt() and 0xFF) - 128) / 128f
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> buf.getShort(pos) / 32768f
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
            val raw = if (bigEndian) {
                (buf.get(pos).toInt() shl 16) or ((buf.get(pos + 1).toInt() and 0xFF) shl 8) or (buf.get(pos + 2).toInt() and 0xFF)
            } else {
                (buf.get(pos).toInt() and 0xFF) or ((buf.get(pos + 1).toInt() and 0xFF) shl 8) or (buf.get(pos + 2).toInt() shl 16)
            }
            raw / 8388608f
        }
        C.ENCODING_PCM_FLOAT -> buf.getFloat(pos)
        else -> buf.getInt(pos) / 2147483648f
    }

    private fun writeSampleFloat(buf: ByteBuffer, pos: Int, encoding: Int, bigEndian: Boolean, v: Float) {
        val clamped = v.coerceIn(-1f, 1f)
        when (encoding) {
            C.ENCODING_PCM_8BIT -> buf.put(pos, ((clamped * 127f).roundToInt() + 128).coerceIn(0, 255).toByte())
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN ->
                buf.putShort(pos, (clamped * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                val q = (clamped * 8388607f).roundToInt().coerceIn(-8388608, 8388607)
                if (bigEndian) {
                    buf.put(pos, (q shr 16).toByte())
                    buf.put(pos + 1, (q shr 8).toByte())
                    buf.put(pos + 2, q.toByte())
                } else {
                    buf.put(pos, q.toByte())
                    buf.put(pos + 1, (q shr 8).toByte())
                    buf.put(pos + 2, (q shr 16).toByte())
                }
            }
            C.ENCODING_PCM_FLOAT -> buf.putFloat(pos, clamped)
            else -> {
                val q = (clamped.toDouble() * 2147483647.0).roundToInt()
                buf.putInt(pos, q)
            }
        }
    }
}
