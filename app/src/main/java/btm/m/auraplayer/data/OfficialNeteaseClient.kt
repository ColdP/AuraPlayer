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
    private val eapiOrigin: String = "https://interfacepc.music.163.com",
    private val clientlogOrigin: String = "https://clientlog.music.163.com",
    private val clientlog3Origin: String = "https://clientlog3.music.163.com",
    private val metadataCache: CloudMetadataCache? = null,
) {
    private val deviceId: String = java.util.UUID.randomUUID().toString().replace("-", "").uppercase()

    private fun parseSessionCookies(authenticated: Boolean): Map<String, String> {
        if (!authenticated) return emptyMap()
        val raw = cookie().split(';')
            .map { it.trim() }
            .filter { '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            .filterValues { it.isNotBlank() }
        // Never send anonymous MUSIC_A alongside an authenticated MUSIC_U session.
        return if (!raw["MUSIC_U"].isNullOrBlank()) raw.filterKeys { it != "MUSIC_A" } else raw
    }

    private fun playlistKey(id: String) = "official/" + CloudMetadataCache.digest(webOrigin + "\n" + cookie()) + "/playlist/$id"
    fun invalidatePlaylist(id: Long) { metadataCache?.remove(playlistKey(id.toString())) }
    fun request(path: String, values: Map<String, String>, authenticated: Boolean): JSONObject {
        fun value(key: String, fallback: String = "") = values[key] ?: fallback
        return when (path.trim('/')) {
            "banner", "banner/get" -> {
                val clientType = when (value("type")) {
                    "1" -> "android"
                    "2" -> "iphone"
                    "3" -> "ipad"
                    else -> "pc"
                }
                call("/api/v2/banner/get", JSONObject().put("clientType", clientType), authenticated = false)
            }
            "homepage/block/page" -> call("/api/homepage/block/page", JSONObject()
                .put("refresh", false).put("cursor", "-1").put("refreshType", 1), authenticated)
            "recommend/resource" -> call("/api/discovery/recommend/resource", JSONObject()
                .put("limit", value("limit", "30")).put("offset", value("offset", "0")), authenticated)
            "recommend/songs" -> call("/api/v3/discovery/recommend/songs", JSONObject()
                .apply { values["afresh"]?.let { put("afresh", it) } }, authenticated)
            "personalized/newsong" -> call("/api/personalized/newsong", JSONObject()
                .put("type", "recommend").put("limit", value("limit", "10")).put("areaId", 0), authenticated)
            "playmode/intelligence/list" -> call("/api/playmode/intelligence/list", JSONObject()
                .put("songId", value("id")).put("playlistId", value("pid")).put("type", "fromPlayOne")
                .put("startMusicId", value("sid", value("id"))).put("count", value("count", "1").toInt()),
                authenticated, eapi = true, androidClient = true)
            "simi/song" -> call("/api/v1/discovery/simiSong", JSONObject()
                .put("songid", value("id"))
                .put("limit", value("limit", "20").toIntOrNull() ?: 20)
                .put("offset", value("offset", "0").toIntOrNull() ?: 0), authenticated)
            "simi/artist" -> call("/api/discovery/simiArtist", JSONObject()
                .put("artistid", value("id")), authenticated)
            "personal_fm" -> call("/api/v1/radio/get", JSONObject(), authenticated)
            "personalized/playlist" -> call("/api/personalized/playlist", JSONObject().put("limit", value("limit", "30")).put("offset", value("offset", "0")), authenticated = false)
            "login/status" -> call("/api/w/nuser/account/get", JSONObject(), authenticated)
            "user/playlist" -> call("/api/user/playlist", JSONObject().put("uid", value("uid"))
                .put("limit", value("limit", "100")).put("offset", value("offset", "0")).put("includeVideo", true), authenticated)
            "cloudsearch" -> call("/api/cloudsearch/pc", JSONObject().put("s", value("keywords")).put("type", value("type", "1").toInt())
                .put("limit", value("limit", "50")).put("offset", value("offset", "0")).put("total", true), authenticated)
            "search" -> call("/api/search/voice/get", JSONObject().put("keyword", value("keywords")).put("scene", "normal")
                .put("limit", value("limit", "50")).put("offset", value("offset", "0")), authenticated)
            "user/record" -> call("/api/v1/play/record", JSONObject().put("uid", value("uid")).put("type", value("type", "0")), authenticated)
            "record/recent/song" -> call("/api/play-record/song/list", JSONObject().put("limit", value("limit", "100").toInt()), authenticated)
            "record/recent/album" -> call("/api/play-record/album/list", JSONObject().put("limit", value("limit", "50").toIntOrNull() ?: 50), authenticated)
            "scrobble/start" -> {
                val id = value("id")
                val sourceId = value("sourceid", id)
                val logs = JSONArray().put(
                    JSONObject().put("action", "startplay").put(
                        "json",
                        JSONObject()
                            .put("id", id)
                            .put("type", "song")
                            .put("mainsite", "1")
                            .put("mainsiteWeb", "1")
                            .put("content", "id=$sourceId"),
                    ),
                )
                call(
                    "/api/feedback/weblog",
                    JSONObject().put("logs", logs.toString()),
                    authenticated,
                    eapi = true,
                    osxClient = true,
                    originOverride = clientlogOrigin,
                )
            }
            "scrobble" -> {
                val id = value("id")
                val sourceId = value("sourceid", id)
                val playedSec = value("time", "30").toIntOrNull()?.coerceAtLeast(1) ?: 30
                val startLogs = JSONArray().put(
                    JSONObject().put("action", "startplay").put(
                        "json",
                        JSONObject()
                            .put("id", id)
                            .put("type", "song")
                            .put("mainsite", "1")
                            .put("mainsiteWeb", "1")
                            .put("content", "id=$sourceId"),
                    ),
                )
                val playLogs = JSONArray().put(
                    JSONObject().put("action", "play").put(
                        "json",
                        JSONObject()
                            .put("download", 0)
                            .put("end", value("end", "playend"))
                            .put("id", id)
                            .put("sourceId", sourceId)
                            .put("time", playedSec)
                            .put("type", "song")
                            .put("wifi", 0)
                            .put("source", "list")
                            .put("mainsite", "1")
                            .put("mainsiteWeb", "1")
                            .put("content", "id=$sourceId"),
                    ),
                )
                call(
                    "/api/feedback/weblog",
                    JSONObject().put("logs", startLogs.toString()),
                    authenticated,
                    eapi = true,
                    osxClient = true,
                    originOverride = clientlogOrigin,
                )
                call(
                    "/api/feedback/weblog",
                    JSONObject().put("logs", playLogs.toString()),
                    authenticated,
                    eapi = true,
                    osxClient = true,
                    originOverride = clientlogOrigin,
                )
            }
            "scrobble/v1" -> NeteaseNcblClient.scrobbleV1(
                client = client,
                rawCookie = if (authenticated) cookie() else "",
                songId = value("id").toLongOrNull() ?: throw CloudApiException(400, "INVALID_SONG_ID"),
                playedSec = value("time", "30").toIntOrNull()?.coerceAtLeast(1) ?: 30,
                totalSec = value("total", value("time", "30")).toIntOrNull()?.coerceAtLeast(1) ?: 30,
                sourceId = value("sourceid", value("id")),
                sourceName = value("source", "list"),
                bitrateKbps = value("bitrate", "320").toIntOrNull() ?: 320,
                level = value("level", "exhigh"),
                endReason = value("end", "playend"),
                uploadOrigin = clientlog3Origin,
            )
            "listen/data/today/song" -> call("/api/content/activity/listen/data/today/song/play/rank", JSONObject(), authenticated, eapi = true)
            "listen/data/realtime/report" -> call(
                "/api/content/activity/listen/data/realtime/report",
                JSONObject().put("type", value("type", "week")),
                authenticated,
                eapi = true,
            )
            "listen/data/report" -> call(
                "/api/content/activity/listen/data/report",
                JSONObject().put("type", value("type", "week")).apply { values["endTime"]?.let { put("endTime", it) } },
                authenticated,
                eapi = true,
            )
            "listen/data/song/play/rank" -> call(
                "/api/content/activity/listen/data/song/play/rank",
                JSONObject().put("type", value("type", "week")).apply { values["endTime"]?.let { put("endTime", it) } },
                authenticated,
                eapi = true,
            )
            "listen/data/year/report" -> call("/api/content/activity/listen/data/year/report", JSONObject(), authenticated, eapi = true)
            "listen/data/total" -> call("/api/content/activity/listen/data/total", JSONObject(), authenticated, eapi = true)
            "user/follows" -> call("/api/user/getfollows/${value("uid")}", JSONObject()
                .put("offset", value("offset", "0")).put("limit", value("limit", "30")).put("order", true), authenticated)
            "likelist" -> call("/api/song/like/get", JSONObject().put("uid", value("uid")), authenticated)
            "like" -> call("/api/radio/like", JSONObject().put("trackId", value("id")).put("like", value("like") == "true").put("alg", "itembased").put("time", "3"), authenticated)
            "playlist/tracks" -> {
                val op = value("op", "add")
                val tracks = value("tracks").split(',').filter(String::isNotBlank)
                fun manipulate(ids: List<String>) = call("/api/playlist/manipulate/tracks", JSONObject()
                    .put("op", op).put("pid", value("pid"))
                    .put("trackIds", JSONArray(ids).toString()).put("imme", "true"), authenticated)
                try { manipulate(tracks) } catch (e: CloudApiException) {
                    if (op == "add" && e.status == 512) manipulate(tracks + tracks) else throw e
                }
            }
            "playlist/detail" -> call("/api/v6/playlist/detail", JSONObject().put("id", value("id")).put("n", 0).put("s", 0), authenticated)
            "song/order/update" -> call(
                "/api/playlist/manipulate/tracks",
                JSONObject()
                    .put("op", "update")
                    .put("pid", value("pid"))
                    .put("trackIds", value("ids")),
                authenticated,
            )
            "album/sublist" -> call("/api/album/sublist", JSONObject().put("limit", value("limit", "25")).put("offset", value("offset", "0")).put("total", true), authenticated)
            "album/newest" -> call("/api/discovery/newAlbum", JSONObject(), authenticated = false, eapi = true)
            "album/new" -> call(
                "/api/album/new",
                JSONObject()
                    .put("area", value("area", "ALL"))
                    .put("limit", value("limit", "20").toIntOrNull() ?: 20)
                    .put("offset", value("offset", "0").toIntOrNull() ?: 0)
                    .put("total", true),
                authenticated = false,
                eapi = true,
            )
            "toplist/detail/v2" -> call("/api/toplist/detail/v2", JSONObject(), authenticated = false, eapi = true)
            "toplist/detail" -> call("/api/toplist/detail", JSONObject(), authenticated = false, eapi = true)
            "style/song" -> call(
                "/api/style-tag/home/song",
                JSONObject()
                    .put("tagId", value("tagId").toLongOrNull() ?: 0L)
                    .put("size", value("size", "50").toIntOrNull() ?: 50)
                    .put("cursor", value("cursor", "0").toIntOrNull() ?: 0)
                    .put("sort", value("sort", "0").toIntOrNull() ?: 0),
                authenticated = false,
                eapi = true,
            )
            "dj/program" -> call("/api/dj/program/byradio", JSONObject().put("radioId", value("rid")).put("limit", value("limit", "50")).put("offset", value("offset", "0")).put("asc", false), authenticated)
            "dj/program/detail" -> call("/api/dj/program/detail", JSONObject().put("id", value("id")), authenticated)
            "album" -> call("/api/v1/album/${value("id")}", JSONObject(), authenticated)
            "album/detail/dynamic" -> call("/api/album/detail/dynamic", JSONObject().put("id", value("id")), authenticated)
            "album/sub" -> {
                val action = if (value("t") == "1") "sub" else "unsub"
                call("/api/album/$action", JSONObject().put("id", value("id")), authenticated, eapi = true)
            }
            "artist/detail" -> call("/api/artist/head/info/get", JSONObject().put("id", value("id")), authenticated)
            "artist/detail/dynamic" -> call("/api/artist/detail/dynamic", JSONObject().put("id", value("id")), authenticated)
            "artist/album" -> call("/api/artist/albums/${value("id")}", JSONObject().put("limit", value("limit", "12")).put("offset", value("offset", "0")), authenticated)
            "artist/sub" -> {
                val action = if (value("t") == "1") "sub" else "unsub"
                call("/api/artist/$action", JSONObject().put("artistId", value("id")).put("artistIds", "[${value("id")}]"), authenticated)
            }
            "artist/sublist" -> call("/api/artist/sublist", JSONObject().put("limit", value("limit", "100")).put("offset", value("offset", "0")), authenticated)
            "artist/songs" -> call("/api/v1/artist/songs", JSONObject().put("id", value("id"))
                .put("private_cloud", "true").put("work_type", 1)
                .put("limit", value("limit", "50")).put("offset", value("offset", "0")).put("order", value("order", "hot")), authenticated)
            "song/download/url/v1" -> call("/api/song/enhance/download/url/v1", JSONObject().put("id", value("id"))
                .put("level", value("level", "standard")).put("immerseType", "c51"), authenticated, eapi = true, androidClient = value("level") == "vivid")
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
            "song/detail" -> call("/api/v3/song/detail", JSONObject().put("c", JSONArray().apply {
                value("ids").split(',').mapNotNull(String::toLongOrNull).forEach { put(JSONObject().put("id", it)) }
            }.toString()), authenticated)
            "song/music/detail" -> {
                val songId = value("id")
                val eapiResp = runCatching {
                    call("/api/song/music/detail/get", JSONObject().put("songId", songId), authenticated, eapi = true)
                }.getOrNull()
                if (eapiResp != null && NeteaseApi.hasQualityDetails(eapiResp)) {
                    eapiResp
                } else {
                    runCatching {
                        call("/api/song/music/detail/get", JSONObject().put("songId", songId), authenticated, eapi = false)
                    }.getOrNull() ?: eapiResp ?: throw IOException("UNAVAILABLE_SONG")
                }
            }
            "lyric/new" -> call("/api/song/lyric/v1", JSONObject().put("id", value("id"))
                .put("cp", false).put("lv", 0).put("kv", 0).put("tv", 0).put("rv", 0)
                .put("yv", 0).put("ytv", 0).put("yrv", 0), authenticated, eapi = true)
            "song/url/v1" -> resolveSongUrlV1(value("id"), value("level", "standard"), authenticated)
            "captcha/sent" -> call("/api/sms/captcha/sent", JSONObject().put("cellphone", value("phone"))
                .put("ctcode", value("ctcode", "86")).put("secrete", "music_middleuser_pclogin"), false)
            "login/cellphone" -> call("/api/w/login/cellphone", JSONObject().put("type", "1").put("https", "true")
                .put("phone", value("phone")).put("countrycode", value("countrycode", "86")).put("captcha", value("captcha"))
                .put("remember", "true"), false)
            "logout" -> call("/api/logout", JSONObject(), authenticated)
            else -> throw IOException("UNSUPPORTED_OFFICIAL_ROUTE")
        }
    }

    private fun isFullPlayableUrlResponse(response: JSONObject?): Boolean {
        val item = response?.optJSONArray("data")?.optJSONObject(0) ?: return false
        val url = item.optString("url")
        if (url.isBlank() || url == "null") return false
        val trial = item.opt("freeTrialInfo")
        return trial == null || trial == JSONObject.NULL || trial.toString() == "null"
    }

    private fun songUrlV1Payload(id: String, level: String): JSONObject = JSONObject()
        .put("ids", "[$id]")
        .put("level", level)
        .put("encodeType", if (level == "vivid") "mp3" else "flac")
        .apply { if (level == "sky") put("immerseType", "c51") }

    private fun resolveSongUrlV1(id: String, level: String, authenticated: Boolean): JSONObject {
        val isVivid = level == "vivid"
        val primary = call(
            "/api/song/enhance/player/url/v1",
            songUrlV1Payload(id, level),
            authenticated,
            eapi = true,
            androidClient = isVivid,
        )
        if (isFullPlayableUrlResponse(primary)) return primary
        val cookies = parseSessionCookies(authenticated)
        if (!authenticated || cookies["MUSIC_U"].isNullOrBlank()) return primary

        // Retry with WEAPI and alternate client profiles when an authenticated VIP session receives a trial clip.
        val weapiCandidate = runCatching {
            call(
                "/api/song/enhance/player/url/v1",
                songUrlV1Payload(id, level),
                authenticated = true,
                eapi = false,
            )
        }.getOrNull()
        if (isFullPlayableUrlResponse(weapiCandidate)) return weapiCandidate!!

        val altEapiCandidate = runCatching {
            call(
                "/api/song/enhance/player/url/v1",
                songUrlV1Payload(id, level),
                authenticated = true,
                eapi = true,
                androidClient = !isVivid,
                originOverride = streamOrigin,
            )
        }.getOrNull()
        if (isFullPlayableUrlResponse(altEapiCandidate)) return altEapiCandidate!!

        if (level in setOf("standard", "higher", "exhigh", "lossless")) {
            val br = when (level) {
                "standard" -> 128000
                "higher" -> 192000
                "exhigh" -> 320000
                else -> 999000
            }
            val legacyCandidate = runCatching {
                call(
                    "/api/song/enhance/player/url",
                    JSONObject().put("ids", "[$id]").put("br", br),
                    authenticated = true,
                    eapi = false,
                )
            }.getOrNull()
            if (isFullPlayableUrlResponse(legacyCandidate)) return legacyCandidate!!
        }

        return listOfNotNull(weapiCandidate, altEapiCandidate)
            .firstOrNull { resp ->
                val url = resp.optJSONArray("data")?.optJSONObject(0)?.optString("url").orEmpty()
                url.isNotBlank() && url != "null"
            } ?: primary
    }

    private fun call(
        path: String,
        data: JSONObject,
        authenticated: Boolean,
        eapi: Boolean = false,
        androidClient: Boolean = false,
        osxClient: Boolean = false,
        originOverride: String? = null,
    ): JSONObject {
        val cookies = parseSessionCookies(authenticated)
        val csrf = cookies["__csrf"].orEmpty()
        val clientOs = when {
            osxClient -> "osx"
            androidClient -> "android"
            else -> "pc"
        }
        val clientAppVer = when {
            osxClient -> "3.1.10.5100"
            androidClient -> "9.5.61"
            else -> "3.1.17.204416"
        }
        val clientOsVer = when {
            osxClient -> "15.5"
            androidClient -> "14"
            else -> "Microsoft-Windows-10-Professional-build-19045-64bit"
        }
        val clientChannel = if (androidClient) "xiaomi" else "netease"
        data.put("csrf_token", csrf).put("e_r", false)
        val body = if (eapi) {
            val reqCounter = java.util.concurrent.ThreadLocalRandom.current().nextInt(1000, 9999)
            data.put("header", JSONObject()
                .put("osver", clientOsVer)
                .put("deviceId", deviceId)
                .put("os", clientOs)
                .put("appver", clientAppVer)
                .put("versioncode", "140")
                .put("buildver", (System.currentTimeMillis() / 1000L).toString())
                .put("resolution", "1920x1080")
                .put("__csrf", csrf)
                .put("channel", clientChannel)
                .put("requestId", "${System.currentTimeMillis()}_$reqCounter")
                .apply {
                    cookies["MUSIC_U"]?.let { put("MUSIC_U", it) }
                    cookies["NMTID"]?.let { put("NMTID", it) }
                })
            NeteaseCrypto.eapi(path, data.toString())
        } else NeteaseCrypto.weapi(data.toString())
        val origin = originOverride ?: if (eapi) (if (androidClient || osxClient) streamOrigin else eapiOrigin) else webOrigin
        val userAgent = when {
            osxClient -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            androidClient -> "NeteaseMusic/9.5.61.260802021928(9005061);Dalvik/2.1.0 (Linux; U; Android 12; HBN-AL00 Build/cd737a2.0)"
            eapi -> "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.29.205117"
            else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36"
        }
        val cookieMap = cookies.toMutableMap().apply {
            put("__remember_me", "true")
            put("os", clientOs)
            put("appver", clientAppVer)
            put("osver", clientOsVer)
            put("channel", clientChannel)
            put("deviceId", deviceId)
        }
        val request = Request.Builder().url(origin + path.replaceFirst("/api/", if (eapi) "/eapi/" else "/weapi/"))
            .header("Referer", "https://music.163.com/")
            .header("User-Agent", userAgent)
            .header("Cookie", cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" })
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
