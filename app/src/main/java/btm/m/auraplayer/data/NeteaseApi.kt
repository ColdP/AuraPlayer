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

/** NeteaseCloudMusicApiEnhanced-compatible HTTP adapter. No third-party public host is trusted by default. */
class NeteaseApi(
    private val endpoint: () -> String,
    private val cookie: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).build(),
) {
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
        val base = endpoint().takeIf { it.isNotBlank() } ?: throw IOException("API_NOT_CONFIGURED")
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
    suspend fun playlists(uid: Long): List<CloudPlaylist> {
        val result = mutableListOf<CloudPlaylist>()
        var offset = 0
        do {
            val response = request("user/playlist", mapOf("uid" to "$uid", "limit" to "100", "offset" to "$offset"))
            val items = response.optJSONArray("playlist") ?: JSONArray()
            for (i in 0 until items.length()) items.getJSONObject(i).let {
                result += CloudPlaylist(it.getLong("id"), it.optString("name"), it.optInt("trackCount"), it.optString("coverImgUrl"))
            }
            offset += items.length()
        } while (response.optBoolean("more") && items.length() > 0)
        return result.distinctBy { it.id }
    }
    suspend fun playlistSongs(id: Long): List<Song> {
        val result = mutableListOf<Song>()
        var offset = 0
        do {
            val response = request("playlist/track/all", mapOf("id" to "$id", "limit" to "500", "offset" to "$offset"))
            val items = response.optJSONArray("songs") ?: JSONArray()
            result += parseSongs(items)
            offset += items.length()
        } while (items.length() == 500)
        return result.distinctBy { it.id }
    }
    suspend fun search(query: String, offset: Int = 0): List<Song> {
        val response = request("cloudsearch", mapOf("keywords" to query, "type" to "1", "limit" to "50", "offset" to "$offset"))
        return parseSongs(response.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray())
    }
    fun resolveBlocking(id: Long): String {
        val response = requestBlocking("song/url/v1", mapOf("id" to "$id", "level" to "standard", "unblock" to "false"))
        val song = response.optJSONArray("data")?.optJSONObject(0) ?: throw IOException("UNAVAILABLE_SONG")
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
