package btm.m.auraplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CloudApiException(val status: Int, message: String) : IOException(message)

internal suspend fun <T> cloudSection(fetch: suspend () -> T): T? = try {
    fetch()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

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
        val cacheable = path in setOf("user/playlist", "playlist/track/all", "song/detail", "lyric/new", "album", "artist/songs", "artist/detail", "artist/album")
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

    /** Parses the server's block model. We deliberately do not walk arbitrary JSON: that
     * used to turn account playlists and unrelated nested objects into fake recommendations. */
    suspend fun homeItems(): List<CloudHomeItem> = withContext(Dispatchers.IO) {
        val response = requestBlocking("homepage/block/page")
        val blocks = response.optJSONObject("data")?.optJSONArray("blocks")
            ?: response.optJSONArray("blocks") ?: JSONArray()
        val result = mutableListOf<CloudHomeItem>()
        fun text(o: JSONObject?, key: String): String = o?.optJSONObject(key)?.optString("title").orEmpty()
        fun parseResource(resource: JSONObject, blockCode: String, creativeType: String): CloudHomeItem? {
            val ui = resource.optJSONObject("uiElement") ?: JSONObject()
            val ext = resource.optJSONObject("resourceExtInfo") ?: JSONObject()
            val imageUi = ui.optJSONObject("image")
            val image = imageUi?.optString("imageUrl").orEmpty().ifBlank { ext.optString("picUrl") }
                .ifBlank { ext.optString("coverImgUrl") }.replaceFirst("http://", "https://")
            val title = text(ui, "mainTitle").ifBlank { ext.optString("name") }
            val subtitle = text(ui, "subTitle").ifBlank { ui.optJSONArray("labelTexts")?.optString(0).orEmpty() }
            val id = resource.optLong("resourceId", ext.optLong("id", 0L))
            if (id <= 0 || title.isBlank() || image.isBlank()) return null
            val lower = (blockCode + " " + creativeType + " " + title).lowercase()
            val resourceType = resource.optString("resourceType").lowercase()
            val kind = when {
                resourceType in setOf("playlist", "song", "album") -> resourceType
                ext.has("songData") -> "song"
                lower.contains("album") -> "album"
                lower.contains("playlist") -> "playlist"
                else -> return null
            }
            val section = when {
                kind == "playlist" && (title.contains("雷达") || lower.contains("radar")) -> "radar"
                lower.contains("daily") -> "daily"
                kind == "playlist" -> "recommended"
                else -> ""
            }
            val actionType = when (kind) { "song" -> "song"; "album" -> "album"; else -> "playlist" }
            return CloudHomeItem(id, title, subtitle, image, kind, id, section, actionType, id)
        }
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            val code = block.optString("blockCode")
            val creatives = block.optJSONArray("creatives") ?: continue
            for (j in 0 until creatives.length()) {
                val creative = creatives.optJSONObject(j) ?: continue
                val resources = creative.optJSONArray("resources") ?: JSONArray()
                for (k in 0 until resources.length()) parseResource(resources.optJSONObject(k) ?: continue, code, creative.optString("creativeType"))?.let(result::add)
            }
        }
        result.distinctBy { "${it.section}:${it.kind}:${it.id}" }.take(100)
    }

    /** Personalized playlist recommendations. This is separate from /user/playlist. */
    suspend fun recommendedPlaylists(limit: Int = 30, personalized: Boolean = true): List<CloudHomeItem> = withContext(Dispatchers.IO) {
        val params = mapOf("limit" to "$limit")
        val response = (if (personalized) cloudSection { requestBlocking("recommend/resource", params) } else null)
            ?: requestBlocking("personalized/playlist", params, authenticated = false)
        val rows = response.optJSONArray("recommend")
            ?: response.optJSONObject("result")?.optJSONArray("recommend")
            ?: response.optJSONObject("data")?.optJSONArray("recommend")
            ?: response.optJSONArray("data")
            ?: response.optJSONArray("playlists")
            ?: response.optJSONArray("result")
            ?: response.optJSONObject("result")?.optJSONArray("playlists") ?: JSONArray()
        List(rows.length()) { i ->
            val o = rows.optJSONObject(i) ?: JSONObject()
            val id = o.optLong("id", o.optLong("resourceId", 0L))
            val title = o.optString("name")
            val lower = title.lowercase()
            val section = when {
                title.contains("每日推荐") || lower.contains("daily") -> "daily"
                title.contains("雷达") || lower.contains("radar") -> "radar"
                title.contains("红心") || title.contains("喜欢") -> "feature"
                else -> "recommended"
            }
            val artwork = o.optString("picUrl").ifBlank { o.optString("coverImgUrl") }.replaceFirst("http://", "https://")
            CloudHomeItem(id, title, o.optString("copywriter"), artwork, "playlist", id, section, "playlist", id)
        }.filter { it.id > 0 && it.title.isNotBlank() && it.artwork.isNotBlank() }
    }

    /** Albums belonging to artists the account actually follows. */
    suspend fun followedArtistAlbums(): List<CloudHomeItem> = withContext(Dispatchers.IO) {
        val artists = requestBlocking("artist/sublist", mapOf("limit" to "100", "offset" to "0"))
            .optJSONArray("data") ?: JSONArray()
        val artistsToLoad = (0 until minOf(artists.length(), 20)).mapNotNull { artists.optJSONObject(it)?.optLong("id")?.takeIf { id -> id > 0 } }
        kotlinx.coroutines.coroutineScope {
            artistsToLoad.map { artistId -> async(Dispatchers.IO) {
                try { artistAlbums(artistId, 0).items.take(3) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: IOException) { emptyList() }
            } }.awaitAll().flatten().map { album ->
                CloudHomeItem(album.id, album.title, album.artist, album.artwork.replaceFirst("http://", "https://"), "album", album.id, "followed", "album", album.id)
            }.distinctBy { it.id }.sortedByDescending { it.id }.take(8)
        }
    }

    /** Public, non-personalized desktop discovery banners (the red-tab NetEase home carousel). */
    suspend fun publicBanners(): List<CloudHomeItem> = withContext(Dispatchers.IO) {
        val response = requestBlocking("banner/get", authenticated = false)
        val banners = response.optJSONArray("banners") ?: JSONArray()
        List(banners.length()) { index ->
            val item = banners.getJSONObject(index)
            val actionType = when (item.optInt("targetType")) { 10 -> "playlist"; 1 -> "song"; 100 -> "album"; else -> "" }
            CloudHomeItem(
                id = item.optLong("targetId", index.toLong()),
                title = item.optString("typeTitle", "网易云音乐"),
                subtitle = item.optString("url"),
                artwork = item.optString("pic").replaceFirst("http://", "https://"),
                kind = "banner",
                actionType = actionType,
                actionId = item.optLong("targetId", index.toLong()),
            )
        }.filter { it.artwork.isNotBlank() }
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
    suspend fun songDetail(id: Long): Song? = parseSongs(request("song/detail", mapOf("ids" to "$id")).optJSONArray("songs") ?: JSONArray()).firstOrNull()
    suspend fun dailySongs(refresh: Boolean = false): List<Song> {
        val response = request("recommend/songs", if (refresh) mapOf("afresh" to "true") else emptyMap())
        val rows = response.optJSONObject("data")?.optJSONArray("dailySongs") ?: response.optJSONArray("recommend") ?: JSONArray()
        return parseSongs(rows).distinctBy { it.id }
    }
    suspend fun discoverySongs(limit: Int = 50): List<Song> {
        val rows = request("personalized/newsong", mapOf("limit" to "$limit"), authenticated = false)
            .optJSONArray("result") ?: JSONArray()
        val songs = JSONArray()
        for (index in 0 until rows.length()) {
            rows.optJSONObject(index)?.optJSONObject("song")?.let(songs::put)
        }
        return parseSongs(songs).distinctBy { it.id }
    }
    suspend fun heartbeatSongs(playlistId: Long, seedId: Long): List<Song> {
        val rows = request("playmode/intelligence/list", mapOf("id" to "$seedId", "pid" to "$playlistId", "count" to "30"))
            .optJSONArray("data") ?: JSONArray()
        val ids = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            row.optLong("id", row.optJSONObject("songInfo")?.optLong("id") ?: 0).takeIf { it > 0 }
        }.distinct()
        if (ids.isEmpty()) return emptyList()
        val details = request("song/detail", mapOf("ids" to ids.joinToString(",")))
        val songs = parseSongs(details.optJSONArray("songs") ?: JSONArray()).associateBy { it.id }
        return ids.mapNotNull(songs::get)
    }
    suspend fun personalFmSongs(): List<Song> {
        val rows = request("personal_fm").optJSONArray("data") ?: JSONArray()
        val ids = (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.optLong("id")?.takeIf { it > 0 }
        }.distinct()
        if (ids.isEmpty()) return emptyList()
        val details = request("song/detail", mapOf("ids" to ids.joinToString(",")))
        val songs = parseSongs(details.optJSONArray("songs") ?: JSONArray()).associateBy { it.id }
        return ids.mapNotNull(songs::get)
    }
    suspend fun follows(uid: Long, offset: Int = 0, limit: Int = 30): CloudPage<CloudFollow> {
        val response = request("user/follows", mapOf("uid" to "$uid", "offset" to "$offset", "limit" to "$limit"))
        val rows = response.optJSONArray("follow") ?: JSONArray()
        val items = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optLong("userId").takeIf { it > 0 } ?: return@mapNotNull null
            CloudFollow(id, row.optString("nickname"), row.optString("avatarUrl"),
                row.optString("signature").takeUnless { it == "null" }.orEmpty())
        }
        return CloudPage(items, offset + rows.length(), rows.length() > 0 && response.optBoolean("more", rows.length() == limit))
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
    data class RecentPlayEntry(val song: Song, val playTimeMs: Long)

    suspend fun recentPlayEntries(limit: Int = 50): List<RecentPlayEntry> {
        val response = request("record/recent/song", mapOf("limit" to "${limit.coerceIn(1, 300)}"))
        val rows = response.optJSONObject("data")?.optJSONArray("list")
            ?: response.optJSONArray("list")
            ?: JSONArray()
        val songs = JSONArray()
        val playTimes = mutableListOf<Long>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val resourceType = row.optString("resourceType")
            if (resourceType.isNotBlank() && !resourceType.equals("SONG", ignoreCase = true)) continue
            val songJson = row.optJSONObject("data")
                ?: row.optJSONObject("song")
                ?: row.takeIf { it.has("id") && it.has("name") }
                ?: continue
            if (songJson.optLong("id") <= 0L) continue
            songs.put(songJson)
            playTimes += row.optLong("playTime", 0L)
        }
        val parsed = parseSongs(songs)
        val seen = HashSet<Long>()
        return buildList {
            for (index in parsed.indices) {
                val song = parsed[index]
                if (seen.add(song.id)) {
                    add(RecentPlayEntry(song, playTimes.getOrElse(index) { 0L }))
                }
            }
        }
    }

    suspend fun recentSongs(limit: Int = 50): List<Song> = recentPlayEntries(limit).map { it.song }

    suspend fun scrobble(
        songId: Long,
        sourceId: Long,
        playedSec: Int,
        totalSec: Int,
        title: String = "",
        artist: String = "",
        bitrateKbps: Int = 320,
        level: String = "exhigh",
        completed: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        if (songId <= 0L || cookie().isBlank()) return@withContext false
        val safePlayed = playedSec.coerceAtLeast(1)
        val safeTotal = totalSec.coerceAtLeast(safePlayed)
        val effectiveSourceId = if (sourceId > 0L) sourceId.toString() else songId.toString()
        val endReason = if (completed) "playend" else "interrupt"
        val v1Params = buildMap {
            put("id", songId.toString())
            put("time", safePlayed.toString())
            put("total", safeTotal.toString())
            put("sourceid", effectiveSourceId)
            put("source", "list")
            put("bitrate", bitrateKbps.coerceAtLeast(128).toString())
            put("level", level.ifBlank { "exhigh" })
            put("end", endReason)
            if (title.isNotBlank()) put("name", title)
            if (artist.isNotBlank()) put("artist", artist)
        }
        val v1Ok = runCatching {
            requestBlocking("scrobble/v1", v1Params, authenticated = true)
            true
        }.getOrDefault(false)
        if (v1Ok) {
            // In official mode, also notify startplay so /record/recent/song updates immediately
            // without double-counting play/duration metrics.
            if (endpoint().isBlank()) {
                runCatching {
                    requestBlocking(
                        "scrobble/start",
                        mapOf("id" to songId.toString(), "sourceid" to effectiveSourceId),
                        authenticated = true,
                    )
                }
            }
            return@withContext true
        }
        runCatching {
            requestBlocking(
                "scrobble",
                mapOf(
                    "id" to songId.toString(),
                    "sourceid" to effectiveSourceId,
                    "time" to safePlayed.toString(),
                    "end" to endReason,
                ),
                authenticated = true,
            )
            true
        }.getOrDefault(false)
    }

    suspend fun listenDataToday(): JSONObject = request("listen/data/today/song")
    suspend fun listenDataRealtimeReport(type: String = "week"): JSONObject =
        request("listen/data/realtime/report", mapOf("type" to type))
    suspend fun listenDataReport(type: String = "week"): JSONObject =
        request("listen/data/report", mapOf("type" to type))
    suspend fun listenDataSongPlayRank(type: String = "week"): JSONObject =
        request("listen/data/song/play/rank", mapOf("type" to type))
    suspend fun listenDataYearReport(): JSONObject = request("listen/data/year/report")
    suspend fun listenDataTotal(): JSONObject = request("listen/data/total")

    suspend fun listeningRank(uid: Long, type: Int = 0): List<Song> {
        val response = request("user/record", mapOf("uid" to "$uid", "type" to "$type"))
        val rows = response.optJSONArray(if (type == 1) "weekData" else "allData") ?: JSONArray()
        val songs = JSONArray()
        val counts = mutableMapOf<Long, Int>()
        for (i in 0 until rows.length()) rows.getJSONObject(i).let { row ->
            row.optJSONObject("song")?.let { song -> songs.put(song); counts[song.optLong("id")] = row.optInt("playCount", row.optInt("playedCount")) }
        }
        return parseSongs(songs).map { it.copy(playCount = counts[it.id] ?: 0) }.distinctBy { it.id }
    }
    private fun parseAlbums(items: JSONArray, defaultArtistId: Long = 0L): List<CatalogAlbum> = List(items.length()) { i ->
        val album = items.getJSONObject(i)
        val artist = album.optJSONObject("artist") ?: album.optJSONArray("artists")?.optJSONObject(0)
        val embeddedTracks = album.optJSONArray("songs")?.let(::parseSongs).orEmpty()
        CatalogAlbum(
            id = album.getLong("id"),
            title = album.optString("name"),
            artwork = album.optString("picUrl"),
            artistId = artist?.optLong("id") ?: defaultArtistId,
            artist = artist?.optString("name").orEmpty(),
            published = album.optLong("publishTime"),
            count = album.optInt("size").takeIf { it > 0 } ?: embeddedTracks.size,
            type = album.optString("type"),
            subType = album.optString("subType"),
            tracks = embeddedTracks,
        )
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
    suspend fun albumTracks(id: Long): List<Song> {
        val response = request("album", mapOf("id" to "$id"))
        return parseSongs(response.optJSONArray("songs") ?: JSONArray())
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
        val data = detail.optJSONObject("data")
        val artist = data?.optJSONObject("artist") ?: detail.optJSONObject("artist") ?: response.optJSONObject("artist") ?: JSONObject()
        val artistName = artist.optString("name")
        val catalogInfo = if (offset == 0) {
            CatalogInfo(
                artistId = id,
                artist = artistName,
                description = artist.optString("briefDesc"),
                albums = optionalArtistAlbums(id),
                artistAliases = parseArtistAliases(artist, artistName),
                artistIdentity = data?.optJSONObject("identify")?.optString("imageDesc").orEmpty().trim(),
                artistSongCount = artist.optInt("musicSize", 0).coerceAtLeast(0),
                artistAlbumCount = artist.optInt("albumSize", 0).coerceAtLeast(0),
            )
        } else null
        return CloudCatalogPage(artistName, artist.optString("cover", artist.optString("picUrl")),
            CloudPage(tracks, offset + tracks.size, tracks.isNotEmpty() && response.optBoolean("more", tracks.size == 50),
                response.optInt("total").takeIf { response.has("total") }),
            catalogInfo)
    }

    private fun parseArtistAliases(artist: JSONObject, artistName: String): List<String> {
        val seen = HashSet<String>().apply {
            val normalizedName = artistName.trim().lowercase()
            if (normalizedName.isNotEmpty()) add(normalizedName)
        }
        return buildList {
            for (field in listOf("transNames", "alias")) {
                val array = artist.optJSONArray(field) ?: continue
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotEmpty() && seen.add(value.lowercase())) add(value)
                }
            }
        }
    }
    suspend fun artistArtwork(id: Long): String {
        val response = request("artist/detail", mapOf("id" to "$id"))
        val artist = response.optJSONObject("data")?.optJSONObject("artist")
            ?: response.optJSONObject("artist") ?: JSONObject()
        return artist.optString("cover", artist.optString("picUrl"))
    }
    suspend fun artistFollowed(id: Long): Boolean {
        val response = request("artist/detail/dynamic", mapOf("id" to "$id"))
        return response.optBoolean("followed", response.optJSONObject("data")?.optBoolean("followed", false) ?: false)
    }
    suspend fun followArtist(id: Long, follow: Boolean) {
        request("artist/sub", mapOf("id" to "$id", "t" to if (follow) "1" else "0"))
    }
    suspend fun songArtists(id: Long): List<SongArtist> =
        parseSongs(request("song/detail", mapOf("ids" to "$id")).optJSONArray("songs") ?: JSONArray()).firstOrNull()?.artists.orEmpty()

    suspend fun songCredits(songId: Long, fallback: Song? = null): SongCreditsData = withContext(Dispatchers.IO) {
        kotlinx.coroutines.coroutineScope {
            val lyricJob = async { request("lyric/new", mapOf("id" to "$songId")) }
            val detailJob = async {
                try {
                    request("song/detail", mapOf("ids" to "$songId"))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: IOException) {
                    null
                }
            }
            val lyricJson = lyricJob.await()
            val detailJson = detailJob.await()
            val detailSongObj = detailJson?.optJSONArray("songs")?.optJSONObject(0)
            val detailPublishTime = detailSongObj?.optLong("publishTime", 0L) ?: 0L
            val albumId = detailSongObj?.optJSONObject("al")?.optLong("id", 0L)
                ?: detailSongObj?.optJSONObject("album")?.optLong("id", 0L)
                ?: fallback?.albumId
                ?: 0L
            val albumPublishTime = if (detailPublishTime <= 0L && albumId > 0L) {
                try {
                    request("album", mapOf("id" to "$albumId")).optJSONObject("album")?.optLong("publishTime", 0L) ?: 0L
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: IOException) {
                    0L
                }
            } else {
                0L
            }

            val base = SongCreditsParser.parse(
                songId = songId,
                lyricJson = lyricJson,
                detailJson = detailJson,
                fallbackSong = fallback,
                albumPublishTimeMs = albumPublishTime,
            )

            val missingAvatarIds = (base.songwriting + base.production)
                .filter { it.avatarUrl.isBlank() && it.artistId > 0L }
                .map { it.artistId }
                .distinct()
            if (missingAvatarIds.isEmpty()) {
                return@coroutineScope base
            }
            val fetchedAvatars = missingAvatarIds.map { artistId ->
                async {
                    artistId to try {
                        artistArtwork(artistId).replaceFirst("http://", "https://")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: IOException) {
                        ""
                    }
                }
            }.awaitAll().toMap()

            fun hydrate(list: List<SongCreditPerson>): List<SongCreditPerson> = list.map { person ->
                if (person.avatarUrl.isBlank() && person.artistId > 0L) {
                    val resolved = fetchedAvatars[person.artistId].orEmpty()
                    if (resolved.isNotBlank()) person.copy(avatarUrl = resolved) else person
                } else {
                    person
                }
            }

            base.copy(
                songwriting = hydrate(base.songwriting),
                production = hydrate(base.production),
            )
        }
    }
    private suspend fun optionalArtistAlbums(id: Long): List<CatalogAlbum> = try {
        val response = request("artist/album", mapOf("id" to "$id", "offset" to "0", "limit" to "30"))
        parseAlbums(response.optJSONArray("hotAlbums") ?: JSONArray(), defaultArtistId = id)
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
