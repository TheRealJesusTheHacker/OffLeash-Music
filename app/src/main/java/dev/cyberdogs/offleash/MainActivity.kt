package dev.cyberdogs.offleash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import dev.cyberdogs.offleash.playback.KEY_NOTIFICATION_PERMISSION_ASKED
import dev.cyberdogs.offleash.playback.NOTIFICATION_PERMISSION_PREFS_FILE
import dev.cyberdogs.offleash.playback.shouldRequestNotificationPermission
import dev.cyberdogs.offleash.ui.OffleashRoot
import dev.cyberdogs.offleash.ui.theme.OffleashTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        val container = (application as OffleashApp).container
        setContent {
            OffleashTheme {
                OffleashRoot(container)
            }
        }
    }

    /**
     * POST_NOTIFICATIONS is a runtime permission from API 33 on.
     *
     * Asked at most once per install (chaos sweep round 2): onCreate
     * also runs on every rotation / activity recreation, and the old
     * code re-requested on each one, so a denial was followed by the
     * system dialog again on the next turn of the phone and on every
     * later launch. The decision is pure
     * ([shouldRequestNotificationPermission]); the asked-once flag is
     * persisted BEFORE the request fires so a process death inside
     * the dialog cannot cause a second ask either. Denial costs the
     * visible notification only — playback and the foreground
     * service are unaffected — and the grant stays available in the
     * system's app settings for anyone who changes their mind.
     */
    private fun requestNotificationPermissionIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences(
            NOTIFICATION_PERMISSION_PREFS_FILE,
            Context.MODE_PRIVATE,
        )
        val askedBefore = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)
        if (!shouldRequestNotificationPermission(
                sdkInt = Build.VERSION.SDK_INT,
                granted = granted,
                askedBefore = askedBefore,
            )
        ) {
            return
        }
        // commit(), not apply() (bug sweep round 3): apply() only
        // queues the disk write, so a process death in the narrow
        // window before that write lands — the exact death this
        // flag exists to survive — could still lose it and cause
        // a second ask. The write is one boolean, once per install.
        prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true).commit()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
    }

    private companion object {
        const val REQUEST_CODE = 1001
    }
}
