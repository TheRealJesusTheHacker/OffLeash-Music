package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The visitor-data cache (0.8.4 browse hunt): a success is cached
 * for the process lifetime; a failure reads as absent, suppresses
 * refetching for the retry window, and never throws — the browse
 * cascade treats "no visitor data right now" as "run the plain
 * legs", so the cache's failure discipline is what keeps a dead
 * network from either hammering the fetch on every surface load
 * or disabling the visitor legs for the whole process.
 */
class VisitorDataCacheTest {

    @Test
    fun successIsFetchedOnceAndCached() {
        var fetches = 0
        val cache = VisitorDataCache(fetcher = { fetches++; "vd-1" }, nowMs = { 1_000L })

        assertEquals("vd-1", cache.get())
        assertEquals("vd-1", cache.get())
        assertEquals(1, fetches)
    }

    @Test
    fun failureReadsAbsentAndSuppressesRefetchInsideTheWindow() {
        var now = 10_000L
        var fetches = 0
        val cache = VisitorDataCache(
            fetcher = { fetches++; null },
            nowMs = { now },
            failureRetryMs = 60_000L,
        )

        assertNull(cache.get())
        now += 30_000L
        assertNull(cache.get())
        assertEquals(1, fetches)
    }

    @Test
    fun failureRetriesAfterTheWindowAndSuccessThenSticks() {
        var now = 10_000L
        var fetches = 0
        val cache = VisitorDataCache(
            fetcher = { fetches++; if (fetches < 2) null else "vd-late" },
            nowMs = { now },
            failureRetryMs = 60_000L,
        )

        assertNull(cache.get())
        now += 61_000L
        assertEquals("vd-late", cache.get())
        assertEquals("vd-late", cache.get())
        assertEquals(2, fetches)
    }

    @Test
    fun throwingFetcherReadsAsFailureNotAsACrash() {
        val cache = VisitorDataCache(
            fetcher = { throw RuntimeException("network died") },
            nowMs = { 5_000L },
        )

        assertNull(cache.get())
    }

    @Test
    fun blankFetchReadsAsFailure() {
        var fetches = 0
        val cache = VisitorDataCache(fetcher = { fetches++; "  " }, nowMs = { 5_000L })

        assertNull(cache.get())
        assertEquals(1, fetches)
    }
}
