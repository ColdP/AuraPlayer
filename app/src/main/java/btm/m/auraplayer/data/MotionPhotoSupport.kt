// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class MotionMediaKind {
    STATIC,
    EMBEDDED_VIDEO,
    ANIMATED_RASTER,
}

internal data class MotionPhotoInfo(
    val kind: MotionMediaKind = MotionMediaKind.STATIC,
    val videoStartOffset: Long = 0L,
    val videoLength: Long = 0L,
    val mime: String = "",
) {
    val isDynamic: Boolean
        get() = kind != MotionMediaKind.STATIC
}

internal object MotionPhotoSupport {
    private const val CACHE_DIR_NAME = "motion-banners"
    private const val MAX_CACHE_ENTRIES = 16
    private const val MAX_REVERSE_FRAME_EDGE = 960
    private const val MAX_REVERSE_FRAMES = 96
    private const val INSPECTION_CACHE_LIMIT = 64

    private data class InspectionCacheKey(
        val path: String,
        val length: Long,
        val lastModified: Long,
    )

    private val inspectionCache = LinkedHashMap<InspectionCacheKey, MotionPhotoInfo>(16, 0.75f, true)

    private val KNOWN_MP4_BRANDS = setOf(
        "isom", "iso2", "iso3", "iso4", "iso5", "iso6",
        "mp41", "mp42", "avc1", "hvc1", "hev1", "m4v ",
        "M4V ", "qt  ", "3gp4", "3gp5", "3gp6", "3gp7",
        "nvr1", "f4v ", "kddi",
    )

    private val KNOWN_HEIF_BRANDS = setOf(
        "heic", "heix", "hevc", "hevx", "mif1", "msf1",
        "avif", "avis", "miaf",
    )

    private val KNOWN_MP4_TOP_BOXES = setOf(
        "ftyp", "free", "skip", "wide", "mdat", "moov",
        "moof", "mfra", "uuid", "meta", "pdin", "sidx",
        "emsg", "styp",
    )

    private data class ContainerDirectoryItem(
        val mime: String,
        val semantic: String,
        val length: Long,
        val padding: Long,
    )

    fun resolveLocalFile(uriOrPath: String): File? {
        if (uriOrPath.isBlank()) return null
        val file = if (uriOrPath.startsWith("file:", ignoreCase = true)) {
            Uri.parse(uriOrPath).path?.let(::File)
        } else {
            File(uriOrPath)
        }
        return file?.takeIf { it.isFile && it.canRead() }
    }

    fun inspect(uriOrPath: String): MotionPhotoInfo {
        val file = resolveLocalFile(uriOrPath) ?: return MotionPhotoInfo()
        val key = InspectionCacheKey(file.absolutePath, file.length(), file.lastModified())
        synchronized(inspectionCache) {
            inspectionCache[key]?.let { return it }
        }
        val result = runCatching { inspectFile(file) }.getOrDefault(MotionPhotoInfo())
        synchronized(inspectionCache) {
            inspectionCache[key] = result
            while (inspectionCache.size > INSPECTION_CACHE_LIMIT) {
                val oldest = inspectionCache.entries.firstOrNull()?.key ?: break
                inspectionCache.remove(oldest)
            }
        }
        return result
    }

    fun inferExtensionFromBytes(bytes: ByteArray, fallbackMime: String?): String {
        if (bytes.size >= 12) {
            val head4 = String(bytes, 0, 4, StandardCharsets.US_ASCII)
            if (head4 == "GIF8") return "gif"
            if (head4 == "RIFF" && String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WEBP") return "webp"
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
                return "png"
            }
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "jpg"
            if (String(bytes, 4, 4, StandardCharsets.US_ASCII) == "ftyp") {
                val brand = String(bytes, 8, 4, StandardCharsets.US_ASCII).lowercase(Locale.ROOT)
                return when {
                    brand == "avif" || brand == "avis" -> "avif"
                    brand in KNOWN_HEIF_BRANDS -> "heic"
                    else -> "heic"
                }
            }
        }
        val mime = fallbackMime.orEmpty().lowercase(Locale.ROOT)
        return when {
            "png" in mime -> "png"
            "webp" in mime -> "webp"
            "gif" in mime -> "gif"
            "heic" in mime || "heif" in mime -> "heic"
            "avif" in mime -> "avif"
            else -> "jpg"
        }
    }

