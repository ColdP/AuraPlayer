package btm.m.auraplayer.data

import android.content.Context
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import btm.m.auraplayer.playback.PlaybackAudioCache

enum class CloudCacheCategory { ARTWORK, METADATA, PLAYBACK_AUDIO }

/** Only generated data is cleared; account, playlists, downloaded audio and settings stay intact. */
class CloudCacheStore(private val context: Context, private val api: NeteaseApi) {
    suspend fun sizes(): Map<CloudCacheCategory, Long> = withContext(Dispatchers.IO) {
        mapOf(
            CloudCacheCategory.ARTWORK to (SingletonImageLoader.get(context).diskCache?.size ?: 0L),
            CloudCacheCategory.METADATA to context.cacheDir.resolve("cloud-metadata")
                .listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() },
            CloudCacheCategory.PLAYBACK_AUDIO to PlaybackAudioCache.size(context),
        )
    }

    suspend fun clear(categories: Set<CloudCacheCategory>) = withContext(Dispatchers.IO) {
        if (CloudCacheCategory.ARTWORK in categories) {
            val loader = SingletonImageLoader.get(context)
            loader.diskCache?.clear()
            loader.memoryCache?.clear()
        }
        if (CloudCacheCategory.METADATA in categories) api.clearMetadataCache()
        if (CloudCacheCategory.PLAYBACK_AUDIO in categories) PlaybackAudioCache.clear(context)
    }
}
