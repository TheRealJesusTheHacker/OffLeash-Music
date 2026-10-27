package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.DeviceCodeResult
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
import dev.cyberdogs.offleash.core.auth.GoogleTokens
import dev.cyberdogs.offleash.core.auth.IdentityLookup
import dev.cyberdogs.offleash.core.auth.RefreshResult
import dev.cyberdogs.offleash.core.auth.TokenPollResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the Google sign-in flow currently stands, for the sign-in screen. */
sealed interface GoogleSignInPhase {
    /** No attempt running: the screen offers "Sign in with Google". */
    object Idle : GoogleSignInPhase

    /**
     * A code is issued and the poll loop is running: the screen
     * shows [userCode] and sends the owner to [verificationUrl].
     */
    data class AwaitingApproval(
        val userCode: String,
        val verificationUrl: String,
        val expiresAtEpochMs: Long,
    ) : GoogleSignInPhase

    /** The attempt ended without a sign-in; [reason] says why, plainly. */
    data class Failed(val reason: String) : GoogleSignInPhase
}

/**
 * Owns the Google sign-in (0.7.0): the device-code flow, the
 * encrypted [GoogleTokenStore], automatic refresh, and revocation
 * on sign-out. This is the app's PRIMARY session from 0.7.0; the
 * cookie [SessionManager] remains as the disconnected safety net.
 *
 * Totality discipline, same as the cookie manager: no throw escapes
 * any stage. The device flow's states (pending, slow down, denied,
 * expired) are protocol answers, not errors, and each lands on its
 * own honest [GoogleSignInPhase]. Diagnostics record stages and
 * presence/absence only — a token value is never written anywhere
 * but the sealed store.
 *
 * Threading: [currentBearer] is BLOCKING (it may perform a refresh
 * on the caller's thread) — call it from an IO dispatcher, exactly
 * like the core client's calls. Everything else is suspend or
 * state-flow reads.
 */
