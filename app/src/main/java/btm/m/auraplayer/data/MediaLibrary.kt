package btm.m.auraplayer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import java.text.Collator
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media as Audio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class MediaLibrary(private val context: Context) {
    suspend fun scan(settings: Appearance): List<Song> = withContext(Dispatchers.IO) {
        val columns = arrayOf(Audio._ID, Audio.TITLE, Audio.ARTIST, Audio.ALBUM, Audio.ALBUM_ID, Audio.ARTIST_ID, Audio.DURATION,
            Audio.RELATIVE_PATH, Audio.DATE_ADDED, Audio.TRACK, Audio.VOLUME_NAME, Audio.YEAR, Audio.DATE_MODIFIED, Audio.COMPOSER, Audio.ALBUM_ARTIST)
        val songs = mutableListOf<Song>()
        // Retro Music queries the merged external collection, including removable storage.
        // MediaProvider validates SQL sort tokens and rejects COLLATE LOCALIZED on some ROMs.
        val collection = Audio.getContentUri(MediaStore.VOLUME_EXTERNAL)
        context.contentResolver.query(collection, columns, "${Audio.IS_MUSIC} != 0 AND ${Audio.IS_PENDING} = 0 AND ${Audio.IS_TRASHED} = 0", null,
                null)?.use { cursor ->
                val ix = columns.map(cursor::getColumnIndexOrThrow)
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val volume = cursor.getString(ix[10]) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                    val songCollection = Audio.getContentUri(volume)
                    val duration = cursor.getLong(ix[6])
                    val path = cursor.getString(ix[7]).orEmpty()
                    if (!LibraryFilter.accepts(path, duration, settings.whitelist, settings.blacklist, settings.durationFilter, settings.minimumSeconds, volume)) continue
                    val id = cursor.getLong(ix[0])
                    val albumId = cursor.getLong(ix[4])
                    songs += Song(id, cursor.getString(ix[1]).orEmpty(), cursor.getString(ix[2]).orEmpty(),
                        cursor.getString(ix[3]).orEmpty(), albumId, cursor.getLong(ix[5]), duration,
                        ContentUris.withAppendedId(songCollection, id).toString(),
                        ContentUris.withAppendedId(Uri.parse("content://media/$volume/audio/albumart"), albumId).toString(),
                        LibraryFilter.normalize(path), cursor.getLong(ix[8]), cursor.getInt(ix[9]), volume = volume,
                        year = cursor.getInt(ix[11]), modified = cursor.getLong(ix[12]),
                        composer = cursor.getString(ix[13]).orEmpty(), albumArtist = cursor.getString(ix[14]).orEmpty())
                }
            }
        val collator = Collator.getInstance()
        songs.sortedWith { first, second -> collator.compare(first.title, second.title) }
    }
}
