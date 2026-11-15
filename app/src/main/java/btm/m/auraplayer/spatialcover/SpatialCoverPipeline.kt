// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.spatialcover

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.graphics.scale
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class SpatialCoverAssets(
    val source: Bitmap,
    val depth: Bitmap,
    val mask: Bitmap?,
    val background: Bitmap?,
    val backgroundDepth: Bitmap?,
    val subjectRelief: Bitmap?,
    val subjectDepth: Float,
    val depthModelId: String,
    val mattingModelId: String,
) {
    val hasLayers: Boolean
        get() = mask != null && background != null && backgroundDepth != null && subjectRelief != null
}

sealed interface SpatialCoverResolveState {
    data object Idle : SpatialCoverResolveState
    data object MissingModels : SpatialCoverResolveState
    data class Generating(val progress: Float) : SpatialCoverResolveState
    data class Ready(val assets: SpatialCoverAssets) : SpatialCoverResolveState
    data class Failed(val message: String) : SpatialCoverResolveState
}

data class SpatialCoverCacheEntry(
    val key: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val albumArtist: String = "",
    val depthModelId: String,
    val mattingModelId: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val sourcePath: String,
) {
    val depthModel: SpatialDepthModel
        get() = SpatialDepthModel.byId(depthModelId)
    val mattingModel: SpatialMattingModel
        get() = SpatialMattingModel.byId(mattingModelId)
}

private class DepthRaw(val width: Int, val height: Int, val values: FloatArray)

private class SpatialDepthEstimator {
    private val lock = Any()
    private var session: OrtSession? = null
    private var loadedId: String? = null
    private var loadedModel: SpatialDepthModel? = null

    fun ensureLoaded(context: Context, model: SpatialDepthModel) {
        synchronized(lock) {
            if (loadedId == model.id && session != null) return
            val resource = model.resource
            if (!SpatialModelStore.isReady(context, resource)) error("Model is not downloaded")
            SpatialRuntimeLoader.ensureLoaded(context)
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 2))
                setInterOpNumThreads(1)
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
                addConfigEntry("session.intra_op.allow_spinning", "0")
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val created = try {
                env.createSession(SpatialModelStore.file(context, resource).absolutePath, options)
            } finally {
                options.close()
            }
            session?.close()
            session = created
            loadedId = model.id
            loadedModel = model
        }
    }

    fun run(bitmap: Bitmap): DepthRaw {
        synchronized(lock) {
            val currentSession = session ?: error("Model is not loaded")
            val model = loadedModel ?: error("Model is not loaded")
            val size = model.inputSize
            val scaled = if (bitmap.width == size && bitmap.height == size) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, size, size, true)
            }
            val plane = size * size
            val pixels = IntArray(plane)
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)
            if (scaled !== bitmap) scaled.recycle()
            val env = OrtEnvironment.getEnvironment()
            val inputName = currentSession.inputNames.first()
            val tensor = if (model.rawUint8Input) {
                val bytes = ByteArray(plane * 3)
                var cursor = 0
                for (index in 0 until plane) {
                    val color = pixels[index]
                    bytes[cursor++] = ((color shr 16) and 0xFF).toByte()
                    bytes[cursor++] = ((color shr 8) and 0xFF).toByte()
                    bytes[cursor++] = (color and 0xFF).toByte()
                }
                OnnxTensor.createTensor(
                    env,
                    ByteBuffer.wrap(bytes),
                    longArrayOf(1L, size.toLong(), size.toLong(), 3L),
                    OnnxJavaType.UINT8,
                )
            } else {
                val data = FloatArray(plane * 3)
                val mean = model.mean
                val std = model.std
                for (index in 0 until plane) {
                    val color = pixels[index]
                    val r = ((color shr 16) and 0xFF) / 255f
                    val g = ((color shr 8) and 0xFF) / 255f
                    val b = (color and 0xFF) / 255f
                    val c0 = if (model.bgr) b else r
                    val c1 = g
                    val c2 = if (model.bgr) r else b
                    data[index] = (c0 - mean[0]) / std[0]
                    data[plane + index] = (c1 - mean[1]) / std[1]
                    data[plane * 2 + index] = (c2 - mean[2]) / std[2]
                }
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(data),
                    longArrayOf(1L, 3L, size.toLong(), size.toLong()),
                )
            }
            try {
                currentSession.run(mapOf(inputName to tensor)).use { outputs ->
                    val output = outputs.get(0) as OnnxTensor
                    val shape = output.info.shape
                    val height = shape[shape.size - 2].toInt()
                    val width = shape[shape.size - 1].toInt()
                    val values = FloatArray(width * height)
                    val buffer = output.floatBuffer
                    buffer.rewind()
                    buffer.get(values)
                    val normalized = SpatialDepthGeometry.normalize(values, width, height)
                    if (model.invertDepth) {
                        for (index in normalized.indices) normalized[index] = 1f - normalized[index]
                    }
                    return DepthRaw(width, height, normalized)
                }
            } finally {
                tensor.close()
            }
        }
    }

    fun close() = synchronized(lock) {
        session?.close()
        session = null
        loadedId = null
        loadedModel = null
    }
}

