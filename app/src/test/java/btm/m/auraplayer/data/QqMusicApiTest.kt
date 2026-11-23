package btm.m.auraplayer.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class QqMusicApiTest {
    private lateinit var server: MockWebServer
    private lateinit var customApi: QqMusicApi
    private lateinit var official: OfficialQqClient

    @Before
    fun setup() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        val base = "http://127.0.0.1:${server.port}"
        customApi = QqMusicApi({ base }, { "10086" }, { "uin=o10086; qqmusic_key=secret" })
        official = OfficialQqClient({ "uin=o10086; qqmusic_key=secret; pgv_pvid=987654" }, { "" }, OkHttpClient(), base, base)
    }

    @After
    fun cleanup() {
        server.shutdown()
    }

    private fun reply(json: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(json))
    }

    @Test
    fun customSearchAndResolveMapSongsToQqProvider() = runBlocking {
        reply("""{"result":100,"data":{"list":[{"songid":77,"songmid":"001Mid","songname":"七里香","singer":[{"name":"周杰伦"}],"albummid":"002Alb","albumname":"七里香","interval":299}],"total":1}}""")
        val page = customApi.search("七里香")
        assertEquals(1, page.items.size)
        val song = page.items.first()
        assertTrue(song.cloud)
        assertTrue(song.isQq)
        assertFalse(song.netease)
        assertEquals("cloud:qq:77", song.key)
        assertEquals("aura://qqmusic/001Mid", song.uri)
        assertEquals("周杰伦", song.artist)
        assertEquals(299_000L, song.duration)
        assertEquals("/search?key=%E4%B8%83%E9%87%8C%E9%A6%99&pageNo=1&pageSize=30&t=0", server.takeRequest().path)

        reply("""{"result":100,"data":"http://isure.stream.qqmusic.qq.com/M500001Mid.mp3?vkey=abc"}""")
        val resolved = customApi.resolveBlocking("001Mid")
        assertEquals("https://isure.stream.qqmusic.qq.com/M500001Mid.mp3?vkey=abc", resolved)
    }

    @Test
    fun customResolveRejectsEmptyUrl() {
        reply("""{"result":100,"data":""}""")
        try {
            customApi.resolveBlocking("001Mid")
            fail("Expected UNAVAILABLE_SONG")
        } catch (expected: IOException) {
            assertEquals("UNAVAILABLE_SONG", expected.message)
        }
    }

    @Test
    fun officialClientParsesAccountSearchAndVkey() {
        reply("""{"code":0,"data":{"creator":{"uin":10086,"nick":"AuraQQ","headpic":"http://q.qlogo.cn/avatar.jpg"}}}""")
        val account = official.account()
        assertEquals(10086L, account.userId)
        assertEquals("AuraQQ", account.nickname)
        assertEquals("https://q.qlogo.cn/avatar.jpg", account.avatar)
        val profileReq = server.takeRequest()
        assertTrue(profileReq.path!!.startsWith("/rsc/fcgi-bin/fcg_get_profile_homepage.fcg"))
        assertEquals("0", profileReq.requestUrl!!.queryParameter("userid"))
        assertEquals("10086", profileReq.requestUrl!!.queryParameter("uin"))
        assertEquals("10086", profileReq.requestUrl!!.queryParameter("loginUin"))
        assertEquals("1", profileReq.requestUrl!!.queryParameter("reqfrom"))
        assertEquals("0", profileReq.requestUrl!!.queryParameter("reqtype"))
        assertEquals("yqq.json", profileReq.requestUrl!!.queryParameter("platform"))
        assertEquals(QqSession("qqmusic_key=secret").gtk().toString(), profileReq.requestUrl!!.queryParameter("g_tk"))
        assertEquals(QqSession("qqmusic_key=secret").gtk(modern = true).toString(), profileReq.requestUrl!!.queryParameter("g_tk_new_20200303"))

        reply("""{"code":0,"req_1":{"code":0,"data":{"meta":{"sum":1},"body":{"song":{"list":[{"id":88,"mid":"003Song","name":"晴天","singer":[{"name":"周杰伦"}],"album":{"mid":"004Alb","name":"叶惠美"},"interval":269}]}}}}}""")
        val searchPage = official.search("晴天", 0, 30, customApi::parseSongs)
        assertEquals(1, searchPage.items.size)
        assertEquals("晴天", searchPage.items[0].title)
        assertEquals("cloud:qq:88", searchPage.items[0].key)
        val searchBody = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("10086", searchBody.getJSONObject("comm").getString("uin"))
        assertEquals("DoSearchForQQMusicDesktop", searchBody.getJSONObject("req_1").getString("method"))

        reply("""{"code":0,"req_0":{"data":{"sip":["http://isure.stream.qqmusic.qq.com/"],"midurlinfo":[{"purl":"C400003Song.m4a?vkey=xyz"}]}}}""")
        val url = official.resolveBlocking("003Song")
        assertEquals("https://isure.stream.qqmusic.qq.com/C400003Song.m4a?vkey=xyz", url)
        val vkeyBody = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("987654", vkeyBody.getJSONObject("req_0").getJSONObject("param").getString("guid"))
    }

    @Test
    fun officialWechatLoginUsesMusicIdentityAndBothCsrfTokens() {
        val cookie = "uin=o10086; wxuin=1152921504606846976; wxopenid=wechat; login_type=2; qqmusic_key=music; skey=legacy"
        val base = "http://127.0.0.1:${server.port}"
        val wechat = OfficialQqClient({ cookie }, { "10086" }, OkHttpClient(), base, base)
        reply("""{"code":0,"data":{"creator":{"uin":"1152921504606846976","nick":"WeChat"}}}""")
        assertEquals(1152921504606846976L, wechat.account().userId)
        val request = server.takeRequest()
        assertEquals(cookie, request.getHeader("Cookie"))
        assertEquals("1152921504606846976", request.requestUrl!!.queryParameter("uin"))
        assertEquals("1152921504606846976", request.requestUrl!!.queryParameter("loginUin"))
        val session = QqSession(cookie)
        assertEquals(session.gtk().toString(), request.requestUrl!!.queryParameter("g_tk"))
        assertEquals(session.gtk(modern = true).toString(), request.requestUrl!!.queryParameter("g_tk_new_20200303"))

        reply("""{"code":0,"req_1":{"data":{}}}""")
        wechat.search("test", 0, 30, customApi::parseSongs)
        val comm = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("comm")
        assertEquals("1152921504606846976", comm.getString("uin"))
        assertEquals(session.gtk(), comm.getLong("g_tk"))
        assertEquals(session.gtk(modern = true), comm.getLong("g_tk_new_20200303"))
    }

    @Test
    fun officialLoginStillRejectsExpiredSessions() {
        for (code in listOf(1000, 200003)) {
            reply("""{"code":$code}""")
            try {
                official.account()
                fail("Expected expired session")
            } catch (expected: CloudApiException) {
                assertEquals(301, expected.status)
            }
        }
    }

    @Test
    fun officialLoginRejectsMissingProfileInsteadOfSavingAnAccount() {
        reply("""{"code":0}""")
        try {
            official.account()
            fail("Expected invalid profile")
        } catch (expected: IOException) {
            assertEquals("INVALID_RESPONSE", expected.message)
        }
    }

    @Test
    fun customAndOfficialArtistAlbumsAndTrackListsClassifyConsistently() = runBlocking {
        reply("""{"result":100,"data":{"total":2,"list":[{"albumID":201,"albumMID":"00Alb1","albumName":"单曲误标专辑","singerName":"歌手","albumtype":"专辑","song_count":2,"pubTime":"2026-05-01"},{"albumID":202,"albumMID":"00Alb2","albumName":"完整专辑","singerName":"歌手","albumtype":"","song_count":10,"pubTime":"2025-10-01"}]}}""")
        val customPage = customApi.artistAlbums("00Singer1")
        assertEquals(2, customPage.items.size)
        assertEquals(PROVIDER_QQ, customPage.items[0].provider)
        assertEquals("https://y.qq.com/music/photo_new/T002R500x500M00000Alb1.jpg", customPage.items[0].artwork)
        assertEquals("/singer/album?singermid=00Singer1&pageNo=1&pageSize=30", server.takeRequest().path)

        reply("""{"result":100,"data":{"list":[{"songid":1,"songmid":"00S1","songname":"主打歌","singer":[{"name":"歌手"}],"albummid":"00Alb1","albumname":"单曲误标专辑","interval":210},{"songid":2,"songmid":"00S2","songname":"主打歌 (伴奏)","singer":[{"name":"歌手"}],"albummid":"00Alb1","albumname":"单曲误标专辑","interval":210}]}}""")
        val customTracks = customApi.albumTracks("00Alb1")
        assertEquals(2, customTracks.size)
        assertEquals(ArtistReleaseCategory.SINGLE_OR_EP, classifyArtistRelease(customPage.items[0].copy(tracks = customTracks)).category)
        assertEquals(ArtistReleaseCategory.ALBUM, classifyArtistRelease(customPage.items[1]).category)
        assertEquals("/album/songs?albummid=00Alb1", server.takeRequest().path)

        reply("""{"code":0,"req_0":{"data":{"total":1,"albumList":[{"albumID":301,"albumMid":"00Alb3","albumName":"五首长曲专辑","singerName":"歌手","albumType":"EP","totalNum":5,"publishDate":"2024-01-01"}]}}}""")
        val officialAlbums = official.artistAlbums("00Singer1", 0, 30, customApi::parseAlbums)
        assertEquals(1, officialAlbums.items.size)
        val albumListBody = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("GetAlbumList", albumListBody.getJSONObject("req_0").getString("method"))

        reply("""{"code":0,"req_0":{"data":{"songList":[{"songInfo":{"id":11,"mid":"00M1","name":"Part 1","singer":[{"name":"歌手"}],"album":{"mid":"00Alb3","name":"五首长曲专辑"},"interval":420}},{"songInfo":{"id":12,"mid":"00M2","name":"Part 2","singer":[{"name":"歌手"}],"album":{"mid":"00Alb3","name":"五首长曲专辑"},"interval":420}},{"songInfo":{"id":13,"mid":"00M3","name":"Part 3","singer":[{"name":"歌手"}],"album":{"mid":"00Alb3","name":"五首长曲专辑"},"interval":420}},{"songInfo":{"id":14,"mid":"00M4","name":"Part 4","singer":[{"name":"歌手"}],"album":{"mid":"00Alb3","name":"五首长曲专辑"},"interval":420}},{"songInfo":{"id":15,"mid":"00M5","name":"Part 5","singer":[{"name":"歌手"}],"album":{"mid":"00Alb3","name":"五首长曲专辑"},"interval":420}}]}}}""")
        val officialTracks = official.albumTracks("00Alb3", customApi::parseSongs)
        assertEquals(5, officialTracks.size)
        assertEquals(ArtistReleaseCategory.ALBUM, classifyArtistRelease(officialAlbums.items[0].copy(tracks = officialTracks)).category)
        val songListBody = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("GetAlbumSongList", songListBody.getJSONObject("req_0").getString("method"))
    }

    @Test
    fun officialAndCustomFetchQualitiesAndResolveRequestedTier() = runBlocking {
        reply("""{"code":0,"songinfo":{"code":0,"data":{"track_info":{"mid":"003Song","interval":240,"file":{"media_mid":"003Media","size_128mp3":3840000,"size_192ogg":5760000,"size_320mp3":9600000,"size_flac":28800000,"size_hires":72000000,"size_new":[90000000,60000000,75000000]}}}}}""")
        val officialQualities = official.qualityDetails("003Song")
        assertEquals(
            listOf(
                btm.m.auraplayer.playback.StreamQuality.STANDARD,
                btm.m.auraplayer.playback.StreamQuality.HIGHER,
                btm.m.auraplayer.playback.StreamQuality.HIGH,
                btm.m.auraplayer.playback.StreamQuality.LOSSLESS,
                btm.m.auraplayer.playback.StreamQuality.HIRES,
                btm.m.auraplayer.playback.StreamQuality.SPATIAL,
                btm.m.auraplayer.playback.StreamQuality.VIVID,
                btm.m.auraplayer.playback.StreamQuality.MASTER,
            ),
            officialQualities.map { it.quality },
        )
        val detailBody = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("get_song_detail_yqq", detailBody.getJSONObject("songinfo").getString("method"))

        reply("""{"code":0,"req_0":{"data":{"sip":["http://isure.stream.qqmusic.qq.com/"],"midurlinfo":[{"purl":"F000003Media.flac?vkey=flac"}]}}}""")
        val flacUrl = official.resolveBlocking("003Song", "lossless")
        assertEquals("https://isure.stream.qqmusic.qq.com/F000003Media.flac?vkey=flac", flacUrl)
        val vkeyReq = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("F000003Media.flac", vkeyReq.getJSONObject("req_0").getJSONObject("param").getJSONArray("filename").getString(0))
        assertEquals("lossless", btm.m.auraplayer.playback.PlaybackStreams.qqRecords["003Song"]?.level)

        reply("""{"result":100,"data":{"track_info":{"mid":"004Custom","interval":200,"file":{"media_mid":"004Custom","size_128mp3":3200000,"size_320mp3":8000000,"size_flac":24000000}}}}""")
        val customQualities = customApi.qualityDetails("004Custom")
        assertEquals(listOf("standard", "exhigh", "lossless"), customQualities.map { it.quality.level })
        assertEquals("/song?songmid=004Custom", server.takeRequest().path)

        reply("""{"result":100,"data":"http://isure.stream.qqmusic.qq.com/M800004Custom.mp3?vkey=320"}""")
        val custom320 = customApi.resolveBlocking("004Custom", "exhigh")
        assertEquals("https://isure.stream.qqmusic.qq.com/M800004Custom.mp3?vkey=320", custom320)
        assertEquals("/song/url?id=004Custom&type=320", server.takeRequest().path)
        assertEquals("exhigh", btm.m.auraplayer.playback.PlaybackStreams.qqRecords["004Custom"]?.level)
    }
}
