package dev.cyberdogs.offleash.playback

/**
 * Pure decision for the POST_NOTIFICATIONS ask (chaos sweep round 2).
 *
 * The ask happens at most ONCE per install: before this decision
 * existed, MainActivity re-requested the permission on every
 * onCreate — which is also every rotation and every other activity
 * recreation — so a person who had already said no got the system
 * dialog again for turning their phone sideways, and again on every
 * later cold launch. Denial is a legitimate answer: playback and the
 * foreground service work without the permission (the media
 * notification is simply not shown in the shade), so nothing in the
 * app may keep asking. A person who changes their mind can grant it
 * from the system's app settings.
 *
 * [askedBefore] is persisted by the caller (SharedPreferences) and
 * recorded BEFORE the system request fires, so even a process death
 * inside the permission dialog cannot cause a second ask.
 */
fun shouldRequestNotificationPermission(
    sdkInt: Int,
    granted: Boolean,
    askedBefore: Boolean,
): Boolean = sdkInt >= 33 && !granted && !askedBefore

/** SharedPreferences file the ask-once flag lives in (the app's prefs file). */
const val NOTIFICATION_PERMISSION_PREFS_FILE: String = "offleash_prefs"

/** The persisted "we already asked once" flag for POST_NOTIFICATIONS. */
const val KEY_NOTIFICATION_PERMISSION_ASKED: String = "notification_permission_asked"
