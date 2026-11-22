package btm.m.auraplayer.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Official QQ Music endpoints used when no self-hosted QQMusicApi service is configured. */
internal class OfficialQqClient(
    private val cookie: () -> String,
    private val uin: () -> String,
    private val client: OkHttpClient,
    private val uOrigin: String = "https://u.y.qq.com",
    private val cOrigin: String = "https://c.y.qq.com",
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private fun cookies(): Map<String, String> = QqSession(cookie()).cookies

    private fun effectiveUin(session: QqSession = QqSession(cookie())): String {
        return session.uin()
            .ifEmpty { QqSession.normalizeUin(uin()) }.ifEmpty { "0" }
    }

    private fun gtk(): Long = QqSession(cookie()).gtk()

    private fun guid(map: Map<String, String> = cookies()): String =
        map["pgv_pvid"]?.takeIf { it.isNotBlank() } ?: "1234567890"

    private fun getJson(url: String): JSONObject {
        val session = QqSession(cookie())
        val authenticatedUrl = url.toHttpUrl().newBuilder()
            .setQueryParameter("uin", effectiveUin(session))
            .setQueryParameter("g_tk", session.gtk().toString())
            .setQueryParameter("g_tk_new_20200303", session.gtk(modern = true).toString())
            .build()
        val request = Request.Builder().url(authenticatedUrl).get()
            .header("Referer", "https://y.qq.com/")
            .header("User-Agent", ua)
            .header("Accept", "application/json")
            .apply { cookie().takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw CloudApiException(response.code, "HTTP ${response.code}")
            val body = response.body?.string().orEmpty().trim()
            val cleaned = if (body.startsWith("callback(") && body.endsWith(")")) body.substring(9, body.length - 1) else body
            return try { JSONObject(cleaned.ifEmpty { "{}" }) } catch (_: Exception) { throw IOException("INVALID_RESPONSE") }
        }
    }

    private fun musicu(blocks: Map<String, JSONObject>): JSONObject {
        val session = QqSession(cookie())
        val payload = JSONObject().put("comm", JSONObject().apply {
            put("ct", 24)
            put("cv", 0)
            put("format", "json")
            put("uin", effectiveUin(session))
            put("g_tk", session.gtk())
            put("g_tk_new_20200303", session.gtk(modern = true))
        })
        blocks.forEach { (k, v) -> payload.put(k, v) }
        val request = Request.Builder().url("$uOrigin/cgi-bin/musicu.fcg").post(payload.toString().toRequestBody(jsonMedia))
            .header("Referer", "https://y.qq.com/")
            .header("Origin", "https://y.qq.com")
            .header("User-Agent", ua)
            .apply { cookie().takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw CloudApiException(response.code, "HTTP ${response.code}")
            val json = try { JSONObject(response.body?.string().orEmpty()) } catch (_: Exception) { throw IOException("INVALID_RESPONSE") }
            val code = json.optInt("code", 0)
            if (code != 0) throw CloudApiException(code, "QQ API $code")
            return json
        }
    }

    private fun profileHomepage(self: Boolean = false): JSONObject {
        val effUin = effectiveUin()
        val userId = if (self) "0" else effUin
        val url = "$cOrigin/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?cid=205360838&ct=24&cv=4747474&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0&userid=$userId&reqfrom=1&reqtype=0&hostUin=0&loginUin=$effUin"
        val json = getJson(url)
        val code = json.optInt("code", 0)
        if (code == 1000 || code == 200003) throw CloudApiException(301, "SESSION_EXPIRED")
        if (code != 0) throw CloudApiException(code, "QQ API $code")
        return json
    }

    fun account(): CloudAccount {
        val session = QqSession(cookie())
        val effUin = effectiveUin(session)
        if (!session.hasCredentials) throw CloudApiException(301, "LOGIN_REQUIRED")
        // userid=0 asks for the authenticated user rather than a possibly public profile.
        val creator = profileHomepage(self = true).optJSONObject("data")?.optJSONObject("creator")
            ?: throw IOException("INVALID_RESPONSE")
        val id = creator.optLong("uin", effUin.toLongOrNull() ?: 0L).takeIf { it > 0L } ?: (effUin.toLongOrNull() ?: 0L)
        val nick = creator.optString("nick").ifBlank { "QQ $effUin" }
        val avatar = creator.optString("headpic")
            .ifBlank { creator.optString("logo") }
            .ifBlank { if (id > 0L) "https://q.qlogo.cn/g?b=qq&nk=$id&s=640" else "" }
            .https()
        return CloudAccount(id, nick, avatar)
    }

    fun playlists(): CloudPage<CloudPlaylist> {
        val effUin = effectiveUin()
        if (effUin == "0") return CloudPage(emptyList(), 0, false, 0)
        val gTk = gtk()
        val result = mutableListOf<CloudPlaylist>()
        // 1. "我喜欢" playlist from profile homepage (often has tid == 0 in created_diss).
        runCatching {
            val myMusic = profileHomepage().optJSONObject("data")?.optJSONArray("mymusic") ?: JSONArray()
            for (i in 0 until myMusic.length()) {
                val item = myMusic.getJSONObject(i)
                val id = item.optLong("id")
                if (id > 0L) result += CloudPlaylist(
                    id = id,
                    name = item.optString("title").ifBlank { "我喜欢的音乐" },
                    count = item.optInt("num0"),
                    artwork = item.optString("picurl").https(),
                    specialType = 5,
                    provider = PROVIDER_QQ,
                )
            }
        }
        // 2. Created playlists.
        runCatching {
            val url = "$cOrigin/rsc/fcgi-bin/fcg_user_created_diss?hostuin=$effUin&sin=0&size=200&r=${System.currentTimeMillis()}&g_tk=$gTk&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
            val json = getJson(url)
            if (json.optInt("code", 0) == 0) {
                val list = json.optJSONObject("data")?.optJSONArray("disslist") ?: JSONArray()
                for (i in 0 until list.length()) {
                    val o = list.getJSONObject(i)
                    val id = o.optLong("tid", o.optLong("dissid"))
                    if (id > 0L) result += CloudPlaylist(
                        id = id,
                        name = o.optString("diss_name", o.optString("dissname")),
                        count = o.optInt("song_cnt", o.optInt("songnum")),
                        artwork = o.optString("diss_cover", o.optString("imgurl")).https(),
                        specialType = if (o.optInt("dirid") == 201) 5 else 0,
                        provider = PROVIDER_QQ,
                    )
                }
            }
        }
        // 3. Collected / subscribed playlists.
        runCatching {
            val url = "$cOrigin/fav/fcgi-bin/fcg_get_profile_order_asset.fcg?ct=24&cv=4747474&cid=205360956&userid=$effUin&reqtype=3&sin=0&ein=50&format=json&g_tk=$gTk"
            val json = getJson(url)
            if (json.optInt("code", 0) == 0) {
                val list = json.optJSONObject("data")?.optJSONArray("cdlist") ?: JSONArray()
                for (i in 0 until list.length()) {
                    val o = list.getJSONObject(i)
                    val id = o.optLong("dissid", o.optLong("tid"))
                    if (id > 0L) result += CloudPlaylist(
                        id = id,
                        name = o.optString("dissname", o.optString("diss_name")),
                        count = o.optInt("songnum", o.optInt("song_cnt")),
                        artwork = o.optString("logo", o.optString("diss_cover")).https(),
                        provider = PROVIDER_QQ,
                    )
                }
            }
        }
        val items = result.distinctBy { it.id }
        return CloudPage(items, items.size, false, items.size)
    }

    fun recommendedPlaylists(): List<CloudPlaylist> {
        val url = "$cOrigin/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg?picmid=1&rnd=${System.currentTimeMillis()}&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0&categoryId=10000000&sortId=5&sin=0&ein=29"
        val json = getJson(url)
        if (json.optInt("code", 0) != 0) return emptyList()
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        return List(list.length()) { i ->
            val o = list.getJSONObject(i)
            CloudPlaylist(
                id = o.optLong("dissid", o.optLong("tid")),
                name = o.optString("dissname", o.optString("diss_name")),
                count = o.optInt("songnum", o.optInt("song_cnt")),
                artwork = o.optString("imgurl", o.optString("diss_cover")).https(),
                provider = PROVIDER_QQ,
            )
        }.filter { it.id > 0 }
    }

    fun playlistSongs(id: Long, parseSongs: (JSONArray) -> List<Song>): CloudPage<Song> {
        val effUin = effectiveUin().toLongOrNull() ?: 0L
        val musicuResp = runCatching {
            musicu(mapOf("req_0" to JSONObject().apply {
                put("module", "music.srfDissInfo.aiDissInfo")
                put("method", "uniform_get_Dissinfo")
                put("param", JSONObject().apply {
                    put("disstid", id)
                    put("login_uin", effUin)
                    put("tag", 1)
                    put("userinfo", 1)
                    put("song_begin", 0)
                    put("song_num", 100)
                })
            })).optJSONObject("req_0")?.optJSONObject("data")?.optJSONArray("songlist")
        }.getOrNull()
        val array = musicuResp?.takeIf { it.length() > 0 } ?: run {
            val url = "$cOrigin/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=$id&g_tk=${gtk()}&format=json&inCharset=utf8&outCharset=utf-8&platform=yqq.json&needNewCode=0"
            val json = getJson(url)
            json.optJSONArray("cdlist")?.optJSONObject(0)?.optJSONArray("songlist") ?: JSONArray()
        }
        val songs = parseSongs(array)
        return CloudPage(songs, songs.size, false, songs.size)
    }

    fun search(query: String, offset: Int, pageSize: Int, parseSongs: (JSONArray) -> List<Song>): CloudPage<Song> {
        val page = offset + 1
        val resp = musicu(mapOf("req_1" to JSONObject().apply {
            put("module", "music.search.SearchCgiService")
            put("method", "DoSearchForQQMusicDesktop")
            put("param", JSONObject().apply {
                put("grp", 1)
                put("num_per_page", pageSize)
                put("page_num", page)
                put("query", query)
                put("search_type", 0)
            })
        }))
        val data = resp.optJSONObject("req_1")?.optJSONObject("data") ?: JSONObject()
        val songObj = data.optJSONObject("body")?.optJSONObject("song") ?: JSONObject()
        val songs = parseSongs(songObj.optJSONArray("list") ?: JSONArray())
        val total = data.optJSONObject("meta")?.optInt("sum") ?: data.optInt("totalnum", 0)
        return CloudPage(songs, page, songs.isNotEmpty() && (total == 0 || page * pageSize < total), total.takeIf { it > 0 })
    }

    fun resolveBlocking(songmid: String): String {
        val map = cookies()
        val effUin = effectiveUin()
        val resp = musicu(mapOf("req_0" to JSONObject().apply {
            put("module", "vkey.GetVkeyServer")
            put("method", "CgiGetVkey")
            put("param", JSONObject().apply {
                put("guid", guid(map))
                put("songmid", JSONArray().put(songmid))
                put("songtype", JSONArray().put(0))
                put("uin", effUin)
                put("loginflag", 1)
                put("platform", "20")
            })
        }))
        val data = resp.optJSONObject("req_0")?.optJSONObject("data") ?: throw IOException("UNAVAILABLE_SONG")
        val purl = data.optJSONArray("midurlinfo")?.optJSONObject(0)?.optString("purl").orEmpty()
        if (purl.isBlank()) throw IOException("UNAVAILABLE_SONG")
        val sip = data.optJSONArray("sip")?.optString(0)?.takeIf { it.isNotBlank() } ?: "https://isure.stream.qqmusic.qq.com/"
        return sip.https().trimEnd('/') + "/" + purl.trimStart('/')
    }

    fun albumTracks(albumMidOrId: String, parseSongs: (JSONArray) -> List<Song>): List<Song> {
        val resp = musicu(mapOf("req_0" to JSONObject().apply {
            put("module", "music.musichallAlbum.AlbumSongList")
            put("method", "GetAlbumSongList")
            put("param", JSONObject().apply {
                albumMidOrId.toLongOrNull()?.let { put("albumId", it) } ?: put("albumMid", albumMidOrId)
                put("begin", 0)
                put("num", 100)
                put("order", 2)
            })
        }))
        val data = resp.optJSONObject("req_0")?.optJSONObject("data") ?: JSONObject()
        val array = data.optJSONArray("songList")
            ?: data.optJSONArray("songlist")
            ?: data.optJSONArray("list")
            ?: JSONArray()
        return parseSongs(array)
    }

    fun artistAlbums(
        singerMidOrId: String,
        offset: Int,
        pageSize: Int,
        parseAlbums: (JSONArray) -> List<CatalogAlbum>,
    ): CloudPage<CatalogAlbum> {
        val resp = musicu(mapOf("req_0" to JSONObject().apply {
            put("module", "music.musichallAlbum.AlbumListServer")
            put("method", "GetAlbumList")
            put("param", JSONObject().apply {
                singerMidOrId.toLongOrNull()?.let { put("singerId", it) } ?: put("singerMid", singerMidOrId)
                put("begin", offset)
                put("num", pageSize)
                put("order", 0)
            })
        }))
        val data = resp.optJSONObject("req_0")?.optJSONObject("data") ?: JSONObject()
        val array = data.optJSONArray("albumList") ?: data.optJSONArray("list") ?: JSONArray()
        val albums = parseAlbums(array)
        val total = data.optInt("total", 0)
        val hasMore = albums.isNotEmpty() && (if (total > 0) offset + albums.size < total else albums.size >= pageSize)
        return CloudPage(albums, offset + albums.size, hasMore, total.takeIf { it > 0 })
    }
    fun subscribeAlbum(albumMidOrId: String, subscribe: Boolean) {
        val method = if (subscribe) "AddFavAlbum" else "DelFavAlbum"
        val param = JSONObject().apply {
            albumMidOrId.toLongOrNull()?.let { put("v_albumId", JSONArray().put(it)) }
                ?: put("v_albumMid", JSONArray().put(albumMidOrId))
        }
        musicu(mapOf("fav" to JSONObject().apply {
            put("module", "music.musicasset.AlbumFavWriteServer")
            put("method", method)
            put("param", param)
        }))
    }

    private fun String.https() = if (startsWith("http://")) "https://" + removePrefix("http://") else this
}
