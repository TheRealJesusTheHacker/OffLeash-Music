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
    fun subsetKeepsExactlyTheNamedSessionFamily() {
        // The classic seven plus the secure-prefixed PSID family
        // (widened 2026-10-24, sign-in hunt: the modern session's
        // carriers, which the endpoints rotate and expect). The
        // subset stays NAMED — everything outside the list, however
        // session-adjacent it looks, is still discarded.
        val raw = "SAPISID=sap; __Secure-3PAPISID=tpap; APISID=api; HSID=hs; " +
            "SSID=ss; SID=sid; LOGIN_INFO=login; " +
            "__Secure-3PSID=p3; __Secure-3PSIDTS=p3ts; __Secure-3PSIDCC=p3cc; " +
            "__Secure-1PSID=p1; __Secure-1PAPISID=pap1; __Secure-1PSIDTS=p1ts; " +
            "__Secure-1PSIDCC=p1cc; SIDCC=sidcc; " +
            "PREF=pref; YSC=ysc; VISITOR_INFO1_LIVE=visitor; CONSENT=consent; OTHER=o"
        val subset = SessionCookies.extractSubset(raw)
        assertEquals(SESSION_COOKIE_NAMES.toSet(), subset.keys)
        assertEquals(15, subset.size)
        assertEquals("sap", subset["SAPISID"])
        assertEquals("login", subset["LOGIN_INFO"])
        assertEquals("p3", subset["__Secure-3PSID"])
        assertEquals("p3ts", subset["__Secure-3PSIDTS"])
        assertEquals("sidcc", subset["SIDCC"])
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

    // ------------------------------------------------------------------
    // Multi-host capture merge (2026-10-27 cookie hunt): the WebView
    // harvest reads every host that can hold the session and merges
    // the subsets. The 0.6.4 device failure was a single-host read
    // returning empty while the session sat in the same cookie store
    // under sibling hosts — these tests pin the merge that makes the
    // capture independent of which host planted what.
    // ------------------------------------------------------------------

    @Test
    fun mergeTakesTheFirstHostsValueForANames() {
        val merged = SessionCookies.mergeSubsets(
            listOf(
                mapOf("SAPISID" to "from-music", "SID" to "sid-music"),
                mapOf("SAPISID" to "from-google", "SID" to "sid-google"),
            )
        )
        assertEquals("from-music", merged["SAPISID"])
        assertEquals("sid-music", merged["SID"])
    }

    @Test
    fun mergeFillsGapsFromLaterHosts() {
        val merged = SessionCookies.mergeSubsets(
            listOf(
                emptyMap(),
                mapOf("SAPISID" to "s"),
                mapOf("SID" to "sid", "LOGIN_INFO" to "li"),
            )
        )
        assertEquals("s", merged["SAPISID"])
        assertEquals("sid", merged["SID"])
        assertEquals("li", merged["LOGIN_INFO"])
        assertTrue(SessionCookies.isPlausibleSession(merged))
    }

    @Test
    fun mergeBlankValueNeverShadowsARealOne() {
        val merged = SessionCookies.mergeSubsets(
            listOf(
                mapOf("SID" to "  "),
                mapOf("SID" to "real-sid"),
            )
        )
        assertEquals("real-sid", merged["SID"])
    }

    @Test
    fun mergeOutputIsInCanonicalOrderAndSubsetOnly() {
        val merged = SessionCookies.mergeSubsets(
            listOf(mapOf("SIDCC" to "c", "PREF" to "p", "SAPISID" to "s"))
        )
        assertEquals(listOf("SAPISID", "SIDCC"), merged.keys.toList())
    }

    @Test
    fun synthesizedStringRoundTripsThroughExtractSubset() {
        val merged = SessionCookies.mergeSubsets(
            listOf(
                mapOf("SAPISID" to "s=with=equals"),
                mapOf("SID" to "sid"),
            )
        )
        val roundTripped = SessionCookies.extractSubset(
            SessionCookies.synthesizeCookieString(merged)
        )
        assertEquals(merged, roundTripped)
    }

    // ------------------------------------------------------------------
    // Missing-pieces naming (2026-10-27 paste fix): the diagnostics
    // read the plausibility rule's gaps from this one helper, so a
    // failed paste names exactly what it lacks — names only, never
    // values.
    // ------------------------------------------------------------------

    @Test
    fun missingPiecesNamesBothGapsOnAnEmptySet() {
        assertEquals(
            listOf(
                "hash source (SAPISID or __Secure-3PAPISID)",
                "session id (SID or LOGIN_INFO)",
            ),
            SessionCookies.missingSessionPieces(emptyMap()),
        )
    }

    @Test
    fun missingPiecesNamesOnlyTheAbsentPiece() {
        assertEquals(
            listOf("session id (SID or LOGIN_INFO)"),
            SessionCookies.missingSessionPieces(mapOf("SAPISID" to "s")),
        )
        assertEquals(
            listOf("hash source (SAPISID or __Secure-3PAPISID)"),
            SessionCookies.missingSessionPieces(mapOf("LOGIN_INFO" to "li")),
        )
        // Blank values count as absent here too.
        assertEquals(
            listOf("session id (SID or LOGIN_INFO)"),
            SessionCookies.missingSessionPieces(
                mapOf("__Secure-3PAPISID" to "t", "SID" to "  "),
            ),
        )
    }

    @Test
    fun missingPiecesIsEmptyOnAPlausibleSet() {
        assertTrue(
            SessionCookies.missingSessionPieces(
                mapOf("SAPISID" to "s", "SID" to "sid"),
            ).isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // Pasted-cookie normalization (2026-10-27 paste fix): the manual
    // fallback accepts what the owner can actually grab — the raw
    // header string in its transport clothes, one pair per line,
    // and the JSON export shape. All values here are synthetic.
    // ------------------------------------------------------------------

    @Test
    fun pasteRawHeaderReadsAsItself() {
        val normalized = SessionCookies.normalizePastedCookie(
            "SAPISID=sap; SID=sid; PREF=pref"
        )
        assertEquals(PastedCookieShape.COOKIE_HEADER, normalized.shape)
        assertEquals(3, normalized.pairCount)
        assertEquals(
            mapOf("SAPISID" to "sap", "SID" to "sid"),
            SessionCookies.extractSubset(normalized.cookieString),
        )
    }

    @Test
    fun pasteCookieLabelIsStripped() {
        for (label in listOf("Cookie:", "cookie:", "COOKIE:")) {
            val normalized = SessionCookies.normalizePastedCookie(
                "$label SAPISID=sap; SID=sid"
            )
            assertEquals(
                mapOf("SAPISID" to "sap", "SID" to "sid"),
                SessionCookies.extractSubset(normalized.cookieString),
            )
        }
    }

    @Test
    fun pasteSurroundingQuotesAreStripped() {
        val doubleQuoted = SessionCookies.normalizePastedCookie(
            "\"SAPISID=sap; SID=sid\""
        )
        assertEquals(
            mapOf("SAPISID" to "sap", "SID" to "sid"),
            SessionCookies.extractSubset(doubleQuoted.cookieString),
        )
        val labeledAndQuoted = SessionCookies.normalizePastedCookie(
            "Cookie: 'SAPISID=sap; SID=sid'"
        )
        assertEquals(
            mapOf("SAPISID" to "sap", "SID" to "sid"),
            SessionCookies.extractSubset(labeledAndQuoted.cookieString),
        )
    }

    @Test
    fun pasteOnePairPerLineReadsAsOneString() {
        val normalized = SessionCookies.normalizePastedCookie(
            "SAPISID=sap\nSID=sid;\nLOGIN_INFO=login"
        )
        assertEquals(3, normalized.pairCount)
        assertEquals(
            mapOf("SAPISID" to "sap", "SID" to "sid", "LOGIN_INFO" to "login"),
            SessionCookies.extractSubset(normalized.cookieString),
        )
    }

    @Test
    fun pasteLinesAndSemicolonsMixFreely() {
        // Line breaks and ';' are interchangeable separators, and a
        // segment with no '=' is skipped by the parser rather than
        // glued onto a neighbor it might not belong to.
        val normalized = SessionCookies.normalizePastedCookie(
            "SAPISID=sap; stray-token\nSID=sid; LOGIN_INFO=login"
        )
        assertEquals(
            mapOf("SAPISID" to "sap", "SID" to "sid", "LOGIN_INFO" to "login"),
            SessionCookies.extractSubset(normalized.cookieString),
        )
    }

    @Test
    fun pasteJsonCookieArrayReadsItsPairs() {
        val json = """
            [
              {"name": "SAPISID", "value": "sap", "domain": ".youtube.com", "path": "/"},
              {"name": "SID", "value": "sid", "expirationDate": 1893456000},
              {"name": "PREF", "value": "pref"}
            ]
        """.trimIndent()
        val normalized = SessionCookies.normalizePastedCookie(json)
        assertEquals(PastedCookieShape.JSON_COOKIE_ARRAY, normalized.shape)
        assertEquals(3, normalized.pairCount)
        assertEquals(
            mapOf("SAPISID" to "sap", "SID" to "sid"),
            SessionCookies.extractSubset(normalized.cookieString),
        )
    }

    @Test
    fun pasteMalformedJsonFallsBackToTheHeaderReading() {
        val normalized = SessionCookies.normalizePastedCookie("[not a cookie export")
        assertEquals(PastedCookieShape.COOKIE_HEADER, normalized.shape)
        assertEquals(0, normalized.pairCount)
        assertTrue(
            SessionCookies.extractSubset(normalized.cookieString).isEmpty()
        )
    }

    @Test
    fun pasteJsonWithoutUsablePairsFallsBackToo() {
        val normalized = SessionCookies.normalizePastedCookie(
            "[{\"domain\": \".youtube.com\"}]"
        )
        assertEquals(PastedCookieShape.COOKIE_HEADER, normalized.shape)
        assertEquals(0, normalized.pairCount)
    }

    // ------------------------------------------------------------------
    // Netscape cookies.txt shape (2026-10-27 0.6.7 hunt): the owner's
    // own export — the format cookie extensions and yt-dlp produce —
    // is one cookie per line, seven TAB-separated fields. 0.6.6 read
    // it as a header string and found nothing in it. All values here
    // are synthetic, as everywhere in this suite.
    // ------------------------------------------------------------------

    @Test
    fun pasteNetscapeCookieFileReadsItsPairs() {
        val text = "# Netscape HTTP Cookie File\n" +
            "# Generated by a cookie tool. Do not edit.\n" +
            "\n" +
            ".youtube.com\tTRUE\t/\tFALSE\t1824657800\tPREF\ttest-pref-value\n" +
            "#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t1827681797\tSAPISID\ttest-sapisid-value\n" +
            ".youtube.com\tTRUE\t/\tTRUE\t1824657800\tSID\ttest-sid-value\n" +
            ".youtube.com\tTRUE\t/\tTRUE\t1824657800\tLOGIN_INFO\ttest-login=info=with-equals\n" +
            ".google.com\tTRUE\t/\tTRUE\t1824657800\tAPISID\ttest-apisid-value"
        val normalized = SessionCookies.normalizePastedCookie(text)
        assertEquals(PastedCookieShape.NETSCAPE_COOKIE_FILE, normalized.shape)
        assertEquals(5, normalized.pairCount)
        assertEquals(
            mapOf(
                "SAPISID" to "test-sapisid-value",
                "APISID" to "test-apisid-value",
                "SID" to "test-sid-value",
                // The value is the seventh field whole: '=' inside survives.
                "LOGIN_INFO" to "test-login=info=with-equals",
            ),
            SessionCookies.extractSubset(normalized.cookieString),
        )
        // The HttpOnly-prefixed line was read as the cookie it is,
        // not thrown away as a comment (SAPISID above proves it),
        // and the file plausibly IS a session once subsetted.
        assertTrue(
            SessionCookies.isPlausibleSession(
                SessionCookies.extractSubset(normalized.cookieString)
            )
        )
    }

    @Test
    fun pasteNetscapeRequiresTheSevenFieldShape() {
        // A six-field line (no value) and an eight-field line (a tab
        // inside what would be the value) are not cookie lines; only
        // the well-formed line's pair is read.
        val text = ".youtube.com\tTRUE\t/\tTRUE\t1824657800\tSID\n" +
            ".youtube.com\tTRUE\t/\tTRUE\t1824657800\tSAPISID\ttest-sap\textra-field\n" +
            ".youtube.com\tTRUE\t/\tTRUE\t1824657800\tAPISID\ttest-apisid-value"
        val normalized = SessionCookies.normalizePastedCookie(text)
        assertEquals(PastedCookieShape.NETSCAPE_COOKIE_FILE, normalized.shape)
        assertEquals(1, normalized.pairCount)
        assertEquals(
            mapOf("APISID" to "test-apisid-value"),
            SessionCookies.extractSubset(normalized.cookieString),
        )
    }

    @Test
    fun pasteNetscapeGarbageBlockNamesTheMissingPieces() {
        // Comments, prose, a truncated row, and one valid line for a
        // NON-session cookie: the block still reads as the Netscape
        // shape, yields no session material, and the failure is the
        // named missing pieces — names only, never a value.
        val text = "# Netscape HTTP Cookie File\n" +
            "this line is not a cookie at all\n" +
            ".youtube.com\tTRUE\t/\n" +
            ".youtube.com\tTRUE\t/\tFALSE\t1824657800\tPREF\ttest-pref-value"
        val normalized = SessionCookies.normalizePastedCookie(text)
        assertEquals(PastedCookieShape.NETSCAPE_COOKIE_FILE, normalized.shape)
        assertEquals(1, normalized.pairCount)
        val subset = SessionCookies.extractSubset(normalized.cookieString)
        assertTrue(subset.isEmpty())
        val missing = SessionCookies.missingSessionPieces(subset)
        assertEquals(
            listOf(
                "hash source (SAPISID or __Secure-3PAPISID)",
                "session id (SID or LOGIN_INFO)",
            ),
            missing,
        )
        assertFalse(missing.joinToString().contains("test-pref-value"))
    }

    @Test
    fun pasteWithoutAnyNetscapeLineKeepsTheHeaderReading() {
        // Tabs alone do not make a cookies.txt file: prose with a
        // stray tab is judged by the header reader, as before.
        val normalized = SessionCookies.normalizePastedCookie(
            "SAPISID=sap; SID=sid"
        )
        assertEquals(PastedCookieShape.COOKIE_HEADER, normalized.shape)
        assertEquals(2, normalized.pairCount)
    }
}
