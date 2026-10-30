package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 12 sweep contracts (confirmation round — breaking the
 * round-11 stop routing):
 *
 * Round 11's stopPlayback cancels the autoplay continuation, the
 * resolve, and bumps the queue epoch before stopping the player.
 * Attacking its state publication found one flag none of those
 * cancels clears: the resolving flag. Every ending that normally
 * clears it is a branch the cancelled continuation never reaches
 * (its post-fetch branches), a listener state a stopped player
 * never enters (READY), the resolve job's own cancellation catch
 * (which only the resolve job runs), or the radio landing's epoch
 * branch (which only the radio job runs). A stop landing during
 * a continuation fetch therefore left the loading state published
 * over a stopped player indefinitely. The stop itself — the
 * terminal intent that cancelled the flag's last owner — must
 * clear it.
 *
 * QueueController is Media3-bound, so the shape is the pin
 * (house style; CI is the compile gate).
 */
class R12SweepContractTest {

    @Test
    fun stopPlaybackClearsTheResolvingFlagItsCancelsOrphan() {
        val body = controller()
            .substringAfter("fun stopPlayback()")
            .substringBefore("fun next()")
        assertTrue(
            "stopPlayback must clear the resolving flag itself: the " +
                "continuation it cancels never reaches a branch that " +
                "clears it, and a stopped player never reaches the " +
                "READY state whose listener clears it — the loading " +
                "state would outlive the stop it cancelled",
            body.contains("setResolving(false)"),
        )
        assertTrue(
            "the flag must be cleared before the stop's state sync, " +
                "which rebuilds the published snapshot around the " +
                "flag's current value",
            body.indexOf("setResolving(false)") <
                body.indexOf("syncPlaybackState()"),
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
