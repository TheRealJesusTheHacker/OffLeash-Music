package dev.cyberdogs.offleash.playback

/**
 * Pure decision for the battery-restriction card: show it only while the
 * app is battery-restricted and the owner has not dismissed it.
 * No Android dependencies, so the matrix is unit-testable on the JVM.
 */
fun shouldShowBatteryCard(isRestricted: Boolean, dismissed: Boolean): Boolean =
    isRestricted && !dismissed
