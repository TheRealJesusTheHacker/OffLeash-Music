package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.BrowseDelegation
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.ClientChangeGate
import dev.cyberdogs.offleash.core.auth.ClientGateDecision
import dev.cyberdogs.offleash.core.auth.CodeExchangeResult
import dev.cyberdogs.offleash.core.auth.GoogleNativeAuth
import dev.cyberdogs.offleash.core.auth.GoogleTokens
import dev.cyberdogs.offleash.core.auth.IdentityLookup
import dev.cyberdogs.offleash.core.auth.OAuthClientKind
import dev.cyberdogs.offleash.core.auth.RedirectResult
import dev.cyberdogs.offleash.core.auth.RefreshResult
import dev.cyberdogs.offleash.core.auth.signInClientLine
import dev.cyberdogs.offleash.core.model.AccountIdentity
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
    /** No attempt running: the screen offers "Continue with Google". */
    object Idle : GoogleSignInPhase

    /**
     * A tap is being turned into Google's page (0.8.7 sweep 3): the
     * attempt is built and the browser is opening — without this
     * phase the screen sat on Idle, its button still live, inviting
     * a second tap and showing nothing.
     */
    object Issuing : GoogleSignInPhase

    /**
     * Google's page is open in the browser (0.8.8): the sign-in
     * finishes when the owner approves there and the browser hands
     * the answer back to the app. [expiresAtEpochMs] bounds the
     * wait — an attempt that never comes back dies honestly instead
     * of being collectable forever.
     */
    data class AwaitingReturn(
        val expiresAtEpochMs: Long,
    ) : GoogleSignInPhase

    /** The attempt ended without a sign-in; [reason] says why, plainly. */
    data class Failed(val reason: String) : GoogleSignInPhase
}

/**
 * A browser launch the sign-in screen must perform (0.8.8): open
 * [url] in a Chrome Custom Tab. Published once per attempt —
 * [attemptId] is the flow generation that minted it, so the screen
 * launches each attempt's page exactly once and never relaunches a
 * spent one on recomposition.
 */
data class NativeSignInLaunch(
    val url: String,
    val attemptId: Long,
)

/**
 * Owns the Google sign-in (0.7.0; native flow from 0.8.8): the
 * authorization-code + PKCE sign-in through the system browser,
 * the encrypted [GoogleTokenStore], automatic refresh, and
 * revocation on sign-out. This is the app's PRIMARY session; the
 * cookie [SessionManager] remains as the disconnected safety net.
 *
 * The flow (decision brief 2026-11-01, Road C; owner direction
 * 2026-11-03 — the own-key console wizard was the wrong route,
 * "we need a better login way", cleaner): one tap on "Continue with
 * Google" builds a one-time authorization request naming the app's
 * ANDROID OAuth client, opens it in a Chrome Custom Tab where the
 * owner picks the Google account already on the phone and approves,
 * and Google returns the owner to the app with a code the app
 * exchanges — PKCE verifier in hand — for the token set. No typed
 * code, no console steps, no pasted keys, no password in the app,
 * no WebView, no cookies.
 *
 * Attempt durability (0.7.1's grant rule, carried to the new flow):
 * an in-flight attempt is persisted (sealed,
 * [GooglePendingNativeAuthStore]) the moment it exists, owned by
 * this manager rather than the screen — the browser leg runs
 * OUTSIDE the app, and the app may be killed while the owner is
 * approving; a fresh manager restores a live attempt and still
 * collects its return, and an expired one is cleared, never
 * collected.
 *
 * Totality discipline, same as the cookie manager: no throw
 * escapes any stage. The flow's outcomes (granted, declined,
 * mismatched return, dead code) are protocol answers, not errors,
 * and each lands on its own honest [GoogleSignInPhase].
 * Diagnostics record stages and presence/absence only — a token
 * value, code, or verifier is never written anywhere but the
 * sealed stores.
 *
 * Threading: [currentBearer] is BLOCKING (it may perform a refresh
 * on the caller's thread) — call it from an IO dispatcher, exactly
 * like the core client's calls. Everything else is suspend or
 * state-flow reads.
 */