private class SpatialMattingEstimator {
    private val lock = Any()
    private var session: OrtSession? = null
    private var loadedModel: SpatialMattingModel? = null

    private fun ensureLoaded(context: Context, model: SpatialMattingModel) {
        synchronized(lock) {
            val resource = model.resource ?: error("No matting model selected")
            check(SpatialModelStore.isReady(context, resource)) { "请先下载所选抠图模型" }
            if (session != null && loadedModel == model) return
            close()
            SpatialRuntimeLoader.ensureLoaded(context)
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 2))
                setInterOpNumThreads(1)
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
                addConfigEntry("session.intra_op.allow_spinning", "0")
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val created = try {
                env.createSession(SpatialModelStore.file(context, resource).absolutePath, options)
            } finally {
                options.close()
            }
            session = created
            loadedModel = model
        }
    }

    fun estimate(context: Context, source: Bitmap, model: SpatialMattingModel): Bitmap? {
        if (model == SpatialMattingModel.NONE || model.resource == null) return null
        synchronized(lock) {
            ensureLoaded(context, model)
            val currentSession = session ?: return null
            val size = model.inputSize
            val scaled = if (source.width == size && source.height == size) {
                source
            } else {
                Bitmap.createScaledBitmap(source, size, size, true)
            }
            val plane = size * size
            val pixels = IntArray(plane)
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)
            if (scaled !== source) scaled.recycle()
            val data = FloatArray(plane * 3)
            val mean = floatArrayOf(.485f, .456f, .406f)
            val std = floatArrayOf(.229f, .224f, .225f)
            for (index in 0 until plane) {
                val color = pixels[index]
                if (model == SpatialMattingModel.ISNET_INT8) {
                    data[index] = (((color shr 16) and 0xFF) - 128f) / 256f
                    data[plane + index] = (((color shr 8) and 0xFF) - 128f) / 256f
                    data[plane * 2 + index] = ((color and 0xFF) - 128f) / 256f
                } else {
                    data[index] = (((color shr 16) and 0xFF) / 255f - mean[0]) / std[0]
                    data[plane + index] = (((color shr 8) and 0xFF) / 255f - mean[1]) / std[1]
                    data[plane * 2 + index] = ((color and 0xFF) / 255f - mean[2]) / std[2]
                }
            }
            val env = OrtEnvironment.getEnvironment()
            val inputName = currentSession.inputNames.first()
            val tensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(data),
                longArrayOf(1L, 3L, size.toLong(), size.toLong()),
            )
            try {
                currentSession.run(mapOf(inputName to tensor)).use { outputs ->
                    val output = outputs.get(0) as OnnxTensor
                    val values = FloatArray(plane)
                    val buffer = output.floatBuffer
                    check(buffer.remaining() == plane) { "抠图模型输出尺寸不匹配" }
                    buffer.rewind()
                    buffer.get(values)
                    if (model.outputLogits) {
                        for (index in values.indices) values[index] = 1f / (1f + exp(-values[index]))
                    }
                    val mask = SpatialMattingEdges.probabilities(values, normalize = model == SpatialMattingModel.U2NETP)
                    val maskPixels = IntArray(mask.size)
                    for (index in maskPixels.indices) {
                        val value = (mask[index] * 255f).roundToInt().coerceIn(0, 255)
                        maskPixels[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
                    }
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(maskPixels, 0, size, 0, 0, size, size)
                    return bitmap
                }
            } finally {
                tensor.close()
            }
        }
    }

    fun close() = synchronized(lock) {
        session?.close()
        session = null
        loadedModel = null
    }
}

