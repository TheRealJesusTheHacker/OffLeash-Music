package dev.cyberdogs.offleash.core.auth

import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.parse.asObj
import dev.cyberdogs.offleash.core.parse.str
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Google OAuth 2.0 for native Android apps — the authorization-code
 * flow with PKCE (RFC 7636), OffLeash's sign-in from 0.8.8 (decision
 * brief 2026-11-01, Road C; owner direction 2026-11-03: the own-key
 * console wizard was the wrong route — "we need a better login way",
 * cleaner).
 *
 * THE SHAPE: the app builds an authorization URL naming its ANDROID
 * OAuth client (a registration bound to the app's package name and
 * signing-certificate fingerprint — an Android client has NO secret;
 * Google treats the fingerprint as the credential), opens it in a
 * Chrome Custom Tab where the owner picks the Google account already
 * on the phone and approves, and Google returns the owner to the
 * app's redirect URI carrying a one-time code. The app exchanges the
 * code — proving possession of the PKCE verifier only this attempt
 * generated — for the token set. No code is typed, no console is
 * visited, no password is typed into the app, no WebView session
 * exists, and no cookie is captured, stored, or replayed.
 *
 * WHY THIS CLIENT TYPE: the token Google issues here is issued TO
 * the app's Android registration, and signed-in browse presents it
 * through the Android YouTube Music presentation (ANDROID_MUSIC)
 * first — an Android-issued token behind an Android presentation is
 * the coherent pairing the device-flow hunts never had (their tokens
 * were minted by TV-class clients and presented as Android). Whether
 * YouTube's internal API accepts the pairing is the owner's device
 * test to settle; nothing in this file claims it does.
 *
 * WHY NOT GOOGLE'S PLAY-SERVICES AUTHORIZATION API: Google's own
 * documentation gives that API no path to a refresh token for a
 * standalone app (its offline access returns a server auth code and
 * names a backend as the exchanger), while its OAuth guide for
 * Android apps using the system browser states the browser flow
 * returns an authorization code "and a refresh token if access was
 * requested while the user was offline" (the `access_type=offline`
 * request this flow makes). The sealed custody + refresh machinery
 * the app already runs therefore carries over unchanged: only the
 * issuance leg differs from [GoogleDeviceAuth].
 *
 * TOTALITY: same contract as the rest of core auth — every function
 * returns a result value and never throws; failure details are the
 * server's allow-listed error code or the failure's class, NEVER
 * token material, the code, or the verifier (details reach the
 * sign-in diagnostics).
 */
object Pkce {

    /** RFC 7636 §4.1: the verifier is 43–128 unreserved characters. */
    const val VERIFIER_BYTES = 32

    /** The state parameter's entropy: 128 bits, base64url-encoded. */
    const val STATE_BYTES = 16

    private val base64Url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** A fresh code verifier: [VERIFIER_BYTES] of randomness, base64url. */
    fun newVerifier(random: SecureRandom): String {
        val bytes = ByteArray(VERIFIER_BYTES)
        random.nextBytes(bytes)
        return base64Url.encodeToString(bytes)
    }

    /** A fresh state value binding one attempt to its return. */
    fun newState(random: SecureRandom): String {
        val bytes = ByteArray(STATE_BYTES)
        random.nextBytes(bytes)
        return base64Url.encodeToString(bytes)
    }

    /** The S256 challenge for [verifier] (RFC 7636 §4.2). */
    fun challengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url.encodeToString(digest)
    }

    /** True when [verifier] has the RFC 7636 §4.1 shape. */
    fun isValidVerifier(verifier: String): Boolean {
        if (verifier.length < 43 || verifier.length > 128) return false
        for (c in verifier) {
            val ok = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '-' || c == '.' || c == '_' || c == '~'
            if (!ok) return false
        }
        return true
    }
}

/**
 * One native sign-in attempt, ready to open: the [authorizationUrl]
 * for the Custom Tab, and the attempt's secrets-of-the-moment —
 * [state] (the return must echo it) and [codeVerifier] (the exchange
 * must present it). The verifier is credential-adjacent: whoever
 * holds it plus the code can collect the tokens, so the app seals it
 * in the pending-attempt store for exactly the attempt's life and
 * never logs it.
 */
data class NativeAuthorizationRequest(
    val authorizationUrl: String,
    val state: String,
    val codeVerifier: String,
    val redirectUri: String,
)

/** What came back on the redirect URI. */
sealed interface RedirectResult {

    /** Google granted: the one-time [code], state already verified. */
    data class Code(val code: String) : RedirectResult

    /** The owner declined (or closed the consent) on Google's page. */
    object Denied : RedirectResult

