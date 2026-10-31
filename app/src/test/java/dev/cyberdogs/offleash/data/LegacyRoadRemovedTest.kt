package dev.cyberdogs.offleash.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.8.3 — the WebView/cookie sign-in road is REMOVED (owner's
 * verdict, 2026-10-30).
 *
 * Why: the road's identity-confirmation step bounced the owner's
 * real, visibly-signed-in brand session in October 2026 — the
 * WebView showed YouTube Music signed in as his brand channel
 * while the app, asking YouTube's account-switcher endpoint with
 * a cookie capture assumed to sit at authUser 0, reported "YouTube
 * didn't confirm a signed-in account for that session". The
 * Google device-code flow replaced the road as the sign-in in
 * 0.7.0 and proved itself on the owner's phone (0.8.1: sign-in
 * won, session healthy); the old machinery stayed in the tree as
 * a one-release safety net, and on 0.8.2 test night a stale copy
 * of the old screen is what the owner was routed back to. The
 * net's release had passed — 0.8.3 deletes the road itself.
 *
 * Pinned here:
 *  - A legacy session stored by an older build is DISCARDED at
 *    construction — wiped from the sealed store, never presented
 *    as signed in — and the discard is named in the sign-in
 *    diagnostics (presence only, never cookie values). This is
 *    the exact trap an upgraded phone would otherwise carry: a
 *    ≤0.6.x session sealed on disk, resurrected as "signed in"
 *    by every launch's load.
 *  - An empty store discards nothing and logs no discard.
 *  - The road's entry points are gone from the production
 *    sources: no completion from cookies, no legacy validation,
 *    no keep-alive worker. (Source-shape pins, the repo's
 *    contract-test style, for wiring the JVM subset cannot run.)
 */
class LegacyRoadRemovedTest {

    @Test
    fun aStoredLegacySessionIsDiscardedAtConstructionAndNamed() {
        val storage = InMemorySecretStorage()
        val store = testSessionStore(storage)
        store.save(seededStoredSession(authUser = 1, pageId = "page-123"))
        val diagnostics = SignInDiagnostics()

        val manager = testSessionManager(store, diagnostics)

        assertTrue(
            "the stored legacy session must be wiped at construction",
            store.isWipedClean(),
        )
        assertNull(store.load())
        assertNull(
            "a discarded session is never presented to the API layer",
            manager.currentAuthSession(),
        )
        assertFalse(
            "a discarded session is never presented as signed in",
            manager.uiState.value.signedIn,
        )
        val lines = diagnostics.lines()
        assertTrue(
            "the discard must be named in the sign-in diagnostics (was: $lines)",
            lines.any { it.contains("legacy-session:") && it.contains("discarded") },
        )
        assertFalse(
            "the diagnostics name presence only — never a cookie value",
            lines.any { it.contains("seeded-sapisid-value") || it.contains("seeded-sid-value") },
        )
    }

    @Test
    fun anEmptyStoreDiscardsNothing() {
        val store = testSessionStore()
        val diagnostics = SignInDiagnostics()

        val manager = testSessionManager(store, diagnostics)

        assertNull(manager.currentAuthSession())
        assertFalse(manager.uiState.value.signedIn)
        assertFalse(
            "no stored session means no discard line",
            diagnostics.lines().any { it.contains("legacy-session:") },
        )
    }

    @Test
    fun theRoadsEntryPointsAreGoneFromTheSessionManager() {
        val source = mainSource(
            "java/dev/cyberdogs/offleash/data/SessionManager.kt"
        )
        for (token in listOf(
            "completeSignIn",
            "validateNow",
            "SignInAttempt",
            "accountIdentities",
            "adoptIdentifiedSession",
            "confirmationDelaysMs",
        )) {
            assertFalse(
                "SessionManager must not carry the removed road's $token",
                source.contains(token),
            )
        }
        assertTrue(
            "the discard must be implemented, not just the absence of the road",
            source.contains("store.wipe()") && source.contains("legacy-session"),
        )
    }

    @Test
    fun noMainSourceCallsTheRemovedRoad() {
        val javaRoot = locateMainJavaRoot()
        val forbidden = listOf("completeSignIn", "validateNow(", "legacySessionValidationShouldRun")
        val offenders = mutableListOf<String>()
        javaRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                for (token in forbidden) {
                    if (text.contains(token)) {
                        offenders += "${file.path} contains $token"
                    }
                }
            }
        assertTrue(
            "no production source may call the removed road (offenders: $offenders)",
            offenders.isEmpty(),
        )
    }

    @Test
    fun theLegacyKeepAliveWorkerIsDeleted() {
        val javaRoot = locateMainJavaRoot()
        val worker = File(
            javaRoot,
            "dev/cyberdogs/offleash/account/SessionKeepAliveWorker.kt",
        )
        assertFalse(
            "the legacy keep-alive worker must not exist",
            worker.exists(),
        )
        val app = mainSource("java/dev/cyberdogs/offleash/OffleashApp.kt")
        assertFalse(
            "the container must not import the legacy keep-alive",
            app.contains("account.SessionKeepAlive"),
        )
        assertTrue(
            "the container must not enqueue the legacy keep-alive",
            app.lines().none { it.trim().startsWith("SessionKeepAlive.enqueue") },
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

    /** Locates app/src/main/java, walking up like the other contract tests. */
    private fun locateMainJavaRoot(): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, "src/main/java")
            candidates += File(dir, "app/src/main/java")
            dir = dir.parentFile
            hops++
        }
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "app main java root not found from " + File(".").absolutePath +
                    " (tried: " + candidates.joinToString { it.path } + ")",
            )
    }
}