private class BuiltLayers(
    val mask: Bitmap,
    val background: Bitmap,
    val backgroundDepth: Bitmap,
    val subjectRelief: Bitmap,
    val subjectDepth: Float,
)

private object SpatialLayerBuilder {
    private const val COVER_LAYER_LONG_SIDE = 960

    fun build(
        source: Bitmap,
        depth: Bitmap,
        mattingMask: Bitmap?,
        preserveMattingEdges: Boolean,
        checkpoint: () -> Unit = {},
    ): BuiltLayers {
        checkpoint()
        val longSide = min(COVER_LAYER_LONG_SIDE, max(source.width, source.height))
        val aspect = source.width.toFloat() / source.height.coerceAtLeast(1)
        val width = if (aspect >= 1f) longSide else max(1, (longSide * aspect).roundToInt())
        val height = if (aspect >= 1f) max(1, (longSide / aspect).roundToInt()) else longSide
        val sourceLayer = if (source.width == width && source.height == height) source else source.scale(width, height)
        var depthLayer: Bitmap? = null
        var maskBitmap: Bitmap? = null
        var background: Bitmap? = null
        var backgroundDepth: Bitmap? = null
        var reliefBitmap: Bitmap? = null
        var delivered = false
        try {
            val scaledDepth = if (depth.width == width && depth.height == height) depth else depth.scale(width, height)
            depthLayer = scaledDepth
            val pixelCount = width * height
            val pixels = IntArray(pixelCount)
            scaledDepth.getPixels(pixels, 0, width, 0, 0, width, height)
            val depthValues = SpatialDepthGeometry.median3(
                FloatArray(pixelCount) { (pixels[it] and 255) / 255f },
                width,
                height,
            )
            val alphaMask = alphaMask(sourceLayer, width, height)
            val matting = if (alphaMask == null) {
                mattingMask?.let { scaleMatting(it, sourceLayer, width, height, preserveMattingEdges, checkpoint) }
            } else null
            val mask = alphaMask ?: matting ?: depthDerivedMask(depthValues, width, height)
            if (alphaMask == null && matting == null) softenMask(mask, width, height)
            var sum = 0f
            var count = 0
            for (i in 0 until pixelCount) {
                if ((i and 0x3FFF) == 0) checkpoint()
                if (mask[i] > .5f) {
                    sum += depthValues[i]
                    count++
                }
            }
            val subjectDepth = if (count > 0) (sum / count).coerceIn(.15f, .95f) else .65f
            val bodyMask = BooleanArray(pixelCount) { mask[it] > 1e-3f }
            val fillRegion = SpatialMattingEdges.fillHolesExcludingFarGaps(bodyMask, depthValues, width, height)
            val removalMask = SpatialMattingEdges.backgroundRemovalMask(
                mask, width, height, (max(width, height) / 240).coerceIn(4, 8), checkpoint,
            )
            for (i in 0 until pixelCount) {
                if (!fillRegion[i] && bodyMask[i]) removalMask[i] = max(removalMask[i], mask[i])
                else if (fillRegion[i] && !bodyMask[i]) removalMask[i] = max(removalMask[i], 1f)
            }
            sourceLayer.getPixels(pixels, 0, width, 0, 0, width, height)
            val filled = SpatialBackgroundInpaint.fill(pixels, depthValues, removalMask, width, height, checkpoint)
            checkpoint()
            background = Bitmap.createBitmap(filled.pixels, width, height, Bitmap.Config.ARGB_8888)
            val fields = SpatialDepthGeometry.buildDisplacementFields(depthValues, mask, fillRegion, width, height, checkpoint)
            for (i in pixels.indices) {
                val value = (fields.backgroundK[i] * 255f).roundToInt().coerceIn(0, 255)
                pixels[i] = (0xff shl 24) or (value shl 16) or (value shl 8) or value
            }
            backgroundDepth = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            for (i in pixels.indices) {
                val value = (fields.foregroundK[i] * 255f).roundToInt().coerceIn(0, 255)
                pixels[i] = (0xff shl 24) or (value shl 16) or (value shl 8) or value
            }
            reliefBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            for (i in 0 until pixelCount) {
                val value = (mask[i] * 255f).roundToInt().coerceIn(0, 255)
                pixels[i] = (0xff shl 24) or (value shl 16) or (value shl 8) or value
            }
            maskBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            checkpoint()
            delivered = true
            return BuiltLayers(maskBitmap, background, backgroundDepth, reliefBitmap, subjectDepth)
        } finally {
            if (sourceLayer !== source) sourceLayer.recycle()
            depthLayer?.let { if (it !== depth) it.recycle() }
            if (!delivered) {
                maskBitmap?.recycle()
                background?.recycle()
                backgroundDepth?.recycle()
                reliefBitmap?.recycle()
            }
        }
    }

