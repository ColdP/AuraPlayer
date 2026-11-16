// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.spatialcover

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class SpatialModelResource(
    val id: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val subDir: String,
    val isArchive: Boolean = false,
    val archiveEntryName: String? = null,
    val extractedSizeBytes: Long = 0L,
    val extractedSha256: String? = null,
    val archiveRequiredFiles: Set<String> = emptySet(),
)

/**
 * Optional downloadable ONNX Runtime 1.31.0 native library pack (arm64-v8a only) so the APK
 * does not bundle ~134 MB of ONNX Runtime native binaries.
 */
enum class SpatialRuntimeAbi(
    val abi: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    ARM64_V8A(
        abi = "arm64-v8a",
        fileName = "onnxruntime-1.31.0-android-arm64-v8a.zip",
        url = "https://coldp.github.io/hyperchanger-updateinfo/runtime/onnxruntime-1.31.0-android-arm64-v8a.zip",
        sizeBytes = 12_899_192L,
        sha256 = "8def0e4d5542b8d096dff72f422f44bee86fdde04781c6cdfdc23d75c92ced5b",
    ),
    ;

    val resource: SpatialModelResource
        get() = SpatialModelResource(
            id = SpatialModelStore.RUNTIME_RESOURCE_ID,
            fileName = fileName,
            url = url,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            subDir = "${SpatialModelStore.RUNTIME_SUBDIR}/$abi",
            isArchive = true,
        )

    companion object {
        fun forSupportedAbis(supportedAbis: Array<String> = Build.SUPPORTED_ABIS): SpatialRuntimeAbi? {
            for (candidate in supportedAbis) {
                entries.firstOrNull { it.abi == candidate }?.let { return it }
            }
            return null
        }
    }
}

/**
 * Exact depth model catalog from HyperChanger-Depth-Wallpaper.
 */
enum class SpatialDepthModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val inputSize: Int,
    val mean: FloatArray,
    val std: FloatArray,
    val bgr: Boolean,
    val invertDepth: Boolean,
    val rawUint8Input: Boolean,
) {
    DEPTH_ANYTHING_V2_SMALL(
        id = "depth_anything_v2_small",
        displayName = "Depth Anything V2 Small",
        fileName = "depth-anything-v2-small.onnx",
        url = "https://coldp.github.io/hyperchanger-updateinfo/models/depth-anything-v2-small.onnx",
        sizeBytes = 27_258_801L,
        sha256 = "fcf51f1b230362b28690bb9d1809bf0431f29cad20534e3f589bd7285547f20d",
        inputSize = 518,
        mean = floatArrayOf(.485f, .456f, .406f),
        std = floatArrayOf(.229f, .224f, .225f),
        bgr = false,
        invertDepth = false,
        rawUint8Input = false,
    ),
    MIDAS_MOBILE(
        id = "midas_mobile",
        displayName = "MiDaS Mobile",
        fileName = "midas-v2.1-small.onnx",
        url = "https://coldp.github.io/hyperchanger-updateinfo/models/midas-v2.1-small.onnx",
        sizeBytes = 66_389_153L,
        sha256 = "b0a5b3f12625137e626805167907fe0410665bec671685d59daaa2daab19f977",
        inputSize = 256,
        mean = floatArrayOf(.485f, .456f, .406f),
        std = floatArrayOf(.229f, .224f, .225f),
        bgr = true,
        invertDepth = false,
        rawUint8Input = false,
    ),
    DEPTH_ANYTHING_V3_BASE(
        id = "depth_anything_v3_base",
        displayName = "Depth Anything 3 Base",
        fileName = "da3-base-int8.onnx",
        url = "https://coldp.github.io/hyperchanger-updateinfo/models/da3-base-int8.onnx",
        sizeBytes = 103_943_002L,
        sha256 = "53cb20d8d0d1194c18de18259deffc46398b6ae7cd3f183e46d84af94b34007d",
        inputSize = 518,
        mean = floatArrayOf(.485f, .456f, .406f),
        std = floatArrayOf(.229f, .224f, .225f),
        bgr = false,
        invertDepth = true,
        rawUint8Input = true,
    ),
    ;

    val resource: SpatialModelResource
        get() = SpatialModelResource(
            id = id,
            fileName = fileName,
            url = url,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            subDir = "depth_models",
        )

    companion object {
        fun byId(id: String?): SpatialDepthModel = entries.firstOrNull { it.id == id } ?: DEPTH_ANYTHING_V2_SMALL
    }
}

