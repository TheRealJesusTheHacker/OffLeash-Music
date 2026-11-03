package dev.cyberdogs.offleash.data

/**
 * Shared fixtures for the native sign-in tests (0.8.8): the fake
 * Android client ID the flow runs under (an obvious fake, shaped
 * like the real thing — no test may carry a live registration),
 * the redirect the tests return through, and the small helpers
 * that drive an attempt from launch to return.
 */
object NativeTestSupport {

    /** The fake Android registration the native tests sign in under. */
    const val ANDROID_ID: String =
        "123456789012-fakeandroidclientid0000000000ab.apps.googleusercontent.com"

    /** A second fake registration, for client-change gate tests. */
    const val OTHER_ANDROID_ID: String =
        "210987654321-otherfakeandroidclientid000000cd.apps.googleusercontent.com"

    /** The redirect URI the test managers are built with. */
    const val REDIRECT: String = "dev.cyberdogs.offleash.hunt://oauth2redirect"

    /** The state an attempt's launch URL carries. */
    fun stateFrom(launchUrl: String): String =
        launchUrl.substringAfter("state=").substringBefore("&")

    /** The redirect URI Google returns through, carrying [code]. */
    fun codeRedirect(state: String, code: String = "auth-code-1"): String =
        "$REDIRECT?code=$code&state=$state"

    /** The redirect URI Google returns through when the owner declines. */
    fun deniedRedirect(state: String): String =
        "$REDIRECT?error=access_denied&state=$state"

    /** A pending-attempt store over in-memory storage. */
    fun attemptStore(): GooglePendingNativeAuthStore =
        GooglePendingNativeAuthStore(InMemorySecretStorage(), testSecretBox())
}
