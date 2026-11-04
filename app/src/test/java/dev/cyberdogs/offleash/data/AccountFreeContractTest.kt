package dev.cyberdogs.offleash.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The account-free contract (0.9.0, owner order 2026-11-04: "For
 * now get rid of the YouTube sign in stuff"). Source-shape pins,
 * in this suite's census style, over the surfaces the order named:
 *
 *  - no sign-in screen, redirect activity, or manifest route
 *    survives anywhere in the presented app;
 *  - no screen offers an account affordance or copy that frames
 *    the app as waiting on one;
 *  - the upgrade cleanup names every sealed file a pre-0.9.0
 *    build could have left behind, so an upgrading device lands
 *    clean instead of half-signed-in.
 */
class AccountFreeContractTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile ?: error("repo root not found")
        }
    }

    private fun mainSource(path: String): String =
        File(repoRoot(), "app/src/main/java/dev/cyberdogs/offleash/$path")
            .readText()

    @Test
    fun signInFilesAndManifestRouteAreGone() {
        val root = repoRoot()
        assertFalse(
            File(root, "app/src/main/java/dev/cyberdogs/offleash/ui/account/SignInScreen.kt").exists(),
        )
        assertFalse(
            File(root, "app/src/main/java/dev/cyberdogs/offleash/ui/account/OAuthRedirectActivity.kt").exists(),
        )
        assertFalse(
            File(root, "app/src/main/java/dev/cyberdogs/offleash/data/GoogleSessionManager.kt").exists(),
        )
        assertFalse(
            File(root, "app/src/main/java/dev/cyberdogs/offleash/data/SessionManager.kt").exists(),
        )
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("oauth2redirect"))
        assertFalse(manifest.contains("OAuthRedirectActivity"))
    }

    @Test
    fun noScreenPresentsAnAccountAffordance() {
        val root = mainSource("ui/OffleashRoot.kt")
        assertFalse(root.contains("\"signin\""))
        assertFalse(root.contains("SignInScreen"))
        val settings = mainSource("ui/settings/SettingsScreen.kt")
        assertFalse(settings.contains("Account"))
        assertFalse(settings.contains("Sign out"))
        val about = mainSource("ui/about/AboutScreen.kt")
        assertFalse(about.contains("Sign in"))
        assertFalse(about.contains("signed-in"))
        val library = mainSource("ui/library/LibraryScreen.kt")
        assertFalse(library.contains("Your YouTube library"))
        assertFalse(library.contains("onSignInClick"))
    }

    @Test
    fun upgradeCleanupNamesEveryLegacySealedFile() {
        val container = mainSource("OffleashApp.kt")
        for (name in listOf(
            "session.bin",
            "google_session.bin",
            "google_pending_grant.bin",
            "google_pending_native_auth.bin",
            "google_signin_seen.txt",
            "own_key.bin",
            "own_key_wizard.bin",
        )) {
            assertTrue("cleanup misses $name", container.contains("\"$name\""))
        }
        assertTrue(container.contains("LEGACY_ACCOUNT_FILES"))
        // The anonymous experience stays wired: feed, resolver and
        // queue are built with no bearer anywhere.
        assertFalse(container.contains("currentBearerToken"))
        assertFalse(container.contains("accountUiState"))
    }
}
