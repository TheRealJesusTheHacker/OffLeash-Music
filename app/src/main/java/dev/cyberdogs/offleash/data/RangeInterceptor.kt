package dev.cyberdogs.offleash.data

import okhttp3.Interceptor
import okhttp3.Response

/**
 * The wire shape of one googlevideo GET as it leaves the shared
 * stack, reported for playback diagnostics: the Range header that
 * was sent (or "none"), the User-Agent class ("okhttp-default" when
 * the request carries no UA of its own and OkHttp's default will be
 * added downstream, "custom" otherwise), and the Accept-Encoding
 * value (or "absent"). Header VALUES that could identify a session
 * are never reported — only the shape.
 */
data class StreamFetchShape(
    val range: String,
    val userAgent: String,
    val acceptEncoding: String,
)

/**
 * The ONE shared record of which Range window won for which stream
 * URL — the handoff between the ladder probe and the player
 * (2026-10-25 hunt, round 3).
 *
 * The probe does not preset a Range header (the pinned probe shape
 * is "no headers of its own"); instead it records the CANDIDATE
 * window for the URL here before fetching bare, and [RangeInterceptor]
 * — the one shaping point every stream fetch passes through —
 * normalizes that fetch with the recorded window. When a window
 * answers 2xx the probe leaves it recorded as the WINNER, and
 * QueueController re-records it for the URL it hands to the player,
 * so the player's own loads of that stream are normalized with the
 * winning window by the same definition. Probe shape and player
 * shape cannot drift: neither side names a window of its own; both
 * read this store through the interceptor.
 *
 * Keys are full stream URLs (each resolution mints a fresh signed
 * URL, so an entry can only ever shape fetches of the exact stream
 * it was won on). Values are byte counts only — never logged from
 * here. The map is bounded at [MAX_ENTRIES] with LEAST-RECENTLY-USED
 * eviction (bug sweep round 2): round 1's clear-all-on-full meant
 * that under adversarial URL churn — every resolve mints a new
 * signed URL, so radio/autoplay resolving ahead fills the map with
 * dead URLs — the 65th new URL evicted EVERY winner, including the
 * CURRENTLY PLAYING stream's, whose remaining loads then rode the
 * default 1 MiB window the ladder had already proven gets refused
 * (403) on exactly those streams. A stream that is playing keeps
 * touching [windowFor] through the interceptor, so LRU keeps its
 * winner warm; only genuinely dead URLs age out. An evicted URL's
 * next fetch rides the default window, exactly the pre-round-3
 * behavior.
 */
class StreamWindowStore {

    // Access-ordered: a get (windowFor) counts as use, so the
    // playing stream's entry is always the freshest. All access is
    // synchronized — LinkedHashMap is not thread-safe, and the
    // interceptor calls in from OkHttp threads.
    private val windows = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Long>,
        ): Boolean = size > MAX_ENTRIES
    }

    /** Record [windowBytes] as the window to shape fetches of [url] with. */
    fun recordWinner(url: String, windowBytes: Long) {
        // A non-positive window is not a window: recording one would
        // shape fetches with a malformed Range ("bytes=0--1"), so it
        // is refused and any previous winner for the URL stands
        // (bug sweep round 1).
        if (windowBytes <= 0L) return
        synchronized(windows) { windows[url] = windowBytes }
    }

    /** The recorded window for [url], or null when none was recorded. */
    fun windowFor(url: String): Long? = synchronized(windows) { windows[url] }

    /** Forget [url]'s window (its next fetch rides the default). */
    fun clear(url: String) {
        synchronized(windows) { windows.remove(url) }
    }

    companion object {
        const val MAX_ENTRIES: Int = 64
    }
}

