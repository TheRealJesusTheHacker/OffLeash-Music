package dev.cyberdogs.offleash.core.playback

import dev.cyberdogs.offleash.core.model.Track
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chaos sweep round 2: property-style fuzzing of [PlayQueue].
 *
 * Long deterministic pseudo-random operation sequences (seeded, so
 * any failure reproduces exactly) over queues of size 1, 2, 3 and
 * 150, checking the structural invariants after EVERY operation:
 *
 * - the current index is in bounds and [PlayQueue.current] agrees
 *   with it;
 * - peekNext and next AGREE — same null-ness and, since round 2,
 *   the same track identity, including at the repeat-ALL shuffle
 *   reshuffle boundary;
 * - peek is stable and does not move the queue;
 * - a successful previous() is always redoable: next() returns the
 *   track just walked away from;
 * - insertNext's track is what next() plays, in both modes.
 *
 * Structured (non-fuzz) tests below pin the pass-level properties
 * the fuzz cannot see: a shuffle pass visits each track exactly
 * once, and appends / inserts / redundant shuffle writes must not
 * restart a pass already in flight.
 */
class PlayQueueChaosTest {

    private fun track(id: String) = Track(
        videoId = id,
        title = "Track $id",
        artists = listOf("Artist"),
        artistBrowseId = null,
        album = null,
        albumBrowseId = null,
        durationSeconds = 180,
        thumbnailUrl = null,
    )

    private fun tracks(n: Int, prefix: String = "v"): List<Track> =
        (0 until n).map { track("$prefix$it") }

    private enum class Op {
        NEXT, PREVIOUS, PEEK, TOGGLE_SHUFFLE, SET_SHUFFLE_ON,
        CYCLE_REPEAT, PLAY_INDEX, APPEND, INSERT_NEXT,
    }

    @Test
    fun fuzzRandomOperationSequencesKeepEveryInvariant() {
        for (size in listOf(1, 2, 3, 150)) {
            for (seed in listOf(1, 7, 42, 99, 1234)) {
                runFuzz(size = size, seed = seed, steps = if (size > 10) 300 else 500)
            }
        }
    }

    private fun runFuzz(size: Int, seed: Int, steps: Int) {
        val where = "size=$size seed=$seed"
        val queue = PlayQueue(Random(seed * 31 + size))
        val rng = Random(seed * 977 + size)
        queue.setQueue(tracks(size), rng.nextInt(size))
        var extra = 0
        repeat(steps) { step ->
            val at = "$where step=$step"
            val op = Op.entries[rng.nextInt(Op.entries.size)]
            when (op) {
                Op.NEXT -> {
                    val peeked = queue.peekNext()
                    val result = queue.next()
                    assertEquals(
                        "$at: peek/next null-agreement",
                        peeked == null, result == null,
                    )
                    if (peeked != null && result != null) {
                        assertEquals(
                            "$at: peek/next identity agreement",
                            peeked.videoId, result.videoId,
                        )
                        assertEquals("$at: next lands on current", result, queue.current())
                    }
                }
                Op.PREVIOUS -> {
                    val before = queue.current()
                    val beforeIndex = queue.currentIndex
                    val result = queue.previous()
                    if (result == null) {
                        assertEquals("$at: failed previous keeps current", before, queue.current())
                        assertEquals("$at: failed previous keeps index", beforeIndex, queue.currentIndex)
                    } else {
                        assertEquals("$at: previous lands on current", result, queue.current())
                        // Redo property: next() must return the track
                        // just walked away from.
                        val redo = queue.next()
                        assertEquals("$at: redo after previous", before?.videoId, redo?.videoId)
                        assertEquals("$at: redo restores index", beforeIndex, queue.currentIndex)
                    }
                }
                Op.PEEK -> {
                    val indexBefore = queue.currentIndex
                    val first = queue.peekNext()
                    val second = queue.peekNext()
                    assertEquals("$at: peek is stable", first?.videoId, second?.videoId)
                    assertEquals("$at: peek does not move the queue", indexBefore, queue.currentIndex)
                }
                Op.TOGGLE_SHUFFLE -> queue.shuffle = !queue.shuffle
                Op.SET_SHUFFLE_ON -> queue.shuffle = true
                Op.CYCLE_REPEAT -> queue.repeatMode = when (queue.repeatMode) {
                    RepeatMode.OFF -> RepeatMode.ALL
                    RepeatMode.ALL -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.OFF
                }
                Op.PLAY_INDEX -> {
                    val index = rng.nextInt(queue.tracks.size + 2) - 1
                    val before = queue.current()
                    val result = queue.playIndex(index)
                    if (index !in queue.tracks.indices) {
                        // playIndex may have shrunk nothing: an invalid
                        // index is a no-op. (tracks.indices here is the
                        // post-call range; the queue never resizes here.)
                        assertNull("$at: invalid playIndex returns null", result)
                        assertEquals("$at: invalid playIndex keeps current", before, queue.current())
                    } else {
                        assertEquals("$at: playIndex lands", index, queue.currentIndex)
                        assertEquals("$at: playIndex returns current", result, queue.current())
                    }
                }
                Op.APPEND -> {
                    val beforeIndex = queue.currentIndex
                    val before = queue.current()
                    queue.append(track("x${extra++}"))
                    assertEquals("$at: append keeps index", beforeIndex, queue.currentIndex)
                    assertEquals("$at: append keeps current", before, queue.current())
                }
                Op.INSERT_NEXT -> {
                    val inserted = track("n${extra++}")
                    queue.insertNext(inserted)
                    assertEquals(
                        "$at: inserted track sits at current+1",
                        inserted.videoId,
                        queue.tracks[queue.currentIndex + 1].videoId,
                    )
                    // "Play next" means NEXT — in both modes, under
                    // any repeat setting (the inserted index always
                    // exists, so next can never be at the end here).
                    val played = queue.next()
                    assertEquals("$at: insertNext is what next plays", inserted.videoId, played?.videoId)
                }
            }
            // Structural invariants after EVERY operation.
            assertTrue("$at: queue never empties in this fuzz", queue.tracks.isNotEmpty())
            assertTrue(
                "$at: current index in bounds (was ${queue.currentIndex})",
                queue.currentIndex in queue.tracks.indices,
            )
            assertEquals(
                "$at: current() agrees with currentIndex",
                queue.tracks[queue.currentIndex], queue.current(),
            )
        }
    }

