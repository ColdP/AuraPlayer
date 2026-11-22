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

internal fun playbackAudioCacheKey(
    provider: String,
    trackId: String,
    quality: String,
    scope: String = "",
): String? = when (provider) {
    "netease" -> if (scope.isNotBlank()) "aura://netease/$trackId?quality=$quality&scope=$scope" else "aura://netease/$trackId?quality=$quality"
    "qqmusic" -> "aura://qqmusic/$trackId?quality=standard"
    else -> null
}

internal fun playbackAudioCacheFlags(flags: Int): Int =
    (flags and DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN.inv()) or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION

internal fun migratePlaybackAudioCache(legacy: File, persistent: File): File = when {
    persistent.isDirectory || !legacy.isDirectory -> persistent
    legacy.renameTo(persistent) -> persistent
    else -> legacy
}

/** Disk cache for online audio. The cache key is the stable Aura URI and includes quality. */
object PlaybackAudioCache {
    private const val DEFAULT_MAX_BYTES = 2048L * 1024 * 1024
    private val holder = AtomicReference<SimpleCache?>(null)
    private val trialKeys = java.util.Collections.synchronizedSet(HashSet<String>())

    internal fun markTrialKey(key: String) {
        if (key.isNotBlank()) trialKeys.add(key)
    }

    internal fun clearTrialKey(key: String) {
        if (key.isNotBlank()) trialKeys.remove(key)
    }

    internal fun isTrialKey(key: String): Boolean = key in trialKeys

    @Synchronized
    fun get(context: Context): SimpleCache {
        holder.get()?.let { return it }
        val configuredBytes = runCatching {
            PlaybackPreferences(context.applicationContext).audioCacheMaxMb * 1024L * 1024L
        }.getOrDefault(DEFAULT_MAX_BYTES)
        val cache = SimpleCache(
            migratePlaybackAudioCache(legacyDirectory(context), persistentDirectory(context)),
            LeastRecentlyUsedCacheEvictor(configuredBytes),
            StandaloneDatabaseProvider(context.applicationContext),
        )
        holder.set(cache)
        return cache
    }

    fun isFullyCached(context: Context, key: String): Boolean {
        if (key.isBlank() || isTrialKey(key)) return false
        val cache = get(context)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0L && cache.isCached(key, 0L, length)
    }

    /** Returns an already-cached NetEase quality at or above [minQuality], preferring [preferred]. */
    fun findCachedQuality(
        context: Context,
        songId: Long,
        preferred: StreamQuality,
        minQuality: StreamQuality,
    ): StreamQuality? {
        if (songId <= 0L) return null
        if (isFullyCached(context, key(context, songId, preferred))) return preferred
        for (candidate in StreamQuality.entries.asReversed()) {
            if (candidate.ordinal < minQuality.ordinal) break
            if (candidate == preferred) continue
            if (isFullyCached(context, key(context, songId, candidate))) return candidate
        }
        return null
    }

    @Synchronized
    fun trimToLimit(context: Context, maxBytes: Long) {
        val cache = holder.get() ?: return
        if (cache.cacheSpace <= maxBytes) return
        val entries = cache.keys.map { key ->
            val lastTouch = cache.getCachedSpans(key).maxOfOrNull { it.lastTouchTimestamp } ?: 0L
            key to lastTouch
        }.sortedBy { it.second }
        for ((key, _) in entries) {
            if (cache.cacheSpace <= maxBytes) break
            runCatching { cache.removeResource(key) }
        }
    }

    fun key(songId: Long, quality: StreamQuality): String = key(songId, quality.level)

    fun key(context: Context, songId: Long, quality: StreamQuality): String = key(context, songId, quality.level)

    internal fun key(songId: Long, level: String): String = checkNotNull(playbackAudioCacheKey("netease", "$songId", level))

    internal fun key(context: Context, songId: Long, level: String): String {
        val scope = (context.applicationContext as? btm.m.auraplayer.AuraApplication)?.neteaseAudioCacheScope().orEmpty()
        return checkNotNull(playbackAudioCacheKey("netease", "$songId", level, scope))
    }

    fun size(context: Context): Long = holder.get()?.cacheSpace ?: listOf(persistentDirectory(context), legacyDirectory(context))
        .sumOf { directory -> directory.walkTopDown().filter(File::isFile).sumOf(File::length) }

    /** Copies a complete cached item, returning false when playback has only cached a partial range. */
    fun copyIfFullyCached(context: Context, key: String, output: File): Boolean {
        if (isTrialKey(key)) return false
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
        if (holder.get() == null) {
            persistentDirectory(context).deleteRecursively()
            legacyDirectory(context).deleteRecursively()
        }
    }

    private fun legacyDirectory(context: Context) = context.cacheDir.resolve("audio-streams")
    private fun persistentDirectory(context: Context) = context.noBackupFilesDir.resolve("audio-streams")

    private fun copy(source: androidx.media3.datasource.DataSource, sink: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == -1) break
            if (count > 0) sink.write(buffer, 0, count)
        }
    }
}
