package dev.cyberdogs.offleash.core.playback

import dev.cyberdogs.offleash.core.model.Track
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayQueueTest {

    private fun tracks(n: Int): List<Track> = (0 until n).map { i ->
        Track(
            videoId = "v$i",
            title = "Track $i",
            artists = listOf("Artist"),
            artistBrowseId = null,
            album = null,
            albumBrowseId = null,
            durationSeconds = 180,
            thumbnailUrl = null,
        )
    }

    @Test
    fun linearAdvanceAndEndOfQueue() {
        val queue = PlayQueue()
        queue.setQueue(tracks(3), 0)
        assertEquals("v0", queue.current()!!.videoId)
        assertEquals(0, queue.currentIndex)
        assertEquals("v1", queue.peekNext()!!.videoId)
        assertEquals("v1", queue.next()!!.videoId)
        assertEquals("v2", queue.next()!!.videoId)
        assertNull(queue.peekNext())
        assertNull(queue.next()) // repeat OFF: end of queue
        assertEquals("v2", queue.current()!!.videoId) // current stays put
    }

    @Test
    fun repeatAllWrapsAtEnd() {
        val queue = PlayQueue()
        queue.repeatMode = RepeatMode.ALL
        queue.setQueue(tracks(3), 0)
        queue.next()
        queue.next()
        assertEquals("v0", queue.peekNext()!!.videoId)
        assertEquals("v0", queue.next()!!.videoId)
        assertEquals(0, queue.currentIndex)
    }

    @Test
    fun repeatOneStillAdvancesWhenNextIsCalled() {
        // Repeat-one is the player's job; the queue advances normally.
        val queue = PlayQueue()
        queue.repeatMode = RepeatMode.ONE
        queue.setQueue(tracks(3), 0)
        assertEquals("v1", queue.next()!!.videoId)
        assertEquals("v2", queue.next()!!.videoId)
        assertNull(queue.next())
    }

    @Test
    fun previousWalksBackThroughPlayedTracks() {
        val queue = PlayQueue()
        queue.setQueue(tracks(4), 0)
        queue.next()
        queue.next()
        assertEquals("v1", queue.previous()!!.videoId)
        assertEquals("v0", queue.previous()!!.videoId)
        // At the very start in linear mode, previous steps to the track
        // before the start index only if there is one — here there isn't
        // after walking back past the queue start... start was index 0.
        assertNull(queue.previous())
        // Redo: next returns the track we came back from.
        assertEquals("v1", queue.next()!!.videoId)
    }

    @Test
    fun previousFromMiddleStartStepsBackLinearly() {
        val queue = PlayQueue()
        queue.setQueue(tracks(4), 2)
        assertEquals("v1", queue.previous()!!.videoId)
        assertEquals("v2", queue.next()!!.videoId)
    }

    @Test
    fun shuffleVisitsEveryIndexExactlyOncePerPass() {
        val queue = PlayQueue(Random(42))
        queue.setQueue(tracks(10), 0)
        queue.shuffle = true
        val visited = mutableListOf<Int>()
        visited.add(queue.currentIndex)
        while (true) {
            val next = queue.next() ?: break
            visited.add(queue.currentIndex)
            assertEquals(next.videoId, "v${queue.currentIndex}")
        }
        assertEquals(10, visited.size)
        assertEquals((0 until 10).toSet(), visited.toSet())
    }

    @Test
    fun shuffleWithRepeatAllKeepsPlayingWithoutImmediateRepeat() {
        val queue = PlayQueue(Random(42))
        queue.setQueue(tracks(5), 0)
        queue.shuffle = true
        queue.repeatMode = RepeatMode.ALL
        var previousIndex = queue.currentIndex
        repeat(12) {
            val next = queue.next()
            assertTrue("next must keep returning tracks under repeat ALL", next != null)
            assertTrue(
                "no immediate repeat across a reshuffle (was $previousIndex, got ${queue.currentIndex})",
                queue.currentIndex != previousIndex
            )
            previousIndex = queue.currentIndex
        }
    }

    @Test
    fun shufflePreviousWalksBackThroughActuallyPlayedHistory() {
        val queue = PlayQueue(Random(42))
        queue.setQueue(tracks(10), 0)
        queue.shuffle = true
        val played = mutableListOf(queue.currentIndex)
        repeat(4) {
            queue.next()
            played.add(queue.currentIndex)
        }
        for (expected in played.dropLast(1).reversed()) {
            val previous = queue.previous()
            assertEquals("v$expected", previous!!.videoId)
        }
    }

    @Test
    fun playIndexJumpsAndValidates() {
        val queue = PlayQueue()
        queue.setQueue(tracks(5), 0)
        assertEquals("v3", queue.playIndex(3)!!.videoId)
        assertEquals(3, queue.currentIndex)
        assertEquals("v4", queue.next()!!.videoId)
        assertNull(queue.playIndex(5))
        assertNull(queue.playIndex(-1))
    }

    @Test
    fun peekNextAgreesWithNextUnderShuffleRepeatAllAfterThePassEnds() {
        // Regression pin (H7 sweep): peekNext used to return null
        // once a shuffle pass was exhausted even under repeat ALL,
        // while next() reshuffled and kept playing. Any caller
        // asking "is there a next track?" (the player-error
        // recovery skip decision) got a false no.
        val queue = PlayQueue(Random(7))
        queue.setQueue(tracks(4), 0)
        queue.shuffle = true
        queue.repeatMode = RepeatMode.ALL
        // Drain the whole first pass.
        repeat(3) { queue.next() }
        val peeked = queue.peekNext()
        assertTrue("peekNext must see the reshuffle next under repeat ALL", peeked != null)
        assertTrue(
            "the reshuffle never starts with the track that just played",
            peeked!!.videoId != queue.current()!!.videoId,
        )
        assertTrue("next() must agree with peekNext here", queue.next() != null)
    }

    @Test
    fun peekNextStaysNullUnderShuffleRepeatOffAfterThePassEnds() {
        val queue = PlayQueue(Random(7))
        queue.setQueue(tracks(3), 0)
        queue.shuffle = true
        repeat(2) { queue.next() }
        assertNull(queue.peekNext())
        assertNull(queue.next())
    }

    @Test
    fun largeQueueShufflePassVisitsEveryTrackExactlyOnce() {
        // 100+ track queues (long playlists): one shuffle pass is
        // a full permutation — no repeats, no drops, and previous()
        // walks the whole history back.
        val queue = PlayQueue(Random(123))
        queue.setQueue(tracks(150), 0)
        queue.shuffle = true
        val visited = mutableListOf(queue.currentIndex)
        while (true) {
            queue.next() ?: break
            visited.add(queue.currentIndex)
        }
        assertEquals(150, visited.size)
        assertEquals((0 until 150).toSet(), visited.toSet())
        // Walk all the way back through actually-played history.
        repeat(149) {
            assertTrue("previous() must keep walking the history", queue.previous() != null)
        }
        assertEquals(visited.first(), queue.currentIndex)
    }

    @Test
    fun emptyQueueIsInert() {
        val queue = PlayQueue()
        queue.setQueue(emptyList(), 0)
        assertNull(queue.current())
        assertNull(queue.next())
        assertNull(queue.peekNext())
        assertNull(queue.previous())
        assertEquals(-1, queue.currentIndex)
    }
}