/**
 * Exact matting model catalog from HyperChanger-Depth-Wallpaper, plus an optional depth-only mode.
 */
enum class SpatialMattingModel(
    val id: String,
    val displayName: String,
    val resource: SpatialModelResource?,
    val inputSize: Int,
    val outputLogits: Boolean = false,
) {
    U2NETP(
        id = "matting_u2netp",
        displayName = "U2Netp（轻量）",
        resource = SpatialModelResource(
            id = "matting_u2netp",
            fileName = "u2netp.onnx",
            url = "https://coldp.github.io/hyperchanger-updateinfo/models/u2netp.onnx",
            sizeBytes = 4_574_861L,
            sha256 = "309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8",
            subDir = "depth_resources",
        ),
        inputSize = 320,
    ),
    ISNET_INT8(
        id = "matting_isnet_int8",
        displayName = "ISNet General INT8（均衡）",
        resource = SpatialModelResource(
            id = "matting_isnet_int8",
            fileName = "isnet-general-int8.onnx",
            url = "https://coldp.github.io/hyperchanger-updateinfo/models/isnet-general-int8.onnx",
            sizeBytes = 44_229_662L,
            sha256 = "3b21a6706dc8d6e4ba9f5b31ebc6940f6c785b58862e27bb25daa9dd4424b87f",
            subDir = "depth_resources",
        ),
        inputSize = 1024,
    ),
    BIREFNET_LITE_INT8(
        id = "matting_birefnet_lite_int8",
        displayName = "BiRefNet Lite INT8（精细，较慢）",
        resource = SpatialModelResource(
            id = "matting_birefnet_lite_int8",
            fileName = "birefnet-lite-int8.onnx",
            url = "https://coldp.github.io/hyperchanger-updateinfo/models/birefnet-lite-int8.onnx",
            sizeBytes = 90_764_431L,
            sha256 = "2cffd9a8441691513a3d1d0990b5b154796f2351ba5ecb22f8ff802ac9dba1d1",
            subDir = "depth_resources",
        ),
        inputSize = 1024,
        outputLogits = true,
    ),
    NONE(
        id = "matting_none",
        displayName = "关闭抠图（仅深度视差）",
        resource = null,
        inputSize = 0,
    ),
    ;

    companion object {
        val downloadableEntries: List<SpatialMattingModel>
            get() = entries.filter { it.resource != null }

        fun byId(id: String?): SpatialMattingModel = entries.firstOrNull { it.id == id } ?: U2NETP
    }
}

object SpatialModelStore {
    private const val BUFFER_SIZE = 256 * 1024
    const val RUNTIME_RESOURCE_ID = "onnxruntime_runtime"
    const val RUNTIME_SUBDIR = "onnxruntime_runtime"
    const val RUNTIME_CORE_SO = "libonnxruntime.so"
    const val RUNTIME_JNI_SO = "libonnxruntime4j_jni.so"
    private val RUNTIME_REQUIRED_LIBS = setOf(RUNTIME_CORE_SO, RUNTIME_JNI_SO)

    fun runtimeAbi(): SpatialRuntimeAbi? = SpatialRuntimeAbi.forSupportedAbis()

    fun runtimeResource(): SpatialModelResource? = runtimeAbi()?.resource

    fun runtimeDirectory(context: Context): File? {
        val res = runtimeResource() ?: return null
        return directory(context, res.subDir)
    }

    fun isRuntimeReady(context: Context): Boolean {
        val res = runtimeResource() ?: return false
        return isReady(context, res)
    }

    fun directory(context: Context, subDir: String): File =
        File(context.filesDir, subDir).apply { if (!isDirectory) mkdirs() }

    fun file(context: Context, resource: SpatialModelResource): File =
        File(directory(context, resource.subDir), resource.fileName)

    private fun markerFile(context: Context, resource: SpatialModelResource): File =
        File(directory(context, resource.subDir), "${resource.fileName}.verified")

    fun partialFile(context: Context, resource: SpatialModelResource): File =
        File(file(context, resource).path + ".part")

