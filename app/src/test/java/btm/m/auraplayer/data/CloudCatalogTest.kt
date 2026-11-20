package btm.m.auraplayer.data

import btm.m.auraplayer.playback.StreamQuality
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class CloudCatalogTest {
    private fun withServer(test: suspend (MockWebServer, NeteaseApi) -> Unit) = runBlocking {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try { test(server, NeteaseApi({ "http://127.0.0.1:${server.port}" }, { "test" })) } finally { server.shutdown() }
    }
    private fun MockWebServer.reply(body: String) = enqueue(MockResponse().setBody(body))
    @Test fun albumReturnsSongsReleaseInformationAndOtherAlbums() = withServer { server, api ->
        server.reply("""{"code":200,"album":{"id":12,"name":"Album","picUrl":"cover","publishTime":1234,"company":"Label","artist":{"id":5,"name":"Singer"}},"songs":[{"id":2,"name":"Song","ar":[{"id":5,"name":"Singer"}],"al":{"id":12,"name":"Album","picUrl":"cover"}}]}""")
        server.reply("""{"code":200,"hotAlbums":[{"id":12,"name":"Album"},{"id":13,"name":"Related","picUrl":"other","artist":{"id":5}}]}""")
        val album = api.albumPage(12)
        assertEquals("Album", album.title); assertEquals("cover", album.artwork)
        assertEquals(1234L, album.info!!.published); assertEquals("Label", album.info!!.company)
        assertEquals(13L, album.info!!.albums.single().id)
        assertEquals(12L, album.page.items.single().albumId); assertFalse(album.page.hasMore)
    }
    @Test fun artistUsesPortraitAndDoesNotRefetchBiographyForEachPage() = withServer { server, api ->
        server.reply("""{"code":200,"more":true,"total":60,"songs":[{"id":1,"name":"Top"}]}""")
        server.reply("""{"code":200,"data":{"artist":{"id":5,"name":"Singer","cover":"portrait","briefDesc":"Biography","transNames":["歌手"],"alias":["Singer","Alias B"],"musicSize":128,"albumSize":14},"identify":{"imageDesc":"华语流行歌手"}}}""")
        server.reply("""{"code":200,"hotAlbums":[{"id":12,"name":"New release","publishTime":2026,"type":"专辑"},{"id":13,"name":"Latest EP","publishTime":2026,"type":"EP/Single"}]}""")
        val first = api.artistPage(5)
        assertEquals("portrait", first.artwork); assertEquals("Biography", first.info!!.description)
        assertEquals(listOf("歌手", "Alias B"), first.info!!.artistAliases)
        assertEquals("华语流行歌手", first.info!!.artistIdentity)
        assertEquals(128, first.info!!.artistSongCount)
        assertEquals(14, first.info!!.artistAlbumCount)
        assertEquals("专辑", first.info!!.albums[0].type)
        assertEquals("EP/Single", first.info!!.albums[1].type)
        assertTrue(first.page.hasMore); assertEquals(60, first.page.total)
        server.reply("""{"code":200,"more":false,"songs":[{"id":2,"name":"Next"}]}""")
        val second = api.artistPage(5, first.page.nextOffset)
        assertEquals(2, second.page.nextOffset); assertFalse(second.page.hasMore)
        assertEquals(4, server.requestCount)
        assertEquals("/artist/songs", server.takeRequest().path)
        assertEquals("/artist/detail", server.takeRequest().path)
        assertEquals("/artist/album", server.takeRequest().path)
        assertTrue(server.takeRequest().body.readUtf8().contains("offset=1"))
    }
    @Test fun qualitiesKeepUnknownBitDepthUnknownAndDownloadsRejectSilentDowngrades() = withServer { server, api ->
        server.reply("""{"code":200,"data":{"l":{"br":128000,"sr":44100},"sq":{"br":1155000,"sr":44100,"type":"flac","bitDepth":16}}}""")
        val quality = api.qualityDetails(1)
        assertEquals(listOf(StreamQuality.STANDARD, StreamQuality.LOSSLESS), quality.map { it.quality })
        assertEquals(0, quality.first().audio.bits); assertEquals(16, quality.last().audio.bits)
        server.reply("""{"code":200,"data":{"url":"http://music.example/song.flac","level":"lossless","type":"flac"}}""")
        val source = api.downloadSource(1, StreamQuality.LOSSLESS)
        assertEquals("https://music.example/song.flac", source.url)
        server.reply("""{"code":200,"data":{"url":"https://music.example/song.mp3","level":"standard","type":"mp3"}}""")
        try { api.downloadSource(1, StreamQuality.LOSSLESS); fail("Expected quality mismatch") } catch (_: IllegalArgumentException) { }
        server.reply("""{"code":200,"data":{"url":null}}""")
        try { api.downloadSource(1, StreamQuality.STANDARD); fail("Expected unavailable") } catch (_: java.io.IOException) { }
    }
    @Test fun artistFollowStateAndSubscriptionUseExpectedEndpoints() = withServer { server, api ->
        server.reply("""{"code":200,"followed":true}""")
        assertTrue(api.artistFollowed(5))
        assertEquals("/artist/detail/dynamic", server.takeRequest().path)
        server.reply("""{"code":200}""")
        api.followArtist(5, true)
        val sub = server.takeRequest()
        assertEquals("/artist/sub", sub.path)
        assertTrue(sub.body.readUtf8().contains("id=5&t=1"))
        server.reply("""{"code":200}""")
        api.followArtist(5, false)
        val unsub = server.takeRequest()
        assertEquals("/artist/sub", unsub.path)
        assertTrue(unsub.body.readUtf8().contains("id=5&t=0"))
    }
}
