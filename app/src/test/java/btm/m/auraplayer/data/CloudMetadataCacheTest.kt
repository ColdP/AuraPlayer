package btm.m.auraplayer.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress

class CloudMetadataCacheTest {
    @get:Rule val temp = TemporaryFolder(File("build/tmp/cloud-cache-tests").apply { mkdirs() })

    @Test fun persistsAcrossInstancesExpiresAndRejectsCorruptEntries() {
        var now = 1000L
        val directory = temp.newFolder()
        val cache = CloudMetadataCache(directory, { now })
        cache.write("one", JSONObject().put("name", "歌单"))
        assertEquals("歌单", CloudMetadataCache(directory, { now }).read("one")!!.getString("name"))
        now += CloudMetadataCache.FRESH_MS + 1
        assertNull(cache.read("one"))
        assertNotNull(cache.read("one", stale = true))
        directory.listFiles()!!.single().writeText("broken")
        assertNull(cache.read("one", stale = true))
    }

    @Test fun boundedCacheEvictsAndSelectiveInvalidationKeepsOtherPlaylists() {
        val directory = temp.newFolder()
        val cache = CloudMetadataCache(directory, maxBytes = 1500)
        repeat(20) { cache.write("p/$it", JSONObject().put("text", "x".repeat(180))) }
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 1500)
        cache.write("a/page/0", JSONObject().put("id", 1))
        cache.write("b/page/0", JSONObject().put("id", 2))
        cache.removePrefix("a/")
        assertNull(cache.read("a/page/0"))
        assertNotNull(cache.read("b/page/0"))
        cache.clear()
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun repeatedPagesUseDiskButRefreshAndDifferentAccountsUseNetwork() = runBlocking {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            val endpoint = "http://127.0.0.1:${server.port}"
            var cookie = "MUSIC_U=first"
            val directory = temp.newFolder()
            fun api() = NeteaseApi({ endpoint }, { cookie }, metadataCache = CloudMetadataCache(directory))
            fun reply(id: Int) = server.enqueue(MockResponse().setBody("""{"code":200,"more":false,"playlist":[{"id":$id}]}"""))
            reply(1)
            assertEquals(1L, api().playlists(42).items.single().id)
            assertEquals(1L, api().playlists(42).items.single().id)
            assertEquals(1, server.requestCount)
            reply(2)
            assertEquals(2L, api().playlists(42, refresh = true).items.single().id)
            cookie = "MUSIC_U=second"
            reply(3)
            assertEquals(3L, api().playlists(42).items.single().id)
            assertEquals(3, server.requestCount)
            assertFalse(directory.listFiles()!!.any { it.readText().contains("MUSIC_U") })
        } finally { server.shutdown() }
    }

    @Test fun staleCacheNeverHidesAnExpiredSession() = runBlocking {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            var now = 1000L
            val cache = CloudMetadataCache(temp.newFolder(), { now })
            val api = NeteaseApi({ "http://127.0.0.1:${server.port}" }, { "session" }, metadataCache = cache)
            server.enqueue(MockResponse().setBody("""{"code":200,"more":false,"playlist":[]}"""))
            api.playlists(42)
            now += CloudMetadataCache.FRESH_MS + 1
            server.enqueue(MockResponse().setBody("""{"code":301,"message":"expired"}"""))
            try { api.playlists(42); fail("Expected session expiry") }
            catch (e: CloudApiException) { assertEquals(301, e.status) }
        } finally { server.shutdown() }
    }

    @Test fun officialIndexIsReusedAndUnavailableSongsStillAdvanceSourceOffset() {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            val base = "http://127.0.0.1:${server.port}"
            val client = OfficialNeteaseClient({ "session" }, OkHttpClient(), base, base, CloudMetadataCache(temp.newFolder()))
            server.enqueue(MockResponse().setBody("""{"code":200,"playlist":{"trackIds":[{"id":1},{"id":2},{"id":3}]}}"""))
            server.enqueue(MockResponse().setBody("""{"code":200,"songs":[]}"""))
            val first = client.request("playlist/track/all", mapOf("id" to "10", "offset" to "0", "limit" to "2"), true)
            assertEquals(2, first.getInt("nextOffset"))
            assertTrue(first.getBoolean("more"))
            server.enqueue(MockResponse().setBody("""{"code":200,"songs":[{"id":3}]}"""))
            val last = client.request("playlist/track/all", mapOf("id" to "10", "offset" to "2", "limit" to "2"), true)
            assertEquals(3, last.getInt("nextOffset"))
            assertFalse(last.getBoolean("more"))
            assertEquals(3, server.requestCount)
            assertEquals("/weapi/v6/playlist/detail", server.takeRequest().path)
            assertEquals("/weapi/v3/song/detail", server.takeRequest().path)
            assertEquals("/weapi/v3/song/detail", server.takeRequest().path)
        } finally { server.shutdown() }
    }
}