    fun isReady(context: Context, resource: SpatialModelResource): Boolean {
        val marker = markerFile(context, resource)
        if (!marker.isFile) return false
        val markerSha = runCatching { marker.readText().trim() }.getOrNull()
        if (!markerSha.equals(resource.sha256, ignoreCase = true)) return false
        if (resource.isArchive && resource.archiveEntryName == null) {
            val dir = directory(context, resource.subDir)
            val required = resource.archiveRequiredFiles.ifEmpty { RUNTIME_REQUIRED_LIBS }
            return required.all { name ->
                val f = File(dir, name)
                f.isFile && f.length() > 0L
            }
        }
        val target = file(context, resource)
        val expectedBytes = if (resource.extractedSizeBytes > 0L) resource.extractedSizeBytes else resource.sizeBytes
        return target.isFile && target.length() == expectedBytes
    }

    fun downloadedBytes(context: Context, resource: SpatialModelResource): Long {
        if (isReady(context, resource)) return resource.sizeBytes
        val partial = partialFile(context, resource)
        return if (partial.isFile) partial.length().coerceAtMost(resource.sizeBytes) else 0L
    }

    fun delete(context: Context, resource: SpatialModelResource) {
        file(context, resource).delete()
        partialFile(context, resource).delete()
        markerFile(context, resource).delete()
        if (resource.isArchive && resource.archiveEntryName == null) {
            val dir = directory(context, resource.subDir)
            val required = resource.archiveRequiredFiles.ifEmpty { RUNTIME_REQUIRED_LIBS }
            for (name in required) {
                File(dir, name).delete()
            }
        }
    }

    fun readySelectionSummary(context: Context, depthModel: SpatialDepthModel, mattingModel: SpatialMattingModel): Boolean {
        if (!isRuntimeReady(context)) return false
        if (!isReady(context, depthModel.resource)) return false
        val mattingResource = mattingModel.resource ?: return true
        return isReady(context, mattingResource)
    }

    fun allDownloadableResources(): List<SpatialModelResource> =
        listOfNotNull(runtimeResource()) +
            listOfNotNull(btm.m.auraplayer.sing.AuraSingQualcommNpu.runtimeResource()) +
            SpatialDepthModel.entries.map { it.resource } +
            SpatialMattingModel.downloadableEntries.mapNotNull { it.resource } +
            btm.m.auraplayer.sing.AuraSingModel.entries.map { it.resource }

