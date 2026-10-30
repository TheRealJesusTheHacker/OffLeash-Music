package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport

/**
 * The reactive half of Google-token freshness (sign-in perfection
 * pass, closing the sweep's W2 open item): an [HttpTransport]
 * decorator that gives a refused Bearer <redacted> exactly one
 * second chance.
 *
 * Proactive refresh (the session manager's 60-second skew) cannot
 * cover every token death: a grant revoked at Google between
 * refreshes, or a device clock running behind so a token reads
 * locally valid long past its real expiry, both surface as an
 * HTTP 401 on a call that had every reason to expect success —
 * and before this decorator that 401 was the end of the story:
 * the surface errored (or silently fell back) while the UI went
 * on claiming "signed in" over a dead grant.
 *
 * The rule here is deliberately narrow:
 *
 * - Only a **401** answer to a request that carried an
 *   `Authorization: Bearer …` header is eligible. The cookie
 *   road's `Authorization: SAPISIDHASH …` header, header-less
 *   calls (LRCLIB, visitor data), and every non-401 answer pass
 *   through untouched — 403 is a refusal refresh cannot fix, and
 *   retrying it would only double the refusal.
 * - The rejected token goes to [bearerRefresh] — the Google
 *   session manager's `bearerAfterRejection`, late-bound by the
 *   container exactly like the cookie-rotation callback, because
 *   the wiring cycle is the same shape. A fresh token comes back
 *   only when refreshing is honest; null (or the same token) ends
 *   the attempt and the ORIGINAL 401 is returned, so the caller's
 *   own error mapping sees the refusal it would have seen before.
 * - The retry happens **once**: the retried response is returned
 *   as-is, even if it is another 401. There is no loop here, and
 *   the manager separately spends at most one forced refresh per
 *   token.
 *
 * Form POSTs (the OAuth endpoints themselves) are never retried:
 * they carry no Bearer header, and their protocol answers are
 * states the auth client maps by hand.
 *
 * Threading: like the transport it wraps, this is blocking — the
 * refresh inside [bearerRefresh] may perform network IO on the
 * caller's thread, which is the IO dispatcher every core call
 * already runs on.
 */
class BearerRetryTransport(
    private val delegate: HttpTransport,
    private val bearerRefresh: (rejectedBearer: String) -> String?,
) : HttpTransport {

    override fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): HttpResponse = withBearerRetry(headers) { delegate.post(url, it, body) }

    override fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpResponse = withBearerRetry(headers) { delegate.get(url, it) }

    override fun postForm(
        url: String,
        headers: Map<String, String>,
        formBody: String,
    ): HttpResponse = delegate.postForm(url, headers, formBody)

    private fun withBearerRetry(
        headers: Map<String, String>,
        call: (Map<String, String>) -> HttpResponse,
    ): HttpResponse {
        val response = call(headers)
        if (response.status != HTTP_UNAUTHORIZED) return response
        var authName: String? = null
        var authValue: String? = null
        for ((name, value) in headers) {
            if (name.equals(AUTHORIZATION_HEADER, ignoreCase = true)) {
                authName = name
                authValue = value
                break
            }
        }
        val bearerValue = authValue ?: return response
        if (!bearerValue.startsWith(BEARER_PREFIX)) return response
        val rejected = bearerValue.substring(BEARER_PREFIX.length).trim()
        if (rejected.isEmpty()) return response
        val fresh = try {
            bearerRefresh(rejected)
        } catch (e: Exception) {
            // A refresh that itself fails must never turn a clean
            // 401 into a transport crash: the caller gets the
            // refusal it would have gotten without this decorator.
            null
        }
        if (fresh == null || fresh == rejected) return response
        val retryHeaders = LinkedHashMap<String, String>(headers.size)
        for ((name, value) in headers) {
            retryHeaders[name] = if (name == authName) {
                BEARER_PREFIX + fresh
            } else {
                value
            }
        }
        return call(retryHeaders)
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}
