package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
import dev.cyberdogs.offleash.core.auth.OAuthClientKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The 0.8.6 own-key wizard's brain, on the JVM: the step machine
 * (advance / back / resume after a restart), the paste format
 * checks, the live test's honesty (a refused pair saves nothing
 * and says so; an accepted pair is the only thing Save will
 * persist), the switch of the live sign-in client on save —
 * including the clean end of a session sealed under the old
 * client — and removal's fallback to the build's pair.
 */
class OwnKeySetupTest {

    /** Form-POST transport for the OAuth endpoints, served from a script. */
    private class ScriptedFormTransport : HttpTransport {
        val responses = ArrayDeque<HttpResponse>()
        val formBodies = mutableListOf<String>()

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("form POSTs only")

        override fun postForm(
            url: String,
            headers: Map<String, String>,
            formBody: String,
        ): HttpResponse {
            formBodies.add(formBody)
            return responses.removeFirst()
        }
    }

    /** Transport whose GET answers the userinfo endpoint. */
    private class UserinfoTransport : HttpTransport {
        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("GET only")

        override fun get(
            url: String,
            headers: Map<String, String>,
        ): HttpResponse = HttpResponse(200, USERINFO_BODY)
    }

    private class Fixture(
        val form: ScriptedFormTransport = ScriptedFormTransport(),
        val keyStore: OwnKeyStore =
            OwnKeyStore(InMemorySecretStorage(), testSecretBox()),
        val wizardStore: OwnKeyWizardStore =
            OwnKeyWizardStore(InMemorySecretStorage(), testSecretBox()),
        val tokenStore: GoogleTokenStore =
            GoogleTokenStore(InMemorySecretStorage(), testSecretBox()),
        val diagnostics: SignInDiagnostics = SignInDiagnostics(),
    ) {
        val auth = GoogleDeviceAuth(form, TestOAuthIdentity.SHARED)
        val manager = GoogleSessionManager(
            store = tokenStore,
            auth = auth,
            client = YoutubeiClient(UserinfoTransport()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            signInDiagnostics = diagnostics,
        )
        val setup = OwnKeySetup(
            keyStore = keyStore,
            wizardStore = wizardStore,
            transport = form,
            auth = auth,
            sessionManager = manager,
            buildIdentity = TestOAuthIdentity.SHARED,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            signInDiagnostics = diagnostics,
        )

        /** A second wizard over the same stores — the app restarted. */
        fun restartedSetup(): OwnKeySetup = OwnKeySetup(
            keyStore = keyStore,
            wizardStore = wizardStore,
            transport = form,
            auth = auth,
            sessionManager = manager,
            buildIdentity = TestOAuthIdentity.SHARED,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            signInDiagnostics = diagnostics,
        )
    }

    private suspend fun awaitTrue(
        timeoutMs: Long = 15_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    private fun scriptDeviceCodeIssued(form: ScriptedFormTransport) {
        form.responses.add(
            HttpResponse(
                200,
                """{"device_code":"dc-1","user_code":"WDJB-MJHT",""" +
                    """"verification_url":"https://www.google.com/device",""" +
                    """"expires_in":1800,"interval":5}""",
            ),
        )
    }

    @Test
    fun theStepMachineAdvancesAndBacksWithinItsBounds() {
        val setup = Fixture().setup
        assertEquals(OwnKeyStep.NEED, setup.state.value.step)
        setup.back()
        assertEquals(OwnKeyStep.NEED, setup.state.value.step)
        repeat(5) { setup.advance() }
        assertEquals(OwnKeyStep.ENTER_KEY, setup.state.value.step)
        // The paste screen is the last step of the machine: only
        // a tested save moves past it, to the landing.
        setup.advance()
        assertEquals(OwnKeyStep.ENTER_KEY, setup.state.value.step)
        setup.back()
        assertEquals(OwnKeyStep.CREATE_KEY, setup.state.value.step)
    }

    @Test
    fun leavingMidFlowResumesOnTheSameStepWithTheSameDraft() {
        val fixture = Fixture()
        val setup = fixture.setup
        setup.advance()
        setup.advance()
        setup.advance()
        setup.updateDraft(VALID_ID, VALID_SECRET)

        val resumed = fixture.restartedSetup()
        assertEquals(OwnKeyStep.CONSENT, resumed.state.value.step)
        assertEquals(VALID_ID, resumed.state.value.draftClientId)
        assertEquals(VALID_SECRET, resumed.state.value.draftClientSecret)
        assertFalse(resumed.state.value.tested)
    }

    @Test
    fun validationCatchesAHalfCopiedPaste() {
        assertTrue(
            OwnKeyValidation.clientIdError("1234-abcd") != null,
        )
        assertTrue(
            OwnKeyValidation.clientIdError("") != null,
        )
        assertNull(OwnKeyValidation.clientIdError(VALID_ID))
        assertTrue(OwnKeyValidation.clientSecretError("short") != null)
        assertTrue(
            OwnKeyValidation.clientSecretError("has spaces in it") != null,
        )
        assertNull(OwnKeyValidation.clientSecretError(VALID_SECRET))
        assertTrue(OwnKeyValidation.isValid(VALID_ID, VALID_SECRET))
        assertFalse(OwnKeyValidation.isValid("nope", VALID_SECRET))
    }

    @Test
    fun aRefusedKeySavesNothingAndSaysSo() = runBlocking {
        val fixture = Fixture()
        fixture.form.responses.add(
            HttpResponse(400, """{"error":"invalid_client"}"""),
        )
        val setup = fixture.setup
        setup.updateDraft(VALID_ID, VALID_SECRET)
        setup.testKey()
        awaitTrue { setup.state.value.testMessage != null }

        assertFalse(setup.state.value.tested)
        assertEquals(
            OwnKeySetup.TEST_FAILED_MESSAGE,
            setup.state.value.testMessage,
        )
        // Nothing saved, nothing switched.
        assertNull(fixture.keyStore.load())
        assertEquals(
            TestOAuthIdentity.SHARED,
            fixture.auth.identity,
        )
        // The wire really was asked, with the entered pair.
        assertTrue(
            fixture.form.formBodies.any {
                it.contains("client_id=$VALID_ID")
            },
        )
    }

    @Test
    fun anAcceptedKeySavesAndSwitchesSignInToIt() = runBlocking {
        val fixture = Fixture()
        scriptDeviceCodeIssued(fixture.form)
        val setup = fixture.setup
        setup.updateDraft(VALID_ID, VALID_SECRET)
        setup.testKey()
        awaitTrue { setup.state.value.tested }
        assertEquals(
            OwnKeySetup.TEST_PASSED_MESSAGE,
            setup.state.value.testMessage,
        )

        setup.saveAndFinish()

        assertEquals(
            StoredOwnKey(VALID_ID, VALID_SECRET),
            fixture.keyStore.load(),
        )
        val identity = fixture.auth.identity
        assertTrue(identity != null)
        assertEquals(VALID_ID, identity?.clientId)
        assertEquals(OAuthClientKind.OWNER, identity?.kind)
        assertEquals(OwnKeyStep.DONE, setup.state.value.step)
        assertTrue(setup.state.value.hasOwnKey)
        // The wizard's progress is spent: a fresh wizard starts
        // at the top, where the saved key's management lives.
        assertEquals(
            OwnKeyStep.NEED,
            fixture.restartedSetup().state.value.step,
        )
    }

    @Test
    fun savingTheKeyEndsASharedSessionCleanly() = runBlocking {
        val fixture = Fixture()
        fixture.tokenStore.save(
            StoredGoogleSession(
                accessToken = "ya29.seeded",
                refreshToken = "rt-seeded",
                accessTokenExpiresAtEpochMs =
                    System.currentTimeMillis() + 3_600_000L,
                identityName = "Test Listener",
                identityEmail = "listener@example.com",
                clientKind = null,
            ),
        )
        // Rebuild the manager + wizard over the seeded store, as
        // a fresh process would meet them.
        val manager = GoogleSessionManager(
            store = fixture.tokenStore,
            auth = fixture.auth,
            client = YoutubeiClient(UserinfoTransport()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            signInDiagnostics = fixture.diagnostics,
        )
        val setup = OwnKeySetup(
            keyStore = fixture.keyStore,
            wizardStore = fixture.wizardStore,
            transport = fixture.form,
            auth = fixture.auth,
            sessionManager = manager,
            buildIdentity = TestOAuthIdentity.SHARED,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            signInDiagnostics = fixture.diagnostics,
        )
        assertTrue(manager.uiState.value.signedIn)

        scriptDeviceCodeIssued(fixture.form)
        setup.updateDraft(VALID_ID, VALID_SECRET)
        setup.testKey()
        awaitTrue { setup.state.value.tested }
        setup.saveAndFinish()

        assertNull(fixture.tokenStore.load())
        assertFalse(manager.uiState.value.signedIn)
        assertEquals(
            CLIENT_CHANGED_REASON,
            manager.uiState.value.lastEndReason,
        )
    }

    @Test
    fun editingTheDraftAfterATestUntestsIt() = runBlocking {
        val fixture = Fixture()
        scriptDeviceCodeIssued(fixture.form)
        val setup = fixture.setup
        setup.updateDraft(VALID_ID, VALID_SECRET)
        setup.testKey()
        awaitTrue { setup.state.value.tested }
        setup.updateDraft(VALID_ID, "$VALID_SECRET-x")
        assertFalse(setup.state.value.tested)
        assertNull(setup.state.value.testMessage)
    }

    @Test
    fun removingTheKeyFallsBackToTheBuildPair() = runBlocking {
        val fixture = Fixture()
        scriptDeviceCodeIssued(fixture.form)
        val setup = fixture.setup
        setup.updateDraft(VALID_ID, VALID_SECRET)
        setup.testKey()
        awaitTrue { setup.state.value.tested }
        setup.saveAndFinish()
        assertTrue(setup.state.value.hasOwnKey)

        setup.removeOwnKey()

        assertNull(fixture.keyStore.load())
        assertEquals(TestOAuthIdentity.SHARED, fixture.auth.identity)
        assertFalse(setup.state.value.hasOwnKey)
        assertEquals(OwnKeyStep.NEED, setup.state.value.step)
    }

    private companion object {

        // The gate's landing copy, pinned literally (see
        // GoogleClientGateTest): a wording change fails on purpose.
        const val CLIENT_CHANGED_REASON =
            "You were signed out because OffLeash's sign-in " +
                "changed. Sign in again to reconnect your account."

        const val USERINFO_BODY =
            """{"sub":"123","name":"Test Listener",""" +
                """"email":"listener@example.com",""" +
                """"picture":"https://example.com/avatar.png"}"""

        // Format-valid fakes: a real-shaped Client ID suffix and a
        // long unbroken secret — never a live pair.
        const val VALID_ID =
            "123456789012-fakefakefakefakefakefakefakefa" +
                ".apps.googleusercontent.com"
        const val VALID_SECRET = "GOCSPX-fakefakefakefakefake"
    }
}
