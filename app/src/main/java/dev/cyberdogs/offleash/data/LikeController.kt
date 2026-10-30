package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.LikeStatus
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.playback.LikeUiState
import dev.cyberdogs.offleash.playback.likeToggleSucceeded
import dev.cyberdogs.offleash.playback.optimisticLikeToggle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns like state for every track surface (Lane G).
 *
 * Signed in, a toggle writes to the account through the core client's
 * `setLike` (WEB_REMIX `like/like` / `like/removelike`) with an
 * optimistic UI flip and an honest rollback — state restored plus a
 * named [error] — when the write fails. Signed out, a toggle only
 * updates the on-device record in [AppPreferences], and the UI labels
 * it device-only (see `likeActionLabel`); it never pretends to touch
 * an account.
 *
 * The per-track state starts from the on-device record. The player
 * additionally refreshes the CURRENT track's state from the account
 * (`likeStatus`) when signed in, so a like set elsewhere shows up
 * without the user toggling first. List rows do not refresh per row —
 * one player call per rendered row would flood the API.
 */
class LikeController(
    private val client: YoutubeiClient,
    private val preferences: AppPreferences,
    private val sessionProvider: () -> AuthSession?,
    /**
     * The Google OAuth access token (0.7.0). IO THREADS ONLY — the
     * provider may refresh the token, which is blocking network
     * work. When it yields a token, that token is the account road
     * for likes, ahead of the cookie session. [bearerSessionPresent]
     * is its cheap companion (a stored Google session exists), safe
     * on any thread, for the branch decisions that must not block.
     * Both default to none so existing constructions (and the
     * cookie road alone) behave exactly as before.
     */
    private val bearerProvider: () -> String? = { null },
    private val bearerSessionPresent: () -> Boolean = { false },
    private val scope: CoroutineScope,
) {

    private val _states = MutableStateFlow<Map<String, LikeUiState>>(
        preferences.localLikedVideoIds().associateWith { videoId ->
            LikeUiState(liked = true, accountBacked = sessionProvider() != null)
        },
    )
    val states: StateFlow<Map<String, LikeUiState>> = _states.asStateFlow()

    private val _accountBacked = MutableStateFlow(sessionProvider() != null)
    /** True while a session exists — like writes go to the account. */
    val accountBacked: StateFlow<Boolean> = _accountBacked.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Plain-language copy for the last failed like write, or null. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Bumped under [intentLock] on every account-session flip
     * (sign-out, or a new sign-in), driven by [setAccountBacked] —
     * the container's sign-in-state callback (bug sweep round 6,
     * closing the r6b census flag). [refreshAccountStatus] captures
     * it with the intent version before its fetch: a report
     * authorized by a session that has since ended — or been
     * replaced — must not apply under whatever session (or none)
     * is current when it lands: the intent-version guard alone
     * cannot see a sign-out, because signing out is not a toggle.
     * Without this, a report landing after sign-out resurrected
     * accountBacked over the state the owner had just chosen and
     * persisted the old account's like into the device-local
     * record. Dropping costs nothing: the next refresh reads the
     * current account fresh.
     */
    private var sessionGeneration = 0L

    /**
     * Tracks seeded in the CURRENT session generation by
     * [noteAccountLiked] (bug sweep round 37). A seed is one
     * account's Liked page asserting its likes: unlike a toggle
     * (the user's own intent, written to whichever account was
     * live) or a device-local like (the device's own record), a
     * seeded entry has no meaning under any other session, so a
     * session flip drops it — entry, confirmed value, and its
     * device-local record — instead of letting the previous
     * account's likes present as the new account's. A toggle or
     * an applied refresh report takes over an entry's provenance
     * (both remove it from this set under [intentLock]).
     *
     * Round 38: the set is persisted beside the local record
     * ([AppPreferences.seededLikedVideoIds]) and restored here,
     * intersected with the record it describes. Round 37 kept it
     * in memory only, but the seed itself persists the seeded id
     * into the device-local record — after a process restart the
     * relaunched controller loaded that id as an ordinary
     * device-local entry with an empty provenance set, the flip
     * drop never fired, and the previous account's seeded like
     * presented as the new account's exactly as in round 37's
     * in-session leak. Every mutation of the set is persisted
     * with it. Round 39: the record/provenance pair is
     * persisted as ONE stored value ([persistLikeState]) —
     * as two writes, a process death between them split
     * the pair in whichever direction the path wrote.
     */
    private val seededVideoIds: MutableSet<String> =
        preferences.seededLikedVideoIds()
            .intersect(preferences.localLikedVideoIds())
            .toMutableSet()

    /** Called by the container whenever sign-in state flips. */
    fun setAccountBacked(backed: Boolean) {
        var droppedSeed = false
        var clearedSeedProvenance = false
        synchronized(intentLock) {
            if (_accountBacked.value != backed) {
                sessionGeneration += 1L
                if (seededVideoIds.isNotEmpty()) {
                    val remaining = _states.value - seededVideoIds
                    if (remaining.size != _states.value.size) {
                        _states.value = remaining
                        droppedSeed = true
                    }
                    for (videoId in seededVideoIds) {
                        confirmedLiked.remove(videoId)
                    }
                    seededVideoIds.clear()
                    clearedSeedProvenance = true
                }
            }
        }
        _accountBacked.value = backed
        _states.value = _states.value.mapValues { (_, state) ->
            state.copy(accountBacked = backed)
        }
        if (droppedSeed || clearedSeedProvenance) persistLikeState()
    }

    fun stateFor(videoId: String): LikeUiState =
        _states.value[videoId]
            ?: LikeUiState(liked = false, accountBacked = _accountBacked.value)

    fun isLiked(videoId: String): Boolean = stateFor(videoId).liked

    fun clearError() {
        _error.value = null
    }

    // -- Serialized per-track write drain (bug sweep round 1) -------
    //
    // Rapid toggling used to drop every tap after the first while a
    // write was in flight, so like→unlike ended LIKED on the account
    // against the user's last intent. Now every tap flips the
    // optimistic state (the newest intent), and one drain per track
    // writes intents in order until the account matches the UI. The
    // flip-and-maybe-start in toggle() and the drain's exit check
    // are both under [intentLock], so an intent flipped while the
    // drain is deciding to exit can never be orphaned.

    private val intentLock = Any()
    private val drainsInFlight = mutableSetOf<String>()

    /** The last value the account is known to hold, per track. */
    private val confirmedLiked = mutableMapOf<String, Boolean>()

    /**
     * A per-track counter bumped on EVERY toggle intent, under
     * [intentLock] (bug sweep round 6). [refreshAccountStatus]
     * captures it before its fetch starts and may apply its report
     * only if the counter is unchanged when the report lands: a
     * report fetched before a toggle carries that toggle's
     * PRE-state, and the round-1 `pending` guard alone could not
     * stop it once the toggle's drain had completed and cleared
     * `pending` — the stale report then flipped the UI back,
     * rewrote the local record, and overwrote [confirmedLiked]
     * (which it also wrote outside [intentLock]) while the account
     * actually held the toggle's result. Dropping the report is
     * always safe: a refresh is repeatable, and the next one reads
     * the account fresh.
     */
    private val intentVersions = mutableMapOf<String, Long>()

    fun toggle(track: Track) {
        val videoId = track.videoId
        val auth = sessionProvider()
        if (auth == null && !bearerSessionPresent()) {
            // Device-only like: persist locally and finish — there is
            // no account write to confirm or roll back.
            synchronized(intentLock) {
                intentVersions[videoId] = (intentVersions[videoId] ?: 0L) + 1L
                seededVideoIds.remove(videoId)
            }
            val optimistic = optimisticLikeToggle(stateFor(videoId))
            update(videoId, likeToggleSucceeded(optimistic))
            persistLikeState()
            return
        }
        var startDrain = false
        var tookOverSeed = false
        synchronized(intentLock) {
            intentVersions[videoId] = (intentVersions[videoId] ?: 0L) + 1L
            tookOverSeed = seededVideoIds.remove(videoId)
            val current = stateFor(videoId)
            update(videoId, optimisticLikeToggle(current))
            if (drainsInFlight.add(videoId)) {
                // No drain running: the pre-toggle value is the last
                // account-confirmed one this sequence starts from.
                confirmedLiked.putIfAbsent(videoId, current.liked)
                startDrain = true
            }
        }
        if (tookOverSeed) persistLikeState()
        if (startDrain) {
            scope.launch { drainWrites(track, auth) }
        }
    }

    /**
     * Writes the track's intents to the account until the account
     * holds the UI's current value. A failure rolls the UI back to
     * the last account-confirmed value (queued intents are dropped
     * with it — the account never held them) and names the error.
     */
    private suspend fun drainWrites(track: Track, auth: AuthSession?) {
        val videoId = track.videoId
        while (true) {
            val target = stateFor(videoId).liked
            // The bearer is read HERE, on the IO thread: obtaining it
            // can mean a token refresh, and a UI-thread toggle must
            // never block on the network.
            var wroteToAccount = false
            val failure = try {
                withContext(Dispatchers.IO) {
                    val bearer = bearerProvider()
                    when {
                        bearer != null -> {
                            client.setLikeOAuth(videoId, bearer, target)
                            wroteToAccount = true
                        }
                        auth != null -> {
                            client.setLike(videoId, auth, target)
                            wroteToAccount = true
                        }
                        else -> Unit // the session ended mid-toggle: device-only outcome
                    }
                }
                null
            } catch (e: Exception) {
                e
            }
            if (failure == null && !wroteToAccount) {
                synchronized(intentLock) {
                    drainsInFlight.remove(videoId)
                    update(videoId, likeToggleSucceeded(stateFor(videoId)))
                }
                persistLikeState()
                return
            }
            if (failure != null) {
                synchronized(intentLock) {
                    drainsInFlight.remove(videoId)
                    val confirmed = confirmedLiked[videoId] ?: !target
                    update(
                        videoId,
                        stateFor(videoId).copy(liked = confirmed, pending = false),
                    )
                }
                persistLikeState()
                _error.value =
                    "Couldn't update your like on YouTube — check your connection and try again."
                return
            }
            var finished = false
            synchronized(intentLock) {
                confirmedLiked[videoId] = target
                if (stateFor(videoId).liked == target) {
                    // The account now holds the newest intent.
                    drainsInFlight.remove(videoId)
                    update(videoId, likeToggleSucceeded(stateFor(videoId)))
                    finished = true
                }
            }
            if (finished) {
                persistLikeState()
                return
            }
            // A newer intent landed while this write was in flight:
            // loop and write it too — last intent wins.
        }
    }

    /**
     * Refreshes one track's state from the account's own report
     * (`likeStatus`). Signed out, or when the response carries no
     * likeStatus (an honest unknown), the current state stands.
     */
    fun refreshAccountStatus(track: Track) {
        val auth = sessionProvider()
        if (auth == null && !bearerSessionPresent()) return
        // Captured BEFORE the fetch can start (the fetch lives in
        // the coroutine launched below), so any toggle that lands
        // while the fetch is in flight — or after it, before the
        // apply — is a version this report predates (round 6).
        // The session generation rides along: a sign-out (or a
        // replacement sign-in) during the fetch is a flip this
        // report also predates, and no toggle is involved.
        val guardAtFetchStart = synchronized(intentLock) {
            (intentVersions[track.videoId] ?: 0L) to sessionGeneration
        }
        scope.launch {
            val status = try {
                withContext(Dispatchers.IO) {
                    // Bearer read on the IO thread, as in toggle().
                    val bearer = bearerProvider()
                    when {
                        bearer != null -> client.likeStatusOAuth(track.videoId, bearer)
                        auth != null -> client.likeStatus(track.videoId, auth)
                        else -> null
                    }
                }
            } catch (e: Exception) {
                null
            }
            // A toggle write in flight outranks this report: the
            // player response may predate the write, and applying it
            // would flip the UI against the intent being written
            // (bug sweep round 1). The drain's own completion is the
            // fresher truth; the next refresh settles the rest.
            //
            // Round 6: `pending` alone was not enough — it is
            // cleared when the drain COMPLETES, and a report whose
            // fetch started before the toggle can be delivered
            // after that, carrying the toggle's pre-state. The
            // version check and the state apply therefore happen
            // together under [intentLock] (the same lock the toggle
            // bumps the version and the drain mutates state under),
            // so no toggle can slip between the check and the
            // apply, and [confirmedLiked] is never written outside
            // the lock again. A dropped report costs nothing: the
            // next refresh reads the account fresh.
            val applied = synchronized(intentLock) {
                if ((intentVersions[track.videoId] ?: 0L) != guardAtFetchStart.first ||
                    sessionGeneration != guardAtFetchStart.second
                ) {
                    false
                } else if (stateFor(track.videoId).pending) {
                    false
                } else {
                    when (status) {
                        LikeStatus.LIKED -> {
                            confirmedLiked[track.videoId] = true
                            seededVideoIds.remove(track.videoId)
                            update(
                                track.videoId,
                                stateFor(track.videoId).copy(
                                    liked = true, accountBacked = true,
                                ),
                            )
                            true
                        }
                        LikeStatus.DISLIKED, LikeStatus.INDIFFERENT -> {
                            confirmedLiked[track.videoId] = false
                            seededVideoIds.remove(track.videoId)
                            update(
                                track.videoId,
                                stateFor(track.videoId).copy(
                                    liked = false, accountBacked = true,
                                ),
                            )
                            true
                        }
                        null -> false // unknown stays unknown — never guessed
                    }
                }
            }
            if (applied) {
                // An applied report takes over the entry's
                // provenance (it removed the track from the seeded
                // set under the lock); the record and the
                // provenance persist as one value.
                persistLikeState()
            }
        }
    }

    /**
     * Round 36: seed the like state of tracks the account's own
     * Liked Songs page just proved are liked. Until now the ONLY
     * road that learned an account like was [refreshAccountStatus],
     * whose sole caller is the player screen, for the one currently
     * playing track: every other track on the Liked Songs page —
     * including the playing track before the full player is ever
     * opened — held NO state entry, so its menu and heart fell back
     * to `liked = false` for a track the page itself exists because
     * the account likes. Its "unlike" tap therefore ran as a LIKE:
     * the first tap re-liked, and only a second tap unliked. A
     * track with an entry already — a toggle's intent, a pending
     * write, a refresh report, a device-local like — is NEVER
     * touched: the page is a fetch snapshot and can predate an
     * in-session unlike, whose entry (present, liked = false) is
     * the fresher truth. Seeded tracks join the device-local
     * record, exactly as a refresh-applied LIKED report does.
     */
    fun noteAccountLiked(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (sessionProvider() == null && !bearerSessionPresent()) return
        var seeded = false
        synchronized(intentLock) {
            for (track in tracks) {
                if (_states.value[track.videoId] == null) {
                    confirmedLiked.putIfAbsent(track.videoId, true)
                    // Round 37: the seed is a state event in the
                    // intent-version protocol — a likeStatus fetch
                    // that started before this page landed carries
                    // the pre-seed account view and must drop, not
                    // apply over the fresher seed (the round-6 rule
                    // for toggles, extended to the seed). And the
                    // entry is this session's provenance: a session
                    // flip drops it (see setAccountBacked).
                    intentVersions[track.videoId] =
                        (intentVersions[track.videoId] ?: 0L) + 1L
                    seededVideoIds += track.videoId
                    update(
                        track.videoId,
                        LikeUiState(liked = true, accountBacked = true),
                    )
                    seeded = true
                }
            }
        }
        if (seeded) {
            persistLikeState()
        }
    }

    private fun update(videoId: String, state: LikeUiState) {
        _states.value = _states.value + (videoId to state)
    }

    /**
     * Persists the local record AND the seed provenance as one
     * stored value (bug sweep round 39): the pair used to be two
     * keys written by two calls (persistLocal + persistSeeded),
     * and a process death between them split the pair — on the
     * seed path the record landed without its provenance (the
     * round-38 leak in miniature: the seeded id relaunched as an
     * ordinary device-local like), and on the refresh-takeover
     * path the record landed while the provenance still named
     * the taken-over entry (a later flip then dropped it as a
     * seed). [AppPreferences.setLikedState] writes the pair in a
     * single store write, so a crash keeps the whole previous
     * pair or the whole next one, never half of each.
     */
    private fun persistLikeState() {
        val likedIds = _states.value.filterValues { it.liked }.keys
        val seeded = synchronized(intentLock) { seededVideoIds.toSet() }
        preferences.setLikedState(likedIds, seeded)
    }
}
