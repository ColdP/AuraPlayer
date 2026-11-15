// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleMusicArtistLogoTest {

    @Test
    fun extractArtistLinkCandidatesPrioritizesExactArtistMatch() {
        val body = """
            {
              "results": [
                {
                  "artistName": "Taylor Swift Cover Band",
                  "artistLinkUrl": "https://music.apple.com/cn/artist/cover/999"
                },
                {
                  "artistName": "Taylor Swift",
                  "artistLinkUrl": "https://music.apple.com/cn/artist/taylor-swift/159260351?uo=4"
                },
                {
                  "artistName": "Other Artist",
                  "artistLinkUrl": "https://music.apple.com/cn/artist/other/123"
                }
              ]
            }
        """.trimIndent()

        val candidates = AppleMusicArtistLogoManager.extractArtistLinkCandidates(body, "Taylor Swift")
        assertEquals(
            listOf(
                "https://music.apple.com/cn/artist/taylor-swift/159260351?uo=4",
                "https://music.apple.com/cn/artist/cover/999",
            ),
            candidates,
        )
    }

    @Test
    fun extractArtistAssetsExtractsLogoArtworkAndMotionVideo() {
        val sectionsWithAll = JSONArray(
            """
            [
              {
                "id": "artists_1065981054_default.marker.ArtistDetailHeader-ArtistDetailHeader",
                "items": [
                  {
                    "title": "Billie Eilish",
                    "artistLogo": {
                      "cropStyle": "bb",
                      "dictionary": {
                        "url": "https://is1-ssl.mzstatic.com/image/thumb/Logo/{w}x{h}{c}.{f}",
                        "width": 2400,
                        "height": 600
                      }
                    },
                    "artwork": {
                      "cropStyle": "sr",
                      "dictionary": {
                        "url": "https://is1-ssl.mzstatic.com/image/thumb/Art/{w}x{h}{c}.{f}",
                        "width": 3000,
                        "height": 3000
                      }
                    },
                    "videoArtwork": {
                      "dictionary": {
                        "motionArtistSquare1x1": {
                          "video": "https://mvod.itunes.apple.com/square/default.m3u8"
                        }
                      }
                    },
                    "wideVideoArtwork": {
                      "dictionary": {
                        "motionArtistFullscreen16x9": {
                          "video": "https://mvod.itunes.apple.com/wide/default.m3u8"
                        }
                      }
                    }
                  }
                ]
              }
            ]
            """.trimIndent(),
        )

        val assets = AppleMusicArtistLogoManager.extractArtistAssets(sectionsWithAll, maxLogoWidthPx = 1200, maxArtworkSizePx = 1200)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Logo/1200x300bb.png", assets?.logoUrl)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Art/1200x1200sr.jpg", assets?.artworkUrl)
        assertEquals("https://mvod.itunes.apple.com/square/default.m3u8", assets?.motionMasterUrl)

        val sectionsWideOnly = JSONArray(
            """
            [
              {
                "id": "artist-detail-header-ArtistDetailHeader",
                "items": [
                  {
                    "title": "Wide Only Artist",
                    "artistLogo": null,
                    "wideVideoArtwork": {
                      "dictionary": {
                        "motionArtistFullscreen16x9": {
                          "video": "https://mvod.itunes.apple.com/wide/only.m3u8"
                        }
                      }
                    }
                  }
                ]
              }
            ]
            """.trimIndent(),
        )
        val wideAssets = AppleMusicArtistLogoManager.extractArtistAssets(sectionsWideOnly)
        assertNull(wideAssets?.logoUrl)
        assertNull(wideAssets?.artworkUrl)
        assertEquals("https://mvod.itunes.apple.com/wide/only.m3u8", wideAssets?.motionMasterUrl)
    }

    @Test
    fun extractArtistLogoUrlBuildsScaledTransparentPngUrlAndHandlesMissingLogo() {
        val sectionsWithLogo = JSONArray(
            """
            [
              {
                "id": "artist-detail-header-ArtistDetailHeader-section-1",
                "items": [
                  {
                    "title": "Taylor Swift",
                    "artistLogo": {
                      "cropStyle": "bb",
                      "dictionary": {
                        "url": "https://is1-ssl.mzstatic.com/image/thumb/lPXHgVOK2LRp_AkQZj_K8A/{w}x{h}{c}.{f}",
                        "width": 4826,
                        "height": 1264
                      }
                    }
                  }
                ]
              }
            ]
            """.trimIndent(),
        )

        val resolved = AppleMusicArtistLogoManager.extractArtistLogoUrl(sectionsWithLogo, maxWidthPx = 1200)
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/lPXHgVOK2LRp_AkQZj_K8A/1200x314bb.png",
            resolved,
        )

        val sectionsWithoutLogo = JSONArray(
            """
            [
              {
                "id": "artist-detail-header-ArtistDetailHeader-section-1",
                "items": [
                  {
                    "title": "Billie Eilish",
                    "artistLogo": null
                  }
                ]
              }
            ]
            """.trimIndent(),
        )
        assertNull(AppleMusicArtistLogoManager.extractArtistLogoUrl(sectionsWithoutLogo))
    }
}
