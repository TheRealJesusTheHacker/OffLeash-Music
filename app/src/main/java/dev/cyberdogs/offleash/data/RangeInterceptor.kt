package dev.cyberdogs.offleash.data

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Guarantees a Range header on stream fetches, and reports the first-fetch
 * HTTP status for googlevideo responses.
 *
 * Why: some googlevideo formats (notably those whose URL carries
 * `gir=yes`) refuse a plain GET with HTTP 403, while any ranged GET is
 * answered 206. Media3's data sources omit the Range header when the
 * requested position is 0 and the length is unset, so the player's very
 * first fetch is exactly the refused shape. Adding `Range: bytes=0-`
 * here makes that first fetch ranged without changing what is played.
 *
 * Scoping is deliberately narrow, and unit-tested exactly:
 * - only GET requests (a Range header on a youtubei POST would be wrong);
 * - only hosts ending in `.googlevideo.com` (API and artwork traffic is
 *   left untouched);
 * - only when the request has no Range header already (an existing,
 *   more specific range always wins).
 *
 * [onGoogleVideoResponse] is invoked with (status, host) for every
 * googlevideo GET response, so playback diagnostics can record what the
 * stream server actually answered.
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
        val request = if (isGoogleVideoGet && original.header("Range") == null) {
            original.newBuilder().header("Range", "bytes=0-").build()
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
        /** The exact scoping rule, kept pure so tests pin it directly. */
        fun isGoogleVideoGet(method: String, host: String): Boolean =
            method.equals("GET", ignoreCase = true) && host.endsWith(".googlevideo.com")

        fun shouldAddRange(method: String, host: String, existingRange: String?): Boolean =
            isGoogleVideoGet(method, host) && existingRange == null
    }
}
