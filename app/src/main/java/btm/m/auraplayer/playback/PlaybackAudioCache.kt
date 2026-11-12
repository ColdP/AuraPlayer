package btm.m.auraplayer.playback

import android.content.Context
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

/** Disk cache for online audio. The cache key is the stable Aura URI and includes quality. */
object PlaybackAudioCache {
    private const val MAX_BYTES = 512L * 1024 * 1024
    private val holder = AtomicReference<SimpleCache?>(null)

    @Synchronized
    fun get(context: Context): SimpleCache {
        holder.get()?.let { return it }
        val cache = SimpleCache(
            context.cacheDir.resolve("audio-streams"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        )
        holder.set(cache)
        return cache
    }

    fun key(songId: Long, quality: StreamQuality): String = "aura://netease/$songId?quality=${quality.level}"

    fun size(context: Context): Long = holder.get()?.cacheSpace ?: directory(context).walkTopDown().filter(File::isFile).sumOf(File::length)

    /** Copies a complete cached item, returning false when playback has only cached a partial range. */
    fun copyIfFullyCached(context: Context, key: String, output: File): Boolean {
        val cache = get(context)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length <= 0L || !cache.isCached(key, 0L, length)) return false
        val source = CacheDataSource.Factory().setCache(cache).setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
            .createDataSourceForDownloading()
        return runCatching {
            source.open(DataSpec.Builder().setUri(key).setPosition(0).setLength(length).build())
            output.parentFile?.mkdirs()
            output.outputStream().buffered().use { sink -> copy(source, sink) }
            source.close()
            output.length() == length
        }.getOrElse {
            runCatching { source.close() }
            output.delete()
            false
        }
    }

    @Synchronized
    fun clear(context: Context) {
        holder.get()?.let { cache -> cache.keys.toList().forEach(cache::removeResource) }
        if (holder.get() == null) directory(context).deleteRecursively()
    }

    private fun directory(context: Context) = context.cacheDir.resolve("audio-streams")

    private fun copy(source: androidx.media3.datasource.DataSource, sink: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == -1) break
            if (count > 0) sink.write(buffer, 0, count)
        }
    }
}
