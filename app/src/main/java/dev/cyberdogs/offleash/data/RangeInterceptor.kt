package dev.cyberdogs.offleash.data

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Normalizes stream fetches to bounded ranges, and reports the
 * first-fetch HTTP status for googlevideo responses.
 *
 * Why: a fresh coordinator probe on the owner's track (fresh
 * resolution, both itag 251 and itag 140, `gir=yes`) showed the
 * googlevideo server requires a BOUNDED range:
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
 * [onGoogleVideoResponse] is invoked with (status, host) for every
 * googlevideo GET response, so playback diagnostics can record what
 * the stream server actually answered.
 */
class RangeInterceptor(
    private val onGoogleVideoResponse: ((status: Int, host: String) -> Unit)? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val isGoogleVideoGet = isGoogleVideoGet(
            method = original.method,
            host = original.url.host,
        )
        val normalized = normalizedRangeHeader(
            method = original.method,
            host = original.url.host,
            existingRange = original.header("Range"),
        )
        val request = if (normalized != null) {
            original.newBuilder().header("Range", normalized).build()
        } else {
            original
        }
        val response = chain.proceed(request)
        if (isGoogleVideoGet) {
            onGoogleVideoResponse?.invoke(response.code, original.url.host)
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
    }
}
