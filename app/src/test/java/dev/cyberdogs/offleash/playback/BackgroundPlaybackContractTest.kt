package dev.cyberdogs.offleash.playback

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract for the background-playback chain (H8) and the
 * notification / lock-screen / Bluetooth transport routing.
 *
 * H8's history: background playback died ~5 seconds after
 * screen-off in Phase 2 and was never re-verified after the
 * addSession fix. The chain has five links, all pinnable here:
 * the controller starts the foreground service on every play (and
 * again when the item lands), the service registers its session
 * (addSession — without it MediaSessionService never promotes the
 * service to the foreground and the process is reaped), the player
 * is built with network wake mode + audio focus + becoming-noisy,
 * the notification permission is requested, and onTaskRemoved
 * only stops the service when nothing is playing. What code
 * cannot pin — OEM Doze behavior, promotion timing on a real
 * device — stays a device-proof item, named in the sweep report.
 *
 * The transport half pins the QueueTransportPlayer routing: the
 * session player feeds external controllers ONE resolved item, so
 * its native next/previous cannot express the real queue; the
 * wrapper must send those commands to QueueController.
 */
class BackgroundPlaybackContractTest {

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

    private fun queueController(): String =
        source("src/main/java/dev/cyberdogs/offleash/playback/QueueController.kt")

    private fun playbackService(): String =
        source("src/main/java/dev/cyberdogs/offleash/playback/PlaybackService.kt")

    private fun appContainer(): String =
        source("src/main/java/dev/cyberdogs/offleash/OffleashApp.kt")

    // -- H8: the foreground chain ----------------------------------------

    @Test
    fun serviceRegistersItsSessionForForegroundPromotion() {
        val service = playbackService()
        assertTrue(
            "PlaybackService must addSession or the service never goes foreground",
            service.contains("addSession(session)"),
        )
        assertTrue(service.contains("MediaSession.Builder(this, sessionPlayer)"))
    }

    @Test
    fun everyPlayStartsTheForegroundService() {
        val controller = queueController()
        assertTrue(controller.contains("ContextCompat.startForegroundService("))
        val resolve = controller.substringAfter("private fun resolveAndPlay")
            .substringBefore("private fun handToPlayer")
        assertTrue(
            "resolveAndPlay must (re)start the service — no one-shot latch",
            resolve.contains("ensureServiceStarted()"),
        )
        assertTrue(
            "handToPlayer must exist at all (round 12 guard: its " +
                "service-start assertion literal also lives in resolveAndPlay)",
            controller.contains("private fun handToPlayer"),
        )
        val handOff = controller.substringAfter("private fun handToPlayer")
            .substringBefore("private suspend fun resolveWithRoads")
        assertTrue(
            "the service start is re-delivered when the item lands and plays",
            handOff.contains("ensureServiceStarted()"),
        )
    }

    @Test
    fun taskRemovalOnlyStopsTheServiceWhenNothingIsPlaying() {
        val service = playbackService()
        val onRemoved = service.substringAfter("override fun onTaskRemoved")
            .substringBefore("override fun onDestroy")
        assertTrue(onRemoved.contains("!sessionPlayer.playWhenReady"))
        assertTrue(onRemoved.contains("stopSelf()"))
    }

    @Test
    fun playerIsBuiltForBackgroundPlayback() {
        val container = appContainer()
        val buildPlayer = container.substringAfter("private fun buildPlayer")
        assertTrue(
            "network wake mode keeps the radio + CPU while streaming",
            buildPlayer.contains(".setWakeMode(C.WAKE_MODE_NETWORK)"),
        )
        assertTrue(
            "audio focus is the player's (duck/pause on calls and other apps)",
            buildPlayer.contains("handleAudioFocus = */ true"),
        )
        assertTrue(
            "becoming-noisy pauses (headphones unplugged)",
            buildPlayer.contains(".setHandleAudioBecomingNoisy(true)"),
        )
        assertTrue(
            "the service adopts the SAME player the container built",
            playbackService().contains("container.player"),
        )
    }

    @Test
    fun notificationPermissionIsRequestedAtLaunch() {
        val mainActivity = source(
            "src/main/java/dev/cyberdogs/offleash/MainActivity.kt",
        )
        assertTrue(mainActivity.contains("Manifest.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun processLifetimeMediaControllerIsRetained() {
        // The container's controller connection keeps the session's
        // controller path live for the process lifetime; playback
        // must not depend on it, but it must not be dropped either.
        val container = appContainer()
        assertTrue(container.contains("MediaController.Builder(app, token).buildAsync()"))
        assertTrue(container.contains("mediaController = future.get()"))
    }

    // -- Transport routing: no dead or wrong-queue controls ---------------

    @Test
    fun sessionPlayerRoutesNextAndPreviousToTheRealQueue() {
        val service = playbackService()
        assertTrue(service.contains("ForwardingPlayer"))
        assertTrue(service.contains("QueueTransportPlayer(container.player"))
        val next = service.substringAfter("override fun seekToNext()")
            .substringBefore("override fun")
        assertTrue(next.contains("queueController.next()"))
        val previous = service.substringAfter("override fun seekToPrevious()")
            .substringBefore("override fun")
            .ifEmpty { service.substringAfter("override fun seekToPrevious()") }
        assertTrue(previous.contains("queueController.previous()"))
    }

    @Test
    fun sessionPlayerPlayRoutesTheDeadStatesToTheController() {
        // Round 4: next/previous were routed, Play was not — the
        // raw player's play() is a no-op in STATE_ENDED (item over)
        // and STATE_IDLE (nothing prepared: after a player error,
        // or a resolve that failed before any item landed), so the
        // notification / lock-screen Play button was dead in
        // exactly the states the in-app button re-resolves through
        // togglePlayPause. The session player must route those
        // states to the toggle — guarded by !isPlaying, because
        // play() must never become a pause.
        val service = playbackService()
        val play = service.substringAfter("override fun play()")
            .substringBefore("override fun seekToNext()")
        assertTrue(play.contains("queueController.togglePlayPause()"))
        assertTrue(play.contains("Player.STATE_ENDED"))
        assertTrue(play.contains("Player.STATE_IDLE"))
        assertTrue(play.contains("!isPlaying"))
        assertTrue(play.contains("super.play()"))
    }

    @Test
    fun sessionPlayerAdvertisesTheQueueTransportCommands() {
        val service = playbackService()
        assertTrue(service.contains("Player.COMMAND_SEEK_TO_NEXT"))
        assertTrue(service.contains("Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM"))
        assertTrue(service.contains("Player.COMMAND_SEEK_TO_PREVIOUS"))
        assertTrue(service.contains("Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM"))
    }

    // -- Autoplay continuation (H2): the background chain's other half ---

    @Test
    fun queueAdvancementReachesAutoplayFromTrackEndAndManualNext() {
        // Local re-pin of the 0.6.3 defect ("it plays, then it
        // stops"): both ways a queue can run out must reach the
        // continuation, on the related-tracks machinery.
        val controller = queueController()
        assertTrue(controller.contains("continueWithAutoplay(seed, fromTrackEnd = true)"))
        assertTrue(controller.contains("continueWithAutoplay(seed, fromTrackEnd = false)"))
        assertTrue(controller.contains("client.related(seed.videoId)"))
        assertTrue(controller.contains("autoplayContinuation("))
    }
}
