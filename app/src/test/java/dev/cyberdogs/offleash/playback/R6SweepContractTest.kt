package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 6 sweep contracts (source-shape pins in the house style —
 * QueueController is Media3-bound and the diagnostics screen is
 * Compose, so neither compiles in this suite; CI is their compile
 * gate):
 *
 * 1. Radio supersession was epoch-based, and the epoch was bumped
 *    by playTracks/playQueueItem only. A manual next(), a
 *    previous() that moves, or a natural track end during a radio
 *    fetch left the epoch untouched, so the returning fetch still
 *    landed and REPLACED the queue the listener had just moved to
 *    — the skip was undone and the radio's seed replayed. (The
 *    older PlaybackChaosContractTest even NAMED its pin "superseded
 *    by ANY later queue takeover" while only pinning playTracks.)
 *    Every queue progression now bumps the epoch.
 *
 * 2. The diagnostics screen's Test playback logged a fetch failure
 *    with the exception's raw message interpolated — the one
 *    raw-message sink left in the diagnostics layer, in a log the
 *    Share diagnostics button sends off-device, where a transport
 *    message can embed the signed stream URL.
 *
 * 3. The same screen's intro claimed the account block shows "the
 *    name and email" and that Share "sends them too"; the block and
 *    the share body both carry ONE identity line (name, or email
 *    when no name is known). The copy now says exactly that.
 */
class R6SweepContractTest {

    @Test
    fun manualNextBumpsTheQueueEpochSoAPendingRadioFetchIsSuperseded() {
        // Round 12 presence guard (meta-audit): queueEpoch += 1
        // is widespread in this file, so an unguarded slice whose
        // anchor vanished would widen and pass hollow (r11a F2).
        assertTrue("next() must exist at all", controller().contains("fun next()"))
        val next = controller()
            .substringAfter("fun next()")
            .substringBefore("fun previous()")
        assertTrue(
            "next() must bump queueEpoch — a skip is a queue takeover, and a " +
                "radio fetch returning after it must find its epoch stale",
            next.contains("queueEpoch += 1"),
        )
    }

    @Test
    fun previousThatMovesBumpsTheQueueEpoch() {
        assertTrue("previous() must exist at all (round 12 guard)", controller().contains("fun previous()"))
        val previous = controller()
            .substringAfter("fun previous()")
            .substringBefore("fun seekTo(")
        assertTrue(
            "previous() must bump queueEpoch when it moves to another track",
            previous.contains("queueEpoch += 1"),
        )
        // Placement is part of the claim (round 8 strengthening):
        // the seek-to-zero early return must stay BEFORE the bump —
        // that branch changes no track and deliberately bumps
        // nothing (a radio fetch for the same track may still
        // land). A bump hoisted above the early return would keep
        // the presence assertion green while changing that
        // behaviour, so the ordering is pinned too.
        val earlyReturnAt = previous.indexOf("player.seekTo(0)")
        val bumpAt = previous.indexOf("queueEpoch += 1")
        assertTrue(
            "the seek-to-zero branch must exist in previous()",
            earlyReturnAt >= 0,
        )
        assertTrue(
            "the epoch bump must come after the seek-to-zero early " +
                "return, in the branch that actually moves tracks",
            bumpAt > earlyReturnAt,
        )
    }

    @Test
    fun trackEndBumpsTheQueueEpoch() {
        assertTrue("onTrackEnded() must exist at all (round 12 guard)", controller().contains("private fun onTrackEnded()"))
        val ended = controller()
            .substringAfter("private fun onTrackEnded()")
            .substringBefore("private fun continueWithAutoplay")
        assertTrue(
            "onTrackEnded() must bump queueEpoch — natural progression " +
                "supersedes a pending radio fetch like any other takeover",
            ended.contains("queueEpoch += 1"),
        )
    }

    @Test
    fun diagnosticsTestFetchLogCarriesNoExceptionMessage() {
        val screen = mainSource("java/dev/cyberdogs/offleash/ui/about/PlaybackDiagnosticsScreen.kt")
        assertFalse(
            "the shared diagnostics log must never interpolate an exception " +
                "message — class name only, like every other diagnostics sink",
            screen.contains("\${e.message}"),
        )
        assertTrue(
            "the test-fetch failure still names the failure's class",
            screen.contains("\"failed (\${e.javaClass.simpleName})\""),
        )
    }

    @Test
    fun diagnosticsIntroMatchesWhatTheAccountBlockAndShareActuallySend() {
        val screen = mainSource("java/dev/cyberdogs/offleash/ui/about/PlaybackDiagnosticsScreen.kt")
        assertTrue(
            "the intro must say the account block shows the name OR email " +
                "(one identity line) — that is what the block renders and " +
                "what the share body sends",
            screen.contains("shows the name or email of the signed-in account"),
        )
        assertFalse(
            "the intro must not claim both the name and the email are shown/sent",
            screen.contains("shows the name and email"),
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
