package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round 6 (duplicate-resolution census): which enumerated identity
 * a stored session re-matches to in [SessionManager.validateNow].
 *
 * The switcher enumerates EVERY identity of an account under one
 * section whose header carries the account's email — brand
 * identities have no email of their own, so the personal identity
 * and a brand channel of the same account share one email value.
 * The old match tried email first, so first-in-list with that
 * email won: a session adopted as the brand channel was silently
 * re-matched to the personal identity on the next validation, and
 * the stored pageId/authUser/name were overwritten with the
 * personal identity's. The routing keys the stored session itself
 * carries (pageId, then authUser) are the identity's real keys and
 * must win; email/name remain only as the drift fallback.
 */
class SessionIdentityMatchTest {

    /**
     * One account section, one shared email, TWO identities: the
     * personal one and a brand channel. [brandFirst] flips their
     * enumeration order — order must never decide the match.
     */
    private class TwoIdentityTransport(
        private val brandFirst: Boolean = false,
    ) : HttpTransport {

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
            handle(url)

        override fun get(url: String, headers: Map<String, String>): HttpResponse =
            handle(url)

        private fun handle(url: String): HttpResponse {
            if (url.contains("sw.js_data")) {
                return HttpResponse(200, """{"visitorData":"V"}""")
            }
            val personal = accountItem(
                name = PERSONAL_NAME,
                pageId = null,
                selected = true,
            )
            val brand = accountItem(
                name = BRAND_NAME,
                pageId = BRAND_PAGE_ID,
                selected = false,
            )
            val items = if (brandFirst) "$brand,$personal" else "$personal,$brand"
            return HttpResponse(
                200,
                """
                {
                  "data": {
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
                                        "email": {"simpleText": "$SHARED_EMAIL"},
                                        "name": {"simpleText": "$PERSONAL_NAME"}
                                      }
                                    },
                                    "contents": [
                                      {
                                        "accountItemSectionRenderer": {
                                          "contents": [$items]
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
                """.trimIndent(),
            )
        }

        private fun accountItem(name: String, pageId: String?, selected: Boolean): String {
            val pageToken = pageId?.let {
                """{"pageIdToken":{"pageId":"$it"}},"""
            } ?: ""
            return """
                {
                  "accountItem": {
                    "accountName": {"simpleText": "$name"},
                    "accountPhoto": {
                      "thumbnails": [{"url": "https://example.com/avatar.png", "width": 64, "height": 64}]
                    },
                    "isSelected": $selected,
                    "serviceEndpoint": {
                      "selectActiveIdentityEndpoint": {
                        "supportedTokens": [
                          $pageToken
                          {"accountSigninToken": {"signinUrl": "https://www.youtube.com/signin?authuser=0"}}
                        ]
                      }
                    }
                  }
                }
            """.trimIndent()
        }
    }

    @Test
    fun brandChannelSessionRematchesToTheBrandIdentityNotThePersonalOne() = runBlocking {
        val store = testSessionStore()
        store.save(
            seededStoredSession(authUser = 0, pageId = BRAND_PAGE_ID)
                .copy(identityName = BRAND_NAME),
        )
        val manager = sessionManager(store, TwoIdentityTransport())

        val health = manager.validateNow()
        assertEquals(SessionHealth.HEALTHY, health)

        val persisted = store.load()
        assertNotNull(persisted)
        assertEquals(
            "a brand session must keep its own pageId through validation",
            BRAND_PAGE_ID,
            persisted!!.pageId,
        )
        assertEquals(BRAND_NAME, persisted.identityName)
        assertEquals(BRAND_NAME, manager.uiState.value.identityName)
    }

    @Test
    fun personalSessionRematchesToThePersonalIdentityEvenWhenBrandEnumeratesFirst() =
        runBlocking {
            val store = testSessionStore()
            store.save(seededStoredSession(authUser = 0, pageId = null))
            val manager = sessionManager(
                store,
                TwoIdentityTransport(brandFirst = true),
            )

            val health = manager.validateNow()
            assertEquals(SessionHealth.HEALTHY, health)

            val persisted = store.load()
            assertNotNull(persisted)
            assertNull(
                "a personal session must not adopt the brand identity's pageId",
                persisted!!.pageId,
            )
            assertEquals(PERSONAL_NAME, persisted.identityName)
        }

    @Test
    fun brandSessionWithAStalePageIdRematchesByNameNotByTheSharedEmail() = runBlocking {
        // Round 7: the routing keys can DRIFT — the server
        // re-indexes and the stored pageId is simply gone from the
        // enumeration. The round-6 fallback then ran email-first,
        // but the parser gives EVERY identity in the section the
        // section header's email, so email-first is
        // first-in-enumeration: the personal identity, enumerated
        // first here, won and the brand session was silently
        // re-matched to it — the round-6 bug through the fallback
        // door. The per-identity name must decide first.
        val store = testSessionStore()
        store.save(
            seededStoredSession(authUser = 0, pageId = "stale-page-9")
                .copy(identityName = BRAND_NAME),
        )
        val manager = sessionManager(store, TwoIdentityTransport())

        val health = manager.validateNow()
        assertEquals(SessionHealth.HEALTHY, health)

        val persisted = store.load()
        assertNotNull(persisted)
        assertEquals(
            "a brand session whose pageId drifted must re-match its own " +
                "identity by name and adopt the enumeration's current pageId",
            BRAND_PAGE_ID,
            persisted!!.pageId,
        )
        assertEquals(BRAND_NAME, persisted.identityName)
    }

    @Test
    fun personalSessionWithAMovedAuthUserDoesNotRematchToABrandEnumeratedFirst() =
        runBlocking {
            // Round 7, the mirror direction: the personal session's
            // authUser moved server-side (stored 7, enumeration now
            // says 0), so the routing match misses; a brand channel
            // enumerates FIRST and carries the same section email.
            // Email-first fallback would adopt the brand identity
            // (pageId and all) for a personal session.
            val store = testSessionStore()
            store.save(seededStoredSession(authUser = 7, pageId = null))
            val manager = sessionManager(
                store,
                TwoIdentityTransport(brandFirst = true),
            )

            val health = manager.validateNow()
            assertEquals(SessionHealth.HEALTHY, health)

            val persisted = store.load()
            assertNotNull(persisted)
            assertNull(
                "a personal session must not adopt a brand identity via the shared email",
                persisted!!.pageId,
            )
            assertEquals(PERSONAL_NAME, persisted.identityName)
            assertEquals(0, persisted.authUser)
        }

    private fun sessionManager(
        store: SessionStore,
        transport: HttpTransport,
    ): SessionManager = SessionManager(
        store = store,
        client = YoutubeiClient(transport),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        signInDiagnostics = SignInDiagnostics(),
        confirmationDelaysMs = emptyList(),
    )

    private companion object {
        const val PERSONAL_NAME = "Test Listener"
        const val BRAND_NAME = "Brand Channel"
        const val BRAND_PAGE_ID = "brand-page-1"
        const val SHARED_EMAIL = FakeAccountTransport.IDENTITY_EMAIL
    }
}