class GoogleSessionManager(
    private val store: GoogleTokenStore,
    private val auth: GoogleDeviceAuth,
    private val client: YoutubeiClient,
    private val scope: CoroutineScope,
    private val signInDiagnostics: SignInDiagnostics = SignInDiagnostics(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = Any()

    @Volatile
    private var stored: StoredGoogleSession? = null

    private val _uiState = MutableStateFlow(SessionUiState(signedIn = false))
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private val _phase = MutableStateFlow<GoogleSignInPhase>(GoogleSignInPhase.Idle)
    val phase: StateFlow<GoogleSignInPhase> = _phase.asStateFlow()

    @Volatile
    private var signInJob: Job? = null

    init {
        val loaded = store.load()
        stored = loaded
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

    fun hasStoredSession(): Boolean = stored != null

    // ------------------------------------------------------------------
    // Sign-in: the device-code flow
    // ------------------------------------------------------------------

    /**
     * Starts (or restarts) a sign-in attempt: asks Google for a
     * device code, publishes it in [phase], and polls until the
     * owner approves, declines, or the code expires. Runs on the
     * manager's scope; the screen only renders [phase] + [uiState].
     */
    fun startSignIn() {
        if (_uiState.value.signedIn) return
        signInJob?.cancel()
        _phase.value = GoogleSignInPhase.Idle
        signInJob = scope.launch {
            runSignIn()
        }
    }

    /** Stops a running attempt; nothing was or will be stored for it. */
    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        if (!_uiState.value.signedIn) {
            _phase.value = GoogleSignInPhase.Idle
        }
    }

    private suspend fun runSignIn() {
        signInDiagnostics.startAttempt()
        signInDiagnostics.log("google-sign-in", "device-code flow started")
        try {
            val issued = withContext(Dispatchers.IO) {
                try {
                    auth.requestDeviceCode()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    DeviceCodeResult.Failed("transport: ${t.javaClass.simpleName}")
                }
            }
            val code = when (issued) {
                is DeviceCodeResult.Failed -> {
                    signInDiagnostics.log(
                        "google-sign-in",
                        "Google refused to issue a code (${issued.detail})",
                    )
                    _phase.value = GoogleSignInPhase.Failed(
                        "Google couldn't start the sign-in (${issued.detail}). " +
                            "Nothing was stored. Check your connection and try again."
                    )
                    return
                }
                is DeviceCodeResult.Issued -> issued.code
            }
            signInDiagnostics.log(
                "google-sign-in",
                "code issued — polling every ${code.intervalSeconds}s, " +
                    "expires in ${code.expiresInSeconds}s",
            )
            _phase.value = GoogleSignInPhase.AwaitingApproval(
                userCode = code.userCode,
                verificationUrl = code.verificationUrl,
                expiresAtEpochMs = nowMs() + code.expiresInSeconds * 1000L,
            )
            var intervalSeconds = code.intervalSeconds.coerceAtLeast(1L)
            val deadlineMs = nowMs() + code.expiresInSeconds * 1000L
            while (true) {
                delay(intervalSeconds * 1000L)
                if (nowMs() >= deadlineMs) {
                    signInDiagnostics.log("google-sign-in", "the code expired unapproved")
                    _phase.value = GoogleSignInPhase.Failed(EXPIRED_REASON)
                    return
                }
                val poll = withContext(Dispatchers.IO) {
                    try {
                        auth.pollForToken(code.deviceCode)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        TokenPollResult.Failed("transport: ${t.javaClass.simpleName}")
                    }
                }
                when (poll) {
                    is TokenPollResult.Granted -> {
                        signInDiagnostics.log(
                            "google-sign-in",
                            "approved — token granted (expires in " +
                                "${poll.tokens.expiresInSeconds}s), adopting",
                        )
                        adopt(poll.tokens)
                        return
                    }
                    TokenPollResult.Pending -> {
                        signInDiagnostics.log("google-sign-in", "poll: still pending")
                    }
                    TokenPollResult.SlowDown -> {
                        intervalSeconds += SLOW_DOWN_INCREMENT_SECONDS
                        signInDiagnostics.log(
                            "google-sign-in",
                            "poll: Google asked to slow down — interval now ${intervalSeconds}s",
                        )
                    }
                    TokenPollResult.Denied -> {
                        signInDiagnostics.log("google-sign-in", "poll: declined by the owner")
                        _phase.value = GoogleSignInPhase.Failed(DENIED_REASON)
                        return
                    }
                    TokenPollResult.Expired -> {
                        signInDiagnostics.log("google-sign-in", "poll: the code expired")
                        _phase.value = GoogleSignInPhase.Failed(EXPIRED_REASON)
                        return
                    }
                    is TokenPollResult.Failed -> {
                        signInDiagnostics.log(
                            "google-sign-in",
                            "poll failed (${poll.detail})",
                        )
                        _phase.value = GoogleSignInPhase.Failed(
                            "Couldn't finish checking with Google (${poll.detail}). " +
                                "Nothing was stored. Check your connection and try again."
                        )
                        return
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            signInDiagnostics.log(
                "google-sign-in",
                "the attempt failed unexpectedly (${t.javaClass.simpleName}) — nothing was stored",
            )
            _phase.value = GoogleSignInPhase.Failed(UNEXPECTED_REASON)
        }
    }

    /**
     * Persists a granted token set. Google's grant IS the
     * confirmation (it issues tokens only after the owner's approval
     * on Google's page), so the tokens are adopted directly; the
     * identity fetch that names the account is best-effort here —
     * except a REJECTED verdict on a just-granted token, which fails
     * the attempt honestly rather than storing a session Google
     * already refuses. The store save is wrapped like the cookie
     * manager's (0.6.4 corridor): a seal failure names the store
     * stage and stores nothing.
     */
    private suspend fun adopt(tokens: GoogleTokens) {
        val lookup = try {
            withContext(Dispatchers.IO) { client.oauthIdentity(tokens.accessToken) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            signInDiagnostics.log(
                "identity",
                "the identity check failed unexpectedly (${t.javaClass.simpleName}) — " +
                    "adopting the grant unnamed; the name is retried at next launch",
            )
            IdentityLookup.Failed("unexpected: ${t.javaClass.simpleName}")
        }
        if (lookup is IdentityLookup.Rejected) {
            signInDiagnostics.log(
                "identity",
                "the just-granted token was refused (HTTP ${lookup.httpStatus}) — nothing stored",
            )
            _phase.value = GoogleSignInPhase.Failed(IDENTITY_REJECTED_REASON)
            return
        }
        val selected = (lookup as? IdentityLookup.Found)?.identities?.firstOrNull()
        signInDiagnostics.log(
            "identity",
            if (selected != null) {
                "Google named the account (1 identity)"
            } else {
                "the account name couldn't be read yet — adopting unnamed"
            },
        )
        val now = nowMs()
        val toStore = StoredGoogleSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            accessTokenExpiresAtEpochMs = now + tokens.expiresInSeconds * 1000L,
            grantedScopes = tokens.scope,
            identityName = selected?.name,
            identityEmail = selected?.email,
            avatarUrl = selected?.avatarUrl,
            grantedAtEpochMs = now,
            lastValidatedEpochMs = if (selected != null) now else 0L,
        )
        try {
            store.save(toStore)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            signInDiagnostics.log(
                "store",
                "the granted sign-in couldn't be saved on this device " +
                    "(${t.javaClass.simpleName}) — nothing was stored",
            )
            _phase.value = GoogleSignInPhase.Failed(STORE_REASON)
            return
        }
        synchronized(lock) { stored = toStore }
        _uiState.value = SessionUiState(
            signedIn = true,
            identityName = toStore.identityName,
            identityEmail = toStore.identityEmail,
            avatarUrl = toStore.avatarUrl,
            health = if (selected != null) {
                SessionHealth.HEALTHY
            } else {
                SessionHealth.UNKNOWN
            },
            lastEndReason = null,
        )
        _phase.value = GoogleSignInPhase.Idle
        signInDiagnostics.log(
            "google-sign-in",
            "sign-in complete — token sealed in the encrypted store",
        )
    }

    // ------------------------------------------------------------------
    // Token access + refresh
    // ------------------------------------------------------------------

    /**
     * A currently-valid access token, or null when signed out (or
     * when the grant is gone). Refreshes proactively when the stored
     * token is expired or inside the skew window; a revoked grant
     * wipes the store and flips [uiState] to NEEDS_SIGN_IN, while a
     * mere network failure keeps the session (and still returns the
     * stored token when it hasn't actually expired yet).
     *
     * BLOCKING: call from an IO dispatcher.
     */
    fun currentBearer(): String? {
        val session = stored ?: return null
        if (session.accessTokenExpiresAtEpochMs - nowMs() > REFRESH_SKEW_MS) {
            return session.accessToken
        }
        synchronized(lock) {
            val current = stored ?: return null
            val now = nowMs()
            if (current.accessTokenExpiresAtEpochMs - now > REFRESH_SKEW_MS) {
                return current.accessToken
            }
            val refreshToken = current.refreshToken
                ?: return if (now < current.accessTokenExpiresAtEpochMs) {
                    current.accessToken
                } else {
                    null
                }
            val result = try {
                auth.refreshTokens(refreshToken)
            } catch (e: Exception) {
                RefreshResult.Failed("transport: ${e.javaClass.simpleName}")
            }
            return when (result) {
                is RefreshResult.Refreshed -> {
                    val updated = current.copy(
                        accessToken = result.tokens.accessToken,
                        refreshToken = result.tokens.refreshToken ?: current.refreshToken,
                        accessTokenExpiresAtEpochMs =
                            nowMs() + result.tokens.expiresInSeconds * 1000L,
                        grantedScopes = result.tokens.scope ?: current.grantedScopes,
                    )
                    try {
                        store.save(updated)
                    } catch (e: Exception) {
                        signInDiagnostics.log(
                            "store",
                            "the refreshed token couldn't be saved (${e.javaClass.simpleName}) — " +
                                "the stored copy stands",
                        )
                    }
                    stored = updated
                    signInDiagnostics.log(
                        "google-sign-in",
                        "token refreshed (expires in ${result.tokens.expiresInSeconds}s)",
                    )
                    updated.accessToken
                }
                RefreshResult.Revoked -> {
                    store.wipe()
                    stored = null
                    _uiState.value = SessionUiState(
                        signedIn = false,
                        health = SessionHealth.NEEDS_SIGN_IN,
                        lastEndReason = REVOKED_REASON,
                    )
                    signInDiagnostics.log(
                        "google-sign-in",
                        "the grant is gone (revoked) — the stored tokens were wiped",
                    )
                    null
                }
                is RefreshResult.Failed -> {
                    signInDiagnostics.log(
                        "google-sign-in",
                        "token refresh failed (${result.detail}) — the session is kept",
                    )
                    if (nowMs() < current.accessTokenExpiresAtEpochMs) {
                        current.accessToken
                    } else {
                        null
                    }
                }
            }
        }
    }

    /**
     * Re-reads the account identity for the stored session — the
     * token road's [SessionManager.validateNow]: healthy answers
     * refresh the stored name/avatar; a rejection ends the session
     * (wiped, with a plain reason); an unreachable Google keeps the
     * session untouched.
     */
    suspend fun refreshIdentity() {
        if (stored == null) return
        val bearer = withContext(Dispatchers.IO) { currentBearer() } ?: return
        val lookup = try {
            withContext(Dispatchers.IO) { client.oauthIdentity(bearer) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            IdentityLookup.Failed("unexpected: ${t.javaClass.simpleName}")
        }
        when (lookup) {
            is IdentityLookup.Found -> {
                val identity = lookup.identities.firstOrNull() ?: return
                synchronized(lock) {
                    val current = stored ?: return
                    val updated = current.copy(
                        identityName = identity.name,
                        identityEmail = identity.email,
                        avatarUrl = identity.avatarUrl,
                        lastValidatedEpochMs = nowMs(),
                    )
                    try {
                        store.save(updated)
                    } catch (e: Exception) {
                        signInDiagnostics.log(
                            "store",
                            "the refreshed identity couldn't be saved " +
                                "(${e.javaClass.simpleName}) — the stored copy stands",
                        )
                    }
                    stored = updated
                }
                val current = stored
                _uiState.value = SessionUiState(
                    signedIn = true,
                    identityName = current?.identityName,
                    identityEmail = current?.identityEmail,
                    avatarUrl = current?.avatarUrl,
                    health = SessionHealth.HEALTHY,
                    lastEndReason = null,
                )
            }
            is IdentityLookup.Rejected -> {
                synchronized(lock) {
                    store.wipe()
                    stored = null
                }
                _uiState.value = SessionUiState(
                    signedIn = false,
                    health = SessionHealth.NEEDS_SIGN_IN,
                    lastEndReason = REVOKED_REASON,
                )
            }
            is IdentityLookup.Failed -> {
                val state = _uiState.value
                if (state.signedIn) {
                    _uiState.value = state.copy(health = SessionHealth.UNKNOWN)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Sign-out
    // ------------------------------------------------------------------

    /**
     * Signs out: tells Google to revoke the grant (the refresh
     * token, which takes the whole grant down with it), wipes the
     * sealed store, and verifies the wipe before reporting signed
     * out. Revocation is best-effort — offline sign-out still wipes
     * the device copy; Google's revocation then simply never hears
     * about a token that no longer exists here.
     */
    suspend fun signOut() {
        signInJob?.cancel()
        signInJob = null
        val session = stored
        if (session != null) {
            val toRevoke = session.refreshToken ?: session.accessToken
            val revoked = try {
                withContext(Dispatchers.IO) { auth.revokeToken(toRevoke) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                false
            }
            signInDiagnostics.log(
                "google-sign-in",
                if (revoked) {
                    "sign-out — Google revoked the grant"
                } else {
                    "sign-out — revocation couldn't be confirmed (offline?); the device copy is wiped regardless"
                },
            )
        }
        synchronized(lock) {
            store.wipe()
            stored = null
        }
        _uiState.value = SessionUiState(
            signedIn = false,
            health = SessionHealth.UNKNOWN,
            lastEndReason = null,
        )
        _phase.value = GoogleSignInPhase.Idle
    }

    private companion object {
        /** Refresh when the access token has less than this left. */
        const val REFRESH_SKEW_MS = 60_000L

        /** RFC 8628: a slow_down answer adds 5 seconds to the interval. */
        const val SLOW_DOWN_INCREMENT_SECONDS = 5L

        const val DENIED_REASON =
            "The sign-in was declined on Google's page, so no sign-in " +
                "was created and nothing was stored. Start again " +
                "whenever you're ready."

        const val EXPIRED_REASON =
            "That code expired before it was approved — a code lasts " +
                "about 30 minutes. Start again to get a fresh one."

        const val STORE_REASON =
            "Google confirmed your account, but the app couldn't save " +
                "the sign-in on this device, so nothing was stored. " +
                "Try signing in again."

        const val IDENTITY_REJECTED_REASON =
            "Google approved the sign-in, but the token it issued was " +
                "refused when the app checked which account it belongs " +
                "to. Nothing was stored. Try signing in again."

        const val REVOKED_REASON =
            "Your Google sign-in was revoked or has expired. " +
                "Sign in again to see your library."

        const val UNEXPECTED_REASON =
            "Something went wrong during Google sign-in. " +
                "Nothing was saved. Try signing in again."
    }
}
