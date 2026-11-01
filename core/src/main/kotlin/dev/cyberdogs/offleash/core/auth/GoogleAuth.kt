package dev.cyberdogs.offleash.core.auth

import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.parse.asObj
import dev.cyberdogs.offleash.core.parse.str
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Google OAuth 2.0 for limited-input devices (the "device-code
 * flow", RFC 8628) — the sign-in OffLeash 0.7.0 is built on, and
 * the pattern Google's own TV-class YouTube clients use (SmartTube
 * is the category's proof): the app asks Google for a short code,
 * the owner types it at `google.com/device` in any browser and
 * approves with their Google account, and the app polls for the
 * token. No password is ever typed into the app, no WebView session
 * exists, and no cookie is captured, stored, or replayed.
 *
 * WHICH client asks is a build-and-setup fact, not a source fact
 * (0.8.6): through 0.8.5 this flow ran under the credentials
 * embedded in YouTube's own TV client — a public, installed-app
 * pair shared by every copy of that client — and Google refused
 * that client's tokens on the account-library surfaces with a
 * uniform HTTP 400, on every request shape the hunts presented
 * (decision brief 2026-11-01, Road B). The pair is therefore no
 * longer carried in source at all: a build is supplied an
 * [OAuthClientIdentity] at build time or the owner enters their
 * own registration in the app's setup wizard, and a build with
 * neither cannot start a sign-in — honestly, at the first call,
 * instead of borrowing a stranger's client again. The security
 * boundary is unchanged: the owner's own Google approval on
 * Google's page, plus the token's custody on this device.
 *
 * TOTALITY: every function here returns a result value and never
 * throws. Transport failures, unexpected statuses, and malformed
 * bodies all land in the `Failed(detail)` cases, where `detail` is
 * the server's error code or the failure's class — NEVER token
 * material (details are written to the sign-in diagnostics).
 */
object GoogleOAuth {

    const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    const val USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"

    /**
     * The YouTube scope — the one the sign-in fix recipe names
     * exactly (decision brief 2026-11-01, Road B): a token issued
     * under the owner's own client registration with this scope is
     * what the account-library surfaces are asked to accept.
     */
    const val YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube"

    /**
     * The exact scopes this build requests — what the surfaces need
     * and no more: [YOUTUBE_SCOPE] first (read the account's
     * YouTube Music library, home personalization and liked songs,
     * resolve playback, and write likes back — the InnerTube
     * surfaces the app calls with the resulting Bearer token
     * enforce this scope), then `openid` + `profile` + `email` to
     * name the account (name, avatar, email) through the userinfo
     * endpoint. The delta against the brief's bare recipe is those
     * three identity scopes, retained deliberately: the sign-in
     * identity path (userinfo, account naming, the health check)
     * runs on them, and dropping them would break the account
     * block to satisfy a recipe written for the library call alone.
     */
    const val SCOPE = "$YOUTUBE_SCOPE openid profile email"

    const val GRANT_TYPE_DEVICE_CODE =
        "urn:ietf:params:oauth:grant-type:device_code"

    /** Where the owner approves: shown verbatim in the sign-in UI. */
    const val VERIFICATION_URL = "https://www.google.com/device"
}

/**
 * Which class of registration issued (or will issue) a sign-in
 * (0.8.6). The class — never the pair itself — is what diagnostics
 * may name and what a sealed session records about its issuer:
 * tokens are bound to the client that issued them, so the class
 * is the whole of what the client-change gate needs to know.
 */
enum class OAuthClientKind(val label: String) {

    /** The owner's own Google Cloud project registration. */
    OWNER("owner"),

    /**
     * A shared, borrowed registration a build was supplied with.
     * Pre-0.8.6 sealed sessions carry no marker and read as this
     * class: the shared TV pair is the only issuer they can have.
     */
    SHARED("shared"),
}

/**
 * A client pair plus its class, supplied to the flow from outside
 * the source tree: injected at build time, or entered by the owner
 * in the app's setup wizard and kept sealed on the device. Never
 * logged, never written to diagnostics — [kind] is the only part
 * of an identity any surface may name.
 */
data class OAuthClientIdentity(
    val clientId: String,
    val clientSecret: String,
    val kind: OAuthClientKind,
) {

    companion object {

        /**
         * Resolves raw build/wizard supplies into an identity.
         * Total, with the failure shapes named: an all-blank supply
         * is [OAuthClientResolution.Absent] (a build that simply
         * carries no pair — sign-in is unavailable, everything else
         * works); a half-supply is
         * [OAuthClientResolution.Malformed], a build defect to
         * surface in diagnostics, never a pair to half-use.
         */
        fun resolve(
            clientId: String?,
            clientSecret: String?,
            kindLabel: String?,
        ): OAuthClientResolution {
            val id = clientId?.trim().orEmpty()
            val secret = clientSecret?.trim().orEmpty()
            val label = kindLabel?.trim().orEmpty()
            if (id.isEmpty() && secret.isEmpty() && label.isEmpty()) {
                return OAuthClientResolution.Absent
            }
            if (id.isEmpty() && secret.isEmpty()) {
                return OAuthClientResolution.Malformed(
                    "a client class arrived with no client pair",
                )
            }
            if (id.isEmpty()) {
                return OAuthClientResolution.Malformed(
                    "a client secret arrived with no client id",
                )
            }
            if (secret.isEmpty()) {
                return OAuthClientResolution.Malformed(
                    "a client id arrived with no client secret",
                )
            }
            if (label.isEmpty()) {
                return OAuthClientResolution.Malformed(
                    "a client pair arrived with no client class",
                )
            }
            val kind = when (label) {
                OAuthClientKind.OWNER.label -> OAuthClientKind.OWNER
                OAuthClientKind.SHARED.label -> OAuthClientKind.SHARED
                else -> return OAuthClientResolution.Malformed(
                    "an unknown client class arrived",
                )
            }
            return OAuthClientResolution.Configured(
                OAuthClientIdentity(id, secret, kind),
            )
        }
    }
}

/** The outcome of resolving a supplied client pair. */
sealed interface OAuthClientResolution {

    /** A complete, well-classed pair: sign-in runs under it. */
    data class Configured(val identity: OAuthClientIdentity) :
        OAuthClientResolution

    /** Nothing was supplied: sign-in is unavailable in this build. */
    object Absent : OAuthClientResolution

    /** A defective supply, described in plain words for diagnostics. */
    data class Malformed(val detail: String) : OAuthClientResolution
}

/** What a session sealed under one client class does at a class change. */
enum class ClientGateDecision {

    /** Same class as the build's: the session carries on untouched. */
    CARRY,

    /**
     * A different class: the sealed tokens can never refresh under
     * this build's client, so the session ends locally, once, with
     * the honest reason — a clean re-sign-in, never an error loop.
     */
    CLEAN_RESIGN,

    /**
     * The build carries no client at all: the sealed session can
     * never refresh here either, so it ends the same clean way.
     */
    CLEAR_UNCONFIGURED,
}

/**
 * The client-change gate (0.8.6): decides a sealed session's fate
 * from its recorded issuer class and the build's current class.
 * Pre-marker sessions (sealed before 0.8.6, or by any build that
 * recorded nothing) read as [OAuthClientKind.SHARED] — the only
 * issuer they can have had is the shared pair this build replaces.
 */
object ClientChangeGate {

    fun decide(
        sealedClientLabel: String?,
        buildKind: OAuthClientKind?,
    ): ClientGateDecision {
        if (buildKind == null) return ClientGateDecision.CLEAR_UNCONFIGURED
        val sealedKind = if (sealedClientLabel == OAuthClientKind.OWNER.label) {
            OAuthClientKind.OWNER
        } else {
            OAuthClientKind.SHARED
        }
        return if (sealedKind == buildKind) {
            ClientGateDecision.CARRY
        } else {
            ClientGateDecision.CLEAN_RESIGN
        }
    }
}

/**
 * The one diagnostics line naming the sign-in client class in use
 * (`signin: client=owner` / `client=shared` / `client=unconfigured`
 * once the stage is prefixed) — the class only, never a value.
 */
fun signInClientLine(kind: OAuthClientKind?): String =
    "client=" + (kind?.label ?: "unconfigured")

/** One issued device code, ready to show the owner. */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Long,
    val expiresInSeconds: Long,
)

