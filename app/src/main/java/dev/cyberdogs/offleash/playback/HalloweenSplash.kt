package dev.cyberdogs.offleash.playback

import java.time.LocalDate

/**
 * The Halloween splash window (2026-10-31, owner's pick).
 *
 * On October 30, October 31, and November 1 — the device's
 * local date, every year — the cold-launch splash is the
 * Moon Howl variant (see
 * [dev.cyberdogs.offleash.ui.HalloweenSplashScreen]) instead
 * of the standard splash. On every other date the standard
 * splash shows, exactly as before.
 *
 * The decision is a pure function of (month, dayOfMonth) so
 * the boundary is pinned by unit tests with injected dates;
 * the launch site reads the local date once, at launch, and
 * no network is involved. [SplashGate] still decides WHETHER
 * a splash shows at all (cold launch only); this gate only
 * decides WHICH splash.
 */
object HalloweenSplash {

    /** True when (month, dayOfMonth) falls inside the Halloween window. */
    fun isActive(month: Int, dayOfMonth: Int): Boolean =
        (month == OCTOBER && (dayOfMonth == 30 || dayOfMonth == 31)) ||
            (month == NOVEMBER && dayOfMonth == 1)

    /** True when [date] falls inside the Halloween window. */
    fun isActiveOn(date: LocalDate): Boolean =
        isActive(date.monthValue, date.dayOfMonth)

    /** The launch-time read: the device's local date, right now. */
    fun isActiveToday(): Boolean = isActiveOn(LocalDate.now())

    private const val OCTOBER: Int = 10
    private const val NOVEMBER: Int = 11
}
