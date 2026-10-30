package dev.cyberdogs.offleash.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * H4 — the legacy cookie session's dormancy contract (0.7.2
 * sweep). The final semantics, documented on [SessionManager]:
 * the 0.6.x cookie session is a fallback ONLY until a Google
 * session exists; while one is stored the legacy session is
 * dormant (no keep-alive validation, no state of its own), and a
 * total sign-out wipes BOTH generations.
 *
 * Pinned here: the keep-alive decision's truth table, plus the
 * source-shape contracts for the two workers and the container's
 * total sign-out (the workers and the container are Android /
 * wiring code the JVM subset cannot execute, so — following the
 * repo's contract-test style — their shape is pinned instead).
 */
class LegacyDormancyTest {

    @Test
    fun keepAliveRunsOnlyForALegacyOnlyDevice() {
        assertTrue(legacySessionValidationShouldRun(true, googleSessionPresent = false))
        assertFalse(
            "a Google session makes the legacy session dormant",
            legacySessionValidationShouldRun(true, googleSessionPresent = true),
        )
        assertFalse(legacySessionValidationShouldRun(false, googleSessionPresent = false))
        assertFalse(legacySessionValidationShouldRun(false, googleSessionPresent = true))
    }

    @Test
    fun keepAliveStaysDormantAfterAGoogleOnlyWipe() {
        // The marker dimension (bug sweep round 2): Google existed,
        // was wiped on its own (no Google session stored now), and
        // the legacy session is still on disk — validation must NOT
        // resume spending battery/network on the zombie.
        assertFalse(
            legacySessionValidationShouldRun(
                legacySessionPresent = true,
                googleSessionPresent = false,
                googleSignInSeen = true,
            )
        )
        assertTrue(
            legacySessionValidationShouldRun(
                legacySessionPresent = true,
                googleSessionPresent = false,
                googleSignInSeen = false,
            )
        )
    }

    @Test
    fun legacyKeepAliveWorkerConsultsTheGoogleSessionBeforeValidating() {
        val source = mainSource(
            "java/dev/cyberdogs/offleash/account/SessionKeepAliveWorker.kt"
        )
        assertTrue(
            "the legacy worker must gate validation on the dormancy decision",
            source.contains("legacySessionValidationShouldRun"),
        )
        assertTrue(
            "the legacy worker must look at the Google session's presence",
            source.contains("googleSessionManager.hasStoredSession()"),
        )
        assertTrue(
            "the legacy worker must also consult the sign-in-seen marker",
            source.contains("googleSignInSeen = container.googleSignInSeenMarker.seen()"),
        )
        assertTrue(
            "validation must still run through the session manager",
            source.contains("sessionManager.validateNow()"),
        )
    }

    @Test
    fun googleKeepAliveWorkerSkipsWhenNoGoogleSessionIsStored() {
        val source = mainSource(
            "java/dev/cyberdogs/offleash/account/GoogleSessionKeepAliveWorker.kt"
        )
        assertTrue(
            "the Google worker must not spend a wake without a stored session",
            source.contains("hasStoredSession()"),
        )
        assertTrue(
            "the refresh must still run through the Google manager",
            source.contains("refreshIdentity()"),
        )
    }

    @Test
    fun containerSignOutWipesBothGenerations() {
        val source = mainSource("java/dev/cyberdogs/offleash/OffleashApp.kt")
        val signOutBody = source
            .substringAfter("suspend fun signOutOfAccount()")
            .substringBefore("}")
        assertTrue(
            "total sign-out must revoke + wipe the Google session",
            signOutBody.contains("googleSessionManager.signOut()"),
        )
        assertTrue(
            "total sign-out must wipe the legacy cookie session too — " +
                "otherwise it resurrects as the presented account",
            signOutBody.contains("sessionManager.signOut()"),
        )
    }

    /** Reads a file under app/src/main, located like the other contract tests. */
    private fun mainSource(relativePath: String): String {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, "src/main/$relativePath")
            candidates += File(dir, "app/src/main/$relativePath")
            dir = dir.parentFile
            hops++
        }
        val found = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "$relativePath not found from " + File(".").absolutePath +
                    " (tried: " + candidates.joinToString { it.path } + ")",
            )
        return found.readText()
    }
}
