package dev.cyberdogs.offleash.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 3 fresh-eyes sweep: golden-path honesty pins. The screens
 * are Compose (CI-compiled only), so — in the house contract style —
 * these assertions pin the exact source shapes the fixes landed:
 *
 * - About described the stored credential as "a session cookie"
 *   (the 0.6.x generation) a full release after the Google sign-in
 *   replaced it with an OAuth token.
 * - Settings presented the radio audio-version toggle as working
 *   ("every control here is functional" + an effect caption) while
 *   the radio builder documents the preference as a no-op today.
 * - The sign-in waiting screen computed its minutes-left copy once
 *   at first composition and never ticked, so the number froze.
 * - QueueController's class doc sat on shouldRetryAfterPlayerError
 *   instead of the class, and still claimed the queue always stops
 *   with no skipping — pre-autoplay, pre-recovery behavior.
 * - SessionManager.signOut documented WebView wiping as "the
 *   caller's job" and no caller did it.
 */
class R3HonestyContractTest {

    @Test
    fun aboutNamesNoCredentialAtAll() {
        // Re-based 0.9.0 (owner order, 2026-11-04): there is no
        // stored credential to name — the sign-in product is
        // removed. The honesty claim this pin now holds is that
        // About describes NO credential and frames the product as
        // it is: YouTube Music is the source; the rest is ours.
        val about = mainSource("java/dev/cyberdogs/offleash/ui/about/AboutScreen.kt")
        assertFalse(
            "About must not describe a stored session cookie",
            about.contains("What the app keeps is a session cookie"),
        )
        assertFalse(
            "About must not describe a stored sign-in token",
            about.contains("token Google hands it"),
        )
        assertTrue(
            "About must frame the product account-free",
            about.contains("Everything else is OffLeash's own"),
        )
    }

    @Test
    fun settingsRadioToggleCaptionDisclosesItIsNotYetInEffect() {
        val settings = mainSource("java/dev/cyberdogs/offleash/ui/settings/SettingsScreen.kt")
        assertFalse(
            "the toggle must not be captioned as if it already changes radio",
            settings.contains("In radio, play the audio version of a song instead of its music video."),
        )
        assertTrue(
            "the caption must say the choice doesn't change radio yet",
            settings.contains("doesn't change radio yet"),
        )
    }

    @Test
    fun signInWaitingScreenMakesNoExpiryPromise() {
        // Re-based 0.9.0 (owner order, 2026-11-04): the waiting
        // screen is deleted with the sign-in product, so the
        // strongest form of this pin holds — there is no screen
        // to make the promise in the first place.
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(
                dir,
                "src/main/java/dev/cyberdogs/offleash/ui/account/SignInScreen.kt",
            )
            candidates += File(
                dir,
                "app/src/main/java/dev/cyberdogs/offleash/ui/account/SignInScreen.kt",
            )
            dir = dir.parentFile
            hops++
        }
        assertTrue(
            "the sign-in screen must not exist in the account-free build",
            candidates.none { it.isFile },
        )
    }

    @Test
    fun queueControllerClassDocSitsOnTheClassAndMatchesCurrentBehavior() {
        val qc = mainSource("java/dev/cyberdogs/offleash/playback/QueueController.kt")
        assertTrue(
            "the class doc must document QueueController, after the pure decisions",
            qc.indexOf("Drives the shared [ExoPlayer]") >
                qc.indexOf("fun shouldRetryAfterPlayerError"),
        )
        assertFalse(
            "the pre-autoplay 'queue stops, no skipping' claim must not survive",
            qc.contains("the queue STOPS"),
        )
    }

    @Test
    fun startupCleanupPerformsTheWebViewWipe() {
        // Re-based 0.9.0 (owner order, 2026-11-04): there is no
        // sign-out path to perform the wipe — the sign-in product
        // is removed. The wipe's duty moved to the one place an
        // upgrading device still needs it: the container's
        // startup cleanup, which clears the legacy WebView cookie
        // jar and web storage beside the sealed-file destruction.
        val app = mainSource("java/dev/cyberdogs/offleash/OffleashApp.kt")
        assertFalse(
            "there must be no sign-out path in the account-free build",
            app.contains("signOutOfAccount"),
        )
        assertTrue(
            "the startup cleanup must clear the WebView cookies",
            app.contains("removeAllCookies"),
        )
        assertTrue(
            "the startup cleanup must clear the WebView storage",
            app.contains("deleteAllData"),
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
