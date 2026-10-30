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
     * Publishes [phase] only while [epoch] is still the flow's —
     * check and publish are ONE locked step (bug sweep round 9).
     * The branches that use this used to epoch-check and then
     * publish as two adjacent statements: a cancel/sign-out
     * landing between them (its bump takes this same lock) had
     * its ending overwritten by the stale publication anyway —
     * the check-then-apply shape rounds 7–8 closed in
     * refreshIdentity and adopt, still open in the poll loop's
     * non-clearing tails and runSignIn's failure tails.
     */
    private fun publishPhaseIfCurrent(epoch: Long, phase: GoogleSignInPhase) {
        synchronized(lock) {
            if (epoch == flowEpoch) {
                _phase.value = phase
            }
        }
    }

    /**
     * The terminal step for a poll of [grant] that ends WITHOUT a
     * session (expired / denied): epoch check, the guarded clear,
     * and the phase publication are ONE locked step (round 9).
     * Round 8 gave the Granted branch a deviceCode-guarded clear,
     * but these branches still epoch-checked, cleared, and
     * published as separate statements — a cancel + fresh start
     * landing in the window let the stale tail's clear wipe the
     * NEWER grant's record and its Failed publication overwrite
     * the new flow's awaiting state. The clear fires only while
     * the standing grant is still this poll's grant, exactly as
     * in the Granted and SlowDown branches.
     */
    private fun finishPollWithFailure(epoch: Long, grant: StoredPendingGrant, reason: String) {
        synchronized(lock) {
            if (epoch != flowEpoch) return
            if (pendingGrant?.deviceCode == grant.deviceCode) {
                clearPendingGrant()
            }
            _phase.value = GoogleSignInPhase.Failed(reason)
        }
    }

    /**
     * Makes [grant] the standing pending grant — the in-memory
     * assignment AND the sealed-store save — only while [epoch]
     * is still the flow's, as ONE locked step (bug sweep round
     * 10). Round 7 moved runSignIn's epoch check to before the
     * persist, but check and persist remained two statements: a
     * cancel/sign-out landing between them (its bump takes this
     * same lock; its clear had nothing to wipe yet) was undone by
     * the assignment and save that followed anyway — the
     * cancelled code stood again in memory and in the sealed
     * store, and while the locked publish below correctly refused
     * to show it, the NEXT startSignIn (via [livePendingGrant])
     * or a fresh manager after process death (via
     * [restorePendingGrant]) revived the flow the owner had
     * cancelled, breaking cancelSignIn's "a cancelled code is
     * never resumed" contract one step later. Inside the lock the
     * ending is atomic against the bump, as with every other
     * apply in this file. Returns false when the epoch has moved
     * (nothing was assigned or saved). A save failure is not a
     * refusal: the attempt still works while this process lives;
     * only the survive-a-restart guarantee is lost, and the
     * diagnostics say so rather than hiding it.
     */
    private fun persistPendingGrantIfCurrent(epoch: Long, grant: StoredPendingGrant): Boolean {
        synchronized(lock) {
            if (epoch != flowEpoch) return false
            pendingGrant = grant
            try {
                pendingGrantStore?.save(grant)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                signInDiagnostics.log(
                    "google-sign-in",
                    "the sign-in code couldn't be saved for resume " +
                        "(${t.javaClass.simpleName}) — this attempt still " +
                        "works while the app stays open",
                )
            }
            return true
        }
    }

    /**
     * The poll loop's Granted and SlowDown branches and
     * [livePendingGrant]'s expired-candidate clear apply the same
     * discipline inline (bug sweep round 10): their deviceCode
     * guard and the clear/write-back they protect are one
     * `synchronized(lock)` step at each site, never a read followed
     * by an unconditional apply — a cancel + fresh start landing
     * between the two statements would otherwise let a stale tail
     * wipe or replace the NEWER grant's record.
     */

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
        // An issuance is already in flight (a job is running but no
        // grant exists yet): a second tap JOINS it instead of
        // cancelling and re-issuing (0.7.2 sweep). The cancelled
        // attempt's blocking issuance still completed at Google,
        // minting a live grant nobody recorded or would ever poll —
        // and a double-tap is exactly how owners tap buttons.
        if (signInJob?.isActive == true) return
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
        bumpFlowEpoch()
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
            // The expired clear fires only while the standing
            // grant is still THIS candidate (round 10): the read
            // above and an unconditional clear were two statements,
            // and a fresh persist landing between them would have
            // its newer record wiped by this stale expiry.
            synchronized(lock) {
                val standing = pendingGrant ?: try {
                    pendingGrantStore?.load()
                } catch (t: Throwable) {
                    null
                }
                if (standing?.deviceCode == candidate.deviceCode) {
                    clearPendingGrant()
                }
            }
            signInDiagnostics.log(
                "google-sign-in",
                "the saved sign-in code has expired — cleared",
            )
            return null
        }
        if (inMemory == null) {
            // Adopt the store-loaded candidate only while no
            // grant has been assigned since the read (round 10):
            // overwriting a newer in-memory grant with this older
            // candidate would republish and poll a superseded code.
            // And return the STANDING grant, not the candidate
            // (round 11): a fresh persist landing between the read
            // and this lock leaves the memory correct under the
            // round-10 guard, but the old unconditional return
            // handed the caller the stale candidate anyway — the
            // screen published its code while the poll loop ran
            // the newer grant, so an approval of the shown code
            // would never have been collected.
            val standing = synchronized(lock) {
                if (pendingGrant == null) pendingGrant = candidate
                pendingGrant
            }
            return standing ?: candidate
        }
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
        val epoch = flowEpoch
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
                    // A cancel/sign-out that landed while the code
                    // was being issued already published the ending
                    // it wanted (round 7): this stale refusal must
                    // not overwrite it. The check and the publish
                    // are one locked step (round 9) — as two
                    // statements, a cancel landing between them was
                    // overwritten by this Failed ending anyway.
                    publishPhaseIfCurrent(
                        epoch,
                        GoogleSignInPhase.Failed(
                            "Google couldn't start the sign-in (${issued.detail}). " +
                                "Nothing was stored. Check your connection and try again."
                        ),
                    )
                    return
                }
                is DeviceCodeResult.Issued -> issued.code
            }
            // The grant is persisted only inside a locked,
            // epoch-checked step (rounds 7 + 10): round 7 moved
            // the epoch check ahead of the persist, but as two
            // statements a cancel/sign-out landing between the
            // check and the assignment/save below still undid
            // itself — the cancelled code stood again in memory
            // and in the sealed store, and a later startSignIn or
            // a fresh manager after process death revived it (see
            // [persistPendingGrantIfCurrent]). The check and the
            // persist are now one locked step against the bump,
            // like every other apply in this file.
            val now = nowMs()
            val grant = StoredPendingGrant(
                deviceCode = code.deviceCode,
                userCode = code.userCode,
                verificationUrl = code.verificationUrl,
                issuedAtEpochMs = now,
                expiresAtEpochMs = now + code.expiresInSeconds * 1000L,
                intervalSeconds = code.intervalSeconds,
            )
            if (!persistPendingGrantIfCurrent(epoch, grant)) {
                signInDiagnostics.log(
                    "google-sign-in",
                    "the sign-in was ended while its code was being issued — nothing was stored",
                )
                return
            }
            signInDiagnostics.log(
                "google-sign-in",
                "code issued — polling every ${code.intervalSeconds}s, " +
                    "expires in ${code.expiresInSeconds}s",
            )
            // A cancel/sign-out that landed while the code was
            // being issued (or saved) ends this attempt silently:
            // the grant record was already wiped by that path, and
            // publishing its code now would resurrect a flow the
            // owner just abandoned. (Death in the same window is
            // harmless by ordering: the code is only ever PUBLISHED
            // after the persist attempt, so a code the owner never
            // saw can never be approved — it simply expires at
            // Google, unrecorded.)
            // The re-check and the publication are one locked
            // step (round 9), for the same reason as the failure
            // tail above: a cancel landing between a bare check
            // and this publish had its Idle ending overwritten by
            // the awaiting state of a grant it had just wiped —
            // a dead code on screen, with its poll never running
            // (pollPendingGrant below reads the wiped grant and
            // returns). Inside the lock the ending is atomic
            // against the bump.
            synchronized(lock) {
                if (epoch != flowEpoch) return
                publishAwaiting(grant)
            }
            pollPendingGrant()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            signInDiagnostics.log(
                "google-sign-in",
                "the attempt failed unexpectedly (${t.javaClass.simpleName}) — nothing was stored",
            )
            publishPhaseIfCurrent(epoch, GoogleSignInPhase.Failed(UNEXPECTED_REASON))
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
        val epoch = flowEpoch
        try {
            var intervalSeconds = grant.intervalSeconds.coerceAtLeast(1L)
            while (true) {
                // Park until the next due poll — or wake at once on
                // a "check now" signal, coalesced by the channel.
                // The park is capped at the grant's remaining life
                // (0.7.2 sweep): with a long poll interval, parking
                // the full interval would sleep straight past the
                // expiry and leave a dead code on screen for hours.
                val remainingMs = (grant.expiresAtEpochMs - nowMs()).coerceAtLeast(0L)
                withTimeoutOrNull(minOf(intervalSeconds * 1000L, remainingMs)) {
                    checkNowSignal.receive()
                }
                if (nowMs() >= grant.expiresAtEpochMs) {
                    signInDiagnostics.log("google-sign-in", "the code expired unapproved")
                    // Terminal outcomes apply only while this loop
                    // still owns the flow — as ONE locked step
                    // (round 7's rule, round 9's shape): after a
                    // cancel/sign-out, clearing could wipe a NEWER
                    // grant and the phase overwrite would undo the
                    // ending that path already published, and a
                    // check-then-clear as separate statements left
                    // exactly that window open between them.
                    finishPollWithFailure(epoch, grant, EXPIRED_REASON)
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
                        // so no path can ever poll it again. Death
                        // inside the adoption that follows loses
                        // the approval (the owner signs in again) —
                        // the alternative ordering resurrects a
                        // spent grant after restart, which can be
                        // polled against an invalid_grant answer
                        // forever; a clean re-sign-in is the honest
                        // failure, a zombie grant is not.
                        // The clear is guarded by the grant's own
                        // identity (round 8 — the SlowDown branch's
                        // discipline, which this branch alone
                        // lacked): every other terminal branch
                        // epoch-checks before clearing, but this one
                        // cleared unconditionally ahead of adopt()'s
                        // check, so a cancel + fresh start landing
                        // in this tail would have the SPENT grant's
                        // clear wipe the NEWER grant's record —
                        // silently breaking the new flow's
                        // resume-after-restart guarantee. Clearing
                        // only while the standing grant is still
                        // THIS one keeps the spent-grant discipline
                        // and touches nothing newer.
                        // The guarded clear is one locked step
                        // (round 10): round 8's shape read the
                        // standing grant's deviceCode and cleared
                        // as a second statement, so a cancel +
                        // fresh start landing between them would
                        // have this spent grant's clear wipe the
                        // NEWER grant's record after all.
                        synchronized(lock) {
                            if (pendingGrant?.deviceCode == grant.deviceCode) {
                                clearPendingGrant()
                            }
                        }
                        adopt(poll.tokens, epoch)
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
                        // The longer interval is persisted with the
                        // grant (0.7.2 sweep): a restart mid-slow-down
                        // used to resume at the ORIGINAL cadence —
                        // the one Google had just refused — and earn
                        // another slow_down (or worse) on its first
                        // poll.
                        val slowed = grant.copy(intervalSeconds = intervalSeconds)
                        // The guarded write-back is one locked step
                        // (round 10), for the Granted branch's reason
                        // above: check, assign, and save as separate
                        // statements left a window where a cancel +
                        // fresh start's newer grant could be replaced
                        // by this stale slowed copy.
                        synchronized(lock) {
                            if (pendingGrant?.deviceCode == grant.deviceCode) {
                                pendingGrant = slowed
                                try {
                                    pendingGrantStore?.save(slowed)
                                } catch (t: Throwable) {
                                    if (t is CancellationException) throw t
                                    signInDiagnostics.log(
                                        "google-sign-in",
                                        "the slower poll interval couldn't be saved " +
                                            "(${t.javaClass.simpleName}) — this attempt still uses it",
                                    )
                                }
                            }
                        }
                    }
                    TokenPollResult.Denied -> {
                        signInDiagnostics.log("google-sign-in", "poll: declined by the owner")
                        finishPollWithFailure(epoch, grant, DENIED_REASON)
                        return
                    }
                    TokenPollResult.Expired -> {
                        signInDiagnostics.log("google-sign-in", "poll: the code expired")
                        finishPollWithFailure(epoch, grant, EXPIRED_REASON)
                        return
                    }
                    TokenPollResult.InvalidGrant -> {
                        // Terminal like Expired (perfection pass):
                        // Google says this code no longer stands —
                        // keeping it would park the owner on a
                        // zombie code whose failure copy claims it
                        // "is still good until it expires".
                        signInDiagnostics.log(
                            "google-sign-in",
                            "poll: the code is no longer valid at Google",
                        )
                        finishPollWithFailure(epoch, grant, INVALID_GRANT_REASON)
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
                        publishPhaseIfCurrent(
                            epoch,
                            GoogleSignInPhase.Failed(
                                "Couldn't finish checking with Google (${poll.detail}). " +
                                    "Nothing was stored, but your code is still good " +
                                    "until it expires — try again to keep checking for it."
                            ),
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
            publishPhaseIfCurrent(epoch, GoogleSignInPhase.Failed(UNEXPECTED_REASON))
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
            rejectionHandledForToken = rejectedBearer
            signInDiagnostics.log(
                "google-sign-in",
                "a request was refused with the stored access token — refreshing it once",
            )
            return refreshUnderLock(current, nowMs(), refusedToken = true)
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
                } catch (e: Throwable) {
                    signInDiagnostics.log(
                        "store",
                        "the refreshed token couldn't be saved (${e.javaClass.simpleName}) — " +
                            "the stored copy stands",
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
        clearPendingGrant()
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
            "That code expired before it was approved. " +
                "Start again to get a fresh code."
        // The perfection pass dropped this ending's old "a code
        // lasts about 30 minutes" clause: the grant's real life is
        // whatever Google issued (expires_in rides the grant and
        // drives the waiting screen's countdown) — a hardcoded
        // duration in the ending would lie for any other value.

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

        const val INVALID_GRANT_REASON =
            "Google cancelled that code before it was approved, so " +
                "it can't be used anymore. Nothing was stored. " +
                "Start again to get a fresh code."
    }
}
