package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The Halloween splash window (2026-10-31, owner's pick):
 * the Moon Howl splash replaces the standard splash ONLY on
 * device-local October 30, 31, and November 1, every year.
 * The boundary is pinned day by day — the day before the
 * window and the day after it are as much the spec as the
 * days inside it — and the whole-year sweep proves the
 * window is exactly those three days, no more. The wiring
 * pins bind the launch site and the variant's identity
 * (real brand asset, the spooky display face, the animated
 * greeting) so the gate cannot silently detach from the
 * screen it selects.
 */
class HalloweenSplashTest {

    @Test
    fun dayBeforeTheWindowIsNormal() {
        assertFalse(HalloweenSplash.isActive(10, 29))
    }

    @Test
    fun octoberThirtyIsHalloween() {
        assertTrue(HalloweenSplash.isActive(10, 30))
    }

    @Test
    fun octoberThirtyFirstIsHalloween() {
        assertTrue(HalloweenSplash.isActive(10, 31))
    }

    @Test
    fun novemberFirstIsHalloween() {
        assertTrue(HalloweenSplash.isActive(11, 1))
    }

    @Test
    fun dayAfterTheWindowIsNormal() {
        assertFalse(HalloweenSplash.isActive(11, 2))
    }

    @Test
    fun theRestOfTheYearIsNormal() {
        assertFalse(HalloweenSplash.isActive(10, 1))
        assertFalse(HalloweenSplash.isActive(10, 15))
        assertFalse(HalloweenSplash.isActive(9, 30))
        assertFalse(HalloweenSplash.isActive(11, 30))
        assertFalse(HalloweenSplash.isActive(12, 1))
        assertFalse(HalloweenSplash.isActive(12, 31))
        assertFalse(HalloweenSplash.isActive(1, 1))
        assertFalse(HalloweenSplash.isActive(6, 15))
    }

    @Test
    fun exactlyThreeDaysInAnyYearAreHalloween() {
        for (year in listOf(2024, 2025, 2026, 2027, 2031)) {
            val active = ArrayList<LocalDate>()
            var date = LocalDate.of(year, 1, 1)
            while (date.year == year) {
                if (HalloweenSplash.isActiveOn(date)) {
                    active.add(date)
                }
                date = date.plusDays(1)
            }
            assertEquals(
                "the Halloween window in $year is exactly Oct 30, Oct 31, Nov 1",
                listOf(
                    LocalDate.of(year, 10, 30),
                    LocalDate.of(year, 10, 31),
                    LocalDate.of(year, 11, 1),
                ),
                active,
            )
        }
    }

    @Test
    fun localDateOverloadAgreesWithTheMonthDayDecision() {
        assertTrue(HalloweenSplash.isActiveOn(LocalDate.of(2026, 10, 30)))
        assertTrue(HalloweenSplash.isActiveOn(LocalDate.of(2026, 10, 31)))
        assertTrue(HalloweenSplash.isActiveOn(LocalDate.of(2026, 11, 1)))
        assertFalse(HalloweenSplash.isActiveOn(LocalDate.of(2026, 10, 29)))
        assertFalse(HalloweenSplash.isActiveOn(LocalDate.of(2026, 11, 2)))
        assertTrue(HalloweenSplash.isActiveOn(LocalDate.of(1999, 10, 31)))
        assertFalse(HalloweenSplash.isActiveOn(LocalDate.of(2030, 11, 2)))
    }

    @Test
    fun launchSiteBranchesOnTheGateAndKeepsTheStandardSplash() {
        val root = fileText(
            "dev/cyberdogs/offleash/ui/OffleashRoot.kt",
        )
        assertTrue(
            "the root must read the Halloween gate once at launch",
            root.contains("HalloweenSplash.isActiveToday()"),
        )
        assertTrue(
            "inside the window the root must show the Moon Howl splash",
            root.contains("HalloweenSplashScreen(onSkip"),
        )
        assertTrue(
            "outside the window the standard splash must still show",
            root.contains("SplashScreen(onSkip"),
        )
    }

    @Test
    fun halloweenScreenCarriesTheBrandTheSpookyFaceAndTheGreeting() {
        val screen = fileText(
            "dev/cyberdogs/offleash/ui/HalloweenSplashScreen.kt",
        )
        assertTrue(
            "the scene is built from the app's real brand asset",
            screen.contains("R.drawable.cyberdogs_logo_alpha"),
        )
        assertTrue(
            "the wordmark face is the bundled spooky display font",
            screen.contains("R.font.pirata_one_regular"),
        )
        assertTrue(
            "the OFFLEASH wordmark is on the Halloween splash",
            screen.contains("\"OFFLEASH\""),
        )
        assertTrue(
            "the greeting is the owner's words",
            screen.contains("\"Happy Halloween\""),
        )
        assertTrue(
            "the greeting is animated, letter by letter, not a static caption",
            screen.contains("AnimatedGreeting"),
        )
    }

    @Test
    fun spookyFontAndItsLicenseShipWithTheApp() {
        val repoRoot = repoRoot()
        assertTrue(
            "the Pirata One font file must ship in res/font",
            File(repoRoot, "app/src/main/res/font/pirata_one_regular.ttf").isFile,
        )
        val license = File(
            repoRoot, "app/src/main/res/raw/pirata_one_ofl.txt",
        )
        assertTrue(
            "the font's OFL license must ship in res/raw",
            license.isFile,
        )
        assertTrue(
            "the shipped license is the SIL Open Font License",
            license.readText().contains("SIL Open Font License"),
        )
    }

    // -- helpers ------------------------------------------------------------

    private fun fileText(relativePath: String): String {
        val file = File(locateAppJavaRoot(), relativePath)
        assertTrue("source must exist: $relativePath", file.isFile)
        return file.readText()
    }

    private fun locateAppJavaRoot(): File {
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            for (candidate in listOf(
                File(dir, "src/main/java"),
                File(dir, "app/src/main/java"),
            )) {
                if (File(candidate, "dev/cyberdogs/offleash/ui/OffleashRoot.kt").isFile) {
                    return candidate
                }
            }
            dir = dir.parentFile
            hops++
        }
        throw AssertionError(
            "app/src/main/java not found from " + File(".").absolutePath,
        )
    }

    private fun repoRoot(): File =
        File(locateAppJavaRoot(), "../../../..").canonicalFile
}
