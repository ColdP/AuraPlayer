package btm.m.auraplayer.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class CloudLibraryFeaturesTest {
    private fun withApi(test: suspend (MockWebServer, NeteaseApi) -> Unit) = runBlocking {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try { test(server, NeteaseApi({ "http://127.0.0.1:${server.port}" }, { "test" })) } finally { server.shutdown() }
    }
    private fun MockWebServer.reply(body: String) = enqueue(MockResponse().setBody(body))
    @Test fun addingCloudSongUsesEnhancedApiPlaylistTracksRoute() = withApi { server, api ->
        server.reply("""{"code":200}""")
        api.addSongToPlaylist(10, 42)
        val request = server.takeRequest()
        assertEquals("/playlist/tracks", request.path)
        val fields = request.body.readUtf8().split('&').map { it.split('=', limit = 2) }
            .associate { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") }
        assertEquals("add", fields["op"])
        assertEquals("10", fields["pid"])
        assertEquals("42", fields["tracks"])
        server.reply("""{"code":403}""")
        try { api.addSongToPlaylist(10, 42); fail("Denied additions must not report success") }
        catch (e: CloudApiException) { assertEquals(403, e.status) }
    }
    @Test fun likedPlaylistIdentityAndUserArePreserved() = withApi { server, api ->
        server.reply("""{"code":200,"playlist":[{"id":10,"name":"Mine","specialType":5,"creator":{"userId":42},"trackCount":2}],"more":false}""")
        val playlist = api.playlists(42).items.single()
        assertEquals(5, playlist.specialType); assertEquals(42L, playlist.creatorId)
        server.reply("""{"code":200,"ids":[1,2]}""")
        assertEquals(setOf(1L, 2L), api.likedSongIds(42))
        server.reply("""{"code":200}"""); api.likeSong(2, false)
        server.takeRequest(); assertEquals("/likelist", server.takeRequest().path)
        val action = server.takeRequest(); assertEquals("/like", action.path)
        assertTrue(action.body.readUtf8().contains("like=false"))
        server.reply("""{"code":301}""")
        try { api.likeSong(2, true); fail("Failed likes must remain visible") } catch (e: CloudApiException) { assertEquals(301, e.status) }
    }
    @Test fun subscribedAndArtistAlbumsUseIndependentOffsets() = withApi { server, api ->
        server.reply("""{"code":200,"data":[{"id":10,"name":"Saved","artists":[{"id":3,"name":"Singer"}]}],"hasMore":true}""")
        val saved = api.subscribedAlbums(25)
        assertEquals(26, saved.nextOffset); assertTrue(saved.hasMore); assertEquals(3L, saved.items.single().artistId)
        assertTrue(server.takeRequest().body.readUtf8().contains("offset=25"))
        server.reply("""{"code":200,"hotAlbums":[{"id":20,"name":"Older"}],"more":false}""")
        val artist = api.artistAlbums(3, 30)
        assertEquals(31, artist.nextOffset); assertFalse(artist.hasMore)
        assertTrue(server.takeRequest().body.readUtf8().contains("offset=30"))
    }
    @Test fun albumArtistAndLyricSearchKeepTheirResultTypes() = withApi { server, api ->
        server.reply("""{"code":200,"result":{"albums":[{"id":4,"name":"Album","artist":{"id":3,"name":"Singer"}}]}}""")
        val album = api.searchCategory("x", SearchCategory.ALBUM).items.single()
        assertEquals(SearchCategory.ALBUM, album.kind); assertEquals(3L, album.artistId)
        assertTrue(server.takeRequest().body.readUtf8().contains("type=10"))
        server.reply("""{"code":200,"result":{"artists":[{"id":3,"name":"Singer","picUrl":"portrait"}]}}""")
        assertEquals("portrait", api.searchCategory("x", SearchCategory.ARTIST).items.single().artwork)
        assertTrue(server.takeRequest().body.readUtf8().contains("type=100"))
        server.reply("""{"code":200,"result":{"songs":[{"id":9,"name":"Lyric match"}]}}""")
        assertEquals(9L, api.searchCategory("line", SearchCategory.LYRICS).items.single().song!!.id)
        assertTrue(server.takeRequest().body.readUtf8().contains("type=1006"))
    }
    @Test fun voicesResolveMainSongAndPodcastsLoadPrograms() = withApi { server, api ->
        server.reply("""{"code":200,"data":{"resources":[{"resourceId":"12","baseInfo":{"id":12,"name":"Episode","coverUrl":"cover"}}],"hasMore":true}}""")
        val voice = api.searchCategory("x", SearchCategory.VOICE)
        assertEquals(12L, voice.items.single().id); assertTrue(voice.hasMore)
        assertEquals("/search", server.takeRequest().path)
        server.reply("""{"code":200,"program":{"id":12,"name":"Episode","dj":{"nickname":"Host"},"coverUrl":"cover","mainSong":{"id":900,"name":"Audio","duration":12000}}}""")
        val song = api.voiceSong(12)!!
        assertEquals(900L, song.id); assertEquals("Episode", song.title); assertEquals("Host", song.artist)
        server.reply("""{"code":200,"result":{"djRadios":[{"id":8,"name":"Podcast","picUrl":"art"}]}}""")
        assertEquals(SearchCategory.BLOG, api.searchCategory("x", SearchCategory.BLOG).items.single().kind)
        server.reply("""{"code":200,"programs":[{"id":12,"name":"Episode","mainSong":{"id":900,"name":"Audio"}}],"more":false}""")
        assertEquals(900L, api.radioPrograms(8, 0).items.single().id)
    }
    @Test fun rankPreservesServerOrderAndTrackMetadata() = withApi { server, api ->
        server.reply("""{"code":200,"allData":[{"playCount":99,"song":{"id":2,"name":"First"}},{"playCount":9,"song":{"id":1,"name":"Second"}}]}""")
        assertEquals(listOf(2L, 1L), api.listeningRank(42).map { it.id })
    }
    @Test fun localSearchSeparatesEntitiesWithoutCloudResults() {
        val songs = listOf(Song(1, "First", "Singer", "Album", 10, 20, uri = "one"), Song(2, "Second", "Singer", "Album", 10, 20, uri = "two"))
        val albums = localMusicSearch(songs, MusicSearchRequest("Album", false, SearchCategory.ALBUM))
        assertEquals(1, albums.items.size); assertFalse(albums.hasMore); assertFalse(albums.items.single().cloud)
        assertEquals(2, localMusicSearch(songs, MusicSearchRequest("Singer", false, SearchCategory.SONG)).items.size)
        assertEquals(SearchCategory.ARTIST, localMusicSearch(songs, MusicSearchRequest("Singer", false, SearchCategory.ARTIST)).items.single().kind)
        assertTrue(localMusicSearch(songs, MusicSearchRequest("Singer", false, SearchCategory.LYRICS)).items.isEmpty())
    }
}
