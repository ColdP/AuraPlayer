package btm.m.auraplayer.sing

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class AuraSingTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun configCodecRoundTripAndDefaults() {
        val defaults = AuraSingConfigCodec.decode(null)
        assertFalse(defaults.enabled)
        assertEquals(AuraSingModel.DEFAULT, defaults.model)
        assertTrue(defaults.hardwareAcceleration)
        assertEquals(AuraSingModel.KUIELAB_B_VOCALS, AuraSingModel.DEFAULT)

        for (model in AuraSingModel.entries) {
            val encoded = AuraSingConfigCodec.encode(
                AuraSingConfig(enabled = true, model = model, hardwareAcceleration = false),
            )
            val decoded = AuraSingConfigCodec.decode(encoded)
            assertTrue(decoded.enabled)
            assertEquals(model, decoded.model)
            assertFalse(decoded.hardwareAcceleration)
        }

        val fallback = AuraSingConfigCodec.decode("""{"enabled":true,"model":"unknown"}""")
        assertTrue(fallback.enabled)
        assertEquals(AuraSingModel.DEFAULT, fallback.model)
    }

    @Test
    fun mixedRadixFftMatchesNaiveDftAndRoundTripsMdxSizes() {
        // Test composite N = 60 = 4 * 3 * 5 against exact naive DFT
        val n = 60
        val re = DoubleArray(n) { i -> sin(i * 0.37) + 0.5 * cos(i * 1.1) }
        val im = DoubleArray(n) { i -> cos(i * 0.23) - 0.3 * sin(i * 0.7) }
        val expectedRe = DoubleArray(n)
        val expectedIm = DoubleArray(n)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val a = -2.0 * PI * k * t / n
                sr += re[t] * cos(a) - im[t] * sin(a)
                si += re[t] * sin(a) + im[t] * cos(a)
            }
            expectedRe[k] = sr
            expectedIm[k] = si
        }
        val origRe = re.copyOf()
        val origIm = im.copyOf()
        val fft = MixedRadixFft(n)
        fft.transform(re, im, inverse = false)
        for (k in 0 until n) {
            assertEquals("DFT real mismatch at $k", expectedRe[k], re[k], 1e-10)
            assertEquals("DFT imag mismatch at $k", expectedIm[k], im[k], 1e-10)
        }
        fft.transform(re, im, inverse = true)
        for (t in 0 until n) {
            assertEquals("IDFT real mismatch at $t", origRe[t], re[t], 1e-10)
            assertEquals("IDFT imag mismatch at $t", origIm[t], im[t], 1e-10)
        }

        // Verify all three real MDX-Net composite FFT sizes round-trip cleanly
        for (size in intArrayOf(5120, 6144, 7680)) {
            val mdxFft = MixedRadixFft(size)
            val aRe = DoubleArray(size) { i -> sin(i * 0.017) }
            val aIm = DoubleArray(size) { i -> cos(i * 0.031) }
            val refRe = aRe.copyOf()
            val refIm = aIm.copyOf()
            mdxFft.transform(aRe, aIm, inverse = false)
            mdxFft.transform(aRe, aIm, inverse = true)
            for (i in 0 until size step 127) {
                assertEquals("size=$size real", refRe[i], aRe[i], 1e-9)
                assertEquals("size=$size imag", refIm[i], aIm[i], 1e-9)
            }
        }
    }

    @Test
    fun mdxStftForwardAndInverseReconstructBandLimitedStereoSignal() {
        val stft = MdxStftProcessor(nFft = 6144, hopLength = 1024, dimF = 2048, dimT = 256)
        val chunk = stft.chunkSize
        // Use mid-band frequencies (inside bins 10..dimF) with zeroed tails so center reconstruction is exact
        val leftIn = FloatArray(chunk) { i ->
            (0.4 * sin(2.0 * PI * 440.0 * i / 44100.0) + 0.25 * cos(2.0 * PI * 1760.0 * i / 44100.0)).toFloat()
        }
        val rightIn = FloatArray(chunk) { i ->
            (0.3 * cos(2.0 * PI * 880.0 * i / 44100.0) - 0.2 * sin(2.0 * PI * 2640.0 * i / 44100.0)).toFloat()
        }
        val spectrum = FloatArray(4 * stft.dimF * stft.dimT)
        stft.forwardStereo(leftIn, rightIn, spectrum, zeroLowBins = true)

        val leftOut = FloatArray(chunk)
        val rightOut = FloatArray(chunk)
        stft.inverseStereo(spectrum, leftOut, rightOut)

        // Check middle genSize samples away from trim edges
        val start = stft.trim
        val end = chunk - stft.trim
        var maxErr = 0f
        for (i in start until end) {
            maxErr = maxOf(maxErr, abs(leftIn[i] - leftOut[i]), abs(rightIn[i] - rightOut[i]))
        }
        assertTrue("STFT->iSTFT max error too high: $maxErr", maxErr < 1e-3f)
    }

    @Test
    fun vocalExitThresholdExitsAtFullVolume() {
        assertFalse(AuraSingMath.shouldExitAuraSing(0.0f))
        assertFalse(AuraSingMath.shouldExitAuraSing(0.5f))
        assertFalse(AuraSingMath.shouldExitAuraSing(0.985f))
        assertTrue(AuraSingMath.shouldExitAuraSing(0.99f))
        assertTrue(AuraSingMath.shouldExitAuraSing(1.0f))
    }

    @Test
    fun overlapAddReconstructsVocalsAndInstrumentalAcrossChunks() {
        val totalFrames = 250
        val seg = 120
        val overlap = 30
        val stride = seg - overlap
        val offsets = AuraSingMath.planChunkOffsets(totalFrames, segmentSamples = seg, strideSamples = stride)
        assertTrue(offsets.size >= 3)

        val writer = AuraSingMath.StemOverlapAddWriter(
            totalFrames = totalFrames,
            segmentSamples = seg,
            overlapSamples = overlap,
        )

        offsets.forEachIndexed { chunkIdx, _ ->
            // Flat shape: [4, 2, seg] where drums=0.1, bass=0.2, other=0.1 => inst=0.4; vocals=0.3
            val flat = FloatArray(4 * 2 * seg)
            for (i in 0 until seg) {
                flat[0 * 2 * seg + i] = 0.10f // drums L
                flat[0 * 2 * seg + seg + i] = -0.10f // drums R
                flat[1 * 2 * seg + i] = 0.20f // bass L
                flat[1 * 2 * seg + seg + i] = -0.20f // bass R
                flat[2 * 2 * seg + i] = 0.10f // other L
                flat[2 * 2 * seg + seg + i] = -0.10f // other R
                flat[3 * 2 * seg + i] = 0.30f // vocals L
                flat[3 * 2 * seg + seg + i] = -0.30f // vocals R
            }
            writer.accumulateChunk(
                stemsFlat = flat,
                offset = offsets[chunkIdx],
                isFirst = chunkIdx == 0,
                isLast = chunkIdx == offsets.lastIndex,
            )
        }

        val vocalsOut = ShortArray(totalFrames * 2)
        val instOut = ShortArray(totalFrames * 2)
        writer.normalizeToStereoPcm16(vocalsOut, instOut)

        // Verify RollingStemOverlapAddWriter matches frame-for-frame
        val rollingWriter = AuraSingMath.RollingStemOverlapAddWriter(
            segmentSamples = seg,
            strideSamples = stride,
        )
        val rollingVoc = mutableListOf<Short>()
        val rollingInst = mutableListOf<Short>()
        offsets.forEachIndexed { chunkIdx, offset ->
            val valid = (totalFrames - offset).coerceIn(0, seg)
            val flat = FloatArray(4 * 2 * seg)
            for (i in 0 until seg) {
                flat[0 * 2 * seg + i] = 0.10f
                flat[0 * 2 * seg + seg + i] = -0.10f
                flat[1 * 2 * seg + i] = 0.20f
                flat[1 * 2 * seg + seg + i] = -0.20f
                flat[2 * 2 * seg + i] = 0.10f
                flat[2 * 2 * seg + seg + i] = -0.10f
                flat[3 * 2 * seg + i] = 0.30f
                flat[3 * 2 * seg + seg + i] = -0.30f
            }
            rollingWriter.accumulateAndDrain(
                stemsFlat = flat,
                validFramesInChunk = valid,
                isFirst = chunkIdx == 0,
                isLast = chunkIdx == offsets.lastIndex,
            ) { vL, vR, iL, iR ->
                rollingVoc.add(vL); rollingVoc.add(vR)
                rollingInst.add(iL); rollingInst.add(iR)
            }
        }
        assertEquals(totalFrames * 2, rollingVoc.size)
        for (idx in vocalsOut.indices) {
            assertEquals("vocal mismatch at $idx", vocalsOut[idx], rollingVoc[idx])
            assertEquals("inst mismatch at $idx", instOut[idx], rollingInst[idx])
        }

        val expectedVocal = (0.30f * 32767f).toInt()
        val expectedInst = (0.40f * 32767f).toInt()
        for (frame in 0 until totalFrames) {
            assertTrue("frame $frame vocal L mismatch", abs(vocalsOut[frame * 2] - expectedVocal) <= 2)
            assertTrue("frame $frame vocal R mismatch", abs(vocalsOut[frame * 2 + 1] + expectedVocal) <= 2)
            assertTrue("frame $frame inst L mismatch", abs(instOut[frame * 2] - expectedInst) <= 2)
            assertTrue("frame $frame inst R mismatch", abs(instOut[frame * 2 + 1] + expectedInst) <= 2)
        }
    }

    @Test
    fun stereoResamplerConverts48kAndMonoTo44100() {
        val resampler = AuraSingMath.StereoResampler44100(srcRate = 48_000, srcChannels = 1)
        val monoInput = ShortArray(4800) { idx -> (idx % 200 * 100).toShort() }
        val left = mutableListOf<Float>()
        val right = mutableListOf<Float>()
        resampler.processInterleavedShort(monoInput, monoInput.size, left, right)
        resampler.finish(left, right)

        // 4800 samples at 48kHz = 0.1s -> ~4410 samples at 44.1kHz
        assertTrue(left.size in 4400..4415)
        assertEquals(left.size, right.size)
        for (i in left.indices) {
            assertEquals(left[i], right[i], 1e-6f)
        }
    }

    @Test
    fun mixStemsIntoPcmBufferSeparatesAndAttenuatesVocals() {
        val frames = 8
        val vocals = ShortArray(frames * 2) { idx -> if (idx % 2 == 0) 16384 else -16384 } // +/-0.5
        val inst = ShortArray(frames * 2) { idx -> if (idx % 2 == 0) 8192 else -8192 } // +/-0.25

        val sinkBuf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        val vocalSpy = ShortArray(frames * 2)
        val instSpy = ShortArray(frames * 2)

        AuraSingMath.mixStemsIntoPcmBuffer(
            sinkBuffer = sinkBuf,
            vocalsStereo = vocals,
            instStereo = inst,
            stemWindowStartFrame = 0L,
            songPositionSamples44100 = 0.0,
            stepPerSinkFrame44100 = 1.0,
            vocalGain = 0.5f,
            stemMixWeight = 1.0f,
            encoding = C.ENCODING_PCM_16BIT,
            channels = 2,
            outVocalStereo = vocalSpy,
            outInstStereo = instSpy,
        )

        val mixedShorts = ShortArray(frames * 2)
        sinkBuf.rewind()
        sinkBuf.asShortBuffer().get(mixedShorts)

        // inst(0.25) + 0.5 * vocal(0.5) = 0.50 -> ~16384
        assertTrue(abs(mixedShorts[0] - 16384) <= 2)
        assertTrue(abs(mixedShorts[1] + 16384) <= 2)
        // Vocal monitor reflects attenuated vocal (~0.25 -> ~8192), instrument monitor reflects ~0.25
        assertTrue(abs(vocalSpy[0] - 8192) <= 2)
        assertTrue(abs(instSpy[0] - 8192) <= 2)
    }

    @Test
    fun qualcommNpuSocDetectionIdentifies8EliteAndAbove() {
        assertTrue(AuraSingQualcommNpu.isSnapdragon8EliteOrAbove(socModel = "SM8750", hardware = "qcom", board = "sun"))
        assertTrue(AuraSingQualcommNpu.isSnapdragon8EliteOrAbove(socModel = "SM8750P", hardware = "qcom", board = "sun"))
        assertTrue(AuraSingQualcommNpu.isSnapdragon8EliteOrAbove(socModel = "SM8850", hardware = "qcom", board = "canoe"))
        assertTrue(AuraSingQualcommNpu.isSnapdragon8EliteOrAbove(socModel = "SM8950", hardware = "qcom", board = "canary"))
        assertFalse(AuraSingQualcommNpu.isSnapdragon8EliteOrAbove(socModel = "SM8650", hardware = "qcom", board = "pineapple"))
        assertFalse(AuraSingQualcommNpu.isSnapdragon8EliteOrAbove(socModel = "MT6989", hardware = "mt6989", board = "k6989v1"))

        assertTrue(AuraSingQualcommNpu.isQualcommSoC(manufacturer = "QTI", socModel = "SM8750", hardware = "qcom", board = "sun"))
        assertFalse(AuraSingQualcommNpu.isQualcommSoC(manufacturer = "MediaTek", socModel = "MT6991", hardware = "mt6991", board = "tb"))
    }

    @Test
    fun cacheStoreCommitsListsAndDeletesEntries() {
        val cache = AuraSingCache(tempFolder.newFolder("sing-cache"))
        val vTmp = File(tempFolder.root, "v.tmp").apply { writeBytes(ByteArray(400) { 1 }) }
        val iTmp = File(tempFolder.root, "i.tmp").apply { writeBytes(ByteArray(400) { 2 }) }

        val entry = cache.commitEntry(
            songKey = "song-42",
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            modelId = AuraSingModel.HTDEMUCS_FP16.id,
            totalFrames = 100,
            durationMs = 2000L,
            vocalsTemp = vTmp,
            instTemp = iTmp,
        )

        assertEquals(800L, entry.totalBytes)
        assertTrue(cache.totalCacheBytes() >= 800L)
        assertEquals(1, cache.listEntries().size)
        val found = cache.findEntry("song-42", AuraSingModel.HTDEMUCS_FP16.id)
        assertNotNull(found)
        assertEquals(entry.id, found!!.id)

        assertTrue(cache.deleteEntry(entry.id))
        assertNull(cache.findEntry("song-42", AuraSingModel.HTDEMUCS_FP16.id))
        assertTrue(cache.listEntries().isEmpty())
    }

    @Test
    fun exportSupportsSingleWavAndMultiTrackZipCombinations() {
        val cache = AuraSingCache(tempFolder.newFolder("export-cache"))
        val vData1 = ByteArray(120) { (it + 1).toByte() }
        val iData1 = ByteArray(120) { (it + 50).toByte() }
        val e1 = cache.commitEntry(
            songKey = "s1",
            title = "Song:One/Test",
            artist = "Artist",
            album = "Album",
            modelId = AuraSingModel.KUIELAB_B_VOCALS.id,
            totalFrames = 30,
            durationMs = 1000L,
            vocalsTemp = File(tempFolder.root, "v1.tmp").apply { writeBytes(vData1) },
            instTemp = File(tempFolder.root, "i1.tmp").apply { writeBytes(iData1) },
        )

        val singleTarget = AuraSingExport.buildTargets(
            entries = listOf(e1),
            selectedEntryIds = setOf(e1.id),
            selectedStems = setOf(AuraSingExportStem.INSTRUMENTAL),
        )
        assertEquals(1, singleTarget.size)
        assertEquals("Song_One_Test_Instrumental.wav", AuraSingExport.suggestedFileName(singleTarget))

        val wavOut = ByteArrayOutputStream()
        AuraSingExport.exportTargetsToStream(cache, singleTarget, wavOut)
        val wavBytes = wavOut.toByteArray()
        assertEquals(AuraSingExport.WAV_HEADER_SIZE + iData1.size, wavBytes.size)
        assertEquals("RIFF", String(wavBytes, 0, 4))
        assertEquals("WAVE", String(wavBytes, 8, 4))
        assertEquals("data", String(wavBytes, 36, 4))
        assertTrue(wavBytes.copyOfRange(AuraSingExport.WAV_HEADER_SIZE, wavBytes.size).contentEquals(iData1))

        val e2 = cache.commitEntry(
            songKey = "s2",
            title = "Song Two",
            artist = "Artist",
            album = "Album",
            modelId = AuraSingModel.KUIELAB_B_VOCALS.id,
            totalFrames = 30,
            durationMs = 1000L,
            vocalsTemp = File(tempFolder.root, "v2.tmp").apply { writeBytes(vData1) },
            instTemp = File(tempFolder.root, "i2.tmp").apply { writeBytes(iData1) },
        )

        // Combine Vocals of Song1 + Song2 with Instrumental of Song1 + Song2 -> 4 WAVs inside ZIP
        val comboTargets = AuraSingExport.buildTargets(
            entries = listOf(e1, e2),
            selectedEntryIds = setOf(e1.id, e2.id),
            selectedStems = setOf(AuraSingExportStem.VOCALS, AuraSingExportStem.INSTRUMENTAL),
        )
        assertEquals(4, comboTargets.size)
        assertTrue(AuraSingExport.suggestedFileName(comboTargets).endsWith(".zip"))

        val zipOut = ByteArrayOutputStream()
        AuraSingExport.exportTargetsToStream(cache, comboTargets, zipOut)
        val entryNames = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zipOut.toByteArray())).use { zis ->
            while (true) {
                val ze = zis.nextEntry ?: break
                entryNames += ze.name
                val payload = zis.readBytes()
                assertEquals(AuraSingExport.WAV_HEADER_SIZE + 120, payload.size)
                assertEquals("RIFF", String(payload, 0, 4))
                zis.closeEntry()
            }
        }
        assertEquals(4, entryNames.size)
        assertTrue("Song_One_Test_Vocal.wav" in entryNames)
        assertTrue("Song Two_Instrumental.wav" in entryNames)
    }
}
