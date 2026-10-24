package dev.cyberdogs.offleash.core.api

/** A raw HTTP response: status code plus the body as text. */
data class HttpResponse(
    val status: Int,
    val body: String,
)

/**
 * String-in/string-out HTTP transport. The app implements this with
 * OkHttp; tests implement it with recorded fixtures. Implementations may
 * throw on transport failure — the client maps that to
 * [dev.cyberdogs.offleash.core.error.NetworkFailure].
 *
 * [post] is the single abstract method, so the interface stays a
 * `fun interface` and existing SAM/lambda uses keep compiling. [get] is
 * a non-abstract convenience for the few plain-GET endpoints Phase 2
 * needs (account switcher, visitor data): its default body throws
 * [UnsupportedOperationException], and real transports (the app's
 * OkHttp one, routing test fakes) override it.
 */
fun interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String): HttpResponse

    /**
     * HTTP GET. The default implementation throws
     * [UnsupportedOperationException] — transports that serve GET
     * endpoints must override this.
     */
    fun get(url: String, headers: Map<String, String>): HttpResponse {
        throw UnsupportedOperationException(
            "this HttpTransport does not implement GET (url=$url)"
        )
    }
}
