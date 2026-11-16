// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongCreditsTest {
    @Test
    fun parsesNeteaseLyricJsonCreditsAndGroupsMultiRolesPerCreator() {
        val lyricJson = JSONObject(
            """
            {
              "lrc": {
                "lyric": "{\"t\":0,\"c\":[{\"tx\":\"作词: \"},{\"tx\":\"重永亮介\",\"li\":\"http://p1.music.126.net/a.jpg\",\"or\":\"orpheus://nm/artist/home?id=29643418&type=artist\"}]}\n{\"t\":543,\"c\":[{\"tx\":\"作曲: \"},{\"tx\":\"重永亮介\",\"li\":\"http://p1.music.126.net/a.jpg\",\"or\":\"orpheus://nm/artist/home?id=29643418&type=artist\"}]}\n{\"t\":1086,\"c\":[{\"tx\":\"编曲: \"},{\"tx\":\"重永亮介\",\"li\":\"http://p1.music.126.net/a.jpg\",\"or\":\"orpheus://nm/artist/home?id=29643418&type=artist\"}]}\n{\"t\":1500,\"c\":[{\"tx\":\"制作人: \"},{\"tx\":\"周以力\",\"li\":\"http://p1.music.126.net/b.jpg\",\"or\":\"orpheus://nm/artist/home?id=48815161&type=artist\"},{\"tx\":\"/\"},{\"tx\":\"郑伟\",\"li\":\"http://p1.music.126.net/c.jpg\",\"or\":\"orpheus://nm/artist/home?id=30285750&type=artist\"}]}\n[00:01.631]Real lyric line"
              }
            }
            """.trimIndent(),
        )
        val detailJson = JSONObject(
            """
            {
              "songs": [
                {
                  "id": 1958384591,
                  "name": "ALIVE",
                  "publishTime": 1657382400000,
                  "ar": [{"id": 18961, "name": "ClariS"}],
                  "al": {"id": 146974035, "name": "ALIVE", "picUrl": "http://p1.music.126.net/cover.jpg"}
                }
              ]
            }
            """.trimIndent(),
        )

        val parsed = SongCreditsParser.parse(
            songId = 1958384591L,
            lyricJson = lyricJson,
            detailJson = detailJson,
        )

        assertEquals("ALIVE", parsed.title)
        assertEquals("ClariS", parsed.artist)
        assertEquals("ALIVE", parsed.album)
        assertEquals("2022", parsed.releaseYear)
        assertEquals("https://p1.music.126.net/cover.jpg", parsed.artwork)

        // Songwriting groups Lyricist + Composer under single creator with avatar
        assertEquals(1, parsed.songwriting.size)
        val writer = parsed.songwriting[0]
        assertEquals("重永亮介", writer.name)
        assertEquals(listOf(SongCreditRole.LYRICIST, SongCreditRole.COMPOSER), writer.roles)
        assertEquals("https://p1.music.126.net/a.jpg", writer.avatarUrl)
        assertEquals(29643418L, writer.artistId)

        // Production includes Arranger + multiple Producers
        assertEquals(3, parsed.production.size)
        assertEquals("重永亮介", parsed.production[0].name)
        assertEquals(listOf(SongCreditRole.ARRANGER), parsed.production[0].roles)
        assertEquals("周以力", parsed.production[1].name)
        assertEquals(listOf(SongCreditRole.PRODUCER), parsed.production[1].roles)
        assertEquals("https://p1.music.126.net/b.jpg", parsed.production[1].avatarUrl)
        assertEquals("郑伟", parsed.production[2].name)
        assertEquals(listOf(SongCreditRole.PRODUCER), parsed.production[2].roles)
    }

    @Test
    fun parsesPlainTextLrcCreditsFallback() {
        val lyricJson = JSONObject(
            """
            {
              "lrc": {
                "lyric": "[00:00.000] 作词 : 方文山\n[00:01.000] 作曲 : 周杰伦\n[00:02.000] 编曲 : 黄雨勋\n[00:03.000] 制作人 : 周杰伦\n[00:10.000] 第一句歌词"
              }
            }
            """.trimIndent(),
        )

        val parsed = SongCreditsParser.parse(
            songId = 185668L,
            lyricJson = lyricJson,
            fallbackSong = Song(
                id = 185668L,
                title = "稻香",
                artist = "周杰伦",
                album = "魔杰座",
                uri = "aura://netease/185668",
                artwork = "https://p1.music.126.net/album.jpg",
                year = 2008,
                cloud = true,
            ),
        )

        assertEquals("稻香", parsed.title)
        assertEquals("2008", parsed.releaseYear)
        assertEquals(2, parsed.songwriting.size)
        assertEquals("方文山", parsed.songwriting[0].name)
        assertEquals(listOf(SongCreditRole.LYRICIST), parsed.songwriting[0].roles)
        assertEquals("周杰伦", parsed.songwriting[1].name)
        assertEquals(listOf(SongCreditRole.COMPOSER), parsed.songwriting[1].roles)

        assertEquals(2, parsed.production.size)
        assertTrue(parsed.production.any { it.name == "黄雨勋" && it.roles == listOf(SongCreditRole.ARRANGER) })
        assertTrue(parsed.production.any { it.name == "周杰伦" && it.roles == listOf(SongCreditRole.PRODUCER) })
    }
}
