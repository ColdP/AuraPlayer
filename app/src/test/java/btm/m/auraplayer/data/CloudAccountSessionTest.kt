package btm.m.auraplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAccountSessionTest {
    @Test
    fun legacyCookieDecodesAndMigratesOnUpsert() {
        val legacy = MultiAccountStateCodec.decode("MUSIC_U=legacy123; __csrf=csrf999")
        assertTrue(legacy.accounts.isEmpty())
        assertEquals("MUSIC_U=legacy123; __csrf=csrf999", legacy.activeCookie)

        val migrated = MultiAccountStateCodec.upsertAndActivate(
            legacy,
            CloudAccount(userId = 1001L, nickname = "Alice", avatar = "https://img/a.jpg"),
            legacy.activeCookie,
        )
        val roundTrip = MultiAccountStateCodec.decode(MultiAccountStateCodec.encode(migrated))
        assertEquals(1001L, roundTrip.activeUserId)
        assertEquals("Alice", roundTrip.activeAccount?.nickname)
        assertEquals("MUSIC_U=legacy123; __csrf=csrf999", roundTrip.activeCookie)
        assertEquals("", roundTrip.legacyCookie)
    }

    @Test
    fun upsertUpdatesExistingAccountInPlaceAndAddsAdditionalUsers() {
        var state = MultiAccountState()
        state = MultiAccountStateCodec.upsertAndActivate(
            state,
            CloudAccount(101L, "User1", "a1"),
            "MUSIC_U=u1",
        )
        state = MultiAccountStateCodec.upsertAndActivate(
            state,
            CloudAccount(202L, "User2", "a2"),
            "MUSIC_U=u2",
        )
        state = MultiAccountStateCodec.upsertAndActivate(
            state,
            CloudAccount(101L, "User1Renamed", "a1b"),
            "MUSIC_U=u1new",
        )

        val decoded = MultiAccountStateCodec.decode(MultiAccountStateCodec.encode(state))
        assertEquals(2, decoded.accounts.size)
        assertEquals(101L, decoded.accounts[0].userId)
        assertEquals("User1Renamed", decoded.accounts[0].nickname)
        assertEquals("MUSIC_U=u1new", decoded.accounts[0].cookie)
        assertEquals(202L, decoded.accounts[1].userId)
        assertEquals(101L, decoded.activeUserId)
    }

    @Test
    fun switchAndRemoveAccountsMaintainValidActiveAccount() {
        var state = MultiAccountState()
        state = MultiAccountStateCodec.upsertAndActivate(state, CloudAccount(1L, "One", ""), "MUSIC_U=1")
        state = MultiAccountStateCodec.upsertAndActivate(state, CloudAccount(2L, "Two", ""), "MUSIC_U=2")

        // Unknown switch is ignored
        assertEquals(2L, MultiAccountStateCodec.switchActive(state, 999L).activeUserId)

        // Valid switch activates target
        val switched = MultiAccountStateCodec.switchActive(state, 1L)
        assertEquals(1L, switched.activeUserId)
        assertEquals("MUSIC_U=1", switched.activeCookie)

        // Removing active falls back to remaining account
        val afterRemoveActive = MultiAccountStateCodec.removeAccount(switched, 1L)
        assertEquals(2L, afterRemoveActive.activeUserId)
        assertEquals(1, afterRemoveActive.accounts.size)

        // Deactivating clears active session while preserving stored accounts
        val deactivated = MultiAccountStateCodec.deactivateActive(afterRemoveActive)
        assertEquals(0L, deactivated.activeUserId)
        assertNull(deactivated.activeAccount)
        assertEquals("", deactivated.activeCookie)
        assertEquals(1, deactivated.accounts.size)

        // Removing last clears everything
        val empty = MultiAccountStateCodec.removeAccount(afterRemoveActive, 2L)
        assertTrue(empty.accounts.isEmpty())
        assertEquals(0L, empty.activeUserId)
        assertEquals("", MultiAccountStateCodec.encode(empty))
    }

    @Test
    fun splitCookieLinesTrimsAndSkipsEmptyParts() {
        val lines = MultiAccountStateCodec.splitCookieLines(" MUSIC_U=abc ; ; __csrf=123; NMTID=xyz ; ")
        assertEquals(listOf("MUSIC_U=abc", "__csrf=123", "NMTID=xyz"), lines)
    }

    @Test
    fun legacyQqSessionMigratesAndKeepsItsProfileAcrossRestart() {
        val cookie = "uin=o10086; qqmusic_key=existing"
        val legacy = MultiAccountStateCodec.decode(cookie)
        val migrated = MultiAccountStateCodec.migrateLegacyAccount(legacy, CloudAccount(10086L, "QQ User", "avatar"))
        val restored = MultiAccountStateCodec.decode(MultiAccountStateCodec.encode(migrated))
        assertEquals(10086L, restored.activeUserId)
        assertEquals("QQ User", restored.activeAccount?.nickname)
        assertEquals(cookie, restored.activeCookie)
        assertEquals(migrated, MultiAccountStateCodec.migrateLegacyAccount(migrated, CloudAccount(999L, "Stale profile", "")))
    }

    @Test
    fun qqAndWechatAccountsSwitchWithExactCredentialsAndSurviveLogout() {
        val qqCookie = "uin=o10086; qqmusic_key=qq"
        val wxCookie = "wxuin=1152921504606846976; qm_keyst=wx; login_type=2"
        val qq = CloudAccount(10086L, "QQ", "")
        val wx = CloudAccount(1152921504606846976L, "WeChat", "")
        val accounts = MultiAccountStateCodec.upsertAndActivate(
            MultiAccountStateCodec.upsertAndActivate(MultiAccountState(), qq, qqCookie), wx, wxCookie)
        val restored = MultiAccountStateCodec.decode(MultiAccountStateCodec.encode(accounts))
        assertEquals(wx.userId, restored.activeUserId)
        assertEquals(wxCookie, restored.activeCookie)
        val switched = MultiAccountStateCodec.switchActive(restored, qq.userId)
        assertEquals(qqCookie, switched.activeCookie)
        val loggedOut = MultiAccountStateCodec.decode(MultiAccountStateCodec.encode(MultiAccountStateCodec.deactivateActive(switched)))
        assertEquals(2, loggedOut.accounts.size)
        assertEquals("", loggedOut.activeCookie)
        val signedBackIn = MultiAccountStateCodec.switchActive(loggedOut, wx.userId)
        assertEquals(wxCookie, signedBackIn.activeCookie)
        val removed = MultiAccountStateCodec.removeAccount(signedBackIn, wx.userId)
        assertEquals(qq.userId, removed.activeUserId)
        assertEquals(qqCookie, removed.activeCookie)
    }

    @Test
    fun importingAnUnverifiedCookiePreservesOtherSavedAccounts() {
        val saved = MultiAccountStateCodec.upsertAndActivate(MultiAccountState(), CloudAccount(10086L, "Saved", ""), "uin=10086; qqmusic_key=saved")
        val pending = saved.copy(activeUserId = 0L, legacyCookie = "uin=20000; qqmusic_key=imported")
        val restored = MultiAccountStateCodec.decode(MultiAccountStateCodec.encode(pending))
        assertEquals(1, restored.accounts.size)
        assertEquals(pending.legacyCookie, restored.activeCookie)
        assertEquals(pending.legacyCookie, MultiAccountStateCodec.removeAccount(restored, 10086L).activeCookie)
        assertEquals("uin=10086; qqmusic_key=saved", MultiAccountStateCodec.switchActive(restored, 10086L).activeCookie)
    }
}
