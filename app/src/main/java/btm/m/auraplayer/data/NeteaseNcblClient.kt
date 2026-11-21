// NCBL v3 telemetry encryption and upload following NeteaseCloudMusicApiEnhanced/api-enhanced.
package btm.m.auraplayer.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.GZIPOutputStream

internal object NeteaseNcblClient {
    private const val UPLOAD_URL = "https://clientlog3.music.163.com/api/clientlog/encrypt/upload?multiupload=true"
    private const val NCBL_VERSION = 3
    private const val HEADER_FIXED_LEN = 70
    private const val META_BLOCK_TYPE = 0x4343
    private const val DEFAULT_MAX_FRAME = 0x8000
    private const val FIELD_SEP = "\u0001"

    private val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)
    private val RSA_N = BigInteger("fd90bd466ff9bc8a3fec2fbcf263b90d5c564879fa5d7aab89b31c1d5cb4139d", 16)
    private val RSA_E = BigInteger.valueOf(65537L)
    private val random = SecureRandom()

    fun scrobbleV1(
        client: OkHttpClient,
        rawCookie: String,
        songId: Long,
        playedSec: Int,
        totalSec: Int,
        sourceId: String,
        sourceName: String = "list",
        bitrateKbps: Int = 320,
        level: String = "exhigh",
        endReason: String = "playend",
        uploadOrigin: String = "https://clientlog3.music.163.com",
    ): JSONObject {
        val cookies = parseCookies(rawCookie)
        val musicU = cookies["MUSIC_U"].orEmpty()
        if (musicU.isBlank()) throw CloudApiException(401, "MISSING_MUSIC_U")

        val safePlayed = playedSec.coerceAtLeast(1)
        val safeTotal = totalSec.coerceAtLeast(safePlayed)
        val resolvedSourceId = sourceId.ifBlank { songId.toString() }
        val appVersion = cookies["appver"].orEmpty().ifBlank { "3.1.35" }
        val appVersionCode = cookies["versioncode"].orEmpty().ifBlank { "205293" }
        val channel = cookies["channel"].orEmpty().ifBlank { "netease" }
        val vipType = cookies["vipType"].orEmpty()
        val cid = cookies["WNMCID"].orEmpty().ifBlank {
            val hex = ByteArray(3).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            "$hex.${System.currentTimeMillis()}.01.0"
        }
        val osver = cookies["osver"].orEmpty().ifBlank { "Microsoft-Windows-10-Professional-build-19045-64bit" }
        val metaJson = buildMetaJson(cookies, musicU, cid, appVersion, appVersionCode, channel, osver)
        val cookieHeader = buildCookieHeader(cookies, musicU, cid, appVersion, appVersionCode, channel, osver)

        val nowSec = System.currentTimeMillis() / 1000L
        val plvJson = buildPlv(
            songId = songId,
            sourceId = resolvedSourceId,
            sourceName = sourceName,
            totalSec = safeTotal,
            bitrateKbps = bitrateKbps.coerceAtLeast(128),
            level = level.ifBlank { "exhigh" },
            appVersion = appVersion,
            appVersionCode = appVersionCode,
            channel = channel,
            vipType = vipType,
        )
        val pldJson = buildPld(
            songId = songId,
            sourceId = resolvedSourceId,
            sourceName = sourceName,
            playedSec = safePlayed.coerceAtMost(safeTotal),
            totalSec = safeTotal,
            bitrateKbps = bitrateKbps.coerceAtLeast(128),
            level = level.ifBlank { "exhigh" },
            endReason = endReason.ifBlank { "playend" },
            appVersion = appVersion,
            appVersionCode = appVersionCode,
            channel = channel,
            vipType = vipType,
        )

        val plvBody = "${nowSec}${FIELD_SEP}_plv${FIELD_SEP}$plvJson"
        val pldBody = "${nowSec}${FIELD_SEP}_pld${FIELD_SEP}$pldJson"
        val uploadUrl = if (uploadOrigin == "https://clientlog3.music.163.com") UPLOAD_URL
        else uploadOrigin.trimEnd('/') + "/api/clientlog/encrypt/upload?multiupload=true"

        val plvResult = upload(client, uploadUrl, metaJson, plvBody, cookieHeader, appVersion)
        if (!plvResult.first) throw CloudApiException(plvResult.second.optInt("code", 500), "NCBL_PLV_FAILED")

        val pldResult = upload(client, uploadUrl, metaJson, pldBody, cookieHeader, appVersion)
        if (!pldResult.first) throw CloudApiException(pldResult.second.optInt("code", 500), "NCBL_PLD_FAILED")

        return JSONObject()
            .put("code", 200)
            .put("data", "ok")
    }

    internal fun encryptNcbl(meta: String, body: String): ByteArray {
        val metaBytes = meta.toByteArray(Charsets.UTF_8)
        val bodyBytes = body.toByteArray(Charsets.UTF_8)

        val keyA = ByteArray(32).also(random::nextBytes)
        if ((keyA[0].toInt() and 0xff) >= 0xa3) keyA[0] = 0xa2.toByte()
        val keyB = rsaWrap(keyA)

        val uuid = ByteArray(16).also(random::nextBytes)
        uuid[6] = ((uuid[6].toInt() and 0x0f) or 0x40).toByte()
        uuid[8] = ((uuid[8].toInt() and 0x3f) or 0x80).toByte()

        val nonce = uuid.copyOfRange(0, 12)
        val counter = (readIntLE(uuid, 12) ushr 2)
        val baseSeq = (random.nextInt(0x10000) and 0xffff)

        val metaCipher = chacha20(keyB, counter, nonce, metaBytes)
        val metaBlock = ByteBuffer.allocate(4 + metaCipher.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(META_BLOCK_TYPE.toShort())
            putShort(metaCipher.size.toShort())
            put(metaCipher)
        }.array()
        val headerLen = HEADER_FIXED_LEN + metaBlock.size

        val compressed = gzip(bodyBytes)
        val trailingOut = ByteArrayOutputStream()
        var seq = baseSeq
        var offset = 0
        while (offset < compressed.size || offset == 0) {
            val end = minOf(offset + DEFAULT_MAX_FRAME, compressed.size)
            val slice = compressed.copyOfRange(offset, end)
            val cipher = chacha20(keyA, counter, nonce, slice)
            val frameHead = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(cipher.size.toShort())
                putInt(seq)
            }.array()
            trailingOut.write(frameHead)
            trailingOut.write(cipher)
            seq++
            if (compressed.isEmpty()) break
            offset += DEFAULT_MAX_FRAME
        }
        val trailing = trailingOut.toByteArray()
        val frameCount = seq - baseSeq

        val header = ByteBuffer.allocate(HEADER_FIXED_LEN).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("NCBL".toByteArray(Charsets.US_ASCII))
            putInt(NCBL_VERSION)
            putShort(headerLen.toShort())
            put(uuid)
            put(keyB)
            putInt(baseSeq)
            putInt(baseSeq + frameCount - 1)
            putInt(trailing.size)
        }.array()

        return ByteArray(header.size + metaBlock.size + trailing.size).apply {
            System.arraycopy(header, 0, this, 0, header.size)
            System.arraycopy(metaBlock, 0, this, header.size, metaBlock.size)
            System.arraycopy(trailing, 0, this, header.size + metaBlock.size, trailing.size)
        }
    }

    internal fun rsaWrap(keyA: ByteArray): ByteArray {
        val wrapped = BigInteger(1, keyA).modPow(RSA_E, RSA_N).toByteArray()
        val out = ByteArray(32)
        val srcStart = if (wrapped.size > 32) wrapped.size - 32 else 0
        val copyLen = minOf(wrapped.size, 32)
        System.arraycopy(wrapped, srcStart, out, 32 - copyLen, copyLen)
        return out
    }

    internal fun chacha20(key: ByteArray, counter: Int, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var offset = 0
        while (offset < data.size) {
            val block = chachaBlock(key, counter + (offset ushr 6), nonce)
            val end = minOf(offset + 64, data.size)
            for (i in offset until end) {
                out[i] = (data[i].toInt() xor block[i - offset].toInt()).toByte()
            }
            offset += 64
        }
        return out
    }

    private fun chachaBlock(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val state = IntArray(16)
        state[0] = SIGMA[0]
        state[1] = SIGMA[1]
        state[2] = SIGMA[2]
        state[3] = SIGMA[3]
        for (i in 0 until 8) state[4 + i] = readIntLE(key, i * 4)
        state[12] = counter
        state[13] = readIntLE(nonce, 0)
        state[14] = readIntLE(nonce, 4)
        state[15] = readIntLE(nonce, 8)

        val work = state.copyOf()
        repeat(10) {
            quarterRound(work, 0, 4, 8, 12)
            quarterRound(work, 1, 5, 9, 13)
            quarterRound(work, 2, 6, 10, 14)
            quarterRound(work, 3, 7, 11, 15)
            quarterRound(work, 0, 5, 10, 15)
            quarterRound(work, 1, 6, 11, 12)
            quarterRound(work, 2, 7, 8, 13)
            quarterRound(work, 3, 4, 9, 14)
        }
        val out = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 16) out.putInt(work[i] + state[i])
        return out.array()
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun upload(
        client: OkHttpClient,
        url: String,
        metaJson: String,
        body: String,
        cookieHeader: String,
        appVersion: String,
    ): Pair<Boolean, JSONObject> {
        val payload = encryptNcbl(metaJson, body)
        val pid = 10000 + random.nextInt(90000)
        val suffix = (random.nextLong() and 0xffffffffL).coerceAtLeast(1L)
        val fileName = "op_${pid}_0_$suffix"
        val boundary = UUID.randomUUID().toString().replace("-", "")
        val multipart = MultipartBody.Builder(boundary)
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                payload.toRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://music.163.com/di")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/$appVersion")
            .header("Accept-Language", "zh-CN,zh;q=0.8")
            .header("Cookie", cookieHeader)
            .post(multipart)
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val json = runCatching { JSONObject(raw) }.getOrElse { throw IOException("INVALID_NCBL_RESPONSE") }
            val code = json.optInt("code", -1)
            val files = json.optJSONObject("data")?.optJSONArray("successfiles")
            val accepted = code == 200 && files != null && (0 until files.length()).any { files.optString(it) == fileName }
            return accepted to json
        }
    }

    private fun parseCookies(raw: String): Map<String, String> =
        raw.split(';').map { it.trim() }.filter { '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    private fun buildCookieHeader(
        cookies: Map<String, String>,
        musicU: String,
        cid: String,
        appVersion: String,
        appVersionCode: String,
        channel: String,
        osver: String,
    ): String = listOf(
        "JSESSIONID-WYYY=${cookies["JSESSIONID-WYYY"].orEmpty()}",
        "MUSIC_U=$musicU",
        "NMTID=${cookies["NMTID"].orEmpty()}",
        "WEVNSM=${cookies["WEVNSM"].orEmpty().ifBlank { "1.0.0" }}",
        "WNMCID=$cid",
        "__csrf=${cookies["__csrf"].orEmpty()}",
        "__remember_me=true",
        "_iuqxldmzr_=33",
        "_ntes_nnid=${cookies["_ntes_nnid"].orEmpty().ifBlank { "," }}",
        "_ntes_nuid=${cookies["_ntes_nuid"].orEmpty()}",
        "appver=$appVersion.$appVersionCode",
        "channel=$channel",
        "clientSign=${cookies["clientSign"].orEmpty()}",
        "deviceId=${cookies["deviceId"] ?: cookies["sDeviceId"].orEmpty()}",
        "mode=${cookies["mode"] ?: cookies["mobilename"].orEmpty()}",
        "ntes_kaola_ad=1",
        "os=pc",
        "osver=$osver",
    ).joinToString("; ")

    private fun buildMetaJson(
        cookies: Map<String, String>,
        musicU: String,
        cid: String,
        appVersion: String,
        appVersionCode: String,
        channel: String,
        osver: String,
    ): String = JSONObject()
        .put("JSESSIONID-WYYY", cookies["JSESSIONID-WYYY"].orEmpty())
        .put("MUSIC_U", musicU)
        .put("NMTID", cookies["NMTID"].orEmpty())
        .put("WEVNSM", cookies["WEVNSM"].orEmpty().ifBlank { "1.0.0" })
        .put("WNMCID", cid)
        .put("__csrf", cookies["__csrf"].orEmpty())
        .put("_iuqxldmzr_", "33")
        .put("_ntes_nnid", cookies["_ntes_nnid"].orEmpty().ifBlank { "," })
        .put("_ntes_nuid", cookies["_ntes_nuid"].orEmpty())
        .put("appver", "$appVersion.$appVersionCode")
        .put("channel", channel)
        .put("clientSign", cookies["clientSign"].orEmpty())
        .put("deviceId", cookies["deviceId"] ?: cookies["sDeviceId"].orEmpty())
        .put("mode", cookies["mode"] ?: cookies["mobilename"].orEmpty())
        .put("ntes_kaola_ad", "1")
        .put("os", "pc")
        .put("osver", osver)
        .toString()

    private fun buildPlv(
        songId: Long,
        sourceId: String,
        sourceName: String,
        totalSec: Int,
        bitrateKbps: Int,
        level: String,
        appVersion: String,
        appVersionCode: String,
        channel: String,
        vipType: String,
    ): String {
        val now = System.currentTimeMillis()
        val addRefer = "[F:63][$now#933#$appVersion#$appVersionCode#c9156c3][e][2][23][cell_pc_songlist_song:2|page_pc_songlist_songflow|page_mine_like_music][$songId:song:x:x|:::|$sourceId:list::]"
        val multiRefers = JSONArray(
            listOf(
                "[F:26][s][18][_ai]",
                "[F:26][s][12][_ai]",
                "[F:63][$now#933#$appVersion#$appVersionCode#c9156c3][e][2][8][cell_pc_main_tab_entrance:6|page_pc_main_tab][我喜欢的音乐:spm::|:::]",
                "[F:26][s][5][_ai]",
                "[F:26][s][0][_ai]",
            ),
        )
        return JSONObject()
            .put("mode", "circulation")
            .put("download", 0)
            .put("alg", "")
            .put("status", "front")
            .put("id", songId.toString())
            .put("bitrate", bitrateKbps)
            .put("type", "song")
            .put("is_listentogether", 0)
            .put("source", sourceName)
            .put("is_heart", 0)
            .put("resource_ratio", "")
            .put("resource_time", totalSec)
            .put("musiceffect_id", "")
            .put("app_mode", 2)
            .put("bitrate_level", level)
            .put("_addrefer", addRefer)
            .put("_multirefers", multiRefers)
            .put("vipType", vipType)
            .put("fee", 1)
            .put("file", 4)
            .put("rightSource", 0)
            .put("sourceId", sourceId)
            .put("sourcetype", "track")
            .put("libra_abt", "")
            .put("channel", channel)
            .put("curStartChannel", "")
            .toString()
    }

    private fun buildPld(
        songId: Long,
        sourceId: String,
        sourceName: String,
        playedSec: Int,
        totalSec: Int,
        bitrateKbps: Int,
        level: String,
        endReason: String,
        appVersion: String,
        appVersionCode: String,
        channel: String,
        vipType: String,
    ): String {
        val now = System.currentTimeMillis()
        val addRefer = "[F:63][$now#616#$appVersion#$appVersionCode#c9156c3][e][2][92][btn_pc_cover_play|cell_pc_songlist_song:6|page_pc_songlist_songflow|page_mine_like_music][:::|$songId:song:x:x|:::|$sourceId:list::]"
        val multiRefers = JSONArray(
            listOf(
                "[F:26][s][87][_ai]",
                "[F:26][s][81][_ai]",
                "[F:26][s][75][_ai]",
                "[F:26][s][69][_ai]",
                "[F:26][s][63][_ai]",
            ),
        )
        return JSONObject()
            .put("mode", "circulation")
            .put("download", 0)
            .put("alg", "")
            .put("status", "front")
            .put("id", songId.toString())
            .put("time", playedSec)
            .put("type", "song")
            .put("is_listentogether", 0)
            .put("source", sourceName)
            .put("is_heart", 0)
            .put("realtime", playedSec)
            .put("resource_ratio", "")
            .put("resource_time", totalSec)
            .put("musiceffect_id", "1001")
            .put("app_mode", 1)
            .put("lyriceffect", "default")
            .put("displayMode", "classic")
            .put("bitrate", bitrateKbps)
            .put("bitrate_level", level)
            .put("_addrefer", addRefer)
            .put("_multirefers", multiRefers)
            .put("vipType", vipType)
            .put("fee", 8)
            .put("file", 4)
            .put("rightSource", 0)
            .put("sourceId", sourceId)
            .put("sourcetype", "track")
            .put("end", endReason)
            .put("libra_abt", "")
            .put("channel", channel)
            .put("curStartChannel", "")
            .toString()
    }
}
