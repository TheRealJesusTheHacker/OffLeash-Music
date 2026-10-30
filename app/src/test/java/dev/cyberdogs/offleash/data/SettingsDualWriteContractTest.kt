package dev.cyberdogs.offleash.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings compiles in CI only, so its wiring is pinned here as a
 * source contract (the codebase's established pattern): every
 * Downloads/Playback control on SettingsScreen must write BOTH
 * preference accessors — ParityPreferences (which the screen
 * displays) and AppPreferences (which the download engine, the
 * auto-download worker, and the queue controller read). A control
 * that wrote only the displayed accessor persisted without taking
 * effect until a restart (see PreferencesCoherenceTest).
 */
class SettingsDualWriteContractTest {

    @Test
    fun settingsWritesBothAccessorsForEverySharedKey() {
        val root = locateMainJavaRoot()
        val settings = File(
            root,
            "dev/cyberdogs/offleash/ui/settings/SettingsScreen.kt",
        ).readText()
        for (setter in listOf(
            "parityPreferences.setDownloadQuality",
            "appPreferences.setDownloadQuality",
            "parityPreferences.setAutoDownloadLiked",
            "appPreferences.setAutoDownloadLiked",
            "parityPreferences.setAudioVersionInRadio",
            "appPreferences.setAudioVersionInRadio",
        )) {
            assertTrue(
                "SettingsScreen must call $setter (a control writing only one " +
                    "accessor does not take effect for the other's readers)",
                settings.contains(setter),
            )
        }
    }

    private fun locateMainJavaRoot(): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, "src/main/java")
            candidates += File(dir, "app/src/main/java")
            dir = dir.parentFile
            hops++
        }
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "src/main/java not found from working dir " + File(".").absolutePath,
            )
    }
}
