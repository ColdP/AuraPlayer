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

/**
 * Uses official QQ Music endpoints by default (via [OfficialQqClient]), or an optional
 * user-configured jsososo/QQMusicApi service (https://github.com/jsososo/QQMusicApi).
 */
class QqMusicApi(
    private val endpoint: () -> String,
    private val uin: () -> String,
    private val cookie: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).followRedirects(false).build(),
) {
    private val official = OfficialQqClient(cookie, uin, client)
    val usingCustomEndpoint: Boolean get() = endpoint().isNotBlank()
    val configured: Boolean get() = true

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
        val explicit = uin().trim().filter(Char::isDigit)
        if (explicit.isNotEmpty()) return explicit
        val map = cookie().split(';').map { it.trim() }.filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val raw = map["uin"] ?: map["p_uin"] ?: map["wxuin"] ?: ""
        return raw.trim().removePrefix("o").trimStart('0').ifEmpty { raw.filter(Char::isDigit) }
    }

    suspend fun account(): CloudAccount = withContext(Dispatchers.IO) {
        if (!usingCustomEndpoint) return@withContext official.account()
        val id = userUin()
        if (id.isBlank()) throw CloudApiException(301, "LOGIN_REQUIRED")
        val detail = runCatching { requestBlocking("user/detail", mapOf("id" to id)) }.getOrNull()
        val creator = detail?.optJSONObject("data")?.optJSONObject("creator")
        val uid = id.toLongOrNull() ?: 0L
        CloudAccount(uid, creator?.optString("nick")?.takeIf { it.isNotBlank() } ?: "QQ $id",
            creator?.optString("headpic").orEmpty().https())
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

    suspend fun playlists(): CloudPage<CloudPlaylist> = withContext(Dispatchers.IO) {
        if (!usingCustomEndpoint) return@withContext official.playlists()
        val id = userUin()
        if (id.isBlank()) return@withContext CloudPage(emptyList(), 0, false, 0)
        val list = request("user/songlist", mapOf("id" to id)).optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val items = List(list.length()) { i ->
            val o = list.getJSONObject(i)
            CloudPlaylist(o.optLong("tid", o.optLong("dissid")), o.optString("diss_name", o.optString("dissname")),
                o.optInt("song_cnt", o.optInt("songnum")), o.optString("diss_cover", o.optString("imgurl")).https(),
                provider = PROVIDER_QQ)
        }.filter { it.id > 0 }
        CloudPage(items, items.size, false, items.size)
    }

    suspend fun recommendedPlaylists(): List<CloudPlaylist> = withContext(Dispatchers.IO) {
        if (!usingCustomEndpoint) return@withContext official.recommendedPlaylists()
        val list = request("recommend/playlist", mapOf("id" to "3317", "pageSize" to "30", "pageNo" to "1")).optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        List(list.length()) { i ->
            val o = list.getJSONObject(i)
            CloudPlaylist(o.optLong("dissid", o.optLong("tid")), o.optString("dissname", o.optString("diss_name")), o.optInt("song_count", o.optInt("songnum")),
                o.optString("imgurl", o.optString("diss_cover")).https(), provider = PROVIDER_QQ)
        }.filter { it.id > 0 }
    }

    suspend fun playlistSongs(id: Long): CloudPage<Song> = withContext(Dispatchers.IO) {
        if (!usingCustomEndpoint) return@withContext official.playlistSongs(id, ::parseSongs)
        val data = request("songlist", mapOf("id" to "$id")).optJSONObject("data")
        val songs = parseSongs(data?.optJSONArray("songlist") ?: JSONArray())
        CloudPage(songs, songs.size, false, songs.size)
    }

    fun resolveBlocking(songmid: String): String {
        if (!usingCustomEndpoint) return official.resolveBlocking(songmid)
        val response = requestBlocking("song/url", mapOf("id" to songmid, "type" to "128"))
        val url = response.optString("data").takeIf { it.isNotBlank() && it != "null" } ?: throw IOException("UNAVAILABLE_SONG")
        return url.toHttpUrl().newBuilder().scheme("https").build().toString()
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
        Song(id, o.optString("songname", o.optString("name", o.optString("title"))), artist, album, 0, 0, seconds * 1000L,
            "aura://qqmusic/$mid", albumMid.takeIf { it.isNotBlank() }?.let { "https://y.qq.com/music/photo_new/T002R500x500M000$it.jpg" }.orEmpty(),
            cloud = true, provider = PROVIDER_QQ)
    }

    private fun String.https() = if (startsWith("http://")) "https://" + removePrefix("http://") else this
}
