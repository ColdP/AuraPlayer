package btm.m.auraplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CloudApiException(val status: Int, message: String) : IOException(message)

/** Uses official NetEase endpoints by default, or an optional user-configured Enhanced API service. */
class NeteaseApi(
    private val endpoint: () -> String,
    private val cookie: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).build(),
    private val metadataCache: CloudMetadataCache? = null,
) {
    private val official = OfficialNeteaseClient(cookie, client, metadataCache = metadataCache)
    private fun cacheScope() = CloudMetadataCache.digest(endpoint() + "\n" + cookie())
    fun clearMetadataCache() { metadataCache?.clear() }
    companion object {
        fun normalizeEndpoint(value: String): String {
            val url = value.trim().trimEnd('/').toHttpUrl()
            val octets = url.host.split('.').mapNotNull(String::toIntOrNull)
            val privateIpv4 = octets.size == 4 && octets.all { it in 0..255 } &&
                (octets[0] == 10 || octets[0] == 127 || octets[0] == 192 && octets[1] == 168 || octets[0] == 172 && octets[1] in 16..31)
            require(url.scheme == "https" || url.host == "localhost" || url.host == "::1" || privateIpv4) { "HTTPS_REQUIRED" }
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "INVALID_ENDPOINT" }
            return url.toString().trimEnd('/')
        }
    }

    fun requestBlocking(path: String, values: Map<String, String> = emptyMap(), authenticated: Boolean = true): JSONObject {
        val cacheable = path in setOf("user/playlist", "playlist/track/all", "song/detail", "album", "artist/songs", "artist/detail", "artist/album")
        val key = cacheScope() + "/" + path + "/" + (values["uid"] ?: values["id"]).orEmpty() + "/" +
            values.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        if (cacheable) metadataCache?.read(key)?.let { return it }
        return try {
            requestNetwork(path, values, authenticated).also { if (cacheable) metadataCache?.write(key, it) }
        } catch (e: IOException) {
            // HTTP/authentication failures must stay visible; only a transport outage uses stale data.
            if (cacheable && e !is CloudApiException) metadataCache?.read(key, stale = true)?.let { return it }
            throw e
        }
    }
    private fun requestNetwork(path: String, values: Map<String, String>, authenticated: Boolean): JSONObject {
        val base = endpoint().takeIf { it.isNotBlank() }
            ?: return official.request(path, values, authenticated)
        val url = (normalizeEndpoint(base) + "/" + path.trimStart('/')).toHttpUrl()
        val body = FormBody.Builder().apply {
            add("timestamp", System.currentTimeMillis().toString())
            values.forEach { (key, value) -> add(key, value) }
            if (authenticated) cookie().takeIf(String::isNotBlank)?.let { add("cookie", it) }
        }.build()
        val request = Request.Builder().url(url).post(body).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw CloudApiException(response.code, "HTTP ${response.code}")
            val json = try { JSONObject(response.body?.string() ?: "{}") } catch (_: Exception) { throw IOException("INVALID_RESPONSE") }
            val code = json.optInt("code", 200)
            if (code != 200) throw CloudApiException(code, json.optString("message", json.optString("msg", "API $code")))
            return json
        }
    }
    private suspend fun request(path: String, values: Map<String, String> = emptyMap(), authenticated: Boolean = true) =
        withContext(Dispatchers.IO) { requestBlocking(path, values, authenticated) }

    suspend fun sendCaptcha(phone: String, countryCode: String) {
        request("captcha/sent", mapOf("phone" to phone, "ctcode" to countryCode), authenticated = false)
    }
    suspend fun login(phone: String, countryCode: String, captcha: String): Pair<String, CloudAccount> {
        val response = request("login/cellphone", mapOf("phone" to phone, "countrycode" to countryCode, "captcha" to captcha), false)
        val session = response.optString("cookie").takeIf { it.isNotBlank() } ?: throw IOException("NO_SESSION")
        val profile = response.optJSONObject("profile") ?: throw IOException("NO_PROFILE")
        return session to parseAccount(profile)
    }
    suspend fun account(): CloudAccount {
        val response = request("login/status")
        val data = response.optJSONObject("data") ?: response
        val profile = data.optJSONObject("profile") ?: throw CloudApiException(301, "SESSION_EXPIRED")
        return parseAccount(profile)
    }
    suspend fun logout() { request("logout") }
    suspend fun playlists(uid: Long, offset: Int = 0, limit: Int = 30, refresh: Boolean = false): CloudPage<CloudPlaylist> {
        require(offset >= 0 && limit in 1..100)
        if (refresh) withContext(Dispatchers.IO) { metadataCache?.removePrefix(cacheScope() + "/user/playlist/$uid/") }
        val result = mutableListOf<CloudPlaylist>()
        val response = request("user/playlist", mapOf("uid" to "$uid", "limit" to "$limit", "offset" to "$offset"))
        val items = response.optJSONArray("playlist") ?: JSONArray()
        for (i in 0 until items.length()) items.getJSONObject(i).let {
            result += CloudPlaylist(it.getLong("id"), it.optString("name"), it.optInt("trackCount"), it.optString("coverImgUrl"), it.optInt("specialType"), it.optJSONObject("creator")?.optLong("userId") ?: 0L)
        }
        return CloudPage(result.distinctBy { it.id }, offset + items.length(),
            items.length() > 0 && response.optBoolean("more", items.length() == limit))
    }
    suspend fun playlistSongs(id: Long, offset: Int = 0, limit: Int = 50, refresh: Boolean = false): CloudPage<Song> {
        require(offset >= 0 && limit in 1..100)
        if (refresh) withContext(Dispatchers.IO) {
            metadataCache?.removePrefix(cacheScope() + "/playlist/track/all/$id/")
            official.invalidatePlaylist(id)
        }
        val response = request("playlist/track/all", mapOf("id" to "$id", "limit" to "$limit", "offset" to "$offset"))
        val items = response.optJSONArray("songs") ?: JSONArray()
        // Advance through source IDs, even if some tracks are unavailable or duplicated.
        val nextOffset = response.optInt("nextOffset", offset + items.length())
        return CloudPage(parseSongs(items).distinctBy { it.id }, nextOffset,
            nextOffset > offset && response.optBoolean("more", items.length() == limit),
            response.optInt("total").takeIf { response.has("total") })
    }
    suspend fun search(query: String, offset: Int = 0): List<Song> {
        val response = request("cloudsearch", mapOf("keywords" to query, "type" to "1", "limit" to "50", "offset" to "$offset"))
        return parseSongs(response.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray())
    }
    fun invalidatePlaylist(id: Long) {
        metadataCache?.removePrefix(cacheScope() + "/playlist/track/all/$id/")
        official.invalidatePlaylist(id)
    }
    suspend fun likedSongIds(uid: Long): Set<Long> {
        val ids = request("likelist", mapOf("uid" to "$uid")).optJSONArray("ids") ?: JSONArray()
        return (0 until ids.length()).map { ids.getLong(it) }.toSet()
    }
    suspend fun likeSong(id: Long, liked: Boolean) { request("like", mapOf("id" to "$id", "like" to "$liked")) }
    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
        request("playlist/tracks", mapOf("op" to "add", "pid" to "$playlistId", "tracks" to "$songId"))
        invalidatePlaylist(playlistId)
    }
    suspend fun listeningRank(uid: Long): List<Song> {
        val response = request("user/record", mapOf("uid" to "$uid", "type" to "0"))
        val rows = response.optJSONArray("allData") ?: JSONArray()
        val songs = JSONArray()
        for (i in 0 until rows.length()) rows.getJSONObject(i).optJSONObject("song")?.let(songs::put)
        return parseSongs(songs)
    }
    private fun parseAlbums(items: JSONArray): List<CatalogAlbum> = List(items.length()) { i ->
        val album = items.getJSONObject(i)
        val artist = album.optJSONObject("artist") ?: album.optJSONArray("artists")?.optJSONObject(0)
        CatalogAlbum(album.getLong("id"), album.optString("name"), album.optString("picUrl"), artist?.optLong("id") ?: 0L,
            artist?.optString("name").orEmpty(), album.optLong("publishTime"), album.optInt("size"))
    }
    suspend fun subscribedAlbums(offset: Int = 0): CloudPage<CatalogAlbum> {
        val response = request("album/sublist", mapOf("offset" to "$offset", "limit" to "25"))
        val albums = parseAlbums(response.optJSONArray("data") ?: JSONArray())
        return CloudPage(albums, offset + albums.size, albums.isNotEmpty() && response.optBoolean("hasMore", albums.size == 25))
    }
    suspend fun artistAlbums(id: Long, offset: Int = 0): CloudPage<CatalogAlbum> {
        val response = request("artist/album", mapOf("id" to "$id", "offset" to "$offset", "limit" to "30"))
        val albums = parseAlbums(response.optJSONArray("hotAlbums") ?: JSONArray())
        return CloudPage(albums, offset + albums.size, albums.isNotEmpty() && response.optBoolean("more", albums.size == 30))
    }
    suspend fun searchCategory(query: String, category: SearchCategory, offset: Int = 0): CloudPage<MusicSearchHit> {
        if (category == SearchCategory.ALL) {
            val pages = kotlinx.coroutines.coroutineScope {
                listOf(SearchCategory.SONG, SearchCategory.ALBUM, SearchCategory.ARTIST).map { type ->
                    async { searchCategory(query, type, 0) }
                }.map { it.await() }
            }
            return CloudPage(pages.flatMap { it.items.take(6) }, 0, false)
        }
        val response = request(if (category == SearchCategory.VOICE) "search" else "cloudsearch",
            mapOf("keywords" to query, "type" to "${category.apiType}", "limit" to "50", "offset" to "$offset"))
        val result = response.optJSONObject("result") ?: response.optJSONObject("data") ?: JSONObject()
        val hits = when (category) {
            SearchCategory.SONG, SearchCategory.LYRICS -> parseSongs(result.optJSONArray("songs") ?: JSONArray()).map { song ->
                MusicSearchHit(song.id, category, song.title, song.artist + " · " + song.album, song.artwork, song) }
            SearchCategory.ALBUM -> parseAlbums(result.optJSONArray("albums") ?: JSONArray()).map {
                MusicSearchHit(it.id, category, it.title, it.artist, it.artwork, artistId = it.artistId) }
            SearchCategory.ARTIST -> {
                val items = result.optJSONArray("artists") ?: JSONArray()
                List(items.length()) { i -> val a = items.getJSONObject(i)
                    MusicSearchHit(a.getLong("id"), category, a.optString("name"), "", a.optString("picUrl")) }
            }
            SearchCategory.BLOG -> {
                val items = result.optJSONArray("djRadios") ?: JSONArray()
                List(items.length()) { i -> val a = items.getJSONObject(i)
                    MusicSearchHit(a.getLong("id"), category, a.optString("name"), a.optJSONObject("dj")?.optString("nickname").orEmpty(), a.optString("picUrl")) }
            }
            SearchCategory.VOICE -> {
                val items = result.optJSONArray("resources") ?: JSONArray()
                List(items.length()) { i -> val a = items.getJSONObject(i); val b = a.optJSONObject("baseInfo") ?: a
                    MusicSearchHit(b.optLong("id", a.optLong("resourceId")), category, b.optString("name"),
                        b.optJSONObject("dj")?.optString("nickname").orEmpty(), b.optString("coverUrl")) }
            }
            else -> emptyList()
        }
        return CloudPage(hits, offset + hits.size, hits.isNotEmpty() && result.optBoolean("hasMore", hits.size == 50))
    }
    private fun programSong(program: JSONObject): Song? {
        val song = program.optJSONObject("mainSong") ?: return null
        return parseSongs(JSONArray().put(song)).firstOrNull()?.copy(title = program.optString("name", song.optString("name")),
            artist = program.optJSONObject("dj")?.optString("nickname").orEmpty(), artwork = program.optString("coverUrl"))
    }
    suspend fun voiceSong(id: Long): Song? = programSong(request("dj/program/detail", mapOf("id" to "$id")).optJSONObject("program") ?: JSONObject())
    suspend fun radioPrograms(id: Long, offset: Int): CloudPage<Song> {
        val response = request("dj/program", mapOf("rid" to "$id", "offset" to "$offset", "limit" to "50"))
        val items = response.optJSONArray("programs") ?: JSONArray()
        val tracks = (0 until items.length()).mapNotNull { programSong(items.getJSONObject(it)) }
        return CloudPage(tracks, offset + items.length(), items.length() > 0 && response.optBoolean("more", items.length() == 50))
    }
    suspend fun availableQualities(id: Long): List<btm.m.auraplayer.playback.StreamQuality> {
        return qualityDetails(id).map { it.quality }
    }
    suspend fun qualityDetails(id: Long): List<btm.m.auraplayer.playback.CloudQuality> {
        val response = request("song/music/detail", mapOf("id" to "$id"))
        val data = response.optJSONObject("data") ?: response.optJSONArray("data")?.optJSONObject(0)
        val qualityArray = data?.optJSONArray("qualities") ?: response.optJSONArray("qualities")
        val fields = mapOf("standard" to "l", "higher" to "m", "exhigh" to "h", "lossless" to "sq", "hires" to "hr",
            "jyeffect" to "je", "sky" to "sk", "dolby" to "db", "vivid" to "vi", "jymaster" to "jm")
        fun detail(quality: btm.m.auraplayer.playback.StreamQuality, info: JSONObject) =
            btm.m.auraplayer.playback.CloudQuality(quality, btm.m.auraplayer.playback.AudioDetails(
                info.optString("type", info.optString("format", "")).uppercase(),
                info.optInt("sr", info.optInt("sampleRate")), info.optInt("bitDepth", info.optInt("bits")),
                info.optInt("br", info.optInt("bitrate"))))
        val result = if (data == null) emptyList() else btm.m.auraplayer.playback.StreamQuality.entries.mapNotNull { quality ->
            val info = data.optJSONObject(fields[quality.level] ?: "") ?: data.optJSONObject(quality.level)
                ?: return@mapNotNull null
            detail(quality, info)
        }
        if (result.isNotEmpty()) return result
        if (qualityArray != null) {
            val arrayResult = (0 until qualityArray.length()).mapNotNull { index ->
                val info = qualityArray.optJSONObject(index) ?: return@mapNotNull null
                val level = info.optString("level", info.optString("quality")).lowercase()
                val quality = btm.m.auraplayer.playback.StreamQuality.entries.firstOrNull { it.level == level }
                    ?: return@mapNotNull null
                detail(quality, info)
            }
            if (arrayResult.isNotEmpty()) return arrayResult
        }
        val single = data?.takeIf { it.has("level") || it.has("quality") }
            ?: response.takeIf { it.has("level") || it.has("quality") }
        val level = single?.optString("level", single.optString("quality")).orEmpty().lowercase()
        val quality = btm.m.auraplayer.playback.StreamQuality.entries.firstOrNull { it.level == level }
        return if (quality != null && single != null) listOf(detail(quality, single)) else emptyList()
    }
    suspend fun albumPage(id: Long): CloudCatalogPage {
        val response = request("album", mapOf("id" to "$id"))
        val album = response.optJSONObject("album") ?: JSONObject()
        val tracks = parseSongs(response.optJSONArray("songs") ?: JSONArray())
        val artist = album.optJSONObject("artist") ?: album.optJSONArray("artists")?.optJSONObject(0) ?: JSONObject()
        val artistId = artist.optLong("id", tracks.firstOrNull()?.artistId ?: 0L)
        val related = if (artistId > 0) optionalArtistAlbums(artistId).filterNot { it.id == id } else emptyList()
        return CloudCatalogPage(album.optString("name"), album.optString("picUrl"), CloudPage(tracks, tracks.size, false, tracks.size),
            CatalogInfo(artistId, artist.optString("name", tracks.firstOrNull()?.artist.orEmpty()), album.optLong("publishTime"),
                album.optString("company"), album.optString("description"), related))
    }
    suspend fun artistPage(id: Long, offset: Int = 0): CloudCatalogPage {
        val response = request("artist/songs", mapOf("id" to "$id", "limit" to "50", "offset" to "$offset", "order" to "hot"))
        val tracks = parseSongs(response.optJSONArray("songs") ?: JSONArray())
        val detail = if (offset == 0) try { request("artist/detail", mapOf("id" to "$id")) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: IOException) { JSONObject() } else JSONObject()
        val artist = detail.optJSONObject("data")?.optJSONObject("artist") ?: detail.optJSONObject("artist") ?: response.optJSONObject("artist") ?: JSONObject()
        return CloudCatalogPage(artist.optString("name"), artist.optString("cover", artist.optString("picUrl")),
            CloudPage(tracks, offset + tracks.size, tracks.isNotEmpty() && response.optBoolean("more", tracks.size == 50),
                response.optInt("total").takeIf { response.has("total") }),
            if (offset == 0) CatalogInfo(id, artist.optString("name"), description = artist.optString("briefDesc"), albums = optionalArtistAlbums(id)) else null)
    }
    suspend fun artistArtwork(id: Long): String {
        val response = request("artist/detail", mapOf("id" to "$id"))
        val artist = response.optJSONObject("data")?.optJSONObject("artist")
            ?: response.optJSONObject("artist") ?: JSONObject()
        return artist.optString("cover", artist.optString("picUrl"))
    }
    suspend fun songArtists(id: Long): List<SongArtist> =
        parseSongs(request("song/detail", mapOf("ids" to "$id")).optJSONArray("songs") ?: JSONArray()).firstOrNull()?.artists.orEmpty()
    private suspend fun optionalArtistAlbums(id: Long): List<CatalogAlbum> = try {
        val response = request("artist/album", mapOf("id" to "$id", "offset" to "0", "limit" to "12"))
        val albums = response.optJSONArray("hotAlbums") ?: JSONArray()
        List(albums.length()) { i -> val album = albums.getJSONObject(i); val artist = album.optJSONObject("artist")
            CatalogAlbum(album.getLong("id"), album.optString("name"), album.optString("picUrl"), artist?.optLong("id") ?: id,
                artist?.optString("name").orEmpty(), album.optLong("publishTime"), album.optInt("size")) }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: IOException) { emptyList() }
    suspend fun downloadSource(id: Long, quality: btm.m.auraplayer.playback.StreamQuality): btm.m.auraplayer.playback.DownloadSource {
        val response = request("song/download/url/v1", mapOf("id" to "$id", "level" to quality.level))
        val data = response.optJSONObject("data") ?: throw IOException("DOWNLOAD_UNAVAILABLE")
        val url = data.optString("url").takeIf { it.isNotBlank() && it != "null" } ?: throw IOException("DOWNLOAD_UNAVAILABLE")
        val parsed = url.toHttpUrl()
        val actual = data.optString("level", quality.level)
        require(actual == quality.level) { "DOWNLOAD_QUALITY_UNAVAILABLE" }
        return btm.m.auraplayer.playback.DownloadSource(parsed.newBuilder().scheme("https").build().toString(), data.optString("type"), quality)
    }
    fun resolveBlocking(id: Long, level: String = "standard"): String {
        val response = requestBlocking("song/url/v1", mapOf("id" to "$id", "level" to level, "unblock" to "false"))
        val song = response.optJSONArray("data")?.optJSONObject(0) ?: throw IOException("UNAVAILABLE_SONG")
        btm.m.auraplayer.playback.PlaybackStreams.records[id] = btm.m.auraplayer.playback.StreamInfo(
            song.optString("level", level), song.optString("type"), song.optInt("br"), song.optInt("sr"), song.optInt("bitDepth", song.optInt("bits")))
        val url = song.optString("url").takeIf { it.isNotBlank() && it != "null" } ?: throw IOException("UNAVAILABLE_SONG")
        val parsed = url.toHttpUrl()
        // NetEase occasionally returns HTTP CDN URLs. HTTPS is supported by its CDN.
        return parsed.newBuilder().scheme("https").build().toString()
    }
    private fun parseAccount(profile: JSONObject) = CloudAccount(profile.getLong("userId"), profile.optString("nickname"), profile.optString("avatarUrl"))
    internal fun parseSongs(array: JSONArray): List<Song> = List(array.length()) { i ->
        val o = array.getJSONObject(i)
        val artistData = o.optJSONArray("ar") ?: o.optJSONArray("artists") ?: JSONArray()
        val artists = List(artistData.length()) { index -> artistData.getJSONObject(index).let {
            SongArtist(it.optLong("id"), it.optString("name"), it.optString("picUrl").takeUnless { url -> url == "null" }.orEmpty())
        } }.filter { it.name.isNotBlank() }
        val album = o.optJSONObject("al") ?: o.optJSONObject("album") ?: JSONObject()
        val id = o.getLong("id")
        Song(id, o.optString("name"), artists.joinToString(" / ") { it.name },
            album.optString("name"), album.optLong("id"), artists.firstOrNull()?.id ?: 0,
            o.optLong("dt", o.optLong("duration")), "aura://netease/$id", album.optString("picUrl"), cloud = true, artists = artists)
    }
}
