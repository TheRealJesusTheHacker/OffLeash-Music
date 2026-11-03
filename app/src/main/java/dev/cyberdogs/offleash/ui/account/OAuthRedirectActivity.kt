package dev.cyberdogs.offleash.ui.account

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.cyberdogs.offleash.MainActivity
import dev.cyberdogs.offleash.OffleashApp

/**
 * The OAuth return door (0.8.8): Google redirects the browser to
 * `<package>://oauth2redirect` after the owner approves (or
 * declines) sign-in, and this activity is what that address
 * opens. It renders nothing — it hands the return URI to the
 * session manager, which binds it to the standing attempt
 * (destination + state checked in core before any exchange),
 * then brings the main activity forward and finishes.
 *
 * A return that arrives with no data, or when the app process
 * had to be rebuilt to receive it, is still handed over exactly
 * the same way: the manager decides what it means (a mistargeted
 * or stateless return is refused there, with diagnostics), and
 * the owner lands in the app either way.
 */
class OAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val returned = intent?.data?.toString()
        if (returned != null) {
            (application as? OffleashApp)?.container
                ?.googleSessionManager
                ?.completeSignIn(returned)
        }
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        finish()
    }
}
