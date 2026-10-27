package dev.cyberdogs.offleash.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import dev.cyberdogs.offleash.core.auth.IdentityLookup
import dev.cyberdogs.offleash.core.auth.SessionValidation
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.auth.SESSION_COOKIE_NAMES
import dev.cyberdogs.offleash.core.auth.SessionCookies
import dev.cyberdogs.offleash.core.model.AccountIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

/** The outcome of one sign-in attempt, in plain language. */
sealed interface SignInAttempt {
    data class Success(val identityName: String) : SignInAttempt
    data class Failed(val reason: String) : SignInAttempt
}

/**
 * Owns the signed-in session: the encrypted [SessionStore] on disk,
 * an in-memory [AuthSession] snapshot for the API layer, and the
 * [uiState] the account screens render.
 *
 * Rollback is by construction: a new sign-in is validated against
 * YouTube BEFORE anything is persisted, so a failed attempt leaves
 * the previously stored session byte-for-byte untouched.
 *
 * A session YouTube says is dead is destroyed, not kept — a stored
 * credential that no longer works is only a liability. A session
 * that merely couldn't be reached (no network, server trouble) is
 * kept: network trouble is not a dead session.
 */
class SessionManager(
    private val store: SessionStore,
    private val client: YoutubeiClient,
    private val scope: CoroutineScope,
    private val signInDiagnostics: SignInDiagnostics = SignInDiagnostics(),
    /**
     * The settling window for sign-in confirmation: delays BETWEEN
     * confirmation attempts (the attempt count is delays + 1).
     * Completing Google's login must converge to signed-in (owner
     * directive, 2026-10-24): a fresh session can take seconds to
     * finish landing on YouTube's side — cookies still being minted,
     * the session not yet recognized by the identity endpoint — and
     * a one-shot check bounces real logins. Confirmation therefore
     * re-reads the cookie source and re-asks YouTube across this
     * window before it is allowed to fail. Injectable so tests run
     * the window instantly.
     */
    private val confirmationDelaysMs: List<Long> = DEFAULT_CONFIRMATION_DELAYS_MS,
) {

    private val mutex = Mutex()

    /** The last stored DTO, kept so updates can preserve its fields. */
    @Volatile
    private var stored: StoredSession? = null

    /** In-memory auth snapshot; the credential itself never leaves here + the encrypted store. */
    @Volatile
    private var snapshot: AuthSession? = null

    private val _uiState = MutableStateFlow(SessionUiState(signedIn = false))
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    init {
        val loaded = store.load()
        stored = loaded
        snapshot = loaded?.toAuthSession()
        if (loaded != null) {
            _uiState.value = SessionUiState(
                signedIn = true,
                identityName = loaded.identityName,
                identityEmail = loaded.identityEmail,
                avatarUrl = loaded.avatarUrl,
                health = SessionHealth.UNKNOWN,
            )
        }
    }

    fun currentAuthSession(): AuthSession? = snapshot

    /**
     * Completes a sign-in from a raw cookie string (manual paste —
     * the string is all there is, so the provider is constant; the
     * confirmation window still applies to the identity stage,
     * because YouTube-side settling is not about our capture).
     *
     * Nothing is stored until YouTube itself confirms which account
     * the session belongs to.
     */
    suspend fun completeSignIn(rawCookieString: String): SignInAttempt =
        completeSignIn(cookieProvider = { rawCookieString })

    /**
     * Completes a sign-in, re-reading the session cookies from
     * [cookieProvider] on every confirmation attempt (the WebView
     * path passes a provider that re-reads the view's cookie jar, so
     * cookies Google mints in the seconds after landing are picked
     * up instead of bouncing the login on a half-landed capture).
     *
     * The window: attempt, then re-read + re-ask after each delay in
     * [confirmationDelaysMs]. The first confirmed identity wins and
     * is adopted (rollback intact — nothing is persisted before
     * confirmation, so an exhausted window leaves any previously
     * stored session byte-for-byte untouched). One stage is exempt
     * from patience: if the captured cookies are implausible AND the
     * re-read string is byte-identical to the previous attempt's,
     * waiting cannot change them, so the attempt fails at the subset
     * stage immediately rather than spinning the window out.
     *
     * Every stage of every attempt is recorded in [signInDiagnostics]
     * (cookie names and outcome classes only — never values), so a
     * failure that survives the window still names its stage.
     */
    suspend fun completeSignIn(cookieProvider: suspend () -> String): SignInAttempt = mutex.withLock {
        signInDiagnostics.startAttempt()
        val maxAttempts = confirmationDelaysMs.size + 1
        var lastFailure: SignInAttempt.Failed = SignInAttempt.Failed(NOT_CONFIRMED_REASON)
        var previousRaw: String? = null
        for (attempt in 1..maxAttempts) {
            if (attempt > 1) {
                delay(confirmationDelaysMs[attempt - 2])
                signInDiagnostics.log(
                    "retry",
                    "attempt $attempt of $maxAttempts — re-reading the session and asking YouTube again",
                )
            }
            val raw = try {
                cookieProvider()
            } catch (e: Exception) {
                ""
            }
            val cookies = SessionCookies.extractSubset(raw)
            signInDiagnostics.recordCookieStage(cookies)
            if (!SessionCookies.isPlausibleSession(cookies)) {
                lastFailure = SignInAttempt.Failed(SUBSET_REASON)
                if (raw == previousRaw) {
                    signInDiagnostics.log(
                        "cookies",
                        "capture unchanged between attempts — the window cannot change these cookies; stopping at the subset stage",
                    )
                    break
                }
                previousRaw = raw
                continue
            }
            previousRaw = raw
            // authUser starts at 0 for validation only; the identity
            // answer below supplies the real index (assuming 0 for a
            // brand channel on a later account answers as signed out).
            val tentative = AuthSession(
                cookies = cookies,
                authUser = 0,
                pageId = null,
                visitorData = null,
            )
            // The confirmation corridor is TOTAL (2026-10-27 crash
            // hunt): the client contract says the lookup never
            // throws, and adoption below is wrapped the same way —
            // but this flow runs inside the sign-in screen's launch,
            // which has no exception handler, so anything that still
            // escapes from here kills the process. That is exactly
            // what 0.6.3 did on the owner's phone: the first real
            // identity answer reached adoption, the on-device seal
            // threw, and the app vanished ~1s after Google's prompt.
            // An unexpected throw is not a settling problem, so it
            // fails the attempt at once, naming its stage — it does
            // not spin the patience window.
            val lookup = try {
                withContext(Dispatchers.IO) { client.accountIdentities(tentative) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                signInDiagnostics.log(
                    "identity",
                    "attempt $attempt: the identity check failed unexpectedly " +
                        "(${t.javaClass.simpleName})",
                )
                return@withLock SignInAttempt.Failed(UNEXPECTED_REASON)
            }
            when (lookup) {
                is IdentityLookup.Found -> {
                    val identities = lookup.identities
                    val shape = lookup.shapeSummary?.let { " — shape: $it" } ?: ""
                    if (identities.isEmpty()) {
                        signInDiagnostics.log(
                            "identity",
                            "attempt $attempt: YouTube answered but listed no account — " +
                                "the session is not recognized yet$shape",
                        )
                        lastFailure = SignInAttempt.Failed(NOT_CONFIRMED_REASON)
                    } else {
                        signInDiagnostics.log(
                            "identity",
                            "attempt $attempt: YouTube confirmed ${identities.size} account(s)$shape",
                        )
                        val selected = identities.firstOrNull { it.isSelected == true }
                            ?: identities.first()
                        return@withLock try {
                            adoptIdentifiedSession(cookies, selected)
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            signInDiagnostics.log(
                                "sign-in",
                                "adopting the confirmed session failed unexpectedly " +
                                    "(${t.javaClass.simpleName}) — nothing was stored",
                            )
                            SignInAttempt.Failed(UNEXPECTED_REASON)
                        }
                    }
                }
                is IdentityLookup.Rejected -> {
                    signInDiagnostics.log(
                        "identity",
                        "attempt $attempt: the session was refused (HTTP ${lookup.httpStatus})",
                    )
                    lastFailure = SignInAttempt.Failed(NOT_CONFIRMED_REASON)
                }
                is IdentityLookup.Failed -> {
                    signInDiagnostics.log(
                        "identity",
                        "attempt $attempt: couldn't reach YouTube (${lookup.detail ?: "no detail"})",
                    )
                    lastFailure = SignInAttempt.Failed(REACH_REASON)
                }
            }
        }
        signInDiagnostics.log(
            "sign-in",
            "confirmation window exhausted after $maxAttempts attempt(s) — nothing was stored",
        )
        lastFailure
    }

    /** Persists a session whose identity YouTube has confirmed. Caller holds [mutex]. */
    private suspend fun adoptIdentifiedSession(
        cookies: Map<String, String>,
        selected: AccountIdentity,
    ): SignInAttempt {
        val visitorData = try {
            withContext(Dispatchers.IO) { client.fetchVisitorData() }
        } catch (e: Exception) {
            null
        }
        val now = System.currentTimeMillis()
        val toStore = StoredSession(
            cookies = cookies,
            authUser = selected.authUser,
            pageId = selected.pageId,
            visitorData = visitorData,
            identityName = selected.name,
            identityEmail = selected.email,
            avatarUrl = selected.avatarUrl,
            capturedAtEpochMs = now,
            lastValidatedEpochMs = now,
        )
        // The first save of the app's life happens HERE, on the
        // first confirmed sign-in — which makes the Keystore seal
        // and the file write first-time operations at exactly this
        // moment (before any session exists, startup's load never
        // reaches the seal at all). Their failures are device
        // failures, not sign-in failures: name the store stage and
        // fail the attempt politely, with rollback intact — an
        // unstored confirmation is not a sign-in (2026-10-27 crash
        // hunt: unwrapped, this throw escaped into the screen's
        // handler-less launch and crashed the app).
        try {
            store.save(toStore)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            signInDiagnostics.log(
                "store",
                "the confirmed session couldn't be saved on this device " +
                    "(${t.javaClass.simpleName}) — nothing was stored",
            )
            return SignInAttempt.Failed(STORE_REASON)
        }
        stored = toStore
        snapshot = toStore.toAuthSession()
        val displayName = selected.name?.takeIf { it.isNotBlank() } ?: "Your account"
        _uiState.value = SessionUiState(
            signedIn = true,
            identityName = displayName,
            identityEmail = selected.email,
            avatarUrl = selected.avatarUrl,
            health = SessionHealth.HEALTHY,
            lastEndReason = null,
        )
        return SignInAttempt.Success(displayName)
    }

    /**
     * Checks the stored session against YouTube and applies the
     * result: healthy sessions get their identity's real
     * authUser/pageId adopted (the switcher's enumeration corrects
     * any drift), dead sessions are wiped, unreachable ones are kept.
     */
    suspend fun validateNow(): SessionHealth {
        if (snapshot == null) return SessionHealth.UNKNOWN
        return mutex.withLock {
            val current = snapshot ?: return@withLock SessionHealth.UNKNOWN
            val validation = withContext(Dispatchers.IO) { client.validateSession(current) }
        when (validation) {
            is SessionValidation.Healthy -> {
                val identities = validation.identities
                if (identities.isEmpty()) {
                    endSessionLocked()
                } else {
                    val previous = stored
                    val matched = matchIdentity(identities, previous) ?: identities.first()
                    val now = System.currentTimeMillis()
                    val updated = StoredSession(
                        cookies = previous?.cookies ?: current.cookies,
                        authUser = matched.authUser,
                        pageId = matched.pageId,
                        visitorData = previous?.visitorData ?: current.visitorData,
                        identityName = matched.name,
                        identityEmail = matched.email,
                        avatarUrl = matched.avatarUrl,
                        capturedAtEpochMs = previous?.capturedAtEpochMs ?: now,
                        lastValidatedEpochMs = now,
                    )
                    // Persisting the refreshed identity is the same
                    // seal corridor as adoption (2026-10-27 crash
                    // hunt): a device-side seal failure must not
                    // escape validation — the in-memory state below
                    // still updates, and the next launch re-reads
                    // the stored copy and re-validates.
                    try {
                        store.save(updated)
                    } catch (e: Exception) {
                        signInDiagnostics.log(
                            "store",
                            "the refreshed session couldn't be saved (${e.javaClass.simpleName}) — " +
                                "the stored copy stands",
                        )
                    }
                    stored = updated
                    snapshot = updated.toAuthSession()
                    _uiState.value = SessionUiState(
                        signedIn = true,
                        identityName = updated.identityName,
                        identityEmail = updated.identityEmail,
                        avatarUrl = updated.avatarUrl,
                        health = SessionHealth.HEALTHY,
                        lastEndReason = null,
                    )
                    SessionHealth.HEALTHY
                }
            }
            is SessionValidation.NeedsSignIn -> endSessionLocked()
            is SessionValidation.Unreachable -> {
                val state = _uiState.value
                if (state.signedIn) {
                    _uiState.value = state.copy(health = SessionHealth.UNKNOWN)
                }
                SessionHealth.UNKNOWN
            }
            }
        }
    }

    /** The stored identity's match in a fresh enumeration: by email first, then by name. */
    private fun matchIdentity(
        identities: List<AccountIdentity>,
        previous: StoredSession?,
    ): AccountIdentity? {
        if (previous == null) return null
        previous.identityEmail?.takeIf { it.isNotBlank() }?.let { email ->
            identities.firstOrNull { it.email == email }?.let { return it }
        }
        previous.identityName?.takeIf { it.isNotBlank() }?.let { name ->
            identities.firstOrNull { it.name == name }?.let { return it }
        }
        return identities.firstOrNull { it.isSelected == true }
    }

    /** Destroys a session YouTube has declared dead. Caller holds [mutex]. */
    private fun endSessionLocked(): SessionHealth {
        store.wipe()
        stored = null
        snapshot = null
        _uiState.value = SessionUiState(
            signedIn = false,
            health = SessionHealth.NEEDS_SIGN_IN,
            lastEndReason = "Your YouTube session ended. Sign in again to see your library.",
        )
        return SessionHealth.NEEDS_SIGN_IN
    }

    /**
     * Cookie rotation from the transport: responses on YouTube hosts
     * may carry fresh values for the session cookies. Only the named
     * session cookies are ever merged, and the store is only touched
     * when a value actually changed.
     *
     * [rotated] arrives as raw name/value pairs (already split from
     * Set-Cookie headers by the transport); subset filtering happens
     * here, by name, against the session-cookie set.
     */
    fun onResponseCookies(rotated: Map<String, String>) {
        if (snapshot == null || rotated.isEmpty()) return
        val filtered = rotated.filterKeys { name -> name in SESSION_COOKIE_NAMES }
        if (filtered.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            // Rotation is best-effort bookkeeping riding on the
            // transport: a seal failure here (the same corridor as
            // adoption, 2026-10-27 crash hunt) must never take down
            // the browsing that carried the cookies in. The in-memory
            // session keeps the pre-rotation values; the next
            // response offers them again.
            try {
                mutex.withLock {
                    val current = snapshot ?: return@withLock
                    val previous = stored ?: return@withLock
                    val merged = current.cookies + filtered
                    if (merged == current.cookies) return@withLock
                    val updated = previous.copy(cookies = merged)
                    store.save(updated)
                    stored = updated
                    snapshot = updated.toAuthSession()
                }
            } catch (e: Exception) {
                // Deliberately silent: rotation retries itself on
                // the next response by construction.
            }
        }
    }

    /**
     * Signs out: the stored credential is wiped AND the wipe is
     * verified before the UI reports signed out. WebView data is the
     * caller's job — see [wipeWebViewData] — because it must happen
     * on the UI side where the WebView lives.
     */
    suspend fun signOut() {
        mutex.withLock {
            store.wipe()
            stored = null
            snapshot = null
            _uiState.value = SessionUiState(
                signedIn = false,
                health = SessionHealth.UNKNOWN,
                lastEndReason = null,
            )
        }
    }

    private fun StoredSession.toAuthSession(): AuthSession = AuthSession(
        cookies = cookies,
        authUser = authUser,
        pageId = pageId,
        visitorData = visitorData,
    )

    private companion object {
        // Copy discipline (2026-10-27 sign-in hunt): when confirmation
        // exhausts its window, the diagnostics have already shown the
        // cookies complete and the sign-in page finished — the old
        // text blamed an unloaded page, which sent the owner to fix
        // a thing that was never broken. The message names the class
        // that actually failed: YouTube would not confirm the session
        // to the app.
        const val NOT_CONFIRMED_REASON =
            "YouTube didn't confirm a signed-in account for that session, " +
                "even though the sign-in page finished and the session " +
                "cookies were all there. Nothing was saved. Try signing in again."

        const val SUBSET_REASON =
            "That sign-in didn't include a usable YouTube session. " +
                "Sign in again and let the YouTube Music page finish loading, " +
                "or paste a cookie from music.youtube.com."

        const val REACH_REASON =
            "Couldn't reach YouTube to verify the sign-in — " +
                "check your connection and try again."

        // Copy discipline (2026-10-27 crash hunt): a confirmed
        // sign-in whose save failed on the device is its own class —
        // YouTube said yes; the phone couldn't keep it. The message
        // says exactly that, and the diagnostics name the store
        // stage with the failure's class.
        const val STORE_REASON =
            "YouTube confirmed your account, but the app couldn't " +
                "save the session on this device, so nothing was " +
                "stored. Try signing in again."

        const val UNEXPECTED_REASON =
            "Something went wrong while confirming the sign-in. " +
                "Nothing was saved. Try signing in again."

        /**
         * The production confirmation window: five attempts across
         * ~30 seconds (0s, +2s, +6s, +14s, +30s). Long enough for a
         * WebView login's cookies to finish landing and for YouTube
         * to recognize the session; short enough that a genuinely
         * dead session still fails while the user is looking at the
         * sign-in screen.
         */
        val DEFAULT_CONFIRMATION_DELAYS_MS: List<Long> =
            listOf(2_000L, 4_000L, 8_000L, 16_000L)
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
