package dev.cyberdogs.offleash.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import dev.cyberdogs.offleash.core.auth.AuthSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the app currently knows about the stored session's health. */
enum class SessionHealth { UNKNOWN, HEALTHY, NEEDS_SIGN_IN }

/** Everything the account UI needs, and nothing it doesn't. */
data class SessionUiState(
    val signedIn: Boolean,
    val identityName: String? = null,
    val identityEmail: String? = null,
    val avatarUrl: String? = null,
    val health: SessionHealth = SessionHealth.UNKNOWN,
    val lastEndReason: String? = null,
)

/**
 * The legacy (0.6.x, WebView/cookie) session's remains — since
 * 0.8.3, ONLY its remains.
 *
 * The road this class used to own is REMOVED, on the owner's
 * verdict of 2026-10-30: sign-in is the Google device-code flow
 * ([GoogleSessionManager]) and nothing else. The WebView sign-in
 * screen was disconnected from navigation in 0.7.0, but the
 * machinery behind it stayed in the tree "for one release as a
 * safety net" — completion from a captured or pasted cookie
 * string, the ~30-second identity-confirmation window against
 * YouTube's account-switcher endpoint, adoption into the sealed
 * store, and periodic re-validation of the stored session. That
 * machinery is what bounced the owner's real, visibly-signed-in
 * brand session in October 2026 ("YouTube didn't confirm a
 * signed-in account for that session"), and a stale copy of the
 * old screen is what he was routed back to on 0.8.2 test night.
 * The safety net's release has long passed; 0.8.3 deletes it:
 *
 *  - No sign-in can be completed from cookies: there is no
 *    completion entry point left on this class at all.
 *  - No stored legacy session is ever presented: construction
 *    DISCARDS one left by an older build (wiped from the sealed
 *    store, named in the sign-in diagnostics — presence only,
 *    never values) instead of loading it, so an upgraded phone
 *    can never resurrect a WebView-era session as "signed in".
 *  - Nothing validates, refreshes, or rotates a legacy session:
 *    the keep-alive worker is deleted and the transport's cookie
 *    rotation callback is a documented no-op.
 *
 * What remains is the slot the rest of the app still reads
 * through: [currentAuthSession] (always null now — the resolver,
 * browse, and library roads take the Google session or the
 * anonymous road), the generation counter the download worker's
 * consistency pair reads, the presented [uiState] (always signed
 * out), and [signOut] — the legacy leg of the container's total
 * sign-out, which still wipes the sealed store so "signed out"
 * leaves nothing of either generation behind.
 */
class SessionManager(
    private val store: SessionStore,
    private val signInDiagnostics: SignInDiagnostics = SignInDiagnostics(),
) {

    private val mutex = Mutex()

    /**
     * The session generation (bug sweep round 8's discipline,
     * kept for the readers that pair it with a session read —
     * see [currentSessionWithGeneration]). With the legacy road
     * removed the only turnover left is [signOut]: no session can
     * be adopted or ended underneath a reader any more.
     */
    @Volatile
    private var sessionGeneration: Long = 0L

    /** The current session generation — see [sessionGeneration]. */
    fun sessionGeneration(): Long = sessionGeneration

    /**
     * The current session snapshot AND its generation, read as one
     * consistent pair (bug sweep round 9). The snapshot is always
     * null since 0.8.3 (see the class doc), so the pair is
     * trivially consistent; the shape stays for the callers built
     * on it (the auto-download worker's capture/re-check).
     */
    fun currentSessionWithGeneration(): Pair<AuthSession?, Long> =
        null to sessionGeneration

    /** The legacy session, for the API layer's fallback reads. Always null since 0.8.3. */
    fun currentAuthSession(): AuthSession? = null

    private val _uiState = MutableStateFlow(SessionUiState(signedIn = false))
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    init {
        // A stored legacy session is DISCARDED, never loaded
        // (0.8.3 removal — see the class doc). The load itself is
        // total by the store's contract; the catch is the belt to
        // its braces, for Errors at the very first read.
        val loaded = try {
            store.load()
        } catch (t: Throwable) {
            signInDiagnostics.log(
                "store",
                "the saved WebView-era session couldn't be read " +
                    "(${t.javaClass.simpleName}) — starting signed out",
            )
            null
        }
        if (loaded != null) {
            try {
                store.wipe()
            } catch (t: Throwable) {
                signInDiagnostics.log(
                    "store",
                    "the WebView-era session couldn't be discarded " +
                        "(${t.javaClass.simpleName}) — it is not in use " +
                        "and sign-out will wipe it",
                )
            }
            signInDiagnostics.log(
                "legacy-session",
                "a WebView-era session left by an older build was " +
                    "found on this device and discarded — that " +
                    "sign-in road was removed in 0.8.3; the Google " +
                    "sign-in is the only road now",
            )
        }
    }

    /**
     * Cookie rotation from the transport — a deliberate no-op
     * since 0.8.3. Responses on YouTube hosts may still carry
     * fresh values for the old session cookies, but there is no
     * legacy session to rotate them into: the wiring stays so the
     * transport's contract is unchanged, and the pairs are dropped
     * here, at the one door they ever entered through.
     */
    fun onResponseCookies(rotated: Map<String, String>) {
        // No-op by design (see above). The parameter is the
        // transport callback's shape, kept intentionally.
    }

    /**
     * The legacy leg of the container's total sign-out: the sealed
     * store is wiped so nothing of the WebView generation survives
     * a sign-out, and the generation turns over so any reader
     * holding a pre-sign-out pair sees the change. WebView data is
     * the caller's job — see [wipeWebViewData]; the container's
     * signOutOfAccount performs it right after this wipe.
     */
    suspend fun signOut() {
        mutex.withLock {
            store.wipe()
            sessionGeneration++
            _uiState.value = SessionUiState(
                signedIn = false,
                health = SessionHealth.UNKNOWN,
                lastEndReason = null,
            )
        }
    }
}

/**
 * Clears everything the sign-in WebView may have left behind: its
 * cookie jar and its web storage. Safe to call from the main thread;
 * both calls are fire-and-forget system services.
 */
fun wipeWebViewData(context: Context) {
    try {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
    } catch (e: Exception) {
        // Best effort — the encrypted store is the credential of
        // record, and sign-out has already wiped it.
    }
    try {
        WebStorage.getInstance().deleteAllData()
    } catch (e: Exception) {
        // Same best-effort reasoning as above.
    }
}
