package btm.m.auraplayer.tags

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.File

/** YEAR is recording year/date; ORIGINAL_YEAR is the album's original release date. */
enum class AudioTagField(val key: FieldKey) {
    TITLE(FieldKey.TITLE), ARTIST(FieldKey.ARTIST), ALBUM(FieldKey.ALBUM),
    COMPOSER(FieldKey.COMPOSER), LYRICIST(FieldKey.LYRICIST), YEAR(FieldKey.YEAR),
    TRACK(FieldKey.TRACK), ALBUM_ARTIST(FieldKey.ALBUM_ARTIST), RELEASE_DATE(FieldKey.ORIGINAL_YEAR),
    COMMENT(FieldKey.COMMENT), LYRICS(FieldKey.LYRICS);

    val multiline get() = this == COMMENT || this == LYRICS
    fun accepts(value: String) = this != TRACK || value.all { it in '0'..'9' }
}

class TagCover(val bytes: ByteArray, val mime: String, val width: Int = 0, val height: Int = 0) {
    override fun equals(other: Any?) = other is TagCover && mime == other.mime && bytes.contentEquals(other.bytes)
    override fun hashCode() = 31 * bytes.contentHashCode() + mime.hashCode()
}

data class AudioTagDocument(val values: Map<AudioTagField, String>, val cover: TagCover?) {
    operator fun get(field: AudioTagField) = values[field].orEmpty()
    fun edited(field: AudioTagField, value: String): AudioTagDocument {
        require(field.accepts(value)) { "INVALID_TRACK_NUMBER" }
        return copy(values = values + (field to if (field.multiline) value else value.replace('\n', ' ').replace('\r', ' ')))
    }
}

/** Edits only changed fields, retaining other tags, artwork, and the encoded audio payload. */
internal object AudioTagFiles {
    fun read(file: File): AudioTagDocument {
        val tag = AudioFileIO.read(file).tagOrCreateAndSetDefault
        val values = AudioTagField.entries.associateWith { runCatching { tag.getFirst(it.key) }.getOrDefault("") }
        val art = tag.artworkList?.let { list -> list.firstOrNull { it.pictureType == 3 } ?: list.firstOrNull() }
        val cover = art?.binaryData?.takeIf { it.isNotEmpty() }?.let { TagCover(it, art.mimeType.orEmpty(), art.width, art.height) }
        return AudioTagDocument(values, cover)
    }

    fun edit(file: File, original: AudioTagDocument, draft: AudioTagDocument) {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        for (field in AudioTagField.entries) {
            if (draft[field] == original[field]) continue
            require(field.accepts(draft[field])) { "INVALID_TRACK_NUMBER" }
            if (draft[field].isEmpty()) tag.deleteField(field.key) else tag.setField(field.key, draft[field])
        }
        if (draft.cover != original.cover) {
            tag.deleteArtworkField()
            draft.cover?.let { cover ->
                tag.setField(AndroidArtwork().apply {
                    binaryData = cover.bytes; mimeType = cover.mime; pictureType = 3; description = ""
                    width = cover.width; height = cover.height
                })
            }
        }
        audio.commit()
        val saved = read(file)
        // Never publish a file if the container could not represent an edited field.
        for (field in AudioTagField.entries) if (draft[field] != original[field]) {
            check(saved[field] == draft[field]) { "TAG_VERIFICATION_FAILED:$field" }
        }
        if (draft.cover != original.cover) check(saved.cover == draft.cover) { "COVER_VERIFICATION_FAILED" }
    }
}
