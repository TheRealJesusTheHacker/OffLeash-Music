package dev.cyberdogs.offleash.playback

import dev.cyberdogs.offleash.core.model.LyricsLine
import dev.cyberdogs.offleash.core.model.LyricsResult

/**
 * Lyrics line-sync selection and the in-memory lyrics cache (Lane G),
 * pure so both are unit-testable without Android.
 */

/**
 * The index of the lyric line current at [positionMs]: the LAST timed
 * line whose start is at or before the position.
 *
 * Returns -1 when there is nothing honest to highlight: no lines, no
 * timed lines at all (plain/unsynced lyrics — those scroll statically
 * and no line is ever claimed current), or a position before the first
 * timed line. A position after the last timed line returns the last
 * timed line's index — the final line stays current until the track
 * ends. Untimed lines interleaved between timed ones are simply never
 * selected.
 */
fun currentLyricsIndex(lines: List<LyricsLine>, positionMs: Long): Int {
    var current = -1
    for ((index, line) in lines.withIndex()) {
        val start = line.startMs ?: continue
        if (start <= positionMs) {
            current = index
        } else {
            break
        }
    }
    return current
}

/** True when at least one line carries a timing (i.e. the lines can sync). */
fun lyricsAreSynced(lines: List<LyricsLine>): Boolean = lines.any { it.startMs != null }

/**
 * Per-track in-memory lyrics cache: a small access-ordered LRU keyed
 * by videoId. Lyrics fetches are async decorations on playback; this
 * cache keeps re-opening the player (or replaying a track) from
 * re-fetching what this process already learned — including an honest
 * [LyricsResult.Unavailable], so a track with no lyrics is not
 * re-probed on every open.
 */
class LyricsCache(private val maxEntries: Int = 24) {

    private val entries = object : LinkedHashMap<String, LyricsResult>(
        /* initialCapacity = */ 16,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LyricsResult>,
        ): Boolean = size > maxEntries
    }

    @Synchronized
    operator fun get(videoId: String): LyricsResult? = entries[videoId]

    @Synchronized
    fun put(videoId: String, result: LyricsResult) {
        entries[videoId] = result
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size
}