/**
 * Normalizes stream fetches to the probe-proven shape, and reports
 * the HTTP status for googlevideo responses.
 *
 * Why the range rule exists: a fresh coordinator probe on the
 * owner's track (fresh resolution, both itag 251 and itag 140,
 * `gir=yes`) showed the googlevideo server requires a BOUNDED range:
 * - GET with no Range header -> HTTP 403 (deterministic, repeated)
 * - GET with `Range: bytes=0-` (open-ended) -> HTTP 403
 * - GET with `Range: bytes=0-2047` (bounded) -> 206
 * - GET with a bounded mid-file range -> 206
 *
 * So `gir=yes` stream URLs refuse range-less AND open-ended requests;
 * bounded windows are what YouTube's own players send. Media3's data
 * sources produce exactly the refused shapes (no Range header when
 * position is 0 and length is unset, or an open-ended range when
 * seeking with an unset length), so this interceptor normalizes
 * every googlevideo GET to a bounded window — see
 * [normalizedRangeHeader]:
 * - no Range header -> `Range: bytes=0-(W-1)`;
 * - open-ended `bytes=N-` -> `bytes=N-(N+W-1)`;
 * - already bounded `bytes=N-M` -> untouched;
 * - non-GET or non-googlevideo -> untouched (a Range header on a
 *   youtubei POST would be wrong, and other hosts are not streams).
 *
 * W is the PER-STREAM window (round 3): the window recorded in the
 * shared [StreamWindowStore] for this exact URL — the window the
 * ladder probe won on — or [STREAM_RANGE_WINDOW_BYTES] (1 MiB) when
 * no window was recorded for the URL. The ladder descends
 * [STREAM_WINDOW_LADDER] within one resolution before re-resolving,
 * because the only proven on-track wins before round 3 used tiny
 * windows (`bytes=0-2047`, `bytes=0-0`) while the fixed 1 MiB window
 * kept being refused on the owner's phone.
 *
 * The shape contract (2026-10-24 hunt): a googlevideo GET leaves
 * this stack with a bounded Range, NO Accept-Encoding header, and
 * whatever User-Agent the caller set — and the stream callers (the
 * player's data source, the ladder probe, the diagnostics test
 * fetch) deliberately set NONE, so they all ride OkHttp's default
 * UA. That parity is the fix for the 2026-10-24 regression, where
 * the ladder probe (no UA of its own, `Range: bytes=0-0`) was
 * answered 206 on a URL the player (iOS app UA +
 * `Accept-Encoding: identity`, both added by Media3's
 * OkHttpDataSource) was refused 403 on, on the same phone in the
 * same second. Media3's data source adds the Accept-Encoding
 * itself, so it is stripped here, at the one choke point every
 * stream fetch passes through; OkHttp's own bridge interceptor
 * will not re-add an encoding header to a request that carries a
 * Range. The parity is pinned by StreamShapeParityTest.
 *
 * [onGoogleVideoResponse] is invoked with (status, host, shape) for
 * every googlevideo GET response, so playback diagnostics can record
 * what the stream server actually answered and the exact shape that
 * was sent for it — every load, not only the first.
 */