    /**
     * The return could not be trusted or used: wrong destination,
     * a state that matches no live attempt, a missing code, or an
     * error answer that is not a decline. [detail] is a stable,
     * allow-listed code for diagnostics — never the URI itself.
     */
    data class Failed(val detail: String) : RedirectResult
}

/** The outcome of exchanging a granted code for tokens. */
sealed interface CodeExchangeResult {

    /** The token set Google issued for this app's Android client. */
    data class Granted(val tokens: GoogleTokens) : CodeExchangeResult

    /**
     * The exchange was refused or unreachable: [detail] is the
     * allow-listed error code or the failure's class. An
     * `invalid_grant` here means the code itself died (expired,
     * consumed, or replayed); an `invalid_client` means the client
     * registration does not match this app.
     */
    data class Failed(val detail: String) : CodeExchangeResult
}

/**
 * The native-flow client: authorization-URL construction, redirect
 * validation, code exchange, refresh, and revocation against
 * Google's OAuth endpoints, over the app's shared [HttpTransport].
 * Synchronous/blocking like the rest of core — callers run it on an
 * IO dispatcher.
 *
 * [clientId] is the app's ANDROID OAuth client ID: public by design
 * (it ships inside the APK and names the registration; the
 * registration's fingerprint binding is what protects it), supplied
 * at build time. A null or blank ID is a build with no sign-in:
 * issuance-side calls fail at once with the same stable
 * not-configured detail the device flow used, while revocation
 * (which names no client) still works.
 */
