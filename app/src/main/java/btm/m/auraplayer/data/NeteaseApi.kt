package btm.m.auraplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val cacheable = path in setOf("user/playlist", "playlist/track/all")
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
            result += CloudPlaylist(it.getLong("id"), it.optString("name"), it.optInt("trackCount"), it.optString("coverImgUrl"))
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
    suspend fun availableQualities(id: Long): List<btm.m.auraplayer.playback.StreamQuality> {
        val data = request("song/music/detail", mapOf("id" to "$id")).optJSONObject("data") ?: return emptyList()
        val fields = mapOf("standard" to "l", "higher" to "m", "exhigh" to "h", "lossless" to "sq", "hires" to "hr",
            "jyeffect" to "je", "sky" to "sk", "dolby" to "db", "vivid" to "vi", "jymaster" to "jm")
        return btm.m.auraplayer.playback.StreamQuality.entries.filter { data.optJSONObject(fields[it.level] ?: "") != null }
    }
    fun resolveBlocking(id: Long, level: String = "standard"): String {
        val response = requestBlocking("song/url/v1", mapOf("id" to "$id", "level" to level, "unblock" to "false"))
        val song = response.optJSONArray("data")?.optJSONObject(0) ?: throw IOException("UNAVAILABLE_SONG")
        btm.m.auraplayer.playback.PlaybackStreams.records[id] = btm.m.auraplayer.playback.StreamInfo(
            song.optString("level", level), song.optString("type"), song.optInt("br"), song.optInt("sr"))
        val url = song.optString("url").takeIf { it.isNotBlank() && it != "null" } ?: throw IOException("UNAVAILABLE_SONG")
        val parsed = url.toHttpUrl()
        // NetEase occasionally returns HTTP CDN URLs. HTTPS is supported by its CDN.
        return parsed.newBuilder().scheme("https").build().toString()
    }
    private fun parseAccount(profile: JSONObject) = CloudAccount(profile.getLong("userId"), profile.optString("nickname"), profile.optString("avatarUrl"))
    internal fun parseSongs(array: JSONArray): List<Song> = List(array.length()) { i ->
        val o = array.getJSONObject(i)
        val artists = o.optJSONArray("ar") ?: o.optJSONArray("artists") ?: JSONArray()
        val album = o.optJSONObject("al") ?: o.optJSONObject("album") ?: JSONObject()
        val id = o.getLong("id")
        Song(id, o.optString("name"), (0 until artists.length()).joinToString(" / ") { artists.getJSONObject(it).optString("name") },
            album.optString("name"), album.optLong("id"), artists.optJSONObject(0)?.optLong("id") ?: 0,
            o.optLong("dt", o.optLong("duration")), "aura://netease/$id", album.optString("picUrl"), cloud = true)
    }
}
