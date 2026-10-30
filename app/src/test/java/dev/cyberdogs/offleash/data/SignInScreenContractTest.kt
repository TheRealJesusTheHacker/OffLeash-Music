package dev.cyberdogs.offleash.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sign-in screen's source-shape contract (0.7.2 sweep),
 * following the repo's contract-test style for Compose code the
 * JVM subset cannot compile: every manager phase has a live
 * rendering with a working action (no dead ends), the last-crash
 * display carried over from the 0.6.x screen is still wired, the
 * approval code's remaining life is computed from the grant (not
 * a hardcoded half hour), and CrashReporter is installed before
 * the container is built in the Application.
 */
class SignInScreenContractTest {

    private val screen: String by lazy {
        mainSource("java/dev/cyberdogs/offleash/ui/account/SignInScreen.kt")
    }

    @Test
    fun everyPhaseRendersWithItsAction() {
        assertTrue(screen.contains("GoogleSignInPhase.Idle"))
        assertTrue(screen.contains("GoogleSignInPhase.AwaitingApproval"))
        assertTrue(screen.contains("GoogleSignInPhase.Failed"))
        // Idle's fresh start, the waiting state's two live actions,
        // and the failure card's retry all reach the manager.
        assertTrue(screen.contains("manager.startSignIn()"))
        assertTrue(screen.contains("manager.checkNow()"))
        assertTrue(screen.contains("manager.cancelSignIn()"))
    }

    @Test
    fun approvalCodeCanBeCopiedToTheClipboard() {
        // Round 13: the code was display-only (a plain Compose
        // Text is not selectable, and no clipboard path existed
        // anywhere in the app), so the expected same-phone path
        // forced the owner to memorise the code or app-switch to
        // re-read it. The waiting state must offer a copy action
        // that puts the grant's exact code on the clipboard.
        assertTrue(
            "the waiting state must offer a visible Copy code action",
            screen.contains("\"Copy code\""),
        )
        assertTrue(
            "the copy action must write the shown code itself — " +
                "copying anything else (or a hardcoded string) would " +
                "paste a code Google never issued",
            screen.contains(
                "setText(AnnotatedString(current.userCode))",
            ),
        )
        // Round 14 strengthening: the round-13 pin above was
        // deletion-sensitive for the button and the copied value,
        // but HOLLOW for the tap feedback — deleting the feedback
        // text (or the state flip that shows it) left both
        // assertions above untouched and the pin green, while the
        // owner would tap and see no confirmation the copy
        // happened. The feedback is part of the fix's claim, so
        // it is pinned too: the copied-state label and the flip
        // that selects it. (Each literal occurs exactly once in
        // the screen, inside the button, so removing the button
        // entirely still fails every assertion here.)
        assertTrue(
            "a tap must flip the copy state that drives the feedback",
            screen.contains("codeCopied = true"),
        )
        assertTrue(
            "the button must show the copied feedback once tapped",
            screen.contains(
                "\"Copied — paste it on Google's page\"",
            ),
        )
        assertTrue(
            "the feedback label must be conditional on the copy " +
                "state, not a static second label",
            screen.contains("if (codeCopied)"),
        )
    }

    @Test
    fun lastCrashDisplayIsWiredWithItsDismiss() {
        assertTrue(screen.contains("CrashReporter.read"))
        assertTrue(screen.contains("CrashReporter.clear"))
        assertTrue(screen.contains("CrashReporter.fileIn(context)"))
    }

    @Test
    fun approvalCodeLifeIsComputedFromTheGrant() {
        assertTrue(
            "the waiting copy must derive its minutes from the grant's expiry",
            screen.contains("current.expiresAtEpochMs"),
        )
        assertFalse(
            "a resumed code must not be sold as a fresh 30 minutes",
            screen.contains("expires in about 30 minutes"),
        )
    }

    @Test
    fun crashReporterInstallsBeforeTheContainerIsBuilt() {
        val app = mainSource("java/dev/cyberdogs/offleash/OffleashApp.kt")
        val installAt = app.indexOf("CrashReporter.install(this)")
        val containerAt = app.indexOf("container = AppContainer(this)")
        assertTrue("CrashReporter must be installed in onCreate", installAt >= 0)
        assertTrue("the container must be built in onCreate", containerAt >= 0)
        assertTrue(
            "the crash recorder goes in FIRST, before anything can fail",
            installAt < containerAt,
        )
    }

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
