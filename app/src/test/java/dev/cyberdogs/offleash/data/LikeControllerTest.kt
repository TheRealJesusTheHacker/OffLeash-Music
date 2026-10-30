package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Like/unlike round trips (bug sweep round 1), driven through the
 * real controller and client against a latch-controlled transport:
 *
 * - a failed write rolls the UI back to the account-confirmed value
 *   and names the error;
 * - rapid toggle mashing serializes to the LAST intent (the old
 *   drop-the-second-tap rule ended like→unlike as liked);
 * - the account's own likeStatus report corrects a stale local
 *   record after relaunch — but never over a write in flight;
 * - signed-out toggles are device-only: persisted locally, and no
 *   request ever leaves.
 */
class LikeControllerTest {

    private val track = Track(
        videoId = "vid1",
        title = "Song",
        artists = listOf("Artist"),
        artistBrowseId = null,
        album = null,
        albumBrowseId = null,
        durationSeconds = 100,
        thumbnailUrl = null,
    )

    private val session = AuthSession(cookies = mapOf("SAPISID" to "hash-source"))

    /**
     * Records like writes in order. When [gate] is set, each write
     * blocks until the test releases it, so toggles can land while a
     * write is genuinely in flight. Player calls answer [likeStatus].
     */
    private class LikeTransport(
        var failWrites: Boolean = false,
        var likeStatus: String = "INDIFFERENT",
    ) : HttpTransport {
        val writes = CopyOnWriteArrayList<Boolean>()
        @Volatile var gate: CountDownLatch? = null
        @Volatile var writeStarted: CountDownLatch? = null
        @Volatile var playerGate: CountDownLatch? = null
        @Volatile var playerStarted: CountDownLatch? = null

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            return when {
                url.contains("like/like") || url.contains("like/removelike") -> {
                    writes.add(url.contains("like/like"))
                    writeStarted?.countDown()
                    gate?.await(10, TimeUnit.SECONDS)
                    if (failWrites) {
                        HttpResponse(503, """{"error": {"message": "down"}}""")
                    } else {
                        HttpResponse(200, "{}")
                    }
                }
                url.contains("player") -> {
                    playerStarted?.countDown()
                    playerGate?.await(10, TimeUnit.SECONDS)
                    HttpResponse(200, """{"likeStatus": "$likeStatus"}""")
                }
                else -> HttpResponse(200, "{}")
            }
        }
    }

    /**
     * Round 39: a store that lands only the first [allowed]
     * writes and drops the rest — a process death mid-persist,
     * replayed deterministically. The record/provenance pair
     * used to be two writes, so a death between them was a
     * state this store can construct exactly.
     */
    private class CrashStore(
        private val inner: InMemoryKeyValueStore,
        private val allowed: Int,
    ) : KeyValueStore {
        @Volatile var attempts: Int = 0
            private set

        override fun getString(key: String): String? = inner.getString(key)

        override fun putString(key: String, value: String) {
            attempts += 1
            if (attempts <= allowed) inner.putString(key, value)
        }
    }

    private fun controller(
        transport: LikeTransport,
        signedIn: Boolean = true,
        seedLiked: Set<String> = emptySet(),
    ): Pair<LikeController, AppPreferences> {
        val preferences = AppPreferences(InMemoryKeyValueStore())
        if (seedLiked.isNotEmpty()) preferences.setLocalLikedVideoIds(seedLiked)
        val controller = LikeController(
            client = YoutubeiClient(transport),
            preferences = preferences,
            sessionProvider = { if (signedIn) session else null },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        return controller to preferences
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for: $label")
            }
            Thread.sleep(10)
        }
    }

    @Test
    fun successfulLikeWritesOnceAndSettles() {
        val transport = LikeTransport()
        val (controller, preferences) = controller(transport)
        controller.toggle(track)
        await("write recorded") { transport.writes.toList() == listOf(true) }
        await("settled") { !controller.stateFor("vid1").pending }
        assertTrue(controller.isLiked("vid1"))
        assertEquals(setOf("vid1"), preferences.localLikedVideoIds())
    }

    @Test
    fun failedWriteRollsBackToTheConfirmedValueAndNamesTheError() {
        val transport = LikeTransport(failWrites = true)
        val (controller, _) = controller(transport, seedLiked = setOf("vid1"))
        assertTrue(controller.isLiked("vid1"))
        controller.toggle(track) // unlike attempt, will fail
        await("error named") { controller.error.value != null }
        await("settled") { !controller.stateFor("vid1").pending }
        assertTrue("rollback restores the confirmed liked state", controller.isLiked("vid1"))
        assertNotNull(controller.error.value)
    }

    @Test
    fun mashingSerializesToTheLastIntent() {
        val transport = LikeTransport()
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        transport.gate = gate
        transport.writeStarted = started
        val (controller, _) = controller(transport)

        controller.toggle(track) // like — write starts and blocks
        assertTrue("first write in flight", started.await(10, TimeUnit.SECONDS))
        controller.toggle(track) // unlike — the last intent so far
        controller.toggle(track) // like again — the final intent
        gate.countDown()

        await("settled on final intent") {
            !controller.stateFor("vid1").pending && controller.isLiked("vid1")
        }
        // The account converged to the last intent. The blocked first
        // write already carried "like", so no second write was owed.
        assertEquals(listOf(true), transport.writes.toList())
        transport.gate = null

        // Now mash the other way: unlike in flight, then like, then
        // unlike — the intents net out to the in-flight write, so
        // the account still ends at the last intent with no
        // follow-up owed.
        val gate2 = CountDownLatch(1)
        val started2 = CountDownLatch(1)
        transport.gate = gate2
        transport.writeStarted = started2
        controller.toggle(track) // unlike — blocks
        assertTrue("second sequence in flight", started2.await(10, TimeUnit.SECONDS))
        controller.toggle(track) // like
        controller.toggle(track) // unlike — final intent
        gate2.countDown()
        await("settled unliked") {
            !controller.stateFor("vid1").pending && !controller.isLiked("vid1")
        }
        assertEquals(listOf(true, false), transport.writes.toList())
    }

    @Test
    fun intentFlippedDuringFlightGetsItsOwnFollowUpWrite() {
        val transport = LikeTransport()
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        transport.gate = gate
        transport.writeStarted = started
        val (controller, _) = controller(transport)
        controller.toggle(track) // like — blocks in flight
        assertTrue(started.await(10, TimeUnit.SECONDS))
        controller.toggle(track) // unlike — differs from the in-flight write
        gate.countDown()
        // The drain must write BOTH, in order, and settle unliked:
        // the pre-sweep code dropped the second tap entirely and the
        // account stayed liked.
        await("both writes landed in order") {
            transport.writes.toList() == listOf(true, false)
        }
        await("settled") { !controller.stateFor("vid1").pending }
        assertFalse(controller.isLiked("vid1"))
    }

    @Test
    fun queuedIntentBehindAFailedWriteRollsBackWithIt() {
        val transport = LikeTransport(failWrites = true)
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        transport.gate = gate
        transport.writeStarted = started
        val (controller, _) = controller(transport)
        controller.toggle(track) // like — will fail
        assertTrue(started.await(10, TimeUnit.SECONDS))
        controller.toggle(track) // unlike intent queued behind it
        controller.toggle(track) // like intent queued behind it
        gate.countDown()
        await("error named") { controller.error.value != null }
        await("settled") { !controller.stateFor("vid1").pending }
        // Nothing was ever confirmed; the UI returns to unliked and
        // only the one (failed) write was attempted for the burst.
        assertFalse(controller.isLiked("vid1"))
        assertEquals(listOf(true), transport.writes.toList())
    }

    @Test
    fun accountReportCorrectsAStaleLocalRecord() {
        // Relaunch divergence: the device record says liked, the
        // account says INDIFFERENT (unliked elsewhere). The player
        // refresh must correct the UI to the account's truth.
        val transport = LikeTransport(likeStatus = "INDIFFERENT")
        val (controller, preferences) = controller(transport, seedLiked = setOf("vid1"))
        assertTrue(controller.isLiked("vid1"))
        controller.refreshAccountStatus(track)
        await("corrected") { !controller.isLiked("vid1") }
        assertEquals(emptySet<String>(), preferences.localLikedVideoIds())

        transport.likeStatus = "LIKE"
        controller.refreshAccountStatus(track)
        await("corrected back") { controller.isLiked("vid1") }
        assertEquals(setOf("vid1"), preferences.localLikedVideoIds())
    }

    @Test
    fun accountReportNeverOverridesAWriteInFlight() {
        val transport = LikeTransport(likeStatus = "INDIFFERENT")
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        transport.gate = gate
        transport.writeStarted = started
        val (controller, _) = controller(transport)
        controller.toggle(track) // like — blocks in flight
        assertTrue(started.await(10, TimeUnit.SECONDS))
        controller.refreshAccountStatus(track) // stale report: INDIFFERENT
        // Give the refresh its chance to (wrongly) land.
        Thread.sleep(300)
        assertTrue("the in-flight intent must not be flipped", controller.isLiked("vid1"))
        gate.countDown()
        await("settled") { !controller.stateFor("vid1").pending }
        assertTrue(controller.isLiked("vid1"))
        assertEquals(listOf(true), transport.writes.toList())
    }

    @Test
    fun staleReportLandedAfterTheToggleDrainSettledDoesNotRevertTheLike() {
        // Round 6 (area E) — the residual Round 5 flagged unproven,
        // proven deterministically: the refresh's player fetch
        // STARTS before the toggle and its (pre-toggle) snapshot —
        // INDIFFERENT — is held in the transport while the toggle's
        // write drain runs to completion (pending cleared, account
        // liked). When the held response is finally delivered, the
        // old code's only guard was `pending`, which the completed
        // drain had already cleared, so the stale report applied:
        // the heart flipped back to unliked, the local record was
        // rewritten unliked, and confirmedLiked was overwritten
        // with the stale value — while the account actually held
        // the like. A report whose fetch began before a toggle
        // must never apply over that toggle.
        val transport = LikeTransport(likeStatus = "INDIFFERENT")
        val playerGate = CountDownLatch(1)
        val playerStarted = CountDownLatch(1)
        transport.playerGate = playerGate
        transport.playerStarted = playerStarted
        val (controller, preferences) = controller(transport)
        controller.refreshAccountStatus(track)
        assertTrue(
            "refresh fetch in flight",
            playerStarted.await(10, TimeUnit.SECONDS),
        )
        controller.toggle(track) // like — the drain runs and settles
        await("like settled") {
            !controller.stateFor("vid1").pending && controller.isLiked("vid1")
        }
        assertEquals(listOf(true), transport.writes.toList())
        // Deliver the stale pre-toggle snapshot now.
        playerGate.countDown()
        // Give the refresh its chance to (wrongly) land, in the
        // style of accountReportNeverOverridesAWriteInFlight.
        Thread.sleep(500)
        assertTrue(
            "a stale report must not revert a like the account confirmed",
            controller.isLiked("vid1"),
        )
        assertEquals(setOf("vid1"), preferences.localLikedVideoIds())
        assertEquals(listOf(true), transport.writes.toList())
    }

    @Test
    fun accountReportLandedAfterSignOutDoesNotResurrectTheOldAccountsLike() {
        // Round 6, closing the r6b census flag: refreshAccountStatus
        // captures its authorization at call time and (before the
        // session-generation guard) applied with only the pending
        // and intent-version checks — neither of which a sign-out
        // moves. Hold the player fetch, flip the controller to
        // signed-out exactly as the container does when the session
        // ends (setAccountBacked), then deliver the old account's
        // LIKED report: it must not apply — no accountBacked
        // resurrection over the owner's sign-out, and the old
        // account's like must not be persisted into the
        // device-local record.
        val transport = LikeTransport(likeStatus = "LIKED")
        val playerGate = CountDownLatch(1)
        val playerStarted = CountDownLatch(1)
        transport.playerGate = playerGate
        transport.playerStarted = playerStarted
        val (controller, preferences) = controller(transport)
        controller.refreshAccountStatus(track)
        assertTrue(
            "refresh fetch in flight",
            playerStarted.await(10, TimeUnit.SECONDS),
        )
        controller.setAccountBacked(false) // the container's sign-out flip
        playerGate.countDown()
        // Give the refresh its chance to (wrongly) land.
        Thread.sleep(500)
        assertFalse(
            "a stale report must not resurrect account backing after sign-out",
            controller.stateFor("vid1").accountBacked,
        )
        assertFalse(controller.isLiked("vid1"))
        assertEquals(emptySet<String>(), preferences.localLikedVideoIds())
    }

    @Test
    fun accountReportLandedAfterASessionSwapDoesNotApplyUnderTheNewAccount() {
        // The same guard, the other direction: sign-out followed
        // by a NEW sign-in while the old account's fetch is still
        // out. The report was authorized by the old session; under
        // the new account it is stale on arrival and must drop —
        // the new account's own refresh will read its own truth.
        val transport = LikeTransport(likeStatus = "LIKED")
        val playerGate = CountDownLatch(1)
        val playerStarted = CountDownLatch(1)
        transport.playerGate = playerGate
        transport.playerStarted = playerStarted
        val (controller, preferences) = controller(transport)
        controller.refreshAccountStatus(track)
        assertTrue(
            "refresh fetch in flight",
            playerStarted.await(10, TimeUnit.SECONDS),
        )
        controller.setAccountBacked(false) // old session ends
        controller.setAccountBacked(true) // a new session begins
        playerGate.countDown()
        Thread.sleep(500)
        assertFalse(
            "the old account's report must not apply under the new account",
            controller.isLiked("vid1"),
        )
        assertEquals(emptySet<String>(), preferences.localLikedVideoIds())
    }

    // -- Round 36: the Liked Songs page seeds what it proves -------
    //
    // Every track on the account's Liked Songs page is liked by
    // construction, but until round 36 nothing told the controller:
    // its only teacher was the player's per-track status fetch, so
    // an unplayed track on that page showed "Like" and its first
    // tap re-liked instead of unliking.

    @Test
    fun likedPageSeedMarksUnknownTracksLiked() {
        val transport = LikeTransport()
        val (controller, preferences) = controller(transport)
        assertFalse(controller.isLiked("vid1"))
        controller.noteAccountLiked(listOf(track))
        assertTrue(controller.isLiked("vid1"))
        assertTrue(controller.stateFor("vid1").accountBacked)
        assertFalse(controller.stateFor("vid1").pending)
        assertEquals(setOf("vid1"), preferences.localLikedVideoIds())
        // Seeding is a state correction, never a write.
        assertEquals(emptyList<Boolean>(), transport.writes.toList())
    }

    @Test
    fun toggleAfterLikedPageSeedUnlikesInOneTap() {
        val transport = LikeTransport()
        val (controller, _) = controller(transport)
        controller.noteAccountLiked(listOf(track))
        controller.toggle(track) // the page's unlike tap
        await("unlike written") { transport.writes.toList() == listOf(false) }
        await("settled") { !controller.stateFor("vid1").pending }
        assertFalse(controller.isLiked("vid1"))
    }

    @Test
    fun likedPageSeedNeverOverridesAnExistingState() {
        val transport = LikeTransport()
        val (controller, _) = controller(transport)
        // A confirmed in-session unlike leaves a present, unliked
        // entry — the page snapshot that lands afterwards predates
        // it and must not resurrect the like.
        controller.toggle(track)
        await("like written") { transport.writes.toList() == listOf(true) }
        controller.toggle(track)
        await("unlike written") { transport.writes.toList() == listOf(true, false) }
        await("settled") { !controller.stateFor("vid1").pending }
        assertFalse(controller.isLiked("vid1"))
        controller.noteAccountLiked(listOf(track))
        assertFalse(controller.isLiked("vid1"))
    }

    // -- Round 37: the seed is account-scoped and versioned -------
    //
    // Round 36's seed filled a genuine gap, but it took no part in
    // the controller's session discipline: entries are keyed by
    // videoId alone, setAccountBacked cleared nothing, and the
    // seed bumped no intent version. Two holes followed.

    @Test
    fun likedPageSeedDoesNotSurviveASessionFlip() {
        // (a) IDENTITY: a seed is one account's page asserting its
        // likes. Sign out and in as someone else (the container's
        // flips), and the first account's seeded entry must not
        // survive to be presented — accountBacked once the new
        // session lands — as the new account's like. (The refresh
        // road got exactly this protection in round 6, via the
        // session generation; the seed had none.)
        val transport = LikeTransport()
        val (controller, preferences) = controller(transport)
        controller.noteAccountLiked(listOf(track))
        assertTrue(controller.isLiked("vid1"))
        assertEquals(setOf("vid1"), preferences.localLikedVideoIds())
        controller.setAccountBacked(false) // sign-out
        controller.setAccountBacked(true) // a different account signs in
        assertFalse(
            "the previous account's seeded like must not present as the new account's",
            controller.isLiked("vid1"),
        )
        assertEquals(
            "the seed's device-local record must be dropped with it",
            emptySet<String>(),
            preferences.localLikedVideoIds(),
        )
    }

    @Test
    fun deviceLocalEntrySurvivesASessionFlip() {
        // The flip-drop above must be scoped to SEEDED entries: a
        // like that was already in the device-local record before
        // any seed (a device-only like, or a previous process's
        // record) is the device's own state, not one account's
        // page snapshot, and flips must not erase it.
        val transport = LikeTransport()
        val (controller, _) = controller(transport, seedLiked = setOf("vid1"))
        assertTrue(controller.isLiked("vid1"))
        controller.setAccountBacked(false)
        controller.setAccountBacked(true)
        assertTrue(controller.isLiked("vid1"))
    }

    // -- Round 38: seed provenance survives process death --------
    //
    // Round 37's provenance set lived in the controller's memory
    // only, but the seed persists the seeded id into the
    // device-local record. A relaunched controller therefore
    // loaded the id as an ordinary device-local entry with an
    // empty provenance set: the flip drop never fired, and the
    // previous account's seeded like presented as the new
    // account's after any restart that landed between the seed
    // and the account switch. The provenance is now persisted
    // beside the record and restored at construction.

    @Test
    fun likedPageSeedDoesNotSurviveASessionFlipAfterARelaunch() {
        val transport = LikeTransport()
        val preferences = AppPreferences(InMemoryKeyValueStore())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = LikeController(
            client = YoutubeiClient(transport),
            preferences = preferences,
            sessionProvider = { session },
            scope = scope,
        )
        first.noteAccountLiked(listOf(track))
        assertTrue(first.isLiked("vid1"))
        assertEquals(setOf("vid1"), preferences.localLikedVideoIds())
        // Process death + relaunch: a fresh controller over the
        // same store. The entry loads liked (it is in the local
        // record), but its provenance must load with it.
        val relaunched = LikeController(
            client = YoutubeiClient(transport),
            preferences = preferences,
            sessionProvider = { session },
            scope = scope,
        )
        assertTrue(relaunched.isLiked("vid1"))
        relaunched.setAccountBacked(false) // sign-out
        relaunched.setAccountBacked(true) // a different account signs in
        assertFalse(
            "the previous account's seeded like must not present as the new " +
                "account's after a relaunch either",
            relaunched.isLiked("vid1"),
        )
        assertEquals(
            "the seed's device-local record must be dropped with it after a relaunch",
            emptySet<String>(),
            preferences.localLikedVideoIds(),
        )
        assertEquals(emptySet<String>(), preferences.seededLikedVideoIds())
    }

    @Test
    fun toggleTakeoverOfASeedSurvivesARelaunch() {
        // The persistence must not over-clear the other way: a
        // toggle takes over a seeded entry's provenance (the
        // entry is then the user's own intent), and that takeover
        // is persisted too — after a relaunch and a session flip
        // the entry is no longer a seed and must stand.
        val transport = LikeTransport()
        val preferences = AppPreferences(InMemoryKeyValueStore())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = LikeController(
            client = YoutubeiClient(transport),
            preferences = preferences,
            sessionProvider = { session },
            scope = scope,
        )
        first.noteAccountLiked(listOf(track))
        assertEquals(setOf("vid1"), preferences.seededLikedVideoIds())
        val relaunched = LikeController(
            client = YoutubeiClient(transport),
            preferences = preferences,
            sessionProvider = { session },
            scope = scope,
        )
        relaunched.toggle(track) // unlike — takes over the provenance
        await("unlike written") { transport.writes.toList() == listOf(false) }
        await("settled") { !relaunched.stateFor("vid1").pending }
        relaunched.toggle(track) // like again — the user's own intent
        await("like written") { transport.writes.toList() == listOf(false, true) }
        await("settled") { !relaunched.stateFor("vid1").pending }
        assertEquals(emptySet<String>(), preferences.seededLikedVideoIds())
        val relaunchedAgain = LikeController(
            client = YoutubeiClient(transport),
            preferences = preferences,
            sessionProvider = { session },
            scope = scope,
        )
        relaunchedAgain.setAccountBacked(false)
        relaunchedAgain.setAccountBacked(true)
        assertTrue(
            "an entry whose provenance a toggle took over is not a seed and must stand",
            relaunchedAgain.isLiked("vid1"),
        )
        assertEquals(setOf("vid1"), preferences.localLikedVideoIds())
    }

    // Round 39: the persisted provenance pair was two keys
    // written by two calls, so every mutation path had a
    // process-death window between the writes. On the seed
    // path (record first, provenance second) the window left
    // a seeded id in the record with NO provenance: the
    // round-38 leak in miniature — after the relaunch the id
    // loaded as an ordinary device-local like and the flip
    // drop never fired. On the refresh-takeover path (also
    // record first) the window left provenance naming an
    // entry the account's own report had taken over, and a
    // later flip dropped that entry as if it were still a
    // seed. The pair now persists as one stored value
    // (AppPreferences.setLikedState), so a crash keeps the
    // whole previous pair or the whole next one. Both pins
    // replay the death with a write-counting store: on the
    // two-write code the first pin's seed loses its
    // provenance write and the second pin's takeover loses
    // both of its writes, and each fails at its headline
    // assert.

    @Test
    fun seedPairSurvivesACrashBetweenTheRecordAndProvenanceWrites() {
        val transport = LikeTransport()
        val inner = InMemoryKeyValueStore()
        // Exactly one write may land: the seed's persist. As
        // two writes, the record landed and the provenance
        // died with the process.
        val crash = CrashStore(inner, allowed = 1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = LikeController(
            client = YoutubeiClient(transport),
            preferences = AppPreferences(crash),
            sessionProvider = { session },
            scope = scope,
        )
        first.noteAccountLiked(listOf(track))
        val landed = AppPreferences(inner)
        assertEquals(setOf("vid1"), landed.localLikedVideoIds())
        assertEquals(
            "the seed's provenance must land in the same single " +
                "write as its record — a crash may not split them",
            setOf("vid1"),
            landed.seededLikedVideoIds(),
        )
        val relaunched = LikeController(
            client = YoutubeiClient(transport),
            preferences = landed,
            sessionProvider = { session },
            scope = scope,
        )
        assertTrue(relaunched.isLiked("vid1"))
        relaunched.setAccountBacked(false)
        relaunched.setAccountBacked(true)
        assertFalse(
            "a seeded like whose provenance write died must not " +
                "present as the new account's after a relaunch",
            relaunched.isLiked("vid1"),
        )
        assertEquals(emptySet<String>(), landed.localLikedVideoIds())
    }

    @Test
    fun refreshTakeoverSurvivesACrashBetweenTheRecordAndProvenanceWrites() {
        val transport = LikeTransport(likeStatus = "LIKE")
        val inner = InMemoryKeyValueStore()
        // Two writes may land: the seed's persist and the
        // applied refresh's persist. As two writes each, the
        // takeover's writes both died — and with the record
        // write alone landed (the wider window), the stale
        // provenance dropped a refresh-owned entry as a seed.
        val crash = CrashStore(inner, allowed = 2)
        val crashPrefs = AppPreferences(crash)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = LikeController(
            client = YoutubeiClient(transport),
            preferences = crashPrefs,
            sessionProvider = { session },
            scope = scope,
        )
        first.noteAccountLiked(listOf(track))
        first.refreshAccountStatus(track)
        await("refresh persist attempted") { crash.attempts >= 2 }
        val landed = AppPreferences(inner)
        assertEquals(
            "the refresh's takeover of the seed's provenance " +
                "must persist with its record",
            emptySet<String>(),
            landed.seededLikedVideoIds(),
        )
        val relaunched = LikeController(
            client = YoutubeiClient(transport),
            preferences = landed,
            sessionProvider = { session },
            scope = scope,
        )
        assertTrue(relaunched.isLiked("vid1"))
        relaunched.setAccountBacked(false)
        relaunched.setAccountBacked(true)
        assertTrue(
            "an entry an applied refresh took over is not a seed " +
                "and must stand after a relaunch and a flip",
            relaunched.isLiked("vid1"),
        )
        assertEquals(setOf("vid1"), landed.localLikedVideoIds())
    }

    @Test
    fun staleRefreshLandedAfterALikedPageSeedDoesNotOverrideTheSeed() {
        // (b) PENDING RACE, refresh side: a likeStatus fetch that
        // started BEFORE the Liked page landed carries the
        // pre-seed account view. The round-6 intent-version guard
        // drops reports that predate a TOGGLE; the seed bumped no
        // version, so the stale report applied over the fresher
        // seed and un-liked, in the UI, a track the account's own
        // Liked page had just proved liked.
        val transport = LikeTransport(likeStatus = "INDIFFERENT")
        val playerGate = CountDownLatch(1)
        val playerStarted = CountDownLatch(1)
        transport.playerGate = playerGate
        transport.playerStarted = playerStarted
        val (controller, _) = controller(transport)
        controller.refreshAccountStatus(track)
        assertTrue(
            "refresh fetch in flight",
            playerStarted.await(10, TimeUnit.SECONDS),
        )
        controller.noteAccountLiked(listOf(track))
        assertTrue(controller.isLiked("vid1"))
        playerGate.countDown()
        Thread.sleep(500)
        assertTrue(
            "a refresh fetched before the seed must not override the seed",
            controller.isLiked("vid1"),
        )
    }

    @Test
    fun likedPageSeedSignedOutIsANoOp() {
        val transport = LikeTransport()
        val (controller, preferences) = controller(transport, signedIn = false)
        controller.noteAccountLiked(listOf(track))
        assertFalse(controller.isLiked("vid1"))
        assertEquals(emptySet<String>(), preferences.localLikedVideoIds())
    }

    @Test
    fun signedOutToggleIsDeviceOnlyAndSendsNothing() {
        val transport = LikeTransport()
        val (controller, preferences) = controller(transport, signedIn = false)
        controller.toggle(track)
        assertTrue(controller.isLiked("vid1"))
        assertFalse(controller.stateFor("vid1").pending)
        assertFalse(controller.stateFor("vid1").accountBacked)
        assertEquals(setOf("vid1"), preferences.localLikedVideoIds())
        assertEquals(emptyList<Boolean>(), transport.writes.toList())
        controller.toggle(track)
        assertFalse(controller.isLiked("vid1"))
        assertEquals(emptySet<String>(), preferences.localLikedVideoIds())
    }
}
