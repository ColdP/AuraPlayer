package btm.m.auraplayer.tags

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import btm.m.auraplayer.data.Song
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume

internal class TagEditSession(val uri: Uri, val directory: File, val originalFile: File,
                              var original: AudioTagDocument, val path: String) : AutoCloseable {
    override fun close() { directory.deleteRecursively() }
}

internal class PreparedTagEdit(val session: TagEditSession, val file: File, val document: AudioTagDocument) : AutoCloseable {
    override fun close() { file.delete() }
}

/** Same cache-copy approach as Retro Music, with validation before touching MediaStore. */
internal object LocalAudioTags {
    private fun source(context: Context, song: Song): Pair<File, String> {
        require(!song.cloud) { "LOCAL_AUDIO_ONLY" }
        val uri = Uri.parse(song.uri)
        var name = ""; var path = ""
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, MediaStore.Audio.Media.DATA), null, null, null)?.use {
            if (it.moveToFirst()) { name = it.getString(0).orEmpty(); path = it.getString(1).orEmpty() }
        }
        if (uri.scheme == "file") { path = uri.path.orEmpty(); name = File(path).name }
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension.matches(Regex("[a-z0-9]{2,5}")) && extension in setOf("mp3", "flac", "m4a", "mp4", "ogg", "opus", "wav", "aif", "aiff", "wma")) { "UNSUPPORTED_TAG_FORMAT" }
        val directory = File(context.cacheDir, "tag-editor/${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        val file = File(directory, "original.$extension")
        try {
            (context.contentResolver.openInputStream(uri) ?: throw IOException("AUDIO_UNREADABLE")).use { input ->
                file.outputStream().buffered().use { input.copyTo(it) }
            }
            return file to path
        } catch (e: Exception) { directory.deleteRecursively(); throw e }
    }

    suspend fun load(context: Context, song: Song): TagEditSession {
        var loaded: TagEditSession? = null
        try {
            return withContext(Dispatchers.IO) {
                val (file, path) = source(context, song)
                try { TagEditSession(Uri.parse(song.uri), file.parentFile!!, file, AudioTagFiles.read(file), path).also { loaded = it } }
                catch (e: Exception) { file.parentFile!!.deleteRecursively(); throw e }
            }
        } catch (e: CancellationException) { loaded?.close(); throw e }
    }

    suspend fun prepare(session: TagEditSession, draft: AudioTagDocument): PreparedTagEdit {
        val file = File(session.directory, "edited-${java.util.UUID.randomUUID()}.${session.originalFile.extension}")
        try {
            return withContext(Dispatchers.IO) {
                session.originalFile.copyTo(file)
                AudioTagFiles.edit(file, session.original, draft)
                PreparedTagEdit(session, file, draft)
            }
        } catch (e: Exception) { file.delete(); throw e }
    }

    suspend fun publish(context: Context, edit: PreparedTagEdit) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // Opening rw checks write permission without truncating the user's file.
        resolver.openFileDescriptor(edit.session.uri, "rw")?.use { descriptor ->
            val current = resolver.openInputStream(edit.session.uri)?.use { digest(it) } ?: error("AUDIO_UNREADABLE")
            check(current.contentEquals(edit.session.originalFile.inputStream().use { digest(it) })) { "AUDIO_CHANGED_EXTERNALLY" }
            withContext(NonCancellable) {
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    try {
                        output.channel.position(0)
                        edit.file.inputStream().use { it.copyTo(output) }
                        output.channel.truncate(edit.file.length()); output.channel.force(true)
                    } catch (failure: Exception) {
                        // Retain the original copy until the entire publish succeeds.
                        runCatching {
                            output.channel.position(0)
                            edit.session.originalFile.inputStream().use { it.copyTo(output) }
                            output.channel.truncate(edit.session.originalFile.length()); output.channel.force(true)
                        }.onFailure { failure.addSuppressed(it) }
                        throw failure
                    }
                }
            }
        } ?: error("AUDIO_UNWRITABLE")
        edit.file.copyTo(edit.session.originalFile, overwrite = true)
        edit.session.original = edit.document
        if (edit.session.path.isNotBlank()) withTimeoutOrNull(6000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                MediaScannerConnection.scanFile(context, arrayOf(edit.session.path), null) { _, _ ->
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        } else resolver.notifyChange(edit.session.uri, null)
    }

    private fun digest(input: java.io.InputStream): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        return hash.digest()
    }

    suspend fun importCover(context: Context, uri: Uri): TagCover = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(32 * 1024 * 1024 + 1) } ?: error("IMAGE_UNREADABLE")
        require(bytes.size <= 32 * 1024 * 1024) { "IMAGE_TOO_LARGE" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth <= 20_000 && bounds.outHeight <= 20_000 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 200_000_000) { "INVALID_IMAGE" }
        var sample = 1
        while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("INVALID_IMAGE")
        try {
            val output = java.io.ByteArrayOutputStream()
            check(image.compress(Bitmap.CompressFormat.JPEG, 94, output))
            TagCover(output.toByteArray(), "image/jpeg", image.width, image.height)
        } finally { image.recycle() }
    }

    /** Called on the lyrics worker; read tags first so edits take effect before online lookup. */
    fun embeddedLyrics(context: Context, song: Song): String {
        if (song.cloud) return ""
        val path = context.contentResolver.query(Uri.parse(song.uri), arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        val direct = path?.let(::File)?.takeIf { it.isFile && it.canRead() }
        if (direct != null) return AudioTagFiles.read(direct)[AudioTagField.LYRICS]
        val (file, _) = source(context, song)
        try { return AudioTagFiles.read(file)[AudioTagField.LYRICS] }
        finally { file.parentFile!!.deleteRecursively() }
    }
}
