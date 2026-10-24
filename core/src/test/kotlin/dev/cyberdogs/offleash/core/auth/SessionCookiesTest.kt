package dev.cyberdogs.offleash.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session-cookie subsetting, parsing, and presence rules (Phase 2).
 * All cookie strings here are synthetic test values, not real sessions.
 */
class SessionCookiesTest {

    @Test
    fun subsetKeepsExactlyTheSevenSessionNames() {
        val raw = "SAPISID=sap; __Secure-3PAPISID=tpap; APISID=api; HSID=hs; " +
            "SSID=ss; SID=sid; LOGIN_INFO=login; PREF=pref; YSC=ysc; " +
            "VISITOR_INFO1_LIVE=visitor; CONSENT=consent; OTHER=o"
        val subset = SessionCookies.extractSubset(raw)
        assertEquals(SESSION_COOKIE_NAMES.toSet(), subset.keys)
        assertEquals(7, subset.size)
        assertEquals("sap", subset["SAPISID"])
        assertEquals("login", subset["LOGIN_INFO"])
        assertFalse(subset.containsKey("PREF"))
        assertFalse(subset.containsKey("YSC"))
        assertFalse(subset.containsKey("VISITOR_INFO1_LIVE"))
        assertFalse(subset.containsKey("CONSENT"))
    }

    @Test
    fun subsetIsInCanonicalOrder() {
        val raw = "SID=sid; LOGIN_INFO=login; SAPISID=sap"
        assertEquals(
            listOf("SAPISID", "SID", "LOGIN_INFO"),
            SessionCookies.extractSubset(raw).keys.toList()
        )
    }

    @Test
    fun parseSplitsOnFirstEqualsSoValuesWithEqualsSurvive() {
        val parsed = SessionCookies.parseCookieString("LOGIN_INFO=abc=def==; SID=x")
        assertEquals("abc=def==", parsed["LOGIN_INFO"])
        assertEquals("x", parsed["SID"])
    }

    @Test
    fun parseSkipsMalformedPairsAndBlanks() {
        val parsed = SessionCookies.parseCookieString("SID=ok;;  ; noequals; =noname; SAPISID=also-ok")
        assertEquals(mapOf("SID" to "ok", "SAPISID" to "also-ok"), parsed)
        assertTrue(SessionCookies.parseCookieString("").isEmpty())
        assertTrue(SessionCookies.parseCookieString(" ; ; ").isEmpty())
    }

    @Test
    fun parseLaterDuplicatesOverwriteEarlier() {
        val parsed = SessionCookies.parseCookieString("SID=old; SID=new")
        assertEquals("new", parsed["SID"])
        assertEquals(1, parsed.size)
    }

    @Test
    fun hashSourcePrefersSapisidAndFallsBack() {
        assertEquals(
            "sap",
            SessionCookies.hashSource(mapOf("SAPISID" to "sap", "__Secure-3PAPISID" to "tpap"))
        )
        assertEquals(
            "tpap",
            SessionCookies.hashSource(mapOf("__Secure-3PAPISID" to "tpap"))
        )
        // A blank SAPISID does not shadow a usable __Secure-3PAPISID.
        assertEquals(
            "tpap",
            SessionCookies.hashSource(mapOf("SAPISID" to "  ", "__Secure-3PAPISID" to "tpap"))
        )
        assertNull(SessionCookies.hashSource(mapOf("SID" to "sid")))
        assertNull(SessionCookies.hashSource(emptyMap()))
    }

    @Test
    fun plausibilityMatrix() {
        // Hash source + SID -> plausible.
        assertTrue(SessionCookies.isPlausibleSession(mapOf("SAPISID" to "s", "SID" to "sid")))
        // Hash source (fallback) + LOGIN_INFO -> plausible.
        assertTrue(
            SessionCookies.isPlausibleSession(
                mapOf("__Secure-3PAPISID" to "t", "LOGIN_INFO" to "li")
            )
        )
        // Hash source but no session id -> not plausible.
        assertFalse(SessionCookies.isPlausibleSession(mapOf("SAPISID" to "s")))
        // Session id but no hash source -> not plausible.
        assertFalse(SessionCookies.isPlausibleSession(mapOf("SID" to "sid")))
        // Blank values count as absent.
        assertFalse(
            SessionCookies.isPlausibleSession(mapOf("SAPISID" to " ", "SID" to "sid"))
        )
        assertFalse(SessionCookies.isPlausibleSession(emptyMap()))
    }
}
