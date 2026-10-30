package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lane G preference additions over the in-memory fake: the
 * audio-version-in-radio default (false = pre-preference behavior),
 * its round-trip, malformed tolerance, and the local liked-ids record
 * the LikeController persists.
 */
class PlayerPreferencesTest {

    @Test
    fun audioVersionInRadioDefaultsToFalse() {
        val prefs = AppPreferences(InMemoryKeyValueStore())
        assertFalse(prefs.audioVersionInRadio)
        assertFalse(prefs.audioVersionInRadioFlow.value)
    }

    @Test
    fun audioVersionInRadioRoundTripsAndPersists() {
        val store = InMemoryKeyValueStore()
        val prefs = AppPreferences(store)
        prefs.setAudioVersionInRadio(true)
        assertTrue(prefs.audioVersionInRadio)
        assertTrue(prefs.audioVersionInRadioFlow.value)
        assertEquals("true", store.getString(AppPreferences.KEY_AUDIO_VERSION_IN_RADIO))
        assertTrue(AppPreferences(store).audioVersionInRadio)

        prefs.setAudioVersionInRadio(false)
        assertFalse(AppPreferences(store).audioVersionInRadio)
    }

    @Test
    fun audioVersionInRadioMalformedReadsAsFalse() {
        val store = InMemoryKeyValueStore()
        store.putString(AppPreferences.KEY_AUDIO_VERSION_IN_RADIO, "yes-please")
        assertFalse(AppPreferences(store).audioVersionInRadio)
    }

    @Test
    fun localLikedIdsDefaultEmptyAndRoundTrip() {
        val store = InMemoryKeyValueStore()
        val prefs = AppPreferences(store)
        assertEquals(emptySet<String>(), prefs.localLikedVideoIds())

        prefs.setLocalLikedVideoIds(setOf("bbb", "aaa"))
        assertEquals(setOf("aaa", "bbb"), prefs.localLikedVideoIds())
        assertEquals(setOf("aaa", "bbb"), AppPreferences(store).localLikedVideoIds())

        prefs.setLocalLikedVideoIds(emptySet())
        assertEquals(emptySet<String>(), AppPreferences(store).localLikedVideoIds())
    }

    @Test
    fun markerlessLikedStateV2FallsBackToTheLegacyPair() {
        // Round 40: a liked_state_v2 value whose marker line is
        // gone (truncated or foreign) is not a combined value —
        // it must not read as an empty pair shadowing the intact
        // legacy record/provenance.
        val store = InMemoryKeyValueStore()
        store.putString(AppPreferences.KEY_LOCAL_LIKED_IDS, "vidA\nvidB")
        store.putString(AppPreferences.KEY_SEEDED_LIKED_IDS, "vidA")
        store.putString(AppPreferences.KEY_LIKED_STATE, "vidA\nvidB")
        val prefs = AppPreferences(store)
        assertEquals(setOf("vidA", "vidB"), prefs.localLikedVideoIds())
        assertEquals(setOf("vidA"), prefs.seededLikedVideoIds())
    }

    @Test
    fun blankLikedStateV2FallsBackToTheLegacyPair() {
        val store = InMemoryKeyValueStore()
        store.putString(AppPreferences.KEY_LOCAL_LIKED_IDS, "vidA\nvidB")
        store.putString(AppPreferences.KEY_SEEDED_LIKED_IDS, "vidA")
        store.putString(AppPreferences.KEY_LIKED_STATE, "")
        val prefs = AppPreferences(store)
        assertEquals(setOf("vidA", "vidB"), prefs.localLikedVideoIds())
        assertEquals(setOf("vidA"), prefs.seededLikedVideoIds())
    }

    @Test
    fun markerlessLikedStateV2WithNoLegacyReadsEmpty() {
        val store = InMemoryKeyValueStore()
        store.putString(AppPreferences.KEY_LIKED_STATE, "vidA\nvidB")
        val prefs = AppPreferences(store)
        assertEquals(emptySet<String>(), prefs.localLikedVideoIds())
        assertEquals(emptySet<String>(), prefs.seededLikedVideoIds())
    }

    @Test
    fun wellFormedLikedStateV2SupersedesTheLegacyPair() {
        val store = InMemoryKeyValueStore()
        store.putString(AppPreferences.KEY_LOCAL_LIKED_IDS, "vidA\nvidB")
        store.putString(AppPreferences.KEY_SEEDED_LIKED_IDS, "vidA")
        store.putString(
            AppPreferences.KEY_LIKED_STATE,
            "vidC\n@@seeded@@\nvidC",
        )
        val prefs = AppPreferences(store)
        assertEquals(setOf("vidC"), prefs.localLikedVideoIds())
        assertEquals(setOf("vidC"), prefs.seededLikedVideoIds())
    }