class RangeInterceptor(
    private val windowStore: StreamWindowStore? = null,
    private val onGoogleVideoResponse: ((status: Int, host: String, shape: StreamFetchShape) -> Unit)? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val isGoogleVideoGet = isGoogleVideoGet(
            method = original.method,
            host = original.url.host,
        )
        val request = if (isGoogleVideoGet) {
            val windowBytes = windowStore?.windowFor(original.url.toString())
                ?: STREAM_RANGE_WINDOW_BYTES
            val normalized = normalizedRangeHeader(
                method = original.method,
                host = original.url.host,
                existingRange = original.header("Range"),
                windowBytes = windowBytes,
            )
            original.newBuilder()
                .removeHeader("Accept-Encoding")
                .apply {
                    if (normalized != null) header("Range", normalized)
                }
                .build()
        } else {
            original
        }
        val response = chain.proceed(request)
        if (isGoogleVideoGet) {
            onGoogleVideoResponse?.invoke(
                response.code,
                original.url.host,
                describeShape(
                    range = request.header("Range"),
                    userAgent = request.header("User-Agent"),
                    acceptEncoding = request.header("Accept-Encoding"),
                ),
            )
        }
        return response
    }

    companion object {
        /**
         * Default bounded window size for normalized stream ranges:
         * 1 MiB — also the FIRST (largest) rung of
         * [STREAM_WINDOW_LADDER]. Coordinator probe (see class doc):
         * `gir=yes` stream URLs refuse range-less AND open-ended
         * requests; bounded windows are what YouTube's own players
         * send.
         */
        const val STREAM_RANGE_WINDOW_BYTES: Long = 1024L * 1024L

        /**
         * The window descent ladder, largest first (round 3): the
         * probe tries these windows IN ORDER within one resolution
         * and the first window answered 2xx wins for that stream.
         * The last rung is the tiny-window shape family of the only
         * proven on-track wins (`bytes=0-2047`, `bytes=0-0`). This
         * list is the one shared definition: the probe descends it,
         * the interceptor shapes with its winner, and tests pin its
         * exact contents.
         */
        val STREAM_WINDOW_LADDER: List<Long> = listOf(
            STREAM_RANGE_WINDOW_BYTES,
            256L * 1024L,
            64L * 1024L,
            2048L,
        )

        /** The bounded Range header for a fetch of [windowBytes] starting at [start]. */
        fun boundedRangeHeader(start: Long, windowBytes: Long): String =
            "bytes=$start-${start + windowBytes - 1}"

        private val RANGE_PATTERN = Regex("""^bytes=(\d+)-(\d*)$""")

        /** The exact scoping rule, kept pure so tests pin it directly. */
        fun isGoogleVideoGet(method: String, host: String): Boolean =
            method.equals("GET", ignoreCase = true) && host.endsWith(".googlevideo.com")

        /**
         * The Range header to send for this request at the default
         * window, or null when the request must be left exactly as
         * it is. Delegates to the per-stream overload with
         * [STREAM_RANGE_WINDOW_BYTES].
         */
        fun normalizedRangeHeader(method: String, host: String, existingRange: String?): String? =
            normalizedRangeHeader(method, host, existingRange, STREAM_RANGE_WINDOW_BYTES)

        /**
         * The Range header to send for this request at the
         * per-stream [windowBytes], or null when the request must
         * be left exactly as it is (non-GET, non-googlevideo,
         * already bounded, or a Range shape we do not understand).
         * Loads stay bounded by construction: the only headers this
         * produces are bounded, and bounded headers pass untouched.
         */
        fun normalizedRangeHeader(
            method: String,
            host: String,
            existingRange: String?,
            windowBytes: Long,
        ): String? {
            if (!isGoogleVideoGet(method, host)) return null
            // A non-positive window can only produce a malformed
            // header ("bytes=0--1"); fall back to the default window.
            val effectiveWindow = if (windowBytes > 0L) windowBytes else STREAM_RANGE_WINDOW_BYTES
            if (existingRange == null) {
                return boundedRangeHeader(0L, effectiveWindow)
            }
            val match = RANGE_PATTERN.matchEntire(existingRange.trim()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2]
            if (end.isNotEmpty()) return null // already bounded — untouched
            return boundedRangeHeader(start, effectiveWindow)
        }

        /**
         * The diagnostics description of a request's fetch shape,
         * kept pure so tests pin it. A request with no User-Agent of
         * its own (or OkHttp's own default) is reported as
         * "okhttp-default" — that is the UA class the probe-proven
         * shape rides on; anything else is "custom".
         */
        fun describeShape(range: String?, userAgent: String?, acceptEncoding: String?): StreamFetchShape =
            StreamFetchShape(
                range = range ?: "none",
                userAgent = if (userAgent == null || userAgent.startsWith("okhttp/")) {
                    "okhttp-default"
                } else {
                    "custom"
                },
                acceptEncoding = acceptEncoding ?: "absent",
            )
    }
}
