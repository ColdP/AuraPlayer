package btm.m.auraplayer.download

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.*
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.*
import btm.m.auraplayer.R
import btm.m.auraplayer.data.Song
import btm.m.auraplayer.playback.DownloadSource
import btm.m.auraplayer.playback.PlaybackAudioCache
import btm.m.auraplayer.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** DownloadManager owns network transfer; WorkManager durably owns conversion, tagging and publishing. */
object MusicDownloads {
    private fun prefs(context: Context) = context.getSharedPreferences("aura.downloads", Context.MODE_PRIVATE)
    internal fun completedPreferences(context: Context) = context.getSharedPreferences("aura.downloaded", Context.MODE_PRIVATE)

    fun isDownloaded(context: Context, song: Song, quality: btm.m.auraplayer.playback.StreamQuality): Boolean =
        completedPreferences(context).contains("${song.id}:${quality.level}")

    fun downloadedSongs(context: Context): List<Song> = completedPreferences(context).all.values.mapNotNull { value ->
        runCatching {
            val o = JSONObject(value as String)
            Song(o.getLong("id"), o.optString("title"), o.optString("artist"), o.optString("album"),
                o.optLong("albumId"), o.optLong("artistId"), o.optLong("duration"), o.optString("uri"),
                o.optString("artwork"), relativePath = "Music/Aura Player/", cloud = false,
                artists = btm.m.auraplayer.data.decodeSongArtists(o.optString("artists")))
        }.getOrNull()
    }.distinctBy { it.key }

    internal fun markDownloaded(context: Context, song: Song, quality: String, publishedUri: String) {
        check(completedPreferences(context).edit().putString("${song.id}:$quality", JSONObject().apply {
            put("id", song.id); put("title", song.title); put("artist", song.artist); put("album", song.album)
            put("albumId", song.albumId); put("artistId", song.artistId); put("duration", song.duration)
            put("uri", publishedUri); put("artwork", song.artwork); put("artists", btm.m.auraplayer.data.encodeSongArtists(song.artists))
        }.toString()).commit())
    }

    internal fun markDownloaded(context: Context, info: JSONObject, publishedUri: String) {
        markDownloaded(context, Song(info.getLong("id"), info.optString("title"), info.optString("artist"), info.optString("album"),
            info.optLong("albumId"), info.optLong("artistId"), info.optLong("duration"), publishedUri, info.optString("artwork"),
            artists = btm.m.auraplayer.data.decodeSongArtists(info.optString("artists"))), info.optString("quality"), publishedUri)
    }
    fun enqueue(context: Context, song: Song, source: DownloadSource): Long {
        val cacheKey = PlaybackAudioCache.key(song.id, source.quality)
        val cachedId = -System.currentTimeMillis()
        val cached = File(context.cacheDir, "download-cache-$cachedId.source")
        if (PlaybackAudioCache.copyIfFullyCached(context, cacheKey, cached)) {
            val record = downloadRecord(song, source, cached.name, cachedSource = cached.absolutePath)
            check(prefs(context).edit().putString("$cachedId", record.toString()).commit())
            schedule(context, cachedId)
            return cachedId
        }
        val name = "aura-${song.id}-${System.currentTimeMillis()}.source"
        val manager = context.getSystemService(DownloadManager::class.java)
        val request = DownloadManager.Request(Uri.parse(source.url))
            .setTitle(song.title).setDescription(song.artist)
            .addRequestHeader("Referer", "https://music.163.com/")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
        val id = manager.enqueue(request)
        val record = downloadRecord(song, source, name)
        if (!prefs(context).edit().putString("$id", record.toString()).commit()) {
            manager.remove(id); error("DOWNLOAD_SAVE_FAILED")
        }
        schedule(context, id, delay = 10)
        return id
    }
    fun schedule(context: Context, id: Long, delay: Long = 0) {
        if (!prefs(context).contains("$id")) return
        val request = OneTimeWorkRequestBuilder<MusicDownloadWorker>().setInputData(workDataOf("downloadId" to id))
            .setInitialDelay(delay, TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("music-download-$id", ExistingWorkPolicy.KEEP, request)
    }
    fun resume(context: Context) { prefs(context).all.keys.mapNotNull(String::toLongOrNull).forEach { schedule(context, it) } }
    internal fun record(context: Context, id: Long): JSONObject? = prefs(context).getString("$id", null)?.let(::JSONObject)
    internal fun save(context: Context, id: Long, value: JSONObject) { check(prefs(context).edit().putString("$id", value.toString()).commit()) }
    internal fun remove(context: Context, id: Long) { prefs(context).edit().remove("$id").commit() }

    private fun downloadRecord(song: Song, source: DownloadSource, name: String, cachedSource: String? = null) =
        JSONObject().put("id", song.id).put("title", song.title).put("artist", song.artist)
            .put("album", song.album).put("albumId", song.albumId).put("artistId", song.artistId).put("duration", song.duration)
            .put("artwork", song.artwork).put("track", song.track).put("year", song.year)
            .put("artists", btm.m.auraplayer.data.encodeSongArtists(song.artists))
            .put("quality", source.quality.level).put("codec", source.codec).put("name", name)
            .put("cachedSource", cachedSource ?: "").put("started", System.currentTimeMillis())
}

class DownloadCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) MusicDownloads.schedule(context, intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1))
    }
}

class MusicDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val id = inputData.getLong("downloadId", -1)
        val info = MusicDownloads.record(context, id) ?: return@withContext Result.success()
        val manager = context.getSystemService(DownloadManager::class.java)
        val cachedSource = info.optString("cachedSource").takeIf(String::isNotBlank)
        val status = if (cachedSource != null) DownloadManager.STATUS_SUCCESSFUL else manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
        }
        if (status in setOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)) return@withContext Result.retry()
        val source = cachedSource?.let(::File) ?: File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), info.getString("name"))
        val decoded = File(context.cacheDir, "download-$id.decoded")
        val tagged = File(context.cacheDir, "download-$id.tagged")
        var published = info.optString("published").takeIf { it.isNotBlank() }?.let(Uri::parse)
        try {
            // If the process died after publishing, the persisted URI makes completion idempotent.
            if (published != null) {
                val pending = context.contentResolver.query(published, arrayOf(MediaStore.Audio.Media.IS_PENDING), null, null, null)?.use {
                    if (it.moveToFirst()) it.getInt(0) else null
                }
                if (pending == 0) {
                    MusicDownloads.markDownloaded(context, info, published.toString())
                    source.delete(); decoded.delete(); tagged.delete(); MusicDownloads.remove(context, id)
                    return@withContext Result.success()
                }
                context.contentResolver.delete(published, null, null); published = null
            }
            check(status == DownloadManager.STATUS_SUCCESSFUL && source.isFile) { "DOWNLOAD_FAILED" }
            val audio = AudioFileTools.decode(source, decoded, MusicTags(info.optString("title"), info.optString("artist"), info.optString("album"), info.optInt("track"), info.optInt("year")))
            val cover = audio.cover.takeIf { it.isNotEmpty() } ?: loadCover(info.optString("artwork"))
            AudioFileTools.writeTags(audio, tagged, cover)
            val title = "${audio.tags.artist} - ${audio.tags.title}".replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(150)
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "$title.${audio.codec}")
                put(MediaStore.Audio.Media.MIME_TYPE, if (audio.codec == "flac") "audio/flac" else "audio/mpeg")
                put(MediaStore.Audio.Media.TITLE, audio.tags.title); put(MediaStore.Audio.Media.ARTIST, audio.tags.artist)
                put(MediaStore.Audio.Media.ALBUM, audio.tags.album)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Aura Player/"); put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("MEDIASTORE_INSERT_FAILED")
            published = uri
            MusicDownloads.save(context, id, info.put("published", uri.toString()))
            context.contentResolver.openOutputStream(uri, "w")!!.use { output -> tagged.inputStream().use { it.copyTo(output) } }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            published = null // The completed library entry must survive later cleanup/notification failures.
            MusicDownloads.markDownloaded(context, info, uri.toString())
            notify(context, id, info.optString("title"), tr("download_complete", "下载完成，已保存到 Music/Aura Player"), uri,
                if (audio.codec == "flac") "audio/flac" else "audio/mpeg")
            source.delete(); MusicDownloads.remove(context, id)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            published?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            android.util.Log.e("AuraDownload", "Download conversion failed ($id)", e)
            notify(context, id, info.optString("title"), tr("download_failed", "下载或处理失败，请重新选择音质下载"))
            source.delete(); MusicDownloads.remove(context, id)
            Result.failure()
        } finally { decoded.delete(); tagged.delete() }
    }
    private fun loadCover(url: String): ByteArray {
        if (!url.startsWith("https://")) return byteArrayOf()
        return runCatching {
            OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build().newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use byteArrayOf()
                val input = response.body?.byteStream() ?: return@use byteArrayOf()
                input.use { it.readNBytes(8 * 1024 * 1024 + 1).takeIf { bytes -> bytes.size <= 8 * 1024 * 1024 } ?: byteArrayOf() }
            }
        }.getOrDefault(byteArrayOf())
    }
    private fun notify(context: Context, id: Long, title: String, text: String, uri: Uri? = null, mime: String? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("music-downloads", tr("downloads", "音乐下载"), NotificationManager.IMPORTANCE_DEFAULT))
        runCatching {
            val notification = NotificationCompat.Builder(context, "music-downloads").setSmallIcon(R.drawable.ic_aura)
                .setContentTitle(title).setContentText(text).setAutoCancel(true)
            if (uri != null) notification.setContentIntent(android.app.PendingIntent.getActivity(context, id.toInt(),
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), android.app.PendingIntent.FLAG_IMMUTABLE))
            manager.notify(id.toInt(), notification.build())
        }
    }
}
