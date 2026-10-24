package dev.cyberdogs.offleash.core

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import org.junit.Assert.assertNotNull

/** Loads a recorded response from core/src/test/resources/fixtures. */
object Fixtures {
    fun load(name: String): String {
        val stream = Fixtures::class.java.getResourceAsStream("/fixtures/$name")
        assertNotNull("fixture $name must exist on the test classpath", stream)
        return stream!!.bufferedReader(Charsets.UTF_8).readText()
    }
}

/** A transport that answers every request with one fixed response. */
class FixedTransport(
    private val status: Int = 200,
    private val body: String,
) : HttpTransport {
    var lastUrl: String? = null
        private set
    var lastHeaders: Map<String, String>? = null
        private set
    var lastBody: String? = null
        private set

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        lastUrl = url
        lastHeaders = headers
        lastBody = body
        return HttpResponse(status, this.body)
    }
}

/** A transport that always throws, like a dead network. */
class ThrowingTransport : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        throw RuntimeException("connection reset by peer")
    }
}

/**
 * A transport that answers GET requests with one fixed response (or a
 * fixed failure). POSTs get the same fixed response, for tests that mix
 * the two. Added for Phase 2's GET endpoints (account switcher,
 * visitor data); [FixedTransport] is unchanged.
 */
class GetTransport(
    private val status: Int = 200,
    private val body: String = "",
    private val failure: Exception? = null,
) : HttpTransport {
    var lastGetUrl: String? = null
        private set
    var lastGetHeaders: Map<String, String>? = null
        private set

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
        HttpResponse(status, this.body)

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        lastGetUrl = url
        lastGetHeaders = headers
        failure?.let { throw it }
        return HttpResponse(status, body)
    }
}

/**
 * A transport that routes every request (POST or GET) to [handler] and
 * records what it was asked, so tests can answer per endpoint/context
 * and assert on captured URLs, headers, and bodies afterwards.
 */
class RoutingTransport(
    private val handler: (url: String, headers: Map<String, String>, body: String?) -> HttpResponse,
) : HttpTransport {

    data class Recorded(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?,
    )

    val requests = mutableListOf<Recorded>()

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        requests.add(Recorded("POST", url, headers, body))
        return handler(url, headers, body)
    }

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        requests.add(Recorded("GET", url, headers, null))
        return handler(url, headers, null)
    }
}
