package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Speed + pitch persistence wiring (bug sweep round 1):
 * QueueController is media3-bound and compiles in CI only, so the
 * wiring is pinned by source shape — the controller seeds its speed
 * state from the injected initial state, reports every change
 * through the injected callback, applies a restored state to the
 * player at startup, and the container wires both ends to
 * AppPreferences. The preference itself is unit-tested in
 * AppPreferencesTest.
 */
class SpeedPersistenceContractTest {

    private fun source(relativeFromAppModule: String): String {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, relativeFromAppModule)
            candidates += File(dir, "app/$relativeFromAppModule")
            dir = dir.parentFile
            hops++
        }
        val hit = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "$relativeFromAppModule not found from ${File(".").absolutePath}",
            )
        return hit.readText()
    }

    private fun queueController(): String =
        source("src/main/java/dev/cyberdogs/offleash/playback/QueueController.kt")

    private fun container(): String =
        source("src/main/java/dev/cyberdogs/offleash/OffleashApp.kt")

    @Test
    fun controllerSeedsAndReportsSpeedState() {
        val qc = queueController()
        assertTrue(qc.contains("private val _speedState = MutableStateFlow(initialSpeedState)"))
        // Both owner-facing setters report the change for persistence.
        assertTrue(
            "setPlaybackSpeed must report the new state",
            qc.contains("fun setPlaybackSpeed(speed: Float)") &&
                qc.contains("onSpeedStateChanged(updated)"),
        )
        assertTrue(
            "a restored state must reach the player at startup",
            qc.contains("applyPlaybackParameters(initialSpeedState)"),
        )
    }

    @Test
    fun containerWiresPreferencesBothWays() {
        val app = container()
        assertTrue(app.contains("initialSpeedState = SpeedUiState("))
        assertTrue(app.contains("speed = appPreferences.playbackSpeed"))
        assertTrue(app.contains("preservePitch = appPreferences.keepPitchAtSpeed"))
        assertTrue(app.contains("appPreferences.setPlaybackSpeed(state.speed)"))
        assertTrue(app.contains("appPreferences.setKeepPitchAtSpeed(state.preservePitch)"))
    }
}
