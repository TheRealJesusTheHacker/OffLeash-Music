package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 7 sweep contracts (QueueController is Media3-bound and
 * does not compile in this suite; CI is its compile gate):
 *
 * Round 6 bumped queueEpoch at next()/previous()/onTrackEnded so a
 * pending radio fetch could not land after a progression and
 * replace the queue. Round 7 completes the census of queue
 * takeovers that still did NOT bump:
 *
 * - addToQueue()/playNext() mutate the queue's contents — a radio
 *   landing after them replaced the whole queue and the track the
 *   listener had just queued (or set to play next) silently
 *   vanished.
 * - retryCurrent() is the listener's explicit takeover of the
 *   current track after a failure.
 * - expireSleepTimer() cancels the autoplay and resolve jobs
 *   precisely so no in-flight work can press play after the
 *   timer's pause — but the radio job is not held in a field, and
 *   its landing (playTracks + play) defeated the timer.
 * - The player-error recovery skip and the autoplay skip advance
 *   the queue exactly like next()/onTrackEnded do.
 */
class R7SweepContractTest {

    @Test
    fun addToQueueBumpsTheQueueEpoch() {
        assertTrue("addToQueue must exist at all (round 12 guard)", controller().contains("fun addToQueue(track: Track)"))
        val body = controller()
            .substringAfter("fun addToQueue(track: Track)")
            .substringBefore("fun playNext(track: Track)")
        assertTrue(
            "addToQueue must bump queueEpoch — a queued track is listener " +
                "intent a stale radio landing must not wipe",
            body.contains("queueEpoch += 1"),
        )
    }

    @Test
    fun playNextBumpsTheQueueEpoch() {
        assertTrue("playNext must exist at all (round 12 guard)", controller().contains("fun playNext(track: Track)"))
        val body = controller()
            .substringAfter("fun playNext(track: Track)")
            .substringBefore("fun clearError()")
        assertTrue(
            "playNext must bump queueEpoch — an inserted next track is " +
                "listener intent a stale radio landing must not wipe",
            body.contains("queueEpoch += 1"),
        )
    }

    @Test
    fun retryCurrentBumpsTheQueueEpoch() {
        assertTrue("retryCurrent must exist at all (round 12 guard)", controller().contains("fun retryCurrent()"))
        val body = controller()
            .substringAfter("fun retryCurrent()")
            .substringBefore("// -- Lane G public API")
        assertTrue(
            "retryCurrent must bump queueEpoch — Try again is a listener takeover",
            body.contains("queueEpoch += 1"),
        )
    }

    @Test
    fun sleepTimerExpiryBumpsTheQueueEpoch() {
        assertTrue("expireSleepTimer must exist at all (round 12 guard)", controller().contains("private fun expireSleepTimer()"))
        val body = controller()
            .substringAfter("private fun expireSleepTimer()")
            .substringBefore("fun setPlaybackSpeed")
        assertTrue(
            "expireSleepTimer must bump queueEpoch — the radio job has no " +
                "field handle, so the epoch is the only way the timer wins " +
                "over a radio fetch still in flight",
            body.contains("queueEpoch += 1"),
        )
    }

    @Test
    fun autoplaySkipBumpsTheQueueEpoch() {
        assertTrue("trySkipFailedAutoplayTrack must exist at all (round 12 guard)", controller().contains("private fun trySkipFailedAutoplayTrack("))
        val body = controller()
            .substringAfter("private fun trySkipFailedAutoplayTrack(")
            .substringBefore("private fun resolveAndPlay(")
        assertTrue(
            "the autoplay failure skip advances the queue and must bump " +
                "queueEpoch like every other progression",
            body.contains("queueEpoch += 1"),
        )
    }

    @Test
    fun playerErrorRecoverySkipBumpsTheQueueEpoch() {
        assertTrue("onPlayerError must exist at all (round 12 guard)", controller().contains("override fun onPlayerError"))
        val body = controller()
            .substringAfter("override fun onPlayerError")
            .substringBefore("// Position polling")
        assertTrue(
            "the player-error recovery skip advances the queue and must " +
                "bump queueEpoch like next()/onTrackEnded do",
            body.contains("queueEpoch += 1"),
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
