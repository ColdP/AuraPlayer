// Request mapping and encryption follow NeteaseCloudMusicApiEnhanced/api-enhanced.
package btm.m.auraplayer.data

import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/** Official endpoints used when no custom service has been configured. */
internal class OfficialNeteaseClient(
    private val cookie: () -> String,
    private val client: OkHttpClient,
    private val webOrigin: String = "https://music.163.com",
    private val streamOrigin: String = "https://interface.music.163.com",
    private val metadataCache: CloudMetadataCache? = null,
) {
    private fun playlistKey(id: String) = "official/" + CloudMetadataCache.digest(webOrigin + "\n" + cookie()) + "/playlist/$id"
    fun invalidatePlaylist(id: Long) { metadataCache?.removePrefix(playlistKey(id.toString())) }
    fun request(path: String, values: Map<String, String>, authenticated: Boolean): JSONObject {
        fun value(key: String, fallback: String = "") = values[key] ?: fallback
        return when (path.trim('/')) {
            "login/status" -> call("/api/w/nuser/account/get", JSONObject(), authenticated)
            "user/playlist" -> call("/api/user/playlist", JSONObject().put("uid", value("uid"))
                .put("limit", value("limit", "100")).put("offset", value("offset", "0")).put("includeVideo", true), authenticated)
            "cloudsearch" -> call("/api/cloudsearch/pc", JSONObject().put("s", value("keywords")).put("type", 1)
                .put("limit", value("limit", "50")).put("offset", value("offset", "0")).put("total", true), authenticated)
            "playlist/track/all" -> {
                val key = playlistKey(value("id"))
                val index = metadataCache?.read(key) ?: run {
                    // Fetch the lightweight ordered ID index once, not thousands of full tracks.
                    val detail = try {
                        call("/api/v6/playlist/detail", JSONObject().put("id", value("id")).put("n", 0).put("s", 0), authenticated)
                    } catch (e: IOException) {
                        if (e !is CloudApiException) metadataCache?.read(key, stale = true)?.let { return@run it }
                        throw e
                    }
                    val ids = detail.optJSONObject("playlist")?.optJSONArray("trackIds") ?: throw IOException("INVALID_PLAYLIST")
                    JSONObject().put("trackIds", ids).also { metadataCache?.write(key, it) }
                }
                val ids = index.getJSONArray("trackIds")
                val offset = value("offset", "0").toInt()
                val limit = value("limit", "50").toInt()
                require(offset >= 0 && limit in 1..100)
                val requested = JSONArray()
                for (i in offset until minOf(ids.length(), offset + limit)) requested.put(JSONObject().put("id", ids.getJSONObject(i).getLong("id")))
                val result = if (requested.length() == 0) JSONObject().put("code", 200).put("songs", JSONArray())
                else call("/api/v3/song/detail", JSONObject().put("c", requested.toString()), authenticated)
                val next = minOf(ids.length(), offset + requested.length())
                result.put("nextOffset", next).put("total", ids.length()).put("more", next < ids.length())
            }
            "song/music/detail" -> call("/api/song/music/detail/get", JSONObject().put("songId", value("id")), authenticated)
            "song/url/v1" -> call("/api/song/enhance/player/url/v1", JSONObject().put("ids", "[${value("id")}]")
                .put("level", value("level", "standard")).put("encodeType", if (value("level") == "vivid") "mp3" else "flac").apply { if (value("level") == "sky") put("immerseType", "c51") }, authenticated, eapi = true, androidClient = value("level") == "vivid")
            "captcha/sent" -> call("/api/sms/captcha/sent", JSONObject().put("cellphone", value("phone"))
                .put("ctcode", value("ctcode", "86")).put("secrete", "music_middleuser_pclogin"), false)
            "login/cellphone" -> call("/api/w/login/cellphone", JSONObject().put("type", "1").put("https", "true")
                .put("phone", value("phone")).put("countrycode", value("countrycode", "86")).put("captcha", value("captcha"))
                .put("remember", "true"), false)
            "logout" -> call("/api/logout", JSONObject(), authenticated)
            else -> throw IOException("UNSUPPORTED_OFFICIAL_ROUTE")
        }
    }

    private fun call(path: String, data: JSONObject, authenticated: Boolean, eapi: Boolean = false, androidClient: Boolean = false): JSONObject {
        val session = if (authenticated) cookie() else ""
        val cookies = session.split(';').map { it.trim() }.filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val csrf = cookies["__csrf"].orEmpty()
        data.put("csrf_token", csrf).put("e_r", false)
        val body = if (eapi) {
            data.put("header", JSONObject().put("os", if (androidClient) "android" else "pc").put("appver", if (androidClient) "9.5.61" else "3.0.18").put("__csrf", csrf)
                .apply { cookies["MUSIC_U"]?.let { put("MUSIC_U", it) } })
            NeteaseCrypto.eapi(path, data.toString())
        } else NeteaseCrypto.weapi(data.toString())
        val origin = if (eapi) streamOrigin else webOrigin
        val request = Request.Builder().url(origin + path.replaceFirst("/api/", if (eapi) "/eapi/" else "/weapi/"))
            .header("Referer", "https://music.163.com/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36")
            .header("Cookie", (cookies.map { "${it.key}=${it.value}" } + (if (androidClient) "os=android" else "os=pc") + (if (androidClient) "appver=9.5.61" else "appver=3.0.18")).joinToString("; "))
            .post(FormBody.Builder().apply { body.forEach { (k, v) -> add(k, v) } }.build()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw CloudApiException(response.code, "HTTP ${response.code}")
            val json = try { JSONObject(response.body?.string().orEmpty()) } catch (_: Exception) { throw IOException("INVALID_RESPONSE") }
            val code = json.optInt("code", 200)
            if (code != 200) throw CloudApiException(code, json.optString("message", "API $code"))
            if (path == "/api/w/login/cellphone") {
                json.put("cookie", response.headers("Set-Cookie").mapNotNull { Cookie.parse(response.request.url, it) }
                    .joinToString("; ") { "${it.name}=${it.value}" })
            }
            return json
        }
    }
}

internal object NeteaseCrypto {
    private const val PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB"
    private val random = SecureRandom()
    private fun aes(text: String, key: String, cbc: Boolean): ByteArray = Cipher.getInstance(if (cbc) "AES/CBC/PKCS5Padding" else "AES/ECB/PKCS5Padding").run {
        val secret = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        if (cbc) init(Cipher.ENCRYPT_MODE, secret, IvParameterSpec("0102030405060708".toByteArray())) else init(Cipher.ENCRYPT_MODE, secret)
        doFinal(text.toByteArray(Charsets.UTF_8))
    }
    fun weapi(text: String, key: String = buildString { repeat(16) { append("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[random.nextInt(62)]) } }): Map<String, String> {
        val first = Base64.getEncoder().encodeToString(aes(text, "0CoJUm6Qyw8W8jud", true))
        val params = Base64.getEncoder().encodeToString(aes(first, key, true))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)))
        val encryptedKey = Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, publicKey); doFinal(key.reversed().toByteArray(Charsets.UTF_8))
        }
        return mapOf("params" to params, "encSecKey" to encryptedKey.joinToString("") { "%02x".format(it) })
    }
    fun eapi(path: String, text: String): Map<String, String> {
        val digest = MessageDigest.getInstance("MD5").digest("nobody${path}use${text}md5forencrypt".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return mapOf("params" to aes("$path-36cd479b6b5-$text-36cd479b6b5-$digest", "e82ckenh8dichen8", false)
            .joinToString("") { "%02X".format(it) })
    }
}