sealed interface DeviceCodeResult {
    data class Issued(val code: DeviceCode) : DeviceCodeResult
    data class Failed(val detail: String) : DeviceCodeResult
}

/** A granted token set. [refreshToken] is null when Google didn't rotate one. */
data class GoogleTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
    val tokenType: String?,
    val scope: String?,
    val idToken: String?,
)

sealed interface TokenPollResult {
    data class Granted(val tokens: GoogleTokens) : TokenPollResult

    /** The owner hasn't approved yet — keep polling at the interval. */
    object Pending : TokenPollResult

    /** Google demands a longer interval; poll again after the added delay. */
    object SlowDown : TokenPollResult

    /** The owner declined the request on Google's page. */
    object Denied : TokenPollResult

    /** The device code expired before approval. */
    object Expired : TokenPollResult

    /**
     * The device code itself is no longer valid at Google
     * (`invalid_grant`): it was revoked, consumed, or never stood.
     * Unlike a transport failure this is TERMINAL — the code can
     * never be approved, so retrying against it would wait on a
     * zombie until its nominal expiry. (Added by the sign-in
     * perfection pass: until then this answer fell into [Failed],
     * which KEEPS the code and tells the owner it "is still good
     * until it expires" — a dead code presented as a live one.)
     */
    object InvalidGrant : TokenPollResult

