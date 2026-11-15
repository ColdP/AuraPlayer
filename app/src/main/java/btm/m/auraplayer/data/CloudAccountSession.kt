package btm.m.auraplayer.data

import org.json.JSONArray
import org.json.JSONObject

data class StoredCloudAccount(
    val userId: Long,
    val nickname: String,
    val avatar: String,
    val cookie: String,
) {
    fun toAccount(): CloudAccount = CloudAccount(userId = userId, nickname = nickname, avatar = avatar)
}

data class MultiAccountState(
    val activeUserId: Long = 0L,
    val accounts: List<StoredCloudAccount> = emptyList(),
    val legacyCookie: String = "",
) {
    val activeAccount: StoredCloudAccount?
        get() = accounts.firstOrNull { it.userId == activeUserId }

    val activeCookie: String
        get() = activeAccount?.cookie ?: legacyCookie
}

object MultiAccountStateCodec {
    private const val VERSION = 1

    fun encode(state: MultiAccountState): String {
        if (state.accounts.isEmpty()) return state.legacyCookie.trim()
        val array = JSONArray()
        state.accounts.forEach { account ->
            array.put(
                JSONObject()
                    .put("userId", account.userId)
                    .put("nickname", account.nickname)
                    .put("avatar", account.avatar)
                    .put("cookie", account.cookie),
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("activeUserId", state.activeUserId)
            .put("accounts", array)
            .toString()
    }

    fun decode(raw: String): MultiAccountState {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return MultiAccountState()
        if (!trimmed.startsWith("{")) return MultiAccountState(legacyCookie = trimmed)
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return MultiAccountState(legacyCookie = trimmed)
        val array = json.optJSONArray("accounts") ?: return MultiAccountState(legacyCookie = trimmed)
        val parsed = buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val userId = obj.optLong("userId", 0L)
                val cookie = obj.optString("cookie", "").trim()
                if (userId <= 0L || cookie.isEmpty()) continue
                add(
                    StoredCloudAccount(
                        userId = userId,
                        nickname = obj.optString("nickname", ""),
                        avatar = obj.optString("avatar", ""),
                        cookie = cookie,
                    ),
                )
            }
        }
        val activeId = json.optLong("activeUserId", 0L)
        val resolvedActive = if (parsed.any { it.userId == activeId }) activeId else 0L
        return MultiAccountState(activeUserId = resolvedActive, accounts = parsed)
    }

    fun upsertAndActivate(state: MultiAccountState, account: CloudAccount, cookie: String): MultiAccountState {
        val cleanedCookie = cookie.trim()
        if (cleanedCookie.isEmpty() || account.userId <= 0L) return state
        val entry = StoredCloudAccount(
            userId = account.userId,
            nickname = account.nickname,
            avatar = account.avatar,
            cookie = cleanedCookie,
        )
        val existingIndex = state.accounts.indexOfFirst { it.userId == account.userId }
        val updated = state.accounts.toMutableList().apply {
            if (existingIndex >= 0) set(existingIndex, entry) else add(entry)
        }
        return MultiAccountState(activeUserId = account.userId, accounts = updated)
    }

    fun switchActive(state: MultiAccountState, userId: Long): MultiAccountState {
        if (state.accounts.none { it.userId == userId }) return state
        return state.copy(activeUserId = userId, legacyCookie = "")
    }

    fun removeAccount(state: MultiAccountState, userId: Long): MultiAccountState {
        val remaining = state.accounts.filterNot { it.userId == userId }
        val nextActive = when {
            remaining.isEmpty() -> 0L
            state.activeUserId == userId -> remaining.first().userId
            remaining.any { it.userId == state.activeUserId } -> state.activeUserId
            else -> 0L
        }
        return MultiAccountState(activeUserId = nextActive, accounts = remaining)
    }

    fun deactivateActive(state: MultiAccountState): MultiAccountState =
        if (state.accounts.isEmpty()) MultiAccountState() else state.copy(activeUserId = 0L, legacyCookie = "")

    fun splitCookieLines(rawCookie: String): List<String> =
        rawCookie.split(';').map { it.trim() }.filter { it.isNotEmpty() }
}
