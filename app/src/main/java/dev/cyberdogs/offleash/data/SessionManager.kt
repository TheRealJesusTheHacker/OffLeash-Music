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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
     * Completes a sign-in from a raw cookie string (WebView capture
     * or manual paste — both arrive in exactly this shape).
     *
     * Nothing is stored until YouTube itself confirms which account
     * the session belongs to.
     */
    suspend fun completeSignIn(rawCookieString: String): SignInAttempt = mutex.withLock {
        val cookies = SessionCookies.extractSubset(rawCookieString)
        if (!SessionCookies.isPlausibleSession(cookies)) {
            SignInAttempt.Failed(
                "That sign-in didn't include a usable YouTube session. " +
                    "Sign in again and let the YouTube Music page finish loading, " +
                    "or paste a cookie from music.youtube.com.",
            )
        } else {
            // authUser starts at 0 for validation only; the identity
            // answer below supplies the real index (assuming 0 for a
            // brand channel on a later account answers as signed out).
            val tentative = AuthSession(
                cookies = cookies,
                authUser = 0,
                pageId = null,
                visitorData = null,
            )
            val lookup = withContext(Dispatchers.IO) { client.accountIdentities(tentative) }
            when (lookup) {
                is IdentityLookup.Found -> {
                    val identities = lookup.identities
                    if (identities.isEmpty()) {
                        SignInAttempt.Failed(NOT_CONFIRMED_REASON)
                    } else {
                        val selected = identities.firstOrNull { it.isSelected == true }
                            ?: identities.first()
                        adoptIdentifiedSession(cookies, selected)
                    }
                }
                is IdentityLookup.Rejected -> SignInAttempt.Failed(NOT_CONFIRMED_REASON)
                is IdentityLookup.Failed -> SignInAttempt.Failed(
                    "Couldn't reach YouTube to verify the sign-in — " +
                        "check your connection and try again.",
                )
            }
        }
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
        store.save(toStore)
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
                    store.save(updated)
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
        const val NOT_CONFIRMED_REASON =
            "YouTube didn't confirm a signed-in account for that session. " +
                "Sign in again, and make sure the YouTube Music page fully loads first."
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
