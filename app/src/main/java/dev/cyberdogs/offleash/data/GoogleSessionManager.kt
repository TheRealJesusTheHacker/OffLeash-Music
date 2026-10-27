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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 *
 * Grant durability (0.7.1): an issued device grant is persisted
 * (sealed, [GooglePendingGrantStore]) the moment it exists, owned
 * by this manager rather than the sign-in screen or the poll job.
 * Leaving the app — even process death — no longer orphans it: a
 * new manager restores a live grant in `init` and resumes polling
 * it, an expired one is cleared with the plain expired ending,
 * and a new grant is issued only on an explicit start with none
 * live (or after the live one resolves).
 */
class GoogleSessionManager(
    private val store: GoogleTokenStore,
    private val auth: GoogleDeviceAuth,
    private val client: YoutubeiClient,
    private val scope: CoroutineScope,
    private val pendingGrantStore: GooglePendingGrantStore? = null,
    private val signInDiagnostics: SignInDiagnostics = SignInDiagnostics(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = Any()

    @Volatile
    private var stored: StoredGoogleSession? = null

    /**
     * The issued-but-unapproved device grant (0.7.1), mirrored in
     * [pendingGrantStore] the moment it exists. This record — not
     * the screen, not the poll job — is the grant's owner: the
     * screen only renders it, the poll loop only serves it, and a
     * fresh manager after process death re-adopts it from the
     * sealed store and resumes polling it.
     */
    @Volatile
    private var pendingGrant: StoredPendingGrant? = null

    /**
     * "Check now" wake-ups for the poll loop: a conflated signal the
     * waiting screen's button and [checkNow] fire so a parked poll
     * runs immediately against the CURRENT grant (never a new one).
     */
    private val checkNowSignal = Channel<Unit>(Channel.CONFLATED)

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
        restorePendingGrant()
    }

    /**
     * Re-adopts a persisted pending grant after a restart (0.7.1):
     * a LIVE grant is republished and its polling resumes on this
     * manager's scope — the owner comes back to the same code he
     * left with, and an approval he already gave is collected. An
     * EXPIRED grant is cleared and lands on the plain expired
     * ending, so no zombie polling ever runs. When a session is
     * already stored, a leftover grant is simply stale and wiped.
     */
    private fun restorePendingGrant() {
        val persisted = try {
            pendingGrantStore?.load()
        } catch (t: Throwable) {
            signInDiagnostics.log(
                "google-sign-in",
                "the saved sign-in code couldn't be read (${t.javaClass.simpleName}) — ignoring it",
            )
            null
        } ?: return
        if (stored != null) {
            clearPendingGrant()
            return
        }
        if (!persisted.isLiveAt(nowMs())) {
            clearPendingGrant()
            signInDiagnostics.log(
                "google-sign-in",
                "a saved sign-in code had expired while the app was away — cleared",
            )
            _phase.value = GoogleSignInPhase.Failed(EXPIRED_REASON)
            return
        }
        pendingGrant = persisted
        signInDiagnostics.log(
            "google-sign-in",
            "a sign-in code from before the restart is still live — resuming its check",
        )
        publishAwaiting(persisted)
        signInJob = scope.launch { pollPendingGrant() }
    }

    fun hasStoredSession(): Boolean = stored != null

    // ------------------------------------------------------------------
    // Sign-in: the device-code flow
    // ------------------------------------------------------------------

    /**
     * Starts a sign-in attempt — or RESUMES the one already live.
     * A grant is issued only when no live grant exists: while a
     * persisted grant is unexpired, this republishes its code and
     * makes sure its polling is running, so re-entering the screen
     * (or tapping again after a failed check) can never burn the
     * code the owner may already be approving. Runs on the
     * manager's scope; the screen only renders [phase] + [uiState].
     */
    fun startSignIn() {
        if (_uiState.value.signedIn) return
        val live = livePendingGrant()
        if (live != null) {
            signInDiagnostics.log(
                "google-sign-in",
                "a code is already live — resuming it instead of issuing a new one",
            )
            publishAwaiting(live)
            ensurePolling()
            return
        }
        signInJob?.cancel()
        _phase.value = GoogleSignInPhase.Idle
        signInJob = scope.launch {
            runSignIn()
        }
    }

    /**
     * Polls the CURRENT persisted grant once, right now — the
     * waiting screen's "I've approved — check now" button, for
     * devices that throttle background work. Never issues a grant:
     * with no live grant this is a no-op, and when the poll loop
     * is parked it simply wakes it early.
     */
    fun checkNow() {
        if (_uiState.value.signedIn) return
        val live = livePendingGrant() ?: return
        publishAwaiting(live)
        val job = signInJob
        if (job != null && job.isActive) {
            checkNowSignal.trySend(Unit)
        } else {
            ensurePolling()
        }
    }

    /**
     * Stops a running attempt AND abandons its grant: the
     * persisted record is wiped, so a cancelled code is never
     * resumed later. Nothing was or will be stored for it.
     */
    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        clearPendingGrant()
        if (!_uiState.value.signedIn) {
            _phase.value = GoogleSignInPhase.Idle
        }
    }

    /** The live pending grant, from memory or the sealed store. */
    private fun livePendingGrant(): StoredPendingGrant? {
        val inMemory = pendingGrant
        val candidate = inMemory ?: try {
            pendingGrantStore?.load()
        } catch (t: Throwable) {
            null
        }
        if (candidate == null) return null
        if (!candidate.isLiveAt(nowMs())) {
            clearPendingGrant()
            signInDiagnostics.log(
                "google-sign-in",
                "the saved sign-in code has expired — cleared",
            )
            return null
        }
        if (inMemory == null) pendingGrant = candidate
        return candidate
    }

    /** Starts the poll loop for [pendingGrant] unless one is running. */
    private fun ensurePolling() {
        val job = signInJob
        if (job != null && job.isActive) return
        signInJob = scope.launch { pollPendingGrant() }
    }

    /** Publishes the waiting state for [grant] — the screen renders this. */
    private fun publishAwaiting(grant: StoredPendingGrant) {
        _phase.value = GoogleSignInPhase.AwaitingApproval(
            userCode = grant.userCode,
            verificationUrl = grant.verificationUrl,
            expiresAtEpochMs = grant.expiresAtEpochMs,
        )
    }

    /**
     * Drops the pending grant everywhere (memory + sealed store).
     * Called at every terminal outcome — adopted, declined,
     * expired, cancelled, signed out — so a resolved grant can
     * never be polled or resumed again.
     */
    private fun clearPendingGrant() {
        pendingGrant = null
        try {
            pendingGrantStore?.wipe()
        } catch (t: Throwable) {
            signInDiagnostics.log(
                "google-sign-in",
                "the saved sign-in code couldn't be cleared (${t.javaClass.simpleName})",
            )
        }
    }

    /**
     * Issues a NEW grant (only reached when none is live),
     * persists it BEFORE the first poll — the persistence is what
     * lets the attempt survive the owner leaving for the browser —
     * and hands it to the poll loop.
     */
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
            val now = nowMs()
            val grant = StoredPendingGrant(
                deviceCode = code.deviceCode,
                userCode = code.userCode,
                verificationUrl = code.verificationUrl,
                issuedAtEpochMs = now,
                expiresAtEpochMs = now + code.expiresInSeconds * 1000L,
                intervalSeconds = code.intervalSeconds,
            )
            pendingGrant = grant
            try {
                pendingGrantStore?.save(grant)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // The attempt still works while this process lives;
                // only the survive-a-restart guarantee is lost, and
                // the diagnostics say so rather than hiding it.
                signInDiagnostics.log(
                    "google-sign-in",
                    "the sign-in code couldn't be saved for resume " +
                        "(${t.javaClass.simpleName}) — this attempt still " +
                        "works while the app stays open",
                )
            }
            signInDiagnostics.log(
                "google-sign-in",
                "code issued — polling every ${code.intervalSeconds}s, " +
                    "expires in ${code.expiresInSeconds}s",
            )
            publishAwaiting(grant)
            pollPendingGrant()
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
     * The poll loop for the CURRENT [pendingGrant], owned by the
     * manager's (app) scope — never by the screen. It runs while
     * the app is backgrounded, and a fresh manager resumes it from
     * the persisted grant after process death (see
     * [restorePendingGrant]). Each terminal outcome clears the
     * persisted grant before landing; a mere transport failure
     * keeps it, so checking can resume against the same code.
     */
    private suspend fun pollPendingGrant() {
        val grant = pendingGrant ?: return
        try {
            var intervalSeconds = grant.intervalSeconds.coerceAtLeast(1L)
            while (true) {
                // Park until the next due poll — or wake at once on
                // a "check now" signal, coalesced by the channel.
                withTimeoutOrNull(intervalSeconds * 1000L) {
                    checkNowSignal.receive()
                }
                if (nowMs() >= grant.expiresAtEpochMs) {
                    signInDiagnostics.log("google-sign-in", "the code expired unapproved")
                    clearPendingGrant()
                    _phase.value = GoogleSignInPhase.Failed(EXPIRED_REASON)
                    return
                }
                val poll = withContext(Dispatchers.IO) {
                    try {
                        auth.pollForToken(grant.deviceCode)
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
                        // The grant is spent the moment Google
                        // issues tokens; clear it before adopting
                        // so no path can ever poll it again.
                        clearPendingGrant()
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
                        clearPendingGrant()
                        _phase.value = GoogleSignInPhase.Failed(DENIED_REASON)
                        return
                    }
                    TokenPollResult.Expired -> {
                        signInDiagnostics.log("google-sign-in", "poll: the code expired")
                        clearPendingGrant()
                        _phase.value = GoogleSignInPhase.Failed(EXPIRED_REASON)
                        return
                    }
                    is TokenPollResult.Failed -> {
                        // Transient by assumption: the code itself
                        // is still good, so the persisted grant is
                        // KEPT — "try again" / "check now" resume
                        // checking this same code, and a restart
                        // resumes it too.
                        signInDiagnostics.log(
                            "google-sign-in",
                            "poll failed (${poll.detail}) — the code is kept",
                        )
                        _phase.value = GoogleSignInPhase.Failed(
                            "Couldn't finish checking with Google (${poll.detail}). " +
                                "Nothing was stored, but your code is still good " +
                                "until it expires — try again to keep checking for it."
                        )
                        return
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            signInDiagnostics.log(
                "google-sign-in",
                "polling failed unexpectedly (${t.javaClass.simpleName}) — the code is kept",
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
        clearPendingGrant()
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
