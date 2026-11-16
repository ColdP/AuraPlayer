package btm.m.auraplayer.sing

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class AuraSingCacheEntry(
    val id: String,
    val songKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val modelId: String,
    val sampleRate: Int,
    val channels: Int,
    val totalFrames: Long,
    val durationMs: Long,
    val vocalsBytes: Long,
    val instrumentalBytes: Long,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
) {
    val totalBytes: Long get() = vocalsBytes + instrumentalBytes
}

/**
 * Disk cache of separated stereo 44,100 Hz 16-bit PCM vocals + instrumental tracks.
 * Directory layout: filesDir/aura_sing_cache/<entryId>/{meta.json, vocals.pcm, instrumental.pcm}
 */
class AuraSingCache(private val rootDir: File) {
    constructor(context: Context) : this(File(context.filesDir, "aura_sing_cache"))

    companion object {
        const val MAX_CACHE_BYTES = 2048L * 1024L * 1024L // 2 GiB
        private const val VOCALS_FILE = "vocals.pcm"
        private const val INST_FILE = "instrumental.pcm"
        private const val META_FILE = "meta.json"

        fun entryId(songKey: String, modelId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$songKey|$modelId".toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }
    }

    fun entryDirectory(id: String): File = File(rootDir, id)
    fun vocalsFile(id: String): File = File(entryDirectory(id), VOCALS_FILE)
    fun instrumentalFile(id: String): File = File(entryDirectory(id), INST_FILE)
    private fun metaFile(id: String): File = File(entryDirectory(id), META_FILE)

    fun hasValidEntry(id: String): Boolean {
        val vocals = vocalsFile(id)
        val inst = instrumentalFile(id)
        val meta = metaFile(id)
        return meta.isFile && vocals.isFile && inst.isFile && vocals.length() > 0L && inst.length() == vocals.length()
    }

    fun findEntry(songKey: String, modelId: String): AuraSingCacheEntry? {
        val id = entryId(songKey, modelId)
        if (!hasValidEntry(id)) return null
        val meta = readMeta(id) ?: return null
        touch(id)
        return meta
    }

    fun listEntries(): List<AuraSingCacheEntry> {
        if (!rootDir.isDirectory) return emptyList()
        return rootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val id = dir.name
                if (hasValidEntry(id)) readMeta(id) else null
            }
            ?.sortedByDescending { it.lastUsedAtMs }
            ?: emptyList()
    }

    fun totalCacheBytes(): Long {
        if (!rootDir.isDirectory) return 0L
        return rootDir.walkTopDown().filter(File::isFile).sumOf(File::length)
    }

    fun commitEntry(
        songKey: String,
        title: String,
        artist: String,
        album: String,
        modelId: String,
        totalFrames: Int,
        durationMs: Long,
        vocalsTemp: File,
        instTemp: File,
    ): AuraSingCacheEntry {
        if (!rootDir.isDirectory) rootDir.mkdirs()
        val id = entryId(songKey, modelId)
        val dir = entryDirectory(id)
        dir.deleteRecursively()
        dir.mkdirs()
        val vocalsDst = vocalsFile(id)
        val instDst = instrumentalFile(id)
        check(vocalsTemp.renameTo(vocalsDst)) { "无法写入人声音轨缓存" }
        check(instTemp.renameTo(instDst)) { "无法写入伴奏音轨缓存" }
        val now = System.currentTimeMillis()
        val entry = AuraSingCacheEntry(
            id = id,
            songKey = songKey,
            title = title,
            artist = artist,
            album = album,
            modelId = modelId,
            sampleRate = AuraSingMath.HTDEMUCS_SAMPLE_RATE,
            channels = AuraSingMath.HTDEMUCS_CHANNELS,
            totalFrames = totalFrames.toLong(),
            durationMs = durationMs.coerceAtLeast(totalFrames.toLong() * 1000L / AuraSingMath.HTDEMUCS_SAMPLE_RATE),
            vocalsBytes = vocalsDst.length(),
            instrumentalBytes = instDst.length(),
            createdAtMs = now,
            lastUsedAtMs = now,
        )
        writeMeta(entry)
        trimToBudget()
        return entry
    }

    fun deleteEntry(id: String): Boolean = entryDirectory(id).deleteRecursively()

    fun clearAll() {
        rootDir.deleteRecursively()
    }

    private fun touch(id: String) {
        val current = readMeta(id) ?: return
        val now = System.currentTimeMillis()
        if (now - current.lastUsedAtMs > 10_000L) {
            writeMeta(current.copy(lastUsedAtMs = now))
        }
    }

    private fun trimToBudget() {
        var total = totalCacheBytes()
        if (total <= MAX_CACHE_BYTES) return
        val oldestFirst = listEntries().sortedBy { it.lastUsedAtMs }
        for (entry in oldestFirst) {
            if (total <= MAX_CACHE_BYTES) break
            val bytes = entry.totalBytes
            if (deleteEntry(entry.id)) {
                total = (total - bytes).coerceAtLeast(0L)
            }
        }
    }

    private fun readMeta(id: String): AuraSingCacheEntry? {
        val file = metaFile(id)
        if (!file.isFile) return null
        val obj = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val vLen = vocalsFile(id).length()
        val iLen = instrumentalFile(id).length()
        return AuraSingCacheEntry(
            id = id,
            songKey = obj.optString("songKey"),
            title = obj.optString("title"),
            artist = obj.optString("artist"),
            album = obj.optString("album"),
            modelId = obj.optString("modelId", AuraSingModel.DEFAULT.id),
            sampleRate = obj.optInt("sampleRate", AuraSingMath.HTDEMUCS_SAMPLE_RATE),
            channels = obj.optInt("channels", AuraSingMath.HTDEMUCS_CHANNELS),
            totalFrames = obj.optLong("totalFrames", vLen / 4L),
            durationMs = obj.optLong("durationMs", 0L),
            vocalsBytes = vLen,
            instrumentalBytes = iLen,
            createdAtMs = obj.optLong("createdAtMs", 0L),
            lastUsedAtMs = obj.optLong("lastUsedAtMs", 0L),
        )
    }

    private fun writeMeta(entry: AuraSingCacheEntry) {
        metaFile(entry.id).writeText(JSONObject().apply {
            put("songKey", entry.songKey)
            put("title", entry.title)
            put("artist", entry.artist)
            put("album", entry.album)
            put("modelId", entry.modelId)
            put("sampleRate", entry.sampleRate)
            put("channels", entry.channels)
            put("totalFrames", entry.totalFrames)
            put("durationMs", entry.durationMs)
            put("createdAtMs", entry.createdAtMs)
            put("lastUsedAtMs", entry.lastUsedAtMs)
        }.toString())
    }
}
