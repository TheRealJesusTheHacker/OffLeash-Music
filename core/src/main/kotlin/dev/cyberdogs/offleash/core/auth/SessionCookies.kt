package dev.cyberdogs.offleash.core.auth

/**
 * The deliberate session-cookie subset, in canonical order.
 *
 * Sign-in capture keeps ONLY these names (Phase 2, per the cookie-auth
 * study's minimize-capture rule): the SAPISID family that feeds the
 * SAPISIDHASH, the session ids the API expects back, LOGIN_INFO, and —
 * since the 2026-10-24 sign-in hunt — the secure-prefixed PSID family
 * that carries the modern Google session. The classic seven alone did
 * not confirm a real, healthy sign-in on the owner's phone: Google's
 * endpoints now rotate and expect the `__Secure-*PSID*` companions
 * (the 3PSID is the third-party-context session token and the
 * 3PSIDTS its rotating timestamp), and the reference client — which
 * replays its whole captured string — was signed in on the same
 * phone and account throughout. The subset stays a NAMED subset:
 * anything else the browser holds (PREF, YSC, VISITOR_INFO1_LIVE,
 * CONSENT, …) is never captured, never stored, and never replayed.
 * The presence rules below (hash source, session id) are unchanged:
 * the widening adds session carriers, it does not weaken what counts
 * as a plausible session.
 */
val SESSION_COOKIE_NAMES: List<String> = listOf(
    "SAPISID",
    "__Secure-3PAPISID",
    "APISID",
    "HSID",
    "SSID",
    "SID",
    "LOGIN_INFO",
    "__Secure-3PSID",
    "__Secure-3PSIDTS",
    "__Secure-3PSIDCC",
    "__Secure-1PSID",
    "__Secure-1PAPISID",
    "__Secure-1PSIDTS",
    "__Secure-1PSIDCC",
    "SIDCC",
)

/** Parsing and presence rules for session cookies. Pure and total. */
object SessionCookies {

    /**
     * Parses a raw `Cookie`-style string ("a=1; b=2") into a name→value
     * map. Pairs are split on ';', each pair on its FIRST '=' (so values
     * containing '=' survive intact), names and values are trimmed, and
     * malformed pairs (no '=', empty name, blank pair) are skipped.
     * Later duplicates overwrite earlier ones.
     */
    fun parseCookieString(raw: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (part in raw.split(';')) {
            val pair = part.trim()
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            if (name.isEmpty()) continue
            out[name] = pair.substring(eq + 1).trim()
        }
        return out
    }

    /** Parses [raw] and keeps only the [SESSION_COOKIE_NAMES] subset. */
    fun extractSubset(raw: String): Map<String, String> {
        val parsed = parseCookieString(raw)
        val out = linkedMapOf<String, String>()
        for (name in SESSION_COOKIE_NAMES) {
            parsed[name]?.let { out[name] = it }
        }
        return out
    }

    /**
     * Merges per-host subsets into one capture, in the order the
     * hosts were read: the first NON-BLANK value for a name wins, so
     * a host holding only a blank placeholder for a name never
     * shadows another host's real value (and if every host's value
     * for a name is blank, the first blank stands — it carries no
     * credential material either way). Output is in canonical
     * [SESSION_COOKIE_NAMES] order.
     *
     * Why this exists (2026-10-27 cookie hunt): the session cookies
     * are planted across several domains — YouTube plants its own
     * copies on `.youtube.com`, Google plants the authoritative set
     * on `.google.com` — and which domain's copy a given read can see
     * depends on how the sign-in flow's redirects landed. A capture
     * that reads ONE host can therefore come back empty while the
     * session demonstrably exists in the same cookie store under a
     * sibling host. Merging every host that can hold the session
     * makes the capture independent of the landing's shape.
     */
    fun mergeSubsets(subsets: List<Map<String, String>>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (name in SESSION_COOKIE_NAMES) {
            val winner = subsets.firstNotNullOfOrNull { subset ->
                subset[name]?.takeIf { it.isNotBlank() }
            } ?: subsets.firstNotNullOfOrNull { it[name] }
            if (winner != null) out[name] = winner
        }
        return out
    }

    /** Renders a cookie map back into a raw `Cookie`-style string. */
    fun synthesizeCookieString(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }

    /**
     * The cookie value the SAPISIDHASH is computed over: SAPISID when
     * present and non-blank, else __Secure-3PAPISID when present and
     * non-blank, else null. This presence rule is deliberate (the study
     * flagged clients that silently send no Authorization unless BOTH
     * cookies exist) and is unit-tested across all four combinations.
     */
    fun hashSource(cookies: Map<String, String>): String? =
        cookies["SAPISID"]?.takeIf { it.isNotBlank() }
            ?: cookies["__Secure-3PAPISID"]?.takeIf { it.isNotBlank() }

    /**
     * Whether [cookies] plausibly represent a signed-in session: a hash
     * source exists AND a session id (SID or LOGIN_INFO) is present and
     * non-blank. Absence means signed-out — a cookie-only "signed-in"
     * state is never inferred.
     */
    fun isPlausibleSession(cookies: Map<String, String>): Boolean =
        hashSource(cookies) != null &&
            (cookies["SID"]?.isNotBlank() == true ||
                cookies["LOGIN_INFO"]?.isNotBlank() == true)
}
