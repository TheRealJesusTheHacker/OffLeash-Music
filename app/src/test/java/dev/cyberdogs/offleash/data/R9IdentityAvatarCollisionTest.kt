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
 * Round 9 (break the round-8 avatar disambiguation): within a
 * name collision, round 8 took the FIRST namesake carrying the
 * stored avatar. An avatar several namesakes share — a default
 * avatar URL can be identical across a personal identity and a
 * brand channel — discriminates nothing, yet first-match
 * pre-empted the server-selected namesake that round 7's order
 * would have picked: the avatar leg made the outcome WORSE than
 * having no avatar leg at all. Only a UNIQUE avatar match within
 * the collision may decide; a shared one falls through to the
 * selected namesake, exactly as when no avatar matches.
 */
class R9IdentityAvatarCollisionTest {

    /**
     * One section, one shared email, TWO identities sharing ONE
     * display name AND one (default) avatar: the personal identity
     * enumerated first (not selected), the brand channel second
     * (server-selected). The stored session is the brand's.
     */
    private class SharedAvatarTransport : HttpTransport {

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
            handle(url)

        override fun get(url: String, headers: Map<String, String>): HttpResponse =
            handle(url)

        private fun handle(url: String): HttpResponse {
            if (url.contains("sw.js_data")) {
                return HttpResponse(200, """{"visitorData":"V"}""")
            }
            val personal = accountItem(
                pageId = null,
                authUser = 0,
                selected = false,
            )
            val brand = accountItem(
                pageId = BRAND_PAGE_ID,
                authUser = 1,
                selected = true,
            )
            return HttpResponse(200, switcherBody("$personal,$brand"))
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
                    "accountName": {"simpleText": "$SHARED_NAME"},
                    "accountPhoto": {
                      "thumbnails": [{"url": "$SHARED_AVATAR", "width": 64, "height": 64}]
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

    @Test
    fun sharedAvatarWithinACollisionFallsToTheSelectedNamesakeNotTheFirst() = runBlocking {
        val store = testSessionStore()
        store.save(
            seededStoredSession(authUser = 1, pageId = "stale-page-9")
                .copy(identityName = SHARED_NAME, avatarUrl = SHARED_AVATAR),
        )
        val manager = SessionManager(
            store = store,
            client = YoutubeiClient(SharedAvatarTransport()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            signInDiagnostics = SignInDiagnostics(),
            confirmationDelaysMs = emptyList(),
        )

        val health = manager.validateNow()
        assertEquals(SessionHealth.HEALTHY, health)

        val persisted = store.load()
        assertNotNull(persisted)
        assertEquals(
            "both namesakes carry the stored avatar, so it discriminates " +
                "nothing — the server-selected brand namesake must win, " +
                "not the personal namesake enumerated first",
            BRAND_PAGE_ID,
            persisted!!.pageId,
        )
        assertEquals(1, persisted.authUser)
    }

    private companion object {
        const val SHARED_NAME = "Alex Rivers"
        const val SHARED_EMAIL = "alex@example.com"
        const val SHARED_AVATAR = "https://example.com/default-avatar.png"
        const val BRAND_PAGE_ID = "brand-page-7"
    }
}
