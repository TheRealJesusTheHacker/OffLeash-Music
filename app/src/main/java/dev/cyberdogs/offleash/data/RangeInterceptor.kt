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
 * Normalizes stream fetches to the probe-proven shape, and reports
 * the first-fetch HTTP status for googlevideo responses.
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
 * - no Range header -> `Range: bytes=0-1048575` (1 MiB window);
 * - open-ended `bytes=N-` -> `bytes=N-(N+1048575)`;
 * - already bounded `bytes=N-M` -> untouched;
 * - non-GET or non-googlevideo -> untouched (a Range header on a
 *   youtubei POST would be wrong, and other hosts are not streams).
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
 * was sent for it.
 */
class RangeInterceptor(
    private val onGoogleVideoResponse: ((status: Int, host: String, shape: StreamFetchShape) -> Unit)? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val isGoogleVideoGet = isGoogleVideoGet(
            method = original.method,
            host = original.url.host,
        )
        val request = if (isGoogleVideoGet) {
            val normalized = normalizedRangeHeader(
                method = original.method,
                host = original.url.host,
                existingRange = original.header("Range"),
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
         * Bounded window size for normalized stream ranges: 1 MiB.
         * Coordinator probe (see class doc): `gir=yes` stream URLs
         * refuse range-less AND open-ended requests; bounded windows
         * are what YouTube's own players send.
         */
        const val STREAM_RANGE_WINDOW_BYTES: Long = 1024L * 1024L

        private val RANGE_PATTERN = Regex("""^bytes=(\d+)-(\d*)$""")

        /** The exact scoping rule, kept pure so tests pin it directly. */
        fun isGoogleVideoGet(method: String, host: String): Boolean =
            method.equals("GET", ignoreCase = true) && host.endsWith(".googlevideo.com")

        /**
         * The Range header to send for this request, or null when the
         * request must be left exactly as it is (non-GET, non-googlevideo,
         * already bounded, or a Range shape we do not understand).
         */
        fun normalizedRangeHeader(method: String, host: String, existingRange: String?): String? {
            if (!isGoogleVideoGet(method, host)) return null
            if (existingRange == null) {
                return "bytes=0-${STREAM_RANGE_WINDOW_BYTES - 1}"
            }
            val match = RANGE_PATTERN.matchEntire(existingRange.trim()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2]
            if (end.isNotEmpty()) return null // already bounded — untouched
            return "bytes=$start-${start + STREAM_RANGE_WINDOW_BYTES - 1}"
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