    suspend fun download(
        context: Context,
        resource: SpatialModelResource,
        onProgress: (downloaded: Long, total: Long, bytesPerSecond: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = file(context, resource)
        val partial = partialFile(context, resource)
        var startOffset = if (partial.isFile) partial.length() else 0L
        if (startOffset >= resource.sizeBytes) {
            partial.delete()
            startOffset = 0L
        }
        val connection = (URL(resource.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "HyperChanger-Android")
            if (startOffset > 0L) setRequestProperty("Range", "bytes=$startOffset-")
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val append = code == 206 && startOffset > 0L
            var written = if (append) startOffset else 0L
            var windowStart = SystemClock.elapsedRealtime()
            var windowBytes = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        windowBytes += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport >= 140L) {
                            val speed = if (now > windowStart) {
                                windowBytes * 1000L / (now - windowStart).coerceAtLeast(1L)
                            } else 0L
                            onProgress(written, resource.sizeBytes, speed)
                            windowStart = now
                            windowBytes = 0L
                            lastReport = now
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (written != resource.sizeBytes) error("文件大小不匹配")
            if (!sha256(partial).equals(resource.sha256, ignoreCase = true)) {
                partial.delete()
                error("文件校验失败")
            }
            if (resource.isArchive && resource.archiveEntryName != null) {
                extractSingleFileArchive(
                    zipFile = partial,
                    entryName = resource.archiveEntryName,
                    targetFile = target,
                    expectedSize = resource.extractedSizeBytes,
                    expectedSha256 = resource.extractedSha256,
                )
                partial.delete()
                markerFile(context, resource).writeText(resource.sha256)
                onProgress(resource.sizeBytes, resource.sizeBytes, 0L)
                target
            } else if (resource.isArchive) {
                val dir = directory(context, resource.subDir)
                val required = resource.archiveRequiredFiles.ifEmpty { RUNTIME_REQUIRED_LIBS }
                extractRuntimeArchive(partial, dir, required)
                partial.delete()
                markerFile(context, resource).writeText(resource.sha256)
                onProgress(resource.sizeBytes, resource.sizeBytes, 0L)
                dir
            } else {
                target.delete()
                if (!partial.renameTo(target)) error("无法保存模型文件")
                markerFile(context, resource).writeText(resource.sha256)
                onProgress(resource.sizeBytes, resource.sizeBytes, 0L)
                target
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun extractRuntimeArchive(
        zipFile: File,
        targetDir: File,
        requiredFiles: Set<String> = RUNTIME_REQUIRED_LIBS,
    ) {
        if (!targetDir.isDirectory) targetDir.mkdirs()
        val extracted = mutableSetOf<String>()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                try {
                    if (entry.isDirectory) continue
                    val rawName = entry.name.replace('\\', '/').substringAfterLast('/')
                    if (rawName !in requiredFiles || entry.name.contains("..")) continue
                    val outFile = File(targetDir, rawName)
                    val tmpFile = File(targetDir, "$rawName.tmp")
                    tmpFile.delete()
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zis.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                    outFile.delete()
                    check(tmpFile.renameTo(outFile)) { "无法保存运行库文件: $rawName" }
                    outFile.setReadOnly()
                    extracted += rawName
                } finally {
                    zis.closeEntry()
                }
            }
        }
        check(extracted.containsAll(requiredFiles)) {
            "运行库压缩包内容不完整"
        }
    }

    internal fun extractSingleFileArchive(
        zipFile: File,
        entryName: String,
        targetFile: File,
        expectedSize: Long,
        expectedSha256: String?,
    ) {
        targetFile.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        val tmpFile = File(targetFile.parentFile, "${targetFile.name}.extracting")
        tmpFile.delete()
        var found = false
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                try {
                    if (entry.isDirectory) continue
                    val rawName = entry.name.replace('\\', '/').substringAfterLast('/')
                    if (rawName != entryName || entry.name.contains("..")) continue
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zis.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                    found = true
                    break
                } finally {
                    zis.closeEntry()
                }
            }
        }
        check(found) { "压缩包内缺少模型文件: $entryName" }
        if (expectedSize > 0L && tmpFile.length() != expectedSize) {
            tmpFile.delete()
            error("解压后模型文件大小不匹配")
        }
        if (!expectedSha256.isNullOrBlank() && !sha256(tmpFile).equals(expectedSha256, ignoreCase = true)) {
            tmpFile.delete()
            error("解压后模型校验失败")
        }
        targetFile.delete()
        if (!tmpFile.renameTo(targetFile)) {
            tmpFile.delete()
            error("无法保存解压后的模型文件")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

sealed interface SpatialModelDownloadState {
    data object Idle : SpatialModelDownloadState
    data class Downloading(val downloaded: Long, val total: Long, val speed: Long) : SpatialModelDownloadState
    data object Done : SpatialModelDownloadState
    data class Failed(val message: String) : SpatialModelDownloadState
}

object SpatialModelDownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val states = mutableStateMapOf<String, SpatialModelDownloadState>()
    private val jobs = mutableMapOf<String, Job>()

    fun syncFromDisk(context: Context) {
        val appContext = context.applicationContext
        for (resource in SpatialModelStore.allDownloadableResources()) {
            if (jobs[resource.id]?.isActive == true) continue
            states[resource.id] = if (SpatialModelStore.isReady(appContext, resource)) {
                SpatialModelDownloadState.Done
            } else {
                SpatialModelDownloadState.Idle
            }
        }
    }

    fun start(context: Context, resource: SpatialModelResource) {
        val appContext = context.applicationContext
        if (jobs[resource.id]?.isActive == true) return
        states[resource.id] = SpatialModelDownloadState.Downloading(
            SpatialModelStore.downloadedBytes(appContext, resource),
            resource.sizeBytes,
            0L,
        )
        val job = scope.launch {
            try {
                SpatialModelStore.download(appContext, resource) { downloaded, total, speed ->
                    scope.launch(Dispatchers.Main.immediate) {
                        states[resource.id] = SpatialModelDownloadState.Downloading(downloaded, total, speed)
                    }
                }
                states[resource.id] = SpatialModelDownloadState.Done
            } catch (cancelled: CancellationException) {
                states[resource.id] = SpatialModelDownloadState.Idle
                throw cancelled
            } catch (error: Throwable) {
                states[resource.id] = SpatialModelDownloadState.Failed(error.message ?: "下载失败")
            } finally {
                jobs.remove(resource.id)
            }
        }
        jobs[resource.id] = job
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
    }

    fun delete(context: Context, resource: SpatialModelResource) {
        val appContext = context.applicationContext
        cancel(resource.id)
        SpatialModelStore.delete(appContext, resource)
        states[resource.id] = SpatialModelDownloadState.Idle
    }
}
