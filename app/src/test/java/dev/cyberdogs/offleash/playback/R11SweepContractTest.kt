package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 11 sweep contracts (confirmation round — breaking the
 * round-10 pause unification):
 *
 * Round 10 routed the session player's pause() to
 * QueueController.pausePlayback(), on the rule that every
 * pause-shaped surface must carry the continuation cancel. The
 * transport census re-run in round 11 found two more verbs on
 * QueueTransportPlayer that never reached the controller:
 *
 * 1. stop() — no override existed, so a controller stop (the
 *    MediaSession COMMAND_STOP command; the legacy/platform
 *    stub's onStop, e.g. a Bluetooth / media-button MEDIA_STOP)
 *    delegated straight to the raw player. The player halted,
 *    but an in-flight autoplay continuation (and a radio fetch
 *    and a resolve) stayed alive: on landing it appended,
 *    advanced and PLAYED — against an explicit stop, the exact
 *    defeat round 10 closed for pause through the same door.
 *    Stop is a terminal intent, so the controller's stop also
 *    cancels the resolve and bumps the queue epoch, mirroring
 *    expireSleepTimer's "the stop wins over everything in
 *    flight" discipline.
 * 2. setPlayWhenReady(false) — the session stub exposes it to
 *    controllers as its own command (gated on COMMAND_PLAY_PAUSE),
 *    and ForwardingPlayer forwarded it to the raw player,
 *    bypassing BOTH the play() and the pause() overrides: a
 *    pause expressed in this verb carried no cancel either.
 *    The override now routes false to the one pause and true
 *    through the play() override, so the dead-state Play
 *    routing applies to this verb too.
 *
 * QueueController and PlaybackService are Media3-bound, so the
 * shape is the pin (house style; CI is the compile gate).
 */
class R11SweepContractTest {

    @Test
    fun controllerHasOneStopThatCancelsEverythingInFlight() {
        assertTrue(
            "the controller must have a stop entry point at all",
            controller().contains("fun stopPlayback()"),
        )
        val body = controller()
            .substringAfter("fun stopPlayback()")
            .substringBefore("fun next()")
        assertTrue(
            "stopPlayback must cancel the in-flight autoplay continuation",
            body.contains("autoplayJob?.cancel()"),
        )
        assertTrue(
            "stopPlayback must cancel an in-flight resolve — a resolve " +
                "landing after a stop would hand its track to the player " +
                "and press play (expireSleepTimer's precedent)",
            body.contains("resolveJob?.cancel()"),
        )
        assertTrue(
            "stopPlayback must bump the queue epoch — a radio fetch " +
                "landing after a stop would replace the queue and play " +
                "(expireSleepTimer's precedent)",
            body.contains("queueEpoch += 1"),
        )
        assertTrue(
            "stopPlayback must stop the player",
            body.contains("player.stop()"),
        )
        assertTrue(
            "the cancels must land before the player stops",
            body.indexOf("autoplayJob?.cancel()") < body.indexOf("player.stop()") &&
                body.indexOf("resolveJob?.cancel()") < body.indexOf("player.stop()"),
        )
    }

    @Test
    fun sessionPlayerStopRoutesToTheControllerStop() {
        // Round 12 guard (meta-audit): this slice's end bound is
        // the setPlayWhenReady override; that anchor is asserted
        // in the sibling test below, and now here too, so this
        // test alone cannot widen to end-of-file in silence.
        assertTrue(
            "the setPlayWhenReady override bounding this slice must exist",
            mainSource("java/dev/cyberdogs/offleash/playback/PlaybackService.kt")
                .contains("override fun setPlayWhenReady"),
        )
        val service = mainSource(
            "java/dev/cyberdogs/offleash/playback/PlaybackService.kt",
        )
        assertTrue(
            "the session player must override stop() at all",
            service.contains("override fun stop()"),
        )
        val stop = service.substringAfter("override fun stop()")
            .substringBefore("override fun setPlayWhenReady")
        assertTrue(
            "the session player's stop must route to the controller's " +
                "stop (which cancels everything in flight), not delegate " +
                "to the raw player",
            stop.contains("queueController.stopPlayback()"),
        )
        assertTrue(
            "the raw-player stop delegation must be gone from the override",
            !stop.contains("super.stop()"),
        )
    }

    @Test
    fun sessionPlayerSetPlayWhenReadyRoutesBothDirections() {
        val service = mainSource(
            "java/dev/cyberdogs/offleash/playback/PlaybackService.kt",
        )
        assertTrue(
            "the session player must override setPlayWhenReady at all — " +
                "without the override the verb delegates to the raw player " +
                "and every assertion below could be satisfied by other " +
                "overrides' text in the same file",
            service.contains("override fun setPlayWhenReady(playWhenReady: Boolean)"),
        )
        val verb = service.substringAfter("override fun setPlayWhenReady(")
        assertTrue(
            "setPlayWhenReady(false) must route to the controller's one " +
                "pause, so a pause in this verb carries the continuation " +
                "cancel like every other pause surface",
            verb.contains("queueController.pausePlayback()"),
        )
        assertTrue(
            "setPlayWhenReady(true) must route through the play() " +
                "override, so the dead-state (ENDED/IDLE) routing to the " +
                "controller toggle applies to this verb too",
            verb.contains("play()"),
        )
        // Round 12 strengthening: the whole-verb contains("play()")
        // above is satisfied by this override's own comment (which
        // spells the call) and by the substring inside a raw-player
        // delegation — deleting the true branch's call, or rerouting
        // it to the raw player, passed it. The branch slice below
        // starts after the comment block, so only the branch's own
        // code can satisfy it.
        val trueBranch = verb
            .substringAfter("if (playWhenReady) {")
            .substringBefore("} else {")
        assertTrue(
            "the true branch itself must call the play() override — " +
                "the comment's spelling of the call must not be what " +
                "satisfies this pin",
            trueBranch.contains("play()"),
        )
        assertTrue(
            "the true branch must not delegate to the raw player — " +
                "that call contains the play() spelling as a substring " +
                "but bypasses the dead-state routing this verb exists " +
                "to carry",
            !trueBranch.contains("super.play()") &&
                !verb.contains("super.play()"),
        )
        assertTrue(
            "the two routings must sit behind the playWhenReady branch",
            verb.indexOf("if (playWhenReady)") <
                verb.indexOf("queueController.pausePlayback()"),
        )
        assertTrue(
            "the raw-player setPlayWhenReady delegation must be gone",
            !verb.contains("super.setPlayWhenReady"),
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