    private fun scaleMatting(source: Bitmap, image: Bitmap, width: Int, height: Int, preserveEdges: Boolean, checkpoint: () -> Unit): FloatArray? {
        if (source.width < 2 || source.height < 2) return null
        val scaled = if (source.width == width && source.height == height) source else source.scale(width, height)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== source) scaled.recycle()
        val values = FloatArray(width * height) { index -> (pixels[index] and 0xFF) / 255f }
        val imagePixels = IntArray(width * height)
        image.getPixels(imagePixels, 0, width, 0, 0, width, height)
        val radius = if (preserveEdges) 2 else (max(width, height) / 240).coerceIn(2, 8)
        return SpatialMattingEdges.refine(values, imagePixels, width, height, radius, checkpoint)
    }

    private fun alphaMask(sourceLayer: Bitmap, width: Int, height: Int): FloatArray? {
        if (!sourceLayer.hasAlpha()) return null
        val pixels = IntArray(width * height)
        sourceLayer.getPixels(pixels, 0, width, 0, 0, width, height)
        val mask = FloatArray(width * height)
        var transparent = false
        for (index in mask.indices) {
            val alpha = (pixels[index] ushr 24) and 0xFF
            mask[index] = alpha / 255f
            if (alpha < 246) transparent = true
        }
        return if (transparent) mask else null
    }

    private fun depthDerivedMask(depth: FloatArray, width: Int, height: Int): FloatArray {
        val pixelCount = width * height
        val score = FloatArray(pixelCount)
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val norm = sqrt(halfWidth * halfWidth + halfHeight * halfHeight).coerceAtLeast(1f)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val dx = x - halfWidth
                val dy = y - halfHeight
                val center = (1f - sqrt(dx * dx + dy * dy) / norm).coerceIn(0f, 1f)
                score[row + x] = (depth[row + x] * .86f + center * .14f).coerceIn(0f, 1f)
            }
        }
        val threshold = otsu(score)
        val low = (threshold - .055f).coerceAtLeast(0f)
        val high = (threshold + .055f).coerceAtMost(1f)
        val range = (high - low).coerceAtLeast(.0001f)
        val mask = FloatArray(pixelCount)
        for (index in mask.indices) {
            mask[index] = ((score[index] - low) / range).coerceIn(0f, 1f)
        }
        morph(mask, width, height, dilate = true)
        morph(mask, width, height, dilate = false)
        var foreground = 0
        for (value in mask) if (value > .5f) foreground++
        val fraction = foreground.toFloat() / pixelCount
        if (fraction < .06f || fraction > .94f) {
            val sorted = mask.copyOf()
            sorted.sort()
            val cut = sorted[(sorted.size * .7f).toInt().coerceIn(0, sorted.lastIndex)]
            for (index in mask.indices) {
                mask[index] = if (mask[index] >= cut) 1f else (mask[index] / cut.coerceAtLeast(.0001f)) * .5f
            }
        }
        return mask
    }

    private fun otsu(values: FloatArray): Float {
        val histogram = IntArray(256)
        for (value in values) histogram[(value * 255f).roundToInt().coerceIn(0, 255)]++
        val total = values.size
        var sumAll = 0.0
        for (index in 0..255) sumAll += index.toDouble() * histogram[index]
        var weightBackground = 0
        var sumBackground = 0.0
        var bestVariance = -1.0
        var bestThreshold = 0
        for (index in 0..255) {
            weightBackground += histogram[index]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += index.toDouble() * histogram[index]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sumAll - sumBackground) / weightForeground
            val delta = meanBackground - meanForeground
            val variance = weightBackground.toDouble() * weightForeground * delta * delta
            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = index
            }
        }
        return (bestThreshold + .5f) / 255f
    }

    private fun morph(values: FloatArray, width: Int, height: Int, dilate: Boolean) {
        val temp = FloatArray(values.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var value = values[row + x]
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    val nRow = ny * width
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx !in 0 until width) continue
                        val other = values[nRow + nx]
                        value = if (dilate) max(value, other) else min(value, other)
                    }
                }
                temp[row + x] = value
            }
        }
        temp.copyInto(values)
    }

    private fun softenMask(values: FloatArray, width: Int, height: Int) {
        repeat(3) { boxBlur(values, width, height) }
    }

    private fun boxBlur(values: FloatArray, width: Int, height: Int) {
        val temp = FloatArray(values.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val left = if (x > 0) values[row + x - 1] else values[row + x]
                val center = values[row + x]
                val right = if (x < width - 1) values[row + x + 1] else values[row + width - 1]
                temp[row + x] = (left + center + right) / 3f
            }
        }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val up = if (y > 0) temp[row - width + x] else temp[x]
                val center = temp[row + x]
                val down = if (y < height - 1) temp[row + width + x] else temp[(height - 1) * width + x]
                values[row + x] = (up + center + down) / 3f
            }
        }
    }
}

