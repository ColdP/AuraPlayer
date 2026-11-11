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
    @Test fun unauthenticatedRequestDoesNotAttachSavedSession() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"code":200}"""))
        client.request("captcha/sent", mapOf("phone" to "12345"), false)
        assertFalse(server.takeRequest().getHeader("Cookie")!!.contains("MUSIC_U"))
    }
}
