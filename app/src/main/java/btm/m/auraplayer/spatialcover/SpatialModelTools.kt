// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.spatialcover

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object SpatialDomesticMirror {
    const val QUARK_URL_INDEX =
        "https://raw.giteeusercontent.com/btm_m/HyperChanger/raw/main/models_and_runtime_quark_dl_url.txt"
    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private val URL_REGEX = Regex("""https?://[^\s<>"'`]+""")
    private val TRAILING_PUNCTUATION = charArrayOf(',', '.', ';', ':', ')', ']', '}', '，', '。', '；', '：', '）', '】', '！', '？')

    suspend fun fetchDownloadUrl(): String = withContext(Dispatchers.IO) {
        val connection = (URL(QUARK_URL_INDEX).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "HyperChanger-Android")
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val body = connection.inputStream.use { input ->
                val buf = ByteArray(MAX_RESPONSE_BYTES)
                var offset = 0
                while (offset < buf.size) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buf, offset, buf.size - offset)
                    if (read <= 0) break
                    offset += read
                }
                String(buf, 0, offset, Charsets.UTF_8)
            }
            extractFirstHttpUrl(body) ?: error("No valid download URL found in remote index")
        } finally {
            connection.disconnect()
        }
    }

    internal fun extractFirstHttpUrl(rawText: String): String? {
        for (match in URL_REGEX.findAll(rawText)) {
            val cleaned = match.value.trimEnd(*TRAILING_PUNCTUATION)
            if (cleaned.startsWith("https://", ignoreCase = true) ||
                cleaned.startsWith("http://", ignoreCase = true)
            ) {
                return cleaned
            }
        }
        return null
    }
}

enum class ImportFailureReason {
    CANNOT_OPEN,
    EMPTY_FILE,
    NOT_A_KNOWN_MODEL,
    ARCHIVE_CORRUPTED_OR_INCOMPLETE,
    WRITE_FAILED,
}

sealed interface SpatialModelImportResult {
    data class Success(val resource: SpatialModelResource) : SpatialModelImportResult
    data class Failure(
        val reason: ImportFailureReason,
        val fileName: String,
        val fileSize: Long,
        val candidateResource: SpatialModelResource? = null,
        val detail: String = "",
    ) : SpatialModelImportResult
}

object SpatialModelImporter {
    private const val BUFFER_SIZE = 256 * 1024

    private data class CopiedSource(
        val tempFile: File,
        val sizeBytes: Long,
        val sha256: String,
        val isZipMagic: Boolean,
    )

    private data class ZipMemberFeature(
        val rawName: String,
        val sizeBytes: Long,
        val sha256: String,
        val isElfMagic: Boolean,
    )

