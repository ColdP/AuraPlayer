package btm.m.auraplayer.data

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long = 0,
    val artistId: Long = 0,
    val duration: Long = 0,
    val uri: String,
    val artwork: String = "",
    val relativePath: String = "",
    val added: Long = 0,
    val track: Int = 0,
    val cloud: Boolean = false,
    val volume: String = "external_primary",
    val year: Int = 0,
    val modified: Long = 0,
    val composer: String = "",
    val albumArtist: String = "",
) { val key: String get() = if (cloud) "cloud:$id" else "local:$uri" }

data class MusicCollection(val key: String, val title: String, val subtitle: String, val songs: List<Song>, val artwork: String = "")
data class LocalPlaylist(val id: String, val name: String, val songKeys: List<String>)
data class MusicDirectory(val title: String, val collections: List<MusicCollection>, val artists: Boolean = false)

data class CloudPlaylist(val id: Long, val name: String, val count: Int, val artwork: String)
data class CloudAccount(val userId: Long, val nickname: String, val avatar: String)

/** Paths are MediaStore RELATIVE_PATH values, including a final slash. */
object LibraryFilter {
    fun normalize(path: String): String = path.replace('\\', '/').trim('/').let { if (it.isEmpty()) "" else "$it/" }
    fun accepts(path: String, duration: Long, white: Boolean, blocked: Set<String>, filterDuration: Boolean, minimumSeconds: Int, volume: String = "external_primary"): Boolean {
        val normalized = normalize(path)
        if (filterDuration && duration < minimumSeconds.coerceIn(0, 60) * 1000L) return false
        if (white) return normalized.startsWith("Music/", ignoreCase = true)
        return blocked.none { entry ->
            val targetVolume = entry.substringBefore(':', "")
            val targetPath = normalize(if (':' in entry) entry.substringAfter(':') else entry)
            (targetVolume.isEmpty() || targetVolume.equals(volume, ignoreCase = true)) &&
                targetPath.isNotEmpty() && normalized.startsWith(targetPath, ignoreCase = true)
        }
    }
}
