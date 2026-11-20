package btm.m.auraplayer.playback

import androidx.media3.datasource.DataSpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackAudioCacheTest {
    @get:Rule val folders = TemporaryFolder()

    @Test fun finiteStreamsWithoutAContentLengthStillWriteFragments() {
        val flags = playbackAudioCacheFlags(DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN or DataSpec.FLAG_ALLOW_GZIP)
        assertEquals(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION or DataSpec.FLAG_ALLOW_GZIP, flags)
    }

    @Test fun neteaseCacheDistinguishesQualityAndQqCacheDoesNot() {
        assertEquals("aura://netease/42?quality=lossless", playbackAudioCacheKey("netease", "42", "lossless"))
        assertNotEquals(playbackAudioCacheKey("netease", "42", "standard"), playbackAudioCacheKey("netease", "42", "lossless"))
        assertEquals("aura://qqmusic/test?quality=standard", playbackAudioCacheKey("qqmusic", "test", "lossless"))
        assertEquals(playbackAudioCacheKey("qqmusic", "test", "lossless"), playbackAudioCacheKey("qqmusic", "test", "standard"))
        assertNull(playbackAudioCacheKey("local", "42", "standard"))
    }

    @Test fun migrationKeepsMedia3UidAndAudioSpansTogether() {
        val legacy = folders.newFolder("legacy")
        val persistent = folders.root.resolve("persistent")
        legacy.resolve("123.uid").createNewFile()
        legacy.resolve("0").mkdirs()
        legacy.resolve("0/0.0.1.v3.exo").writeText("cached bytes")
        assertEquals(persistent, migratePlaybackAudioCache(legacy, persistent))
        assertTrue(persistent.resolve("123.uid").isFile)
        assertEquals("cached bytes", persistent.resolve("0/0.0.1.v3.exo").readText())
        assertFalse(legacy.exists())
        assertEquals(persistent, migratePlaybackAudioCache(legacy, persistent))
    }
}
