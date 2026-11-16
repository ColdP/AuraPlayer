// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.lyrics

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class LlmRawOutputRecord(
    val id: String,
    val createdAtMs: Long,
    val songKey: String,
    val songTitle: String,
    val artist: String,
    val albumTitle: String,
    val provider: LlmApiProvider,
    val modelName: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val httpStatus: Int,
    val succeeded: Boolean,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1,
    val rawContent: String = "",
    val rawResponseBody: String = "",
    val sizeBytes: Long = 0L,
) {
    val primaryOutput: String
        get() = rawContent.ifBlank { rawResponseBody }
}

enum class LlmRawOutputExportFormat(val extension: String, val mimeType: String) {
    JSON("json", "application/json"),
    TXT("txt", "text/plain"),
}

internal object LlmRawOutputExporter {
    const val ZIP_MANIFEST_NAME = "raw_outputs.json"

    fun suggestedFileName(record: LlmRawOutputRecord, format: LlmRawOutputExportFormat): String {
        val baseTitle = sanitizeFilePart(record.songTitle, 48, "llm_output")
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date(record.createdAtMs))
        val chunkTag = if (record.totalChunks > 1) "_p${record.chunkIndex + 1}" else ""
        return "${baseTitle}_${stamp}${chunkTag}.${format.extension}"
    }

    fun export(record: LlmRawOutputRecord, format: LlmRawOutputExportFormat): String = when (format) {
        LlmRawOutputExportFormat.JSON -> toJson(record)
        LlmRawOutputExportFormat.TXT -> toTxt(record)
    }

    fun toJson(record: LlmRawOutputRecord): String = JSONObject().apply {
        put("id", record.id)
        put("createdAtMs", record.createdAtMs)
        put("songKey", record.songKey)
        put("songTitle", record.songTitle)
        put("artist", record.artist)
        put("albumTitle", record.albumTitle)
        put("provider", record.provider.name)
        put("modelName", record.modelName)
        put("sourceLanguage", record.sourceLanguage)
        put("targetLanguage", record.targetLanguage)
        put("httpStatus", record.httpStatus)
        put("succeeded", record.succeeded)
        put("chunkIndex", record.chunkIndex)
        put("totalChunks", record.totalChunks)
        put("rawContent", record.rawContent)
        put("rawResponseBody", record.rawResponseBody)
    }.toString(2)

    fun toTxt(record: LlmRawOutputRecord): String = buildString {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(record.createdAtMs))
        appendLine("Title: ${record.songTitle.ifBlank { "Untitled" }}")
        if (record.artist.isNotBlank()) appendLine("Artist: ${record.artist}")
        if (record.albumTitle.isNotBlank()) appendLine("Album: ${record.albumTitle}")
        appendLine("Time: $timestamp")
        appendLine("Provider: ${record.provider.name}")
        if (record.modelName.isNotBlank()) appendLine("Model: ${record.modelName}")
        appendLine("Language: ${record.sourceLanguage} -> ${record.targetLanguage}")
        appendLine("HTTP Status: ${record.httpStatus} (succeeded=${record.succeeded})")
        if (record.totalChunks > 1) {
            appendLine("Batch: ${record.chunkIndex + 1}/${record.totalChunks}")
        }
        appendLine()
        appendLine("--- Model Output ---")
        appendLine(record.rawContent.ifBlank { "(empty)" })
        if (record.rawResponseBody.isNotBlank() && record.rawResponseBody != record.rawContent) {
            appendLine()
            appendLine("--- Raw HTTP Response ---")
            appendLine(record.rawResponseBody)
        }
    }

    fun writeZip(records: List<LlmRawOutputRecord>, output: OutputStream) {
        require(records.isNotEmpty())
        val usedNames = HashSet<String>(records.size)
        ZipOutputStream(output.buffered()).use { zip ->
            val manifest = JSONArray()
            records.forEach { record ->
                val baseTitle = sanitizeFilePart(record.songTitle, 40, "llm_output")
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(record.createdAtMs))
                val idSuffix = sanitizeFilePart(record.id, 12, "item")
                val stem = listOf(baseTitle, stamp, idSuffix)
                    .filter { it.isNotBlank() }
                    .joinToString("-")
                var fileName = "$stem.json"
                var dedup = 1
                while (!usedNames.add(fileName.lowercase(Locale.ROOT))) {
                    fileName = "$stem-${dedup++}.json"
                }
                manifest.put(
                    JSONObject()
                        .put("id", record.id)
                        .put("createdAtMs", record.createdAtMs)
                        .put("songTitle", record.songTitle)
                        .put("artist", record.artist)
                        .put("modelName", record.modelName)
                        .put("provider", record.provider.name)
                        .put("httpStatus", record.httpStatus)
                        .put("succeeded", record.succeeded)
                        .put("fileName", fileName),
                )
                zip.putNextEntry(ZipEntry(fileName))
                zip.write(toJson(record).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(ZIP_MANIFEST_NAME))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun sanitizeFilePart(value: String, maxLen: Int, fallback: String): String =
        value.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .take(maxLen)
            .trim()
            .ifBlank { fallback }
}

object LlmRawOutputHistoryStore {
    private const val DIR_NAME = "llm_raw_output_history"
    private const val FILE_EXT = ".json"
    private val fileLock = Any()
    private val sequence = java.util.concurrent.atomic.AtomicLong(0L)

    fun historyDirectory(cacheRoot: File): File = File(cacheRoot, DIR_NAME)

    fun buildId(
        createdAtMs: Long,
        songKey: String,
        modelName: String,
        chunkIndex: Int,
        rawPayload: String,
    ): String {
        val seq = sequence.getAndIncrement()
        val seed = "$createdAtMs|$seq|$songKey|$modelName|$chunkIndex|${rawPayload.hashCode()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        val hex = digest.take(6).joinToString("") { "%02x".format(it) }
        return "${createdAtMs}_${chunkIndex}_$hex"
    }

    fun record(cacheRoot: File, entry: LlmRawOutputRecord): Boolean = synchronized(fileLock) {
        val dir = historyDirectory(cacheRoot)
        if (!dir.exists() && !dir.mkdirs()) return false
        val target = File(dir, fileNameForId(entry.id))
        val encoded = LlmRawOutputExporter.toJson(entry)
        runCatching {
            target.writeText(encoded, Charsets.UTF_8)
        }.isSuccess
    }

    fun list(cacheRoot: File): List<LlmRawOutputRecord> = synchronized(fileLock) {
        val dir = historyDirectory(cacheRoot)
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(FILE_EXT) } ?: return emptyList()
        files.mapNotNull(::decodeFile)
            .sortedWith(compareByDescending<LlmRawOutputRecord> { it.createdAtMs }.thenByDescending { it.chunkIndex })
    }

    fun deleteEntries(cacheRoot: File, ids: Collection<String>): Int = synchronized(fileLock) {
        if (ids.isEmpty()) return 0
        val dir = historyDirectory(cacheRoot)
        if (!dir.isDirectory) return 0
        var deleted = 0
        for (id in ids) {
            val file = File(dir, fileNameForId(id))
            if (file.isFile && file.delete()) deleted++
        }
        deleted
    }

    fun clear(cacheRoot: File): Boolean = synchronized(fileLock) {
        val dir = historyDirectory(cacheRoot)
        if (!dir.exists()) return true
        val files = dir.listFiles().orEmpty()
        files.all { !it.exists() || it.deleteRecursively() }
    }

    internal fun filter(records: List<LlmRawOutputRecord>, query: String): List<LlmRawOutputRecord> {
        val tokens = query.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { it.lowercase(Locale.ROOT) }
        if (tokens.isEmpty()) return records
        return records.filter { record ->
            val searchable = buildString {
                append(record.songTitle).append('\n')
                append(record.artist).append('\n')
                append(record.albumTitle).append('\n')
                append(record.modelName).append('\n')
                append(record.provider.name).append('\n')
                append(record.sourceLanguage).append('\n')
                append(record.targetLanguage).append('\n')
                append(record.httpStatus).append('\n')
                append(record.rawContent).append('\n')
                append(record.rawResponseBody)
            }.lowercase(Locale.ROOT)
            tokens.all { token -> token in searchable }
        }
    }

    private fun fileNameForId(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + FILE_EXT
    }

    private fun decodeFile(file: File): LlmRawOutputRecord? = runCatching {
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val id = root.optString("id").ifBlank { file.nameWithoutExtension }
        val provider = LlmApiProvider.entries.firstOrNull { it.name == root.optString("provider") }
            ?: LlmApiProvider.OPENAI_COMPATIBLE
        LlmRawOutputRecord(
            id = id,
            createdAtMs = root.optLong("createdAtMs", file.lastModified()),
            songKey = root.optString("songKey"),
            songTitle = root.optString("songTitle"),
            artist = root.optString("artist"),
            albumTitle = root.optString("albumTitle"),
            provider = provider,
            modelName = root.optString("modelName"),
            sourceLanguage = root.optString("sourceLanguage"),
            targetLanguage = root.optString("targetLanguage"),
            httpStatus = root.optInt("httpStatus", 0),
            succeeded = root.optBoolean("succeeded", false),
            chunkIndex = root.optInt("chunkIndex", 0).coerceAtLeast(0),
            totalChunks = root.optInt("totalChunks", 1).coerceAtLeast(1),
            rawContent = root.optString("rawContent"),
            rawResponseBody = root.optString("rawResponseBody"),
            sizeBytes = file.length().coerceAtLeast(0L),
        )
    }.getOrNull()
}
