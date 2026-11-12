package btm.m.auraplayer.data

internal fun localMusicSearch(songs: List<Song>, request: MusicSearchRequest): CloudPage<MusicSearchHit> {
        val query = request.query
        val hits = mutableListOf<MusicSearchHit>()
        if (request.category in listOf(SearchCategory.ALL, SearchCategory.SONG)) hits += songs.filter {
            "${it.title} ${it.artist} ${it.album}".contains(query, true)
        }.map { MusicSearchHit(it.id, SearchCategory.SONG, it.title, "${it.artist} · ${it.album}", it.artwork, it, cloud = false) }
        if (request.category in listOf(SearchCategory.ALL, SearchCategory.ALBUM)) hits += songs.groupBy { "${it.volume}:${it.albumId}:${it.album}" }.values
            .filter { it.first().album.contains(query, true) }.map { val song = it.first()
                MusicSearchHit(song.albumId, SearchCategory.ALBUM, song.album, song.artist, song.artwork, song, cloud = false) }
        if (request.category in listOf(SearchCategory.ALL, SearchCategory.ARTIST)) hits += songs.groupBy { "${it.volume}:${it.artistId}:${it.artist}" }.values
            .filter { it.first().artist.contains(query, true) }.map { val song = it.first()
                MusicSearchHit(song.artistId, SearchCategory.ARTIST, song.artist, "", song.artwork, song, cloud = false) }
        return CloudPage(hits, hits.size, false)
}