class GoogleNativeAuth(
    private val transport: HttpTransport,
    val clientId: String?,
    private val random: SecureRandom = SecureRandom(),
) {

    /** True when this build carries an Android client ID. */
    val isConfigured: Boolean
        get() = !clientId.isNullOrBlank()

    /**
     * Starts an attempt: fresh state + verifier, and the URL the
     * Custom Tab opens. Null when the build is unconfigured — the
     * caller renders its one honest not-configured state instead of
     * a dead button.
     *
     * The request asks for offline access with a forced consent
     * (`access_type=offline`, `prompt=consent`): Google's documented
     * conditions for returning a refresh token to a browser-flow
     * client, so the sealed session can refresh without reopening
     * the browser.
     */
    fun beginAuthorization(redirectUri: String): NativeAuthorizationRequest? {
        val id = clientId?.takeIf { it.isNotBlank() } ?: return null
        val state = Pkce.newState(random)
        val verifier = Pkce.newVerifier(random)
        return NativeAuthorizationRequest(
            authorizationUrl = authorizationUrl(id, redirectUri, state, verifier),
            state = state,
            codeVerifier = verifier,
            redirectUri = redirectUri,
        )
    }

    /**
     * The authorization URL for an attempt whose [state] and
     * [codeVerifier] already exist — the same builder
     * [beginAuthorization] uses, exposed so a restored attempt (the
     * app was killed while the owner was in the browser) can rebuild
     * the identical URL from its sealed state.
     */
    fun authorizationUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        codeVerifier: String,
    ): String {
        val params = listOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "scope" to GoogleOAuth.SCOPE,
            "state" to state,
            "code_challenge" to Pkce.challengeS256(codeVerifier),
            "code_challenge_method" to "S256",
            "access_type" to "offline",
            "prompt" to "consent",
        )
        return GoogleOAuth.AUTHORIZATION_URL + "?" +
            params.joinToString("&") { (name, value) ->
                "${urlEncode(name)}=${urlEncode(value)}"
            }
    }

    /**
     * Validates what returned on the redirect URI against the live
     * attempt: the destination must be exactly [expectedRedirectUri],
     * a Google error answer maps to its named outcome, and the
     * echoed state must equal [expectedState] before the code is
     * believed (the CSRF binding — a return this attempt didn't
     * start is not this attempt's to collect).
     */
    fun parseRedirect(
        returnedUri: String,
        expectedRedirectUri: String,
        expectedState: String,
    ): RedirectResult {
        val queryStart = returnedUri.indexOf('?')
        val base = if (queryStart >= 0) {
            returnedUri.substring(0, queryStart)
        } else {
            returnedUri
        }
        if (base != expectedRedirectUri) {
            return RedirectResult.Failed("unexpected_redirect_destination")
        }
        if (queryStart < 0) {
            return RedirectResult.Failed("redirect carried no answer")
        }
        val params = parseQuery(returnedUri.substring(queryStart + 1))
        val error = params["error"]
        if (error != null) {
            return if (error == "access_denied") {
                RedirectResult.Denied
            } else {
                RedirectResult.Failed(
                    "redirect error: ${errorCodeForDetail(error)}"
                )
            }
        }
        val state = params["state"]
        if (state == null || state != expectedState) {
            return RedirectResult.Failed("state_mismatch")
        }
        val code = params["code"]
        if (code.isNullOrBlank()) {
            return RedirectResult.Failed("redirect carried no code")
        }
        return RedirectResult.Code(code)
    }

    /**
     * Exchanges a granted [code] for the token set. The request
     * names the client ID and the PKCE verifier — and NO secret:
     * Android clients have none, the registration's fingerprint
     * binding plus the verifier are the proof.
     */
    fun exchangeCode(
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): CodeExchangeResult {
        val id = clientId?.takeIf { it.isNotBlank() }
            ?: return CodeExchangeResult.Failed(NOT_CONFIGURED_DETAIL)
        val response = try {
            transport.postForm(
                GoogleOAuth.TOKEN_URL,
                FORM_HEADERS,
                formBody(
                    "client_id" to id,
                    "code" to code,
                    "code_verifier" to codeVerifier,
                    "grant_type" to GoogleOAuth.GRANT_TYPE_AUTHORIZATION_CODE,
                    "redirect_uri" to redirectUri,
                ),
            )
        } catch (e: Exception) {
            return CodeExchangeResult.Failed("transport: ${e.javaClass.simpleName}")
        }
        if (response.status == 200) {
            val root = parseJsonObject(response.body)
                ?: return CodeExchangeResult.Failed("grant body was not valid JSON")
            val tokens = parseTokens(root)
            if (tokens.accessToken.isBlank()) {
                return CodeExchangeResult.Failed("grant carried no access token")
            }
            return CodeExchangeResult.Granted(tokens)
        }
        val root = parseJsonObject(response.body)
        return CodeExchangeResult.Failed(
            "HTTP ${response.status}: ${errorCodeForDetail(root?.str("error"))}"
        )
    }

    /** Exchanges a stored refresh token for a fresh access token. */
    fun refreshTokens(refreshToken: String): RefreshResult {
        val id = clientId?.takeIf { it.isNotBlank() }
            ?: return RefreshResult.Failed(NOT_CONFIGURED_DETAIL)
        val response = try {
            transport.postForm(
                GoogleOAuth.TOKEN_URL,
                FORM_HEADERS,
                formBody(
                    "client_id" to id,
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
            RefreshResult.Failed("HTTP ${response.status}: ${errorCodeForDetail(error)}")
        }
    }

    /**
     * Revokes a token at Google (RFC 7009): after this, the token —
     * and, for a refresh token, the whole grant — stops working
     * everywhere, not just on this device. Best effort by contract:
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
        // an Error, which must also read as unparseable.
        null
    }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    /**
     * Splits a query string into decoded name→value pairs. A pair
     * without `=` reads as an empty value; a malformed escape keeps
     * its raw text (the value then simply fails its comparison —
     * total, never a throw).
     */
    private fun parseQuery(query: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (query.isEmpty()) return out
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val name = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            out[urlDecode(name)] = urlDecode(value)
        }
        return out
    }

    private fun formBody(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (name, value) ->
            "${urlEncode(name)}=${urlEncode(value)}"
        }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun urlDecode(value: String): String = try {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (e: Exception) {
        value
    }

    private companion object {

        /**
         * The failure detail every client-bound call returns when
         * this build carries no client ID — the same stable code
         * the device flow used, so diagnostics read identically.
         */
        const val NOT_CONFIGURED_DETAIL = "client_not_configured"

        val FORM_HEADERS = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
            "Accept" to "application/json",
        )

        /**
         * The OAuth error codes Google documents for its endpoints
         * (the same allow-list the device flow keeps): a failure
         * detail may carry one of these verbatim; any other
         * server-supplied `error` text is endpoint-controlled free
         * text and is replaced, so it can never inject lines into
         * the diagnostics panel or a user-facing reason.
         */
        val KNOWN_ERROR_CODES = setOf(
            "access_denied",
            "admin_policy_enforced",
            "disallowed_useragent",
            "invalid_client",
            "invalid_grant",
            "invalid_request",
            "invalid_scope",
            "org_internal",
            "redirect_uri_mismatch",
            "server_error",
            "temporarily_unavailable",
            "unauthorized_client",
            "unsupported_grant_type",
        )

        fun errorCodeForDetail(error: String?): String = when {
            error == null -> "no error code"
            error in KNOWN_ERROR_CODES -> error
            else -> "unrecognized error"
        }

        const val DEFAULT_TOKEN_LIFETIME_SECONDS = 3600L
    }
}