    /**
     * A TRANSIENT failure (0.8.1 hotfix): the poll never got a
     * protocol answer — a transport exception (DNS, timeout, IO)
     * or an HTTP 5xx from Google's side. Nothing about the code's
     * standing changed, so the caller keeps watching: the owner's
     * first 0.8.0 attempt died when one UnknownHostException in a
     * run of clean pending polls was mapped to [Failed] and the
     * manager ended the approval watch on a terminal card, in the
     * middle of the window where the owner was about to approve.
     * Distinct from [Failed], which now means an answer arrived
     * and it was neither a named state nor retryable.
     */
    data class Transient(val detail: String) : TokenPollResult

    data class Failed(val detail: String) : TokenPollResult
}

sealed interface RefreshResult {
    data class Refreshed(val tokens: GoogleTokens) : RefreshResult

    /**
     * Google says the grant is gone (revoked, or the owner removed
     * the app's access): the stored tokens are dead and must be
     * wiped — signing in again is the only road back.
     */
    object Revoked : RefreshResult

    data class Failed(val detail: String) : RefreshResult
}

/**
 * The device-flow client: code issuance, polling, refresh, and
 * revocation against Google's OAuth endpoints, over the app's
 * shared [HttpTransport]. Synchronous/blocking like the rest of
 * core — callers run it on an IO dispatcher.
 *
 * [identity] is the client the flow asks as (0.8.6): supplied at
 * build time or by the owner's setup wizard, and replaceable at
 * runtime — the wizard's save switches the app to the owner's pair
 * by assigning the new identity here and running the session
 * manager's client-change gate. A null identity is a build with
 * no sign-in: code issuance, polling, and refresh each fail at
 * once with [NOT_CONFIGURED_DETAIL] rather than borrowing a pair,
 * while revocation (which names no client) still works.
 */
