// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.lyrics

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs

data class LlmCachedLine(
    val startMs: Int,
    val text: String,
    val translation: String?,
)

data class LlmTranslationCacheEntry(
    val cacheKey: String,
    val songKey: String,
    val songTitle: String,
    val artist: String,
    val albumTitle: String,
    val modelName: String,
    val provider: LlmApiProvider,
    val sourceLanguage: String,
    val targetLanguage: String,
    val customStylePrompt: String,
    val lines: List<LlmCachedLine>,
    val updatedAtMs: Long,
    val sizeBytes: Long = 0L,
    val imported: Boolean = false,
)

enum class LlmTranslationExportFormat(val extension: String, val mimeType: String) {
    JSON("json", "application/json"),
    MARKDOWN("md", "text/markdown"),
    TXT("txt", "text/plain"),
}

internal fun formatLlmCacheBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe < 1024L -> "$safe B"
        safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
        safe < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", safe / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
    }
}

internal object LlmTranslationExporter {
    fun suggestedFileName(entry: LlmTranslationCacheEntry, format: LlmTranslationExportFormat): String {
        val rawTitle = entry.songTitle.trim().ifBlank { "lyrics" }
        val rawArtist = entry.artist.trim()
        val base = if (rawArtist.isNotEmpty()) "$rawTitle - $rawArtist" else rawTitle
        val sanitized = sanitizeFilePart(base, 80, "lyrics")
        return "$sanitized.${format.extension}"
    }

    fun export(entry: LlmTranslationCacheEntry, format: LlmTranslationExportFormat): String = when (format) {
        LlmTranslationExportFormat.JSON -> toJson(entry)
        LlmTranslationExportFormat.MARKDOWN -> toMarkdown(entry)
        LlmTranslationExportFormat.TXT -> toTxt(entry)
    }