    // -- Pass-level properties -------------------------------------------

    @Test
    fun reEnablingShuffleMidPassDoesNotRestartThePass() {
        // Minimal pin for the round-2 finding: the shuffle setter
        // rebuilt the pass on ANY write, so `shuffle = true` while
        // already on re-admitted tracks played earlier in the pass.
        val queue = PlayQueue(Random(42))
        queue.setQueue(tracks(10), 0)
        queue.shuffle = true
        val visited = mutableListOf(queue.currentIndex)
        repeat(3) { visited.add(queue.next()!!.let { queue.currentIndex }) }
        queue.shuffle = true // redundant write — must be a no-op
        queue.shuffle = true
        while (true) {
            queue.next() ?: break
            visited.add(queue.currentIndex)
        }
        assertEquals("the pass still visits each track exactly once", 10, visited.size)
        assertEquals((0 until 10).toSet(), visited.toSet())
    }

    @Test
    fun appendMidPassJoinsThePassWithoutRepeatsOrDrops() {
        // The old controller emulation (setQueue rebuild) restarted
        // the pass on every Add to queue. PlayQueue.append must not.
        val queue = PlayQueue(Random(7))
        queue.setQueue(tracks(5), 0)
        queue.shuffle = true
        val visited = mutableListOf(queue.current()!!.videoId)
        repeat(2) { visited.add(queue.next()!!.videoId) }
        queue.append(track("late"))
        while (true) {
            val next = queue.next() ?: break
            visited.add(next.videoId)
        }
        assertEquals(6, visited.size)
        assertEquals(setOf("v0", "v1", "v2", "v3", "v4", "late"), visited.toSet())
    }

    @Test
    fun appendAllAfterThePassEndsMakesTheNewTracksPlayableInOrder() {
        // Autoplay's shape: the queue is exhausted, then a
        // continuation lands. In shuffle the appended tracks join
        // as the pass tail, in append order.
        val queue = PlayQueue(Random(3))
        queue.setQueue(tracks(3), 0)
        queue.shuffle = true
        while (queue.next() != null) { /* drain */ }
        queue.appendAll(tracks(2, prefix = "c"))
        assertEquals("c0", queue.next()!!.videoId)
        assertEquals("c1", queue.next()!!.videoId)
        assertNull(queue.next())
        // And in linear mode the append is plain list order.
        val linear = PlayQueue()
        linear.setQueue(tracks(2), 0)
        linear.next()
        assertNull(linear.next())
        linear.appendAll(tracks(2, prefix = "c"))
        assertEquals("c0", linear.next()!!.videoId)
        assertEquals("c1", linear.next()!!.videoId)
        assertNull(linear.next())
    }

