package dev.cyberdogs.offleash.data

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class RangeInterceptorTest {

    private class FakeChain(private val original: Request) : Interceptor.Chain {
        var proceeded: Request? = null
        override fun request(): Request = original
        override fun proceed(request: Request): Response {
            proceeded = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private fun run(request: Request): Request {
        val chain = FakeChain(request)
        RangeInterceptor().intercept(chain)
        return chain.proceeded!!
    }

    @Test
    fun addsRangeOnGooglevideoGetWithoutRange() {
        val out = run(
            Request.Builder().url("https://rr1---sn-example.googlevideo.com/videoplayback").get().build(),
        )
        assertEquals("bytes=0-", out.header("Range"))
    }

    @Test
    fun leavesExistingRangeUntouched() {
        val out = run(
            Request.Builder().url("https://rr1---sn-example.googlevideo.com/videoplayback")
                .header("Range", "bytes=100-200").get().build(),
        )
        assertEquals("bytes=100-200", out.header("Range"))
    }

    @Test
    fun leavesPostsUntouched() {
        val body = "{}".toRequestBody(null)
        val out = run(
            Request.Builder().url("https://rr1---sn-example.googlevideo.com/videoplayback").post(body).build(),
        )
        assertNull(out.header("Range"))
    }

    @Test
    fun leavesNonGooglevideoHostsUntouched() {
        val out = run(
            Request.Builder().url("https://music.youtube.com/youtubei/v1/browse").get().build(),
        )
        assertNull(out.header("Range"))
    }

    @Test
    fun scopingRuleMatchesSpec() {
        assertEquals(true, RangeInterceptor.shouldAddRange("GET", "x.googlevideo.com", null))
        assertEquals(false, RangeInterceptor.shouldAddRange("POST", "x.googlevideo.com", null))
        assertEquals(false, RangeInterceptor.shouldAddRange("GET", "x.googlevideo.com", "bytes=1-"))
        assertEquals(false, RangeInterceptor.shouldAddRange("GET", "example.com", null))
    }
}
