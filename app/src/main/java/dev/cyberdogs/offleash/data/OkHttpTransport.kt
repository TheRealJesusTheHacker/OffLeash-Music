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
 * and records their first-fetch status into diagnostics.
 *
 * There is deliberately NO logging interceptor here, at any level:
 * authenticated requests carry session material in their headers,
 * and header-bearing request logging is how that material leaks.
 * A contract test pins the absence.
 */
internal fun buildSharedClient(diagnostics: PlaybackDiagnostics): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(
            RangeInterceptor { status, host, shape ->
                diagnostics.recordFirstFetch(status, host, shape)
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

    private fun execute(request: Request): HttpResponse {
        httpClient.newCall(request).execute().use { response ->
            notifyResponseCookies(request, response.headers("Set-Cookie"))
            return HttpResponse(
                status = response.code,
                body = response.body?.string() ?: "",
            )
        }
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
    }
}
