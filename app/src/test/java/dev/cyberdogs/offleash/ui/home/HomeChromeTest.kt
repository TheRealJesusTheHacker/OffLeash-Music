package dev.cyberdogs.offleash.ui.home

import dev.cyberdogs.offleash.core.model.HomeChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signed-in home chrome (Lane H): the greeting's hour bands and name
 * handling, the personalized section-title classification, and the
 * signed-in/signed-out chrome selection — signed out must select NO
 * chrome at all, so the Phase 1 home stays exactly as it was.
 */
class HomeChromeTest {

    private val chips = listOf(
        HomeChip("Relax", "FEmusic_home", "params-relax"),
        HomeChip("Focus", "FEmusic_home", "params-focus"),
    )

    // -- Greeting ----------------------------------------------------

    @Test
    fun greetingBandsByHour() {
        assertEquals("Good morning, Ada", greetingForHour(5, "Ada"))
        assertEquals("Good morning, Ada", greetingForHour(11, "Ada"))
        assertEquals("Good afternoon, Ada", greetingForHour(12, "Ada"))
        assertEquals("Good afternoon, Ada", greetingForHour(16, "Ada"))
        assertEquals("Good evening, Ada", greetingForHour(17, "Ada"))
        assertEquals("Good evening, Ada", greetingForHour(23, "Ada"))
        assertEquals("Good evening, Ada", greetingForHour(0, "Ada"))
        assertEquals("Good evening, Ada", greetingForHour(4, "Ada"))
    }

    @Test
    fun greetingWithoutANameIsJustTheGreeting() {
        assertEquals("Good evening", greetingForHour(19, null))
        assertEquals("Good morning", greetingForHour(8, ""))
        assertEquals("Good afternoon", greetingForHour(13, "   "))
    }

    @Test
    fun greetingTrimsTheName() {
        assertEquals("Good evening, Ada", greetingForHour(20, "  Ada  "))
    }

    // -- Section classification --------------------------------------

    @Test
    fun personalizedTitlesClassifyByExactTitle() {
        assertEquals(HomeSectionKind.LISTEN_AGAIN, classifyHomeSection("Listen again"))
        assertEquals(
            HomeSectionKind.FORGOTTEN_FAVORITES,
            classifyHomeSection("Forgotten favorites"),
        )
    }

    @Test
    fun classificationToleratesCaseAndSurroundingWhitespace() {
        assertEquals(HomeSectionKind.LISTEN_AGAIN, classifyHomeSection("  listen again "))
        assertEquals(
            HomeSectionKind.FORGOTTEN_FAVORITES,
            classifyHomeSection("FORGOTTEN FAVORITES"),
        )
        assertEquals(
            HomeSectionKind.FORGOTTEN_FAVORITES,
            classifyHomeSection("Forgotten favourites"),
        )
    }

    @Test
    fun everythingElseIsOther() {
        assertEquals(HomeSectionKind.OTHER, classifyHomeSection("Quick picks"))
        assertEquals(HomeSectionKind.OTHER, classifyHomeSection("Throwbacks"))
        assertEquals(HomeSectionKind.OTHER, classifyHomeSection("Listen again!"))
        assertEquals(HomeSectionKind.OTHER, classifyHomeSection(""))
        assertEquals(HomeSectionKind.OTHER, classifyHomeSection(null))
    }

    // -- Chrome selection ---------------------------------------------

    @Test
    fun signedOutSelectsNoChromeEvenWhenTheResponseCarriesChips() {
        val chrome = homeChromeState(
            signedIn = false,
            identityName = "Ada",
            identityEmail = "ada@example.com",
            chips = chips,
        )
        assertFalse(chrome.showGreeting)
        assertFalse(chrome.showLikedMusicCard)
        assertNull(chrome.displayName)
        assertTrue(chrome.chips.isEmpty())
    }

    @Test
    fun signedInShowsGreetingLikedCardAndTheResponsesChips() {
        val chrome = homeChromeState(
            signedIn = true,
            identityName = "Ada",
            identityEmail = "ada@example.com",
            chips = chips,
        )
        assertTrue(chrome.showGreeting)
        assertTrue(chrome.showLikedMusicCard)
        assertEquals("Ada", chrome.displayName)
        assertEquals(chips, chrome.chips)
    }

    @Test
    fun signedInDisplayNameFallsBackToEmailThenToNull() {
        assertEquals(
            "ada@example.com",
            homeChromeState(true, null, "ada@example.com", emptyList()).displayName,
        )
        assertEquals(
            "ada@example.com",
            homeChromeState(true, "  ", "ada@example.com", emptyList()).displayName,
        )
        assertNull(homeChromeState(true, null, null, emptyList()).displayName)
    }

    @Test
    fun signedInWithNoChipsInTheResponseGetsAnEmptyChipRow() {
        val chrome = homeChromeState(true, "Ada", null, emptyList())
        assertTrue(chrome.showGreeting)
        assertTrue(chrome.chips.isEmpty())
    }

    @Test
    fun likedMusicCardTargetsTheLmPlaylist() {
        assertEquals("VLLM", LIKED_MUSIC_BROWSE_ID)
    }
}
