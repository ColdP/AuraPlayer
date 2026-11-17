package btm.m.auraplayer.data

/** Cookie and CSRF conventions shared by the official login page and QQ API requests. */
internal class QqSession(cookie: String) {
    val cookies: Map<String, String> = parse(cookie)

    fun uin(): String {
        val wechat = cookies["login_type"] == "2" || !cookies["wxopenid"].isNullOrBlank()
        val keys = if (wechat) listOf("wxuin", "uin", "p_uin", "qqmusic_uin")
        else listOf("uin", "p_uin", "qqmusic_uin", "wxuin")
        return keys.firstNotNullOfOrNull { normalizeUin(cookies[it].orEmpty()).takeIf(String::isNotEmpty) }.orEmpty()
    }

    val hasCredentials: Boolean
        get() = uin().isNotEmpty() && listOf("qqmusic_key", "qm_keyst").any { !cookies[it].isNullOrBlank() }

    // The website sends both tokens: the legacy token prefers skey, the newer one prefers the music key.
    fun gtk(modern: Boolean = false): Long {
        val keys = if (modern) listOf("qqmusic_key", "qm_keyst", "p_skey", "skey", "p_lskey", "lskey")
        else listOf("skey", "qqmusic_key", "qm_keyst")
        val key = keys.firstNotNullOfOrNull { cookies[it]?.takeIf(String::isNotBlank) }.orEmpty()
        var hash = 5381L
        for (ch in key) hash += (hash shl 5) + ch.code
        return hash and 0x7fffffffL
    }

    companion object {
        private val sessionKeys = setOf(
            "uin", "p_uin", "qqmusic_uin", "qqmusic_key", "qm_keyst", "skey", "p_skey", "p_lskey", "lskey",
            "wxuin", "wxopenid", "euin", "musickey", "login_type", "tmeLoginType", "pgv_pvid", "qqmusic_guid",
        )

        fun normalizeUin(value: String): String = value.trim().removePrefix("o")
            .takeIf { it.isNotEmpty() && it.all { ch -> ch in '0'..'9' } }?.trimStart('0').orEmpty()

        private fun parse(cookie: String): Map<String, String> = buildMap {
            cookie.split(';').forEach { part ->
                val key = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                // CookieManager orders more specific cookies first; don't overwrite them with a parent domain.
                if (key.isNotEmpty() && value.isNotEmpty() && key !in this) put(key, value)
            }
        }

        /** Pass the y.qq.com page cookies first so stale API-domain cookies cannot replace them. */
        fun loginCookie(originCookies: List<String>): String = buildMap {
            originCookies.forEach { cookie ->
                parse(cookie).forEach { (key, value) ->
                    if (key in sessionKeys && key !in this) put(key, value)
                }
            }
        }.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
