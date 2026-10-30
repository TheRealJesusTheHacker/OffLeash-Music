package dev.cyberdogs.offleash.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contracts for the chaos sweep round 2 controller wiring.
 * QueueController needs a device to run, but every round-2 fix is a
 * wiring shape that can be pinned here in the house contract style,
 * so a future refactor cannot silently unwire it.
 */
class PlaybackChaosContractTest {

    private fun source(relativeFromAppModule: String): String {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, relativeFromAppModule)
            candidates += File(dir, "app/$relativeFromAppModule")
            dir = dir.parentFile
            hops++
        }
        val hit = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "$relativeFromAppModule not found from ${File(".").absolutePath}",
            )
        return hit.readText()
    }

    private fun controller(): String =
        source("src/main/java/dev/cyberdogs/offleash/playback/QueueController.kt")

    private fun onPlayerErrorBody(): String =
        controller()
            .substringAfter("override fun onPlayerError")
            .substringBefore("// Position polling")

    // -- Stale player errors ----------------------------------------------

    @Test
    fun stalePlayerErrorsAreIgnoredBeforeAnyDecision() {
        val body = onPlayerErrorBody()
        val guard = body.indexOf("playerErrorIsStale(")
        val decision = body.indexOf("playerErrorRecoveryDecision(")
        assertTrue("the stale guard must exist", guard >= 0)
        assertTrue("the recovery decision must exist", decision >= 0)
        assertTrue("the stale guard runs BEFORE the decision", guard < decision)
        assertTrue(body.contains("resolveJob?.isActive"))
    }

    // -- Cascade cap wiring -------------------------------------------------

    @Test
    fun recoveryDecisionReceivesTheCascadeState() {
        val body = onPlayerErrorBody()
        assertTrue(body.contains("cascadeSkips = recoveryCascadeSkips"))
        assertTrue(body.contains("queueSize = queue.tracks.size"))
        assertTrue(
            "a taken skip spends the cascade",
            body.contains("recoveryCascadeSkips += 1"),
        )
    }

    @Test
    fun cascadeResetsOnReadyAndOnListenerTakeover() {
        val source = controller()
        // Round 12 presence guards (meta-audit): the asserted
        // reset literal also lives in playTracks/syncQueueState/
        // retry, so an unguarded READY or playTracks slice whose
        // anchor vanished would widen over a sibling's reset and
        // pass hollow (the r11a F2 pattern).
        assertTrue("the STATE_READY branch must exist at all", source.contains("playbackState == Player.STATE_READY"))
        assertTrue("playTracks must exist at all", source.contains("fun playTracks("))
        val ready = source
            .substringAfter("playbackState == Player.STATE_READY")
            .substringBefore("Player.STATE_ENDED")
        assertTrue(
            "READY (playing) ends the failure cascade",
            ready.contains("recoveryCascadeSkips = 0"),
        )
        val playTracks = source.substringAfter("fun playTracks(")
            .substringBefore("fun play(")
        assertTrue(playTracks.contains("recoveryCascadeSkips = 0"))
        val retry = source.substringAfter("fun retryCurrent()")
            .substringBefore("fun ")
        assertTrue(retry.contains("recoveryCascadeSkips = 0"))
    }

    // -- Fallback-then-recover stays on the stream --------------------------

    @Test
    fun reResolvesAfterALocalFallbackStayOnTheStream() {
        val body = onPlayerErrorBody()
        // Both re-resolve branches pass the fallback state as
        // forceStream: replaying the local file that already failed
        // would surface the error instead of recovering the stream.
        assertTrue(
            "re-resolve branches must force the stream once the fallback was used",
            body.split("forceStream = localFallbackUsed").size - 1 >= 2,
        )
    }

    // -- Recovery respects the paused state ---------------------------------

    @Test
    fun recoveryHandsBackThePlayWhenReadyCapturedAtTheError() {
        val body = onPlayerErrorBody()
        assertTrue(body.contains("val playWhenReadyAtError = player.playWhenReady"))
        assertTrue(
            "recovery branches must thread the captured intent",
            body.split("resumePlayback = playWhenReadyAtError").size - 1 >= 4,
        )
        val handOff = controller().substringAfter("private fun handToPlayer")
        assertTrue(handOff.contains("if (resumePlayback)"))
        assertTrue(handOff.contains("player.pause()"))
    }

    // -- The sleep timer wins over an in-flight recovery --------------------

    @Test
    fun sleepTimerExpiryCancelsAnInFlightResolve() {
        assertTrue("expireSleepTimer must exist at all (round 12 guard)", controller().contains("private fun expireSleepTimer()"))
        val expire = controller()
            .substringAfter("private fun expireSleepTimer()")
            .substringBefore("private fun applyPlaybackParameters")
        assertTrue(expire.contains("resolveJob?.cancel()"))
        assertTrue(expire.contains("autoplayJob?.cancel()"))
    }

    // -- Superseded fetches cannot resurrect a queue --------------------------

    @Test
    fun autoplayFetchAbandonsWhenThePositionMovedMidFetch() {
        val autoplay = controller()
            .substringAfter("private fun continueWithAutoplay")
            .substringBefore("private fun trySkipFailedAutoplayTrack")
        assertTrue(autoplay.contains("queue.currentIndex != indexAtRequest"))
        assertTrue(autoplay.contains("queue.tracks !== queueAtRequest"))
    }

    @Test
    fun radioFetchIsSupersededByAnyLaterQueueTakeover() {
        val source = controller()
        assertTrue(source.contains("private var queueEpoch"))
        val radio = source.substringAfter("fun startRadio(track: Track)")
            .substringBefore("private fun onTrackEnded()")
        assertTrue(radio.contains("epochAtStart"))
        assertTrue(radio.contains("epochAtStart != queueEpoch"))
        val playTracks = source.substringAfter("fun playTracks(")
            .substringBefore("fun play(")
        assertTrue("a fresh queue bumps the epoch", playTracks.contains("queueEpoch += 1"))
    }

    // -- Queue mutations preserve history and pass --------------------------

    @Test
    fun queueMutationsUseThePreservingCoreOperations() {
        val source = controller()
        val add = source.substringAfter("fun addToQueue(track: Track)")
            .substringBefore("fun playNext(track: Track)")
        assertTrue("addToQueue appends without rebuilding", add.contains("queue.append(track)"))
        assertFalse("addToQueue must not rebuild via setQueue", add.contains("setQueue(current"))
        val playNext = source.substringAfter("fun playNext(track: Track)")
            .substringBefore("fun clearError()")
        assertTrue("playNext inserts at the head of the pass", playNext.contains("queue.insertNext(track)"))
        val autoplay = source.substringAfter("private fun continueWithAutoplay")
            .substringBefore("private fun trySkipFailedAutoplayTrack")
        assertTrue("autoplay appends its continuation", autoplay.contains("queue.appendAll(continuation)"))
        assertFalse(
            "autoplay must not rebuild the queue via setQueue",
            autoplay.contains("queue.setQueue(queueAtRequest"),
        )
    }

    // -- No double-spend of the autoplay failure counter ---------------------

    @Test
    fun autoplaySkipIsOfferedExactlyOncePerPlayerError() {
        // The SKIP branch offers the track to the capped autoplay
        // skip; the fall-through below it is guarded to SURFACE
        // decisions only, so one error can never spend the counter
        // twice (pinned in round 1, re-pinned after round 2 edits).
        val body = onPlayerErrorBody()
        assertTrue(
            body.contains("decision == PlayerErrorRecoveryAction.SURFACE_ERROR"),
        )
        assertEquals(
            "trySkipFailedAutoplayTrack is called at the two decision exits only here",
            2,
            body.split("trySkipFailedAutoplayTrack(").size - 1,
        )
    }
}
