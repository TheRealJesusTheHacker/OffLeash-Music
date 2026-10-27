package dev.cyberdogs.offleash.playback

/**
 * The cold-launch splash gate (2026-10-27, owner's spec).
 *
 * The splash is a cold-launch-only moment: it shows for
 * [SPLASH_DURATION_MS] (any tap skips it), and it NEVER comes back
 * inside a process that has already shown it — and never at all
 * once the app has played something, including in the background:
 * returning to a session that has played (or is playing) goes
 * straight into the app. The facts live here, in process-lifetime
 * state, because composition state dies with the activity while
 * these facts must not.
 *
 * [decideSplash] is the pure decision, pinned by unit tests; the
 * object holds the live flags.
 */
object SplashGate {

    /** The spec'd splash duration: 3.8 seconds, then into the app. */
    const val SPLASH_DURATION_MS: Long = 3_800L

    @Volatile
    var shownThisProcess: Boolean = false
        private set

    @Volatile
    var playbackHasHappened: Boolean = false
        private set

    fun markShown() {
        shownThisProcess = true
    }

    /** Called by the queue controller the first time a track is READY to play. */
    fun markPlaybackHappened() {
        playbackHasHappened = true
    }

    /** Live decision for this composition: see [decideSplash]. */
    fun shouldShow(playerHasMedia: Boolean): Boolean = decideSplash(
        shownThisProcess = shownThisProcess,
        playbackHasHappened = playbackHasHappened,
        playerHasMedia = playerHasMedia,
    )
}

/**
 * Pure splash decision: show only on a true cold start — not shown
 * yet in this process, nothing has played yet in this process, and
 * the player holds no media (the still-playing case after an
 * activity relaunch while the background service kept the session).
 */
fun decideSplash(
    shownThisProcess: Boolean,
    playbackHasHappened: Boolean,
    playerHasMedia: Boolean,
): Boolean = !shownThisProcess && !playbackHasHappened && !playerHasMedia
