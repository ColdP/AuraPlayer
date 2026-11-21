// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

internal enum class MotionPhotoVendorProtocol {
    XIAOMI,
    OPLUS,
    VIVO,
    SAMSUNG,
    GOOGLE_STANDARD,
}

/**
 * Exports static covers, standalone MP4 motion videos, and OEM-compliant Motion Photos
 * (Xiaomi/HyperOS, OPPO/OnePlus/Realme O-Live Photo, vivo/iQOO, Samsung SEF, and Google Standard)
 * into MediaStore.
 */
internal object MotionPhotoExporter {
    private const val SAVE_DIR_NAME = "Aura Player"
    private const val XMP_APP1_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val OPLUS_USER_COMMENT = "oplus_8388608"
    private const val SAMSUNG_FIELD_NAME = "MotionPhoto_Data"
    private const val SAMSUNG_SEF_VERSION = 106
    private const val SAMSUNG_TRAILER_PADDING = 32L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    internal fun detectVendorProtocol(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        brand: String = Build.BRAND.orEmpty(),
    ): MotionPhotoVendorProtocol {
        val combined = "${manufacturer.trim().lowercase(Locale.ROOT)} ${brand.trim().lowercase(Locale.ROOT)}"
        return when {
            "xiaomi" in combined || "redmi" in combined || "poco" in combined || "blackshark" in combined ->
                MotionPhotoVendorProtocol.XIAOMI
            "oppo" in combined || "oneplus" in combined || "realme" in combined || "oplus" in combined ->
                MotionPhotoVendorProtocol.OPLUS
            "vivo" in combined || "iqoo" in combined ->
                MotionPhotoVendorProtocol.VIVO
            "samsung" in combined ->
                MotionPhotoVendorProtocol.SAMSUNG
            else ->
                MotionPhotoVendorProtocol.GOOGLE_STANDARD
        }
    }

    internal fun motionPhotoDisplayName(
        baseName: String,
        protocol: MotionPhotoVendorProtocol,
        timestampMs: Long = System.currentTimeMillis(),
    ): String {
        val safe = sanitizeFilePart(baseName, "cover")
        return when (protocol) {
            MotionPhotoVendorProtocol.XIAOMI -> "MVIMG_${safe}_$timestampMs.jpg"
            MotionPhotoVendorProtocol.OPLUS,
            MotionPhotoVendorProtocol.VIVO,
            -> "IMG_${safe}_$timestampMs.jpg"
            MotionPhotoVendorProtocol.SAMSUNG -> "${safe}_$timestampMs.jpg"
            MotionPhotoVendorProtocol.GOOGLE_STANDARD -> "${safe}_$timestampMs.MP.jpg"
        }
    }

