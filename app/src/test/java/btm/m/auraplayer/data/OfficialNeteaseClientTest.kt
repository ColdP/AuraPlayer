package btm.m.auraplayer.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class OfficialNeteaseClientTest {
    @Test fun matchesUpstreamEncryptionVectorsIncludingUtf8() {
        val text = """{"hello":"音乐","e_r":false}"""
        val encrypted = NeteaseCrypto.weapi(text, "aaaaaaaaaaaaaaaa")
        assertEquals("EYg4w4+pp4DlxH1gwQMRYziLV2mju4hbfcEl5gr8+scNAbTuYo7l0uzDWRPDHtBK", encrypted["params"])
        assertEquals("d473b9eca232f1b4090dd606b0df86de318748dd2eec307e4ed4345030fc4ee30331e598f41d5a6f5befaab94630ea1a1eda7cfade84fbec1a907913d2e4d2c8744bc572b99a050075e075b4537f645ecfa994f95906c32818e076aeda6bdb906bfa0bb96c4cf4bc3ed6d9ab76cf08441153d9d85e1ea3d78fa8d9210d581cee", encrypted["encSecKey"])
        assertEquals("4DC723619A991588865191FD2F319BAD8954CD01D5119DCCE845005F60FF9CD901A4BAA9035B74C3FEFABD2D92DC1A9485A7278A9A5CFABBA38D85AB7D4FFAE2E08A63C74DD62C18DA64A6A28DD33444F1E805F2E9140504C84C4D42A860FDEACA3A66B3F11E51EFD0F5AD0CE6A32783", NeteaseCrypto.eapi("/api/test", text)["params"])
    }
    private fun withServer(block: (MockWebServer, OfficialNeteaseClient) -> Unit) {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        val base = "http://127.0.0.1:${server.port}"
        try { block(server, OfficialNeteaseClient({ "MUSIC_U=test-session; __csrf=csrf" }, OkHttpClient(), base, base)) }
        finally { server.shutdown() }
    }
    @Test fun requestsPlaybackAtOfficialEapiWithoutPuttingSessionInUrl() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"code":200,"data":[{"url":"https://example.org/music.mp3"}]}"""))
        client.request("song/url/v1", mapOf("id" to "42", "level" to "standard"), true)
        val request = server.takeRequest()
        assertEquals("/eapi/song/enhance/player/url/v1", request.path)
        assertTrue(request.getHeader("Cookie")!!.contains("MUSIC_U=test-session"))
        val hex = URLDecoder.decode(request.body.readUtf8().substringAfter("params="), "UTF-8")
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val decrypted = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES")); String(doFinal(bytes))
        }
        val data = JSONObject(decrypted.split("-36cd479b6b5-")[1])
        assertEquals("[42]", data.getString("ids"))
        assertEquals("standard", data.getString("level"))
        assertFalse(data.getBoolean("e_r"))
    }
    @Test fun officialPlaylistPaginationRetrievesOnlyRequestedTrackSlice() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"code":200,"playlist":{"trackIds":[{"id":1},{"id":2}]}}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"songs":[{"id":2}]}"""))
        val result = client.request("playlist/track/all", mapOf("id" to "10", "offset" to "1", "limit" to "1"), true)
        assertEquals(2L, result.getJSONArray("songs").getJSONObject(0).getLong("id"))
        assertEquals("/weapi/v6/playlist/detail", server.takeRequest().path)
        assertEquals("/weapi/v3/song/detail", server.takeRequest().path)
    }
    @Test fun routesDiscoveryHeartbeatAndFollowsThroughOfficialClient() = withServer { server, client ->
        val routes = listOf(
            Triple("playmode/intelligence/list", mapOf("id" to "42", "pid" to "10"), "/eapi/playmode/intelligence/list"),
            Triple("recommend/songs", emptyMap(), "/weapi/v3/discovery/recommend/songs"),
            Triple("personalized/newsong", emptyMap(), "/weapi/personalized/newsong"),
            Triple("user/follows", mapOf("uid" to "42"), "/weapi/user/getfollows/42"),
            Triple("artist/detail/dynamic", mapOf("id" to "77"), "/weapi/artist/detail/dynamic"),
            Triple("artist/sub", mapOf("id" to "77", "t" to "1"), "/weapi/artist/sub"),
            Triple("artist/sub", mapOf("id" to "77", "t" to "0"), "/weapi/artist/unsub"),
        )
        for ((route, parameters, path) in routes) {
            server.enqueue(MockResponse().setBody("""{"code":200}"""))
            client.request(route, parameters, true)
            assertEquals(path, server.takeRequest().path)
        }
    }
    @Test fun unauthenticatedRequestDoesNotAttachSavedSession() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"code":200}"""))
        client.request("captcha/sent", mapOf("phone" to "12345"), false)
        assertFalse(server.takeRequest().getHeader("Cookie")!!.contains("MUSIC_U"))
    }
    @Test fun officialPlaylistAdditionRetriesTheDocumented512Response() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"code":512}"""))
        server.enqueue(MockResponse().setBody("""{"code":200}"""))
        assertEquals(200, client.request("playlist/tracks", mapOf("pid" to "10", "tracks" to "42", "op" to "add"), true).getInt("code"))
        assertEquals("/weapi/playlist/manipulate/tracks", server.takeRequest().path)
        assertEquals("/weapi/playlist/manipulate/tracks", server.takeRequest().path)
        assertEquals(2, server.requestCount)
    }
    @Test fun musicDetailUsesEapiFirstAndFallsBackToWeapiWhenEmpty() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"code":200,"data":{}}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"data":{"l":{"br":128000},"sq":{"br":900000}}}"""))
        val result = client.request("song/music/detail", mapOf("id" to "42"), true)
        assertTrue(NeteaseApi.hasQualityDetails(result))
        assertEquals("/eapi/song/music/detail/get", server.takeRequest().path)
        assertEquals("/weapi/song/music/detail/get", server.takeRequest().path)
    }
}