    fun writeZip(selected: List<LlmTranslationCacheEntry>, output: OutputStream) {
        require(selected.isNotEmpty())
        val usedNames = HashSet<String>(selected.size)
        ZipOutputStream(output.buffered()).use { zip ->
            val manifest = JSONArray()
            selected.forEach { entry ->
                val baseTitle = sanitizeFilePart(entry.songTitle, 48, "lyrics")
                val baseArtist = sanitizeFilePart(entry.artist, 32, "")
                val keySuffix = LlmTranslationCache.shortHash(entry.cacheKey)
                val stem = listOf(baseTitle, baseArtist, keySuffix)
                    .filter { it.isNotBlank() }
                    .joinToString("-")
                var fileName = "$stem.json"
                var dedup = 1
                while (!usedNames.add(fileName.lowercase(Locale.ROOT))) {
                    fileName = "$stem-${dedup++}.json"
                }
                manifest.put(
                    JSONObject()
                        .put("cacheKey", entry.cacheKey)
                        .put("songKey", entry.songKey)
                        .put("songTitle", entry.songTitle)
                        .put("artist", entry.artist)
                        .put("albumTitle", entry.albumTitle)
                        .put("modelName", entry.modelName)
                        .put("provider", entry.provider.name)
                        .put("sourceLanguage", entry.sourceLanguage)
                        .put("targetLanguage", entry.targetLanguage)
                        .put("updatedAtMs", entry.updatedAtMs)
                        .put("fileName", fileName),
                )
                zip.putNextEntry(ZipEntry(fileName))
                zip.write(export(entry, LlmTranslationExportFormat.JSON).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(ZIP_MANIFEST_NAME))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    internal const val ZIP_MANIFEST_NAME = "translations.json"

    private fun sanitizeFilePart(value: String, maxLen: Int, fallback: String): String =
        value.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .take(maxLen)
            .trim()
            .ifBlank { fallback }

    private fun toJson(entry: LlmTranslationCacheEntry): String {
        val array = JSONArray()
        for (line in entry.lines) {
            array.put(
                JSONObject()
                    .put("timeMs", line.startMs)
                    .put("timestamp", formatTimestamp(line.startMs))
                    .put("original", line.text)
                    .put("translation", line.translation ?: JSONObject.NULL),
            )
        }
        return JSONObject().apply {
            if (entry.songKey.isNotBlank()) put("songKey", entry.songKey)
            put("songTitle", entry.songTitle)
            put("artist", entry.artist)
            put("albumTitle", entry.albumTitle)
            put("modelName", entry.modelName)
            put("provider", entry.provider.name)
            put("sourceLanguage", entry.sourceLanguage)
            put("targetLanguage", entry.targetLanguage)
            if (entry.customStylePrompt.isNotBlank()) put("customStylePrompt", entry.customStylePrompt)
            put("updatedAtMs", entry.updatedAtMs)
            put("lines", array)
        }.toString(2)
    }

    private fun toMarkdown(entry: LlmTranslationCacheEntry): String = buildString {
        val header = if (entry.artist.isNotBlank()) "${entry.songTitle} — ${entry.artist}" else entry.songTitle
        append("# ").append(header.ifBlank { "Lyrics" }).append("\n\n")
        if (entry.albumTitle.isNotBlank()) append("- **Album**: ").append(entry.albumTitle).append('\n')
        if (entry.modelName.isNotBlank()) append("- **Model**: ").append(entry.modelName).append('\n')
        append("- **Languages**: ").append(entry.sourceLanguage).append(" -> ").append(entry.targetLanguage).append("\n\n")
        for (line in entry.lines) {
            append("**[").append(formatTimestamp(line.startMs)).append("]** ").append(line.text).append("  \n")
            if (!line.translation.isNullOrBlank()) {
                append(line.translation).append('\n')
            }
            append('\n')
        }
    }.trimEnd() + "\n"

    private fun toTxt(entry: LlmTranslationCacheEntry): String = buildString {
        append(entry.songTitle.ifBlank { "Untitled" })
        if (entry.artist.isNotBlank()) append(" - ").append(entry.artist)
        append('\n')
        if (entry.albumTitle.isNotBlank()) append(entry.albumTitle).append('\n')
        if (entry.modelName.isNotBlank()) append("Model: ").append(entry.modelName).append('\n')
        append('\n')
        for (line in entry.lines) {
            append('[').append(formatTimestamp(line.startMs)).append("] ").append(line.text).append('\n')
            if (!line.translation.isNullOrBlank()) {
                append(line.translation).append('\n')
            }
        }
    }

    internal fun formatTimestamp(ms: Int): String {
        val safe = ms.coerceAtLeast(0)
        val minutes = safe / 60000
        val seconds = (safe / 1000) % 60
        val hundredths = (safe % 1000) / 10
        return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, hundredths)
    }
}

internal object LlmTranslationImporter {
    private const val MAX_SINGLE_FILE_BYTES = 16L * 1024L * 1024L
    private const val MAX_ZIP_BYTES = 64L * 1024L * 1024L
    private const val MAX_ZIP_ENTRIES = 1000

    private val mdTimestampRegex = Regex("""^\*{0,2}\[(\d{1,3}:\d{2}(?:\.\d{1,3})?)]\*{0,2}\s*(.*)$""")
    private val txtTimestampRegex = Regex("""^\[(\d{1,3}:\d{2}(?:\.\d{1,3})?)]\s*(.*)$""")
    private val lrcMetaRegex = Regex("""^\[(ti|ar|al):([^\]]*)]$""", RegexOption.IGNORE_CASE)
    private val headerMetaRegex = Regex("""^-\s*(?:\*\*)?([A-Za-z\u4e00-\u9fff]+)(?:\*\*)?\s*:\s*(.+)$""")

    fun importStream(
        fileName: String?,
        input: InputStream,
        defaultProvider: LlmApiProvider = LlmApiProvider.OPENAI_COMPATIBLE,
    ): List<LlmTranslationCacheEntry> {
        val bytes = readBounded(input, MAX_ZIP_BYTES)
        return importBytes(fileName, bytes, defaultProvider)
    }

    fun importBytes(
        fileName: String?,
        bytes: ByteArray,
        defaultProvider: LlmApiProvider = LlmApiProvider.OPENAI_COMPATIBLE,
    ): List<LlmTranslationCacheEntry> {
        require(bytes.isNotEmpty()) { "empty file" }
        val ext = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        val isZipMagic = bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())
        if (ext == "zip" || isZipMagic) {
            return importZipBytes(bytes, defaultProvider)
        }
        require(bytes.size <= MAX_SINGLE_FILE_BYTES) { "file too large" }
        val text = decodeUtf8Text(bytes)
        return parseTextDocument(fileName, text, defaultProvider)
    }

    private fun parseTextDocument(
        fileName: String?,
        text: String,
        defaultProvider: LlmApiProvider,
    ): List<LlmTranslationCacheEntry> {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        val trimmed = text.trimStart()
        val entries = when (ext) {
            "json" -> parseJson(text, fileName, defaultProvider)
            "md", "markdown" -> parseMarkdown(text, fileName, defaultProvider)
            "txt" -> parseTxt(text, fileName, defaultProvider)
            else -> when {
                trimmed.startsWith("{") || trimmed.startsWith("[") -> parseJson(text, fileName, defaultProvider)
                trimmed.startsWith("# ") || text.lineSequence().any { it.trimStart().startsWith("**[") } ->
                    parseMarkdown(text, fileName, defaultProvider)
                else -> parseTxt(text, fileName, defaultProvider)
            }
        }
        require(entries.isNotEmpty()) { "no valid translation entries found" }
        return entries
    }

    internal fun parseJson(
        text: String,
        fileNameHint: String? = null,
        defaultProvider: LlmApiProvider = LlmApiProvider.OPENAI_COMPATIBLE,
    ): List<LlmTranslationCacheEntry> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            return buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    parseJsonObjectEntry(obj, fileNameHint, defaultProvider, now)?.let(::add)
                }
            }
        }
        val root = JSONObject(trimmed)
        val batchArray = root.optJSONArray("entries") ?: root.optJSONArray("translations")
        if (batchArray != null && root.optJSONArray("lines") == null) {
            return buildList {
                for (i in 0 until batchArray.length()) {
                    val obj = batchArray.optJSONObject(i) ?: continue
                    parseJsonObjectEntry(obj, fileNameHint, defaultProvider, now)?.let(::add)
                }
            }
        }
        return listOfNotNull(parseJsonObjectEntry(root, fileNameHint, defaultProvider, now))
    }

    private fun parseJsonObjectEntry(
        obj: JSONObject,
        fileNameHint: String?,
        defaultProvider: LlmApiProvider,
        now: Long,
    ): LlmTranslationCacheEntry? {
        val array = obj.optJSONArray("lines") ?: return null
        val parsedLines = ArrayList<LlmCachedLine>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val original = when {
                item.has("original") && !item.isNull("original") -> item.optString("original")
                item.has("o") && !item.isNull("o") -> item.optString("o")
                item.has("text") && !item.isNull("text") -> item.optString("text")
                item.has("lyric") && !item.isNull("lyric") -> item.optString("lyric")
                else -> ""
            }
            val rawTrans = when {
                item.has("translation") && !item.isNull("translation") -> item.optString("translation")
                item.has("t") && !item.isNull("t") -> item.optString("t")
                item.has("trans") && !item.isNull("trans") -> item.optString("trans")
                else -> null
            }
            val translation = rawTrans?.trim()?.takeIf { it.isNotEmpty() }
            val startMs = when {
                item.has("timeMs") -> item.optInt("timeMs", 0)
                item.has("s") -> item.optInt("s", 0)
                item.has("startMs") -> item.optInt("startMs", 0)
                item.has("time") -> item.optInt("time", 0)
                item.has("timestamp") -> parseTimestampMs(item.optString("timestamp")) ?: 0
                else -> 0
            }.coerceAtLeast(0)
            if (original.isNotEmpty() || translation != null) {
                parsedLines += LlmCachedLine(startMs = startMs, text = original, translation = translation)
            }
        }
        if (parsedLines.isEmpty() || parsedLines.none { !it.translation.isNullOrBlank() }) return null

        val (hintTitle, hintArtist) = splitTitleAndArtistFromFileName(fileNameHint)
        val songTitle = obj.optString("songTitle").trim()
            .ifBlank { obj.optString("title").trim() }
            .ifBlank { hintTitle }
        val artist = obj.optString("artist").trim().ifBlank { hintArtist }
        val albumTitle = obj.optString("albumTitle").trim().ifBlank { obj.optString("album").trim() }
        val modelName = obj.optString("modelName").trim()
            .ifBlank { obj.optString("model").trim() }
            .ifBlank { "Imported" }
        val provider = LlmApiProvider.entries.firstOrNull {
            it.name.equals(obj.optString("provider").trim(), ignoreCase = true)
        } ?: defaultProvider
        val sourceLanguage = obj.optString("sourceLanguage").trim().ifBlank { LlmSourceLanguage.AUTO.code }
        val targetLanguage = obj.optString("targetLanguage").trim().ifBlank { LlmTargetLanguage.ZH_HANS.code }
        val customStylePrompt = obj.optString("customStylePrompt").trim()
        val songKey = obj.optString("songKey").trim()
        val existingCacheKey = obj.optString("cacheKey").trim()
        val cacheKey = existingCacheKey.ifBlank {
            buildImportedCacheKey(songKey, songTitle, artist, modelName, targetLanguage, parsedLines)
        }
        return LlmTranslationCacheEntry(
            cacheKey = cacheKey,
            songKey = songKey,
            songTitle = songTitle.ifBlank { "Untitled" },
            artist = artist,
            albumTitle = albumTitle,
            modelName = modelName,
            provider = provider,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            customStylePrompt = customStylePrompt,
            lines = parsedLines,
            updatedAtMs = now,
            imported = true,
        )
    }

    internal fun parseMarkdown(
        text: String,
        fileNameHint: String? = null,
        defaultProvider: LlmApiProvider = LlmApiProvider.OPENAI_COMPATIBLE,
    ): List<LlmTranslationCacheEntry> {
        val (hintTitle, hintArtist) = splitTitleAndArtistFromFileName(fileNameHint)
        var songTitle = ""
        var artist = ""
        var albumTitle = ""
        var modelName = ""
        var sourceLanguage = LlmSourceLanguage.AUTO.code
        var targetLanguage = LlmTargetLanguage.ZH_HANS.code

        val parsedLines = ArrayList<LlmCachedLine>()
        var currentStartMs: Int? = null
        var currentOriginal: String? = null
        val currentTranslationParts = ArrayList<String>()

        fun flushLine() {
            val orig = currentOriginal ?: return
            val trans = currentTranslationParts.joinToString(" ").trim().takeIf { it.isNotEmpty() }
            parsedLines += LlmCachedLine(
                startMs = currentStartMs ?: 0,
                text = orig,
                translation = trans,
            )
            currentStartMs = null
            currentOriginal = null
            currentTranslationParts.clear()
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            val tsMatch = mdTimestampRegex.matchEntire(trimmed)
            if (tsMatch != null) {
                flushLine()
                currentStartMs = parseTimestampMs(tsMatch.groupValues[1]) ?: 0
                currentOriginal = tsMatch.groupValues[2].trim()
                continue
            }
            if (currentOriginal == null) {
                if (trimmed.startsWith("# ") && songTitle.isBlank()) {
                    val heading = trimmed.removePrefix("# ").trim()
                    val (parsedTitle, parsedArtist) = splitHeaderTitleArtist(heading)
                    songTitle = parsedTitle
                    if (parsedArtist.isNotBlank()) artist = parsedArtist
                    continue
                }
                val metaMatch = headerMetaRegex.matchEntire(trimmed)
                if (metaMatch != null) {
                    val key = metaMatch.groupValues[1].lowercase(Locale.ROOT)
                    val value = metaMatch.groupValues[2].trim()
                    when (key) {
                        "album", "专辑" -> if (albumTitle.isBlank()) albumTitle = value
                        "artist", "歌手", "艺术家" -> if (artist.isBlank()) artist = value
                        "model", "模型" -> if (modelName.isBlank()) modelName = value
                        "languages", "language", "语言" -> {
                            val parts = value.split("->", "→", limit = 2)
                            if (parts.size == 2) {
                                sourceLanguage = parts[0].trim().ifBlank { sourceLanguage }
                                targetLanguage = parts[1].trim().ifBlank { targetLanguage }
                            }
                        }
                    }
                    continue
                }
            } else {
                if (trimmed.isEmpty()) {
                    flushLine()
                } else {
                    currentTranslationParts += trimmed
                }
            }
        }
        flushLine()

        if (parsedLines.isEmpty() || parsedLines.none { !it.translation.isNullOrBlank() }) return emptyList()
        val finalTitle = songTitle.ifBlank { hintTitle }.ifBlank { "Untitled" }
        val finalArtist = artist.ifBlank { hintArtist }
        val finalModel = modelName.ifBlank { "Imported" }
        return listOf(
            LlmTranslationCacheEntry(
                cacheKey = buildImportedCacheKey("", finalTitle, finalArtist, finalModel, targetLanguage, parsedLines),
                songKey = "",
                songTitle = finalTitle,
                artist = finalArtist,
                albumTitle = albumTitle,
                modelName = finalModel,
                provider = defaultProvider,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                customStylePrompt = "",
                lines = parsedLines,
                updatedAtMs = System.currentTimeMillis(),
                imported = true,
            ),
        )
    }

    internal fun parseTxt(
        text: String,
        fileNameHint: String? = null,
        defaultProvider: LlmApiProvider = LlmApiProvider.OPENAI_COMPATIBLE,
    ): List<LlmTranslationCacheEntry> {
        val (hintTitle, hintArtist) = splitTitleAndArtistFromFileName(fileNameHint)
        var songTitle = ""
        var artist = ""
        var albumTitle = ""
        var modelName = ""
        var seenHeaderBlank = false
        val headerLines = ArrayList<String>()

        val parsedLines = ArrayList<LlmCachedLine>()
        var currentStartMs: Int? = null
        var currentOriginal: String? = null
        val currentTranslationParts = ArrayList<String>()

        fun flushLine() {
            val orig = currentOriginal ?: return
            val trans = currentTranslationParts.joinToString(" ").trim().takeIf { it.isNotEmpty() }
            parsedLines += LlmCachedLine(
                startMs = currentStartMs ?: 0,
                text = orig,
                translation = trans,
            )
            currentStartMs = null
            currentOriginal = null
            currentTranslationParts.clear()
        }

        for (rawLine in text.lineSequence()) {
            val trimmed = rawLine.trim()
            if (currentOriginal == null && !seenHeaderBlank) {
                val lrcMeta = lrcMetaRegex.matchEntire(trimmed)
                if (lrcMeta != null) {
                    val value = lrcMeta.groupValues[2].trim()
                    when (lrcMeta.groupValues[1].lowercase(Locale.ROOT)) {
                        "ti" -> if (value.isNotBlank()) songTitle = value
                        "ar" -> if (value.isNotBlank()) artist = value
                        "al" -> if (value.isNotBlank()) albumTitle = value
                    }
                    continue
                }
            }
            val tsMatch = txtTimestampRegex.matchEntire(trimmed)
            if (tsMatch != null) {
                val startMs = parseTimestampMs(tsMatch.groupValues[1]) ?: 0
                val content = tsMatch.groupValues[2].trim()
                // Bilingual LRC saved as TXT: second consecutive line shares the exact same timestamp.
                if (currentStartMs != null && startMs == currentStartMs && currentOriginal != null &&
                    currentTranslationParts.isEmpty() && content.isNotEmpty()
                ) {
                    currentTranslationParts += content
                } else {
                    flushLine()
                    currentStartMs = startMs
                    currentOriginal = content
                }
                continue
            }

            if (parsedLines.isEmpty() && currentOriginal == null) {
                if (trimmed.isEmpty()) {
                    if (headerLines.isNotEmpty()) seenHeaderBlank = true
                    continue
                }
                val lower = trimmed.lowercase(Locale.ROOT)
                when {
                    lower.startsWith("model:") -> modelName = trimmed.substringAfter(':').trim()
                    trimmed.startsWith("模型:") || trimmed.startsWith("模型：") ->
                        modelName = trimmed.substring(3).trim()
                    else -> headerLines += trimmed
                }
            } else if (currentOriginal != null) {
                if (trimmed.isEmpty()) {
                    flushLine()
                } else {
                    currentTranslationParts += trimmed
                }
            }
        }
        flushLine()

        if (headerLines.isNotEmpty() && songTitle.isBlank()) {
            val (t, a) = splitHeaderTitleArtist(headerLines[0])
            songTitle = t
            if (a.isNotBlank() && artist.isBlank()) artist = a
            if (headerLines.size >= 2 && albumTitle.isBlank()) {
                albumTitle = headerLines[1]
            }
        }

        if (parsedLines.isEmpty() || parsedLines.none { !it.translation.isNullOrBlank() }) return emptyList()
        val finalTitle = songTitle.ifBlank { hintTitle }.ifBlank { "Untitled" }
        val finalArtist = artist.ifBlank { hintArtist }
        val finalModel = modelName.ifBlank { "Imported" }
        return listOf(
            LlmTranslationCacheEntry(
                cacheKey = buildImportedCacheKey("", finalTitle, finalArtist, finalModel, LlmTargetLanguage.ZH_HANS.code, parsedLines),
                songKey = "",
                songTitle = finalTitle,
                artist = finalArtist,
                albumTitle = albumTitle,
                modelName = finalModel,
                provider = defaultProvider,
                sourceLanguage = LlmSourceLanguage.AUTO.code,
                targetLanguage = LlmTargetLanguage.ZH_HANS.code,
                customStylePrompt = "",
                lines = parsedLines,
                updatedAtMs = System.currentTimeMillis(),
                imported = true,
            ),
        )
    }

    internal fun importZipBytes(
        zipBytes: ByteArray,
        defaultProvider: LlmApiProvider = LlmApiProvider.OPENAI_COMPATIBLE,
    ): List<LlmTranslationCacheEntry> {
        val files = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        var entryCount = 0
        ZipInputStream(ByteArrayInputStream(zipBytes).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val normalizedName = normalizeZipEntryName(entry.name)
                    if (normalizedName != null) {
                        entryCount++
                        require(entryCount <= MAX_ZIP_ENTRIES) { "too many zip entries" }
                        val remaining = MAX_ZIP_BYTES - totalBytes
                        require(remaining > 0L) { "zip uncompressed limit exceeded" }
                        val bytes = readBounded(zip, remaining)
                        totalBytes += bytes.size
                        if (normalizedName !in files) {
                            files[normalizedName] = bytes
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        require(files.isNotEmpty()) { "empty zip archive" }

        val results = ArrayList<LlmTranslationCacheEntry>()
        val consumedNames = HashSet<String>()

        val manifestName = listOf(LlmTranslationExporter.ZIP_MANIFEST_NAME, "manifest.json")
            .firstOrNull { candidate -> files.keys.any { it.equals(candidate, ignoreCase = true) } }
            ?.let { target -> files.keys.first { it.equals(target, ignoreCase = true) } }

        if (manifestName != null) {
            consumedNames += manifestName
            val manifestBytes = files.getValue(manifestName)
            val manifest = JSONArray(decodeUtf8Text(manifestBytes))
            require(manifest.length() in 1..MAX_ZIP_ENTRIES)
            for (i in 0 until manifest.length()) {
                val item = manifest.optJSONObject(i) ?: continue
                val rawFileName = item.optString("fileName").trim()
                val resolvedName = files.keys.firstOrNull { it == rawFileName || it.equals(rawFileName, ignoreCase = true) }
                    ?: continue
                consumedNames += resolvedName
                val entryBytes = files.getValue(resolvedName)
                if (entryBytes.isEmpty() || entryBytes.size > MAX_SINGLE_FILE_BYTES) continue
                val parsedList = runCatching {
                    parseTextDocument(resolvedName, decodeUtf8Text(entryBytes), defaultProvider)
                }.getOrDefault(emptyList())
                for (parsed in parsedList) {
                    results += mergeManifestMetadata(parsed, item)
                }
            }
        }

        // Also support standalone .json / .md / .txt translation files inside any ZIP archive.
        for ((name, entryBytes) in files) {
            if (name in consumedNames) continue
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext !in setOf("json", "md", "markdown", "txt")) continue
            if (entryBytes.isEmpty() || entryBytes.size > MAX_SINGLE_FILE_BYTES) continue
            val parsedList = runCatching {
                parseTextDocument(name, decodeUtf8Text(entryBytes), defaultProvider)
            }.getOrDefault(emptyList())
            results += parsedList
        }

        require(results.isNotEmpty()) { "no valid translation files in zip" }
        return results
    }

    private fun mergeManifestMetadata(entry: LlmTranslationCacheEntry, meta: JSONObject): LlmTranslationCacheEntry {
        val mSongKey = meta.optString("songKey").trim()
        val mTitle = meta.optString("songTitle").trim()
        val mArtist = meta.optString("artist").trim()
        val mAlbum = meta.optString("albumTitle").trim()
        val mModel = meta.optString("modelName").trim()
        val mSource = meta.optString("sourceLanguage").trim()
        val mTarget = meta.optString("targetLanguage").trim()
        val mProvider = LlmApiProvider.entries.firstOrNull {
            it.name.equals(meta.optString("provider").trim(), ignoreCase = true)
        }
        val mCacheKey = meta.optString("cacheKey").trim()
        return entry.copy(
            cacheKey = mCacheKey.ifBlank { entry.cacheKey },
            songKey = mSongKey.ifBlank { entry.songKey },
            songTitle = mTitle.ifBlank { entry.songTitle },
            artist = mArtist.ifBlank { entry.artist },
            albumTitle = mAlbum.ifBlank { entry.albumTitle },
            modelName = mModel.ifBlank { entry.modelName },
            provider = mProvider ?: entry.provider,
            sourceLanguage = mSource.ifBlank { entry.sourceLanguage },
            targetLanguage = mTarget.ifBlank { entry.targetLanguage },
            imported = true,
        )
    }

    private fun normalizeZipEntryName(rawName: String): String? {
        val clean = rawName.replace('\\', '/').trim().trimStart('/')
        if (clean.isEmpty()) return null
        if (clean.startsWith("__MACOSX/", ignoreCase = true)) return null
        val filePart = clean.substringAfterLast('/')
        if (filePart.isEmpty() || filePart == ".DS_Store") return null
        val segments = clean.split('/')
        if (segments.any { it == ".." || it == "." || it.isEmpty() }) return null
        return clean
    }

    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            size += count
            require(size <= maxBytes) { "input exceeds size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun decodeUtf8Text(bytes: ByteArray): String {
        val decoded = bytes.toString(Charsets.UTF_8)
        return decoded.removePrefix("\uFEFF")
    }

    private fun parseTimestampMs(raw: String): Int? {
        val parts = raw.trim().split(':')
        if (parts.size != 2) return null
        val mins = parts[0].toLongOrNull() ?: return null
        val secParts = parts[1].split('.')
        val secs = secParts[0].toLongOrNull() ?: return null
        val frac = if (secParts.size > 1) {
            val f = secParts[1].take(3).padEnd(3, '0')
            f.toLongOrNull() ?: 0L
        } else {
            0L
        }
        return ((mins * 60_000L) + (secs * 1000L) + frac).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun splitHeaderTitleArtist(header: String): Pair<String, String> {
        val emIdx = header.lastIndexOf(" — ")
        if (emIdx > 0) {
            return header.substring(0, emIdx).trim() to header.substring(emIdx + 3).trim()
        }
        val hyphenIdx = header.lastIndexOf(" - ")
        if (hyphenIdx > 0) {
            return header.substring(0, hyphenIdx).trim() to header.substring(hyphenIdx + 3).trim()
        }
        return header.trim() to ""
    }

    private fun splitTitleAndArtistFromFileName(fileName: String?): Pair<String, String> {
        val base = fileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringBeforeLast('.')
            ?.trim()
            .orEmpty()
        if (base.isEmpty()) return "" to ""
        return splitHeaderTitleArtist(base)
    }

    private fun buildImportedCacheKey(
        songKey: String,
        songTitle: String,
        artist: String,
        modelName: String,
        targetLanguage: String,
        lines: List<LlmCachedLine>,
    ): String {
        val textHash = lines.joinToString("\n") { it.text }.hashCode()
        val keySeed = listOf(
            "imported",
            songKey.trim(),
            songTitle.trim().lowercase(Locale.ROOT),
            artist.trim().lowercase(Locale.ROOT),
            modelName.trim().lowercase(Locale.ROOT),
            targetLanguage.trim().lowercase(Locale.ROOT),
            textHash,
        ).joinToString("|")
        return "imported|" + LlmTranslationCache.shortHash(keySeed) + "|" + textHash
    }
}

internal data class LlmImportedCacheMatch(
    val entry: LlmTranslationCacheEntry,
    val alignedTranslations: List<String?>,
)

object LlmTranslationCache {
    private const val DIR_NAME = "llm_lyric_translations"
    private const val FILE_EXT = ".json"
    private val fileLock = Any()

    fun cacheDirectory(cacheRoot: File): File = File(cacheRoot, DIR_NAME)

    internal fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    internal fun fileNameForKey(cacheKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + FILE_EXT
    }

    fun read(cacheRoot: File, cacheKey: String): LlmTranslationCacheEntry? = synchronized(fileLock) {
        val file = File(cacheDirectory(cacheRoot), fileNameForKey(cacheKey))
        if (!file.isFile) return null
        decodeFile(file)
    }

    fun write(cacheRoot: File, entry: LlmTranslationCacheEntry): Boolean = synchronized(fileLock) {
        writeLocked(cacheDirectory(cacheRoot), entry)
    }

    fun writeAll(cacheRoot: File, entries: List<LlmTranslationCacheEntry>): Int = synchronized(fileLock) {
        if (entries.isEmpty()) return 0
        val dir = cacheDirectory(cacheRoot)
        var written = 0
        for (entry in entries) {
            if (writeLocked(dir, entry)) written++
        }
        written
    }

    fun updateLineTranslation(
        cacheRoot: File,
        entry: LlmTranslationCacheEntry,
        lineIndex: Int,
        newTranslation: String?,
    ): LlmTranslationCacheEntry? = synchronized(fileLock) {
        if (lineIndex !in entry.lines.indices) return@synchronized null
        val normalized = newTranslation?.trim()?.takeIf { it.isNotEmpty() }
        val updatedLines = entry.lines.toMutableList()
        updatedLines[lineIndex] = updatedLines[lineIndex].copy(translation = normalized)
        val updatedEntry = entry.copy(
            lines = updatedLines,
            updatedAtMs = System.currentTimeMillis(),
        )
        val dir = cacheDirectory(cacheRoot)
        if (!writeLocked(dir, updatedEntry)) return@synchronized null
        val file = File(dir, fileNameForKey(updatedEntry.cacheKey))
        if (file.isFile) {
            updatedEntry.copy(sizeBytes = file.length().coerceAtLeast(0L))
        } else {
            updatedEntry
        }
    }

    internal const val ARTIST_SIMILARITY_THRESHOLD = 0.30

    internal fun artistSimilarity(first: String, second: String): Double {
        val a = normalizeMatchText(first)
        val b = normalizeMatchText(second)
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0

        val maxLen = maxOf(a.length, b.length)
        val levSim = 1.0 - levenshteinDistance(a, b).toDouble() / maxLen.toDouble()

        val tokensA = splitArtistTokens(a)
        val tokensB = splitArtistTokens(b)
        val union = tokensA.size + tokensB.size - tokensA.intersect(tokensB).size
        val tokenSim = if (union > 0) tokensA.intersect(tokensB).size.toDouble() / union.toDouble() else 0.0

        return maxOf(levSim, tokenSim).coerceIn(0.0, 1.0)
    }

    private fun splitArtistTokens(normalizedArtist: String): Set<String> =
        normalizedArtist
            .split(Regex("""\s*(?:/|,|、|;|&|\+|×|・|\bfeat\.?\b|\bft\.?\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost,
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    /**
     * Cross-source translation cache lookup shared across cloud, local, and imported entries.
     * Matching rules:
     * - songTitle: 100% normalized match
     * - albumTitle: 100% normalized match (imported entries without album metadata can still match)
     * - artist: >= 30% similarity (or same-songKey exact fallback for imported entries without artist tags)
     */
    internal fun findMetadataMatch(
        cacheRoot: File,
        songKey: String,
        songTitle: String,
        artist: String,
        albumTitle: String,
        targetLanguage: String,
        lines: List<LyricLine>,
        excludeCacheKey: String? = null,
    ): LlmImportedCacheMatch? {
        if (lines.isEmpty()) return null
        val candidates = listEntries(cacheRoot)
        if (candidates.isEmpty()) return null

        val normTitle = normalizeMatchText(songTitle)
        val normAlbum = normalizeMatchText(albumTitle)
        val normArtist = normalizeMatchText(artist)

        val matchedCandidates = candidates.mapNotNull { candidate ->
            if (excludeCacheKey != null && candidate.cacheKey == excludeCacheKey) return@mapNotNull null
            if (!candidate.imported &&
                targetLanguage.isNotBlank() &&
                candidate.targetLanguage.isNotBlank() &&
                candidate.targetLanguage != targetLanguage
            ) {
                return@mapNotNull null
            }

            val songKeyExact = songKey.isNotBlank() && candidate.songKey == songKey
            val candTitle = normalizeMatchText(candidate.songTitle)
            val candAlbum = normalizeMatchText(candidate.albumTitle)
            val candArtist = normalizeMatchText(candidate.artist)

            val titleMatchesExact = normTitle.isNotEmpty() && candTitle == normTitle
            if (!titleMatchesExact && !songKeyExact) return@mapNotNull null

            val albumExactMatch = candAlbum == normAlbum
            val albumAllowed = albumExactMatch || (candidate.imported && candAlbum.isEmpty())
            if (!albumAllowed) return@mapNotNull null

            val artistSim = artistSimilarity(candArtist, normArtist)
            val artistAllowed = artistSim >= ARTIST_SIMILARITY_THRESHOLD ||
                (candidate.imported && (candArtist.isEmpty() || normArtist.isEmpty()) && titleMatchesExact) ||
                (songKeyExact && titleMatchesExact && albumExactMatch)
            if (!artistAllowed) return@mapNotNull null

            candidate to Triple(albumExactMatch, artistSim, candidate.updatedAtMs)
        }.sortedWith(
            compareByDescending<Pair<LlmTranslationCacheEntry, Triple<Boolean, Double, Long>>> { it.second.third }
                .thenByDescending { it.second.first }
                .thenByDescending { it.second.second },
        )

        for ((candidate, _) in matchedCandidates) {
            val aligned = alignCachedLines(candidate.lines, lines) ?: continue
            return LlmImportedCacheMatch(candidate, aligned)
        }
        return null
    }

    private fun alignCachedLines(cached: List<LlmCachedLine>, target: List<LyricLine>): List<String?>? {
        if (cached.isEmpty() || target.isEmpty()) return null
        if (cached.size == target.size) {
            val sameTextOrTiming = target.indices.count { i ->
                val a = normalizeMatchText(cached[i].text)
                val b = normalizeMatchText(target[i].text)
                (a.isNotEmpty() && a == b) || abs(cached[i].startMs - target[i].start) <= 300
            }
            if (sameTextOrTiming >= (target.size + 1) / 2 || target.all { it.text.isBlank() }) {
                val direct = target.indices.map { i -> cached[i].translation?.takeIf { it.isNotBlank() } }
                if (direct.any { it != null }) return direct
            }
        }

        val usedIndices = HashSet<Int>()
        val aligned = MutableList<String?>(target.size) { null }
        for (i in target.indices) {
            val tLine = target[i]
            val tNorm = normalizeMatchText(tLine.text)
            var matchedIdx = -1
            if (tNorm.isNotEmpty()) {
                var bestDelta = Int.MAX_VALUE
                for (j in cached.indices) {
                    if (j in usedIndices) continue
                    if (normalizeMatchText(cached[j].text) == tNorm) {
                        val delta = abs(cached[j].startMs - tLine.start)
                        if (delta < bestDelta) {
                            bestDelta = delta
                            matchedIdx = j
                        }
                    }
                }
            }
            if (matchedIdx < 0 && tLine.start > 0) {
                var bestDelta = 301
                for (j in cached.indices) {
                    if (j in usedIndices) continue
                    val delta = abs(cached[j].startMs - tLine.start)
                    if (delta < bestDelta) {
                        bestDelta = delta
                        matchedIdx = j
                    }
                }
            }
            if (matchedIdx >= 0) {
                usedIndices += matchedIdx
                aligned[i] = cached[matchedIdx].translation?.takeIf { it.isNotBlank() }
            }
        }
        return aligned.takeIf { list -> list.any { it != null } }
    }

    private fun normalizeMatchText(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace(Regex("[\\s\\u3000]+"), " ")

    private fun writeLocked(dir: File, entry: LlmTranslationCacheEntry): Boolean {
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileNameForKey(entry.cacheKey))
        val tmp = File(dir, file.name + ".tmp")
        return runCatching {
            val payload = encodeEntry(entry)
            tmp.writeText(payload, Charsets.UTF_8)
            if (file.exists()) file.delete()
            check(tmp.renameTo(file) || run {
                file.writeText(payload, Charsets.UTF_8)
                tmp.delete()
                true
            })
            true
        }.getOrElse {
            runCatching { tmp.delete() }
            false
        }
    }

    fun listEntries(cacheRoot: File): List<LlmTranslationCacheEntry> = synchronized(fileLock) {
        val dir = cacheDirectory(cacheRoot)
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(FILE_EXT) } ?: return emptyList()
        files.mapNotNull(::decodeFile).sortedByDescending { it.updatedAtMs }
    }

    fun totalSizeBytes(cacheRoot: File): Long = synchronized(fileLock) {
        val dir = cacheDirectory(cacheRoot)
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(FILE_EXT) } ?: return 0L
        files.sumOf { it.length().coerceAtLeast(0L) }
    }

    fun deleteEntry(cacheRoot: File, cacheKey: String): Boolean = synchronized(fileLock) {
        val file = File(cacheDirectory(cacheRoot), fileNameForKey(cacheKey))
        if (file.exists()) file.delete() else false
    }

    fun deleteEntries(cacheRoot: File, cacheKeys: Set<String>): Int = synchronized(fileLock) {
        if (cacheKeys.isEmpty()) return 0
        val dir = cacheDirectory(cacheRoot)
        var deleted = 0
        for (cacheKey in cacheKeys) {
            val file = File(dir, fileNameForKey(cacheKey))
            if (file.exists() && file.delete()) deleted++
        }
        deleted
    }

    fun clearAll(cacheRoot: File): Boolean = synchronized(fileLock) {
        val dir = cacheDirectory(cacheRoot)
        if (!dir.exists()) return true
        val files = dir.listFiles() ?: return true
        var ok = true
        for (file in files) {
            if (!file.delete()) ok = false
        }
        ok
    }

    private fun encodeEntry(entry: LlmTranslationCacheEntry): String {
        val array = JSONArray()
        for (line in entry.lines) {
            array.put(
                JSONObject()
                    .put("s", line.startMs)
                    .put("o", line.text)
                    .put("t", line.translation ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("cacheKey", entry.cacheKey)
            .put("songKey", entry.songKey)
            .put("songTitle", entry.songTitle)
            .put("artist", entry.artist)
            .put("albumTitle", entry.albumTitle)
            .put("modelName", entry.modelName)
            .put("provider", entry.provider.name)
            .put("sourceLanguage", entry.sourceLanguage)
            .put("targetLanguage", entry.targetLanguage)
            .put("customStylePrompt", entry.customStylePrompt)
            .put("updatedAtMs", entry.updatedAtMs)
            .put("imported", entry.imported)
            .put("lines", array)
            .toString()
    }

    private fun decodeFile(file: File): LlmTranslationCacheEntry? = runCatching {
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val array = json.optJSONArray("lines") ?: return null
        val lines = ArrayList<LlmCachedLine>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val translated = if (item.isNull("t")) null else item.optString("t").takeIf { it.isNotBlank() }
            lines.add(
                LlmCachedLine(
                    startMs = item.optInt("s", 0),
                    text = item.optString("o"),
                    translation = translated,
                ),
            )
        }
        if (lines.isEmpty()) return null
        val provider = LlmApiProvider.entries.firstOrNull { it.name == json.optString("provider") }
            ?: LlmApiProvider.OPENAI_COMPATIBLE
        LlmTranslationCacheEntry(
            cacheKey = json.optString("cacheKey"),
            songKey = json.optString("songKey"),
            songTitle = json.optString("songTitle"),
            artist = json.optString("artist"),
            albumTitle = json.optString("albumTitle"),
            modelName = json.optString("modelName"),
            provider = provider,
            sourceLanguage = json.optString("sourceLanguage"),
            targetLanguage = json.optString("targetLanguage"),
            customStylePrompt = json.optString("customStylePrompt"),
            lines = lines,
            updatedAtMs = json.optLong("updatedAtMs", file.lastModified()),
            sizeBytes = file.length().coerceAtLeast(0L),
            imported = json.optBoolean("imported", false),
        )
    }.getOrNull()
}
