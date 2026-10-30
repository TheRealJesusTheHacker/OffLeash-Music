package dev.cyberdogs.offleash.playback

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract for the autoplay continuation wiring (2026-10-27
 * queue-advancement hunt), in the house contract-test style: the
 * controller's behavior needs a device, but the WIRING — end-of-track
 * and end-of-queue reaching the continuation, the sleep timer
 * cancelling the fetch, user-chosen queues resetting the chain —
 * is pinnable here so a future refactor can't silently unwire it.
 */
class AutoplayWiringContractTest {

    private fun queueControllerSource(): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidates = listOf(
                File(
                    dir,
                    "src/main/java/dev/cyberdogs/offleash/playback/QueueController.kt",
                ),
                File(
                    dir,
                    "app/src/main/java/dev/cyberdogs/offleash/playback/QueueController.kt",
                ),
            )
            val hit = candidates.firstOrNull { it.isFile }
            if (hit != null) return hit.readText()
            dir = dir.parentFile
        }
        throw AssertionError("QueueController.kt not found from ${File(".").absolutePath}")
    }

    @Test
    fun endOfTrackAndManualNextBothReachTheContinuation() {
        val source = queueControllerSource()
        assertTrue(source.contains("continueWithAutoplay(seed, fromTrackEnd = true)"))
        assertTrue(source.contains("continueWithAutoplay(seed, fromTrackEnd = false)"))
    }

    @Test
    fun continuationReusesTheRelatedMachineryAndThePureBuilder() {
        val source = queueControllerSource()
        assertTrue(source.contains("client.related(seed.videoId)"))
        assertTrue(source.contains("autoplayContinuation("))
    }

    @Test
    fun sleepTimerExpiryCancelsTheAutoplayFetch() {
        val source = queueControllerSource()
        // Slice bound tightened in round 9: the next declaration
        // after expireSleepTimer is the PUBLIC setPlaybackSpeed, so
        // bounding on "private fun" let the slice run on through two
        // more functions. It still contained exactly one cancel —
        // expiry's own — so the pin was deletion-sensitive, but the
        // slice now ends where the function ends. (retryCurrent's
        // own cancel of the same job is pinned separately by
        // playback/R8SweepContractTest; this file's claim is the
        // timer's.)
        assertTrue(
            "expireSleepTimer must exist at all (round 12 guard: " +
                "the cancel literal is widespread, so an unguarded " +
                "missing anchor would widen this slice and pass hollow)",
            source.contains("private fun expireSleepTimer()"),
        )
        val expire = source.substringAfter("private fun expireSleepTimer()")
            .substringBefore("fun setPlaybackSpeed")
        assertTrue(expire.contains("autoplayJob?.cancel()"))
    }

    @Test
    fun listenerQueuesResetTheAutoplayChain() {
        val source = queueControllerSource()
        assertTrue(source.contains("private fun resetAutoplayChain()"))
        assertTrue(
            "playTracks must exist at all (round 12 guard: a " +
                "widened slice reaches resetAutoplayChain's definition)",
            source.contains("fun playTracks("),
        )
        val playTracks = source.substringAfter("fun playTracks(")
            .substringBefore("fun play(")
        assertTrue(playTracks.contains("resetAutoplayChain()"))
    }

    @Test
    fun failureSkipsAreCappedAndScopedToAutoplayTracks() {
        val source = queueControllerSource()
        assertTrue(source.contains("MAX_AUTOPLAY_CONSECUTIVE_FAILURES"))
        assertTrue(source.contains("failed.videoId !in autoplayTrackIds"))
    }
}
