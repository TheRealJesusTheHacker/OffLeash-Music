package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 8 sweep contracts (QueueController is Media3-bound and
 * does not compile in this suite; CI is its compile gate).
 *
 * Round 7 bumped queueEpoch at retryCurrent() so the error card's
 * Try again supersedes a radio fetch — but the bump alone leaves
 * one in-flight worker untouched: the AUTOPLAY CONTINUATION is
 * not epoch-guarded at all. It keys on queue identity + index,
 * and a retry changes neither, so a continuation in flight when
 * Try again is tapped (reachable: the last track fails, the
 * listener taps Next — starting a continuation — and then taps
 * the still-visible error card's Try again) still lands after
 * the retry, appends its tracks, and plays the next one,
 * cancelling the retry's resolve. Every other takeover either
 * cancels the continuation job (playTracks / playQueueItem via
 * resetAutoplayChain, expireSleepTimer directly) or trips its
 * identity/index guards (addToQueue / playNext replace the track
 * list; the skips and next() move the index). retryCurrent must
 * cancel the job outright, exactly like expireSleepTimer.
 */
class R8SweepContractTest {

    @Test
    fun retryCurrentCancelsAnInFlightAutoplayContinuation() {
        assertTrue("retryCurrent must exist at all (round 12 guard)", controller().contains("fun retryCurrent()"))
        val body = controller()
            .substringAfter("fun retryCurrent()")
            .substringBefore("// -- Lane G public API")
        assertTrue(
            "retryCurrent must cancel autoplayJob — the continuation is " +
                "not epoch-guarded and a retry trips neither of its guards",
            body.contains("autoplayJob?.cancel()"),
        )
        assertTrue(
            "the cancel must precede the retry's resolve",
            body.indexOf("autoplayJob?.cancel()") < body.indexOf("resolveAndPlay(track)"),
        )
    }

    private fun controller(): String =
        mainSource("java/dev/cyberdogs/offleash/playback/QueueController.kt")

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
