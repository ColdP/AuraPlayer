package btm.m.auraplayer.data

import org.junit.Assert.*
import org.junit.Test

class QqSessionTest {
    @Test
    fun loginCookieKeepsPageCredentialsAndWechatMarkers() {
        val cookie = QqSession.loginCookie(listOf(
            "qqmusic_key=fresh=key; uin=o0010086; wxopenid=openid; login_type=2; wxuin=1152921504606846976; unrelated=ignored",
            "qqmusic_key=stale; uin=o99999; skey=legacy",
        ))
        val session = QqSession(cookie)
        assertEquals("fresh=key", session.cookies["qqmusic_key"])
        assertEquals("o0010086", session.cookies["uin"])
        assertEquals("openid", session.cookies["wxopenid"])
        assertEquals("legacy", session.cookies["skey"])
        assertFalse(session.cookies.containsKey("unrelated"))
        assertEquals("1152921504606846976", session.uin())
        assertTrue(session.hasCredentials)
    }

    @Test
    fun duplicateCookieNamesKeepFirstSpecificValue() {
        val session = QqSession("uin=o10086; qqmusic_key=current; qqmusic_key=stale; uin=o99999")
        assertEquals("10086", session.uin())
        assertEquals("current", session.cookies["qqmusic_key"])
    }

    @Test
    fun accountAliasesAndEmptyValuesAreHandledConsistently() {
        assertEquals("10086", QqSession("uin=; p_uin=o0010086").uin())
        assertEquals("10086", QqSession("qqmusic_uin=10086").uin())
        assertEquals("10086", QqSession("uin=0; p_uin=o10086").uin())
        assertTrue(QqSession("qqmusic_uin=10086; qm_keyst=key").hasCredentials)
        assertEquals("1152921504606846976", QqSession("uin=o10086; wxuin=1152921504606846976; wxopenid=openid").uin())
        assertEquals("10086", QqSession("uin=o10086; wxuin=1152921504606846976; login_type=1").uin())
    }

    @Test
    fun incompleteOrEncryptedIdentityCannotCompleteLogin() {
        for (cookie in listOf("uin=o10086; skey=key", "euin=encrypted123; qqmusic_key=key", "uin=0; qqmusic_key=key", "qqmusic_key=key")) {
            assertFalse(QqSession(cookie).hasCredentials)
        }
    }

    @Test
    fun csrfTokensMatchWebsiteKeyPriority() {
        // DJB hashes for the independent single-character keys a=177670 and b=177671.
        val session = QqSession("skey=a; qqmusic_key=b")
        assertEquals(177670L, session.gtk())
        assertEquals(177671L, session.gtk(modern = true))
        assertEquals(177671L, QqSession("qqmusic_key=; qm_keyst=b; p_skey=a").gtk(modern = true))
        assertEquals(5381L, QqSession("").gtk())
        assertEquals(5381L, QqSession("").gtk(modern = true))
    }
}
