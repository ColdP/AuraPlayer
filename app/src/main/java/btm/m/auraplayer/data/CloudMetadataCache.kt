package btm.m.auraplayer.data

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Bounded, disposable metadata only. Session cookies and expiring audio URLs are never stored. */
class CloudMetadataCache(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 16L * 1024 * 1024,
) {
    companion object {
        const val FRESH_MS = 15L * 60 * 1000
        private const val RETAIN_MS = 7L * 24 * 60 * 60 * 1000
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun read(key: String, stale: Boolean = false): JSONObject? = runCatching {
        val file = File(directory, digest(key) + ".json")
        val entry = JSONObject(file.readText())
        val age = now() - entry.getLong("savedAt")
        if (age < 0 || age > if (stale) RETAIN_MS else FRESH_MS) return null
        file.setLastModified(now())
        entry.getJSONObject("data")
    }.getOrNull()

    @Synchronized
    fun write(key: String, data: JSONObject) {
        runCatching {
            directory.mkdirs()
            val file = File(directory, digest(key) + ".json")
            val pending = File(directory, file.name + ".tmp")
            pending.writeText(JSONObject().put("key", key).put("savedAt", now()).put("data", data).toString())
            java.nio.file.Files.move(pending.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val files = directory.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.lastModified() }
            var bytes = files.sumOf { it.length() }
            var count = files.size
            for (old in files) {
                if (bytes <= maxBytes && count <= 512) break
                val size = old.length()
                if (old.delete()) { bytes -= size; count-- }
            }
        }
    }

    @Synchronized
    fun removePrefix(prefix: String) {
        directory.listFiles { f -> f.extension == "json" }.orEmpty().forEach { file ->
            runCatching { if (JSONObject(file.readText()).getString("key").startsWith(prefix)) file.delete() }
        }
    }

    @Synchronized
    fun clear() {
        directory.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
            check(file.delete() || !file.exists()) { "CACHE_DELETE_FAILED" }
        }
    }
}