    suspend fun inspectAsync(uriOrPath: String): MotionPhotoInfo = withContext(Dispatchers.IO) {
        inspect(uriOrPath)
    }

    suspend fun resolveOrExtractVideoFile(context: Context, uriOrPath: String): File? = withContext(Dispatchers.IO) {
        val sourceFile = resolveLocalFile(uriOrPath) ?: return@withContext null
        val info = inspect(uriOrPath)
        if (info.kind != MotionMediaKind.EMBEDDED_VIDEO || info.videoLength <= 0L) {
            return@withContext null
        }
        val start = info.videoStartOffset
        val length = info.videoLength
        if (start < 0L || start + length > sourceFile.length()) return@withContext null

        val dir = cacheDirectory(context)
        val key = cacheKey(sourceFile, start, length)
        val target = File(dir, "$key.mp4")
        if (target.isFile && target.length() == length) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }

        val temp = File(dir, "$key.mp4.tmp")
        val extracted = runCatching {
            RandomAccessFile(sourceFile, "r").use { raf ->
                FileOutputStream(temp).use { out ->
                    raf.seek(start)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0L) {
                        val toRead = min(buffer.size.toLong(), remaining).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                    out.fd.sync()
                }
            }
            if (temp.length() == length && temp.renameTo(target)) {
                pruneCacheDirectory(dir)
                target
            } else {
                temp.delete()
                null
            }
        }.getOrElse {
            temp.delete()
            null
        }
        extracted
    }

    /**
     * Builds or returns a compact disk-backed JPEG frame pack (`AREV`) sorted in forward capture
     * order so reverse playback steps `[count - 1 downTo 0]` at native speed using bounded RAM.
     */
    suspend fun resolveOrBuildReversePack(context: Context, videoFile: File): File? = withContext(Dispatchers.IO) {
        if (!videoFile.isFile) return@withContext null
        val dir = cacheDirectory(context)
        val key = cacheKey(videoFile, 0L, videoFile.length())
        val target = File(dir, "$key.revpack")
        if (target.isFile && target.length() > 16L) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }

        val temp = File(dir, "$key.revpack.tmp")
        val built = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoFile.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(100L) ?: 2000L
                val rawFrameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toIntOrNull()?.coerceAtLeast(0) ?: 0

                val targetFps = 24f
                val desiredFrames = ((durationMs / 1000f) * targetFps).roundToInt()
                    .coerceIn(12, MAX_REVERSE_FRAMES)
                val frames = extractSampledFrames(retriever, durationMs, rawFrameCount, desiredFrames)
                if (frames.isEmpty()) return@runCatching null

                DataOutputStream(FileOutputStream(temp).buffered()).use { out ->
                    out.writeBytes("AREV")
                    out.writeInt(frames.size)
                    val baos = ByteArrayOutputStream(64 * 1024)
                    frames.forEach { (ptsMs, bitmap) ->
                        baos.reset()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                        val bytes = baos.toByteArray()
                        out.writeLong(ptsMs)
                        out.writeInt(bytes.size)
                        out.write(bytes)
                        bitmap.recycle()
                    }
                    out.flush()
                }
                if (temp.length() > 16L && temp.renameTo(target)) {
                    pruneCacheDirectory(dir)
                    target
                } else {
                    temp.delete()
                    null
                }
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrElse {
            temp.delete()
            null
        }
        built
    }

    private fun extractSampledFrames(
        retriever: MediaMetadataRetriever,
        durationMs: Long,
        rawFrameCount: Int,
        desiredFrames: Int,
    ): List<Pair<Long, Bitmap>> {
        val result = ArrayList<Pair<Long, Bitmap>>(desiredFrames)
        if (rawFrameCount in 2..MAX_REVERSE_FRAMES * 3) {
            val step = (rawFrameCount.toFloat() / desiredFrames.toFloat()).coerceAtLeast(1f)
            val batchParams = MediaMetadataRetriever.BitmapParams().apply {
                preferredConfig = Bitmap.Config.ARGB_8888
            }
            val indices = (0 until desiredFrames).map { i ->
                (i * step).roundToInt().coerceIn(0, rawFrameCount - 1)
            }.distinct()
            if (indices.isNotEmpty() && step <= 1.25f) {
                var pos = 0
                while (pos < indices.size) {
                    val count = min(10, indices.size - pos)
                    val batch = runCatching {
                        retriever.getFramesAtIndex(indices[pos], count, batchParams)
                    }.getOrNull().orEmpty()
                    if (batch.isEmpty()) break
                    batch.forEachIndexed { offset, bmp ->
                        val frameIdx = indices[pos + offset]
                        val ptsMs = ((frameIdx.toFloat() / (rawFrameCount - 1).coerceAtLeast(1)) * durationMs).toLong()
                        result.add(ptsMs to downscaleForReverse(bmp))
                    }
                    pos += batch.size
                }
                if (result.size >= desiredFrames / 2) return result
                result.forEach { it.second.recycle() }
                result.clear()
            }
        }

        for (i in 0 until desiredFrames) {
            val ptsMs = if (desiredFrames <= 1) 0L else (i * durationMs) / (desiredFrames - 1)
            val ptsUs = ptsMs * 1000L
            val bmp = runCatching {
                retriever.getScaledFrameAtTime(
                    ptsUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    MAX_REVERSE_FRAME_EDGE,
                    MAX_REVERSE_FRAME_EDGE,
                )
            }.getOrNull() ?: continue
            result.add(ptsMs to downscaleForReverse(bmp))
        }
        return result
    }

    private fun downscaleForReverse(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = maxOf(w, h)
        if (longest <= MAX_REVERSE_FRAME_EDGE) return bitmap
        val scale = MAX_REVERSE_FRAME_EDGE.toFloat() / longest.toFloat()
        val dstW = (w * scale).roundToInt().coerceAtLeast(1)
        val dstH = (h * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun inspectFile(file: File): MotionPhotoInfo {
        val fileSize = file.length()
        if (fileSize < 32L) return MotionPhotoInfo()

        RandomAccessFile(file, "r").use { raf ->
            val headLen = min(fileSize, 4096L).toInt()
            val head = ByteArray(headLen)
            raf.seek(0L)
            raf.readFully(head)

            // 1. GIF (GIF87a / GIF89a with multiple frames)
            if (isAnimatedGif(head, raf, fileSize)) {
                return MotionPhotoInfo(kind = MotionMediaKind.ANIMATED_RASTER, mime = "image/gif")
            }

            // 2. Animated WebP (VP8X animation flag or ANIM chunk)
            if (isAnimatedWebP(head)) {
                return MotionPhotoInfo(kind = MotionMediaKind.ANIMATED_RASTER, mime = "image/webp")
            }

            // 3. Parse XMP metadata across Google / Xiaomi / OPPO / vivo / HONOR standards
            val xmpBlocks = extractXmpBlocks(raf, fileSize)
            for (xmp in xmpBlocks) {
                parseXmpMotionBounds(xmp, raf, fileSize)?.let { (start, end) ->
                    return MotionPhotoInfo(
                        kind = MotionMediaKind.EMBEDDED_VIDEO,
                        videoStartOffset = start,
                        videoLength = end - start,
                        mime = "video/mp4",
                    )
                }
            }

            // 4. ISOBMFF containers (HEIC / HEIF / AVIF: Android MotionPhoto `mpvd` box or appended MP4)
            if (head.size >= 12 && String(head, 4, 4, StandardCharsets.US_ASCII) == "ftyp") {
                val brand = String(head, 8, 4, StandardCharsets.US_ASCII).lowercase(Locale.ROOT)
                if (brand in KNOWN_HEIF_BRANDS) {
                    findIsobmffMotionVideo(raf, fileSize)?.let { (start, end) ->
                        return MotionPhotoInfo(
                            kind = MotionMediaKind.EMBEDDED_VIDEO,
                            videoStartOffset = start,
                            videoLength = end - start,
                            mime = "video/mp4",
                        )
                    }
                    if (brand == "msf1" || brand == "avis") {
                        return MotionPhotoInfo(kind = MotionMediaKind.ANIMATED_RASTER, mime = "image/heif")
                    }
                }
            }

            // 5. Samsung MotionPhoto_Data binary trailer
            findSamsungMotionPhotoBounds(raf, fileSize)?.let { (start, end) ->
                return MotionPhotoInfo(
                    kind = MotionMediaKind.EMBEDDED_VIDEO,
                    videoStartOffset = start,
                    videoLength = end - start,
                    mime = "video/mp4",
                )
            }

            // 6. Universal tail MP4 box-chain scan (covers Huawei/HONOR HWCF, vivo, stripped XMP)
            findHeuristicMp4Trailer(raf, fileSize)?.let { (start, end) ->
                return MotionPhotoInfo(
                    kind = MotionMediaKind.EMBEDDED_VIDEO,
                    videoStartOffset = start,
                    videoLength = end - start,
                    mime = "video/mp4",
                )
            }
        }

        return MotionPhotoInfo()
    }

    private fun isAnimatedGif(head: ByteArray, raf: RandomAccessFile, fileSize: Long): Boolean {
        if (head.size < 14) return false
        val header = String(head, 0, 6, StandardCharsets.US_ASCII)
        if (header != "GIF87a" && header != "GIF89a") return false

        // Parse Logical Screen Descriptor + Global Color Table, then count image descriptors (0x2C).
        var pos = 13L
        val packed = head[10].toInt() and 0xFF
        if ((packed and 0x80) != 0) {
            val gctEntries = 1 shl ((packed and 0x07) + 1)
            pos += gctEntries * 3L
        }
        var imageCount = 0
        val buf = ByteArray(1)
        val extHead = ByteArray(2)
        while (pos < fileSize && imageCount < 2) {
            raf.seek(pos++)
            if (raf.read(buf) <= 0) break
            when (buf[0].toInt() and 0xFF) {
                0x2C -> { // Image Descriptor
                    imageCount++
                    if (imageCount >= 2) return true
                    if (pos + 9 > fileSize) break
                    val desc = ByteArray(9)
                    raf.seek(pos)
                    raf.readFully(desc)
                    pos += 9L
                    val localPacked = desc[8].toInt() and 0xFF
                    if ((localPacked and 0x80) != 0) {
                        val lctEntries = 1 shl ((localPacked and 0x07) + 1)
                        pos += lctEntries * 3L
                    }
                    pos += 1L // LZW min code size
                    pos = skipGifSubBlocks(raf, pos, fileSize)
                }
                0x21 -> { // Extension
                    if (pos + 1 > fileSize) break
                    raf.seek(pos)
                    raf.readFully(extHead, 0, 1)
                    pos += 1L
                    pos = skipGifSubBlocks(raf, pos, fileSize)
                }
                0x3B -> break // Trailer
                else -> break
            }
        }
        return false
    }

    private fun skipGifSubBlocks(raf: RandomAccessFile, startPos: Long, fileSize: Long): Long {
        var pos = startPos
        val lenBuf = ByteArray(1)
        while (pos < fileSize) {
            raf.seek(pos++)
            if (raf.read(lenBuf) <= 0) return fileSize
            val blockSize = lenBuf[0].toInt() and 0xFF
            if (blockSize == 0) return pos
            pos += blockSize.toLong()
        }
        return pos
    }

    private fun isAnimatedWebP(head: ByteArray): Boolean {
        if (head.size < 24) return false
        if (String(head, 0, 4, StandardCharsets.US_ASCII) != "RIFF") return false
        if (String(head, 8, 4, StandardCharsets.US_ASCII) != "WEBP") return false
        val chunkTag = String(head, 12, 4, StandardCharsets.US_ASCII)
        if (chunkTag == "VP8X") {
            val flags = head[20].toInt() and 0xFF
            if ((flags and 0x02) != 0) return true
        }
        val ascii = String(head, 12, min(head.size - 12, 2048), StandardCharsets.ISO_8859_1)
        return ascii.contains("ANIM")
    }

    private fun extractXmpBlocks(raf: RandomAccessFile, fileSize: Long): List<String> {
        val scanLimit = min(fileSize, 4L * 1024L * 1024L)
        val startMarkers = listOf("<x:xmpmeta".toByteArray(StandardCharsets.US_ASCII), "<rdf:RDF".toByteArray(StandardCharsets.US_ASCII))
        val endMarkers = listOf("</x:xmpmeta>", "</rdf:RDF>")
        val hits = ArrayList<String>(2)

        val chunkSize = 256 * 1024
        val overlap = 32
        var offset = 0L
        val buffer = ByteArray(chunkSize + overlap)

        while (offset < scanLimit && hits.size < 2) {
            val toRead = min(chunkSize.toLong(), scanLimit - offset).toInt()
            raf.seek(offset)
            val actual = raf.read(buffer, 0, toRead)
            if (actual <= 0) break

            for ((mIdx, startMarker) in startMarkers.withIndex()) {
                val localIdx = indexOfBytes(buffer, actual, startMarker, 0)
                if (localIdx >= 0) {
                    val absStart = offset + localIdx
                    val readLen = min(fileSize - absStart, 128L * 1024L).toInt()
                    val xmpBytes = ByteArray(readLen)
                    raf.seek(absStart)
                    raf.readFully(xmpBytes)
                    val rawXml = String(xmpBytes, StandardCharsets.UTF_8)
                    val endTag = endMarkers[mIdx]
                    val endIdx = rawXml.indexOf(endTag)
                    val trimmed = if (endIdx >= 0) rawXml.substring(0, endIdx + endTag.length) else rawXml
                    if (hits.none { it == trimmed }) hits.add(trimmed)
                }
            }
            if (offset + actual >= scanLimit) break
            offset += (actual - overlap).coerceAtLeast(1)
        }
        return hits
    }

    private fun parseXmpMotionBounds(xmp: String, raf: RandomAccessFile, fileSize: Long): Pair<Long, Long>? {
        val items = ArrayList<ContainerDirectoryItem>()
        var microVideoFlag = false
        var microVideoOffset = 0L
        var motionPhotoFlag = false
        var explicitVideoOffset = 0L

        runCatching {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmp))

            var inContainerItem = false
            var curMime = ""
            var curSemantic = ""
            var curLength = 0L
            var curPadding = 0L
            var currentTag = ""

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val rawName = parser.name.orEmpty()
                        val localName = rawName.substringAfter(':')
                        currentTag = localName

                        for (i in 0 until parser.attributeCount) {
                            val attrLocal = parser.getAttributeName(i).substringAfter(':')
                            val attrVal = parser.getAttributeValue(i).trim()
                            when {
                                attrLocal.equals("MotionPhoto", ignoreCase = true) ->
                                    if (attrVal == "1" || attrVal.equals("true", ignoreCase = true)) motionPhotoFlag = true
                                attrLocal.equals("MicroVideo", ignoreCase = true) ->
                                    if (attrVal == "1" || attrVal.equals("true", ignoreCase = true)) microVideoFlag = true
                                attrLocal.equals("MicroVideoOffset", ignoreCase = true) ->
                                    attrVal.toLongOrNull()?.let { microVideoOffset = it }
                                attrLocal.equals("MotionPhotoOffset", ignoreCase = true) ||
                                    attrLocal.equals("LivePhotoOffset", ignoreCase = true) ||
                                    attrLocal.equals("VideoOffset", ignoreCase = true) ->
                                    attrVal.toLongOrNull()?.let { explicitVideoOffset = it }
                            }
                        }

                        if (localName.equals("Item", ignoreCase = true)) {
                            inContainerItem = true
                            curMime = ""
                            curSemantic = ""
                            curLength = 0L
                            curPadding = 0L
                            for (i in 0 until parser.attributeCount) {
                                val attrLocal = parser.getAttributeName(i).substringAfter(':')
                                val attrVal = parser.getAttributeValue(i).trim()
                                when {
                                    attrLocal.equals("Mime", ignoreCase = true) -> curMime = attrVal
                                    attrLocal.equals("Semantic", ignoreCase = true) -> curSemantic = attrVal
                                    attrLocal.equals("Length", ignoreCase = true) -> curLength = attrVal.toLongOrNull() ?: 0L
                                    attrLocal.equals("Padding", ignoreCase = true) -> curPadding = attrVal.toLongOrNull() ?: 0L
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            if (inContainerItem) {
                                when {
                                    currentTag.equals("Mime", ignoreCase = true) -> curMime = text
                                    currentTag.equals("Semantic", ignoreCase = true) -> curSemantic = text
                                    currentTag.equals("Length", ignoreCase = true) -> text.toLongOrNull()?.let { curLength = it }
                                    currentTag.equals("Padding", ignoreCase = true) -> text.toLongOrNull()?.let { curPadding = it }
                                }
                            }
                            when {
                                currentTag.equals("MotionPhoto", ignoreCase = true) ->
                                    if (text == "1" || text.equals("true", ignoreCase = true)) motionPhotoFlag = true
                                currentTag.equals("MicroVideo", ignoreCase = true) ->
                                    if (text == "1" || text.equals("true", ignoreCase = true)) microVideoFlag = true
                                currentTag.equals("MicroVideoOffset", ignoreCase = true) ->
                                    text.toLongOrNull()?.let { microVideoOffset = it }
                                currentTag.equals("MotionPhotoOffset", ignoreCase = true) ||
                                    currentTag.equals("LivePhotoOffset", ignoreCase = true) ||
                                    currentTag.equals("VideoOffset", ignoreCase = true) ->
                                    text.toLongOrNull()?.let { explicitVideoOffset = it }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val localName = parser.name.orEmpty().substringAfter(':')
                        if (localName.equals("Item", ignoreCase = true) && inContainerItem) {
                            items.add(ContainerDirectoryItem(curMime, curSemantic, curLength, curPadding))
                            inContainerItem = false
                        }
                        currentTag = ""
                    }
                }
                event = parser.next()
            }
        }

        // Priority 1: Google Motion Photo Container Directory (Pixel / Xiaomi / OPPO O-Live / vivo / HONOR)
        val motionIdx = items.indexOfFirst {
            it.semantic.equals("MotionPhoto", ignoreCase = true) ||
                (it.mime.startsWith("video/", ignoreCase = true) && it.length > 0L)
        }
        if (motionIdx >= 0 && items[motionIdx].length > 0L) {
            var trailingSum = 0L
            for (i in motionIdx until items.size) {
                trailingSum += items[i].length.coerceAtLeast(0L) + items[i].padding.coerceAtLeast(0L)
            }
            val approxStart = fileSize - trailingSum
            val targetLen = items[motionIdx].length
            alignAndValidateMp4(raf, approxStart, (approxStart + targetLen).coerceAtMost(fileSize), fileSize)?.let {
                return it
            }
            // Fallback: some encoders count Padding before the video or append Primary length without trailing sum tweaks.
            val tailStart = fileSize - targetLen
            alignAndValidateMp4(raf, tailStart, fileSize, fileSize)?.let {
                return it
            }
        }

        // Priority 2: Legacy Google / Xiaomi MicroVideoOffset
        val candidateOffset = when {
            (microVideoFlag || motionPhotoFlag) && microVideoOffset > 0L -> microVideoOffset
            microVideoOffset > 0L -> microVideoOffset
            explicitVideoOffset > 0L -> explicitVideoOffset
            else -> 0L
        }
        if (candidateOffset > 0L && candidateOffset < fileSize) {
            val approxStart = fileSize - candidateOffset
            alignAndValidateMp4(raf, approxStart, fileSize, fileSize)?.let { return it }
        }
        return null
    }

    private fun findIsobmffMotionVideo(raf: RandomAccessFile, fileSize: Long): Pair<Long, Long>? {
        var pos = 0L
        val header = ByteArray(16)
        var seenFirstFtyp = false
        while (pos + 8L <= fileSize) {
            raf.seek(pos)
            raf.readFully(header, 0, 8)
            val size32 = readUint32BE(header, 0)
            val boxType = String(header, 4, 4, StandardCharsets.US_ASCII)
            var boxHeaderLen = 8L
            val boxSize = when {
                size32 == 1L && pos + 16L <= fileSize -> {
                    raf.seek(pos + 8L)
                    raf.readFully(header, 8, 8)
                    boxHeaderLen = 16L
                    readUint64BE(header, 8)
                }
                size32 == 0L -> fileSize - pos
                else -> size32
            }
            if (boxSize < boxHeaderLen || pos + boxSize > fileSize) break

            if (boxType == "mpvd") {
                val payloadStart = pos + boxHeaderLen
                val payloadEnd = pos + boxSize
                alignAndValidateMp4(raf, payloadStart, payloadEnd, fileSize)?.let { return it }
            }
            if (boxType == "ftyp") {
                if (seenFirstFtyp && boxSize in 16L..128L && pos + 12L <= fileSize) {
                    val brandBuf = ByteArray(4)
                    raf.seek(pos + 8L)
                    raf.readFully(brandBuf)
                    val brand = String(brandBuf, StandardCharsets.US_ASCII)
                    if (brand in KNOWN_MP4_BRANDS) {
                        verifyMp4BoxChain(raf, pos, fileSize)?.let { end ->
                            return pos to end
                        }
                    }
                }
                seenFirstFtyp = true
            }
            pos += boxSize
        }
        return null
    }

    private fun findSamsungMotionPhotoBounds(raf: RandomAccessFile, fileSize: Long): Pair<Long, Long>? {
        val marker = "MotionPhoto_Data".toByteArray(StandardCharsets.US_ASCII)
        val scanStart = (fileSize - min(fileSize, 32L * 1024L * 1024L)).coerceAtLeast(0L)
        val chunkSize = 256 * 1024
        val overlap = marker.size + 64
        val buf = ByteArray(chunkSize + overlap)
        var offset = scanStart

        while (offset < fileSize) {
            val toRead = min((chunkSize + overlap).toLong(), fileSize - offset).toInt()
            raf.seek(offset)
            val actual = raf.read(buf, 0, toRead)
            if (actual <= marker.size) break
            val hit = indexOfBytes(buf, actual, marker, 0)
            if (hit >= 0) {
                val afterMarker = offset + hit + marker.size
                alignAndValidateMp4(raf, afterMarker, fileSize, fileSize, searchRadius = 64L)?.let { return it }
            }
            if (offset + actual >= fileSize) break
            offset += (actual - overlap).coerceAtLeast(1)
        }
        return null
    }

    private fun findHeuristicMp4Trailer(raf: RandomAccessFile, fileSize: Long): Pair<Long, Long>? {
        if (fileSize < 16_384L) return null
        val ftyp = "ftyp".toByteArray(StandardCharsets.US_ASCII)
        val chunkSize = 256 * 1024
        val overlap = 64
        val buf = ByteArray(chunkSize + overlap)
        var offset = 64L

        while (offset < fileSize) {
            val toRead = min((chunkSize + overlap).toLong(), fileSize - offset).toInt()
            raf.seek(offset)
            val actual = raf.read(buf, 0, toRead)
            if (actual < 12) break

            var searchFrom = 4
            while (searchFrom < actual - 8) {
                val hit = indexOfBytes(buf, actual, ftyp, searchFrom)
                if (hit < 0) break
                val boxSize = readUint32BE(buf, hit - 4)
                if (boxSize in 16L..128L && hit + 8 <= actual) {
                    val brand = String(buf, hit + 4, 4, StandardCharsets.US_ASCII)
                    if (brand in KNOWN_MP4_BRANDS) {
                        val candidateStart = offset + (hit - 4)
                        val validEnd = verifyMp4BoxChain(raf, candidateStart, fileSize)
                        if (validEnd != null && validEnd - candidateStart >= 8192L) {
                            return candidateStart to validEnd
                        }
                    }
                }
                searchFrom = hit + 4
            }

            if (offset + actual >= fileSize) break
            offset += (actual - overlap).coerceAtLeast(1)
        }
        return null
    }

    private fun alignAndValidateMp4(
        raf: RandomAccessFile,
        approxStart: Long,
        maxEnd: Long,
        fileSize: Long,
        searchRadius: Long = 32L,
    ): Pair<Long, Long>? {
        val windowStart = (approxStart - searchRadius).coerceAtLeast(0L)
        val windowEnd = (approxStart + searchRadius + 16L).coerceAtMost(fileSize)
        val span = (windowEnd - windowStart).toInt()
        if (span < 12) return null

        val peek = ByteArray(span)
        raf.seek(windowStart)
        raf.readFully(peek)

        val ftyp = "ftyp".toByteArray(StandardCharsets.US_ASCII)
        var pos = 4
        while (pos < peek.size - 8) {
            val hit = indexOfBytes(peek, peek.size, ftyp, pos)
            if (hit < 0) break
            val boxSize = readUint32BE(peek, hit - 4)
            if (boxSize in 16L..128L && hit + 8 <= peek.size) {
                val brand = String(peek, hit + 4, 4, StandardCharsets.US_ASCII)
                if (brand in KNOWN_MP4_BRANDS) {
                    val exactStart = windowStart + (hit - 4)
                    val endLimit = if (maxEnd > exactStart) maxEnd.coerceAtMost(fileSize) else fileSize
                    val chainEnd = verifyMp4BoxChain(raf, exactStart, endLimit)
                    if (chainEnd != null) return exactStart to chainEnd
                }
            }
            pos = hit + 4
        }
        return null
    }

    private fun verifyMp4BoxChain(raf: RandomAccessFile, start: Long, limit: Long): Long? {
        if (start < 0L || start + 16L > limit) return null
        var pos = start
        var sawFtyp = false
        var sawMoov = false
        var sawMdat = false
        val header = ByteArray(16)

        while (pos + 8L <= limit) {
            raf.seek(pos)
            raf.readFully(header, 0, 8)
            val size32 = readUint32BE(header, 0)
            val boxType = String(header, 4, 4, StandardCharsets.US_ASCII)
            if (boxType !in KNOWN_MP4_TOP_BOXES) break

            var hdrLen = 8L
            val boxSize = when {
                size32 == 1L && pos + 16L <= limit -> {
                    raf.seek(pos + 8L)
                    raf.readFully(header, 8, 8)
                    hdrLen = 16L
                    readUint64BE(header, 8)
                }
                size32 == 0L -> limit - pos
                else -> size32
            }
            if (boxSize < hdrLen || pos + boxSize > limit) break

            when (boxType) {
                "ftyp" -> sawFtyp = true
                "moov" -> sawMoov = true
                "mdat" -> sawMdat = true
            }
            pos += boxSize
        }
        return if (sawFtyp && sawMoov && sawMdat && pos > start) pos else null
    }

    private fun indexOfBytes(haystack: ByteArray, length: Int, needle: ByteArray, fromIndex: Int): Int {
        if (needle.isEmpty() || length < needle.size) return -1
        val maxStart = length - needle.size
        var i = fromIndex.coerceAtLeast(0)
        while (i <= maxStart) {
            var matched = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
            i++
        }
        return -1
    }

    private fun readUint32BE(buf: ByteArray, offset: Int): Long =
        ((buf[offset].toLong() and 0xFFL) shl 24) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 8) or
            (buf[offset + 3].toLong() and 0xFFL)

    private fun readUint64BE(buf: ByteArray, offset: Int): Long =
        (readUint32BE(buf, offset) shl 32) or readUint32BE(buf, offset + 4)

    private fun cacheDirectory(context: Context): File =
        File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }

    private fun cacheKey(file: File, offset: Long, length: Long): String {
        val raw = "${file.absolutePath}|${file.length()}|${file.lastModified()}|$offset|$length"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun pruneCacheDirectory(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        if (files.size <= MAX_CACHE_ENTRIES) return
        files.drop(MAX_CACHE_ENTRIES).forEach { runCatching { it.delete() } }
    }
}
