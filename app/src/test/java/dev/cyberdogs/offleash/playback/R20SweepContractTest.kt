package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 20 sweep contracts (confirmation round — fresh-eyes XIII):
 *
 * The home feed's empty state was guarded by
 * `sections.isEmpty() && chrome.chips.isEmpty()`. Chips are
 * navigation chrome, not content, and for a signed-in listener
 * they are the norm — the personalized feed carries a chip cloud,
 * and the view model deliberately keeps the full feed's chips
 * across a chip re-browse that returns none of its own. So a
 * signed-in feed, or a chip browse, that returned ZERO sections
 * rendered the greeting, the chip row and the Liked Music card
 * and then silence: no indication the browse came back empty,
 * and no way to tell an empty result from a broken list. Every
 * sibling browse surface (Charts, Moods, MoodPlaylists, Search,
 * the queue) names its empty result explicitly.
 *
 * Fix: the home empty state keys on the sections alone.
 *
 * HomeScreen is Compose-bound, so the shape is the pin (house
 * style; CI is the compile gate). The pin lives in the playback
 * test source set, like R19SweepContractTest, so the local
 * harness runs it.
 *
 * Round 21 strengthening: the original pin asserted the guard
 * and the text as whole-file presence and banned ONE compound
 * spelling. Mutation runs in round 21 showed it green with
 * (a) the compound guard restored in a line-broken spelling
 * (`sections.isEmpty() &&\n chrome.chips.isEmpty()`) while
 * the sections-only needle survived in a comment, (b) the
 * sections guard kept but the empty text moved under an inner
 * `if (chrome.chips.isEmpty())`, restoring the silent blank
 * both times, and (c) the guard respelled `sections.size == 0`
 * with the needle comment-only (semantically equal, so no
 * product harm in that instance — but proof the presence
 * needles can be satisfied by a comment alone). The pin now
 * counts the guard and the text (exactly once each, so neither
 * can survive as a comment decoy beside a different real
 * guard), pins the exact nested block (guard -> item -> Text),
 * and bans a compound whose first leg is the sections check
 * in any line-breaking. Brittleness direction is safe: a
 * legitimate equivalent respelling of the guard (size == 0,
 * isNotEmpty negation) fails this pin closed rather than
 * passing silently — noted, not a defect.
 */
class R20SweepContractTest {

    @Test
    fun homeEmptyStateKeysOnSectionsAloneNotOnChips() {
        val home = homeScreen()
        assertTrue(
            "the home empty-state text must exist at all",
            home.contains("Nothing to show right now."),
        )
        assertTrue(
            "the home empty state must render whenever the landed " +
                "feed has no sections — chips are navigation chrome " +
                "and must not suppress it",
            home.contains("if (current.data.sections.isEmpty()) {"),
        )
        assertFalse(
            "the home empty state must not be suppressed by the " +
                "presence of chips: a signed-in feed or chip browse " +
                "with zero sections would render a silent blank",
            home.contains("sections.isEmpty() && chrome.chips.isEmpty()"),
        )
        // Round 21: presence needles survive as comment decoys.
        // Count both needles (exactly once each — a decoy copy
        // beside a different real guard makes two), pin the
        // exact nested block so the text cannot move under a
        // second, inner condition, and ban the compound in any
        // line-breaking whose first leg is the sections check.
        assertEquals(
            "the sections-only guard must occur exactly once — " +
                "a second occurrence means the asserted shape " +
                "survives as a comment or dead copy while the " +
                "real guard is something else",
            1,
            occurrences(home, "if (current.data.sections.isEmpty()) {"),
        )
        assertEquals(
            "the empty-state text must occur exactly once — a " +
                "second occurrence means a decoy copy can keep " +
                "the presence needle alive while the rendered " +
                "text sits under a different condition",
            1,
            occurrences(home, "Nothing to show right now."),
        )
        assertTrue(
            "the empty text must sit DIRECTLY inside the " +
                "sections-only guard, as its item's Text — an " +
                "inner condition between the guard and the text " +
                "(the round-21 inner chips guard) suppresses it " +
                "exactly as the compound guard did",
            home.contains(
                "if (current.data.sections.isEmpty()) {\n" +
                    "                        item {\n" +
                    "                            Text(\n" +
                    "                                text = \"Nothing to show right now. Try again in a bit.\",",
            ),
        )
        assertFalse(
            "the sections check must not be the first leg of " +
                "any compound guard, in any line-breaking — the " +
                "round-21 line-broken compound restored the " +
                "silent blank while the banned single-line " +
                "spelling stayed absent",
            home.contains("current.data.sections.isEmpty() &&"),
        )
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    private fun homeScreen(): String =
        mainSource("java/dev/cyberdogs/offleash/ui/home/HomeScreen.kt")

    /** Reads a file under app/src/main, located like the other contract tests. */
    private fun mainSource(relativePath: String): String {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, "src/main/$relativePath")
            candidates += File(dir, "app/src/main/$relativePath")
            dir = dir.parentFile
            hops++
        }
        val found = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "$relativePath not found from " + File(".").absolutePath +
                    " (tried: " + candidates.joinToString { it.path } + ")",
            )
        return found.readText()
    }
}