    suspend fun importFromUri(context: Context, uri: Uri): SpatialModelImportResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val displayName = queryDisplayName(appContext, uri)
        val tempFile = File(appContext.cacheDir, "spatial_import_${System.nanoTime()}.tmp")
        try {
            val copied = copyAndInspect(appContext, uri, tempFile)
                ?: return@withContext SpatialModelImportResult.Failure(
                    reason = ImportFailureReason.CANNOT_OPEN,
                    fileName = displayName,
                    fileSize = 0L,
                )

            if (copied.sizeBytes <= 0L) {
                return@withContext SpatialModelImportResult.Failure(
                    reason = ImportFailureReason.EMPTY_FILE,
                    fileName = displayName,
                    fileSize = 0L,
                )
            }

            val allResources = SpatialModelStore.allDownloadableResources()

            // 1. Exact outer SHA-256 + size match (works regardless of file renaming)
            allResources.firstOrNull { res ->
                copied.sizeBytes == res.sizeBytes && copied.sha256.equals(res.sha256, ignoreCase = true)
            }?.let { matched ->
                return@withContext installMatched(appContext, copied.tempFile, matched, matchedAsExtracted = false, displayName, copied.sizeBytes)
            }

            // 2. Exact match against extracted single-file model (e.g., user pre-extracted htdemucs FP16 .onnx)
            allResources.firstOrNull { res ->
                res.extractedSizeBytes > 0L &&
                    !res.extractedSha256.isNullOrBlank() &&
                    copied.sizeBytes == res.extractedSizeBytes &&
                    copied.sha256.equals(res.extractedSha256, ignoreCase = true)
            }?.let { matched ->
                return@withContext installMatched(appContext, copied.tempFile, matched, matchedAsExtracted = true, displayName, copied.sizeBytes)
            }

            // 3. ZIP internal feature matching (handles repacked model/runtime archives)
            if (copied.isZipMagic) {
                val zipResult = inspectZipMembers(copied.tempFile)
                if (zipResult == null) {
                    return@withContext SpatialModelImportResult.Failure(
                        reason = ImportFailureReason.ARCHIVE_CORRUPTED_OR_INCOMPLETE,
                        fileName = displayName,
                        fileSize = copied.sizeBytes,
                    )
                }

                // 3a. Single model stored inside a ZIP archive (match by inner member size + SHA-256)
                for (member in zipResult) {
                    val directModelMatch = allResources.firstOrNull { res ->
                        !res.isArchive &&
                            member.sizeBytes == res.sizeBytes &&
                            member.sha256.equals(res.sha256, ignoreCase = true)
                    }
                    if (directModelMatch != null) {
                        return@withContext installSingleEntryFromZip(
                            appContext = appContext,
                            zipFile = copied.tempFile,
                            entryName = member.rawName,
                            resource = directModelMatch,
                            displayName = displayName,
                            sourceSize = copied.sizeBytes,
                        )
                    }
                    val archiveSingleModelMatch = allResources.firstOrNull { res ->
                        res.isArchive &&
                            res.archiveEntryName != null &&
                            res.extractedSizeBytes > 0L &&
                            !res.extractedSha256.isNullOrBlank() &&
                            member.sizeBytes == res.extractedSizeBytes &&
                            member.sha256.equals(res.extractedSha256, ignoreCase = true)
                    }
                    if (archiveSingleModelMatch != null) {
                        return@withContext installSingleEntryFromZip(
                            appContext = appContext,
                            zipFile = copied.tempFile,
                            entryName = member.rawName,
                            resource = archiveSingleModelMatch,
                            displayName = displayName,
                            sourceSize = copied.sizeBytes,
                        )
                    }
                }

                // 3b. Repacked native runtime ZIP (match required .so set + valid ELF magic, largest set first)
                val elfMembersByName = zipResult
                    .filter { it.isElfMagic && it.sizeBytes > 0L }
                    .associateBy { it.rawName }

                val runtimeCandidates = allResources
                    .filter { it.isArchive && it.archiveEntryName == null }
                    .sortedByDescending { it.archiveRequiredFiles.ifEmpty { SpatialModelStore.RUNTIME_REQUIRED_LIBS }.size }

                for (runtimeRes in runtimeCandidates) {
                    val required = runtimeRes.archiveRequiredFiles.ifEmpty { SpatialModelStore.RUNTIME_REQUIRED_LIBS }
                    if (required.all { it in elfMembersByName }) {
                        return@withContext installMatched(
                            appContext = appContext,
                            sourceFile = copied.tempFile,
                            resource = runtimeRes,
                            matchedAsExtracted = false,
                            displayName = displayName,
                            sourceSize = copied.sizeBytes,
                        )
                    }
                }
            }

            // No existing model/runtime matched: find closest hint for diagnostic failure dialog
            val candidateHint = findSimilarCandidate(displayName, copied.sizeBytes, allResources)
            SpatialModelImportResult.Failure(
                reason = ImportFailureReason.NOT_A_KNOWN_MODEL,
                fileName = displayName,
                fileSize = copied.sizeBytes,
                candidateResource = candidateHint,
            )
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun installMatched(
        appContext: Context,
        sourceFile: File,
        resource: SpatialModelResource,
        matchedAsExtracted: Boolean,
        displayName: String,
        sourceSize: Long,
    ): SpatialModelImportResult {
        SpatialModelDownloadManager.cancelAndJoin(resource.id)
        return runCatching {
            SpatialModelStore.partialFile(appContext, resource).delete()
            if (resource.isArchive && resource.archiveEntryName != null && !matchedAsExtracted) {
                val target = SpatialModelStore.file(appContext, resource)
                SpatialModelStore.extractSingleFileArchive(
                    zipFile = sourceFile,
                    entryName = resource.archiveEntryName,
                    targetFile = target,
                    expectedSize = resource.extractedSizeBytes,
                    expectedSha256 = resource.extractedSha256,
                )
            } else if (resource.isArchive && !matchedAsExtracted) {
                val dir = SpatialModelStore.directory(appContext, resource.subDir)
                val required = resource.archiveRequiredFiles.ifEmpty { SpatialModelStore.RUNTIME_REQUIRED_LIBS }
                SpatialModelStore.extractRuntimeArchive(sourceFile, dir, required)
            } else {
                val target = SpatialModelStore.file(appContext, resource)
                copyAtomically(sourceFile, target)
            }
            SpatialModelStore.markerFile(appContext, resource).writeText(resource.sha256)
            withContext(Dispatchers.Main.immediate) {
                SpatialModelDownloadManager.markReady(appContext, resource)
            }
            SpatialModelImportResult.Success(resource)
        }.getOrElse { err ->
            SpatialModelImportResult.Failure(
                reason = if (resource.isArchive && !matchedAsExtracted) {
                    ImportFailureReason.ARCHIVE_CORRUPTED_OR_INCOMPLETE
                } else {
                    ImportFailureReason.WRITE_FAILED
                },
                fileName = displayName,
                fileSize = sourceSize,
                candidateResource = resource,
                detail = err.message.orEmpty(),
            )
        }
    }

    private suspend fun installSingleEntryFromZip(
        appContext: Context,
        zipFile: File,
        entryName: String,
        resource: SpatialModelResource,
        displayName: String,
        sourceSize: Long,
    ): SpatialModelImportResult {
        SpatialModelDownloadManager.cancelAndJoin(resource.id)
        return runCatching {
            SpatialModelStore.partialFile(appContext, resource).delete()
            val target = SpatialModelStore.file(appContext, resource)
            val expectedSize = if (resource.extractedSizeBytes > 0L) resource.extractedSizeBytes else resource.sizeBytes
            val expectedSha = resource.extractedSha256?.takeIf { it.isNotBlank() } ?: resource.sha256
            SpatialModelStore.extractSingleFileArchive(
                zipFile = zipFile,
                entryName = entryName,
                targetFile = target,
                expectedSize = expectedSize,
                expectedSha256 = expectedSha,
            )
            SpatialModelStore.markerFile(appContext, resource).writeText(resource.sha256)
            withContext(Dispatchers.Main.immediate) {
                SpatialModelDownloadManager.markReady(appContext, resource)
            }
            SpatialModelImportResult.Success(resource)
        }.getOrElse { err ->
            SpatialModelImportResult.Failure(
                reason = ImportFailureReason.ARCHIVE_CORRUPTED_OR_INCOMPLETE,
                fileName = displayName,
                fileSize = sourceSize,
                candidateResource = resource,
                detail = err.message.orEmpty(),
            )
        }
    }

    private fun copyAtomically(sourceFile: File, targetFile: File) {
        targetFile.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        val staging = File(targetFile.parentFile, "${targetFile.name}.importing")
        staging.delete()
        try {
            sourceFile.inputStream().use { input ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            targetFile.delete()
            check(staging.renameTo(targetFile)) { "无法保存导入的模型文件" }
        } catch (t: Throwable) {
            staging.delete()
            throw t
        }
    }

    private suspend fun copyAndInspect(context: Context, uri: Uri, dest: File): CopiedSource? {
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(4)
        var headerCount = 0
        var totalBytes = 0L
        stream.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (headerCount < 4) {
                        val copyLen = (4 - headerCount).coerceAtMost(read)
                        System.arraycopy(buffer, 0, header, headerCount, copyLen)
                        headerCount += copyLen
                    }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    totalBytes += read
                }
                output.flush()
                output.fd.sync()
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val isZip = headerCount >= 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() &&
            header[3] == 0x04.toByte()
        return CopiedSource(dest, totalBytes, sha, isZip)
    }

    private suspend fun inspectZipMembers(zipFile: File): List<ZipMemberFeature>? = runCatching {
        val members = mutableListOf<ZipMemberFeature>()
        val buffer = ByteArray(64 * 1024)
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zis.nextEntry ?: break
                try {
                    if (entry.isDirectory || entry.name.contains("..")) continue
                    val rawName = entry.name.replace('\\', '/').substringAfterLast('/')
                    if (rawName.isBlank()) continue
                    val digest = MessageDigest.getInstance("SHA-256")
                    val magic = ByteArray(4)
                    var magicCount = 0
                    var entryBytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = zis.read(buffer)
                        if (read < 0) break
                        if (magicCount < 4) {
                            val copyLen = (4 - magicCount).coerceAtMost(read)
                            System.arraycopy(buffer, 0, magic, magicCount, copyLen)
                            magicCount += copyLen
                        }
                        digest.update(buffer, 0, read)
                        entryBytes += read
                    }
                    val isElf = magicCount >= 4 &&
                        magic[0] == 0x7F.toByte() &&
                        magic[1] == 0x45.toByte() &&
                        magic[2] == 0x4C.toByte() &&
                        magic[3] == 0x46.toByte()
                    members += ZipMemberFeature(
                        rawName = rawName,
                        sizeBytes = entryBytes,
                        sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                        isElfMagic = isElf,
                    )
                } finally {
                    zis.closeEntry()
                }
            }
        }
        members
    }.getOrNull()

    private fun findSimilarCandidate(
        displayName: String,
        sizeBytes: Long,
        allResources: List<SpatialModelResource>,
    ): SpatialModelResource? {
        allResources.firstOrNull {
            it.sizeBytes == sizeBytes || (it.extractedSizeBytes > 0L && it.extractedSizeBytes == sizeBytes)
        }?.let { return it }

        val normalizedName = displayName.lowercase()
            .replace(Regex("""\s*\(\d+\)(?=\.[^.]+$)"""), "")
            .trim()
        if (normalizedName.isBlank()) return null

        return allResources.firstOrNull { res ->
            val targetBase = res.fileName.substringBeforeLast('.').lowercase()
            val expectedName = res.fileName.lowercase()
            normalizedName == expectedName ||
                normalizedName.contains(targetBase) ||
                (res.archiveEntryName != null && normalizedName.contains(res.archiveEntryName.substringBeforeLast('.').lowercase()))
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "unknown"
    }
}
