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
    fun absentRangeBecomesBoundedFromZero() {
        val out = run(
            Request.Builder().url("https://rr1---sn-example.googlevideo.com/videoplayback").get().build(),
        )
        assertEquals("bytes=0-1048575", out.header("Range"))
    }

    @Test
    fun openEndedRangeBecomesBoundedPreservingStart() {
        val out = run(
            Request.Builder().url("https://rr1---sn-example.googlevideo.com/videoplayback")
                .header("Range", "bytes=2048-").get().build(),
        )
        assertEquals("bytes=2048-1050623", out.header("Range"))
        val fromZero = run(
            Request.Builder().url("https://rr1---sn-example.googlevideo.com/videoplayback")
                .header("Range", "bytes=0-").get().build(),
        )
        assertEquals("bytes=0-1048575", fromZero.header("Range"))
    }

    @Test
    fun boundedRangeIsUnchanged() {
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
        // Even a POST that already carries an open-ended Range is untouched.
        val posted = run(
            Request.Builder().url("https://rr1---sn-example.googlevideo.com/videoplayback")
                .header("Range", "bytes=0-").post(body).build(),
        )
        assertEquals("bytes=0-", posted.header("Range"))
    }

    @Test
    fun leavesNonGooglevideoHostsUntouched() {
        val out = run(
            Request.Builder().url("https://music.youtube.com/youtubei/v1/browse").get().build(),
        )
        assertNull(out.header("Range"))
        val ranged = run(
            Request.Builder().url("https://example.com/file")
                .header("Range", "bytes=0-").get().build(),
        )
        assertEquals("bytes=0-", ranged.header("Range"))
    }

    @Test
    fun normalizerRuleMatchesSpec() {
        assertEquals(1024L * 1024L, RangeInterceptor.STREAM_RANGE_WINDOW_BYTES)
        assertEquals(
            "bytes=0-1048575",
            RangeInterceptor.normalizedRangeHeader("GET", "x.googlevideo.com", null),
        )
        assertEquals(
            "bytes=5-1048580",
            RangeInterceptor.normalizedRangeHeader("GET", "x.googlevideo.com", "bytes=5-"),
        )
        assertNull(RangeInterceptor.normalizedRangeHeader("GET", "x.googlevideo.com", "bytes=5-9"))
        assertNull(RangeInterceptor.normalizedRangeHeader("POST", "x.googlevideo.com", null))
        assertNull(RangeInterceptor.normalizedRangeHeader("POST", "x.googlevideo.com", "bytes=5-"))
        assertNull(RangeInterceptor.normalizedRangeHeader("GET", "example.com", null))
        assertNull(RangeInterceptor.normalizedRangeHeader("GET", "example.com", "bytes=5-"))
    }
}
