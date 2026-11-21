// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleMusicAlbumLabelTest {

    @Test
    fun extractCopyrightFromFooterDescriptionHandlesStandardAndMissingFooters() {
        assertEquals(
            "℗ 2026 Sony Music Label Inc.",
            AppleMusicAlbumLabelManager.extractCopyrightFromFooterDescription(
                "2026年3月1日\n10 首歌曲、38 分钟\n℗ 2026 Sony Music Label Inc.",
            ),
        )
        assertEquals(
            "A Polydor Records release; ℗ 2026 UNIVERSAL MUSIC LLC",
            AppleMusicAlbumLabelManager.extractCopyrightFromFooterDescription(
                "2026年5月12日\n12 首歌曲、44 分钟\nA Polydor Records release; ℗ 2026 UNIVERSAL MUSIC LLC",
            ),
        )
        assertEquals(
            "℗ 2026 Label A\n© 2026 Label A",
            AppleMusicAlbumLabelManager.extractCopyrightFromFooterDescription(
                "May 1, 2026\n11 songs, 40 minutes\n℗ 2026 Label A\n© 2026 Label A",
            ),
        )
        assertNull(
            AppleMusicAlbumLabelManager.extractCopyrightFromFooterDescription(
                "2026年5月12日\n12 首歌曲、44 分钟",
            ),
        )
    }

    @Test
    fun parseAlbumCandidatesReadsTitleLinksAndRanksExactMatchesFirst() {
        val sections = JSONArray(
            """
            [
              {
                "id": "square-section - album",
                "items": [
                  {
                    "titleLinks": [{"title": "1989 (Deluxe Edition)"}],
                    "subtitleLinks": [{"title": "Taylor Swift"}],
                    "trackCount": 16,
                    "contentDescriptor": {
                      "kind": "album",
                      "identifiers": {"storeAdamID": "1445881848"},
                      "url": "https://music.apple.com/cn/album/1989-deluxe-edition/1445881848"
                    }
                  },
                  {
                    "title": "1989",
                    "subtitle": "专辑 · Taylor Swift",
                    "trackCount": 13,
                    "contentDescriptor": {
                      "kind": "album",
                      "identifiers": {"storeAdamID": "1445888258"},
                      "url": "https://music.apple.com/cn/album/1989/1445888258"
                    }
                  },
                  {
                    "title": "1989",
                    "subtitle": "专辑 · Cover Artist",
                    "trackCount": 13,
                    "contentDescriptor": {
                      "kind": "album",
                      "identifiers": {"storeAdamID": "999999999"},
                      "url": "https://music.apple.com/cn/album/1989/999999999"
                    }
                  }
                ]
              }
            ]
            """.trimIndent(),
        )

        val candidates = AppleMusicAlbumLabelManager.parseAlbumCandidates(
            sections = sections,
            albumTitle = "1989",
            artistName = "Taylor Swift",
            expectedTrackCount = 13,
        )
        assertEquals(listOf("1445888258", "1445881848"), candidates.map { it.albumId })
    }

    @Test
    fun extractTracklistFooterCopyrightReadsFooterSection() {
        val sections = JSONArray(
            """
            [
              {
                "id": "album-detail-header-section - 1754263364",
                "items": [
                  {
                    "title": "Zanmu",
                    "subtitleLinks": [{"title": "Ado"}],
                    "trackCount": 16
                  }
                ]
              },
              {
                "id": "track-list-section - 1754263364",
                "itemKind": "containerDetailTracklistFooterLockup",
                "items": [
                  {
                    "id": "tracklist-footer - 1754263364",
                    "description": "2024年7月10日\n16 首歌曲、54 分钟\nA Virgin Music release; ℗ 2024 UNIVERSAL MUSIC LLC"
                  }
                ]
              }
            ]
            """.trimIndent(),
        )

        assertEquals(
            "A Virgin Music release; ℗ 2024 UNIVERSAL MUSIC LLC",
            AppleMusicAlbumLabelManager.extractTracklistFooterCopyright(sections),
        )
    }

    @Test
    fun findLabelFromWebStorefrontAndItunesUsResolveCorrectCopyright() = runBlocking {
        val searchHtml = """
            <html><body><script id="serialized-server-data" type="application/json">
            {"data":[{"data":{"sections":[
              {"id":"top-search-section","items":[
                {"title":"LOST CORNER","subtitleLinks":[{"title":"Kenshi Yonezu"}],"trackCount":20,
                 "contentDescriptor":{"kind":"album","identifiers":{"storeAdamID":"1759899363"},"url":"https://music.apple.com/cn/album/lost-corner/1759899363"}}
              ]}
            ]}}]}
            </script></body></html>
        """.trimIndent()
        val albumHtml = """
            <html><body><script id="serialized-server-data" type="application/json">
            {"data":[{"data":{"sections":[
              {"id":"album-detail-header-section - 1759899363","items":[
                {"title":"LOST CORNER","subtitleLinks":[{"title":"Kenshi Yonezu"}],"trackCount":20}
              ]},
              {"id":"track-list-section - 1759899363","itemKind":"containerDetailTracklistFooterLockup","items":[
                {"id":"tracklist-footer - 1759899363","description":"2024年8月21日\n20 首歌曲、1 小时 10 分钟\n℗ 2026 Sony Music Label Inc."}
              ]}
            ]}}]}
            </script></body></html>
        """.trimIndent()

        val webLabel = AppleMusicAlbumLabelManager.findLabelFromWebStorefront(
            storefront = "cn",
            albumTitle = "LOST CORNER",
            artistName = "Kenshi Yonezu",
            expectedTrackCount = 20,
            fetcher = { url ->
                when {
                    url.contains("/search?") -> searchHtml
                    url.contains("/album/1759899363") -> albumHtml
                    else -> null
                }
            },
        )
        assertEquals("℗ 2026 Sony Music Label Inc.", webLabel)

        val itunesJson = """
            {
              "results": [
                {
                  "collectionId": 1001,
                  "collectionName": "Sample Album",
                  "artistName": "Cover Band",
                  "trackCount": 12,
                  "copyright": "℗ 2026 Fake Cover"
                },
                {
                  "collectionId": 1002,
                  "collectionName": "Sample Album",
                  "artistName": "Target Artist",
                  "trackCount": 12,
                  "copyright": "A Polydor Records release; ℗ 2026 UNIVERSAL MUSIC LLC"
                }
              ]
            }
        """.trimIndent()
        val itunesLabel = AppleMusicAlbumLabelManager.findLabelFromItunesUs(
            albumTitle = "Sample Album",
            artistName = "Target Artist",
            expectedTrackCount = 12,
            fetcher = { itunesJson },
        )
        assertEquals("A Polydor Records release; ℗ 2026 UNIVERSAL MUSIC LLC", itunesLabel)
    }
}
