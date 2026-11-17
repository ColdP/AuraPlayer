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
}
