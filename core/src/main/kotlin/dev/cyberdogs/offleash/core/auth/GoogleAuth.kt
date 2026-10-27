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
 * The client id/secret below are the credentials embedded in
 * YouTube's own TV client — a public, installed-app credential pair
 * (the same pair yt-dlp ships), not a secret of this project: device
 * flow clients cannot keep a secret, which is why the flow exists.
 * The security boundary is the owner's own Google approval on
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

    /** YouTube's own TV / limited-input client identity. */
    const val CLIENT_ID =
        "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
    const val CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"

    /**
     * The exact scopes this build requests — what the surfaces need
     * and no more:
     * - `youtube`: read the account's YouTube Music library, home
     *   personalization and liked songs, resolve playback, and write
     *   likes back (the InnerTube surfaces the app calls with the
     *   resulting Bearer token enforce this scope);
     * - `openid` + `profile` + `email`: name the account (name,
     *   avatar, email) through the userinfo endpoint.
     */
    const val SCOPE =
        "https://www.googleapis.com/auth/youtube openid profile email"

    const val GRANT_TYPE_DEVICE_CODE =
        "urn:ietf:params:oauth:grant-type:device_code"

    /** Where the owner approves: shown verbatim in the sign-in UI. */
    const val VERIFICATION_URL = "https://www.google.com/device"
}

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
 */
class GoogleDeviceAuth(
    private val transport: HttpTransport,
) {

    /** Asks Google for a fresh device code to show the owner. */
    fun requestDeviceCode(): DeviceCodeResult {
        val response = try {
            transport.postForm(
                GoogleOAuth.DEVICE_CODE_URL,
                FORM_HEADERS,
                formBody(
                    "client_id" to GoogleOAuth.CLIENT_ID,
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
     * the interval; `access_denied` and `expired_token` end the
     * attempt as named states, not errors.
     */
    fun pollForToken(deviceCode: String): TokenPollResult {
        val response = try {
            transport.postForm(
                GoogleOAuth.TOKEN_URL,
                FORM_HEADERS,
                formBody(
                    "client_id" to GoogleOAuth.CLIENT_ID,
                    "client_secret" to GoogleOAuth.CLIENT_SECRET,
                    "device_code" to deviceCode,
                    "grant_type" to GoogleOAuth.GRANT_TYPE_DEVICE_CODE,
                ),
            )
        } catch (e: Exception) {
            return TokenPollResult.Failed("transport: ${e.javaClass.simpleName}")
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
            else -> TokenPollResult.Failed(
                "HTTP ${response.status}: ${error ?: "no error code"}"
            )
        }
    }

    /** Exchanges a stored refresh token for a fresh access token. */
    fun refreshTokens(refreshToken: String): RefreshResult {
        val response = try {
            transport.postForm(
                GoogleOAuth.TOKEN_URL,
                FORM_HEADERS,
                formBody(
                    "client_id" to GoogleOAuth.CLIENT_ID,
                    "client_secret" to GoogleOAuth.CLIENT_SECRET,
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
        val FORM_HEADERS = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
            "Accept" to "application/json",
        )
        const val DEFAULT_INTERVAL_SECONDS = 5L
        const val DEFAULT_EXPIRES_SECONDS = 1800L
        const val DEFAULT_TOKEN_LIFETIME_SECONDS = 3600L
    }
}
