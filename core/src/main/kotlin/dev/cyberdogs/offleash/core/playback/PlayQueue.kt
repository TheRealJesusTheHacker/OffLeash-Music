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
            field = value
            rebuildUpcoming()
        }

    /** Indices actually played, in play order; [historyPos] points at the current one. */
    private val history = mutableListOf<Int>()
    private var historyPos: Int = -1

    /** Indices still to come in the current shuffle pass. */
    private val upcoming = ArrayDeque<Int>()

    private fun rebuildUpcoming() {
        upcoming.clear()
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
                val pool = tracks.indices.filter { it != currentIndex }.shuffled(random)
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
                ?: if (repeatMode == RepeatMode.ALL && tracks.size == 1) current() else null
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
}
