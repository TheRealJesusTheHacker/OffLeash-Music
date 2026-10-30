package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 10 sweep contracts (confirmation round — breaking the
 * round-9 fixes):
 *
 * Round 9's continuation census taught the IN-APP pause
 * (togglePlayPause's pause branch) to cancel an in-flight
 * autoplay continuation, on the stated rule "a pause is the
 * listener's latest word". But the census keyed on the
 * controller's own entry points, and one pause surface never
 * reaches the controller at all: external controls (notification,
 * lock screen, Bluetooth) act on the MEDIA SESSION's player, and
 * QueueTransportPlayer overrode play/next/previous — never
 * pause — so an external pause delegated straight to the raw
 * player and carried no cancel. A continuation fetch started by
 * a manual Next at the end of the queue therefore survived an
 * external pause and, on landing, advanced and played against
 * it — the exact defeat round 9 closed for the in-app button,
 * through the session door.
 *
 * The fix: the controller grows one pause every surface routes
 * through (`pausePlayback` — cancel + pause + sync), the toggle's
 * pause branch delegates to it, and QueueTransportPlayer.pause()
 * routes to it. QueueController and PlaybackService are
 * Media3-bound, so the shape is the pin (house style; CI is the
 * compile gate).
 */
class R10SweepContractTest {

    @Test
    fun controllerHasOnePauseThatCancelsTheContinuation() {
        // Round 12 strengthening (meta-audit): stopPlayback was
        // inserted between pausePlayback and next() in round 11,
        // so the old slice bound ("fun next()") now spans stop's
        // body too — and stop's own autoplay cancel satisfied the
        // cancel assertion even with pause's cancel deleted. The
        // slice is re-bounded at its true successor, and the
        // anchor itself is asserted present (the r11a F2 lesson:
        // substringAfter returns the whole file when its anchor
        // is absent, and the whole file before stopPlayback
        // contains other cancels and a player.pause()).
        assertTrue(
            "pausePlayback must exist at all",
            controller().contains("fun pausePlayback()"),
        )
        val body = controller()
            .substringAfter("fun pausePlayback()")
            .substringBefore("fun stopPlayback()")
        assertTrue(
            "pausePlayback must cancel the in-flight autoplay continuation",
            body.contains("autoplayJob?.cancel()"),
        )
        assertTrue(
            "pausePlayback must pause the player",
            body.contains("player.pause()"),
        )
        val toggle = controller()
            .substringAfter("fun togglePlayPause()")
            .substringBefore("fun pausePlayback()")
        assertTrue(
            "togglePlayPause's pause branch must delegate to the one " +
                "pause, not carry a second, diverging pause shape",
            toggle.contains("pausePlayback()"),
        )
    }

    @Test
    fun sessionPlayerPauseRoutesToTheControllerPause() {
        val service = mainSource(
            "java/dev/cyberdogs/offleash/playback/PlaybackService.kt",
        )
        // Round 12 strengthening (meta-audit): this slice used to
        // run to end-of-file because pause() was the transport's
        // last member (the round-11 audit verified exactly that).
        // Round 11 added stop() and setPlayWhenReady() AFTER
        // pause() — and setPlayWhenReady's false branch itself
        // calls queueController.pausePlayback(), so the unbounded
        // slice satisfied the routing assertion even with pause's
        // own routing (or the whole pause override) deleted. The
        // slice is now bounded at its true successor, stop(), and
        // the anchor is asserted present.
        assertTrue(
            "the session player must override pause() at all",
            service.contains("override fun pause()"),
        )
        val pause = service.substringAfter("override fun pause()")
            .substringBefore("override fun stop()")
        assertTrue(
            "the session player's pause must route to the controller's " +
                "pause (which cancels the continuation), not delegate " +
                "to the raw player",
            pause.contains("queueController.pausePlayback()"),
        )
        assertTrue(
            "the raw-player pause delegation must be gone from the override",
            !pause.contains("super.pause()"),
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
