package btm.m.auraplayer.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class NeteaseApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: NeteaseApi
    @Before fun setup() {
        server = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
        api = NeteaseApi({ "http://127.0.0.1:${server.port}/api" }, { "MUSIC_U=secret-session" })
    }
    @After fun cleanup() { server.shutdown() }
    private fun reply(json: String) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(json)) }

    @Test fun smsDoesNotSendExistingSessionAndUsesCorrectCountryParameter() = runBlocking {
        reply("""{"code":200,"data":true}""")
        api.sendCaptcha("13800000000", "86")
        val request = server.takeRequest()
        assertEquals("/api/captcha/sent", request.path)
        val form = request.body.readUtf8()
        assertTrue(form.contains("ctcode=86"))
        assertFalse(form.contains("cookie"))
        assertFalse(request.path!!.contains("13800000000"))
    }
    @Test fun loginReturnsSessionAndAccountWithoutRequestingPassword() = runBlocking {
        reply("""{"code":200,"cookie":"MUSIC_U=test","profile":{"userId":42,"nickname":"Aura","avatarUrl":"https://example.org/a.jpg"}}""")
        val (cookie, account) = api.login("13800000000", "86", "123456")
        assertEquals("MUSIC_U=test", cookie)
        assertEquals(42L, account.userId)
        val request = server.takeRequest()
        assertEquals("/api/login/cellphone", request.path)
        val form = request.body.readUtf8()
        assertTrue(form.contains("countrycode=86"))
        assertTrue(form.contains("captcha=123456"))
        assertFalse(form.contains("password"))
        assertFalse(form.contains("secret-session"))
    }
    @Test fun paginatesPlaylistsUntilMoreIsFalse() = runBlocking {
        reply("""{"code":200,"more":true,"playlist":[{"id":1,"name":"One","trackCount":2}]}""")
        reply("""{"code":200,"more":false,"playlist":[{"id":2,"name":"Two","trackCount":3}]}""")
        assertEquals(listOf(1L, 2L), api.playlists(42).map { it.id })
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("offset=0"))
        assertTrue(second.contains("offset=1"))
        assertTrue(first.contains("cookie=MUSIC_U%3Dsecret-session"))
    }
    @Test fun streamResolutionKeepsRightsRestrictionAndRejectsNullUrl() {
        reply("""{"code":200,"data":[{"id":1,"url":null}]}""")
        try { api.resolveBlocking(1); fail("Expected unavailable song") } catch (expected: java.io.IOException) { assertEquals("UNAVAILABLE_SONG", expected.message) }
        val form = server.takeRequest().body.readUtf8()
        assertTrue(form.contains("unblock=false"))
        assertTrue(form.contains("level=standard"))
    }
    @Test fun doesNotFollowRedirectWithCredentials() {
        val other = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/leak")))
            try { api.requestBlocking("user/playlist"); fail("Expected redirect rejection") } catch (e: CloudApiException) { assertEquals(302, e.status) }
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }
    @Test fun surfacesLoginExpiryInsteadOfReturningAnEmptyLibrary() = runBlocking {
        reply("""{"code":301,"message":"login required"}""")
        try { api.playlists(42); fail("Expected expired session") } catch (e: CloudApiException) { assertEquals(301, e.status) }
    }
    @Test fun parsesBothSearchAndPlaylistSongShapes() {
        val songs = api.parseSongs(JSONArray("""[
            {"id":1,"name":"One","dt":1000,"ar":[{"id":8,"name":"A"}],"al":{"id":9,"name":"Album","picUrl":"https://example.org/1.jpg"}},
            {"id":2,"name":"Two","duration":2000,"artists":[{"id":8,"name":"A"},{"id":10,"name":"B"}],"album":{"name":"Other"}}
        ]"""))
        assertEquals("aura://netease/1", songs[0].uri)
        assertEquals("A / B", songs[1].artist)
        assertEquals(2000L, songs[1].duration)
        assertTrue(songs.all { it.cloud })
    }
    @Test fun rejectsPublicCleartextAndEmbeddedCredentials() {
        for (url in listOf("http://example.org", "http://10.example.org", "https://user:password@example.org", "https://example.org/?cookie=secret")) {
            try { NeteaseApi.normalizeEndpoint(url); fail(url) } catch (_: IllegalArgumentException) { }
        }
        assertEquals("http://192.168.1.2:3000", NeteaseApi.normalizeEndpoint("http://192.168.1.2:3000/"))
        assertEquals("https://example.org/api", NeteaseApi.normalizeEndpoint("https://example.org/api/"))
    }
}
