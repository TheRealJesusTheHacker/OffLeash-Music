package dev.cyberdogs.offleash.playback

import dev.cyberdogs.offleash.core.api.YoutubeiContexts
import dev.cyberdogs.offleash.data.RangeInterceptor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The anonymous-road ladder's pure decisions: the probe verdict matrix
 * and the context rotation (coordinator amendment — the ladder rotates
 * contexts across ANONYMOUS_ROAD instead of re-resolving on one).
 */
class LadderDecisionTest {

    // -- Probe verdict ------------------------------------------------------

    @Test
    fun probe2xxProceedsOnAnyAttempt() {
        for (attempt in 1..LADDER_MAX_PAIRS) {
            assertEquals(LadderAction.PROCEED, ladderProbeVerdict(attempt, 200))
            assertEquals(LadderAction.PROCEED, ladderProbeVerdict(attempt, 206))
            assertEquals(LadderAction.PROCEED, ladderProbeVerdict(attempt, 204))
        }
    }

    @Test
    fun probe403ReResolvesUntilTheLastPair() {
        assertEquals(LadderAction.RE_RESOLVE, ladderProbeVerdict(1, 403))
        assertEquals(LadderAction.RE_RESOLVE, ladderProbeVerdict(2, 403))
        assertEquals(LadderAction.HAND_TO_PLAYER_ANYWAY, ladderProbeVerdict(3, 403))
    }

    @Test
    fun probeTransportFailureCountsAsRefusal() {
        // Null status = the probe itself failed in transport.
        assertEquals(LadderAction.RE_RESOLVE, ladderProbeVerdict(1, null))
        assertEquals(LadderAction.RE_RESOLVE, ladderProbeVerdict(2, null))
        assertEquals(LadderAction.HAND_TO_PLAYER_ANYWAY, ladderProbeVerdict(3, null))
    }

    @Test
    fun otherNon2xxStatusesFollowTheSameLadder() {
        assertEquals(LadderAction.RE_RESOLVE, ladderProbeVerdict(1, 404))
        assertEquals(LadderAction.RE_RESOLVE, ladderProbeVerdict(2, 500))
        assertEquals(LadderAction.HAND_TO_PLAYER_ANYWAY, ladderProbeVerdict(3, 500))
        // Past the ladder's end the answer stays HAND_TO_PLAYER_ANYWAY.
        assertEquals(LadderAction.HAND_TO_PLAYER_ANYWAY, ladderProbeVerdict(4, 403))
    }

    // -- Context rotation ---------------------------------------------------

    @Test
    fun rotationStartingFromIosIsIosAndroidIos() {
        val road = YoutubeiContexts.ANONYMOUS_ROAD
        assertEquals(
            YoutubeiContexts.IOS,
            ladderContextFor(0, road, YoutubeiContexts.IOS),
        )
        assertEquals(
            YoutubeiContexts.ANDROID,
            ladderContextFor(1, road, YoutubeiContexts.IOS),
        )
        assertEquals(
            YoutubeiContexts.IOS,
            ladderContextFor(2, road, YoutubeiContexts.IOS),
        )
    }

    @Test
    fun rotationStartingFromAndroidIsAndroidIosAndroid() {
        val road = YoutubeiContexts.ANONYMOUS_ROAD
        assertEquals(
            YoutubeiContexts.ANDROID,
            ladderContextFor(0, road, YoutubeiContexts.ANDROID),
        )
        assertEquals(
            YoutubeiContexts.IOS,
            ladderContextFor(1, road, YoutubeiContexts.ANDROID),
        )
        assertEquals(
            YoutubeiContexts.ANDROID,
            ladderContextFor(2, road, YoutubeiContexts.ANDROID),
        )
    }

    @Test
    fun rotationWrapsAndUnknownStartFallsBackToRoadHead() {
        val road = YoutubeiContexts.ANONYMOUS_ROAD
        // Attempt index 3 wraps back to the second context from iOS.
        assertEquals(
            YoutubeiContexts.ANDROID,
            ladderContextFor(3, road, YoutubeiContexts.IOS),
        )
        // A start context outside the road starts at the road's head.
        assertEquals(
            road.first(),
            ladderContextFor(0, road, YoutubeiContexts.WEB_REMIX),
        )
        assertEquals(
            road[1],
            ladderContextFor(1, road, YoutubeiContexts.WEB_REMIX),
        )
    }

    // -- Window descent (round 3) --------------------------------------------

    @Test
    fun window403DescendsBeforeAnyReResolve() {
        // Within one resolution: 1 MiB refused -> descend, 256 KiB
        // refused -> descend, 64 KiB refused -> descend, 2 KiB is
        // the last rung: refused there, the descent stops and the
        // pair verdict takes over.
        val count = RangeInterceptor.STREAM_WINDOW_LADDER.size
        assertEquals(WindowAction.DESCEND, windowProbeVerdict(0, count, 403))
        assertEquals(WindowAction.DESCEND, windowProbeVerdict(1, count, 403))
        assertEquals(WindowAction.DESCEND, windowProbeVerdict(2, count, 403))
        assertEquals(WindowAction.STOP, windowProbeVerdict(3, count, 403))
    }

    @Test
    fun window2xxWinsAtAnyRungAndStopsTheDescent() {
        val count = RangeInterceptor.STREAM_WINDOW_LADDER.size
        for (index in 0 until count) {
            assertEquals(WindowAction.WINNER, windowProbeVerdict(index, count, 206))
            assertEquals(WindowAction.WINNER, windowProbeVerdict(index, count, 200))
        }
    }

    @Test
    fun windowDescentStopsAtTheFirst206() {
        // The descent order as the ladder runs it: walk the verdicts
        // for 403, 403, 206 and the walk must visit exactly the
        // first three rungs, in ladder order, and stop at the win.
        val ladder = RangeInterceptor.STREAM_WINDOW_LADDER
        val answers = listOf(403, 403, 206)
        val visited = mutableListOf<Long>()
        var winner: Long? = null
        for (index in ladder.indices) {
            visited += ladder[index]
            when (windowProbeVerdict(index, ladder.size, answers[index])) {
                WindowAction.WINNER -> {
                    winner = ladder[index]
                    break
                }
                WindowAction.DESCEND -> continue
                WindowAction.STOP -> break
            }
        }
        assertEquals(listOf(1048576L, 262144L, 65536L), visited)
        assertEquals(65536L, winner)
    }

    @Test
    fun non403RefusalsDoNotDescend() {
        // Shrinking the window is the answer to a 403-shaped
        // refusal only; other refusals and transport failures stop
        // the descent and let the pair verdict decide.
        val count = RangeInterceptor.STREAM_WINDOW_LADDER.size
        assertEquals(WindowAction.STOP, windowProbeVerdict(0, count, 404))
        assertEquals(WindowAction.STOP, windowProbeVerdict(0, count, 500))
        assertEquals(WindowAction.STOP, windowProbeVerdict(1, count, null))
    }
}
