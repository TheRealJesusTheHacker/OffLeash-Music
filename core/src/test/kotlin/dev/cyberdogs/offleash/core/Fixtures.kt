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
