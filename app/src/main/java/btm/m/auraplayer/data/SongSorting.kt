package btm.m.auraplayer.data

import java.text.Collator

enum class SongSort(val key: String) {
    DEFAULT("default"), TITLE_ASC("title_asc"), TITLE_DESC("title_desc"), ARTIST("artist"), ALBUM("album"),
    YEAR("year"), DATE_ADDED("date_added"), DATE_MODIFIED("date_modified"), COMPOSER("composer"), ALBUM_ARTIST("album_artist"), DURATION("duration");

    fun apply(songs: List<Song>): List<Song> {
        val collator = Collator.getInstance()
        val titles = Comparator<Song> { a, b -> collator.compare(a.title, b.title) }
        val order = when (this) {
            DEFAULT, TITLE_ASC -> titles
            TITLE_DESC -> titles.reversed()
            ARTIST -> Comparator { a, b -> collator.compare(a.artist, b.artist) }
            ALBUM -> Comparator { a, b -> collator.compare(a.album, b.album) }
            COMPOSER -> Comparator { a, b -> collator.compare(a.composer, b.composer) }
            ALBUM_ARTIST -> Comparator { a, b -> collator.compare(a.albumArtist, b.albumArtist) }
            YEAR -> compareByDescending { it.year }
            DATE_ADDED -> compareByDescending { it.added }
            DATE_MODIFIED -> compareByDescending { it.modified }
            DURATION -> compareByDescending { it.duration }
        }
        return songs.sortedWith(order.then(titles).thenBy { it.key })
    }
    companion object { fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: DEFAULT }
}
