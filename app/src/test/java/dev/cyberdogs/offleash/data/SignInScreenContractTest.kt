package dev.cyberdogs.offleash.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sign-in screen's source-shape contract (0.8.8), following
 * the repo's contract-test style for Compose code the JVM subset
 * cannot compile: the native flow's every phase has a live
 * rendering with a working action (no dead ends), the one
 * primary action is "Continue with Google", the browser launch
 * is a Custom Tab with a plain-view fallback, the unconfigured
 * state is honest and carries NO setup road (the wizard is gone
 * from the product, not just from this screen), the device-era
 * machinery (typed code, clipboard, google.com/device, checkNow)
 * is absent, the last-crash display carried over from the 0.6.x
 * screen is still wired, and CrashReporter is installed before
 * the container is built in the Application.
 */
class SignInScreenContractTest {

    private val screen: String by lazy {
        mainSource("java/dev/cyberdogs/offleash/ui/account/SignInScreen.kt")
    }

    @Test
    fun everyPhaseRendersWithItsAction() {
        assertTrue(screen.contains("GoogleSignInPhase.Idle"))
        assertTrue(screen.contains("GoogleSignInPhase.Issuing"))
        assertTrue(screen.contains("GoogleSignInPhase.AwaitingReturn"))
        assertTrue(screen.contains("GoogleSignInPhase.Failed"))
        // Idle's fresh start, the waiting state's relaunch +
        // cancel, and the failure card's retry all reach the
        // manager.
        assertTrue(screen.contains("manager.startSignIn()"))
        assertTrue(screen.contains("manager.cancelSignIn()"))
        assertTrue(screen.contains("manager.authorizationLaunch"))
    }

    @Test
    fun theOnePrimaryActionIsContinueWithGoogle() {
        assertTrue(
            "the primary button is the normal phone way in",
            screen.contains("\"Continue with Google\""),
        )
        assertTrue(screen.contains("\"Try again\""))
        assertTrue(screen.contains("\"Continue signing in\""))
    }

    @Test
    fun theBrowserLaunchIsACustomTabWithAPlainFallback() {
        assertTrue(screen.contains("CustomTabsIntent"))
        assertTrue(screen.contains("launchUrl(context, uri)"))
        assertTrue(
            "a browser without Custom Tabs still gets the page",
            screen.contains("Intent.ACTION_VIEW"),
        )
    }

    @Test
    fun theDeviceEraMachineryIsGone() {
        assertFalse(screen.contains("checkNow"))
        assertFalse(screen.contains("userCode"))
        assertFalse(screen.contains("google.com/device"))
        assertFalse(screen.contains("Copy code"))
        assertFalse(screen.contains("LocalClipboardManager"))
        assertFalse(screen.contains("OwnKey"))
        assertFalse(screen.contains("ownkey"))
        assertFalse(screen.contains("Set up your own Google key"))
    }

    @Test
    fun theUnconfiguredStateIsHonestAndOffersNoSetupRoad() {
        assertTrue(
            screen.contains("isn't set up in this") &&
                screen.contains("build of OffLeash yet"),
        )
        assertTrue(screen.contains("googleAuth.isConfigured"))
    }

    @Test
    fun lastCrashDisplayIsWiredWithItsDismiss() {
        assertTrue(screen.contains("CrashReporter.read"))
        assertTrue(screen.contains("CrashReporter.clear"))
        assertTrue(screen.contains("CrashReporter.fileIn(context)"))
    }

    @Test
    fun theRedirectActivityHandsTheReturnToTheManager() {
        val activity = mainSource(
            "java/dev/cyberdogs/offleash/ui/account/OAuthRedirectActivity.kt"
        )
        assertTrue(activity.contains("completeSignIn(returned)"))
        assertTrue(activity.contains("MainActivity"))
        val manifest = mainSource("AndroidManifest.xml")
        assertTrue(manifest.contains("OAuthRedirectActivity"))
        assertTrue(manifest.contains("oauth2redirect"))
        assertTrue(manifest.contains("\${applicationId}"))
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
