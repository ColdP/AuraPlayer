package btm.m.auraplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Provider ids stored on [Song.provider] / [CloudPlaylist.provider]. */
const val PROVIDER_QQ = "qq"

/** True for NetEase-backed cloud songs (the original, default cloud provider). */
val Song.netease: Boolean get() = cloud && provider != PROVIDER_QQ
val Song.isQq: Boolean get() = cloud && provider == PROVIDER_QQ
fun Song.qqMid(): String = uri.substringAfter("aura://qqmusic/", "").substringBefore('?').trim('/')

/**
 * Uses official QQ Music endpoints by default (via [OfficialQqClient]), or an optional
 * user-configured jsososo/QQMusicApi service (https://github.com/jsososo/QQMusicApi).
 */
class QqMusicApi(
    private val endpoint: () -> String,
    private val uin: () -> String,
    private val cookie: () -> String,
    private val client: OkHttpClient = CloudHttpClient.api,
    private val metadataCache: CloudMetadataCache? = null,
) {
    private val official = OfficialQqClient(cookie, uin, client)
    val usingCustomEndpoint: Boolean get() = endpoint().isNotBlank()
    val configured: Boolean get() = true

    private data class CachedQqUrl(
        val url: String,
        val info: btm.m.auraplayer.playback.StreamInfo,
        val expiresAtMs: Long,
    )

    companion object {
        internal fun parseTrackQualities(trackOrFile: JSONObject, intervalSec: Long = 0L): List<btm.m.auraplayer.playback.CloudQuality> {
            val file = trackOrFile.optJSONObject("file") ?: trackOrFile
            val durationSec = trackOrFile.optLong("interval", intervalSec).coerceAtLeast(0L)
            fun estBitrate(bytes: Long): Int =
                if (bytes > 0L && durationSec > 0L) ((bytes * 8L) / durationSec).toInt() else 0
            val size128 = file.optLong("size_128mp3", file.optLong("size_128", 0L))
            val size96aac = file.optLong("size_96aac", file.optLong("size_48aac", 0L))
            val size192ogg = file.optLong("size_192ogg", 0L)
            val size192aac = file.optLong("size_192aac", 0L)
            val size320 = file.optLong("size_320mp3", file.optLong("size_320", 0L))
            val sizeFlac = file.optLong("size_flac", 0L)
            val sizeApe = file.optLong("size_ape", 0L)
            val sizeHires = file.optLong("size_hires", 0L)
            val sizeNew = file.optJSONArray("size_new")
            val sizeMaster = sizeNew?.optLong(0, 0L) ?: 0L
            val sizeSpatial = sizeNew?.optLong(1, 0L) ?: 0L
            val sizeVivid = sizeNew?.optLong(2, 0L) ?: 0L
            return buildList {
                if (size128 > 0L) {
                    add(btm.m.auraplayer.playback.CloudQuality(
                        btm.m.auraplayer.playback.StreamQuality.STANDARD,
                        btm.m.auraplayer.playback.AudioDetails("MP3", 0, 0, 128_000),
                    ))
                } else if (size96aac > 0L) {
                    add(btm.m.auraplayer.playback.CloudQuality(
                        btm.m.auraplayer.playback.StreamQuality.STANDARD,
                        btm.m.auraplayer.playback.AudioDetails("AAC", 0, 0, 96_000),
                    ))
                }
                if (size192ogg > 0L) {
                    add(btm.m.auraplayer.playback.CloudQuality(
                        btm.m.auraplayer.playback.StreamQuality.HIGHER,
                        btm.m.auraplayer.playback.AudioDetails("OGG", 0, 0, 192_000),
                    ))
                } else if (size192aac > 0L) {
                    add(btm.m.auraplayer.playback.CloudQuality(
                        btm.m.auraplayer.playback.StreamQuality.HIGHER,
                        btm.m.auraplayer.playback.AudioDetails("AAC", 0, 0, 192_000),
                    ))
                }
                if (size320 > 0L) {
                    add(btm.m.auraplayer.playback.CloudQuality(
                        btm.m.auraplayer.playback.StreamQuality.HIGH,
                        btm.m.auraplayer.playback.AudioDetails("MP3", 0, 0, 320_000),
                    ))
                }
                if (sizeFlac > 0L) {
                    add(btm.m.auraplayer.playback.CloudQuality(
                        btm.m.auraplayer.playback.StreamQuality.LOSSLESS,
                        btm.m.auraplayer.playback.AudioDetails("FLAC", 0, 0, estBitrate(sizeFlac)),
                    ))
                } else if (sizeApe > 0L) {
                    add(btm.m.auraplayer.playback.CloudQuality(
                        btm.m.auraplayer.playback.StreamQuality.LOSSLESS,
                        btm.m.auraplayer.playback.AudioDetails("APE", 0, 0, estBitrate(sizeApe)),
                    ))
                }
                if (sizeHires > 0L) {
                    add(btm.m.auraplayer.playback.CloudQuality(
                        btm.m.auraplayer.playback.StreamQuality.HIRES,
                        btm.m.auraplayer.playback.AudioDetails("FLAC", 0, 0, estBitrate(sizeHires)),
                    ))
                }
                if (sizeSpatial > 0L) {
                    add(btm.m.auraplayer.playback.CloudQuality(
                        btm.m.auraplayer.playback.StreamQuality.SPATIAL,
                        btm.m.auraplayer.playback.AudioDetails("FLAC", 0, 0, estBitrate(sizeSpatial)),
                    ))
                }
                if (sizeVivid > 0L) {
                    add(btm.m.auraplayer.playback.CloudQuality(
                        btm.m.auraplayer.playback.StreamQuality.VIVID,
                        btm.m.auraplayer.playback.AudioDetails("FLAC", 0, 0, estBitrate(sizeVivid)),
                    ))
                }
                if (sizeMaster > 0L) {
                    add(btm.m.auraplayer.playback.CloudQuality(
                        btm.m.auraplayer.playback.StreamQuality.MASTER,
                        btm.m.auraplayer.playback.AudioDetails("FLAC", 0, 0, estBitrate(sizeMaster)),
                    ))
                }
            }
        }

        internal fun streamInfoFromUrl(
            urlOrPurl: String,
            fallbackLevel: String,
            details: List<btm.m.auraplayer.playback.CloudQuality> = emptyList(),
        ): btm.m.auraplayer.playback.StreamInfo {
            val fileName = urlOrPurl.substringBefore('?').substringAfterLast('/').uppercase()
            val (detectedLevel, codec, baseBitrate) = when {
                fileName.startsWith("RS01") -> Triple("hires", "FLAC", 0)
                fileName.startsWith("AI00") -> Triple("jymaster", "FLAC", 0)
                fileName.startsWith("Q000") -> Triple("jyeffect", "FLAC", 0)
                fileName.startsWith("Q001") || fileName.startsWith("Q003") -> Triple("vivid", "FLAC", 0)
                fileName.startsWith("F000") -> Triple("lossless", "FLAC", 0)
                fileName.startsWith("A000") -> Triple("lossless", "APE", 0)
                fileName.startsWith("M800") -> Triple("exhigh", "MP3", 320_000)
                fileName.startsWith("O600") -> Triple("higher", "OGG", 192_000)
                fileName.startsWith("C600") -> Triple("higher", "AAC", 192_000)
                fileName.startsWith("M500") -> Triple("standard", "MP3", 128_000)
                fileName.startsWith("C400") -> Triple("standard", "AAC", 96_000)
                fileName.startsWith("C200") -> Triple("standard", "AAC", 48_000)
                else -> {
                    val ext = fileName.substringAfterLast('.', "")
                    val guessedCodec = when (ext) {
                        "FLAC" -> "FLAC"
                        "MP3" -> "MP3"
                        "M4A" -> "AAC"
                        "OGG" -> "OGG"
                        "APE" -> "APE"
                        else -> ""
                    }
                    Triple(fallbackLevel, guessedCodec, 0)
                }
            }
            val detail = details.firstOrNull { it.quality.level == detectedLevel }
            val bitrate = if (baseBitrate > 0) baseBitrate else (detail?.audio?.bitrate ?: 0)
            val sampleRate = detail?.audio?.sampleRate ?: 0
            val bits = detail?.audio?.bits ?: 0
            return btm.m.auraplayer.playback.StreamInfo(
                level = detectedLevel,
                codec = codec.ifBlank { detail?.audio?.codec.orEmpty() },
                bitrate = bitrate,
                sampleRate = sampleRate,
                bits = bits,
            )
        }
    }

    private val resolvedUrlCache = object : LinkedHashMap<String, CachedQqUrl>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedQqUrl>?) = size > 128
    }

    private fun cacheScope(): String =
        "qq/" + CloudMetadataCache.digest(endpoint() + "\n" + uin() + "\n" + cookie())

    private inline fun cachedJson(
        subKey: String,
        ttlMs: Long,
        refresh: Boolean = false,
        fetch: () -> JSONObject,
    ): JSONObject {
        val fullKey = "${cacheScope()}/$subKey"
        if (!refresh) {
            metadataCache?.read(fullKey, ttlMs)?.let { return it }
        }
        return try {
            fetch().also { metadataCache?.write(fullKey, it) }
        } catch (e: IOException) {
            if (e !is CloudApiException) {
                metadataCache?.read(fullKey, ttlMs, stale = true)?.let { return it }
            }
            throw e
        }
    }

    fun requestBlocking(path: String, values: Map<String, String> = emptyMap()): JSONObject {
        val base = endpoint().takeIf { it.isNotBlank() } ?: throw CloudApiException(0, "QQ_ENDPOINT_MISSING")
        val builder = (NeteaseApi.normalizeEndpoint(base) + "/" + path.trimStart('/')).toHttpUrl().newBuilder()
        values.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        val request = Request.Builder().url(builder.build()).get().header("Accept", "application/json")
            .apply { cookie().takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw CloudApiException(response.code, "HTTP ${response.code}")
            val json = try { JSONObject(response.body?.string() ?: "{}") } catch (_: Exception) { throw IOException("INVALID_RESPONSE") }
            val code = json.optInt("result", 100)
            if (code != 100) throw CloudApiException(code, json.optString("errMsg", json.optString("message", "API $code")))
            return json
        }
    }

    private suspend fun request(path: String, values: Map<String, String> = emptyMap()) =
        withContext(Dispatchers.IO) { requestBlocking(path, values) }

    fun userUin(): String {
        val explicit = QqSession.normalizeUin(uin())
        if (explicit.isNotEmpty()) return explicit
        return QqSession(cookie()).uin()
    }

    suspend fun account(): CloudAccount = withContext(Dispatchers.IO) {
        if (!usingCustomEndpoint) return@withContext official.account()
        val id = userUin()
        if (id.isBlank()) throw CloudApiException(301, "LOGIN_REQUIRED")
        val detail = runCatching { requestBlocking("user/detail", mapOf("id" to id)) }.getOrNull()
        val creator = detail?.optJSONObject("data")?.optJSONObject("creator")
        val uid = id.toLongOrNull() ?: 0L
        CloudAccount(
            uid,
            creator?.optString("nick")?.takeIf { it.isNotBlank() } ?: "QQ $id",
            creator?.optString("headpic").orEmpty()
                .ifBlank { creator?.optString("logo").orEmpty() }
                .ifBlank { if (uid > 0L) "https://q.qlogo.cn/g?b=qq&nk=$uid&s=640" else "" }
                .https(),
        )
    }

    /** [offset] is a zero-based page index; the returned nextOffset is the following page index. */
    suspend fun search(query: String, offset: Int = 0, pageSize: Int = 30): CloudPage<Song> = withContext(Dispatchers.IO) {
        if (!usingCustomEndpoint) return@withContext official.search(query, offset, pageSize, ::parseSongs)
        val page = offset + 1
        val data = request("search", mapOf("key" to query, "pageNo" to "$page", "pageSize" to "$pageSize", "t" to "0")).optJSONObject("data")
        val songs = parseSongs(data?.optJSONArray("list") ?: JSONArray())
        val total = data?.optJSONObject("pageInfo")?.optInt("total") ?: data?.optInt("total") ?: 0
        CloudPage(songs, page, songs.isNotEmpty() && (total == 0 || page * pageSize < total), total.takeIf { it > 0 })
    }

    suspend fun playlists(refresh: Boolean = false): CloudPage<CloudPlaylist> = withContext(Dispatchers.IO) {
        if (!usingCustomEndpoint) {
            val id = userUin()
            if (id.isBlank() || id == "0") return@withContext CloudPage(emptyList(), 0, false, 0)
            val cacheKey = "${cacheScope()}/official-playlists/$id"
            if (!refresh) {
                metadataCache?.read(cacheKey, CloudMetadataCache.FRESH_MS)?.let { cached ->
                    val arr = cached.optJSONArray("items") ?: JSONArray()
                    val items = List(arr.length()) { i ->
                        val o = arr.getJSONObject(i)
                        CloudPlaylist(o.getLong("id"), o.optString("name"), o.optInt("count"), o.optString("artwork"), o.optInt("specialType"), provider = PROVIDER_QQ)
                    }
                    return@withContext CloudPage(items, items.size, false, items.size)
                }
            }
            return@withContext official.playlists().also { page ->
                if (page.items.isNotEmpty()) {
                    val arr = JSONArray().apply {
                        page.items.forEach { p ->
                            put(JSONObject().put("id", p.id).put("name", p.name).put("count", p.count).put("artwork", p.artwork).put("specialType", p.specialType))
                        }
                    }
                    metadataCache?.write(cacheKey, JSONObject().put("items", arr))
                }
            }
        }
        val id = userUin()
        if (id.isBlank()) return@withContext CloudPage(emptyList(), 0, false, 0)
        val list = cachedJson("user/songlist/$id", CloudMetadataCache.FRESH_MS, refresh) {
            requestBlocking("user/songlist", mapOf("id" to id))
        }.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val items = List(list.length()) { i ->
            val o = list.getJSONObject(i)
            CloudPlaylist(o.optLong("tid", o.optLong("dissid")), o.optString("diss_name", o.optString("dissname")),
                o.optInt("song_cnt", o.optInt("songnum")), o.optString("diss_cover", o.optString("imgurl")).https(),
                provider = PROVIDER_QQ)
        }.filter { it.id > 0 }
        CloudPage(items, items.size, false, items.size)
    }

    suspend fun recommendedPlaylists(refresh: Boolean = false): List<CloudPlaylist> = withContext(Dispatchers.IO) {
        if (!usingCustomEndpoint) {
            val cacheKey = "${cacheScope()}/official-recommended"
            if (!refresh) {
                metadataCache?.read(cacheKey, CloudMetadataCache.FRESH_MS)?.let { cached ->
                    val arr = cached.optJSONArray("items") ?: JSONArray()
                    return@withContext List(arr.length()) { i ->
                        val o = arr.getJSONObject(i)
                        CloudPlaylist(o.getLong("id"), o.optString("name"), o.optInt("count"), o.optString("artwork"), provider = PROVIDER_QQ)
                    }
                }
            }
            return@withContext official.recommendedPlaylists().also { items ->
                if (items.isNotEmpty()) {
                    val arr = JSONArray().apply {
                        items.forEach { p ->
                            put(JSONObject().put("id", p.id).put("name", p.name).put("count", p.count).put("artwork", p.artwork))
                        }
                    }
                    metadataCache?.write(cacheKey, JSONObject().put("items", arr))
                }
            }
        }
        val list = cachedJson("recommend/playlist", CloudMetadataCache.FRESH_MS, refresh) {
            requestBlocking("recommend/playlist", mapOf("id" to "3317", "pageSize" to "30", "pageNo" to "1"))
        }.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        List(list.length()) { i ->
            val o = list.getJSONObject(i)
            CloudPlaylist(o.optLong("dissid", o.optLong("tid")), o.optString("dissname", o.optString("diss_name")), o.optInt("song_count", o.optInt("songnum")),
                o.optString("imgurl", o.optString("diss_cover")).https(), provider = PROVIDER_QQ)
        }.filter { it.id > 0 }
    }

    suspend fun playlistSongs(id: Long, refresh: Boolean = false): CloudPage<Song> = withContext(Dispatchers.IO) {
        if (!usingCustomEndpoint) return@withContext official.playlistSongs(id, ::parseSongs)
        val data = cachedJson("songlist/$id", CloudMetadataCache.FRESH_MS, refresh) {
            requestBlocking("songlist", mapOf("id" to "$id"))
        }.optJSONObject("data")
        val songs = parseSongs(data?.optJSONArray("songlist") ?: JSONArray()).distinctBy { it.key }
        CloudPage(songs, songs.size, false, songs.size)
    }

    suspend fun albumTracks(albumMidOrId: String): List<Song> = withContext(Dispatchers.IO) {
        if (!usingCustomEndpoint) return@withContext official.albumTracks(albumMidOrId, ::parseSongs)
        val json = cachedJson("album/songs/$albumMidOrId", 24L * 60 * 60 * 1000) {
            requestBlocking("album/songs", mapOf("albummid" to albumMidOrId))
        }
        parseAlbumTracks(json)
    }
    suspend fun subscribeAlbum(albumMidOrId: String, subscribe: Boolean) = withContext(Dispatchers.IO) {
        if (!usingCustomEndpoint) {
            official.subscribeAlbum(albumMidOrId, subscribe)
        } else {
            request("album/sub", mapOf("albummid" to albumMidOrId, "t" to if (subscribe) "1" else "0"))
        }
    }

    suspend fun artistAlbums(singerMidOrId: String, offset: Int = 0, pageSize: Int = 30): CloudPage<CatalogAlbum> = withContext(Dispatchers.IO) {
        if (!usingCustomEndpoint) return@withContext official.artistAlbums(singerMidOrId, offset, pageSize, ::parseAlbums)
        val page = offset / pageSize.coerceAtLeast(1) + 1
        val data = request("singer/album", mapOf("singermid" to singerMidOrId, "pageNo" to "$page", "pageSize" to "$pageSize"))
            .optJSONObject("data")
        val albums = parseAlbums(data?.optJSONArray("list") ?: JSONArray())
        val total = data?.optInt("total", 0) ?: 0
        val hasMore = albums.isNotEmpty() && (if (total > 0) offset + albums.size < total else albums.size >= pageSize)
        CloudPage(albums, offset + albums.size, hasMore, total.takeIf { it > 0 })
    }

    suspend fun qualityDetails(songmid: String): List<btm.m.auraplayer.playback.CloudQuality> = withContext(Dispatchers.IO) {
        if (songmid.isBlank()) return@withContext emptyList()
        if (!usingCustomEndpoint) {
            return@withContext official.qualityDetails(songmid)
        }
        val response = runCatching { requestBlocking("song", mapOf("songmid" to songmid)) }
        val track = response.getOrNull()?.optJSONObject("data")?.optJSONObject("track_info")
        if (track != null) {
            official.rememberTrackInfo(songmid, track)
            val parsed = parseTrackQualities(track)
            if (parsed.isNotEmpty()) return@withContext parsed
        }
        val cached = official.cachedQualities(songmid)
        if (cached.isNotEmpty()) return@withContext cached
        response.exceptionOrNull()?.let { throw it }
        emptyList()
    }

    fun resolveBlocking(songmid: String, level: String = "standard"): String {
        val urlKey = "${cacheScope()}:$songmid:$level"
        val now = System.currentTimeMillis()
        synchronized(resolvedUrlCache) {
            resolvedUrlCache[urlKey]?.takeIf { it.expiresAtMs > now }?.let { cached ->
                btm.m.auraplayer.playback.PlaybackStreams.qqRecords[songmid] = cached.info
                return cached.url
            }
        }
        val resolved = if (!usingCustomEndpoint) {
            official.resolveBlocking(songmid, level)
        } else {
            val types = when (level) {
                "hires", "lossless", "jymaster", "vivid", "jyeffect", "sky", "dolby" -> listOf("flac", "320", "128")
                "exhigh", "higher" -> listOf("320", "128")
                else -> listOf("128")
            }
            var resolvedUrl: String? = null
            for (type in types) {
                val url = runCatching {
                    requestBlocking("song/url", mapOf("id" to songmid, "type" to type))
                        .optString("data").takeIf { it.isNotBlank() && it != "null" }
                }.getOrNull()
                if (url != null) {
                    resolvedUrl = url.toHttpUrl().newBuilder().scheme("https").build().toString()
                    break
                }
            }
            val finalUrl = resolvedUrl ?: throw IOException("UNAVAILABLE_SONG")
            val info = streamInfoFromUrl(finalUrl, level, official.cachedQualities(songmid))
            btm.m.auraplayer.playback.PlaybackStreams.qqRecords[songmid] = info
            finalUrl
        }
        val streamInfo = btm.m.auraplayer.playback.PlaybackStreams.qqRecords[songmid]
            ?: streamInfoFromUrl(resolved, level, official.cachedQualities(songmid)).also {
                btm.m.auraplayer.playback.PlaybackStreams.qqRecords[songmid] = it
            }
        synchronized(resolvedUrlCache) {
            resolvedUrlCache[urlKey] = CachedQqUrl(resolved, streamInfo, now + 8L * 60 * 1000)
        }
        return resolved
    }

    internal fun parseAlbumTracks(json: JSONObject): List<Song> {
        val data = json.optJSONObject("req_0")?.optJSONObject("data")
            ?: json.optJSONObject("data")
            ?: json
        val array = data.optJSONArray("songList")
            ?: data.optJSONArray("songlist")
            ?: data.optJSONArray("list")
            ?: data.optJSONArray("songs")
            ?: JSONArray()
        return parseSongs(array)
    }

    internal fun parseAlbums(items: JSONArray, defaultArtistId: Long = 0L): List<CatalogAlbum> = (0 until items.length()).mapNotNull { i ->
        val album = items.optJSONObject(i) ?: return@mapNotNull null
        val mid = album.optString("albumMid", album.optString("albummid", album.optString("album_mid", album.optString("mid"))))
        val rawId = album.optLong("albumID", album.optLong("albumid", album.optLong("albumId", album.optLong("id"))))
        val id = rawId.takeIf { it > 0L } ?: mid.takeIf { it.isNotBlank() }?.let { it.hashCode().toLong() and 0x7fffffffL } ?: 0L
        val title = album.optString("albumName", album.optString("albumname", album.optString("album_name", album.optString("name"))))
        if (id <= 0L && title.isBlank()) return@mapNotNull null
        val singer = album.optJSONArray("singer")?.optJSONObject(0) ?: album.optJSONObject("singer")
        val artistId = album.optLong("singerID", album.optLong("singerId", singer?.optLong("id") ?: defaultArtistId))
        val artist = album.optString("singerName", album.optString("singername", singer?.optString("name").orEmpty()))
        val artwork = album.optString("albumPic", album.optString("picUrl", album.optString("pic"))).https()
            .ifBlank { mid.takeIf { it.isNotBlank() }?.let { "https://y.qq.com/music/photo_new/T002R500x500M000$it.jpg" }.orEmpty() }
        val embeddedTracks = parseAlbumTracks(album)
        val rawCount = album.optInt("totalNum", album.optInt("song_count", album.optInt("songCount", album.optInt("cur_song_num", album.optInt("size", 0)))))
        val rawType = album.optString("albumType", album.optString("albumtype", album.optString("album_type", album.optString("type"))))
            .takeUnless { it.all(Char::isDigit) }.orEmpty()
        val published = album.optLong("publishTime", 0L).takeIf { it > 0L }
            ?: parseQqDateMs(album.optString("publishDate", album.optString("pubTime", album.optString("aDate"))))
        CatalogAlbum(
            id = id,
            title = title,
            artwork = artwork,
            artistId = artistId,
            artist = artist,
            published = published,
            count = rawCount.takeIf { it > 0 } ?: embeddedTracks.size,
            type = rawType,
            tracks = embeddedTracks,
            provider = PROVIDER_QQ,
        )
    }

    private fun parseQqDateMs(raw: String): Long {
        val text = raw.trim()
        if (text.isEmpty()) return 0L
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(text)?.time ?: 0L
        }.getOrDefault(0L)
    }

    internal fun parseSongs(array: JSONArray): List<Song> = (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i)?.let { it.optJSONObject("songInfo") ?: it } ?: return@mapNotNull null
        val mid = o.optString("songmid", o.optString("mid"))
        if (mid.isBlank()) return@mapNotNull null
        val id = o.optLong("songid", o.optLong("id")).takeIf { it > 0 } ?: (mid.hashCode().toLong() and 0x7fffffffL)
        val singers = o.optJSONArray("singer") ?: JSONArray()
        val artist = (0 until singers.length()).mapNotNull { singers.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }.joinToString(" / ")
        val albumMid = o.optString("albummid", o.optJSONObject("album")?.optString("mid").orEmpty())
        val album = o.optString("albumname", o.optJSONObject("album")?.optString("name").orEmpty())
        val seconds = o.optLong("interval", 0L)
        if (o.has("file")) official.rememberTrackInfo(mid, o, seconds)
        val pay = o.optJSONObject("pay")
        val vip = pay?.optInt("pay_play", pay.optInt("payplay", 0)) == 1 || o.optInt("isvip", 0) == 1
        Song(id, o.optString("songname", o.optString("name", o.optString("title"))), artist, album, 0, 0, seconds * 1000L,
            "aura://qqmusic/$mid", albumMid.takeIf { it.isNotBlank() }?.let { "https://y.qq.com/music/photo_new/T002R500x500M000$it.jpg" }.orEmpty(),
            cloud = true, provider = PROVIDER_QQ, vip = vip)
    }

    private fun String.https() = if (startsWith("http://")) "https://" + removePrefix("http://") else this
}