class GoogleDeviceAuth(
    private val transport: HttpTransport,
    var identity: OAuthClientIdentity?,
) {

    /** Asks Google for a fresh device code to show the owner. */
    fun requestDeviceCode(): DeviceCodeResult {
        val client = identity
            ?: return DeviceCodeResult.Failed(NOT_CONFIGURED_DETAIL)
        val response = try {
            transport.postForm(
                GoogleOAuth.DEVICE_CODE_URL,
                FORM_HEADERS,
                formBody(
                    "client_id" to client.clientId,
                    "scope" to GoogleOAuth.SCOPE,
                ),
            )
        } catch (e: Exception) {
            return DeviceCodeResult.Failed("transport: ${e.javaClass.simpleName}")
        }
        val root = parseJsonObject(response.body)
            ?: return DeviceCodeResult.Failed("HTTP ${response.status}: unparseable body")
        if (response.status != 200) {
            return DeviceCodeResult.Failed(
                "HTTP ${response.status}: ${root.str("error") ?: "no error code"}"
            )
        }
        val deviceCode = root.str("device_code")
            ?: return DeviceCodeResult.Failed("response carried no device_code")
        val userCode = root.str("user_code")
            ?: return DeviceCodeResult.Failed("response carried no user_code")
        return DeviceCodeResult.Issued(
            DeviceCode(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUrl = root.str("verification_url")
                    ?: GoogleOAuth.VERIFICATION_URL,
                intervalSeconds = root.long("interval") ?: DEFAULT_INTERVAL_SECONDS,
                expiresInSeconds = root.long("expires_in") ?: DEFAULT_EXPIRES_SECONDS,
            )
        )
    }

    /**
     * One poll of the token endpoint for [deviceCode]. The protocol's
     * answer set is mapped by the book (RFC 8628 §3.5): 200 grants;
     * `authorization_pending` keeps waiting; `slow_down` lengthens
     * the interval; `access_denied`, `expired_token`, and
     * `invalid_grant` (the code no longer stands) end the attempt as
     * named states, not errors.
     */
    fun pollForToken(deviceCode: String): TokenPollResult {
        val client = identity
            ?: return TokenPollResult.Failed(NOT_CONFIGURED_DETAIL)
        val response = try {
            transport.postForm(
                GoogleOAuth.TOKEN_URL,
                FORM_HEADERS,
                formBody(
                    "client_id" to client.clientId,
                    "client_secret" to client.clientSecret,
                    "device_code" to deviceCode,
                    "grant_type" to GoogleOAuth.GRANT_TYPE_DEVICE_CODE,
                ),
            )
        } catch (e: Exception) {
            // No answer at all arrived (DNS, timeout, IO): the
            // code's standing is unknown, not bad — transient
            // (0.8.1 hotfix; this was Failed until the owner's
            // 0.8.0 attempt died on a single DNS hiccup).
            return TokenPollResult.Transient("transport: ${e.javaClass.simpleName}")
        }
        if (response.status == 200) {
            val root = parseJsonObject(response.body)
                ?: return TokenPollResult.Failed("grant body was not valid JSON")
            val tokens = parseTokens(root)
            if (tokens.accessToken.isBlank()) {
                return TokenPollResult.Failed("grant carried no access token")
            }
            return TokenPollResult.Granted(tokens)
        }
        val root = parseJsonObject(response.body)
        return when (val error = root?.str("error")) {
            "authorization_pending" -> TokenPollResult.Pending
            "slow_down" -> TokenPollResult.SlowDown
            "access_denied" -> TokenPollResult.Denied
            "expired_token" -> TokenPollResult.Expired
            // The same verdict the refresh path reads as Revoked:
            // the grant behind this device code no longer exists.
            // Terminal, like Expired — never a kept-code failure.
            "invalid_grant" -> TokenPollResult.InvalidGrant
            else -> if (response.status in 500..599) {
                // Google's side hiccuped; the code's standing is
                // not in question. Transient, like a transport
                // failure (0.8.1 hotfix).
                TokenPollResult.Transient(
                    "HTTP ${response.status}: ${error ?: "no error code"}"
                )
            } else {
                TokenPollResult.Failed(
                    "HTTP ${response.status}: ${error ?: "no error code"}"
                )
            }
        }
    }

    /** Exchanges a stored refresh token for a fresh access token. */
    fun refreshTokens(refreshToken: String): RefreshResult {
        val client = identity
            ?: return RefreshResult.Failed(NOT_CONFIGURED_DETAIL)
        val response = try {
            transport.postForm(
                GoogleOAuth.TOKEN_URL,
                FORM_HEADERS,
                formBody(
                    "client_id" to client.clientId,
                    "client_secret" to client.clientSecret,
                    "refresh_token" to refreshToken,
                    "grant_type" to "refresh_token",
                ),
            )
        } catch (e: Exception) {
            return RefreshResult.Failed("transport: ${e.javaClass.simpleName}")
        }
        if (response.status == 200) {
            val root = parseJsonObject(response.body)
                ?: return RefreshResult.Failed("refresh body was not valid JSON")
            val tokens = parseTokens(root)
            if (tokens.accessToken.isBlank()) {
                return RefreshResult.Failed("refresh carried no access token")
            }
            return RefreshResult.Refreshed(tokens)
        }
        val root = parseJsonObject(response.body)
        val error = root?.str("error")
        // invalid_grant is Google's verdict that the grant no longer
        // exists — revoked by the owner, or the token rotated out.
        return if (error == "invalid_grant") {
            RefreshResult.Revoked
        } else {
            RefreshResult.Failed("HTTP ${response.status}: ${error ?: "no error code"}")
        }
    }

    /**
     * Revokes a token at Google (RFC 7009): after this, the token —
     * and, for a refresh token, the whole grant — stops working
     * everywhere, not just on this device. Sign-out calls this with
     * the refresh token when one exists. Best effort by contract:
     * returns whether Google confirmed; never throws.
     */
    fun revokeToken(token: String): Boolean {
        val response = try {
            transport.postForm(
                GoogleOAuth.REVOKE_URL,
                FORM_HEADERS,
                formBody("token" to token),
            )
        } catch (e: Exception) {
            return false
        }
        return response.status == 200
    }

    private fun parseTokens(root: JsonObject): GoogleTokens = GoogleTokens(
        accessToken = root.str("access_token") ?: "",
        refreshToken = root.str("refresh_token"),
        expiresInSeconds = root.long("expires_in") ?: DEFAULT_TOKEN_LIFETIME_SECONDS,
        tokenType = root.str("token_type"),
        scope = root.str("scope"),
        idToken = root.str("id_token"),
    )

    private fun parseJsonObject(body: String): JsonObject? = try {
        Json.parseToJsonElement(body).asObj()
    } catch (e: Exception) {
        null
    } catch (t: Throwable) {
        // The class contract is "never throws": the JSON parser
        // recurses, so a deeply nested hostile body overflows with
        // an Error, which must also read as unparseable (round 2).
        null
    }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun formBody(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (name, value) ->
            "${urlEncode(name)}=${urlEncode(value)}"
        }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {

        /**
         * The failure detail every client-bound call returns when
         * this build carries no client identity (0.8.6): a stable
         * code the session manager recognizes and renders in plain
         * words, never a borrowed-pair fallback.
         */
        const val NOT_CONFIGURED_DETAIL = "client_not_configured"

        val FORM_HEADERS = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
            "Accept" to "application/json",
        )
        const val DEFAULT_INTERVAL_SECONDS = 5L
        const val DEFAULT_EXPIRES_SECONDS = 1800L
        const val DEFAULT_TOKEN_LIFETIME_SECONDS = 3600L
    }
}
