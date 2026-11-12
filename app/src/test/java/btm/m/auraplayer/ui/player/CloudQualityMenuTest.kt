package btm.m.auraplayer.ui.player

import btm.m.auraplayer.data.NeteaseApi
import btm.m.auraplayer.playback.StreamQuality
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class CloudQualityMenuTest {
    @Test fun returnedQualitiesBecomeSelectableDownloadOptions() = runBlocking {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            server.enqueue(MockResponse().setBody("""{"code":200,"data":{"l":{"br":128000},"sq":{"br":1155000,"sr":44100,"type":"flac","bitDepth":16}}}"""))
            val levels = NeteaseApi({ "http://127.0.0.1:${server.port}" }, { "" }).qualityDetails(42)
            var downloaded: StreamQuality? = null
            val items = qualityDropdownItems(levels, StreamQuality.LOSSLESS, { it.quality.level }) {
                downloaded = it.quality
            }
            assertEquals(listOf("standard", "lossless"), items.map { it.text })
            assertTrue(items.last().selected)
            assertFalse(items.first().selected)
            items.last().onClick!!.invoke()
            assertEquals(StreamQuality.LOSSLESS, downloaded)
        } finally { server.shutdown() }
    }
}
