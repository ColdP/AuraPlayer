package btm.m.auraplayer.data

import org.json.JSONArray
import org.json.JSONObject

data class SongArtist(val id: Long, val name: String, val artwork: String = "")

/** Keep credits separate from the display string, which may contain slashes in artist names. */
fun Song.creditedArtists(): List<SongArtist> = artists.ifEmpty {
    val names = artist.split(Regex("\\s+/\\s+|\\s*[;；、]\\s*")).map(String::trim).filter(String::isNotBlank)
    names.map { name -> SongArtist(if (names.size == 1) artistId else 0, name) }
}

fun encodeSongArtists(artists: List<SongArtist>): String = JSONArray().apply {
    artists.forEach { artist -> put(JSONObject().put("id", artist.id).put("name", artist.name).put("artwork", artist.artwork)) }
}.toString()

fun decodeSongArtists(raw: String?): List<SongArtist> = runCatching {
    val array = JSONArray(raw ?: "[]")
    List(array.length()) { index -> array.getJSONObject(index).let {
        SongArtist(it.optLong("id"), it.optString("name"), it.optString("artwork"))
    } }.filter { it.name.isNotBlank() }
}.getOrDefault(emptyList())
