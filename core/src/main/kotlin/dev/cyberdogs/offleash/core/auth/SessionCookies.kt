package dev.cyberdogs.offleash.core.auth

import dev.cyberdogs.offleash.core.parse.asObj
import dev.cyberdogs.offleash.core.parse.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

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

    /**
     * Which pieces the plausibility rule requires are ABSENT from
     * [cookies], named for the sign-in diagnostics: rule labels and
     * cookie names only — never values. Empty when the set is
     * plausible. This is the one source of that wording; the
     * diagnostics panel reads it from here so a failed paste (or
     * capture) names exactly what it is missing.
     */
    fun missingSessionPieces(cookies: Map<String, String>): List<String> {
        val missing = mutableListOf<String>()
        if (hashSource(cookies) == null) {
            missing += "hash source (SAPISID or __Secure-3PAPISID)"
        }
        if (cookies["SID"]?.isNotBlank() != true &&
            cookies["LOGIN_INFO"]?.isNotBlank() != true
        ) {
            missing += "session id (SID or LOGIN_INFO)"
        }
        return missing
    }

    /**
     * Reads a cookie the owner PASTED (the manual sign-in fallback)
     * into the canonical header string the confirmation pipeline
     * consumes. A paste arrives in whatever shape the owner's
     * browser or cookie tool handed it over in; this is the tolerant
     * reader for those shapes (2026-10-27 paste fix):
     *
     *  - the raw header string itself (`name=value; name=value`) —
     *    the primary shape, read exactly as [parseCookieString]
     *    reads any capture;
     *  - the same string wearing its transport clothes: a leading
     *    `Cookie:` label (a copied request header), one pair of
     *    surrounding quotes, stray whitespace;
     *  - one pair per line (a common export) — line breaks count
     *    as pair separators alongside `;`;
     *  - a JSON array of `{"name": ..., "value": ...}` objects, as
     *    browser cookie tools export (extra fields are ignored);
     *  - a Netscape cookies.txt export (the Get-cookies.txt /
     *    yt-dlp shape): one cookie per line, seven TAB-separated
     *    fields — domain, flag, path, secure, expiry, name, value.
     *    Comment lines (`#…`) carry no cookies and are ignored;
     *    HttpOnly cookies wear a `#HttpOnly_` prefix on the domain
     *    field, which is stripped so the line still reads as the
     *    cookie it is. Only the name and value fields are taken.
     *
     * Total by construction: a paste that matches no shape is read
     * as a header string and judged by the subset stage like any
     * other capture — normalization never throws and never invents
     * pairs. The result carries the shape and the pair count so the
     * diagnostics can say what the paste was read as (counts and
     * shape only — never values).
     */
    fun normalizePastedCookie(raw: String): NormalizedPaste {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            parseJsonCookieArray(trimmed)?.let { pairs ->
                return NormalizedPaste(
                    cookieString = pairs.entries.joinToString("; ") { (name, value) ->
                        "$name=$value"
                    },
                    shape = PastedCookieShape.JSON_COOKIE_ARRAY,
                    pairCount = pairs.size,
                )
            }
            // Not a JSON cookie export after all — fall through and
            // let the header reader judge what's there.
        }
        parseNetscapeCookieLines(trimmed)?.let { pairs ->
            return NormalizedPaste(
                cookieString = pairs.entries.joinToString("; ") { (name, value) ->
                    "$name=$value"
                },
                shape = PastedCookieShape.NETSCAPE_COOKIE_FILE,
                pairCount = pairs.size,
            )
        }
        val cleaned = cleanPastedHeader(trimmed)
        return NormalizedPaste(
            cookieString = cleaned,
            shape = PastedCookieShape.COOKIE_HEADER,
            pairCount = parseCookieString(cleaned).size,
        )
    }

    /** The header-shape cleaning half of [normalizePastedCookie]. */
    private fun cleanPastedHeader(text: String): String {
        // One pair of surrounding quotes is packaging, not cookie —
        // stripped before AND after the label, since a copied header
        // can arrive as `Cookie: "a=1; b=2"` as well as `"Cookie: …"`.
        fun unwrapQuotes(s: String): String {
            val t = s.trim()
            return if (t.length >= 2 &&
                (t.first() == '"' && t.last() == '"' || t.first() == '\'' && t.last() == '\'')
            ) {
                t.substring(1, t.length - 1).trim()
            } else {
                t
            }
        }
        var t = unwrapQuotes(text)
        // A copied request header arrives with its label attached.
        val label = "cookie:"
        if (t.regionMatches(0, label, 0, label.length, ignoreCase = true)) {
            t = unwrapQuotes(t.substring(label.length))
        }
        // One pair per line is a common export; a single line of
        // pairs is the other. Both are the same string once line
        // breaks count as pair separators alongside ';' — the
        // parser then judges each segment exactly as it judges a
        // captured one (a segment with no '=' is skipped, never
        // glued onto a neighbor it might not belong to).
        return t.split('\n')
            .map { it.trim().trim(';').trim() }
            .filter { it.isNotEmpty() }
            .joinToString("; ")
    }

    /**
     * The JSON-export shape of [normalizePastedCookie]: an array of
     * objects with string `name`/`value` members. Returns null when
     * the text is not that shape at all (unparseable, not an array,
     * or no usable pair in it) so the caller can fall back to the
     * header reading.
     */
    private fun parseJsonCookieArray(text: String): Map<String, String>? {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return null
        } catch (t: Throwable) {
            // normalizePastedCookie's contract is "never throws": a
            // bracket-bomb paste overflows the recursive parser with
            // an Error — that is "not a JSON cookie export" too, and
            // the header reading gets to judge it (round 2).
            return null
        }
        val array = root as? JsonArray ?: return null
        val out = linkedMapOf<String, String>()
        for (element in array) {
            val obj = element.asObj() ?: continue
            val name = obj.str("name")?.takeIf { it.isNotBlank() } ?: continue
            val value = obj.str("value") ?: continue
            out[name] = value
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** The domain-field prefix HttpOnly cookies wear in a cookies.txt file. */
    private const val NETSCAPE_HTTP_ONLY_PREFIX = "#HttpOnly_"

    /**
     * The Netscape cookies.txt shape of [normalizePastedCookie]:
     * one cookie per line, seven TAB-separated fields (domain,
     * tail-match flag, path, secure flag, expiry, name, value).
     * Comment lines (`#…`) are file furniture, not cookies, and are
     * skipped — except that HttpOnly cookies are written with a
     * `#HttpOnly_` prefix on the domain field, so that prefix is
     * stripped BEFORE the comment check or every HttpOnly cookie in
     * the file would be thrown away as a comment. A line counts as
     * a cookie line only at exactly seven fields; any other line
     * (headers, prose, a truncated row) is skipped like a malformed
     * pair in the header reading, never glued onto a neighbor.
     * The value is the seventh field whole — it may itself contain
     * `=`. Later lines overwrite earlier ones, as in
     * [parseCookieString].
     *
     * Returns null when NO line in the text is a cookie line, so
     * the caller can fall back to the header reading and have the
     * subset stage judge what is actually there.
     */
    private fun parseNetscapeCookieLines(text: String): Map<String, String>? {
        val out = linkedMapOf<String, String>()
        for (line in text.split('\n')) {
            val raw = line.trimEnd('\r')
            if (raw.isBlank()) continue
            val stripped = raw.removePrefix(NETSCAPE_HTTP_ONLY_PREFIX)
            if (stripped.startsWith("#")) continue
            val fields = stripped.split('\t')
            if (fields.size != 7) continue
            val name = fields[5].trim()
            if (name.isEmpty()) continue
            out[name] = fields[6].trim()
        }
        return out.takeIf { it.isNotEmpty() }
    }
}

/** The shapes a pasted cookie can arrive in (see [SessionCookies.normalizePastedCookie]). */
enum class PastedCookieShape {
    /** A `Cookie`-header style string: `name=value; name=value`. */
    COOKIE_HEADER,

    /** A JSON array of `{"name": ..., "value": ...}` objects (browser cookie-tool exports). */
    JSON_COOKIE_ARRAY,

    /** A Netscape cookies.txt file: one cookie per line, seven TAB-separated fields. */
    NETSCAPE_COOKIE_FILE,
}

/**
 * A paste, normalized (see [SessionCookies.normalizePastedCookie]):
 * [cookieString] is the canonical header string the confirmation
 * pipeline consumes; [shape] records what the paste was read as and
 * [pairCount] how many cookie pairs it carried. Shape and count are
 * diagnostics-safe — they are not credential material.
 */
data class NormalizedPaste(
    val cookieString: String,
    val shape: PastedCookieShape,
    val pairCount: Int,
)