object SpatialCoverManager {
    private val depthEstimator = SpatialDepthEstimator()
    private val mattingEstimator = SpatialMattingEstimator()
    private val generationMutex = Mutex()
    private val memoryCache = LruCache<String, SpatialCoverAssets>(4)
    private const val MAX_DISK_CACHE_DIRS = 32

    private fun cacheDir(context: Context): File =
        File(context.filesDir, "spatial_cover_cache").apply { if (!isDirectory) mkdirs() }

    internal fun normalizeAlbumToken(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(java.util.Locale.ROOT)

    internal fun cacheScopeKey(
        artworkUri: String,
        songAlbum: String = "",
        songAlbumArtist: String = "",
        songArtist: String = "",
    ): String {
        val normalizedAlbum = normalizeAlbumToken(songAlbum)
        val effectiveAlbumArtist = normalizeAlbumToken(songAlbumArtist.ifBlank { songArtist })
        return if (normalizedAlbum.isNotEmpty() && effectiveAlbumArtist.isNotEmpty()) {
            "album:$normalizedAlbum|$effectiveAlbumArtist"
        } else {
            "art:$artworkUri"
        }
    }

    internal fun keyFor(
        artworkUri: String,
        depthModel: SpatialDepthModel,
        mattingModel: SpatialMattingModel,
        songAlbum: String = "",
        songAlbumArtist: String = "",
        songArtist: String = "",
    ): String {
        val scope = cacheScopeKey(artworkUri, songAlbum, songAlbumArtist, songArtist)
        val raw = "$scope|${depthModel.id}|${mattingModel.id}|v3"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun loadFromDisk(dir: File): SpatialCoverAssets? {
        val metaFile = File(dir, "meta.json")
        if (!metaFile.isFile) return null
        return runCatching {
            val meta = JSONObject(metaFile.readText())
            val source = BitmapFactory.decodeFile(File(dir, "source.png").path) ?: return null
            val depth = BitmapFactory.decodeFile(File(dir, "depth.png").path) ?: return null
            val hasLayers = meta.optBoolean("hasLayers", false)
            val subjectDepth = meta.optDouble("subjectDepth", 0.68).toFloat()
            val depthModelId = meta.optString("depthModelId", "")
            val mattingModelId = meta.optString("mattingModelId", "")
            val mask = if (hasLayers) BitmapFactory.decodeFile(File(dir, "mask.png").path) else null
            val bg = if (hasLayers) BitmapFactory.decodeFile(File(dir, "bg.jpg").path) else null
            val bgDepth = if (hasLayers) BitmapFactory.decodeFile(File(dir, "bg_depth.png").path) else null
            val relief = if (hasLayers) BitmapFactory.decodeFile(File(dir, "relief.png").path) else null
            dir.setLastModified(System.currentTimeMillis())
            SpatialCoverAssets(source, depth, mask, bg, bgDepth, relief, subjectDepth, depthModelId, mattingModelId)
        }.getOrNull()
    }

    private fun saveToDisk(
        context: Context,
        dir: File,
        assets: SpatialCoverAssets,
        songTitle: String,
        songArtist: String,
        songAlbum: String,
        songAlbumArtist: String,
    ) {
        runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            fun writePng(name: String, bmp: Bitmap) {
                FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            fun writeJpg(name: String, bmp: Bitmap) {
                FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            }
            writePng("source.png", assets.source)
            writePng("depth.png", assets.depth)
            if (assets.hasLayers) {
                assets.mask?.let { writePng("mask.png", it) }
                assets.background?.let { writeJpg("bg.jpg", it) }
                assets.backgroundDepth?.let { writePng("bg_depth.png", it) }
                assets.subjectRelief?.let { writePng("relief.png", it) }
            }
            val meta = JSONObject().apply {
                put("hasLayers", assets.hasLayers)
                put("subjectDepth", assets.subjectDepth.toDouble())
                put("depthModelId", assets.depthModelId)
                put("mattingModelId", assets.mattingModelId)
                put("title", songTitle)
                put("artist", songArtist)
                put("album", songAlbum)
                put("albumArtist", songAlbumArtist.ifBlank { songArtist })
                put("createdAt", System.currentTimeMillis())
            }
            File(dir, "meta.json").writeText(meta.toString())
            trimCache(cacheDir(context))
        }
    }

    suspend fun listCacheEntries(context: Context): List<SpatialCoverCacheEntry> = withContext(Dispatchers.IO) {
        val root = cacheDir(context.applicationContext)
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return@withContext emptyList()
        dirs.mapNotNull { dir ->
            val metaFile = File(dir, "meta.json")
            val sourceFile = File(dir, "source.png")
            if (!metaFile.isFile || !sourceFile.isFile) return@mapNotNull null
            runCatching {
                val meta = JSONObject(metaFile.readText())
                val bytes = dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
                SpatialCoverCacheEntry(
                    key = dir.name,
                    title = meta.optString("title", ""),
                    artist = meta.optString("artist", ""),
                    album = meta.optString("album", ""),
                    albumArtist = meta.optString("albumArtist", ""),
                    depthModelId = meta.optString("depthModelId", SpatialDepthModel.DEPTH_ANYTHING_V2_SMALL.id),
                    mattingModelId = meta.optString("mattingModelId", SpatialMattingModel.U2NETP.id),
                    sizeBytes = bytes,
                    createdAt = meta.optLong("createdAt", dir.lastModified()),
                    sourcePath = sourceFile.absolutePath,
                )
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    suspend fun loadCacheEntryAssets(context: Context, key: String): SpatialCoverAssets? = withContext(Dispatchers.IO) {
        memoryCache.get(key)?.let { return@withContext it }
        val dir = File(cacheDir(context.applicationContext), key)
        if (!dir.isDirectory) return@withContext null
        loadFromDisk(dir)?.also { memoryCache.put(key, it) }
    }

    suspend fun deleteCacheEntry(context: Context, key: String) = withContext(Dispatchers.IO) {
        memoryCache.remove(key)
        File(cacheDir(context.applicationContext), key).deleteRecursively()
    }

    suspend fun clearAllCache(context: Context) = withContext(Dispatchers.IO) {
        memoryCache.evictAll()
        cacheDir(context.applicationContext).listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun trimCache(root: File) {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
        if (dirs.size <= MAX_DISK_CACHE_DIRS) return
        dirs.sortedBy { it.lastModified() }
            .take(dirs.size - MAX_DISK_CACHE_DIRS)
            .forEach { it.deleteRecursively() }
    }

    suspend fun resolve(
        context: Context,
        artworkUri: String,
        depthModel: SpatialDepthModel,
        mattingModel: SpatialMattingModel,
        songTitle: String = "",
        songArtist: String = "",
        songAlbum: String = "",
        songAlbumArtist: String = "",
        onState: (SpatialCoverResolveState) -> Unit,
    ) {
        if (artworkUri.isBlank()) {
            onState(SpatialCoverResolveState.Idle)
            return
        }
        val appContext = context.applicationContext
        val key = keyFor(artworkUri, depthModel, mattingModel, songAlbum, songAlbumArtist, songArtist)
        memoryCache.get(key)?.let { cached ->
            onState(SpatialCoverResolveState.Ready(cached))
            return
        }
        val entryDir = File(cacheDir(appContext), key)
        val diskCached = withContext(Dispatchers.IO) { loadFromDisk(entryDir) }
        if (diskCached != null) {
            memoryCache.put(key, diskCached)
            onState(SpatialCoverResolveState.Ready(diskCached))
            return
        }
        if (!SpatialModelStore.readySelectionSummary(appContext, depthModel, mattingModel)) {
            onState(SpatialCoverResolveState.MissingModels)
            return
        }
        onState(SpatialCoverResolveState.Generating(0.04f))
        try {
            val assets = generationMutex.withLock {
                memoryCache.get(key)?.let { return@withLock it }
                val decoded = withContext(Dispatchers.IO) {
                    val result = SingletonImageLoader.get(appContext).execute(
                        ImageRequest.Builder(appContext)
                            .data(artworkUri)
                            .size(960)
                            .allowHardware(false)
                            .build(),
                    ) as? SuccessResult ?: return@withContext null
                    val raw = result.image.toBitmap()
                    if (raw.config == Bitmap.Config.ARGB_8888) raw else raw.copy(Bitmap.Config.ARGB_8888, false)
                } ?: error("无法读取封面图片")

                withContext(Dispatchers.Default) {
                    val task = currentCoroutineContext()
                    task.ensureActive()
                    onState(SpatialCoverResolveState.Generating(0.14f))
                    depthEstimator.ensureLoaded(appContext, depthModel)
                    onState(SpatialCoverResolveState.Generating(0.32f))
                    val rawDepth = try {
                        depthEstimator.run(decoded)
                    } finally {
                        depthEstimator.close()
                    }
                    task.ensureActive()
                    onState(SpatialCoverResolveState.Generating(0.68f))
                    val depthBitmap = buildDepthBitmap(rawDepth, decoded)
                    val useLayers = mattingModel != SpatialMattingModel.NONE
                    if (!useLayers) {
                        val built = SpatialCoverAssets(
                            source = decoded,
                            depth = depthBitmap,
                            mask = null,
                            background = null,
                            backgroundDepth = null,
                            subjectRelief = null,
                            subjectDepth = 0.65f,
                            depthModelId = depthModel.id,
                            mattingModelId = mattingModel.id,
                        )
                        saveToDisk(appContext, entryDir, built, songTitle, songArtist, songAlbum, songAlbumArtist)
                        built
                    } else {
                        val mattingMask = try {
                            mattingEstimator.estimate(appContext, decoded, mattingModel)
                        } finally {
                            mattingEstimator.close()
                        }
                        task.ensureActive()
                        onState(SpatialCoverResolveState.Generating(0.84f))
                        val layers = try {
                            SpatialLayerBuilder.build(
                                source = decoded,
                                depth = depthBitmap,
                                mattingMask = mattingMask,
                                preserveMattingEdges = mattingModel != SpatialMattingModel.U2NETP,
                                checkpoint = { task.ensureActive() },
                            )
                        } finally {
                            mattingMask?.recycle()
                        }
                        val sourceLayer = if (decoded.width == layers.mask.width && decoded.height == layers.mask.height) {
                            decoded
                        } else {
                            decoded.scale(layers.mask.width, layers.mask.height).also {
                                if (it !== decoded) decoded.recycle()
                            }
                        }
                        val built = SpatialCoverAssets(
                            source = sourceLayer,
                            depth = depthBitmap,
                            mask = layers.mask,
                            background = layers.background,
                            backgroundDepth = layers.backgroundDepth,
                            subjectRelief = layers.subjectRelief,
                            subjectDepth = layers.subjectDepth,
                            depthModelId = depthModel.id,
                            mattingModelId = mattingModel.id,
                        )
                        saveToDisk(appContext, entryDir, built, songTitle, songArtist, songAlbum, songAlbumArtist)
                        built
                    }
                }
            }
            memoryCache.put(key, assets)
            onState(SpatialCoverResolveState.Ready(assets))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            onState(SpatialCoverResolveState.Failed(error.message ?: "生成空间封面失败"))
        }
    }

    private fun buildDepthBitmap(raw: DepthRaw, source: Bitmap): Bitmap {
        val aspect = source.width.toFloat() / source.height.coerceAtLeast(1)
        val longSide = minOf(960, maxOf(source.width, source.height))
        val width: Int
        val height: Int
        if (aspect >= 1f) {
            width = longSide
            height = max(1, (longSide / aspect).roundToInt())
        } else {
            width = max(1, (longSide * aspect).roundToInt())
            height = longSide
        }
        val small = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(raw.width * raw.height)
        for (index in pixels.indices) {
            val value = (raw.values[index] * 255f).roundToInt().coerceIn(0, 255)
            pixels[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        small.setPixels(pixels, 0, raw.width, 0, 0, raw.width, raw.height)
        val scaled = Bitmap.createScaledBitmap(small, width, height, true)
        if (scaled !== small) small.recycle()
        return scaled
    }
}
