package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A fake [HttpTransport] that answers the account calls the way
 * YouTube's shapes suggest, so SessionManager can be driven end to
 * end on the JVM: the real [YoutubeiClient] runs against this, and
 * only the wire is faked.
 *
 * Behavior switch:
 * - [Behavior.IDENTITIES]: account calls answer 200 with the account
 *   switcher's real menu-shaped body (see [identitiesBody]),
 *   enumerating [authUser] / [pageId] for the test identity.
 * - [Behavior.REJECTED]: account calls answer 401.
 * - [Behavior.THROW]: every call throws, like a dead network.
 * Visitor-data calls answer 200 (except under THROW, which they
 * never reach in the flows under test).
 *
 * Also carries the small builders the manager tests share: a
 * JVM-keyed [AesGcmSecretBox], a [SessionStore] over in-memory
 * storage, a [SessionManager] over a real client + this transport,
 * and a seeded [StoredSession].
 */
class FakeAccountTransport(
    var behavior: Behavior = Behavior.IDENTITIES,
) : HttpTransport {

    enum class Behavior { IDENTITIES, REJECTED, THROW, EMPTY }

    /**
     * Scripted per-account-call behaviors, consumed in order before
     * the standing [behavior] applies (2026-10-24 sign-in hunt: the
     * confirmation window's convergence needs "not yet, not yet,
     * now" sequences). Visitor-data calls never consume the script.
     */
    val behaviorQueue: ArrayDeque<Behavior> = ArrayDeque()

    /** Account (non-visitor) calls served so far. */
    var accountCallCount: Int = 0
        private set

    /** The authUser index the fake's enumeration reports. */
    var enumeratedAuthUser: Int = 1

    /** The pageId the fake's enumeration reports. */
    var enumeratedPageId: String? = "page-123"

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
        handle(url)

    override fun get(url: String, headers: Map<String, String>): HttpResponse =
        handle(url)

    private fun handle(url: String): HttpResponse {
        if (isVisitorDataCall(url)) {
            if (behavior == Behavior.THROW) {
                throw RuntimeException("connection reset by peer")
            }
            return HttpResponse(200, visitorDataBody())
        }
        accountCallCount++
        val effective = if (behaviorQueue.isEmpty()) behavior else behaviorQueue.removeFirst()
        return when (effective) {
            Behavior.IDENTITIES -> HttpResponse(200, identitiesBody())
            Behavior.REJECTED -> HttpResponse(
                401,
                """{"error":{"code":401,"message":"Unauthorized","status":"UNAUTHENTICATED"}}""",
            )
            Behavior.EMPTY -> HttpResponse(200, """{"data":{"actions":[],"responseContext":{}}}""")
            Behavior.THROW -> throw RuntimeException("connection reset by peer")
        }
    }

    private fun isVisitorDataCall(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("sw.js_data") || lower.contains("visitor")
    }

    private fun visitorDataBody(): String =
        """{"visitorData":"$VISITOR_DATA","responseContext":{"visitorData":"$VISITOR_DATA"}}"""

    private fun identitiesBody(): String {
        // The switcher's real response shape (2026-10-27 sign-in
        // hunt): the account menu under data.actions, one
        // accountSectionListRenderer per Google account, identities
        // as accountItems carrying their routing in the
        // selectActiveIdentityEndpoint tokens. The pre-hunt fake
        // answered in a spray of guessed shapes (a flat accounts
        // array among them) that the old parser happened to read —
        // the end-to-end tests certified the guess, not the wire.
        val pageToken = enumeratedPageId?.let {
            """{"pageIdToken":{"pageId":"$it"}},"""
        } ?: ""
        return """
            {
              "data": {
                "responseContext": {"visitorData": "$VISITOR_DATA"},
                "actions": [
                  {
                    "getMultiPageMenuAction": {
                      "menu": {
                        "multiPageMenuRenderer": {
                          "sections": [
                            {
                              "accountSectionListRenderer": {
                                "header": {
                                  "googleAccountHeaderRenderer": {
                                    "email": {"simpleText": "$IDENTITY_EMAIL"},
                                    "name": {"simpleText": "$IDENTITY_NAME"}
                                  }
                                },
                                "contents": [
                                  {
                                    "accountItemSectionRenderer": {
                                      "contents": [
                                        {
                                          "accountItem": {
                                            "accountName": {"simpleText": "$IDENTITY_NAME"},
                                            "accountPhoto": {
                                              "thumbnails": [{"url": "$IDENTITY_AVATAR_URL", "width": 64, "height": 64}]
                                            },
                                            "isSelected": true,
                                            "hasChannel": true,
                                            "serviceEndpoint": {
                                              "selectActiveIdentityEndpoint": {
                                                "supportedTokens": [
                                                  $pageToken
                                                  {"accountSigninToken": {"signinUrl": "https://www.youtube.com/signin?action_handle_signin=true&authuser=$enumeratedAuthUser&feature=switcher"}}
                                                ]
                                              }
                                            }
                                          }
                                        }
                                      ]
                                    }
                                  }
                                ]
                              }
                            }
                          ]
                        }
                      }
                    }
                  }
                ]
              }
            }
        """.trimIndent()
    }

    companion object {
        const val IDENTITY_NAME = "Test Listener"
        const val IDENTITY_EMAIL = "listener@example.com"
        const val IDENTITY_AVATAR_URL = "https://example.com/avatar.png"
        const val VISITOR_DATA = "TEST_VISITOR_DATA"

        /** A raw cookie string carrying the full session-cookie family. */
        const val RAW_COOKIE =
            "SAPISID=test-sapisid-value; __Secure-3PAPISID=test-3p-value; " +
                "APISID=test-apisid-value; HSID=test-hsid-value; SSID=test-ssid-value; " +
                "SID=test-sid-value; LOGIN_INFO=test-login-info; " +
                "PREF=f1=50000000; VISITOR_INFO1_LIVE=test-visitor-live; YSC=test-ysc-value"
    }
}

/** A locally generated AES key + the box under test, for JVM tests. */
fun testSecretKey(): SecretKey =
    KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()

fun testSecretBox(key: SecretKey = testSecretKey()): AesGcmSecretBox =
    AesGcmSecretBox { key }

fun testSessionStore(storage: SecretStorage = InMemorySecretStorage()): SessionStore =
    SessionStore(storage, testSecretBox())

fun testSessionManager(
    store: SessionStore,
    transport: FakeAccountTransport,
    confirmationDelaysMs: List<Long> = emptyList(),
    signInDiagnostics: SignInDiagnostics = SignInDiagnostics(),
): SessionManager =
    SessionManager(
        store = store,
        client = YoutubeiClient(transport),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        signInDiagnostics = signInDiagnostics,
        confirmationDelaysMs = confirmationDelaysMs,
    )

/** A plausible stored session, as a previous successful sign-in would have left it. */
fun seededStoredSession(
    authUser: Int = 0,
    pageId: String? = null,
): StoredSession = StoredSession(
    cookies = mapOf(
        "SAPISID" to "seeded-sapisid-value",
        "__Secure-3PAPISID" to "seeded-3p-value",
        "SID" to "seeded-sid-value",
    ),
    authUser = authUser,
    pageId = pageId,
    visitorData = "SEEDED_VISITOR_DATA",
    identityName = FakeAccountTransport.IDENTITY_NAME,
    identityEmail = FakeAccountTransport.IDENTITY_EMAIL,
    avatarUrl = FakeAccountTransport.IDENTITY_AVATAR_URL,
    capturedAtEpochMs = 1_000L,
    lastValidatedEpochMs = 2_000L,
)
