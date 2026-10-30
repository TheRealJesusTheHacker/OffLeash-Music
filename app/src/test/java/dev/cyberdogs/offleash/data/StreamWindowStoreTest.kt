package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StreamWindowStore bounds (bug sweep round 1), companion to
 * WinningWindowHandoffTest (whose contract is untouched): a
 * non-positive "winner" must never be recorded — it would shape a
 * fetch with a malformed Range — and re-recording an existing
 * URL's winner at capacity must not evict every other winner.
 */
class StreamWindowStoreTest {

    private val url = "https://rr1---sn-example.googlevideo.com/videoplayback?expire=1"

    @Test
    fun nonPositiveWindowsAreRefused() {
        val store = StreamWindowStore()
        store.recordWinner(url, 0L)
        assertNull(store.windowFor(url))
        store.recordWinner(url, -2048L)
        assertNull(store.windowFor(url))
        store.recordWinner(url, 2048L)
        assertEquals(2048L, store.windowFor(url))
        // A later bogus record does not replace the real winner.
        store.recordWinner(url, 0L)
        assertEquals(2048L, store.windowFor(url))
    }

    @Test
    fun interceptorFallsBackToTheDefaultWindowForNonPositiveWindows() {
        assertEquals(
            "bytes=0-1048575",
            RangeInterceptor.normalizedRangeHeader(
                method = "GET",
                host = "rr1---sn-example.googlevideo.com",
                existingRange = null,
                windowBytes = 0L,
            ),
        )
        assertEquals(
            "bytes=100-1048675",
            RangeInterceptor.normalizedRangeHeader(
                method = "GET",
                host = "rr1---sn-example.googlevideo.com",
                existingRange = "bytes=100-",
                windowBytes = -5L,
            ),
        )
    }

    @Test
    fun reRecordingAnExistingUrlAtCapacityKeepsTheOtherWinners() {
        val store = StreamWindowStore()
        val urls = (1..StreamWindowStore.MAX_ENTRIES).map { i ->
            "https://rr$i---sn-example.googlevideo.com/videoplayback?expire=$i"
        }
        urls.forEach { store.recordWinner(it, 1024L) }
        // At capacity: updating an existing entry is not an insert.
        store.recordWinner(urls.first(), 2048L)
        assertEquals(2048L, store.windowFor(urls.first()))
        assertEquals(1024L, store.windowFor(urls.last()))
        // Round 2 replaced round 1's clear-all bound with LRU
        // eviction: a genuinely new URL at capacity now evicts only
        // the least-recently-used entry (urls[1] — urls.first() was
        // just re-recorded and read, urls.last() just read), never
        // every winner at once.
        val fresh = "https://rrX---sn-example.googlevideo.com/videoplayback?expire=9"
        store.recordWinner(fresh, 4096L)
        assertEquals(4096L, store.windowFor(fresh))
        assertEquals(2048L, store.windowFor(urls.first()))
        assertEquals(null, store.windowFor(urls[1]))
    }

    @Test
    fun concurrentReadsAndWritesNeverCorruptOrUnboundTheStore() {
        // Bug sweep round 3 attack on the round-2 LRU: an
        // access-ordered LinkedHashMap MUTATES on every get, so a
        // single unsynchronized access path would corrupt it under
        // the interceptor's multi-thread load. Hammer inserts,
        // reads and clears from many threads, then prove the bound
        // still holds by counting how many churned URLs retain a
        // window, and that a survivor reads back its own value.
        val store = StreamWindowStore()
        val threads = (1..8).map { t ->
            Thread {
                repeat(500) { i ->
                    val u = "https://rr$t-$i---sn-example.googlevideo.com/videoplayback?expire=$i"
                    store.recordWinner(u, 1024L + i)
                    store.windowFor(u)
                    if (i % 97 == 0) store.clear(u)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        var retained = 0
        for (t in 1..8) {
            for (i in 0 until 500) {
                val u = "https://rr$t-$i---sn-example.googlevideo.com/videoplayback?expire=$i"
                if (store.windowFor(u) != null) retained++
            }
        }
        assertTrue(
            "retained $retained entries, bound is ${StreamWindowStore.MAX_ENTRIES}",
            retained <= StreamWindowStore.MAX_ENTRIES,
        )
        store.recordWinner(url, 2048L)
        assertEquals(2048L, store.windowFor(url))
    }

    @Test
    fun adversarialUrlChurnNeverEvictsTheCurrentlyPlayingStreamsWinner() {
        // Round 2 charter scenario: signed URLs differ per resolve,
        // so churn fills the map with dead URLs. The playing stream
        // keeps being READ through the interceptor, so its winner
        // must survive churn far past capacity.
        val store = StreamWindowStore()
        val current = "https://rr9---sn-example.googlevideo.com/videoplayback?expire=now"
        store.recordWinner(current, 2048L)
        for (i in 1..(StreamWindowStore.MAX_ENTRIES * 4)) {
            store.recordWinner(
                "https://rr$i---sn-example.googlevideo.com/videoplayback?expire=$i",
                1024L,
            )
            // One player load of the current stream per resolve —
            // the access that marks it recently used.
            assertEquals(2048L, store.windowFor(current))
        }
        assertEquals(2048L, store.windowFor(current))
        // A URL recorded once and never touched again DOES age out.
        val dead = "https://rrD---sn-example.googlevideo.com/videoplayback?expire=dead"
        store.recordWinner(dead, 512L)
        for (i in 1..StreamWindowStore.MAX_ENTRIES) {
            store.recordWinner(
                "https://rrF$i---sn-example.googlevideo.com/videoplayback?expire=f$i",
                1024L,
            )
            // The current stream is still playing: its loads keep
            // reading its winner while the churn flows past.
            assertEquals(2048L, store.windowFor(current))
        }
        assertNull(store.windowFor(dead))
        assertEquals(2048L, store.windowFor(current))
    }
}
