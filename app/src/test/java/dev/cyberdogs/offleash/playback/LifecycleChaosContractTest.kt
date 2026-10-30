package dev.cyberdogs.offleash.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chaos sweep round 2 contracts: permissions, interruptions,
 * process death at unexamined moments, accessibility labels, and
 * the release-candidate copy pass. Compose/Android behavior cannot
 * run on this JVM suite, so — in the repo's established style — the
 * pure decisions are unit-tested and the wiring is pinned by
 * source shape.
 */
class LifecycleChaosContractTest {

    private fun locate(relativeFromAppModule: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, "app/src/main/java/$relativeFromAppModule")
            candidates += File(dir, "src/app/src/main/java/$relativeFromAppModule")
            candidates += File(dir, "src/main/java/$relativeFromAppModule")
            dir = dir.parentFile
            hops++
        }
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "not found from " + File(".").absolutePath + ": " + relativeFromAppModule,
            )
    }

    private fun source(relativeFromAppModule: String): String =
        locate(relativeFromAppModule).readText()

    private fun mainActivity(): String =
        source("dev/cyberdogs/offleash/MainActivity.kt")

    private fun queueController(): String =
        source("dev/cyberdogs/offleash/playback/QueueController.kt")

    // -- 1. Permission denials --------------------------------------

    @Test
    fun notificationPermissionDecisionMatrix() {
        // Pre-33: no runtime permission exists to ask for.
        assertFalse(shouldRequestNotificationPermission(32, granted = false, askedBefore = false))
        // Already granted: never ask.
        assertFalse(shouldRequestNotificationPermission(33, granted = true, askedBefore = false))
        assertFalse(shouldRequestNotificationPermission(35, granted = true, askedBefore = true))
        // Asked once and denied: never ask again (the round-2 fix).
        assertFalse(shouldRequestNotificationPermission(33, granted = false, askedBefore = true))
        assertFalse(shouldRequestNotificationPermission(35, granted = false, askedBefore = true))
        // The one legitimate ask: modern API, not granted, never asked.
        assertTrue(shouldRequestNotificationPermission(33, granted = false, askedBefore = false))
        assertTrue(shouldRequestNotificationPermission(35, granted = false, askedBefore = false))
    }

    @Test
    fun mainActivityAsksOnceAndPersistsTheFlagBeforeRequesting() {
        val activity = mainActivity()
        assertTrue(
            "MainActivity must route the ask through the pure decision",
            activity.contains("shouldRequestNotificationPermission("),
        )
        assertTrue(
            "the asked-once flag must be read from the persisted prefs",
            activity.contains("getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)"),
        )
        val persistAt = activity.indexOf("putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true)")
        val requestAt = activity.indexOf("requestPermissions(")
        assertTrue("the flag must be written", persistAt >= 0)
        assertTrue("the system request must still fire when the decision says so", requestAt >= 0)
        assertTrue(
            "the flag must be persisted BEFORE the request fires — a process death " +
                "inside the dialog must not cause a second ask on relaunch",
            persistAt < requestAt,
        )
        assertTrue(
            "the flag must be persisted SYNCHRONOUSLY (bug sweep round 3): " +
                "apply() only queues the disk write, so a death in the window " +
                "before it lands loses the flag and asks a second time",
            activity.contains("putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true).commit()"),
        )
    }

    @Test
    fun batteryCardDismissalPersistsAndRestrictionIsReReadOnResume() {
        val card = source("dev/cyberdogs/offleash/ui/components/BatteryRestrictionCard.kt")
        assertTrue(
            "dismissal must be persisted, not just remembered",
            card.contains("putBoolean(KEY_BATTERY_CARD_DISMISSED, true)"),
        )
        assertTrue(
            "restriction state must be re-evaluated on resume so granting the " +
                "exemption removes the card without a restart",
            card.contains("Lifecycle.Event.ON_RESUME") &&
                card.contains("isRestricted = readIsRestricted(context)"),
        )
        assertTrue(
            "the action must target the per-app exemption dialog first",
            card.contains("Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS") &&
                card.contains("\"package:\" + context.packageName"),
        )
        assertTrue(
            "with a fallback to the system exemption list",
            card.contains("Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS"),
        )
        assertTrue(
            "an OEM throwing something other than ActivityNotFoundException for " +
                "the dialog intent must reach the fallback, not crash the screen",
            card.contains("catch (e: Exception)"),
        )
        assertTrue(
            "the FALLBACK intent must be just as crash-proof (bug sweep round 3): " +
                "round 2 widened only the outer catch, so an OEM answering the " +
                "fallback list intent with a SecurityException still crashed " +
                "the screen — the fallback's catch must not name the narrow type",
            card.contains("catch (ignored: Exception)") &&
                !card.contains("catch (ignored: ActivityNotFoundException)"),
        )
        assertTrue(
            "dismissal must persist synchronously (bug sweep round 3): apply()'s " +
                "queued disk write can be lost to a process death right after " +
                "the tap, resurrecting a card the owner already dismissed",
            card.contains("putBoolean(KEY_BATTERY_CARD_DISMISSED, true)") &&
                card.contains(".commit()"),
        )
        assertTrue(
            "the pure gate stays: restricted AND not dismissed",
            card.contains("shouldShowBatteryCard(isRestricted, dismissed)"),
        )
    }

    // -- 2. Interruptions -------------------------------------------

    @Test
    fun playbackStateIsPlayingAlwaysComesFromThePlayer() {
        // A transient audio-focus loss pauses the PLAYER (ExoPlayer's
        // focus handling); the UI can only stay honest about it if
        // every snapshot rebuilds isPlaying from player.isPlaying and
        // the is-playing listener resyncs — never from playWhenReady
        // or a remembered flag.
        val controller = queueController()
        val sync = controller.substringAfter("private fun syncPlaybackState()")
            .substringBefore("private companion object")
        assertTrue(sync.contains("isPlaying = player.isPlaying"))
        assertTrue(
            "onIsPlayingChanged must resync the UI state",
            controller.substringAfter("override fun onIsPlayingChanged")
                .substringBefore("override fun")
                .contains("syncPlaybackState()"),
        )
        val container = source("dev/cyberdogs/offleash/OffleashApp.kt")
        val buildPlayer = container.substringAfter("private fun buildPlayer")
        assertTrue(
            "audio focus stays ExoPlayer-handled",
            buildPlayer.contains("handleAudioFocus = */ true"),
        )
        assertTrue(
            "becoming-noisy (wired unplug AND Bluetooth disconnect both " +
                "broadcast it) stays handled by the player",
            buildPlayer.contains(".setHandleAudioBecomingNoisy(true)"),
        )
    }

    @Test
    fun ioRecoveryReResolvesFreshInsteadOfReplayingTheDeadUrl() {
        // Network switch mid-stream (Wi-Fi to cellular): the stream
        // URL is bound to the resolving network identity, so recovery
        // must run a NEW resolve — the only setMediaItem site builds
        // its item from the fresh hand-off, never from stored state.
        val controller = queueController()
        val ioBranch = controller
            .substringAfter("PlayerErrorRecoveryAction.RE_RESOLVE_IO ->")
            .substringBefore("PlayerErrorRecoveryAction.FALLBACK_TO_STREAM ->")
        assertTrue(
            "the IO recovery branch must re-enter the resolve path",
            ioBranch.contains("resolveAndPlay("),
        )
        val resolve = controller.substringAfter("private fun resolveAndPlay")
            .substringBefore("private fun handToPlayer")
        assertTrue(
            "resolveAndPlay must resolve through the roads (a fresh signed URL)",
            resolve.contains("resolveWithRoads(track)"),
        )
        assertEquals(
            "exactly one setMediaItem site: the fresh hand-off",
            1,
            Regex("player\\.setMediaItem\\(").findAll(controller).count(),
        )
        assertTrue(
            "the hand-off hands the player the freshly resolved URI",
            controller.substringAfter("private fun handToPlayer")
                .contains("player.setMediaItem(mediaItem)"),
        )
    }

    @Test
    fun signInWaitingStateIsManagerHeldNotScreenHeld() {
        // Rotation (or process death) during the wait must re-render
        // the SAME grant: the phase lives in the manager, and the
        // screen holds no copy of the code in composition state.
        val screen = source("dev/cyberdogs/offleash/ui/account/SignInScreen.kt")
        assertTrue(screen.contains("val manager = container.googleSessionManager"))
        assertTrue(screen.contains("manager.phase.collectAsState()"))
        assertTrue(
            "the rendered code must come from the manager's phase",
            screen.contains("current.userCode"),
        )
        assertFalse(
            "the screen must not remember its own copy of the grant",
            screen.contains("userCode by remember") || screen.contains("remember { mutableStateOf(current"),
        )
        assertTrue(
            "a transport-failed poll must land on the Failed phase (the spinner " +
                "unmounts) rather than leaving a dead 'still checking' wait",
            source("dev/cyberdogs/offleash/data/GoogleSessionManager.kt")
                .contains("GoogleSignInPhase.Failed("),
        )
    }

    // -- 3. Process death at unexamined moments ----------------------

    @Test
    fun downloadManagerReconcilesDeadDownloadingRecordsBeforeAnyPaint() {
        // The manager is built in Application.onCreate, before any
        // screen exists: a record a killed process left DOWNLOADING
        // must already read QUEUED at the UI's first paint, and the
        // re-queued records must get fresh jobs in the same pass.
        val manager = source("dev/cyberdogs/offleash/data/DownloadManager.kt")
        val init = manager.substringAfter("init {")
            .substringBefore("private fun sweepOrphanFiles")
        assertTrue(init.contains("record.state == DownloadState.DOWNLOADING"))
        assertTrue(init.contains("toState = DownloadState.QUEUED"))
        assertTrue(
            "queued records left by the dead process must be relaunched",
            init.contains(".forEach { launchJob(it.track) }"),
        )
    }

    @Test
    fun sleepTimerStateDefaultsToUnarmedInTheController() {
        // The timer is in-memory by design: after a process death it
        // is simply gone, and the player UI — which renders only the
        // controller's flow — must have nothing to claim otherwise.
        val controller = queueController()
        assertTrue(
            controller.contains(
                "_sleepTimerState = MutableStateFlow(SleepTimerState())",
            ),
        )
        assertFalse(SleepTimerState().isArmed)
        val player = source("dev/cyberdogs/offleash/ui/player/PlayerScreen.kt")
        assertTrue(
            "the player's sleep rows must render the controller's flow, " +
                "not any screen-local or persisted timer state",
            player.contains("queueController.sleepTimerState.collectAsState()"),
        )
    }

    @Test
    fun splashIsDismissedByTimerAndTapAndNeverBlocksSignIn() {
        val root = source("dev/cyberdogs/offleash/ui/OffleashRoot.kt")
        assertTrue(
            "the root must run the gate's timer dismissal",
            root.contains("delay(SplashGate.SPLASH_DURATION_MS)") &&
                root.contains("splashDismissed = true"),
        )
        assertTrue(
            "the splash must render as an overlay, so the app (and its " +
                "sign-in) exists underneath it from the first frame",
            root.contains("AnimatedVisibility(") && root.contains("SplashScreen("),
        )
        val splash = source("dev/cyberdogs/offleash/ui/SplashScreen.kt")
        assertTrue(
            "tap-to-skip must be labelled for TalkBack",
            splash.contains("onClickLabel = \"Skip the splash\""),
        )
    }

    // -- 4/6. Accessibility + config change --------------------------

    @Test
    fun noLaunchedEffectInTheRootStartsPlayback() {
        // Rotation recreates the activity and re-runs every effect:
        // an effect that (re)started playback would re-resolve the
        // current track on every turn of the phone. Playback starts
        // only from taps and the controller's own advancement.
        val root = source("dev/cyberdogs/offleash/ui/OffleashRoot.kt")
        val banned = listOf("playTracks(", "resolveAndPlay(", ".play(", "startRadio(")
        var index = root.indexOf("LaunchedEffect(")
        assertTrue("the root is expected to have effects to check", index >= 0)
        while (index >= 0) {
            val next = root.indexOf("LaunchedEffect(", index + 1)
            val end = if (next < 0) minOf(index + 700, root.length) else next
            val segment = root.substring(index, end)
            for (token in banned) {
                assertFalse(
                    "a LaunchedEffect in OffleashRoot must not start playback (found $token)",
                    segment.contains(token),
                )
            }
            index = next
        }
    }

    @Test
    fun miniPlayerExpandCarriesAnActionLabel() {
        val root = source("dev/cyberdogs/offleash/ui/OffleashRoot.kt")
        assertTrue(
            root.contains("onClickLabel = \"Open the full player\""),
        )
    }

    @Test
    fun signOutDialogTextScrollsSoItsButtonsSurviveLargeFonts() {
        val about = source("dev/cyberdogs/offleash/ui/about/AboutScreen.kt")
        val dialog = about.substringAfter("Text(\"Sign out?\")")
        assertTrue(
            "the sign-out dialog's text must be height-bounded and scrollable — " +
                "at 2.0x font its buttons were pushed past the dialog's edge",
            dialog.contains("heightIn(max = 320.dp)") && dialog.contains("verticalScroll"),
        )
    }

    // -- 5. Copy & embarrassment pass --------------------------------

    @Test
    fun aboutReadsTheRealVersionAndNeverClaimsAnActiveCheckThatIsNotRunning() {
        val about = source("dev/cyberdogs/offleash/ui/about/AboutScreen.kt")
        assertTrue(
            "the version must come from the installed package, not a literal",
            about.contains("getPackageInfo(context.packageName, 0).versionName") &&
                about.contains("\"Version \$versionName\""),
        )
        assertFalse(
            "a hardcoded version string would lie at the next bump",
            Regex("Version 0\\.").containsMatchIn(about),
        )
        assertFalse(
            "UNKNOWN health is mostly 'the last check failed to reach Google' — " +
                "About must not call it 'checking…'",
            about.contains("Session: checking"),
        )
        val diagnostics = source(
            "dev/cyberdogs/offleash/ui/about/PlaybackDiagnosticsScreen.kt",
        )
        assertFalse(
            "the diagnostics account line must not claim an active check either",
            diagnostics.contains("\"Checking…\""),
        )
        assertFalse(
            "the diagnostics intro must not promise plain language its own " +
                "technical log lines do not keep",
            diagnostics.contains("in plain language"),
        )
    }
}
