package dev.cyberdogs.offleash.playback

import dev.cyberdogs.offleash.core.api.YoutubeiContexts
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
}
