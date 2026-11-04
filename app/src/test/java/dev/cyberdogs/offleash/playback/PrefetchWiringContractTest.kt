package dev.cyberdogs.offleash.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract for the next-track prefetch wiring (2026-11-04
 * playback-finish run), in the house contract-test style: the
 * controller's behaviour needs a device, but the WIRING — prefetch
 * armed from the position poll and every hand-off, the stash
 * claimed under the pure verdict inside resolveAndPlay AFTER the
 * local-file hand-off, the prefetch resolve barred from structured
 * diagnostics and from the session's revalidation budget, and the
 * continuation excluding recent history — is pinnable here so a
 * future refactor can't silently unwire any of it.
 */
class PrefetchWiringContractTest {

    private fun sourceOf(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidates = listOf(
                File(dir, "src/main/java/dev/cyberdogs/offleash/playback/$relative"),
                File(dir, "app/src/main/java/dev/cyberdogs/offleash/playback/$relative"),
            )
            val hit = candidates.firstOrNull { it.isFile }
            if (hit != null) return hit.readText()
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found from ${File(".").absolutePath}")
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    @Test
    fun prefetchDecisionsLiveInThePureFile() {
        val source = sourceOf("PlaybackPrefetch.kt")
        assertTrue(source.contains("fun prefetchCandidate("))
        assertTrue(source.contains("fun prefetchConsumeVerdict("))
        assertTrue(source.contains("fun prefetchDueByPosition("))
        assertTrue(source.contains("PREFETCH_MAX_AGE_MS"))
        assertTrue(source.contains("PREFETCH_LEAD_MS"))
    }

    @Test
    fun prefetchIsArmedFromThePollAndFromEveryHandOff() {
        val source = sourceOf("QueueController.kt")
        assertTrue(
            "the definition must exist (guard: the call-site pins " +
                "below would pass hollow on the definition alone)",
            source.contains("private fun maybePrefetchNext()"),
        )
        // Definition + handToPlayer tail + position poll = at
        // least three mentions of the call.
        assertTrue(
            "maybePrefetchNext must be called from handToPlayer and the poll",
            source.countOccurrences("maybePrefetchNext()") >= 3,
        )
        assertTrue(
            source.contains(
                "prefetchDueByPosition(player.duration, player.currentPosition)",
            ),
        )
    }

    @Test
    fun stashIsClaimedUnderTheVerdictAfterTheLocalFileWins() {
        val source = sourceOf("QueueController.kt")
        assertTrue(source.contains("prefetchConsumeVerdict("))
        val localCheck = source.indexOf("localFileProvider(track)")
        val verdictCall = source.indexOf("prefetchConsumeVerdict(")
        assertTrue("resolveAndPlay's local-file check must exist", localCheck >= 0)
        assertTrue(
            "the prefetch verdict is consulted only after the " +
                "downloaded-file hand-off",
            verdictCall > localCheck,
        )
        assertTrue(source.contains("PrefetchConsume.TAKE"))
    }

    @Test
    fun prefetchResolveIsBarredFromDiagnosticsAndRevalidation() {
        val source = sourceOf("QueueController.kt")
        assertTrue(
            "prefetch must pass recordDiagnostics = false",
            source.contains("recordDiagnostics = false"),
        )
        assertTrue(
            "prefetch must pass allowRevalidate = false",
            source.contains("allowRevalidate = false"),
        )
        assertEquals(
            "the resolveWithRoads gate defaults stay honest",
            1,
            source.countOccurrences("recordDiagnostics: Boolean = true"),
        )
    }

    @Test
    fun prefetchNeverTouchesTheLiveResolveOrTheNamedError() {
        val source = sourceOf("QueueController.kt")
        assertTrue(source.contains("private fun maybePrefetchNext()"))
        val body = source.substringAfter("private fun maybePrefetchNext()")
            .substringBefore("private fun contextByClientName")
        assertFalse(
            "a failed prefetch must stay silent — no named error",
            body.contains("_namedError"),
        )
        assertFalse(
            "prefetch must not drive the resolving flag",
            body.contains("setResolving("),
        )
        assertTrue(
            "prefetch failure is logged under its own stage",
            body.contains("\"prefetch\""),
        )
    }

    @Test
    fun stopPlaybackRetiresThePrefetch() {
        val source = sourceOf("QueueController.kt")
        assertTrue(source.contains("fun stopPlayback()"))
        val body = source.substringAfter("fun stopPlayback()")
            .substringBefore("fun next()")
        assertTrue(body.contains("prefetchJob?.cancel()"))
        assertTrue(body.contains("prefetchedNext = null"))
    }

    @Test
    fun continuationExcludesRecentHistory() {
        val source = sourceOf("QueueController.kt")
        assertTrue(source.contains("recentsStore.recents()"))
        assertTrue(source.contains("recentIds = recentIds"))
    }
}
