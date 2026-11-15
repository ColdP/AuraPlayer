// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import btm.m.auraplayer.ui.player.shouldUseImmersiveCover
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicMotionCoverTest {

    @Test
    fun albumCacheKeyReusesSameAlbumAcrossTracksAndAlbumArtistFallback() {
        val k1 = AppleMusicMotionCoverManager.albumCacheKey(
            title = "Blank Space (feat. Guest)",
            artist = "Taylor Swift / Guest",
            album = "1989 (Taylor's Version)",
            albumArtist = "Taylor Swift",
        )
        val k2 = AppleMusicMotionCoverManager.albumCacheKey(
            title = "Style",
            artist = "Taylor Swift, Producer",
            album = "１９８９ (taylor's version)",
            albumArtist = "",
        )
        assertEquals(k1, k2)
        assertTrue(k1.startsWith("a:"))

        val singleKey = AppleMusicMotionCoverManager.albumCacheKey(
            title = "Standalone (feat. X)",
            artist = "Artist One & Artist Two",
            album = "",
            albumArtist = "",
        )
        assertTrue(singleKey.startsWith("s:"))
        assertEquals("s:artistone|standalone", singleKey)
    }

    @Test
    fun findMotionArtMatchesAlbumAndDurationAcrossStorefronts() = runBlocking {
        val searchJson = JSONObject().put(
            "data",
            JSONArray().put(
                JSONObject().put(
                    "data",
                    JSONObject().put(
                        "sections",
                        JSONArray().put(
                            JSONObject().put(
                                "items",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("title", "ABCD (feat. Guest)")
                                            .put("subtitle", "歌曲 · NAYEON")
                                            .put(
                                                "contentDescriptor",
                                                JSONObject()
                                                    .put("kind", "song")
                                                    .put("url", "/cn/album/na-ep/1749999001?i=1749999111"),
                                            ),
                                    )
                                    .put(
                                        JSONObject()
                                            .put("title", "ABCD")
                                            .put("subtitle", "歌曲 · NAYEON")
                                            .put(
                                                "contentDescriptor",
                                                JSONObject()
                                                    .put("kind", "song")
                                                    .put("url", "/cn/album/na/1749999002?i=1749999222"),
                                            ),
                                    ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val wrongDurationAlbum = albumHtml(
            albumTitle = "NA - EP",
            songId = "1749999111",
            durationMs = 210_000L,
            motionMasterUrl = "https://mvod.itunes.apple.com/wrong.m3u8",
        )
        val matchedAlbum = albumHtml(
            albumTitle = "NA - EP",
            songId = "1749999222",
            durationMs = 162_500L,
            motionMasterUrl = "https://mvod.itunes.apple.com/na_master.m3u8",
        )

        val match = AppleMusicMotionCoverManager.findMotionArt(
            title = "ABCD",
            artist = "NAYEON",
            album = "NA",
            durationMs = 162_000L,
            fetcher = { url ->
                when {
                    url.startsWith("https://music.apple.com/cn/search?") ->
                        "<html><script id=\"serialized-server-data\">$searchJson</script></html>"
                    url == "https://music.apple.com/cn/album/1749999001" -> wrongDurationAlbum
                    url == "https://music.apple.com/cn/album/1749999002" -> matchedAlbum
                    else -> null
                }
            },
        )
        assertNotNull(match)
        assertEquals("1749999002", match!!.albumId)
        assertEquals("NA - EP", match.albumTitle)
        assertEquals("https://mvod.itunes.apple.com/na_master.m3u8", match.masterUrl)
    }

    @Test
    fun pickSdrH264VariantAndParseMediaByteRangesSelectLeanest1080SdrStream() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=6500000,RESOLUTION=2160x2160,CODECS="hvc1.1.6.L150",VIDEO-RANGE="HLG"
            too_big_hdr.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4200000,RESOLUTION=1080x1080,CODECS="avc1.640028",VIDEO-RANGE="SDR"
            sdr_high.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2600000,RESOLUTION=1080x1080,CODECS="avc1.640028",VIDEO-RANGE="SDR"
            sdr_lean.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=720x720,CODECS="avc1.4d401f",VIDEO-RANGE="SDR"
            sdr_720.m3u8
        """.trimIndent()
        val picked = AppleMusicMotionCoverManager.pickSdrH264Variant(
            "https://mvod.itunes.apple.com/path/master.m3u8",
            master,
        )
        assertEquals("https://mvod.itunes.apple.com/path/sdr_lean.m3u8", picked)

        val media = """
            #EXTM3U
            #EXT-X-MAP:URI="video.mp4",BYTERANGE="720@0"
            #EXT-X-BYTERANGE:1024@720
            video.mp4
            #EXT-X-BYTERANGE:2048
            video.mp4
        """.trimIndent()
        val parsed = AppleMusicMotionCoverManager.parseMediaByteRanges(
            "https://mvod.itunes.apple.com/path/sdr_lean.m3u8",
            media,
        )
        assertNotNull(parsed)
        assertEquals("https://mvod.itunes.apple.com/path/video.mp4", parsed!!.fileUrl)
        assertEquals(3, parsed.ranges.size)
        assertEquals(0L to 720L, parsed.ranges[0].offset to parsed.ranges[0].length)
        assertEquals(720L to 1024L, parsed.ranges[1].offset to parsed.ranges[1].length)
        assertEquals(1744L to 2048L, parsed.ranges[2].offset to parsed.ranges[2].length)
    }

    @Test
    fun appleMusicMotionForcesImmersiveCoverWhenEnabledAndAvailable() {
        // Off by default and without AM data
        assertFalse(
            shouldUseImmersiveCover(
                immersiveCoverSetting = false,
                appleMusicAnimatedCoverEnabled = false,
                appleMusicAnimatedCoverAvailable = false,
                fullscreen = false,
                hasLandscapeGeometry = false,
            ),
        )
        // AM enabled but no motion data found -> respects user's immersiveCover setting (off)
        assertFalse(
            shouldUseImmersiveCover(
                immersiveCoverSetting = false,
                appleMusicAnimatedCoverEnabled = true,
                appleMusicAnimatedCoverAvailable = false,
                fullscreen = false,
                hasLandscapeGeometry = false,
            ),
        )
        // AM enabled AND data available -> FORCES immersive cover even if immersiveCover is OFF!
        assertTrue(
            shouldUseImmersiveCover(
                immersiveCoverSetting = false,
                appleMusicAnimatedCoverEnabled = true,
                appleMusicAnimatedCoverAvailable = true,
                fullscreen = false,
                hasLandscapeGeometry = false,
            ),
        )
        // AM disabled -> does not force immersive cover even if stale path somehow existed
        assertFalse(
            shouldUseImmersiveCover(
                immersiveCoverSetting = false,
                appleMusicAnimatedCoverEnabled = false,
                appleMusicAnimatedCoverAvailable = true,
                fullscreen = false,
                hasLandscapeGeometry = false,
            ),
        )
        // Fullscreen still disables immersive cover; landscape supports immersive cover cleanly
        assertFalse(
            shouldUseImmersiveCover(
                immersiveCoverSetting = false,
                appleMusicAnimatedCoverEnabled = true,
                appleMusicAnimatedCoverAvailable = true,
                fullscreen = true,
                hasLandscapeGeometry = false,
            ),
        )
        assertTrue(
            shouldUseImmersiveCover(
                immersiveCoverSetting = false,
                appleMusicAnimatedCoverEnabled = true,
                appleMusicAnimatedCoverAvailable = true,
                fullscreen = false,
                hasLandscapeGeometry = true,
            ),
        )
        assertTrue(
            shouldUseImmersiveCover(
                immersiveCoverSetting = true,
                appleMusicAnimatedCoverEnabled = false,
                appleMusicAnimatedCoverAvailable = false,
                fullscreen = false,
                hasLandscapeGeometry = true,
            ),
        )
    }

    private fun albumHtml(
        albumTitle: String,
        songId: String,
        durationMs: Long,
        motionMasterUrl: String?,
    ): String {
        val headerItem = JSONObject().put("title", albumTitle)
        if (motionMasterUrl != null) {
            headerItem.put(
                "videoArtwork",
                JSONObject().put(
                    "dictionary",
                    JSONObject().put(
                        "motionDetailSquare",
                        JSONObject().put("video", motionMasterUrl),
                    ),
                ),
            )
        }
        val sections = JSONArray()
            .put(
                JSONObject()
                    .put("id", "album-detail-header-1")
                    .put("items", JSONArray().put(headerItem)),
            )
            .put(
                JSONObject()
                    .put("id", "track-list-1")
                    .put(
                        "items",
                        JSONArray().put(
                            JSONObject()
                                .put("duration", durationMs)
                                .put(
                                    "contentDescriptor",
                                    JSONObject().put(
                                        "identifiers",
                                        JSONObject().put("storeAdamID", songId),
                                    ),
                                ),
                        ),
                    ),
            )
        val root = JSONObject().put(
            "data",
            JSONArray().put(
                JSONObject().put(
                    "data",
                    JSONObject().put("sections", sections),
                ),
            ),
        )
        return "<html><script id=\"serialized-server-data\">$root</script></html>"
    }
}
