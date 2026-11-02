package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.playback.PlaybackDiagnostics
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Builds the ONE OkHttp client for the whole app: youtubei API calls
 * and stream fetches go through the same stack (same connection pool,
 * same address-family choices), because googlevideo stream URLs are
 * bound to the network identity that resolved them. The scoped
 * [RangeInterceptor] normalizes googlevideo GETs to bounded ranges
 * — shaped per stream by [streamWindows], the shared record of the
 * window the ladder probe won for each URL (round 3) — and records
 * EVERY stream load's status into diagnostics.
 *
 * There is deliberately NO logging interceptor here, at any level:
 * authenticated requests carry session material in their headers,
 * and header-bearing request logging is how that material leaks.
 * A contract test pins the absence.
 */
internal fun buildSharedClient(
    diagnostics: PlaybackDiagnostics,
    streamWindows: StreamWindowStore = StreamWindowStore(),
): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // A whole-call bound on top of the per-phase ones (0.8.7
        // sweep 1): a server dribbling one byte per read-timeout
        // window reset the read timer forever and the call never
        // ended — sixty seconds end to end, whatever the phases do.
        .callTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(
            RangeInterceptor(streamWindows) { status, host, shape ->
                diagnostics.recordStreamLoad(status, host, shape)
            },
        )
        .build()

/**
 * [HttpTransport] over OkHttp.
 *
 * Any HTTP status is returned as an [HttpResponse] — HTTP errors are data
 * for the core client (it maps them onto the error taxonomy), never an
 * exception here. Only genuine transport failures throw (IOException), and
 * the core client wraps those in NetworkFailure itself. GET follows the
 * same contract as POST.
 *
 * Cookie rotation: when a response arrives from a youtube.com host,
 * the first name=value pair of each Set-Cookie header is collected
 * and handed to [onResponseCookies]. The pairs are raw — deciding
 * which names belong to the session is the session manager's job.
 * Nothing about those headers is ever logged, here or anywhere.
 */
class OkHttpTransport(
    private val httpClient: OkHttpClient,
    private val onResponseCookies: ((Map<String, String>) -> Unit)? = null,
) : HttpTransport {

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
        for ((name, value) in headers) {
            requestBuilder.header(name, value)
        }
        return execute(requestBuilder.build())
    }

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        for ((name, value) in headers) {
            requestBuilder.header(name, value)
        }
        return execute(requestBuilder.build())
    }

    /**
     * Form-encoded POST for the OAuth endpoints (device code,
     * token, revocation). Same contract as [post]: any HTTP status
     * is data in an [HttpResponse]; only transport failures throw.
     * These calls carry no session material of the cookie kind, and
     * the token values in their bodies are never logged (there is
     * no logging interceptor on this client, by design — see the
     * class documentation).
     */
    override fun postForm(
        url: String,
        headers: Map<String, String>,
        formBody: String,
    ): HttpResponse {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(formBody.toRequestBody(FORM_MEDIA_TYPE))
        for ((name, value) in headers) {
            requestBuilder.header(name, value)
        }
        return execute(requestBuilder.build())
    }

    private fun execute(request: Request): HttpResponse {
        httpClient.newCall(request).execute().use { response ->
            notifyResponseCookies(request, response.headers("Set-Cookie"))
            val responseBody = response.body
            return HttpResponse(
                status = response.code,
                body = if (responseBody == null) "" else readBodyBounded(responseBody),
            )
        }
    }

    /**
     * Reads a response body under a hard cap (0.8.7 sweep 1):
     * `Body.string()` materialized whatever arrived, so a broken
     * or hostile endpoint decided the app's memory (and the cap
     * applies after any transparent decompression, so a tiny
     * gzipped bomb counts at its expanded size). Past the cap the
     * read fails as the IOException it is, and the core client
     * wraps it in NetworkFailure exactly like any other transport
     * failure.
     */
    private fun readBodyBounded(body: okhttp3.ResponseBody): String {
        val source = body.source()
        source.request(MAX_BODY_BYTES + 1)
        if (source.buffer.size > MAX_BODY_BYTES) {
            throw java.io.IOException(
                "response body exceeds $MAX_BODY_BYTES bytes",
            )
        }
        return source.buffer.readUtf8()
    }

    private fun notifyResponseCookies(request: Request, setCookieHeaders: List<String>) {
        val callback = onResponseCookies ?: return
        if (setCookieHeaders.isEmpty()) return
        if (!isYouTubeHost(request.url.host)) return
        val pairs = parseSetCookiePairs(setCookieHeaders)
        if (pairs.isEmpty()) return
        try {
            callback(pairs)
        } catch (e: Exception) {
            // Rotation is best-effort bookkeeping; it must never
            // break the request that carried the cookies in.
        }
    }

    companion object {
        /** Hard cap on a single API response body (0.8.7 sweep 1). */
        const val MAX_BODY_BYTES: Long = 32L * 1024 * 1024


        /** youtube.com itself, or any subdomain of it — nothing else. */
        internal fun isYouTubeHost(host: String): Boolean {
            val normalized = host.lowercase()
            return normalized == "youtube.com" || normalized.endsWith(".youtube.com")
        }

        /**
         * The first `name=value` pair of each Set-Cookie header.
         * A value may itself contain '='; everything from the first
         * ';' on is an attribute (expires, path, …), not the value.
         * Pure, so tests can pin the parsing directly.
         */
        internal fun parseSetCookiePairs(setCookieHeaders: List<String>): Map<String, String> {
            val pairs = LinkedHashMap<String, String>()
            for (header in setCookieHeaders) {
                val firstPair = header.substringBefore(';').trim()
                val equals = firstPair.indexOf('=')
                if (equals <= 0) continue
                val name = firstPair.substring(0, equals).trim()
                val value = firstPair.substring(equals + 1).trim()
                if (name.isEmpty()) continue
                pairs[name] = value
            }
            return pairs
        }

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
    }
}
