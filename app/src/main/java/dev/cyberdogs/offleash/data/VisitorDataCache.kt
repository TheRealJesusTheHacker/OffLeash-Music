package dev.cyberdogs.offleash.data

/**
 * Process-lifetime cache for the anonymous visitor data the OAuth
 * road binds into signed-in browse (0.8.4 browse hunt — see
 * [dev.cyberdogs.offleash.core.api.YoutubeiClient]'s
 * `visitorDataProvider`).
 *
 * Visitor data is NOT account data: the fetcher reads it with no
 * auth from YouTube's public `sw.js_data`, exactly the call the
 * core client already made for the cookie road. It is long-lived
 * material, so a success is cached for the process lifetime. A
 * failure is NOT cached as a value — [get] returns null (the
 * browse cascade then runs its non-visitor legs) and the next
 * fetch is allowed once [failureRetryMs] has passed, so a
 * transient network death at first need doesn't disable the
 * visitor legs for the whole process, and a dead network isn't
 * hammered once per surface load either.
 *
 * Threading: [get] is BLOCKING (the fetch is a network call) and
 * is called from the browse surfaces' IO dispatchers, like every
 * core client call. Concurrent callers share one fetch: the lock
 * is held across it deliberately — a second caller arriving
 * mid-fetch wants the same value, not a second fetch.
 *
 * Totality: a throwing fetcher reads as a failure, never as an
 * exception out of [get].
 */
class VisitorDataCache(
    private val fetcher: () -> String?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val failureRetryMs: Long = FAILURE_RETRY_MS,
) {

    private val lock = Any()

    @Volatile
    private var cached: String? = null

    /** When the last failed fetch happened; null when none has. */
    @Volatile
    private var lastFailureAtMs: Long? = null

    /** The visitor data, or null when unavailable right now. */
    fun get(): String? {
        val hit = cached
        if (hit != null) return hit
        synchronized(lock) {
            val lockedHit = cached
            if (lockedHit != null) return lockedHit
            val lastFailure = lastFailureAtMs
            if (lastFailure != null && nowMs() - lastFailure < failureRetryMs) {
                return null
            }
            val fetched = try {
                fetcher()
            } catch (t: Throwable) {
                null
            }
            if (fetched.isNullOrBlank()) {
                lastFailureAtMs = nowMs()
                return null
            }
            cached = fetched
            return fetched
        }
    }

    companion object {
        /** How long a failed fetch suppresses the next attempt. */
        const val FAILURE_RETRY_MS: Long = 60_000L
    }
}
