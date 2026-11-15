// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.spatialcover

import btm.m.auraplayer.playback.NowPlayingCodec
import btm.m.auraplayer.playback.NowPlayingConfig
import btm.m.auraplayer.playback.shouldPromptSpatialCoverPowerNotice
import btm.m.auraplayer.ui.shouldHideBottomChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpatialCoverTest {
    @Test
    fun modelCatalogMatchesHyperChangerDepthWallpaperSpec() {
        assertEquals(3, SpatialDepthModel.entries.size)
        assertEquals(
            "https://coldp.github.io/hyperchanger-updateinfo/models/depth-anything-v2-small.onnx",
            SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL.url,
        )
        assertEquals(27_258_801L, SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL.sizeBytes)
        assertEquals(
            "https://coldp.github.io/hyperchanger-updateinfo/models/midas-v2.1-small.onnx",
            SpatialDepthModel.MIDAS_MOBILE.url,
        )
        assertEquals(66_389_153L, SpatialDepthModel.MIDAS_MOBILE.sizeBytes)
        assertEquals(
            "https://coldp.github.io/hyperchanger-updateinfo/models/da3-base-int8.onnx",
            SpatialDepthModel.DEPTH_ANYTHING_V3_BASE.url,
        )
        assertEquals(103_943_002L, SpatialDepthModel.DEPTH_ANYTHING_V3_BASE.sizeBytes)

        assertEquals(3, SpatialMattingModel.downloadableEntries.size)
        for (model in SpatialMattingModel.downloadableEntries) {
            val resource = model.resource
            assertNotNull(resource)
            assertTrue(resource!!.url.startsWith("https://coldp.github.io/hyperchanger-updateinfo/models/"))
            assertTrue(resource.sizeBytes > 0L)
            assertEquals(64, resource.sha256.length)
        }
        assertNull(SpatialMattingModel.NONE.resource)
    }

    @Test
    fun codecRoundTripsSpatialCoverConfigAndPowerNoticeGating() {
        val defaultDecoded = NowPlayingCodec.decode("{}")
        assertFalse(defaultDecoded.spatialCover)
        assertFalse(defaultDecoded.spatialCoverPowerNoticeDismissed)
        assertEquals(SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL.id, defaultDecoded.spatialCoverDepthModel)
        assertEquals(SpatialMattingModel.U2NETP.id, defaultDecoded.spatialCoverMattingModel)
        assertTrue(defaultDecoded.spatialCoverViewActive)

        val encoded = NowPlayingCodec.encode(
            NowPlayingConfig(
                spatialCover = true,
                spatialCoverPowerNoticeDismissed = true,
                spatialCoverDepthModel = SpatialDepthModel.DEPTH_ANYTHING_V3_BASE.id,
                spatialCoverMattingModel = SpatialMattingModel.BIREFNET_LITE_INT8.id,
                spatialCoverViewActive = false,
            ),
        )
        val decoded = NowPlayingCodec.decode(encoded)
        assertTrue(decoded.spatialCover)
        assertTrue(decoded.spatialCoverPowerNoticeDismissed)
        assertEquals(SpatialDepthModel.DEPTH_ANYTHING_V3_BASE.id, decoded.spatialCoverDepthModel)
        assertEquals(SpatialMattingModel.BIREFNET_LITE_INT8.id, decoded.spatialCoverMattingModel)
        assertFalse(decoded.spatialCoverViewActive)

        assertTrue(shouldPromptSpatialCoverPowerNotice(enabling = true, dismissed = false))
        assertFalse(shouldPromptSpatialCoverPowerNotice(enabling = true, dismissed = true))
        assertFalse(shouldPromptSpatialCoverPowerNotice(enabling = false, dismissed = false))
    }

    @Test
    fun spatialCoverPreviewRouteHidesBottomChrome() {
        assertTrue(shouldHideBottomChrome(listOf("spatial_cover_cache", "spatial_cover_preview:abc123"), closingIndex = -1))
        assertTrue(shouldHideBottomChrome(listOf("spatial_cover_preview:abc123", "other"), closingIndex = 1))
        assertFalse(shouldHideBottomChrome(listOf("spatial_cover_cache"), closingIndex = -1))
    }

    @Test
    fun inpaintAndReliefProduceValidFiniteOutputs() {
        val w = 16
        val h = 16
        val pixels = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x in 5..10 && y in 5..10) 0xFFFF2020.toInt() else 0xFF2060B0.toInt()
        }
        val rawDepth = FloatArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x in 5..10 && y in 5..10) 0.9f else 0.25f
        }
        val normalized = SpatialDepthGeometry.normalize(rawDepth, w, h)
        assertTrue(normalized.all { it in 0f..1f })

        val mask = FloatArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x in 5..10 && y in 5..10) 1f else 0f
        }
        val removal = SpatialMattingEdges.backgroundRemovalMask(mask, w, h, radius = 1)
        val filled = SpatialBackgroundInpaint.fill(pixels, normalized, removal, w, h)
        assertEquals(w * h, filled.pixels.size)
        assertEquals(w * h, filled.depth.size)
        assertTrue(filled.depth.all { it.isFinite() && it in 0f..1f })
        // Center pixel behind removed foreground should now reflect background blue rather than foreground red.
        val center = filled.pixels[8 * w + 8]
        val red = (center ushr 16) and 0xFF
        val blue = center and 0xFF
        assertTrue("Expected inpainted blue ($blue) > red ($red)", blue > red)

        val relief = SpatialDepthGeometry.subjectRelief(normalized, mask, w, h, anchor = 0.8f)
        assertEquals(w * h, relief.size)
        assertTrue(relief.all { it.isFinite() && it in 0f..1f })
    }

    @Test
    fun runtimeCatalogSupportsArm64OnlyAndExtractsNativeLibrariesSafely() {
        assertEquals(1, SpatialRuntimeAbi.entries.size)
        assertEquals(SpatialRuntimeAbi.ARM64_V8A, SpatialRuntimeAbi.forSupportedAbis(arrayOf("arm64-v8a", "armeabi-v7a")))
        assertNull(SpatialRuntimeAbi.forSupportedAbis(arrayOf("armeabi-v7a", "x86")))

        val res = SpatialRuntimeAbi.ARM64_V8A.resource
        assertTrue(res.isArchive)
        assertEquals(
            "https://coldp.github.io/hyperchanger-updateinfo/runtime/onnxruntime-1.31.0-android-arm64-v8a.zip",
            res.url,
        )
        assertEquals(12_899_192L, res.sizeBytes)
        assertEquals(64, res.sha256.length)

        val tempRoot = Files.createTempDirectory("ort_runtime_test").toFile()
        try {
            val zipFile = File(tempRoot, "runtime.zip")
            ZipOutputStream(zipFile.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("../evil.so"))
                zos.write("ignored".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry(SpatialModelStore.RUNTIME_CORE_SO))
                zos.write("core-elf".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry(SpatialModelStore.RUNTIME_JNI_SO))
                zos.write("jni-elf".toByteArray())
                zos.closeEntry()
            }
            val outDir = File(tempRoot, "out")
            SpatialModelStore.extractRuntimeArchive(zipFile, outDir)
            val core = File(outDir, SpatialModelStore.RUNTIME_CORE_SO)
            val jni = File(outDir, SpatialModelStore.RUNTIME_JNI_SO)
            assertTrue(core.isFile && core.readText() == "core-elf")
            assertTrue(jni.isFile && jni.readText() == "jni-elf")
            assertFalse(File(tempRoot, "evil.so").exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun spatialCacheKeyIsSharedPerNormalizedAlbumTitleAndAlbumArtist() {
        val depth = SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL
        val matting = SpatialMattingModel.U2NETP

        // Different track titles, artwork URIs, and featured artists but matching album + albumArtist.
        val track1Key = SpatialCoverManager.keyFor(
            artworkUri = "content://media/external/audio/media/101/albumart",
            depthModel = depth,
            mattingModel = matting,
            songAlbum = "1989 (Taylor's Version)",
            songAlbumArtist = "Taylor Swift",
            songArtist = "Taylor Swift",
        )
        val track2Key = SpatialCoverManager.keyFor(
            artworkUri = "content://media/external/audio/media/102/albumart",
            depthModel = depth,
            mattingModel = matting,
            songAlbum = "  １９８９ (taylor's  version) ",
            songAlbumArtist = "taylor swift",
            songArtist = "Taylor Swift / Kendrick Lamar",
        )
        assertEquals(track1Key, track2Key)

        // When albumArtist is omitted, artist acts as fallback album artist cleanly.
        val fallbackArtistKey = SpatialCoverManager.keyFor(
            artworkUri = "content://other/art",
            depthModel = depth,
            mattingModel = matting,
            songAlbum = "1989 (Taylor's Version)",
            songAlbumArtist = "",
            songArtist = "Taylor Swift",
        )
        assertEquals(track1Key, fallbackArtistKey)

        // Different album title or different album artist must not collide.
        val differentAlbumKey = SpatialCoverManager.keyFor(
            artworkUri = "content://media/external/audio/media/101/albumart",
            depthModel = depth,
            mattingModel = matting,
            songAlbum = "Red (Taylor's Version)",
            songAlbumArtist = "Taylor Swift",
            songArtist = "Taylor Swift",
        )
        val differentArtistKey = SpatialCoverManager.keyFor(
            artworkUri = "content://media/external/audio/media/101/albumart",
            depthModel = depth,
            mattingModel = matting,
            songAlbum = "1989 (Taylor's Version)",
            songAlbumArtist = "Another Artist",
            songArtist = "Another Artist",
        )
        val differentModelKey = SpatialCoverManager.keyFor(
            artworkUri = "content://media/external/audio/media/101/albumart",
            depthModel = SpatialDepthModel.MIDAS_MOBILE,
            mattingModel = matting,
            songAlbum = "1989 (Taylor's Version)",
            songAlbumArtist = "Taylor Swift",
            songArtist = "Taylor Swift",
        )
        assertFalse(track1Key == differentAlbumKey)
        assertFalse(track1Key == differentArtistKey)
        assertFalse(track1Key == differentModelKey)

        // Missing album metadata falls back to artworkUri isolation so unrelated singles don't share cache.
        val singleA = SpatialCoverManager.keyFor(
            artworkUri = "content://single/a",
            depthModel = depth,
            mattingModel = matting,
            songAlbum = "",
            songAlbumArtist = "",
            songArtist = "Singer",
        )
        val singleB = SpatialCoverManager.keyFor(
            artworkUri = "content://single/b",
            depthModel = depth,
            mattingModel = matting,
            songAlbum = "",
            songAlbumArtist = "",
            songArtist = "Singer",
        )
        assertFalse(singleA == singleB)
    }

    @Test
    fun displacementFieldsRemoveNoiseFillBackgroundDepthSmoothlyAndExtendSubject() {
        val w = 32
        val h = 32
        val rawDepth = FloatArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            when {
                x == 2 && y == 2 -> 0.99f // isolated background noise spike
                x in 10..21 && y in 10..21 -> 0.85f
                else -> 0.25f
            }
        }
        val med = SpatialDepthGeometry.median3(rawDepth, w, h)
        assertTrue("Isolated spike must be suppressed by median3", med[2 * w + 2] < 0.35f)

        val mask = FloatArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x in 10..21 && y in 10..21) 1f else 0f
        }
        val bodyMask = BooleanArray(w * h) { mask[it] > 1e-3f }
        val fillRegion = SpatialMattingEdges.fillHolesExcludingFarGaps(bodyMask, med, w, h)
        val fields = SpatialDepthGeometry.buildDisplacementFields(med, mask, fillRegion, w, h)

        assertEquals(w * h, fields.backgroundK.size)
        assertEquals(w * h, fields.foregroundK.size)
        assertTrue(fields.backgroundK.all { it.isFinite() && it in 0f..1f })
        assertTrue(fields.foregroundK.all { it.isFinite() && it in 0f..1f })

        // Background field over subject interior should follow background displacement rather than collapsing to fg minimum.
        val centerBgK = fields.backgroundK[16 * w + 16]
        val cornerBgK = fields.backgroundK[2 * w + 2]
        val centerFgK = fields.foregroundK[16 * w + 16]
        assertTrue("Expected bg field over subject ($centerBgK) close to outer bg ($cornerBgK)", kotlin.math.abs(centerBgK - cornerBgK) < 0.08f)
        assertTrue("Expected subject fg ($centerFgK) smaller shift than distant bg ($cornerBgK)", centerFgK < cornerBgK)
    }
}
