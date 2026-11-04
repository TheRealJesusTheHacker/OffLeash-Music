package dev.cyberdogs.offleash.ui.home

import dev.cyberdogs.offleash.core.model.HomeChip

/**
 * Pure home-chrome logic (Lane H): the greeting and the section
 * classification. Everything here is Android-free so the whole
 * surface is JVM-testable; [HomeScreen] only renders what these
 * functions decide.
 *
 * Account-free shape (0.9.0): the home feed is the anonymous
 * YouTube Music feed, and the chrome is OffLeash's own — a
 * time-of-day greeting with no account name, and the feed's own
 * filter chips. There is no account chrome to select.
 */

/**
 * The time-of-day greeting for [hourOfDay] (0–23, local time).
 * Hours outside 0–23 clamp into the nearest band rather than
 * failing — a clock edge must never break the home feed. Bands:
 * 05–11 morning, 12–16 afternoon, everything else evening (the
 * late-night hours greet as evening, matching how the reference
 * clients phrase it).
 */
fun greetingForHour(hourOfDay: Int): String =
    when (hourOfDay) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

/** The feed section kinds the home rails can carry. */
enum class HomeSectionKind {
    LISTEN_AGAIN,
    FORGOTTEN_FAVORITES,
    OTHER,
}

/**
 * Classifies a parsed section by its title. The home parser
 * yields section titles generically, so classification is an
 * exact title match after trimming and case-folding — "Listen
 * again" and "Forgotten favorites" (the US spelling the API
 * serves; the UK "favourites" is accepted too). Anything else —
 * including a null or blank title — is [HomeSectionKind.OTHER]
 * and renders as the ordinary titled rail it already is.
 */
fun classifyHomeSection(title: String?): HomeSectionKind =
    when (title?.trim()?.lowercase()) {
        "listen again" -> HomeSectionKind.LISTEN_AGAIN
        "forgotten favorites", "forgotten favourites" -> HomeSectionKind.FORGOTTEN_FAVORITES
        else -> HomeSectionKind.OTHER
    }

/**
 * What home chrome renders for the anonymous feed: the greeting
 * always shows, and the response's chips (possibly empty — an
 * empty list is rendered as no row, never a faked one) pass
 * through. There is no account card in the account-free product.
 */
data class HomeChromeState(
    val showGreeting: Boolean,
    val chips: List<HomeChip>,
)

/** Selects the home chrome for the feed that was served. */
fun homeChromeState(chips: List<HomeChip>): HomeChromeState =
    HomeChromeState(
        showGreeting = true,
        chips = chips,
    )