class GoogleSessionManager(
    private val store: GoogleTokenStore,
    private val auth: GoogleNativeAuth,
    private val client: YoutubeiClient,
    private val scope: CoroutineScope,
    private val redirectUri: String,
    private val pendingAttemptStore: GooglePendingNativeAuthStore? = null,
    private val signInDiagnostics: SignInDiagnostics = SignInDiagnostics(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = Any()

    @Volatile
    private var stored: StoredGoogleSession? = null

    /**
     * Memory is ahead of disk (0.8.7 sweep 1): a refresh landed
     * new tokens — possibly a ROTATED refresh token — but the
     * sealed save threw. Until a retry lands, a restart would
     * reload the rotated-out refresh token and read its
     * invalid_grant as a revocation, wiping a healthy session.
     * Guarded by [lock] wherever it is written; read unlocked as
     * a fast-path hint in [currentBearer].
     */
    @Volatile
    private var sessionSaveDirty: Boolean = false

    /**
     * The access token the standing session most recently rotated
     * AWAY from (sign-in perfection pass): a request that left with
     * it can still be in flight when its 401 comes back, and
     * [bearerAfterRejection] answers that refusal with the standing
     * token instead of a spurious error. One generation is enough —
     * a refusal older than one rotation is indistinguishable from
     * a foreign token, and is treated as one.
     */
    @Volatile
    private var previousAccessToken: String? = null

    /**
     * The token a forced (reactive) refresh has already been spent
     * on: [bearerAfterRejection] refreshes a refused standing token
     * ONCE, so a caller replaying the same dead token across many
     * requests can't turn each one into a token-endpoint call.
     * Meaningful only while it still equals the standing token;
     * any rotation makes it stale on its face.
     */
    @Volatile
    private var rejectionHandledForToken: String? = null

    /**
     * The token whose most recent forced refresh FAILED
     * transiently, and when a retry of it becomes allowed again
     * (0.8.7 sweep 1): a failed attempt starts a cooldown instead
     * of spending the one-shot forever — one network blip used to
     * leave a refused-but-locally-valid token unrecoverable until
     * its natural expiry, which is exactly the clock-skew case
     * [bearerAfterRejection] exists for.
     */
    private var rejectionFailedToken: String? = null
    private var rejectionRetryAtMs: Long = 0L

    /**
     * The in-flight native sign-in attempt (0.8.8), mirrored in
     * [pendingAttemptStore] the moment it exists. This record —
     * not the screen — is the attempt's owner: the screen only
     * renders its phase and launches its page, and a fresh manager
     * after process death re-adopts it from the sealed store so
     * the browser's return still lands.
     */
    @Volatile
    private var pendingAttempt: StoredPendingNativeAuth? = null

    private val _uiState = MutableStateFlow(SessionUiState(signedIn = false))
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private val _phase = MutableStateFlow<GoogleSignInPhase>(GoogleSignInPhase.Idle)
    val phase: StateFlow<GoogleSignInPhase> = _phase.asStateFlow()

    /**
     * The browser launch for the standing attempt, published once
     * per attempt (see [NativeSignInLaunch]). Null when no attempt
     * is waiting on a launch — before the first tap, after the
     * return lands, and after a cancel.
     */
    private val _authorizationLaunch =
        MutableStateFlow<NativeSignInLaunch?>(null)
    val authorizationLaunch: StateFlow<NativeSignInLaunch?> =
        _authorizationLaunch.asStateFlow()

    /**
     * Whether the standing attempt is DURABLE — sealed in
     * [pendingAttemptStore], so it survives the app being killed
     * in the browser — or lives only in this process because its
     * save failed (the persist caveat, round 11's pin). True when
     * no attempt stands: the caveat describes a standing attempt,
     * and with none standing there is nothing to lose. The screen
     * reads this to say, honestly and only when it is true, that
     * an attempt won't survive the app closing.
     */
    private val _pendingAttemptDurability = MutableStateFlow(true)
    val pendingAttemptDurability: StateFlow<Boolean> =
        _pendingAttemptDurability.asStateFlow()

    @Volatile
    private var signInJob: Job? = null

    /**
     * The sign-in flow's generation counter (0.7.2 sweep). Cancel
     * and sign-out bump it; issuance and adoption capture it and
     * re-check it before publishing or persisting anything. This is
     * what makes "cancel / sign out while an adoption is in its
     * final, non-suspending steps" deterministic: coroutine
     * cancellation alone cannot stop that tail (it contains no
     * suspension point), so without the epoch an adoption finishing
     * a beat after a sign-out could re-store tokens and re-publish
     * a signed-in state the owner had just ended.
     */
    @Volatile
    private var flowEpoch: Long = 0L

    /** Ends the current flow generation: in-flight work goes stale. */
    private fun bumpFlowEpoch() {
        synchronized(lock) { flowEpoch++ }
    }

    /**
     * The terminal step for an attempt that ends WITHOUT a session
     * (declined / mismatched / dead code): epoch check, the
     * guarded clear, and the phase publication are ONE locked step
     * (round 9, carried from the poll loop's endings). The clear
     * fires only while the standing attempt is still this
     * attempt — a cancel + fresh start landing in the window must
     * not lose the NEWER attempt's record to this stale tail.
     */
    private fun finishAttemptWithFailure(
        epoch: Long,
        attempt: StoredPendingNativeAuth,
        reason: String,
    ) {
        synchronized(lock) {
            if (epoch != flowEpoch) return
            if (pendingAttempt?.state == attempt.state) {
                clearPendingAttempt()
            }
            _authorizationLaunch.value = null
            _phase.value = GoogleSignInPhase.Failed(reason)
        }
    }

    /**
     * Makes [attempt] the standing pending attempt — the in-memory
     * assignment AND the sealed-store save — only while [epoch]
     * is still the flow's, as ONE locked step (round 10, carried
     * from the grant discipline): check-then-persist as separate
     * statements once let a cancel landing between them be undone
     * by the persist that followed. Returns false when the epoch
     * has moved (nothing was assigned or saved). A save failure is
     * not a refusal: the attempt still completes while this
     * process lives; only the survive-a-restart guarantee is lost,
     * and the diagnostics say so rather than hiding it.
     */
    private fun persistPendingAttemptIfCurrent(
        epoch: Long,
        attempt: StoredPendingNativeAuth,
    ): Boolean {
        synchronized(lock) {
            if (epoch != flowEpoch) return false
            pendingAttempt = attempt
            try {
                pendingAttemptStore?.save(attempt)
                _pendingAttemptDurability.value = true
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _pendingAttemptDurability.value = false
                signInDiagnostics.log(
                    "google-sign-in",
                    "the sign-in attempt couldn't be saved for resume " +
                        "(${t.javaClass.simpleName}) — this attempt still " +
                        "works while the app stays open",
                )
            }
            return true
        }
    }

    init {
        // Totality (H3): a store whose read fails at the very first
        // load must read as "no session" here too, never crash the
        // container build on launch. (The store's load is itself
        // total; this is the belt to its braces, for Errors.)
        val loaded = try {
            store.load()
        } catch (t: Throwable) {
            signInDiagnostics.log(
                "store",
                "the saved Google sign-in couldn't be read (${t.javaClass.simpleName}) — starting signed out",
            )
            null
        }
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
        restorePendingAttempt()
    }

    /**
     * Re-adopts a persisted pending attempt after a restart
     * (0.8.8): the browser leg runs outside the app, so the owner
     * may well approve while no process of ours is alive — a LIVE
     * attempt is republished with its launch rebuilt, and its
     * return is still collectable. An EXPIRED attempt is cleared
     * quietly (no failure card for a browser visit the owner
     * abandoned while the app was closed — a deliberate divergence
     * from the device flow's restored-code ending, whose shown
     * code the owner might still have been typing). When a session
     * is already stored, a leftover attempt is simply stale and
     * wiped.
     */
    private fun restorePendingAttempt() {
        val persisted = try {
            pendingAttemptStore?.load()
        } catch (t: Throwable) {
            signInDiagnostics.log(
                "google-sign-in",
                "the saved sign-in attempt couldn't be read (${t.javaClass.simpleName}) — ignoring it",
            )
            null
        } ?: return
        if (stored != null) {
            clearPendingAttempt()
            return
        }
        if (!persisted.isLiveAt(nowMs())) {
            clearPendingAttempt()
            signInDiagnostics.log(
                "google-sign-in",
                "a saved sign-in attempt had expired while the app was away — cleared",
            )
            return
        }
        pendingAttempt = persisted
        // It was read back from the sealed store: durable by
        // definition — the caveat cannot apply to it.
        _pendingAttemptDurability.value = true
        signInDiagnostics.log(
            "google-sign-in",
            "a sign-in attempt from before the restart is still live — its return is being watched for",
        )
        publishAwaitingReturn(persisted)
    }

    fun hasStoredSession(): Boolean = stored != null

    // ------------------------------------------------------------------
    // Sign-in: the native authorization-code flow
    // ------------------------------------------------------------------

    /**
     * Starts a sign-in attempt — or RESUMES the one already live.
     * A new attempt is built only when no live attempt exists:
     * while a persisted attempt is unexpired, this republishes its
     * waiting state and its launch, so re-entering the screen can
     * never orphan the browser page the owner may already be
     * approving on. Building the attempt is local work (state +
     * PKCE verifier + URL) — nothing is asked of Google until the
     * owner approves on Google's page.
     */
    fun startSignIn() {
        if (_uiState.value.signedIn) return
        // A build with no Android client ID (0.8.8) cannot sign in
        // at all: end the attempt here with the honest reason
        // instead of opening a browser page that could only fail.
        if (!auth.isConfigured) {
            _phase.value = GoogleSignInPhase.Failed(NOT_CONFIGURED_REASON)
            signInDiagnostics.log(
                "google-sign-in",
                "sign-in was requested, but this build carries no " +
                    "Android client registration — nothing was asked of Google",
            )
            return
        }
        val live = livePendingAttempt()
        if (live != null) {
            signInDiagnostics.log(
                "google-sign-in",
                "a sign-in attempt is already live — resuming it instead of starting a new one",
            )
            publishAwaitingReturn(live)
            return
        }
        // An exchange is already in flight (a return landed and is
        // being collected): a fresh tap JOINS the standing flow
        // instead of minting a competing attempt (0.7.2 sweep's
        // double-tap rule, carried over).
        if (signInJob?.isActive == true) return
        val epoch = flowEpoch
        // Issuing, not Idle: the tap shows progress while Google's
        // page opens.
        _phase.value = GoogleSignInPhase.Issuing
        signInDiagnostics.startAttempt()
        signInDiagnostics.log(
            "google-sign-in",
            "native sign-in started — opening Google's page",
        )
        val request = auth.beginAuthorization(redirectUri)
        if (request == null) {
            // isConfigured said a client ID exists; begin refused
            // anyway (a blank-after-trim ID). The same honest end.
            _phase.value = GoogleSignInPhase.Failed(NOT_CONFIGURED_REASON)
            return
        }
        val now = nowMs()
        val attempt = StoredPendingNativeAuth(
            state = request.state,
            codeVerifier = request.codeVerifier,
            clientId = auth.clientId.orEmpty(),
            redirectUri = redirectUri,
            issuedAtEpochMs = now,
            expiresAtEpochMs = now + ATTEMPT_TTL_MS,
        )
        if (!persistPendingAttemptIfCurrent(epoch, attempt)) {
            // A cancel/sign-out raced the build and won: its ending
            // already published; this attempt was never persisted.
            return
        }
        publishAwaitingReturn(attempt)
    }

    /**
     * Collects the browser's return (0.8.8): the redirect activity
     * hands the full returned URI here. Validation is the core
     * client's — destination, error answer, and above all the
     * state binding, checked against the SEALED attempt, so a
     * return this app didn't start (or one replayed after the
     * attempt resolved) collects nothing. A granted code is
     * exchanged on the manager's scope and adopted through the
     * same tail every issuance path uses.
     */
    fun completeSignIn(returnedUri: String) {
        if (_uiState.value.signedIn) return
        val attempt = livePendingAttempt()
        if (attempt == null) {
            signInDiagnostics.log(
                "google-sign-in",
                "a sign-in return arrived with no live attempt behind it — ignored, nothing collected",
            )
            return
        }
        val epoch = flowEpoch
        val parsed = auth.parseRedirect(
            returnedUri, attempt.redirectUri, attempt.state,
        )
        when (parsed) {
            RedirectResult.Denied -> {
                signInDiagnostics.log(
                    "google-sign-in",
                    "the owner declined the sign-in on Google's page — nothing was stored",
                )
                finishAttemptWithFailure(epoch, attempt, DENIED_REASON)
            }
            is RedirectResult.Failed -> {
                signInDiagnostics.log(
                    "google-sign-in",
                    "the sign-in return was refused (${parsed.detail}) — nothing was stored",
                )
                finishAttemptWithFailure(
                    epoch,
                    attempt,
                    if (parsed.detail == "state_mismatch") {
                        STATE_MISMATCH_REASON
                    } else {
                        RETURN_FAILED_REASON
                    },
                )
            }
            is RedirectResult.Code -> {
                signInJob = scope.launch {
                    val exchanged = withContext(Dispatchers.IO) {
                        try {
                            auth.exchangeCode(
                                parsed.code,
                                attempt.codeVerifier,
                                attempt.redirectUri,
                            )
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            CodeExchangeResult.Failed(
                                "transport: ${t.javaClass.simpleName}"
                            )
                        }
                    }
                    when (exchanged) {
                        is CodeExchangeResult.Granted -> {
                            // The attempt is spent: clear it under
                            // the same state guard the failure
                            // endings use, then adopt — adoption's
                            // own epoch discipline decides whether
                            // the tokens may land.
                            synchronized(lock) {
                                if (pendingAttempt?.state == attempt.state) {
                                    clearPendingAttempt()
                                }
                                _authorizationLaunch.value = null
                            }
                            adopt(exchanged.tokens, epoch)
                        }
                        is CodeExchangeResult.Failed -> {
                            signInDiagnostics.log(
                                "google-sign-in",
                                "finishing the sign-in failed (${exchanged.detail}) — nothing was stored",
                            )
                            finishAttemptWithFailure(
                                epoch, attempt, EXCHANGE_FAILED_REASON,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Stops a running attempt AND abandons it: the persisted
     * record is wiped, so a cancelled attempt's return is never
     * collected later. Nothing was or will be stored for it.
     */
    fun cancelSignIn() {
        bumpFlowEpoch()
        signInJob?.cancel()
        signInJob = null
        clearPendingAttempt()
        _authorizationLaunch.value = null
        if (!_uiState.value.signedIn) {
            _phase.value = GoogleSignInPhase.Idle
        }
    }

    /** The live pending attempt, from memory or the sealed store. */
    private fun livePendingAttempt(): StoredPendingNativeAuth? {
        val inMemory = pendingAttempt
        val candidate = inMemory ?: try {
            pendingAttemptStore?.load()
        } catch (t: Throwable) {
            null
        }
        if (candidate == null) return null
        if (!candidate.isLiveAt(nowMs())) {
            // The expired clear fires only while the standing
            // attempt is still THIS candidate (round 10's rule,
            // carried over): a fresh persist landing between the
            // read and the clear must not lose its newer record.
            synchronized(lock) {
                val standing = pendingAttempt ?: try {
                    pendingAttemptStore?.load()
                } catch (t: Throwable) {
                    null
                }
                if (standing?.state == candidate.state) {
                    clearPendingAttempt()
                }
                if (pendingAttempt == null) {
                    _authorizationLaunch.value = null
                }
            }
            signInDiagnostics.log(
                "google-sign-in",
                "the saved sign-in attempt has expired — cleared",
            )
            return null
        }
        if (inMemory == null) {
            // Adopt the store-loaded candidate only while no
            // attempt has been assigned since the read, and return
            // the STANDING attempt (rounds 10–11, carried over).
            val standing = synchronized(lock) {
                if (pendingAttempt == null) pendingAttempt = candidate
                pendingAttempt
            }
            return standing ?: candidate
        }
        return candidate
    }

    /** Publishes the waiting state + browser launch for [attempt]. */
    private fun publishAwaitingReturn(attempt: StoredPendingNativeAuth) {
        _phase.value = GoogleSignInPhase.AwaitingReturn(
            expiresAtEpochMs = attempt.expiresAtEpochMs,
        )
        _authorizationLaunch.value = NativeSignInLaunch(
            url = auth.authorizationUrl(
                attempt.clientId,
                attempt.redirectUri,
                attempt.state,
                attempt.codeVerifier,
            ),
            attemptId = flowEpoch,
        )
    }

    /**
     * Drops the pending attempt everywhere (memory + sealed
     * store). Called at every terminal outcome — adopted,
     * declined, expired, cancelled, signed out — so a resolved
     * attempt can never be collected or resumed again.
     */
    private fun clearPendingAttempt() {
        pendingAttempt = null
        _pendingAttemptDurability.value = true
        try {
            pendingAttemptStore?.wipe()
        } catch (t: Throwable) {
            signInDiagnostics.log(
                "google-sign-in",
                "the saved sign-in attempt couldn't be cleared (${t.javaClass.simpleName})",
            )
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
    private suspend fun adopt(tokens: GoogleTokens, epoch: Long) {
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
        // A cancel or sign-out that landed during the identity
        // check ends the adoption here: the flow that collected
        // this grant no longer exists, and its ending path already
        // published the state the owner asked for.
        if (epoch != flowEpoch) {
            signInDiagnostics.log(
                "google-sign-in",
                "the sign-in was ended while its approval was being adopted — nothing was stored",
            )
            return
        }
        if (lookup is IdentityLookup.Rejected) {
            signInDiagnostics.log(
                "identity",
                "the just-granted token was refused (HTTP ${lookup.httpStatus}) — nothing stored",
            )
            // The publication is one locked, epoch-checked step
            // (round 8): the bare epoch re-check above is separated
            // from this assignment by the log call, and a
            // cancel/sign-out completing in between (its own phase
            // publication is a plain assignment on its thread)
            // could be overwritten by this stale Failed ending.
            // Inside the lock the check and the publish are atomic
            // against the epoch bump, as with refreshIdentity's
            // outcomes.
            synchronized(lock) {
                if (epoch == flowEpoch) {
                    _phase.value = GoogleSignInPhase.Failed(IDENTITY_REJECTED_REASON)
                }
            }
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
        // Brand capture (0.8.2): userinfo names only the bare
        // Google identity; the account's brand channel — where the
        // owner's playlists live — comes from the account menu.
        // Best-effort by design: a miss stores no delegation, and
        // signed-in browse runs its undelegated presentations. The
        // epoch is re-checked after this second fetch by the locked
        // save below, exactly as after the first.
        val brand = if (lookup is IdentityLookup.Found) {
            fetchBrandContext(tokens.accessToken)
        } else {
            null
        }
        val now = nowMs()
        val toStore = StoredGoogleSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            accessTokenExpiresAtEpochMs = now + tokens.expiresInSeconds * 1000L,
            grantedScopes = tokens.scope,
            // A userinfo answer can carry an email but no display
            // name (the core lookup reports the name as blank in
            // that shape): blank is not a name — store null, so the
            // UI falls back honestly instead of rendering "".
            identityName = selected?.name?.takeIf { it.isNotBlank() },
            identityEmail = selected?.email?.takeIf { it.isNotBlank() },
            avatarUrl = selected?.avatarUrl,
            identityPageId = brand?.pageId,
            identityAuthUser = brand?.authUser,
            // The issuing client class (0.8.6): the marker the
            // client-change gate reads at the next launch — the
            // class only, never the pair.
            // The issuing registration (0.8.8): the native
            // flow's Android client — the class the gate reads,
            // plus the client ID itself (a public identifier)
            // so a DIFFERENT Android registration also ends the
            // session once, cleanly, instead of failing refresh
            // after refresh.
            clientKind = OAuthClientKind.ANDROID.label,
            issuingClientId = auth.clientId,
            grantedAtEpochMs = now,
            lastValidatedEpochMs = if (selected != null) now else 0L,
        )
        // The save, the in-memory adoption, and the signed-in
        // publication are ONE locked step, re-checked against the
        // flow epoch (0.7.2 sweep): a sign-out racing this tail
        // either lands first (the epoch check fails — nothing is
        // stored) or lands after (it grabs and wipes the session
        // saved here). There is no interleaving where tokens are
        // re-stored or a signed-in state re-published behind a
        // completed sign-out's back.
        val saved = synchronized(lock) {
            if (epoch != flowEpoch) {
                false
            } else {
                try {
                    store.save(toStore)
                    // A session this adoption replaces leaves its
                    // token recognisable for one generation, like a
                    // refresh rotation (see [previousAccessToken]).
                    val replacedToken = stored?.accessToken
                    if (replacedToken != null && replacedToken != toStore.accessToken) {
                        previousAccessToken = replacedToken
                    }
                    stored = toStore
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
                    true
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    signInDiagnostics.log(
                        "store",
                        "the granted sign-in couldn't be saved on this device " +
                            "(${t.javaClass.simpleName}) — nothing was stored",
                    )
                    false
                }
            }
        }
        if (!saved) {
            // The same locked check-and-publish as the Rejected
            // branch above (round 8): an unlocked check here left
            // the same window for a cancel's Idle ending to be
            // overwritten by this stale store-failure ending.
            synchronized(lock) {
                if (epoch == flowEpoch) {
                    _phase.value = GoogleSignInPhase.Failed(STORE_REASON)
                }
            }
            return
        }
        signInDiagnostics.log(
            "google-sign-in",
            "sign-in complete — token sealed in the encrypted store",
        )
    }


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
    /**
     * The stored brand delegation for signed-in browse (0.8.2), or
     * null when signed out or when the account listed no brand
     * identity at adoption. Cheap and non-blocking — a plain read
     * of the standing session, no refresh: browse surfaces call it
     * beside [currentBearer] on their IO dispatchers.
     */
    fun browseDelegation(): BrowseDelegation? {
        val session = stored ?: return null
        val pageId = session.identityPageId ?: return null
        return BrowseDelegation(
            authUser = session.identityAuthUser ?: 0,
            pageId = pageId,
        )
    }

    /**
     * Re-attempts the sealed save a failed refresh left behind
     * (0.8.7 sweep 1, see [sessionSaveDirty]). Called at the head
     * of the bearer paths; a no-op unless a save is outstanding.
     */
    private fun retrySessionSave() {
        if (!sessionSaveDirty) return
        synchronized(lock) {
            if (!sessionSaveDirty) return
            val current = stored
            if (current == null) {
                // The session ended; nothing is owed to disk.
                sessionSaveDirty = false
                return
            }
            try {
                store.save(current)
                sessionSaveDirty = false
                signInDiagnostics.log(
                    "store",
                    "the refreshed token's save was retried and landed",
                )
            } catch (e: Throwable) {
                // Still dirty; the next bearer call tries again.
            }
        }
    }

    fun currentBearer(): String? {
        retrySessionSave()
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
            return refreshUnderLock(current, now, refusedToken = false)
        }
    }

    /**
     * The reactive half of token freshness (sign-in perfection
     * pass, closing the sweep's W2 open item): a surface has just
     * refused [rejectedBearer] with HTTP 401. Proactive refresh
     * alone cannot cover every death — a grant revoked at Google
     * between refreshes, or a device clock running behind so the
     * token reads locally valid long past its real expiry, both
     * leave callers holding a token Google refuses while the UI
     * still claims "signed in". The transport's Bearer retry calls
     * this once per refused request; it returns the token the
     * retry should carry, or null when no retry is honest:
     *
     * - the rejected token is the one JUST rotated away from (its
     *   request was in flight across a refresh): the standing
     *   token is the retry;
     * - the rejected token IS the standing one: one forced refresh
     *   (once per token — see [rejectionHandledForToken]), whose
     *   outcomes are the refresh outcomes: refreshed → the new
     *   token; revoked → the session ends honestly (wiped,
     *   NEEDS_SIGN_IN) and null; failed → null, session kept;
     * - anything else — above all the just-granted token inside
     *   `adopt()`, whose refusal is adoption's own Rejected
     *   verdict — is not this manager's to refresh: null.
     *
     * BLOCKING like [currentBearer]: call from an IO dispatcher.
     */
    fun bearerAfterRejection(rejectedBearer: String): String? {
        retrySessionSave()
        synchronized(lock) {
            val current = stored ?: return null
            if (rejectedBearer == previousAccessToken) {
                return current.accessToken
            }
            if (rejectedBearer != current.accessToken) {
                return null
            }
            if (rejectionHandledForToken == rejectedBearer) {
                return null
            }
            if (rejectionFailedToken == rejectedBearer &&
                nowMs() < rejectionRetryAtMs
            ) {
                return null
            }
            signInDiagnostics.log(
                "google-sign-in",
                "a request was refused with the stored access token — refreshing it once",
            )
            val refreshed =
                refreshUnderLock(current, nowMs(), refusedToken = true)
            if (refreshed != null || stored == null) {
                // Refreshed — or the session ended (revoked): the
                // rejection is definitively handled, and the
                // one-shot is spent for this token.
                rejectionHandledForToken = rejectedBearer
                rejectionFailedToken = null
            } else {
                // A transient failure spends nothing but a
                // cooldown: a later refusal of the same token may
                // try again once it lapses (0.8.7 sweep 1).
                rejectionFailedToken = rejectedBearer
                rejectionRetryAtMs = nowMs() + REJECTION_RETRY_COOLDOWN_MS
            }
            return refreshed
        }
    }

    /**
     * The refresh exchange for [current] and every ending it can
     * land on, extracted from [currentBearer] (the caller holds
     * [lock]). [refusedToken] is true when the server has just
     * REFUSED [current]'s access token (the reactive path): a
     * refused token is never handed back as a fallback, and a
     * refused token with no refresh token ends the session
     * honestly — it can never work again, and leaving it stored
     * would keep every surface claiming "signed in" over a dead
     * grant. In the proactive path both behaviours are unchanged
     * from 0.7.0: an unexpired token survives a failed refresh,
     * and only an EXPIRED unrefreshable token ends the session.
     */
    private fun refreshUnderLock(
        current: StoredGoogleSession,
        now: Long,
        refusedToken: Boolean,
    ): String? {
        val refreshToken = current.refreshToken
            ?: return if (!refusedToken && now < current.accessTokenExpiresAtEpochMs) {
                current.accessToken
            } else {
                // Unrefreshable and (expired or refused): this
                // session can never work again, and keeping it
                // stored would leave the UI claiming "signed in"
                // while every surface silently falls back (0.7.2
                // sweep). End it honestly, like a revocation —
                // the stored copy is wiped and the state lands
                // on NEEDS_SIGN_IN.
                store.wipe()
                stored = null
                previousAccessToken = null
                _uiState.value = SessionUiState(
                    signedIn = false,
                    health = SessionHealth.NEEDS_SIGN_IN,
                    lastEndReason = REVOKED_REASON,
                )
                signInDiagnostics.log(
                    "google-sign-in",
                    if (refusedToken) {
                        "the stored token was refused and no refresh token was granted — the session was ended"
                    } else {
                        "the stored token has expired and no refresh token was granted — the session was ended"
                    },
                )
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
                    sessionSaveDirty = false
                } catch (e: Throwable) {
                    // Memory takes the rotated tokens (they are
                    // the only valid ones now) and the divergence
                    // is named honestly: the STORED copy is stale,
                    // not standing (0.8.7 sweep 1).
                    sessionSaveDirty = true
                    signInDiagnostics.log(
                        "store",
                        "the refreshed token couldn't be saved (${e.javaClass.simpleName}) — " +
                            "the copy in memory is ahead of the stored one; " +
                            "the save will be retried",
                    )
                }
                // The token just rotated away from stays
                // recognisable for one generation (see
                // [bearerAfterRejection]): a request still in
                // flight with it gets the standing token as its
                // retry instead of a spurious refusal.
                previousAccessToken = current.accessToken
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
                previousAccessToken = null
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
                if (!refusedToken && nowMs() < current.accessTokenExpiresAtEpochMs) {
                    current.accessToken
                } else {
                    null
                }
            }
        }
    }

    /**
     * Best-effort brand capture (0.8.2): reads the account-menu
     * identity list and returns the delegation signed-in browse
     * should run as — the SELECTED identity when it carries a page
     * id, else the first identity that does. Null when the menu
     * can't be read or no identity carries a page id. Never throws
     * (cancellation excepted) and never fails a sign-in: presence
     * only is logged, never the channel's name or page id.
     */
    private suspend fun fetchBrandContext(bearer: String): BrowseDelegation? {
        val lookup = try {
            withContext(Dispatchers.IO) { client.accountIdentitiesOAuth(bearer) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            signInDiagnostics.log(
                "identity",
                "the account menu check failed unexpectedly " +
                    "(${t.javaClass.simpleName}) — continuing without brand context",
            )
            return null
        }
        val identities = (lookup as? IdentityLookup.Found)?.identities.orEmpty()
        // The selected brand identity wins; the first brand
        // identity is the fallback. Spelled as a plain loop: this
        // file's spelling censuses (rounds 54/55) count its
        // gateway/terminal spellings, and a loop adds none.
        var chosen: AccountIdentity? = null
        for (identity in identities) {
            if (identity.pageId == null) continue
            if (identity.isSelected) {
                chosen = identity
                break
            }
            if (chosen == null) chosen = identity
        }
        signInDiagnostics.log(
            "identity",
            when {
                chosen != null ->
                    "brand context captured — browse will run as the account's brand channel"
                // The menu attempt's own outcome, named (0.8.5):
                // before this, a refused or failed menu folded
                // into the same "no brand context" line as an
                // empty one, and the HTTP status that explained
                // the empty capture reached no diagnostics.
                lookup is IdentityLookup.Rejected ->
                    "the account menu rejected the check " +
                        "(HTTP ${lookup.httpStatus}) — browse runs undelegated"
                lookup is IdentityLookup.Failed ->
                    "the account menu check failed (${lookup.detail}) " +
                        "— browse runs undelegated"
                identities.isNotEmpty() ->
                    "the account lists ${identities.size} identities, none a brand channel " +
                        "— browse runs undelegated"
                else ->
                    "no brand context from the account menu — browse runs undelegated"
            },
        )
        val found = chosen ?: return null
        return BrowseDelegation(authUser = found.authUser, pageId = found.pageId)
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
        // Stale-outcome guard (bug sweep round 6): the identity
        // fetch below is a BLOCKING network call outside any lock,
        // and this function — unlike the sign-in flow it sits next
        // to — never captured the flow epoch. A sign-out landing
        // while the fetch was out (it bumps the epoch first, exactly
        // to stale in-flight work) was therefore invisible here: a
        // REJECTED answer for the OLD token still wiped the store
        // and published NEEDS_SIGN_IN + the revoked reason over the
        // signed-out state the owner had just chosen — and, when a
        // NEW sign-in had already landed, wiped THAT session and
        // overwrote its state; a FOUND answer merged the old
        // account's identity into whatever session was current.
        // Capture the epoch up front and let no outcome apply once
        // it has moved.
        val epochAtStart = flowEpoch
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
                // Brand re-capture (0.8.2): the same best-effort
                // account-menu read adoption performs, so a session
                // adopted before brand capture existed (or whose
                // channel set changed) gains its delegation at the
                // next validation. A miss keeps the stored one.
                val brand = fetchBrandContext(bearer)
                synchronized(lock) {
                    if (epochAtStart != flowEpoch) return
                    val current = stored ?: return
                    // A partial answer never ERASES what the store
                    // already knows (0.7.2 sweep): userinfo can
                    // answer with an email but no name or picture,
                    // and copying those blanks over the stored
                    // identity left the account header blank until
                    // the next full answer. Non-blank wins, field
                    // by field.
                    val updated = current.copy(
                        identityName = identity.name.takeIf { it.isNotBlank() }
                            ?: current.identityName,
                        identityEmail = identity.email?.takeIf { it.isNotBlank() }
                            ?: current.identityEmail,
                        avatarUrl = identity.avatarUrl?.takeIf { it.isNotBlank() }
                            ?: current.avatarUrl,
                        identityPageId = brand?.pageId ?: current.identityPageId,
                        identityAuthUser = brand?.authUser ?: current.identityAuthUser,
                        lastValidatedEpochMs = nowMs(),
                    )
                    try {
                        store.save(updated)
                    } catch (e: Throwable) {
                        signInDiagnostics.log(
                            "store",
                            "the refreshed identity couldn't be saved " +
                                "(${e.javaClass.simpleName}) — the stored copy stands",
                        )
                    }
                    stored = updated
                    // Published inside the same locked step
                    // (0.7.2 sweep): publishing after the lock from
                    // a re-read of [stored] left a window where a
                    // sign-out that landed in between was followed
                    // by a signed-in state for a wiped session.
                    _uiState.value = SessionUiState(
                        signedIn = true,
                        identityName = updated.identityName,
                        identityEmail = updated.identityEmail,
                        avatarUrl = updated.avatarUrl,
                        health = SessionHealth.HEALTHY,
                        lastEndReason = null,
                    )
                }
            }
            is IdentityLookup.Rejected -> {
                // Epoch check first (round 6, see above): a stale
                // rejection must not wipe a newer session or
                // resurrect a "revoked" ending over a deliberate
                // sign-out. Round 7: the wipe AND the publication
                // are one locked step — the round-6 shape checked
                // the epoch a third time and then published OUTSIDE
                // the lock, so a sign-out landing between that
                // check and the assignment (its epoch bump is a
                // locked step, but its own publication comes after
                // a network revocation) could still be overwritten
                // by this stale NEEDS_SIGN_IN state. Inside the
                // lock the check and the publish are atomic against
                // the bump: either this outcome lands first (and
                // the sign-out's own ending overwrites it) or the
                // bump has already happened and nothing applies.
                synchronized(lock) {
                    if (epochAtStart != flowEpoch) return
                    // A session must still STAND for its rejection
                    // to end (round 8): a refresh that started
                    // inside signOut's bump-to-wipe window captured
                    // the new epoch over the old session, and the
                    // wipe has since landed — there is nothing left
                    // to reject, and publishing the revoked ending
                    // here would overwrite the completed sign-out
                    // (see signOut's second bump, which closes the
                    // same window from the other side).
                    stored ?: return
                    store.wipe()
                    stored = null
                    _uiState.value = SessionUiState(
                        signedIn = false,
                        health = SessionHealth.NEEDS_SIGN_IN,
                        lastEndReason = REVOKED_REASON,
                    )
                }
            }
            is IdentityLookup.Failed -> {
                // One locked step (round 7), for the round-6 reason
                // above: the old shape checked the epoch, then read
                // and re-published uiState OUTSIDE the lock — a
                // sign-out landing in between left this branch
                // writing back a copy of the pre-sign-out state
                // (signedIn = true, health UNKNOWN) over the
                // signed-out state.
                synchronized(lock) {
                    if (epochAtStart != flowEpoch) return
                    val state = _uiState.value
                    if (state.signedIn) {
                        _uiState.value = state.copy(health = SessionHealth.UNKNOWN)
                    }
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
        // The epoch bump comes FIRST (0.7.2 sweep): any adoption
        // still in flight for the current flow — even one already
        // past its last suspension point, where cancelling the job
        // can no longer stop it — sees a stale generation at its
        // locked save step and stores nothing. And an adoption that
        // already completed its locked save is picked up by the
        // grab-and-wipe below, so sign-out is total either way.
        bumpFlowEpoch()
        signInJob?.cancel()
        signInJob = null
        clearPendingAttempt()
        val session = synchronized(lock) {
            // Bump AGAIN at the wipe itself (round 8): the first
            // bump and this wipe are separated by the pending-grant
            // wipe above — sealed-store IO in production — and work
            // that STARTS inside that window (a keep-alive
            // refreshIdentity, say) captures the already-bumped
            // epoch over the not-yet-wiped session, legitimately
            // passing its epoch check at apply time. This second
            // bump, atomic with the wipe, makes every such
            // in-flight outcome stale by construction: work that
            // started before the wipe sees a moved epoch, and work
            // that starts after it sees no session at all.
            flowEpoch++
            val current = stored
            store.wipe()
            stored = null
            previousAccessToken = null
            rejectionHandledForToken = null
            rejectionFailedToken = null
            rejectionRetryAtMs = 0L
            current
        }
        // The device copy is gone, so the presented account
        // clears NOW (round 61): the revocation below is a
        // blocking network call, and publishing only after it
        // returned left the old account's name and email on
        // the Settings/About account block for its whole
        // duration — on a slow or hanging network, seconds of
        // "still signed in" after the owner confirmed
        // sign-out, even though the local truth was already
        // signed-out (the diagnostics line below says the
        // same: the device copy is wiped regardless of the
        // revocation outcome). The final publish at the end
        // of this function states the same value again.
        _uiState.value = SessionUiState(
            signedIn = false,
            health = SessionHealth.UNKNOWN,
            lastEndReason = null,
        )
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
        _uiState.value = SessionUiState(
            signedIn = false,
            health = SessionHealth.UNKNOWN,
            lastEndReason = null,
        )
        _phase.value = GoogleSignInPhase.Idle
    }

    /**
     * The client-change gate (0.8.6): run once at container build
     * (before any account state is read) and again whenever the
     * live client identity is switched at runtime — the setup
     * wizard's save, or a key removal. Tokens are bound to the
     * client that issued them: a session sealed under one client
     * class can never refresh under another, so when the classes
     * differ the session ends HERE, once, cleanly — sealed store
     * wiped, signed-out state published with the honest reason —
     * instead of degrading into refresh failures the owner would
     * meet as a mystery sign-out or an error loop. The ending is
     * LOCAL only: no revocation is attempted at Google, because
     * revocation names the issuing client and this build may no
     * longer hold it; the abandoned grant simply dies unused,
     * which is the outcome the owner asked for by switching.
     *
     * Always logs the one diagnostics line naming the client class
     * in use (`signin: client=owner` / `client=shared` /
     * `client=unconfigured`) — the class only, never a value.
     *
     * [forceCleanResign] covers the pair-level change the class
     * gate cannot see: the wizard saving a DIFFERENT owner pair
     * over a standing one (same class, different registration —
     * the sealed tokens name the old registration and cannot
     * refresh under the new one either).
     */

    /**
     * The client-change gate (0.8.6; native form 0.8.8): run once
     * at container build (before any account state is read) and
     * again whenever the live client identity changes. Tokens are
     * bound to the client that issued them: a session sealed under
     * a different registration than this build's Android client —
     * a device-flow class (owner / shared / pre-marker), or a
     * DIFFERENT Android client ID — can never refresh here, so it
     * ends HERE, once, cleanly: sealed store wiped, signed-out
     * state published with the honest reason, instead of degrading
     * into refresh failures the owner would meet as a mystery
     * sign-out or an error loop. The ending is LOCAL only: no
     * revocation is attempted at Google, because revocation names
     * the issuing client and this build may no longer hold it; the
     * abandoned grant simply dies unused.
     *
     * The kind-level verdict is the core gate's
     * ([ClientChangeGate.decide] — its matrix is pinned by core
     * tests); this manager adds the identity compare the kind
     * alone cannot see (0.8.7 sweep 1's B-H3 rule): an android
     * session carries only when its recorded issuing client ID IS
     * this build's client ID.
     *
     * Always logs the one diagnostics line naming the client class
     * in use (`signin: client=android` / `client=unconfigured`) —
     * the class only, never a value.
     *
     * [forceCleanResign] covers a same-class identity change a
     * caller has already established — kept from the 0.8.6 gate
     * for parity; the native build's identity is fixed at build
     * time, so in practice the compare above decides.
     */
    fun applyClientGate(forceCleanResign: Boolean = false) {
        val currentClientId = auth.clientId?.takeIf { it.isNotBlank() }
        signInDiagnostics.log(
            "signin",
            signInClientLine(
                if (currentClientId != null) {
                    OAuthClientKind.ANDROID
                } else {
                    null
                }
            ),
        )
        // The in-flight half of this gate is at the bottom: when
        // nothing stands and nothing is saved, the epoch moves so
        // an exchange blocked in its network call — which has
        // persisted nothing yet — cannot land afterwards stamped
        // with the NEW client's class (the zombie-code shape,
        // 0.8.7 sweep 1). A standing session or attempt is handled
        // by the branches below, and a resumed attempt's return
        // (same registration, nothing changed) is never
        // epoch-killed here.
        val session = synchronized(lock) { stored }
        if (session != null) {
            val kindDecision = ClientChangeGate.decide(
                session.clientKind,
                if (currentClientId != null) {
                    OAuthClientKind.ANDROID
                } else {
                    null
                },
            )
            val decision = when {
                forceCleanResign -> ClientGateDecision.CLEAN_RESIGN
                kindDecision != ClientGateDecision.CARRY ->
                    kindDecision
                session.issuingClientId == currentClientId ->
                    ClientGateDecision.CARRY
                else -> ClientGateDecision.CLEAN_RESIGN
            }
            if (decision != ClientGateDecision.CARRY) {
                endSessionForClientChange(
                    if (decision == ClientGateDecision.CLEAR_UNCONFIGURED) {
                        UNCONFIGURED_END_REASON
                    } else {
                        CLIENT_CHANGED_REASON
                    },
                )
                return
            }
        }
        // No session stands (or it carries): a leftover pending
        // attempt minted under another registration can never be
        // collected under this one — clear it rather than watching
        // for a return whose exchange could only fail.
        val attempt = pendingAttempt
        if (attempt != null &&
            (forceCleanResign || attempt.clientId != currentClientId)
        ) {
            bumpFlowEpoch()
            signInJob?.cancel()
            signInJob = null
            clearPendingAttempt()
            _authorizationLaunch.value = null
            _phase.value = GoogleSignInPhase.Idle
            signInDiagnostics.log(
                "google-sign-in",
                "a saved sign-in attempt from the previous sign-in " +
                    "setup was cleared — it could never be " +
                    "collected under this one",
            )
        }
        if (session == null && pendingAttempt == null) {
            // Nothing stands and nothing is saved: anything still
            // in flight was minted under the client declaration
            // this gate just replaced — move the epoch so it can
            // neither persist nor publish when it lands.
            bumpFlowEpoch()
        }
    }

    /**
     * The gate's clean ending, shaped like sign-out's teardown
     * minus the revocation: invalidate everything in flight, wipe
     * the sealed session, and publish the signed-out state WITH
     * its reason, once — the sign-in screen shows the reason until
     * the owner signs in again, which is the whole of the
     * "clean re-sign-in" contract.
     */
    private fun endSessionForClientChange(reason: String) {
        bumpFlowEpoch()
        signInJob?.cancel()
        signInJob = null
        clearPendingAttempt()
        _authorizationLaunch.value = null
        synchronized(lock) {
            // Atomic with the wipe, as at sign-out (round 8): work
            // that started before it sees a moved epoch, and work
            // that starts after it sees no session at all.
            flowEpoch++
            store.wipe()
            stored = null
            previousAccessToken = null
            rejectionHandledForToken = null
            rejectionFailedToken = null
            rejectionRetryAtMs = 0L
        }
        _uiState.value = SessionUiState(
            signedIn = false,
            health = SessionHealth.UNKNOWN,
            lastEndReason = reason,
        )
        _phase.value = GoogleSignInPhase.Idle
        signInDiagnostics.log(
            "google-sign-in",
            "the saved sign-in was ended because the sign-in setup " +
                "changed — sign in again to reconnect",
        )
    }

    private companion object {
        /** Refresh when the access token has less than this left. */
        const val REFRESH_SKEW_MS = 60_000L

        /**
         * How long a native sign-in attempt stays collectable:
         * the browser leg is a minute of the owner's time, but the
         * app may sit dead for most of it (the owner approving
         * slowly, the phone busy) — thirty minutes matches the
         * device flow's grant life, and past it the return's code
         * would be dying at Google anyway.
         */
        const val ATTEMPT_TTL_MS = 30L * 60L * 1000L

        /**
         * How long a transiently failed forced refresh waits
         * before the same refused token may spend another attempt
         * (0.8.7 sweep 1, see [bearerAfterRejection]).
         */
        const val REJECTION_RETRY_COOLDOWN_MS = 60_000L

        const val DENIED_REASON =
            "You closed or declined the sign-in on Google's page, " +
                "so no sign-in was created and nothing was stored. " +
                "Start again whenever you're ready."

        const val STATE_MISMATCH_REASON =
            "The answer that came back from Google doesn't match " +
                "the sign-in this app started, so it was refused " +
                "and nothing was stored. Start again to sign in."

        const val RETURN_FAILED_REASON =
            "Google's answer couldn't be used to finish the " +
                "sign-in, and nothing was stored. Start again " +
                "to sign in."

        const val EXCHANGE_FAILED_REASON =
            "Google approved the sign-in, but finishing it " +
                "failed — the connection or Google's answer let " +
                "it down. Nothing was stored. Start again to " +
                "sign in."

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

        /**
         * The client-change gate's landing copy (0.8.6): the
         * session ended because the sign-in setup changed — one
         * client registration replaced another. Plain about what
         * happened and what to do; shown on the sign-in screen
         * until the next sign-in.
         */
        const val CLIENT_CHANGED_REASON =
            "You were signed out because OffLeash's sign-in " +
                "changed. Sign in again to reconnect your account. " +
                "Your playlists and downloads on this device are " +
                "untouched."

        /**
         * The gate's landing copy when the build carries no
         * sign-in at all (0.8.6; native wording 0.8.8): the sealed
         * session could never refresh here.
         */
        const val UNCONFIGURED_END_REASON =
            "You were signed out because this build of OffLeash " +
                "has no Google sign-in set up, so the saved sign-in " +
                "could never be used here. Your playlists and " +
                "downloads on this device are untouched."

        /**
         * The phase copy when a sign-in is attempted in a build
         * with no client registration (0.8.6; native wording
         * 0.8.8): the attempt ends before anything is asked of
         * Google, saying exactly why — and naming no homework,
         * because there is none the owner can do in the app.
         */
        const val NOT_CONFIGURED_REASON =
            "Google sign-in isn't set up in this build of " +
                "OffLeash yet, so there's nothing to sign in " +
                "with. Everything else in the app works as normal."

        const val UNEXPECTED_REASON =
            "Something went wrong during Google sign-in. " +
                "Nothing was saved. Try signing in again."
    }
}
