package dev.cyberdogs.offleash.ui.home

import dev.cyberdogs.offleash.core.model.HomeChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home chrome (0.9.0, owner order 2026-11-04). The account era's
 * chrome rules — the greeting shown only while signed in, the
 * name it greeted with, the Liked Music account card — are gone
 * with the sign-in surface: Home is the anonymous feed's home for
 * everyone, the greeting is a plain time-of-day line with no
 * account state behind it, and the chips are exactly what the
 * feed carried.
 */
class HomeChromeTest {

    @Test
    fun greetingNamesThePartOfDay() {
        assertEquals("Good morning", greetingForHour(6))
        assertEquals("Good morning", greetingForHour(11))
        assertEquals("Good afternoon", greetingForHour(12))
        assertEquals("Good afternoon", greetingForHour(17))
        assertEquals("Good evening", greetingForHour(18))
        assertEquals("Good evening", greetingForHour(23))
        assertEquals("Good evening", greetingForHour(2))
    }

    @Test
    fun chromeShowsTheGreetingAndPassesTheChipsThrough() {
        val chips = listOf(
            HomeChip(label = "Relax", browseId = "FEmusic", params = "p1"),
            HomeChip(label = "Energize", browseId = "FEmusic", params = "p2"),
        )
        val state = homeChromeState(chips)
        assertTrue(state.showGreeting)
        assertEquals(chips, state.chips)
    }

    @Test
    fun chromeWithNoChipsStillGreets() {
        val state = homeChromeState(emptyList())
        assertTrue(state.showGreeting)
        assertEquals(emptyList<HomeChip>(), state.chips)
    }
}