    @Test
    fun separatorNamedIdIsDroppedAtWriteAndCannotCorruptThePair() {
        // Round 41: an entry equal to the format's own separator
        // line, persisted as a record id, is found first by the
        // reader's indexOf and read AS the separator — the record
        // before it is discarded wholesale and the real record is
        // demoted to provenance. Seeded ids arrive from a fetched
        // Liked page (server-shaped data), so the writer must
        // refuse the separator as an entry; the rest of the pair
        // round-trips intact.
        val store = InMemoryKeyValueStore()
        val prefs = AppPreferences(store)
        prefs.setLikedState(setOf("@@seeded@@", "vidA"), setOf("vidA"))
        val reread = AppPreferences(store)
        assertEquals(setOf("vidA"), reread.localLikedVideoIds())
        assertEquals(setOf("vidA"), reread.seededLikedVideoIds())
    }

    @Test
    fun idCarryingALineBreakIsDroppedAtWriteNotSplitIntoPhantoms() {
        // Round 41: a line break inside an entry would be written
        // as two lines and read back as two ids neither of which
        // was ever liked. The entry is dropped whole instead.
        val store = InMemoryKeyValueStore()
        val prefs = AppPreferences(store)
        prefs.setLikedState(setOf("vidA\nvidB", "vidC"), setOf("vidC"))
        val reread = AppPreferences(store)
        assertEquals(setOf("vidC"), reread.localLikedVideoIds())
        assertEquals(setOf("vidC"), reread.seededLikedVideoIds())
    }

    @Test
    fun markerNamedEntryStoredInTheProvenanceSegmentIsDroppedAtRead() {
        // Round 42: the round-41 writer filter cannot repair a
        // value already on disk. A stored value whose provenance
        // segment carries the separator as an entry (a pre-filter
        // or foreign write) read the marker back AS a provenance
        // id — a phantom seed the pair never held. The reader now
        // applies the same validity rule at parse, in both
        // segments, so the phantom is dropped and the rest of the
        // pair salvages.
        val store = InMemoryKeyValueStore()
        store.putString(
            AppPreferences.KEY_LIKED_STATE,
            "vidA\n@@seeded@@\n@@seeded@@\nvidA",
        )
        val prefs = AppPreferences(store)
        assertEquals(setOf("vidA"), prefs.localLikedVideoIds())
        assertEquals(setOf("vidA"), prefs.seededLikedVideoIds())

        val markerOnly = InMemoryKeyValueStore()
        markerOnly.putString(
            AppPreferences.KEY_LIKED_STATE,
            "vidA\n@@seeded@@\n@@seeded@@",
        )
        val markerOnlyPrefs = AppPreferences(markerOnly)
        assertEquals(setOf("vidA"), markerOnlyPrefs.localLikedVideoIds())
        assertEquals(emptySet<String>(), markerOnlyPrefs.seededLikedVideoIds())
    }

    @Test
    fun carriageReturnBearingEntryStoredInTheRecordIsDroppedAtRead() {
        // Round 42: a stored line carrying an interior carriage
        // return is one malformed entry (the value splits on '\n'
        // only, and the trim cannot reach the interior). It is
        // dropped whole at read, not returned as an id no writer
        // produced.
        val store = InMemoryKeyValueStore()
        store.putString(
            AppPreferences.KEY_LIKED_STATE,
            "vidA\rvideo\rB\n@@seeded@@\nvidC",
        )
        val prefs = AppPreferences(store)
        assertEquals(emptySet<String>(), prefs.localLikedVideoIds())
        assertEquals(setOf("vidC"), prefs.seededLikedVideoIds())
    }

    @Test
    fun carriageReturnBearingEntryStoredInTheProvenanceIsDroppedAtRead() {
        val store = InMemoryKeyValueStore()
        store.putString(
            AppPreferences.KEY_LIKED_STATE,
            "vidA\n@@seeded@@\nvidC\rvideo",
        )
        val prefs = AppPreferences(store)
        assertEquals(setOf("vidA"), prefs.localLikedVideoIds())
        assertEquals(emptySet<String>(), prefs.seededLikedVideoIds())
    }

    @Test
    fun storedDuplicatesCollapseAndStoredBlanksDropAtRead() {
        // Round 42 probe, clean-salvage half: duplicate lines
        // within a segment and the same id in both segments (the
        // legitimate seeded shape) collapse through the sets, and
        // blank lines drop — the reader's per-entry salvage for
        // shapes validity does not condemn.
        val store = InMemoryKeyValueStore()
        store.putString(
            AppPreferences.KEY_LIKED_STATE,
            "\n  \nvidA\nvidA\n@@seeded@@\n\nvidA\nvidA",
        )
        val prefs = AppPreferences(store)
        assertEquals(setOf("vidA"), prefs.localLikedVideoIds())
        assertEquals(setOf("vidA"), prefs.seededLikedVideoIds())
    }

