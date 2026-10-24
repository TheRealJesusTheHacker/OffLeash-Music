package dev.cyberdogs.offleash.ui.home

import dev.cyberdogs.offleash.core.model.HomeChip

/**
 * Pure home-chrome logic (Lane H): the greeting, the personalized
 * section classification, and the signed-in/signed-out chrome
 * selection. Everything here is Android-free so the whole surface is
 * JVM-testable; [HomeScreen] only renders what these functions decide.
 */

/**
 * The time-of-day greeting for [hourOfDay] (0–23, local time), with
 * the account [displayName] appended when one is known:
 * "Good evening, Skyler". Hours outside 0–23 clamp into the nearest
 * band rather than failing — a clock edge must never break the home
 * feed. Bands: 05–11 morning, 12–16 afternoon, everything else
 * evening (the late-night hours greet as evening, matching how the
 * reference clients phrase it).
 */
fun greetingForHour(hourOfDay: Int, displayName: String?): String {
    val greeting = when (hourOfDay) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    val name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: return greeting
    return "$greeting, $name"
}

/** The personalized section kinds the signed-in home can carry. */
enum class HomeSectionKind {
    LISTEN_AGAIN,
    FORGOTTEN_FAVORITES,
    OTHER,
}

/**
 * Classifies a parsed section by its title. The authed home parser
 * yields section titles generically (proven against the derived
 * personalized fixture in :core), so classification is an exact
 * title match after trimming and case-folding — "Listen again" and
 * "Forgotten favorites" (the US spelling the API serves; the UK
 * "favourites" is accepted too). Anything else — including a null
 * or blank title — is [HomeSectionKind.OTHER] and renders as the
 * ordinary titled rail it already is.
 */
fun classifyHomeSection(title: String?): HomeSectionKind =
    when (title?.trim()?.lowercase()) {
        "listen again" -> HomeSectionKind.LISTEN_AGAIN
        "forgotten favorites", "forgotten favourites" -> HomeSectionKind.FORGOTTEN_FAVORITES
        else -> HomeSectionKind.OTHER
    }

/**
 * What home chrome renders for one session state. Signed out, every
 * flag is off and the chip list is empty: the signed-out home stays
 * EXACTLY the Phase 1 feed, even though the anonymous response itself
 * carries chips. Signed in, the greeting and the Liked Music card
 * show, and the response's chips (possibly empty — an empty list is
 * rendered as no row, never a faked one) pass through.
 */
data class HomeChromeState(
    val showGreeting: Boolean,
    val displayName: String?,
    val showLikedMusicCard: Boolean,
    val chips: List<HomeChip>,
)

/** Selects the home chrome for the current session state. */
fun homeChromeState(
    signedIn: Boolean,
    identityName: String?,
    identityEmail: String?,
    chips: List<HomeChip>,
): HomeChromeState {
    if (!signedIn) {
        return HomeChromeState(
            showGreeting = false,
            displayName = null,
            showLikedMusicCard = false,
            chips = emptyList(),
        )
    }
    val displayName = identityName?.trim()?.takeIf { it.isNotEmpty() }
        ?: identityEmail?.trim()?.takeIf { it.isNotEmpty() }
    return HomeChromeState(
        showGreeting = true,
        displayName = displayName,
        showLikedMusicCard = true,
        chips = chips,
    )
}

/** The browse id of the account's Liked Music auto-playlist (the "LM" list). */
const val LIKED_MUSIC_BROWSE_ID: String = "VLLM"
