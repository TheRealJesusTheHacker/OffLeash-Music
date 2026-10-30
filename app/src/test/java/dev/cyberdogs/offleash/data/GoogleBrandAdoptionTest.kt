package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Brand capture at adoption (0.8.2 browse hunt): userinfo names
 * only the bare Google identity, but the owner's playlists live on
 * his brand channel — so adoption (and identity refresh) also read
 * the account menu and store the selected brand identity's page id
 * + authUser as the browse delegation. The menu body is the core
 * suite's derived switcher fixture (copied to this suite's
 * resources as account_switcher.json): a personal identity plus a
 * SELECTED brand channel ("Sentinel Brand Channel", page id
 * 987654321098765432109).
 *
 * Brand capture is enrichment, never a gate: a refused menu still
 * adopts the grant, with no delegation stored.
 */
class GoogleBrandAdoptionTest {

    /** Form-POST transport for the OAuth endpoints, served from a script. */
    private class ScriptedFormTransport : HttpTransport {
        val responses = ArrayDeque<HttpResponse>()

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("form POSTs only")

        override fun postForm(
            url: String,
            headers: Map<String, String>,
            formBody: String,
        ): HttpResponse = responses.removeFirst()
    }

    /**
     * The client transport: GET answers userinfo, POST answers the
     * account menu with the scripted status/body.
     */
    private class IdentityTransport(
        private val menuStatus: Int = 200,
        private val menuBody: String = switcherBody(),
    ) : HttpTransport {
        override fun get(url: String, headers: Map<String, String>): HttpResponse =
            HttpResponse(200, USERINFO_BODY)

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = HttpResponse(menuStatus, menuBody)
    }

    private fun manager(
        formTransport: ScriptedFormTransport,
        store: GoogleTokenStore,
        clientTransport: HttpTransport = IdentityTransport(),
    ): GoogleSessionManager = GoogleSessionManager(
        store = store,
        auth = GoogleDeviceAuth(formTransport),
        client = YoutubeiClient(clientTransport),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        signInDiagnostics = SignInDiagnostics(),
    )

    private fun testStore(): GoogleTokenStore =
        GoogleTokenStore(InMemorySecretStorage(), testSecretBox())

    private suspend fun awaitTrue(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    private fun scriptedGrant(): ScriptedFormTransport {
        val form = ScriptedFormTransport()
        form.responses.add(
            HttpResponse(
                200,
                """{"device_code":"dc-1","user_code":"WDJB-MJHT",""" +
                    """"verification_url":"https://www.google.com/device",""" +
                    """"expires_in":1800,"interval":0}""",
            )
        )
        form.responses.add(
            HttpResponse(
                200,
                """{"access_token":"ya29.granted","expires_in":3599,""" +
                    """"refresh_token":"rt-1","token_type":"Bearer",""" +
                    """"scope":"https://www.googleapis.com/auth/youtube openid profile email"}""",
            )
        )
        return form
    }

    @Test
    fun adoptionStoresTheSelectedBrandDelegation() = runBlocking {
        val store = testStore()
        val manager = manager(scriptedGrant(), store)

        manager.startSignIn()
        awaitTrue { manager.uiState.value.signedIn }

        val stored = store.load()
        assertNotNull(stored)
        assertEquals(BRAND_PAGE_ID, stored!!.identityPageId)
        assertNotNull(stored.identityAuthUser)
        val delegation = manager.browseDelegation()
        assertNotNull(delegation)
        assertEquals(BRAND_PAGE_ID, delegation!!.pageId)
        assertEquals(stored.identityAuthUser, delegation.authUser)
    }

    @Test
    fun refusedAccountMenuStillAdoptsWithoutDelegation() = runBlocking {
        val store = testStore()
        val manager = manager(
            scriptedGrant(),
            store,
            IdentityTransport(menuStatus = 401, menuBody = AUTH_ERROR_BODY),
        )

        manager.startSignIn()
        awaitTrue { manager.uiState.value.signedIn }

        assertEquals(SessionHealth.HEALTHY, manager.uiState.value.health)
        val stored = store.load()
        assertNotNull(stored)
        assertNull(stored!!.identityPageId)
        assertNull(manager.browseDelegation())
    }

    @Test
    fun identityRefreshCapturesBrandForAPreBrandSession() = runBlocking {
        val store = testStore()
        // A session sealed before brand capture existed: valid
        // token, identity known, no page id anywhere.
        store.save(
            StoredGoogleSession(
                accessToken = "ya29.standing",
                refreshToken = "rt-1",
                accessTokenExpiresAtEpochMs =
                    System.currentTimeMillis() + 3_600_000L,
                identityName = "Test Listener",
                identityEmail = "listener@example.com",
                grantedAtEpochMs = System.currentTimeMillis(),
            )
        )
        val manager = manager(ScriptedFormTransport(), store)
        assertNull(manager.browseDelegation())

        manager.refreshIdentity()

        val stored = store.load()
        assertNotNull(stored)
        assertEquals(BRAND_PAGE_ID, stored!!.identityPageId)
        assertEquals(BRAND_PAGE_ID, manager.browseDelegation()?.pageId)
    }

    private companion object {
        const val BRAND_PAGE_ID = "987654321098765432109"

        const val USERINFO_BODY =
            """{"sub":"123","name":"Test Listener",""" +
                """"email":"listener@example.com",""" +
                """"picture":"https://example.com/avatar.png"}"""

        // Google's real 401 shape, captured by the hunt's live
        // probes (2026-10-30) — message trimmed, fields verbatim.
        val AUTH_ERROR_BODY: String =
            "{\"error\": {\"code\": 401, \"message\": \"Request had invalid " +
                "authentication credentials.\", \"errors\": [{\"message\": " +
                "\"Invalid Credentials\", \"domain\": \"global\", \"reason\": " +
                "\"authError\"}], \"status\": \"UNAUTHENTICATED\"}}"

        fun switcherBody(): String =
            GoogleBrandAdoptionTest::class.java
                .getResourceAsStream("/account_switcher.json")!!
                .bufferedReader(Charsets.UTF_8).readText()
    }
}