    @Test
    fun markerNamedEntriesStoredInTheLegacyPairAreDroppedAtRead() {
        // Round 43: round 42 filtered the combined reader at
        // parse, but the LEGACY pair reader — the fallback
        // both getters take when no valid v2 value stands —
        // applied no validity rule at all. The legacy encoding
        // has no separator, so a marker-named entry stored in
        // either legacy key read back as a phantom id (in the
        // record: a liked id for a track that does not exist;
        // in the provenance: a phantom seed). The legacy
        // reader now applies the same storable-id rule.
        val store = InMemoryKeyValueStore()
        store.putString(
            AppPreferences.KEY_LOCAL_LIKED_IDS,
            "@@seeded@@\nvidA",
        )
        store.putString(
            AppPreferences.KEY_SEEDED_LIKED_IDS,
            "@@seeded@@\nvidA",
        )
        val prefs = AppPreferences(store)
        assertEquals(setOf("vidA"), prefs.localLikedVideoIds())
        assertEquals(setOf("vidA"), prefs.seededLikedVideoIds())
    }

    @Test
    fun carriageReturnBearingEntriesStoredInTheLegacyPairAreDroppedAtRead() {
        // Round 43: the legacy value splits on '\n' only, so
        // an interior carriage return survived the trim and
        // the malformed line read back verbatim as an id, in
        // both legacy keys — the round-42 phantom shape on
        // the fallback road.
        val store = InMemoryKeyValueStore()
        store.putString(
            AppPreferences.KEY_LOCAL_LIKED_IDS,
            "vidA\rvideo\rB\nvidC",
        )
        store.putString(
            AppPreferences.KEY_SEEDED_LIKED_IDS,
            "vidC\rvideo",
        )
        val prefs = AppPreferences(store)
        assertEquals(setOf("vidC"), prefs.localLikedVideoIds())
        assertEquals(emptySet<String>(), prefs.seededLikedVideoIds())
    }

    @Test
    fun markerlessV2FallbackReadsTheLegacyPairThroughTheSameFilter() {
        // Round 43: the markerless-v2 fallback (round 40)
        // lands on the legacy reader, so the filter must hold
        // there too — a damaged v2 over a damaged legacy pair
        // must not resurrect the legacy phantoms.
        val store = InMemoryKeyValueStore()
        store.putString(
            AppPreferences.KEY_LOCAL_LIKED_IDS,
            "@@seeded@@\nvidA",
        )
        store.putString(
            AppPreferences.KEY_SEEDED_LIKED_IDS,
            "vidA",
        )
        store.putString(AppPreferences.KEY_LIKED_STATE, "vidA\nvidB")
        val prefs = AppPreferences(store)
        assertEquals(setOf("vidA"), prefs.localLikedVideoIds())
        assertEquals(setOf("vidA"), prefs.seededLikedVideoIds())
    }

    @Test
    fun legacyPairSurvivesMigrationToV2WithEveryValidIdIntact() {
        // Round 43 (migration seam): when the fallback pair is
        // next persisted, setLikedState writes v2 from the
        // in-memory pair the getters returned. Every VALID
        // legacy id survives that migration — including one
        // stored with padding, which the legacy reader's trim
        // canonicalises before the writer ever sees it — so
        // the only entries the migration drops are the ones
        // the validity rule condemns at read already (the
        // marker-named phantom above), never a real id whose
        // legacy encoding merely differed.
        val store = InMemoryKeyValueStore()
        store.putString(
            AppPreferences.KEY_LOCAL_LIKED_IDS,
            " vidA \nvidB\nvidB",
        )
        store.putString(AppPreferences.KEY_SEEDED_LIKED_IDS, "vidA")
        val prefs = AppPreferences(store)
        val record = prefs.localLikedVideoIds()
        val seeded = prefs.seededLikedVideoIds()
        assertEquals(setOf("vidA", "vidB"), record)
        assertEquals(setOf("vidA"), seeded)
        prefs.setLikedState(record, seeded)
        val reread = AppPreferences(store)
        assertEquals(setOf("vidA", "vidB"), reread.localLikedVideoIds())
        assertEquals(setOf("vidA"), reread.seededLikedVideoIds())
    }

    @Test
    fun localLikedIdsDropBlanksAndTolerateJunk() {
        val store = InMemoryKeyValueStore()
        val prefs = AppPreferences(store)
        prefs.setLocalLikedVideoIds(setOf("aaa", "", "  "))
        assertEquals(setOf("aaa"), prefs.localLikedVideoIds())

        store.putString(AppPreferences.KEY_LOCAL_LIKED_IDS, "\n\nccc\n\n")
        assertEquals(setOf("ccc"), AppPreferences(store).localLikedVideoIds())
    }
}
