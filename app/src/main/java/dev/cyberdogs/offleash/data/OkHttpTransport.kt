package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [HttpTransport] over OkHttp.
 *
 * Any HTTP status is returned as an [HttpResponse] — HTTP errors are data
 * for the core client (it maps them onto the error taxonomy), never an
 * exception here. Only genuine transport failures throw (IOException), and
 * the core client wraps those in NetworkFailure itself.
 */
class OkHttpTransport(private val httpClient: OkHttpClient) : HttpTransport {

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
        for ((name, value) in headers) {
            requestBuilder.header(name, value)
        }
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            return HttpResponse(
                status = response.code,
                body = response.body?.string() ?: "",
            )
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
