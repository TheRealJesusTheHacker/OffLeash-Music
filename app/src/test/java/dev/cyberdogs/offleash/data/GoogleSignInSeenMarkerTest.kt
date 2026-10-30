package dev.cyberdogs.offleash.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The Google-sign-in-seen marker and the account-presentation
 * decision it drives (bug sweep round 1, integration): once a
 * Google sign-in has happened, the legacy cookie session may never
 * be presented again — a Google store wiped on its own (revoked
 * grant, rejected identity, unrefreshable token) must land on the
 * honest Google signed-out state, not resurrect the zombie.
 */
class GoogleSignInSeenMarkerTest {

    private fun markerIn(dir: File): GoogleSignInSeenMarker =
        GoogleSignInSeenMarker(File(dir, "seen.txt"))

    @Test
    fun freshDeviceHasNotSeenAGoogleSignIn() {
        val dir = Files.createTempDirectory("marker").toFile()
        assertFalse(markerIn(dir).seen())
    }

    @Test
    fun markPersistsAcrossInstances() {
        val dir = Files.createTempDirectory("marker").toFile()
        markerIn(dir).mark()
        // A new instance (a new process, in device terms) sees it.
        assertTrue(markerIn(dir).seen())
    }

    @Test
    fun markIsIdempotentAndKeepsTheFirstMark() {
        val dir = Files.createTempDirectory("marker").toFile()
        val marker = markerIn(dir)
        marker.mark()
        val first = File(dir, "seen.txt").readText()
        marker.mark()
        assertTrue(marker.seen())
        assertTrue(File(dir, "seen.txt").readText() == first)
    }

    @Test
    fun anEmptyFileIsNotAMark() {
        val dir = Files.createTempDirectory("marker").toFile()
        File(dir, "seen.txt").writeText("")
        assertFalse(markerIn(dir).seen())
    }

    @Test
    fun anUnwritableMarkerDegradesInsteadOfThrowing() {
        // A path inside a nonexistent directory: mark() must not
        // throw, and seen() reads as the pre-sweep behaviour.
        val marker = GoogleSignInSeenMarker(
            File("/nonexistent-dir-for-marker-test/seen.txt")
        )
        marker.mark()
        assertFalse(marker.seen())
    }

    @Test
    fun legacyFallbackAppliesOnlyBeforeAnyGoogleSignIn() {
        // Never had Google: the cookie session names the account.
        assertTrue(legacyFallbackApplies(googleSignedIn = false, googleSignInSeen = false))
        // Google live: no fallback (and no need of one).
        assertFalse(legacyFallbackApplies(googleSignedIn = true, googleSignInSeen = false))
        assertFalse(legacyFallbackApplies(googleSignedIn = true, googleSignInSeen = true))
        // The resurrection case: Google wiped alone after having
        // existed — the fallback must NOT apply.
        assertFalse(legacyFallbackApplies(googleSignedIn = false, googleSignInSeen = true))
    }

    @Test
    fun containerWiringConsultsTheMarkerEverywhere() {
        val app = mainSource("java/dev/cyberdogs/offleash/OffleashApp.kt")
        // The snapshot decides through the pure rule.
        assertTrue(app.contains("legacyFallbackApplies(google.signedIn, googleSignInSeenMarker.seen())"))
        // Adoption marks (collector), a stored session at start
        // marks (init), and both legacy refresh paths are gated.
        assertTrue(app.contains("if (it.signedIn) googleSignInSeenMarker.mark()"))
        assertTrue(app.contains("googleSignInSeenMarker.mark()"))
        assertTrue(
            "app-start legacy validation must be dormancy-gated",
            app.contains("!googleSessionManager.hasStoredSession() &&") &&
                app.contains("!googleSignInSeenMarker.seen()"),
        )
    }

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
                "$relativePath not found from " + File(".").absolutePath
            )
        return found.readText()
    }
}
