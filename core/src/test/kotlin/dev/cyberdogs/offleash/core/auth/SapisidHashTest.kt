package dev.cyberdogs.offleash.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAPISIDHASH construction (Phase 2).
 *
 * Known-vector derivation, computed INDEPENDENTLY of this codebase with
 * Python hashlib while writing the test:
 *   python3 -c "import hashlib; print(hashlib.sha1(
 *     b'1700000000 TestSapisidValue123 https://music.youtube.com').hexdigest())"
 *     -> f570ea8ec6bbdf7e766ef2f241a1ec3829fafed4
 *   python3 -c "import hashlib; print(hashlib.sha1(
 *     b'1700000000 Fallback3PValue456 https://music.youtube.com').hexdigest())"
 *     -> b90dcac2f46fbfba3b09c3071d648a943b850adb
 * Recipe: header value is "<epochSeconds>_<sha1hex>" where the digest
 * input is "<epochSeconds> <cookieValue> https://music.youtube.com"
 * with the RAW epoch seconds (no /1000).
 */
class SapisidHashTest {

    private val ts = 1700000000L

    @Test
    fun knownVectorSapisid() {
        val session = AuthSession(cookies = mapOf("SAPISID" to "TestSapisidValue123"))
        assertEquals(
            "SAPISIDHASH 1700000000_f570ea8ec6bbdf7e766ef2f241a1ec3829fafed4",
            session.authorizationHeader(ts)
        )
    }

    @Test
    fun knownVectorFallbackOnly() {
        val session = AuthSession(cookies = mapOf("__Secure-3PAPISID" to "Fallback3PValue456"))
        assertEquals(
            "SAPISIDHASH 1700000000_b90dcac2f46fbfba3b09c3071d648a943b850adb",
            session.authorizationHeader(ts)
        )
    }

    @Test
    fun knownVectorWwwOrigin() {
        // python3 -c "import hashlib; print(hashlib.sha1(
        //   b'1700000000 TestSapisidValue123 https://www.youtube.com'
        // ).hexdigest())" -> 54c129c6535bc481b3b18ae261a47031c5366ff1
        // The account-switcher call is minted for the www origin; the
        // digest MUST differ from the music-origin value for the same
        // inputs, or the origin term is not really in the recipe.
        val session = AuthSession(cookies = mapOf("SAPISID" to "TestSapisidValue123"))
        assertEquals(
            "SAPISIDHASH 1700000000_54c129c6535bc481b3b18ae261a47031c5366ff1",
            session.authorizationHeader(ts, "https://www.youtube.com")
        )
        assertEquals(
            session.authorizationHeader(ts, "https://www.youtube.com"),
            session.headers(ts, "https://www.youtube.com")["Authorization"]
        )
    }

    @Test
    fun sapisidWinsWhenBothPresent() {
        val session = AuthSession(
            cookies = mapOf(
                "SAPISID" to "TestSapisidValue123",
                "__Secure-3PAPISID" to "Fallback3PValue456",
            )
        )
        assertEquals(
            "SAPISIDHASH 1700000000_f570ea8ec6bbdf7e766ef2f241a1ec3829fafed4",
            session.authorizationHeader(ts)
        )
    }

    @Test
    fun noHashSourceMeansNoAuthorizationAnywhere() {
        val session = AuthSession(cookies = mapOf("SID" to "sid", "APISID" to "api"))
        assertNull(session.authorizationHeader(ts))
        assertFalse(session.headers(ts).containsKey("Authorization"))
    }

    @Test
    fun headersCarryTheFullAuthSet() {
        val session = AuthSession(
            cookies = mapOf("SAPISID" to "TestSapisidValue123", "SID" to "sid"),
            authUser = 2,
            pageId = "UCpage",
            visitorData = "CgVisitorData",
        )
        val headers = session.headers(ts)
        assertEquals("SAPISID=TestSapisidValue123; SID=sid", headers["Cookie"])
        assertEquals(
            "SAPISIDHASH 1700000000_f570ea8ec6bbdf7e766ef2f241a1ec3829fafed4",
            headers["Authorization"]
        )
        assertEquals("2", headers["X-Goog-Authuser"])
        assertEquals("UCpage", headers["X-Goog-Pageid"])
        assertEquals("CgVisitorData", headers["X-Goog-Visitor-Id"])
    }

    @Test
    fun headersOmitPageidAndVisitorWhenUnsetButAlwaysSendAuthuser() {
        val session = AuthSession(cookies = mapOf("SAPISID" to "s", "SID" to "sid"))
        val headers = session.headers(ts)
        assertEquals("0", headers["X-Goog-Authuser"])
        assertFalse(headers.containsKey("X-Goog-Pageid"))
        assertFalse(headers.containsKey("X-Goog-Visitor-Id"))
        assertTrue(headers.containsKey("Cookie"))
        assertTrue(headers.containsKey("Authorization"))
    }

    @Test
    fun cookieHeaderUsesCanonicalOrderNotMapOrder() {
        val session = AuthSession(
            cookies = mapOf("LOGIN_INFO" to "li", "SID" to "sid", "SAPISID" to "sap")
        )
        assertEquals("SAPISID=sap; SID=sid; LOGIN_INFO=li", session.cookieHeader())
    }
}
