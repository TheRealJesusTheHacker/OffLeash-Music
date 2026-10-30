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
import org.junit.Test

/**
 * Round 8 (attack the round-7 name-first drift fallback): the
 * name is per-identity, but it is not UNIQUE — a personal identity
 * and a brand channel can share one display name (an owner who
 * named the channel after themselves), as can two brand channels.
 * Round 7's fallback returns the FIRST identity carrying the
 * stored name, so with routing drifted a namesake enumerated
 * earlier silently wins — the cross-match, one level down. The
 * stored session carries one more per-identity discriminator the
 * fallback never consulted: the avatar URL captured at adoption
 * (the switcher parser reads each identity's own account photo).
 * Within a name collision the avatar must decide; when the name
 * itself has drifted (a rename), a UNIQUE avatar match must still
 * find the identity ahead of the shared section email.
 */
class R8IdentityCollisionTest {

    /**
     * One section, one shared email, TWO identities sharing ONE
     * display name: the personal identity (avatar P) enumerated
     * first and selected, and a brand channel (avatar B) second.
     */
    private class SameNameTransport : HttpTransport {

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
            handle(url)

        override fun get(url: String, headers: Map<String, String>): HttpResponse =
            handle(url)

        private fun handle(url: String): HttpResponse {
            if (url.contains("sw.js_data")) {
                return HttpResponse(200, """{"visitorData":"V"}""")
            }
            val personal = accountItem(
                name = SHARED_NAME,
                avatar = PERSONAL_AVATAR,
                pageId = null,
                authUser = 0,
                selected = true,
            )
            val brand = accountItem(
                name = SHARED_NAME,
                avatar = BRAND_AVATAR,
                pageId = BRAND_PAGE_ID,
                authUser = 1,
                selected = false,
            )
            return HttpResponse(
                200,
                switcherBody("$personal,$brand"),
            )
        }

        private fun switcherBody(items: String): String = """
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
                                    "name": {"simpleText": "$SHARED_NAME"}
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
        """.trimIndent()

        private fun accountItem(
            name: String,
            avatar: String,
            pageId: String?,
            authUser: Int,
            selected: Boolean,
        ): String {
            val pageToken = pageId?.let {
                """{"pageIdToken":{"pageId":"$it"}},"""
            } ?: ""
            return """
                {
                  "accountItem": {
                    "accountName": {"simpleText": "$name"},
                    "accountPhoto": {
                      "thumbnails": [{"url": "$avatar", "width": 64, "height": 64}]
                    },
                    "isSelected": $selected,
                    "serviceEndpoint": {
                      "selectActiveIdentityEndpoint": {
                        "supportedTokens": [
                          $pageToken
                          {"accountSigninToken": {"signinUrl": "https://www.youtube.com/signin?authuser=$authUser"}}
                        ]
                      }
                    }
                  }
                }
            """.trimIndent()
        }
    }

    private fun sessionManager(store: SessionStore): SessionManager = SessionManager(
        store = store,
        client = YoutubeiClient(SameNameTransport()),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        signInDiagnostics = SignInDiagnostics(),
        confirmationDelaysMs = emptyList(),
    )

    @Test
    fun brandSessionWithAStalePageIdAndASharedNameRematchesByAvatarNotByEnumeration() =
        runBlocking {
            val store = testSessionStore()
            store.save(
                seededStoredSession(authUser = 1, pageId = "stale-page-9")
                    .copy(identityName = SHARED_NAME, avatarUrl = BRAND_AVATAR),
            )
            val manager = sessionManager(store)

            val health = manager.validateNow()
            assertEquals(SessionHealth.HEALTHY, health)

            val persisted = store.load()
            assertNotNull(persisted)
            assertEquals(
                "two identities share the stored name; the brand session's " +
                    "own avatar must pick the brand identity, not the " +
                    "personal namesake enumerated first",
                BRAND_PAGE_ID,
                persisted!!.pageId,
            )
            assertEquals(1, persisted.authUser)
            assertEquals(BRAND_AVATAR, persisted.avatarUrl)
        }

    @Test
    fun renamedBrandSessionWithAStalePageIdIsFoundByItsUniqueAvatar() = runBlocking {
        // The channel was RENAMED after adoption: routing drifted
        // AND the stored name matches nothing in the enumeration.
        // Before round 8 the fallback fell to the shared section
        // email — first-in-enumeration, the personal identity. The
        // stored avatar still matches exactly one identity.
        val store = testSessionStore()
        store.save(
            seededStoredSession(authUser = 1, pageId = "stale-page-9")
                .copy(identityName = "The Channel's Old Name", avatarUrl = BRAND_AVATAR),
        )
        val manager = sessionManager(store)

        val health = manager.validateNow()
        assertEquals(SessionHealth.HEALTHY, health)

        val persisted = store.load()
        assertNotNull(persisted)
        assertEquals(
            "a renamed identity with drifted routing must be found by " +
                "its unique avatar, not by the shared section email",
            BRAND_PAGE_ID,
            persisted!!.pageId,
        )
        assertEquals(SHARED_NAME, persisted.identityName)
    }

    private companion object {
        const val SHARED_NAME = "Alex Rivers"
        const val SHARED_EMAIL = "alex@example.com"
        const val BRAND_PAGE_ID = "brand-page-7"
        const val PERSONAL_AVATAR = "https://example.com/personal.png"
        const val BRAND_AVATAR = "https://example.com/brand.png"
    }
}
