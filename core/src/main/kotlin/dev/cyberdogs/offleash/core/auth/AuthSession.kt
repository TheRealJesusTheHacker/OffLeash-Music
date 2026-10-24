package dev.cyberdogs.offleash.core.auth

import dev.cyberdogs.offleash.core.model.AccountIdentity
import java.security.MessageDigest

/**
 * An authenticated YouTube Music session (Phase 2): the deliberate
 * cookie subset captured at sign-in plus the per-identity routing
 * values learned from the account switcher.
 *
 * Cookie values are credentials. They are never logged, never shown,
 * and never leave `:core` except inside the Cookie / Authorization
 * headers built here.
 */
data class AuthSession(
    val cookies: Map<String, String>,
    val authUser: Int = 0,
    val pageId: String? = null,
    val visitorData: String? = null,
) {

    /** The `Cookie` header value: present subset pairs, canonical order. */
    fun cookieHeader(): String =
        SESSION_COOKIE_NAMES.mapNotNull { name ->
            cookies[name]?.takeIf { it.isNotBlank() }?.let { "$name=$it" }
        }.joinToString("; ")

    /**
     * The complete `Authorization` header value in the classic
     * SAPISIDHASH form, `"SAPISIDHASH <epochSeconds>_<sha1hex>"`, where
     * the digest is SHA-1 over
     * `"<epochSeconds> <hashValue> https://music.youtube.com"` and
     * hashValue is [SessionCookies.hashSource]. The timestamp term is
     * the raw epoch SECONDS — the study flagged a `/1000` variant in the
     * wild as off-recipe; it is deliberately not inherited here.
     *
     * Returns null when the session has no hash source, so callers send
     * Cookie-only rather than a malformed Authorization.
     *
     * Coordinator correction at integration (2026-10-23): the value
     * carries the `SAPISIDHASH` scheme word — [headers] places this
     * string verbatim into the `Authorization` header, and the wire
     * protocol is `Authorization: SAPISIDHASH <ts>_<hex>`.
     */
    fun authorizationHeader(epochSeconds: Long): String? {
        val hashValue = SessionCookies.hashSource(cookies) ?: return null
        val digest = sha1Hex("$epochSeconds $hashValue https://music.youtube.com")
        return "SAPISIDHASH ${epochSeconds}_$digest"
    }

    /**
     * The full auth header set for one request: Cookie (always),
     * Authorization (when a hash source exists), X-Goog-Authuser
     * (always, from [authUser]), X-Goog-Pageid (when [pageId] is set),
     * and X-Goog-Visitor-Id (when [visitorData] is set).
     */
    fun headers(epochSeconds: Long): Map<String, String> {
        val out = linkedMapOf<String, String>()
        out["Cookie"] = cookieHeader()
        authorizationHeader(epochSeconds)?.let { out["Authorization"] = it }
        out["X-Goog-Authuser"] = authUser.toString()
        pageId?.let { out["X-Goog-Pageid"] = it }
        visitorData?.let { out["X-Goog-Visitor-Id"] = it }
        return out
    }

    private fun sha1Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

/** The outcome of asking the account switcher who a session belongs to. */
sealed interface IdentityLookup {
    /** The endpoint answered; [identities] may be empty (signed-out). */
    data class Found(val identities: List<AccountIdentity>) : IdentityLookup

    /** HTTP 401/403 — the session itself was refused. */
    data class Rejected(val httpStatus: Int) : IdentityLookup

    /** Transport failure, an unexpected status, or an unreadable body. */
    data class Failed(val detail: String?) : IdentityLookup
}

/**
 * The health of a stored session, as the app acts on it. A network
 * failure maps to [Unreachable], never to [NeedsSignIn]: an offline
 * phone must not read as a dead session.
 */
sealed interface SessionValidation {
    data class Healthy(val identities: List<AccountIdentity>) : SessionValidation
    object NeedsSignIn : SessionValidation
    data class Unreachable(val detail: String?) : SessionValidation
}