    suspend fun saveStaticImage(
        context: Context,
        artworkSource: String,
        motionVideoPath: String?,
        baseName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val rawBytes = loadStillImageBytes(context, artworkSource, motionVideoPath) ?: return@withContext false
        val ext = MotionPhotoSupport.inferExtensionFromBytes(rawBytes, null)
        val mime = when (ext) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "avif" -> "image/avif"
            else -> "image/jpeg"
        }
        val safe = sanitizeFilePart(baseName, "cover")
        val displayName = "${safe}_${System.currentTimeMillis()}.$ext"
        writeImageToMediaStore(context, displayName, mime) { out ->
            out.write(rawBytes)
        }
    }

    suspend fun saveVideo(
        context: Context,
        motionVideoPath: String,
        baseName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val videoFile = File(motionVideoPath)
        if (!videoFile.isFile || videoFile.length() <= 0L) return@withContext false
        val safe = sanitizeFilePart(baseName, "motion")
        val displayName = "${safe}_${System.currentTimeMillis()}.mp4"
        writeVideoToMediaStore(context, displayName) { out ->
            FileInputStream(videoFile).use { input ->
                input.copyTo(out, bufferSize = 64 * 1024)
            }
        }
    }

    suspend fun saveMotionPhoto(
        context: Context,
        artworkSource: String,
        motionVideoPath: String,
        baseName: String,
        protocol: MotionPhotoVendorProtocol = detectVendorProtocol(),
    ): Boolean = withContext(Dispatchers.IO) {
        val videoFile = File(motionVideoPath)
        if (!videoFile.isFile || videoFile.length() <= 0L) return@withContext false

        val rawStill = loadStillImageBytes(context, artworkSource, motionVideoPath) ?: return@withContext false
        val cleanJpeg = normalizeToCleanJpegBytes(rawStill) ?: return@withContext false
        val jpegWithExif = if (protocol == MotionPhotoVendorProtocol.OPLUS) {
            injectOplusUserComment(context, cleanJpeg)
        } else {
            cleanJpeg
        }

        val mp4Length = videoFile.length()
        val xmpXml = buildVendorXmp(protocol, mp4Length)
        val preparedJpeg = injectXmpApp1IntoJpeg(jpegWithExif, xmpXml) ?: return@withContext false
        val displayName = motionPhotoDisplayName(baseName, protocol)

        writeImageToMediaStore(context, displayName, "image/jpeg") { out ->
            out.write(preparedJpeg)
            if (protocol == MotionPhotoVendorProtocol.SAMSUNG) {
                val dataHeader = buildSamsungDataFieldHeader()
                out.write(dataHeader)
                FileInputStream(videoFile).use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
                val dataBlockLength = dataHeader.size.toLong() + mp4Length
                out.write(buildSamsungSefTrailer(dataBlockLength))
            } else {
                FileInputStream(videoFile).use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
            }
        }
    }

    internal fun buildVendorXmp(
        protocol: MotionPhotoVendorProtocol,
        mp4Length: Long,
    ): String {
        val extraNamespaces = when (protocol) {
            MotionPhotoVendorProtocol.XIAOMI ->
                "\n    xmlns:MiCamera=\"http://ns.xiaomi.com/photos/1.0/camera/\""
            MotionPhotoVendorProtocol.OPLUS ->
                "\n    xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\""
            MotionPhotoVendorProtocol.VIVO ->
                "\n    xmlns:VCamera=\"http://ns.vivo.com/photos/1.0/camera/\""
            MotionPhotoVendorProtocol.SAMSUNG,
            MotionPhotoVendorProtocol.GOOGLE_STANDARD,
            -> ""
        }

        val extraAttrs = when (protocol) {
            MotionPhotoVendorProtocol.XIAOMI ->
                """
    GCamera:MicroVideo="1"
    GCamera:MicroVideoVersion="1"
    GCamera:MicroVideoOffset="$mp4Length"
    GCamera:MicroVideoPresentationTimestampUs="0""""
            MotionPhotoVendorProtocol.OPLUS ->
                """
    OpCamera:MotionPhotoOwner="oplus"
    OpCamera:OLivePhotoVersion="2"
    OpCamera:VideoLength="$mp4Length"
    OpCamera:MotionPhotoPrimaryPresentationTimestampUs="0""""
            MotionPhotoVendorProtocol.VIVO ->
                """
    GCamera:MicroVideo="1"
    GCamera:MicroVideoVersion="1"
    GCamera:MicroVideoOffset="$mp4Length"
    GCamera:MicroVideoPresentationTimestampUs="0""""
            MotionPhotoVendorProtocol.SAMSUNG,
            MotionPhotoVendorProtocol.GOOGLE_STANDARD,
            -> ""
        }

        val extraElements = when (protocol) {
            MotionPhotoVendorProtocol.XIAOMI ->
                "\n   <MiCamera:XMPMeta>Xiaomi/HyperOS LivePhoto</MiCamera:XMPMeta>"
            MotionPhotoVendorProtocol.VIVO ->
                "\n   <VCamera:VMotionPhotoVersion>1</VCamera:VMotionPhotoVersion>\n   <VCamera:VMediaKitVersion>1.0.0.8</VCamera:VMediaKitVersion>"
            MotionPhotoVendorProtocol.OPLUS,
            MotionPhotoVendorProtocol.SAMSUNG,
            MotionPhotoVendorProtocol.GOOGLE_STANDARD,
            -> ""
        }

        val videoPadding = if (protocol == MotionPhotoVendorProtocol.SAMSUNG) SAMSUNG_TRAILER_PADDING else 0L

        return """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"$extraNamespaces
    GCamera:MotionPhoto="1"
    GCamera:MotionPhotoVersion="1"
    GCamera:MotionPhotoPresentationTimestampUs="0"$extraAttrs>$extraElements
   <Container:Directory>
    <rdf:Seq>
     <rdf:li rdf:parseType="Resource">
      <Container:Item
        Item:Mime="image/jpeg"
        Item:Semantic="Primary"
        Item:Length="0"
        Item:Padding="0"/>
     </rdf:li>
     <rdf:li rdf:parseType="Resource">
      <Container:Item
        Item:Mime="video/mp4"
        Item:Semantic="MotionPhoto"
        Item:Length="$mp4Length"
        Item:Padding="$videoPadding"/>
     </rdf:li>
    </rdf:Seq>
   </Container:Directory>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>"""
    }

    internal fun buildSamsungDataFieldHeader(): ByteArray {
        val nameBytes = SAMSUNG_FIELD_NAME.toByteArray(StandardCharsets.US_ASCII)
        return ByteBuffer.allocate(8 + nameBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .putShort(0x0A30.toShort())
            .putInt(nameBytes.size)
            .put(nameBytes)
            .array()
    }

    internal fun buildSamsungSefTrailer(dataBlockLength: Long): ByteArray {
        val clampedLen = dataBlockLength.coerceIn(0L, 0xFFFF_FFFFL).toInt()
        // SEF body between "SEFH" and sefSize: version(4) + count(4) + 1 entry(12) = 20 bytes
        val sefBodySize = 20
        return ByteBuffer.allocate(4 + sefBodySize + 4 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("SEFH".toByteArray(StandardCharsets.US_ASCII))
            .putInt(SAMSUNG_SEF_VERSION)
            .putInt(1)
            .putShort(0)
            .putShort(0x0A30.toShort())
            .putInt(clampedLen)
            .putInt(clampedLen)
            .putInt(sefBodySize)
            .put("SEFT".toByteArray(StandardCharsets.US_ASCII))
            .array()
    }

    internal fun normalizeToCleanJpegBytes(rawBytes: ByteArray): ByteArray? {
        if (rawBytes.size >= 4 && rawBytes[0] == 0xFF.toByte() && rawBytes[1] == 0xD8.toByte()) {
            val stripped = stripXmpAndTrailingBytesFromJpeg(rawBytes)
            if (stripped != null) return stripped
        }
        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) null else out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    internal fun stripXmpAndTrailingBytesFromJpeg(jpegBytes: ByteArray): ByteArray? {
        if (jpegBytes.size < 4 || jpegBytes[0] != 0xFF.toByte() || jpegBytes[1] != 0xD8.toByte()) {
            return null
        }
        val out = ByteArrayOutputStream(jpegBytes.size)
        out.write(0xFF)
        out.write(0xD8)

        var pos = 2
        val headerBytes = XMP_APP1_HEADER.toByteArray(StandardCharsets.US_ASCII)
        while (pos < jpegBytes.size) {
            while (pos < jpegBytes.size && jpegBytes[pos] != 0xFF.toByte()) pos++
            while (pos < jpegBytes.size && jpegBytes[pos] == 0xFF.toByte()) pos++
            if (pos >= jpegBytes.size) break
            val marker = jpegBytes[pos].toInt() and 0xFF
            pos++
            if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD7) {
                out.write(0xFF)
                out.write(marker)
                continue
            }
            if (marker == 0xD9) {
                out.write(0xFF)
                out.write(0xD9)
                return out.toByteArray()
            }
            if (marker == 0xDA) {
                // Start of Scan: copy up to true EOI (FF D9), skipping byte-stuffed FF 00 & RSTn.
                val sosStart = pos - 2
                val eoiEnd = findJpegEoiAfterSos(jpegBytes, sosStart) ?: return null
                out.write(jpegBytes, sosStart, eoiEnd - sosStart)
                return out.toByteArray()
            }
            if (pos + 2 > jpegBytes.size) return null
            val segLen = ((jpegBytes[pos].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 1].toInt() and 0xFF)
            if (segLen < 2 || pos + segLen > jpegBytes.size) return null
            val isXmpApp1 = marker == 0xE1 &&
                segLen >= 2 + headerBytes.size &&
                matchesBytesAt(jpegBytes, pos + 2, headerBytes)
            if (!isXmpApp1) {
                out.write(0xFF)
                out.write(marker)
                out.write(jpegBytes, pos, segLen)
            }
            pos += segLen
        }
        return null
    }

    internal fun injectXmpApp1IntoJpeg(jpegBytes: ByteArray, xmpXml: String): ByteArray? {
        if (jpegBytes.size < 4 || jpegBytes[0] != 0xFF.toByte() || jpegBytes[1] != 0xD8.toByte()) {
            return null
        }
        val headerBytes = XMP_APP1_HEADER.toByteArray(StandardCharsets.US_ASCII)
        val xmpBytes = xmpXml.toByteArray(StandardCharsets.UTF_8)
        val segLen = 2 + headerBytes.size + xmpBytes.size
        if (segLen > 0xFFFF) return null

        // Insert after SOI and any leading APP0 / EXIF APP1 segments.
        var insertAt = 2
        var pos = 2
        while (pos + 4 <= jpegBytes.size) {
            if (jpegBytes[pos] != 0xFF.toByte()) break
            val marker = jpegBytes[pos + 1].toInt() and 0xFF
            if (marker != 0xE0 && marker != 0xE1) break
            val len = ((jpegBytes[pos + 2].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 3].toInt() and 0xFF)
            if (len < 2 || pos + 2 + len > jpegBytes.size) break
            pos += 2 + len
            insertAt = pos
        }

        val out = ByteArrayOutputStream(jpegBytes.size + segLen + 2)
        out.write(jpegBytes, 0, insertAt)
        out.write(0xFF)
        out.write(0xE1)
        out.write((segLen ushr 8) and 0xFF)
        out.write(segLen and 0xFF)
        out.write(headerBytes)
        out.write(xmpBytes)
        out.write(jpegBytes, insertAt, jpegBytes.size - insertAt)
        return out.toByteArray()
    }

    private fun findJpegEoiAfterSos(jpegBytes: ByteArray, sosStart: Int): Int? {
        if (sosStart + 4 > jpegBytes.size) return null
        val sosLen = ((jpegBytes[sosStart + 2].toInt() and 0xFF) shl 8) or
            (jpegBytes[sosStart + 3].toInt() and 0xFF)
        var pos = sosStart + 2 + sosLen
        if (pos > jpegBytes.size) return null
        while (pos < jpegBytes.size - 1) {
            if (jpegBytes[pos] == 0xFF.toByte()) {
                val next = jpegBytes[pos + 1].toInt() and 0xFF
                when {
                    next == 0x00 || next in 0xD0..0xD7 || next == 0xFF -> {
                        pos += if (next == 0xFF) 1 else 2
                    }
                    next == 0xD9 -> return pos + 2
                    else -> pos += 2
                }
            } else {
                pos++
            }
        }
        return null
    }

    private fun matchesBytesAt(haystack: ByteArray, offset: Int, needle: ByteArray): Boolean {
        if (offset < 0 || offset + needle.size > haystack.size) return false
        for (i in needle.indices) {
            if (haystack[offset + i] != needle[i]) return false
        }
        return true
    }

    private fun injectOplusUserComment(context: Context, cleanJpeg: ByteArray): ByteArray {
        val tmp = File(context.cacheDir, "oplus_exif_${System.nanoTime()}.jpg")
        return try {
            tmp.writeBytes(cleanJpeg)
            val exif = ExifInterface(tmp.absolutePath)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, OPLUS_USER_COMMENT)
            exif.saveAttributes()
            tmp.readBytes()
        } catch (_: Throwable) {
            cleanJpeg
        } finally {
            tmp.delete()
        }
    }

    private fun loadStillImageBytes(
        context: Context,
        artworkSource: String,
        motionVideoPath: String?,
    ): ByteArray? {
        val src = artworkSource.trim()
        val fetched = when {
            src.startsWith("http://") || src.startsWith("https://") -> runCatching {
                val req = Request.Builder().url(src).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes()?.takeIf { it.isNotEmpty() } else null
                }
            }.getOrNull()
            src.startsWith("content://") -> runCatching {
                context.contentResolver.openInputStream(Uri.parse(src))?.use { it.readBytes() }
                    ?.takeIf { it.isNotEmpty() }
            }.getOrNull()
            src.startsWith("file://") -> runCatching {
                Uri.parse(src).path?.let(::File)?.takeIf { it.isFile }?.readBytes()
                    ?.takeIf { it.isNotEmpty() }
            }.getOrNull()
            src.isNotEmpty() -> runCatching {
                File(src).takeIf { it.isFile }?.readBytes()?.takeIf { it.isNotEmpty() }
            }.getOrNull()
            else -> null
        }
        if (fetched != null) return fetched

        val videoPath = motionVideoPath?.trim().orEmpty()
        if (videoPath.isEmpty()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            try {
                ByteArrayOutputStream().use { out ->
                    if (frame.compress(Bitmap.CompressFormat.JPEG, 95, out)) out.toByteArray() else null
                }
            } finally {
                frame.recycle()
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun writeImageToMediaStore(
        context: Context,
        displayName: String,
        mimeType: String,
        writer: (OutputStream) -> Unit,
    ): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SAVE_DIR_NAME")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val stream = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                return false
            }
            stream.use { writer(it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    private fun writeVideoToMediaStore(
        context: Context,
        displayName: String,
        writer: (OutputStream) -> Unit,
    ): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$SAVE_DIR_NAME")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val stream = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                return false
            }
            stream.use { writer(it) }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    private fun sanitizeFilePart(name: String, fallback: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').ifBlank { fallback }
}
