package dev.cyberdogs.offleash.core.playback

import dev.cyberdogs.offleash.core.model.Track
import kotlin.random.Random

enum class RepeatMode { OFF, ALL, ONE }

/**
 * Pure play-queue logic (SPEC §4): order, shuffle and repeat semantics
 * live here so they are unit-testable without a player.
 *
 * Semantics:
 * - [next] advances one step. At the end of the queue it wraps to the
 *   start when [repeatMode] is [RepeatMode.ALL] and returns null when it
 *   is [RepeatMode.OFF].
 * - [RepeatMode.ONE] is the *player's* job (the player repeats the
 *   current item itself); the queue treats it like OFF — [next] still
 *   advances normally when the player asks for the next track.
 * - With [shuffle] on, playback walks a shuffled permutation of the
 *   queue indices with no repeats until the permutation is exhausted;
 *   with repeat ALL a fresh permutation is then shuffled (never starting
 *   with the track that just played, when the queue has more than one).
 * - [previous] walks back through the history of tracks actually played
 *   (which is what "previous" means under shuffle), and falls back to a
 *   plain index step in linear mode.
 */
class PlayQueue(
    private val random: Random = Random,
) {
    var tracks: List<Track> = emptyList()
        private set

    var currentIndex: Int = -1
        private set

    var repeatMode: RepeatMode = RepeatMode.OFF

    var shuffle: Boolean = false
        set(value) {
            // Re-assigning the value already in effect must NOT
            // reshuffle the remaining pass (chaos sweep round 2):
            // rebuilding here re-admitted tracks already played in
            // this pass and dropped the visit-each-exactly-once
            // guarantee for a no-op write. Callers like a "Shuffle"
            // button that unconditionally enables shuffle hit this.
            if (field == value) return
            field = value
            rebuildUpcoming()
        }

    /** Indices actually played, in play order; [historyPos] points at the current one. */
    private val history = mutableListOf<Int>()
    private var historyPos: Int = -1

    /** Indices still to come in the current shuffle pass. */
    private val upcoming = ArrayDeque<Int>()

    /**
     * The next shuffle pass, materialized early by [peekNext] at a
     * repeat-ALL pass boundary so the peeked track and the track
     * [next] actually plays are the SAME track (chaos sweep round
     * 2): previously peek reported the first non-current index while
     * next reshuffled at random, so the two disagreed on identity
     * exactly where the recovery skip decision reads the peek.
     * Consumed by [next] only while repeat ALL still holds;
     * discarded by any queue/pass rebuild.
     */
    private var pendingReshuffle: List<Int>? = null

    private fun generateReshufflePool(): List<Int> =
        tracks.indices.filter { it != currentIndex }.shuffled(random)

    private fun rebuildUpcoming() {
        upcoming.clear()
        pendingReshuffle = null
        if (shuffle && tracks.size > 1) {
            val pool = tracks.indices.filter { it != currentIndex }.shuffled(random)
            upcoming.addAll(pool)
        }
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        this.tracks = tracks
        history.clear()
        historyPos = -1
        if (tracks.isEmpty()) {
            currentIndex = -1
            upcoming.clear()
            pendingReshuffle = null
            return
        }
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        currentIndex = start
        history.add(start)
        historyPos = 0
        rebuildUpcoming()
    }

    fun current(): Track? = tracks.getOrNull(currentIndex)

    private fun moveTo(index: Int): Track {
        while (history.size > historyPos + 1) history.removeAt(history.lastIndex)
        history.add(index)
        historyPos++
        currentIndex = index
        return tracks[index]
    }

    fun next(): Track? {
        if (tracks.isEmpty() || currentIndex < 0) return null
        if (historyPos < history.lastIndex) {
            historyPos++
            currentIndex = history[historyPos]
            return current()
        }
        val candidate: Int = if (shuffle) {
            if (upcoming.isEmpty()) {
                if (repeatMode != RepeatMode.ALL) return null
                if (tracks.size == 1) return moveTo(currentIndex)
                val pool = pendingReshuffle ?: generateReshufflePool()
                pendingReshuffle = null
                upcoming.addAll(pool)
            }
            upcoming.removeFirst()
        } else {
            val nextIndex = currentIndex + 1
            when {
                nextIndex <= tracks.lastIndex -> nextIndex
                repeatMode == RepeatMode.ALL -> 0
                else -> return null
            }
        }
        return moveTo(candidate)
    }

    fun peekNext(): Track? {
        if (tracks.isEmpty() || currentIndex < 0) return null
        if (historyPos < history.lastIndex) return tracks[history[historyPos + 1]]
        return if (shuffle) {
            upcoming.firstOrNull()?.let { tracks[it] }
                ?: if (repeatMode == RepeatMode.ALL && tracks.size == 1) {
                    current()
                } else if (repeatMode == RepeatMode.ALL) {
                    // The pass is exhausted, but next() would
                    // reshuffle (excluding the current track) and
                    // keep playing — peek must agree that a next
                    // exists AND which track it is, so the reshuffle
                    // is materialized now and next() consumes exactly
                    // this pool.
                    val pool = pendingReshuffle
                        ?: generateReshufflePool().also { pendingReshuffle = it }
                    pool.firstOrNull()?.let { tracks[it] }
                } else {
                    null
                }
        } else {
            val nextIndex = currentIndex + 1
            when {
                nextIndex <= tracks.lastIndex -> tracks[nextIndex]
                repeatMode == RepeatMode.ALL -> tracks[0]
                else -> null
            }
        }
    }

    fun previous(): Track? {
        if (tracks.isEmpty() || currentIndex < 0) return null
        if (historyPos > 0) {
            historyPos--
            currentIndex = history[historyPos]
            return current()
        }
        // Nothing played before the current track in this session: in
        // linear mode "previous" still means the track before it.
        if (!shuffle && currentIndex > 0) {
            val target = currentIndex - 1
            history.add(0, target)
            historyPos = 0
            currentIndex = target
            return current()
        }
        return null
    }

    fun playIndex(index: Int): Track? {
        if (index !in tracks.indices) return null
        history.clear()
        history.add(index)
        historyPos = 0
        currentIndex = index
        rebuildUpcoming()
        return current()
    }

    /**
     * Appends [track] to the end of the queue WITHOUT disturbing
     * the current position, the played history, or the in-flight
     * shuffle pass (chaos sweep round 2). The controller used to
     * emulate this with [setQueue], which cleared the history —
     * previous() forgot everything played — and rebuilt the shuffle
     * pass from scratch, re-admitting tracks already played in the
     * pass and dropping ones still to come. Here the new index
     * simply joins the current pass at its tail (or the pending
     * reshuffle, when a repeat-ALL pass boundary was peeked), so
     * the visit-each-exactly-once guarantee survives the append; in
     * linear mode the list order alone makes it reachable.
     */
    fun append(track: Track) {
        if (tracks.isEmpty()) {
            setQueue(listOf(track), 0)
            return
        }
        val newIndex = tracks.size
        tracks = tracks + track
        if (shuffle) {
            val pending = pendingReshuffle
            if (pending != null) {
                pendingReshuffle = pending + newIndex
            } else {
                upcoming.addLast(newIndex)
            }
        }
    }

    /** Appends several tracks, each with [append]'s guarantees. */
    fun appendAll(newTracks: List<Track>) {
        for (track in newTracks) append(track)
    }

    /**
     * Inserts [track] so it is the very next thing played, in BOTH
     * modes (chaos sweep round 2). The controller's old rebuild
     * emulation only achieved that in linear mode: under shuffle
     * the rebuilt pass was shuffled at random, so "Play next" played
     * at a random point in the pass — or, with repeat off and the
     * pass partly played, possibly never before the pass ended.
     *
     * Inserting shifts every stored index at or after the insertion
     * point (history and upcoming hold indices), so those are
     * remapped. A forward history (the listener had walked back) is
     * truncated — inserting is a branch, exactly like [moveTo]'s —
     * so next() cannot detour through the redo entries first.
     */
    fun insertNext(track: Track) {
        if (tracks.isEmpty() || currentIndex < 0) {
            setQueue(listOf(track), 0)
            return
        }
        val insertAt = currentIndex + 1
        while (history.size > historyPos + 1) history.removeAt(history.lastIndex)
        for (i in history.indices) {
            if (history[i] >= insertAt) history[i] = history[i] + 1
        }
        val remappedUpcoming = upcoming.map { if (it >= insertAt) it + 1 else it }
        upcoming.clear()
        upcoming.addAll(remappedUpcoming)
        pendingReshuffle = pendingReshuffle?.map { if (it >= insertAt) it + 1 else it }
        tracks = tracks.toMutableList().apply { add(insertAt, track) }
        if (shuffle) {
            upcoming.addFirst(insertAt)
        }
    }
}