    @Test
    fun insertNextUnderShuffleIsHonoredAndThePassStaysComplete() {
        val queue = PlayQueue(Random(11))
        queue.setQueue(tracks(6), 0)
        queue.shuffle = true
        val visited = mutableListOf(queue.current()!!.videoId)
        visited.add(queue.next()!!.videoId)
        queue.insertNext(track("next-up"))
        assertEquals("next-up", queue.next()!!.videoId)
        visited.add("next-up")
        while (true) {
            val next = queue.next() ?: break
            visited.add(next.videoId)
        }
        assertEquals(7, visited.size)
        assertEquals((0 until 6).map { "v$it" }.toSet() + "next-up", visited.toSet())
    }

    @Test
    fun insertNextTruncatesForwardHistory() {
        // Walked back, then a branch: Play next must win over the
        // redo entries — next plays the inserted track, and the
        // track after it is the one the insertion shifted.
        val queue = PlayQueue()
        queue.setQueue(tracks(5), 0)
        queue.next() // v1
        queue.next() // v2
        assertEquals("v1", queue.previous()!!.videoId)
        queue.insertNext(track("branch"))
        assertEquals("branch", queue.next()!!.videoId)
        assertEquals("v2", queue.next()!!.videoId)
    }

    @Test
    fun peekAtTheReshuffleBoundaryNamesTheTrackNextPlays() {
        // Minimal pin for the round-2 finding: at a repeat-ALL pass
        // boundary, peek used to report the first non-current index
        // while next reshuffled at random — identity disagreement.
        for (seed in listOf(1, 2, 3, 5, 8, 13, 21)) {
            for (size in listOf(2, 3, 5)) {
                val queue = PlayQueue(Random(seed))
                queue.setQueue(tracks(size), 0)
                queue.shuffle = true
                queue.repeatMode = RepeatMode.ALL
                repeat(size - 1) { queue.next() } // drain the first pass
                val peeked = queue.peekNext()
                assertTrue("seed=$seed size=$size: a next exists", peeked != null)
                assertNotEquals(
                    "seed=$seed size=$size: reshuffle never starts with the track that just played",
                    queue.current()!!.videoId, peeked!!.videoId,
                )
                assertEquals(
                    "seed=$seed size=$size: peek is stable at the boundary",
                    peeked.videoId, queue.peekNext()!!.videoId,
                )
                assertEquals(
                    "seed=$seed size=$size: next plays the peeked track",
                    peeked.videoId, queue.next()!!.videoId,
                )
            }
        }
    }

    @Test
    fun repeatAllPassesAfterTheFirstAreDistinctBlocksWithNoImmediateRepeat() {
        val queue = PlayQueue(Random(42))
        val size = 5
        queue.setQueue(tracks(size), 0)
        queue.shuffle = true
        queue.repeatMode = RepeatMode.ALL
        val visited = mutableListOf(queue.currentIndex)
        repeat(3 * size) { visited.add(queue.next()!!.let { queue.currentIndex }) }
        for (i in 1 until visited.size) {
            assertNotEquals("no immediate repeat at $i", visited[i - 1], visited[i])
        }
        // First pass: every track once. Later passes are size-1
        // blocks (the track that just played sits the head out) and
        // are distinct within the block.
        assertEquals((0 until size).toSet(), visited.subList(0, size).toSet())
        assertEquals(size - 1, visited.subList(size, 2 * size - 1).toSet().size)
        assertEquals(size - 1, visited.subList(2 * size - 1, 3 * size - 2).toSet().size)
    }

    @Test
    fun historyWalkBackRetracesShufflePlayExactly() {
        val queue = PlayQueue(Random(99))
        queue.setQueue(tracks(10), 0)
        queue.shuffle = true
        val played = mutableListOf(queue.current()!!.videoId)
        repeat(6) { played.add(queue.next()!!.videoId) }
        for (expected in played.dropLast(1).reversed()) {
            assertEquals(expected, queue.previous()!!.videoId)
        }
        for (expected in played.drop(1)) {
            assertEquals(expected, queue.next()!!.videoId)
        }
    }

    @Test
    fun appendAndInsertOnAnEmptyQueueBecomeTheCurrentTrack() {
        val appended = PlayQueue()
        appended.append(track("only"))
        assertEquals("only", appended.current()!!.videoId)
        assertEquals(0, appended.currentIndex)

        val inserted = PlayQueue()
        inserted.insertNext(track("only"))
        assertEquals("only", inserted.current()!!.videoId)
        assertEquals(0, inserted.currentIndex)
    }
}
