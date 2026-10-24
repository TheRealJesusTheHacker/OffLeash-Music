package dev.cyberdogs.offleash.core.api

/** A raw HTTP response: status code plus the body as text. */
data class HttpResponse(
    val status: Int,
    val body: String,
)

/**
 * String-in/string-out HTTP POST transport. The app implements this with
 * OkHttp; tests implement it with recorded fixtures. Implementations may
 * throw on transport failure — the client maps that to
 * [dev.cyberdogs.offleash.core.error.NetworkFailure].
 */
fun interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String): HttpResponse
}
